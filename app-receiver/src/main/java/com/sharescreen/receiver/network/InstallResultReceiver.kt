package com.sharescreen.receiver.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.d("InstallResult", "status=$status, message=$message")

        when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, "APK 安装成功！", Toast.LENGTH_LONG).show()
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // On Android 12+ the user needs to confirm in the system UI
                val userActionIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (userActionIntent != null) {
                    userActionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(userActionIntent)
                }
            }
            else ->
                Toast.makeText(context, "APK 安装失败: $message", Toast.LENGTH_LONG).show()
        }
    }
}
