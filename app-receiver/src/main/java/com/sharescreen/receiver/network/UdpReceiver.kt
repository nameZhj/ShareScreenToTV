package com.sharescreen.receiver.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class UdpReceiver(private val port: Int) {
    private var socket: DatagramSocket? = null
    @Volatile private var isRunning = false
    private val TAG = "UdpReceiver"
    
    var onVideoFrameReceived: ((ByteArray, Long) -> Unit)? = null
    var onAudioFrameReceived: ((ByteArray, Long) -> Unit)? = null

    // FrameId -> FrameData
    private val frameBuffer = ConcurrentHashMap<Int, FrameData>()
    
    private class FrameData(val totalChunks: Int, val pts: Long) {
        val chunks = arrayOfNulls<ByteArray>(totalChunks)
        var receivedChunks = 0
        var lastUpdateTime = System.currentTimeMillis()
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        thread(name = "UdpReceiver-$port") {
            try {
                socket = DatagramSocket(java.net.InetSocketAddress("0.0.0.0", port))
                socket?.receiveBufferSize = 2 * 1024 * 1024 // 2MB
                val receiveData = ByteArray(65535)
                while (isRunning) {
                    try {
                        val packet = DatagramPacket(receiveData, receiveData.size)
                        val sock = socket ?: break
                        sock.receive(packet)
                        
                        if (packet.length < 17) continue
                        
                        val buffer = ByteBuffer.wrap(packet.data, 0, packet.length)
                        val type = buffer.get()
                        val frameId = buffer.getInt()
                        val totalChunks = buffer.short.toInt()
                        val chunkIndex = buffer.short.toInt()
                        val pts = buffer.getLong()
                        
                        if (totalChunks <= 0 || totalChunks > 4000 || chunkIndex < 0 || chunkIndex >= totalChunks) {
                            continue
                        }
                        
                        val payloadSize = packet.length - 17
                        if (payloadSize <= 0) continue
                        
                        val payload = ByteArray(payloadSize)
                        buffer.get(payload)
                        
                        handleChunk(type, frameId, totalChunks, chunkIndex, pts, payload)
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "UDP socket error on port $port: ${e.message}")
                }
            }
        }
        
        // Cleanup thread for incomplete frames
        thread(name = "UdpReceiver-Cleanup-$port") {
            while (isRunning) {
                try {
                    Thread.sleep(100)
                    val now = System.currentTimeMillis()
                    val iterator = frameBuffer.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if (now - entry.value.lastUpdateTime > 200) {
                            iterator.remove()
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }
    
    private fun handleChunk(type: Byte, frameId: Int, totalChunks: Int, chunkIndex: Int, pts: Long, payload: ByteArray) {
        try {
            val frameData = frameBuffer.compute(frameId) { _, existing ->
                if (existing != null && existing.totalChunks == totalChunks) {
                    existing
                } else {
                    FrameData(totalChunks, pts)
                }
            } ?: return

            frameData.lastUpdateTime = System.currentTimeMillis()
            
            if (chunkIndex in frameData.chunks.indices && frameData.chunks[chunkIndex] == null) {
                frameData.chunks[chunkIndex] = payload
                frameData.receivedChunks++
            }
            
            if (frameData.receivedChunks == frameData.totalChunks) {
                frameBuffer.remove(frameId)
                val totalSize = frameData.chunks.sumOf { it?.size ?: 0 }
                if (totalSize > 0) {
                    val completeFrame = ByteArray(totalSize)
                    var offset = 0
                    for (chunk in frameData.chunks) {
                        if (chunk != null) {
                            System.arraycopy(chunk, 0, completeFrame, offset, chunk.size)
                            offset += chunk.size
                        }
                    }
                    
                    if (type == 1.toByte()) {
                        onVideoFrameReceived?.invoke(completeFrame, pts)
                    } else if (type == 2.toByte()) {
                        onAudioFrameReceived?.invoke(completeFrame, pts)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling chunk: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        frameBuffer.clear()
    }
}
