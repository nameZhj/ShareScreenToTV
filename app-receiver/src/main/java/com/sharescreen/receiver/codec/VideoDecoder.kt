package com.sharescreen.receiver.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

class VideoDecoder(private val surface: Surface) {
    private var mediaCodec: MediaCodec? = null
    private var isRunning = false
    private var isConfigured = false

    var onVideoSizeChanged: ((Int, Int) -> Unit)? = null

    fun start(width: Int, height: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec?.configure(format, surface, null, 0)
        mediaCodec?.start()
        isRunning = true
        isConfigured = true
        
        // Notify initial size
        if (width > 0 && height > 0) {
            onVideoSizeChanged?.invoke(width, height)
        }

        // Thread to just discard output buffers (since it's rendered to Surface automatically)
        Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isRunning) {
                try {
                    val outIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = mediaCodec?.outputFormat
                            if (newFormat != null) {
                                val w = if (newFormat.containsKey(MediaFormat.KEY_WIDTH)) newFormat.getInteger(MediaFormat.KEY_WIDTH) else 0
                                val h = if (newFormat.containsKey(MediaFormat.KEY_HEIGHT)) newFormat.getInteger(MediaFormat.KEY_HEIGHT) else 0
                                if (w > 0 && h > 0) {
                                    onVideoSizeChanged?.invoke(w, h)
                                }
                            }
                        }
                        outIndex >= 0 -> {
                            // true = render to surface
                            mediaCodec?.releaseOutputBuffer(outIndex, true)
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) e.printStackTrace()
                }
            }
        }.start()
    }
    
    fun queueInputBuffer(data: ByteArray, pts: Long) {
        if (!isConfigured || !isRunning) return
        try {
            val inputIndex = mediaCodec?.dequeueInputBuffer(10000) ?: -1
            if (inputIndex >= 0) {
                val inputBuffer = mediaCodec?.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(data)
                    mediaCodec?.queueInputBuffer(inputIndex, 0, data.size, pts, 0)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        isRunning = false
        isConfigured = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
        }
        mediaCodec = null
    }
}
