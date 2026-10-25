package com.bailensn.sort.ocr

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 高精度模型（medium）的下载调度。
 *
 * - 第一次在**前台**下载：用户能看见进度；失败后转**后台**重试，
 *   不影响用内置轻量模型识别；
 * - 后台只在 Wi-Fi 下自动重试，网络恢复时会自动再试；
 * - 状态通过 [state] 暴露，模型状态页实时显示。
 */
object ModelDownloadManager {

    sealed interface State {
        data object Idle : State
        data class Downloading(val percent: Int, val message: String) : State
        data object Ready : State
        data class Failed(val message: String) : State

        /** 有网但当前是移动数据，等 Wi-Fi。 */
        data object WaitingForWifi : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile
    private var callbackRegistered = false
    @Volatile
    private var allowMetered = false

    fun refresh(context: Context) {
        when {
            ModelRepository.isReady(context) -> _state.value = State.Ready
            _state.value is State.Ready -> _state.value = State.Idle
        }
    }

    fun reportDownloading(percent: Int, message: String) {
        _state.value = State.Downloading(percent, message)
    }

    fun reportReady() {
        _state.value = State.Ready
    }

    fun reportFailed(message: String) {
        _state.value = State.Failed(message)
    }

    /** 前台下载结束（成功或失败）后，失败就转后台重试。 */
    fun afterForegroundAttempt(context: Context, success: Boolean, message: String? = null) {
        if (success) {
            _state.value = State.Ready
            return
        }
        _state.value = State.Failed(message ?: "下载失败")
        startBackground(context)
    }

    fun setAllowMetered(value: Boolean) {
        allowMetered = value
    }

    /** 后台下载：只在 Wi-Fi（或用户允许流量）时进行；已有任务则不重复启动。 */
    fun startBackground(context: Context) {
        val app = context.applicationContext
        if (ModelRepository.isReady(app)) {
            _state.value = State.Ready
            return
        }
        if (job?.isActive == true) return
        registerNetworkCallback(app)
        job = scope.launch {
            if (!NetworkStatusHolder.isOnline(app)) {
                _state.value = State.WaitingForWifi
                return@launch
            }
            if (NetworkStatusHolder.isMetered(app) && !allowMetered) {
                _state.value = State.WaitingForWifi
                return@launch
            }
            _state.value = State.Downloading(0, "正在后台下载高精度模型")
            try {
                ModelRepository.ensure(app) { percent, text ->
                    _state.value = State.Downloading(percent, text)
                }
                _state.value = State.Ready
            } catch (t: Throwable) {
                _state.value = State.Failed(t.message ?: "下载失败")
            }
        }
    }

    fun clear(context: Context) {
        job?.cancel()
        job = null
        ModelRepository.clear(context)
        _state.value = State.Idle
    }

    private fun registerNetworkCallback(context: Context) {
        if (callbackRegistered) return
        callbackRegistered = true
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        val current = _state.value
                        if (current is State.WaitingForWifi || current is State.Failed) {
                            startBackground(context)
                        }
                    }
                },
            )
        }
    }

    /** 避免循环依赖：NetworkStatus 在 util 包，这里只做一次转发。 */
    private object NetworkStatusHolder {
        fun isOnline(context: Context) = com.bailensn.sort.util.NetworkStatus.isOnline(context)
        fun isMetered(context: Context) = com.bailensn.sort.util.NetworkStatus.isMetered(context)
    }

    suspend fun downloadForeground(
        context: Context,
        onProgress: (Int, String) -> Unit,
    ): File? = try {
        ModelRepository.ensure(context) { percent, text ->
            reportDownloading(percent, text)
            onProgress(percent, text)
        }.also { reportReady() }
    } catch (t: Throwable) {
        afterForegroundAttempt(context, success = false, message = t.message)
        null
    }
}
