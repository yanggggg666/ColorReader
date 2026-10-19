package com.oceanreader.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/* ---------------------------------------------------------------------------
 *  Frosted glass — the translucent, grained panels ColorOS 17 uses for its
 *  bars, sheets and floating controls.
 * ------------------------------------------------------------------------- */

internal object Grain {
    val tile: Bitmap by lazy {
        val n = 96
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val rnd = Random(20240517)
        val px = IntArray(n * n)
        for (i in px.indices) {
            val g = 150 + rnd.nextInt(105)
            val a = rnd.nextInt(28)
            px[i] = (a shl 24) or (g shl 16) or (g shl 8) or g
        }
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        bmp
    }
}

/**
 * Translucent glass surface: flat tint + fine film grain + a hairline luminous
 * edge. Stands in for a true backdrop blur (which Compose can only do by
 * snapshotting the whole hierarchy) at a fraction of the cost.
 */
@Composable
fun Modifier.glassPanel(
    shape: Shape,
    tint: Color,
    grain: Float = 0.14f,
    edge: Color? = null,
    edgeWidth: Dp = 1.dp
): Modifier {
    val gAlpha = (grain * 255).toInt().coerceIn(0, 255)
    // Density is read outside the DrawCache scope (it has no `density`).
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scale = 1f / density.density
    val edgeStroke = with(density) { edgeWidth.toPx() }
    val grainPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = false
        shader = BitmapShader(
            Grain.tile,
            Shader.TileMode.REPEAT,
            Shader.TileMode.REPEAT
        ).apply {
            setLocalMatrix(Matrix().apply { postScale(scale, scale) })
        }
        alpha = gAlpha
    }
    val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = with(density) { edgeWidth.toPx() }
    }
    @Suppress("unused")
    val unusedContext = context
    return this
        .clip(shape)
        .background(tint)
        .drawWithCache {
            onDrawBehind {
                if (gAlpha > 0) {
                    drawIntoCanvas { c -> c.nativeCanvas.drawPaint(grainPaint) }
                }
                if (edge != null && edgeStroke > 0f) {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    when (outline) {
                        is Outline.Generic -> drawIntoCanvas { c ->
                            c.nativeCanvas.drawPath(outline.path.asAndroidPath(), edgePaint)
                        }
                        is Outline.Rounded -> drawIntoCanvas { c ->
                            val r = outline.roundRect
                            c.nativeCanvas.drawRoundRect(
                                r.left, r.top, r.right, r.bottom,
                                r.width / 8f, r.height / 8f, edgePaint
                            )
                        }
                        is Outline.Rectangle -> drawIntoCanvas { c ->
                            val rect = outline.rect
                            c.nativeCanvas.drawRect(
                                rect.left, rect.top, rect.right, rect.bottom, edgePaint
                            )
                        }
                    }
                }
            }
        }
}

/** Soft coloured drop-shadow used by floating ColorOS elements. */
fun Modifier.softShadow(
    elevation: Dp,
    shape: Shape,
    spotColor: Color = Color(0x33000000),
    ambientColor: Color = Color(0x1A000000)
): Modifier = shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = ambientColor,
    spotColor = spotColor
)

fun Color.lighten(amount: Float): Color = Color(
    red = red + (1f - red) * amount,
    green = green + (1f - green) * amount,
    blue = blue + (1f - blue) * amount,
    alpha = alpha
)

fun Color.darken(amount: Float): Color = Color(
    red = red * (1f - amount),
    green = green * (1f - amount),
    blue = blue * (1f - amount),
    alpha = alpha
)

/** Returns a readable on-colour for [this]. */
fun Color.contentColor(): Color {
    val l = 0.2126f * red + 0.7152f * green + 0.0722 * blue
    return if (l > 0.55f) Color(0xFF10131C) else Color.White
}