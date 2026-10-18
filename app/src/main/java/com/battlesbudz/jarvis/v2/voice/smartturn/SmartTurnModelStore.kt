package com.battlesbudz.jarvis.v2.voice.smartturn

import com.battlesbudz.jarvis.v2.ai.storage.ModelDownloader
import com.battlesbudz.jarvis.v2.ai.storage.sha256
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Explicit setup only. Capture calls availableFile; it never triggers a download or file hash. */
internal class SmartTurnModelStore(
    private val directory: File,
    private val download: suspend (File, (Long, Long) -> Unit, (String) -> Unit) -> Unit = { temporary, progress, status ->
        ModelDownloader().download(SmartTurnModelSpec.URL, temporary, progress, status)
    },
) {
    fun availableFile(): File? = File(directory, SmartTurnModelSpec.FILE_NAME).takeIf {
        it.isFile && it.length() == SmartTurnModelSpec.BYTES
    }

    suspend fun ensureReady(report: (String) -> Unit = {}): File = withContext(Dispatchers.IO) {
        installMutex.withLock {
            val target = File(directory, SmartTurnModelSpec.FILE_NAME)
            if (target.isFile && target.length() == SmartTurnModelSpec.BYTES && target.sha256() == SmartTurnModelSpec.SHA256)
                return@withLock target
            check(directory.isDirectory || directory.mkdirs()) { "Could not create Smart Turn storage" }
            val temporary = File(directory, SmartTurnModelSpec.FILE_NAME + ".part")
            if (temporary.length() > SmartTurnModelSpec.BYTES) temporary.delete()
            try {
                report("Downloading Smart Turn turn-ending model: 8.7 MB…")
                download(temporary,
                    { received, total ->
                        check(received <= SmartTurnModelSpec.BYTES && (total <= 0 || total == SmartTurnModelSpec.BYTES)) {
                            "Smart Turn model size mismatch"
                        }
                    }, report)
                coroutineContext.ensureActive()
                check(temporary.length() == SmartTurnModelSpec.BYTES && temporary.sha256() == SmartTurnModelSpec.SHA256) {
                    "Smart Turn model integrity check failed"
                }
                check(temporary.renameTo(target)) { "Could not atomically install Smart Turn model" }
                target
            } catch (error: Exception) {
                // No unverified completed file is admitted; setup can safely restart this small download.
                temporary.delete()
                throw error
            }
        }
    }
    companion object { private val installMutex = Mutex() }
}
