package com.worldtv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.worldtv.desktop.BuildInfo
import com.worldtv.desktop.Updates
import com.worldtv.desktop.graph
import com.worldtv.desktop.themeId
import com.worldtv.iptvplayer.AutoRefresh
import com.worldtv.iptvplayer.data.model.ContentType
import com.worldtv.iptvplayer.data.net.AppDns
import com.worldtv.iptvplayer.data.repo.AdminCategory
import com.worldtv.iptvplayer.data.repo.CategoryRow
import com.worldtv.iptvplayer.data.repo.Pin
import com.worldtv.iptvplayer.data.repo.Playlist
import com.worldtv.iptvplayer.data.repo.Repository
import com.worldtv.iptvplayer.data.source.XtreamCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreens(route: Route) {
    when (route) {
        Route.Settings -> SettingsScreen()
        Route.Playlists -> PlaylistsScreen()
        is Route.EditPlaylist -> EditPlaylistScreen(route.playlistId)
        Route.ChannelCheck -> ChannelCheckScreen()
        Route.Categories -> HiddenCategoriesScreen()
        Route.Help -> HelpScreen()
        else -> {}
    }
}

private val REFRESH_LABELS = mapOf(
    AutoRefresh.EVERY_START to "Every start",
    AutoRefresh.DAILY to "Every day (3 am)",
    AutoRefresh.EVERY_2_DAYS to "Every 2 days (3 am)",
)

private val DNS_LABELS = listOf(
    AppDns.MODE_SYSTEM to "System DNS",
    AppDns.MODE_BOTH to "Encrypted: Cloudflare, then Google",
    AppDns.MODE_CLOUDFLARE to "Encrypted: Cloudflare",
    AppDns.MODE_GOOGLE to "Encrypted: Google",
)

/** App Settings: a list, as on Android. */
@Composable
private fun SettingsScreen() {
    val nav = LocalNav.current
    val wt = LocalWt.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val prefs = graph.prefs
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    var refresh by remember { mutableStateOf(prefs.autoRefresh) }
    var dns by remember { mutableIntStateOf(prefs.dnsMode) }
    var autoUpdate by remember { mutableStateOf(prefs.autoUpdate) }
    var regions by remember { mutableStateOf(prefs.spotlightRegions) }
    var checkLine by remember { mutableStateOf("") }
    val checkProgress by graph.channelCheck.progress.collectAsState()

    LaunchedEffect(checkProgress) {
        val p = graph.activePlaylist() ?: return@LaunchedEffect
        checkLine = checkSummary(p)
    }

    ThemedBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar("Settings", { nav.back() })
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 8.dp)) {
                Section("Playlists")
                SettingRow(Icons.AutoMirrored.Filled.List, "Playlists", "Pick, reload, edit or delete") { nav.push(Route.Playlists) }
                SettingRow(Icons.Default.Add, "Add playlist", "Xtream login or M3U link") { nav.push(Route.EditPlaylist(null)) }
                SettingRow(Icons.Default.Autorenew, "Automatic refresh", REFRESH_LABELS[refresh]) {
                    dialog = {
                        ChoiceDialog("Automatic refresh", REFRESH_LABELS.values.toList(), REFRESH_LABELS.keys.indexOf(refresh), onDismiss = { dialog = null }) { i ->
                            refresh = REFRESH_LABELS.keys.elementAt(i)
                            prefs.autoRefresh = refresh
                            dialog = null
                        }
                    }
                }
                SettingRow(Icons.Default.CheckCircle, "Channel check", checkLine.ifEmpty { "Find the live channels that work" }) { nav.push(Route.ChannelCheck) }
                SettingRow(Icons.Default.VisibilityOff, "Hidden categories", "Hide categories you never watch") { nav.push(Route.Categories) }

                SettingRow(Icons.Default.Public, "Backdrop region", com.worldtv.iptvplayer.data.spotlight.SliderRegions.label(regions)) {
                    dialog = {
                        RegionsDialog(regions, onDismiss = { dialog = null }) { chosen ->
                            dialog = null
                            if (chosen != prefs.spotlightRegions) {
                                prefs.spotlightRegions = chosen
                                regions = chosen
                                graph.spotlight.regionsChanged()
                                toaster.show("The home screen slider is being picked again for these languages")
                            }
                        }
                    }
                }

                Section("Look and network")
                SettingRow(Icons.Default.Palette, "Theme", Themes.of(themeId).label) {
                    dialog = {
                        ChoiceDialog("Theme", Themes.ALL.map { it.label }, Themes.ALL.indexOfFirst { it.id == themeId }, onDismiss = { dialog = null }) { i ->
                            themeId = Themes.ALL[i].id
                            prefs.theme = themeId
                            dialog = null
                        }
                    }
                }
                SettingRow(Icons.Default.Dns, "DNS", DNS_LABELS.firstOrNull { it.first == dns }?.second) {
                    dialog = {
                        ChoiceDialog("DNS for playlists and streams", DNS_LABELS.map { it.second }, DNS_LABELS.indexOfFirst { it.first == dns }, onDismiss = { dialog = null }) { i ->
                            dns = DNS_LABELS[i].first
                            prefs.dnsMode = dns
                            dialog = null
                        }
                    }
                }

                Section("App")
                SettingSwitch(Icons.Default.SystemUpdate, "Check for updates at start", autoUpdate) {
                    autoUpdate = it
                    prefs.autoUpdate = it
                }
                SettingRow(Icons.Default.Download, "Check for updates now", "This is WorldTV ${BuildInfo.VERSION}") {
                    scope.launch {
                        toaster.show("Checking…")
                        val r = runCatching { Updates.newest(graph) }
                        val rel = r.getOrNull()
                        when {
                            r.isFailure -> toaster.show("Couldn't check: ${r.exceptionOrNull()?.message}")
                            rel == null -> toaster.show("WorldTV ${BuildInfo.VERSION} is the newest")
                            else -> dialog = {
                                ConfirmDialog("WorldTV ${rel.version} is out", "Open its download page? Pick the package for your system (.deb, .rpm, Flatpak or AppImage).", "Open", onDismiss = { dialog = null }) {
                                    dialog = null
                                    Updates.open(rel.page)
                                }
                            }
                        }
                    }
                }
                SettingRow(Icons.Default.History, "Clear history", "Forget where films and episodes were left") {
                    dialog = {
                        ConfirmDialog("Clear history?", "Continue watching and every resume point are cleared.", "Clear", onDismiss = { dialog = null }) {
                            dialog = null
                            scope.launch {
                                graph.repo.clearHistory(ContentType.values().toList())
                                toaster.show("History cleared")
                            }
                        }
                    }
                }
                SettingRow(Icons.Default.RestartAlt, "Reset app", "Every playlist and setting removed") {
                    dialog = {
                        ConfirmDialog("Reset WorldTV?", "Every playlist, favourite, group and setting is removed, as after a new install.", "Reset", onDismiss = { dialog = null }) {
                            dialog = null
                            scope.launch {
                                graph.player.stop()
                                graph.repo.resetAll()
                                prefs.resetAll()
                                themeId = prefs.theme
                                graph.addDefaultPlaylistOnce()
                                graph.reloadIfDue()
                                nav.home()
                            }
                        }
                    }
                }
                SettingRow(Icons.AutoMirrored.Filled.Help, "Help and keyboard shortcuts", null) { nav.push(Route.Help) }
                SettingRow(Icons.Default.Info, "About", "WorldTV ${BuildInfo.VERSION} for Linux · GPL-3.0 · plays with VLC") { Updates.open(Updates.PAGE) }
                Spacer(Modifier.height(24.dp))
            }
        }
        dialog?.invoke()
    }
}

/** "33 / 335 checked · took 12 min 30 s", as the Android tile says. */
private suspend fun checkSummary(p: Playlist): String {
    if (graph.repo.checkCategories(p.id).isEmpty()) return "Off for ${p.name}: pick categories to check"
    val st = graph.channelCheck.status(p.id)
    val count = "${st.checked} / ${st.total} checked"
    val time = if (st.activeMs >= 0) Dates.duration(st.activeMs) else ""
    return when {
        st.running -> "$count · $time so far"
        st.unfinished -> "$count · goes on when idle"
        st.doneAt > 0 && st.activeMs < 0 -> count
        st.doneAt > 0 -> "$count · took $time"
        else -> "$count · starts after a minute with nothing playing"
    }
}

@Composable
private fun Section(title: String) {
    val wt = LocalWt.current
    Text(title.uppercase(), color = wt.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp, start = 8.dp))
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, value: String?, onClick: () -> Unit) {
    val wt = LocalWt.current
    ListRow(selected = false, onClick = onClick, height = if (value != null) 60.dp else 48.dp, modifier = Modifier.widthIn(max = 900.dp)) {
        Icon(icon, null, tint = wt.textSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, color = wt.textPrimary, fontSize = 16.sp)
            if (value != null) Text(value, color = wt.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SettingSwitch(icon: ImageVector, title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val wt = LocalWt.current
    ListRow(selected = false, onClick = { onChange(!on) }, height = 52.dp, modifier = Modifier.widthIn(max = 900.dp)) {
        Icon(icon, null, tint = wt.textSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(title, color = wt.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(
            on, onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedTrackColor = wt.accent, checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                uncheckedTrackColor = wt.surface, uncheckedThumbColor = wt.textSecondary, uncheckedBorderColor = wt.textSecondary,
            ),
        )
    }
}

/** Settings > Backdrop region: tick the languages the home slider shows (none: all). */
@Composable
private fun RegionsDialog(current: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var chosen by remember { mutableStateOf(current) }
    val wt = LocalWt.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backdrop region") },
        text = {
            Column {
                Text("The home screen slider shows titles in these languages. None ticked: every language.", fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                com.worldtv.iptvplayer.data.spotlight.SliderRegions.ALL.forEach { r ->
                    val on = r.id in chosen
                    ListRow(selected = false, onClick = { chosen = if (on) chosen - r.id else chosen + r.id }, height = 44.dp) {
                        Checkbox(on, { chosen = if (on) chosen - r.id else chosen + r.id }, colors = CheckboxDefaults.colors(checkedColor = wt.accent))
                        Spacer(Modifier.width(8.dp))
                        Text(r.label, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(chosen) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { i, label ->
                    ListRow(selected = false, onClick = { onPick(i) }, height = 44.dp) {
                        RadioButton(i == selected, onClick = { onPick(i) })
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- playlist PIN ----

/**
 * The playlist's 6-digit PIN (000000 until changed), as on Android: it guards the playlist's
 * details, deleting it and its hidden categories; never watching.
 */
@Composable
fun PinDialog(p: Playlist, what: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val f = remember { androidx.compose.ui.focus.FocusRequester() }
    fun check() {
        val stored = p.pinHash
        if (stored == null || Pin.matches(pin, stored)) onOk() else {
            wrong = true
            pin = ""
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PIN of ${p.name}") },
        text = {
            Column {
                Text("$what needs the playlist's PIN (000000 unless it was changed).", fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    pin,
                    { v -> if (v.length <= Pin.LENGTH && v.all { it.isDigit() }) { pin = v; wrong = false; if (v.length == Pin.LENGTH) check() } },
                    label = { Text("PIN") },
                    singleLine = true,
                    isError = wrong,
                    supportingText = if (wrong) ({ Text("Wrong PIN") }) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.focusRequester(f),
                )
                LaunchedEffect(Unit) { runCatching { f.requestFocus() } }
            }
        },
        confirmButton = { TextButton(onClick = { check() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- playlists ----

@Composable
private fun PlaylistsScreen() {
    val nav = LocalNav.current
    val wt = LocalWt.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var active by remember { mutableStateOf(graph.prefs.activePlaylist) }
    val syncing by graph.syncing.collectAsState()
    val progress by graph.syncProgress.collectAsState()
    val synced by graph.synced.collectAsState()
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    var bump by remember { mutableIntStateOf(0) }
    LaunchedEffect(synced, bump, syncing) {
        list = graph.repo.playlists()
        active = graph.activePlaylist()?.id ?: -1
    }

    ThemedBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar("Playlists", { nav.back() }) {
                WtButton("Add playlist", { nav.push(Route.EditPlaylist(null)) }, primary = true, icon = Icons.Default.Add)
            }
            if (syncing) Text(progress, color = wt.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp))
            if (list.isEmpty()) CenterNote("No playlist yet", Modifier.fillMaxSize())
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
                items(list, key = { it.id }) { p ->
                    Row(
                        Modifier.widthIn(max = 1000.dp).fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (p.id == active) wt.surfaceActive else wt.surface).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name, color = wt.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                if (p.id == active) {
                                    Spacer(Modifier.width(10.dp))
                                    Badge("IN USE", wt.accent)
                                }
                            }
                            val parts = listOfNotNull(
                                if (p.isXtream) "Xtream" else "M3U",
                                p.status?.takeIf { it.isNotBlank() && p.isXtream },
                                p.expires?.takeIf { it > 0 }?.let { "expires " + Dates.day(it * 1000) },
                                p.maxConnections.takeIf { it > 0 }?.let { "$it connection" + if (it > 1) "s" else "" },
                                if (p.lastSync > 0) "loaded " + Dates.ago(p.lastSync) else "not loaded yet",
                            )
                            Text(parts.joinToString(" · "), color = wt.textSecondary, fontSize = 13.sp)
                        }
                        if (p.id != active) WtButton("Use", {
                            graph.prefs.activePlaylist = p.id
                            graph.prefs.lastLiveCategory = null
                            graph.prefs.lastLiveItem = null
                            active = p.id
                            toaster.show("Now using ${p.name}")
                            if (p.lastSync <= 0) scope.launch { graph.reload(p)?.let { toaster.show("Couldn't load ${p.name}: $it") } }
                        }, icon = Icons.Default.PlayArrow)
                        Spacer(Modifier.width(6.dp))
                        IconAction(Icons.Default.Refresh, "Reload", {
                            if (!syncing) scope.launch {
                                val err = graph.reload(p)
                                toaster.show(err?.let { "Couldn't load ${p.name}: $it" } ?: "${p.name} is up to date")
                            }
                        })
                        IconAction(Icons.Default.Edit, "Edit", {
                            dialog = { PinDialog(p, "Editing", onDismiss = { dialog = null }) { dialog = null; nav.push(Route.EditPlaylist(p.id)) } }
                        })
                        IconAction(Icons.Default.Delete, "Delete", {
                            dialog = { PinDialog(p, "Deleting", onDismiss = { dialog = null }) { dialog = {
                                ConfirmDialog("Delete ${p.name}?", "Its favourites, groups and resume points go too.", "Delete", onDismiss = { dialog = null }) {
                                    dialog = null
                                    scope.launch {
                                        if (p.id == active) graph.player.stop()
                                        graph.repo.deletePlaylist(p.id)
                                        bump++
                                        toaster.show("${p.name} deleted")
                                    }
                                }
                            } } }
                        })
                    }
                }
            }
        }
        dialog?.invoke()
    }
}

/** Add or edit a playlist: an Xtream login, or an M3U link. Checked and loaded before it is kept. */
@Composable
private fun EditPlaylistScreen(id: Long?) {
    val nav = LocalNav.current
    val wt = LocalWt.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Playlist?>(null) }
    var xtream by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var m3u by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf("") }

    LaunchedEffect(id) {
        val p = id?.let { graph.repo.playlist(it) } ?: return@LaunchedEffect
        editing = p
        name = p.name
        xtream = p.isXtream
        if (p.isXtream) {
            server = p.url
            user = p.username.orEmpty()
            pass = p.password.orEmpty()
        } else {
            m3u = p.url
        }
    }
    BackHandler { busy }

    fun submit() {
        if (busy) return
        error = null
        val title = name.trim().ifEmpty { "My playlist" }
        var useXtream = xtream
        var s = server.trim()
        var u = user.trim()
        var pw = pass.trim()
        val link = m3u.trim()
        if (newPin.isNotEmpty() && !Pin.isValid(newPin)) return run { error = "The PIN must be exactly 6 digits." }
        val pinHash = newPin.takeIf { it.isNotEmpty() }?.let { Pin.hash(it) }
        if (!useXtream) {
            if (link.isEmpty()) return run { error = "Enter the M3U link." }
            // An Xtream panel's own M3U link: its login is used instead (faster, with movies, series and the guide).
            XtreamCredentials.fromM3uUrl(link)?.let {
                useXtream = true
                s = it.baseUrl
                u = it.username
                pw = it.password
            }
        } else if (s.isEmpty() || u.isEmpty() || pw.isEmpty()) {
            return run { error = "Enter the server address, username and password." }
        }
        busy = true
        scope.launch {
            var created = -1L
            try {
                val repo = graph.repo
                val e = editing
                val pid: Long
                if (useXtream) {
                    val creds = XtreamCredentials(s, u, pw)
                    info = "Signing in…"
                    graph.syncer.login(creds)
                    pid = if (e != null) {
                        repo.updatePlaylist(e.id, title, Playlist.KIND_XTREAM, creds.baseUrl, u, pw, pinHash)
                        e.id
                    } else {
                        repo.addPlaylist(title, Playlist.KIND_XTREAM, creds.baseUrl, u, pw, pinHash).also { created = it }
                    }
                } else {
                    pid = if (e != null) {
                        repo.updatePlaylist(e.id, title, Playlist.KIND_M3U, link, null, null, pinHash)
                        e.id
                    } else {
                        repo.addPlaylist(title, Playlist.KIND_M3U, link, null, null, pinHash).also { created = it }
                    }
                }
                val p = repo.playlist(pid) ?: error("Playlist not saved")
                val progressJob = launch {
                    graph.syncProgress.collect { if (it.isNotEmpty()) info = it }
                }
                val err = graph.reload(p)
                progressJob.cancel()
                if (err != null) throw java.io.IOException(err)
                graph.prefs.activePlaylist = pid
                graph.prefs.lastLiveCategory = null
                graph.prefs.lastLiveItem = null
                toaster.show("${p.name} is ready")
                nav.home()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (created > 0) graph.repo.deletePlaylist(created)
                error = e.message ?: "Couldn't load the playlist"
                busy = false
                info = ""
            }
        }
    }

    ThemedBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(if (id == null) "Add playlist" else "Edit playlist", { if (!busy) nav.back() })
            Column(Modifier.widthIn(max = 760.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WtButton("Xtream login", { xtream = true }, primary = xtream)
                    WtButton("M3U link", { xtream = false }, primary = !xtream)
                }
                Spacer(Modifier.height(16.dp))
                Field("Name", name, { name = it }, "My playlist")
                if (xtream) {
                    Field("Server address", server, { server = it }, "http://example.com:8080")
                    Field("Username", user, { user = it })
                    Field("Password", pass, { pass = it }, password = true)
                } else {
                    Field("M3U link", m3u, { m3u = it }, "http://…/get.php?username=…  or  https://…/list.m3u")
                }
                Field(
                    if (id == null) "PIN (6 digits; empty: 000000)" else "New PIN (6 digits; empty: keep it)",
                    newPin, { v -> if (v.length <= Pin.LENGTH && v.all { it.isDigit() }) newPin = v }, password = true,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WtButton(if (id == null) "Add and load" else "Save and load", { submit() }, primary = true, enabled = !busy)
                    if (busy) {
                        Spacer(Modifier.width(16.dp))
                        CircularProgressIndicator(Modifier.size(24.dp), color = wt.accent, strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(info, color = wt.textSecondary, fontSize = 14.sp)
                    }
                }
                error?.let { Text(it, color = wt.error, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
                Spacer(Modifier.height(20.dp))
                Text(
                    "WorldTV has no channels of its own: add the login or link your IPTV provider gave you. " +
                        "Logins are kept on this computer only.",
                    color = wt.textSecondary,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, hint: String? = null, password: Boolean = false) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        placeholder = hint?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

// ---- channel check ----

/** Settings > Channel check: which Live TV categories of the playlist in use are checked, and how far it got. */
@Composable
private fun ChannelCheckScreen() {
    val nav = LocalNav.current
    val wt = LocalWt.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var playlist by remember { mutableStateOf<Playlist?>(null) }
    var cats by remember { mutableStateOf<List<CategoryRow>>(emptyList()) }
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    val filter = remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    val progress by graph.channelCheck.progress.collectAsState()

    LaunchedEffect(Unit) {
        val p = graph.activePlaylist() ?: return@LaunchedEffect
        playlist = p
        cats = graph.repo.categories(p.id, ContentType.LIVE).filter { !Repository.isBuiltIn(it.key) }
        chosen = graph.repo.checkCategories(p.id)
    }
    LaunchedEffect(playlist, progress) {
        while (true) {
            playlist?.let { summary = checkSummary(it) }
            delay(5000)
        }
    }

    fun save(now: Boolean) {
        val p = playlist ?: return
        scope.launch {
            graph.repo.setCheckCategories(p.id, chosen)
            if (chosen.isEmpty()) return@launch toaster.show("Channel check is off for ${p.name}")
            graph.channelCheck.request(p.id, fresh = true, now = now)
            toaster.show(if (now) "Checking now" else "Saved: it checks after a minute with nothing playing")
            summary = checkSummary(p)
        }
    }

    ThemedBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar("Channel check", { nav.back() }, subtitle = playlist?.name) {
                SearchField(filter, "Filter, e.g. UK", Modifier.width(260.dp))
                WtButton("Save", { save(false) })
                WtButton("Check now", { save(true) }, primary = true)
            }
            Column(Modifier.padding(horizontal = 32.dp)) {
                Text(
                    "Each chosen channel is opened for a few seconds, one at a time, when nothing plays from this server. " +
                        "Channels that work get a green dot in Live TV; those that don't are dimmed (they still play). " +
                        "Checked again after every reload of the playlist.",
                    color = wt.textSecondary, fontSize = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(summary + (progress?.let { "  ·  now $it" } ?: ""), color = wt.textPrimary, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val shown = cats.filter { filter.value.isBlank() || it.name.contains(filter.value.trim(), ignoreCase = true) }
                    WtButton("Select shown (${shown.size})", { chosen = chosen + shown.map { it.key } })
                    WtButton("Clear shown", { chosen = chosen - shown.map { it.key }.toSet() })
                    Text("${chosen.size} chosen", color = wt.textSecondary, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
            val shown = cats.filter { filter.value.isBlank() || it.name.contains(filter.value.trim(), ignoreCase = true) }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
                items(shown, key = { it.key }) { c ->
                    val on = c.key in chosen
                    ListRow(selected = false, onClick = { chosen = if (on) chosen - c.key else chosen + c.key }, modifier = Modifier.widthIn(max = 800.dp)) {
                        Checkbox(on, { chosen = if (on) chosen - c.key else chosen + c.key }, colors = CheckboxDefaults.colors(checkedColor = wt.accent))
                        Spacer(Modifier.width(8.dp))
                        Text(c.name, color = wt.textPrimary, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

// ---- hidden categories ----

@Composable
private fun HiddenCategoriesScreen() {
    val nav = LocalNav.current
    val wt = LocalWt.current
    val scope = rememberCoroutineScope()
    var playlist by remember { mutableStateOf<Playlist?>(null) }
    var type by remember { mutableStateOf(ContentType.LIVE) }
    var cats by remember { mutableStateOf<List<AdminCategory>>(emptyList()) }
    val filter = remember { mutableStateOf("") }
    var bump by remember { mutableIntStateOf(0) }
    var unlocked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { playlist = graph.activePlaylist() }
    LaunchedEffect(type, bump, unlocked) {
        val p = playlist ?: return@LaunchedEffect
        if (unlocked) cats = graph.repo.categoriesForAdmin(p.id, type)
    }
    val locked = playlist
    if (locked != null && !unlocked) {
        ThemedBackground {}
        PinDialog(locked, "Hiding categories", onDismiss = { nav.back() }) { unlocked = true }
        return
    }
    ThemedBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar("Hidden categories", { nav.back() }, subtitle = playlist?.name) {
                SearchField(filter, "Filter", Modifier.width(240.dp))
            }
            Row(Modifier.padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(ContentType.LIVE to "Live TV", ContentType.MOVIE to "Movies", ContentType.SERIES to "Series").forEach { (t, label) ->
                    WtButton(label, { type = t }, primary = t == type)
                }
            }
            Text(
                "Unticked categories are left out everywhere, also from All, Favourites and search. Adult categories are hidden by themselves.",
                color = wt.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
            HorizontalDivider(color = wt.divider)
            val shown = cats.filter { filter.value.isBlank() || it.row.name.contains(filter.value.trim(), ignoreCase = true) }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
                items(shown, key = { it.row.key }) { c ->
                    val visible = !c.hidden
                    fun toggle() {
                        val p = playlist ?: return
                        scope.launch {
                            graph.repo.setHidden(p.id, type, listOf(c.row.key), hidden = visible)
                            bump++
                        }
                    }
                    ListRow(selected = false, onClick = { toggle() }, modifier = Modifier.widthIn(max = 800.dp)) {
                        Checkbox(visible, { toggle() }, colors = CheckboxDefaults.colors(checkedColor = wt.accent))
                        Spacer(Modifier.width(8.dp))
                        Text(c.row.name, color = if (visible) wt.textPrimary else wt.textSecondary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (c.hiddenEntries > 0) Text("${c.hiddenEntries} hidden inside", color = wt.textSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ---- help ----

@Composable
private fun HelpScreen() {
    val nav = LocalNav.current
    val wt = LocalWt.current
    val rows = listOf(
        "Everywhere" to listOf(
            "Esc" to "Back (and out of full screen)",
            "F11" to "Window full screen",
            "Ctrl+F" to "Search the list",
        ),
        "Live TV" to listOf(
            "Click" to "Play in the preview",
            "Double click, F" to "Full screen",
            "Up / Down" to "Move in the list; in full screen: change channel",
            "Page Up / Down" to "10 channels at a time; in full screen: change channel",
            "Left / Right" to "Between categories and channels",
            "Enter" to "Play; again: full screen",
            "0, Backspace" to "Back to the channel before",
            "Right click, Menu key" to "Favourites, groups, catch-up",
        ),
        "Films and episodes" to listOf(
            "Space, click" to "Pause / play",
            "Left / Right" to "10 s back / forward (with Shift: 1 min)",
            "Page Up / Down" to "Previous / next episode",
            "Up / Down, wheel" to "Volume",
        ),
        "Picture and sound" to listOf(
            "M" to "Mute",
            "+ / -" to "Volume",
            "A" to "Next audio language",
            "S" to "Next subtitles / off",
            "Z" to "Picture shape: fit, fill, stretch, 16:9, 4:3",
        ),
    )
    ThemedBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar("Help", { nav.back() })
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 8.dp)) {
                Text(
                    "Add your provider's playlist in Settings › Add playlist: an Xtream login (server, username, password) " +
                        "or an M3U link. Live TV, Movies and Series are on the home screen. Playback uses VLC, " +
                        "so almost every stream plays.",
                    color = wt.textPrimary, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.widthIn(max = 900.dp),
                )
                rows.forEach { (section, keys) ->
                    Section(section)
                    keys.forEach { (k, what) ->
                        Row(Modifier.padding(vertical = 3.dp, horizontal = 8.dp)) {
                            Box(Modifier.width(220.dp)) { Text(k, color = wt.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                            Text(what, color = wt.textSecondary, fontSize = 14.sp)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
