package com.logcat.viewer

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var adapter: AppLogAdapter

    private val appLogs = mutableMapOf<String, MutableList<LogEntry>>()
    private var displayList = mutableListOf<Pair<String, MutableList<LogEntry>>>()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var logcatProcess: Process? = null
    private var currentFilter = ""

    data class LogEntry(
        val timestamp: String,
        val level: String,
        val tag: String,
        val message: String,
        val raw: String,
        val time: Long = System.currentTimeMillis()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        searchEdit = findViewById(R.id.searchEdit)
        statusText = findViewById(R.id.statusText)

        adapter = AppLogAdapter()
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.adapter = adapter

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentFilter = s?.toString() ?: ""
                updateDisplayList()
            }
        })

        if (!checkLogPermission()) {
            showPermissionDialog()
        } else {
            startLogcat()
        }
    }

    private fun updateDisplayList() {
        displayList.clear()
        val query = currentFilter.lowercase()

        synchronized(appLogs) {
            if (query.isEmpty()) {
                displayList.addAll(appLogs.map { it.key to it.value })
            } else {
                displayList.addAll(appLogs.filter { (pkg, logs) ->
                    pkg.lowercase().contains(query) ||
                    logs.any { it.raw.lowercase().contains(query) }
                }.map { it.key to it.value })
            }
        }

        // Sort by most recent activity
        displayList.sortByDescending { it.second.lastOrNull()?.time ?: 0 }
        adapter.notifyDataSetChanged()
    }

    private fun checkLogPermission(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("logcat -d -t 1")
            val reader = process.inputStream.bufferedReader()
            val line = reader.readLine()
            process.destroy()
            line != null && !line.contains("Permission denied")
        } catch (e: Exception) {
            false
        }
    }

    private fun showPermissionDialog() {
        val cmd = "adb shell pm grant ${packageName} android.permission.READ_LOGS"

        AlertDialog.Builder(this)
            .setTitle("Grant Permission")
            .setMessage("Run this command via ADB:\n\n$cmd\n\nThen restart the app.")
            .setPositiveButton("Copy Command") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ADB Command", cmd))
                Toast.makeText(this, "Command copied!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Try Anyway") { _, _ ->
                startLogcat()
            }
            .setCancelable(false)
            .show()
    }

    private fun startLogcat() {
        statusText.text = "Starting fresh..."

        scope.launch(Dispatchers.IO) {
            try {
                // Clear old logs - start fresh
                Runtime.getRuntime().exec("logcat -c").waitFor()

                // Start reading live logs only
                logcatProcess = Runtime.getRuntime().exec("logcat -v threadtime")
                val reader = logcatProcess!!.inputStream.bufferedReader()

                var lineCount = 0
                var lastUpdate = System.currentTimeMillis()

                reader.forEachLine { line ->
                    parseLogLine(line)?.let { entry ->
                        synchronized(appLogs) {
                            val pkg = extractPackage(entry.tag)
                            appLogs.getOrPut(pkg) { mutableListOf() }.add(entry)
                            // Keep only last 50 entries per app
                            if (appLogs[pkg]!!.size > 50) {
                                appLogs[pkg]!!.removeAt(0)
                            }
                        }
                        lineCount++

                        // Update UI frequently
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 200) {
                            lastUpdate = now
                            launch(Dispatchers.Main) {
                                statusText.text = "Live | ${appLogs.size} apps | $lineCount logs"
                                updateDisplayList()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    statusText.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun parseLogLine(line: String): LogEntry? {
        val regex = """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+\d+\s+\d+\s+([VDIWEF])\s+([^:]+):\s*(.*)$""".toRegex()
        val match = regex.find(line) ?: return null

        return LogEntry(
            timestamp = match.groupValues[1],
            level = match.groupValues[2],
            tag = match.groupValues[3].trim(),
            message = match.groupValues[4],
            raw = line
        )
    }

    private fun extractPackage(tag: String): String {
        return when {
            tag.contains("ActivityManager") -> "Activity"
            tag.contains("WindowManager") -> "Window"
            tag.contains("AudioFlinger") || tag.contains("AudioOut") || tag.contains("AudioALSA") -> "Audio"
            tag.contains("MediaPlayer") || tag.contains("MediaSession") || tag.contains("NuPlayer") -> "Media"
            tag.contains("Wifi") || tag.contains("wifi") || tag.contains("WifiNetworkSelector") -> "WiFi"
            tag.contains("Bluetooth") || tag.contains("bt_") -> "Bluetooth"
            tag.contains("Power") || tag.contains("Battery") -> "Power"
            tag.contains("radiogarden") -> "Radio Garden"
            tag.contains("StatusBar") -> "StatusBar"
            tag.contains("BufferQueue") -> "Graphics"
            tag.contains("chatty") -> "System"
            tag.contains("vendor.mediatek") -> "MTK"
            tag.startsWith("com.") -> {
                val parts = tag.substringBefore("/").split(".")
                if (parts.size >= 3) parts.takeLast(2).joinToString(".") else tag.take(15)
            }
            else -> tag.take(12)
        }
    }

    private fun showAppLogs(pkg: String, logs: List<LogEntry>) {
        val view = layoutInflater.inflate(R.layout.dialog_logs, null)
        val listView = view.findViewById<ListView>(R.id.logListView)
        val searchLog = view.findViewById<EditText>(R.id.searchLogEdit)

        var filteredLogs = logs.sortedByDescending { it.time }.toMutableList()
        val displayLogs = filteredLogs.map { "[${it.level}] ${it.message}".take(80) }.toMutableList()
        val logAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayLogs)
        listView.adapter = logAdapter

        searchLog.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.lowercase() ?: ""
                filteredLogs = if (query.isEmpty()) logs.sortedByDescending { it.time }.toMutableList()
                    else logs.filter { it.raw.lowercase().contains(query) }.sortedByDescending { it.time }.toMutableList()
                logAdapter.clear()
                logAdapter.addAll(filteredLogs.map { "[${it.level}] ${it.message}".take(80) })
            }
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position < filteredLogs.size) {
                val entry = filteredLogs[position]
                AlertDialog.Builder(this)
                    .setTitle("${entry.level}: ${entry.tag}")
                    .setMessage("${entry.timestamp}\n\n${entry.message}")
                    .setPositiveButton("Copy") { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Log", entry.raw))
                        Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }

        AlertDialog.Builder(this)
            .setTitle("$pkg (${logs.size} logs)")
            .setView(view)
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        logcatProcess?.destroy()
    }

    inner class AppLogAdapter : RecyclerView.Adapter<AppLogAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val appName: TextView = view.findViewById(R.id.appName)
            val logCount: TextView = view.findViewById(R.id.logCount)
            val lastLog: TextView = view.findViewById(R.id.lastLog)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val (pkg, logs) = displayList[position]
            val last = logs.lastOrNull()

            holder.appName.text = pkg
            holder.logCount.text = "${logs.size}"
            holder.lastLog.text = last?.message?.take(40) ?: ""

            holder.itemView.setOnClickListener {
                showAppLogs(pkg, logs.toList())
            }
        }

        override fun getItemCount() = displayList.size
    }
}
