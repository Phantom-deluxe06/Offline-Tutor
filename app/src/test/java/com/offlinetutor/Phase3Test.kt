package com.offlinetutor

import com.offlinetutor.llm.Priority
import com.offlinetutor.llm.PriorityGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class PriorityGateTest {
    @Test fun grantsInPriorityOrderFifoWithin() = runBlocking {
        val gate = PriorityGate()
        val order = Collections.synchronizedList(ArrayList<String>())
        val release = CompletableDeferred<Unit>()
        val first = launch { gate.withPermit(Priority.P0) { order.add("hold"); release.await() } }
        delay(50)
        val jobs = listOf("p2" to Priority.P2, "p1a" to Priority.P1, "p0" to Priority.P0, "p1b" to Priority.P1).map { (n, p) ->
            launch { gate.withPermit(p) { order.add(n) } }.also { delay(20) }
        }
        release.complete(Unit)
        withTimeout(2000) { (jobs + first).forEach { it.join() } }
        assertEquals(listOf("hold", "p0", "p1a", "p1b", "p2"), order)
        assertNull(gate.holderPriority)
    }

    @Test fun moreUrgentCallerPreemptsOnceAndGetsTheGateNext() = runBlocking {
        val gate = PriorityGate()
        var preempts = 0
        val stopped = CompletableDeferred<Unit>()
        val background = async {
            gate.withPermit(Priority.P1) { h ->
                h.onPreempt = { preempts++; stopped.complete(Unit) }
                stopped.await() // the "native generation" returns once stop() is called
                h.preempted
            }
        }
        delay(50)
        val live = async { gate.withPermit(Priority.P0) { "live" } }
        val live2 = async { gate.withPermit(Priority.P0) { "live2" } } // second urgent caller: no second stop
        assertTrue(withTimeout(2000) { background.await() })
        assertEquals("live", live.await()); assertEquals("live2", live2.await())
        assertEquals(1, preempts)
    }

    @Test fun equalOrLowerPriorityDoesNotPreempt() = runBlocking {
        val gate = PriorityGate()
        var preempted = false
        val release = CompletableDeferred<Unit>()
        val holder = launch { gate.withPermit(Priority.P1) { h -> h.onPreempt = { preempted = true }; release.await() } }
        delay(50)
        val other = launch { gate.withPermit(Priority.P1) { } }
        val night = launch { gate.withPermit(Priority.P2) { } }
        delay(50)
        assertFalse(preempted)
        release.complete(Unit)
        withTimeout(2000) { holder.join(); other.join(); night.join() }
    }

    @Test fun cancelledWaiterDoesNotBlockTheGate() = runBlocking {
        val gate = PriorityGate()
        val release = CompletableDeferred<Unit>()
        val holder = launch { gate.withPermit(Priority.P0) { release.await() } }
        delay(30)
        val waiter = launch { gate.withPermit(Priority.P1) { } }
        delay(30)
        waiter.cancel()
        release.complete(Unit)
        holder.join()
        withTimeout(1000) { gate.withPermit(Priority.P2) { } } // still usable
        assertEquals(0, gate.waiting)
    }
}
