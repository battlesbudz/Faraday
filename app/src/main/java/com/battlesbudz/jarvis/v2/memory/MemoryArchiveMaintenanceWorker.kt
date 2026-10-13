package com.battlesbudz.jarvis.v2.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opportunistic deletion; every archive read separately enforces the exact expiry boundary. */
class MemoryArchiveMaintenanceWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (AndroidMemoryOs.sources(applicationContext).purgeExpiredSources()) Result.success() else Result.retry()
    }
}
