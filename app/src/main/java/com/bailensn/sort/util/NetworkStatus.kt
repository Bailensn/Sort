package com.bailensn.sort.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** 下载模型前判断当前网络：没网直接报错，走流量要先问一句。 */
object NetworkStatus {

    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 流量、计费热点都算 metered；Wi-Fi 一般不算。 */
    fun isMetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return try {
            manager.isActiveNetworkMetered
        } catch (t: Throwable) {
            true
        }
    }
}
