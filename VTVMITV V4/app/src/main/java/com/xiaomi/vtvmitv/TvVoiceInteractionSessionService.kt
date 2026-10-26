package com.xiaomi.vtvmitv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class TvVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return TvVoiceSession(this)
    }

    private class TvVoiceSession(context: Context) : VoiceInteractionSession(context) {
        override fun onHandleAssist(
            data: Bundle?,
            structure: android.app.assist.AssistStructure?,
            content: android.app.assist.AssistContent?
        ) {
            super.onHandleAssist(data, structure, content)
            // Chỉ bật hộp thoại thu âm nổi nhỏ, KHÔNG mở toàn màn hình MainActivity
            val launchIntent = Intent(context, VoiceDialogActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(launchIntent)
            hide()
        }
    }
}