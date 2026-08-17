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
    @Volatile private var isRunning = false
    @Volatile private var isConfigured = false
    private var outputThread: Thread? = null

    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val TAG = "AudioDecoder"

    fun start() {
        if (isRunning) stop()

        try {
            // AAC-LC / 44100Hz / Stereo AudioSpecificConfig = 0x11 0x90
            val csd0 = ByteArray(2)
            csd0[0] = 0x11
            csd0[1] = 0x90.toByte()

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd0))
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            
            mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            mediaCodec?.configure(format, null, null, 0)
            mediaCodec?.start()

            val calculatedMinBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = if (calculatedMinBuffer > 0) calculatedMinBuffer * 2 else 16384

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
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack?.play()
            } else {
                Log.w(TAG, "AudioTrack failed to initialize (state=${audioTrack?.state})")
            }

            isRunning = true
            isConfigured = true
            Log.d(TAG, "AudioDecoder started successfully")

            outputThread = Thread({
                val bufferInfo = MediaCodec.BufferInfo()
                while (isRunning) {
                    try {
                        val codec = mediaCodec ?: break
                        val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                        when {
                            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                Log.d(TAG, "AudioDecoder output format changed")
                            }
                            outIndex >= 0 -> {
                                val outputBuffer = codec.getOutputBuffer(outIndex)
                                if (outputBuffer != null && bufferInfo.size > 0) {
                                    outputBuffer.position(bufferInfo.offset)
                                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                                    val pcmData = ByteArray(bufferInfo.size)
                                    outputBuffer.get(pcmData)

                                    val track = audioTrack
                                    if (isRunning && track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                        track.write(pcmData, 0, pcmData.size)
                                    }
                                }
                                codec.releaseOutputBuffer(outIndex, false)
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) Log.w(TAG, "Audio output exception: ${e.message}")
                    } catch (t: Throwable) {
                        break
                    }
                }
            }, "AudioDecoder-OutputThread").apply { start() }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioDecoder: ${e.message}", e)
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
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        isRunning = false
        isConfigured = false
        try {
            outputThread?.interrupt()
            outputThread = null
        } catch (_: Exception) {}

        try {
            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack?.stop()
            }
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
    }
}
