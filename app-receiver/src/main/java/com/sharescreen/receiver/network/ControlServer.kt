package com.sharescreen.receiver.network

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.json.JSONObject

class ControlServer(private val screenWidth: Int, private val screenHeight: Int) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val CONTROL_PORT = 20000
    private val TAG = "ControlServer"
    
    var onClientConnected: ((String) -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        thread {
            try {
                serverSocket = ServerSocket(CONTROL_PORT)
                Log.d(TAG, "Control Server started on port $CONTROL_PORT")
                while (isRunning) {
                    val clientSocket = serverSocket?.accept()
                    if (clientSocket != null) {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Control server error: ${e.message}")
                }
            }
        }
    }
    
    private fun handleClient(clientSocket: Socket) {
        val clientIp = clientSocket.inetAddress.hostAddress ?: "Unknown"
        Log.d(TAG, "Client connected: $clientIp")
        onClientConnected?.invoke(clientIp)
        
        thread {
            try {
                val reader = BufferedReader(InputStreamReader(clientSocket.inputStream))
                val writer = PrintWriter(clientSocket.outputStream, true)
                
                // Read commands
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.d(TAG, "Received command: $line")
                    if (line == "GET_RESOLUTION") {
                        val json = JSONObject()
                        json.put("width", screenWidth)
                        json.put("height", screenHeight)
                        writer.println(json.toString())
                    } else if (line == "STOP") {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Client connection error: ${e.message}")
            } finally {
                clientSocket.close()
                onClientDisconnected?.invoke()
            }
        }
    }

    fun stop() {
        isRunning = false
        serverSocket?.close()
        serverSocket = null
    }
}
