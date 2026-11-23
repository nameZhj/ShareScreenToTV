package com.sharescreen.sender.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class UdpSender(private val targetIp: String, private val port: Int) {
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var boundNetwork: android.net.Network? = null
    private var lastSocketError: String? = null
    private val maxPayloadSize = 1400
    private var frameId = 0

    // Header: Type(1) | FrameId(4) | TotalChunks(2) | ChunkIndex(2) | Pts(8) = 17 bytes

    @Synchronized
    private fun getSocket(): Pair<DatagramSocket, InetAddress>? {
        return try {
            if (socket == null || socket!!.isClosed) {
                val route = NetworkBinder.createDatagramRoute(targetIp)
                socket = route.socket
                address = route.address
                boundNetwork = route.network
                lastSocketError = null
            }
            Pair(socket!!, address!!)
        } catch (e: Exception) {
            val message = e.localizedMessage ?: e.javaClass.simpleName
            if (message != lastSocketError) {
                lastSocketError = message
                Log.e("UdpSender", "LAN UDP route unavailable for " + targetIp + ": " + message)
            }
            null
        }
    }

    fun sendData(type: Byte, data: ByteArray, pts: Long) {
        val (sock, addr) = getSocket() ?: return
        val totalChunks = (data.size + maxPayloadSize - 1) / maxPayloadSize
        val currentFrameId = frameId++

        for (i in 0 until totalChunks) {
            val offset = i * maxPayloadSize
            val length = minOf(maxPayloadSize, data.size - offset)

            val buffer = ByteBuffer.allocate(17 + length)
            buffer.put(type)
            buffer.putInt(currentFrameId)
            buffer.putShort(totalChunks.toShort())
            buffer.putShort(i.toShort())
            buffer.putLong(pts)
            buffer.put(data, offset, length)

            val packetData = buffer.array()
            val packet = DatagramPacket(packetData, packetData.size, addr, port)
            try {
                sock.send(packet)
            } catch (e: Exception) {
                // A lost physical network invalidates the socket. Recreate it on the next frame.
                Log.w("UdpSender", "UDP send failed; refreshing LAN socket: " + e.message)
                close()
                break
            }
            if (i % 10 == 9) {
                try { Thread.sleep(1) } catch (_: Exception) {}
            }
        }
    }

    @Synchronized
    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        address = null
        boundNetwork = null
    }
}
