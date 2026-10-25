package com.xiaomi.vtvmitv

import java.io.BufferedReader
import java.io.StringReader

data class Channel(
    val name: String,
    val streamUrls: List<String>,
    val logoUrl: String = "",
    val groupTitle: String = ""
)

object M3UParser {

    fun parse(m3uContent: String): List<Channel> {
        val rawChannels = mutableListOf<Pair<String, String>>()
        val reader = BufferedReader(StringReader(m3uContent))
        var line: String?
        var currentName = ""

        while (reader.readLine().also { line = it } != null) {
            val trimmed = line?.trim() ?: continue
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                val commaIndex = trimmed.lastIndexOf(',')
                currentName = if (commaIndex != -1 && commaIndex < trimmed.length - 1) {
                    trimmed.substring(commaIndex + 1).trim()
                } else {
                    "Kênh TV"
                }
            } else if (!trimmed.startsWith("#") && (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true))) {
                if (currentName.isNotEmpty()) {
                    rawChannels.add(Pair(currentName, trimmed))
                    currentName = ""
                }
            }
        }

        val groupedMap = linkedMapOf<String, MutableList<String>>()

        for ((name, url) in rawChannels) {
            val cleanKey = name.replace("FHD", "", ignoreCase = true)
                .replace("HD", "", ignoreCase = true)
                .replace("SD", "", ignoreCase = true)
                .replace("4K", "", ignoreCase = true)
                .replace("Backup", "", ignoreCase = true)
                .replace("Dự phòng", "", ignoreCase = true)
                .trim()

            val list = groupedMap.getOrPut(cleanKey) { mutableListOf() }
            if (!list.contains(url) && list.size < 3) {
                list.add(url)
            }
        }

        return groupedMap.map { (name, urls) ->
            Channel(
                name = name,
                streamUrls = urls
            )
        }
    }
}