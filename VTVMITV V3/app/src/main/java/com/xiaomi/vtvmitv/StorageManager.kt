package com.xiaomi.vtvmitv

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object ChannelCacheManager {
    private const val CACHE_FILE_NAME = "iptv_channels_cache.json"
    private val gson = Gson()

    fun saveCache(context: Context, channels: List<Channel>) {
        try {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            file.writeText(gson.toJson(channels))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadCache(context: Context): List<Channel> {
        return try {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            if (!file.exists()) return emptyList()
            val type = object : TypeToken<List<Channel>>() {}.type
            gson.fromJson<List<Channel>>(file.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

object AppPreferences {
    private const val PREF_NAME = "vtv_mitv_prefs"
    private const val KEY_LAST_CHANNEL_NAME = "last_channel_name"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveLastChannel(context: Context, channelName: String) {
        if (channelName.isNotBlank()) {
            getPrefs(context).edit().putString(KEY_LAST_CHANNEL_NAME, channelName).apply()
        }
    }

    fun getLastChannel(context: Context): String? {
        return getPrefs(context).getString(KEY_LAST_CHANNEL_NAME, null)
    }
}