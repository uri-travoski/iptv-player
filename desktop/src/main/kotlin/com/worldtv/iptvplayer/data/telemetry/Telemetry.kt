package com.worldtv.iptvplayer.data.telemetry

import com.worldtv.iptvplayer.data.repo.Playlist

/**
 * Desktop stand-in: the desktop app sends no diagnostics. The shared Syncer takes a nullable
 * Telemetry and is always given null; this only lets it compile.
 */
class Telemetry private constructor() {
    fun playlist(p: Playlist, counts: Map<String, Any?> = emptyMap()) {}
    fun event(kind: String, action: String, fields: Map<String, Any?> = emptyMap()) {}
}
