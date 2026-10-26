package com.xiaomi.vtvmitv

import java.text.Normalizer

object VoiceMatcher {

    /**
     * Bóc tách số kênh chuẩn tiếng Việt:
     * Hỗ trợ "kênh 1", "kênh 3", "kênh số 2", "số 5", "1", "3", "kênh một", "kênh ba"...
     */
    fun extractChannelNumber(rawText: String): Int? {
        val lower = rawText.lowercase().trim()

        if (lower.contains("vtv") || lower.contains("vtc") || lower.contains("htv") || lower.contains("thvl")) {
            return null
        }

        val prefixes = listOf("kênh số", "kênh thứ", "chuyển sang kênh", "bật kênh", "mở kênh", "xem kênh", "kênh", "số")
        var withoutPrefix = lower
        var hasChannelPrefix = false

        for (p in prefixes) {
            if (withoutPrefix.startsWith(p)) {
                hasChannelPrefix = true
                withoutPrefix = withoutPrefix.removePrefix(p).trim()
                break
            }
        }

        val cleanDigits = withoutPrefix.replace("[^0-9]".toRegex(), "")
        if (cleanDigits.isNotEmpty()) {
            if (hasChannelPrefix || withoutPrefix == cleanDigits) {
                val num = cleanDigits.toIntOrNull()
                if (num != null && num > 0) return num
            }
        }

        val wordMap = mapOf(
            "mot" to 1, "mốt" to 1, "một" to 1,
            "hai" to 2,
            "ba" to 3,
            "bon" to 4, "bốn" to 4, "tư" to 4,
            "nam" to 5, "năm" to 5, "lăm" to 5,
            "sau" to 6, "sáu" to 6,
            "bay" to 7, "bảy" to 7,
            "tam" to 8, "tám" to 8,
            "chin" to 9, "chín" to 9,
            "muoi" to 10, "mười" to 10
        )

        val cleanWord = withoutPrefix.trim()
        if (wordMap.containsKey(cleanWord) && (hasChannelPrefix || cleanWord.split(" ").size == 1)) {
            return wordMap[cleanWord]
        }

        return null
    }

    /**
     * So khớp tên kênh truyền hình (VTV1, VTV3, VTV Cần Thơ...)
     */
    fun matchChannelName(rawText: String, channels: List<Channel>): Int {
        val cleanTarget = cleanText(rawText)
        if (cleanTarget.isEmpty() || channels.isEmpty()) return -1

        var matchedIndex = channels.indexOfFirst { cleanText(it.name) == cleanTarget }

        if (matchedIndex == -1) {
            matchedIndex = channels.indexOfFirst {
                val cleanName = cleanText(it.name)
                cleanName == cleanTarget || (cleanName.contains(cleanTarget) && cleanTarget.length >= 3)
            }
        }
        return matchedIndex
    }

    /**
     * Chuẩn hóa văn bản tiếng Việt bỏ dấu và chuyển số từ chữ sang ký tự
     */
    fun cleanText(str: String?): String {
        if (str.isNullOrBlank()) return ""
        var text = str.lowercase().trim()

        val removeWords = listOf(
            "mở kênh", "mở", "bật kênh", "bật", "xem kênh",
            "xem", "chuyển sang kênh", "chuyển sang", "chuyển kênh", "kênh", "cho xem"
        )
        for (w in removeWords) {
            text = text.replace(w, " ")
        }

        val normalized = try {
            val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
            nfd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        } catch (_: Throwable) {
            text
        }

        var result = normalized.replace(" ", "").trim()

        val numberMap = mapOf(
            "mot" to "1", "hai" to "2", "ba" to "3",
            "bon" to "4", "tu" to "4", "nam" to "5", "sau" to "6",
            "bay" to "7", "tam" to "8", "chin" to "9"
        )
        for ((word, digit) in numberMap) {
            result = result.replace(word, digit)
        }

        return result
    }
}