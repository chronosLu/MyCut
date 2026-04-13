package com.mycut.app

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class LogViewerActivity : AppCompatActivity() {
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)
        logText = findViewById(R.id.logText)
        val refreshButton: MaterialButton = findViewById(R.id.refreshLogButton)
        val exportButton: MaterialButton = findViewById(R.id.exportLogButton)
        val clearButton: MaterialButton = findViewById(R.id.clearLogButton)
        refreshButton.setOnClickListener { refreshLog() }
        exportButton.setOnClickListener { exportLogs() }
        clearButton.setOnClickListener { clearLogs() }
        refreshLog()
    }

    private fun refreshLog() {
        logText.text = AppLog.readForDisplay(this)
    }

    private fun exportLogs() {
        val shareIntent = AppLog.createShareIntent(this)
        if (shareIntent == null) {
            Toast.makeText(this, "当前没有可导出的日志", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent.createChooser(shareIntent, "导出日志"))
    }

    private fun clearLogs() {
        AppLog.clear(this)
        refreshLog()
        Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
    }
}
