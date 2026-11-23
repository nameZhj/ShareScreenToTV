package com.sharescreen.sender.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Selects a physical LAN network for TV traffic without changing the process-wide
 * default network. Every TCP/UDP socket is explicitly bound to the physical route
 * that reaches the TV, so unrelated Internet traffic keeps using the user's VPN.
 */
object NetworkBinder {
    private const val TAG = "LanNetworkBinder"

    @Volatile private var connectivityManager: ConnectivityManager? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val physicalNetworks = ConcurrentHashMap.newKeySet<Network>()

    @Volatile
    var wifiNetwork: Network? = null
        private set

    data class BoundDatagramRoute(
        val socket: DatagramSocket,
        val address: InetAddress,
        val network: Network
    )

    @Synchronized
    fun start(context: Context) {
        if (connectivityManager != null) {
            refreshNetworks()
            return
        }

        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = manager

        // Clear the default NOT_VPN capability and explicitly request only physical
        // Wi-Fi/Ethernet transports. The VPN transport itself is never selected.
        val request = NetworkRequest.Builder()
            .clearCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateNetwork(network)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                updateNetwork(network, networkCapabilities)
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties
            ) {
                updateNetwork(network)
            }

            override fun onLost(network: Network) {
                physicalNetworks.remove(network)
                refreshNetworks()
                Log.i(TAG, "Physical LAN network lost: " + network)
            }
        }

        networkCallback = callback
        try {
            manager.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            networkCallback = null
            Log.e(TAG, "Unable to monitor physical LAN networks: " + e.message, e)
        }
        refreshNetworks()
    }

    @Synchronized
    private fun refreshNetworks() {
        val manager = connectivityManager ?: return
        val current = manager.allNetworks.toSet()
        physicalNetworks.removeIf { it !in current }
        current.forEach { updateNetwork(it) }
        wifiNetwork = physicalNetworks.firstOrNull { isWifi(it) }
            ?: physicalNetworks.firstOrNull()
    }

    private fun updateNetwork(
        network: Network,
        knownCapabilities: NetworkCapabilities? = null
    ) {
        val manager = connectivityManager ?: return
        try {
            val capabilities = knownCapabilities ?: manager.getNetworkCapabilities(network)
            val isPhysicalLan = capabilities != null &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            val links = manager.getLinkProperties(network)
            val hasIpv4 = links?.linkAddresses?.any {
                it.address !is java.net.Inet6Address &&
                    !it.address.isLoopbackAddress &&
                    !it.address.isLinkLocalAddress
            } == true

            if (isPhysicalLan && hasIpv4) {
                physicalNetworks.add(network)
                wifiNetwork = physicalNetworks.firstOrNull { isWifi(it) }
                    ?: physicalNetworks.firstOrNull()
            } else {
                physicalNetworks.remove(network)
                wifiNetwork = physicalNetworks.firstOrNull { isWifi(it) }
                    ?: physicalNetworks.firstOrNull()
            }
        } catch (e: Exception) {
            physicalNetworks.remove(network)
            Log.w(TAG, "Unable to inspect network " + network + ": " + e.message)
        }
    }

    private fun isWifi(network: Network): Boolean {
        val manager = connectivityManager ?: return false
        return try {
            manager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resolve the TV address on the selected physical network and prefer the network
     * with the most-specific route to that address (important with Wi-Fi + Ethernet).
     */
    fun getNetworkForAddress(host: String): Network {
        val manager = connectivityManager
            ?: throw IOException("局域网网络管理器尚未初始化")
        refreshNetworks()

        val address = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            throw IOException("电视地址无效: " + host, e)
        }

        val candidates = physicalNetworks.mapNotNull { network ->
            try {
                val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                val links = manager.getLinkProperties(network) ?: return@mapNotNull null
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                val isPhysical = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!isPhysical) return@mapNotNull null

                val matchingPrefix = links.routes
                    .filter { it.matches(address) }
                    .maxOfOrNull { it.destination.prefixLength } ?: -1
                if (matchingPrefix < 0) return@mapNotNull null

                Triple(network, matchingPrefix, capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            } catch (e: Exception) {
                Log.w(TAG, "Unable to inspect LAN route: " + e.message)
                null
            }
        }

        return candidates
            .sortedWith(
                compareByDescending<Triple<Network, Int, Boolean>> { it.second }
                    .thenByDescending { it.third }
            )
            .firstOrNull()?.first
            ?: throw IOException(
                "找不到通往电视 $host 的物理 Wi-Fi/以太网路由。请确认手机与电视在同一局域网，" +
                    "并在 VPN 设置中允许局域网访问或为 ShareScreen 开启绕过 VPN。"
            )
    }

    fun createTcpSocket(host: String, port: Int, timeoutMs: Int): Socket {
        val network = getNetworkForAddress(host)
        val socket = try {
            network.socketFactory.createSocket()
        } catch (e: Exception) {
            throw IOException("无法创建局域网 TCP 连接", e)
        }

        try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            return socket
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw IOException(
                "无法通过物理局域网连接电视 $host:$port。VPN 可能禁止应用绕过隧道访问局域网，" +
                    "请检查 VPN 的“允许局域网/LAN 访问”设置。",
                e
            )
        }
    }

    fun createDatagramRoute(host: String): BoundDatagramRoute {
        val network = getNetworkForAddress(host)
        val address = try {
            network.getByName(host)
        } catch (e: Exception) {
            throw IOException("无法在局域网网络上解析电视地址: " + host, e)
        }

        val socket = try {
            DatagramSocket().apply {
                network.bindSocket(this)
                sendBufferSize = 2 * 1024 * 1024
            }
        } catch (e: Exception) {
            throw IOException(
                "无法绑定局域网 UDP Socket。VPN 可能禁止绕过隧道访问局域网，" +
                    "请检查 VPN 的“允许局域网/LAN 访问”设置。",
                e
            )
        }

        return BoundDatagramRoute(socket, address, network)
    }
}
