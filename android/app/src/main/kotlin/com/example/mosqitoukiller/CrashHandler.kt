package com.example.mosqitoukiller

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "CrashHandler"
    private const val LOG_DIR = "mosqitoukiller_crashes"
    private const val MAX_LOG_FILES = 5
    private const val MAX_LOG_AGE_DAYS = 7

    private lateinit var context: Context
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        cleanupOldLogs()
        Log.i(TAG, "CrashHandler initialized")
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        Log.e(TAG, "Uncaught exception on thread: ${t.name}", e)
        saveCrashLog(e)
        defaultHandler?.uncaughtException(t, e)
    }

    fun recordHandledException(e: Throwable, source: String = "unknown") {
        Log.e(TAG, "Handled exception from: $source", e)
        saveCrashLog(e, isUncaught = false, source = source)
    }

    private fun saveCrashLog(e: Throwable, isUncaught: Boolean = true, source: String = "unknown") {
        try {
            val logFile = getLogFile()
            val writer = FileWriter(logFile, true)
            writer.apply {
                write("=".repeat(80))
                write("\n")
                write("CRASH_TIME: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date())}\n")
                write("CRASH_TYPE: ${if (isUncaught) "UNCAUGHT" else "HANDLED"}\n")
                write("SOURCE: $source\n")
                write("PACKAGE_NAME: ${context.packageName}\n")
                write("VERSION_NAME: ${getVersionName()}\n")
                write("VERSION_CODE: ${getVersionCode()}\n")
                write("ANDROID_VERSION: ${Build.VERSION.RELEASE}\n")
                write("API_LEVEL: ${Build.VERSION.SDK_INT}\n")
                write("DEVICE_MODEL: ${Build.MODEL}\n")
                write("MANUFACTURER: ${Build.MANUFACTURER}\n")
                write("PROCESS_ID: ${Process.myPid()}\n")
                write("THREAD_NAME: ${Thread.currentThread().name}\n")
                write("\n")
                write("STACK_TRACE:\n")
                write(getStackTraceString(e))
                write("\n")
                write("-".repeat(80))
                write("\n\n")
                flush()
                close()
            }
            Log.i(TAG, "Crash log saved to: ${logFile.absolutePath}")
        } catch (ex: Throwable) {
            Log.e(TAG, "Failed to save crash log", ex)
        }
    }

    fun getLatestCrashLog(): String {
        val dir = File(context.cacheDir, LOG_DIR)
        if (!dir.exists()) return "No crash logs found"
        val files = dir.listFiles { _, name -> name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
        if (files.isNullOrEmpty()) return "No crash logs found"
        return files[0].readText()
    }

    fun clearCrashLogs() {
        try {
            val dir = File(context.cacheDir, LOG_DIR)
            if (!dir.exists()) return
            dir.listFiles { _, name -> name.endsWith(".log") }?.forEach { it.delete() }
            Log.i(TAG, "All crash logs cleared")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to clear crash logs", e)
        }
    }

    private fun getLogFile(): File {
        val dir = File(context.cacheDir, LOG_DIR)
        if (!dir.exists()) dir.mkdirs()
        // Ensure we work with a List<File> to avoid type ambiguity with Array and overloads like last()
        val files = dir.listFiles { _, name -> name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList<File>()
        // Safely delete the oldest file if we exceeded the max
        if (files.size >= MAX_LOG_FILES) {
            files.lastOrNull()?.delete()
        }
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        return File(dir, "crash_$dateStr.log")
    }

    private fun cleanupOldLogs() {
        try {
            val dir = File(context.cacheDir, LOG_DIR)
            if (!dir.exists()) return
            val cutoff = System.currentTimeMillis() - MAX_LOG_AGE_DAYS * 24 * 60 * 60 * 1000L
            dir.listFiles { _, name -> name.endsWith(".log") }?.forEach {
                if (it.lastModified() < cutoff) {
                    it.delete()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to cleanup old logs", e)
        }
    }

    private fun getStackTraceString(e: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        e.printStackTrace(pw)
        pw.close()
        return sw.toString()
    }

    private fun getVersionName(): String {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            info.versionName ?: "unknown"
        } catch (e: Throwable) {
            "unknown"
        }
    }

    private fun getVersionCode(): String {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toString()
            }
        } catch (e: Throwable) {
            "unknown"
        }
    }
}
