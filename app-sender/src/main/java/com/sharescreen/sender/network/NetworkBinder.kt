package com.sharescreen.sender.network

import android.net.Network

/**
 * Singleton that holds a reference to the bound Wi-Fi Network.
 * Populated by MainActivity once the Wi-Fi network becomes available.
 * Used by ControlClient and UdpSender to create sockets that are
 * explicitly tied to the Wi-Fi interface, bypassing any active VPN.
 */
object NetworkBinder {
    @Volatile
    var wifiNetwork: Network? = null
}
