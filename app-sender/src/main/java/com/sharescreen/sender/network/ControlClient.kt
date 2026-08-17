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
    private val TAG = "ControlClient"
    var onDisconnected: (() -> Unit)? = null

    fun connect(ip: String, port: Int = 20000, onConnected: (Int, Int) -> Unit, onError: (String) -> Unit) {
        thread(name = "ControlClient-Connect") {
            var isConnectionEstablished = false
            try {
                var sock: Socket? = null
                var lastException: Exception? = null

                // Try 1: Prefer Wi-Fi network socket factory to bypass VPN
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && NetworkBinder.wifiNetwork != null) {
                    try {
                        val s = NetworkBinder.wifiNetwork!!.socketFactory.createSocket()
                        s.soTimeout = 5000
                        s.connect(InetSocketAddress(ip, port), 5000)
                        sock = s
                        Log.d(TAG, "Connected via Wi-Fi socketFactory to $ip:$port")
                    } catch (e: Exception) {
                        lastException = e
                        Log.w(TAG, "Wi-Fi socketFactory connection failed: ${e.message}, falling back to default socket")
                    }
                }

                // Try 2: Standard socket fallback
                if (sock == null || !sock.isConnected) {
                    try {
                        val s = Socket()
                        s.soTimeout = 5000
                        s.connect(InetSocketAddress(ip, port), 5000)
                        sock = s
                        Log.d(TAG, "Connected via default socket to $ip:$port")
                    } catch (e: Exception) {
                        lastException = e
                        Log.e(TAG, "Default socket connection failed: ${e.message}")
                    }
                }

                if (sock == null || !sock.isConnected) {
                    val msg = lastException?.localizedMessage ?: "无法连接到电视 ($ip:$port)"
                    onError(msg)
                    return@thread
                }

                socket = sock
                writer = PrintWriter(sock.outputStream, true)
                reader = BufferedReader(InputStreamReader(sock.inputStream))

                // Request resolution
                writer?.println("GET_RESOLUTION")
                val response = reader?.readLine()
                if (response != null) {
                    val json = JSONObject(response)
                    val width = json.getInt("width")
                    val height = json.getInt("height")
                    Log.d(TAG, "TV Resolution: ${width}x${height}")

                    try {
                        sock.soTimeout = 0
                        sock.keepAlive = true
                    } catch (_: Exception) {}

                    isConnectionEstablished = true
                    onConnected(width, height)

                    // Monitor connection health; when connection drops, trigger onDisconnected
                    while (true) {
                        val line = reader?.readLine() ?: break
                        Log.d(TAG, "Control server message: $line")
                    }
                    Log.d(TAG, "Control server connection closed")
                    onDisconnected?.invoke()
                } else {
                    onError("未能获取电视端分辨率响应")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Control client exception: ${e.message}")
                if (isConnectionEstablished) {
                    onDisconnected?.invoke()
                } else {
                    val errMsg = e.localizedMessage ?: "连接失败"
                    onError(errMsg)
                }
            }
        }
    }

    fun disconnect() {
        thread(name = "ControlClient-Disconnect") {
            try {
                writer?.println("STOP")
                socket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting: ${e.message}")
            }
        }
    }
}
