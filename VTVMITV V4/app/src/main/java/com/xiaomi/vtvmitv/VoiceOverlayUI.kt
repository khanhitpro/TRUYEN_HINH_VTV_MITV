package com.xiaomi.vtvmitv

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Giao diện Google TV Assistant: Thanh ngang tràn mép cao 180dp, đổ màu mờ Gradient đen
 */
@Composable
fun GoogleTvVoiceOverlay(
    isListening: Boolean,
    recognizedText: String,
    rmsLevel: Float
) {
    val googleBlue = Color(0xFF4285F4)
    val googleRed = Color(0xFFEA4335)
    val googleYellow = Color(0xFFFBBC05)
    val googleGreen = Color(0xFF34A853)

    val infiniteTransition = rememberInfiniteTransition(label = "GoogleWave")

    val dot1Scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )

    val dot2Scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, delayMillis = 100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )

    val dot3Scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, delayMillis = 200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    val dot4Scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, delayMillis = 300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot4"
    )

    val rmsBoost = (rmsLevel.coerceIn(0f, 10f) / 10f) * 0.35f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF000000),
                        Color(0xF8050505),
                        Color(0xD90A0A0A),
                        Color(0x80101010),
                        Color(0x20101010),
                        Color.Transparent
                    )
                )
            )
            .padding(start = 52.dp, end = 52.dp, top = 34.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cụm 4 chấm tròn Google Assistant
            Row(
                modifier = Modifier.padding(end = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .scale(if (isListening) dot1Scale + rmsBoost else 1f)
                        .clip(CircleShape)
                        .background(googleBlue)
                )
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .scale(if (isListening) dot2Scale + rmsBoost else 1f)
                        .clip(CircleShape)
                        .background(googleRed)
                )
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .scale(if (isListening) dot3Scale + rmsBoost else 1f)
                        .clip(CircleShape)
                        .background(googleYellow)
                )
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .scale(if (isListening) dot4Scale + rmsBoost else 1f)
                        .clip(CircleShape)
                        .background(googleGreen)
                )
            }

            Text(
                text = if (recognizedText.isNotBlank()) recognizedText else "Hãy bấm giữ nút Mic và nói để tìm kiếm...",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}