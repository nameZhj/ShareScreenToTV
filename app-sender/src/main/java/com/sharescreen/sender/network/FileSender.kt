package com.sharescreen.sender.network

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class FileSender {
    private val TAG = "FileSender"
    private val FILE_PORT = 20003

    private fun createSocket(ip: String, timeoutMs: Int): Socket {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && NetworkBinder.wifiNetwork != null) {
            try {
                val s = NetworkBinder.wifiNetwork!!.socketFactory.createSocket()
                s.soTimeout = timeoutMs
                s.connect(InetSocketAddress(ip, FILE_PORT), timeoutMs)
                return s
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi socketFactory failed: ${e.message}, trying default socket")
            }
        }
        val s = Socket()
        s.soTimeout = timeoutMs
        s.connect(InetSocketAddress(ip, FILE_PORT), timeoutMs)
        return s
    }

    suspend fun sendFile(context: Context, ip: String, uri: Uri, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        var socketRef: Socket? = null
        try {
            val socket = createSocket(ip, 15000)
            socketRef = socket

            val contentResolver = context.contentResolver
            var fileName = "uploaded_file"
            var fileSize = 0L

            // Query actual file name and size
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIndex != -1) {
                        val name = it.getString(nameIndex)
                        if (!name.isNullOrEmpty()) fileName = name
                    }
                    if (sizeIndex != -1) {
                        fileSize = it.getLong(sizeIndex)
                    }
                }
            }

            if (fileSize <= 0L) {
                try {
                    val pfd = contentResolver.openFileDescriptor(uri, "r")
                    fileSize = pfd?.statSize ?: 0L
                    pfd?.close()
                } catch (_: Exception) {}
            }

            contentResolver.openInputStream(uri)?.use { inputStream ->
                if (fileSize <= 0L) {
                    fileSize = inputStream.available().toLong()
                }
                if (fileSize <= 0L) return@withContext false

                val out = DataOutputStream(socket.outputStream)
                out.writeUTF(fileName)
                out.writeLong(fileSize)

                val buffer = ByteArray(65536)
                var totalRead = 0L
                var bytesRead: Int
                var lastProgress = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    val progress = ((totalRead.toFloat() / fileSize) * 100).toInt()
                    if (progress != lastProgress) {
                        lastProgress = progress
                        withContext(Dispatchers.Main) {
                            onProgress(progress)
                        }
                    }
                }
                out.flush()
                return@withContext true
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "File send error: ${e.message}", e)
            false
        } finally {
            try { socketRef?.close() } catch (e: Exception) {}
        }
    }

    suspend fun stopCasting(ip: String) = withContext(Dispatchers.IO) {
        var socketRef: Socket? = null
        try {
            val socket = createSocket(ip, 5000)
            socketRef = socket
            val out = DataOutputStream(socket.outputStream)
            out.writeUTF("STOP")
            out.writeLong(0)
            out.flush()
            Log.d(TAG, "Successfully sent STOP media command to $ip:$FILE_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Stop cast error: ${e.message}")
        } finally {
            try { socketRef?.close() } catch (e: Exception) {}
        }
    }
}
