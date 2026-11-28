package com.battlesbudz.jarvis.v2.privilege

/** Pure checklist policy for the Android wireless pairing journey. */
internal object PairingWizardFlow {
    enum class Step { DeveloperOptions, UsbDebugging, WirelessDebugging, PairingCode, Connect }
    enum class Phase { Guide, Working, Success, Failed }
    enum class Mark { Done, Current, Todo, Failed, Skipped }

    data class Input(
        val developerOptions: Boolean?,
        val usbDebugging: Boolean?,
        val wirelessDebugging: Boolean?,
        val wifiConnected: Boolean,
        val accessibilityRunning: Boolean,
        val notificationsAllowed: Boolean,
        val pairingChannelBlocked: Boolean,
        val helperPresent: Boolean,
        val paired: Boolean,
        val armed: Boolean,
        val attemptInFlight: Boolean,
        val dialogFound: Boolean,
        val lastFailure: String?,
        val confirmed: Set<Step>,
    )

    data class View(
        val phase: Phase,
        val marks: Map<Step, Mark>,
        val currentStep: Step?,
        val autoRead: Boolean,
        val needsNotifications: Boolean,
        val suspect: Step?,
        val wifiMissing: Boolean,
    )

    val steps: List<Step> = listOf(
        Step.DeveloperOptions,
        Step.UsbDebugging,
        Step.WirelessDebugging,
        Step.PairingCode,
        Step.Connect,
    )

    fun evaluate(input: Input): View {
        if (input.helperPresent) return View(
            Phase.Success, steps.associateWith { Mark.Done }, null,
            input.accessibilityRunning, false, null, false,
        )

        val switchReadings = listOf(
            Step.DeveloperOptions to input.developerOptions,
            Step.UsbDebugging to input.usbDebugging,
            Step.WirelessDebugging to input.wirelessDebugging,
        )
        val satisfied = switchReadings.associate { (step, enabled) ->
            step to (enabled != false || step in input.confirmed)
        }
        val currentSwitch = if (input.paired) null else
            switchReadings.firstOrNull { (step, _) -> satisfied[step] != true }?.first
        val current = when {
            currentSwitch != null -> currentSwitch
            input.paired -> Step.Connect
            else -> Step.PairingCode
        }
        val failure = input.lastFailure?.takeIf(String::isNotBlank)
        val suspect = failure?.let(::suspectStep)
        val marks = LinkedHashMap<Step, Mark>()
        steps.forEach { step ->
            marks[step] = when {
                failure != null && step == suspect -> Mark.Failed
                input.paired && step in satisfied -> Mark.Done
                step in satisfied && satisfied[step] == true -> Mark.Done
                step == Step.Connect && !input.paired -> Mark.Skipped
                step == current -> Mark.Current
                step == Step.PairingCode && input.paired -> Mark.Done
                step == Step.Connect && input.paired -> Mark.Current
                else -> Mark.Todo
            }
        }
        val pairingCurrent = current == Step.PairingCode
        val phase = when {
            failure != null -> Phase.Failed
            input.armed || input.attemptInFlight -> Phase.Working
            input.paired -> Phase.Working
            else -> Phase.Guide
        }
        return View(
            phase = phase,
            marks = marks,
            currentStep = current,
            autoRead = pairingCurrent && input.accessibilityRunning,
            needsNotifications = pairingCurrent && !input.accessibilityRunning &&
                (!input.notificationsAllowed || input.pairingChannelBlocked),
            suspect = suspect,
            wifiMissing = current == Step.WirelessDebugging && !input.wifiConnected,
        )
    }

    private fun suspectStep(reason: String): Step? {
        val text = reason.lowercase()
        if ("code" in text && listOf("wrong", "expired", "reject").any { text.contains(it) }) {
            return Step.PairingCode
        }
        if (listOf("endpoint", "connect", "pairing service", "pairing transport", "wireless debugging", "wireless-debugging")
                .any { text.contains(it) } &&
            listOf("unavailable", "unreachable", "failed", "closed", "missing", "requires", "absent")
                .any { text.contains(it) }) {
            return Step.WirelessDebugging
        }
        return null
    }
}

