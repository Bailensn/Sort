package com.bailensn.sort.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 识别过程的滚动日志：一边写 logcat，一边留给界面显示最近几十行。 */
object ScanLog {

    private const val TAG = "Sort"
    private const val MAX_LINES = 120

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun d(message: String) = append("D", message, null)
    fun w(message: String) = append("W", message, null)
    fun e(message: String, error: Throwable? = null) = append("E", message, error)

    fun clear() {
        _lines.value = emptyList()
    }

    private fun append(level: String, message: String, error: Throwable?) {
        when (level) {
            "D" -> Log.d(TAG, message)
            "W" -> Log.w(TAG, message)
            else -> Log.e(TAG, message, error)
        }
        val line = "${clock.format(Date())} $level $message"
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }
}
