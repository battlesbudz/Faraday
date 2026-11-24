package com.battlesbudz.jarvis.v2.privilege

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import com.battlesbudz.jarvis.v2.MainActivity

/** Posts pairing progress and the system RemoteInput fallback under notification id 4417. */
internal object PairingNotifier : PairingSetupNotifier {
    const val CHANNEL_ID = "app_control_pairing"
    const val KEY_CODE = "pairing_code"
    private const val NOTIFICATION_ID = 4417
    @Volatile private var inputTimeoutMs = 600_000L

    @Volatile private var appContext: Context? = null

    internal fun bind(context: Context, armedWindowMs: Long) {
        appContext = context.applicationContext
        inputTimeoutMs = armedWindowMs
    }

    override fun showEnterCode(error: String?) = withManager { context, manager ->
        ensureChannel(manager)
        val open = PendingIntent.getActivity(
            context, 4417, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val input = RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").setAllowFreeFormInput(true).build()
        val reply = PendingIntent.getBroadcast(
            context, 4417, Intent(context, PairingCodeInputReceiver::class.java),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(null, "Pair with code", reply)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) action.setAuthenticationRequired(false)
        val text = error ?: "Open the Android pairing dialog, then type the 6-digit code here."
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
            .setContentTitle("Pair with Faraday")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setTimeoutAfter(inputTimeoutMs)
            .addAction(action.build())
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun showWorking() = withManager { context, manager ->
        ensureChannel(manager)
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentTitle("Pairing…")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .build(),
        )
    }

    override fun showDone() = withManager { context, manager ->
        ensureChannel(manager)
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentTitle("Paired")
                .setContentText("The app-control helper can now start.")
                .setAutoCancel(true)
                .setTimeoutAfter(8_000)
                .build(),
        )
    }

    override fun showProblem(reason: String) = withManager { context, manager ->
        ensureChannel(manager)
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentTitle("Pairing did not finish")
                .setContentText(reason)
                .setAutoCancel(true)
                .build(),
        )
    }

    override fun cancel() = withManager { _, manager -> manager.cancel(NOTIFICATION_ID) }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App control pairing", NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    private inline fun withManager(block: (Context, NotificationManager) -> Unit) {
        val context = appContext ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { block(context, manager) }
    }
}
