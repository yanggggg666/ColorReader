package com.oceanreader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

private val sansSerif = FontFamily.SansSerif
private val serif = FontFamily.Serif
private val mono = FontFamily.Monospace
private val rounded = FontFamily.SansSerif

val ReaderTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = rounded, fontWeight = FontWeight.Bold,
        fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = (-0.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = rounded, fontWeight = FontWeight.Bold,
        fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = (-0.4).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = rounded, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 36.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = rounded, fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp, lineHeight = 31.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = rounded, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = rounded, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = rounded, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontFamily = rounded, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = sansSerif, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = sansSerif, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = sansSerif, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = rounded, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = rounded, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = rounded, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 15.sp
    )
)

val SansStyle = sansSerif
val SerifStyle = serif
val MonoStyle = mono
val RoundedStyle = rounded

val BookTitleStyle = TextStyle(
    fontFamily = serif, fontWeight = FontWeight.Bold,
    fontSize = 26.sp, lineHeight = 34.sp
)

val BookBodyStyle = TextStyle(
    fontFamily = serif, fontWeight = FontWeight.Normal,
    fontSize = 17.sp, lineHeight = 28.sp
)

fun bodyLineHeightStyle(alignment: Int) = LineHeightStyle(
    alignment = LineHeightStyle.Alignment(alignment, alignment),
    trim = LineHeightStyle.Trim.None
)