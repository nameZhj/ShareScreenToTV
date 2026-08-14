package com.sharescreen.sender.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class DeviceDiscovery {
    private val DISCOVERY_PORT = 19999
    private val TAG = "DeviceDiscovery"

    fun discoverTv(onDeviceFound: (ip: String) -> Unit) {
        thread {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                socket.soTimeout = 3000 // 3 seconds timeout

                val message = "SHARE_SCREEN_DISCOVER"
                val data = message.toByteArray()
                // Broadcast address
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(data, data.size, broadcastAddress, DISCOVERY_PORT)
                
                socket.send(packet)
                Log.d(TAG, "Sent discovery broadcast")

                // Wait for response
                val receiveBuffer = ByteArray(256)
                val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                
                while (true) {
                    socket.receive(receivePacket)
                    val response = String(receivePacket.data, 0, receivePacket.length)
                    if (response == "SHARE_SCREEN_TV_HERE") {
                        val tvIp = receivePacket.address.hostAddress
                        Log.d(TAG, "Found TV at: $tvIp")
                        if (tvIp != null) {
                            onDeviceFound(tvIp)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Discovery failed: ${e.message}")
            } finally {
                socket?.close()
            }
        }
    }
}
