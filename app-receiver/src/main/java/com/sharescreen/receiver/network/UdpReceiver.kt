package com.sharescreen.receiver.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class UdpReceiver(private val port: Int) {
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var isRunning = false
    @Volatile var allowedSenderIp: String? = null

    private val tag = "UdpReceiver"
    private val maxPayloadSize = 1400
    private val maxActiveFrames = 4
    private val maxFrameSize = 8 * 1024 * 1024
    var onVideoFrameReceived: ((ByteArray, Long) -> Unit)? = null
    var onAudioFrameReceived: ((ByteArray, Long) -> Unit)? = null

    private val frameBuffer = ConcurrentHashMap<Int, FrameData>()

    private class FrameData(val totalChunks: Int, val pts: Long) {
        val chunks = arrayOfNulls<ByteArray>(totalChunks)
        @Volatile var receivedChunks = 0
        @Volatile var lastUpdateTime = System.currentTimeMillis()
        @Volatile var receivedBytes = 0
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        thread(name = "UdpReceiver-" + port) {
            try {
                val receiver = DatagramSocket(java.net.InetSocketAddress("0.0.0.0", port))
                socket = receiver
                receiver.receiveBufferSize = 2 * 1024 * 1024
                val receiveData = ByteArray(65535)
                while (isRunning) {
                    try {
                        val packet = DatagramPacket(receiveData, receiveData.size)
                        receiver.receive(packet)
                        val allowedIp = allowedSenderIp ?: continue
                        if (packet.address.hostAddress != allowedIp) continue
                        if (packet.length < 18) continue

                        val buffer = ByteBuffer.wrap(packet.data, 0, packet.length)
                        val type = buffer.get()
                        val frameId = buffer.int
                        val totalChunks = buffer.short.toInt() and 0xFFFF
                        val chunkIndex = buffer.short.toInt() and 0xFFFF
                        val pts = buffer.long
                        val payloadSize = packet.length - 17

                        if (totalChunks !in 1..4096 ||
                            chunkIndex !in 0 until totalChunks ||
                            payloadSize !in 1..maxPayloadSize
                        ) continue
                        if (type != 1.toByte() && type != 2.toByte()) continue

                        val payload = ByteArray(payloadSize)
                        buffer.get(payload)
                        handleChunk(type, frameId, totalChunks, chunkIndex, pts, payload)
                    } catch (e: Exception) {
                        if (!isRunning) break
                        Log.w(tag, "Packet rejected on " + port + ": " + e.message)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(tag, "UDP socket error on " + port + ": " + e.message, e)
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
            }
        }

        thread(name = "UdpReceiver-Cleanup-" + port) {
            while (isRunning) {
                try {
                    Thread.sleep(100)
                    val now = System.currentTimeMillis()
                    frameBuffer.forEach { (key, value) ->
                        if (now - value.lastUpdateTime > 1000) frameBuffer.remove(key, value)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun handleChunk(type: Byte, frameId: Int, totalChunks: Int, chunkIndex: Int, pts: Long, payload: ByteArray) {
        try {
            if (!frameBuffer.containsKey(frameId) && frameBuffer.size >= maxActiveFrames) {
                val oldest = frameBuffer.entries.minByOrNull { it.value.lastUpdateTime }?.key
                if (oldest != null) frameBuffer.remove(oldest)
            }

            val frameData = frameBuffer.compute(frameId) { _, existing ->
                if (existing != null && existing.totalChunks == totalChunks) existing
                else FrameData(totalChunks, pts)
            } ?: return

            frameData.lastUpdateTime = System.currentTimeMillis()
            if (frameData.chunks[chunkIndex] == null) {
                if (frameData.receivedBytes + payload.size > maxFrameSize) {
                    frameBuffer.remove(frameId, frameData)
                    return
                }
                frameData.chunks[chunkIndex] = payload
                frameData.receivedChunks++
                frameData.receivedBytes += payload.size
            }

            if (frameData.receivedChunks == frameData.totalChunks) {
                frameBuffer.remove(frameId, frameData)
                val totalSize = frameData.receivedBytes
                if (totalSize !in 1..maxFrameSize) return
                val completeFrame = ByteArray(totalSize)
                var offset = 0
                for (chunk in frameData.chunks) {
                    if (chunk != null) {
                        System.arraycopy(chunk, 0, completeFrame, offset, chunk.size)
                        offset += chunk.size
                    }
                }
                if (type == 1.toByte()) onVideoFrameReceived?.invoke(completeFrame, frameData.pts)
                else onAudioFrameReceived?.invoke(completeFrame, frameData.pts)
            }
        } catch (e: Exception) {
            Log.w(tag, "Error handling frame chunk: " + e.message)
        }
    }

    fun stop() {
        isRunning = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        allowedSenderIp = null
        frameBuffer.clear()
    }
}
