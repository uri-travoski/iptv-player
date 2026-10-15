# WorldTV desktop: shrink only (no obfuscation, no optimisation). Libraries that load classes by
# name or through JNA keep everything.
-keep class com.worldtv.** { *; }
-keep class android.** { *; }
-keep class uk.co.caprica.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keep class org.sqlite.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.json.** { *; }
-keep class coil3.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class org.jetbrains.skia.** { *; }
-keep class org.jetbrains.skiko.** { *; }
-dontwarn **
-dontnote **
