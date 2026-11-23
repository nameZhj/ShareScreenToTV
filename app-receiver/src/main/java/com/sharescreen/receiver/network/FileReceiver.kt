package com.sharescreen.receiver.network

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

class FileReceiver {
    private val tag = "FileReceiver"
    private val filePort = 20003
    private val maxFileSize = 2L * 1024L * 1024L * 1024L
    private val maxConcurrentClients = Semaphore(2)
    private val clients = java.util.Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false
    @Volatile var allowedSenderIp: String? = null

    @Synchronized
    fun startListening(
        context: Context,
        authToken: String,
        onFileReceived: (File) -> Unit,
        onStopRequested: () -> Unit
    ) {
        if (isRunning) return
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(filePort))
            }
        } catch (e: Exception) {
            Log.e(tag, "Unable to bind file port: " + e.message, e)
            return
        }
        serverSocket = server
        isRunning = true
        thread(name = "FileReceiver-Accept") {
            try {
                Log.d(tag, "File receiver listening on " + filePort)
                while (isRunning) {
                    val client = server.accept()
                    val allowedIp = allowedSenderIp
                    if (allowedIp == null || client.inetAddress.hostAddress != allowedIp) {
                        client.close()
                        continue
                    }
                    if (!maxConcurrentClients.tryAcquire()) {
                        client.close()
                        continue
                    }
                    clients.add(client)
                    thread(name = "FileReceiver-Client") {
                        try {
                            handleClient(context, client, authToken, onFileReceived, onStopRequested)
                        } finally {
                            clients.remove(client)
                            try { client.close() } catch (_: Exception) {}
                            maxConcurrentClients.release()
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(tag, "File receiver error: " + e.message, e)
            } finally {
                isRunning = false
                try { server.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handleClient(
        context: Context,
        client: Socket,
        authToken: String,
        onFileReceived: (File) -> Unit,
        onStopRequested: () -> Unit
    ) {
        var temporaryFile: File? = null
        try {
            client.soTimeout = 5000
            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
            val receivedToken = input.readUTF()
            if (!constantTimeEquals(receivedToken, authToken)) {
                sendResponse(client, "DENIED")
                return
            }
            client.soTimeout = 30_000

            val rawName = input.readUTF()
            val fileSize = input.readLong()
            if (rawName == "STOP" && fileSize == 0L) {
                sendResponse(client, "OK")
                onStopRequested()
                return
            }

            val safeName = sanitizeFileName(rawName)
            if (safeName == null) {
                sendResponse(client, "ERROR:INVALID_NAME")
                return
            }
            if (fileSize <= 0L || fileSize > maxFileSize) {
                sendResponse(client, "ERROR:INVALID_SIZE")
                return
            }

            val cacheDir = context.cacheDir.canonicalFile
            if (!cacheDir.exists() && !cacheDir.mkdirs()) throw IOException("无法创建缓存目录")
            if (cacheDir.usableSpace > 0 && fileSize > cacheDir.usableSpace) {
                sendResponse(client, "ERROR:INSUFFICIENT_SPACE")
                return
            }

            temporaryFile = File.createTempFile("sharescreen_", ".part", cacheDir)
            FileOutputStream(temporaryFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                var remaining = fileSize
                while (remaining > 0) {
                    val requested = minOf(buffer.size.toLong(), remaining).toInt()
                    val count = input.read(buffer, 0, requested)
                    if (count < 0) throw IOException("文件传输提前结束")
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                output.fd.sync()
            }

            val destination = uniqueDestination(cacheDir, safeName).canonicalFile
            if (destination.parentFile != cacheDir) throw IOException("无效的目标路径")
            if (!temporaryFile.renameTo(destination)) throw IOException("无法保存接收文件")
            temporaryFile = null

            sendResponse(client, "OK")
            Log.d(tag, "Received " + destination.name + " (" + fileSize + " bytes)")
            val nameLower = destination.name.lowercase()
            if (nameLower.endsWith(".apk")) {
                android.os.Handler(context.mainLooper).post {
                    android.widget.Toast.makeText(
                        context,
                        "收到 APK: " + destination.name + "\n正在启动安装...",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                ApkInstaller.install(context, destination)
            } else if (isMediaFile(nameLower)) {
                onFileReceived(destination)
            } else {
                android.os.Handler(context.mainLooper).post {
                    android.widget.Toast.makeText(
                        context,
                        "已接收文件: " + destination.name + "\n已存至电视缓存",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Rejected or interrupted file transfer: " + e.message)
            try { sendResponse(client, "ERROR:TRANSFER_FAILED") } catch (_: Exception) {}
        } finally {
            temporaryFile?.delete()
        }
    }

    private fun sendResponse(client: Socket, response: String) {
        val output = DataOutputStream(client.getOutputStream())
        output.writeUTF(response)
        output.flush()
    }

    private fun constantTimeEquals(first: String, second: String): Boolean =
        MessageDigest.isEqual(first.toByteArray(Charsets.UTF_8), second.toByteArray(Charsets.UTF_8))

    private fun sanitizeFileName(value: String): String? {
        val name = value.trim()
        if (name.isEmpty() || name == "." || name == ".." || name.length > 180) return null
        if (name.contains('/') || name.contains('\\') || name.any { it.isISOControl() }) return null
        return name
    }

    private fun uniqueDestination(directory: File, name: String): File {
        val original = File(directory, name)
        if (!original.exists()) return original
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        var index = 1
        while (true) {
            val candidate = File(directory, stem + "_" + index + extension)
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun isMediaFile(name: String): Boolean {
        val extensions = listOf(
            ".mp4", ".mkv", ".avi", ".mov", ".flv", ".webm", ".3gp", ".wmv", ".ts",
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp",
            ".mp3", ".wav", ".aac", ".flac", ".ogg", ".m4a"
        )
        return extensions.any(name::endsWith)
    }

    @Synchronized
    fun stopListening() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        allowedSenderIp = null
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        clients.clear()
    }
}
