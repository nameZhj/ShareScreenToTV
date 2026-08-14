package com.sharescreen.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sharescreen.sender.codec.AudioEncoder
import com.sharescreen.sender.codec.VideoEncoder
import com.sharescreen.sender.network.UdpSender

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var videoUdpSender: UdpSender? = null
    private var audioUdpSender: UdpSender? = null
    
    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_IP = "EXTRA_IP"
        const val EXTRA_WIDTH = "EXTRA_WIDTH"
        const val EXTRA_HEIGHT = "EXTRA_HEIGHT"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground()
                startCapture(intent)
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
        val resultData: Intent? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val ip = intent.getStringExtra(EXTRA_IP) ?: return
        val tvWidth = intent.getIntExtra(EXTRA_WIDTH, 1920)
        val tvHeight = intent.getIntExtra(EXTRA_HEIGHT, 1080)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData!!)
        
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
            }
        }, null)

        videoUdpSender = UdpSender(ip, 20001) // Video port
        audioUdpSender = UdpSender(ip, 20002) // Audio port
        
        videoEncoder = VideoEncoder()
        audioEncoder = AudioEncoder()
        
        videoEncoder?.onEncodedFrame = { data, pts ->
            videoUdpSender?.sendData(1, data, pts)
        }
        
        audioEncoder?.onEncodedFrame = { data, pts ->
            audioUdpSender?.sendData(2, data, pts)
        }
        
        videoEncoder?.start(tvWidth, tvHeight)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioEncoder?.start(mediaProjection!!)
        }
        
        val surface = videoEncoder?.inputSurface
        if (surface != null) {
            mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                tvWidth, tvHeight, 300,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )
        }
    }

    private fun stopCapture() {
        videoEncoder?.stop()
        videoEncoder = null
        audioEncoder?.stop()
        audioEncoder = null
        videoUdpSender?.close()
        videoUdpSender = null
        audioUdpSender?.close()
        audioUdpSender = null
        mediaProjection?.stop()
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
