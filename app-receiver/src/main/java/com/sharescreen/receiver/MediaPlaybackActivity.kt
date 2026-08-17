package com.sharescreen.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bumptech.glide.Glide
import java.io.File
import java.lang.ref.WeakReference

class MediaPlaybackActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_FILE_PATH = "file_path"
        const val ACTION_STOP_MEDIA = "com.sharescreen.ACTION_STOP_MEDIA"
        private val TAG = "MediaPlaybackActivity"
        
        @Volatile private var currentInstance: WeakReference<MediaPlaybackActivity>? = null

        fun stopCurrentPlayback() {
            try {
                currentInstance?.get()?.finish()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping playback instance: ${e.message}")
            }
        }
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received ACTION_STOP_MEDIA broadcast, finishing MediaPlaybackActivity")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentInstance = WeakReference(this)
        
        // Fullscreen
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        if (filePath == null) {
            finish()
            return
        }

        val file = File(filePath)
        if (!file.exists()) {
            finish()
            return
        }

        if (filePath.endsWith(".mp4", ignoreCase = true) || filePath.endsWith(".mkv", ignoreCase = true)) {
            val videoView = VideoView(this)
            setContentView(videoView)
            
            val mediaController = MediaController(this)
            mediaController.setAnchorView(videoView)
            videoView.setMediaController(mediaController)
            videoView.setVideoURI(Uri.fromFile(file))
            videoView.setOnCompletionListener {
                finish()
            }
            videoView.start()
        } else {
            val imageView = ImageView(this)
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            setContentView(imageView)
            
            Glide.with(this)
                .load(file)
                .into(imageView)
        }
        
        // Register LocalBroadcastManager for intra-app communication
        LocalBroadcastManager.getInstance(this).registerReceiver(stopReceiver, IntentFilter(ACTION_STOP_MEDIA))

        // Register Global broadcast receiver with explicit flag
        try {
            val filter = IntentFilter(ACTION_STOP_MEDIA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stopReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(stopReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error registering global broadcast: ${e.message}")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (currentInstance?.get() == this) {
            currentInstance = null
        }
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
    }
}
