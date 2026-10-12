package com.battlesbudz.jarvis.v2.verification

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.content.ContentValues
import android.app.ActivityManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import com.battlesbudz.jarvis.v2.MainActivity
import com.battlesbudz.jarvis.v2.JarvisRuntime
import com.battlesbudz.jarvis.v2.actions.*
import com.battlesbudz.jarvis.v2.ai.ToolCall
import com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import com.battlesbudz.jarvis.v2.ai.ModelStore
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext
import com.battlesbudz.jarvis.v2.memory.*
import com.battlesbudz.jarvis.v2.ui.ConversationScreen
import com.battlesbudz.jarvis.v2.ui.VoiceCallOverlay
import com.battlesbudz.jarvis.v2.ui.MemoryScreen
import com.battlesbudz.jarvis.v2.ui.JarvisApp
import com.battlesbudz.jarvis.v2.ui.WispPresence
import com.battlesbudz.jarvis.v2.ui.WispAppFrame
import com.battlesbudz.jarvis.v2.presentation.AgentActivitySnapshot
import com.battlesbudz.jarvis.v2.presentation.AgentActivityKind
import com.battlesbudz.jarvis.v2.ui.PipelineBenchmarkScreen
import com.battlesbudz.jarvis.v2.diagnostics.*
import com.battlesbudz.jarvis.v2.voice.*
import java.net.ServerSocket
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Real release UI/Android actions; controlled ToolCalls verify routing without model weights. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReleaseJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var activity: ActivityScenario<MainActivity>

    @get:Rule val testName = TestName()

    @Before fun launch() {
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        find(By.res("model_browse"))
    }

    @After fun close() {
        try {
            captureEvidence(testName.methodName)
        } finally { if (::activity.isInitialized) activity.close() }
    }

    private fun hideKeyboardWithoutNavigating() {
        // UiObject2.setText need not open the IME. A blind Back can finish the Activity.
        activity.onActivity { host ->
            host.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(host.window.decorView.windowToken, 0)
        }
        device.waitForIdle()
    }

    private fun captureEvidence(name: String) {
        val directory = File(context.cacheDir, "verification").apply { mkdirs() }
        val screenshot = File(directory, "$name.png")
        val hierarchy = File(directory, "$name.xml")
        assertTrue("Screenshot capture failed", device.takeScreenshot(screenshot))
        device.dumpWindowHierarchy(hierarchy)
        exportEvidence(screenshot, "image/png")
        exportEvidence(hierarchy, "application/xml")
    }

    private fun exportEvidence(file: File, mime: String) {
        val folder = InstrumentationRegistry.getArguments().getString("jarvisEvidenceDir")
            ?: error("The verification controller must supply a unique evidence directory")
        require(folder.matches(Regex("jarvis-verification-[0-9]+")))
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            exportCapturedEvidenceThroughShell(file, folder)
            return
        }
        // Android 11 blocks adb shell from app-specific external storage. Publish
        // these test-owned files through Downloads instead; no storage grant/root
        // is needed, and this export exists only in the instrumentation APK.
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$folder")
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        checkNotNull(resolver.openOutputStream(uri)).use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        }
    }


    /** Delayed PACKAGE_DATA_CLEARED work can orphan newly inserted MediaStore rows.
     * Transport the original capture through shell stdin, independent of that ownership. */
    private fun exportCapturedEvidenceThroughShell(file: File, folder: String) {
        require(file.name.matches(Regex("[A-Za-z0-9_]+\\.(png|xml)")))
        val bytes = file.readBytes()
        check(bytes.isNotEmpty()) { "Empty captured evidence: ${file.name}" }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
        val target = "/sdcard/Download/$folder/${file.name}"
        // Runtime.exec tokenizes whitespace. Keep this shell script one token;
        // validated path components contain no whitespace or shell metacharacters.
        val gap = "\${IFS}"
        val script = "(mkdir${gap}-p${gap}/sdcard/Download/$folder&&" +
            "test${gap}!${gap}-e${gap}$target&&cat>$target&&" +
            "n=\$(wc${gap}-c<$target)&&h=\$(sha256sum${gap}$target)&&" +
            "printf${gap}'JARVIS_EXPORT_1:0:%s:%s\\n'${gap}\$n${gap}" +
            "\${h%%[!0123456789abcdef]*})2>&1||" +
            "printf${gap}'JARVIS_EXPORT_1:%s\\n'${gap}\$?"
        check(script.none { it.isWhitespace() })
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        val pipes = instrumentation.uiAutomation.executeShellCommandRw("/system/bin/sh -c $script")
        if (pipes.size != 2) {
            val failure = IllegalStateException("Unexpected shell descriptor count: ${pipes.size}")
            for (pipe in pipes) try { pipe.close() }
            catch (error: java.io.IOException) { failure.addSuppressed(error) }
            throw failure
        }
        pipes[0].use { output -> pipes[1].use { input ->
            for (pipe in pipes) {
                val flags = Os.fcntlInt(pipe.fileDescriptor, OsConstants.F_GETFL, 0)
                Os.fcntlInt(pipe.fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
            }
            val response = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var sent = 0
            var inputOpen = true
            while (true) {
                check(SystemClock.elapsedRealtime() < deadline) { "Evidence export deadline: $target" }
                var progressed = false
                if (inputOpen) {
                    try {
                        val count = Os.write(input.fileDescriptor, bytes, sent, minOf(8192, bytes.size - sent))
                        check(count > 0) { "Evidence export write made no progress" }
                        sent += count
                        progressed = true
                    } catch (error: ErrnoException) {
                        if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
                    }
                    if (sent == bytes.size) { input.close(); inputOpen = false }
                }
                try {
                    val count = Os.read(output.fileDescriptor, buffer, 0, buffer.size)
                    if (count == 0) break
                    check(response.size() + count <= 1024) { "Evidence export response exceeds 1 KiB" }
                    response.write(buffer, 0, count)
                    progressed = true
                } catch (error: ErrnoException) {
                    if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
                }
                if (!progressed) {
                    val watched = mutableListOf(StructPollfd().apply {
                        fd = output.fileDescriptor; events = OsConstants.POLLIN.toShort()
                    })
                    if (inputOpen) watched.add(StructPollfd().apply {
                        fd = input.fileDescriptor; events = OsConstants.POLLOUT.toShort()
                    })
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    check(remaining > 0) { "Evidence export deadline: $target" }
                    try { Os.poll(watched.toTypedArray(), minOf(remaining, 1000L).toInt()) }
                    catch (error: ErrnoException) { if (error.errno != OsConstants.EINTR) throw error }
                }
            }
            check(!inputOpen && sent == bytes.size && SystemClock.elapsedRealtime() < deadline)
            val receipt = response.toString("UTF-8")
            check(receipt == "JARVIS_EXPORT_1:0:${bytes.size}:$hash\n") {
                "Evidence export receipt mismatch for $target: $receipt"
            }
            android.util.Log.i("JarvisReleaseEvidence", "shellExport path=$target bytes=${bytes.size} sha256=$hash")
        } }
    }

    private fun find(selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), 15_000)
            ?: throw AssertionError("Missing UI element: $selector")

    /** Finds controls above or below the current viewport without relying on content height. */
    private fun scrollTo(selector: BySelector): UiObject2 {
        fun seek(fromY: Int, toY: Int): UiObject2? {
            repeat(14) {
                device.findObject(selector)?.let { return it }
                device.swipe(device.displayWidth / 2, fromY, device.displayWidth / 2, toY, 25)
                device.waitForIdle()
            }
            return device.findObject(selector)
        }
        return seek(device.displayHeight * 4 / 5, device.displayHeight / 3)
            ?: seek(device.displayHeight / 3, device.displayHeight * 4 / 5)
            ?: find(selector)
    }

    /** Requires a non-edge, non-empty visible area before a single physical tap. */
    private fun hasSafeTapBounds(control: UiObject2): Boolean {
        val bounds = control.visibleBounds
        val safeInset = 24
        return bounds.width() > 0 && bounds.height() > 0 &&
            bounds.left >= safeInset && bounds.right <= device.displayWidth - safeInset &&
            bounds.top >= safeInset && bounds.bottom <= device.displayHeight - safeInset
    }

    /**
     * Waits for the async reload after a mutation, then verifies a fresh Compose node
     * remains enabled with unchanged tap bounds for a bounded settling interval.
     */
    private fun enabled(selector: BySelector): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            // Scrolling and Compose recomposition can invalidate a prior accessibility node.
            val control = scrollTo(selector)
            try {
                if (control.isEnabled && hasSafeTapBounds(control)) {
                    val before = control.visibleBounds
                    device.waitForIdle()
                    SystemClock.sleep(300)
                    val fresh = device.findObject(selector)
                    if (fresh != null && fresh.isEnabled && hasSafeTapBounds(fresh) && fresh.visibleBounds == before) {
                        return fresh
                    }
                }
            } catch (_: StaleObjectException) {
                // No action has been dispatched; retry with a fresh node within the same bound.
            }
            device.waitForIdle()
        }
        throw AssertionError("Control did not become stably enabled: $selector")
    }

    /** Waits for an async UI state update and verifies a fresh node remains disabled. */
    private fun waitUntilDisabled(selector: BySelector): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                val control = device.findObject(selector)
                if (control != null && !control.isEnabled) {
                    device.waitForIdle()
                    SystemClock.sleep(300)
                    val fresh = device.findObject(selector)
                    if (fresh != null && !fresh.isEnabled) {
                        return fresh
                    }
                }
            } catch (_: StaleObjectException) {
                // Re-query after Compose replaces the accessibility node.
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Control did not become stably disabled: $selector")
    }

    private fun openBrowser() { find(By.res("model_browse")).click(); find(By.res("model_search")) }

    private fun clickEnabled(selector: BySelector) {
        enabled(selector).click()
        device.waitForIdle()
    }

    /** Modal actions must settle without page-seeking gestures that can dismiss the dialog. */
    private fun clickModalAction(selector: BySelector) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var ready: UiObject2? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                val control = device.findObject(selector)
                if (control != null && control.isEnabled && hasSafeTapBounds(control)) {
                    val bounds = control.visibleBounds
                    device.waitForIdle((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1))
                    SystemClock.sleep(minOf(300, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
                    val fresh = device.findObject(selector)
                    if (SystemClock.uptimeMillis() < deadline && fresh != null && fresh.isEnabled &&
                        hasSafeTapBounds(fresh) && fresh.visibleBounds == bounds) {
                        ready = fresh
                        break
                    }
                }
            } catch (_: StaleObjectException) {
                // No tap has been dispatched; await a fresh node in the same modal.
            }
            SystemClock.sleep(minOf(100, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
        }
        val control = ready ?: throw AssertionError("Modal action did not become stably enabled: $selector")
        assertTrue("Modal action must settle within its deadline", SystemClock.uptimeMillis() < deadline)
        // The tap is outside the retry loop: a mutation must never be replayed.
        control.click()
        device.waitForIdle()
    }

    private data class FixedMemoryControl(
        val control: UiObject2,
        val bounds: android.graphics.Rect,
        val started: Long,
        val deadline: Long,
        val diagnostic: String,
    )

    private fun recordMemoryNavigation(message: String) {
        android.util.Log.i("JarvisVerification", message)
        instrumentation.sendStatus(1, android.os.Bundle().apply { putString("jarvisMemoryNavigation", message) })
    }

    /**
     * Memory's header/search/bottom bar and its list tabs do not scroll with content.
     * One 15s deadline bounds preparation/dispatch admission, not synchronous input injection.
     */
    private fun stableFixedMemoryControl(
        selector: BySelector,
        started: Long = SystemClock.uptimeMillis(),
        forTap: Boolean = false,
    ): FixedMemoryControl {
        val deadline = started + 15_000L
        val configuration = Configurator.getInstance()
        var attempts = 0
        var before = "unobserved"
        var fresh = "unobserved"
        var display = "unobserved"
        var state = "starting"
        fun diagnostic() = "fixedMemory selector=$selector elapsedMs=${SystemClock.uptimeMillis() - started} " +
            "attempts=$attempts state=$state before=$before fresh=$fresh display=$display swipes=0"
        fun fail(reason: String): Nothing {
            state = reason
            val message = diagnostic()
            recordMemoryNavigation(message)
            throw AssertionError("Fixed Memory control did not become stably enabled: $message")
        }
        fun remaining(stage: String): Long {
            val left = deadline - SystemClock.uptimeMillis()
            if (left <= 0L) fail("deadline after $stage ($state)")
            return left
        }
        // Preserve the configured ordinary idle wait, capped only by the shared deadline.
        // UiObject2 getters also wait for idle; cap each one, and check after it returns.
        fun <T> observe(stage: String, block: () -> T): T {
            val left = remaining("before $stage")
            val saved = configuration.waitForIdleTimeout
            configuration.setWaitForIdleTimeout(minOf(saved, left))
            return try {
                val result = block()
                remaining(stage)
                result
            } finally { configuration.setWaitForIdleTimeout(saved) }
        }
        fun lookup(): UiObject2? {
            observe("cache refresh") { clearNavigationCache() }
            return observe("lookup") { device.findObject(selector) }
        }
        fun safe(bounds: android.graphics.Rect): Boolean {
            val width = observe("display width") { device.displayWidth }
            val height = observe("display height") { device.displayHeight }
            display = "${width}x$height"
            return bounds.width() > 0 && bounds.height() > 0 &&
                bounds.left >= 24 && bounds.right <= width - 24 &&
                bounds.top >= 24 && bounds.bottom <= height - 24
        }
        while (SystemClock.uptimeMillis() < deadline) {
            attempts++
            before = "missing"
            fresh = "unobserved"
            state = "missing"
            try {
                val control = lookup()
                if (control != null) {
                    val initialEnabled = observe("initial enabled") { control.isEnabled }
                    val bounds = observe("initial bounds") { android.graphics.Rect(control.visibleBounds) }
                    before = bounds.toString()
                    state = "disabled, unsafe or non-default display"
                    if (initialEnabled &&
                        observe("initial display") { control.displayId } == android.view.Display.DEFAULT_DISPLAY && safe(bounds)) {
                        state = "settling"
                        observe("settling idle") { device.waitForIdle() }
                        if (remaining("before stability interval") <= 300L) fail("insufficient full 300ms stability interval")
                        SystemClock.sleep(300)
                        remaining("stability interval")
                        val current = lookup()
                        fresh = "missing"
                        state = "fresh missing, disabled, unsafe or moving"
                        if (current != null) {
                            val currentEnabled = observe("fresh enabled") { current.isEnabled }
                            val currentBounds = observe("fresh bounds") { android.graphics.Rect(current.visibleBounds) }
                            fresh = currentBounds.toString()
                            if (currentEnabled &&
                                observe("fresh display") { current.displayId } == android.view.Display.DEFAULT_DISPLAY &&
                                safe(currentBounds) && currentBounds == bounds) {
                                state = "stable candidate"
                                val message = diagnostic()
                                recordMemoryNavigation(message)
                                if (forTap) recordMemoryNavigation("$message dispatch=before finalFreshCheck=pending")
                                remaining("candidate diagnostics")
                                // Status reporting can block. Reacquire after it and reject a
                                // different accessibility node/window, even at identical bounds.
                                val final = lookup()
                                state = "final missing, replaced, disabled, unsafe or moving"
                                if (final != null && observe("final identity") { final == current }) {
                                    val finalEnabled = observe("final enabled") { final.isEnabled }
                                    val finalBounds = observe("final bounds") { android.graphics.Rect(final.visibleBounds) }
                                    fresh = finalBounds.toString()
                                    if (finalEnabled &&
                                        observe("final display") { final.displayId } == android.view.Display.DEFAULT_DISPLAY &&
                                        safe(finalBounds) && finalBounds == currentBounds) {
                                        state = "ready"
                                        remaining("final observation")
                                        return FixedMemoryControl(final, finalBounds, started, deadline, diagnostic())
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: StaleObjectException) {
                // No action has been dispatched. Start again with fresh discovery only.
                state = "stale"
            }
            val left = remaining("observation")
            SystemClock.sleep(minOf(100L, left))
        }
        fail("deadline ($state)")
    }

    private fun clickFixedMemoryControl(selector: BySelector) {
        val ready = stableFixedMemoryControl(selector, forTap = true)
        // UiObject2.click() does another implicit idle/refresh before injecting. Tap the
        // freshly checked geometry directly, so no hidden idle wait follows this guard.
        assertTrue("Fixed Memory tap expired: ${ready.diagnostic} elapsedMs=${SystemClock.uptimeMillis() - ready.started}",
            SystemClock.uptimeMillis() < ready.deadline)
        val dispatchedAt = SystemClock.uptimeMillis()
        val injected = device.click(ready.bounds.centerX(), ready.bounds.centerY())
        recordMemoryNavigation("${ready.diagnostic} dispatch=after dispatchStartedMs=${dispatchedAt - ready.started} " +
            "dispatchElapsedMs=${SystemClock.uptimeMillis() - ready.started} injected=$injected")
        assertTrue("Fixed Memory physical tap failed: ${ready.diagnostic}", injected)
        // Outside observation/retry: never replay a physical action, even on failure.
        device.waitForIdle()
    }

    private fun enterFixedMemorySearchText(value: String) {
        val started = SystemClock.uptimeMillis()
        fun assign() {
            val ready = stableFixedMemoryControl(By.res("memory_search_input"), started)
            val configuration = Configurator.getInstance()
            val saved = configuration.waitForIdleTimeout
            val left = ready.deadline - SystemClock.uptimeMillis()
            assertTrue("Fixed Memory text preparation expired: ${ready.diagnostic}", left > 0L)
            configuration.setWaitForIdleTimeout(minOf(saved, left))
            try {
                // setText refreshes internally. Cap its idle wait, but synchronous Android
                // action completion cannot be interrupted at our API-admission deadline.
                ready.control.text = value
                assertTrue("Fixed Memory text operation exceeded deadline: ${ready.diagnostic}",
                    SystemClock.uptimeMillis() < ready.deadline)
            } finally { configuration.setWaitForIdleTimeout(saved) }
        }
        try { assign() } catch (_: StaleObjectException) {
            // Preserve enterText's one idempotent replacement retry, within the same deadline.
            assign()
        }
        device.waitForIdle()
        if (device.hasObject(By.pkg(java.util.regex.Pattern.compile(".*inputmethod.*")))) device.pressBack()
        device.waitForIdle()
    }

    /** Await the newly mounted History before page-seeking can scroll its newest row away. */
    private fun awaitMemoryHistoryRow(content: String) {
        val started = SystemClock.uptimeMillis()
        val deadline = started + 15_000L
        val configuration = Configurator.getInstance()
        val savedIdleTimeout = configuration.waitForIdleTimeout
        var observation = "unobserved"
        // This read-only poll owns one deadline. UiObject2 getters must not each
        // insert their ordinary implicit idle wait; restore it on every exit.
        try {
            configuration.setWaitForIdleTimeout(0)
            while (SystemClock.uptimeMillis() < deadline) {
                clearNavigationCache()
                if (SystemClock.uptimeMillis() >= deadline) break
                try {
                    val selected = device.findObject(By.res("memory_history_tab").pkg(context.packageName).selected(true))
                    if (SystemClock.uptimeMillis() >= deadline) break
                    val row = device.findObject(By.text(content).pkg(context.packageName))
                    if (SystemClock.uptimeMillis() >= deadline) break
                    val bounds = row?.visibleBounds
                    observation = "selected=${selected != null} row=$bounds"
                    if (selected != null && bounds != null && bounds.width() > 0 && bounds.height() > 0 &&
                        SystemClock.uptimeMillis() < deadline) {
                        recordMemoryNavigation("historyReady elapsedMs=${SystemClock.uptimeMillis() - started} $observation swipes=0")
                        if (SystemClock.uptimeMillis() < deadline) return
                        break
                    }
                } catch (_: StaleObjectException) {
                    observation = "stale"
                }
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining > 0) SystemClock.sleep(minOf(100L, remaining))
            }
            throw AssertionError("History and its newest row did not become ready within 15,000 ms: $observation")
        } finally { configuration.setWaitForIdleTimeout(savedIdleTimeout) }
    }

    private fun recordModelGeometry(message: String) {
        android.util.Log.i("JarvisVerification", message)
        // Keep diagnostics in instrumentation.txt even if later platform logs
        // rotate. This non-reserved status key carries no test completion fields.
        instrumentation.sendStatus(1, android.os.Bundle().apply { putString("jarvisModelChooseGeometry", message) })
    }

    /** Keep explicit settlement waits, without repeating implicit idle waits for every getter. */
    private inline fun <T> observeNavigation(block: () -> T): T {
        if (android.os.Build.VERSION.SDK_INT < 34) return block()
        val configuration = Configurator.getInstance()
        val savedIdleTimeout = configuration.getWaitForIdleTimeout()
        configuration.setWaitForIdleTimeout(0)
        return try { block() } finally { configuration.setWaitForIdleTimeout(savedIdleTimeout) }
    }

    private fun clearNavigationCache() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            assertTrue("Navigation discovery requires a fresh accessibility cache", instrumentation.uiAutomation.clearCache())
        }
    }

    private fun findNavigationObject(selector: BySelector): UiObject2? {
        clearNavigationCache()
        return device.findObject(selector)
    }

    /** A matching node may be clipped; scroll inside the list until its full 48 dp target is visible. */
    private fun fullyVisibleModelChoose(selector: BySelector, navigationInset: Int): UiObject2 =
        observeNavigation { revealModelChoose(selector, navigationInset) }

    private fun revealModelChoose(selector: BySelector, navigationInset: Int): UiObject2 {
        val minimum = (48 * context.resources.displayMetrics.density).roundToInt()
        val deadline = SystemClock.uptimeMillis() + 15_000
        var swipes = 0
        var stationary = 0
        var lastBounds = "missing"
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                val list = findNavigationObject(By.res("model_list"))
                    ?: throw AssertionError("Model list disappeared while revealing Choose")
                val viewport = list.visibleBounds
                val bottom = minOf(viewport.bottom, device.displayHeight - navigationInset)
                fun isFullyVisible(control: UiObject2, currentViewport: android.graphics.Rect = viewport,
                    currentBottom: Int = bottom): Boolean {
                    clearNavigationCache()
                    val bounds = control.visibleBounds
                    lastBounds = bounds.toString()
                    return control.isEnabled && control.isClickable &&
                        bounds.width() >= minimum && bounds.height() >= minimum &&
                        currentViewport.contains(bounds) && bounds.bottom <= currentBottom
                }
                val control = findNavigationObject(selector)
                if (control != null && isFullyVisible(control)) {
                    val before = control.visibleBounds
                    device.waitForIdle((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1))
                    SystemClock.sleep(minOf(150, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
                    var freshViewport = viewport
                    var freshBottom = bottom
                    if (android.os.Build.VERSION.SDK_INT >= 34) {
                        freshViewport = findNavigationObject(By.res("model_list"))?.visibleBounds
                            ?: throw AssertionError("Model list disappeared while settling Choose")
                        var freshNavigationInset = navigationInset
                        activity.onActivity {
                            freshNavigationInset = it.window.decorView.rootWindowInsets
                                ?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: navigationInset
                        }
                        freshBottom = minOf(freshViewport.bottom, device.displayHeight - freshNavigationInset)
                    }
                    val fresh = findNavigationObject(selector)
                    if (SystemClock.uptimeMillis() < deadline && fresh != null &&
                        isFullyVisible(fresh, freshViewport, freshBottom) && fresh.visibleBounds == before) {
                        recordModelGeometry("model_navigation ready selector=$selector target=${fresh.visibleBounds} viewport=$freshViewport minimumPx=$minimum navigationBottom=$freshBottom gestures=$swipes")
                        if (SystemClock.uptimeMillis() < deadline) return fresh
                    }
                }
                if (SystemClock.uptimeMillis() >= deadline || swipes >= 14 || stationary >= 2) break
                val before = benchmarkViewportSignature(list)
                val scrollDown = control == null || control.visibleBounds.centerY() >= viewport.centerY()
                val targetBefore = control?.visibleBounds?.toString() ?: "missing"
                assertTrue("Model list must provide a usable gesture viewport", viewport.width() > 96 && viewport.height() > 96)
                // The generic unfolded device has a hinge at the exact center X.
                // Keep gestures in the left quarter of the actual list, and both
                // vertical ends away from navigation and button edges.
                val swipeX = viewport.left + viewport.width() / 4
                // UiAutomator synchronously injects every step. A longer stroke
                // with fewer steps keeps navigation within its existing deadline.
                val highY = viewport.top + viewport.height() * 15 / 100
                val lowY = viewport.top + viewport.height() * 85 / 100
                if (SystemClock.uptimeMillis() >= deadline) break
                assertTrue("Model list swipe must dispatch", device.swipe(swipeX,
                    if (scrollDown) lowY else highY, swipeX, if (scrollDown) highY else lowY, 12))
                swipes++
                device.waitForIdle((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1))
                SystemClock.sleep(minOf(150, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
                val freshList = findNavigationObject(By.res("model_list"))
                    ?: throw AssertionError("Model list disappeared after scrolling")
                val after = benchmarkViewportSignature(freshList)
                stationary = if (after == before) stationary + 1 else 0
                val targetAfter = findNavigationObject(selector)?.visibleBounds?.toString() ?: "missing"
                recordModelGeometry("model_navigation gesture=$swipes x=$swipeX direction=${if (scrollDown) "DOWN" else "UP"} viewportBefore=$viewport viewportAfter=${freshList.visibleBounds} targetBefore=$targetBefore targetAfter=$targetAfter moved=${after != before} stationary=$stationary minimumPx=$minimum navigationBottom=$bottom remainingMs=${(deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)}")
            } catch (_: StaleObjectException) {
                // Reacquire the list and target after Compose scrolling/recomposition.
            }
        }
        throw AssertionError("Choose did not expose its full 48 dp target above navigation: $selector bounds=$lastBounds swipes=$swipes")
    }

    /** Category chips live in horizontal LazyRows, so vertical page seeking cannot reveal all of them. */
    private fun clickHorizontalChip(strip: BySelector, target: BySelector) {
        repeat(8) {
            val row = find(strip).visibleBounds
            device.findObject(target)?.let { chip ->
                if (chip.isChecked) return
                // API 30 can expose a chip's unclipped bounds beyond its LazyRow.
                // Reveal the whole chip, then observe selection before continuing.
                if (chip.isEnabled && hasSafeTapBounds(chip) && row.contains(chip.visibleBounds)) {
                    chip.click()
                    device.waitForIdle()
                    if (device.wait(Until.hasObject(By.copy(target).checked(true)), 2_000)) return
                }
            }
            device.swipe(row.right - 12, row.centerY(), row.left + 12, row.centerY(), 180)
            device.waitForIdle()
        }
        throw AssertionError("Category chip did not become selected: $target")
    }

    private fun enterText(selector: BySelector, value: String) {
        try {
            enabled(selector).text = value
        } catch (_: StaleObjectException) {
            // Text assignment is an idempotent replacement, so a fresh-node retry is safe.
            enabled(selector).text = value
        }
        // ACTION_SET_TEXT can leave the IME closed. Unconditional Escape dismisses the
        // AlertDialog (retained build-842 evidence). Dismiss only a visible keyboard.
        device.waitForIdle()
        if (device.hasObject(By.pkg(java.util.regex.Pattern.compile(".*inputmethod.*")))) device.pressBack()
        device.waitForIdle()
    }

    private fun searchMemory(query: String, expected: BySelector) {
        enterFixedMemorySearchText(query)
        // Search is live in the wiki.  The adjacent action clears a search; it must not be
        // tapped here or the assertion would inspect the unfiltered page.
        stableFixedMemoryControl(By.res("memory_search_input"))
        assertNotNull(scrollTo(expected))
    }

    @Test fun test01_setupRequiresAnInstalledModel() {
        assertNotNull(find(By.text("Jarvis setup")))
        assertFalse(scrollTo(By.res("model_check")).isEnabled)
    }

    @Test fun test02_searchHasAnHonestEmptyState() {
        openBrowser()
        find(By.res("model_search")).text = "no-such-jarvis-model-8429"
        assertNotNull(find(By.textStartsWith("No matching models.")))
        find(By.text("Done")).click()
    }

    @Test fun test03_browsingDoesNotChangeTheSelectedModel() {
        assertNotNull(find(By.text("Gemma-4-E2B-it")))
        openBrowser()
        find(By.res("model_search")).text = "Gemma-4-E4B-it"
        find(By.res("model_family_Gemma")).click()
        find(By.text("Done")).click()
        assertNotNull(find(By.text("Gemma-4-E2B-it")))
    }

    @Test fun test04_batteryToolReadsAndroid() {
        val percent = context.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        assertTrue("Battery fixture must expose a valid capacity", percent in 0..100)
        val result = MobileActionPipeline(executor = AndroidMobileActionExecutor(context))
            .execute(ActionRequest("read_battery"))
        assertTrue(result.succeeded)
        assertEquals("Battery is at $percent percent.", result.message)
    }

    @Test fun test05_volumeToolChangesAndroidAndRejectsBadInput() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context))
        try {
            assertTrue(pipeline.execute(ActionRequest("set_volume", mapOf("level" to "40"))).succeeded)
            val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            assertEquals((audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .4).roundToInt(), actual)
            assertFalse(pipeline.execute(ActionRequest("set_volume", mapOf("level" to "101"))).succeeded)
            assertEquals(actual, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0) }
    }

    @Test fun test06_missingAppReturnsFailure() {
        val result = MobileActionPipeline(executor = AndroidMobileActionExecutor(context))
            .execute(ActionRequest("open_app", mapOf("app" to "jarvis-nonexistent-app-8429")))
        assertFalse(result.succeeded)
    }

    @Test fun test07_settingsToolOpensAndroidSettings() {
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { true }))
        val result = pipeline.execute(ActionRequest("open_app", mapOf("app" to "Settings", "package" to "com.android.settings")))
        // A submitted launch is never promoted to a verified opening: the
        // receipt reports the request as sent but unconfirmed.
        assertEquals(ExecutionResult.Outcome.UNKNOWN_COMPLETION, result.outcome)
        assertFalse("a submitted launch is not a verified opening: ${result.message}", result.succeeded)
        assertTrue("the receipt must say the request was sent and opening could not be confirmed: ${result.message}",
            result.message.contains("was sent") && result.message.contains("could not be confirmed"))
        // Destination visibility is asserted independently with UiDevice:
        // only an actually observed foreground package may claim a verified
        // opening.
        assertTrue("Settings must actually appear, not merely report success",
            device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 15_000))
        val foregroundObserved = device.currentPackageName == "com.android.settings"
        assertTrue("Settings must be the foreground package to claim a verified opening", foregroundObserved)
        val verified = verifiedLaunchReceipt(
            "Settings", BackgroundLaunchRoute.DIRECT, platformError = null, foregroundObserved = true
        )
        assertEquals(ExecutionResult.Outcome.SUCCEEDED, verified.outcome)
        assertTrue(verified.succeeded)
        // @After captures the launched Settings screen before closing Jarvis's scenario.
    }

    @Test fun test08_rejectedToolSequencePreservesAndroidVolume() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context))
        val requests = listOf(ActionRequest("unsupported_action"), ActionRequest("open_app")) +
            listOf("", "NaN", "Infinity", "-1", "101", "loud").map {
                ActionRequest("set_volume", mapOf("level" to it))
            }
        for (request in requests) {
            assertFalse("Rejected input must not succeed: $request", pipeline.execute(request).succeeded)
            assertEquals("Rejected input changed volume: $request", before,
                audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        }
    }

    @Test fun test09_repeatedVolumeRequestIsIdempotent() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context))
        val request = ActionRequest("set_volume", mapOf("level" to "40"))
        try {
            repeat(2) {
                assertTrue(pipeline.execute(request).succeeded)
                assertEquals((audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .4).roundToInt(),
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            }
        } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0) }
    }

    @Test fun test10_modelIssueWarningPrecedesSelectionAndCancelPreservesModel() {
        // Exercise the release UI without linking to R8-optimized catalog internals.
        val original = "Gemma-4-E2B-it"
        openBrowser()
        find(By.res("model_search")).text = "Zamba2-2.7B"
        find(By.res("model_family_Zamba")).click()
        assertNotNull(scrollTo(By.textStartsWith("Warning: GPU startup reportedly rebooted")))
        scrollTo(By.res("model_choose_Zamba2-2.7B-instruct")).click()
        assertNotNull(find(By.text("Known issue — read before choosing")))
        find(By.text("Cancel")).click()
        find(By.text("Done")).click()
        assertNotNull(find(By.text(original)))
    }

    @Test fun test11_modelDetailsAreOptionalAndDoNotChangeSelection() {
        val selectedBefore = ModelStore(context).selectedModel().id
        assertEquals("Gemma-4-E2B-it", selectedBefore)
        assertFalse(device.hasObject(By.textContains("bundle /")))
        find(By.res("selected_model_details")).click()
        assertNotNull(find(By.text("About this model")))
        find(By.text("Close details")).click()
        assertNotNull(find(By.text("Gemma-4-E2B-it")))
        openBrowser()
        enterText(By.res("model_search"), "Gemma")
        clickEnabled(By.res("model_family_Gemma"))
        assertNotNull(find(By.res("model_list")))
        assertNotNull(find(By.text("Not yet verified for this Jarvis setup.")))
        assertFalse(device.hasObject(By.textContains("bundle /")))
        scrollTo(By.res("model_details_Gemma3-1B-IT")).click()
        assertNotNull(find(By.text("About this model")))
        captureEvidence("model_details_open")
        find(By.text("Close details")).click()
        assertNotNull(find(By.res("model_choose_Gemma3-1B-IT")))
        captureEvidence("model_details_returned_to_family")
        clickEnabled(By.text("Done"))
        assertTrue("Details browsing must close without choosing a model", device.wait(
            Until.gone(By.res("model_search")), 15_000
        ))
        assertNotNull(find(By.res("selected_model_details")))
        assertNotNull(find(By.text(selectedBefore)))
        assertEquals("Details must not change the durable model selection", selectedBefore, ModelStore(context).selectedModel().id)
    }

    @Test fun test12_lastFamilyModelCanBeSelectedAboveNavigationBar() {
        openBrowser()
        enterText(By.res("model_search"), "Gemma")
        clickEnabled(By.res("model_family_Gemma"))
        assertNotNull(find(By.res("model_list")))
        val target = By.res("model_choose_codegemma-7b-it-int4-litertlm")
        var navigationInset = 0
        activity.onActivity {
            val insets = it.window.decorView.rootWindowInsets
            navigationInset = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0
            } else insets?.systemWindowInsetBottom ?: 0
        }
        val button = fullyVisibleModelChoose(target, navigationInset)
        val bounds = button.visibleBounds
        assertTrue("Choose must be above Android navigation", bounds.bottom <= device.displayHeight - navigationInset)
        assertTrue("Choose must have its full 48 dp touch target", bounds.height() >= (48 * context.resources.displayMetrics.density).roundToInt())
        captureEvidence("last_model_button")
        button.click()
        assertTrue("Choosing the last model must dismiss the browser", device.wait(
            Until.gone(By.res("model_search")), 15_000
        ))
        assertNotNull(find(By.res("selected_model_details")))
        assertNotNull(find(By.text("codegemma-7b-it-int4-litertlm")))
        assertEquals("The last model must be selected in durable preferences", "codegemma-7b-it-int4-litertlm", ModelStore(context).selectedModel().id)
        // Restore the starting model without touching files or bypassing the UI.
        openBrowser()
        enterText(By.res("model_search"), "Gemma-4-E2B-it")
        clickEnabled(By.res("model_family_Gemma"))
        assertNotNull(find(By.res("model_list")))
        scrollTo(By.res("model_choose_Gemma-4-E2B-it")).click()
        assertTrue("Restoring the starting model must dismiss the browser", device.wait(
            Until.gone(By.res("model_search")), 15_000
        ))
        assertNotNull(find(By.res("selected_model_details")))
        assertNotNull(find(By.text("Gemma-4-E2B-it")))
        assertEquals("The starting model must be restored in durable preferences", "Gemma-4-E2B-it", ModelStore(context).selectedModel().id)
    }

    @Test fun test13_imageAttachmentIsResizedStoredAndInvalidInputRejected() {
        val original = File(context.cacheDir, "attachment-source.png")
        val bitmap = android.graphics.Bitmap.createBitmap(2000, 1000, android.graphics.Bitmap.Config.ARGB_8888)
        try { original.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val media = com.battlesbudz.jarvis.v2.chat.ChatMediaStore.prepare(context, android.net.Uri.fromFile(original),
            com.battlesbudz.jarvis.v2.chat.AttachmentKind.IMAGE)
        try {
            val file = File(android.net.Uri.parse(media.uri).path!!)
            assertTrue(file.exists())
            val decoded = android.graphics.BitmapFactory.decodeFile(file.path)
            try { assertEquals(1536, decoded.width); assertEquals(768, decoded.height) }
            finally { decoded.recycle() }
            original.writeText("This is not an image")
            try {
                com.battlesbudz.jarvis.v2.chat.ChatMediaStore.prepare(context, android.net.Uri.fromFile(original),
                    com.battlesbudz.jarvis.v2.chat.AttachmentKind.IMAGE)
                fail("Corrupt input must be rejected")
            } catch (_: android.graphics.ImageDecoder.DecodeException) { }
        } finally { com.battlesbudz.jarvis.v2.chat.ChatMediaStore.discard(context, media); original.delete() }
    }

    @Test fun test14_threeActionTurnUsesRealAndroidState() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            val outcome = ActionTurnRunner(AndroidMobileActionExecutor(context, canLaunchDirectly = { true })).run(
                ActionTurnPlan.parse("Read battery then set media volume to 30 percent then open Settings"), listOf(listOf(
                    com.battlesbudz.jarvis.v2.ai.ToolCall("read_battery", "{}"),
                    com.battlesbudz.jarvis.v2.ai.ToolCall("set_volume", "{\"level\":30}"),
                    com.battlesbudz.jarvis.v2.ai.ToolCall("open_app", "{\"app\":\"Settings\"}"))))
            // The first two steps use real Android state. The trailing launch
            // is submitted but unconfirmed, so the turn stops instead of
            // completing: the volume change is real, the opening is not
            // claimed as verified.
            assertEquals(kotlin.math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .3).toInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertFalse("the turn must not complete on an unconfirmed launch: ${outcome.message}", outcome.completed)
            assertTrue("the turn must stop at the unconfirmed launch: ${outcome.message}", outcome.stopped)
            val launch = outcome.receipts.last()
            assertEquals("open_app", launch.request.name)
            assertEquals(ExecutionResult.Outcome.UNKNOWN_COMPLETION, launch.result.outcome)
            assertTrue("the launch receipt must stay honest: ${launch.result.message}",
                launch.result.message.contains("was sent") && launch.result.message.contains("could not be confirmed"))
            assertTrue("Settings must actually appear, not merely be reported",
                device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 15_000))
        } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0) }
    }

    @Test fun test15_invalidActionTurnPreservesAndroidState() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val outcome = ActionTurnRunner(AndroidMobileActionExecutor(context)).run(
            ActionTurnPlan.parse("Set media volume to 5000 percent then delete files"), emptyList())
        assertFalse(outcome.completed)
        assertEquals(before, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test fun test16_partialFailureKeepsCompletedAndroidAction() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            val outcome = ActionTurnRunner(AndroidMobileActionExecutor(context)).run(
                ActionTurnPlan.parse("Set media volume to 30 percent then open jarvis-nonexistent-app-8429 then read battery"), listOf(listOf(
                    com.battlesbudz.jarvis.v2.ai.ToolCall("set_volume", "{\"level\":30}"),
                    com.battlesbudz.jarvis.v2.ai.ToolCall("open_app", "{\"app\":\"jarvis-nonexistent-app-8429\"}"),
                    com.battlesbudz.jarvis.v2.ai.ToolCall("read_battery", "{}"))))
            assertFalse(outcome.completed)
            assertEquals(2, outcome.receipts.size)
            assertEquals(kotlin.math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .3).toInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0) }
    }

    @Test fun test17_cancelledAndDuplicateActionTurnsDoNotReplay() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val prefs = context.getSharedPreferences("action-turn-cancel", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val history = com.battlesbudz.jarvis.v2.chat.ConversationHistory(prefs)
        val reply = "cancelled-reply"
        history.updateReply(history.current.value.id, reply, "", false)
        val real = AndroidMobileActionExecutor(context)
        var executions = 0
        val job = kotlinx.coroutines.Job()
        val execute = MobileActionExecutor { action ->
            executions++
            val result = real.execute(action)
            history.recordReplyAction(history.current.value.id, reply,
                com.battlesbudz.jarvis.v2.chat.ActionReceipt("set_volume", result.message, result.succeeded))
            if (executions == 1) job.cancel()
            result
        }
        val runner = ActionTurnRunner(execute)
        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(job) {
                    runner.runNative(ActionTurnPlan.parse("Set media volume to 30 percent then read battery"), listOf(
                        com.battlesbudz.jarvis.v2.ai.ToolCall("set_volume", "{\"level\":30}"),
                        com.battlesbudz.jarvis.v2.ai.ToolCall("read_battery", "{}")), dispatch = { request ->
                            val action = MobileActionValidator().validate(request) as ActionValidation.Valid
                            execute.execute(action.action)
                        }, nextCalls = { emptyList() })
                }
            }
        } catch (_: java.util.concurrent.CancellationException) { }
        try {
            assertEquals("Cancellation must stop before the second Android action", 1, executions)
            val restored = com.battlesbudz.jarvis.v2.chat.ConversationHistory(prefs)
            assertTrue(restored.current.value.messages.single().text.contains("Media volume set to 30 percent."))
            assertTrue(restored.context().single().text.contains("Media volume set to 30 percent."))
            assertEquals(kotlin.math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .3).toInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            var duplicateExecutions = 0
            val duplicateRunner = ActionTurnRunner(MobileActionExecutor { action ->
                duplicateExecutions++
                real.execute(action)
            })
            val duplicateOutcome = duplicateRunner.run(
                ActionTurnPlan.parse("Set media volume to 30 percent then read battery"), listOf(
                    listOf(com.battlesbudz.jarvis.v2.ai.ToolCall("set_volume", "{\"level\":30}")),
                    listOf(com.battlesbudz.jarvis.v2.ai.ToolCall("set_volume", "{\"level\":30}"),
                        com.battlesbudz.jarvis.v2.ai.ToolCall("read_battery", "{}"))))
            assertTrue(duplicateOutcome.completed)
            assertEquals("Duplicate model call must not rerun Android volume", 2, duplicateExecutions)
            assertEquals(2, duplicateOutcome.receipts.size)
            assertEquals(kotlin.math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .3).toInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0) }
    }


    @Test fun test18_naturalActionRoutingSubmitsSettingsAndStopsBeforeBattery() {
        val plan = com.battlesbudz.jarvis.v2.ai.TurnOrchestrator(com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient())
            .plan("Can you open up Settings and tell me what my battery percentage is?")
        assertEquals(com.battlesbudz.jarvis.v2.ai.TurnKind.NORMAL_CHAT, plan.kind)
        assertNull(plan.lookupQuery)
        val outcome = ActionTurnRunner(AndroidMobileActionExecutor(context, canLaunchDirectly = { true })).run(plan.actionPlan, listOf(listOf(
            com.battlesbudz.jarvis.v2.ai.ToolCall("open_app", "{\"app\":\"Settings\"}"),
            com.battlesbudz.jarvis.v2.ai.ToolCall("read_battery", "{}"))))
        // The launch is submitted but unconfirmed, so the turn stops here:
        // the battery read must not inherit the unestablished opening.
        assertFalse("the turn must not complete on an unconfirmed launch: ${outcome.message}", outcome.completed)
        assertEquals(listOf("open_app"), outcome.receipts.map { it.request.name })
        assertEquals(ExecutionResult.Outcome.UNKNOWN_COMPLETION, outcome.receipts.single().result.outcome)
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 15_000))
    }

    @Test fun test19_retryLiteralUnknownAppStopsWithoutBattery() {
        val plan = com.battlesbudz.jarvis.v2.ai.TurnOrchestrator(com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient())
            .plan("I said, can you open up the fistbook and tell me what my battery percentage is?")
        assertEquals(com.battlesbudz.jarvis.v2.ai.TurnKind.NORMAL_CHAT, plan.kind)
        assertNull(plan.lookupQuery)
        val ready = plan.actionPlan as ActionTurnPlan.Ready
        assertEquals("fistbook", ready.steps.first().request.arguments["app"])
        var calls = 0
        val substituted = ActionTurnRunner(MobileActionExecutor { calls++; ExecutionResult(true, "bad") }).run(ready, listOf(listOf(
            com.battlesbudz.jarvis.v2.ai.ToolCall("open_app", "{\"app\":\"Facebook\"}"))))
        assertFalse(substituted.completed); assertEquals(0, calls)
        var androidAttempts = 0
        val real = AndroidMobileActionExecutor(context)
        val outcome = ActionTurnRunner(MobileActionExecutor { action -> androidAttempts++; real.execute(action) }).run(ready, listOf(listOf(
            com.battlesbudz.jarvis.v2.ai.ToolCall("open_app", "{\"app\":\"fistbook\"}"),
            com.battlesbudz.jarvis.v2.ai.ToolCall("read_battery", "{}"))))
        assertFalse(outcome.completed); assertEquals(1, androidAttempts); assertEquals(1, outcome.receipts.size)
        assertFalse(outcome.receipts.single().result.succeeded)
        assertEquals("open_app", outcome.receipts.single().request.name)
    }


    @Test fun test20_followupQueuesWhileAcceptedAndroidActionRuns() = runBlocking {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        val firstNativeGeneration = CompletableDeferred<Unit>()
        val releaseFirstNativeGeneration = CompletableDeferred<Unit>()
        val executionOrder = mutableListOf<String>()
        try {
            assertNotNull(queue.admit("accepted-A", "utterance-A", "A"))
            queue.start { task ->
                if (task.value == "A") {
                    firstNativeGeneration.complete(Unit)
                    releaseFirstNativeGeneration.await()
                }
                val plan = if (task.value == "A") {
                    ActionTurnPlan.parse("Read battery then set media volume to 30 percent")
                } else ActionTurnPlan.parse("Open Settings")
                val calls = if (task.value == "A") listOf(
                    ToolCall("read_battery", "{}"), ToolCall("set_volume", "{\"level\":30}")
                ) else listOf(ToolCall("open_app", "{\"app\":\"Settings\"}"))
                val outcome = ActionTurnRunner(MobileActionExecutor { error("controlled calls dispatch below") }).runNative(
                    plan, calls,
                    dispatch = { request ->
                        executionOrder += "${task.value}:${request.name}"
                        MobileActionPipeline(executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { true })).execute(request)
                    },
                    nextCalls = { emptyList() }
                )
                assertTrue(session.onTaskEvent(task.id, "${task.value}: ${outcome.message}"))
                outcome.completed
            }
            withTimeout(15_000) { firstNativeGeneration.await() }
            assertTrue("The listener/session must remain available before native dispatch", session.canAdmitAcceptedAction())
            assertNotNull("Follow-up B must be accepted while A is blocked", queue.admit("accepted-B", "utterance-B", "B"))
            assertEquals(1, queue.pendingCount())
            releaseFirstNativeGeneration.complete(Unit)
            withTimeout(20_000) { queue.awaitIdle() }

            assertEquals(listOf("A:read_battery", "A:set_volume", "B:open_app"), executionOrder)
            assertEquals(kotlin.math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .3).toInt(),
                audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 15_000))
            val delivery = checkNotNull(session.nextDelivery())
            val combinedReport = delivery.reports.joinToString(" ") { it.text }
            assertTrue(combinedReport.contains("Battery is at"))
            assertTrue(combinedReport.contains("Media volume set to 30 percent."))
            assertTrue(combinedReport.contains("The launch request for Settings was sent, but opening could not be confirmed."))
            assertTrue(session.markDelivered(delivery.attemptId, delivery.reports.map { it.taskId }.toSet()))
            assertEquals(0, session.pendingReportCount())
        } finally {
            queue.close()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
        }
    }

    @Test fun test21_speechInterruptionPreservesAcceptedAndroidActions() = runBlocking {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        val originalBlocked = CompletableDeferred<Unit>()
        val releaseOriginal = CompletableDeferred<Unit>()
        val dispatches = mutableListOf<String>()
        try {
            assertNotNull(queue.admit("original", "original-utterance", "original"))
            queue.start { task ->
                if (task.value == "original") {
                    originalBlocked.complete(Unit)
                    releaseOriginal.await()
                }
                val plan = if (task.value == "original") {
                    ActionTurnPlan.parse("Set media volume to 30 percent then read battery")
                } else ActionTurnPlan.parse("Open Settings")
                val calls = if (task.value == "original") listOf(
                    ToolCall("set_volume", "{\"level\":30}"), ToolCall("read_battery", "{}")
                ) else listOf(ToolCall("open_app", "{\"app\":\"Settings\"}"))
                val outcome = ActionTurnRunner(MobileActionExecutor { error("controlled calls dispatch below") }).runNative(
                    plan, calls,
                    dispatch = { request ->
                        dispatches += "${task.value}:${request.name}"
                        MobileActionPipeline(executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { true })).execute(request)
                    }, nextCalls = { emptyList() }
                )
                assertTrue(session.onTaskEvent(task.id, "${task.value}: ${outcome.message}"))
                outcome.completed
            }
            withTimeout(15_000) { originalBlocked.await() }
            assertNotNull(queue.admit("followup", "followup-utterance", "followup"))
            session.onCaptureStarted()
            val control = session.control("stop speaking")
            assertEquals(VoiceActionControl.SpeechOnly, control)
            assertTrue(session.onCaptured(SessionCapture("speech-interrupt", "stop speaking"),
                CapturedKind.Control(control)) is CaptureOutcome.Control)
            assertTrue("Speech interruption must leave accepted actions alive", queue.hasUnfinished())
            releaseOriginal.complete(Unit)
            withTimeout(20_000) { queue.awaitIdle() }

            assertEquals(listOf("original:set_volume", "original:read_battery", "followup:open_app"), dispatches)
            assertEquals(1, dispatches.count { it == "original:set_volume" })
            assertEquals(1, dispatches.count { it == "followup:open_app" })
            val firstDelivery = checkNotNull(session.nextDelivery())
            assertEquals(2, firstDelivery.reports.size)
            session.onCaptureStarted()
            assertTrue("A speech floor handoff must interrupt only delivery", session.interruptDelivery(firstDelivery.attemptId).not())
            assertEquals(2, session.pendingReportCount())
            session.onCaptureStopped()
            val acknowledgedDelivery = checkNotNull(session.nextDelivery())
            assertEquals(firstDelivery.reports.map { it.taskId }.toSet(), acknowledgedDelivery.reports.map { it.taskId }.toSet())
            assertTrue(session.markDelivered(acknowledgedDelivery.attemptId,
                acknowledgedDelivery.reports.map { it.taskId }.toSet()))
            assertEquals(0, session.pendingReportCount())
        } finally {
            queue.close()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
        }
    }

    @Test fun test22_explicitCancellationKeepsCompletedAndroidReceipts() = runBlocking {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val preferences = context.getSharedPreferences("continuous-action-cancel", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val store = SharedPreferencesVoiceCallStore(preferences)
        val calls = VoiceSessionController(store)
        val call = calls.beginCall()
        val replyId = "cancelled-accepted-reply"
        calls.beginReply(call.id, replyId)
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        val volumeReceipt = CompletableDeferred<Unit>()
        val allowBattery = CompletableDeferred<Unit>()
        var batteryDispatches = 0
        var settingsDispatches = 0
        try {
            assertNotNull(queue.admit("cancel-A", "cancel-utterance-A", "A"))
            queue.start { task ->
                if (task.value == "B-settings") {
                    val outcome = ActionTurnRunner(MobileActionExecutor { error("controlled calls dispatch below") }).runNative(
                        ActionTurnPlan.parse("Open Settings"), listOf(ToolCall("open_app", "{\"app\":\"Settings\"}")),
                        dispatch = { request ->
                            settingsDispatches++
                            MobileActionPipeline(executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { true })).execute(request)
                        }, nextCalls = { emptyList() }
                    )
                    outcome.completed
                } else try {
                    val outcome = ActionTurnRunner(MobileActionExecutor { error("controlled calls dispatch below") }).runNative(
                        ActionTurnPlan.parse("Set media volume to 30 percent then read battery"),
                        listOf(ToolCall("set_volume", "{\"level\":30}")),
                        dispatch = { request ->
                            val result = MobileActionPipeline(executor = AndroidMobileActionExecutor(context)).execute(request)
                            calls.recordReplyAction(call.id, replyId, VoiceActionOutcome(request.name, result.message, result.succeeded))
                            volumeReceipt.complete(Unit)
                            result
                        },
                        nextCalls = {
                            allowBattery.await()
                            batteryDispatches++
                            listOf(ToolCall("read_battery", "{}"))
                        }
                    )
                    outcome.completed
                } finally {
                    session.onTaskEvent(task.id, "A cancelled after its durable volume receipt")
                }
            }
            withTimeout(15_000) { volumeReceipt.await() }
            assertNotNull(queue.admit("cancel-B", "cancel-utterance-B", "B-settings"))
            queue.cancel(VoiceActionControl.CancelAll)
            withTimeout(15_000) { queue.awaitIdle() }

            assertEquals("Cancellation must prevent the blocked battery dispatch", 0, batteryDispatches)
            assertEquals("Cancellation must prevent queued Settings", 0, settingsDispatches)
            assertEquals(AcceptedActionState.CANCELLED, queue.tasks.value.first { it.id == "cancel-A" }.state)
            assertEquals(AcceptedActionState.CANCELLED, queue.tasks.value.first { it.id == "cancel-B" }.state)
            val savedReceipt = store.list().single { it.id == call.id }.transcript.single { it.replyId == replyId }.actions.single()
            assertEquals("set_volume", savedReceipt.name)
            assertTrue(savedReceipt.succeeded)
            assertEquals(kotlin.math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .3).toInt(),
                audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally {
            queue.close()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
        }
    }

    @Test fun test23_actionResultsSurviveEndCallWithoutReplay() = runBlocking {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val preferences = context.getSharedPreferences("continuous-action-end-call", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val store = SharedPreferencesVoiceCallStore(preferences)
        val controller = VoiceSessionController(store)
        val originalCall = controller.beginCall()
        val replyId = "ended-call-accepted-reply"
        controller.beginReply(originalCall.id, replyId)
        val queue = AcceptedActionQueue<String>()
        val session = ContinuousActionSession(queue)
        val nativeBlocked = CompletableDeferred<Unit>()
        val releaseNative = CompletableDeferred<Unit>()
        var executions = 0
        try {
            assertNotNull(queue.admit("ended-A", "ended-utterance-A", "A"))
            queue.start { task ->
                nativeBlocked.complete(Unit)
                releaseNative.await()
                val outcome = ActionTurnRunner(MobileActionExecutor { error("controlled calls dispatch below") }).runNative(
                    ActionTurnPlan.parse("Read battery then set media volume to 30 percent"),
                    listOf(ToolCall("read_battery", "{}"), ToolCall("set_volume", "{\"level\":30}")),
                    dispatch = { request ->
                        executions++
                        val result = MobileActionPipeline(executor = AndroidMobileActionExecutor(context)).execute(request)
                        controller.recordReplyAction(originalCall.id, replyId,
                            VoiceActionOutcome(request.name, result.message, result.succeeded))
                        result
                    }, nextCalls = { emptyList() }
                )
                controller.updateTaskForCall(originalCall.id, VoiceTaskStatus(
                    if (outcome.completed) VoiceTaskState.COMPLETED else VoiceTaskState.FAILED,
                    completedSteps = outcome.receipts.map { it.request.name }
                ))
                assertTrue(session.onTaskEvent(task.id, outcome.message))
                outcome.completed
            }
            withTimeout(15_000) { nativeBlocked.await() }
            controller.end()
            releaseNative.complete(Unit)
            withTimeout(20_000) { queue.awaitIdle() }

            assertEquals(2, executions)
            val reloadedStore = SharedPreferencesVoiceCallStore(preferences)
            val saved = reloadedStore.list().single { it.id == originalCall.id }
            assertNotNull(saved.endedAtMs)
            assertEquals(listOf("read_battery", "set_volume"),
                saved.transcript.single { it.replyId == replyId }.actions.map { it.name })
            assertEquals(VoiceTaskState.COMPLETED, saved.taskStatus?.state)
            assertEquals(kotlin.math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .3).toInt(),
                audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            val reloadedController = VoiceSessionController(reloadedStore)
            val newCall = reloadedController.beginCall()
            assertTrue(reloadedController.currentTranscript().isEmpty())
            assertEquals("Reloading storage must not replay completed Android work", 2, executions)
            reloadedController.end()
            assertNotEquals(originalCall.id, newCall.id)
        } finally {
            queue.close()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
        }
    }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test24_memoryManagerReviewsCorrectsSearchesAndErases() {
        /*
         * This mounts the shipping MemoryScreen on a real, file-backed MemoryOs.  The two
         * ConversationMemory inputs are deliberately finalized fixtures: this proves the
         * production capture boundary and review UI without claiming microphone/ASR/model work.
         */
        val file = File(context.cacheDir, "release-memory-wiki-journey.db").apply { delete() }
        val store = SQLiteMemoryStore(file)
        val memoryOs = MemoryOs(store)
        val bridge = ConversationMemory(memoryOs)
        val sapphire = "I prefer sapphire notebooks for verification"
        val cobalt = "My favorite color is cobalt"
        val atlas = "Project Atlas uses [[Cobalt]]."
        val cobaltNotes = "Cobalt reference notes for verification."
        val indigo = "I prefer indigo notebooks for verification"
        val persistent = "I prefer persistent amber tea for verification"

        fun mountMemoryWiki() {
            activity.onActivity { host -> host.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    MemoryScreen(memoryOs = memoryOs, onBack = {})
                } }
            } }
            assertNotNull(find(By.text("Memory")))
        }
        fun addManual(content: String, category: String, topic: String) {
            clickFixedMemoryControl(By.res("memory_new"))
            assertNotNull(find(By.text("Add a memory")))
            enterText(By.res("memory_new_content"), content)
            clickHorizontalChip(By.res("memory_category_picker"), By.res("memory_category_$category"))
            enterText(By.res("memory_topic_input"), topic)
            clickEnabled(By.res("memory_propose"))
            assertTrue("Add dialog did not close after its saved proposal", device.wait(
                Until.gone(By.res("memory_new_content")), 15_000
            ))
        }
        fun approveOnlyPending() {
            clickFixedMemoryControl(By.res("memory_review_tab"))
            assertNotNull(find(By.text("Review (1)")))
            clickEnabled(By.res("memory_approve"))
            assertNotNull(find(By.text("Review (0)")))
        }

        try {
            // Keep the app-level entry and return route in the release journey before mounting
            // the controlled, file-backed capture fixture below.
            clickEnabled(By.res("memory_open"))
            assertNotNull(find(By.text("Memory")))
            clickEnabled(By.res("memory_back"))
            assertNotNull(find(By.res("model_browse")))
            mountMemoryWiki()

            // Add remains a modal. Restricted content must stay visibly rejected in that modal.
            clickFixedMemoryControl(By.res("memory_new"))
            assertNotNull(find(By.text("Add a memory")))
            enterText(By.res("memory_new_content"), "Bank account number 1234 5678 9012 3456")
            clickEnabled(By.res("memory_propose"))
            assertNotNull(find(By.res("memory_error")))
            clickEnabled(By.text("Cancel"))

            val now = System.currentTimeMillis()
            assertEquals(ConversationMemoryOutcome.PROPOSED, bridge.capture(FinalMemoryInput(
                "release-wiki-text", "release-wiki-thread", null, ConversationMemorySource.TEXT,
                "Remember $sapphire.", now
            )).outcome)
            assertEquals(ConversationMemoryOutcome.PROPOSED, bridge.capture(FinalMemoryInput(
                "release-wiki-voice", "release-wiki-thread", "release-wiki-call", ConversationMemorySource.VOICE,
                "$cobalt.", now + 1
            )).outcome)

            // Pending capture is reviewable but never part of Wiki search until a real Approve tap.
            assertNotNull(find(By.text("Review (2)")))
            searchMemory("sapphire notebooks", By.text("No approved memories match that search."))
            enterFixedMemorySearchText("")
            clickFixedMemoryControl(By.res("memory_review_tab"))
            assertNotNull(scrollTo(By.text(sapphire)))
            captureEvidence("memory_wiki_review_pending")
            clickEnabled(By.res("memory_approve"))
            assertNotNull(find(By.text("Review (1)")))
            clickEnabled(By.res("memory_approve"))
            assertNotNull(find(By.text("Review (0)")))

            // Exact wiki search opens the derived topic page; Sources exposes the capture provenance.
            clickEnabled(By.res("memory_wiki_tab"))
            searchMemory("sapphire notebooks", By.text(sapphire))
            clickEnabled(By.text(sapphire))
            assertNotNull(find(By.text("Preferences")))
            clickEnabled(By.res("memory_sources_tab"))
            assertNotNull(scrollTo(By.textStartsWith("Captured from Text conversation")))
            clickEnabled(By.res("memory_article_back"))

            // Explicit category/topic placement can be changed later and survives the store reload.
            addManual(atlas, "projects", "Atlas")
            addManual(cobaltNotes, "knowledge", "Cobalt")
            clickFixedMemoryControl(By.res("memory_review_tab"))
            assertNotNull(find(By.text("Review (2)")))
            clickEnabled(By.res("memory_approve"))
            assertNotNull(find(By.text("Review (1)")))
            clickEnabled(By.res("memory_approve"))
            assertNotNull(find(By.text("Review (0)")))
            clickEnabled(By.res("memory_wiki_tab"))
            searchMemory("atlas", By.text(atlas))
            clickEnabled(By.text(atlas))
            assertNotNull(find(By.res("memory_article_tab")))
            assertNotNull(find(By.text("Atlas")))
            clickEnabled(By.text(atlas))
            assertNotNull(find(By.text("Memory detail")))
            clickEnabled(By.res("memory_organize"))
            assertNotNull(find(By.text("Organize memory")))
            clickHorizontalChip(By.res("memory_organize_category_picker"), By.res("memory_organize_category_knowledge"))
            enterText(By.res("memory_organize_topic"), "Verified links")
            captureEvidence("memory_wiki_organize_dialog")
            clickEnabled(By.res("memory_assign"))
            assertTrue("Organize dialog did not close after saving placement", device.wait(
                Until.gone(By.res("memory_organize_topic")), 15_000
            ))
            // Assignment moved the record from Atlas to Verified links and refreshed the page index.
            clickEnabled(By.res("memory_detail_back"))
            searchMemory("atlas", By.text(atlas))
            assertNotNull(find(By.text("Verified links")))
            clickEnabled(By.text(atlas))
            assertNotNull(find(By.text("Verified links")))
            assertNotNull(scrollTo(By.text("Linked pages")))
            clickEnabled(By.text("Cobalt"))
            assertNotNull(find(By.text("Cobalt")))
            assertNotNull(scrollTo(By.text("Backlinks")))
            assertNotNull(scrollTo(By.text("Verified links")))
            captureEvidence("memory_wiki_linked_article")
            clickEnabled(By.res("memory_article_back"))

            // Corrections stay out of the index until reviewed, then supersede the old search hit.
            searchMemory("sapphire notebooks", By.text(sapphire))
            clickEnabled(By.text(sapphire))
            assertNotNull(find(By.res("memory_article_tab")))
            clickEnabled(By.text(sapphire))
            clickEnabled(By.res("memory_correct"))
            assertNotNull(find(By.text("Correct memory")))
            enterText(By.res("memory_new_content"), indigo)
            clickEnabled(By.res("memory_propose"))
            assertTrue("Correction dialog did not close after its saved proposal", device.wait(
                Until.gone(By.res("memory_new_content")), 15_000
            ))
            clickEnabled(By.res("memory_article_back"))
            approveOnlyPending()
            clickEnabled(By.res("memory_wiki_tab"))
            searchMemory("sapphire notebooks", By.text("No approved memories match that search."))
            searchMemory("indigo notebooks", By.text(indigo))
            clickEnabled(By.text(indigo))
            assertNotNull(find(By.res("memory_article_tab")))
            clickEnabled(By.text(indigo))
            assertNotNull(find(By.text("This is a correction of an earlier saved fact.")))
            val indigoId = checkNotNull(memoryOs.read().snapshot).memories.first { it.content == indigo }.id
            clickEnabled(By.res("memory_delete"))
            assertNotNull(find(By.text("Erase this memory?")))
            clickEnabled(By.res("memory_delete_cancel"))
            assertTrue("Cancelled erase dialog must close before reopening", device.wait(
                Until.gone(By.text("Erase this memory?")), 15_000
            ))
            assertNotNull(find(By.text(indigo)))
            clickEnabled(By.res("memory_delete"))
            // Await the modal before a scroll-capable helper can dispatch a gesture.
            assertNotNull(find(By.res("memory_delete_confirm")))
            clickEnabled(By.res("memory_delete_confirm"))
            assertTrue("Deletion confirmation must finish before returning from a removed article", device.wait(
                Until.gone(By.text("Erase this memory?")), 15_000
            ))
            assertTrue("The deleted detail must leave the UI before optional article navigation", device.wait(
                Until.gone(By.res("memory_detail_$indigoId")), 15_000
            ))
            // A deletion can remove the page that was open. Return only when the page remains.
            device.findObject(By.res("memory_article_back"))?.click()
            stableFixedMemoryControl(By.res("memory_search_input"))
            searchMemory("indigo notebooks", By.text("No approved memories match that search."))
            searchMemory("sapphire notebooks", By.text("No approved memories match that search."))
            enterFixedMemorySearchText("")

            // Rejected pending records are retained only in History, never in the wiki index.
            addManual("Rejected private note for verification.", "knowledge", "Rejected")
            clickFixedMemoryControl(By.res("memory_review_tab"))
            clickEnabled(By.res("memory_reject"))
            assertNotNull(find(By.text("Review (0)")))
            clickEnabled(By.res("memory_wiki_tab"))
            searchMemory("rejected private note", By.text("No approved memories match that search."))
            enterFixedMemorySearchText("")
            clickFixedMemoryControl(By.res("memory_history_tab"))
            awaitMemoryHistoryRow("Rejected private note for verification.")
            assertNotNull(scrollTo(By.text("Rejected private note for verification.")))
            assertNotNull(scrollTo(By.textStartsWith("Rejected ·")))

            // A fresh approved record survives Activity recreation using the same real store.
            addManual(persistent, "preferences", "Tea")
            approveOnlyPending()
            activity.recreate()
            val reloaded = SQLiteMemoryStore(file).use { checkNotNull(it.read().snapshot) }
            assertTrue("A separately opened store must retain the approved record after recreation",
                reloaded.memories.any { it.content == persistent && it.reviewStatus == MemoryReviewStatus.APPROVED })
            assertTrue("Manual category/topic organization must also survive a separate store reopen",
                reloaded.memories.any { it.content == atlas && it.wikiAssignment == MemoryWikiAssignment(WikiCategory.KNOWLEDGE, "Verified links") })
            mountMemoryWiki()
            clickEnabled(By.res("memory_wiki_tab"))
            searchMemory("persistent amber tea", By.text(persistent))
            captureEvidence("memory_wiki_reloaded_search")
            enterFixedMemorySearchText("")
            clickEnabled(By.res("memory_erase_all"))
            assertNotNull(find(By.text("Erase all memories?")))
            clickModalAction(By.text("Cancel"))
            assertTrue("Cancelled erase-all dialog must close before reopening", device.wait(
                Until.gone(By.text("Erase all memories?")), 15_000
            ))
            searchMemory("persistent amber tea", By.text(persistent))
            enterFixedMemorySearchText("")
            clickEnabled(By.res("memory_erase_all"))
            assertNotNull(find(By.text("Erase all memories?")))
            clickModalAction(By.res("memory_delete_all_confirm"))
            assertTrue("Erase-all confirmation must finish before checking the ledger", device.wait(
                Until.gone(By.text("Erase all memories?")), 15_000
            ))
            clickFixedMemoryControl(By.res("memory_history_tab"))
            assertNotNull(find(By.text("No memory history yet.")))
            clickEnabled(By.res("memory_wiki_tab"))
            searchMemory("persistent amber tea", By.text("No approved memories match that search."))

            // History keeps the bulk action even when no approved page exists.
            // Remounting preserves rememberSaveable state; leave the search explicitly first.
            enterFixedMemorySearchText("")
            val pendingOnly = checkNotNull(memoryOs.propose(MemoryProposal("Pending ledger-only note", MemorySource("release-pending-only", "manual", System.currentTimeMillis()))).memory)
            val rejectedOnly = checkNotNull(memoryOs.propose(MemoryProposal("Rejected ledger-only note", MemorySource("release-rejected-only", "manual", System.currentTimeMillis() + 1))).memory)
            assertEquals(MemoryOutcome.REJECTED, memoryOs.reject(rejectedOnly.id, rejectedOnly.revision).outcome)
            mountMemoryWiki()
            clickFixedMemoryControl(By.res("memory_history_tab"))
            assertNotNull(scrollTo(By.text(pendingOnly.content)))
            assertNotNull(scrollTo(By.text(rejectedOnly.content)))
            clickEnabled(By.res("memory_erase_all"))
            assertNotNull(find(By.text("Erase all memories?")))
            clickModalAction(By.res("memory_delete_all_confirm"))
            assertTrue("History erase confirmation must finish", device.wait(Until.gone(By.text("Erase all memories?")), 15_000))
            assertNotNull(find(By.text("No memory history yet.")))
        } finally {
            store.close(); file.delete()
        }
    }

    @Test fun test25_finalizedTextAndVoiceMemoryNeedsApprovalBeforePromptUse() {
        // Controlled finalized-input fixtures exercise the production local bridge and prompt builder,
        // not a microphone or model-generated response.
        val file = File(context.cacheDir, "release-finalized-memory.json").apply { delete() }
        val now = System.currentTimeMillis()
        try {
            val os = MemoryOs(file) { now }
            val bridge = ConversationMemory(os)
            val text = bridge.capture(FinalMemoryInput(
                "release-text-memory", "thread-release", null, ConversationMemorySource.TEXT,
                "Remember I prefer bergamot tea.", now
            ))
            val voice = bridge.capture(FinalMemoryInput(
                "release-voice-memory", "thread-release", "call-release", ConversationMemorySource.VOICE,
                "My favorite color is cobalt.", now
            ))
            assertEquals(ConversationMemoryOutcome.PROPOSED, text.outcome)
            assertEquals(ConversationMemoryOutcome.PROPOSED, voice.outcome)
            val pending = checkNotNull(os.read().snapshot).memories
            assertEquals(setOf(MemoryReviewStatus.PENDING), pending.map { it.reviewStatus }.toSet())
            assertTrue(bridge.approvedContext("tea", 600).packet!!.memories.isEmpty())

            pending.forEach { record -> assertEquals(MemoryOutcome.APPROVED, os.approve(record.id, record.revision).outcome) }
            val packet = checkNotNull(bridge.approvedContext("tea cobalt", 900).packet).text
            assertTrue(packet.contains("I prefer bergamot tea"))
            assertTrue(packet.contains("My favorite color is cobalt"))
            val builder = ConversationPromptBuilder(ShortTermConversationContext())
            assertTrue(builder.buildGemmaPrompt("What tea and color do I prefer?", null, emptyList(), true,
                memoryContext = packet).contains(packet))
            assertTrue(builder.buildGemmaPrompt("What tea and color do I prefer?", null, emptyList(), true,
                voice = true, memoryContext = packet).contains(packet))
        } finally { file.delete() }
    }

    @Test fun test26_memoryCorrectionAndEraseRefreshApprovedPacket() {
        // This is a local persistence/refresh contract; it does not claim a generated reply changed.
        val file = File(context.cacheDir, "release-memory-refresh.json").apply { delete() }
        val now = System.currentTimeMillis()
        try {
            val os = MemoryOs(file) { now }
            val bridge = ConversationMemory(os)
            val created = checkNotNull(bridge.capture(FinalMemoryInput(
                "release-original-memory", "thread-release", null, ConversationMemorySource.TEXT,
                "Remember I prefer blue mugs.", now
            )).memory)
            val approved = checkNotNull(os.approve(created.id, created.revision).memory)
            val before = bridge.approvedContext("mugs", 600)
            assertTrue(checkNotNull(before.packet).text.contains("blue mugs"))

            val correction = checkNotNull(os.propose(MemoryProposal(
                "I prefer green mugs",
                MemorySource("release-correction-memory", "final_text_input", now),
                correctsMemoryId = approved.id,
                expectedTargetRevision = approved.revision,
            )).memory)
            val replacement = checkNotNull(os.approve(correction.id, correction.revision).memory)
            val afterCorrection = bridge.approvedContext("mugs", 600)
            assertTrue(checkNotNull(afterCorrection.packet).text.contains("green mugs"))
            assertFalse(afterCorrection.packet.text.contains("blue mugs"))
            assertNotEquals(before.stateToken, afterCorrection.stateToken)

            assertEquals(MemoryOutcome.DELETED, os.delete(replacement.id, replacement.revision).outcome)
            val afterErase = bridge.approvedContext("mugs", 600)
            assertTrue(checkNotNull(afterErase.packet).memories.isEmpty())
            assertNotEquals(afterCorrection.stateToken, afterErase.stateToken)
        } finally { file.delete() }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test27_controlledConversationSurfaceKeepsCallUntilExplicitEnd() {
        // Controlled StateFlows exercise the production Compose navigation surface. They do not
        // start audio capture, load weights, or represent a microphone/model result.
        val prefs = context.getSharedPreferences("release-conversation-surface", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val history = ConversationHistory(prefs)
        val busy = MutableStateFlow(false)
        val callState = MutableStateFlow(VoiceSessionState.ACTIVELY_LISTENING)
        val ends = AtomicInteger(0)
        val sends = AtomicInteger(0)
        val memoryBacks = AtomicInteger(0)
        val memoryFile = File(context.cacheDir, "release-memory-overlay.json").apply { delete() }
        val queueFull = AtomicBoolean(true)
        val forceChat = androidx.compose.runtime.mutableStateOf(false)
        val forceChatConsumed = AtomicInteger(0)
        val forceChatAcknowledged = java.util.concurrent.CountDownLatch(1)
        try {
            activity.onActivity { host -> host.setContent {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    ConversationScreen(
                        history = history,
                        busy = busy,
                        callState = callState,
                        onSend = { _, attachment ->
                            sends.incrementAndGet()
                            if (attachment != null) "Attachments are unavailable while a Voice Call is active."
                            else if (queueFull.get()) "Voice Call input queue is full. Wait for the current turn." else null
                        },
                        selectedModel = LocalModelSpec("release-fixture", "release-fixture.bin", recommendedGpu = false),
                        onSelectConversation = { null },
                        onEndVoice = { done -> ends.incrementAndGet(); done("Voice Call ended.") },
                        onOpenVoiceCalls = {},
                        resumedVoice = false,
                        forceChatDestination = forceChat.value,
                        onForceChatConsumed = {
                            forceChatConsumed.incrementAndGet()
                            forceChat.value = false
                            forceChatAcknowledged.countDown()
                        },
                        voiceContent = { visible, _, _, _ ->
                            if (visible) VoiceCallOverlay.Bubble(
                                phase = "Listening",
                                status = VoiceSessionUi.status.value.ifBlank { "Voice Call is listening" },
                                level = 0.2f,
                                active = true,
                                microphonePaused = false,
                                canStart = false,
                                stopReplyAvailable = false,
                                onStart = {},
                                onStopReply = {},
                                onToggleMicrophone = {},
                                onEndCall = { ends.incrementAndGet() },
                                transcriptSpeaker = "",
                                transcript = ""
                            )
                        },
                    )
                    }
                }
            } }
            VoiceSessionUi.status.value = "Voice Call is listening — controlled fixture with a deliberately long status that must not hide End call on a narrow screen."
            VoiceSessionUi.armed.value = true
            assertNotNull(find(By.res("voice_call_overlay")))
            device.pressBack()
            device.waitForIdle()
            assertEquals("Back must not end the active call", 0, ends.get())
            assertNotNull("Back keeps the floating call bubble over chat", find(By.res("voice_call_overlay")))
            assertNotNull(find(By.res("voice_call_status")))
            val endBounds = find(By.res("voice_call_end")).visibleBounds
            assertTrue("End call must remain visible beside a long status", endBounds.width() > 0 && endBounds.right <= device.displayWidth)
            assertFalse("Unified chat has no mode tabs", device.hasObject(By.res("voice_tab")))
            assertEquals("Unified chat has no mode tabs", false, device.hasObject(By.res("chat_tab")))
            find(By.res("voice_call_open")).click()
            assertNotNull(find(By.res("voice_call_overlay")))
            device.pressBack()
            device.waitForIdle()
            assertEquals("Back leaves the call bubble open without ending the call", 0, ends.get())
            assertNotNull(find(By.res("voice_call_overlay")))
            activity.onActivity { forceChat.value = true }
            // These views already exist in unified chat; wait for the new request itself.
            assertTrue("Memory return must be consumed before checking the shared call surface",
                forceChatAcknowledged.await(15, java.util.concurrent.TimeUnit.SECONDS))
            assertNotNull(find(By.res("chat_composer")))
            assertTrue(device.wait(Until.hasObject(By.res("voice_call_overlay")), 15_000))
            assertEquals("Returning from Memory preserves the active overlay", 1, forceChatConsumed.get())
            assertEquals(0, ends.get())
            assertNotNull(find(By.res("voice_call_status")))
            assertNotNull(find(By.text("Attachments are unavailable during a voice call. End the call to add one.")))
            enterText(By.res("chat_composer"), "Synthetic typed call follow-up")
            clickEnabled(By.res("chat_send"))
            assertEquals(1, sends.get())
            assertNotNull(find(By.text("Voice Call input queue is full. Wait for the current turn.")))
            assertEquals("Synthetic typed call follow-up", find(By.res("chat_composer")).text)
            queueFull.set(false)
            clickEnabled(By.res("chat_send"))
            assertEquals(2, sends.get())
            assertFalse("Successful armed admission must clear the draft", find(By.res("chat_send")).isEnabled)
            find(By.res("voice_call_end")).click()
            assertEquals(1, ends.get())
            VoiceSessionUi.armed.value = false

            // Mount the shipping parent with an isolated, explicitly seeded ready-state store.
            // Its inert callbacks deliberately avoid native model, microphone, and download work.
            val fixtureRoot = File(context.cacheDir, "release-jarvis-app-memory-route").apply { deleteRecursively(); mkdirs() }
            val fixtureContext = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = File(fixtureRoot, "files").apply { mkdirs() }
                override fun getCacheDir(): File = File(fixtureRoot, "cache").apply { mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("release-jarvis-app-$name", mode)
            }
            val fixtureStore = ModelStore(fixtureContext)
            val fixtureSpec = ModelCatalog.gemma4E2b
            val fixturePrefs = fixtureContext.getSharedPreferences("model_setup", Context.MODE_PRIVATE)
            fixturePrefs.edit().clear().commit()
            val fixtureFile = fixtureStore.fileFor(fixtureSpec).apply { parentFile?.mkdirs(); writeText("ready-state fixture") }
            fixturePrefs.edit()
                .putString("selected_model", fixtureSpec.id)
                .putString("sha256_${fixtureSpec.id}", "ready-state-fixture")
                .putLong("sha256_${fixtureSpec.id}_length", fixtureFile.length())
                .putLong("sha256_${fixtureSpec.id}_modified", fixtureFile.lastModified())
                .putBoolean("sha256_${fixtureSpec.id}_invalid", false)
                .putBoolean("smoke_test_passed_${fixtureSpec.id}", true)
                .commit()
            assertTrue("The isolated fixture must reach JarvisApp's ready branch", fixtureStore.isUsable() && fixtureStore.smokeTestPassed())
            val appHistory = ConversationHistory(fixtureContext.getSharedPreferences("conversation-history", Context.MODE_PRIVATE))
            val combinedTaskFile = File(fixtureRoot, "combined-tasks.json")
            val combinedTaskStore = FileToolTaskStore(combinedTaskFile)
            val combinedLedger = ToolTaskLedger(combinedTaskStore)
            val combinedApprovals = ActionApprovalStore(combinedTaskStore)
            val combinedGroup = combinedLedger.admit(listOf(ActionRequest("set_volume", mapOf("level" to "25"))),
                appHistory.current.value.id, ToolAuthority.EXACT_APPROVAL)
            val combinedChoice = ActionDispatchGate(combinedApprovals, combinedLedger)
                .prepare(checkNotNull(combinedLedger.get(combinedGroup.attemptIds.single())))
            val combinedJournal = MutableStateFlow<ToolTaskJournal?>(combinedLedger.journal())
            val callsBeforeRoute = ends.get()
            activity.onActivity { host -> host.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    JarvisApp(
                        store = fixtureStore, conversationHistory = appHistory, chatBusy = busy, callState = callState,
                        onSendChat = { _, _ -> null }, onSelectConversation = { null }, onSelectModel = { null }, onDeleteModel = { null },
                        voicePlayback = MutableStateFlow(VoicePlaybackFrame()), voiceModelStore = TtsModelStore(fixtureContext),
                        phoneTasks = combinedJournal,
                        onPhoneTaskAction = { id, generation, command ->
                            val attempt = combinedLedger.get(id)?.takeIf { it.generation == generation }
                            if (command == "deny") attempt?.approvalId?.let { combinedApprovals.deny(it) }
                            combinedJournal.value = combinedLedger.journal()
                        },
                        initialVoiceCalls = emptyList(), onRunModelSmokeTest = { done -> done("Ready-state fixture") },
                        onRunReliabilityCheck = { _, _, _, done ->
                            done(kotlin.Result.failure(IllegalStateException("Reliability checks are disabled in this route fixture.")))
                        },
                        onVoiceTurn = { _, _, _, done -> done("Voice is disabled in this route fixture.") },
                        onEndVoiceCall = { done -> ends.incrementAndGet(); done("") },
                        onResumeVoiceCall = { _, done -> done(null) }, onDeleteVoiceCall = {}, onRefreshVoiceCalls = { emptyList() },
                        onDownloadGemma = { _, _, _, done -> done("Downloads are disabled in this route fixture.") },
                        onCancelModelDownload = {},
                        onImportModel = { _, _, done -> done("Imports are disabled in this route fixture.") },
                        onCopyDiagnostics = {},
                    )
                } }
            } }
            assertNotNull("Wisp is present before starting a call", find(By.res("jarvis_wisp")))
            VoiceSessionUi.armed.value = true
            assertNotNull(find(By.res("voice_call_overlay")))
            assertNotNull(find(By.res("voice_call_status")))
            clickEnabled(By.res("memory_open"))
            assertNotNull("Wisp stays present while reviewing memory", find(By.res("jarvis_wisp")))
            assertNotNull(find(By.text("Memory")))
            clickFixedMemoryControl(By.res("memory_nav_chat"))
            assertNotNull(find(By.res("chat_composer")))
            assertEquals("The parent Memory-to-Chat route must preserve an active call", callsBeforeRoute, ends.get())
            clickEnabled(By.res("phone_tasks_open"))
            assertNotNull(find(By.text("Set media volume to 25%")))
            assertNotNull(find(By.text("Waiting for your approval")))
            clickEnabled(By.res("task_deny_${combinedChoice.task.id}"))
            assertEquals(ToolTaskState.CANCELLED, combinedLedger.get(combinedChoice.task.id)?.state)
            clickEnabled(By.text("Done"))
            assertNotNull(find(By.res("voice_call_overlay")))
            assertEquals("Task approval UI must not end the active call", callsBeforeRoute, ends.get())
            assertNotNull(find(By.res("voice_call_overlay")))
            clickEnabled(By.res("memory_open"))
            clickFixedMemoryControl(By.res("memory_nav_voice"))
            assertNotNull(find(By.res("voice_call_overlay")))
            assertNotNull("Voice and Chat now share the same transcript", find(By.res("chat_composer")))
            assertEquals("Memory-to-Voice must preserve the same call", callsBeforeRoute, ends.get())
            VoiceSessionUi.armed.value = false
            fixtureRoot.deleteRecursively()

            activity.onActivity { host -> host.setContent {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        Box(Modifier.fillMaxSize()) {
                            androidx.compose.material3.Text("Underlying call surface")
                            MemoryScreen(
                                memoryOs = MemoryOs(memoryFile),
                                onBack = { memoryBacks.incrementAndGet() },
                                callActive = true,
                                callStatus = "A deliberately long controlled call status that must keep the End call action visible on a narrow screen.",
                                onEndCall = { done -> ends.incrementAndGet(); done("Voice Call ended.") },
                            )
                        }
                    }
                }
            } }
            assertNotNull(find(By.text("Memory")))
            stableFixedMemoryControl(By.res("memory_back"))
            val memoryEndBounds = find(By.res("voice_call_end")).visibleBounds
            assertTrue("Memory must keep End call visible beside a long status", memoryEndBounds.width() > 0 && memoryEndBounds.right <= device.displayWidth)
            device.pressBack()
            device.waitForIdle()
            assertEquals("Memory overlay must consume system Back before its underlying call surface", 1, memoryBacks.get())
        } finally {
            VoiceSessionUi.armed.value = false
            VoiceSessionUi.status.value = ""
            memoryFile.delete()
        }
    }



    @Test fun test28_voiceNavigationRetainsCallIdUntilExplicitEnd() {
        val preferences = context.getSharedPreferences("voice-navigation-contract", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val controller = VoiceSessionController(SharedPreferencesVoiceCallStore(preferences))
        val call = controller.beginCall()
        // ConversationScreen dispatches these same transitions to its end-call callback.
        VoiceNavigationPolicy.dispatch(VoiceNavigationPolicy.Transition.SHOW_CHAT) { controller.end() }
        assertEquals(call.id, controller.currentCallId())
        VoiceNavigationPolicy.dispatch(VoiceNavigationPolicy.Transition.SHOW_VOICE) { controller.end() }
        assertEquals(call.id, controller.currentCallId())
        VoiceNavigationPolicy.dispatch(VoiceNavigationPolicy.Transition.EXPLICIT_END) { controller.end() }
        assertNull(controller.currentCallId())
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test29_eachAssistantReplyKeepsPersistedMetricsAcrossReload() {
        val prefs = context.getSharedPreferences("release-live-metrics", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val history = ConversationHistory(prefs)
        val threadId = history.current.value.id
        history.updateReply(threadId, "one", "First response", true)
        history.updateReplyMetrics(threadId, "one") { it.submitted(100).firstRawToken(220).copy(estimatedTokensPerSecond = 12.5) }
        history.updateReply(threadId, "two", "Second response", true)
        history.updateReplyMetrics(threadId, "two") { it.submitted(400).firstRawToken(760).copy(estimatedTokensPerSecond = 5.0) }
        activity.onActivity { host -> host.setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                ConversationScreen(history, MutableStateFlow(false), MutableStateFlow(VoiceSessionState.PASSIVE_LISTENING),
                    onSend = { _, _ -> null }, selectedModel = LocalModelSpec("release-fixture", "release-fixture.bin", recommendedGpu = false),
                    onSelectConversation = { null }, onEndVoice = {}, onOpenVoiceCalls = {}, resumedVoice = false,
                    voiceContent = { _, _, _, _ -> })
            } }
        } }
        assertTrue(find(By.res("reply_metrics_one")).text.contains("TTFT 0.12s"))
        assertTrue(find(By.res("reply_metrics_two")).text.contains("TTFT 0.36s"))
        val reloaded = ConversationHistory(prefs)
        assertEquals(12.5, reloaded.current.value.messages.first { it.id == "one" }.metrics?.estimatedTokensPerSecond)
        assertEquals(5.0, reloaded.current.value.messages.first { it.id == "two" }.metrics?.estimatedTokensPerSecond)
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test30_phoneCallOverlaysUnifiedChatAndKeepsVoiceNoteMic() {
        val prefs = context.getSharedPreferences("release-call-overlay", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val history = ConversationHistory(prefs)
        repeat(20) { history.updateReply(history.current.value.id, "swipe-$it", "Scrollable message $it", true) }
        val busy = MutableStateFlow(false)
        val ends = AtomicInteger(0)
        val liveCaption = "Live speech appears without minimizing"
        try {
            VoiceSessionUi.armed.value = false
            VoiceSessionUi.status.value = ""
            VoiceSessionUi.phase.value = VoicePhase.IDLE
            VoiceSessionUi.liveTranscript.value = ""
            activity.onActivity { host -> host.setContent {
                val callArmed by VoiceSessionUi.armed.collectAsState()
                MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    ConversationScreen(history, busy, MutableStateFlow(VoiceSessionState.ACTIVELY_LISTENING),
                        onSend = { _, _ -> null },
                        selectedModel = LocalModelSpec("release-fixture", "release-fixture.bin", recommendedGpu = false),
                        onSelectConversation = { null }, onEndVoice = { done -> ends.incrementAndGet(); done("") },
                        onOpenVoiceCalls = {}, resumedVoice = false,
                        voiceContent = { visible, _, _, _ ->
                            if (visible) VoiceCallOverlay.Bubble(
                                phase = "Listening",
                                status = VoiceSessionUi.status.value.ifBlank { "Voice Call is listening" },
                                transcriptSpeaker = "You",
                                transcript = liveCaption,
                                level = VoiceSessionUi.level.value,
                                active = callArmed,
                                microphonePaused = VoiceSessionUi.paused.value,
                                canStart = !callArmed,
                                stopReplyAvailable = false,
                                onStart = {},
                                onStopReply = {},
                                onToggleMicrophone = {},
                                onEndCall = { ends.incrementAndGet() }
                            )
                        })
                } }
            } }
            enterText(By.res("chat_composer"), "Keep my draft")
            hideKeyboardWithoutNavigating() // Keep the activity alive when no IME was opened.
            device.waitForIdle()
            assertNotNull(find(By.res("chat_voice_input")))
            busy.value = true
            assertFalse(waitUntilDisabled(By.res("voice_call_open")).isEnabled)
            busy.value = false
            enabled(By.res("voice_call_open"))
            find(By.res("voice_call_open")).click()
            assertNotNull("Voice button opens the in-window call controls", find(By.res("voice_call_overlay")))
            assertNotNull(find(By.res("voice_start")))
            assertNotNull("The separate dictation microphone stays in the composer", find(By.res("chat_voice_input")))
            VoiceSessionUi.status.value = "Voice Call is listening · live transcript fixture"
            VoiceSessionUi.phase.value = VoicePhase.LISTENING
            VoiceSessionUi.armed.value = true
            activity.onActivity {
                history.updateReply(history.current.value.id, "streaming-voice", "Transcript is updating while I speak", false)
            }
            VoiceSessionUi.liveTranscript.value = liveCaption
            assertEquals("Live speech appears without minimizing", find(By.res("voice_call_live_transcript")).text)
            assertNotNull(find(By.res("voice_call_status")))
            assertFalse("The call pill must not duplicate the persistent character", device.hasObject(By.res("voice_call_orb")))
            assertNotNull(find(By.res("voice_call_end")))
            assertNotNull(find(By.res("voice_call_pause")))
            assertFalse("A minimize control is not part of the call overlay", device.hasObject(By.res("voice_call_minimize")))
            val liveTranscript = find(By.text("Transcript is updating while I speak"))
            val overlayBounds = find(By.res("voice_call_overlay")).visibleBounds
            val transcriptBounds = find(By.res("conversation_transcript")).visibleBounds
            assertTrue("Call controls must be visible in the chat window", overlayBounds.width() > 0 && overlayBounds.height() > 0)
            assertTrue("The call controls must leave most chat width clear", overlayBounds.width() < transcriptBounds.width() / 2)
            assertFalse("Live text must remain outside the floating controls", android.graphics.Rect.intersects(
                find(By.res("voice_call_live_transcript")).visibleBounds, overlayBounds))
            assertTrue("The conversation viewport remains on screen under the bubble", transcriptBounds.width() > 0 && transcriptBounds.height() > 0)
            assertTrue("The live chat transcript remains visible with the bubble", liveTranscript.visibleBounds.width() > 0)
            assertTrue("The existing draft survives opening the bubble", find(By.res("chat_composer")).text.contains("Keep my draft"))
            assertEquals("Keeping the bubble open must not end the call", 0, ends.get())
            find(By.res("voice_call_open")).click()
            assertNotNull(find(By.res("voice_call_overlay")))
            assertEquals(0, ends.get())
            captureEvidence("test30_activeVoiceOrb")
        } finally {
            VoiceSessionUi.armed.value = false
            VoiceSessionUi.status.value = ""
            VoiceSessionUi.phase.value = VoicePhase.IDLE
            VoiceSessionUi.liveTranscript.value = ""
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test31_voiceInputReviewsTextAndCancelPreservesDraft() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        val prefs = context.getSharedPreferences("release-chat-dictation", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val history = ConversationHistory(prefs)
        val sends = AtomicInteger(0)
        val sessions = AtomicInteger(0)
        val transcribes = AtomicInteger(0)
        val textOnlyModel = LocalModelSpec("text-only-fixture", "fixture.bin", recommendedGpu = false)
        val audioSent = java.util.concurrent.atomic.AtomicReference<com.battlesbudz.jarvis.v2.chat.ChatAttachment?>()
        val rawPcm = ByteArray(3200) { (it % 127).toByte() }
        val cancellations = AtomicInteger(0)
        VoiceSessionUi.armed.value = false
        fun renderModel(model: LocalModelSpec) {
        activity.onActivity { host -> host.setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                ConversationScreen(history, MutableStateFlow(false), MutableStateFlow(VoiceSessionState.PASSIVE_LISTENING),
                    onSend = { text, attachment ->
                        history.appendUser(text, attachment)
                        audioSent.set(attachment); sends.incrementAndGet(); null },
                    selectedModel = model,
                    onSelectConversation = { null }, onEndVoice = {}, onOpenVoiceCalls = {}, resumedVoice = false,
                    dictationFactory = {
                        val number = sessions.incrementAndGet()
                        object : ChatDictation {
                            val result = CompletableDeferred<ByteArray>()
                            override suspend fun record(onStatus: (String) -> Unit): ByteArray {
                                onStatus("Recording controlled speech")
                                try { return result.await() }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { cancellations.incrementAndGet(); throw cancelled }
                            }
                            override fun finish() { result.complete(rawPcm) }
                            override suspend fun transcribe(pcm: ByteArray, onStatus: (String) -> Unit): String {
                                transcribes.incrementAndGet()
                                if (number == 3 || number == 5) error("No speech detected. Try recording again.")
                                return "dictated words"
                            }
                        }
                    }, voiceContent = { _, _, _, _ -> })
            } }
        } }
        }
        renderModel(textOnlyModel)
        assertFalse(device.hasObject(By.text("Attach audio")))
        enterText(By.res("chat_composer"), "Existing draft")
        hideKeyboardWithoutNavigating()
        clickEnabled(By.res("chat_voice_input"))
        assertNotNull(find(By.res("dictation_status")))
        assertFalse("The recording panel replaces the composer, including the call button",
            device.hasObject(By.res("voice_call_open")))
        assertFalse("Text-only model must not receive raw audio", find(By.res("dictation_send")).isEnabled)
        assertFalse("Recording replaces the text-send controls", device.hasObject(By.res("chat_send")))
        clickEnabled(By.res("dictation_stop"))
        enabled(By.res("chat_voice_input"))
        enabled(By.res("voice_call_open"))
        assertEquals("Existing draft dictated words", find(By.res("chat_composer")).text)
        assertEquals("Dictation must not send a message", 0, sends.get())
        clickEnabled(By.res("chat_voice_input"))
        assertNotNull(find(By.res("dictation_status")))
        clickEnabled(By.res("dictation_cancel"))
        enabled(By.res("chat_voice_input"))
        enabled(By.res("voice_call_open"))
        assertEquals(1, cancellations.get())
        assertEquals("Existing draft dictated words", find(By.res("chat_composer")).text)
        clickEnabled(By.res("chat_voice_input"))
        assertNotNull(find(By.res("dictation_status")))
        clickEnabled(By.res("dictation_stop"))
        assertNotNull(find(By.text("No speech detected. Try recording again.")))
        assertEquals("Existing draft dictated words", find(By.res("chat_composer")).text)
        assertEquals(0, sends.get())
        enterText(By.res("chat_composer"), "Reviewed and edited")
        clickEnabled(By.res("chat_send"))
        assertEquals(1, sends.get())
        assertNull(audioSent.get())
        renderModel(textOnlyModel.copy(supportsAudio = true))
        clickEnabled(By.res("chat_voice_input"))
        enabled(By.res("dictation_send"))
        captureEvidence("voice_recording_controls")
        val beforeAudioSend = transcribes.get()
        clickEnabled(By.res("dictation_send"))
        enabled(By.res("chat_voice_input"))
        assertEquals(2, sends.get())
        assertEquals("Send must include a transcript", beforeAudioSend + 1, transcribes.get())
        val saved = history.current.value.messages.last()
        assertEquals("dictated words", saved.text)
        assertTrue(saved.spoken)
        assertNotNull(saved.attachment)
        val reloaded = ConversationHistory(prefs).current.value.messages.last()
        assertEquals(saved.text, reloaded.text)
        assertEquals(saved.attachment, reloaded.attachment)
        assertNotNull(find(By.desc("Play voice message")))
        val attachment = requireNotNull(audioSent.get())
        assertEquals(com.battlesbudz.jarvis.v2.chat.AttachmentKind.AUDIO, attachment.kind)
        val wav = File(requireNotNull(android.net.Uri.parse(attachment.uri).path)).readBytes()
        com.battlesbudz.jarvis.v2.chat.AttachmentPolicy.validateAudio(wav)
        assertArrayEquals("Audio payload must preserve the recording", rawPcm, wav.copyOfRange(44, wav.size))
        captureEvidence("voice_message_with_transcript")
        enterText(By.res("chat_composer"), "Keep my draft")
        hideKeyboardWithoutNavigating()
        clickEnabled(By.res("chat_voice_input"))
        clickEnabled(By.res("dictation_send"))
        assertNotNull(find(By.text("No speech detected. Try recording again.")))
        enabled(By.res("chat_voice_input"))
        assertEquals("Keep my draft", find(By.res("chat_composer")).text)
        assertEquals("Failed Send transcription must not send", 2, sends.get())
        com.battlesbudz.jarvis.v2.chat.ChatMediaStore.discard(context, attachment)
    }

    @Test fun test32_installedModelAndCompletedDownloadSurviveStoreRecreation() = runBlocking {
        val bytes = ByteArray(4097) { (it % 251).toByte() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val spec = LocalModelSpec(
            id = "verification-storage", fileName = "verification-storage.litertlm",
            expectedSha256 = hash, recommendedGpu = false, downloadBytes = bytes.size.toLong(),
            downloadUrl = "https://127.0.0.1:9/must-not-download.litertlm"
        )
        val store = com.battlesbudz.jarvis.v2.ai.ModelStore(context)
        val file = store.fileFor(spec)
        val preferences = context.getSharedPreferences("model_setup", android.content.Context.MODE_PRIVATE)
        val key = "sha256_${spec.id}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, spec.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
            assertTrue(store.importModel(uri, spec).isSuccess)
            assertTrue(store.hasModel(spec))
            assertTrue(com.battlesbudz.jarvis.v2.ai.ModelStore(context).isUsable(spec))
            // Lose only verification metadata, as can happen on upgrade/restore.
            preferences.edit().remove(key).remove("${key}_length").remove("${key}_modified").commit()
            val restored = com.battlesbudz.jarvis.v2.ai.ModelStore(context)
            assertTrue(restored.hasModel(spec))
            assertFalse(restored.isUsable(spec))
            assertTrue(restored.verifyIntegrity(spec))
            assertTrue(restored.isUsable(spec))
            assertTrue(restored.downloadOrReuse(spec).isSuccess)
            assertArrayEquals(bytes, file.readBytes())
            // Corrupt installed bytes must never be accepted as usable.
            file.writeBytes(ByteArray(bytes.size))
            preferences.edit().remove("${key}_modified").commit()
            assertFalse(restored.verifyIntegrity(spec))
            assertFalse(restored.isUsable(spec))
            // A fully downloaded checkpoint installs without a network request.
            file.delete()
            File(file.parentFile, "${spec.fileName}.part").writeBytes(bytes)
            context.contentResolver.delete(uri, null, null)
            // Simulate the actual voice owner's native lease. Installing another
            // model must neither reject the transfer nor release that lease.
            val selectedId = restored.selectedModel().id
            assertTrue(restored.tryBeginModelOperation())
            try {
                assertTrue(restored.downloadOrReuse(spec).isSuccess)
                assertTrue("Download completion must preserve the voice owner's lock", restored.isModelOperationActive())
                assertFalse(restored.tryBeginModelOperation())
                assertEquals(selectedId, restored.selectedModel().id)
            } finally { restored.endModelOperation() }
            assertFalse(restored.isModelOperationActive())
            assertTrue(restored.downloadOrReuse(spec).isSuccess)
            assertTrue(com.battlesbudz.jarvis.v2.ai.ModelStore(context).isUsable(spec))
            assertArrayEquals(bytes, file.readBytes())
        } finally {
            context.contentResolver.delete(uri, null, null)
            file.delete()
            File(file.parentFile, "${spec.fileName}.part").delete()
            val edit = preferences.edit()
            preferences.all.keys.filter { it.contains(spec.id) }.forEach { edit.remove(it) }
            edit.commit()
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test33_downloadDoesNotSelectOrDismissCurrentModel() {
        val selectedId = com.battlesbudz.jarvis.v2.ai.ModelStore(context).selectedModel().id
        val requested = AtomicReference<String?>()
        val chosen = AtomicReference<String?>()
        val downloadDelivered = java.util.concurrent.CountDownLatch(1)
        val selectionDismissed = java.util.concurrent.CountDownLatch(1)
        val choices = AtomicInteger(0)
        val dismissals = AtomicInteger(0)
        activity.onActivity { host -> host.setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                com.battlesbudz.jarvis.v2.ui.ModelBrowser(
                    phone = com.battlesbudz.jarvis.v2.ai.PhoneProfile("Release fixture", "Fixture", "Fixture",
                        12_000_000_000L, 8_000_000_000L, 20_000_000_000L, true),
                    selectedId = selectedId, isInstalled = { it.id == selectedId },
                    benchmarkStore = AndroidPipelineBenchmarkStore(context),
                    onSelect = { chosen.set(it.id); choices.incrementAndGet(); null },
                    onDismiss = { dismissals.incrementAndGet(); selectionDismissed.countDown() },
                    onDownload = { requested.set(it.id); downloadDelivered.countDown() })
            }
        } }
        enterText(By.res("model_search"), "LFM2.5-230M")
        hideKeyboardWithoutNavigating()
        find(By.res("model_family_Liquid · LFM")).click()
        scrollTo(By.res("model_download_LFM2.5-230M")).click()
        // UiObject2.click injects input asynchronously; observe its callback before assertions.
        assertTrue("Download callback must arrive within the UI deadline",
            downloadDelivered.await(15, java.util.concurrent.TimeUnit.SECONDS))
        activity.onActivity {
            assertEquals("LFM2.5-230M", requested.get())
            assertEquals("Download must not choose the requested model", 0, choices.get())
            assertEquals("Download must keep the browser available", 0, dismissals.get())
            assertEquals(selectedId, com.battlesbudz.jarvis.v2.ai.ModelStore(context).selectedModel().id)
        }
        scrollTo(By.res("model_choose_LFM2.5-230M")).click()
        // ModelBrowser calls onSelect then onDismiss in the same UI handler. Await its
        // terminal callback, then inspect both exact counts after that handler completes.
        assertTrue("Choose dismissal callback must arrive within the UI deadline",
            selectionDismissed.await(15, java.util.concurrent.TimeUnit.SECONDS))
        activity.onActivity {
            assertEquals("Choosing remains a separate explicit action", 1, choices.get())
            assertEquals("LFM2.5-230M", chosen.get())
            assertEquals(1, dismissals.get())
        }
    }

    @Test fun test34_sqliteMigrationPreservesHistoryAndEraseAcrossReopen() {
        val root = File(context.cacheDir, "release-sqlite-migration").apply { deleteRecursively(); mkdirs() }
        val legacy = File(root, "memory-os.json")
        val database = File(root, "memory-os.db")
        val now = System.currentTimeMillis()
        try {
            val original = MemoryOs(legacy) { now }
            val approved = original.propose(MemoryProposal("I prefer azure mugs", MemorySource("migration-approved", "manual", now))).memory!!
            original.approve(approved.id)
            val replacement = original.propose(MemoryProposal("I prefer jade mugs", MemorySource("migration-correction", "manual", now), correctsMemoryId = approved.id)).memory!!
            original.approve(replacement.id)
            val erasedProposal = MemoryProposal("An erased orchid note", MemorySource("migration-erased", "manual", now))
            val erased = original.propose(erasedProposal).memory!!
            original.delete(erased.id)
            original.propose(MemoryProposal("Pending quartz note", MemorySource("migration-pending", "manual", now)))
            val expected = checkNotNull(original.read().snapshot)
            // Exercise the actual schema-1 migration path too, including optional older fields.
            val json = org.json.JSONObject(legacy.readText()).apply { put("schemaVersion", 1) }
            legacy.writeText(json.toString())
            val oldBytes = legacy.readBytes()
            SQLiteMemoryStore(database, legacy).use { storage ->
                assertEquals(expected, storage.read().snapshot)
                assertFalse("Retire the JSON only after a validated SQLite commit", legacy.exists())
                val os = MemoryOs(storage) { now }
                assertTrue(os.contextPacket("mugs", 900).packet!!.text.contains("jade mugs"))
                assertFalse(os.contextPacket("mugs", 900).packet!!.text.contains("azure mugs"))
                assertEquals(MemoryOutcome.DELETED, os.propose(erasedProposal).outcome)
                assertEquals(MemoryOutcome.DELETED, os.delete(replacement.id).outcome)
            }
            // Simulate a stale pre-migration file surviving a crash: never import it twice.
            legacy.writeBytes(oldBytes)
            SQLiteMemoryStore(database, legacy).use { storage ->
                val os = MemoryOs(storage) { now }
                assertNotNull(os.read().snapshot)
                assertFalse(legacy.exists())
                assertTrue(os.contextPacket("mugs", 900).packet!!.memories.isEmpty())
                assertEquals(MemoryOutcome.DELETED, os.propose(erasedProposal).outcome)
                assertEquals(1, storage.read().snapshot!!.memories.size)
                assertEquals(3, storage.read().snapshot!!.tombstones.size)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun test35_sqliteCorruptMigrationAndFailedEraseDoNotLoseData() {
        val root = File(context.cacheDir, "release-sqlite-failure").apply { deleteRecursively(); mkdirs() }
        val legacy = File(root, "memory-os.json")
        val database = File(root, "memory-os.db")
        val now = System.currentTimeMillis()
        try {
            legacy.writeText("{broken")
            SQLiteMemoryStore(database, legacy).use { storage ->
                assertNotNull(storage.read().error)
                assertNull(storage.read().snapshot)
                assertEquals("{broken", legacy.readText())
            }
            // Failed migration rolls back schema/data too; repair the source and retry safely.
            legacy.delete()
            val proposal = MemoryProposal("I prefer silver spoons", MemorySource("sqlite-failure-original", "manual", now))
            val source = MemoryOs(legacy) { now }
            val record = source.propose(proposal).memory!!
            source.approve(record.id)
            SQLiteMemoryStore(database, legacy).use { storage ->
                val os = MemoryOs(storage) { now }
                val before = checkNotNull(os.read().snapshot)
                android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { db ->
                    db.execSQL("CREATE TRIGGER reject_erasure BEFORE INSERT ON tombstones BEGIN SELECT RAISE(ABORT, 'injected write failure'); END")
                }
                assertEquals(MemoryOutcome.STORAGE_FAILURE, os.delete(record.id).outcome)
                assertEquals("Rows and generation must roll back together", before, os.read().snapshot)
                assertTrue(os.contextPacket("spoons", 900).packet!!.text.contains("silver spoons"))
                android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { db -> db.execSQL("DROP TRIGGER reject_erasure") }
                assertEquals(MemoryOutcome.DELETED, os.delete(record.id).outcome)
            }
            SQLiteMemoryStore(database).use { storage ->
                assertTrue(storage.read().snapshot!!.memories.isEmpty())
                assertEquals(MemoryOutcome.DELETED, MemoryOs(storage) { now }.propose(proposal).outcome)
            }
            // A future DB version must never be reset or treated as an empty ledger.
            android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { it.version = 99 }
            SQLiteMemoryStore(database).use { storage -> assertNotNull(storage.read().error) }
            android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { assertEquals(99, it.version) }
        } finally { root.deleteRecursively() }
    }

    @Test fun test36_sqliteExceedsLegacyByteLimitAndSerializesSeparateWriters() {
        val root = File(context.cacheDir, "release-sqlite-capacity").apply { deleteRecursively(); mkdirs() }
        val database = File(root, "memory-os.db")
        val now = System.currentTimeMillis()
        try {
            SQLiteMemoryStore(database).use { storage ->
                val seed = MemoryOs(storage) { now }.propose(MemoryProposal("Capacity seed", MemorySource("capacity-seed", "manual", now))).memory!!
                // Controlled valid rows isolate the storage ceiling without 450 extraction calls.
                val result = storage.update { before ->
                    val records = (1..450).map { index -> seed.copy(id = java.util.UUID.randomUUID().toString(), content = "Capacity row $index " + "q".repeat(1950), source = seed.source.copy(eventId = java.util.UUID.randomUUID().toString().replace("-", "")), payloadFingerprint = null) }
                    before.copy(generation = before.generation + 1, memories = before.memories + records) to records.size
                }
                assertEquals(450, result.value)
                android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { db ->
                    db.rawQuery("SELECT SUM(length(payload)) FROM memories", null).use { cursor -> cursor.moveToFirst(); assertTrue(cursor.getLong(0) > MemoryStore.MAX_STORE_BYTES) }
                }
            }
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            try {
                val futures = (1..8).map { index -> pool.submit<MemoryOutcome> {
                    SQLiteMemoryStore(database).use { storage -> MemoryOs(storage) { now }.propose(MemoryProposal("Concurrent writer $index", MemorySource("writer-$index", "manual", now))).outcome }
                } }
                futures.forEach { assertEquals(MemoryOutcome.CREATED, it.get(60, java.util.concurrent.TimeUnit.SECONDS)) }
            } finally { pool.shutdownNow() }
            SQLiteMemoryStore(database).use { storage ->
                assertEquals(459, storage.read().snapshot!!.memories.size)
                assertEquals(10L, storage.read().snapshot!!.generation)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun test37_sourceArchiveRetainsExplicitHistoryAndExpiresWithoutFactLoss() {
        val root = File(context.cacheDir, "release-source-archive").apply { deleteRecursively(); mkdirs() }
        val database = File(root, "memory-os.db")
        var now = System.currentTimeMillis()
        val capturedAt = now
        var unlocked = true
        fun input(id: String, text: String) = FinalMemoryInput(id, "archive-conversation", "archive-call", ConversationMemorySource.TEXT, text, capturedAt)
        val removed = input("archive-removed", "I prefer silver spoons")
        try {
            SQLiteMemoryStore(database, canReadSourceText = { unlocked }, archiveClock = { now }).use { storage ->
                val os = MemoryOs(storage) { now }
                val bridge = ConversationMemory(os, storage)
                assertEquals(ConversationMemoryOutcome.IGNORED, bridge.capture(input("archive-episode", "We discussed sapphire notebook delivery yesterday")).outcome)
                assertTrue(os.contextPacket("sapphire", 900).packet!!.memories.isEmpty())
                assertEquals(1, storage.searchExplicitHistory("sapphire").episodes.size)
                assertTrue(storage.searchExplicitHistory("%").episodes.isEmpty())
                val fact = bridge.capture(removed).memory!!
                os.approve(fact.id)
                os.delete(fact.id)
                assertTrue(os.contextPacket("spoons", 900).packet!!.memories.isEmpty())
                assertEquals(1, storage.searchExplicitHistory("spoons").episodes.size)
                bridge.capture(removed)
                assertTrue("Retained source must not recreate an erased fact", os.read().snapshot!!.memories.isEmpty())
                val retained = bridge.capture(input("archive-retained", "I prefer jade mugs")).memory!!
                os.approve(retained.id)
                unlocked = false
                assertEquals(SourceArchiveOutcome.LOCKED, storage.searchExplicitHistory("jade").outcome)
                assertTrue(storage.searchExplicitHistory("jade").episodes.isEmpty())
                unlocked = true
                now = capturedAt + com.battlesbudz.jarvis.v2.memory.MemoryArchivePolicy.RETENTION_MS - 1
                assertEquals(1, storage.searchExplicitHistory("jade").episodes.size)
                now++
                assertTrue(storage.searchExplicitHistory("jade").episodes.isEmpty())
                assertTrue(os.contextPacket("mugs", 900).packet!!.text.contains("jade mugs"))
                assertEquals(SourceArchiveOutcome.EXPIRED, storage.captureSource(removed.copy(capturedAtMs = now)).outcome)
                android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { db ->
                    db.rawQuery("SELECT count(*), sum(text_bytes), count(text) FROM source_events", null).use {
                        assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0)); assertEquals(0, it.getInt(1)); assertEquals(0, it.getInt(2))
                    }
                }
            }
            SQLiteMemoryStore(database, canReadSourceText = { true }, archiveClock = { now }).use { storage ->
                assertTrue(storage.searchExplicitHistory("sapphire").episodes.isEmpty())
                assertTrue(MemoryOs(storage) { now }.contextPacket("mugs", 900).packet!!.text.contains("jade mugs"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun test38_sourceArchiveUpgradesV1RejectsSecretsAndRollsBackFailure() {
        val root = File(context.cacheDir, "release-source-archive-failure").apply { deleteRecursively(); mkdirs() }
        val database = File(root, "memory-os.db")
        val now = System.currentTimeMillis()
        fun input(id: String, text: String) = FinalMemoryInput(id, "archive-thread", null, ConversationMemorySource.TEXT, text, now)
        try {
            val before = SQLiteMemoryStore(database).use { storage ->
                val os = MemoryOs(storage) { now }
                val fact = os.propose(MemoryProposal("I prefer copper mugs", MemorySource("archive-v1-fact", "manual", now))).memory!!
                os.approve(fact.id); os.read().snapshot!!
            }
            // Restore the exact v1 table layout to exercise the additive upgrade.
            android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { db -> db.execSQL("DROP TABLE source_events"); db.version = 1 }
            SQLiteMemoryStore(database, canReadSourceText = { true }, archiveClock = { now }).use { storage ->
                assertEquals(before, storage.read().snapshot)
                assertEquals(SourceArchiveOutcome.STORED, storage.captureSource(input("health", "My doctor appointment is Monday")).outcome)
                assertEquals(SourceArchiveOutcome.ALREADY_RECORDED, storage.captureSource(input("health", "My doctor appointment is Monday")).outcome)
                assertEquals(SourceArchiveOutcome.CONFLICT, storage.captureSource(input("health", "My doctor appointment is Tuesday")).outcome)
                assertEquals(SourceArchiveOutcome.STORED, storage.captureSource(input("financial", "My bank balance is $500").copy(source = ConversationMemorySource.VOICE)).outcome)
                assertEquals(ConversationMemorySource.VOICE, storage.searchExplicitHistory("bank balance").episodes.single().source)
                listOf("PIN: 1234", "My password is x", "Card number 4111 1111 1111 1111", "x".repeat(4_000) + " password: hunter22").forEachIndexed { index, text ->
                    assertEquals(SourceArchiveOutcome.EXCLUDED, storage.captureSource(input("secret-$index", text)).outcome)
                }
                assertEquals(SourceArchiveOutcome.IGNORED, storage.captureSource(input("draft", "tea").copy(complete = false)).outcome)
                android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { db ->
                    assertEquals(2, db.version)
                    db.execSQL("CREATE TRIGGER reject_source BEFORE INSERT ON source_events BEGIN SELECT RAISE(ABORT, 'injected archive failure'); END")
                }
                assertEquals(SourceArchiveOutcome.STORAGE_FAILURE, storage.captureSource(input("failed", "We discussed amber notebooks")).outcome)
                assertEquals(before, storage.read().snapshot)
                assertTrue(storage.searchExplicitHistory("amber").episodes.isEmpty())
                assertEquals(1, storage.searchExplicitHistory("Monday").episodes.size)
                android.database.sqlite.SQLiteDatabase.openDatabase(database.path, null, 0).use { db ->
                    db.rawQuery("SELECT count(*) FROM source_events", null).use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
                    db.execSQL("DROP TRIGGER reject_source")
                }
                assertEquals(SourceArchiveOutcome.STORED, storage.captureSource(input("failed", "We discussed amber notebooks")).outcome)
            }
            var reads = 0
            SQLiteMemoryStore(database, canReadSourceText = { ++reads == 1 }, archiveClock = { now }).use { storage ->
                assertEquals(SourceArchiveOutcome.LOCKED, storage.searchExplicitHistory("amber").outcome)
            }
            SQLiteMemoryStore(database).use { assertEquals(SourceArchiveOutcome.LOCKED, it.searchExplicitHistory("amber").outcome) }
        } finally { root.deleteRecursively() }
    }

    @Test fun test39_referencePdfExtractionAndPendingAcknowledgmentUseReleaseCode() {
        val bytes = instrumentation.context.assets.open("reference-fixture.pdf").use { it.readBytes() }
        val text = com.battlesbudz.jarvis.v2.ai.ReferencePdfText.read(context, bytes)
        assertTrue(text.contains("Fermented plant juice (FPJ)"))
        assertTrue(text.contains("brown sugar"))
        try {
            com.battlesbudz.jarvis.v2.ai.ReferencePdfText.read(context, "not a PDF".toByteArray())
            fail("Malformed PDF must not become reference evidence")
        } catch (_: java.io.IOException) { }
        val root = File(context.cacheDir, "ack-receipt-${System.nanoTime()}").apply { mkdirs() }
        try {
            val os = MemoryOs(File(root, "memory.json"))
            val receipt = ConversationMemory(os).capture(FinalMemoryInput("ack", "conversation", source = ConversationMemorySource.TEXT,
                text = "Remember I like apricots", capturedAtMs = System.currentTimeMillis()))
            assertEquals(MemoryReviewStatus.PENDING, receipt.memory!!.reviewStatus)
            assertEquals("I've added a pending memory for your review. It isn't approved yet.", MemoryCaptureAcknowledgment.reply(receipt))
            assertTrue(os.contextPacket("what fruit do I like?", 1200).packet!!.memories.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun test40_phoneActionJournalPreservesReceiptsAndFencesUnknownEffects() {
        val directory = File(context.noBackupFilesDir, "release-action-journal").apply { deleteRecursively(); mkdirs() }
        val file = File(directory, "attempts.json")
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val pipeline = JournaledActionPipeline(ledger, AndroidMobileActionExecutor(context))
        try {
            assertTrue(pipeline.execute(ActionRequest("set_volume", mapOf("level" to "40"))).succeeded)
            val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            assertEquals((audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .4).roundToInt(), actual)
            val receipt = ToolTaskLedger(FileToolTaskStore(file)).snapshot().single()
            assertEquals(ToolTaskState.SUCCEEDED, receipt.state)
            assertEquals(ExecutionResult.Outcome.SUCCEEDED, receipt.resultOutcome)

            // Simulate process loss after persisted intent, without claiming a second effect ran.
            val unfinished = ledger.create(ActionRequest("set_volume", mapOf("level" to "90")), ToolTaskState.RUNNING)
            val reopened = ToolTaskLedger(FileToolTaskStore(file))
            reopened.recoverAfterRestart()
            assertEquals(receipt, reopened.get(receipt.id))
            assertEquals(ToolTaskState.UNKNOWN_OUTCOME, reopened.get(unfinished.id)?.state)
            assertNull(reopened.transition(unfinished.id, unfinished.generation, ToolTaskState.RUNNING))
            assertEquals(actual, audio.getStreamVolume(AudioManager.STREAM_MUSIC))

            file.writeText("corrupt")
            val rejected = JournaledActionPipeline(reopened, AndroidMobileActionExecutor(context))
                .execute(ActionRequest("set_volume", mapOf("level" to "90")))
            assertFalse(rejected.succeeded)
            assertEquals(actual, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals("corrupt", file.readText())
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
            directory.deleteRecursively()
        }
    }

    @Test fun test41_taskRecoveryAndExactApprovalPreserveAndroidEffects() {
        val directory = File(context.noBackupFilesDir, "release-task-owner").apply { deleteRecursively(); mkdirs() }
        val file = File(directory, "journal.json")
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            val ledger = ToolTaskLedger(FileToolTaskStore(file))
            val group = ledger.admit(listOf(ActionRequest("set_volume", mapOf("level" to "40")), ActionRequest("read_battery")), "release-thread")
            val pipeline = JournaledActionPipeline(ledger, AndroidMobileActionExecutor(context))
            assertTrue(pipeline.executeAttempt(checkNotNull(ledger.get(group.attemptIds[0]))).succeeded)
            val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val reopened = ToolTaskLedger(FileToolTaskStore(file))
            reopened.recoverAfterRestart()
            assertEquals(ToolTaskState.READY, reopened.get(group.attemptIds[1])?.state)
            val recovered = JournaledActionPipeline(reopened, AndroidMobileActionExecutor(context))
            assertFalse(recovered.executeAttempt(checkNotNull(reopened.get(group.attemptIds[0]))).succeeded)
            assertTrue(recovered.executeAttempt(checkNotNull(reopened.get(group.attemptIds[1]))).succeeded)
            assertEquals(actual, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            val approvalStore = ActionApprovalStore(FileToolTaskStore(file))
            val gate = ActionDispatchGate(approvalStore, reopened)
            val guarded = reopened.admit(listOf(ActionRequest("set_volume", mapOf("level" to "90"))), "release-thread", ToolAuthority.EXACT_APPROVAL)
            val pending = gate.prepare(checkNotNull(reopened.get(guarded.attemptIds.single())))
            assertFalse(recovered.executeAttempt(pending.task).succeeded)
            assertNull(gate.authorize(pending, schemaVersion = MobileToolCatalog.VERSION + 1))
            assertEquals(actual, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(ApprovalDecision.DENIED, approvalStore.deny(pending.approval.id))
            assertEquals(ToolTaskState.CANCELLED, ToolTaskLedger(FileToolTaskStore(file)).get(pending.task.id)?.state)
            assertEquals(ApprovalDecision.DENIED, ActionApprovalStore(FileToolTaskStore(file)).get(pending.approval.id)?.decision)
            assertEquals(2, reopened.journal().events.count { it.kind == ToolTaskEventKind.DISPATCHED })
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
            directory.deleteRecursively()
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test42_taskPanelShowsExactChoiceAndReconcilesWithoutRetry() {
        val file = File(context.cacheDir, "release-task-panel.json").apply { delete() }
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val approvals = ActionApprovalStore(FileToolTaskStore(file))
        val gate = ActionDispatchGate(approvals, ledger)
        val group = ledger.admit(listOf(ActionRequest("set_volume", mapOf("level" to "25"))), "panel-thread", ToolAuthority.EXACT_APPROVAL)
        val pending = gate.prepare(checkNotNull(ledger.get(group.attemptIds.single())))
        val unknown = ledger.create(ActionRequest("read_battery"), ToolTaskState.RUNNING)
        val legacy = ledger.create(ActionRequest("read_battery"))
        ledger.recoverAfterRestart()
        val journal = MutableStateFlow<ToolTaskJournal?>(ledger.journal())
        val effects = AtomicInteger(0)
        val decide: (String, Long, String) -> Unit = { id, generation, command ->
            val a = ledger.get(id)?.takeIf { it.generation == generation }
            if (a != null) {
                when (command) {
                    "approve" -> JournaledActionPipeline(ledger, executor = MobileActionExecutor { effects.incrementAndGet(); ExecutionResult(true, "25%") })
                        .executeAttempt(a, a.approvalId?.let { approvals.get(it) })
                    "deny" -> a.approvalId?.let { approvals.deny(it) }
                    "checked" -> ledger.reconcileUnknown(id, generation)
                    "cancel" -> ledger.cancelLegacyAttempt(id, generation)
                }
                journal.value = ledger.journal()
            }
        }
        try {
            activity.onActivity { host -> host.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    val snapshot by journal.collectAsState()
                    com.battlesbudz.jarvis.v2.ui.PhoneTaskPanel(snapshot, "panel-thread", null, decide)
                } }
            } }
            find(By.res("phone_tasks_open")).click()
            assertNotNull(find(By.text("Set media volume to 25%")))
            assertEquals(0, effects.get())
            captureEvidence("phone_task_approval")
            clickEnabled(By.res("task_approve_${pending.task.id}"))
            assertTrue("Approved choice must finish before checking its effect", device.wait(
                Until.gone(By.res("task_approve_${pending.task.id}")), 15_000
            ))
            assertEquals(1, effects.get())
            assertEquals(ToolTaskState.SUCCEEDED, ledger.get(pending.task.id)?.state)
            clickEnabled(By.res("task_checked_${unknown.id}"))
            assertTrue("Reconciliation must finish before reopening the ledger", device.wait(
                Until.gone(By.res("task_checked_${unknown.id}")), 15_000
            ))
            assertTrue(checkNotNull(ToolTaskLedger(FileToolTaskStore(file)).get(unknown.id)).reconciled)
            assertEquals(1, effects.get())
            clickEnabled(By.res("task_cancel_${legacy.id}"))
            assertTrue("Cancellation must finish before reopening the ledger", device.wait(
                Until.gone(By.res("task_cancel_${legacy.id}")), 15_000
            ))
            assertEquals(ToolTaskState.CANCELLED, ToolTaskLedger(FileToolTaskStore(file)).get(legacy.id)?.state)
            val declined = ledger.admit(listOf(ActionRequest("read_battery")), "panel-thread", ToolAuthority.EXACT_APPROVAL)
            val choice = gate.prepare(checkNotNull(ledger.get(declined.attemptIds.single())))
            journal.value = ledger.journal()
            clickEnabled(By.res("task_deny_${choice.task.id}"))
            assertTrue("Decline must finish before reopening the ledger", device.wait(
                Until.gone(By.res("task_deny_${choice.task.id}")), 15_000
            ))
            assertEquals(ToolTaskState.CANCELLED, ToolTaskLedger(FileToolTaskStore(file)).get(choice.task.id)?.state)
            assertEquals(1, effects.get())
        } finally { file.delete() }
    }

    @Test fun test43_conditionalAndCommaPlansUseRealAndroidWithoutModelCalls() = runBlocking {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { true })
        val battery = MobileActionPipeline(executor = executor).execute(ActionRequest("read_battery"))
        assertTrue(battery.succeeded)
        assertEquals(context.getSystemService(android.os.BatteryManager::class.java)
            .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY), battery.batteryPercent)
        val percent = checkNotNull(battery.batteryPercent)
        val directory = File(context.noBackupFilesDir, "conditional-journey").apply { deleteRecursively(); mkdirs() }
        val ledger = ToolTaskLedger(FileToolTaskStore(File(directory, "journal.json")))
        try {
            val runner = ActionTurnRunner(executor)
            val plan = ActionTurnPlan.parse("If my battery is at least $percent%, set volume to 40%, tell me my battery percentage, and open Settings") as ActionTurnPlan.Ready
            var group: ToolTaskGroup? = null
            var index = 0
            val pipeline = JournaledActionPipeline(ledger, executor)
            val outcome = runner.runValidated(plan, dispatch = { request ->
                if (group == null) group = ledger.admit(plan.steps.map { it.request }, "conditional-thread", resumeAfterRestart = false)
                pipeline.executeBound(checkNotNull(ledger.get(group!!.attemptIds[index++])), request)
            }, checkBattery = { MobileActionPipeline(executor = executor).execute(ActionRequest("read_battery")) })
            // The trailing launch is submitted but unconfirmed, so the turn
            // reports honestly instead of completing: every step dispatched,
            // none of them over-claimed.
            assertFalse("the turn must not complete on an unconfirmed launch: ${outcome.message}", outcome.completed)
            assertEquals(true, outcome.conditionMatched)
            assertEquals(listOf("set_volume", "read_battery", "open_app"), outcome.receipts.map { it.request.name })
            assertEquals(ExecutionResult.Outcome.UNKNOWN_COMPLETION, outcome.receipts.last().result.outcome)
            assertTrue("the last receipt must stay honest: ${outcome.receipts.last().result.message}",
                outcome.receipts.last().result.message.contains("could not be confirmed"))
            assertEquals((audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .4).roundToInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(percent, outcome.receipts[1].result.batteryPercent)
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 10_000))
            device.pressBack()
            activity.recreate()
            val falseCondition = ActionTurnPlan.parse("If my battery is below 0 set volume to 90%") as ActionTurnPlan.Ready
            val skipped = runner.runValidated(falseCondition, dispatch = { error("False condition dispatched") },
                checkBattery = { MobileActionPipeline(executor = executor).execute(ActionRequest("read_battery")) })
            assertTrue(skipped.completed)
            assertEquals(false, skipped.conditionMatched)
            assertTrue(skipped.receipts.isEmpty())
            assertEquals((audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .4).roundToInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(2, ledger.snapshot().count { it.state == ToolTaskState.SUCCEEDED })
            assertEquals("the unconfirmed launch must be journaled as unknown, never succeeded",
                1, ledger.snapshot().count { it.state == ToolTaskState.UNKNOWN_OUTCOME })
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
            directory.deleteRecursively()
        }
    }

    @Test fun test44_backgroundAssistantSubmitsLaunchAndStopsPlanWithoutTap() = runBlocking {
        val service = context.packageName + "/com.battlesbudz.jarvis.v2.assistant.JarvisInteractionService"
        val keys = listOf("assistant", "voice_interaction_service", "voice_recognition_service")
        val saved = keys.associateWith { device.executeShellCommand("settings get secure $it").trim() }
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val diagnostics = mutableListOf<String>()
        try {
            // Configure the real system assistant on this disposable emulator, rather than
            // giving the executor a fake foreground/launch exemption or using shell to launch.
            device.executeShellCommand("settings put secure assistant $service")
            device.executeShellCommand("settings put secure voice_interaction_service $service")
            val readyBy = SystemClock.elapsedRealtime() + 10_000
            while (!com.battlesbudz.jarvis.v2.assistant.JarvisInteractionService.isReady(context) &&
                SystemClock.elapsedRealtime() < readyBy) SystemClock.sleep(100)
            assertTrue("System must bind the selected assistant", com.battlesbudz.jarvis.v2.assistant.JarvisInteractionService.isReady(context))
            device.pressHome()
            assertFalse(device.hasObject(By.pkg(context.packageName)))
            // Do not let the recent-foreground grace period prove the assistant route.
            SystemClock.sleep(11_000)
            val executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { false }, onDiagnostic = diagnostics::add)
            val plan = ActionTurnPlan.parse("set volume to 40% then open Settings then tell me my battery") as ActionTurnPlan.Ready
            val outcome = ActionTurnRunner(executor).runValidated(plan,
                dispatch = { request -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    MobileActionPipeline(executor = executor).execute(request)
                } }, checkBattery = { error("No conditional reading requested") })
            // The assistant route submits the launch but cannot observe the
            // destination, so the plan stops here instead of treating the
            // battery read as success-backed by an unconfirmed opening.
            assertFalse("the plan must not complete on an unconfirmed launch: ${outcome.message}", outcome.completed)
            assertEquals(listOf("set_volume", "open_app"), outcome.receipts.map { it.request.name })
            assertEquals(ExecutionResult.Outcome.UNKNOWN_COMPLETION, outcome.receipts.last().result.outcome)
            assertTrue("the assistant receipt must stay honest: ${outcome.receipts.last().result.message}",
                outcome.receipts.last().result.message.contains("could not be confirmed"))
            assertEquals((audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * .4).roundToInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertTrue("Background app command must really open Settings without notification interaction",
                device.wait(Until.hasObject(By.pkg("com.android.settings")), 10_000))
            assertTrue(diagnostics.any { "route=selected_assistant visible=false binding=true" in it })
            captureEvidence("background_assistant_first_launch")
            // A second command while Jarvis is still hidden must not fall back to a tap.
            device.pressHome()
            val again = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                MobileActionPipeline(executor = executor).execute(ActionRequest("open_app", mapOf("app" to "Settings")))
            }
            assertEquals("the assistant submission stays unconfirmed, never success",
                ExecutionResult.Outcome.UNKNOWN_COMPLETION, again.outcome)
            assertFalse(again.succeeded)
            assertFalse(again.message.contains("tap", ignoreCase = true))
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 10_000))
            val missing = MobileActionPipeline(executor = executor).execute(ActionRequest("open_app", mapOf("app" to "jarvis nonexistent fixture app")))
            assertFalse(missing.succeeded)
            assertTrue(missing.message.contains("could not find", ignoreCase = true))
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
            for ((key, value) in saved) {
                if (value == "null" || value.isBlank()) device.executeShellCommand("settings delete secure $key")
                else {
                    require(value.matches(Regex("[a-zA-Z0-9_./:]+")))
                    device.executeShellCommand("settings put secure $key $value")
                }
            }
            // Settings is intentionally foreground and Jarvis is STOPPED. Recreating the
            // stopped activity here waits for RESUMED and masks the real test result.
            // @After closes this scenario; the next journey launches its own activity.
        }
    }

    /** Benchmark observations own their explicit deadline on every supported API. */
    private inline fun <T> observeBenchmarkNavigation(block: () -> T): T {
        val configuration = Configurator.getInstance()
        val savedIdleTimeout = configuration.getWaitForIdleTimeout()
        configuration.setWaitForIdleTimeout(0)
        return try { block() } finally { configuration.setWaitForIdleTimeout(savedIdleTimeout) }
    }

    private fun benchmarkRefreshNavigationCache() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            clearNavigationCache()
        } else {
            // Android 10/11 UiAutomation.setServiceInfo clears the client cache.
            // Reapply the current info unchanged, preserving every service flag.
            val automation = instrumentation.uiAutomation
            val info = checkNotNull(automation.serviceInfo) { "Benchmark accessibility service is unavailable" }
            automation.serviceInfo = info
        }
    }

    private fun benchmarkFindNavigationObject(selector: BySelector): UiObject2? {
        benchmarkRefreshNavigationCache()
        return device.findObject(selector)
    }

    private fun benchmarkObserveTarget(selector: BySelector, deadline: Long): UiObject2? {
        if (SystemClock.uptimeMillis() >= deadline) return null
        val first = benchmarkFindNavigationObject(selector)
        if (first != null) return first
        // A missing descendant may be awaiting accessibility settlement. Refresh
        // and observe once more without spending another gesture or new deadline.
        val remaining = deadline - SystemClock.uptimeMillis()
        if (remaining <= 0) return null
        val pause = remaining.coerceAtMost(100)
        SystemClock.sleep(pause)
        if (SystemClock.uptimeMillis() >= deadline) return null
        val refreshed = benchmarkFindNavigationObject(selector)
        android.util.Log.i("JarvisVerification", "benchmark_navigation_missing_observation selector=$selector api=${android.os.Build.VERSION.SDK_INT} pauseMs=$pause refreshedTarget=${refreshed?.visibleBounds} remainingMs=${deadline - SystemClock.uptimeMillis()}")
        return refreshed
    }

    /** Benchmark-only navigation: Compose may export descendants wholly outside a LazyColumn. */
    private fun benchmarkScrollAncestor(control: UiObject2): UiObject2? {
        benchmarkRefreshNavigationCache()
        var ancestor = control.parent
        repeat(40) {
            val current = ancestor ?: return null
            if (current.isScrollable) return current
            ancestor = current.parent
        }
        throw AssertionError("Benchmark control has an unexpectedly deep ancestor chain")
    }

    private fun benchmarkHasSafeBounds(control: UiObject2): Boolean {
        benchmarkRefreshNavigationCache()
        val bounds = control.visibleBounds
        val safeInset = 24
        val edgeRoom = 12
        val viewport = android.graphics.Rect(safeInset, safeInset,
            device.displayWidth - safeInset, device.displayHeight - safeInset)
        var ancestor = control.parent
        var depth = 0
        while (ancestor != null && depth++ < 40) {
            val current = ancestor
            if (current.isScrollable && !viewport.intersect(current.visibleBounds)) viewport.setEmpty()
            ancestor = current.parent
        }
        if (ancestor != null) return false
        // Requiring vertical room also rejects a partially clipped descendant whose
        // accessibility rectangle was truncated exactly at the viewport edge.
        return bounds.width() > 0 && bounds.height() > 0 && viewport.contains(bounds) &&
            bounds.top >= viewport.top + edgeRoom && bounds.bottom <= viewport.bottom - edgeRoom &&
            bounds.centerX() >= viewport.left + safeInset && bounds.centerX() <= viewport.right - safeInset &&
            bounds.centerY() >= viewport.top + safeInset && bounds.centerY() <= viewport.bottom - safeInset
    }

    /** Observe actual visible content; Compose need not emit a UiAutomator scroll event. */
    private fun benchmarkViewportSignature(list: UiObject2): String {
        clearNavigationCache()
        val viewport = list.visibleBounds
        val rows = mutableListOf<String>()
        fun visit(node: UiObject2, depth: Int) {
            if (depth > 12 || rows.size >= 256) return
            val bounds = node.visibleBounds
            if (bounds.width() <= 0 || bounds.height() <= 0 ||
                !android.graphics.Rect.intersects(viewport, bounds)) return
            rows.add("${node.resourceName}|${node.text}|$bounds")
            node.children.forEach { visit(it, depth + 1) }
        }
        list.children.forEach { visit(it, 0) }
        return rows.joinToString("\n")
    }

    private data class BenchmarkViewportObservation(val signature: String, val receipt: String)

    /** Keep raw content only in memory; diagnostic rows expose fixed identities and geometry. */
    private fun benchmarkViewportObservation(list: UiObject2): BenchmarkViewportObservation {
        benchmarkRefreshNavigationCache()
        val viewport = list.visibleBounds
        val rows = mutableListOf<String>()
        val visibleRows = mutableListOf<Pair<android.graphics.Rect, String>>()
        val visibleMetrics = mutableListOf<String>()
        val metricKeys = setOf("process_cpu_work_ms", "turn_total_ms", "model_load_ms", "llm_setup_ms",
            "attachment_preparation_ms", "memory_retrieval_ms", "reference_lookup_ms", "tool_execution_ms",
            "audio_fallback_ms", "gemma_final_caption_ms", "endpoint_to_preparation_sealed_ms",
            "request_processing_ms", "microphone_ready_ms", "first_reply_text_ready_ms",
            "endpoint_to_first_answer_text_ready_ms", "endpoint_to_first_answer_text_ms",
            "endpoint_to_first_answer_playback_ms", "speech_end_to_first_answer_text_ms",
            "speech_end_to_first_answer_playback_ms", "reply_dispatch_to_first_answer_text_ms",
            "asr_load_ms", "asr_capture_ready_ms", "asr_input_audio_ms", "asr_decode_work_ms",
            "asr_finalization_ms", "asr_first_partial_ms", "asr_endpoint_delay_ms", "asr_max_decode_chunk_ms",
            "asr_max_backlog_ms", "asr_realtime_factor", "tts_load_ms", "tts_first_text_to_pcm_ms",
            "tts_first_text_to_playback_ms", "tts_synthesis_ms", "tts_generated_audio_ms", "tts_queue_wait_ms",
            "tts_playback_starvation_ms", "tts_underruns", "tts_realtime_factor", "asr_wer", "asr_cer")
        fun visit(node: UiObject2, depth: Int) {
            if (depth > 12 || rows.size >= 256) return
            val bounds = node.visibleBounds
            if (bounds.width() <= 0 || bounds.height() <= 0 ||
                !android.graphics.Rect.intersects(viewport, bounds)) return
            val resource = node.resourceName
            val text = node.text
            val children = node.children
            rows.add("$resource|$text|$bounds")
            if (resource != null || text != null || children.isEmpty()) {
                val tag = resource?.substringAfterLast('/')?.let {
                    when {
                        it.startsWith("pipeline_benchmark_sample_") -> "pipeline_benchmark_sample"
                        it in setOf("pipeline_benchmark_reference", "pipeline_benchmark_quality_save",
                            "pipeline_benchmark_quality_verify", "pipeline_benchmark_copy_json",
                            "pipeline_benchmark_reset", "pipeline_benchmark_status") -> it
                        it.matches(Regex("benchmark_quality_(task|intent|factuality)_(PASS|FAIL|NOT_EVALUATED)")) -> it
                        it.matches(Regex("benchmark_environment_(UNSPECIFIED|QUIET|NOISY)")) -> it
                        else -> null
                    }
                }
                val metric = text?.substringBefore(": ")?.takeIf { it in metricKeys }
                if (metric != null) visibleMetrics.add("$metric:$bounds")
                visibleRows.add(android.graphics.Rect(bounds) to "${node.className}:${tag ?: metric ?: "other"}:$bounds")
            }
            children.forEach { visit(it, depth + 1) }
        }
        list.children.forEach { visit(it, 0) }
        val ordered = visibleRows.sortedWith(compareBy({ it.first.top }, { it.first.bottom }, { it.first.left }))
        return BenchmarkViewportObservation(rows.joinToString("\n"),
            "rows=${ordered.size},first=${ordered.firstOrNull()?.second},last=${ordered.lastOrNull()?.second},metrics=$visibleMetrics,truncated=${rows.size >= 256}")
    }

    private fun benchmarkScrollList(): UiObject2? {
        val screen = benchmarkFindNavigationObject(By.res("pipeline_benchmark_screen")) ?: return null
        benchmarkRefreshNavigationCache()
        return screen.findObject(By.scrollable(true))
    }

    /** Text assertions observe rendered content; they do not require room for a tap. */
    private fun benchmarkHasVisibleTextBounds(control: UiObject2): Boolean {
        benchmarkRefreshNavigationCache()
        val bounds = control.visibleBounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val viewport = android.graphics.Rect(0, 0, device.displayWidth, device.displayHeight)
        var ancestor = control.parent
        var depth = 0
        while (ancestor != null && depth++ < 40) {
            val current = ancestor
            if (current.isScrollable && !viewport.intersect(current.visibleBounds)) return false
            ancestor = current.parent
        }
        return ancestor == null && viewport.contains(bounds)
    }

    /** A real half-viewport stroke with one elapsed-time endpoint hold. */
    private fun benchmarkHeldMetricSwipe(swipeX: Int, fromY: Int, toY: Int, deadline: Long): Boolean {
        if (SystemClock.uptimeMillis() >= deadline) return false
        val downTime = SystemClock.uptimeMillis()
        var currentY = fromY
        var needsCancel = false
        fun inject(action: Int): Boolean {
            val properties = android.view.MotionEvent.PointerProperties().apply {
                id = 0
                toolType = Configurator.getInstance().toolType
            }
            val coords = android.view.MotionEvent.PointerCoords().apply {
                x = swipeX.toFloat()
                y = currentY.toFloat()
                pressure = 1f
                size = 1f
            }
            val event = android.view.MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            return try {
                if (action != android.view.MotionEvent.ACTION_CANCEL && SystemClock.uptimeMillis() >= deadline)
                    false else instrumentation.uiAutomation.injectInputEvent(event, true)
            }
                finally { event.recycle() }
        }
        try {
            if (SystemClock.uptimeMillis() >= deadline) return false
            // A rejected or interrupted injection can leave an uncertain touch state.
            needsCancel = true
            if (!inject(android.view.MotionEvent.ACTION_DOWN) || SystemClock.uptimeMillis() >= deadline)
                return false
            for (step in 1..12) {
                if (SystemClock.uptimeMillis() >= deadline) return false
                currentY = fromY + (toY - fromY) * step / 12
                if (!inject(android.view.MotionEvent.ACTION_MOVE) || SystemClock.uptimeMillis() >= deadline)
                    return false
                if (step < 12) {
                    if (deadline - SystemClock.uptimeMillis() <= 5) return false
                    SystemClock.sleep(5)
                    if (SystemClock.uptimeMillis() >= deadline) return false
                }
            }
            val holdStarted = SystemClock.uptimeMillis()
            if (deadline - holdStarted <= 250) return false
            // Repeated stationary MOVE injections each synchronize with the platform;
            // they are not an elapsed-time timer. Keep the contact down for 250 ms.
            SystemClock.sleep(250)
            if (SystemClock.uptimeMillis() >= deadline) return false
            val heldMs = SystemClock.uptimeMillis() - holdStarted
            if (!inject(android.view.MotionEvent.ACTION_UP) || SystemClock.uptimeMillis() >= deadline)
                return false
            needsCancel = false
            android.util.Log.i("JarvisVerification", "benchmark_metric_swipe_completed x=$swipeX fromY=$fromY toY=$toY moves=12 heldMs=$heldMs gestureMs=${SystemClock.uptimeMillis() - downTime} remainingMs=${deadline - SystemClock.uptimeMillis()}")
            return true
        } finally {
            if (needsCancel) {
                // Cleanup must also run after expiry; a late UP never qualifies the stroke.
                try {
                    check(inject(android.view.MotionEvent.ACTION_CANCEL)) { "Benchmark swipe cancellation failed" }
                } catch (failure: RuntimeException) {
                    android.util.Log.w("JarvisVerification", "Benchmark swipe cancellation failed", failure)
                }
            }
        }
    }

    private fun benchmarkFindVisible(selector: BySelector, towardTop: Boolean, inDialog: Boolean,
        deadline: Long, swipes: AtomicInteger, requireSafeTapBounds: Boolean = true,
        holdTextDiscovery: Boolean = false, sparseHeldTextDiscovery: Boolean = false,
        alignForTap: Boolean = false): UiObject2 {
        fun hasRequiredBounds(control: UiObject2): Boolean = if (requireSafeTapBounds)
            benchmarkHasSafeBounds(control) else benchmarkHasVisibleTextBounds(control)
        var direction = if (towardTop) Direction.UP else Direction.DOWN
        var reversedAtEdge = false
        var unchangedGestures = 0
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                val control = benchmarkObserveTarget(selector, deadline)
                if (control != null && hasRequiredBounds(control)) {
                    if (SystemClock.uptimeMillis() < deadline) return control
                    break
                }
                if (inDialog) {
                    SystemClock.sleep(100)
                    continue
                }
                val list = if (control != null) benchmarkScrollAncestor(control)
                    else benchmarkScrollList()
                if (list == null) {
                    SystemClock.sleep(100)
                    continue
                }
                if (swipes.incrementAndGet() > 14) break
                val viewport = list.visibleBounds
                check(viewport.width() > 0 && viewport.height() > 96) { "Benchmark scroll viewport is unavailable" }
                val bounds = control?.visibleBounds
                // An empty accessibility rectangle has no useful direction. UiAutomator
                // also retains off-viewport bounds when clipping has no intersection.
                val knownBounds = bounds?.takeIf { it.width() > 0 && it.height() > 0 }
                val alignmentInset = 24
                if (knownBounds != null) direction = when {
                    knownBounds.top < viewport.top + alignmentInset && knownBounds.bottom <= viewport.bottom - alignmentInset -> Direction.UP
                    knownBounds.bottom > viewport.bottom - alignmentInset && knownBounds.top >= viewport.top + alignmentInset -> Direction.DOWN
                    else -> if (knownBounds.centerY() < viewport.centerY()) Direction.UP else Direction.DOWN
                }
                // Dispatch physical gestures as the older release journeys do. The
                // UiObject2.scroll result conflates a missing accessibility event with
                // an actual edge, so determine progress from fresh visible content.
                val before = benchmarkViewportObservation(list)
                // Avoid the unfolded hinge and the system clipboard preview at
                // the lower left, preserving overlap and the same safe-tap checks.
                val swipeX = viewport.left + viewport.width() * 3 / 4
                val lowY = viewport.top + viewport.height() * 85 / 100
                val highY = viewport.top + viewport.height() * 15 / 100
                // Once the target overlaps the viewport, align it instead of taking
                // another full search stroke. Aim 24 pixels inside the viewport,
                // add the same room, and cap each adjustment at one fifth of it.
                val fineLimit = viewport.height() / 5
                // API 30 and modern accessibility can retain a positive rectangle just outside
                // the viewport. Align that nearby known target with the same bounded
                // fine gesture instead of another full search stroke. Fresh safe bounds still
                // authorize the tap; unknown or distant targets retain discovery.
                val nearbyOutside = (android.os.Build.VERSION.SDK_INT == 30 || android.os.Build.VERSION.SDK_INT >= 35) &&
                    knownBounds != null &&
                    knownBounds.left < viewport.right && knownBounds.right > viewport.left &&
                    ((knownBounds.bottom <= viewport.top && viewport.top - knownBounds.bottom <= fineLimit) ||
                        (knownBounds.top >= viewport.bottom && knownBounds.top - viewport.bottom <= fineLimit))
                val fine = knownBounds != null &&
                    (android.graphics.Rect.intersects(viewport, knownBounds) || nearbyOutside)
                val gap = if (knownBounds == null) 0 else if (direction == Direction.UP)
                    viewport.top + alignmentInset - knownBounds.top else knownBounds.bottom - (viewport.bottom - alignmentInset)
                val hold = (holdTextDiscovery || sparseHeldTextDiscovery) && !fine
                // A small visible fragment at the scroll edge does not reveal the
                // control's full vertical extent. For a pending physical tap, move
                // it inward with the existing fine cap instead of trusting that
                // clipped edge as the full target position. Read-only navigation
                // and nonintersecting targets retain their original gestures.
                val edgeFragmentForTap = android.os.Build.VERSION.SDK_INT >= 35 && alignForTap &&
                    requireSafeTapBounds &&
                    fine && knownBounds != null && knownBounds.height() < fineLimit &&
                    android.graphics.Rect.intersects(viewport, knownBounds) &&
                    knownBounds.left >= viewport.left && knownBounds.right <= viewport.right &&
                    ((direction == Direction.UP && knownBounds.top <= viewport.top) ||
                        (direction == Direction.DOWN && knownBounds.bottom >= viewport.bottom))
                val stroke = if (edgeFragmentForTap) fineLimit
                    else if (fine) (gap + alignmentInset).coerceIn(48.coerceAtMost(fineLimit), fineLimit)
                    else if (hold) viewport.height() / 2 else lowY - highY
                val fromY = if (direction == Direction.DOWN) lowY else highY
                val toY = fromY + if (direction == Direction.DOWN) -stroke else stroke
                val steps = if (hold && sparseHeldTextDiscovery) 12 else if (hold) 51 else if (fine) 24 else 12
                val scrollStarted = SystemClock.uptimeMillis()
                if (SystemClock.uptimeMillis() >= deadline) break
                android.util.Log.i("JarvisVerification", "benchmark_navigation_dispatch selector=$selector direction=$direction mode=${if (hold) "held_text" else if (fine) "fine" else "blind"} requireSafeTapBounds=$requireSafeTapBounds beforeTarget=$bounds beforeViewport=$viewport beforeRows=${before.receipt} stroke=$stroke x=$swipeX fromY=$fromY toY=$toY steps=$steps gestures=${swipes.get()} reversedAtEdge=$reversedAtEdge remainingMs=${deadline - scrollStarted}")
                // Overlap successive discovery viewports and hold the endpoint
                // for 250 ms, as UiAutomator's own scroll gesture does. This is a
                // bounded settlement trial, not proof of the stale-node cause.
                // The public point path still requires successful real injection;
                // viewport observations determine progress, not scroll events.
                val dispatched = if (hold && sparseHeldTextDiscovery)
                    benchmarkHeldMetricSwipe(swipeX, fromY, toY, deadline)
                    else if (hold) device.swipe(arrayOf(
                    android.graphics.Point(swipeX, fromY), android.graphics.Point(swipeX, toY),
                    android.graphics.Point(swipeX, toY)), steps)
                    else device.swipe(swipeX, fromY, swipeX, toY, steps)
                check(dispatched) {
                    "Benchmark swipe dispatch failed"
                }
                device.waitForIdle((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1))
                if (SystemClock.uptimeMillis() >= deadline) break
                SystemClock.sleep((deadline - SystemClock.uptimeMillis()).coerceIn(0, 150))
                val fresh = benchmarkObserveTarget(selector, deadline)
                val freshReady = fresh != null && hasRequiredBounds(fresh)
                val freshBounds = fresh?.visibleBounds
                val freshList = benchmarkScrollList()
                val freshViewport = freshList?.visibleBounds
                val after = if (freshReady) null else freshList?.let { benchmarkViewportObservation(it) }
                android.util.Log.i("JarvisVerification", "benchmark_navigation_observed selector=$selector direction=$direction mode=${if (hold) "held_text" else if (fine) "fine" else "blind"} requireSafeTapBounds=$requireSafeTapBounds afterTarget=$freshBounds afterViewport=$freshViewport afterRows=${if (freshReady) "not_required_ready" else after?.receipt} ready=$freshReady gestures=${swipes.get()} reversedAtEdge=$reversedAtEdge gestureMs=${SystemClock.uptimeMillis() - scrollStarted} remainingMs=${deadline - SystemClock.uptimeMillis()}")
                if (fresh != null && freshReady) {
                    if (SystemClock.uptimeMillis() < deadline) return fresh
                    break
                }
                val moved = after != null && after.signature != before.signature
                unchangedGestures = if (moved) 0 else unchangedGestures + 1
                android.util.Log.i("JarvisVerification", "benchmark_navigation selector=$selector direction=$direction moved=$moved unchangedGestures=$unchangedGestures gestures=${swipes.get()} gestureMs=${SystemClock.uptimeMillis() - scrollStarted} remainingMs=${deadline - SystemClock.uptimeMillis()}")
                // Require two observed stationary gestures before reversing once.
                if (after != null && unchangedGestures >= 2) {
                    if (reversedAtEdge) break
                    direction = if (direction == Direction.UP) Direction.DOWN else Direction.UP
                    reversedAtEdge = true
                    unchangedGestures = 0
                }
            } catch (_: StaleObjectException) {
                // Re-query after scrolling or recomposition, before dispatching any tap.
            }
        }
        throw AssertionError(if (requireSafeTapBounds) "Benchmark control did not become fully visible: $selector"
            else "Benchmark text did not become visible: $selector")
    }

    private fun benchmarkScrollTo(selector: BySelector, towardTop: Boolean = false,
        holdDiscovery: Boolean = false, sparseHeldDiscovery: Boolean = false): UiObject2 = observeBenchmarkNavigation {
        benchmarkFindVisible(selector, towardTop, false, SystemClock.uptimeMillis() + 15_000, AtomicInteger(),
            holdTextDiscovery = holdDiscovery, sparseHeldTextDiscovery = sparseHeldDiscovery)
    }

    private fun benchmarkRevealText(text: String, holdDiscovery: Boolean = false): UiObject2 = observeBenchmarkNavigation {
        benchmarkFindVisible(By.text(text), false, false, SystemClock.uptimeMillis() + 15_000,
            AtomicInteger(), requireSafeTapBounds = false, holdTextDiscovery = holdDiscovery)
    }

    private fun benchmarkClickEnabled(selector: BySelector, towardTop: Boolean = false, inDialog: Boolean = false,
        holdDiscovery: Boolean = false, sparseHeldDiscovery: Boolean = false) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        val swipes = AtomicInteger()
        while (SystemClock.uptimeMillis() < deadline) {
            val ready = observeBenchmarkNavigation {
                var ready: UiObject2? = null
                try {
                    val control = benchmarkFindVisible(selector, towardTop, inDialog, deadline, swipes,
                        holdTextDiscovery = holdDiscovery, sparseHeldTextDiscovery = sparseHeldDiscovery,
                        alignForTap = true)
                    if (control.isEnabled) {
                        val before = control.visibleBounds
                        device.waitForIdle((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1))
                        SystemClock.sleep(300)
                        val fresh = benchmarkFindNavigationObject(selector)
                        if (SystemClock.uptimeMillis() < deadline && fresh != null && fresh.isEnabled &&
                            benchmarkHasSafeBounds(fresh) && fresh.visibleBounds == before) ready = fresh
                    } else SystemClock.sleep(100)
                } catch (_: StaleObjectException) {
                    // Re-query stale nodes only before dispatching the physical tap.
                }
                ready
            }
            // The observation scope has restored the original idle timeout before any tap.
            if (SystemClock.uptimeMillis() >= deadline) break
            ready?.let {
                it.click() // Exactly one tap; a dispatch error is never retried.
                device.waitForIdle()
                return
            }
        }
        throw AssertionError("Benchmark control did not become stably enabled inside its viewport: $selector")
    }

    /** Controlled observations exercise the release dashboard; they are not model/audio performance evidence. */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test45_pipelineBenchmarksScoreOriginalAsrPersistAndExportRedactedEvidence() = runBlocking {
        val directory = File(context.cacheDir, "release-pipeline-benchmarks").apply { deleteRecursively(); mkdirs() }
        val fixtureContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val exportProbe = BenchmarkExportJourneyProbe(context)
        exportProbe.verifyRepeatedTransports()
        val store = AndroidPipelineBenchmarkStore(fixtureContext)
        val completedId = "completed-benchmark-fixture"
        val cancelledId = "cancelled-benchmark-fixture"
        val originalAsr = "alpha beta wrong delta"
        val reference = "alpha beta gamma delta"
        var renderGeneration = 0
        val committedGeneration = AtomicInteger(0)
        fun render(value: AndroidPipelineBenchmarkStore) {
            val generation = ++renderGeneration
            activity.onActivity { host -> host.setContent {
                // A restored store represents a new process: do not reuse the prior
                // screen's remembered selection, status or reference-dialog state.
                androidx.compose.runtime.key(value, generation) {
                    // setContent updates an existing ComposeView asynchronously. A screen
                    // tag can still belong to the old owner; SideEffect acknowledges this
                    // exact commit after old scopes/launchers are disposed and new ones registered.
                    androidx.compose.runtime.SideEffect { committedGeneration.set(generation) }
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.activity.compose.LocalActivityResultRegistryOwner provides exportProbe,
                        androidx.compose.ui.platform.LocalContext provides exportProbe.exportContext) {
                        MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                            PipelineBenchmarkScreen(value, onClose = {})
                        } }
                    }
                }
            } }
            exportProbe.awaitObserved("Benchmark screen owner replacement committed") {
                committedGeneration.get() == generation
            }
            device.waitForIdle()
            find(By.res("pipeline_benchmark_screen"))
        }
        try {
            store.clear()
            val provenance = store.captureProvenance(
                models = mapOf("asr" to PipelineBenchmarkModel("controlled-whisper-fixture", runtime = "fixture")),
                configuration = mapOf("evidence" to "controlled_release_fixture_not_model_or_device_performance"))
            store.append(PipelineBenchmarkTurn(
                turnId = cancelledId, channel = "voice", capturedAtEpochMs = System.currentTimeMillis() - 1,
                provenance = provenance, outcome = PipelineBenchmarkOutcome.CANCELLED,
                clock = "controlled_fixture_monotonic", stageOffsetsMs = mapOf("turn_started" to 0, "turn_finished" to 500),
                failureCode = "fixture_cancelled"))
            store.append(PipelineBenchmarkTurn(
                turnId = completedId, channel = "voice", capturedAtEpochMs = System.currentTimeMillis(),
                provenance = provenance, outcome = PipelineBenchmarkOutcome.COMPLETE,
                clock = "controlled_fixture_monotonic",
                stageOffsetsMs = mapOf("turn_started" to 0, "microphone_ready" to 20, "recognition_finalized" to 200,
                    "reply_dispatched" to 210, "first_reply_text" to 350, "turn_finished" to 900,
                    "speech_started" to 40, "speech_ended" to 150, "endpoint_decided" to 190,
                    "early_gemma_session_prepare_started" to 30, "early_gemma_fixed_prefill_completed" to 100,
                    "smart_turn_0_backend_called" to 110, "smart_turn_0_backend_returned" to 160),
                asr = PipelineBenchmarkAsr("controlled-whisper-fixture", inputAudioMs = 400, decodeWorkMs = 200,
                    transcriptCharacters = originalAsr.length),
                submissions = listOf(PipelineBenchmarkSubmission(
                    "fixture-submission", PipelineBenchmarkPurpose.ANSWER, PipelineBenchmarkOutcome.COMPLETE,
                    firstTokenMs = 100, totalGenerationMs = 300, estimatedOutputTokens = 3,
                    tokenTelemetrySource = "unavailable_fixture_no_native_token_ids"))), hypothesis = originalAsr)
            store.flush()
            render(store)
            benchmarkClickEnabled(By.res("pipeline_benchmark_sample_$completedId"))
            // The expanded header starts at the lower edge on API 30. Discover the
            // adjacent controls with an overlapping held stroke, keeping the same
            // deadline, gesture budget, fresh safe bounds and exactly-one-tap rule.
            benchmarkClickEnabled(By.res("benchmark_environment_NOISY"),
                holdDiscovery = android.os.Build.VERSION.SDK_INT == 30)
            benchmarkClickEnabled(By.res("pipeline_benchmark_reference"))
            assertTrue(find(By.text("Original ASR: $originalAsr")).text.contains(originalAsr))
            assertFalse(find(By.res("pipeline_benchmark_reference_score")).isEnabled)
            find(By.res("pipeline_benchmark_reference_text")).text = reference
            hideKeyboardWithoutNavigating()
            assertFalse("Typing a reference is insufficient without explicit verification",
                find(By.res("pipeline_benchmark_reference_score")).isEnabled)
            benchmarkClickEnabled(By.res("pipeline_benchmark_reference_verify"), inDialog = true)
            benchmarkClickEnabled(By.res("pipeline_benchmark_reference_score"), inDialog = true)
            assertTrue(benchmarkScrollTo(By.res("pipeline_benchmark_status"), towardTop = true).text.contains("WER 25.0%"))
            val scored = store.samples.value.single { it.turnId == completedId }
            val score = requireNotNull(scored.accuracy)
            assertEquals(4, score.referenceWords)
            assertEquals(1, score.wordSubstitutions)
            assertEquals(0.25, score.wer!!, 0.000001)
            assertNull("Verified reference words must not become retained content", score.reference)
            assertNull("Original ASR content must not become retained content", score.hypothesis)

            // A completed pipeline can still have an incorrect result: human review is independent.
            benchmarkClickEnabled(By.res("benchmark_quality_task_FAIL"), holdDiscovery = true,
                sparseHeldDiscovery = android.os.Build.VERSION.SDK_INT >= 35)
            // These adjacent controls may start just below the viewport. Use the existing
            // overlapping discovery path instead of a blind fling across the review section.
            benchmarkClickEnabled(By.res("benchmark_quality_intent_PASS"), holdDiscovery = true,
                sparseHeldDiscovery = android.os.Build.VERSION.SDK_INT >= 35)
            benchmarkClickEnabled(By.res("benchmark_quality_factuality_FAIL"))
            assertFalse(benchmarkScrollTo(By.res("pipeline_benchmark_quality_save"),
                sparseHeldDiscovery = android.os.Build.VERSION.SDK_INT >= 35).isEnabled)
            benchmarkClickEnabled(By.res("pipeline_benchmark_quality_verify"))
            benchmarkClickEnabled(By.res("pipeline_benchmark_quality_save"))
            withTimeout(5_000) { store.samples.first { turns -> turns.single { it.turnId == completedId }.quality != null } }
            val quality = store.samples.value.single { it.turnId == completedId }.quality!!
            assertEquals(PipelineBenchmarkVerdict.FAIL, quality.taskVerdict)
            assertEquals(PipelineBenchmarkVerdict.PASS, quality.intentVerdict)
            assertEquals(PipelineBenchmarkVerdict.FAIL, quality.factualityVerdict)
            assertEquals(PipelineBenchmarkOutcome.COMPLETE, store.samples.value.single { it.turnId == completedId }.outcome)
            assertEquals(PipelineBenchmarkEnvironment.NOISY, store.samples.value.single { it.turnId == completedId }.environment)
            // Timing details remain discoverable without adding 31 verbose rows
            // to every expanded sample. Opening/closing them cannot change data.
            val beforeTiming = store.samples.value.single { it.turnId == completedId }.json().toString()
            fun timingToggle() = By.res("pipeline_benchmark_timing_toggle")
            assertNotNull(benchmarkScrollTo(timingToggle().hasDescendant(By.text("Show timing measurements (9/31 available)")),
                holdDiscovery = true, sparseHeldDiscovery = android.os.Build.VERSION.SDK_INT >= 35))
            assertFalse(device.hasObject(By.res("pipeline_benchmark_timing_details")))
            repeat(2) {
                benchmarkClickEnabled(timingToggle(), holdDiscovery = true,
                    sparseHeldDiscovery = android.os.Build.VERSION.SDK_INT >= 35)
                assertNotNull(benchmarkRevealText("early_gemma_preparation_capture_overlap_ms: 70.00", holdDiscovery = true))
                captureEvidence("pipeline_benchmark_timing_expanded_$it")
                benchmarkClickEnabled(timingToggle().hasDescendant(By.text("Hide timing measurements")), towardTop = true,
                    holdDiscovery = true, sparseHeldDiscovery = android.os.Build.VERSION.SDK_INT >= 35)
                assertTrue("Collapse is acknowledged before checking hidden details", device.wait(Until.hasObject(
                    timingToggle().hasDescendant(By.text("Show timing measurements (9/31 available)"))), 5_000))
                assertFalse(device.hasObject(By.res("pipeline_benchmark_timing_details")))
                assertEquals("Timing visibility must not mutate retained/exported data", beforeTiming,
                    store.samples.value.single { it.turnId == completedId }.json().toString())
            }
            assertNotNull(if (android.os.Build.VERSION.SDK_INT == 30)
                benchmarkRevealText("tts_load_ms: unavailable", holdDiscovery = true)
                else benchmarkScrollTo(By.text("tts_load_ms: unavailable"), sparseHeldDiscovery = true))
            captureEvidence("pipeline_benchmark_verified_reference_and_review")

            benchmarkClickEnabled(By.res("pipeline_benchmark_copy_text"), towardTop = true)
            find(By.res("pipeline_benchmark_part_copy")).click()
            val reportPart = AtomicReference<String>()
            activity.onActivity { host -> reportPart.set(host.getSystemService(android.content.ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.coerceToText(host)?.toString().orEmpty()) }
            assertTrue(reportPart.get().contains("part 1/"))
            assertTrue(reportPart.get().contains("Retained attempts=2"))
            assertTrue(reportPart.get().toByteArray(Charsets.UTF_8).size <= PipelineBenchmarkTextExport.COPY_BYTES)
            find(By.res("pipeline_benchmark_part_next")).click()
            // Compose exposes the tagged button and its exact text on separate nodes.
            find(By.res("pipeline_benchmark_part_copy").hasDescendant(By.text("Copy part 2"))).click()
            activity.onActivity { host -> reportPart.set(host.getSystemService(android.content.ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.coerceToText(host)?.toString().orEmpty()) }
            assertTrue(reportPart.get().contains("part 2/"))
            find(By.res("pipeline_benchmark_parts_done")).click()
            // Cancel then save twice: pending state clears, payload stays whole-scope and UTF-8.
            exportProbe.holdResult = true
            find(By.res("pipeline_benchmark_save_text")).click()
            exportProbe.awaitObserved("Picker request observed") { exportProbe.creates.size == 1 }
            // The picker is controlled so the underlying screen can scroll/recompose while pending.
            // Overlap discovery viewports and settle the expanded sample header before observing it.
            benchmarkScrollTo(By.res("pipeline_benchmark_sample_$completedId"),
                sparseHeldDiscovery = android.os.Build.VERSION.SDK_INT >= 35)
            assertFalse(device.hasObject(By.res("pipeline_benchmark_save_text")))
            exportProbe.completePending()
            benchmarkScrollTo(By.res("pipeline_benchmark_save_text").enabled(true), towardTop = true)
            repeat(2) { index ->
                val destination = exportProbe.destination()
                exportProbe.nextSave = destination
                find(By.res("pipeline_benchmark_save_text")).click()
                exportProbe.awaitObserved("Repeated picker request observed") { exportProbe.creates.size == index + 2 }
                store.setEnvironment(completedId, PipelineBenchmarkEnvironment.QUIET)
                exportProbe.completePending()
                exportProbe.awaitObserved("Whole report written after save") {
                    exportProbe.read(destination).contains("END REPORT: 2 retained attempts.")
                }
                assertTrue(device.wait(Until.hasObject(By.res("pipeline_benchmark_save_text").enabled(true)), 5_000))
                val text = exportProbe.read(destination)
                assertTrue(text.contains("id=\"$completedId\""))
                assertTrue(text.contains("id=\"$cancelledId\""))
                assertFalse(text.contains(originalAsr))
                assertFalse(text.contains(reference))
                assertTrue(text.contains("early_gemma_preparation_capture_overlap_ms"))
                assertTrue(text.contains("smart_turn_7_backend_after_speech_ms"))
                assertTrue("Pending export keeps its original scope/settings despite recomposition", text.contains("environment=NOISY"))
                store.setEnvironment(completedId, PipelineBenchmarkEnvironment.NOISY)
                activity.onActivity { } // drain the state change before requesting the next snapshot
            }
            exportProbe.holdResult = false
            assertEquals(3, exportProbe.creates.size)
            exportProbe.creates.forEach { intent ->
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
                assertEquals("text/plain", intent.type)
                assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".txt"))
                assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
            }
            repeat(2) { index ->
                find(By.res("pipeline_benchmark_share_text")).click()
                exportProbe.awaitObserved("Share chooser request observed") { exportProbe.shares.size == index + 1 }
                assertTrue(device.wait(Until.hasObject(By.res("pipeline_benchmark_share_text").enabled(true)), 5_000))
            }
            assertEquals(2, exportProbe.shares.size)
            exportProbe.shares.forEach { intent ->
                assertEquals("text/plain", intent.type)
                @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
                assertTrue(exportProbe.read(uri).contains("Retained attempts=2"))
            }

            // Replacing the screen owner aborts an old picker. Its late URI must never
            // receive a new export's payload, even if the next screen has the same scope.
            val staleDestination = exportProbe.destination()
            exportProbe.holdResult = true
            exportProbe.nextSave = staleDestination
            find(By.res("pipeline_benchmark_save_text")).click()
            exportProbe.awaitObserved("Old screen picker request observed") { exportProbe.creates.size == 4 }
            render(store)
            exportProbe.completePending()
            activity.onActivity { }
            assertEquals("old bytes must be replaced", exportProbe.read(staleDestination))
            exportProbe.holdResult = false
            val freshDestination = exportProbe.destination()
            exportProbe.nextSave = freshDestination
            benchmarkClickEnabled(By.res("pipeline_benchmark_save_text"), towardTop = true)
            exportProbe.awaitObserved("Recreated screen can export again") {
                exportProbe.read(freshDestination).contains("END REPORT: 2 retained attempts.")
            }
            assertEquals("old bytes must be replaced", exportProbe.read(staleDestination))

            benchmarkClickEnabled(By.res("pipeline_benchmark_copy_json"), towardTop = true)
            assertEquals("Redacted JSON report copied.", benchmarkScrollTo(By.res("pipeline_benchmark_status")
                .text("Redacted JSON report copied."), towardTop = true).text)
            val clipboard = AtomicReference<String>()
            activity.onActivity { host ->
                clipboard.set(host.getSystemService(android.content.ClipboardManager::class.java)
                    .primaryClip?.getItemAt(0)?.coerceToText(host)?.toString().orEmpty())
            }
            val export = org.json.JSONObject(clipboard.get())
            assertFalse(export.getJSONObject("privacy").getBoolean("textIncluded"))
            assertFalse(clipboard.get().contains(originalAsr))
            assertFalse(clipboard.get().contains(reference))
            assertEquals(2, export.getJSONObject("allAttempts").getInt("turnCount"))
            assertEquals(1, export.getJSONObject("allAttempts").getJSONObject("outcomes").getInt("CANCELLED"))
            assertEquals(1, export.getJSONObject("completedTurns").getInt("turnCount"))
            val exportedCompleted = (0 until export.getJSONArray("turns").length())
                .map { export.getJSONArray("turns").getJSONObject(it) }.single { it.getString("turnId") == completedId }
            val exportedTiming = PipelineBenchmarkTurn.read(exportedCompleted).metrics()
            assertEquals(70.0, exportedTiming["early_gemma_preparation_capture_overlap_ms"])
            assertEquals(40.0, exportedTiming["smart_turn_0_backend_speech_span_overlap_ms"])
            assertTrue(exportedTiming.containsKey("smart_turn_7_backend_after_speech_ms"))
            assertNull(exportedTiming["smart_turn_7_backend_after_speech_ms"])
            assertTrue("Missing playback measurements remain JSON null", exportedCompleted.isNull("tts"))
            assertTrue("Token estimates do not invent native counts", exportedCompleted.getJSONArray("submissions")
                .getJSONObject(0).isNull("exactOutputTokens"))
            assertEquals(0.25, export.getJSONObject("allAttempts").getDouble("corpusWer"), 0.000001)

            store.flush()
            val restored = AndroidPipelineBenchmarkStore(fixtureContext)
            val persisted = restored.samples.value.single { it.turnId == completedId }
            assertEquals(scored.accuracy, persisted.accuracy)
            assertEquals(quality, persisted.quality)
            assertEquals(PipelineBenchmarkEnvironment.NOISY, persisted.environment)
            assertNull(persisted.metrics()["endpoint_to_first_answer_playback_ms"])
            assertNull("Original transcript is process-only and is not restored from disk", restored.hypothesis(completedId))
            assertEquals(PipelineBenchmarkOutcome.CANCELLED, restored.samples.value.single { it.turnId == cancelledId }.outcome)
            render(restored)
            benchmarkClickEnabled(By.res("pipeline_benchmark_sample_$completedId"))
            assertFalse(benchmarkScrollTo(By.res("pipeline_benchmark_reference"), holdDiscovery = true,
                sparseHeldDiscovery = android.os.Build.VERSION.SDK_INT >= 35).isEnabled)
            captureEvidence("pipeline_benchmark_restored_redacted_scores")
            benchmarkClickEnabled(By.res("pipeline_benchmark_reset"), towardTop = true)
            benchmarkClickEnabled(By.res("pipeline_benchmark_reset_confirm"), inDialog = true)
            assertNotNull(benchmarkRevealText("No pipeline measurements yet. Complete a text or voice turn, then return here."))
            restored.flush()
            assertTrue(AndroidPipelineBenchmarkStore(fixtureContext).samples.value.isEmpty())
        } finally {
            store.clear()
            directory.deleteRecursively()
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test46_replyMetricsExportOnlyTheirConversationAcrossReload() = runBlocking {
        val directory = File(context.cacheDir, "release-conversation-benchmarks").apply { deleteRecursively(); mkdirs() }
        val fixtureContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val prefs = context.getSharedPreferences("release-benchmark-conversation", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val history = ConversationHistory(prefs)
        val thread = history.current.value.id
        val reply = "conversation-benchmark-reply"
        history.syncCall(VoiceCallRecord("benchmark-call", System.currentTimeMillis(), conversationId = thread,
            transcript = listOf(TranscriptEntry("Jarvis", "Measured reply", replyId = reply,
                metrics = ReplyMetrics().submitted(100).firstRawToken(200)))))
        val row = history.current.value.messages.single()
        assertEquals(reply, ConversationHistory(prefs).current.value.messages.single().sourceReplyId)
        val store = AndroidPipelineBenchmarkStore(fixtureContext)
        try {
            val provenance = store.captureProvenance(configuration = mapOf("reply_id" to reply, "evidence" to "controlled_fixture"))
            for (conversation in listOf(thread, "other-conversation")) store.append(PipelineBenchmarkTurn(
                turnId = "attempt-$conversation", channel = "text", capturedAtEpochMs = System.currentTimeMillis(),
                provenance = provenance, outcome = PipelineBenchmarkOutcome.COMPLETE,
                clock = "controlled_fixture", stageOffsetsMs = mapOf("turn_started" to 0, "turn_finished" to 200),
                conversationId = conversation))
            store.flush()
            val restored = AndroidPipelineBenchmarkStore(fixtureContext)
            assertEquals(1, restored.report(conversationId = thread).turns.size)
            activity.onActivity { host -> host.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    ConversationScreen(history, MutableStateFlow(false), MutableStateFlow(VoiceSessionState.PASSIVE_LISTENING),
                        onSend = { _, _ -> null }, selectedModel = LocalModelSpec("release-fixture", "fixture.bin", recommendedGpu = false),
                        onSelectConversation = { null }, onEndVoice = {}, onOpenVoiceCalls = {}, resumedVoice = false,
                        pipelineBenchmarkStore = restored, voiceContent = { _, _, _, _ -> })
                } }
            } }
            find(By.res("conversation_metrics_copy")).click()
            find(By.text("Conversation metrics copied."))
            val copiedConversation = AtomicReference<String>()
            activity.onActivity { host -> copiedConversation.set(host.getSystemService(android.content.ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.coerceToText(host)?.toString().orEmpty()) }
            val directReport = org.json.JSONObject(copiedConversation.get())
            assertEquals(1, directReport.getJSONArray("turns").length())
            assertEquals(thread, directReport.getJSONArray("turns").getJSONObject(0).getString("conversationId"))
            assertEquals(4, directReport.getJSONArray("replyMetrics").getJSONObject(0).getInt("estimatedOutputTokens"))
            assertFalse(copiedConversation.get().contains("other-conversation"))
            assertFalse(copiedConversation.get().contains("Measured reply"))
            find(By.res("conversation_metrics_open")).click()
            find(By.res("pipeline_benchmark_screen"))
            benchmarkClickEnabled(By.res("pipeline_benchmark_close"), towardTop = true)
            find(By.res("reply_metrics_${row.id}")).click()
            find(By.res("pipeline_benchmark_screen"))
            benchmarkClickEnabled(By.res("pipeline_benchmark_copy_json"), towardTop = true)
            find(By.res("pipeline_benchmark_status"))
            val clipboard = AtomicReference<String>()
            activity.onActivity { host -> clipboard.set(host.getSystemService(android.content.ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.coerceToText(host)?.toString().orEmpty()) }
            val report = org.json.JSONObject(clipboard.get())
            assertEquals(1, report.getJSONArray("turns").length())
            assertEquals(thread, report.getJSONArray("turns").getJSONObject(0).getString("conversationId"))
            assertFalse(clipboard.get().contains("other-conversation"))
            assertFalse(clipboard.get().contains("Measured reply"))
            captureEvidence("conversation_scoped_benchmark_export")
            benchmarkClickEnabled(By.res("pipeline_benchmark_close"), towardTop = true)
            assertNotNull(find(By.res("chat_composer")))
        } finally { store.clear(); directory.deleteRecursively(); prefs.edit().clear().commit() }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test47_audioInputChoiceAndDisplayCaptionTogglePersistWithoutLoadingModels() {
        val originalMode = VoiceInputMode.selected(context)
        val originalEngine = VoiceInputMode.captionEngine(context)
        fun render() {
            activity.onActivity { host -> host.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    androidx.compose.foundation.lazy.LazyColumn {
                        item { com.battlesbudz.jarvis.v2.ui.VoiceInputSettings(enabled = true, onBusy = {}) }
                    }
                } }
            } }
            device.waitForIdle()
        }
        try {
            VoiceInputMode.select(context, VoiceInputMode.TRANSCRIBED_TEXT)
            VoiceInputMode.captionEngine(context, null)
            render()
            find(By.text("Use Gemma audio understanding")).click()
            assertEquals(VoiceInputMode.GEMMA_AUDIO, VoiceInputMode.selected(context))
            assertNotNull(find(By.textContains("Captions are display-only")))
            assertNotNull(find(By.text("Off")))
            // Exercise the actual dropdown and rebuild the UI for each persisted choice.
            // Expected UI labels are fixture data, not getters across the independently
            // optimized app/test APK boundary (the app can inline AsrEngine.label).
            for ((label, engine) in listOf("Whisper base · Snapdragon NPU" to AsrEngine.WHISPER_NPU,
                "Whisper base.en" to AsrEngine.WHISPER, "Off" to null)) {
                find(By.res("gemma_caption_engine")).click()
                // The separate popup window does not inherit testTagsAsResourceId.
                // Each new label differs from the selected value; other controls
                // use prefixed labels, so this targets the actual visible option.
                find(By.text(label)).click()
                assertEquals(engine, VoiceInputMode.captionEngine(context))
                render()
                assertNotNull(find(By.text(label)))
            }
            assertFalse(VoiceInputMode.captions(context))
            captureEvidence("gemma_audio_display_caption_settings")
            find(By.text("Use Speech recognition")).click()
            assertEquals(VoiceInputMode.TRANSCRIBED_TEXT, VoiceInputMode.selected(context))
            assertNull(VoiceInputMode.captionEngine(context))
        } finally {
            VoiceInputMode.select(context, originalMode)
            VoiceInputMode.captionEngine(context, originalEngine)
        }
    }

    @Suppress("DEPRECATION")
    @Test fun test48_spokenCallEndRetainsWakeSessionAndExplicitStopDisarms() = runBlocking {
        val runtime = JarvisRuntime.get(context)
        val controller = runtime.voiceSessionController
        val originalTurn = runtime.voiceTurnJob
        val originalRecoveryAttempts = runtime.audioRecoveryAttempts
        val originalWakeCue = runtime.returnToWakeCuePending.get()
        val originalReport = runtime.sessionReport
        val originalPhase = VoiceSessionUi.phase.value
        val originalStatus = VoiceSessionUi.status.value
        val originalTranscript = VoiceSessionUi.liveTranscript.value
        val originalLevel = VoiceSessionUi.level.value
        val originalUiArmed = VoiceSessionUi.armed.value
        val originalPaused = VoiceSessionUi.paused.value
        val originalServiceStop = VoiceCallService.stopRequested.value
        val originalOnCallBegan = controller.onCallBegan
        val callIds = mutableListOf<String>()
        val finishingTurn = Job()
        val passiveTurn = Job()
        fun callService() = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
            .singleOrNull { it.service.className == VoiceCallService::class.java.name }
        fun awaitService(message: String, condition: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < until && !condition()) SystemClock.sleep(100)
            assertTrue(message, condition())
        }
        assertFalse("The controlled runtime journey must start with an idle wake session", runtime.voiceSessionArmed)
        assertNull("The controlled runtime journey must not replace another call", controller.currentCallId())
        assertTrue("The previous runtime turn must already be complete", originalTurn?.isCompleted != false)
        assertNull("The controlled runtime journey must own its foreground service", callService())
        try {
            runtime.sessionReport = {}
            // Park the production onCallBegan hook (which would start the real
            // VideoCallService via START_VIDEO_CAPTURE): this journey never
            // exercises video, and a leaked capture intent racing the next
            // test's service quiesce lands on test49's fresh service AFTER its
            // farewell refresh, leaving a stale "Video on" notification that
            // fails test49's IDLE assertion (API 35 run 1224). Same parking
            // as test49.
            controller.onCallBegan = null
            // An owned, incomplete turn keeps sendChat at its real queue boundary without
            // loading ASR, wake-word, Gemma or Piper models in this lifecycle fixture.
            runtime.voiceTurnJob = finishingTurn
            runtime.arm()
            activity.onActivity { host ->
                host.startForegroundService(Intent(host, VoiceCallService::class.java).setAction("release-verification-hold"))
            }
            awaitService("The armed runtime must retain foreground microphone eligibility", { callService()?.foreground == true })
            val oldCall = controller.beginCall().also { callIds += it.id }
            controller.appendTranscript("You", "Stop listening")
            assertNull(runtime.sendChat("This queued draft belongs only to the ending call."))

            // Exercise the same runtime callback used by a final spoken farewell. Native
            // turn cleanup owns rearm; returning to wake mode must not cancel that owner.
            runtime.returnToWakeListening(oldCall.id)
            assertTrue("A spoken farewell must keep the user-armed wake session", runtime.voiceSessionArmed)
            assertTrue(VoiceSessionUi.armed.value)
            assertFalse(VoiceSessionUi.paused.value)
            assertEquals(VoiceSessionState.PASSIVE_LISTENING, controller.state.value)
            assertNull(controller.currentCallId())
            assertTrue("The exact turn must survive until its own cleanup finishes", finishingTurn.isActive)
            assertFalse(finishingTurn.isCancelled)
            assertTrue("Returning to wake mode must queue its ready cue after cleanup", runtime.returnToWakeCuePending.get())
            assertTrue("A farewell must retain the actual foreground service", callService()?.foreground == true)
            assertFalse("A farewell must not request service termination", VoiceCallService.stopRequested.value)
            val saved = SharedPreferencesVoiceCallStore(context.getSharedPreferences("voice_calls", Context.MODE_PRIVATE))
                .list().single { it.id == oldCall.id }
            assertNotNull("The ending call must be durable before another call begins", saved.endedAtMs)
            assertTrue(saved.transcript.any { it.role == "You" && it.text == "Stop listening" })
            assertEquals("The ending call must terminalize its queued typed input once", 1,
                saved.transcript.count { it.text == "Cancelled before processing typed message: This queued draft belongs only to the ending call." })
            assertEquals("Voice Call is waiting for its wake word; keep this draft until the call is listening.",
                runtime.sendChat("A passive-session draft must wait for the next wake word."))

            finishingTurn.complete()
            withTimeout(5_000) { finishingTurn.join() }
            runtime.voiceTurnJob = passiveTurn
            val newCall = controller.beginCall().also { callIds += it.id }
            assertNotEquals("A new wake conversation must get a fresh call identity", oldCall.id, newCall.id)
            assertTrue("Ended-call drafts must not leak into the new call", controller.currentTranscript().isEmpty())
            runtime.returnToWakeListening(oldCall.id)
            assertEquals("A stale farewell must not end a newer call", newCall.id, controller.currentCallId())
            assertEquals(VoiceSessionState.ACTIVELY_LISTENING, controller.state.value)
            assertTrue(passiveTurn.isActive)
            runtime.returnToWakeListening(newCall.id)
            assertNull(controller.currentCallId())
            assertEquals(VoiceSessionState.PASSIVE_LISTENING, controller.state.value)
            assertTrue(runtime.voiceSessionArmed)
            assertTrue(callService()?.foreground == true)

            runtime.endVoiceCall {}
            assertFalse("Explicit End Call must stop the whole session even while passive", runtime.voiceSessionArmed)
            assertFalse(VoiceSessionUi.armed.value)
            assertFalse(runtime.returnToWakeCuePending.get())
            assertTrue("Explicit End Call must cancel the passive turn", passiveTurn.isCancelled)
            awaitService("Explicit End Call must release its foreground service", {
                callService() == null && VoiceCallService.stopRequested.value
            })
            assertTrue(VoiceCallService.stopRequested.value)
        } finally {
            try {
                runtime.endVoiceCall {}
                context.stopService(Intent(context, VoiceCallService::class.java))
                finishingTurn.cancel()
                passiveTurn.cancel()
                withTimeout(5_000) { finishingTurn.join(); passiveTurn.join() }
                awaitService("The runtime fixture must release its service owner", {
                    callService() == null && VoiceCallService.stopRequested.value
                })
                instrumentation.waitForIdleSync()
            } finally {
                controller.currentCallId()?.takeIf { it in callIds }?.let { controller.end() }
                callIds.forEach(runtime.voiceCallStore::delete)
                runtime.voiceTurnJob = originalTurn
                runtime.audioRecoveryAttempts = originalRecoveryAttempts
                runtime.returnToWakeCuePending.set(originalWakeCue)
                runtime.sessionReport = originalReport
                VoiceSessionUi.phase.value = originalPhase
                VoiceSessionUi.status.value = originalStatus
                VoiceSessionUi.liveTranscript.value = originalTranscript
                VoiceSessionUi.level.value = originalLevel
                VoiceSessionUi.armed.value = originalUiArmed
                VoiceSessionUi.paused.value = originalPaused
                VoiceCallService.stopRequested.value = originalServiceStop
                controller.onCallBegan = originalOnCallBegan
            }
        }
    }

    /** Test49 observations own their deadlines; a replaced node never causes another action. */
    private inline fun <T> observeWisp(block: () -> T): T {
        val configuration = Configurator.getInstance()
        val savedIdleTimeout = configuration.getWaitForIdleTimeout()
        configuration.setWaitForIdleTimeout(0)
        return try { block() } finally { configuration.setWaitForIdleTimeout(savedIdleTimeout) }
    }

    private fun refreshWispObservation() {
        val automation = instrumentation.uiAutomation
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            assertTrue("Wisp discovery requires a fresh accessibility cache", automation.clearCache())
        } else {
            // Reapply the current service info unchanged, as in benchmark/layout observations.
            automation.serviceInfo = checkNotNull(automation.serviceInfo)
        }
    }

    private fun awaitWispFixtureReady(fixtureText: String) = observeWisp {
        // Idle has no status node. Require the fixture transcript and real character together
        // so the previous setup shell cannot establish that setContent has committed.
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            refreshWispObservation()
            if (SystemClock.uptimeMillis() >= deadline) break
            try {
                val transcript = device.findObject(By.res("conversation_transcript").pkg(context.packageName))
                val fixture = transcript?.findObject(By.text(fixtureText))
                val composer = device.findObject(By.res("chat_composer").pkg(context.packageName))
                val character = device.findObject(By.res("jarvis_wisp").pkg(context.packageName))
                val pose = device.findObject(By.res("jarvis_wisp_status").pkg(context.packageName))
                if (fixture?.text == fixtureText && composer?.resourceName == "chat_composer" &&
                    character != null && pose == null && SystemClock.uptimeMillis() < deadline) return@observeWisp
            } catch (_: StaleObjectException) {
                // The old setup or fixture node was replaced. Observe again; never remount it.
            }
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining > 0) SystemClock.sleep(remaining.coerceAtMost(25))
        }
        throw AssertionError("The controlled Wisp conversation and idle character must mount within 10,000 ms")
    }

    private fun awaitWispGeometry(tag: String, message: String, timeoutMs: Long, stableMs: Long,
        expected: (android.graphics.Rect) -> Boolean): android.graphics.Rect = observeWisp {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var previous: android.graphics.Rect? = null
        var stableSince: Long? = null
        var lastObservation = "missing"
        while (SystemClock.uptimeMillis() < deadline) {
            refreshWispObservation()
            if (SystemClock.uptimeMillis() >= deadline) break
            val bounds = try {
                val control = device.findObject(By.res(tag).pkg(context.packageName))
                if (control != null && control.resourceName == tag && control.applicationPackage == context.packageName) {
                    android.graphics.Rect(control.visibleBounds).also { lastObservation = it.toString() }
                } else {
                    lastObservation = "missing"
                    null
                }
            } catch (_: StaleObjectException) {
                lastObservation = "stale"
                null
            }
            val now = SystemClock.uptimeMillis()
            if (now >= deadline) break
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0 && expected(bounds)) {
                if (bounds != previous) stableSince = now
                previous = bounds
                if (now - checkNotNull(stableSince) >= stableMs && SystemClock.uptimeMillis() < deadline) {
                    return@observeWisp bounds
                }
            } else {
                // Neither missing/stale reads nor an out-of-range tween can count as stable.
                previous = null
                stableSince = null
            }
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining > 0) SystemClock.sleep(remaining.coerceAtMost(25))
        }
        throw AssertionError("$message; target=$tag last observation=$lastObservation")
    }

    private fun awaitWispBounds(tag: String): android.graphics.Rect =
        awaitWispGeometry(tag, "Missing fresh Wisp geometry", 15_000, 0) { true }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test49_wispStaysPresentAndReflectsOnlyObservedWork() {
        val prefs = context.getSharedPreferences("release-wisp", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val history = ConversationHistory(prefs)
        val fixtureText = "Keep this conversation visible while Jarvis works."
        history.appendUser(fixtureText)
        val busy = MutableStateFlow(false)
        val call = MutableStateFlow(VoiceSessionState.PASSIVE_LISTENING)
        val playback = MutableStateFlow(VoicePlaybackFrame())
        val journal = MutableStateFlow<ToolTaskJournal?>(ToolTaskJournal())
        val journalWarning = "The action journal is unavailable. Phone actions are paused."
        val taskError = MutableStateFlow<String?>(null)
        val observed = MutableStateFlow<AgentActivitySnapshot?>(null)
        val windowTarget = AtomicReference<CommittedWispWindowCapture.Target?>()
        fun captureWispEvidence(name: String) {
            val target = checkNotNull(windowTarget.get()) { "Wisp fixture window is not mounted" }
            val directory = File(context.cacheDir, "verification").apply { mkdirs() }
            val screenshot = File(directory, "$name.png")
            val hierarchy = File(directory, "$name.xml")
            val bitmap = CommittedWispWindowCapture.capture(target)
            try {
                screenshot.outputStream().use { output ->
                    assertTrue("Wisp window PNG encoding failed", bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally { bitmap.recycle() }
            // Keep each established PNG/XML name and export contract. The PNG contains
            // committed app-window pixels; system bars/other windows are not claimed.
            device.dumpWindowHierarchy(hierarchy)
            exportEvidence(screenshot, "image/png")
            exportEvidence(hierarchy, "application/xml")
        }
        val motionFrameWait = AtomicReference<Pair<Boolean, java.util.concurrent.CountDownLatch>?>(null)
        val approvals = AtomicInteger()
        val stops = AtomicInteger()
        val ends = AtomicInteger()
        fun endCall() {
            ends.incrementAndGet()
            VoiceSessionUi.armed.value = false
            VoiceSessionUi.phase.value = VoicePhase.IDLE
            VoiceSessionUi.level.value = 0f
            playback.value = VoicePlaybackFrame()
            call.value = VoiceSessionState.PASSIVE_LISTENING
        }
        fun idle() {
            assertTrue("Idle Wisp must have no activity text or accessibility status node", device.wait(
                Until.gone(By.res("jarvis_wisp_status")), 10_000))
            assertNotNull("The character remains while idle", find(By.res("jarvis_wisp")))
            if (taskError.value != null) assertTrue("The separate task warning remains visible", device.wait(
                Until.hasObject(By.text("Phone tasks need attention")), 5_000))
        }
        fun pose(label: String) {
            assertTrue("Wisp should show $label", device.wait(
                Until.hasObject(By.res("jarvis_wisp_status").text(label)), 10_000))
            if (taskError.value != null) assertTrue("The task warning must remain visible during $label", device.wait(
                Until.hasObject(By.text("Phone tasks need attention")), 5_000))
        }
        fun awaitWispSize(message: String, expected: (android.graphics.Rect) -> Boolean): android.graphics.Rect =
            awaitWispGeometry("jarvis_wisp_viewport", message, 5_000, 200, expected)
        fun task(state: ToolTaskState, tool: String = "read_battery", generation: Long = 0,
            outcome: ExecutionResult.Outcome? = null) {
            journal.value = ToolTaskJournal(attempts = listOf(ToolTaskAttempt(
                "wisp-task", generation, state, ActionRequest(tool, if (tool == "set_volume") mapOf("level" to "25") else emptyMap()),
                1, 2, resultOutcome = outcome)))
        }
        val animationScaleBefore = device.executeShellCommand("settings get global animator_duration_scale").trim()
        check(animationScaleBefore == "null" || animationScaleBefore.isBlank() ||
            animationScaleBefore.toFloatOrNull()?.isFinite() == true) { "Unrecognized animator scale fixture value" }
        var fixtureFailure: Throwable? = null
        fun animationScale(value: String) {
            device.executeShellCommand("settings put global animator_duration_scale $value")
            assertEquals(value.toFloat(), device.executeShellCommand("settings get global animator_duration_scale").trim().toFloat(), 0f)
        }
        fun renderedAnimationScale(value: String) {
            // A fresh waiter, installed before changing scale, cannot reuse an old observation.
            val wait = (value.toFloat() > 0f) to java.util.concurrent.CountDownLatch(1)
            motionFrameWait.set(wait)
            try {
                animationScale(value)
                assertTrue("Wisp must draw the requested motion state before capture",
                    wait.second.await(5, java.util.concurrent.TimeUnit.SECONDS))
            } finally {
                motionFrameWait.compareAndSet(wait, null)
            }
        }
        try {
            // The suite normally disables animation. Enable only this visual fixture, restoring
            // the original setting below; window and transition scales remain untouched.
            animationScale("1")
            VoiceSessionUi.armed.value = false
            VoiceSessionUi.phase.value = VoicePhase.IDLE
            VoiceSessionUi.paused.value = false
            activity.onActivity { host ->
                windowTarget.set(CommittedWispWindowCapture.Target(host.window, host.window.decorView))
                host.setContent {
                MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        WispAppFrame(presence = {
                            WispPresence(history, busy, call, playback, journal, taskError, observed,
                                onMotionFrame = { moving ->
                                    motionFrameWait.get()?.let { (expected, latch) ->
                                        if (moving == expected) latch.countDown()
                                    }
                                })
                        }) {
                                ConversationScreen(history, busy, call, onSend = { _, _ -> null },
                                    selectedModel = LocalModelSpec("wisp-fixture", "fixture.bin", recommendedGpu = false),
                                    onSelectConversation = { null }, onEndVoice = { done -> endCall(); done("") },
                                    onOpenVoiceCalls = {}, resumedVoice = false,
                                    phoneTasks = journal, phoneTaskError = taskError,
                                    onPhoneTaskAction = { _, _, _ -> approvals.incrementAndGet() },
                                    voiceContent = { visible, _, _, request ->
                                        androidx.compose.runtime.LaunchedEffect(request) {
                                            if (request > 0) {
                                                VoiceSessionUi.armed.value = true
                                                VoiceSessionUi.phase.value = VoicePhase.LISTENING
                                                call.value = VoiceSessionState.ACTIVELY_LISTENING
                                            }
                                        }
                                        val armed by VoiceSessionUi.armed.collectAsState()
                                        val phase by VoiceSessionUi.phase.collectAsState()
                                        val paused by VoiceSessionUi.paused.collectAsState()
                                        if (visible) VoiceCallOverlay.Bubble(phase.label, phase.label, 0f,
                                            armed, paused, !armed, phase == VoicePhase.SPEAKING,
                                            onStart = {}, onStopReply = {
                                                stops.incrementAndGet(); playback.value = VoicePlaybackFrame()
                                                VoiceSessionUi.phase.value = VoicePhase.LISTENING
                                                call.value = VoiceSessionState.ACTIVELY_LISTENING
                                            }, onToggleMicrophone = {
                                                VoiceSessionUi.paused.value = !VoiceSessionUi.paused.value
                                            }, onEndCall = { endCall() })
                                    })
                        }
                    }
                }
            } }
            awaitWispFixtureReady(fixtureText)
            val wisp = awaitWispBounds("jarvis_wisp")
            val idleViewport = awaitWispBounds("jarvis_wisp_viewport")
            fun isModestlyExpanded(bounds: android.graphics.Rect) =
                bounds.width() >= idleViewport.width() * 1.15f && bounds.width() <= idleViewport.width() * 1.35f &&
                    bounds.height() >= idleViewport.height() * 1.15f && bounds.height() <= idleViewport.height() * 1.35f
            val transcript = awaitWispBounds("conversation_transcript")
            assertTrue("Wisp stays above the transcript", wisp.bottom <= transcript.top)
            assertEquals("Wisp is centered", device.displayWidth / 2, wisp.centerX())
            assertFalse("There is no visual-mode setting", device.hasObject(By.text("Visual mode")))
            captureWispEvidence("test49_wispReady")
            busy.value = true
            pose("Thinking")
            observed.value = AgentActivitySnapshot(1, "another-conversation", AgentActivityKind.CHECKING_REFERENCES, "Checking references")
            pose("Thinking")
            observed.value = AgentActivitySnapshot(2, history.current.value.id, AgentActivityKind.CHECKING_REFERENCES, "Checking references")
            pose("Checking references")
            captureWispEvidence("test49_wispReferences")
            observed.value = AgentActivitySnapshot(20, history.current.value.id, AgentActivityKind.WORKING,
                "Comparing the three selected routes")
            pose("Comparing the three selected routes")
            captureWispEvidence("test49_wispPublicProgress")
            observed.value = AgentActivitySnapshot(21, history.current.value.id, AgentActivityKind.WORKING,
                "Reading https://example.org/private?token=secret")
            pose("Working on your request")
            observed.value = null
            busy.value = false
            observed.value = AgentActivitySnapshot(3, history.current.value.id, AgentActivityKind.ERROR, "Something went wrong")
            pose("Something went wrong")
            busy.value = true
            pose("Thinking")
            observed.value = null
            busy.value = false
            for ((tool, label) in listOf("open_app" to "Opening app", "read_battery" to "Checking battery",
                "set_volume" to "Adjusting media volume to 25%", "screen_observe" to "Reading the current screen",
                "screen_tap" to "Tapping the selected screen control", "screen_scroll" to "Scrolling the screen")) {
                task(ToolTaskState.RUNNING, tool)
                pose(label)
                captureWispEvidence("test49_wisp_$tool")
            }
            task(ToolTaskState.WAITING_RESOURCE, "screen_scroll", 1)
            pose("Waiting for a resource")
            captureWispEvidence("test49_wispWaiting")
            task(ToolTaskState.WAITING_APPROVAL, "set_volume", 1)
            pose("Waiting for your approval")
            assertEquals("Animation must not approve work", 0, approvals.get())
            captureWispEvidence("test49_wispApproval")
            task(ToolTaskState.SUCCEEDED, "set_volume", 2, ExecutionResult.Outcome.SUCCEEDED)
            pose("Task complete")
            captureWispEvidence("test49_wispSuccess")
            idle()
            task(ToolTaskState.UNKNOWN_OUTCOME, "open_app", 3, ExecutionResult.Outcome.UNKNOWN_COMPLETION)
            pose("Check the task outcome")
            captureWispEvidence("test49_wispUnknownOutcome")
            assertEquals(0, approvals.get())
            journal.value = ToolTaskJournal()
            idle()
            // Keep the same storage failure throughout real UI state transitions; animation must
            // neither hide the phone-action warning nor require clearing its process-owned state.
            taskError.value = journalWarning
            idle()
            clickEnabled(By.res("phone_tasks_open"))
            assertNotNull(find(By.text(journalWarning)))
            // This dialog may own a separate window; retain its established full-display capture.
            captureEvidence("test49_wispJournalWarning")
            clickEnabled(By.text("Done"))
            busy.value = true
            pose("Thinking")
            observed.value = AgentActivitySnapshot(4, history.current.value.id, AgentActivityKind.CHECKING_REFERENCES, "Checking references")
            pose("Checking references")
            observed.value = null
            busy.value = false
            idle()
            enterText(By.res("chat_composer"), "Keep my Wisp draft")
            hideKeyboardWithoutNavigating()
            repeat(2) {
                clickEnabled(By.res("voice_call_open"))
                pose("Listening")
                awaitWispSize("An active call must give Wisp modestly more room", ::isModestlyExpanded)
                val expanded = awaitWispBounds("jarvis_wisp")
                assertTrue("The enlarged call character must remain above the transcript",
                    expanded.bottom <= awaitWispBounds("conversation_transcript").top)
                VoiceSessionUi.level.value = .7f
                assertNotNull(find(By.res("conversation_transcript")))
                device.pressBack()
                assertNotNull("Back must retain the character", find(By.res("jarvis_wisp")))
                assertNotNull("Back must retain call controls", find(By.res("voice_call_end")))
                clickEnabled(By.res("voice_call_pause"))
                pose("Microphone paused")
                awaitWispSize("Pausing a call must retain its larger workspace", ::isModestlyExpanded)
                clickEnabled(By.res("voice_call_pause"))
                pose("Listening")
                VoiceSessionUi.phase.value = VoicePhase.PREPARING
                pose("Preparing")
                VoiceSessionUi.phase.value = VoicePhase.THINKING
                call.value = VoiceSessionState.PROCESSING
                pose("Thinking")
                VoiceSessionUi.phase.value = VoicePhase.SPEAKING
                call.value = VoiceSessionState.SPEAKING
                playback.value = VoicePlaybackFrame("A controlled playback frame", .8f)
                pose("Speaking")
                captureWispEvidence("test49_wispSpeaking$it")
                // One body and one independent audio owner: work must not hide the speaking
                // mouth, and stopping speech must not cancel or approve the observed tool.
                task(ToolTaskState.RUNNING, "screen_scroll", 10L + it)
                pose("Scrolling the screen")
                assertEquals(VoicePhase.SPEAKING, VoiceSessionUi.phase.value)
                assertEquals(.8f, playback.value.level, 0f)
                if (it == 0) {
                    captureWispEvidence("test49_wispSpeakingWhileScrolling")
                    renderedAnimationScale("0")
                    captureWispEvidence("test49_wispReducedMotionWhileSpeaking")
                    renderedAnimationScale("1")
                }
                clickEnabled(By.res("voice_call_stop_reply"))
                pose("Scrolling the screen")
                assertEquals(VoicePhase.LISTENING, VoiceSessionUi.phase.value)
                assertEquals(0f, playback.value.level, 0f)
                assertEquals(ToolTaskState.RUNNING, journal.value!!.attempts.single().state)
                if (it == 0) captureWispEvidence("test49_wispStoppedReplyWhileScrolling")
                journal.value = ToolTaskJournal()
                pose("Listening")
                awaitWispSize("Stopping a reply must not shrink an active call", ::isModestlyExpanded)
                clickEnabled(By.res("voice_call_end"))
                idle()
                awaitWispSize("Ending a call must return Wisp to its idle size") {
                    kotlin.math.abs(it.height() - idleViewport.height()) <= 1 && kotlin.math.abs(it.width() - idleViewport.width()) <= 1
                }
                assertTrue(find(By.res("chat_composer")).text.contains("Keep my Wisp draft"))
            }
            assertEquals(2, stops.get())
            assertEquals(2, ends.get())
            assertEquals("Poses cannot clear the journal failure", journalWarning, taskError.value)
            assertEquals("Poses cannot authorize paused phone actions", 0, approvals.get())
            assertNotNull("Ending a call never removes Wisp", find(By.res("jarvis_wisp")))
            assertFalse(device.hasObject(By.res("voice_call_orb")))
        } catch (failure: Throwable) {
            fixtureFailure = failure
            throw failure
        } finally {
            val restoreFailure = runCatching {
                if (animationScaleBefore == "null" || animationScaleBefore.isBlank()) {
                    device.executeShellCommand("settings delete global animator_duration_scale")
                } else {
                    // Validated as a finite numeric string before changing the setting.
                    device.executeShellCommand("settings put global animator_duration_scale $animationScaleBefore")
                }
                assertEquals(if (animationScaleBefore.isBlank()) "null" else animationScaleBefore,
                    device.executeShellCommand("settings get global animator_duration_scale").trim())
            }.exceptionOrNull()
            motionFrameWait.set(null)
            windowTarget.set(null)
            VoiceSessionUi.armed.value = false
            VoiceSessionUi.phase.value = VoicePhase.IDLE
            VoiceSessionUi.paused.value = false
            VoiceSessionUi.level.value = 0f
            VoiceSessionUi.liveTranscript.value = ""
            // Never mask the fixture's original assertion/capture error with cleanup failure.
            if (restoreFailure != null) fixtureFailure?.addSuppressed(restoreFailure) ?: throw restoreFailure
        }
    }

    @Test fun test80_cameraCaptureEndsWithSpokenFarewell() = runBlocking {
        val runtime = JarvisRuntime.get(context)
        val controller = runtime.voiceSessionController
        val originalTurn = runtime.voiceTurnJob
        val originalArmed = runtime.voiceSessionArmed
        val originalServiceStop = VoiceCallService.stopRequested.value
        val originalOnCallBegan = controller.onCallBegan
        val originalVisionController = CallVisionRegistry.controller
        val originalVisionHub = CallVisionRegistry.hub
        // POST_NOTIFICATIONS is a suite-level fixture prerequisite on API
        // 33+ (see the grant below): the disposable suite owns it for its
        // entire lifetime. The initial grant state is intentionally NOT
        // captured for restoration — restoring would mean revoking the
        // live test process's own permission, which is what killed the
        // instrumented process in CI (test49, run 1228 attempt 2).
        val finishingTurn = Job()
        val callIds = mutableListOf<String>()
        // A fake vision pipeline stands in for the camera: the runtime's
        // farewell path must end its capture while wake listening stays
        // armed. The production onCallBegan hook (which would start the real
        // VideoCallService) is parked so the fake stays registered.
        val binder = object : CallVisionController.VideoBinder {
            var binds = 0
            var unbinds = 0
            override val cleanupUnresolved = false
            override fun bind(): CallVisionController.BindResult {
                binds++
                return CallVisionController.BindResult.Started
            }
            override fun unbind() { unbinds++ }
        }
        val hub = VisionFrameHub()
        val vision = CallVisionController(binder, hub = hub)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        fun awaitVideo(message: String, condition: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < until && !condition()) SystemClock.sleep(100)
            assertTrue(message, condition())
        }
        fun videoNotificationText(): String? = notificationManager.activeNotifications
            .firstOrNull {
                it.notification.extras.getString(Notification.EXTRA_TITLE) == "Jarvis video"
            }?.notification?.extras?.getString(Notification.EXTRA_TEXT)
        // Failure diagnostics: permission status, the enabled state, every
        // active notification id, and each relevant notification's
        // title/text — so a repeat failure shows whether the notification
        // is absent (prerequisite) or present-but-stale (refresh path).
        fun videoNotificationDiagnostics(): String {
            val granted = context.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val enabled = notificationManager.areNotificationsEnabled()
            val actives = notificationManager.activeNotifications.joinToString("; ") {
                val n = it.notification
                "id=${it.id} title=${n.extras.getString(Notification.EXTRA_TITLE)} " +
                    "text=${n.extras.getString(Notification.EXTRA_TEXT)}"
            }
            return "POST_NOTIFICATIONS granted=$granted, areNotificationsEnabled=$enabled, active=[$actives]"
        }
        // The first exception thrown by the test body is captured here
        // BEFORE the finally cleanup runs, so the test report names the
        // original failure even when cleanup or the service watchdog throws
        // afterwards (see the catch/finally below).
        var firstFailure: Throwable? = null
        try {
            controller.onCallBegan = null
            // The farewell refresh only reaches the service/notification
            // path through the live service instance — without one,
            // refreshVideoStatusAfterFarewell is a no-op by design and the
            // fixture would never exercise the production route. Start the
            // real service so the farewell exercises that exact path; the
            // fake pipeline is installed afterwards, so the service's own
            // onCreate pipeline never sees the fixture's calls. The
            // production live-instance guard is untouched.
            //
            // Quiesce first: the previous test's production onCallBegan also
            // starts this real service, and its teardown (stopSelf via the
            // stopRequested fail-safe) races this start. A start that lands
            // mid-teardown trips the system's foreground-start timeout and
            // kills the whole process (ForegroundServiceDidNotStartInTimeException
            // — the deterministic CI crash at this test). Stopping and
            // awaiting the release first makes this a clean, fresh start
            // every time, with the camera released between tests.
            context.stopService(Intent(context, VideoCallService::class.java))
            awaitVideo("A previous video service instance must fully release before a fresh start",
                { VideoCallService.instance == null })
            // API 33+: the video notification is only observable while the
            // suite holds POST_NOTIFICATIONS (device setup installs without
            // -g). Establish it as an explicit fixture prerequisite before
            // the service starts. The disposable suite owns this permission
            // for its entire lifetime: NEVER revoke the target app's
            // permission from inside its live test process — ActivityManager
            // kills the instrumented process when its permission is revoked
            // (proven CI self-kill at test49, run 1228 attempt 2: the pm
            // revoke in the finally block ran while Jarvis was still the
            // live instrumented process). The CI emulator is discarded after
            // the run, so no restoration is needed or safe to attempt here.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                runCatching {
                    device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
                }
                assertTrue("test80 requires POST_NOTIFICATIONS on API 33+ before the video service starts",
                    context.checkSelfPermission(
                        android.Manifest.permission.POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED)
                assertTrue("test80 requires notifications enabled on API 33+ before the video service starts",
                    notificationManager.areNotificationsEnabled())
            }
            // Own the live-call prerequisite before the service observes its fail-safe.
            // The original flag is restored by the existing finally block.
            VoiceCallService.stopRequested.value = false
            context.startForegroundService(Intent(context, VideoCallService::class.java))
            awaitVideo("The video service must register its live instance",
                { VideoCallService.instance != null })
            CallVisionRegistry.controller = vision
            CallVisionRegistry.hub = hub
            runtime.voiceTurnJob = finishingTurn
            runtime.arm()
            assertTrue("The fixture must arm the wake session", runtime.voiceSessionArmed)

            // Grant camera, start a call, capture is active with a cached frame.
            val oldCall = controller.beginCall().also { callIds += it.id }
            assertEquals(CallVisionController.State.ACTIVE,
                vision.start(cameraPermissionGranted = true, callId = oldCall.id))
            hub.register(object : VisionObserver {
                override fun onFrame(jpegBytes: ByteArray, timestampMs: Long) {}
            })
            hub.dispatch(byteArrayOf(1, 2, 3), 100L)
            assertNotNull("The fixture must hold a cached frame before the farewell", hub.latest())

            // The spoken farewell ends the call: capture must end with it.
            // The service handles the refresh asynchronously on its own
            // scope, so the unbind and the notification update are awaited
            // — never assumed synchronous.
            runtime.returnToWakeListening(oldCall.id)
            awaitVideo("A farewell must unbind the camera through the live service refresh",
                { binder.unbinds == 1 })
            assertEquals(CallVisionController.State.IDLE, vision.state)
            assertNull("A farewell must clear the frame cache", hub.latest())
            assertNull(vision.captureCallId())
            try {
                awaitVideo(
                    "The service notification must reflect the IDLE controller after the farewell, never a stale \"Video on\"",
                    { videoNotificationText()?.contains("Video idle") == true })
            } catch (timeout: AssertionError) {
                // The wait timed out: attach failure-time diagnostics here,
                // since the follow-up assertTrue with diagnostics below is
                // unreachable on this path. Evaluated lazily at failure
                // time so a repeat failure records the actual permission /
                // notification state; a diagnostic fault can never mask the
                // original timeout, which is preserved as the cause.
                val diag = runCatching { videoNotificationDiagnostics() }
                    .getOrElse { "diagnostics unavailable: ${it.javaClass.simpleName}" }
                throw AssertionError("${timeout.message}. Timeout diagnostics: $diag", timeout)
            }
            assertTrue(
                "The service notification must reflect the IDLE controller after the farewell, never a stale " +
                    "\"Video on\". Diagnostics: ${videoNotificationDiagnostics()}",
                videoNotificationText()?.contains("Video idle") == true)
            assertTrue("A farewell must keep the user-armed wake session", runtime.voiceSessionArmed)
            assertEquals(VoiceSessionState.PASSIVE_LISTENING, controller.state.value)
            assertNull(controller.currentCallId())
            assertFalse("A farewell must not request service termination",
                VoiceCallService.stopRequested.value)

            // A new call starts a fresh capture under its own identity; a
            // stale farewell for the old call must not end it.
            val newCall = controller.beginCall().also { callIds += it.id }
            assertEquals(CallVisionController.State.ACTIVE,
                vision.start(cameraPermissionGranted = true, callId = newCall.id))
            assertEquals(2, binder.binds)
            runtime.returnToWakeListening(oldCall.id)
            assertEquals("A stale farewell must not end a newer call's capture",
                CallVisionController.State.ACTIVE, vision.state)
            assertEquals(newCall.id, vision.captureCallId())
            assertEquals("A stale farewell must not unbind the camera", 1, binder.unbinds)
            assertEquals(newCall.id, controller.currentCallId())

            // No-resurrection: once the service is gone, a farewell refresh
            // must not bring the service (or its notification) back — the
            // live-instance guard drops the refresh instead.
            context.stopService(Intent(context, VideoCallService::class.java))
            awaitVideo("The video service must release its live instance",
                { VideoCallService.instance == null })
            VideoCallService.refreshVideoStatusAfterFarewell(newCall.id)
            SystemClock.sleep(1_500)
            assertNull("A farewell refresh must never resurrect a stopped service",
                VideoCallService.instance)
            assertNull("A farewell refresh must never resurrect a stopped notification",
                videoNotificationText())
        } catch (t: Throwable) {
            // Stash the FIRST fixture exception before the finally cleanup
            // runs: a body crash (e.g. a NoClassDefFoundError from a
            // removed companion) followed by the service watchdog's own
            // failure would otherwise report only the watchdog's
            // ForegroundServiceDidNotStartInTimeException and hide the real
            // cause. The original exception is rethrown after cleanup below,
            // with any cleanup failure attached as suppressed.
            firstFailure = t
            throw t
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                try {
                    controller.currentCallId()?.takeIf { it in callIds }?.let { controller.end() }
                    callIds.forEach(runtime.voiceCallStore::delete)
                    finishingTurn.cancel()
                    runtime.voiceTurnJob = originalTurn
                    runtime.voiceSessionArmed = originalArmed
                    VoiceCallService.stopRequested.value = originalServiceStop
                    context.stopService(Intent(context, VideoCallService::class.java))
                } finally {
                    controller.onCallBegan = originalOnCallBegan
                    CallVisionRegistry.controller = originalVisionController
                    CallVisionRegistry.hub = originalVisionHub
                    // No POST_NOTIFICATIONS revocation here: revoking the
                    // live instrumented process's own permission makes
                    // ActivityManager kill the process (test49 CI self-kill).
                    // The disposable suite owns the permission; the emulator
                    // is discarded after the run.
                }
            } catch (t: Throwable) {
                cleanupFailure = t
            }
            if (firstFailure != null) {
                // The body's exception is the test result: report it even
                // when cleanup or the later service watchdog threw too.
                cleanupFailure?.let { firstFailure!!.addSuppressed(it) }
                throw firstFailure!!
            }
            cleanupFailure?.let { throw it }
        }
    }

    @Test fun test50_mediaControlDispatchesViaAudioManager() {
        val audio = context.getSystemService(AudioManager::class.java)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context))
        try {
            listOf("play", "pause", "toggle", "next", "previous").forEach { verb ->
                val result = pipeline.execute(ActionRequest("media_control", mapOf("action" to verb)))
                assertTrue("media_control $verb must dispatch: ${result.message}", result.succeeded)
                assertTrue("receipt must describe the dispatch honestly, not claim a playback change",
                    result.message.contains("active media session"))
            }
            val rejected = pipeline.execute(ActionRequest("media_control", mapOf("action" to "rewind")))
            assertFalse("unknown media verb must not dispatch", rejected.succeeded)
            assertEquals("media keys must not change the volume", before,
                audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
        }
    }

    @Test fun test51_mediaTextRequestParsesAndDispatches() {
        // Regression for the Fold 6 report (2026-10-04): text "pause music" must
        // become a Ready media_control plan and dispatch through the real
        // executor. A NotAction plan would fall through to chat and let the
        // model hallucinate success, which is the bug being fixed.
        val plan = ActionTurnPlan.parse("pause music")
        assertTrue("text 'pause music' must parse as an action plan, was $plan",
            plan is ActionTurnPlan.Ready)
        val request = (plan as ActionTurnPlan.Ready).steps.single().request
        assertEquals("media_control", request.name)
        assertEquals("pause", request.arguments["action"])
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context))
        val result = pipeline.execute(request)
        assertTrue("parsed media_control must dispatch: ${result.message}", result.succeeded)
        assertTrue("receipt must describe the dispatch honestly, not claim a playback change",
            result.message.contains("active media session"))
    }

    @Test fun test52_openSettingsWifiShowsSettings() {
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { true }))
        val result = pipeline.execute(ActionRequest("open_settings", mapOf("screen" to "wifi")))
        // Submitted, never promoted: only an observed foreground package
        // may claim a verified opening.
        assertEquals(ExecutionResult.Outcome.UNKNOWN_COMPLETION, result.outcome)
        assertFalse("a submitted launch is not a verified opening: ${result.message}", result.succeeded)
        assertTrue("Wi-Fi settings must actually appear, not merely report success",
            device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 15_000))
        // @After captures the launched Settings screen before closing Jarvis's scenario.
    }

    @Test fun test53_openWebsiteDispatchesHonestReceipt() {
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { true }))
        val result = pipeline.execute(ActionRequest("open_website", mapOf("url" to "example.com")))
        assertEquals("an unobserved website launch stays unconfirmed: ${result.message}",
            ExecutionResult.Outcome.UNKNOWN_COMPLETION, result.outcome)
        assertTrue("receipt must name the normalized URL honestly",
            result.message.contains("https://example.com"))
        val rejected = pipeline.execute(ActionRequest("open_website", mapOf("url" to "javascript:alert(1)")))
        assertFalse("dangerous URL scheme must not dispatch", rejected.succeeded)
    }

    @Test fun test54_navigateDispatchesHonestReceipt() {
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context, canLaunchDirectly = { true }))
        val result = pipeline.execute(ActionRequest("navigate", mapOf("destination" to "1600 Amphitheatre Parkway")))
        assertEquals("an unobserved navigation launch stays unconfirmed: ${result.message}",
            ExecutionResult.Outcome.UNKNOWN_COMPLETION, result.outcome)
        assertTrue("receipt must name the destination honestly",
            result.message.contains("1600 Amphitheatre Parkway"))
        val rejected = pipeline.execute(ActionRequest("navigate", mapOf("destination" to "   ")))
        assertFalse("blank destination must not dispatch", rejected.succeeded)
    }

    // M1c screen control journeys.

    private class FakeScreenBridge(
        var observation: ScreenObservation? = null,
        var available: Boolean = true
    ) : ScreenBridge {
        val tapped = mutableListOf<ScreenNode>()
        val scrolled = mutableListOf<Pair<ScreenNode, ScreenScrollDirection>>()
        val typed = mutableListOf<Pair<ScreenNode, String>>()
        var overlayShown = false

        override fun isAvailable(): Boolean = available
        override fun observe(): ScreenObservation? = observation
        override fun currentWindowIdentity(): String? = observation?.windowIdentity
        override fun currentContentFingerprint(): String? =
            observation?.let { contentFingerprintOf(it.nodes) }
        override fun tap(node: ScreenNode): Boolean {
            tapped += node
            return true
        }
        override fun scroll(node: ScreenNode, direction: ScreenScrollDirection): Boolean {
            scrolled += node to direction
            return true
        }
        override fun type(node: ScreenNode, text: String): Boolean {
            typed += node to text
            return true
        }
        override fun showStopOverlay(taskLabel: String): Boolean {
            overlayShown = true
            return true
        }
        override fun hideStopOverlay() {
            overlayShown = false
        }
    }

    private fun screenFixtureNodes() = listOf(
        ScreenNode("n0", "Search", "button", "10,20-100,80", clickable = true,
            viewId = "com.example.app:id/search", windowIdentity = "com.example.app#1"),
        ScreenNode("n1", "Name", "field", "10,100-400,160", editable = true,
            viewId = "com.example.app:id/name", windowIdentity = "com.example.app#1"),
        ScreenNode("n2", "Results", "list", "0,200-1080,1800", scrollable = true,
            viewId = "com.example.app:id/results", windowIdentity = "com.example.app#1")
    )

    @Suppress("DEPRECATION")
    @Test fun test55_screenObservationExtractsCompactSnapshot() {
        // The real Android tree-walking logic against the live window tree:
        // UiAutomation hands back genuine AccessibilityNodeInfo instances, so
        // getChild/recycle follow the production path exactly.
        val root = instrumentation.uiAutomation.rootInActiveWindow
            ?: throw AssertionError("No active window for screen extraction")
        try {
            val nodes = extractScreenNodes(root)
            assertTrue(
                "the Jarvis screen must expose actionable nodes, got ${nodes.size}",
                nodes.isNotEmpty()
            )
            assertTrue(
                "node IDs must be n<index>",
                nodes.all { it.id.matches(Regex("^n[0-9]{1,4}$")) }
            )
            assertTrue(
                "every node must be actionable or labeled",
                nodes.all { it.clickable || it.editable || it.scrollable || it.label.isNotBlank() }
            )
            val text = ScreenObservation("com.battlesbudz.jarvis.v2", nodes)
                .compactText("abcdef1234567890")
            assertTrue(text.contains("observation token: abcdef1234567890"))
            assertTrue(text.lines().size <= nodes.size.coerceAtMost(64) + 3)
        } finally {
            root.recycle()
        }
        // The service must be declared with the accessibility binding permission.
        val info = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, ScreenControlService::class.java), 0
        )
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", info.permission)
    }

    @Test fun test56_screenTapNeedsVerifiedTarget() {
        val bridge = FakeScreenBridge(observation = ScreenObservation("com.example.app", screenFixtureNodes(), windowIdentity = "com.example.app#1"))
        val session = ScreenControlSession()
        val pipeline = MobileActionPipeline(
            executor = AndroidMobileActionExecutor(context, screenBridge = bridge, screenSession = session)
        )
        // Mutations need an admitted session grant; without it nothing dispatches.
        val denied = pipeline.execute(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to "abcdef1234567890")))
        assertFalse("unadmitted tap must not dispatch: ${denied.message}", denied.succeeded)
        assertTrue(denied.message.contains("approval"))
        assertTrue(bridge.tapped.isEmpty())

        assertEquals(AdmitResult.Admitted, session.admit("test-41", userApproved = true))
        val observed = pipeline.execute(ActionRequest("screen_observe"))
        assertTrue("observe must succeed: ${observed.message}", observed.succeeded)
        val token = session.currentToken!!
        assertTrue(observed.message.contains("observation token: $token"))

        // A well-formed but stale token never dispatches.
        val stale = pipeline.execute(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to "0000000000000000")))
        assertFalse("stale token must not dispatch: ${stale.message}", stale.succeeded)
        assertTrue(stale.message.contains("stale"))
        assertTrue(bridge.tapped.isEmpty())

        // Fresh token plus verified target dispatches with an honest receipt.
        val tapped = pipeline.execute(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to token)))
        assertTrue("verified tap must dispatch: ${tapped.message}", tapped.succeeded)
        assertEquals(listOf("n0"), bridge.tapped.map { it.id })

        // Wrong-kind target is rejected without dispatch.
        val wrongKind = pipeline.execute(
            ActionRequest("screen_type", mapOf("target" to "n0", "text" to "hi", "token" to token))
        )
        assertFalse("non-editable type target must not dispatch", wrongKind.succeeded)
        assertTrue(bridge.typed.isEmpty())

        // Release ends the grant; the overlay hides with it.
        assertTrue(bridge.showStopOverlay("test-41"))
        session.release()
        bridge.hideStopOverlay()
        assertFalse(session.isAdmitted)
        assertFalse(bridge.overlayShown)
    }

    @Test fun test57_touchPauseAndIdleResumeReobserves() {
        var now = 10_000L
        val bridge = FakeScreenBridge(observation = ScreenObservation("com.example.app", screenFixtureNodes(), windowIdentity = "com.example.app#1"))
        val session = ScreenControlSession(clock = { now }, touchIdleMs = 3_000L)
        val pipeline = MobileActionPipeline(
            executor = AndroidMobileActionExecutor(context, screenBridge = bridge, screenSession = session)
        )
        session.admit("test-42", userApproved = true)
        assertTrue(pipeline.execute(ActionRequest("screen_observe")).succeeded)
        val token = session.currentToken!!

        // Manual touch pauses dispatch.
        session.noteTouchStart()
        val paused = pipeline.execute(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to token)))
        assertFalse("tap during manual touch must pause: ${paused.message}", paused.succeeded)
        assertTrue(paused.message.contains("touching the screen"))
        assertTrue(bridge.tapped.isEmpty())

        // After the touch-idle interval the executor re-observes the changed
        // screen without a countdown; the pre-touch token is stale by design.
        session.noteTouchEnd()
        now += 3_000L
        bridge.observation = ScreenObservation(
            "com.example.other",
            listOf(ScreenNode("n0", "Other", "button", "0,0-50,50", clickable = true,
                viewId = "com.example.other:id/other", windowIdentity = "com.example.other#1")),
            windowIdentity = "com.example.other#1"
        )
        val resumed = pipeline.execute(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to token)))
        assertFalse("pre-touch token must be stale after resume: ${resumed.message}", resumed.succeeded)
        assertTrue(resumed.message.contains("stale"))
        assertTrue(bridge.tapped.isEmpty())

        // The resume re-observed: a fresh observation token dispatches.
        val token2 = session.currentToken!!
        assertNotEquals(token, token2)
        val retried = pipeline.execute(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to token2)))
        assertTrue("fresh token must dispatch after resume: ${retried.message}", retried.succeeded)
        assertEquals(listOf("n0"), bridge.tapped.map { it.id })
    }

    @Test fun test58_screenToolsReportHonestlyWhenServiceDisabled() {
        // Real service bridge; the service is not enabled on the emulator, so
        // every screen tool must answer honestly instead of claiming effects.
        val pipeline = MobileActionPipeline(executor = AndroidMobileActionExecutor(context))
        val observed = pipeline.execute(ActionRequest("screen_observe"))
        assertFalse("observe without the enabled service must not succeed", observed.succeeded)
        assertTrue(
            "receipt must name the missing Accessibility enablement, was: ${observed.message}",
            observed.message.contains("Accessibility")
        )
        val tap = pipeline.execute(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to "abcdef1234567890")))
        assertFalse("tap without a grant must not dispatch", tap.succeeded)
        assertTrue(tap.message.contains("approval"))
    }

    // M1d task/conversation scheduling journeys.

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test59_panelApprovalAdmitsScreenSessionAndDispatchesExactly() {
        // M1d approval-UI wiring (T07): approving a screen task in the task
        // panel admits the screen-control session grant for its group, and the
        // ledger claim consumes the approval atomically with dispatch
        // eligibility. A changed target invalidates the prior approval (D13).
        val file = File(context.cacheDir, "release-m1d-approval.json").apply { delete() }
        val ledger = ToolTaskLedger(FileToolTaskStore(file))
        val approvals = ActionApprovalStore(FileToolTaskStore(file))
        val session = ScreenControlSession()
        val bridge = FakeScreenBridge(observation = ScreenObservation("com.example.app", screenFixtureNodes(), windowIdentity = "com.example.app#1"))
        val token = session.recordObservation(checkNotNull(bridge.observation))
        val executor = AndroidMobileActionExecutor(context, screenBridge = bridge, screenSession = session)
        val admission = ScreenApprovalAdmission(session)
        fun tapRequest(target: String) = ActionRequest("screen_tap", mapOf("target" to target, "token" to token))
        val group = ledger.admit(listOf(tapRequest("n0")), "panel-thread")
        val attempt = checkNotNull(ledger.get(group.attemptIds.single()))
        val pending = ledger.requestApproval(attempt.id, attempt.generation, "native", MobileToolCatalog.VERSION)
        // D13: the target changes before approval...
        val changedOnce = checkNotNull(ledger.revise(pending.task.id, pending.task.generation, tapRequest("n1")))
        // ...so the stale approval cannot admit the session.
        assertTrue("changed target must invalidate the prior approval",
            admission.admitForApproval(checkNotNull(ledger.get(changedOnce.id)), pending.approval) is AdmitResult.Denied)
        // Fresh approval for the current action goes through the panel.
        val changedBack = checkNotNull(ledger.revise(changedOnce.id, changedOnce.generation, tapRequest("n0")))
        val fresh = ledger.requestApproval(changedBack.id, changedBack.generation, "native", MobileToolCatalog.VERSION)
        val journal = MutableStateFlow<ToolTaskJournal?>(ledger.journal())
        val approvalCompleted = java.util.concurrent.CountDownLatch(1)
        val decide: (String, Long, String) -> Unit = { id, generation, command ->
            val a = ledger.get(id)?.takeIf { it.generation == generation }
            if (a != null && command == "approve") {
                val approval = a.approvalId?.let { approvals.get(it) }
                if (approval != null) {
                    // The M1d approval wiring: the panel approval admits the
                    // session grant for this group before the ledger claim.
                    admission.admitForApproval(a, approval)
                    JournaledActionPipeline(ledger, executor).executeAttempt(a, approval)
                    if (session.holderGroupId == a.groupId) bridge.showStopOverlay("test-45")
                }
            }
            journal.value = ledger.journal()
            approvalCompleted.countDown()
        }
        try {
            activity.onActivity { host -> host.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    val snapshot by journal.collectAsState()
                    com.battlesbudz.jarvis.v2.ui.PhoneTaskPanel(snapshot, "panel-thread", null, decide)
                } }
            } }
            find(By.res("phone_tasks_open")).click()
            captureEvidence("m1d_screen_approval")
            find(By.res("task_approve_${fresh.task.id}")).click()
            // The tap is only an intermediate effect: the UI callback still has
            // to save its receipt, show Stop and publish the journal afterward.
            assertTrue("Panel approval callback must complete within the UI deadline",
                approvalCompleted.await(10, java.util.concurrent.TimeUnit.SECONDS))
            activity.onActivity {
                assertEquals("approved tap must dispatch exactly once", listOf("n0"), bridge.tapped.map { it.id })
                assertEquals("session grant must belong to the approved group", group.id, session.holderGroupId)
                assertEquals(ToolTaskState.SUCCEEDED, ledger.get(fresh.task.id)?.state)
                assertTrue("approval must be consumed", checkNotNull(approvals.get(fresh.approval.id)).consumed)
                assertTrue("Stop overlay shows while the admitted group holds the lease", bridge.overlayShown)
                // A finished group releases the lease and the overlay hides (T04).
                assertTrue(session.releaseIf(group.id))
                bridge.hideStopOverlay()
                assertFalse(session.isAdmitted)
                assertFalse(bridge.overlayShown)
            }
        } finally { file.delete() }
    }

    @Test fun test60_conflictingScreenTaskQueuesBehindTheLease() {
        // T02: a follow-up screen task approved while another group holds the
        // lease is denied a second grant and stays waiting for its turn; it is
        // never rejected and never steals the lease.
        val session = ScreenControlSession()
        val bridge = FakeScreenBridge(observation = ScreenObservation("com.example.app", screenFixtureNodes(), windowIdentity = "com.example.app#1"))
        val token = session.recordObservation(checkNotNull(bridge.observation))
        val ledger = ToolTaskLedger()
        val admission = ScreenApprovalAdmission(session)
        fun parkTap(): Triple<ToolTaskGroup, ToolTaskAttempt, ActionApprovalRequest> {
            val group = ledger.admit(listOf(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to token))), "thread-1")
            val attempt = checkNotNull(ledger.get(group.attemptIds.single()))
            val pending = ledger.requestApproval(attempt.id, attempt.generation, "native", MobileToolCatalog.VERSION)
            return Triple(group, pending.task, pending.approval)
        }
        val (groupA, _, approvalA) = parkTap()
        val taskA = checkNotNull(ledger.get(groupA.attemptIds.single()))
        assertEquals(AdmitResult.Admitted, admission.admitForApproval(taskA, approvalA))
        val (groupB, _, approvalB) = parkTap()
        val taskB = checkNotNull(ledger.get(groupB.attemptIds.single()))
        val denied = admission.admitForApproval(taskB, approvalB)
        assertTrue("second grant must be denied while the lease is held, was: $denied", denied is AdmitResult.Denied)
        assertEquals("lease must stay with the first group", groupA.id, session.holderGroupId)
        assertEquals("denied task stays waiting for its turn", ToolTaskState.WAITING_APPROVAL, ledger.get(taskB.id)?.state)
        assertFalse("denied approval stays unconsumed",
            checkNotNull(ledger.journal().approvals.find { it.id == approvalB.id }).consumed)
        // The first group finishes: the lease releases and the queued task can
        // take its turn.
        assertTrue(session.releaseIf(groupA.id))
        assertEquals(AdmitResult.Admitted, admission.admitForApproval(taskB, approvalB))
        assertEquals(groupB.id, session.holderGroupId)
        // Independent (non-screen) work is never blocked by the lease.
        val scheduler = TaskScheduler()
        assertEquals(ScheduleDecision.RunNow, scheduler.schedule(
            scheduler.resourceFor(ActionRequest("read_battery")), listOf(TaskResource(TaskResourceKind.SCREEN_LEASE))))
    }

    @Test fun test61_progressNotificationPostsSilentlyDuringDnd() {
        // T04/T15/D35: task progress posts to notifications immediately and
        // silently, even during Do Not Disturb — never deferred.
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching {
            device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        }
        val dndBefore = runCatching { device.executeShellCommand("settings get global zen_mode").trim() }.getOrNull()
        runCatching { device.executeShellCommand("settings put global zen_mode 1") }
        try {
            val dndOn = runCatching { device.executeShellCommand("settings get global zen_mode").trim() }.getOrNull() == "1"
            val working = TaskStatusProjection("group-47", "Test task", TaskProjectionState.WORKING,
                1, 3, "Test task: working (1 of 3 steps done).", listOf("step one done"))
            assertTrue("progress must post", TaskProgressNotification.postProgress(context, working))
            val id = 0x7a000000 or ("group-47".hashCode() and 0x00ffffff)
            var posted = false
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            while (!posted && android.os.SystemClock.uptimeMillis() < deadline) {
                posted = manager.activeNotifications.any { it.id == id }
                if (!posted) Thread.sleep(200)
            }
            assertTrue("progress notification must be posted immediately${if (dndOn) " during Do Not Disturb" else ""}", posted)
            val finished = working.copy(state = TaskProjectionState.FINISHED, completedSteps = 3,
                statusLine = "Test task: done (3 of 3 steps).",
                stepReceipts = listOf("step one done", "step two done", "step three done"))
            assertTrue("finished must post", TaskProgressNotification.postFinished(context, finished))
            TaskProgressNotification.cancel(context, "group-47")
        } finally {
            runCatching { device.executeShellCommand("settings put global zen_mode ${dndBefore ?: 0}") }
        }
    }

    @Test fun test62_finishedScreenGroupReleasesLeaseNotifiesAndProjects() {
        // T04: ending a call does not cancel admitted work; when the group
        // finishes, the screen lease releases, the Stop overlay hides, and
        // the ordered projection reports completion.
        val session = ScreenControlSession()
        val bridge = FakeScreenBridge(observation = ScreenObservation("com.example.app", screenFixtureNodes(), windowIdentity = "com.example.app#1"))
        val token = session.recordObservation(checkNotNull(bridge.observation))
        val ledger = ToolTaskLedger()
        val admission = ScreenApprovalAdmission(session)
        val group = ledger.admit(listOf(ActionRequest("screen_tap", mapOf("target" to "n0", "token" to token))), "thread-1")
        val attempt = checkNotNull(ledger.get(group.attemptIds.single()))
        val pending = ledger.requestApproval(attempt.id, attempt.generation, "native", MobileToolCatalog.VERSION)
        assertEquals(AdmitResult.Admitted, admission.admitForApproval(pending.task, pending.approval))
        // "Call ends": detaching audio never touches admitted work.
        assertEquals(ToolTaskState.WAITING_APPROVAL, ledger.get(attempt.id)?.state)
        assertEquals(group.id, session.holderGroupId)
        val executor = AndroidMobileActionExecutor(context, screenBridge = bridge, screenSession = session)
        assertTrue(bridge.showStopOverlay("test-48"))
        val result = JournaledActionPipeline(ledger, executor).executeAttempt(pending.task, pending.approval)
        assertTrue("approved tap must dispatch: ${result.message}", result.succeeded)
        assertEquals(listOf("n0"), bridge.tapped.map { it.id })
        // Finished group: release the lease, hide the overlay, project.
        assertTrue(session.releaseIf(group.id))
        bridge.hideStopOverlay()
        assertFalse(session.isAdmitted)
        assertFalse(bridge.overlayShown)
        val projection = checkNotNull(TaskProgressProjector().project(ledger.journal(), group.id))
        assertEquals(TaskProjectionState.FINISHED, projection.state)
        assertTrue(projection.isTerminal)
        assertEquals(listOf("Tapped \"Search\"."), projection.stepReceipts)
        assertEquals(1, projection.completedSteps)
    }

    @Test fun test63_sourceAccessDenialBlocksDispatchAcrossAdapters() {
        // T08: first-source access is remembered per family; a denial or
        // revocation blocks dispatch on every adapter with a truthful
        // receipt, and a new tool can never broaden an existing grant's
        // scope. The approval claim path is blocked too.
        val dispatched = AtomicInteger(0)
        val executor = MobileActionExecutor { dispatched.incrementAndGet(); ExecutionResult(true, "ok") }
        val ledger = ToolTaskLedger()
        val access = ToolSourceAccess(ledger)
        val pipeline = JournaledActionPipeline(ledger, executor,
            sourceAccess = access,
            capabilityProbe = ToolCapabilityProbe { null },
            lockGate = DeviceLockGate(isLocked = { false }))
        // First grant: read_battery succeeds and its family access is remembered.
        assertTrue(pipeline.execute(ActionRequest("read_battery")).succeeded)
        val record = ledger.journal().sourceAccess.single { it.family == "phone" }
        assertEquals(SourceAccessState.GRANTED, record.state)
        assertEquals(ToolSourcePolicy.familyScopes("phone"), record.scopes)
        // Revocation blocks the direct path with an honest receipt.
        assertTrue(access.revoke("phone"))
        val denied = pipeline.execute(ActionRequest("set_volume", mapOf("level" to "25")))
        assertFalse(denied.succeeded)
        assertEquals(ExecutionResult.Outcome.DENIED_PERMISSION, denied.outcome)
        // Revocation blocks the approval claim path too: no dispatch.
        val group = ledger.admit(listOf(ActionRequest("read_battery")), "thread-49")
        val attempt = checkNotNull(ledger.get(group.attemptIds.single()))
        val pending = ledger.requestApproval(attempt.id, attempt.generation, "native", MobileToolCatalog.VERSION)
        assertNull("revoked family must not claim",
            ledger.claim(pending.task.id, pending.task.generation, approval = pending.approval))
        assertEquals("exactly one real dispatch happened", 1, dispatched.get())
        // A new tool can never broaden an existing grant's scope: a record
        // claiming another family's scopes does not admit.
        val tamperedStore = InMemoryToolTaskStore()
        tamperedStore.updateJournal { j -> j.copy(sourceAccess = listOf(
            ToolSourceAccessRecord("web", setOf("screen.control"), SourceAccessState.GRANTED, 0))) }
        val tamperedDenial = ToolSourceAccess(ToolTaskLedger(tamperedStore))
            .denial(ActionRequest("open_website", mapOf("url" to "https://example.com")))
        assertNotNull("out-of-family scope must not admit", tamperedDenial)
        assertEquals(ExecutionResult.Outcome.DENIED_PERMISSION, tamperedDenial!!.outcome)
    }

    @Test fun test64_lockedDeviceGatesSensitiveActions() {
        // T09: on a locked device, sensitive actions hand off to unlock;
        // owner recognition is gated (a voice match never authorizes); the
        // non-sensitive battery read still dispatches through the real
        // Android adapter. The CI emulator ships without a lock screen, so
        // the journey sets a real PIN via locksettings first — the keyguard
        // state below is genuine Android lock state, not a fixture.
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val keyguardDismissal = AtomicReference("not_requested")
        var authenticatedBeforeClear = false
        device.executeShellCommand("locksettings set-pin 1234")
        try {
            device.sleep()
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            while (keyguard?.isDeviceLocked != true && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(200)
            }
            assertTrue("device must be locked for this journey (locksettings PIN must take effect)",
                keyguard?.isDeviceLocked == true)
            val gate = androidLockGate(context)
            assertEquals(OwnerRecognitionMode.GATED, gate.ownerRecognition)
            val dispatched = AtomicInteger(0)
            val fake = MobileActionExecutor { dispatched.incrementAndGet(); ExecutionResult(true, "ok") }
            val realExecutor = AndroidMobileActionExecutor(context)
            val pipeline = JournaledActionPipeline(ToolTaskLedger(), fake,
                sourceAccess = null, capabilityProbe = null, lockGate = gate)
            val realPipeline = JournaledActionPipeline(ToolTaskLedger(), realExecutor,
                sourceAccess = null, capabilityProbe = null, lockGate = gate)
            val battery = realPipeline.execute(ActionRequest("read_battery"))
            assertTrue("non-sensitive read works while locked: ${battery.message}", battery.succeeded)
            assertTrue("real battery receipt, was: ${battery.message}",
                battery.message.startsWith("Battery is at"))
            val tap = pipeline.execute(
                ActionRequest("screen_tap", mapOf("target" to "n0", "token" to "0123456789abcdef")))
            assertEquals(ExecutionResult.Outcome.NEEDS_UNLOCK, tap.outcome)
            assertTrue("handoff must name the lock, was: ${tap.message}",
                tap.message.contains("locked", ignoreCase = true))
            assertFalse("never describe a voice match as authorization",
                tap.message.contains("voice", ignoreCase = true))
            val volume = pipeline.execute(ActionRequest("set_volume", mapOf("level" to "25")))
            assertEquals(ExecutionResult.Outcome.NEEDS_UNLOCK, volume.outcome)
            assertEquals("only the battery read dispatched", 0, dispatched.get())
        } finally {
            // Authenticate the known disposable fixture before removing its PIN.
            // Clearing credentials while the PIN view is already showing can leave
            // SystemUI in that stale security mode even when isDeviceSecure is false.
            val cleanupDeadline = android.os.SystemClock.uptimeMillis() + 10_000
            var pinSubmitted = false
            try {
                device.wakeUp()
                if (android.os.SystemClock.uptimeMillis() < cleanupDeadline) {
                    activity.onActivity { host ->
                        if (android.os.SystemClock.uptimeMillis() < cleanupDeadline &&
                            keyguard?.isDeviceSecure == true) {
                            keyguardDismissal.set("requested")
                            keyguard.requestDismissKeyguard(host,
                                object : android.app.KeyguardManager.KeyguardDismissCallback() {
                                    override fun onDismissSucceeded() { keyguardDismissal.set("succeeded") }
                                    override fun onDismissError() { keyguardDismissal.set("error") }
                                    override fun onDismissCancelled() { keyguardDismissal.set("cancelled") }
                                })
                        } else keyguardDismissal.set("precondition_changed")
                    }
                }
                var pinReady = false
                while (android.os.SystemClock.uptimeMillis() < cleanupDeadline && !pinReady) {
                    pinReady = fixturePinEntryReady()
                    if (!pinReady) Thread.sleep(100)
                }
                if (android.os.SystemClock.uptimeMillis() < cleanupDeadline && pinReady &&
                    keyguard?.isDeviceSecure == true && keyguard.isKeyguardLocked) {
                    // Hardware digit/confirm events follow SystemUI's real PIN path.
                    // Submit exactly once, only to the observed focused PIN field;
                    // no coordinate assumptions, repeated guesses or per-key idle waits.
                    pinSubmitted = true
                    device.executeShellCommand("input keyevent 8 9 10 11 66")
                }
                while (android.os.SystemClock.uptimeMillis() < cleanupDeadline &&
                    (keyguard?.isDeviceLocked == true || keyguard?.isKeyguardLocked == true)) {
                    Thread.sleep(100)
                }
                authenticatedBeforeClear = pinSubmitted && keyguard?.isDeviceLocked == false &&
                    keyguard.isKeyguardLocked == false
            } catch (error: Exception) {
                keyguardDismissal.set("cleanup_failed:${error.javaClass.simpleName}")
            } finally {
                // Always remove the disposable credential, including a failed unlock.
                // The explicit authentication assertion below prevents this removal
                // from disguising a failed real keyguard transition.
                runCatching { device.executeShellCommand("locksettings clear --old 1234") }
            }
            while (android.os.SystemClock.uptimeMillis() < cleanupDeadline &&
                (keyguard?.isDeviceSecure == true || keyguard?.isDeviceLocked == true ||
                    keyguard?.isKeyguardLocked == true || !device.hasObject(By.res("model_browse")))) {
                Thread.sleep(100)
            }
            android.util.Log.i("JarvisVerification", "test64 dismissal=${keyguardDismissal.get()} " +
                "pinSubmitted=$pinSubmitted authenticatedBeforeClear=$authenticatedBeforeClear " +
                "secure=${keyguard?.isDeviceSecure} deviceLocked=${keyguard?.isDeviceLocked} " +
                "keyguardShowing=${keyguard?.isKeyguardLocked}")
        }
        assertTrue("The fixture PIN must authenticate before credential removal", authenticatedBeforeClear)
        assertFalse("PIN must be cleared so later journeys run unlocked",
            keyguard?.isDeviceLocked == true)
        assertFalse("The fixture PIN must no longer secure the device", keyguard?.isDeviceSecure == true)
        assertFalse("Keyguard UI must be dismissed before later journeys (${keyguardDismissal.get()})",
            keyguard?.isKeyguardLocked == true)
        assertNotNull("The owned setup screen must be accessible after lock cleanup",
            device.findObject(By.res("model_browse")))
    }

    @Suppress("DEPRECATION")
    private fun fixturePinEntryReady(): Boolean {
        // Read the framework snapshot directly so each poll does not introduce
        // UiAutomator's unrelated idle-wait timeout into the cleanup deadline.
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        try {
            fun ready(id: String, field: Boolean = false): Boolean {
                val nodes = root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/$id")
                return try {
                    nodes.any { node ->
                        node.packageName?.toString() == "com.android.systemui" &&
                            node.isVisibleToUser && node.isEnabled &&
                            if (field) node.isFocused && node.isPassword else node.isClickable
                    }
                } finally { nodes.forEach { it.recycle() } }
            }
            return ready("pinEntry", field = true) &&
                listOf("key1", "key2", "key3", "key4", "key_enter").all { ready(it) }
        } finally { root.recycle() }
    }

    @Test fun test65_crossFamilyRegressionInvalidArgsProduceNoEffects() {
        // T01 regression across all M1 command families: invalid args are
        // rejected before any adapter runs, so nothing changes on the device.
        val audio = context.getSystemService(AudioManager::class.java)
        val volumeBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val bridge = FakeScreenBridge(observation = ScreenObservation("com.example.app", screenFixtureNodes(), windowIdentity = "com.example.app#1"))
        val executor = AndroidMobileActionExecutor(context, screenBridge = bridge)
        val dispatched = AtomicInteger(0)
        val counting = MobileActionExecutor { action -> dispatched.incrementAndGet(); executor.execute(action) }
        val ledger = ToolTaskLedger()
        val pipeline = JournaledActionPipeline(ledger, counting,
            sourceAccess = ToolSourceAccess(ledger),
            capabilityProbe = ToolCapabilityProbe { null },
            lockGate = DeviceLockGate(isLocked = { false }))
        val invalid = listOf(
            ActionRequest("set_volume", mapOf("level" to "999")),
            ActionRequest("media_control", mapOf("action" to "explode")),
            ActionRequest("open_app", mapOf("app" to "   ")),
            ActionRequest("open_website", mapOf("url" to "javascript:alert(1)")),
            ActionRequest("open_settings", mapOf("screen" to "nuclear")),
            ActionRequest("navigate", mapOf("destination" to "   ")),
            ActionRequest("screen_tap", mapOf("target" to "zzz", "token" to "bad")),
            ActionRequest("screen_scroll", mapOf("target" to "n2", "direction" to "sideways", "token" to "bad")),
            ActionRequest("screen_type", mapOf("target" to "n1", "text" to "", "token" to "bad"))
        )
        for (request in invalid) {
            val rejected = pipeline.execute(request)
            assertEquals("invalid ${request.name} must be rejected, was: ${rejected.message}",
                ExecutionResult.Outcome.REJECTED_VALIDATION, rejected.outcome)
        }
        assertEquals("no adapter may run for invalid args", 0, dispatched.get())
        assertEquals("volume must be unchanged", volumeBefore, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue("no screen effects", bridge.tapped.isEmpty() && bridge.scrolled.isEmpty() && bridge.typed.isEmpty())
        // Valid anchor: the real battery adapter still dispatches through the gate.
        val battery = pipeline.execute(ActionRequest("read_battery"))
        assertTrue("valid read_battery must dispatch: ${battery.message}", battery.succeeded)
        assertEquals(1, dispatched.get())
    }

    @Test fun test66_crashBeforeAndAfterDispatchReconcilesWithoutRepeat() {
        // T10: crash before dispatch and crash after dispatch both recover to
        // an unknown outcome; stale callbacks are rejected and the unknown
        // mutation is never blindly repeated.
        val file = File(context.cacheDir, "release-m1e-crash.json").apply { delete() }
        try {
            val dispatched = AtomicInteger(0)
            val executor = MobileActionExecutor {
                dispatched.incrementAndGet()
                ExecutionResult(true, "Battery 80%")
            }
            // Crash before dispatch: claimed RUNNING, then "process death".
            var ledger = ToolTaskLedger(FileToolTaskStore(file))
            val before = ledger.admit(listOf(ActionRequest("read_battery")), "thread-52")
            val beforeAttempt = checkNotNull(ledger.get(before.attemptIds.single()))
            val claimedBefore = checkNotNull(ledger.claim(beforeAttempt.id, beforeAttempt.generation))
            ledger = ToolTaskLedger(FileToolTaskStore(file))
            val recoveredBefore = ledger.recoverAfterRestart().single { it.id == claimedBefore.id }
            assertEquals(ToolTaskState.UNKNOWN_OUTCOME, recoveredBefore.state)
            assertNull("stale pre-crash callback must be rejected",
                ledger.finish(claimedBefore, ExecutionResult(true, "late")))
            assertEquals(ToolTaskState.UNKNOWN_OUTCOME, ledger.get(claimedBefore.id)?.state)
            assertTrue(ledger.reconcileUnknown(recoveredBefore.id, recoveredBefore.generation))
            // Crash after dispatch: the real effect happened once, then
            // "death" before the receipt saved.
            val after = ledger.admit(listOf(ActionRequest("read_battery")), "thread-52")
            val afterAttempt = checkNotNull(ledger.get(after.attemptIds.single()))
            val claimedAfter = checkNotNull(ledger.claim(afterAttempt.id, afterAttempt.generation))
            val afterAction = (MobileActionValidator().validate(claimedAfter.request) as ActionValidation.Valid).action
            executor.execute(afterAction)
            assertEquals(1, dispatched.get())
            ledger = ToolTaskLedger(FileToolTaskStore(file))
            val recoveredAfter = ledger.recoverAfterRestart().single { it.id == claimedAfter.id }
            assertEquals(ToolTaskState.UNKNOWN_OUTCOME, recoveredAfter.state)
            assertTrue(ledger.reconcileUnknown(recoveredAfter.id, recoveredAfter.generation))
            // Neither unknown mutation is ever repeated.
            val pipeline = JournaledActionPipeline(ledger, executor)
            for (id in listOf(claimedBefore.id, claimedAfter.id)) {
                val retry = pipeline.executeAttempt(checkNotNull(ledger.get(id)))
                assertFalse("reconciled attempt must not redispatch", retry.succeeded)
            }
            assertEquals("the effect happened exactly once", 1, dispatched.get())
        } finally { file.delete() }
    }

    @Test fun test67_routineGrantReuseMatchesLimitsAndDisablePausesAffectedTasks() {
        // T11: a saved workflow's routine steps run under a reusable routine
        // grant whose exact limits must match; an approval wait blocks only
        // its dependents; disabling the routine pauses affected work while
        // unrelated tasks are untouched.
        val dispatched = AtomicInteger(0)
        val executor = MobileActionExecutor { dispatched.incrementAndGet(); ExecutionResult(true, "ok") }
        val store = InMemoryToolTaskStore()
        val tasks = ToolTaskLedger(store)
        val workflows = WorkflowLedger(store)
        val battery = ActionRequest("read_battery")
        val volume20 = ActionRequest("set_volume", mapOf("level" to "20"))
        val definition = WorkflowDefinition(UUID.randomUUID().toString(), "Evening check", "test",
            listOf(WorkflowStep.Tool(UUID.randomUUID().toString(), battery),
                WorkflowStep.Tool(UUID.randomUUID().toString(), volume20)),
            listOf(WorkflowTrigger.Manual), WorkflowOrigin.CONVERSATION, createdAtMs = 0, updatedAtMs = 0)
        val saved = workflows.saveDraft(definition)
        workflows.enable(saved.id)
        // Production-shaped dispatch: reuse the exact-limits grant, admit
        // under ROUTINE authority, claim and dispatch through the pipeline —
        // one step at a time, so a failure stops later steps.
        fun dispatchStep(request: ActionRequest): ExecutionResult {
            val grant = workflows.reusableGrant(saved.id, listOf(request))
                ?: workflows.createGrant(saved.id, listOf(request))
            val group = tasks.admit(listOf(request), "workflow:occ-53",
                authority = ToolAuthority.ROUTINE, grantId = grant.id)
            val attempt = checkNotNull(tasks.get(group.attemptIds.single()))
            return JournaledActionPipeline(tasks, executor).executeAttempt(attempt)
        }
        val occurrence = checkNotNull(workflows.scheduleOccurrence(saved.id, 0,
            System.currentTimeMillis() - 1, System.currentTimeMillis() - 1, "t53-1"))
        val claimedOccurrence = checkNotNull(workflows.claimDueOccurrence(occurrence.id))
        val outcome = WorkflowEngine().run(checkNotNull(workflows.definitionFor(claimedOccurrence)),
            dispatch = ::dispatchStep)
        assertTrue("routine occurrence completes under its grant", outcome is WorkflowRunOutcome.Completed)
        assertEquals(2, dispatched.get())
        // Each routine step ran under its own exact-limits grant (one grant
        // per resolved request, so placeholder-resolved dispatches still
        // match exactly). Those grants are reusable for identical limits —
        // but never for changed limits, and a new tool can never broaden one.
        val batteryGrant = checkNotNull(workflows.reusableGrant(saved.id, listOf(battery)))
        val volumeGrant = checkNotNull(workflows.reusableGrant(saved.id, listOf(volume20)))
        assertNotNull(volumeGrant)
        assertNull("changed limits must not reuse the grant",
            workflows.reusableGrant(saved.id, listOf(battery, ActionRequest("set_volume", mapOf("level" to "30")))))
        // A second occurrence reuses the identical-limits grants; no new
        // grants are minted for the same limits.
        val grantsBefore = store.readJournal().grants.size
        val occurrenceB = checkNotNull(workflows.scheduleOccurrence(saved.id, 0,
            System.currentTimeMillis() - 1, System.currentTimeMillis() - 1, "t53-1b"))
        val claimedB = checkNotNull(workflows.claimDueOccurrence(occurrenceB.id))
        val outcomeB = WorkflowEngine().run(checkNotNull(workflows.definitionFor(claimedB)),
            dispatch = ::dispatchStep)
        assertTrue("second occurrence completes", outcomeB is WorkflowRunOutcome.Completed)
        assertEquals("identical limits reuse the existing grants",
            grantsBefore, store.readJournal().grants.size)
        try {
            tasks.admit(listOf(battery, ActionRequest("set_volume", mapOf("level" to "30"))),
                "workflow:occ-x", authority = ToolAuthority.ROUTINE, grantId = batteryGrant.id)
            fail("admit must reject requests outside the grant's exact limits")
        } catch (_: IllegalArgumentException) { }
        // Approval waits block dependents only.
        val waiting = tasks.admit(listOf(battery, volume20), "chat-t53")
        val first = checkNotNull(tasks.get(waiting.attemptIds[0]))
        tasks.requestApproval(first.id, first.generation, "native", MobileToolCatalog.VERSION)
        val second = checkNotNull(tasks.get(waiting.attemptIds[1]))
        assertNull("dependent step cannot claim while its approval waits", tasks.claim(second.id, second.generation))
        val independent = tasks.admit(listOf(battery), "chat-t53-free")
        val freeAttempt = checkNotNull(tasks.get(independent.attemptIds.single()))
        assertNotNull("independent work is unaffected", tasks.claim(freeAttempt.id, freeAttempt.generation))
        // Disable pauses the routine's unfinished work, not the unrelated wait.
        val occurrence2 = checkNotNull(workflows.scheduleOccurrence(saved.id, 0,
            System.currentTimeMillis() + 600_000, System.currentTimeMillis() + 600_000, "t53-2"))
        val disabled = workflows.disable(saved.id)
        assertTrue(occurrence2.id in disabled.pausedOccurrenceIds)
        assertEquals(WorkflowOccurrenceState.CANCELLED, workflows.occurrence(occurrence2.id)!!.state)
        assertEquals("unrelated approval wait untouched",
            ToolTaskState.WAITING_APPROVAL, tasks.get(first.id)!!.state)
    }

    @Test fun test68_workflowDraftNeedsEnablementAndRevisionKeepsRunningVersion() {
        // T12: a conversation-created workflow shows its plain-language
        // summary and cannot run until explicitly enabled; revising the
        // definition never mutates an already-running occurrence's version.
        val store = InMemoryToolTaskStore()
        val workflows = WorkflowLedger(store)
        val v1 = WorkflowDefinition(UUID.randomUUID().toString(), "Morning briefing", "test",
            listOf(WorkflowStep.Tool(UUID.randomUUID().toString(), ActionRequest("read_battery"))),
            listOf(WorkflowTrigger.Daily(7, 0)), WorkflowOrigin.CONVERSATION, createdAtMs = 0, updatedAtMs = 0)
        val draft = workflows.saveDraft(v1)
        val preview = workflows.preview(draft.id)
        assertTrue(preview.contains("Morning briefing"))
        assertTrue("preview names the step", preview.contains("battery"))
        assertTrue("preview names the trigger", preview.contains("07:00"))
        assertTrue("preview states the draft needs enabling", preview.contains("draft"))
        assertNull("disabled drafts schedule nothing",
            workflows.scheduleOccurrence(draft.id, 0, System.currentTimeMillis() + 60_000,
                System.currentTimeMillis() + 60_000, "t54-1"))
        // Capture path: a completed task becomes a disabled draft too.
        val tasks = ToolTaskLedger(store)
        val group = tasks.admit(listOf(ActionRequest("read_battery")), "chat-t54")
        val attempt = checkNotNull(tasks.get(group.attemptIds.single()))
        tasks.finish(checkNotNull(tasks.claim(attempt.id, attempt.generation)),
            ExecutionResult(true, "Battery is at 80 percent."))
        val captured = checkNotNull(workflows.captureFromTask(group.id, "Battery check"))
        assertFalse("captured drafts start disabled", captured.enabled)
        assertEquals(WorkflowOrigin.CAPTURED, captured.origin)
        // Enable, start an occurrence, then revise: the running one keeps v1.
        workflows.enable(draft.id)
        val occurrence = checkNotNull(workflows.scheduleOccurrence(draft.id, 0,
            System.currentTimeMillis() - 1, System.currentTimeMillis() - 1, "t54-2"))
        val running = checkNotNull(workflows.claimDueOccurrence(occurrence.id))
        val revised = workflows.revise(draft.id, v1.copy(name = "Morning briefing v2", steps = listOf(
            WorkflowStep.Tool(UUID.randomUUID().toString(), ActionRequest("read_battery")),
            WorkflowStep.Tool(UUID.randomUUID().toString(), ActionRequest("set_volume", mapOf("level" to "20"))))))
        assertEquals(2, revised.version)
        assertEquals("running occurrence keeps its pinned version",
            1, checkNotNull(workflows.definitionFor(running)).version)
        assertEquals(2, checkNotNull(workflows.current(draft.id)).version)
        assertTrue(workflows.preview(draft.id).contains("Morning briefing v2"))
    }

    @Test fun test69_reminderTimingWindowsDstAndRebootDedup() {
        // T13: reminders target the requested time; flexible routines use
        // windows; DST shifts resolve forward; reboot recovery never replays
        // a trigger and never duplicates an occurrence.
        val file = File(context.cacheDir, "release-m2-schedule.json").apply { delete() }
        try {
            val store = FileToolTaskStore(file)
            val workflows = WorkflowLedger(store)
            val at = System.currentTimeMillis()
            val definition = WorkflowDefinition(UUID.randomUUID().toString(), "Water reminder", "test",
                listOf(WorkflowStep.Tool(UUID.randomUUID().toString(), ActionRequest("read_battery"))),
                listOf(WorkflowTrigger.Reminder(at + 3_600_000),
                    WorkflowTrigger.Window(at + 7_200_000, at + 10_800_000)),
                WorkflowOrigin.CONVERSATION, createdAtMs = 0, updatedAtMs = 0)
            val saved = workflows.saveDraft(definition)
            val scheduled = workflows.enable(saved.id)
            assertEquals(2, scheduled.size)
            val reminder = scheduled.single { it.triggerIndex == 0 }
            assertEquals("reminder targets the requested time", at + 3_600_000, reminder.scheduledForMs)
            val window = scheduled.single { it.triggerIndex == 1 }
            assertEquals(at + 7_200_000, window.scheduledForMs)
            assertEquals(at + 10_800_000, window.windowEndMs)
            // Exact-alarm honesty against the real Android alarm service.
            val alarm = context.getSystemService(android.app.AlarmManager::class.java)
            val canExact = if (android.os.Build.VERSION.SDK_INT >= 31) {
                try { alarm!!.canScheduleExactAlarms() } catch (_: SecurityException) { false }
            } else true
            val alarmScheduler = WorkflowAlarmScheduler(context)
            val scheduledAlarm = alarmScheduler.schedule(reminder)
            assertEquals(if (canExact) WorkflowScheduling.AlarmMode.EXACT
                else WorkflowScheduling.AlarmMode.INEXACT_FALLBACK, scheduledAlarm.mode)
            if (!canExact) assertNotNull("fallback must say so honestly", scheduledAlarm.honestNote)
            alarmScheduler.cancel(reminder.id)
            // DST: a daily trigger across the spring-forward resolves forward.
            val zone = java.time.ZoneId.of("America/New_York")
            val before = java.time.ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
            val next = WorkflowScheduling.nextDailyFire(WorkflowTrigger.Daily(2, 30), before, zone)
            val zoned = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone)
            assertEquals(8, zoned.dayOfMonth)
            // Reboot: reconstruct the ledger ("restart"), recover, and prove
            // no trigger replays and no occurrence duplicates.
            val rebooted = WorkflowLedger(FileToolTaskStore(file))
            val recovered = rebooted.recoverAfterRestart()
            assertEquals(2, recovered.filter { it.state == WorkflowOccurrenceState.SCHEDULED }.size)
            assertNull("dedup key survives the restart",
                rebooted.scheduleOccurrence(saved.id, 0, at + 3_600_000, at + 3_600_000, reminder.dedupKey))
            val due = checkNotNull(rebooted.scheduleOccurrence(saved.id, 0, at - 1_000, at - 1_000, "t55-due"))
            assertNotNull(rebooted.claimDueOccurrence(due.id))
            assertNull("a redelivered claim cannot double-fire", rebooted.claimDueOccurrence(due.id))
        } finally { file.delete() }
    }

    @Test fun test70_missedRunEvaluationAndBoundedEffort() {
        // T14: a missed run is evaluated against current circumstances —
        // run if relevant, report if not, ask if uncertain — with decision
        // receipts; bounded effort asks the user instead of repeating
        // completed work.
        val store = InMemoryToolTaskStore()
        val workflows = WorkflowLedger(store)
        val at = System.currentTimeMillis()
        val definition = WorkflowDefinition(UUID.randomUUID().toString(), "Missed routine", "test",
            listOf(WorkflowStep.Tool(UUID.randomUUID().toString(), ActionRequest("read_battery"))),
            listOf(WorkflowTrigger.Reminder(at - 5 * 60_000)),
            WorkflowOrigin.CONVERSATION, createdAtMs = 0, updatedAtMs = 0)
        val saved = workflows.saveDraft(definition)
        workflows.enable(saved.id)
        // enable() skips the past reminder; schedule it explicitly for evaluation.
        val missed = checkNotNull(workflows.scheduleOccurrence(saved.id, 0,
            at - 5 * 60_000, at - 5 * 60_000, "t56-1"))
        val relevant = WorkflowScheduling.evaluateMissedRun(missed, checkNotNull(workflows.current(saved.id)),
            WorkflowScheduling.MissedRunCircumstances(true, true, 5 * 60_000))
        assertTrue("fresh and active: relevant", relevant is MissedRunDecision.Relevant)
        val uncertain = WorkflowScheduling.evaluateMissedRun(missed, checkNotNull(workflows.current(saved.id)),
            WorkflowScheduling.MissedRunCircumstances(true, false, 2 * 3_600_000))
        assertTrue("stale and nobody around: ask", uncertain is MissedRunDecision.Uncertain)
        assertTrue(workflows.recordMissedEvaluation(missed.id,
            MissedRunDecision.Irrelevant("the moment has passed")))
        assertEquals(WorkflowOccurrenceState.MISSED, workflows.occurrence(missed.id)!!.state)
        assertTrue("decision receipts are kept",
            workflows.receiptsFor(saved.id).any { it.kind == WorkflowReceiptKind.MISSED_IRRELEVANT })
        // A stale duplicate slot coalesces to the latest — no catch-up storm.
        val old1 = checkNotNull(workflows.scheduleOccurrence(saved.id, 0,
            at - 90 * 60_000, at - 90 * 60_000, "t56-old1"))
        val old2 = checkNotNull(workflows.scheduleOccurrence(saved.id, 0,
            at - 60 * 60_000, at - 60 * 60_000, "t56-old2"))
        val (keep, skipped) = WorkflowScheduling.coalesceMissed(
            store.readJournal().occurrences.filter { it.state == WorkflowOccurrenceState.SCHEDULED })
        assertTrue(old2.id in keep.map { it.id })
        assertTrue(old1.id in skipped.map { it.id })
        // Bounded effort: the adaptive step exhausts its budget and asks the
        // user; completed steps are never re-run for the question.
        val dispatched = AtomicInteger(0)
        val adaptive = WorkflowDefinition(UUID.randomUUID().toString(), "Try quiet", "test", listOf(
            WorkflowStep.Tool(UUID.randomUUID().toString(), ActionRequest("read_battery")),
            WorkflowStep.Adaptive(UUID.randomUUID().toString(), "lower the volume",
                listOf(ActionRequest("set_volume", mapOf("level" to "10"))),
                EffortBudget(3, 60_000, 2))),
            listOf(WorkflowTrigger.Manual), WorkflowOrigin.CONVERSATION, createdAtMs = 0, updatedAtMs = 0)
        val outcome = WorkflowEngine().run(adaptive, dispatch = { request ->
            dispatched.incrementAndGet()
            if (request.name == "read_battery") ExecutionResult.battery(42)
            else ExecutionResult(false, "denied")
        })
        assertTrue("budget exhaustion asks the user", outcome is WorkflowRunOutcome.NeedsUser)
        val asked = outcome as WorkflowRunOutcome.NeedsUser
        assertTrue(asked.question.contains("lower the volume"))
        assertEquals("one battery read plus three bounded attempts", 4, dispatched.get())
        assertEquals("only the completed battery read is recorded", 1, asked.completedStepIds.size)
    }

    // -- M3 ecosystem integrations (T16) --------------------------------------

    private fun t71Metadata(
        packageName: String,
        functionId: String,
        version: Long = 1,
        params: AppFunctionType.Obj = AppFunctionType.Obj(
            mapOf("text" to AppFunctionProperty(AppFunctionType.Text, true))),
        scopeNames: Set<String> = setOf("read"),
        description: String = "A controlled test function."
    ): AppFunctionMetadata {
        val provider = ProviderId.appFunctions(packageName)
        return AppFunctionMetadata(
            providerPackage = packageName,
            functionId = functionId,
            displayName = functionId,
            description = description,
            versionCode = version,
            parameters = params,
            resultType = AppFunctionType.Text,
            scopes = scopeNames.map { ProviderWireNames.scopedName(provider, it) }.toSet()
        )
    }

    private fun t71SeedResolver(registry: ProviderRegistry) {
        ToolSourcePolicy.setProviderScopeResolver { wireName ->
            ProviderWireNames.parseToolName(wireName)?.let { parsed ->
                if (parsed.provider.kind == ProviderKind.APP_FUNCTIONS)
                    registry.metadataFor(parsed.provider, parsed.functionId)?.scopes.orEmpty()
                else emptySet()
            }.orEmpty()
        }
    }

    @Test fun test71_appFunctionsDiscoveryAndControlledJourney() {
        // T16: AppFunctions nested schema/types, state/update/uninstall/name
        // collisions; ordinary-app access vs ADB labeled. The platform probe
        // below runs against the real PackageManager; the provider world is
        // a controlled fake — the "one controlled dependent function
        // journey" the plan requires before real priority apps.
        val probe = AppFunctionPlatformProbe(context).probe()
        assertEquals("discovery access must be labeled",
            DiscoveryAccessMethod.ORDINARY_APP, probe.accessMethod)
        assertTrue("provider count is never negative", probe.providersFound >= 0)
        assertTrue("the probe note must be honest", probe.note.isNotBlank())

        val pkgA = "com.example.sample"
        val pkgB = "com.other.sample"
        val providerA = ProviderId.appFunctions(pkgA)
        val nestedParams = AppFunctionType.Obj(mapOf(
            "user" to AppFunctionProperty(AppFunctionType.Obj(mapOf(
                "id" to AppFunctionProperty(AppFunctionType.Integer, true),
                "tags" to AppFunctionProperty(
                    AppFunctionType.Arr(AppFunctionType.Text, maxItems = 3), false)
            )), true)
        ))
        val registry = ProviderRegistry()
        val firstDiff = registry.update(DiscoverySnapshot(listOf(
            t71Metadata(pkgA, "echo", description = "Echoes the given text back."),
            t71Metadata(pkgA, "shout", description = "Upper-cases the given text."),
            t71Metadata(pkgA, "lookup_user", params = nestedParams,
                description = "Looks up a user by id with optional tags."),
            t71Metadata(pkgB, "echo", description = "Another app's echo.")
        ), DiscoveryAccessMethod.ORDINARY_APP, 0L))
        assertEquals("four functions discovered", 4, firstDiff.added.size)
        t71SeedResolver(registry)

        // Collision-safe aliases: one short alias per colliding name would
        // be ambiguous, so the bindings stay unique and flagged.
        val aliases = registry.aliasRegistry.all().map { it.alias }.toSet()
        assertEquals(setOf("echo", "echo_sample", "shout", "lookup_user"), aliases)
        val collided = (registry.aliasRegistry.resolve("echo_sample") as AliasResolution.Resolved).binding
        assertEquals(pkgB, collided.identity.providerPackage)
        assertTrue("collision must be flagged", collided.collided)
        assertNotNull("collision must be explained", collided.collisionNote)
        val plain = (registry.aliasRegistry.resolve("echo") as AliasResolution.Resolved).binding
        assertEquals(pkgA, plain.identity.providerPackage)

        // Task-relevant selection surfaces the user-lookup function first for
        // the natural query, even though "look up" shares no whole token
        // with the "lookup_user" alias.
        val selected = AppFunctionTaskSelection.select(
            "look up the user by id",
            registry.aliasRegistry.all(),
            metadataFor = { binding -> registry.metadataForWire(binding.wireName) })
        assertEquals("lookup_user", selected.first().alias)

        // The controlled dependent-function journey: shout consumes echo's
        // output through a typed binding.
        val dispatched = AtomicInteger(0)
        val invoker = ProviderInvoker { call ->
            dispatched.incrementAndGet()
            val text = call.arguments["text"]?.toString().orEmpty()
            when (call.functionId) {
                "shout" -> ProviderCallResult.Success(mapOf("text" to text.uppercase()), "shouted")
                "lookup_user" -> {
                    @Suppress("UNCHECKED_CAST")
                    val user = call.arguments["user"] as Map<String, Any?>
                    ProviderCallResult.Success(mapOf("id" to user["id"].toString()), "found")
                }
                else -> ProviderCallResult.Success(mapOf("text" to text), "echoed")
            }
        }
        val ledger = ToolTaskLedger()
        val dispatcher = ProviderDispatcher(ledger, registry,
            McpRegistry(McpHttpClient { _, _, _ -> McpHttpResponse(500, "", emptyMap()) },
                InMemoryMcpCredentialStore()),
            mapOf(providerA to invoker, ProviderId.appFunctions(pkgB) to invoker))
        val journey = dispatcher.runJourney(listOf(
            ProviderJourneyStep("s1", "echo", mapOf("text" to "hello")),
            ProviderJourneyStep("s2", "shout", mapOf("text" to "\${s1.text}"))))
        assertTrue("the dependent journey completes", journey.succeeded)
        assertEquals("HELLO",
            (journey.stepResults[1] as ProviderCallResult.Success).data["text"])

        // Strict nested types: a valid nested call dispatches; a string id
        // is rejected with no dispatch.
        val nestedOk = dispatcher.dispatchByAlias("lookup_user",
            mapOf("user" to mapOf("id" to 7, "tags" to listOf("a", "b"))))
        assertTrue(nestedOk is ProviderCallResult.Success)
        assertEquals("7", (nestedOk as ProviderCallResult.Success).data["id"])
        val before = dispatched.get()
        val nestedBad = dispatcher.dispatchByAlias("lookup_user",
            mapOf("user" to mapOf("id" to "7")))
        assertTrue(nestedBad is ProviderCallResult.TypedError)
        assertEquals(ProviderErrorCode.INVALID_ARGUMENTS,
            (nestedBad as ProviderCallResult.TypedError).code)
        assertEquals("no dispatch on invalid nested args", before, dispatched.get())

        // Update and uninstall invalidate exactly the affected bindings.
        // (Descriptions are carried over so the only change is echo's
        // version bump; shout disappears.)
        val secondDiff = registry.update(DiscoverySnapshot(listOf(
            t71Metadata(pkgA, "echo", version = 2,
                description = "Echoes the given text back."),
            t71Metadata(pkgA, "lookup_user", params = nestedParams,
                description = "Looks up a user by id with optional tags."),
            t71Metadata(pkgB, "echo", version = 1,
                description = "Another app's echo.")
        ), DiscoveryAccessMethod.ORDINARY_APP, 1L))
        assertEquals(listOf("echo"), secondDiff.updated.map { it.functionId })
        assertEquals(listOf("shout"), secondDiff.removed.map { it.functionId })
        assertTrue(registry.aliasRegistry.resolve("shout") is AliasResolution.Unknown)
        assertTrue(registry.aliasRegistry.resolve("echo") is AliasResolution.Resolved)

        // T08 for the new provider family: the first successful call
        // remembers the grant; revocation blocks every adapter while the
        // independent provider keeps working.
        val familyA = "provider:appfunctions:com.example.sample"
        val record = ledger.journal().sourceAccess.single { it.family == familyA }
        assertEquals(SourceAccessState.GRANTED, record.state)
        assertTrue(ToolSourceAccess(ledger).revoke(familyA))
        val blocked = dispatcher.dispatchByAlias("echo", mapOf("text" to "hi"))
        assertTrue(blocked is ProviderCallResult.TypedError)
        assertEquals(ProviderErrorCode.DENIED_PERMISSION,
            (blocked as ProviderCallResult.TypedError).code)
        val otherStillWorks = dispatcher.dispatchByAlias("echo_sample", mapOf("text" to "hi"))
        assertTrue("an independent provider family is unaffected",
            otherStillWorks is ProviderCallResult.Success)

        // The settings surface labels ordinary-app access honestly.
        val rows = ProviderSettings.rows(registry,
            McpRegistry(McpHttpClient { _, _, _ -> McpHttpResponse(500, "", emptyMap()) },
                InMemoryMcpCredentialStore()),
            probe.let { AppFunctionPlatformStatus(it.accessMethod, it.platformServiceAvailable,
                it.providersFound, it.note) })
        val platformRow = rows.single { it.id == "appfunctions-platform" }
        assertTrue("unavailable providers are explained, not implied",
            platformRow.explanation.contains("ordinary app"))
    }

    // -- M3 MCP (T17) ----------------------------------------------------------

    /** Minimal loopback HTTP/1.1 MCP stub: initialize, tools/list, tools/call. */
    private class StubMcpServer : java.io.Closeable {
        private val socket = ServerSocket(0)
        val port: Int = socket.localPort
        @Volatile var requireToken: String? = "good-token"
        @Volatile var toolNames: List<String> = listOf("free_lookup", "paid_export")
        private val running = AtomicBoolean(true)
        private val worker = kotlin.concurrent.thread(isDaemon = true, name = "stub-mcp") {
            while (running.get()) {
                try {
                    handle(socket.accept())
                } catch (_: Exception) {
                    if (!running.get()) return@thread
                }
            }
        }

        private fun toolJson(name: String): String {
            val pricing = if (name == "paid_export") "paid" else "free"
            return "{\"name\":\"$name\",\"description\":\"$name tool\"," +
                "\"inputSchema\":{\"type\":\"object\"},\"x-jarvis-pricing\":\"$pricing\"," +
                "\"x-jarvis-scopes\":[\"lookup\"]}"
        }

        private fun handle(client: java.net.Socket) {
            client.use { sock ->
                val input = sock.getInputStream().bufferedReader(Charsets.UTF_8)
                val requestLine = input.readLine() ?: return
                val headers = mutableMapOf<String, String>()
                if (requestLine.startsWith("POST")) {
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        val idx = line.indexOf(':')
                        if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val chars = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(chars, read, length - read)
                    if (n <= 0) break
                    read += n
                }
                val body = String(chars, 0, read)
                if (!requestLine.startsWith("POST")) {
                    respond(sock, 404, ""); return
                }
                if (requireToken != null && headers["authorization"] != "Bearer $requireToken") {
                    respond(sock, 401, "unauthorized"); return
                }
                val method = try { org.json.JSONObject(body).optString("method") } catch (_: Exception) { "" }
                when (method) {
                    "initialize" -> respond(sock, 200,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{" +
                            "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}}",
                        mapOf("Mcp-Session-Id" to "stub-session"))
                    "notifications/initialized" -> respond(sock, 202, "")
                    "tools/list" -> {
                        val tools = toolNames.joinToString(",") { toolJson(it) }
                        respond(sock, 200,
                            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[$tools]}}")
                    }
                    "tools/call" -> respond(sock, 200,
                        "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":" +
                            "[{\"type\":\"text\",\"text\":\"stub-result\"}]}}")
                    else -> respond(sock, 404, "")
                }
            }
        }

        private fun respond(sock: java.net.Socket, status: Int, body: String,
                             extraHeaders: Map<String, String> = emptyMap()) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val out = sock.getOutputStream()
            val reason = when (status) {
                200 -> "OK"; 202 -> "Accepted"; 401 -> "Unauthorized"; else -> "Not Found"
            }
            val head = buildString {
                append("HTTP/1.1 $status $reason\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                for ((k, v) in extraHeaders) append("$k: $v\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }

        override fun close() {
            running.set(false)
            try { socket.close() } catch (_: Exception) { }
        }
    }

    @Test fun test72_mcpGuidedSetupAndServerStates() {
        // T17: guided/custom connection, auth failure, disconnect, schema
        // change, scope limits and paid-service default — against a real
        // loopback HTTP server through the real HttpURLConnection transport.
        val stub = StubMcpServer()
        try {
            val credentials = InMemoryMcpCredentialStore()
            val http = UrlConnectionMcpHttpClient(connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
            val flow = McpSetupFlow(http, credentials)
            // Custom URL validation happens before any network use.
            val badUrl = flow.run("Stub", "ftp://example.com/", null)
            assertTrue(badUrl is McpSetupFlow.FlowResult.Failed)
            assertEquals(McpSetupFlow.Stage.URL, (badUrl as McpSetupFlow.FlowResult.Failed).stage)
            // Guided setup connects; only free tools turn on by default and
            // the secret never lands in the config.
            val connected = flow.run("Stub", "http://127.0.0.1:${stub.port}/", "good-token")
            assertTrue(connected is McpSetupFlow.FlowResult.Connected)
            val status = (connected as McpSetupFlow.FlowResult.Connected).status
            assertEquals(McpServerState.CONNECTED, status.state)
            assertEquals(setOf("free_lookup"), status.enabledTools)
            assertFalse("secret must not leak into the config",
                status.config.redacted().contains("good-token"))
            val registry = McpRegistry(http, credentials)
            val placed = registry.add(status)
            val serverId = placed.config.id
            val provider = ProviderId.mcp(serverId)
            ToolSourcePolicy.setProviderScopeResolver { wireName ->
                ProviderWireNames.parseToolName(wireName)?.let { parsed ->
                    if (parsed.provider.kind == ProviderKind.MCP)
                        registry.toolsFor(parsed.provider.id)
                            .firstOrNull { it.name == parsed.functionId }
                            ?.scopes?.map { ProviderWireNames.scopedName(parsed.provider, it) }
                            .orEmpty().toSet()
                    else emptySet()
                }.orEmpty()
            }
            val dispatcher = ProviderDispatcher(ToolTaskLedger(), ProviderRegistry(), registry,
                mapOf(provider to McpInvoker(registry, http, credentials)))
            // A free tool call dispatches through the real transport.
            val ok = dispatcher.dispatch(ProviderCall(provider, "free_lookup", mapOf("q" to "x")))
            assertTrue(ok is ProviderCallResult.Success)
            assertEquals("stub-result", (ok as ProviderCallResult.Success).data["text"])
            // Paid-service default: disabled until explicitly enabled, and
            // even then purchase confirmation is never waived.
            val paidDisabled = dispatcher.dispatch(ProviderCall(provider, "paid_export", mapOf("q" to "x")))
            assertTrue(paidDisabled is ProviderCallResult.TypedError)
            assertEquals(ProviderErrorCode.NOT_ENABLED,
                (paidDisabled as ProviderCallResult.TypedError).code)
            assertTrue(registry.setToolEnabled(serverId, "paid_export", true))
            val paidConfirm = dispatcher.dispatch(ProviderCall(provider, "paid_export", mapOf("q" to "x")))
            assertTrue("paid enablement never waives purchase confirmation",
                paidConfirm is ProviderCallResult.NeedsPurchaseConfirmation)
            // Schema change blocks calls until re-reviewed.
            stub.toolNames = listOf("free_lookup", "paid_export_changed")
            val changed = registry.refresh(serverId)
            assertEquals(McpServerState.SCHEMA_CHANGED, changed.state)
            val blocked = dispatcher.dispatch(ProviderCall(provider, "free_lookup", mapOf("q" to "x")))
            assertTrue(blocked is ProviderCallResult.TypedError)
            assertEquals(ProviderErrorCode.PROVIDER_UNAVAILABLE,
                (blocked as ProviderCallResult.TypedError).code)
            assertTrue(registry.acknowledgeSchemaChange(serverId))
            assertEquals(McpServerState.CONNECTED, registry.status(serverId)!!.state)
            // Auth failure is an explicit denied state, not a silent retry.
            stub.toolNames = listOf("free_lookup", "paid_export")
            stub.requireToken = "rotated-token"
            val denied = registry.refresh(serverId)
            assertEquals(McpServerState.DENIED, denied.state)
            assertTrue(denied.explanation.contains("credentials"))
            // Explicit disconnect makes calls honestly unavailable.
            stub.requireToken = "good-token"
            assertTrue(registry.disconnect(serverId))
            assertEquals(McpServerState.DISABLED, registry.status(serverId)!!.state)
            val gone = dispatcher.dispatch(ProviderCall(provider, "free_lookup", mapOf("q" to "x")))
            assertTrue(gone is ProviderCallResult.TypedError)
            val goneError = gone as ProviderCallResult.TypedError
            assertEquals(ProviderErrorCode.PROVIDER_UNAVAILABLE, goneError.code)
            assertTrue(goneError.message.contains("Disconnected"))
        } finally {
            stub.close()
        }
    }

    // -- M3 provider grants and exposure gate (T08 for new providers) ------------

    @Test fun test73_providerGrantDisciplineAndExposureGate() {
        // T08 applied to new providers: first-source access is remembered per
        // provider family, a grant can never broaden (in scope or across
        // families), denial/revocation blocks every adapter, and provider
        // tools stay structurally unavailable to the model until M7.
        val pkg = "com.example.sample"
        val provider = ProviderId.appFunctions(pkg)
        val wire = ProviderWireNames.toolName(provider, "echo")
        val registry = ProviderRegistry()
        registry.update(DiscoverySnapshot(listOf(
            AppFunctionMetadata(pkg, "echo", "Echo", "Echoes.", 1,
                AppFunctionType.Obj(mapOf("text" to AppFunctionProperty(AppFunctionType.Text, true))),
                AppFunctionType.Text,
                setOf(ProviderWireNames.scopedName(provider, "read")))
        ), DiscoveryAccessMethod.ORDINARY_APP, 0L))
        ToolSourcePolicy.setProviderScopeResolver { wireName ->
            if (wireName == wire) setOf(ProviderWireNames.scopedName(provider, "read")) else emptySet()
        }
        val ledger = ToolTaskLedger()
        val dispatched = AtomicInteger(0)
        val invoker = ProviderInvoker { call ->
            dispatched.incrementAndGet()
            ProviderCallResult.Success(mapOf("text" to call.arguments["text"].toString()), "echoed")
        }
        val dispatcher = ProviderDispatcher(ledger, registry,
            McpRegistry(McpHttpClient { _, _, _ -> McpHttpResponse(500, "", emptyMap()) },
                InMemoryMcpCredentialStore()),
            mapOf(provider to invoker))
        // The model-exposure gate is structural, not advisory.
        val modelCall = dispatcher.dispatchByAlias("echo", mapOf("text" to "hi"),
            ProviderCallerKind.MODEL)
        assertTrue(modelCall is ProviderCallResult.TypedError)
        assertEquals(ProviderErrorCode.DENIED_PERMISSION,
            (modelCall as ProviderCallResult.TypedError).code)
        assertEquals("no model dispatch while exposure is off", 0, dispatched.get())
        // The first successful call remembers the grant within its scopes.
        assertTrue(dispatcher.dispatchByAlias("echo", mapOf("text" to "hi"))
            is ProviderCallResult.Success)
        val family = "provider:appfunctions:com.example.sample"
        val record = ledger.journal().sourceAccess.single { it.family == family }
        assertEquals(SourceAccessState.GRANTED, record.state)
        assertEquals(setOf("appfunctions:com.example.sample:read"), record.scopes)
        // A later grant attempt can never broaden the scopes.
        ToolSourceAccess(ledger).recordProviderGrant(wire, setOf("battery.read"))
        assertEquals(setOf("appfunctions:com.example.sample:read"),
            ledger.journal().sourceAccess.single { it.family == family }.scopes)
        assertEquals("the narrowing attempt dispatched nothing new", 1, dispatched.get())
        // Denial blocks the provider adapter with a truthful receipt.
        ToolSourceAccess(ledger).recordDenial(family)
        val denied = dispatcher.dispatchByAlias("echo", mapOf("text" to "hi"))
        assertTrue(denied is ProviderCallResult.TypedError)
        assertEquals(ProviderErrorCode.DENIED_PERMISSION,
            (denied as ProviderCallResult.TypedError).code)
        assertEquals("denied calls never reach the adapter", 1, dispatched.get())
        // The settings surface stays honest about availability.
        val rows = ProviderSettings.rows(registry,
            McpRegistry(McpHttpClient { _, _, _ -> McpHttpResponse(500, "", emptyMap()) },
                InMemoryMcpCredentialStore()),
            AppFunctionPlatformStatus(DiscoveryAccessMethod.ORDINARY_APP, false, 0,
                "No provider declarations found by the package scan."))
        val platformRow = rows.single { it.id == "appfunctions-platform" }
        assertEquals("unavailable", platformRow.state)
        assertTrue("access method is labeled", platformRow.explanation.contains("ordinary app"))
        val projection = WorkflowSettingsProjection.from(ledger.journal(), 0L, rows)
        assertEquals("provider rows reach the settings projection", rows, projection.providers)
    }

    @Test fun test74_reminderTextRequestCreatesRealScheduleAndListsIt() {
        // Regression for the Fold 6 report (build 1002): voice "remind me to
        // go door dashing tomorrow at 4" produced a confabulated confirmation
        // with nothing scheduled, and follow-ups looped "It is noted in your
        // schedule" with no schedule in existence. A text "remind me" request
        // must parse to a Ready create_reminder plan, write a real
        // WorkflowLedger entry through the real Android executor, and
        // show_schedule must list it.
        val storeFile = File(context.cacheDir, "reminder-journey-${UUID.randomUUID()}.json")
        try {
            val ledger = WorkflowLedger(FileToolTaskStore(storeFile))
            val coordinator = ReminderCoordinator(ledger, { occurrence ->
                WorkflowAlarmScheduler(context).schedule(occurrence)
            })
            val plan = ActionTurnPlan.parse("remind me to go door dashing tomorrow at 4")
            assertTrue("text reminder must parse as an action plan, was $plan",
                plan is ActionTurnPlan.Ready)
            val request = (plan as ActionTurnPlan.Ready).steps.single().request
            assertEquals("create_reminder", request.name)
            assertEquals("go door dashing", request.arguments["message"])
            val pipeline = MobileActionPipeline(
                executor = AndroidMobileActionExecutor(context, reminderScheduling = coordinator))
            val created = pipeline.execute(request)
            assertTrue("parsed create_reminder must dispatch: ${created.message}", created.succeeded)
            assertTrue("receipt must claim the set honestly, not confabulate: ${created.message}",
                created.message.startsWith("Reminder set for"))
            assertTrue("receipt must name the requested time: ${created.message}",
                created.message.contains("4:00 PM"))
            assertFalse("receipt must not carry an alarm failure: ${created.message}",
                created.message.contains("couldn't"))
            val listed = pipeline.execute(ActionRequest("show_schedule"))
            assertTrue("show_schedule must dispatch: ${listed.message}", listed.succeeded)
            assertTrue("the schedule must list the reminder that was just set: ${listed.message}",
                listed.message.contains("door dashing"))
            assertFalse("the schedule must not claim emptiness after a reminder was set: ${listed.message}",
                listed.message.contains("Nothing is scheduled"))
        } finally {
            storeFile.delete()
        }
    }

    @Test fun test75_emptyScheduleRendersHonestEmptyState() {
        // The other half of the build-1002 loop: with no reminders scheduled,
        // asking for the schedule must say so plainly instead of inventing one.
        val storeFile = File(context.cacheDir, "reminder-empty-${UUID.randomUUID()}.json")
        try {
            val ledger = WorkflowLedger(FileToolTaskStore(storeFile))
            val coordinator = ReminderCoordinator(ledger, { occurrence ->
                WorkflowAlarmScheduler(context).schedule(occurrence)
            })
            val pipeline = MobileActionPipeline(
                executor = AndroidMobileActionExecutor(context, reminderScheduling = coordinator))
            val result = pipeline.execute(ActionRequest("show_schedule"))
            assertTrue("empty schedule read must succeed: ${result.message}", result.succeeded)
            assertTrue("empty schedule must say so honestly: ${result.message}",
                result.message.contains("Nothing is scheduled"))
        } finally {
            storeFile.delete()
        }
    }
    // Leave this selection in durable preferences for the controller's separate-process check.
    @Test fun test76_browseToolsUnavailableUntilRuntimeWired() {
        // M4 acceptance: the browser runtime path (browser decorator, session
        // owner, approval UI) is not installed in the production factories,
        // so the nine browse tools must be unavailable on every model-facing
        // surface, and a browse action that somehow reaches the base executor
        // fails closed with an honest receipt.
        MobileToolCatalog.BrowserRuntimeGate.wired = false
        try {
            val names = MobileToolCatalog.all().map { it.name }
            assertTrue("No browse tool may be model-visible while unwired",
                names.none { it in MobileToolCatalog.BROWSE_TOOL_NAMES })
            val schemas = MobileActionToolDefinitions.all()
                .map { it.getToolDescriptionJsonString() }
            assertTrue("No browse schema may reach the model while unwired",
                schemas.none { it.contains("\"browse_open\"") })
            val plan = ActionTurnPlan.parse("browse to example.com")
            assertTrue("Deterministic routing must not plan browse while unwired",
                plan is ActionTurnPlan.NotAction)
            val executor = AndroidMobileActionExecutor(context)
            val result = executor.execute(MobileAction.BrowseOpen("https://example.com"))
            assertFalse("A browse action must not succeed without the runtime path", result.succeeded)
            assertTrue("The receipt must name the missing wiring, got: ${result.message}",
                result.message.contains("isn't wired into this action path"))
        } finally {
            MobileToolCatalog.BrowserRuntimeGate.wired = false
        }
    }

    @Test fun test77_browserApprovalDiesWithReplacedDom() {
        // M4 acceptance on Android: a same-URL DOM replacement between
        // approval and dispatch invalidates the approval, and the backend
        // performs zero submission.
        val session = BrowserSession()
        val bridge = FakeBrowserBridge()
        val executor = AndroidBrowserExecutor(context,
            MobileActionExecutor { ExecutionResult(true, "delegated") },
            session = session, bridge = bridge)
        fun loginPage(fingerprint: String, actionUrl: String) = BrowserPageSnapshot(
            url = "https://example.com/login",
            title = "Example login",
            textExcerpt = "Sign in to Example",
            links = listOf(BrowserLink("l0", "Home", "https://example.com/")),
            forms = listOf(
                BrowserForm(
                    id = "form0",
                    actionUrl = actionUrl,
                    method = "POST",
                    fields = listOf(
                        BrowserField("f0", "Email", FieldKind.EMAIL),
                        BrowserField("f1", "Password", FieldKind.PASSWORD, secret = true)
                    ),
                    submitLabel = "Sign in"
                )
            ),
            contentFingerprint = fingerprint
        )
        bridge.snapshotToReturn = loginPage("fp-login", "https://example.com/session")
        assertTrue(executor.execute(MobileAction.BrowseRead).succeeded)
        val token = session.currentPage()!!.pageToken
        assertTrue(executor.execute(MobileAction.BrowseFill("f0", "user@example.com", token)).succeeded)
        session.admitSubmit(session.proposeSubmit(token)!!)
        // The DOM is replaced (same URL, new contents) before dispatch.
        bridge.refreshedSnapshot = loginPage("fp-hijacked", "https://evil.example/collect")
        val submit = executor.execute(MobileAction.BrowseSubmit(token))
        assertFalse("a stale approval must never submit", submit.succeeded)
        assertTrue("stale token must be reported, got: ${submit.message}",
            submit.message.contains("page changed"))
        assertEquals("zero submission may reach the backend", 0, bridge.submittedForms)
    }

    @Test fun test78_scriptStepRunsOnDeviceWithAllowlistedHost() {
        // M5 acceptance on Android: a workflow Script step runs through the
        // real isolated interpreter with the production allowlisted host,
        // and its result binds into a later step.
        val scriptId = "123e4567-e89b-42d3-a456-426614174011"
        val toolId = "123e4567-e89b-42d3-a456-426614174012"
        val definition = WorkflowDefinition(
            id = "123e4567-e89b-42d3-a456-426614174010",
            name = "M5 script journey",
            description = "Script step with result binding.",
            steps = listOf(
                WorkflowStep.Script(scriptId, "log(\"hello\"); return 40 + 2;", listOf("log")),
                WorkflowStep.Tool(toolId,
                    ActionRequest("set_volume", mapOf("level" to "20")),
                    bindings = mapOf("level" to WorkflowBinding(scriptId, "result")))
            ),
            triggers = listOf(WorkflowTrigger.Manual),
            origin = WorkflowOrigin.CONVERSATION,
            createdAtMs = 1_700_000_000_000L,
            updatedAtMs = 1_700_000_000_000L
        )
        val seen = mutableListOf<ActionRequest>()
        val outcome = WorkflowEngine().run(
            definition,
            dispatch = { req -> seen += req; ExecutionResult(true, "ok") },
            runScript = { step -> step.runWithInterpreter(ScriptHost.withLog()) }
        )
        assertTrue("script workflow must complete, got $outcome",
            outcome is WorkflowRunOutcome.Completed)
        outcome as WorkflowRunOutcome.Completed
        assertEquals("42", outcome.results[scriptId]?.get("result"))
        // The script's result bound into the tool step's arguments.
        assertEquals("42", seen.single().arguments["level"])
        // And an undeclared host function stays denied on device too.
        val denied = WorkflowStep.Script("123e4567-e89b-42d3-a456-426614174013", "read_file(\"/x\");", emptyList())
            .runWithInterpreter(ScriptHost.withLog())
        assertTrue(denied is ScriptExecution.Failed)
    }

    @Test fun test79_workflowExportSanitizesPersonalContent() {
        // M5 acceptance on Android: export redacts reminder text, the
        // description and location coordinates; the preview exposes every
        // retained value; repeated keys with distinct values get distinct
        // bindings. open_website is not routine-eligible, so the repeated
        // steps use post_notification: routine-eligible, with personal-content
        // args (title, text) that are always redacted into setup bindings.
        val message = "Call mom about Sunday dinner"
        val titleA = "Dentist appointment reminder"
        val textA = "Call the dentist to confirm Tuesday"
        val titleB = "Trash day reminder"
        val textB = "Put the bins out tonight"
        val definition = WorkflowDefinition(
            id = "123e4567-e89b-42d3-a456-426614174020",
            name = "Export journey",
            description = "My private routine",
            steps = listOf(
                WorkflowStep.Tool("123e4567-e89b-42d3-a456-426614174021", ActionRequest("create_reminder",
                    mapOf("message" to message, "at_ms" to "1791230400000"))),
                WorkflowStep.Tool("123e4567-e89b-42d3-a456-426614174022", ActionRequest("post_notification",
                    mapOf("title" to titleA, "text" to textA))),
                WorkflowStep.Tool("123e4567-e89b-42d3-a456-426614174023", ActionRequest("post_notification",
                    mapOf("title" to titleB, "text" to textB)))
            ),
            triggers = listOf(
                WorkflowTrigger.Manual,
                WorkflowTrigger.OnLocation(40.7128, -74.0060, 100.0)
            ),
            origin = WorkflowOrigin.CONVERSATION,
            createdAtMs = 1_700_000_000_000L,
            updatedAtMs = 1_700_000_000_000L
        )
        val export = exportWorkflow(definition, author = "tester", nowMs = 1_700_000_000_000L)
        assertFalse(export.manifestJson.contains(message))
        assertFalse(export.manifestJson.contains("My private routine"))
        assertFalse(export.manifestJson.contains("40.7128"))
        assertFalse(export.manifestJson.contains(titleA))
        assertFalse(export.manifestJson.contains(textA))
        assertFalse(export.manifestJson.contains(titleB))
        assertFalse(export.manifestJson.contains(textB))
        assertTrue(export.manifestJson.contains("{{setup:message}}"))
        assertTrue(export.manifestJson.contains("{{setup:title}}"))
        assertTrue(export.manifestJson.contains("{{setup:title_2}}"))
        assertTrue(export.manifestJson.contains("{{setup:text}}"))
        assertTrue(export.manifestJson.contains("{{setup:text_2}}"))
        // The exported manifest still parses and validates on device.
        val parsed = parseWorkflowManifest(export.manifestJson)
        // The location trigger is removed (replaced by Manual): coordinates
        // never survive, so there is no placeholder slot in the JSON — the
        // importer adds their own location via the location_trigger binding.
        assertTrue(parsed.workflow.triggers.none { it is WorkflowTrigger.OnLocation })
        assertTrue(parsed.workflow.triggers.any { it is WorkflowTrigger.Manual })
        val shared = export.preview.shared.joinToString("\n")
        assertTrue(shared.contains("create_reminder"))
        assertTrue(shared.contains("message=\"{{setup:message}}\""))
        assertTrue(shared.contains("manual (when you ask in chat)"))
        assertTrue(parsed.setupBindings.map { it.name }.containsAll(
            listOf("message", "title", "title_2", "text", "text_2", "description", "location_trigger")))
    }


    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun test81_smartTurnEndpointPersistsAndNativeControlMatchesPinnedModel() {
        val preferences = context.getSharedPreferences("voice_input", Context.MODE_PRIVATE)
        val key = "smart_turn_endpoint_enabled"
        val oldKey = "smart_turn_shadow_enabled"
        val hadOldValue = preferences.contains(oldKey)
        val oldValue = preferences.getBoolean(oldKey, false)
        val hadValue = preferences.contains(key)
        val original = preferences.getBoolean(key, false)
        val mode = VoiceInputMode.selected(context)
        val captions = VoiceInputMode.captionEngine(context)
        fun render(enabled: Boolean) {
            activity.onActivity { host -> host.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    androidx.compose.foundation.lazy.LazyColumn {
                        item { androidx.compose.runtime.key(enabled) {
                            com.battlesbudz.jarvis.v2.ui.VoiceInputSettings(enabled = enabled, onBusy = {})
                        } }
                    }
                } }
            } }
            device.waitForIdle()
        }
        try {
            preferences.edit().remove(key).putBoolean(oldKey, false).commit()
            render(false)
            scrollTo(By.res("smart_turn_endpoint_toggle"))
            assertTrue("Live-call settings must not change endpoint/model setup",
                device.wait(Until.hasObject(By.res("smart_turn_endpoint_toggle").enabled(false)), 15_000))
            assertTrue(com.battlesbudz.jarvis.v2.voice.smartturn.SmartTurnSettings.enabled(context))
            assertNotNull(scrollTo(By.text("Download Smart Turn (8.7 MB)")))
            // Default-on endpoint control remains independently disableable without loading weights.
            preferences.edit().putBoolean(key, true).commit()
            render(true)
            scrollTo(By.res("smart_turn_endpoint_toggle"))
            // UiObject resolves this complete selector inside click(), not a UiObject2
            // cached from either composition. Tap the exact label within its enabled,
            // clickable tagged parent. Only lookup polls; the action is dispatched once.
            val disable = UiSelector().resourceId("smart_turn_endpoint_toggle")
                .enabled(true).clickable(true).childSelector(UiSelector().text("Disable Smart Turn"))
            assertTrue("Disable Smart Turn must be inside the enabled, clickable control",
                device.findObject(disable).waitForExists(15_000))
            val configuration = Configurator.getInstance()
            val selectorTimeout = configuration.waitForSelectorTimeout
            try {
                // Re-resolve at dispatch; disappearance fails without a second wait.
                configuration.waitForSelectorTimeout = 0
                device.findObject(disable).click()
            } finally {
                configuration.waitForSelectorTimeout = selectorTimeout
            }
            // Input injection can return before the UI handler. Its Enable label is
            // published only after the synchronous preference update; find is bounded.
            find(By.text("Enable Smart Turn"))
            assertFalse(preferences.getBoolean(key, true))
            activity.recreate()
            render(true)
            assertNotNull(scrollTo(By.text("Enable Smart Turn")))
            assertFalse(preferences.getBoolean(key, true))
            assertEquals(mode, VoiceInputMode.selected(context))
            assertEquals(captions, VoiceInputMode.captionEngine(context))
            captureEvidence("smart_turn_endpoint_default_settings")
            instrumentation.sendStatus(1, android.os.Bundle().apply {
                putString("jarvisSmartTurnStart", "android-smart-turn-native-v1")
            })
            val nativeProbe = SmartTurnNativeJourneyProbe.run(instrumentation,
                InstrumentationRegistry.getArguments().getString("jarvisSmartTurnInput"))
            instrumentation.sendStatus(1, android.os.Bundle().apply {
                putString("jarvisSmartTurnResult", org.json.JSONObject(nativeProbe.reportFields()).toString())
            })
            nativeProbe.assertPassed()
        } finally {
            if (hadValue) preferences.edit().putBoolean(key, original).commit()
            else preferences.edit().remove(key).commit()
            if (hadOldValue) preferences.edit().putBoolean(oldKey, oldValue).commit()
            else preferences.edit().remove(oldKey).commit()
        }
    }

    @Test fun test90_modelSelectionPersistsAcrossRecreation() {
        openBrowser()
        enterText(By.res("model_search"), "Gemma-4-E4B-it")
        clickEnabled(By.res("model_family_Gemma"))
        assertNotNull(find(By.res("model_list")))
        scrollTo(By.res("model_choose_Gemma-4-E4B-it")).click()
        assertNotNull(find(By.text("Gemma-4-E4B-it")))
        activity.recreate()
        assertNotNull(find(By.text("Gemma-4-E4B-it")))
    }
}
