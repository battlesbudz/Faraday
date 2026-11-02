package com.battlesbudz.jarvis.v2.diagnostics

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * The production destination of "Save diagnostics": a MediaStore Downloads
 * insert carrying the export's display name and MIME type, with the payload
 * as its contents. A fake media provider stands in for the platform one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsDownloadsSaveMediaStoreTest {
    private lateinit var defaultZone: TimeZone
    private lateinit var provider: FakeDownloadsProvider

    // 2026-11-02 01:15:00 in America/New_York (the zone is pinned in setUp).
    private val nowMs = 1_793_600_100_000L
    private val expectedName = "faraday-diagnostics-build1667-20261102-011500.json"

    @Before fun setUp() {
        defaultZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val context = RuntimeEnvironment.getApplication()
        provider = FakeDownloadsProvider()
        provider.attachInfo(context, ProviderInfo().apply {
            authority = "media"
            packageName = context.packageName
        })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @After fun tearDown() { TimeZone.setDefault(defaultZone) }

    @Test fun saveInsertsIntoDownloadsWithTheNameMimeAndContents() {
        val context = RuntimeEnvironment.getApplication()
        val outcome = DiagnosticsDownloadsSave.save(
            DiagnosticsDownloadsSave.downloads(context), "1667", "{\"export\":true}", nowMs)
        assertEquals(DiagnosticsDownloadsSave.Outcome.Saved(expectedName), outcome)
        assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, provider.insertUri)
        assertEquals(1, provider.inserts.size)
        val values = provider.inserts.single()
        assertEquals(expectedName, values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("application/json", values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals(Environment.DIRECTORY_DOWNLOADS, values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("{\"export\":true}", provider.fileFor(provider.insertedRow!!).readText())
        assertTrue(provider.deleted.isEmpty())
    }

    @Test fun aRefusedInsertIsAFailureNotASuccess() {
        provider.failInsert = true
        val context = RuntimeEnvironment.getApplication()
        val outcome = DiagnosticsDownloadsSave.save(
            DiagnosticsDownloadsSave.downloads(context), "1667", "{}", nowMs)
        assertTrue(outcome is DiagnosticsDownloadsSave.Outcome.Failed)
        assertEquals("Save diagnostics failed: Downloads refused the new file",
            DiagnosticsDownloadsSave.message(outcome))
    }

    @Test fun aFailedWriteDeletesThePartialRow() {
        provider.failOpen = true
        val context = RuntimeEnvironment.getApplication()
        val outcome = DiagnosticsDownloadsSave.save(
            DiagnosticsDownloadsSave.downloads(context), "1667", "{}", nowMs)
        assertTrue(outcome is DiagnosticsDownloadsSave.Outcome.Failed)
        assertEquals(listOf(provider.insertedRow), provider.deleted)
    }
}

/** Minimal stand-in for the platform media provider's Downloads surface. */
internal class FakeDownloadsProvider : ContentProvider() {
    var failInsert = false
    var failOpen = false
    var insertUri: Uri? = null
    var insertedRow: Uri? = null
    val inserts = mutableListOf<ContentValues>()
    val deleted = mutableListOf<Uri>()
    private val files = mutableMapOf<Uri, File>()

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        insertUri = uri
        if (failInsert) return null
        inserts += ContentValues(values)
        val row = uri.buildUpon().appendPath((files.size + 1).toString()).build()
        files[row] = File.createTempFile("faraday-downloads", ".json")
        insertedRow = row
        return row
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (failOpen) throw FileNotFoundException("provider refuses the stream")
        val file = files[uri] ?: throw FileNotFoundException("unknown row $uri")
        return ParcelFileDescriptor.open(file,
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        deleted += uri
        return 1
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?): Int = 0

    override fun getType(uri: Uri): String? = "application/json"

    fun fileFor(uri: Uri): File = files.getValue(uri)
}
