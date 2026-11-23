package com.sharescreen.sender.network

import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class ControlClient {
    @Volatile private var socket: Socket? = null
    private val connectionGeneration = AtomicLong(0)
    private val tag = "ControlClient"
    var onDisconnected: (() -> Unit)? = null

    fun connect(
        ip: String,
        port: Int = 20000,
        authToken: String,
        onConnected: (Int, Int) -> Unit,
        onError: (String) -> Unit
    ) {
        disconnect()
        val generation = connectionGeneration.incrementAndGet()
        thread(name = "ControlClient-Connect") {
            var connected = false
            var currentSocket: Socket? = null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val wifiNetwork = NetworkBinder.wifiNetwork
                    if (wifiNetwork != null) {
                        try {
                            val candidate = wifiNetwork.socketFactory.createSocket()
                            candidate.connect(InetSocketAddress(ip, port), 5000)
                            currentSocket = candidate
                        } catch (e: Exception) {
                            Log.w(tag, "Wi-Fi socket connection failed: " + e.message)
                        }
                    }
                }

                if (currentSocket == null) {
                    val candidate = Socket()
                    candidate.connect(InetSocketAddress(ip, port), 5000)
                    currentSocket = candidate
                }
                if (generation != connectionGeneration.get()) {
                    currentSocket?.close()
                    return@thread
                }

                val sock = currentSocket ?: throw IllegalStateException("Socket 未建立")
                socket = sock
                sock.soTimeout = 5000
                val writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))

                writer.println("AUTH:" + authToken)
                val authResponse = reader.readLine()
                if (authResponse != "OK") {
                    throw IllegalStateException(
                        if (authResponse == "BUSY") "电视已连接其他设备"
                        else "电视身份验证失败，请重新扫描二维码"
                    )
                }

                writer.println("GET_RESOLUTION")
                if (writer.checkError()) throw IllegalStateException("发送分辨率请求失败")
                val response = reader.readLine() ?: throw IllegalStateException("电视未返回分辨率")
                val json = JSONObject(response)
                val width = json.getInt("width")
                val height = json.getInt("height")
                require(width in 1..16384 && height in 1..16384) { "电视返回了无效分辨率" }

                sock.keepAlive = true
                sock.soTimeout = 15_000
                if (generation != connectionGeneration.get()) return@thread
                connected = true
                onConnected(width, height)

                // Application-level heartbeat detects a dead Wi-Fi path much faster than
                // relying on TCP keepalive defaults, which can take hours.
                while (generation == connectionGeneration.get()) {
                    Thread.sleep(10_000)
                    if (generation != connectionGeneration.get()) break
                    writer.println("PING")
                    if (writer.checkError() || reader.readLine() != "PONG") break
                }
                if (generation == connectionGeneration.get()) onDisconnected?.invoke()
            } catch (e: Exception) {
                Log.w(tag, "Control connection ended: " + e.message)
                if (generation == connectionGeneration.get()) {
                    if (connected) onDisconnected?.invoke()
                    else onError(e.localizedMessage ?: "无法连接到电视，请确认两台设备处于同一局域网")
                }
            } finally {
                if (socket === currentSocket) socket = null
                try { currentSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    fun disconnect() {
        connectionGeneration.incrementAndGet()
        val oldSocket = socket
        socket = null
        try { oldSocket?.close() } catch (e: Exception) {
            Log.w(tag, "Error closing control socket: " + e.message)
        }
    }
}
