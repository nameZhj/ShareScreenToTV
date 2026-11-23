package com.sharescreen.sender

import com.sharescreen.sender.network.NetworkBinder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.sharescreen.sender.network.ControlClient
import com.sharescreen.sender.network.FileSender
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var tvIp: String? by mutableStateOf(null)
    private var tvToken: String? by mutableStateOf(null)
    private var tvWidth: Int = 1920
    private var tvHeight: Int = 1080
    private var isSharing by mutableStateOf(false)
    private var isCastingFile by mutableStateOf(false)
    private var fileCastState by mutableStateOf(FileCastService.STATE_IDLE)
    private var isUploadingFile by mutableStateOf(false)
    private var pendingMediaUri: Uri? = null
    private var fileCastReceiverRegistered = false
    private var fileCastProgress by mutableStateOf(0)
    private var uploadFileProgress by mutableStateOf(0)
    private var statusMessage by mutableStateOf("请扫描电视上的二维码连接")
    private val controlClient = ControlClient()
    private val fileSender = FileSender()

    // --- Launchers ---
    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "需要录音权限以捕获系统声音", Toast.LENGTH_SHORT).show()
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchQrScanner()
        else Toast.makeText(this, "需要摄像头权限以扫描二维码", Toast.LENGTH_SHORT).show()
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "通知权限未开启，后台传输仍会继续，但通知栏进度可能不可见",
                Toast.LENGTH_LONG
            ).show()
        }
        val uri = pendingMediaUri
        pendingMediaUri = null
        if (uri != null) launchBackgroundFileCast(uri)
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            isSharing = false
        }
    }

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && tvIp != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some document providers grant only a temporary read permission.
            } catch (_: IllegalArgumentException) {
                // The foreground service still receives the URI grant from its start Intent.
            }
            startFileCasting(uri)
        }
    }

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && tvIp != null) {
            uploadFileToTv(uri)
        }
    }

    // QR Scanner via ZXing
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleQrResult(result.contents)
        }
    }

    private val fileCastStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != FileCastService.ACTION_STATE_CHANGED) return
            applyFileCastState(
                intent.getStringExtra(FileCastService.PREF_STATE) ?: FileCastService.STATE_IDLE,
                intent.getIntExtra(FileCastService.PREF_PROGRESS, 0)
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(FileCastService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(fileCastStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(fileCastStateReceiver, filter)
        }
        fileCastReceiverRegistered = true
        restoreFileCastState()
    }

    override fun onStop() {
        if (fileCastReceiverRegistered) {
            try { unregisterReceiver(fileCastStateReceiver) } catch (_: Exception) {}
            fileCastReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request audio permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        controlClient.onDisconnected = {
            runOnUiThread {
                if (tvIp != null) {
                    stopCaptureService()
                    fileSender.cancelTransfer()
                    tvToken = null
                    tvIp = null
                    isSharing = false
                    isCastingFile = false
                    isUploadingFile = false
                    statusMessage = "与电视的连接已断开，请重新扫码"
                    Toast.makeText(this@MainActivity, "连接已断开，已返回扫码页面", Toast.LENGTH_SHORT).show()
                }
            }
        }

        bindToWifiNetwork()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        controlClient.disconnect()
        fileSender.cancelTransfer()
        super.onDestroy()

    }

    // Track physical LAN networks without changing the process-wide default network.
    // Only sockets that target the TV are routed outside the VPN.
    private fun bindToWifiNetwork() {
        NetworkBinder.start(applicationContext)
    }

    private fun handleQrResult(content: String) {
        try {
            val json = JSONObject(content)
            val ip = json.getString("ip")
            val port = json.optInt("port", 20000)
            require(port in 1..65535) { "端口号无效" }
            val token = json.getString("token")
            require(token.matches(Regex("[0-9a-fA-F]{64}"))) { "二维码缺少有效配对密钥" }
            statusMessage = "正在连接 $ip:$port..."
            controlClient.connect(ip, port, token, onConnected = { w, h ->
                runOnUiThread {
                    tvIp = ip
                    tvToken = token
                    tvWidth = w
                    tvHeight = h
                    statusMessage = "已连接到电视 $ip (${w}x${h})"
                }
            }, onError = { errMsg ->
                runOnUiThread {
                    tvToken = null
                    tvIp = null
                    statusMessage = "连接失败: $errMsg\n请确保电视端App已打开且处于同一局域网"
                    Toast.makeText(this, "连接失败: $errMsg", Toast.LENGTH_LONG).show()
                }
            })
        } catch (e: Exception) {
            Toast.makeText(this, "二维码格式错误：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("将摄像头对准电视上的二维码")
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        qrScanLauncher.launch(options)
    }

    private fun startScanFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            launchQrScanner()
        }
    }

    @Composable
    fun MainScreen() {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (tvIp == null) {
                Text(
                    text = "ShareScreen",
                    style = MaterialTheme.typography.headlineLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { startScanFlow() },
                    modifier = Modifier.fillMaxWidth(0.7f).height(52.dp)
                ) {
                    Text("扫描电视二维码")
                }
            } else {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        if (isSharing) stopCaptureService()
                        else requestScreenCapture()
                    },
                    modifier = Modifier.fillMaxWidth(0.7f).height(52.dp),
                    enabled = !isCastingFile && !isUploadingFile
                ) {
                    Text(if (isSharing) "停止投屏屏幕" else "开始投屏屏幕")
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                if (isCastingFile) {
                    if (fileCastState == FileCastService.STATE_RUNNING) {
                        Text(
                            "后台传输中: $fileCastProgress%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        LinearProgressIndicator(
                            progress = fileCastProgress / 100f,
                            modifier = Modifier.fillMaxWidth(0.7f)
                        )
                        Text(
                            "现在可以返回桌面或切换其他应用，手机会继续向电视传输",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "视频已发送到电视，电视端正在独立播放",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { stopFileCasting() },
                        modifier = Modifier.fillMaxWidth(0.7f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(
                            if (fileCastState == FileCastService.STATE_RUNNING) "取消后台传输"
                            else "停止电视播放"
                        )
                    }
                } else {
                    Button(
                        onClick = { pickMediaLauncher.launch(arrayOf("video/*", "audio/*", "image/*")) },
                        modifier = Modifier.fillMaxWidth(0.7f).height(52.dp),
                        enabled = !isSharing && !isUploadingFile
                    ) {
                        Text("投屏本地多媒体文件")
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                if (isUploadingFile) {
                    Text("文件上传中: $uploadFileProgress%", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = uploadFileProgress / 100f,
                        modifier = Modifier.fillMaxWidth(0.7f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { stopFileUpload() },
                        modifier = Modifier.fillMaxWidth(0.7f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("取消文件上传")
                    }
                } else {
                    Button(
                        onClick = { pickFileLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth(0.7f).height(52.dp),
                        enabled = !isSharing && !isCastingFile
                    ) {
                        Text("上传文件到电视")
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        fileSender.cancelTransfer()
                        if (fileCastState == FileCastService.STATE_RUNNING) {
                            FileCastService.cancel(this@MainActivity)
                        }
                        controlClient.disconnect()
                        if (isSharing) stopCaptureService()
                        tvToken = null
                        tvIp = null
                        isCastingFile = false
                        isUploadingFile = false
                        statusMessage = "请扫描电视上的二维码连接"
                    },
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Text("重新扫码")
                }
            }
        }
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_IP, tvIp)
            putExtra(ScreenCaptureService.EXTRA_WIDTH, tvWidth)
            putExtra(ScreenCaptureService.EXTRA_HEIGHT, tvHeight)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        isSharing = true
    }

    private fun stopCaptureService() {
        startService(Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        })
        isSharing = false
    }

    private fun startFileCasting(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingMediaUri = uri
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchBackgroundFileCast(uri)
    }

    private fun launchBackgroundFileCast(uri: Uri) {
        val ip = tvIp ?: return
        val token = tvToken ?: return
        val serviceIntent = Intent(this, FileCastService::class.java).apply {
            action = FileCastService.ACTION_START
            putExtra(FileCastService.EXTRA_IP, ip)
            putExtra(FileCastService.EXTRA_TOKEN, token)
            putExtra(FileCastService.EXTRA_URI, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            applyFileCastState(FileCastService.STATE_RUNNING, 0)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "无法启动后台投屏服务: ${e.localizedMessage ?: "未知错误"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopFileCasting() {
        when (fileCastState) {
            FileCastService.STATE_RUNNING -> FileCastService.cancel(this)
            FileCastService.STATE_COMPLETED -> {
                val ip = tvIp ?: return
                val token = tvToken ?: return
                lifecycleScope.launch {
                    fileSender.stopCasting(ip, token)
                    FileCastService.clearCompletedState(this@MainActivity)
                    applyFileCastState(FileCastService.STATE_IDLE, 0)
                }
            }
        }
    }

    private fun restoreFileCastState() {
        val preferences = getSharedPreferences(FileCastService.PREFS_NAME, MODE_PRIVATE)
        val state = preferences.getString(FileCastService.PREF_STATE, FileCastService.STATE_IDLE)
            ?: FileCastService.STATE_IDLE
        val updatedAt = preferences.getLong(FileCastService.PREF_UPDATED_AT, 0L)
        val isExpiredCompletedState = state == FileCastService.STATE_COMPLETED &&
            updatedAt > 0L && System.currentTimeMillis() - updatedAt > 24L * 60L * 60L * 1000L
        if (isExpiredCompletedState) {
            FileCastService.clearCompletedState(this)
            applyFileCastState(FileCastService.STATE_IDLE, 0)
            return
        }
        applyFileCastState(
            state,
            preferences.getInt(FileCastService.PREF_PROGRESS, 0)
        )
    }

    private fun applyFileCastState(state: String, progress: Int) {
        fileCastState = state
        fileCastProgress = progress.coerceIn(0, 100)
        isCastingFile = state == FileCastService.STATE_RUNNING ||
            state == FileCastService.STATE_COMPLETED
    }

    private fun uploadFileToTv(uri: android.net.Uri) {
        val ip = tvIp ?: return
        val token = tvToken ?: return
        isUploadingFile = true
        uploadFileProgress = 0
        lifecycleScope.launch {
            val success = fileSender.sendFile(this@MainActivity, ip, token, uri) { progress ->
                uploadFileProgress = progress
            }
            runOnUiThread {
                isUploadingFile = false
                if (success) {
                    Toast.makeText(this@MainActivity, "文件已成功上传至电视！", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "文件上传失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun stopFileUpload() {
        fileSender.cancelTransfer()
        isUploadingFile = false
        Toast.makeText(this, "已取消文件上传", Toast.LENGTH_SHORT).show()
    }
}
