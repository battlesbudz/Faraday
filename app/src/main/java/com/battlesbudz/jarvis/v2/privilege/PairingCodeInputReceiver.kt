package com.battlesbudz.jarvis.v2.privilege

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Delivers the notification's private RemoteInput value directly to the process pairing owner. */
internal class PairingCodeInputReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotifier.KEY_CODE)?.toString() ?: return
        PairingSetupController.get(context).submitManual(code)
    }
}
