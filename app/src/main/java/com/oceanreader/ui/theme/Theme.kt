package com.oceanreader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Tokens for the frosted surfaces used across the shell. */
@Immutable
data class GlassTokens(
    val topBarTint: Color,
    val bottomBarTint: Color,
    val sheetTint: Color,
    val dialogTint: Color,
    val edge: Color,
    val card: Color,
    val cardElevated: Color,
    val divider: Color,
    val shadow: Color
)

val LocalGlass = staticCompositionLocalOf {
    GlassTokens(
        topBarTint = Color(0xCCFFFFFF),
        bottomBarTint = Color(0xD9FFFFFF),
        sheetTint = Color(0xF2FFFFFF),
        dialogTint = Color(0xFAFFFFFF),
        edge = Color(0x1F000000),
        card = Color(0xFFFFFFFF),
        cardElevated = Color(0xFFFFFFFF),
        divider = Color(0x14000000),
        shadow = Color(0x14000000)
    )
}

/** The accent gradient currently in use, exposed for hero surfaces. */
@Immutable
data class Accent(val start: Color, val end: Color) {
    fun brush(): Brush = Brush.linearGradient(listOf(start, end))
    fun horizontal(): Brush = Brush.horizontalGradient(listOf(start, end))
}

val LocalAccent = staticCompositionLocalOf { Accent(OceanBlue, AuroraPurple) }

private fun lightScheme(accent: Accent) = lightColorScheme(
    primary = accent.start,
    onPrimary = Color.White,
    primaryContainer = accent.start.copy(alpha = 0.14f),
    onPrimaryContainer = accent.start.darken(0.25f),
    secondary = accent.end,
    onSecondary = Color.White,
    secondaryContainer = accent.end.copy(alpha = 0.14f),
    onSecondaryContainer = accent.end.darken(0.25f),
    tertiary = AuroraTeal,
    onTertiary = Color.White,
    background = Neutral5,
    onBackground = Neutral85,
    surface = Neutral0,
    onSurface = Neutral85,
    surfaceVariant = Neutral10,
    onSurfaceVariant = Neutral50,
    outline = Neutral20,
    outlineVariant = Neutral10,
    error = Color(0xFFE53935),
    scrim = Color(0x66000000)
)

private fun darkScheme(accent: Accent) = darkColorScheme(
    primary = OceanBlueDark,
    onPrimary = Color(0xFF0B1A3D),
    primaryContainer = accent.start.copy(alpha = 0.26f),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = AuroraPurpleDark,
    onSecondary = Color(0xFF221146),
    secondaryContainer = accent.end.copy(alpha = 0.26f),
    onSecondaryContainer = Color(0xFFE8E1FF),
    tertiary = AuroraTealDark,
    onTertiary = Color(0xFF00302B),
    background = Neutral98,
    onBackground = Neutral20,
    surface = Neutral95,
    onSurface = Neutral20,
    surfaceVariant = Neutral90,
    onSurfaceVariant = Neutral50,
    outline = Neutral70,
    outlineVariant = Neutral85,
    error = Color(0xFFFF6B6B),
    scrim = Color(0x99000000)
)

private fun glassTokens(dark: Boolean) = if (dark) {
    GlassTokens(
        topBarTint = Color(0xB310131C),
        bottomBarTint = Color(0xC610131C),
        sheetTint = Color(0xF210131C),
        dialogTint = Color(0xFA12151E),
        edge = Color(0x1FFFFFFF),
        card = Color(0xFF161A24),
        cardElevated = Color(0xFF1D2230),
        divider = Color(0x1FFFFFFF),
        shadow = Color(0x66000000)
    )
} else {
    GlassTokens(
        topBarTint = Color(0xB3FFFFFF),
        bottomBarTint = Color(0xD9FFFFFF),
        sheetTint = Color(0xF2FBFCFE),
        dialogTint = Color(0xFAFFFFFF),
        edge = Color(0x14000000),
        card = Color(0xFFFFFFFF),
        cardElevated = Color(0xFFFFFFFF),
        divider = Color(0x12000000),
        shadow = Color(0x1A334155)
    )
}

@Composable
fun ColorReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentIndex: Int = 0,
    useDynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val accentPair = AccentGradients.getOrElse(accentIndex) { AccentGradients.first() }
    val accent = Accent(accentPair.start, accentPair.end)

    val scheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        darkTheme -> darkScheme(accent)
        else -> lightScheme(accent)
    }

    CompositionLocalProvider(
        LocalGlass provides glassTokens(darkTheme),
        LocalAccent provides accent
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = ReaderTypography,
            shapes = androidx.compose.material3.Shapes(
                extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(50),
                small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
            ),
            content = content
        )
    }
}

/** Aurora background used behind the shelf and reader headers. */
fun auroraBrush(dark: Boolean, accent: Accent): Brush = Brush.linearGradient(
    listOf(
        if (dark) accent.start.darken(0.55f) else accent.start.copy(alpha = 0.16f),
        if (dark) accent.end.darken(0.62f) else accent.end.copy(alpha = 0.13f),
        if (dark) Color(0xFF0B0E15) else Color.Transparent
    )
)