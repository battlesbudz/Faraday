package com.battlesbudz.jarvis.v2.display

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Pulls the currently owned helper frame into the watch surface's bounded mailbox. */
class OperatedFrameFeed(
    private val display: HelperOperatedDisplay,
    private val mailbox: DisplayFrameMailbox = DisplayFrameMailbox(),
) {
    private var pollingJob: Job? = null
    private var newestFrame: DisplayFrameMailbox.Frame? = null
    private var bitmap: Bitmap? = null
    private var bitmapCapturedAtMs: Long? = null

    @Synchronized fun pollOnce(): Boolean {
        val frame = display.readFrame() ?: return false
        if (!mailbox.offer(frame)) return false
        newestFrame = frame
        return true
    }

    @Synchronized fun currentBitmap(): Bitmap? {
        val frame = newestFrame ?: return null
        if (bitmap != null && bitmapCapturedAtMs == frame.capturedAtMs) return bitmap
        val pixels = IntArray(frame.width * frame.height)
        for (i in pixels.indices) {
            val offset = i * 4
            pixels[i] = Color.argb(frame.rgba[offset + 3].toInt() and 0xff,
                frame.rgba[offset].toInt() and 0xff, frame.rgba[offset + 1].toInt() and 0xff,
                frame.rgba[offset + 2].toInt() and 0xff)
        }
        return Bitmap.createBitmap(pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888).also {
            bitmap = it
            bitmapCapturedAtMs = frame.capturedAtMs
        }
    }

    @Synchronized fun start(scope: CoroutineScope) {
        pollingJob?.cancel()
        pollingJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                pollOnce()
                delay(DisplayFrameMailbox.FRAME_INTERVAL_MS)
            }
        }
    }

    @Synchronized fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }
}
