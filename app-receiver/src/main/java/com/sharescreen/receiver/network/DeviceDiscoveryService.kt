package com.sharescreen.receiver.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class DeviceDiscoveryService {
    private var socket: DatagramSocket? = null
    private var isRunning = false
    private val DISCOVERY_PORT = 19999
    private val TAG = "DeviceDiscovery"

    fun start() {
        if (isRunning) return
        isRunning = true
        thread {
            try {
                socket = DatagramSocket(DISCOVERY_PORT)
                socket?.broadcast = true
                val buffer = ByteArray(256)
                while (isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    Log.d(TAG, "Received discovery message: $message from ${packet.address.hostAddress}")
                    if (message == "SHARE_SCREEN_DISCOVER") {
                        val response = "SHARE_SCREEN_TV_HERE"
                        val responseData = response.toByteArray()
                        val responsePacket = DatagramPacket(
                            responseData,
                            responseData.size,
                            packet.address,
                            packet.port
                        )
                        socket?.send(responsePacket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Discovery service error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        socket?.close()
        socket = null
    }
}
