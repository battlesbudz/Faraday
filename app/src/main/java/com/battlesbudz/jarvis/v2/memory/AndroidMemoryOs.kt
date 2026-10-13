package com.battlesbudz.jarvis.v2.memory

import android.content.Context
import android.app.KeyguardManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.io.File

/** Process-wide access to the app's private, device-local memory ledger. */
object AndroidMemoryOs {
    private data class Services(val memory: MemoryOs, val sources: SQLiteMemoryStore)
    @Volatile private var instance: Services? = null

    fun get(context: Context): MemoryOs = services(context).memory
    fun sources(context: Context): MemorySourceArchive = services(context).sources

    private fun services(context: Context): Services = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            val directory = app.noBackupFilesDir
            val sources = SQLiteMemoryStore(File(directory, "memory-os.db"), File(directory, "memory-os.json"), canReadSourceText = {
                // Current lock state, never merely "unlocked since boot". Missing service fails closed.
                app.getSystemService(KeyguardManager::class.java)?.let { !it.isDeviceLocked && !it.isKeyguardLocked } == true
            })
            Services(MemoryOs(sources), sources).also {
                // Android may delay background work; read/write paths also purge and filter expiry.
                WorkManager.getInstance(app).enqueueUniquePeriodicWork("memory-source-expiry", ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<MemoryArchiveMaintenanceWorker>(24, TimeUnit.HOURS).build())
                instance = it
            }
        }
    }
}
