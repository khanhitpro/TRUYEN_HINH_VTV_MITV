package com.xiaomi.vtvmitv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OtaDialogView(
    updateInfo: UpdateInfo,
    isDownloading: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .border(2.dp, Color.Yellow, RoundedCornerShape(12.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "BẢN CẬP NHẬT MỚI: v${updateInfo.versionName}",
                color = Color.Yellow,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = updateInfo.releaseNotes.replace("\\n", "\n"),
                color = Color.White,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(20.dp))

            if (isDownloading) {
                Text(
                    text = "Đang tải xuống bản cài đặt... Vui lòng chờ",
                    color = Color.Cyan,
                    fontSize = 14.sp
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF444444), RoundedCornerShape(6.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(text = "Bỏ qua (Bấm Quay lại)", color = Color.White, fontSize = 13.sp)
                    }

                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1E3A8A), RoundedCornerShape(6.dp))
                            .border(1.dp, Color.Cyan, RoundedCornerShape(6.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(text = "Cập nhật (Bấm OK)", color = Color.Yellow, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}