package com.sharescreen.sender.network

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

class FileSender {
    private val tag = "FileSender"
    private val filePort = 20003
    private val maxFileSize = 2L * 1024L * 1024L * 1024L
    private val activeTransferSocket = AtomicReference<Socket?>(null)

    private fun createSocket(ip: String, timeoutMs: Int): Socket {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val network = NetworkBinder.wifiNetwork
            if (network != null) {
                val candidate = network.socketFactory.createSocket()
                try {
                    candidate.connect(InetSocketAddress(ip, filePort), timeoutMs)
                    return candidate
                } catch (e: Exception) {
                    try { candidate.close() } catch (_: Exception) {}
                    Log.w(tag, "Wi-Fi socket connection failed: " + e.message)
                }
            }
        }
        return Socket().apply { connect(InetSocketAddress(ip, filePort), timeoutMs) }
    }

    suspend fun sendFile(
        context: Context,
        ip: String,
        authToken: String,
        uri: Uri,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        var socket: Socket? = null
        try {
            val resolver = context.contentResolver
            var fileName = "uploaded_file"
            var fileSize = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { fileName = it }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) fileSize = cursor.getLong(sizeIndex)
                }
            }

            if (fileSize > maxFileSize) throw IllegalArgumentException("文件超过 2 GB 接收上限")
            if (fileSize <= 0L) {
                val stagedFile = File.createTempFile("sharescreen_upload_", ".tmp", context.cacheDir)
                tempFile = stagedFile
                val source = resolver.openInputStream(uri) ?: return@withContext false
                source.use { input ->
                    FileOutputStream(stagedFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            if (total > maxFileSize) throw IllegalArgumentException("文件超过 2 GB 接收上限")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                fileSize = stagedFile.length()
            }
            if (fileSize <= 0L || fileSize > maxFileSize) return@withContext false

            val sourceStream = if (tempFile != null) FileInputStream(tempFile) else resolver.openInputStream(uri)
                ?: return@withContext false

            val transferSocket = createSocket(ip, 15_000)
            socket = transferSocket
            activeTransferSocket.set(transferSocket)
            transferSocket.soTimeout = 30_000

            sourceStream.use { input ->
                val output = DataOutputStream(BufferedOutputStream(transferSocket.getOutputStream()))
                output.writeUTF(authToken)
                output.writeUTF(fileName)
                output.writeLong(fileSize)

                val buffer = ByteArray(64 * 1024)
                var totalRead = 0L
                var lastProgress = -1
                while (totalRead < fileSize) {
                    val requested = minOf(buffer.size.toLong(), fileSize - totalRead).toInt()
                    val count = input.read(buffer, 0, requested)
                    if (count < 0) throw IllegalStateException("源文件读取不完整")
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    totalRead += count
                    val progress = ((totalRead * 100L) / fileSize).toInt().coerceIn(0, 100)
                    if (progress != lastProgress) {
                        lastProgress = progress
                        withContext(Dispatchers.Main) { onProgress(progress) }
                    }
                }
                output.flush()

                val response = DataInputStream(BufferedInputStream(transferSocket.getInputStream())).readUTF()
                if (response != "OK") throw IllegalStateException("电视拒绝文件: " + response)
                withContext(Dispatchers.Main) { onProgress(100) }
            }
            true
        } catch (e: Exception) {
            Log.e(tag, "File send error: " + e.message, e)
            false
        } finally {
            socket?.let { activeTransferSocket.compareAndSet(it, null) }
            try { socket?.close() } catch (_: Exception) {}
            try { tempFile?.delete() } catch (_: Exception) {}
        }
    }

    fun cancelTransfer() {
        val socket = activeTransferSocket.getAndSet(null)
        try { socket?.close() } catch (e: Exception) {
            Log.w(tag, "Error cancelling transfer: " + e.message)
        }
    }

    suspend fun stopCasting(ip: String, authToken: String) = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            val stopSocket = createSocket(ip, 5000)
            socket = stopSocket
            stopSocket.soTimeout = 5000
            val output = DataOutputStream(BufferedOutputStream(stopSocket.getOutputStream()))
            output.writeUTF(authToken)
            output.writeUTF("STOP")
            output.writeLong(0L)
            output.flush()
            val response = DataInputStream(BufferedInputStream(stopSocket.getInputStream())).readUTF()
            Log.d(tag, "TV stop command response: " + response)
        } catch (e: Exception) {
            Log.e(tag, "Stop cast error: " + e.message)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
