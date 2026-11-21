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

class PoolPolicyTest {
    @Test fun neighboursStayInRange() {
        assertEquals(listOf(2), com.offlinetutor.domain.PoolPolicy.neighbours(1))
        assertEquals(listOf(1, 3), com.offlinetutor.domain.PoolPolicy.neighbours(2))
        assertEquals(listOf(2), com.offlinetutor.domain.PoolPolicy.neighbours(3))
    }

    @Test fun serveStatsFromServedRows() {
        val R = com.offlinetutor.domain.ServeStats.Companion
        val rows = listOf(
            com.offlinetutor.domain.ServeStats.Row("pool", 120, 21_000, "llm", true),
            com.offlinetutor.domain.ServeStats.Row("pool", 80, 19_000, "llm", true),
            com.offlinetutor.domain.ServeStats.Row("live", 22_000, 22_000, "llm", true),
            com.offlinetutor.domain.ServeStats.Row("instant-template", 15, 0, "template", true),
            com.offlinetutor.domain.ServeStats.Row(null, null, null, "llm", false), // old row, not served via pool code
        )
        val s = R.from(rows, window = 4)
        assertEquals(4, s.served)
        assertEquals(0.5, s.hitRateLast!!, 1e-9)
        assertEquals(120L, s.medianWaitPoolMs)
        assertEquals(22_000L, s.medianWaitLiveMs)
        assertEquals(21_000L, s.medianTaggedGenMs)
        assertNull(com.offlinetutor.domain.PoolPolicy.hitRate(emptyList()))
    }
}

class NightPlanTest {
    @Test fun studentWorkFirstThenEvalBatch() {
        val w = com.offlinetutor.domain.NightPlan.ProfileWork(4,
            weak = (1..6).map { Triple("Mathematics", "c$it", 1) },
            reviews = listOf(Triple("Mathematics", "fractions", 2), Triple("Mathematics", "c1", 1)), // c1 L1 already weak
            pendingFixes = listOf(11L))
        val plan = com.offlinetutor.domain.NightPlan.build(listOf(w), evalMcqs = 50, evalTemplates = 30)
        val pools = plan.filterIsInstance<com.offlinetutor.domain.NightTask.Pool>()
        assertEquals(5, pools.size) // 4 weakest + 1 new review target (the duplicate is dropped)
        assertTrue(plan.indexOfFirst { it is com.offlinetutor.domain.NightTask.EvalMcq } > plan.indexOfFirst { it is com.offlinetutor.domain.NightTask.Fix })
        assertEquals(50, plan.count { it is com.offlinetutor.domain.NightTask.EvalMcq })
        assertEquals(30, plan.count { it is com.offlinetutor.domain.NightTask.EvalTemplate })
        // Round-trips through the stored string form.
        assertEquals(plan, plan.map { com.offlinetutor.domain.NightTask.decode(it.encode()) })
    }

    @Test fun evalSlotsCoverEveryCellAndLevel() {
        val slots = (0 until 50).map { com.offlinetutor.domain.NightPlan.mcqSlot(it, 5, 3) }
        assertEquals(15, slots.map { it.first to it.second }.toSet().size)
        assertEquals(setOf(1, 2, 3), slots.map { it.third }.toSet())
        val counts = slots.groupingBy { it.third }.eachCount()
        assertTrue(counts.values.all { it in 16..17 })
        val tpl = (0 until 30).map { com.offlinetutor.domain.NightPlan.templateSlot(it, 8) }
        assertEquals(8, tpl.map { it.first }.toSet().size)
        assertEquals(setOf(1, 2, 3), tpl.map { it.second }.toSet())
    }
}

class PrefillPrefixTest {
    @Test fun systemPrefixEndsAfterTheSystemMessage() {
        val p = com.offlinetutor.llm.Llama3Prompt.single("You are a tutor.", "Explain fractions.", "{\"q\":")
        val prefix = com.offlinetutor.llm.Llama3Prompt.systemPrefix(p)!!
        assertTrue(p.startsWith(prefix))
        assertTrue(prefix.endsWith("You are a tutor.<|eot_id|>"))
        assertTrue(p.substring(prefix.length).startsWith("<|start_header_id|>user"))
        // Two prompts with the same system message share the prefix (what makes idle prefill reusable).
        val q = com.offlinetutor.llm.Llama3Prompt.single("You are a tutor.", "Explain percentages.")
        assertEquals(prefix, com.offlinetutor.llm.Llama3Prompt.systemPrefix(q))
        assertNull(com.offlinetutor.llm.Llama3Prompt.systemPrefix("plain text"))
    }
}

class ThermalRulesTest {
    @Test fun governorPausesByPriority() {
        val R = com.offlinetutor.llm.ThermalRules
        // cool
        assertFalse(R.paused(Priority.P1, 0, 0.3f)); assertFalse(R.paused(Priority.P2, 1, null))
        // MODERATE (status or headroom forecast): night batch paused, pool refill continues
        assertTrue(R.paused(Priority.P2, 2, null)); assertFalse(R.paused(Priority.P1, 2, null))
        assertTrue(R.paused(Priority.P2, 0, 0.9f))
        // SEVERE: P1 and P2 paused; live requests never
        assertTrue(R.paused(Priority.P1, 3, null)); assertTrue(R.paused(Priority.P1, 0, 1.0f))
        assertFalse(R.paused(Priority.P0, 4, 1.5f))
    }
}
