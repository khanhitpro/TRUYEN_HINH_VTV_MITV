package com.xiaomi.vtvmitv

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.text.Normalizer

class VoiceSuggestionProvider : ContentProvider() {

    companion object {
        private const val CACHE_EXPIRE_TIME = 600000L // 10 phút (0x927c0)
        private var cachedList: List<Channel>? = null
        private var cachedAt: Long = 0L

        // Đúng 4 cột nguyên bản theo Smali: _id, suggest_text_1, suggest_intent_data, suggest_icon_1
        private val COLUMNS = arrayOf(
            "_id",
            "suggest_text_1",
            "suggest_intent_data",
            "suggest_icon_1"
        )

        private const val DEFAULT_M3U = """
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

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val ctx = context ?: return cursor

        // 1. Logic lấy từ khóa theo đúng mã Smali:
        // if (selectionArgs != null && selectionArgs.length > 0) v0 = selectionArgs[0]
        // else v0 = uri.getLastPathSegment()
        var rawQuery: String? = if (selectionArgs != null && selectionArgs.isNotEmpty()) {
            selectionArgs[0]
        } else {
            uri.lastPathSegment
        }

        if (rawQuery == null || rawQuery == "search_suggest_query") {
            rawQuery = ""
        }
        val queryText = rawQuery.trim().lowercase()

        // 2. Logic Cache & Load theo mã Smali
        var channels = cachedList
        val now = System.currentTimeMillis()
        if (channels == null || (now - cachedAt) > CACHE_EXPIRE_TIME) {
            val prefs = ctx.getSharedPreferences("vtvmitv_playlist_pref", Context.MODE_PRIVATE)
            val cachedM3u = prefs.getString("cached_m3u_content", null)
            channels = try {
                if (!cachedM3u.isNullOrBlank()) {
                    M3UParser.parse(cachedM3u)
                } else {
                    M3UParser.parse(DEFAULT_M3U)
                }
            } catch (_: Throwable) {
                M3UParser.parse(DEFAULT_M3U)
            }
            cachedList = channels
            cachedAt = now
        }

        if (channels.isNullOrEmpty()) {
            return cursor
        }

        // 3. Lọc danh sách và trả về tối đa 8 kết quả (const/16 v7, 0x8)
        var count = 0
        val maxResults = 8

        for (channel in channels) {
            if (count >= maxResults) break

            val channelName = channel.name ?: ""
            val nameLower = channelName.lowercase()

            // Nếu từ khóa rỗng thì hiện luôn, nếu có từ khóa thì so khớp contains
            if (queryText.isNotEmpty()) {
                val cleanName = removeAccents(nameLower).replace(" ", "")
                val cleanQuery = removeAccents(queryText).replace(" ", "")
                if (!nameLower.contains(queryText) && !cleanName.contains(cleanQuery)) {
                    continue
                }
            }

            val streamUrl = channel.streamUrls.firstOrNull() ?: ""
            if (streamUrl.isEmpty()) continue

            // Scheme xiaomitv://play?url=... trong mã Smali:
            // "xiaomitv://play?url=" + Uri.encode(url) + "&name=" + Uri.encode(channelName)
            val intentData = "vtvmitv://play?url=" + Uri.encode(streamUrl) +
                    "&name=" + Uri.encode(channelName)

            // Lấy ID icon nội bộ
            val logoResId = LauncherChannelBridge.getLocalLogoResId(ctx, channelName)
            val iconUri = "android.resource://${ctx.packageName}/$logoResId"

            // Đưa đúng 4 cột vào bảng kết quả theo Smali
            cursor.addRow(
                arrayOf(
                    count,          // _id
                    channelName,    // suggest_text_1
                    intentData,     // suggest_intent_data
                    iconUri         // suggest_icon_1
                )
            )
            count++
        }

        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun removeAccents(str: String?): String {
        if (str.isNullOrBlank()) return ""
        return try {
            val nfd = Normalizer.normalize(str, Normalizer.Form.NFD)
            nfd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        } catch (_: Throwable) {
            str
        }
    }
}