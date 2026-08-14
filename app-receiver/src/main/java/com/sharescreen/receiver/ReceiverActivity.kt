package com.sharescreen.receiver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.sharescreen.receiver.codec.AudioDecoder
import com.sharescreen.receiver.codec.VideoDecoder
import com.sharescreen.receiver.network.ControlServer
import com.sharescreen.receiver.network.DeviceDiscoveryService
import com.sharescreen.receiver.network.UdpReceiver
import com.sharescreen.receiver.network.FileReceiver
import android.content.Intent
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import java.net.Inet4Address
import java.net.NetworkInterface

class ReceiverActivity : AppCompatActivity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var tvStatus: TextView
    private lateinit var qrCard: LinearLayout
    private lateinit var ivQrCode: ImageView
    private lateinit var tvIpAddress: TextView
    private lateinit var btnManageCache: android.widget.Button

    private var isNetworkBound = false
    private var isServersStarted = false
    private var currentIp: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastVideoFrameTime = 0L
    private var discoveryService: DeviceDiscoveryService? = null
    private var controlServer: ControlServer? = null
    private var videoUdpReceiver: UdpReceiver? = null
    private var audioUdpReceiver: UdpReceiver? = null
    private var fileReceiver: FileReceiver? = null
    private var videoDecoder: VideoDecoder? = null
    private var audioDecoder: AudioDecoder? = null

    // Scaling mode
    companion object {
        const val SCALE_MODE_DEFAULT = 0
        const val SCALE_MODE_ASPECT_FIT = 1
    }
    private var scaleMode = SCALE_MODE_DEFAULT
    private var screenWidth = 1920
    private var screenHeight = 1080
    private var currentVideoWidth = 0
    private var currentVideoHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_receiver)

        surfaceView = findViewById(R.id.surfaceView)
        tvStatus = findViewById(R.id.tvStatus)
        qrCard = findViewById(R.id.qrCard)
        ivQrCode = findViewById(R.id.ivQrCode)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        btnManageCache = findViewById(R.id.btnManageCache)

        btnManageCache.setOnClickListener {
            startActivity(Intent(this, com.sharescreen.receiver.ui.CacheManagerActivity::class.java))
        }

        btnManageCache.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.04f).scaleY(1.04f).translationZ(12f).setDuration(120).start()
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(120).start()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            screenWidth = windowMetrics.bounds.width()
            screenHeight = windowMetrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                videoDecoder = VideoDecoder(holder.surface)
                videoDecoder?.onVideoSizeChanged = { vw, vh ->
                    currentVideoWidth = vw
                    currentVideoHeight = vh
                    runOnUiThread { applyScaleMode() }
                }
                videoDecoder?.start(screenWidth, screenHeight)
                audioDecoder = AudioDecoder()
                audioDecoder?.start()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                videoDecoder?.stop(); videoDecoder = null
                audioDecoder?.stop(); audioDecoder = null
            }
        })

        surfaceView.isClickable = true
        surfaceView.setOnClickListener {
            showMainMenuDialog()
        }

        bindToNetworkAndStartServers(screenWidth, screenHeight)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If a dialog is already showing, let default behavior handle D-pad navigation
        when (keyCode) {
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BOOKMARK,
            KeyEvent.KEYCODE_TV,
            KeyEvent.KEYCODE_TV_INPUT,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_INFO -> {
                // If QR card is showing with buttons, let D-pad center click the focused button
                if (qrCard.visibility == View.VISIBLE && btnManageCache.hasFocus()) {
                    return super.onKeyDown(keyCode, event)
                }
                showMainMenuDialog()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showMainMenuDialog() {
        val items = arrayOf("文件管理", "画面缩放")
        com.sharescreen.receiver.ui.TvDialogBuilder(this)
            .setTitle("菜单选项")
            .setItems(items) { which ->
                when (which) {
                    0 -> startActivity(Intent(this, com.sharescreen.receiver.ui.CacheManagerActivity::class.java))
                    1 -> showScaleModeDialog()
                }
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun showScaleModeDialog() {
        val items = arrayOf("默认 (当前投屏状态)", "平铺 (保持比例铺满屏幕)")
        val checkedItem = if (scaleMode == SCALE_MODE_DEFAULT) 0 else 1
        com.sharescreen.receiver.ui.TvDialogBuilder(this)
            .setTitle("画面缩放")
            .setSingleChoiceItems(items, checkedItem) { which ->
                scaleMode = if (which == 0) SCALE_MODE_DEFAULT else SCALE_MODE_ASPECT_FIT
                applyScaleMode()
                val modeName = if (scaleMode == SCALE_MODE_DEFAULT) "默认模式" else "平铺模式"
                Toast.makeText(this, "已切换为: $modeName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyScaleMode() {
        val lp = surfaceView.layoutParams as? ConstraintLayout.LayoutParams ?: return
        if (scaleMode == SCALE_MODE_ASPECT_FIT && currentVideoWidth > 0 && currentVideoHeight > 0) {
            // Keep original aspect ratio while longest side fills the screen (aspect fit)
            val scale = minOf(
                screenWidth.toFloat() / currentVideoWidth.toFloat(),
                screenHeight.toFloat() / currentVideoHeight.toFloat()
            )
            val targetW = (currentVideoWidth * scale).toInt()
            val targetH = (currentVideoHeight * scale).toInt()

            lp.width = targetW
            lp.height = targetH
            lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        } else {
            // Default: stretch to match parent constraints
            lp.width = 0
            lp.height = 0
            lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        }
        surfaceView.layoutParams = lp
    }

    private fun getWifiIpAddress(): String? {
        // Try WifiManager first (most reliable on Android)
        try {
            @Suppress("DEPRECATION")
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt != 0) {
                val bytes = ByteArray(4)
                bytes[0] = (ipInt and 0xFF).toByte()
                bytes[1] = (ipInt shr 8 and 0xFF).toByte()
                bytes[2] = (ipInt shr 16 and 0xFF).toByte()
                bytes[3] = (ipInt shr 24 and 0xFF).toByte()
                return Inet4Address.getByAddress(bytes).hostAddress
            }
        } catch (_: Exception) {}

        // Fallback: iterate network interfaces
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun generateQrCode(content: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun showQrCode(ip: String) {
        // Encode connection info as JSON for the phone scanner
        val payload = """{"ip":"$ip","port":20000}"""
        runOnUiThread {
            try {
                val qrBitmap = generateQrCode(payload, 600)
                ivQrCode.setImageBitmap(qrBitmap)
                tvIpAddress.text = "TV IP: $ip"
                qrCard.visibility = View.VISIBLE
            } catch (e: Exception) {
                tvIpAddress.text = "IP: $ip"
                qrCard.visibility = View.VISIBLE
            }
        }
    }

    private fun checkAndUpdateIp() {
        val newIp = getWifiIpAddress()
        if (newIp != null && newIp != currentIp) {
            currentIp = newIp
            showQrCode(newIp)
            runOnUiThread {
                Toast.makeText(this, "检测到网络变化，已切换IP: $newIp", Toast.LENGTH_SHORT).show()
            }
        } else if (newIp == null && currentIp != null) {
            currentIp = null
            runOnUiThread {
                tvIpAddress.text = "网络已断开，请检查WiFi连接"
                qrCard.visibility = View.VISIBLE
            }
        }
    }

    private fun bindToNetworkAndStartServers(width: Int, height: Int) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Try to find and bind to Wi-Fi network immediately (works even with VPN active)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val wifiNet = cm.allNetworks.firstOrNull { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (wifiNet != null) {
                cm.bindProcessToNetwork(wifiNet)
                isNetworkBound = true
            }
        }

        startServersWithIp(width, height)

        // Continuous network monitoring for automatic IP / network interface switching
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm.bindProcessToNetwork(network)
                } else {
                    @Suppress("DEPRECATION")
                    ConnectivityManager.setProcessDefaultNetwork(network)
                }
                checkAndUpdateIp()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                checkAndUpdateIp()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                checkAndUpdateIp()
            }

            override fun onLost(network: Network) {
                checkAndUpdateIp()
            }
        }
        networkCallback = callback
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startServersWithIp(width: Int, height: Int) {
        if (isServersStarted) return
        isServersStarted = true

        val ip = getWifiIpAddress()
        currentIp = ip
        if (ip != null) {
            showQrCode(ip)
        } else {
            runOnUiThread {
                tvIpAddress.text = "无法获取IP，请检查WiFi连接"
                qrCard.visibility = View.VISIBLE
            }
        }

        discoveryService = DeviceDiscoveryService()
        discoveryService?.start()

        controlServer = ControlServer(width, height)
        controlServer?.onClientConnected = { clientIp ->
            runOnUiThread {
                qrCard.visibility = View.GONE
                tvStatus.visibility = View.GONE
            }
        }
        controlServer?.onClientDisconnected = {
            runOnUiThread {
                // Only restore QR code if we haven't received video frames recently
                if (System.currentTimeMillis() - lastVideoFrameTime > 2000) {
                    tvStatus.text = "Waiting for connection..."
                    val currentIp = getWifiIpAddress()
                    if (currentIp != null) showQrCode(currentIp)
                    else qrCard.visibility = View.VISIBLE
                }
            }
        }
        controlServer?.start()

        videoUdpReceiver = UdpReceiver(20001)
        videoUdpReceiver?.onVideoFrameReceived = { data, pts ->
            lastVideoFrameTime = System.currentTimeMillis()
            if (qrCard.visibility != View.GONE || tvStatus.visibility != View.GONE) {
                runOnUiThread {
                    qrCard.visibility = View.GONE
                    tvStatus.visibility = View.GONE
                }
            }
            videoDecoder?.queueInputBuffer(data, pts)
        }
        videoUdpReceiver?.start()

        audioUdpReceiver = UdpReceiver(20002)
        audioUdpReceiver?.onAudioFrameReceived = { data, pts ->
            if (qrCard.visibility != View.GONE || tvStatus.visibility != View.GONE) {
                runOnUiThread {
                    qrCard.visibility = View.GONE
                    tvStatus.visibility = View.GONE
                }
            }
            audioDecoder?.queueInputBuffer(data, pts)
        }
        audioUdpReceiver?.start()

        fileReceiver = FileReceiver()
        fileReceiver?.startListening(this, onFileReceived = { file ->
            val intent = Intent(this, MediaPlaybackActivity::class.java)
            intent.putExtra(MediaPlaybackActivity.EXTRA_FILE_PATH, file.absolutePath)
            startActivity(intent)
        }, onStopRequested = {
            runOnUiThread {
                val intent = Intent("com.sharescreen.ACTION_STOP_MEDIA")
                sendBroadcast(intent)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        discoveryService?.stop()
        controlServer?.stop()
        videoUdpReceiver?.stop()
        audioUdpReceiver?.stop()
        fileReceiver?.stopListening()
        videoDecoder?.stop()
        audioDecoder?.stop()
    }
}
