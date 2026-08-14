package com.sharescreen.receiver.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class FileReceiver {
    private val TAG = "FileReceiver"
    private val FILE_PORT = 20003
    private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false

    fun startListening(context: Context, onFileReceived: (File) -> Unit, onStopRequested: () -> Unit) {
        if (isRunning) return
        isRunning = true
        thread {
            try {
                serverSocket = ServerSocket(FILE_PORT)
                Log.d(TAG, "FileReceiver listening on $FILE_PORT")
                while (isRunning) {
                    val client = serverSocket!!.accept()
                    thread {
                        try {
                            val input = DataInputStream(client.inputStream)
                            val fileName = input.readUTF()
                            val fileSize = input.readLong()

                            if (fileName == "STOP" && fileSize == 0L) {
                                Log.d(TAG, "Received STOP command")
                                onStopRequested()
                            } else {
                                Log.d(TAG, "Receiving file: $fileName ($fileSize bytes)")
                                val file = File(context.cacheDir, fileName)
                                val out = FileOutputStream(file)
                                val buffer = ByteArray(65536)
                                var totalRead = 0L
                                var bytesRead: Int = 0
                                while (totalRead < fileSize && input.read(buffer, 0, minOf(buffer.size.toLong(), fileSize - totalRead).toInt()).also { bytesRead = it } != -1) {
                                    out.write(buffer, 0, bytesRead)
                                    totalRead += bytesRead
                                }
                                out.flush()
                                out.close()
                                if (totalRead == fileSize) {
                                    Log.d(TAG, "File received successfully: ${file.absolutePath}")
                                    val nameLower = file.name.lowercase()
                                    if (nameLower.endsWith(".apk")) {
                                        android.os.Handler(context.mainLooper).post {
                                            android.widget.Toast.makeText(context, "收到 APK: ${file.name}\n正在启动安装...", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                        ApkInstaller.install(context, file)
                                    } else if (isMediaFile(nameLower)) {
                                        onFileReceived(file)
                                    } else {
                                        android.os.Handler(context.mainLooper).post {
                                            android.widget.Toast.makeText(context, "已接收文件: ${file.name}\n已存至电视存储", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error receiving file chunk: ${e.message}")
                        } finally {
                            try { client.close() } catch (e: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "FileReceiver error: ${e.message}", e)
            }
        }
    }

    private fun isMediaFile(name: String): Boolean {
        val mediaExtensions = listOf(
            ".mp4", ".mkv", ".avi", ".mov", ".flv", ".webm", ".3gp", ".wmv", ".ts",
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp",
            ".mp3", ".wav", ".aac", ".flac", ".ogg", ".m4a"
        )
        return mediaExtensions.any { name.endsWith(it) }
    }

    fun stopListening() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
    }
}
