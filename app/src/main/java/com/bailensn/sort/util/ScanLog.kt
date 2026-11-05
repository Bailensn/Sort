package com.bailensn.sort.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 两级日志：d/w/e 只进 logcat（模型下载这类后台琐事）；
 * scan/scanError 同时进界面，专门给「扫描中途」看。
 */
object ScanLog {

    private const val TAG = "Sort"
    private const val MAX_LINES = 120

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun d(message: String) = logcat("D", message, null)
    fun w(message: String) = logcat("W", message, null)
    fun e(message: String, error: Throwable? = null) = logcat("E", message, error)

    /** 扫描过程的步骤：logcat 和界面都要看到。 */
    fun scan(message: String) = record("D", message, null)

    fun scanError(message: String, error: Throwable? = null) = record("E", message, error)

    fun clear() {
        _lines.value = emptyList()
    }

    private fun logcat(level: String, message: String, error: Throwable?) {
        when (level) {
            "D" -> Log.d(TAG, message)
            "W" -> Log.w(TAG, message)
            else -> Log.e(TAG, message, error)
        }
    }

    private fun record(level: String, message: String, error: Throwable?) {
        logcat(level, message, error)
        val line = "${clock.format(Date())} $message"
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }
}
