package com.battlesbudz.jarvis.v2.display

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.battlesbudz.jarvis.v2.actions.ActionApprovalRequest
import com.battlesbudz.jarvis.v2.actions.ActionApprovalStore

data class PendingWatchApproval(val request: ActionApprovalRequest, val target: String, val exactContent: String)

@Composable
fun OperatedWatchSurface(
    appName: String,
    taskName: String,
    frame: Bitmap?,
    paused: Boolean,
    approval: PendingWatchApproval?,
    approvalStore: ActionApprovalStore?,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
    takeoverSuspended: Boolean = false,
    onHandBack: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(appName, style = MaterialTheme.typography.titleLarge)
        Text(taskName, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        if (frame != null) Image(frame.asImageBitmap(), "Live view of $appName on the operated display",
            Modifier.fillMaxWidth().heightIn(max = 360.dp))
        else Text("Waiting for the operated display frame…")
        approval?.let { pending ->
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Approval required", style = MaterialTheme.typography.titleMedium)
                    Text(pending.request.action.name)
                    Text("Target: ${pending.target}")
                    Text(pending.exactContent)
                    Row {
                        Button(onClick = {
                            approvalStore?.consume(pending.request.id, pending.request.fingerprint)
                        }) { Text("Approve") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { approvalStore?.deny(pending.request.id) }) { Text("Decline") }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 12.dp)) {
            Button(onClick = if (paused) onResume else onPause) { Text(if (paused) "Resume" else "Pause") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onStop) { Text("End task") }
            if (takeoverSuspended) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onHandBack) { Text("I'm done — hand back") }
            }
        }
    }
}

/** High-importance out-of-app signal; the watch card remains the decision surface. */
object OperatedApprovalNotifier {
    private const val CHANNEL = "operated-approval"
    private const val ID = 9217
    fun post(context: Context, taskName: String, actionName: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Operated task approvals", NotificationManager.IMPORTANCE_HIGH))
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Faraday needs your approval: $taskName — $actionName")
            .setContentText("Open Faraday to review this action.")
            .setAutoCancel(true)
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            builder.setContentIntent(android.app.PendingIntent.getActivity(context, ID, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
        }
        manager.notify(ID, builder.build())
    }
    fun withdraw(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(ID) }
}
