package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.voice.CallFinalInput

/** Every claimed typed final is promoted, restored, handed off, or visibly terminalized once. */
internal class VoiceTypedInputOwnership(private val call: VoiceCallAccess) {
    fun relinquish(input: CallFinalInput?) {
        val typed = input ?: return
        if (call.state.inputQueue.restore(typed, call.controller.currentCallId())) {
            call.state.preparingTypedInput.compareAndSet(typed, null)
            return
        }
        if (call.state.inputQueue.terminalize(typed)) {
            call.state.preparingTypedInput.compareAndSet(typed, null)
            terminalReceipt(typed)
        }
    }

    fun terminalize(input: CallFinalInput) {
        if (call.state.inputQueue.terminalize(input)) terminalReceipt(input)
    }

    private fun terminalReceipt(input: CallFinalInput) {
        call.controller.recordTerminalInputForCall(input.callId, input.id,
            "Cancelled before processing typed message: ${input.text}")
    }
}
