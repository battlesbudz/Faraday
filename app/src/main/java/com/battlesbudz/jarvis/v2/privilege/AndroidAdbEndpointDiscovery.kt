package com.battlesbudz.jarvis.v2.privilege

import android.content.Context
import android.net.ConnectivityManager
import android.provider.Settings
import java.net.Inet4Address

/** Discovers the phone's wireless-debugging connect endpoint from its active network. */
internal class AndroidAdbEndpointDiscovery(context: Context) {
    private val appContext = context.applicationContext
    fun discover(): AdbEndpoint? {
        val port = Settings.Global.getInt(appContext.contentResolver, "adb_wifi_port", -1)
            .takeIf { it in 1..65535 } ?: return null
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        val properties = manager.getLinkProperties(manager.activeNetwork) ?: return null
        val address = properties.linkAddresses.asSequence().map { it.address }
            .filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?: return null
        return adbEndpoint(address.hostAddress, port)
    }
}
