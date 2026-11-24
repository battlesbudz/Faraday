package com.battlesbudz.jarvis.v2.privilege

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Process owner for the armed pairing reader, user fallbacks and pairing attempt admission. */
internal class PairingSetupController(
    context: Context,
    private val supervisor: PrivilegeSupervisor,
    private val discovery: () -> AdbEndpoint? = { AndroidAdbEndpointDiscovery(context.applicationContext).discover() },
    private val transportFactory: () -> WirelessDebuggingPairingTransport = { AndroidAdbPairingTransport() },
    private val windowTexts: () -> List<List<String>> = { PairingCodeReader.windowTexts(context.applicationContext) },
    private val notifier: PairingSetupNotifier = PairingNotifier,
    private val pollIntervalMs: Long = 700,
    private val armedWindowMs: Long = 600_000,
    private val autoReadGraceMs: Long = 5_000,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val attemptMutex = Mutex()
    private val mutableState = MutableStateFlow(PairingSetupState())
    private var armedJob: Job? = null
    @Volatile private var deadline = 0L

    init {
        PairingNotifier.bind(app, armedWindowMs)
    }

    val state: StateFlow<PairingSetupState> = mutableState.asStateFlow()

    fun status(): PrivilegeWatchdogController.Status = supervisor.status()

    @Synchronized
    fun arm() {
        deadline = SystemClock.elapsedRealtime() + armedWindowMs
        val freshArm = !mutableState.value.armed
        mutableState.value = mutableState.value.copy(armed = true, lastMessage = null)
        if (freshArm) {
            armedJob?.cancel()
            armedJob = scope.launch { runArmedWindow() }
        }
    }

    @Synchronized
    fun disarm() {
        finishArmed(cancelNotice = true)
    }

    fun submitManual(raw: String): Boolean {
        val code = raw.filterNot(Char::isWhitespace)
        if (!CODE.matches(code)) {
            updateMessage("That is not the 6-digit code from the pairing dialog.")
            return false
        }
        scope.launch { pairAttempt(code, null) }
        return true
    }

    fun revoke() {
        disarm()
        supervisor.revokePairing()
        updateMessage("Pairing revoked. App control is off until you pair again.")
    }

    private suspend fun runArmedWindow() {
        val readerAvailable = com.battlesbudz.jarvis.v2.actions.ScreenControlService.pairingReaderInstance() != null
        val reader = scope.launch {
            PairingCodeReader.run(
                pollIntervalMs = pollIntervalMs,
                isArmed = { mutableState.value.armed },
                attemptInFlight = { mutableState.value.attemptInFlight },
                discovery = discovery,
                waitingToPair = { runCatching { !supervisor.status().present }.getOrDefault(false) },
                windowTexts = windowTexts,
                onAttempt = ::pairAttempt,
            )
        }
        try {
            if (!readerAvailable) {
                notifier.showEnterCode(mutableState.value.lastMessage)
            } else {
                delay(minOf(autoReadGraceMs, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)))
                if (mutableState.value.armed && SystemClock.elapsedRealtime() < deadline) {
                    notifier.showEnterCode(mutableState.value.lastMessage)
                }
            }
            while (mutableState.value.armed) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    synchronized(this) { finishArmed(cancelNotice = true) }
                    return
                }
                delay(minOf(pollIntervalMs, remaining))
            }
        } finally {
            reader.cancel()
        }
    }

    private suspend fun pairAttempt(code: String, endpointOverride: AdbEndpoint?) {
        if (!attemptMutex.tryLock()) return
        try {
            mutableState.value = mutableState.value.copy(attemptInFlight = true)
            notifier.showWorking()
            val states = PairingStateMachine()
            val engine = PairingCoordinator(
                states = states,
                discover = { endpointOverride ?: discovery() },
                transport = transportFactory(),
            )
            val reason = engine.pair(code.toCharArray())
            when (states.snapshot().state) {
                PairingStateMachine.State.CONNECTED -> {
                    supervisor.recordPairingConsent(true)
                    mutableState.value = mutableState.value.copy(
                        armed = false,
                        lastMessage = "Paired. The app-control helper can now start.",
                    )
                    notifier.showDone()
                    synchronized(this) { finishArmed(cancelNotice = false, cancelJob = false) }
                }
                else -> {
                    mutableState.value = mutableState.value.copy(lastMessage = reason)
                    if (mutableState.value.armed) notifier.showEnterCode(reason)
                    else notifier.showProblem(reason)
                }
            }
        } finally {
            mutableState.value = mutableState.value.copy(attemptInFlight = false)
            attemptMutex.unlock()
        }
    }

    @Synchronized
    private fun finishArmed(cancelNotice: Boolean, cancelJob: Boolean = true) {
        val job = armedJob
        armedJob = null
        if (cancelJob) job?.cancel()
        mutableState.value = mutableState.value.copy(armed = false)
        if (cancelNotice) notifier.cancel()
    }

    private fun updateMessage(message: String) {
        mutableState.value = mutableState.value.copy(lastMessage = message)
    }

    companion object {
        private val CODE = Regex("""\d{6}""")
        @Volatile private var instance: PairingSetupController? = null

        fun get(context: Context): PairingSetupController = instance ?: synchronized(this) {
            instance ?: PairingSetupController(
                context.applicationContext,
                PrivilegeSupervisor.get(context.applicationContext),
            ).also { instance = it }
        }
    }
}

/** Small UI-observable state owned by [PairingSetupController]. */
internal data class PairingSetupState(
    val armed: Boolean = false,
    val attemptInFlight: Boolean = false,
    val lastMessage: String? = null,
)

/** Notification operations used by the process controller and replaceable by behavior-test fakes. */
internal interface PairingSetupNotifier {
    fun showEnterCode(error: String?)
    fun showWorking()
    fun showDone()
    fun showProblem(reason: String)
    fun cancel()
}
