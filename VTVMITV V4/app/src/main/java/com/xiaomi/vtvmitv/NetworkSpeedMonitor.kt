package com.xiaomi.vtvmitv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

data class NetworkInfoState(
    val speedText: String = "0 KB/s",
    val connectionType: String = "Đang kiểm tra...",
    val isGoodQuality: Boolean = true
)

object NetworkSpeedMonitor {

    fun observeNetworkSpeed(context: Context) = flow {
        var lastTotalRxBytes = TrafficStats.getTotalRxBytes()
        var lastTimeStamp = System.currentTimeMillis()

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        while (true) {
            delay(1000)

            val currentTotalRxBytes = TrafficStats.getTotalRxBytes()
            val currentTimeStamp = System.currentTimeMillis()

            val bytesDelta = currentTotalRxBytes - lastTotalRxBytes
            val timeDelta = (currentTimeStamp - lastTimeStamp).coerceAtLeast(1)

            val speedBytesPerSec = if (bytesDelta > 0) (bytesDelta * 1000) / timeDelta else 0

            val speedFormatted = when {
                speedBytesPerSec >= 1024 * 1024 -> {
                    val mb = speedBytesPerSec.toFloat() / (1024 * 1024)
                    String.format("%.1f MB/s", mb)
                }
                else -> {
                    val kb = speedBytesPerSec / 1024
                    "$kb KB/s"
                }
            }

            val activeNetwork = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNetwork)
            val typeText = when {
                caps == null -> "Mất mạng"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "LAN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                else -> "Mạng TV"
            }

            val isGood = speedBytesPerSec >= 800 * 1024 || (speedBytesPerSec > 100 * 1024 && caps != null)

            emit(
                NetworkInfoState(
                    speedText = speedFormatted,
                    connectionType = typeText,
                    isGoodQuality = isGood
                )
            )

            lastTotalRxBytes = currentTotalRxBytes
            lastTimeStamp = currentTimeStamp
        }
    }
}