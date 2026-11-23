package com.sharescreen.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.MediaController
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
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
                currentInstance?.get()?.let { activity ->
                    activity.runOnUiThread {
                        if (!activity.isFinishing) activity.finish()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping playback instance: ${e.message}")
            }
        }
    }

    private var mediaPlayer: MediaPlayer? = null

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

        val extension = file.extension.lowercase()
        if (extension in setOf("mp4", "mkv", "avi", "mov", "flv", "webm", "3gp", "wmv", "ts")) {
            val videoView = VideoView(this)
            setContentView(videoView)

            val mediaController = MediaController(this)
            mediaController.setAnchorView(videoView)
            videoView.setMediaController(mediaController)
            videoView.setVideoURI(Uri.fromFile(file))
            videoView.setOnCompletionListener { finish() }
            videoView.start()
        } else if (extension in setOf("mp3", "wav", "aac", "flac", "ogg", "m4a")) {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(32, 32, 32, 32)
            }
            container.addView(TextView(this).apply {
                text = file.name
                textSize = 20f
                gravity = android.view.Gravity.CENTER
            })
            val playButton = Button(this).apply { text = "正在准备音频..." }
            container.addView(playButton)
            setContentView(container)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { player ->
                    playButton.text = "暂停"
                    playButton.isEnabled = true
                    player.start()
                }
                setOnCompletionListener { finish() }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Audio playback error: " + what + "/" + extra)
                    finish()
                    true
                }
                prepareAsync()
            }
            playButton.isEnabled = false
            playButton.setOnClickListener {
                val player = mediaPlayer ?: return@setOnClickListener
                if (player.isPlaying) {
                    player.pause()
                    playButton.text = "播放"
                } else {
                    player.start()
                    playButton.text = "暂停"
                }
            }
        } else {
            val imageView = ImageView(this)
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            setContentView(imageView)

            Glide.with(this)
                .load(file)
                .into(imageView)
        }
        
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
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }
}
