package com.battlesbudz.jarvis.v2.verification

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import java.io.File

/** Test APK only: phase evidence survives replacement and deliberate target-process death. */
internal object ReleasePhaseEvidence {
    fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val folder = checkNotNull(InstrumentationRegistry.getArguments().getString("jarvisEvidenceDir"))
        require(folder.matches(Regex("jarvis-verification-[0-9]+")))
        val directory = File(context.cacheDir, "verification-phases").apply { mkdirs() }
        for (extension in listOf("png", "xml")) {
            val file = File(directory, "$name.$extension")
            if (extension == "png") assertTrue("Screenshot capture failed", device.takeScreenshot(file))
            else device.dumpWindowHierarchy(file)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (extension == "png") "image/png" else "application/xml")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$folder")
            }
            val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            checkNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                file.inputStream().use { it.copyTo(output) }
            }
        }
    }
}
