package com.battlesbudz.jarvis.v2.memory

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Memory RAG Unit 3's scheduled trigger: a 24 h periodic WorkManager job
 * (KEEP) with the constraint set the plan names — charging, device idle,
 * battery not low, storage not low. WorkManager picks the moment inside the
 * flex window; the design never assumes an hour, and the watermark/journal
 * machinery tolerates the job being deferred for days. All real work lives
 * in [AndroidMemoryDream.runScheduledPass]; this class is the platform edge.
 */
class MemoryDreamWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        AndroidMemoryDream.runScheduledPass(applicationContext)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "memory-dream-synthesis"
        const val PERIOD_HOURS = 24L
        const val FLEX_HOURS = 6L

        fun requiredConstraints(): Constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        fun buildRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<MemoryDreamWorker>(PERIOD_HOURS, TimeUnit.HOURS, FLEX_HOURS, TimeUnit.HOURS)
                .setConstraints(requiredConstraints())
                .build()
    }
}
