package com.xiaomi.vtvmitv

import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    companion object {
        const val TAG = "MainActivity"
        const val PREFS_NAME = "vtvmitv_playlist_pref"
        const val KEY_CACHED_M3U = "cached_m3u_content"
        const val ACTION_STOP_PLAYER = "com.xiaomi.vtvmitv.ACTION_STOP_PLAYER"
        const val ACTION_VOICE_SWITCH = "com.xiaomi.vtvmitv.ACTION_VOICE_SWITCH"

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
        // Đánh dấu chính xác trạng thái hiển thị của app trên màn hình
        @Volatile
        var isForeground = false

        // Instance phục vụ gọi chuyển kênh trực tiếp từ Voice
        var activeInstance: MainActivity? = null

        fun switchChannelFromVoice(targetIndex: Int): Boolean {
            val activity = activeInstance ?: return false
            activity.runOnUiThread {
                activity.performDirectSwitch(targetIndex)
            }
            return true
        }
    }

    private var pendingTargetIndex: Int? = null
    private var pendingTargetChannelName: String? = null
    private var currentActiveIndex by mutableIntStateOf(0)
    private var channelsState = mutableStateOf<List<Channel>>(emptyList())
    private var isLoadingState = mutableStateOf(true)

    // Nhận lệnh chuyển kênh khi đang xem trực tiếp
    private val voiceCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_VOICE_SWITCH) {
                val index = intent.getIntExtra("target_index", -1)
                if (index != -1) {
                    performDirectSwitch(index)
                }
            }
        }
    }

    private val stopPlayerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            IPTVPlayerController.stopAndRelease()
            channelsState.value = emptyList()
            moveTaskToBack(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance = this

        handleIncomingIntent(intent)
        val initialChannels = loadInitialPlaylist()

        val targetIdx = pendingTargetIndex
        if (targetIdx != null && initialChannels.isNotEmpty() && targetIdx in initialChannels.indices) {
            currentActiveIndex = targetIdx
        }

        setContent {
            val channels by channelsState
            val isLoading by isLoadingState
            val activeIdx = currentActiveIndex

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading && channels.isEmpty()) {
                    CircularProgressIndicator(color = Color(0xFF00E676))
                } else if (channels.isNotEmpty()) {
                    key(activeIdx) {
                        IPTVAppScreen(
                            channels = channels,
                            initialIndex = activeIdx,
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
        }

        syncPlaylistBackground()
    }

    // Chuyển kênh đồng bộ cho cả Compose UI và ExoPlayer Controller
    fun performDirectSwitch(targetIndex: Int) {
        val channels = channelsState.value
        if (channels.isNotEmpty() && targetIndex in channels.indices) {
            currentActiveIndex = targetIndex
            IPTVPlayerController.switchChannelDirectly(targetIndex)
            try {
                LauncherChannelBridge.updateWatchNextAsync(this, channels[targetIndex], targetIndex)
            } catch (_: Throwable) {}
        }
    }

    private fun loadInitialPlaylist(): List<Channel> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val localCachedM3u = prefs.getString(KEY_CACHED_M3U, null) ?: DEFAULT_M3U_BACKUP

        return try {
            val quickChannels = M3UParser.parse(localCachedM3u)
            if (quickChannels.isNotEmpty()) {
                channelsState.value = quickChannels
                isLoadingState.value = false
                applyPendingChannel(quickChannels)
            }
            quickChannels
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override fun onStart() {
        super.onStart()
        activeInstance = this
        val stopFilter = IntentFilter(ACTION_STOP_PLAYER)
        val voiceFilter = IntentFilter(ACTION_VOICE_SWITCH)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopPlayerReceiver, stopFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(voiceCommandReceiver, voiceFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopPlayerReceiver, stopFilter)
            registerReceiver(voiceCommandReceiver, voiceFilter)
        }
    }

    override fun onResume() {
        super.onResume()
        activeInstance = this
        isForeground = true
        if (channelsState.value.isEmpty()) {
            loadInitialPlaylist()
        }
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activeInstance = this
        handleIncomingIntent(intent)

        var channels = channelsState.value
        if (channels.isEmpty()) {
            channels = loadInitialPlaylist()
        }

        lifecycleScope.launch(Dispatchers.Main) {
            delay(120)
            if (channels.isNotEmpty()) {
                applyPendingChannel(channels)
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        val data: Uri? = intent.data
        if (data != null && data.scheme == "vtvmitv") {
            val indexParam = data.getQueryParameter("index")
            if (!indexParam.isNullOrBlank()) {
                val idx = indexParam.toIntOrNull()
                if (idx != null && idx >= 0) {
                    pendingTargetIndex = idx
                    pendingTargetChannelName = null
                    return
                }
            }

            val name = data.getQueryParameter("name")
            if (!name.isNullOrBlank()) {
                pendingTargetChannelName = name
                pendingTargetIndex = null
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

            val pathSegment = data.lastPathSegment
            if (!pathSegment.isNullOrBlank() && pathSegment != "play") {
                pendingTargetChannelName = pathSegment
                pendingTargetIndex = null
                return
            }
        }

        if (pendingTargetIndex == null) {
            val query = intent.getStringExtra(SearchManager.QUERY)
                ?: intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE)
                ?: intent.getStringExtra("android.speech.extra.QUERY")
                ?: intent.getStringExtra(SearchManager.EXTRA_DATA_KEY)
                ?: intent.getStringExtra("query")

            if (!query.isNullOrBlank()) {
                pendingTargetChannelName = query
                pendingTargetIndex = null
            }
        }
    }

    // Tận dụng module VoiceMatcher và YouTubeLauncher đã tách riêng
    private fun applyPendingChannel(channels: List<Channel>) {
        if (channels.isEmpty()) return

        // 1. Nếu có sẵn Index từ deep-link
        val targetIdx = pendingTargetIndex
        if (targetIdx != null) {
            if (targetIdx in channels.indices) {
                performDirectSwitch(targetIdx)
            }
            pendingTargetIndex = null
            pendingTargetChannelName = null
            return
        }

        val rawTarget = pendingTargetChannelName ?: return
        val trimmed = rawTarget.trim()
        val lower = trimmed.lowercase()

        // 2. Bóc tách số kênh qua VoiceMatcher
        val extractedNumber = VoiceMatcher.extractChannelNumber(trimmed)
        if (extractedNumber != null) {
            val calcIndex = extractedNumber - 1
            if (calcIndex in channels.indices) {
                performDirectSwitch(calcIndex)
                pendingTargetChannelName = null
                return
            }
        }

        // 3. Kiểm tra từ khóa YouTube
        val isExplicitYouTube = lower.contains("youtube") ||
                lower.contains("du tu") ||
                lower.contains("dù tu") ||
                lower.contains("du túp")

        if (isExplicitYouTube) {
            YouTubeLauncher.open(this, trimmed)
            pendingTargetChannelName = null
            return
        }

        // 4. So khớp tên kênh qua VoiceMatcher
        val matchedIndex = VoiceMatcher.matchChannelName(trimmed, channels)
        if (matchedIndex != -1) {
            performDirectSwitch(matchedIndex)
            pendingTargetChannelName = null
            return
        }

        // 5. Nếu không khớp kênh nào -> Mở YouTube
        YouTubeLauncher.open(this, trimmed)
        pendingTargetChannelName = null
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

    override fun onDestroy() {
        super.onDestroy()
        isForeground = false
        if (activeInstance == this) {
            activeInstance = null
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(stopPlayerReceiver)
            unregisterReceiver(voiceCommandReceiver)
        } catch (_: Throwable) {}
        if (isFinishing) {
            isForeground = false
            if (activeInstance == this) {
                activeInstance = null
            }
            finishAndRemoveTask()
        }
    }
}