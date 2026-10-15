import org.jetbrains.compose.desktop.application.dsl.TargetFormat

/*
 * WorldTV for Linux desktops and laptops: Compose for Desktop, VLC (libVLC 3 through vlcj) for
 * playback. The Android app's pure-Kotlin data layer (parsers, database, playlist loading, guide,
 * channel check analyser) is compiled in from app/src/main unchanged; the few Android classes it
 * touches (SQLite, ContentValues, Log) are small stand-ins under src/main/kotlin/android/.
 *
 * Packages (.deb, .rpm, AppImage, Flatpak) are made from `createReleaseDistributable` by
 * the scripts in packaging/linux, on GitHub when a release is published (.github/workflows/linux.yml).
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

/** The release tag (CI passes -PappVersion), else the Android app's versionName. */
val appVersion: String = (findProperty("appVersion") as String?)?.takeIf { it.isNotBlank() }
    ?: Regex("""versionName\s*=\s*"([^"]+)"""").find(rootProject.file("app/build.gradle.kts").readText())!!.groupValues[1]

/** The Android sources shared with the desktop app: no Android imports beyond the stand-ins. */
val sharedSources = tasks.register<Sync>("sharedSources") {
    from(rootProject.file("app/src/main/java")) {
        include(
            "com/worldtv/iptvplayer/data/model/**",
            "com/worldtv/iptvplayer/data/source/**",
            "com/worldtv/iptvplayer/data/repo/**",
            "com/worldtv/iptvplayer/data/db/**",
            "com/worldtv/iptvplayer/data/sync/**",
            "com/worldtv/iptvplayer/data/online/**",
            "com/worldtv/iptvplayer/data/update/**",
            "com/worldtv/iptvplayer/data/check/TsStats.kt",
            "com/worldtv/iptvplayer/data/check/ChannelProbe.kt",
            "com/worldtv/iptvplayer/data/spotlight/SpotlightPicker.kt",
            "com/worldtv/iptvplayer/data/net/AppDns.kt",
            "com/worldtv/iptvplayer/AutoRefresh.kt",
        )
    }
    into(layout.buildDirectory.dir("shared/kotlin"))
}

/** Version and repository, read by the app (About, update check). */
val buildInfo = tasks.register("buildInfo") {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    val version = appVersion
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/worldtv/desktop/BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            """
            |package com.worldtv.desktop
            |
            |object BuildInfo {
            |    const val VERSION = "$version"
            |}
            |""".trimMargin(),
        )
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.ui.ExperimentalComposeUiApi",
            "androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
    sourceSets.main {
        kotlin.srcDir(sharedSources)
        kotlin.srcDir(buildInfo)
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coroutines.swing)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.vlcj)
    implementation(libs.sqlite.jdbc)
    implementation(libs.org.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.onnxruntime)
    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "com.worldtv.desktop.MainKt"
        jvmArgs += listOf("-Xmx1g", "-Dsun.java2d.uiScale.enabled=true")
        nativeDistributions {
            // Packages are built by packaging/linux/ from the distributable (app image).
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "worldtv"
            packageVersion = appVersion
            description = "WorldTV IPTV player"
            vendor = "WorldTV"
            licenseFile.set(rootProject.file("LICENSE"))
            // java.sql for SQLite, jdk.unsupported for JNA (VLC), java.naming for OkHttp.
            modules("java.sql", "jdk.unsupported", "java.naming", "jdk.crypto.ec", "java.net.http")
            linux {
                iconFile.set(project.file("packaging/worldtv.png"))
                packageName = "worldtv"
                appCategory = "AudioVideo"
                menuGroup = "AudioVideo;Video;Player;TV"
            }
        }
        // Release builds drop unused classes (most of the icon set): the app shrinks by ~40 MB.
        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
            obfuscate.set(false)
            optimize.set(false)
        }
    }
}
