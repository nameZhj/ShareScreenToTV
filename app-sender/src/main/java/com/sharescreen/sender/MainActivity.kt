package com.sharescreen.sender

import com.sharescreen.sender.network.NetworkBinder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    private var tvWidth: Int = 1920
    private var tvHeight: Int = 1080
    private var isSharing by mutableStateOf(false)
    private var isCastingFile by mutableStateOf(false)
    private var isUploadingFile by mutableStateOf(false)
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
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && tvIp != null) {
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

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
        super.onDestroy()
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
    }

    // Bind process to real Wi-Fi, bypassing VPN, and monitor network changes
    private fun bindToWifiNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        fun updateWifiNetwork(network: Network, isChanged: Boolean = false) {
            NetworkBinder.wifiNetwork = network
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.bindProcessToNetwork(network)
            } else {
                @Suppress("DEPRECATION")
                ConnectivityManager.setProcessDefaultNetwork(network)
            }
            if (isChanged) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "检测到网络变化，已切换Wi-Fi网络", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Initial search
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val wifiNet = cm.allNetworks.firstOrNull { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (wifiNet != null) {
                updateWifiNetwork(wifiNet)
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val isNew = NetworkBinder.wifiNetwork != null && NetworkBinder.wifiNetwork != network
                updateWifiNetwork(network, isNew)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                updateWifiNetwork(network, true)
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Wi-Fi网络已断开", Toast.LENGTH_SHORT).show()
                    if (tvIp != null) {
                        stopCaptureService()
                        tvIp = null
                        isSharing = false
                        isCastingFile = false
                        isUploadingFile = false
                        statusMessage = "Wi-Fi已断开，请连接Wi-Fi后重新扫码"
                    }
                }
            }
        }
        networkCallback = callback
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleQrResult(content: String) {
        try {
            val json = JSONObject(content)
            val ip = json.getString("ip")
            val port = json.optInt("port", 20000)
            statusMessage = "正在连接 $ip:$port..."
            controlClient.connect(ip, port, onConnected = { w, h ->
                tvIp = ip
                tvWidth = w
                tvHeight = h
                runOnUiThread { statusMessage = "已连接到电视 $ip (${w}x${h})" }
            }, onError = { errMsg ->
                runOnUiThread {
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
                    Text("文件投屏中: $fileCastProgress%", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = fileCastProgress / 100f,
                        modifier = Modifier.fillMaxWidth(0.7f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { stopFileCasting() },
                        modifier = Modifier.fillMaxWidth(0.7f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("停止媒体投屏")
                    }
                } else {
                    Button(
                        onClick = { pickMediaLauncher.launch("*/*") },
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
                        tvIp = null
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

    private fun startFileCasting(uri: android.net.Uri) {
        val ip = tvIp ?: return
        isCastingFile = true
        fileCastProgress = 0
        lifecycleScope.launch {
            val success = fileSender.sendFile(this@MainActivity, ip, uri) { progress ->
                fileCastProgress = progress
            }
            if (success) {
                runOnUiThread { Toast.makeText(this@MainActivity, "文件传输完成", Toast.LENGTH_SHORT).show() }
            } else {
                runOnUiThread { Toast.makeText(this@MainActivity, "文件传输失败", Toast.LENGTH_SHORT).show() }
                isCastingFile = false
            }
        }
    }

    private fun stopFileCasting() {
        val ip = tvIp ?: return
        lifecycleScope.launch {
            fileSender.stopCasting(ip)
            isCastingFile = false
        }
    }

    private fun uploadFileToTv(uri: android.net.Uri) {
        val ip = tvIp ?: return
        isUploadingFile = true
        uploadFileProgress = 0
        lifecycleScope.launch {
            val success = fileSender.sendFile(this@MainActivity, ip, uri) { progress ->
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
        val ip = tvIp ?: return
        lifecycleScope.launch {
            fileSender.stopCasting(ip)
            isUploadingFile = false
        }
    }
}
