package com.sharescreen.receiver.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

class VideoDecoder(private val surface: Surface) {
    private var mediaCodec: MediaCodec? = null
    @Volatile private var isRunning = false
    @Volatile private var isConfigured = false
    private var outputThread: Thread? = null

    private val TAG = "VideoDecoder"
    var onVideoSizeChanged: ((Int, Int) -> Unit)? = null

    fun start(width: Int, height: Int) {
        if (isRunning) stop()
        
        try {
            val safeWidth = if (width > 0) width else 1920
            val safeHeight = if (height > 0) height else 1080
            
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, safeWidth, safeHeight)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024) // 1MB buffer for 1080p/4K I-frames
            
            mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, surface, null, 0)
            mediaCodec?.start()
            
            isRunning = true
            isConfigured = true
            Log.d(TAG, "VideoDecoder started successfully (${safeWidth}x${safeHeight})")

            outputThread = Thread({
                val bufferInfo = MediaCodec.BufferInfo()
                while (isRunning) {
                    try {
                        val codec = mediaCodec ?: break
                        val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                        when {
                            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val newFormat = codec.outputFormat
                                val w = if (newFormat.containsKey(MediaFormat.KEY_WIDTH)) newFormat.getInteger(MediaFormat.KEY_WIDTH) else 0
                                val h = if (newFormat.containsKey(MediaFormat.KEY_HEIGHT)) newFormat.getInteger(MediaFormat.KEY_HEIGHT) else 0
                                if (w > 0 && h > 0) {
                                    onVideoSizeChanged?.invoke(w, h)
                                }
                            }
                            outIndex >= 0 -> {
                                if (isRunning && surface.isValid) {
                                    codec.releaseOutputBuffer(outIndex, true)
                                } else {
                                    codec.releaseOutputBuffer(outIndex, false)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.w(TAG, "Output loop exception: ${e.message}")
                        }
                    } catch (t: Throwable) {
                        break
                    }
                }
            }, "VideoDecoder-OutputThread").apply { start() }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VideoDecoder: ${e.message}", e)
            stop()
        }
    }
    
    fun queueInputBuffer(data: ByteArray, pts: Long) {
        if (!isConfigured || !isRunning) return
        try {
            val codec = mediaCodec ?: return
            val inputIndex = codec.dequeueInputBuffer(5000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    if (data.size <= inputBuffer.capacity()) {
                        inputBuffer.put(data)
                        codec.queueInputBuffer(inputIndex, 0, data.size, pts, 0)
                    } else {
                        Log.w(TAG, "Frame size ${data.size} exceeds buffer capacity ${inputBuffer.capacity()}")
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore non-fatal codec exceptions during streaming
        }
    }

    fun stop() {
        isRunning = false
        isConfigured = false
        try {
            outputThread?.interrupt()
            outputThread = null
        } catch (_: Exception) {}

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
    }
}
