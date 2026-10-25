package com.xiaomi.vtvmitv

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import org.json.JSONArray

class VoiceSuggestionProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val query = if (uri.pathSegments.isNotEmpty() && uri.lastPathSegment != SearchManager.SUGGEST_URI_PATH_QUERY) {
            uri.lastPathSegment?.trim()?.lowercase() ?: ""
        } else {
            selectionArgs?.firstOrNull()?.trim()?.lowercase() ?: ""
        }

        val columns = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_ICON_1,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_CONTENT_TYPE,
            SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR,
            SearchManager.SUGGEST_COLUMN_DURATION,
            "suggest_is_live"
        )

        val matrixCursor = MatrixCursor(columns)
        val ctx = context ?: return matrixCursor

        // Đọc danh sách tên kênh trực tiếp từ bộ nhớ đệm
        val cachedNames = getCachedChannelNames(ctx)
        if (cachedNames.isEmpty()) return matrixCursor

        // Lọc và xếp độ ưu tiên theo từ khóa tìm kiếm
        val filtered = if (query.isEmpty() || query == "vtv" || query == "truyen hinh") {
            cachedNames
        } else {
            cachedNames.filter { name ->
                val lower = name.lowercase()
                lower.contains(query) || query.contains(lower) ||
                        lower.replace(" ", "").contains(query.replace(" ", ""))
            }.sortedByDescending { name ->
                val lower = name.lowercase()
                when {
                    lower == query -> 100
                    lower.startsWith(query) -> 80
                    else -> 50
                }
            }
        }

        filtered.forEachIndexed { index, channelName ->
            val logoResId = LauncherChannelBridge.getLocalLogoResId(ctx, channelName)
            val iconUri = if (logoResId != 0) {
                "android.resource://${ctx.packageName}/$logoResId"
            } else {
                "android.resource://${ctx.packageName}/${R.drawable.ic_app_mitv}"
            }

            matrixCursor.addRow(
                arrayOf(
                    index.toLong(),
                    channelName,
                    "Phát trực tiếp • Truyền hình số vệ tinh",
                    iconUri,
                    channelName,
                    "android.intent.action.VIEW",
                    "vnd.android.cursor.item/vnd.com.xiaomi.vtvmitv.channel",
                    "2026",
                    "0",
                    "true"
                )
            )
        }

        return matrixCursor
    }

    private fun getCachedChannelNames(context: Context): List<String> {
        val names = mutableListOf<String>()
        try {
            val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("cached_channels", null)
                ?: prefs.getString("channels_json", null)
                ?: prefs.getString("m3u_channels", null)

            if (!jsonStr.isNullOrEmpty()) {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.optString("name")
                    if (name.isNotEmpty()) {
                        names.add(name)
                    }
                }
            }
        } catch (_: Throwable) {}

        // Danh sách kênh VTV mặc định đảm bảo tìm kiếm luôn trả về kết quả
        if (names.isEmpty()) {
            return listOf(
                "VTV1", "VTV2", "VTV3", "VTV4", "VTV5",
                "VTV5 Tây Nam Bộ", "VTV5 Tây Nguyên", "VTV6", "VTV7",
                "VTV8", "VTV9", "VTV Cần Thơ"
            )
        }
        return names
    }

    override fun getType(uri: Uri): String {
        return SearchManager.SUGGEST_MIME_TYPE
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}