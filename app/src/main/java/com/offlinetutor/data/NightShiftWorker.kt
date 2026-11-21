package com.offlinetutor.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.offlinetutor.TutorApp
import com.offlinetutor.llm.ModelMissingException
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * 3c: WorkManager entry point. Runs only while the phone is charging AND idle. Each run works for at most
 * [BUDGET_MS] (well under WorkManager's 10-minute limit, so no foreground service is needed); if tonight's plan
 * is not finished, a continuation with the same constraints is queued and resumes at the saved task.
 */
class NightShiftWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as TutorApp).container
        if (!c.flags.isOn(Feature.NIGHT_SHIFT)) return Result.success()
        return try {
            val finished = c.night.run(BUDGET_MS)
            if (!finished) NightShiftScheduler.continueTonight(applicationContext)
            Result.success()
        } catch (_: ModelMissingException) {
            Result.success() // nothing to do without a model
        } catch (ce: CancellationException) {
            throw ce // constraints no longer met: WorkManager stops us; the cursor is saved per task
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    companion object {
        const val BUDGET_MS = 8 * 60_000L
    }
}

object NightShiftScheduler {
    private const val PERIODIC = "night-shift"
    private const val CONTINUE = "night-shift-continue"

    private fun constraints() = Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build()

    /** Once a day while charging + idle (kept if already scheduled). */
    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<NightShiftWorker>(24, TimeUnit.HOURS).setConstraints(constraints()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun continueTonight(context: Context) {
        val req = OneTimeWorkRequestBuilder<NightShiftWorker>().setConstraints(constraints()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(CONTINUE, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(CONTINUE)
    }
}
