package com.sharescreen.receiver.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sharescreen.receiver.R
import java.io.File

class CacheManagerActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var btnClearAll: Button
    private lateinit var adapter: CacheFileAdapter
    private var fileList = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cache_manager)

        recyclerView = findViewById(R.id.recyclerView)
        btnClearAll = findViewById(R.id.btnClearAll)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.clipChildren = false
        recyclerView.clipToPadding = false
        adapter = CacheFileAdapter(fileList) { file ->
            showFileOptionsDialog(file)
        }
        recyclerView.adapter = adapter

        btnClearAll.setOnClickListener {
            showClearAllDialog()
        }

        btnClearAll.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.04f).scaleY(1.04f).translationZ(12f).setDuration(120).start()
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(120).start()
            }
        }

        loadFiles()
    }

    private fun loadFiles() {
        val cacheDir = cacheDir
        fileList.clear()
        if (cacheDir.exists() && cacheDir.isDirectory) {
            val files = cacheDir.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.isFile) {
                        fileList.add(file)
                    }
                }
            }
        }
        fileList.sortByDescending { it.lastModified() }
        adapter.notifyDataSetChanged()
    }

    private fun showFileOptionsDialog(file: File) {
        val nameLower = file.name.lowercase()
        val isApk = nameLower.endsWith(".apk")
        val isMedia = nameLower.endsWith(".mp4") || nameLower.endsWith(".mkv") || nameLower.endsWith(".avi") ||
                nameLower.endsWith(".jpg") || nameLower.endsWith(".jpeg") || nameLower.endsWith(".png") || nameLower.endsWith(".webp")

        val options = mutableListOf<String>()
        if (isApk) options.add("安装应用")
        if (isMedia) options.add("播放/查看")
        options.add("删除文件")

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "安装应用" -> {
                        Toast.makeText(this, "正在启动安装程序...", Toast.LENGTH_SHORT).show()
                        com.sharescreen.receiver.network.ApkInstaller.install(this, file)
                    }
                    "播放/查看" -> {
                        val intent = android.content.Intent(this, com.sharescreen.receiver.MediaPlaybackActivity::class.java)
                        intent.putExtra(com.sharescreen.receiver.MediaPlaybackActivity.EXTRA_FILE_PATH, file.absolutePath)
                        startActivity(intent)
                    }
                    "删除文件" -> showDeleteDialog(file)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteDialog(file: File) {
        AlertDialog.Builder(this)
            .setTitle("删除文件")
            .setMessage("确定要删除文件 ${file.name} 吗？")
            .setPositiveButton("删除") { _, _ ->
                if (file.delete()) {
                    Toast.makeText(this, "删除成功", Toast.LENGTH_SHORT).show()
                    loadFiles()
                } else {
                    Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearAllDialog() {
        if (fileList.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("一键清空")
            .setMessage("确定要清空所有缓存文件吗？(共 ${fileList.size} 个)")
            .setPositiveButton("清空") { _, _ ->
                var count = 0
                for (file in fileList) {
                    if (file.delete()) {
                        count++
                    }
                }
                Toast.makeText(this, "成功删除 $count 个文件", Toast.LENGTH_SHORT).show()
                loadFiles()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class CacheFileAdapter(
        private val files: List<File>,
        private val onItemClick: (File) -> Unit
    ) : RecyclerView.Adapter<CacheFileAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvFileName: TextView = view.findViewById(R.id.tvFileName)
            val tvFileSize: TextView = view.findViewById(R.id.tvFileSize)

            init {
                view.setOnClickListener {
                    onItemClick(files[adapterPosition])
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_cache_file, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = files[position]
            holder.tvFileName.text = file.name
            holder.tvFileSize.text = Formatter.formatFileSize(this@CacheManagerActivity, file.length())
            
            // TV focus animation
            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.02f).scaleY(1.02f).translationZ(12f).setDuration(120).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(120).start()
                }
            }
        }

        override fun getItemCount() = files.size
    }
}
