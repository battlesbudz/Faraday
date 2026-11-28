package com.battlesbudz.jarvis.v2.privilege

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.battlesbudz.jarvis.v2.actions.ScreenControlService

/** Thin Android reads used by the visible pairing checklist. */
internal object PairingWizardSystemState {
    data class Snapshot(
        val developerOptions: Boolean?,
        val usbDebugging: Boolean?,
        val wirelessDebugging: Boolean?,
        val wifiConnected: Boolean,
        val accessibilityRunning: Boolean,
        val notificationsAllowed: Boolean,
        val pairingChannelBlocked: Boolean,
    )

    fun read(context: Context): Snapshot {
        val app = context.applicationContext
        fun global(name: String): Boolean? = runCatching {
            Settings.Global.getInt(app.contentResolver, name) == 1
        }.getOrNull()
        val notificationsEnabled = runCatching {
            NotificationManagerCompat.from(app).areNotificationsEnabled()
        }.getOrDefault(false)
        val notificationsAllowed = notificationsEnabled && (Build.VERSION.SDK_INT < 33 || runCatching {
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false))
        val channelBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                app.getSystemService(android.app.NotificationManager::class.java)
                    ?.getNotificationChannel(PairingNotifier.CHANNEL_ID)
                    ?.importance == android.app.NotificationManager.IMPORTANCE_NONE
            }.getOrDefault(false)
        } else false
        val wifiConnected = runCatching {
            val manager = app.getSystemService(ConnectivityManager::class.java)
            val network = manager?.activeNetwork
            network != null && manager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)
        return Snapshot(
            developerOptions = global("development_settings_enabled"),
            usbDebugging = global("adb_enabled"),
            wirelessDebugging = global("adb_wifi_enabled"),
            wifiConnected = wifiConnected,
            accessibilityRunning = ScreenControlService.pairingReaderInstance() != null,
            notificationsAllowed = notificationsAllowed,
            pairingChannelBlocked = channelBlocked,
        )
    }
}
