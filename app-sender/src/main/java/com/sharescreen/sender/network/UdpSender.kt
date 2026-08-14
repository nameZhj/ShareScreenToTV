package com.sharescreen.sender.network

import android.os.Build
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class UdpSender(private val targetIp: String, private val port: Int) {
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private val MAX_PAYLOAD_SIZE = 1400
    private var frameId = 0

    // Header: Type(1) | FrameId(4) | TotalChunks(2) | ChunkIndex(2) | Pts(8) = 17 bytes

    @Synchronized
    private fun getSocket(): Pair<DatagramSocket, InetAddress>? {
        return try {
            if (socket == null || socket!!.isClosed) {
                val sock = DatagramSocket()
                // Bind the UDP socket to the explicit Wi-Fi network so it bypasses VPN
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    NetworkBinder.wifiNetwork?.bindSocket(sock)
                }
                sock.sendBufferSize = 2 * 1024 * 1024 // 2MB
                socket = sock
                address = InetAddress.getByName(targetIp)
            }
            Pair(socket!!, address!!)
        } catch (e: Exception) {
            null
        }
    }

    fun sendData(type: Byte, data: ByteArray, pts: Long) {
        val (sock, addr) = getSocket() ?: return
        val totalChunks = (data.size + MAX_PAYLOAD_SIZE - 1) / MAX_PAYLOAD_SIZE
        val currentFrameId = frameId++

        for (i in 0 until totalChunks) {
            val offset = i * MAX_PAYLOAD_SIZE
            val length = minOf(MAX_PAYLOAD_SIZE, data.size - offset)

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
                // Tolerate single packet drop in real-time stream
            }
            if (i % 10 == 9) {
                try { Thread.sleep(1) } catch (e: Exception) {}
            }
        }
    }

    fun close() {
        socket?.close()
        socket = null
    }
}
