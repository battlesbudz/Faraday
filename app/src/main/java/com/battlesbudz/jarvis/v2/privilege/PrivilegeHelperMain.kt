package com.battlesbudz.jarvis.v2.privilege

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Display
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/** app_process entry point. Launched only by wireless ADB; the handoff nonce is supplied on stdin. */
object PrivilegeHelperMain {
    @JvmStatic fun main(args: Array<String>) {
        if (args.size != 3 || args[1].toIntOrNull() == null) exitProcess(2)
        val packageName = args[0]
        val appUid = args[1].toInt()
        val certPin = args[2]
        val nonce = runCatching { System.`in`.bufferedReader().readLine()?.trim() }.getOrNull()
        if (nonce.isNullOrBlank()) exitProcess(2)
        Looper.prepareMainLooper()
        val context = ShellContext.create()
        val backend = ShellPrivilegeBackend(context, packageName, appUid)
        val binder = object : IPrivilegeHelper.Stub() {
            override fun createVirtualDisplay(name: String, width: Int, height: Int, densityDpi: Int) = backend.createVirtualDisplay(name, width, height, densityDpi)
            override fun releaseVirtualDisplay(displayId: Int) = backend.releaseVirtualDisplay(displayId)
            override fun enableAccessibilityService(component: String) = backend.enableAccessibilityService(component)
            override fun grantPermission(permissionName: String) = backend.grantPermission(permissionName)
            override fun getPrivilegeState() = backend.state()
            override fun injectInput(displayId: Int, event: Bundle) = backend.injectInput(displayId, event)
            override fun moveTaskToDisplay(taskId: Int, displayId: Int) = backend.moveTaskToDisplay(taskId, displayId)
            override fun getClipboardText() = backend.getClipboardText()
            override fun setClipboardText(text: String) = backend.setClipboardText(text)
            override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                if (code in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION && Binder.getCallingUid() != appUid)
                    throw SecurityException("helper accepts calls only from its paired app uid")
                return super.onTransact(code, data, reply, flags)
            }
        }
        attach(packageName, appUid, certPin, nonce, binder)
        Looper.loop()
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun attach(packageName: String, appUid: Int, certPin: String, nonce: String, binder: IBinder) {
        try {
            val activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
            val token = Binder()
            val authority = "$packageName.helper"
            val userId = appUid / 100000
            val holder = activityManager.javaClass.getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
                .invoke(activityManager, authority, userId, token, "FaradayHelper") ?: exitProcess(1)
            try {
                val info = holder.javaClass.getField("info").get(holder) as? android.content.pm.ProviderInfo ?: exitProcess(1)
                if (info.packageName != packageName || info.applicationInfo?.uid != appUid) exitProcess(0)
                if (!signedAsPinned(packageName, appUid, certPin)) exitProcess(0)
                val provider = holder.javaClass.getField("provider").get(holder)!!
                val extras = Bundle().apply {
                    putString(HelperHandoff.NONCE, nonce); putBinder(HelperHandoff.HELPER, binder)
                    putInt(HelperHandoff.UID, Process.myUid()); putInt(HelperHandoff.PID, Process.myPid())
                    putString(HelperHandoff.APK, System.getProperty("java.class.path"))
                }
                val call = provider.javaClass.methods.first { it.name == "call" && it.parameterTypes.size == 5 }
                val caller = AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build()
                val result = call.invoke(provider, caller, authority, HelperHandoff.ATTACH, null, extras) as? Bundle
                if (result?.getBoolean(HelperHandoff.ACCEPTED) != true) exitProcess(0)
                val appToken = result.getBinder(HelperHandoff.TOKEN) ?: exitProcess(1)
                appToken.linkToDeath({ ShellPrivilegeBackend.releaseAll() }, 0)
                val next = result.getString(HelperHandoff.NEXT) ?: exitProcess(1)
                // Reattach after app process death using the successor nonce.
                appToken.linkToDeath({
                    ShellPrivilegeBackend.releaseAll()
                    if (!isInstalled(packageName)) exitProcess(0)
                    Thread { Thread.sleep(1_000); attach(packageName, appUid, certPin, next, binder) }.apply { isDaemon = true; start() }
                }, 0)
            } finally {
                runCatching { activityManager.javaClass.getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType).invoke(activityManager, authority, token, userId) }
            }
        } catch (t: Throwable) { Log.e("FaradayHelper", "handoff failed", t); exitProcess(1) }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun signedAsPinned(packageName: String, uid: Int, pin: String): Boolean = runCatching {
        val pm = ShellContext.create().packageManager
        val info = pm.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners ?: return@runCatching false
        val certs = signers.map { cert ->
            java.security.MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
        }.sorted().joinToString(",")
        info.applicationInfo?.uid == uid && certs == pin
    }.getOrDefault(false)

    private fun isInstalled(packageName: String): Boolean = runCatching {
        ShellContext.create().packageManager.getPackageInfo(packageName, 0); true
    }.getOrDefault(false)
}

/** Mika-derived minimal shell process context; OS services must see uid/package 2000. */
private class ShellContext(base: Context) : ContextWrapper(base) {
    override fun getPackageName() = "com.android.shell"
    override fun getOpPackageName() = "com.android.shell"
    override fun getAttributionSource() = AttributionSource.Builder(2000).setPackageName("com.android.shell").build()
    override fun getApplicationContext(): Context = this
    companion object {
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi") fun create(): Context {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null) ?: run {
                val obj = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, obj)
                at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(obj, true); obj
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching {
                val controller = Class.forName("android.app.ConfigurationController")
                    .getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"))
                    .apply { isAccessible = true }.newInstance(thread)
                at.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(thread, controller)
            }
            runCatching {
                val bindData = Class.forName("android.app.ActivityThread\$AppBindData")
                    .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                bindData.javaClass.getDeclaredField("appInfo").apply { isAccessible = true }
                    .set(bindData, ApplicationInfo().apply { packageName = "com.android.shell" })
                at.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(thread, bindData)
            }
            val base = at.getDeclaredMethod("getSystemContext").invoke(thread) as Context
            val shell = ShellContext(base)
            if (at.getDeclaredField("mInitialApplication").apply { isAccessible = true }.get(thread) == null) runCatching {
                val app = Instrumentation.newApplication(Application::class.java, shell)
                at.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(thread, app)
            }
            return shell
        }
    }
}

/** Shell-side enforcement duplicates the charter's closed AIDL and A7 symbolic permission set. */
internal class ShellPrivilegeBackend(private val context: Context, private val appPackage: String, private val appUid: Int) {
    private val displays = ConcurrentHashMap<Int, android.hardware.display.VirtualDisplay>()
    private val displayReaders = ConcurrentHashMap<Int, ImageReader>()
    fun createVirtualDisplay(name: String, width: Int, height: Int, densityDpi: Int): Int {
        require(name.isNotBlank() && width in 1..8192 && height in 1..8192 && densityDpi in 1..1000)
        // The display surface comes from an ImageReader retained for the display's
        // lifetime (Mika's AgentDisplay route). VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
        // is a hidden constant (1 shl 6); Mika defines it locally the same way.
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(name, width, height, densityDpi, reader.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 6))
        if (display == null) { reader.close(); return -1 }
        displays[display.display.displayId] = display
        displayReaders[display.display.displayId] = reader
        return display.display.displayId
    }
    fun releaseVirtualDisplay(displayId: Int) {
        displays.remove(displayId)?.release()
        displayReaders.remove(displayId)?.close()
    }
    fun enableAccessibilityService(component: String): Boolean {
        require(component == ShellPrivilegePolicy.accessibilityComponent)
        val enabledResult = shellOutput("settings", "get", "secure", "enabled_accessibility_services")
        val enabled = enabledResult.trim().takeUnless { it == "null" }.orEmpty()
        val merged = (enabled.split(':').filter { it.isNotBlank() } + "$appPackage/.actions.ScreenControlService").distinct().joinToString(":")
        val saved = runCommand("settings", "put", "secure", "enabled_accessibility_services", merged)
        if (saved.isEmpty()) return false
        return runCommand("settings", "put", "secure", "accessibility_enabled", "1").isNotEmpty()
    }
    fun grantPermission(name: String): Boolean {
        require(ShellPrivilegePolicy.acceptsPermission(name))
        // A7 supplies symbolic capabilities only; it defines no Android permission mapping.
        return false
    }
    fun state(): String = "helper_uid=${Process.myUid()};helper_pid=${Process.myPid()};" +
        if (displays.isNotEmpty()) "present" else "present; helper active, no owned virtual displays"
    fun injectInput(displayId: Int, data: Bundle): Boolean {
        if (!ShellPrivilegePolicy.ownsDisplay(displayId, displays.keys)) return false
        val event = when (data.getString("type")) {
            "key" -> KeyEvent(data.getLong("downTime", android.os.SystemClock.uptimeMillis()), android.os.SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN, data.getInt("keyCode", -1), 0)
            "tap" -> MotionEvent.obtain(android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(),
                MotionEvent.ACTION_DOWN, data.getFloat("x"), data.getFloat("y"), 0)
            else -> return false
        }
        try {
            event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
            val manager = inputManager()
            val method = manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            return method.invoke(manager, event, 0) as Boolean
        } catch (_: Throwable) {
            val command = if (event is KeyEvent) arrayOf("input", "-d", "$displayId", "keyevent", "${event.keyCode}")
            else arrayOf("input", "-d", "$displayId", "tap", "${data.getFloat("x")}", "${data.getFloat("y")}")
            return runCommandSucceeded(*command)
        } finally {
            if (event is MotionEvent) event.recycle()
        }
    }
    fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean {
        if (!displays.containsKey(displayId)) return false
        return runCatching {
            val service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            service.javaClass.methods.first { it.name == "moveRootTaskToDisplay" && it.parameterTypes.size >= 2 }.invoke(service, taskId, displayId)
            true
        }.getOrDefault(false)
    }
    fun getClipboardText(): String? = if (ShellPrivilegePolicy.mayUseClipboard(false)) null else null // S10 lock is not present; fail closed.
    fun setClipboardText(text: String): Boolean { require(text.length <= 100_000); return ShellPrivilegePolicy.mayUseClipboard(false) }
    private fun inputManager(): Any = runCatching { Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null) }
        .getOrElse { Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!! }
    private fun runCommand(vararg args: String): String = try {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().take(4096)
        if (p.waitFor() == 0) out.ifBlank { "ok" } else ""
    } catch (_: Throwable) { "" }
    private fun runCommandSucceeded(vararg args: String): Boolean = try {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        p.inputStream.use { input -> val buffer = ByteArray(1024); while (input.read(buffer) >= 0) Unit }
        p.waitFor() == 0
    } catch (_: Throwable) { false }
    private fun shellOutput(vararg args: String): String = try {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().take(4096)
        if (p.waitFor() == 0) out else ""
    } catch (_: Throwable) { "" }
    companion object {
        @Volatile private var active: ShellPrivilegeBackend? = null
        fun releaseAll() { active?.displays?.values?.forEach { runCatching { it.release() } }; active?.displays?.clear() }
    }
    init { active = this }
}
