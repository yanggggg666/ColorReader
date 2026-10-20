package com.oceanreader.ui.reader

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.oceanreader.reader.HighlightSpan
import com.oceanreader.reader.ReaderStyle
import com.oceanreader.reader.TextPaginator
import com.oceanreader.tts.SpeechCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Default highlight palette (matches the settings screen chips). */
val HighlightColors: List<Int> = listOf(
    0xFFFFF176.toInt(), // yellow
    0xFFAED581.toInt(), // green
    0xFF90CAF9.toInt(), // blue
    0xFFF48FB1.toInt()  // pink
)

/**
 * Draws the current page of flowing text through [TextPaginator].
 *
 * The paginator owns the `StaticLayout`; we only ask for the page's character
 * range and paint it, which keeps recomposition cheap on every TTS cursor
 * update. User highlights and the TTS cursor are painted as rects in layout
 * space, so the text itself is never rebuilt while reading.
 */
@Composable
fun TextPageView(
    style: ReaderStyle,
    paginator: TextPaginator,
    page: Int,
    speechCursor: SpeechCursor,
    highlights: List<HighlightSpan>,
    onPageChanged: (Int, Int) -> Unit,
    onLongPress: (Int) -> Unit,
    topInsetPx: Float = 0f
) {
    val pageCount = paginator.pageCount
    var dragOffset by remember { mutableFloatStateOf(0f) }

    // Any committed page change invalidates an in-flight drag offset; without
    // this a cancelled gesture shifts every later page down and eats its top
    // lines (pages look like they hold fewer and fewer lines).
    LaunchedEffect(page) { dragOffset = 0f }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(style.backgroundColor))
            .pointerInput(pageCount) {
                detectVerticalDragGestures(
                    onDragStart = { dragOffset = 0f },
                    onDragEnd = {
                        val threshold = size.height * 0.18f
                        val target = when {
                            dragOffset < -threshold -> (page + 1).coerceAtMost(pageCount - 1)
                            dragOffset > threshold -> (page - 1).coerceAtLeast(0)
                            else -> page
                        }
                        if (target != page) onPageChanged(target, paginator.startOf(target))
                        dragOffset = 0f
                    },
                    // A cancelled gesture (fling interception, pointer up outside)
                    // used to leave the offset dangling and shift every later page.
                    onDragCancel = { dragOffset = 0f },
                    onVerticalDrag = { _, amount -> dragOffset += amount }
                )
            }
            .pointerInput(page) {
                detectTapGestures(
                    onLongPress = { offset ->
                        val l = paginator.layout() ?: return@detectTapGestures
                        val localY = (offset.y - dragOffset - style.marginVerticalPx)
                            .toInt().coerceAtLeast(0)
                        val line = l.getLineForVertical(localY)
                        if (line !in 0 until l.lineCount) return@detectTapGestures
                        val charOffset = l.getOffsetForHorizontal(line, offset.x - style.marginHorizontalPx)
                        onLongPress(charOffset)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val l = paginator.layout() ?: return@Canvas
            val start = paginator.startOf(page)
            val end = paginator.endOf(page).coerceIn(start, l.text.length)
            if (end <= start) return@Canvas

            val top = paginator.topOf(page)
            val sentenceStart = speechCursor.sentenceStart
            val sentenceEnd = speechCursor.sentenceEnd
            val wordStart = speechCursor.wordStart
            val wordEnd = speechCursor.wordEnd

            drawIntoCanvas { canvas ->
                val native: Canvas = canvas.nativeCanvas
                native.save()
                native.translate(
                    style.marginHorizontalPx,
                    // + topInsetPx keeps the first line below the top chrome.
                    style.marginVerticalPx + topInsetPx - top + dragOffset
                )

                // Paints one highlight range as a run of line rects.
                fun paintRange(rangeStart: Int, rangeEnd: Int, color: Int, alpha: Int) {
                    if (rangeEnd <= rangeStart) return
                    val paint = Paint().apply {
                        this.color = withAlpha(color, alpha)
                        isAntiAlias = true
                    }
                    val firstLine = l.getLineForOffset(rangeStart).coerceIn(0, l.lineCount - 1)
                    val lastLine = l.getLineForOffset(rangeEnd - 1).coerceIn(0, l.lineCount - 1)
                    for (line in firstLine..lastLine) {
                        val lineStart = l.getLineStart(line).coerceAtLeast(rangeStart)
                        val lineEnd = l.getLineEnd(line).coerceAtMost(rangeEnd)
                        if (lineEnd <= lineStart) continue
                        val x1 = l.getPrimaryHorizontal(lineStart)
                        val x2 = l.getSecondaryHorizontal(lineEnd)
                        native.drawRect(
                            x1,
                            l.getLineTop(line).toFloat(),
                            x2,
                            l.getLineBottom(line).toFloat(),
                            paint
                        )
                    }
                }

                fun paintUnderline(rangeStart: Int, rangeEnd: Int, color: Int) {
                    if (rangeEnd <= rangeStart) return
                    val paint = Paint().apply {
                        this.color = color
                        // `style` would resolve to the outer ReaderStyle val.
                        this.style = Paint.Style.STROKE
                        strokeWidth = 2.5f
                        isAntiAlias = true
                    }
                    val firstLine = l.getLineForOffset(rangeStart).coerceIn(0, l.lineCount - 1)
                    val lastLine = l.getLineForOffset(rangeEnd - 1).coerceIn(0, l.lineCount - 1)
                    for (line in firstLine..lastLine) {
                        val lineStart = l.getLineStart(line).coerceAtLeast(rangeStart)
                        val lineEnd = l.getLineEnd(line).coerceAtMost(rangeEnd)
                        if (lineEnd <= lineStart) continue
                        val y = l.getLineBottom(line) - 2f
                        native.drawLine(
                            l.getPrimaryHorizontal(lineStart),
                            y,
                            l.getSecondaryHorizontal(lineEnd),
                            y,
                            paint
                        )
                    }
                }

                // User highlights first, so the TTS cursor composites over them.
                for (h in highlights) {
                    if (h.underline) paintUnderline(h.start, h.end, h.color)
                    else paintRange(h.start, h.end, h.color, h.alpha)
                }

                // Sentence highlight (跟读)
                if (sentenceEnd > sentenceStart) {
                    paintRange(sentenceStart, sentenceEnd, style.sentenceColor, 58)
                }

                // Word highlight (跟读)
                if (wordEnd > wordStart) {
                    paintRange(wordStart, wordEnd, style.wordColor, 150)
                }

                // The text itself
                l.draw(native)
                native.restore()
            }
        }
    }
}

/**
 * Renders a PDF page as large as the screen allows without ever cutting
 * content off:
 *
 *  1. the page is rasterised at its own aspect ratio (filling the screen's
 *     smaller constraint, so nothing is stretched);
 *  2. the page's white margins are detected and cropped away (裁白边) — this
 *     is what actually buys back the screen area, the way WeRead/WPS do it;
 *  3. the cropped page is fitted (contain) and centred, so left/right sides
 *     never protrude and no band is larger than the page's own margin.
 *
 * Dark palettes invert the page so scanned PDFs stay readable at night.
 */
@Composable
fun PdfPageView(
    filePath: String,
    page: Int,
    pageCount: Int,
    dark: Boolean = false,
    onPageChanged: (Int) -> Unit
) {
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var originX by remember { mutableFloatStateOf(0f) }
    var originY by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(1f) }
    val renderer = remember(filePath) {
        com.oceanreader.parse.pdf.PdfPageRenderer(java.io.File(filePath))
    }

    LaunchedEffect(page) { dragOffset = 0f }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(pageCount) {
                detectVerticalDragGestures(
                    onDragStart = { dragOffset = 0f },
                    onDragEnd = {
                        val threshold = size.height * 0.18f
                        val target = when {
                            dragOffset < -threshold -> (page + 1).coerceAtMost(pageCount - 1)
                            dragOffset > threshold -> (page - 1).coerceAtLeast(0)
                            else -> page
                        }
                        if (target != page) onPageChanged(target)
                        dragOffset = 0f
                    },
                    onDragCancel = { dragOffset = 0f },
                    onVerticalDrag = { _, amount -> dragOffset += amount }
                )
            }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val screenW = with(density) { maxWidth.toPx() }
            val screenH = with(density) { maxHeight.toPx() }

            LaunchedEffect(page, filePath, screenW, screenH, dark) {
                if (screenW < 1f || screenH < 1f) return@LaunchedEffect
                val aspect = renderer.pageAspect(page).takeIf { it > 0.01f } ?: 0.707f
                // Fill the tighter screen dimension; the other follows the page.
                val bitmapW: Int
                val bitmapH: Int
                if (screenW / screenH <= aspect) {
                    bitmapW = screenW.toInt().coerceAtLeast(1)
                    bitmapH = (screenW / aspect).toInt().coerceAtLeast(1)
                } else {
                    bitmapH = screenH.toInt().coerceAtLeast(1)
                    bitmapW = (screenH * aspect).toInt().coerceAtLeast(1)
                }

                val rendered = renderer.render(page, bitmapW, bitmapH) ?: return@LaunchedEffect
                val cropped = withContext(Dispatchers.Default) { autoCropWhiteMargins(rendered) }
                // PdfTone.invert returns a copy and recycles its input.
                val finalBitmap = if (dark) com.oceanreader.parse.pdf.PdfTone.invert(cropped) else cropped
                if (finalBitmap !== rendered && !rendered.isRecycled) rendered.recycle()
                bitmap = finalBitmap

                // Contain-fit the (cropped) page: never crop content again.
                val pageAspect = finalBitmap.width.toFloat() / finalBitmap.height.toFloat()
                val drawW: Float
                val drawH: Float
                if (pageAspect >= screenW / screenH) {
                    drawW = screenW
                    drawH = screenW / pageAspect
                } else {
                    drawH = screenH
                    drawW = screenH * pageAspect
                }
                scale = drawW / finalBitmap.width.toFloat()
                originX = (screenW - drawW) / 2f
                originY = (screenH - drawH) / 2f
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val bmp = bitmap ?: return@Canvas
            if (bmp.isRecycled) return@Canvas
            drawIntoCanvas { canvas ->
                val native: Canvas = canvas.nativeCanvas
                native.save()
                native.translate(originX, originY + dragOffset)
                native.scale(scale, scale)
                native.drawBitmap(bmp, 0f, 0f, null)
                native.restore()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { renderer.close() }
    }
}

/**
 * Detects the page's near-white border and returns a copy without it.
 *
 * Sampling is coarse (~240 probes on the long side) so a full page costs a
 * few milliseconds. Returns the source bitmap untouched when the border is
 * thin, so normal pages are never reallocated.
 */
private fun autoCropWhiteMargins(src: android.graphics.Bitmap, padFraction: Float = 0.015f): android.graphics.Bitmap {
    val w = src.width
    val h = src.height
    if (w < 16 || h < 16) return src
    val step = maxOf(2, minOf(w, h) / 240)
    val paper = src.getPixel(2, 2)
    val pr = (paper shr 16) and 0xFF
    val pg = (paper shr 8) and 0xFF
    val pb = paper and 0xFF
    val tolerance = 26

    var minX = w
    var minY = h
    var maxX = -1
    var maxY = -1
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            val c = src.getPixel(x, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (abs(r - pr) > tolerance || abs(g - pg) > tolerance || abs(b - pb) > tolerance) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            x += step
        }
        y += step
    }
    // Blank page or a border thinner than the sampling grid: keep it whole.
    if (maxX < 0 || maxY < 0) return src

    val pad = (minOf(w, h) * padFraction).toInt()
    val left = (minX - pad).coerceIn(0, w - 1)
    val top = (minY - pad).coerceIn(0, h - 1)
    val right = (maxX + pad).coerceIn(1, w)
    val bottom = (maxY + pad).coerceIn(1, h)
    val cw = right - left
    val ch = bottom - top
    val meaningfulX = cw < w * 0.94f
    val meaningfulY = ch < h * 0.94f
    if (!meaningfulX && !meaningfulY) return src
    return try {
        android.graphics.Bitmap.createBitmap(src, left, top, cw, ch)
    } catch (e: Throwable) {
        src
    }
}

private fun withAlpha(color: Int, alpha: Int): Int {
    val a = ((color ushr 24) and 0xFF) * alpha / 255
    return (a shl 24) or (color and 0x00FFFFFF)
}
