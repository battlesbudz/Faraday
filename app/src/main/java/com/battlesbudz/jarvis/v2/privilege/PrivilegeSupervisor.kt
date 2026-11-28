package com.battlesbudz.jarvis.v2.privilege

import android.content.Context
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.security.MessageDigest

/** Owns helper liveness, consented recovery decisions, diagnostics and periodic health scheduling. */
internal class PrivilegeSupervisor private constructor(private val context: Context) {
    private val app = context.applicationContext
    private val prefs get() = app.getSharedPreferences("app_control_privilege", Context.MODE_PRIVATE)
    @Volatile private var identityProblem: String? = PairingIdentity.initialize(app)
    private val recoveryExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "privilege-supervisor").apply { isDaemon = true } }
    @Volatile private var recoveryQueued = false
    @Volatile private var accessibilityEnableAttempted = false
    private val watchdog = PrivilegeWatchdogController(
        helperResponsive = { helperBinder()?.pingBinder() == true },
        pairingValid = { identityProblem == null && PairingIdentity.peerInfo() != null && prefs.getBoolean("adb_pairing_complete", false) },
        recoveryConsentGranted = { prefs.getBoolean(CONSENT, false) },
        requestConsentedRecovery = { recover("watchdog") },
    )

    fun start() {
        PrivilegeHandoffState.pending.compareAndSet(null, prefs.getString("helper_next_nonce", null))
        PrivilegeHandoffState.onHelperDeath = { record("helper death", false); check("helper death") }
        PrivilegeHandoffState.onHelperAttached = { check("helper attached") }
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            HEALTH_WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<PrivilegeHealthWorker>(15, TimeUnit.MINUTES).build(),
        )
        check("app start")
    }

    fun onForeground() = check("foreground")
    fun status(): PrivilegeWatchdogController.Status = check("state query")
    fun refreshForOperation(): PrivilegeWatchdogController.Status = check("operated task")
    fun enableAccessibilityService(): Boolean {
        val binder = helperBinder() ?: return false
        return runCatching {
            IPrivilegeHelper.Stub.asInterface(binder).enableAccessibilityService(
                ShellPrivilegePolicy.accessibilityComponent)
        }.getOrDefault(false)
    }
    fun recordPairingConsent(consented: Boolean) {
        // commit(), not apply(): consent is durable security state — the
        // write must be on disk before anything acts on it, matching
        // PairingIdentity.storePeerInfo's commit() in the pairing path.
        prefs.edit().putBoolean(CONSENT, consented).commit()
        if (consented) check("pairing consent") else record("pairing consent revoked", false, "wireless-debugging recovery requires user consent")
    }

    /** Revokes pairing admission without deleting the identity record initialized for this process. */
    fun revokePairing() {
        // commit(): a revocation must be durable the moment it returns —
        // an apply() still queued for disk flush could let a stale
        // on-disk consent survive a process death and resurrect pairing.
        prefs.edit().putBoolean(CONSENT, false).putBoolean("adb_pairing_complete", false).commit()
        check("pairing revoked")
    }

    @Synchronized fun check(source: String): PrivilegeWatchdogController.Status {
        val result = if (identityProblem != null) {
            PrivilegeWatchdogController.Status(false, identityProblem)
        } else watchdog.refresh()
        if (!result.present) {
            accessibilityEnableAttempted = false
        } else {
            val consented = prefs.getBoolean(CONSENT, false)
            val component = ShellPrivilegePolicy.accessibilityComponent
            val enabledServices = runCatching {
                Settings.Secure.getString(
                    app.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty().split(':')
            }.getOrDefault(emptyList())
            val accessibilityEnabled = component in enabledServices
            if (!accessibilityEnableAttempted && PostPairingCompletion.shouldAttemptEnable(
                    helperPresent = result.present,
                    consentGranted = consented,
                    accessibilityEnabled = accessibilityEnabled,
                )) {
                accessibilityEnableAttempted = true
                recoveryExecutor.execute { runCatching { enableAccessibilityService() } }
            }
        }
        record(source, result.present, result.reason)
        return result
    }

    /** Recovery always rediscovers the endpoint before an attempted connect. */
    private fun recover(source: String): Boolean {
        if (helperBinder() != null) return true
        synchronized(this) {
            if (recoveryQueued) return false
            recoveryQueued = true
        }
        recoveryExecutor.execute {
            try { launchHelper(source) }
            finally { recoveryQueued = false }
        }
        return false
    }

    private fun launchHelper(source: String): Boolean {
        val endpoint = AndroidAdbEndpointDiscovery(app).discover()
        if (endpoint == null) {
            record("$source recovery", false, "wireless-debugging connect endpoint is unavailable")
            return false
        }
        if (identityProblem != null || PairingIdentity.peerInfo() == null) {
            record("$source recovery", false, identityProblem ?: "wireless-debugging pairing identity is missing")
            return false
        }
        val nonce = HelperHandoff.nonce()
        PrivilegeHandoffState.pending.set(nonce)
        val preferences = prefs
        preferences.edit().putString("helper_launch_nonce", nonce).apply()
        val packageInfo = runCatching {
            app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrElse {
            PrivilegeHandoffState.pending.compareAndSet(nonce, null)
            record("$source recovery", false, "cannot read app signing certificate: ${it.message ?: "unknown error"}")
            return false
        }
        val certs = packageInfo.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        if (certs.isEmpty()) {
            PrivilegeHandoffState.pending.compareAndSet(nonce, null)
            record("$source recovery", false, "app signing certificate is unavailable")
            return false
        }
        val pin = certs.map { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } }.sorted().joinToString(",")
        val command = HelperHandoff.command(app.applicationInfo.sourceDir, app.packageName, android.os.Process.myUid(), pin)
        return try {
            val attached = AndroidAdbExecTransport().launch(endpoint, command, nonce) {
                helperBinder() != null
            }
            if (attached) record("$source recovery", true)
            else {
                PrivilegeHandoffState.pending.compareAndSet(nonce, null)
                record("$source recovery", false, "shell helper did not attach before the ADB exec deadline")
            }
            attached
        } catch (t: Throwable) {
            PrivilegeHandoffState.pending.compareAndSet(nonce, null)
            record("$source recovery", false, "paired ADB exec failed: ${t.message ?: t.javaClass.simpleName}")
            false
        }
    }

    private fun helperBinder(): IBinder? = PrivilegeHandoffState.helper.get()?.takeIf { it.isBinderAlive }
    private fun record(source: String, present: Boolean, reason: String? = null) {
        val state = if (present) "present" else "blocked: ${reason ?: "helper unavailable"}"
        prefs.edit().putString(STATE, state).putString("last_attempt", "$source: $state").apply()
        Log.i(TAG, "$source: $state")
    }

    companion object {
        const val CONSENT = "pairing_consent"
        private const val STATE = "state"
        private const val HEALTH_WORK = "privilege-helper-health"
        private const val TAG = "PrivilegeSupervisor"
        @Volatile private var instance: PrivilegeSupervisor? = null
        fun get(context: Context): PrivilegeSupervisor = instance ?: synchronized(this) {
            instance ?: PrivilegeSupervisor(context.applicationContext).also { instance = it }
        }
        fun runHealth(context: Context) = get(context).check("periodic health")
        fun savedState(context: Context): String? = context.applicationContext
            .getSharedPreferences("app_control_privilege", Context.MODE_PRIVATE).getString(STATE, null)
    }
}

class PrivilegeHealthWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        PrivilegeSupervisor.runHealth(applicationContext)
        return Result.success()
    }
}
