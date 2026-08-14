package com.sharescreen.sender.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

class VideoEncoder {
    private var mediaCodec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set
    private var isRunning = false
    
    var onEncodedFrame: ((ByteArray, Long) -> Unit)? = null

    fun start(width: Int, height: Int, frameRate: Int = 30) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 4000000) // 4 Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Keyframe every 1 second

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = mediaCodec?.createInputSurface()
        
        mediaCodec?.start()
        isRunning = true

        Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isRunning) {
                try {
                    val outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.get(data)
                            
                            onEncodedFrame?.invoke(data, bufferInfo.presentationTimeUs)
                        }
                        mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                    }
                } catch (e: Exception) {
                    if (isRunning) e.printStackTrace()
                }
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
        }
        mediaCodec = null
        inputSurface = null
    }
}
