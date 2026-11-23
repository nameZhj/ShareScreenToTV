package com.sharescreen.sender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sharescreen.sender.codec.AudioEncoder
import com.sharescreen.sender.codec.VideoEncoder
import com.sharescreen.sender.network.NetworkBinder
import com.sharescreen.sender.network.UdpSender

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var videoUdpSender: UdpSender? = null
    private var audioUdpSender: UdpSender? = null

    @Volatile private var isStopping = false

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_IP = "EXTRA_IP"
        const val EXTRA_WIDTH = "EXTRA_WIDTH"
        const val EXTRA_HEIGHT = "EXTRA_HEIGHT"
        private const val TAG = "ScreenCaptureService"
    }

    override fun onCreate() {
        super.onCreate()
        NetworkBinder.start(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Ignore duplicate start intents. A MediaProjection grant is single-use on
                // newer Android releases and must not be accidentally initialized twice.
                if (mediaProjection != null) return START_NOT_STICKY
                isStopping = false
                startForeground()
                try {
                    startCapture(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to start screen capture: " + e.message, e)
                    stopCapture()
                }
            }
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startForeground() {
        val channelId = "screen_capture_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Screen Capture", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screen Sharing Active")
            .setContentText("Sharing screen to TV...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        startForeground(1, notification)
    }

    private fun startCapture(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val projectionData = resultData ?: throw IllegalArgumentException("缺少屏幕录制授权数据")
        val ip = intent.getStringExtra(EXTRA_IP) ?: throw IllegalArgumentException("缺少电视 IP")
        val tvWidth = intent.getIntExtra(EXTRA_WIDTH, 1920).coerceIn(1, 16384)
        val tvHeight = intent.getIntExtra(EXTRA_HEIGHT, 1080).coerceIn(1, 16384)

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, projectionData)
            ?: throw IllegalStateException("无法创建 MediaProjection")
        mediaProjection = projection

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture(stopProjection = false)
            }
        }, Handler(Looper.getMainLooper()))

        videoUdpSender = UdpSender(ip, 20001)
        audioUdpSender = UdpSender(ip, 20002)

        val video = VideoEncoder()
        videoEncoder = video
        video.onEncodedFrame = { data, pts -> videoUdpSender?.sendData(1, data, pts) }
        video.start(tvWidth, tvHeight)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val audio = AudioEncoder()
                audioEncoder = audio
                audio.onEncodedFrame = { data, pts -> audioUdpSender?.sendData(2, data, pts) }
                audio.start(projection)
            } catch (e: Exception) {
                Log.w(TAG, "Audio capture unavailable; continuing with video only: " + e.message)
                audioEncoder?.stop()
                audioEncoder = null
            }
        } else {
            Log.i(TAG, "Audio capture permission unavailable; continuing with video only")
        }

        val surface = videoEncoder?.inputSurface
            ?: throw IllegalStateException("视频编码器未创建输入 Surface")
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenCapture",
            tvWidth,
            tvHeight,
            300,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            Handler(Looper.getMainLooper())
        ) ?: throw IllegalStateException("无法创建虚拟显示器")
    }

    @Synchronized
    private fun stopCapture(stopProjection: Boolean = true) {
        if (isStopping) return
        isStopping = true

        val projection = mediaProjection
        mediaProjection = null

        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null

        try { videoEncoder?.stop() } catch (e: Exception) { Log.w(TAG, "Video encoder stop failed: " + e.message) }
        videoEncoder = null
        try { audioEncoder?.stop() } catch (e: Exception) { Log.w(TAG, "Audio encoder stop failed: " + e.message) }
        audioEncoder = null

        try { videoUdpSender?.close() } catch (_: Exception) {}
        videoUdpSender = null
        try { audioUdpSender?.close() } catch (_: Exception) {}
        audioUdpSender = null

        if (stopProjection) {
            try { projection?.stop() } catch (_: Exception) {}
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
