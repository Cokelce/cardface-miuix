package com.dsh.miuix.cardface.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** 当前是否走 Wi-Fi / 以太网（离线时按非 Wi-Fi 处理，省流量模式用缩略图）。 */
object NetworkStatus {

    fun isWifi(context: Context): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    } catch (_: Throwable) {
        false
    }
}
