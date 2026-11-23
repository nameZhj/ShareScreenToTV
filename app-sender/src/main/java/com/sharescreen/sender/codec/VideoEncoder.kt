package com.sharescreen.sender.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

class VideoEncoder {
    private var mediaCodec: MediaCodec? = null
    private var outputThread: Thread? = null

    @Volatile private var isRunning = false

    var inputSurface: Surface? = null
        private set

    var onEncodedFrame: ((ByteArray, Long) -> Unit)? = null

    companion object {
        private const val TAG = "VideoEncoder"
    }

    @Synchronized
    fun start(width: Int, height: Int, frameRate: Int = 30) {
        stop()
        require(width > 0 && height > 0) { "视频分辨率无效" }
        require(frameRate in 1..120) { "视频帧率无效" }

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        )
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var surface: Surface? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()

            mediaCodec = codec
            inputSurface = surface
            isRunning = true

            outputThread = Thread({
                val bufferInfo = MediaCodec.BufferInfo()
                while (isRunning) {
                    try {
                        val activeCodec = mediaCodec ?: break
                        val outputBufferIndex = activeCodec.dequeueOutputBuffer(bufferInfo, 10_000)
                        if (outputBufferIndex >= 0) {
                            try {
                                val outputBuffer = activeCodec.getOutputBuffer(outputBufferIndex)
                                if (outputBuffer != null && bufferInfo.size > 0) {
                                    outputBuffer.position(bufferInfo.offset)
                                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                                    val data = ByteArray(bufferInfo.size)
                                    outputBuffer.get(data)
                                    onEncodedFrame?.invoke(data, bufferInfo.presentationTimeUs)
                                }
                            } finally {
                                try { activeCodec.releaseOutputBuffer(outputBufferIndex, false) } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) Log.w(TAG, "Video encoder output error: " + e.message)
                        else break
                    }
                }
            }, "VideoEncoder-OutputThread").apply { start() }
        } catch (e: Exception) {
            isRunning = false
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { surface?.release() } catch (_: Exception) {}
            mediaCodec = null
            inputSurface = null
            throw e
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false

        val worker = outputThread
        outputThread = null
        if (worker != null && worker !== Thread.currentThread()) {
            worker.interrupt()
            try {
                worker.join(1_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        val codec = mediaCodec
        mediaCodec = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}

        val surface = inputSurface
        inputSurface = null
        try { surface?.release() } catch (_: Exception) {}
    }
}
