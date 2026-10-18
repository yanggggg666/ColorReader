package com.oceanreader.ui.theme

import androidx.compose.ui.graphics.Color

/* ---------------------------------------------------------------------------
 *  ColorOS 17 inspired palette.
 *  Saturated "aurora" gradients, soft neutral surfaces, one loud accent.
 * ------------------------------------------------------------------------- */

val OceanBlue = Color(0xFF2F6BFF)
val OceanBlueDark = Color(0xFF9CB8FF)
val AuroraPurple = Color(0xFF7C5CFF)
val AuroraPurpleDark = Color(0xFFC7B8FF)
val AuroraTeal = Color(0xFF00C9B7)
val AuroraTealDark = Color(0xFF6FE7DA)
val AuroraOrange = Color(0xFFFF7A45)
val AuroraPink = Color(0xFFFF4D8D)

val Neutral0 = Color(0xFFFFFFFF)
val Neutral5 = Color(0xFFF6F8FC)
val Neutral10 = Color(0xFFEFF2F8)
val Neutral20 = Color(0xFFDCE2EE)
val Neutral30 = Color(0xFFB6BFD2)
val Neutral50 = Color(0xFF6B7488)
val Neutral70 = Color(0xFF3C4356)
val Neutral85 = Color(0xFF222736)
val Neutral90 = Color(0xFF171B26)
val Neutral95 = Color(0xFF10131C)
val Neutral98 = Color(0xFF090B11)

/** Accent gradients offered in settings; index is persisted. */
data class AccentGradient(
    val name: String,
    val start: Color,
    val end: Color
)

val AccentGradients = listOf(
    AccentGradient("海洋", OceanBlue, AuroraPurple),
    AccentGradient("极光", AuroraTeal, OceanBlue),
    AccentGradient("暮色", AuroraPurple, AuroraPink),
    AccentGradient("日落", Color(0xFFFF9A3C), Color(0xFFFF4D8D)),
    AccentGradient("薄荷", Color(0xFF2BD9A8), Color(0xFF0E9BD6)),
    AccentGradient("石墨", Color(0xFF6C7A99), Color(0xFF2B3245))
)

/** Page colours used inside the reading area. */
data class PagePalette(
    val id: String,
    val name: String,
    val background: Color,
    val text: Color,
    val secondary: Color,
    val isDark: Boolean
)

val PagePalettesLight = listOf(
    PagePalette("white", "纸白", Color(0xFFFFFFFF), Color(0xFF1A1A1A), Color(0xFF8A8A8A), false),
    PagePalette("sepia", "羊皮", Color(0xFFF6ECD9), Color(0xFF463A28), Color(0xFF9A8A70), false),
    PagePalette("green", "护眼", Color(0xFFDCEBD9), Color(0xFF25362A), Color(0xFF6C8672), false),
    PagePalette("gray", "雅灰", Color(0xFFE8EAEE), Color(0xFF23262D), Color(0xFF7C818C), false),
    PagePalette("blue", "天青", Color(0xFFE6EEFA), Color(0xFF1E2A3C), Color(0xFF75889E), false)
)

val PagePalettesDark = listOf(
    PagePalette("black", "纯黑", Color(0xFF000000), Color(0xFFB9B9B9), Color(0xFF5A5A5A), true),
    PagePalette("dark", "夜阑", Color(0xFF16181D), Color(0xFFB4B4B4), Color(0xFF5E6067), true),
    PagePalette("ink", "墨蓝", Color(0xFF101820), Color(0xFFAEBECB), Color(0xFF5A7182), true),
    PagePalette("brown", "暗棕", Color(0xFF231D18), Color(0xFFC2B09A), Color(0xFF6E6154), true)
)

val HighlightColors = listOf(
    Color(0xFFFFE082) to "暖黄",
    Color(0xFFA5D6A7) to "青草",
    Color(0xFF90CAF9) to "天蓝",
    Color(0xFFF48FB1) to "桃粉",
    Color(0xFFCE93D8) to "藕紫"
)

val UnderlineColors = listOf(
    Color(0xFFFF7043) to "橙",
    Color(0xFF26A69A) to "青",
    Color(0xFF5C6BC0) to "靛",
    Color(0xFF8D6E63) to "棕"
)