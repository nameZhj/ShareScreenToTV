package com.sharescreen.receiver.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class UdpReceiver(private val port: Int) {
    private var socket: DatagramSocket? = null
    private var isRunning = false
    private val TAG = "UdpReceiver"
    
    var onVideoFrameReceived: ((ByteArray, Long) -> Unit)? = null
    var onAudioFrameReceived: ((ByteArray, Long) -> Unit)? = null

    // FrameId -> Pair<TotalChunks, Array<ByteArray?>>
    private val frameBuffer = ConcurrentHashMap<Int, FrameData>()
    
    private class FrameData(val totalChunks: Int, val pts: Long) {
        val chunks = arrayOfNulls<ByteArray>(totalChunks)
        var receivedChunks = 0
        var lastUpdateTime = System.currentTimeMillis()
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        thread {
            try {
                socket = DatagramSocket(java.net.InetSocketAddress("0.0.0.0", port))
                socket?.receiveBufferSize = 2 * 1024 * 1024 // 2MB
                val receiveData = ByteArray(2048)
                while (isRunning) {
                    val packet = DatagramPacket(receiveData, receiveData.size)
                    socket?.receive(packet)
                    Log.d(TAG, "Received UDP packet on port $port, length: ${packet.length}")
                    
                    val buffer = ByteBuffer.wrap(packet.data, 0, packet.length)
                    val type = buffer.get()
                    val frameId = buffer.getInt()
                    val totalChunks = buffer.short.toInt()
                    val chunkIndex = buffer.short.toInt()
                    val pts = buffer.getLong()
                    
                    val payloadSize = packet.length - 17
                    val payload = ByteArray(payloadSize)
                    buffer.get(payload)
                    
                    handleChunk(type, frameId, totalChunks, chunkIndex, pts, payload)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "UDP receive error: ${e.message}")
                }
            }
        }
        
        // Cleanup thread for incomplete frames
        thread {
            while (isRunning) {
                Thread.sleep(100)
                val now = System.currentTimeMillis()
                val iterator = frameBuffer.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (now - entry.value.lastUpdateTime > 200) {
                        // Frame incomplete for 200ms, discard
                        iterator.remove()
                    }
                }
            }
        }
    }
    
    private fun handleChunk(type: Byte, frameId: Int, totalChunks: Int, chunkIndex: Int, pts: Long, payload: ByteArray) {
        val frameData = frameBuffer.getOrPut(frameId) { FrameData(totalChunks, pts) }
        frameData.lastUpdateTime = System.currentTimeMillis()
        
        if (frameData.chunks[chunkIndex] == null) {
            frameData.chunks[chunkIndex] = payload
            frameData.receivedChunks++
        }
        
        if (frameData.receivedChunks == frameData.totalChunks) {
            // Frame complete
            val totalSize = frameData.chunks.sumOf { it?.size ?: 0 }
            val completeFrame = ByteArray(totalSize)
            var offset = 0
            for (chunk in frameData.chunks) {
                if (chunk != null) {
                    System.arraycopy(chunk, 0, completeFrame, offset, chunk.size)
                    offset += chunk.size
                }
            }
            frameBuffer.remove(frameId)
            
            if (type == 1.toByte()) {
                onVideoFrameReceived?.invoke(completeFrame, pts)
            } else if (type == 2.toByte()) {
                onAudioFrameReceived?.invoke(completeFrame, pts)
            }
        }
    }

    fun stop() {
        isRunning = false
        socket?.close()
        socket = null
        frameBuffer.clear()
    }
}
