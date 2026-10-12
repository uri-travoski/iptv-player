package com.worldtv.iptvplayer.data.sync

/**
 * The playlist a new install (and Reset app) starts with: Free-TV/IPTV, a public M3U of
 * free-to-air channels maintained on GitHub. Live TV only. Loaded like any M3U playlist; the
 * user can delete it, and it isn't added again.
 */
object DefaultPlaylist {
    const val NAME = "Free TV"
    const val URL = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
}
