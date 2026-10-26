package com.xiaomi.vtvmitv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

object YouTubeLauncher {

    private const val TAG = "YouTubeLauncher"

    /**
     * Dừng IPTV ngầm và mở ứng dụng YouTube/SmartTube TV phù hợp
     */
    fun open(context: Context, searchQuery: String?) {
        val trimmed = searchQuery?.trim() ?: ""

        // Dọn dẹp từ khóa gọi lệnh trước khi gửi tìm kiếm
        val ytQuery = trimmed.replace("(?i)youtube|mở youtube|bật youtube|trên youtube|xem youtube".toRegex(), "").trim()
        val query = if (ytQuery.isBlank()) trimmed else ytQuery

        // 1. Dừng phát IPTV
        try {
            IPTVPlayerController.stopAndRelease()
        } catch (_: Throwable) {}

        try {
            val stopIntent = Intent(MainActivity.ACTION_STOP_PLAYER).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(stopIntent)
        } catch (_: Throwable) {}

        // 2. Thử mở YouTube TV chính thức
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                setPackage("com.google.android.youtube.tv")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        } catch (_: Throwable) {}

        // 3. Thử mở SmartTube
        try {
            val smartTubeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).apply {
                setPackage("com.teamsmart.videomanager.tv")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (smartTubeIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(smartTubeIntent)
                return
            }
        } catch (_: Throwable) {}

        // 4. Dự phòng qua deep link hệ thống
        try {
            val deepLink = if (query.isNotEmpty()) {
                "vnd.youtube.launch://results?search_query=${Uri.encode(query)}"
            } else {
                "vnd.youtube.launch://"
            }
            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(viewIntent)
        } catch (e: Throwable) {
            Log.e(TAG, "Không thể khởi chạy YouTube: ${e.message}")
        }
    }
}