package com.sharescreen.receiver.codec

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat

import android.util.Log

class AudioDecoder {
    private var mediaCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var isRunning = false
    private var isConfigured = false

    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private val TAG = "AudioDecoder"

    fun start() {
        // AAC-LC / 44100Hz / Stereo AudioSpecificConfig = 0x11 0x90
        // Profile(5bit)=2(LC) | SampleRate(4bit)=4(44100) | Channels(4bit)=2(stereo) | framelen(1bit)=0 | dependsOnCoreCoder(1bit)=0 | extensionFlag(1bit)=0
        val csd0 = ByteArray(2)
        csd0[0] = 0x11  // 0001 0001  -> profile(2<<3) | sampleRateIdx(4>>1)
        csd0[1] = 0x90.toByte()  // 1001 0000  -> sampleRateIdx(4&1)<<7 | channels(2)<<3

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2)
        format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd0))
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        mediaCodec?.configure(format, null, null, 0)
        mediaCodec?.start()
        
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
            
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()
            
        audioTrack = AudioTrack(
            audioAttributes,
            audioFormat,
            minBufferSize * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.play()

        isRunning = true
        isConfigured = true

        Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isRunning) {
                try {
                    val outIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Log.d(TAG, "AudioDecoder output format changed")
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                            // Deprecated on API 21+ but handle anyway
                        }
                        outIndex >= 0 -> {
                            val outputBuffer = mediaCodec?.getOutputBuffer(outIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                                val pcmData = ByteArray(bufferInfo.size)
                                outputBuffer.get(pcmData)

                                audioTrack?.write(pcmData, 0, pcmData.size)
                            }
                            mediaCodec?.releaseOutputBuffer(outIndex, false)
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
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
        
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {}
        mediaCodec = null
    }
}
