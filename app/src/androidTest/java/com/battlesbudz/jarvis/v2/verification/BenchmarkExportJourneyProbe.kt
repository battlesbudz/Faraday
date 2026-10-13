package com.battlesbudz.jarvis.v2.verification

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import com.battlesbudz.jarvis.v2.ui.BenchmarkExportFiles
import com.battlesbudz.jarvis.v2.ui.BenchmarkExportFormat
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Controlled picker/receiver outcomes, real production contracts and ContentResolver/FileProvider I/O.
 * This does not exercise a third-party picker or prove that ChatGPT accepts a file. */
internal class BenchmarkExportJourneyProbe(context: Context) : ActivityResultRegistryOwner {
    val creates = CopyOnWriteArrayList<Intent>()
    val shares = CopyOnWriteArrayList<Intent>()
    @Volatile var nextSave: Uri? = null
    @Volatile var holdResult = false
    private val pending = CopyOnWriteArrayList<() -> Unit>()
    fun completePending() {
        check(pending.size == 1)
        Handler(Looper.getMainLooper()).post(pending.removeAt(0))
    }
    val exportContext = object : ContextWrapper(context) {
        override fun startActivity(intent: Intent) {
            check(intent.action == Intent.ACTION_CHOOSER)
            @Suppress("DEPRECATION")
            shares.add(requireNotNull(intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)))
        }
    }
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            creates.add(contract.createIntent(exportContext, input))
            val uri = nextSave
            nextSave = null
            val deliver: () -> Unit = {
                dispatchResult(requestCode, if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, uri?.let { Intent().setData(it) })
            }
            if (holdResult) pending.add(deliver) else Handler(Looper.getMainLooper()).post(deliver)
        }
    }

    fun destination(): Uri {
        val directory = File(exportContext.cacheDir, "benchmark-exports").apply { mkdirs() }
        return FileProvider.getUriForFile(exportContext, "${exportContext.packageName}.benchmark-exports",
            File(directory, BenchmarkExportFiles.filename(BenchmarkExportFormat.TXT)).apply { writeText("old bytes must be replaced") })
    }

    fun awaitObserved(description: String, observed: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        while (!observed() && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(10)
        assertTrue(description, observed())
    }

    fun read(uri: Uri): String = exportContext.contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun verifyCreateDocumentContracts() {
        BenchmarkExportFormat.entries.forEach { format ->
            // Match ActivityResultRegistry's erased ABI across the independent test DEX.
            // R8 may fold CreateDocument's typed overloads into the kept base methods.
            val contract: ActivityResultContract<String, Uri?> = BenchmarkExportFiles.createDocument(format)
            val names = List(3) { BenchmarkExportFiles.filename(format) }
            val intents = names.map { contract.createIntent(exportContext, it) }
            val recreatedContract: ActivityResultContract<String, Uri?> = BenchmarkExportFiles.createDocument(format)
            val recreated = recreatedContract.createIntent(exportContext, names.last())
            (intents + recreated).forEachIndexed { index, intent ->
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
                assertEquals(format.mime, intent.type)
                assertEquals(names[index.coerceAtMost(names.lastIndex)], intent.getStringExtra(Intent.EXTRA_TITLE))
                assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".${format.extension}"))
                assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
            }
            assertNotSame(intents[0], intents[1])
            assertNotSame(intents.last(), recreated)
            val result = Intent().setData(Uri.parse("content://benchmark-test/result.${format.extension}"))
            assertNull(contract.getSynchronousResult(exportContext, names.first()))
            assertNull(contract.parseResult(Activity.RESULT_CANCELED, result))
            assertNull(contract.parseResult(Activity.RESULT_OK, null))
            assertEquals(result.data, contract.parseResult(Activity.RESULT_OK, result))
        }
    }

    fun verifyRepeatedTransports() {
        verifyCreateDocumentContracts()
        val payload = "Whole call 日本語 🙂\n".repeat(500)
        val uri = destination()
        BenchmarkExportFiles.save(exportContext, uri, payload)
        assertEquals(payload, read(uri))
        BenchmarkExportFiles.save(exportContext, uri, "short")
        assertEquals("short", read(uri)) // truncate mode, never retain stale tail bytes
        val previous = mutableListOf<Uri>()
        repeat(6) {
            val intent = BenchmarkExportFiles.share(exportContext, BenchmarkExportFormat.TXT, payload)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("text/plain", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            @Suppress("DEPRECATION") val stream = requireNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertEquals(stream, intent.clipData!!.getItemAt(0).uri)
            assertEquals("content", stream.scheme)
            assertEquals("text/plain", exportContext.contentResolver.getType(stream))
            assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".txt"))
            assertEquals(payload, read(stream))
            previous.add(stream)
        }
        assertEquals(6, previous.distinct().size)
        previous.forEach { assertEquals(payload, read(it)) } // later shares do not evict pending receivers
        listOf(BenchmarkExportFormat.JSON, BenchmarkExportFormat.CSV).forEach { format ->
            val intent = BenchmarkExportFiles.share(exportContext, format, payload)
            assertEquals(format.mime, intent.type)
            assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".${format.extension}"))
        }
    }
}
