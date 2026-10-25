package com.xiaomi.vtvmitv

import android.app.SearchManager
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
import java.text.Normalizer

class MainActivity : ComponentActivity() {

    private companion object {
        const val TAG = "MainActivity"
        const val PREFS_NAME = "vtvmitv_playlist_pref"
        const val KEY_CACHED_M3U = "cached_m3u_content"

        // Đổi link danh sách kênh sang repo của bạn
        const val DEFAULT_M3U_URL = "https://raw.githubusercontent.com/khanhitpro/storetv/refs/heads/main/tv.m3u"

        const val DEFAULT_M3U_BACKUP = """
#EXTM3U
#EXTINF:-1,VTV1
https://vtv1.mediacdn.vn/vtv1hi/playlist.m3u8
#EXTINF:-1,VTV2
https://vtv2.mediacdn.vn/vtv2hi/playlist.m3u8
#EXTINF:-1,VTV3
https://vtv3.mediacdn.vn/vtv3hi/playlist.m3u8
#EXTINF:-1,VTV4
https://vtv4.mediacdn.vn/vtv4hi/playlist.m3u8
#EXTINF:-1,VTV5
https://vtv5.mediacdn.vn/vtv5hi/playlist.m3u8
#EXTINF:-1,VTV6
https://vtv6.mediacdn.vn/vtv6hi/playlist.m3u8
#EXTINF:-1,VTV7
https://vtv7.mediacdn.vn/vtv7hi/playlist.m3u8
#EXTINF:-1,VTV8
https://vtv8.mediacdn.vn/vtv8hi/playlist.m3u8
#EXTINF:-1,VTV9
https://vtv9.mediacdn.vn/vtv9hi/playlist.m3u8
"""
    }

    private var pendingTargetChannelName: String? = null
    private var currentActiveIndex by mutableIntStateOf(0)
    private var channelsState = mutableStateOf<List<Channel>>(emptyList())
    private var isLoadingState = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        parseIntent(intent)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val localCachedM3u = prefs.getString(KEY_CACHED_M3U, null) ?: DEFAULT_M3U_BACKUP

        try {
            val quickChannels = M3UParser.parse(localCachedM3u)
            if (quickChannels.isNotEmpty()) {
                channelsState.value = quickChannels
                isLoadingState.value = false
                applyPendingChannel(quickChannels)
            }
        } catch (_: Throwable) {}

        setContent {
            val channels by channelsState
            val isLoading by isLoadingState

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading && channels.isEmpty()) {
                    CircularProgressIndicator(color = Color(0xFF00E676))
                } else if (channels.isNotEmpty()) {
                    IPTVAppScreen(
                        channels = channels,
                        initialIndex = currentActiveIndex,
                        onChannelChanged = { channel, index ->
                            currentActiveIndex = index
                            try {
                                LauncherChannelBridge.updateWatchNextAsync(this@MainActivity, channel, index)
                            } catch (e: Throwable) {
                                Log.w(TAG, "WatchNext error: ${e.message}")
                            }
                        }
                    )
                }
            }
        }

        syncPlaylistBackground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseIntent(intent)
        val channels = channelsState.value
        if (channels.isNotEmpty()) {
            applyPendingChannel(channels)
        }
    }

    private fun parseIntent(intent: Intent?) {
        if (intent == null) return

        val data: Uri? = intent.data
        if (data != null && data.scheme == "vtvmitv") {
            val name = data.getQueryParameter("name")
            if (!name.isNullOrBlank()) {
                pendingTargetChannelName = name
                return
            }
            val url = data.getQueryParameter("url")
            if (!url.isNullOrBlank()) {
                val matched = channelsState.value.indexOfFirst { it.streamUrls.contains(url) }
                if (matched != -1) {
                    currentActiveIndex = matched
                    return
                }
            }
        }

        val query = intent.getStringExtra(SearchManager.QUERY)
        if (!query.isNullOrBlank()) {
            pendingTargetChannelName = query
        }
    }

    private fun applyPendingChannel(channels: List<Channel>) {
        val target = pendingTargetChannelName ?: return
        val cleanTarget = cleanText(target)

        val foundIndex = channels.indexOfFirst { channel ->
            val cleanName = cleanText(channel.name)
            cleanName == cleanTarget ||
                    cleanName.contains(cleanTarget) ||
                    cleanTarget.contains(cleanName)
        }

        if (foundIndex != -1) {
            currentActiveIndex = foundIndex
            pendingTargetChannelName = null
        }
    }

    private fun cleanText(str: String?): String {
        if (str.isNullOrBlank()) return ""
        return try {
            val nfd = Normalizer.normalize(str, Normalizer.Form.NFD)
            nfd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
                .lowercase()
                .replace(" ", "")
                .trim()
        } catch (_: Throwable) {
            str.lowercase().replace(" ", "").trim()
        }
    }

    private fun syncPlaylistBackground() {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val networkM3u = fetchM3uFromNetwork(DEFAULT_M3U_URL)

            if (!networkM3u.isNullOrBlank()) {
                val newChannels = try {
                    M3UParser.parse(networkM3u)
                } catch (e: Throwable) {
                    emptyList()
                }

                if (newChannels.isNotEmpty()) {
                    prefs.edit().putString(KEY_CACHED_M3U, networkM3u).apply()

                    withContext(Dispatchers.Main) {
                        if (channelsState.value.isEmpty() || channelsState.value.size != newChannels.size) {
                            channelsState.value = newChannels
                            isLoadingState.value = false
                            applyPendingChannel(newChannels)
                        }
                    }

                    try {
                        LauncherChannelBridge.syncChannelsAsync(this@MainActivity, newChannels)
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun fetchM3uFromNetwork(urlString: String): String? {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV; SMART-TV; Linux) AppleWebKit/537.36")
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
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            finishAndRemoveTask()
        }
    }
}