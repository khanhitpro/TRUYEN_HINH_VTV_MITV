package com.xiaomi.vtvmitv

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns

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
            SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID
        )

        val matrixCursor = MatrixCursor(columns)
        val context = context ?: return matrixCursor

        // Đọc danh sách kênh trực tiếp từ ChannelCacheManager gốc
        val channels = ChannelCacheManager.loadCache(context)
        if (channels.isEmpty()) return matrixCursor

        val filtered = if (query.isEmpty() || query == "vtv" || query == "truyen hinh") {
            channels
        } else {
            channels.filter { channel ->
                val name = channel.name.lowercase()
                name.contains(query) || query.contains(name) ||
                        name.replace(" ", "").contains(query.replace(" ", ""))
            }
        }

        filtered.forEachIndexed { index, channel ->
            val logoResId = LauncherChannelBridge.getLocalLogoResId(context, channel.name)
            val iconUri = if (logoResId != 0) {
                "android.resource://${context.packageName}/$logoResId"
            } else {
                "android.resource://${context.packageName}/${R.drawable.ic_app_mitv}"
            }

            matrixCursor.addRow(
                arrayOf(
                    index.toLong(),
                    channel.name,
                    "Truyền Hình VTV MITV",
                    iconUri,
                    channel.name
                )
            )
        }

        return matrixCursor
    }

    override fun getType(uri: Uri): String {
        return SearchManager.SUGGEST_MIME_TYPE
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}