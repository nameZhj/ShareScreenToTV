package com.sharescreen.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sharescreen.sender.network.FileSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class FileCastService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val fileSender = FileSender()
    private val cancelRequested = AtomicBoolean(false)

    private var transferRunning = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentFileName = "媒体文件"
    private var lastProgress = -1

    companion object {
        const val ACTION_START = "com.sharescreen.sender.action.START_FILE_CAST"
        const val ACTION_CANCEL = "com.sharescreen.sender.action.CANCEL_FILE_CAST"

        const val EXTRA_IP = "extra_tv_ip"
        const val EXTRA_TOKEN = "extra_pairing_token"
        const val EXTRA_URI = "extra_media_uri"

        const val ACTION_STATE_CHANGED = "com.sharescreen.sender.action.FILE_CAST_STATE_CHANGED"
        const val STATE_IDLE = "idle"
        const val STATE_RUNNING = "running"
        const val STATE_COMPLETED = "completed"
        const val STATE_FAILED = "failed"
        const val STATE_CANCELLED = "cancelled"

        const val PREFS_NAME = "file_cast_state"
        const val PREF_STATE = "state"
        const val PREF_PROGRESS = "progress"
        const val PREF_FILE_NAME = "file_name"
        const val PREF_UPDATED_AT = "updated_at"

        private const val CHANNEL_ID = "file_cast_channel"
        private const val NOTIFICATION_ID = 2004
        private const val TAG = "FileCastService"

        fun cancel(context: Context) {
            context.startService(
                Intent(context, FileCastService::class.java).setAction(ACTION_CANCEL)
            )
        }

        fun clearCompletedState(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_STATE, STATE_IDLE)
                .putInt(PREF_PROGRESS, 0)
                .putLong(PREF_UPDATED_AT, System.currentTimeMillis())
                .apply()
            context.sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(context.packageName).apply {
                putExtra(PREF_STATE, STATE_IDLE)
                putExtra(PREF_PROGRESS, 0)
            })
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                if (transferRunning) {
                    cancelRequested.set(true)
                    fileSender.cancelTransfer()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            ACTION_START -> {
                if (transferRunning) return START_NOT_STICKY
                cancelRequested.set(false)
                startAsForeground()
                startTransfer(intent)
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = buildProgressNotification(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startTransfer(intent: Intent) {
        val ip = intent.getStringExtra(EXTRA_IP)
        val token = intent.getStringExtra(EXTRA_TOKEN)
        @Suppress("DEPRECATION")
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_URI, Uri::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_URI)
        }

        if (ip.isNullOrBlank() || token.isNullOrBlank() ||
            !token.matches(Regex("[0-9a-fA-F]{64}")) || uri == null
        ) {
            finishTransfer(STATE_FAILED, 0, "投屏参数无效")
            return
        }

        currentFileName = queryDisplayName(uri)
        transferRunning = true
        lastProgress = -1
        publishState(STATE_RUNNING, 0)

        try {
            acquireWakeLock()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to acquire wake lock: " + e.message)
        }

        serviceScope.launch {
            val success = try {
                fileSender.sendFile(
                    context = applicationContext,
                    ip = ip,
                    authToken = token,
                    uri = uri,
                    shouldCancel = { cancelRequested.get() }
                ) { progress ->
                    if (progress != lastProgress) {
                        lastProgress = progress
                        publishState(STATE_RUNNING, progress)
                        updateProgressNotification(progress)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background media transfer failed: " + e.message, e)
                false
            }

            when {
                cancelRequested.get() -> finishTransfer(STATE_CANCELLED, lastProgress.coerceAtLeast(0), "传输已取消")
                success -> finishTransfer(STATE_COMPLETED, 100, "已发送到电视，电视端可独立播放")
                else -> finishTransfer(STATE_FAILED, lastProgress.coerceAtLeast(0), "传输失败，请检查网络后重试")
            }
        }
    }

    private fun finishTransfer(state: String, progress: Int, message: String) {
        transferRunning = false
        publishState(state, progress)

        when (state) {
            STATE_COMPLETED -> {
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentTitle("视频已发送到电视")
                    .setContentText(currentFileName + " · " + message)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setOnlyAlertOnce(true)
                    .build()
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
                stopForeground(STOP_FOREGROUND_DETACH)
            }
            STATE_CANCELLED -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            else -> {
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("视频投屏失败")
                    .setContentText(currentFileName + " · " + message)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .build()
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
                stopForeground(STOP_FOREGROUND_DETACH)
            }
        }

        releaseWakeLock()
        stopSelf()
    }

    private fun publishState(state: String, progress: Int) {
        val safeProgress = progress.coerceIn(0, 100)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_STATE, state)
            .putInt(PREF_PROGRESS, safeProgress)
            .putString(PREF_FILE_NAME, currentFileName)
            .putLong(PREF_UPDATED_AT, System.currentTimeMillis())
            .apply()

        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName).apply {
            putExtra(PREF_STATE, state)
            putExtra(PREF_PROGRESS, safeProgress)
            putExtra(PREF_FILE_NAME, currentFileName)
        })
    }

    private fun buildProgressNotification(progress: Int): Notification {
        val cancelIntent = Intent(this, FileCastService::class.java).setAction(ACTION_CANCEL)
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("正在向电视传输视频")
            .setContentText(currentFileName + " · " + progress + "%")
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "取消传输",
                cancelPendingIntent
            )
            .build()
    }

    private fun updateProgressNotification(progress: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildProgressNotification(progress))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "媒体后台投屏",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示手机向电视传输媒体文件的进度"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() }
                } else null
            } ?: "媒体文件"
        } catch (_: Exception) {
            "媒体文件"
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            packageName + ":FileCastTransfer"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // A user leaving the Activity or removing the task must not cancel an active transfer.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (transferRunning) {
            cancelRequested.set(true)
            fileSender.cancelTransfer()
            publishState(STATE_FAILED, lastProgress.coerceAtLeast(0))
        }
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }
}
