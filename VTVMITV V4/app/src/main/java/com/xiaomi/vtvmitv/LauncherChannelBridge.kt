package com.xiaomi.vtvmitv

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.text.Normalizer
import java.util.regex.Pattern

object LauncherChannelBridge {

    private const val TAG = "HomeChannel"
    private const val PREF = "vtvmitv_home_store"
    // Đổi key mới để ép tạo lại channel và nạp icon logo mới vào Projectivy Launcher
    private const val KEY_CHANNEL_ID = "vtv_channel_row_with_app_icon_v3"

    private val VTV_DRAWABLE_RES_IDS = intArrayOf(
        R.drawable.vtv1,
        R.drawable.vtv2,
        R.drawable.vtv3,
        R.drawable.vtv4,
        R.drawable.vtv5,
        R.drawable.vtv6,
        R.drawable.vtv7,
        R.drawable.vtv8,
        R.drawable.vtv9
    )

    private fun cleanChannelName(str: String?): String {
        if (str.isNullOrBlank()) return ""
        return try {
            val nfd = Normalizer.normalize(str, Normalizer.Form.NFD)
            nfd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
                .lowercase()
                .trim()
        } catch (_: Throwable) {
            str.lowercase().trim()
        }
    }

    fun getVtvNumber(channelName: String?): Int? {
        if (channelName.isNullOrBlank()) return null
        val clean = cleanChannelName(channelName)
        val pattern = Pattern.compile("vtv\\s*([1-9])(?![0-9])")
        val matcher = pattern.matcher(clean)
        return if (matcher.find()) {
            matcher.group(1)?.toIntOrNull()
        } else {
            null
        }
    }

    fun getLocalLogoResId(context: Context, channelName: String?): Int {
        val vtvNum = getVtvNumber(channelName)
        return if (vtvNum != null && vtvNum in 1..9) {
            VTV_DRAWABLE_RES_IDS[vtvNum - 1]
        } else {
            R.drawable.ic_app_mitv
        }
    }

    private fun getResourceUriByChannelNumber(context: Context, channelNum: Int): Uri {
        val index = (channelNum - 1).coerceIn(0, VTV_DRAWABLE_RES_IDS.size - 1)
        val resId = VTV_DRAWABLE_RES_IDS[index]
        return Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/$resId")
    }

    // Nạp Bitmap logo trực tiếp vào hệ thống Provider để Launcher đọc được icon nhỏ
    private fun storeChannelLogo(context: Context, channelId: Long) {
        try {
            val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.ic_app_mitv)
            if (bitmap != null) {
                // Cách chuẩn Android TV Provider
                ChannelLogoUtils.storeChannelLogo(context, channelId, bitmap)

                // Ghi đè thêm luồng Stream vào logo URI tương thích tuyệt đối Projectivy Launcher
                val logoUri = TvContractCompat.buildChannelLogoUri(channelId)
                context.contentResolver.openOutputStream(logoUri)?.use { outStream: OutputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outStream)
                }
                Log.d(TAG, "Lưu logo icon thành công cho channelId=$channelId")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Lỗi lưu logo icon: ${e.message}")
        }
    }

    private fun ensureHomeChannel(context: Context): Long {
        return try {
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val channelId = prefs.getLong(KEY_CHANNEL_ID, -1L)
            if (channelId > 0) {
                // Đảm bảo icon luôn tồn tại
                storeChannelLogo(context, channelId)
                return channelId
            }

            val appUri = Uri.parse("vtvmitv://channel")

            val channelBuilder = PreviewChannel.Builder().apply {
                setDisplayName("VTV MITV")
                setDescription("VTV MITV – Kênh Quốc Gia")
                setAppLinkIntentUri(appUri)
            }

            val uri = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI,
                channelBuilder.build().toContentValues()
            )

            if (uri != null) {
                val newId = ContentUris.parseId(uri)
                prefs.edit().putLong(KEY_CHANNEL_ID, newId).apply()
                // Ghi icon nhỏ ngay sau khi tạo kênh
                storeChannelLogo(context, newId)
                Log.d(TAG, "Created home channel id=$newId with logo")
                newId
            } else -1L
        } catch (e: Throwable) {
            Log.w(TAG, "ensureHomeChannel error", e)
            -1L
        }
    }

    fun syncChannelsAsync(context: Context, channels: List<Channel>) {
        if (channels.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val channelId = ensureHomeChannel(context)
                if (channelId <= 0) return@launch

                val programsUri = TvContractCompat.buildPreviewProgramsUriForChannel(channelId)
                context.contentResolver.delete(programsUri, null, null)

                var syncedCount = 0

                for (targetNum in 1..9) {
                    val matchedChannel = channels.firstOrNull { ch ->
                        getVtvNumber(ch.name) == targetNum
                    } ?: continue

                    val streamUrl = matchedChannel.streamUrls.firstOrNull() ?: continue
                    val posterUri = getResourceUriByChannelNumber(context, targetNum)

                    val intentUri = Uri.parse(
                        "vtvmitv://play?name=" + Uri.encode(matchedChannel.name) +
                                "&url=" + Uri.encode(streamUrl) +
                                "&src=home"
                    )

                    val program = PreviewProgram.Builder().apply {
                        setChannelId(channelId)
                        setTitle("VTV$targetNum")
                        setDescription("Xem trực tiếp kênh VTV$targetNum")
                        setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
                        setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                        setIntentUri(intentUri)
                        setPosterArtUri(posterUri)
                    }.build()

                    context.contentResolver.insert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI,
                        program.toContentValues()
                    )
                    syncedCount++
                }
                Log.d(TAG, "Synced programs completed: $syncedCount")
            } catch (e: Throwable) {
                Log.w(TAG, "syncPrograms error", e)
            }
        }
    }

    fun updateWatchNextAsync(context: Context, channel: Channel, currentIndex: Int) {
        val streamUrl = channel.streamUrls.firstOrNull() ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val vtvNum = getVtvNumber(channel.name) ?: ((currentIndex % 9) + 1)
                val posterUri = getResourceUriByChannelNumber(context, vtvNum)

                val intentUri = Uri.parse(
                    "vtvmitv://play?name=" + Uri.encode(channel.name) +
                            "&url=" + Uri.encode(streamUrl) +
                            "&src=home"
                )

                val watchNextProgram = WatchNextProgram.Builder().apply {
                    setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    setTitle(channel.name)
                    setDescription("Tiếp tục xem trên VTV MITV")
                    setIntentUri(intentUri)
                    setPosterArtUri(posterUri)
                }.build()

                context.contentResolver.insert(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    watchNextProgram.toContentValues()
                )
            } catch (e: Throwable) {
                Log.w(TAG, "WatchNext error", e)
            }
        }
    }

    fun openCustomizeChannels(context: Context) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val channelId = prefs.getLong(KEY_CHANNEL_ID, -1L)

        val intent = Intent("android.media.tv.action.CHANNEL_BROWSABLE_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (channelId > 0) {
                putExtra("android.media.tv.extra.CHANNEL_ID", channelId)
            }
        }

        try {
            ContextCompat.startActivity(context, intent, null)
            return
        } catch (_: Throwable) {}

        try {
            val fallbackHome = Intent("android.settings.HOME_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(context, fallbackHome, null)
        } catch (e: Throwable) {
            Log.w(TAG, "openCustomizeChannels failed", e)
        }
    }
}