package com.oceanreader.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
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
fun Modifier.glassPanel(
    shape: Shape,
    tint: Color,
    grain: Float = 0.14f,
    edge: Color? = null,
    edgeWidth: Dp = 1.dp
): Modifier {
    val gAlpha = (grain * 255).toInt().coerceIn(0, 255)
    return this
        .clip(shape)
        .background(tint)
        .drawWithCache {
            val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = false
                shader = BitmapShader(
                    Grain.tile,
                    Shader.TileMode.REPEAT,
                    Shader.TileMode.REPEAT
                ).apply {
                    val s = 1f / density.density
                    setLocalMatrix(Matrix().apply { postScale(s, s) })
                }
                alpha = gAlpha
            }
            val strokeWidth = with(density) { edgeWidth.toPx() }
            val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = with(density) { edgeWidth.toPx() }
            }
            onDrawBehind {
                if (gAlpha > 0) {
                    drawIntoCanvas { c -> c.nativeCanvas.drawPaint(grainPaint) }
                }
                if (edge != null && strokeWidth > 0f) {
                    when (val o = shape.createOutline(size, layoutDirection, this)) {
                        is Outline.Generic -> drawIntoCanvas { c ->
                            c.nativeCanvas.drawPath(o.path.asAndroidPath(), edgePaint)
                        }
                        is Outline.Rounded -> drawIntoCanvas { c ->
                            c.nativeCanvas.drawRoundRect(
                                o.left, o.top, o.right, o.bottom,
                                o.cornerRadius.x, o.cornerRadius.y, edgePaint
                            )
                        }
                        is Outline.Rectangle -> drawIntoCanvas { c ->
                            c.nativeCanvas.drawRect(o.left, o.top, o.right, o.bottom, edgePaint)
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

/** Scale + dim driven by an externally supplied pressed state. */
@Composable
fun Modifier.pressScale(pressed: Boolean, minScale: Float = 0.96f): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (pressed) minScale else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "pressScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "pressAlpha"
    )
    return this.graphicsLayer(scaleX = scale, scaleY = scale, alpha = alpha)
}

/** Clickable with ColorOS press feedback and a proper accessibility role. */
@Composable
fun Modifier.tappable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    minScale: Float = 0.96f,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return Modifier
        .pressScale(pressed, minScale)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onLongClickLabel = onLongClickLabel,
            onLongClick = onLongClick
        ) { onClick() }
}

/** Vertical fade used at the top/bottom of scrolling shelves. */
fun Modifier.verticalEdgeFade(edgeHeight: Dp, top: Boolean): Modifier = drawWithCache {
    val h = with(density) { edgeHeight.toPx() }
    val start = if (top) 0f else (size.height - h).coerceAtLeast(0f)
    val brush = Brush.verticalGradient(
        0f to Color.Transparent,
        1f to Color.Black,
        startY = start,
        endY = start + h
    )
    onDrawWithContent {
        drawContent()
        drawRect(brush)
    }
}

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
    val l = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return if (l > 0.55f) Color(0xFF10131C) else Color.White
}