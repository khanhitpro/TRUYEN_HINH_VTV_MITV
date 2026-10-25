package com.xiaomi.vtvmitv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    private companion object {
        const val TAG = "MainActivity"
        const val PREFS_NAME = "vtvmitv_playlist_pref"
        const val KEY_CACHED_M3U = "cached_m3u_content"
        const val DEFAULT_M3U_URL = "https://github.com/hakison/xiaomi/releases/download/khoapp1/oktv.m3u"

        // Playlist dự phòng tĩnh phòng khi TV chưa có mạng lúc mở app
        const val FALLBACK_M3U = """
#EXTM3U
#EXTINF:-1,VTV1
https://vtv1.mediacdn.vn/vtv1hi/playlist.m3u8
#EXTINF:-1,VTV2
https://vtv2.mediacdn.vn/vtv2hi/playlist.m3u8
#EXTINF:-1,VTV3
https://vtv3.mediacdn.vn/vtv3hi/playlist.m3u8
"""
    }

    private var initialChannelIndex by mutableIntStateOf(0)
    private var channelsState = mutableStateOf<List<Channel>>(emptyList())
    private var isLoadingState = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIntent(intent)

        setContent {
            val channels by channelsState
            val isLoading by isLoadingState

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color(0xFFE50914))
                } else if (channels.isNotEmpty()) {
                    IPTVAppScreen(
                        channels = channels,
                        initialIndex = initialChannelIndex,
                        onChannelChanged = { channel, index ->
                            try {
                                LauncherChannelBridge.updateWatchNextAsync(this@MainActivity, channel, index)
                            } catch (e: Throwable) {
                                Log.w(TAG, "WatchNext update failed: ${e.message}")
                            }
                        }
                    )
                }
            }
        }

        loadPlaylistData()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data != null && data.scheme == "vtvmitv") {
            val channelName = data.getQueryParameter("name")
            val channels = channelsState.value
            if (!channelName.isNullOrBlank() && channels.isNotEmpty()) {
                val matchedIndex = channels.indexOfFirst { it.name.equals(channelName, ignoreCase = true) }
                if (matchedIndex != -1) {
                    initialChannelIndex = matchedIndex
                }
            }
        }
    }

    private fun loadPlaylistData() {
        lifecycleScope.launch(Dispatchers.IO) {
            var m3uContent: String? = null
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            try {
                // 1. Thử lấy từ bộ nhớ đệm
                val cached = prefs.getString(KEY_CACHED_M3U, null)
                if (!cached.isNullOrBlank()) {
                    m3uContent = cached
                } else {
                    // 2. Tải qua mạng nếu chưa có cache
                    m3uContent = fetchM3uFromNetwork(DEFAULT_M3U_URL)
                    if (!m3uContent.isNullOrBlank()) {
                        prefs.edit().putString(KEY_CACHED_M3U, m3uContent).apply()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Lỗi tải M3U mạng: ${e.message}")
            }

            // 3. Fallback chống treo nếu không tải được mạng
            if (m3uContent.isNullOrBlank()) {
                m3uContent = FALLBACK_M3U
            }

            val parsedChannels = try {
                M3UParser.parse(m3uContent)
            } catch (e: Throwable) {
                Log.e(TAG, "Lỗi parse M3U", e)
                emptyList()
            }

            withContext(Dispatchers.Main) {
                channelsState.value = parsedChannels
                isLoadingState.value = false
                handleIntent(intent)
            }

            // 4. Đồng bộ 9 hàng kênh ra màn hình chính TV
            if (parsedChannels.isNotEmpty()) {
                try {
                    LauncherChannelBridge.syncChannelsAsync(this@MainActivity, parsedChannels)
                } catch (e: Throwable) {
                    Log.w(TAG, "Sync launcher failed: ${e.message}")
                }
            }
        }
    }

    private fun fetchM3uFromNetwork(urlString: String): String? {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.connect()

            if (connection.responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line).append("\n")
                }
                reader.close()
                sb.toString()
            } else {
                null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Không thể tải M3U: ${e.message}")
            null
        }
    }

    // Xử lý nút Back thoát app dứt điểm
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }

    // Đảm bảo dừng luồng hoàn toàn khi Activity bị đưa xuống nền
    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            finishAndRemoveTask()
        }
    }
}