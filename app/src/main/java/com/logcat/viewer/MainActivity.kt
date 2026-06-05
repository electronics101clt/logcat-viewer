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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.io.BufferedReader

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var adapter: AppLogAdapter

    private val appLogs = mutableMapOf<String, MutableList<LogEntry>>()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var logcatProcess: Process? = null

    data class LogEntry(
        val timestamp: String,
        val level: String,
        val tag: String,
        val message: String,
        val raw: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        searchEdit = findViewById(R.id.searchEdit)
        statusText = findViewById(R.id.statusText)

        adapter = AppLogAdapter(appLogs) { pkg, logs ->
            showAppLogs(pkg, logs)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString() ?: "")
            }
        })

        if (!checkLogPermission()) {
            showPermissionDialog()
        } else {
            startLogcat()
        }
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
        statusText.text = "Reading logcat..."

        scope.launch(Dispatchers.IO) {
            try {
                // Clear old logs first
                Runtime.getRuntime().exec("logcat -c").waitFor()

                logcatProcess = Runtime.getRuntime().exec("logcat -v threadtime")
                val reader = logcatProcess!!.inputStream.bufferedReader()

                var lineCount = 0
                reader.forEachLine { line ->
                    parseLogLine(line)?.let { entry ->
                        synchronized(appLogs) {
                            val pkg = extractPackage(entry.tag)
                            appLogs.getOrPut(pkg) { mutableListOf() }.add(entry)
                            // Keep only last 100 entries per app
                            if (appLogs[pkg]!!.size > 100) {
                                appLogs[pkg]!!.removeAt(0)
                            }
                        }
                        lineCount++
                        if (lineCount % 10 == 0) {
                            launch(Dispatchers.Main) {
                                statusText.text = "Apps: ${appLogs.size} | Lines: $lineCount"
                                adapter.notifyDataSetChanged()
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
        // Format: 06-04 23:24:24.056 pid tid level tag: message
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
        // Common patterns to identify apps
        return when {
            tag.contains("ActivityManager") -> "System: Activity"
            tag.contains("WindowManager") -> "System: Window"
            tag.contains("AudioFlinger") -> "System: Audio"
            tag.contains("MediaPlayer") -> "Media: Player"
            tag.contains("MediaSession") -> "Media: Session"
            tag.contains("Wifi") || tag.contains("wifi") -> "System: WiFi"
            tag.contains("Bluetooth") || tag.contains("bt_") -> "System: Bluetooth"
            tag.contains("Power") || tag.contains("Battery") -> "System: Power"
            tag.startsWith("com.") -> tag.substringBefore("/").substringBeforeLast(".")
            else -> tag.take(20)
        }
    }

    private fun showAppLogs(pkg: String, logs: List<LogEntry>) {
        val view = layoutInflater.inflate(R.layout.dialog_logs, null)
        val listView = view.findViewById<ListView>(R.id.logListView)
        val searchLog = view.findViewById<EditText>(R.id.searchLogEdit)

        val allLogs = logs.toMutableList()
        val logAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            allLogs.map { "${it.timestamp} [${it.level}] ${it.message}".take(100) })
        listView.adapter = logAdapter

        searchLog.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.lowercase() ?: ""
                val filtered = if (query.isEmpty()) allLogs
                    else allLogs.filter { it.raw.lowercase().contains(query) }
                logAdapter.clear()
                logAdapter.addAll(filtered.map { "${it.timestamp} [${it.level}] ${it.message}".take(100) })
            }
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = allLogs[position]
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

        AlertDialog.Builder(this)
            .setTitle(pkg)
            .setView(view)
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        logcatProcess?.destroy()
    }

    inner class AppLogAdapter(
        private val data: MutableMap<String, MutableList<LogEntry>>,
        private val onClick: (String, List<LogEntry>) -> Unit
    ) : RecyclerView.Adapter<AppLogAdapter.ViewHolder>() {

        private var filteredKeys = data.keys.toList()

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
            val pkg = filteredKeys[position]
            val logs = data[pkg] ?: emptyList()

            holder.appName.text = pkg
            holder.logCount.text = "${logs.size} logs"
            holder.lastLog.text = logs.lastOrNull()?.message?.take(50) ?: ""

            holder.itemView.setOnClickListener {
                onClick(pkg, logs)
            }
        }

        override fun getItemCount() = filteredKeys.size

        fun filter(query: String) {
            filteredKeys = if (query.isEmpty()) {
                data.keys.toList()
            } else {
                data.keys.filter { pkg ->
                    pkg.lowercase().contains(query.lowercase()) ||
                    data[pkg]?.any { it.raw.lowercase().contains(query.lowercase()) } == true
                }
            }
            notifyDataSetChanged()
        }
    }
}
