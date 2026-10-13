package com.battlesbudz.jarvis.v2.verification

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Test49-only app-window pixels, not a screenshot of the system compositor/other windows.
 * A Compose draw callback and accessibility update can both precede buffer submission.
 * Android recommends registerFrameCommitCallback + PixelCopy for this boundary.
 */
internal object CommittedWispWindowCapture {
    data class Target(val window: Window, val decor: View)

    fun capture(target: Target, timeoutMs: Long = 5_000): Bitmap {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Window capture must not block the UI thread" }
        require(timeoutMs > 0)
        val started = SystemClock.elapsedRealtime()
        val result = CompletableFuture<Bitmap>()
        val main = Handler(Looper.getMainLooper())
        // These handles are installed and removed only on the main thread.
        var observer: ViewTreeObserver? = null
        var committed: Runnable? = null
        val requestCopy = Runnable {
            if (result.isDone) return@Runnable
            var destination: Bitmap? = null
            try {
                val decor = target.decor
                check(decor.isAttachedToWindow && target.window.peekDecorView() === decor) {
                    "Wisp capture window detached or replaced"
                }
                val width = decor.width
                val height = decor.height
                check(width > 0 && height > 0) { "Wisp capture has no drawable window" }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                destination = bitmap
                // Window coordinates exclude Surface padding; PixelCopy's Window overload
                // applies surfaceInsets internally. Preserve one-to-one decor dimensions.
                PixelCopy.request(target.window, Rect(0, 0, width, height), bitmap, { status ->
                    if (status != PixelCopy.SUCCESS || !decor.isAttachedToWindow ||
                        target.window.peekDecorView() !== decor || decor.width != width || decor.height != height) {
                        bitmap.recycle()
                        result.completeExceptionally(AssertionError(
                            "Wisp PixelCopy failed or window changed: status=$status ${width}x$height"))
                    } else if (!result.complete(bitmap)) {
                        // Timeout/cancellation won. Only this callback may now release the
                        // destination: native PixelCopy has finished writing to it.
                        bitmap.recycle()
                    }
                }, main)
                // The asynchronous callback now owns the destination until completion.
                destination = null
            } catch (failure: Throwable) {
                // A synchronous request rejection never transfers ownership to PixelCopy.
                destination?.recycle()
                result.completeExceptionally(failure)
            }
        }
        val register = Runnable {
            if (result.isDone) return@Runnable
            try {
                val decor = target.decor
                check(decor.isAttachedToWindow && decor.isHardwareAccelerated) {
                    "Wisp committed-frame capture requires an attached hardware-rendered window"
                }
                check(target.window.peekDecorView() === decor) { "Wisp capture target changed" }
                val liveObserver = decor.viewTreeObserver
                check(liveObserver.isAlive) { "Wisp capture observer is no longer alive" }
                val callback = Runnable {
                    // Frame commit may be delivered from rendering infrastructure. Marshal
                    // the Window access and PixelCopy callback ownership onto the UI thread.
                    if (!result.isDone && !main.post(requestCopy)) {
                        result.completeExceptionally(AssertionError("Could not queue Wisp PixelCopy"))
                    }
                }
                observer = liveObserver
                committed = callback
                liveObserver.registerFrameCommitCallback(callback)
                // Force a fresh submission even when reduced motion has stopped its clock.
                decor.invalidate()
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }
        check(main.post(register)) { "Could not queue Wisp frame capture" }
        try {
            val remainingMs = timeoutMs - (SystemClock.elapsedRealtime() - started)
            if (remainingMs <= 0) throw TimeoutException("Wisp capture deadline elapsed before wait")
            val bitmap = result.get(remainingMs, TimeUnit.MILLISECONDS)
            android.util.Log.i("JarvisVerification", "Wisp app-window PixelCopy ${bitmap.width}x${bitmap.height} " +
                "after frame commit in ${SystemClock.elapsedRealtime() - started}ms")
            return bitmap
        } catch (failure: TimeoutException) {
            // Completion can race the deadline. If it already transferred a bitmap into
            // the future, release that unclaimed result; otherwise its callback will do so.
            if (!result.cancel(false)) runCatching { result.getNow(null) }.getOrNull()?.recycle()
            throw AssertionError("Wisp frame commit and PixelCopy exceeded ${timeoutMs}ms", failure)
        } catch (failure: InterruptedException) {
            if (!result.cancel(false)) runCatching { result.getNow(null) }.getOrNull()?.recycle()
            Thread.currentThread().interrupt()
            throw AssertionError("Wisp capture interrupted", failure)
        } catch (failure: ExecutionException) {
            throw AssertionError("Wisp committed-window capture failed", failure.cause ?: failure)
        } finally {
            main.removeCallbacks(register)
            main.removeCallbacks(requestCopy)
            main.post {
                val callback = committed
                val liveObserver = observer
                if (callback != null && liveObserver?.isAlive == true) {
                    liveObserver.unregisterFrameCommitCallback(callback)
                }
            }
        }
    }
}
