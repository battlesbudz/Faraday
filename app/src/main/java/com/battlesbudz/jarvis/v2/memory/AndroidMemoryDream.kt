package com.battlesbudz.jarvis.v2.memory

import android.content.Context
import java.io.File

/**
 * Process-wide access to the Unit 3 dream journal. The journal is deliberately
 * separate from both the canonical ledger and the vector projection: dry-run
 * nights may write here and nowhere else, and Copy Diagnostics/settings read
 * the same state the worker writes.
 */
object AndroidMemoryDream {
    private class Services(context: Context) {
        val app: Context = context.applicationContext
        val journal: MemoryDreamJournal = MemoryDreamJournal(
            FileDreamJournalStore(File(app.noBackupFilesDir, "memory-dream-journal.json"))
        )
    }

    @Volatile private var instance: Services? = null

    private fun services(context: Context): Services = instance ?: synchronized(this) {
        instance ?: Services(context).also { instance = it }
    }

    fun journal(context: Context): MemoryDreamJournal = services(context).journal

    fun journalState(context: Context): DreamJournalState = journal(context).state()

    /** Live, read-only line for the standard Copy Diagnostics export. */
    fun diagnosticsLine(context: Context): String = journal(context).diagnosticsLine()
}
