package com.worldtv.iptvplayer.data.help

/**
 * Settings > Help: the user guide, written once for the TV / phone app and the Linux app. Each
 * topic is a heading and a list of points; [topics] leaves out what a platform doesn't have and
 * words the controls its way (OK / tap / click). Shown as a list of topics beside the text.
 */
object HelpGuide {

    enum class Platform { TV, MOBILE, LINUX }

    class Topic(val group: String, val title: String, val points: List<String>)

    private class Builder(val p: Platform) {
        val topics = ArrayList<Topic>()
        private var group = ""
        private var points: ArrayList<String>? = null

        val tv get() = p == Platform.TV
        val linux get() = p == Platform.LINUX
        val android get() = p != Platform.LINUX

        /** "OK", "Tap" or "Click" (start of a sentence); [ok2] mid-sentence. */
        val ok get() = when (p) { Platform.TV -> "OK"; Platform.MOBILE -> "Tap"; Platform.LINUX -> "Click" }
        val ok2 get() = ok.lowercase().let { if (p == Platform.TV) "OK" else it }

        /** The "more options" action: Menu on a remote, long-press on touch, right click. */
        val menu get() = when (p) { Platform.TV -> "Menu (or hold OK)"; Platform.MOBILE -> "Long-press"; Platform.LINUX -> "Right click" }
        val back get() = when (p) { Platform.TV -> "Back"; Platform.MOBILE -> "Back"; Platform.LINUX -> "Esc (or ← top left)" }
        val settings get() = if (linux) "Settings (⚙ on the home screen)" else "App Settings (⋮ Menu top right of the home screen)"

        fun group(name: String) { group = name }

        fun topic(title: String, only: Set<Platform>? = null, body: Builder.() -> Unit) {
            if (only != null && p !in only) return
            val list = ArrayList<String>()
            points = list
            body()
            points = null
            if (list.isNotEmpty()) topics += Topic(group, title, list)
        }

        fun all(s: String) { points!! += s }
        fun tv(s: String) { if (tv) all(s) }
        fun mobile(s: String) { if (p == Platform.MOBILE) all(s) }
        fun android(s: String) { if (android) all(s) }
        fun linux(s: String) { if (linux) all(s) }
    }

    private val ANDROID = setOf(Platform.TV, Platform.MOBILE)
    private val TV_ONLY = setOf(Platform.TV)

    fun topics(p: Platform): List<Topic> = Builder(p).apply {

        group("Getting started")

        topic("What WorldTV is") {
            all("WorldTV plays your IPTV subscription: live TV channels, movies and series from your provider. It doesn't sell or include any channels itself.")
            all("Everything comes from the playlist you add: an Xtream login (server, username and password) or an M3U link from your provider.")
            all("The home screen opens Live TV, Movies and Series. $settings holds everything else, including this Help.")
            android("Free TV, a free playlist of about 2,000 free-to-air channels from around the world (live TV only), is there to start with, so there is something to watch before you add your own.")
        }

        topic("First start") {
            android("Choose TV (a TV box with a remote) or Mobile (a phone or tablet with touch). You can change it later: App Settings > Layout.")
            android("Free TV loads first; a \"Please wait\" screen shows until it is ready.")
            all("Add your own playlist: $settings > Add playlist (see \"Adding a playlist\").")
            all("As the person setting it up: hide the categories nobody needs (Categories (show / hide)) and change the playlist PIN from 000000 to your own, so nobody can undo your choices. Adult content is already hidden.")
            all("Choose the Live TV categories to check in Channel check: you'll then see which channels work, and those categories move to the top of Live TV.")
        }

        topic("TV or Mobile layout", ANDROID) {
            all("TV: big tiles and lists for the remote. Up / Down / Left / Right move, OK selects, Back goes back, Menu opens more options for the thing you're on.")
            all("Mobile: portrait screens for touch. Tap selects, long-press works like Menu. Video in full screen turns the phone sideways.")
            all("Change it any time: App Settings > Layout. The screens are rebuilt straight away.")
        }

        topic("Moving around", setOf(Platform.LINUX)) {
            all("Mouse: click to choose, double click a channel for full screen, right click for more options (favourites, groups, catch-up).")
            all("Keyboard: the arrow keys move, Enter chooses, Esc goes back (and leaves full screen), F11 makes the window full screen, Ctrl+F jumps to the search box.")
            all("The full list of keys is in \"Keyboard shortcuts\".")
        }

        topic("Adding a playlist") {
            all("$settings > Add playlist.")
            all("Xtream Codes: enter the server address (for example http://example.com:8080), the username and the password from your provider.")
            all("M3U link: paste the playlist link. If it is an Xtream link (get.php?username=…), WorldTV signs in with Xtream by itself, which is faster and adds movies, series, the TV guide and catch-up.")
            all("PIN: leave it empty for 000000, or type your own 6 digits.")
            all("Saving loads everything: channels, movies and series. Big playlists can take a few minutes on a slow box; let it finish.")
            all("Adult content is hidden from that first load on.")
        }

        group("Watching")

        topic("Home screen") {
            all("Live TV, Movies and Series open the libraries of the active playlist.")
            all("Top right: how long the subscription is valid (for example Valid Till: 07-Mar-2027), ⟳ Reload playlist and ${if (linux) "⚙ Settings" else "⋮ Menu (App Settings, All apps, System settings)"}.")
            android("Subscription reminders: 3 days and 2 days before that date a reminder appears, and once more when it has run out. Each shows once; renewing starts them over.")
            all("Reload playlist downloads everything again: new channels, movies and series, and anything removed goes. Favourites, your groups, Continue watching, sorting and your show / hide choices are kept. It also happens by itself (see \"Automatic reload\").")
            tv("Under the big tiles your own apps can sit in a row: ⋮ Menu > Add app to home screen. Menu on one changes or removes it. App Settings > Home app slots sets how many (none to start with).")
            tv("When you open WorldTV, Live TV is already selected: press OK to go straight to your channels.")
        }

        topic("The picture slider", setOf(Platform.TV, Platform.LINUX)) {
            all("Behind the home screen, a slider shows 5 recent movies, then 3 recent series, that are in your playlist, with large pictures. They change every 8 seconds.")
            tv("Press Up to reach More info: Left / Right change the title, OK opens its page (play it from there).")
            linux("More info opens the title's page.")
            all("Only family-friendly titles: movies rated PG-13 or lower, series rated TV-14 / M or lower, no horror. Every picture is also checked on the device by an image filter that leaves out anything revealing.")
            all("The titles are picked once a day, after the 3 am reload, from the active playlist.")
            all("If one still isn't right: ${if (linux) "right click More info" else "Menu on More info"} > Hide from the home screen (needs the playlist PIN). Another title takes its place.")
            all("$settings > Backdrop region: show only titles in the languages you tick (English, Indian languages, French, Spanish, German, Italian, Korean, Japanese; several at once). Each language is judged by its own country's family ratings. None ticked: every language.")
            all("Titles and pictures come from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.")
        }

        topic("Live TV") {
            when (p) {
                Platform.TV -> all("Categories on the left, channels in the middle, a preview on the right.")
                Platform.MOBILE -> all("The preview is on top, categories run in a strip you can swipe sideways, channels are below.")
                Platform.LINUX -> all("Categories on the left, channels in the middle, the preview on the right. Each list has its own search box: Search categories and Search channels.")
            }
            all("Live TV opens where you left it: the last category, with the last channel you watched selected. Nothing plays until you choose a channel.")
            all("$ok on a channel plays it in the preview; $ok2 again (${if (linux) "or double click, or F" else "or a tap on the preview"}) goes full screen.")
            all("Order of the category list: your own groups, ${if (android) "Most watched, " else ""}All, Favourites, then the categories you chose in Channel check, then the rest in your provider's order. If you put the categories in your own order (Settings > Categories order), your order is used instead.")
            all("In full screen: ${if (linux) "Up / Down or Page Up / Page Down" else "Up / Down or CH+ / CH-"} change channel, ${if (linux) "moving the mouse" else "OK"} shows the controls, $back steps back.")
            all("${if (linux) "0 or Backspace" else "0 (or the Last / Recall key on the remote, or ⇄ in full screen)"} goes back to the channel you watched before; again returns. Handy for flipping between two channels.")
            all("$menu on a channel: Favourites, your groups and catch-up.")
            all("Channels in categories you chose for Channel check have a green dot ● when they work; the ones that didn't work are dimmed. A dimmed channel can still be played.")
        }

        topic("TV guide and catch-up") {
            all("Now and next: when your provider has a TV guide for a channel, what is on now (with a bar showing how far in it is) and what is on next show under the preview and in the full-screen banner. Not every provider or channel has one.")
            all("Catch-up: on channels where your provider keeps recordings, ⟲ Catch-up shows ${if (linux) "under the preview and in the right-click menu" else "under the preview, in the Menu and as a button in full screen"}. It lists the earlier programmes you can watch again, newest first; one plays like a movie, with pause and seeking. $back returns to live TV.")
            all("Channels without recordings don't show it.")
        }

        topic(if (p == Platform.LINUX) "Favourites and your groups" else "Favourites, your groups and Most watched") {
            all("Favourites is a category at the top of each library. Add: $menu on a channel${if (android) " or poster, or the ★ button in the Live TV player" else ", or Add to favourites on a movie's or series' page"}.")
            all("Your own groups (Live TV): ＋ Add group at the top of the category list makes one; groups sit at the very top. $menu on a channel adds it to a group, or makes a new group with it (＋ New group…). $menu on a group renames or deletes it (its channels stay in the playlist).")
            android("Most watched (Live TV): the channels you really watch. A channel joins after you watch it 5 minutes or more on 3 occasions within 30 days; only time with the picture playing counts, and a stretch of over 4 hours (a box left on) doesn't. The more often and the more lately you watch, the higher it is; channels you stop watching slowly drop off. Up to 25 channels, never adult or hidden ones. $menu on one: Remove from Most watched.")
            all("Favourites and groups are kept when the playlist reloads and when you Clear history. Each playlist has its own.")
        }

        topic("Movies and Series") {
            all("Categories on the left, posters on the right. $ok opens a title's page.")
            all("Movie page: Play, or Resume and From the start if you watched part of it. Series page: choose a season, then an episode.")
            all("Sort (top right): Newest (latest added first, the default), Top rated, A–Z, Z–A or Provider's order. Movies and Series remember their own.")
            android("Menu (long-press) on a poster adds it to or removes it from Favourites.")
            linux("Add to favourites on a title's page adds it to Favourites (again: removes it).")
            all("Every poster with a rating shows it (★ 7.3 out of 10). A title's page shows the rating, year, length, genre, director, plot and when it was added.")
            all("Trailer opens the trailer on YouTube; if the provider has no trailer link, a YouTube search for it.")
            android("Cast: photos with names, from TMDB (with a TMDB key) or Wikipedia. OK (tap) on a photo lists that actor's movies and series in your playlist.")
            linux("Cast: the actors' photos and names.")
        }

        topic("Watch progress and Continue watching") {
            all("Posters of titles you started show a bar along the bottom: how far you got.")
            all("Series posters also show the episode to carry on with, for example S1 E6.")
            all("On a series page every episode shows how far you got: ✓ (and dimmed) when finished, \"▶ 12 min in\" when started. The Resume button picks up the last episode you didn't finish.")
            all("Only real watching counts: under 30 seconds isn't kept. The last few minutes (credits) count as finished, and a finished movie leaves Continue watching.")
            all("Continue watching (a category in Movies and in Series) lists what you stopped part-way, latest first. Adult titles are never listed.")
            all("$settings > Clear history empties it; each playlist keeps its own.")
        }

        topic("Search") {
            all("The search box is at the top of each list${if (linux) " (Ctrl+F)" else ""}.")
            all("It finds channel and title names, and categories too: searching \"cricket\" shows a \"Search: cricket\" row with the matching channels, and every category with cricket in its name.")
            tv("The keyboard's Search key jumps to the results.")
            all("Clear the box to get the whole list back.")
        }

        topic("Player controls") {
            if (linux) {
                all("Move the mouse to show the controls; they hide by themselves.")
                all("Space or a click pauses and plays. Left / Right: 10 seconds back / forward (with Shift: 1 minute). Drag the bar to jump.")
                all("Up / Down or the mouse wheel: volume. M: mute. A: next audio language. S: next subtitles / off. Z: picture shape (fit, fill, stretch, 16:9, 4:3).")
                all("Page Up / Page Down: previous / next episode. F or double click: full screen. Esc: back.")
                all("Playback uses VLC, so almost every stream plays.")
            } else {
                all("$ok shows the controls; they hide after a few seconds. The selected button's name shows above it${if (p == Platform.MOBILE) " (long-press a button)" else ""}.")
                all("Buttons: audio language, subtitles, previous / next (channel or episode), 10 s back, play / pause, 30 s forward, picture (Fit, Stretch, Zoom), and Open in… (another player, for streams WorldTV can't play).")
                all("Movies and episodes: ${if (tv) "Left / Right jump 10 s back and 30 s forward while the controls are hidden" else "drag the bar to jump"}. The next episode starts by itself at the end of one.")
                mobile("The arrow top left leaves full screen.")
            }
        }

        topic("Subtitles", ANDROID) {
            all("Subtitles come from the video itself (most MKV files have some, many MP4 files none) and, for movies and episodes, from OpenSubtitles.com once it is set up.")
            all("In the player: the subtitles button lists the built-in ones, Off, and Search online…")
            all("Search online… looks the title up on OpenSubtitles in your language, then English. Pick one and it appears within a few seconds, in time with the video.")
            all("Setting it up (once, free): make an account at opensubtitles.com, then Profile > API consumers > New consumer, and add the key in App Settings > Online accounts > OpenSubtitles.")
            all("App Settings > Subtitle settings: show them automatically, language, size (Small to Huge), colour and background (outline, black box or see-through box).")
        }

        group("Channel check")

        topic("Channel check: which channels work") {
            all("$settings > Channel check: choose the Live TV categories to check (type a few letters, for example AU, to find them). Save, or Check now to start at once.")
            all("WorldTV then tries every channel in those categories in the background: each one is opened for a few seconds and measured, without showing or playing anything. Channels that only show a black screen (for example event channels between events) count as not working.")
            all("In Live TV, working channels get a green dot ● and the others are dimmed.")
            all("The categories you chose move to the top of the Live TV category list, just under Favourites, so the channels that matter are first. If you put the categories in your own order (Categories order), your order wins.")
            all("The tile shows how far it got, for example \"33 / 335 checked · 2 min 10 s so far\", and when it is done, how long it took.")
        }

        topic("Channel check: when it runs") {
            all("After the playlist is added or reloaded: so every night, after the 3 am reload, once nothing has played for a minute.")
            all("Only while nothing is playing: most accounts allow one connection at a time, so pressing play stops a check at once; it carries on later from where it stopped.")
            all("If the provider turns WorldTV away (too many connections, the account in use elsewhere), nothing is marked and it tries again later.")
            all("Results count for 7 days. After that (for example the box was off), channels look normal again until the next check.")
            all("Each check downloads a little of every channel (about 0.5 to 2 MB each): on a mobile data plan choose only the categories you need. Choose none to turn it off.")
        }

        group("Organising")

        topic("Showing and hiding categories and channels") {
            all("$settings > ${if (linux) "Hidden categories" else "Categories (show / hide)"}, then the playlist PIN.")
            android("Tabs: Live TV, Movies, Series. ✓ = shown, ✕ = hidden. OK (tap) on a category shows or hides all of it.")
            linux("Tick the categories to hide in Live TV, Movies and Series; a category with hidden channels inside shows how many.")
            android("Menu (long-press) on a category lists its channels or titles, to show or hide them one by one. A category with some hidden shows \"(N hidden)\".")
            all("Hidden means gone everywhere: the category list, All, Favourites, Continue watching, search and changing channel.")
            android("Show all goes back to the start: everything shown except adult content.")
            all("Your choices are kept on every reload.")
        }

        topic("Categories order and Live channel order", ANDROID) {
            all("App Settings > Categories order: put the categories of Live TV, Movies and Series in your own order (tabs at the top). OK (tap) on a category picks it up (⇅), Up / Down move it, OK again puts it down. Or use the buttons: Top, ▲ Up, ▼ Down, Bottom.")
            all("Until you move one, Live TV lists the categories in Channel check first. Once you have moved one, your whole order is kept. Default order goes back to the automatic one.")
            all("App Settings > Live channel order: choose a category, then put its channels in your own order the same way. Provider order in that category undoes it.")
            all("Saved at once and kept when the playlist reloads. Favourites, your groups and Most watched keep their own order.")
        }

        topic("Adult content") {
            all("Adult content is hidden automatically every time a playlist loads or reloads, new channels included.")
            all("Hidden: categories with words like XXX, Adult, 18+ in their name, channels and titles with adult studio names or explicit words, and anything the provider marks as adult.")
            all("Words also used in normal titles (sex, porn…) only hide something when two different ones appear together, so films like \"Sex Education\" stay.")
            all("It goes by name only: check the category list yourself. What you show or hide by hand always wins.")
            android("Adult words (on the Categories screen, PIN needed): the word lists, for categories and for channels and titles. Add or remove words; Restore defaults puts back the built-in lists. Leaving the screen checks every playlist again.")
            all("Adult movies and series are never kept in Continue watching.")
        }

        group("Settings")

        topic("Playlists and the PIN") {
            all("$settings > Playlists lists your playlists; the active one is marked. $ok on one to open, reload, edit or delete it.")
            all("Every playlist has a 6-digit PIN, 000000 to start with. ${if (linux) "Change it with Edit playlist." else "Change it under the playlist's details; Reset to 000000 puts the default back."}")
            all("The PIN protects the login details, the show / hide choices, the adult words, hiding slider titles, deleting the playlist and Reset app. It doesn't lock watching.")
            all("After 5 wrong PINs, entering one is blocked for 30 seconds.")
            all("Each playlist keeps its own favourites, groups, Continue watching, watch progress, Most watched, hidden categories, category and channel order and channel check. Theme, layout, backdrop region and the other settings are the same for every playlist.")
        }

        topic("Automatic reload") {
            all("$settings > ${if (linux) "Automatic refresh" else "Playlist Auto Reload"}: the active playlist downloads again by itself, so new channels, movies and series appear without pressing Reload playlist.")
            all("Every start, every day at 3 am (the default), or every 2 days at 3 am.")
            tv("At 3 am the box loads it in the background with the screen off. If the box was off then, it loads the next time the home screen shows.")
            all("You can keep watching meanwhile. If the server can't be reached, it tries again at the next start.")
        }

        topic("Online accounts (TMDB, OpenSubtitles)", ANDROID) {
            all("App Settings > Online accounts: your own free accounts for the services WorldTV can use. Add several of each: when one fails (key refused, daily limit, service down), the next is used by itself.")
            all("TMDB (themoviedb.org): cast photos, ratings and trailers on title pages, and an actor's whole filmography. Get a key: themoviedb.org > Settings > API.")
            all("OpenSubtitles (opensubtitles.com): subtitles from the internet (see \"Subtitles\").")
            all("OK on an account: Test, Edit, Move to the top, Remove. Use only your own accounts.")
        }

        topic("Look and start-up") {
            all("Theme: Classic (maroon), Glass (see-through panels on deep blue) or Showtime (indigo and orange). It changes at once.")
            android("Layout: TV or Mobile (see \"TV or Mobile layout\").")
            tv("Auto-start on boot: opens WorldTV when the box starts (some boxes need \"display over other apps\" allowed). Off to start with.")
            tv("Home screen: make WorldTV the box's home screen, instead of the box's own. All apps and System settings in ⋮ Menu are the way out.")
            all("DNS: 1.1.1.1 + 8.8.8.8 (encrypted, the default), just one of them, or Off (the ${if (linux) "computer's" else "box's"} own). Encrypted DNS helps when a provider's server can't be found.")
            all("Dates show as day-month-year, for example 10-Oct-2026.")
        }

        topic("Updates") {
            if (linux) {
                all("Check for updates at start (on to start with): WorldTV looks for a new version when it starts and says so. Check for updates now looks straight away.")
                all("New versions come as .deb, .rpm, AppImage and Flatpak from the WorldTV releases page on GitHub.")
            } else {
                all("Check for updates on start (on to start with): WorldTV looks for a new version when it starts and offers to install it. Update now looks straight away.")
                all("The first update may ask you to allow \"install unknown apps\" for WorldTV. Your playlists and settings stay.")
            }
        }

        topic("Clear history and Reset app") {
            android("Clear history: tick what to clear: Live TV (last channel and Most watched), Movies and Series (Continue watching and watch progress).")
            linux("Clear history forgets where movies and episodes were left: Continue watching and every resume point.")
            all("Favourites and groups are never cleared.")
            all("Reset app puts WorldTV back as if just installed: every playlist, favourites, groups, history, hidden categories, adult words and settings go. It asks to confirm, then the active playlist's PIN.")
            android("Only the TV / Mobile choice stays; Free TV is added again.")
        }

        group("Help")

        topic("Keyboard shortcuts", setOf(Platform.LINUX)) {
            all("Everywhere: Esc back (and out of full screen) · F11 window full screen · Ctrl+F search.")
            all("Live TV: click plays in the preview · double click or F full screen · Up / Down move (full screen: change channel) · Page Up / Down 10 at a time · Left / Right between categories and channels · Enter plays, again full screen · 0 or Backspace the channel before · right click or the Menu key: favourites, groups, catch-up.")
            all("Movies and episodes: Space or click pause / play · Left / Right 10 s (Shift: 1 min) · Page Up / Down previous / next episode · Up / Down or wheel volume.")
            all("Picture and sound: M mute · + / - volume · A audio language · S subtitles · Z picture shape.")
        }

        topic("Troubleshooting") {
            all("A channel doesn't play: try another. Event channels (sports, pay-per-view) only play during the event.${if (android) " Try Open in… with another player." else ""}")
            all("\"Not available\" or it stops at once: the provider isn't sending it, or your account is in use on another device (most allow 1 connection at a time).")
            all("Buffering: the provider's server is slow or far away. Try the DNS setting, or another time of day.")
            all("Nothing loads or sign-in fails: check the server address, username, password and the Valid Till date on the home screen.")
            all("A playlist takes long to load: big catalogues are slow on small boxes; let it finish.")
            android("No subtitles: the video has none built in; use Search online… in the player.")
            all("Something is hidden that shouldn't be, or the other way round: Categories (show / hide), or the adult words.")
            all("Channels look wrong after a while: Reload playlist gets the provider's latest list.")
        }
    }.topics
}
