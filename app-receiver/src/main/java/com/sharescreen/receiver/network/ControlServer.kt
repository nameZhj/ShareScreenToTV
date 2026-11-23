package com.sharescreen.receiver.network

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class ControlServer(
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val authToken: String
) {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false
    @Volatile private var activeClient: Socket? = null
    private val clientLock = Any()
    private val clients = java.util.Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val maxConcurrentHandshakes = java.util.concurrent.Semaphore(8)
    private val controlPort = 20000
    private val tag = "ControlServer"

    var onClientConnected: ((String) -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null

    @Synchronized
    fun start() {
        if (isRunning) return
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(controlPort))
            }
        } catch (e: Exception) {
            Log.e(tag, "Unable to bind control port: " + e.message, e)
            return
        }
        serverSocket = server
        isRunning = true
        thread(name = "ControlServer-Accept") {
            try {
                Log.d(tag, "Control server listening on " + controlPort)
                while (isRunning) {
                    val client = server.accept()
                    if (!maxConcurrentHandshakes.tryAcquire()) {
                        client.close()
                        continue
                    }
                    clients.add(client)
                    thread(name = "ControlServer-Client") {
                        try { handleClient(client) }
                        finally { maxConcurrentHandshakes.release() }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(tag, "Control server error: " + e.message, e)
            } finally {
                isRunning = false
                try { server.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handleClient(client: Socket) {
        var authenticated = false
        val clientIp = client.inetAddress.hostAddress ?: "Unknown"
        try {
            client.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8), true)

            val authLine = reader.readLine()
            val expected = "AUTH:" + authToken
            if (authLine == null ||
                !MessageDigest.isEqual(authLine.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
            ) {
                writer.println("DENIED")
                return
            }

            synchronized(clientLock) {
                if (activeClient?.isClosed == false) {
                    writer.println("BUSY")
                    return
                }
                activeClient = client
            }
            authenticated = true
            writer.println("OK")
            client.soTimeout = 0
            onClientConnected?.invoke(clientIp)
            Log.d(tag, "Authenticated client connected: " + clientIp)

            while (isRunning && !client.isClosed) {
                val command = reader.readLine() ?: break
                when (command) {
                    "GET_RESOLUTION" -> {
                        val response = JSONObject()
                            .put("width", screenWidth)
                            .put("height", screenHeight)
                            .toString()
                        writer.println(response)
                        if (writer.checkError()) break
                    }
                    "PING" -> writer.println("PONG")
                    "STOP" -> break
                    else -> writer.println("ERROR")
                }
            }
        } catch (e: Exception) {
            if (authenticated) Log.d(tag, "Client disconnected: " + e.message)
            else Log.w(tag, "Rejected or incomplete client connection")
        } finally {
            clients.remove(client)
            synchronized(clientLock) {
                if (activeClient === client) activeClient = null
            }
            try { client.close() } catch (_: Exception) {}
            if (authenticated) onClientDisconnected?.invoke()
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        clients.clear()
        synchronized(clientLock) { activeClient = null }
    }
}
