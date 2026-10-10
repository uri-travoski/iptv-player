# WorldTV

A lightweight IPTV player for low-spec Android TV boxes (1 GB RAM, ~1 GHz), modelled on IBO Player Pro.
Users bring their own M3U URL or Xtream Codes login. The app ships no channels, playlists or provider links.

**Status:** early test builds: Live TV, Movies, Series, search, Continue watching, playlist PIN, auto-start on boot.

- MVP document: https://claude.ai/code/artifact/f7b3b895-aef4-4a3d-9555-9848fd1bd09e
- Rules for contributors (and Claude Code): [CLAUDE.md](CLAUDE.md)

## Get a test APK

Each version's APK is built locally and attached to a **GitHub Release** (tag = version, e.g. `0.1.4`).

1. Open **Releases** on the repo page and pick the newest one.
2. Download `worldtv-<version>.apk`.
3. Sideload it onto the box (e.g. with `adb install -r worldtv-<version>.apk`, or a USB stick and a file manager).

The **Build** workflow in GitHub Actions no longer runs on every push; start it by hand from the Actions tab if needed.

Builds are signed with a **test key** committed in `keystore/` so they install over each other.
Do not publish an APK signed with that key outside test use.

## Build locally

Requires JDK 17 and the Android SDK (compileSdk 36).

```bash
./gradlew testReleaseUnitTest assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. CI fails the build if it reaches 15 MB.

## Layout

```
app/src/main/java/com/worldtv/iptvplayer/
  MainActivity.kt            single activity, plain Views
  data/model/Content.kt      ContentType, Category, Entry
  data/source/M3uParser.kt   streaming M3U/M3U8 parser
  data/source/Xtream.kt      Xtream login + URL builder
  data/source/XtreamParser.kt streaming parsers for player_api.php responses
  data/source/JsonPull.kt    tiny lenient streaming JSON reader
```

## License

WorldTV is free software: you can redistribute it and/or modify it under the terms of the
**GNU General Public License v3.0** (see [LICENSE](LICENSE)).

It bundles the [Jellyfin Media3 FFmpeg decoder](https://github.com/jellyfin/jellyfin-androidx-media)
(GPL-3.0), built on [FFmpeg](https://ffmpeg.org), for audio formats many TV boxes cannot decode
(Dolby AC-3/E-AC-3, MP2, DTS, TrueHD). Other libraries (AndroidX Media3, OkHttp, Coil, Kotlin
coroutines) are Apache-2.0.

