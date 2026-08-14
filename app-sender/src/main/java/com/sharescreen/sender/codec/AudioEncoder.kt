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

        setupEncoder()

        audioRecord?.startRecording()
        isRunning = true

        Thread {
            val pcmBuffer = ByteArray(minBufferSize)
            while (isRunning) {
                val readResult = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1
                if (readResult > 0) {
                    encode(pcmBuffer, readResult)
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
                            
                            val packetLen = bufferInfo.size + 7
                            val packet = ByteArray(packetLen)
                            addAdtsHeader(packet, packetLen)
                            System.arraycopy(data, 0, packet, 7, data.size)
                            
                            onEncodedFrame?.invoke(packet, bufferInfo.presentationTimeUs)
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

    private fun addAdtsHeader(packet: ByteArray, packetLen: Int) {
        val profile = 2 // AAC LC
        val freqIdx = 4 // 44.1KHz
        val chanCfg = 2 // CPE (Stereo)
        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte()
        packet[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        packet[3] = (((chanCfg and 3) shl 6) + (packetLen shr 11)).toByte()
        packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
        packet[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
        packet[6] = 0xFC.toByte()
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
