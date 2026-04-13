package com.mycut.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val CRASH_PREFIX = "crash_"
    private const val RUNTIME_FILE = "runtime.log"

    fun init(context: Context) {
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrash(context, throwable)
            oldHandler?.uncaughtException(thread, throwable)
        }
    }

    fun write(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        val file = File(logDir(context), RUNTIME_FILE)
        val line = buildString {
            append(time())
            append(" [")
            append(tag)
            append("] ")
            append(message)
            if (throwable != null) {
                append('\n')
                append(throwable.stackTraceToString())
            }
            append('\n')
        }
        file.appendText(line)
    }

    fun exportUri(context: Context): Uri? {
        val logDir = logDir(context)
        if (!logDir.exists()) return null
        val exportFile = File(logDir, "export_${stamp()}.txt")
        val content = buildString {
            val runtime = File(logDir, RUNTIME_FILE)
            if (runtime.exists()) {
                append("===== runtime.log =====\n")
                append(runtime.readText())
                append('\n')
            }
            logDir.listFiles()
                ?.filter { it.name.startsWith(CRASH_PREFIX) }
                ?.sortedByDescending { it.lastModified() }
                ?.take(3)
                ?.forEach { file ->
                    append("===== ${file.name} =====\n")
                    append(file.readText())
                    append('\n')
                }
        }
        if (content.isBlank()) return null
        exportFile.writeText(content)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile
        )
    }

    fun createShareIntent(context: Context): Intent? {
        val uri = exportUri(context) ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun readForDisplay(context: Context): String {
        val logDir = logDir(context)
        val content = buildString {
            val runtime = File(logDir, RUNTIME_FILE)
            if (runtime.exists()) {
                append("===== runtime.log =====\n")
                append(runtime.readText())
                append('\n')
            }
            logDir.listFiles()
                ?.filter { it.name.startsWith(CRASH_PREFIX) }
                ?.sortedByDescending { it.lastModified() }
                ?.take(5)
                ?.forEach { file ->
                    append("===== ${file.name} =====\n")
                    append(file.readText())
                    append('\n')
                }
        }
        return if (content.isBlank()) "当前暂无日志" else content
    }

    fun clear(context: Context) {
        val logDir = logDir(context)
        runCatching {
            File(logDir, RUNTIME_FILE).takeIf { it.exists() }?.delete()
            logDir.listFiles()
                ?.filter { it.name.startsWith(CRASH_PREFIX) || it.name.startsWith("export_") }
                ?.forEach { it.delete() }
        }
    }

    private fun writeCrash(context: Context, throwable: Throwable) {
        val file = File(logDir(context), "${CRASH_PREFIX}${stamp()}.log")
        file.writeText("${time()}\n${throwable.stackTraceToString()}\n")
    }

    private fun logDir(context: Context): File {
        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private fun time(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
