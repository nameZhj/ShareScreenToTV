package com.sharescreen.receiver

import android.net.Uri
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.VideoView
import android.widget.MediaController
import androidx.appcompat.app.AppCompatActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bumptech.glide.Glide
import java.io.File

class MediaPlaybackActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_FILE_PATH = "file_path"
        const val ACTION_STOP_MEDIA = "com.sharescreen.ACTION_STOP_MEDIA"
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
            videoView.start()
        } else {
            val imageView = ImageView(this)
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            setContentView(imageView)
            
            Glide.with(this)
                .load(file)
                .into(imageView)
        }
        
        // Register receiver for both Local and Global broadcasts depending on how it's sent
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, IntentFilter(ACTION_STOP_MEDIA), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stopReceiver, IntentFilter(ACTION_STOP_MEDIA))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(stopReceiver) } catch (e: Exception) {}
    }
}
