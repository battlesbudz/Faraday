package com.battlesbudz.jarvis.v2.privilege

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/** Narrow, one-shot app_process handoff protocol. The nonce is never placed in process arguments. */
internal object HelperHandoff {
    const val SHELL_UID = 2000
    const val ATTACH = "attach"
    const val NONCE = "nonce"
    const val HELPER = "binder"
    const val UID = "uid"
    const val PID = "pid"
    const val APK = "apk"
    const val ACCEPTED = "accepted"
    const val REASON = "reason"
    const val NEXT = "next"
    const val TOKEN = "token"

    enum class Reject(val reason: String) {
        NOT_SHELL("not-shell"), ID_MISMATCH("id-mismatch"), STALE_APK("stale-apk"),
        NO_BINDER("no-binder"), BAD_NONCE("bad-nonce"), BINDER_ELSEWHERE("binder-elsewhere")
    }

    fun validate(
        callerUid: Int, callerPid: Int, reportedUid: Int, reportedPid: Int,
        apk: String?, installedApk: String, binder: IBinder?, nonceMatches: Boolean,
        binderOwnerUid: Int = reportedUid, binderOwnerPid: Int = reportedPid,
    ): Reject? = when {
        callerUid != SHELL_UID -> Reject.NOT_SHELL
        reportedUid != callerUid || reportedPid != callerPid -> Reject.ID_MISMATCH
        apk != installedApk -> Reject.STALE_APK
        binder == null -> Reject.NO_BINDER
        !nonceMatches -> Reject.BAD_NONCE
        binderOwnerUid != reportedUid || binderOwnerPid != reportedPid -> Reject.BINDER_ELSEWHERE
        else -> null
    }

    fun nonce(): String = ByteArray(32).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }

    /** Shell command used on the ADB exec stream. The secret is sent separately on stdin. */
    fun command(apkPath: String, packageName: String, appUid: Int, certificatePin: String): String {
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        return "read n; (echo \"\$n\" | CLASSPATH=${quote(apkPath)} setsid app_process /system/bin " +
            "--nice-name=${quote("$packageName:helper")} com.battlesbudz.jarvis.v2.privilege.PrivilegeHelperMain " +
            "${quote(packageName)} $appUid ${quote(certificatePin)} >/dev/null 2>&1 &); read _"
    }

    fun nonceStdin(nonce: String) = "$nonce\n".toByteArray(Charsets.UTF_8)
}

/** Internal provider, reached by shell via ActivityManager.getContentProviderExternal. */
class PrivilegeHandoffProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != HelperHandoff.ATTACH || extras == null) return reject("bad-nonce")
        val nonce = extras.getString(HelperHandoff.NONCE)
        val callerUid = Binder.getCallingUid()
        val callerPid = Binder.getCallingPid()
        val binder = extras.getBinder(HelperHandoff.HELPER)
        val pin = PrivilegeHandoffState.pending.get()
        val installed = context?.applicationInfo?.sourceDir.orEmpty()
        val helperApk = extras.getString(HelperHandoff.APK)
        val reason = HelperHandoff.validate(
            callerUid, callerPid, extras.getInt(HelperHandoff.UID, -1), extras.getInt(HelperHandoff.PID, -1),
            helperApk, installed, binder, nonce != null && pin != null && MessageDigest.isEqual(
                nonce.toByteArray(Charsets.UTF_8), pin.toByteArray(Charsets.UTF_8)),
        )
        if (reason != null) return reject(reason.reason)
        if (context?.packageName != "com.battlesbudz.jarvis.v2" || context?.applicationInfo?.uid != Process.myUid()) {
            return reject("binder-elsewhere")
        }
        val helperIdentity = runCatching {
            IPrivilegeHelper.Stub.asInterface(binder).getPrivilegeState()
        }.getOrNull().orEmpty()
        val selfReportedByBinder = Regex("helper_uid=(\\d+);helper_pid=(\\d+)").find(helperIdentity)
        if (selfReportedByBinder?.groupValues?.getOrNull(1)?.toIntOrNull() != extras.getInt(HelperHandoff.UID, -1) ||
            selfReportedByBinder?.groupValues?.getOrNull(2)?.toIntOrNull() != extras.getInt(HelperHandoff.PID, -1)) {
            return reject("binder-elsewhere")
        }
        val next = HelperHandoff.nonce()
        if (!PrivilegeHandoffState.pending.compareAndSet(pin, null)) return reject("bad-nonce")
        val token = PrivilegeHandoffState.appToken
        val death = object : IBinder.DeathRecipient { override fun binderDied() { PrivilegeHandoffState.helperDied() } }
        try { binder!!.linkToDeath(death, 0) } catch (_: Throwable) { return reject("binder-elsewhere") }
        PrivilegeHandoffState.helper.set(binder)
        PrivilegeHandoffState.pending.set(next)
        context?.getSharedPreferences("app_control_privilege", android.content.Context.MODE_PRIVATE)
            ?.edit()?.putString("helper_next_nonce", next)?.apply()
        return Bundle().apply {
            putBoolean(HelperHandoff.ACCEPTED, true)
            putBinder(HelperHandoff.TOKEN, token)
            putString(HelperHandoff.NEXT, next)
        }
    }
    private fun reject(reason: String) = Bundle().apply {
        putBoolean(HelperHandoff.ACCEPTED, false); putString(HelperHandoff.REASON, reason)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

internal object PrivilegeHandoffState {
    val pending = AtomicReference<String?>(null)
    val helper = AtomicReference<IBinder?>(null)
    val appToken: IBinder = Binder()
    @Volatile var onHelperDeath: (() -> Unit)? = null
    fun helperDied() { helper.set(null); onHelperDeath?.invoke() }
}
