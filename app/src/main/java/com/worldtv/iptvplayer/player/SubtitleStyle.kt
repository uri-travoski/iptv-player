package com.worldtv.iptvplayer.player

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.worldtv.iptvplayer.Prefs

/** Settings > Subtitle settings: size, colour and background of subtitles, and the choices offered. */
@OptIn(UnstableApi::class)
object SubtitleStyle {

    val SIZE_LABELS = listOf("Small", "Normal", "Large", "Extra large", "Huge")
    private val SIZES = floatArrayOf(0.8f, 1f, 1.25f, 1.5f, 1.8f)

    val COLOR_LABELS = listOf("White", "Yellow", "Green", "Cyan", "Pink")
    private val COLORS = intArrayOf(Color.WHITE, 0xFFFFE94D.toInt(), 0xFF9BE36A.toInt(), 0xFF6FE3F2.toInt(), 0xFFFF9CC2.toInt())

    val BACKGROUND_LABELS = listOf("Outline only", "Black box", "See-through box")

    /** Preferred subtitle languages (ISO 639-1), first is "the box's language". */
    val LANGUAGES = listOf(
        "" to "Box language", "en" to "English", "ar" to "Arabic", "hi" to "Hindi", "ur" to "Urdu", "pa" to "Punjabi",
        "ta" to "Tamil", "te" to "Telugu", "ml" to "Malayalam", "bn" to "Bengali", "fr" to "French", "es" to "Spanish",
        "pt" to "Portuguese", "de" to "German", "it" to "Italian", "nl" to "Dutch", "tr" to "Turkish", "el" to "Greek",
        "pl" to "Polish", "ro" to "Romanian", "ru" to "Russian", "fa" to "Persian", "he" to "Hebrew", "id" to "Indonesian",
        "tl" to "Filipino", "vi" to "Vietnamese", "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean",
    )

    fun languageLabel(code: String) = LANGUAGES.firstOrNull { it.first == code }?.second ?: code

    /** Applies the chosen look to the app's one player view. Cheap: call after every change. */
    fun apply(view: PlayerView, prefs: Prefs) {
        val sv = view.subtitleView ?: return
        val fg = COLORS.getOrElse(prefs.subsColor) { Color.WHITE }
        val style = when (prefs.subsBackground) {
            1 -> CaptionStyleCompat(fg, Color.BLACK, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, null)
            2 -> CaptionStyleCompat(fg, 0x99000000.toInt(), Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, null)
            else -> CaptionStyleCompat(fg, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null)
        }
        // The chosen look wins over styles some subtitle files carry (colours, font sizes).
        sv.setApplyEmbeddedStyles(false)
        sv.setStyle(style)
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * SIZES.getOrElse(prefs.subsSize) { 1f })
    }
}
