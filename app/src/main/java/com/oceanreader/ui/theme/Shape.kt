package com.oceanreader.ui.theme

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * ColorOS 17's signature silhouette: the super ellipse (超椭圆).
 *
 * A rounded rectangle whose corner arcs follow |x|^n + |y|^n = 1 instead of a
 * circle. `exponent = 2f` gives a classic rounded rectangle, `exponent = 4.2f`
 * reproduces the continuous-curvature squircle ColorOS uses for cards, icons
 * and bottom sheets.
 */
class SuperShape(
    private val cornerFraction: Float = 0.30f,
    private val exponent: Float = 4.2f,
    private val clamp: Boolean = true
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return Outline.Rectangle(Rect(0f, 0f, 0f, 0f))

        var r = minOf(w, h) * cornerFraction
        if (clamp) r = r.coerceAtMost(minOf(w, h) / 2f)
        if (r <= 0f) return Outline.Rectangle(Rect(0f, 0f, w, h))

        val n = if (exponent <= 0f) 2f else exponent
        val p = 2f / n

        val path = Path()
        val steps = 16
        path.moveTo(r, 0f)
        path.lineTo(w - r, 0f)
        arc(path, w - r, r, r, p, PI_F / 2f, 0f, steps)
        path.lineTo(w, h - r)
        arc(path, w - r, h - r, r, p, 0f, -PI_F / 2f, steps)
        path.lineTo(r, h)
        arc(path, r, h - r, r, p, -PI_F / 2f, -PI_F, steps)
        path.lineTo(0f, r)
        arc(path, r, r, r, p, PI_F, PI_F / 2f, steps)
        path.close()
        return Outline.Generic(path)
    }
}

private const val PI_F: Float = Math.PI.toFloat()

private fun arc(
    path: Path,
    cx: Float,
    cy: Float,
    r: Float,
    p: Float,
    fromRad: Float,
    toRad: Float,
    steps: Int
) {
    for (i in 1..steps) {
        val t = fromRad + (toRad - fromRad) * i / steps
        val x = cx + r * signedPow(cos(t), p)
        val y = cy + r * signedPow(sin(t), p)
        path.lineTo(x, y)
    }
}

private fun signedPow(v: Float, p: Float): Float {
    val a = if (v < 0f) -v else v
    val r = Math.pow(a.toDouble(), p.toDouble()).toFloat()
    return if (v < 0f) -r else r
}

/** Icon / chip / avatar silhouette. */
val SuperCircle = SuperShape(cornerFraction = 0.5f, exponent = 4.4f)

/** Standard ColorOS card. */
val SuperCard = SuperShape(cornerFraction = 0.22f, exponent = 4.4f)

/** Large panels & bottom sheets. */
val SuperSheet = SuperShape(cornerFraction = 0.32f, exponent = 4.6f)
