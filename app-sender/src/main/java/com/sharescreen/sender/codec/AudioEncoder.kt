package com.sharescreen.sender.codec

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

class AudioEncoder {
    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var inputThread: Thread? = null
    private var outputThread: Thread? = null

    @Volatile private var isRunning = false

    private val sampleRate = 44_100
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    var onEncodedFrame: ((ByteArray, Long) -> Unit)? = null

    companion object {
        private const val TAG = "AudioEncoder"
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(mediaProjection: MediaProjection) {
        stop()
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            check(minBufferSize > 0) { "AudioRecord 不支持当前采样率或声道配置" }

            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()

            val recorder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBufferSize * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            audioRecord = recorder
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }

            setupEncoder()
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord 无法开始录音" }

            isRunning = true
            inputThread = Thread({
                val activeRecorder = audioRecord ?: return@Thread
                val pcmBuffer = ByteArray(minBufferSize)
                while (isRunning) {
                    try {
                        val readResult = activeRecorder.read(pcmBuffer, 0, pcmBuffer.size)
                        when {
                            readResult > 0 -> encode(pcmBuffer, readResult)
                            readResult == AudioRecord.ERROR_DEAD_OBJECT -> {
                                Log.w(TAG, "AudioRecord became unavailable")
                                isRunning = false
                                break
                            }
                            readResult < 0 -> Thread.sleep(10)
                        }
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        if (isRunning) Log.w(TAG, "Audio capture input error: " + e.message)
                        isRunning = false
                        break
                    }
                }
            }, "AudioEncoder-InputThread").apply { start() }

            outputThread = Thread({
                val activeCodec = mediaCodec ?: return@Thread
                val bufferInfo = MediaCodec.BufferInfo()
                while (isRunning) {
                    try {
                        val outputIndex = activeCodec.dequeueOutputBuffer(bufferInfo, 10_000)
                        if (outputIndex >= 0) {
                            try {
                                val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                val outputBuffer = activeCodec.getOutputBuffer(outputIndex)
                                if (isRunning && !isCodecConfig && outputBuffer != null && bufferInfo.size > 0) {
                                    outputBuffer.position(bufferInfo.offset)
                                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                                    val data = ByteArray(bufferInfo.size)
                                    outputBuffer.get(data)
                                    // Receiver is configured with AudioSpecificConfig (csd-0):
                                    // only raw AAC access units belong on the audio transport.
                                    onEncodedFrame?.invoke(data, bufferInfo.presentationTimeUs)
                                }
                            } finally {
                                try { activeCodec.releaseOutputBuffer(outputIndex, false) } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.w(TAG, "Audio encoder output error: " + e.message)
                            isRunning = false
                        } else {
                            break
                        }
                    }
                }
            }, "AudioEncoder-OutputThread").apply { start() }
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    private fun setupEncoder() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        mediaCodec = encoder
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
    }

    private fun encode(data: ByteArray, length: Int) {
        val codec = mediaCodec ?: return
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)
            if (inputBuffer != null) {
                inputBuffer.clear()
                if (length <= inputBuffer.capacity()) {
                    inputBuffer.put(data, 0, length)
                    codec.queueInputBuffer(inputIndex, 0, length, System.nanoTime() / 1000, 0)
                } else {
                    codec.queueInputBuffer(inputIndex, 0, 0, System.nanoTime() / 1000, 0)
                    Log.w(TAG, "PCM block exceeds codec input buffer capacity")
                }
            } else {
                codec.queueInputBuffer(inputIndex, 0, 0, System.nanoTime() / 1000, 0)
            }
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false

        val recorder = audioRecord
        try {
            if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } catch (_: Exception) {}

        joinWorker(inputThread)
        inputThread = null
        joinWorker(outputThread)
        outputThread = null

        try { recorder?.release() } catch (_: Exception) {}
        audioRecord = null

        val codec = mediaCodec
        mediaCodec = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
    }

    private fun joinWorker(worker: Thread?) {
        if (worker == null || worker === Thread.currentThread()) return
        worker.interrupt()
        try {
            worker.join(1_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
