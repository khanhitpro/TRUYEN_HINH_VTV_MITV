package com.xiaomi.vtvmitv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.runtime.*
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
class VoiceDialogActivity : ComponentActivity() {

    private companion object {
        const val PREFS_NAME = "vtvmitv_playlist_pref"
        const val KEY_CACHED_M3U = "cached_m3u_content"

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

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizedText by mutableStateOf("")
    private var isListening by mutableStateOf(true)
    private var currentRms by mutableFloatStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setGravity(Gravity.TOP or Gravity.START)
        val lp = window.attributes
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        window.attributes = lp
        window.setBackgroundDrawableResource(android.R.color.transparent)

        setContent {
            GoogleTvVoiceOverlay(
                isListening = isListening,
                recognizedText = recognizedText,
                rmsLevel = currentRms
            )
        }

        startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Thiết bị không hỗ trợ nhận dạng giọng nói", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

                    if (!text.isNullOrBlank()) {
                        recognizedText = text
                        dispatchCommand(text)
                    } else {
                        finish()
                    }
                }

                override fun onError(error: Int) {
                    finish()
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    currentRms = rmsdB
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!partial.isNullOrBlank()) {
                        recognizedText = partial
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.startListening(recognizerIntent)
    }

    private fun dispatchCommand(rawText: String) {
        val channels = loadChannelsList()

        // 1. Module giải mã bóc tách số kênh
        val channelNumber = VoiceMatcher.extractChannelNumber(rawText)
        if (channelNumber != null && channels.isNotEmpty()) {
            val targetIndex = channelNumber - 1
            if (targetIndex in channels.indices) {
                routeChannelSwitch(targetIndex)
                return
            }
        }

        // 2. Module giải mã so khớp tên kênh
        val matchedIndex = VoiceMatcher.matchChannelName(rawText, channels)
        if (matchedIndex != -1) {
            routeChannelSwitch(matchedIndex)
            return
        }

        // 3. Module khởi chạy YouTube
        YouTubeLauncher.open(this, rawText)
        finish()
    }

    private fun routeChannelSwitch(targetIndex: Int) {
        if (MainActivity.isForeground) {
            val broadcastIntent = Intent(MainActivity.ACTION_VOICE_SWITCH).apply {
                putExtra("target_index", targetIndex)
                setPackage(packageName)
            }
            sendBroadcast(broadcastIntent)
        } else {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("vtvmitv://play?index=$targetIndex")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(launchIntent)
        }
        finish()
    }

    private fun loadChannelsList(): List<Channel> {
        return try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val m3uContent = prefs.getString(KEY_CACHED_M3U, null) ?: DEFAULT_M3U_BACKUP
            M3UParser.parse(m3uContent)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
    }
}