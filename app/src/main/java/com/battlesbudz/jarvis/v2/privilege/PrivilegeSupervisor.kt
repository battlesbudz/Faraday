package com.battlesbudz.jarvis.v2.privilege

import android.content.Context
import android.os.IBinder
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Owns helper liveness, consented recovery decisions, diagnostics and periodic health scheduling. */
internal class PrivilegeSupervisor private constructor(private val context: Context) {
    private val app = context.applicationContext
    private val prefs get() = app.getSharedPreferences("app_control_privilege", Context.MODE_PRIVATE)
    @Volatile private var identityProblem: String? = PairingIdentity.initialize(app)
    private val watchdog = PrivilegeWatchdogController(
        helperResponsive = { helperBinder()?.pingBinder() == true },
        pairingValid = { identityProblem == null && PairingIdentity.peerInfo() != null && prefs.getBoolean("adb_pairing_complete", false) },
        recoveryConsentGranted = { prefs.getBoolean(CONSENT, false) },
        requestConsentedRecovery = { recover("watchdog") },
    )

    fun start() {
        PrivilegeHandoffState.pending.compareAndSet(null, prefs.getString("helper_next_nonce", null))
        PrivilegeHandoffState.onHelperDeath = { record("helper death", false) }
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            HEALTH_WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<PrivilegeHealthWorker>(15, TimeUnit.MINUTES).build(),
        )
        check("app start")
    }

    fun onForeground() = check("foreground")
    fun status(): PrivilegeWatchdogController.Status = watchdog.status()
    fun refreshForOperation(): PrivilegeWatchdogController.Status = check("operated task")
    fun enableAccessibilityService(): Boolean {
        val binder = helperBinder() ?: return false
        return runCatching {
            IPrivilegeHelper.Stub.asInterface(binder).enableAccessibilityService(
                ShellPrivilegePolicy.accessibilityComponent)
        }.getOrDefault(false)
    }
    fun recordPairingConsent(consented: Boolean) {
        prefs.edit().putBoolean(CONSENT, consented).apply()
        if (consented) check("pairing consent") else record("pairing consent revoked", false, "wireless-debugging recovery requires user consent")
    }

    @Synchronized fun check(source: String): PrivilegeWatchdogController.Status {
        val result = if (identityProblem != null) {
            PrivilegeWatchdogController.Status(false, identityProblem)
        } else watchdog.refresh()
        record(source, result.present, result.reason)
        return result
    }

    /** Recovery always rediscovers the endpoint before an attempted connect. */
    private fun recover(source: String): Boolean {
        val endpoint = AndroidAdbEndpointDiscovery(app).discover()
        if (endpoint == null) {
            record("$source recovery", false, "wireless-debugging connect endpoint is unavailable")
            return false
        }
        // Pairing establishes TLS identity, while ADB exec is a separate channel.
        // Until both are available no helper is launched and operation remains blocked.
        record("$source recovery", false, "paired ADB exec channel is unavailable")
        return false
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
