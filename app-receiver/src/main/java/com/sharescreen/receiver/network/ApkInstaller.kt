package com.sharescreen.receiver.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.util.Log
import java.io.File
import java.io.FileInputStream

object ApkInstaller {
    private val TAG = "ApkInstaller"

    fun install(context: Context, apkFile: File) {
        try {
            require(apkFile.isFile && apkFile.canRead() && apkFile.length() > 0L) {
                "APK 文件不存在、不可读或为空"
            }
            val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                ?: throw IllegalArgumentException("文件不是有效的 APK")
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            // The APK file name is user-controlled and is not its Android package name.
            params.setAppPackageName(archiveInfo.packageName)

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            // Write APK data into the session
            val out = session.openWrite("package", 0, apkFile.length())
            FileInputStream(apkFile).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val len = input.read(buffer)
                    if (len < 0) break
                    if (len > 0) out.write(buffer, 0, len)
                }
            }
            session.fsync(out)
            out.close()

            // Create a PendingIntent for the result
            val intent = Intent("com.sharescreen.receiver.INSTALL_RESULT").apply {
                setPackage(context.packageName)
            }
            val pi = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

            session.commit(pi.intentSender)
            session.close()

            Log.d(TAG, "APK install session committed for: ${apkFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK: ${e.message}", e)
        }
    }
}
