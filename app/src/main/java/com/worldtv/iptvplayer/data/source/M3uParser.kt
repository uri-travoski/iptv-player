package com.worldtv.iptvplayer.data.source

import com.worldtv.iptvplayer.data.model.ContentType
import java.io.BufferedReader
import java.io.Reader

/** One entry from an M3U/M3U8 playlist, before it is stored. */
data class M3uEntry(
    val name: String,
    val url: String,
    val group: String?,
    val logo: String?,
    val tvgId: String?,
    val tvgName: String?,
    val catchup: String?,
    val catchupDays: Int,
    val catchupSource: String?,
    val type: ContentType,
)

/**
 * Streaming M3U parser: reads line by line and hands each entry to [onEntry] immediately,
 * so a 100k-line playlist never sits in memory.
 */
object M3uParser {

    private val attributeRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    /**
     * @param onEpgUrl called once with the EPG URL from the #EXTM3U header (url-tvg / x-tvg-url), if any.
     * @return the number of entries parsed.
     */
    fun parse(
        reader: Reader,
        onEpgUrl: (String) -> Unit = {},
        onEntry: (M3uEntry) -> Unit,
    ): Int {
        val buffered = if (reader is BufferedReader) reader else BufferedReader(reader, 64 * 1024)
        var count = 0
        var pendingInfo: String? = null
        var pendingGroup: String? = null
        var first = true

        while (true) {
            var line = buffered.readLine() ?: break
            if (first) {
                line = line.removePrefix("﻿")
                first = false
            }
            line = line.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = attributes(line)
                    val epg = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"]
                    epg?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let(onEpgUrl)
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingInfo = line
                    pendingGroup = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substring(8).trim().ifEmpty { null }
                }
                line.startsWith("#") -> Unit // other directives (#EXTVLCOPT, #KODIPROP, ...) ignored
                else -> {
                    onEntry(buildEntry(pendingInfo, pendingGroup, line))
                    count++
                    pendingInfo = null
                    pendingGroup = null
                }
            }
        }
        return count
    }

    private fun buildEntry(info: String?, extGroup: String?, url: String): M3uEntry {
        val attrs = if (info != null) attributes(info) else emptyMap()
        val title = info?.let { displayName(it) }?.takeIf { it.isNotEmpty() }
            ?: attrs["tvg-name"]?.takeIf { it.isNotEmpty() }
            ?: url.substringAfterLast('/')
        return M3uEntry(
            name = title,
            url = url,
            group = attrs["group-title"]?.takeIf { it.isNotEmpty() } ?: extGroup,
            logo = attrs["tvg-logo"]?.takeIf { it.isNotEmpty() },
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotEmpty() },
            tvgName = attrs["tvg-name"]?.takeIf { it.isNotEmpty() },
            catchup = attrs["catchup"] ?: attrs["tvg-rec"]?.let { "default" },
            catchupDays = (attrs["catchup-days"] ?: attrs["tvg-rec"])?.trim()?.toIntOrNull() ?: 0,
            catchupSource = attrs["catchup-source"]?.takeIf { it.isNotEmpty() },
            type = classify(url),
        )
    }

    /** Xtream-style URLs tell us the library; anything else is treated as live TV. */
    fun classify(url: String): ContentType {
        val path = url.substringBefore('?').lowercase()
        return when {
            "/movie/" in path -> ContentType.MOVIE
            "/series/" in path -> ContentType.SERIES
            else -> ContentType.LIVE
        }
    }

    internal fun attributes(line: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (m in attributeRegex.findAll(line)) {
            map[m.groupValues[1].lowercase()] = m.groupValues[2].trim()
        }
        return map
    }

    /** The title is everything after the first comma that is outside quotes. */
    internal fun displayName(info: String): String {
        var inQuotes = false
        for (i in info.indices) {
            val c = info[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return info.substring(i + 1).trim()
        }
        return ""
    }
}
