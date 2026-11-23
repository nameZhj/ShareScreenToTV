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
import androidx.annotation.RequiresApi

class AudioEncoder {
    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var isRunning = false

    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    
    var onEncodedFrame: ((ByteArray, Long) -> Unit)? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    fun start(mediaProjection: MediaProjection) {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        check(minBufferSize > 0) { "AudioRecord 不支持当前采样率或声道配置" }
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
            
        val format = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBufferSize * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(audioRecord?.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }

        setupEncoder()

        audioRecord?.startRecording()
        check(audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord 无法开始录音" }
        isRunning = true

        Thread {
            val pcmBuffer = ByteArray(minBufferSize)
            while (isRunning) {
                val readResult = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1
                if (readResult > 0) {
                    encode(pcmBuffer, readResult)
                } else if (readResult < 0) {
                    if (readResult == AudioRecord.ERROR_DEAD_OBJECT) break
                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                }
            }
        }.start()
        
        Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isRunning) {
                try {
                    val outIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                    if (outIndex >= 0) {
                        val outputBuffer = mediaCodec?.getOutputBuffer(outIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.get(data)
                            
                            // The receiver configures AAC with AudioSpecificConfig (csd-0),
                            // so transport raw AAC access units, not ADTS-framed packets.
                            onEncodedFrame?.invoke(data, bufferInfo.presentationTimeUs)
                        }
                        mediaCodec?.releaseOutputBuffer(outIndex, false)
                    }
                } catch (e: Exception) {
                    if (isRunning) e.printStackTrace()
                }
            }
        }.start()
    }

    private fun setupEncoder() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec?.start()
    }

    private fun encode(data: ByteArray, length: Int) {
        val inputIndex = mediaCodec?.dequeueInputBuffer(10000) ?: -1
        if (inputIndex >= 0) {
            val inputBuffer = mediaCodec?.getInputBuffer(inputIndex)
            inputBuffer?.clear()
            inputBuffer?.put(data, 0, length)
            val pts = System.nanoTime() / 1000
            mediaCodec?.queueInputBuffer(inputIndex, 0, length, pts, 0)
        }
    }

    fun stop() {
        isRunning = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
        
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {}
        mediaCodec = null
    }
}
