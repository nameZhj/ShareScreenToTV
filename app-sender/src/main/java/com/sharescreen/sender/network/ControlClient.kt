package com.sharescreen.sender.network

import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import org.json.JSONObject

class ControlClient {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val CONTROL_PORT = 20000
    private val TAG = "ControlClient"
    var onDisconnected: (() -> Unit)? = null

    fun connect(ip: String, onConnected: (Int, Int) -> Unit, onError: () -> Unit) {
        thread {
            try {
                // Prefer to create the socket through the explicit Wi-Fi Network reference
                // so it bypasses any active VPN TUN interface on the phone.
                val sock: Socket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    NetworkBinder.wifiNetwork != null) {
                    Log.d(TAG, "Creating socket via explicit Wi-Fi network")
                    NetworkBinder.wifiNetwork!!.socketFactory.createSocket()
                } else {
                    Log.d(TAG, "Creating socket via default network")
                    Socket()
                }
                sock.soTimeout = 8000
                sock.connect(InetSocketAddress(ip, CONTROL_PORT), 8000)
                socket = sock
                writer = PrintWriter(socket!!.outputStream, true)
                reader = BufferedReader(InputStreamReader(socket!!.inputStream))
                
                Log.d(TAG, "Connected to control server at $ip")
                
                // Request resolution
                writer?.println("GET_RESOLUTION")
                val response = reader?.readLine()
                if (response != null) {
                    val json = JSONObject(response)
                    val width = json.getInt("width")
                    val height = json.getInt("height")
                    Log.d(TAG, "TV Resolution: ${width}x${height}")
                    // Reset read timeout so idle connection doesn't throw timeout exceptions
                    try {
                        socket?.soTimeout = 0
                        socket?.keepAlive = true
                    } catch (_: Exception) {}
                    onConnected(width, height)

                    // Monitor connection health; when connection drops, trigger onDisconnected
                    while (true) {
                        val line = reader?.readLine() ?: break
                        Log.d(TAG, "Control server message: $line")
                    }
                    Log.d(TAG, "Control server connection closed")
                    onDisconnected?.invoke()
                } else {
                    onError()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Control client connection failed/ended: ${e.message}")
                onError()
                onDisconnected?.invoke()
            }
        }
    }

    fun disconnect() {
        thread {
            try {
                writer?.println("STOP")
                socket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting: ${e.message}")
            }
        }
    }
}
