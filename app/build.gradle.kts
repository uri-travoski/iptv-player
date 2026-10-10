import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.sardinemehico.iptvplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.sardinemehico.iptvplayer"
        minSdk = 24
        targetSdk = 36
        versionCode = 32
        versionName = "0.1.31"
        // English only for now; keeps unused library translations out of the APK.
        resourceConfigurations += listOf("en")
        // TV boxes are ARM. Keeps the FFmpeg audio decoder to two ~1.4 MB native libraries.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        // TEST KEY ONLY. Lets CI builds install over each other on test boxes.
        // Replace with a private key (GitHub secret) before any public release.
        create("test") {
            storeFile = rootProject.file("keystore/test-release.jks")
            storePassword = "iptvtest123"
            keyAlias = "iptvtest"
            keyPassword = "iptvtest123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("test")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.recyclerview)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.ffmpeg.decoder)
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.profileinstaller)

    testImplementation(libs.junit)
}
