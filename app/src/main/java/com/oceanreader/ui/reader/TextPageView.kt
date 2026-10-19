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
    onLongPress: (Int) -> Unit
) {
    val pageCount = paginator.pageCount
    var dragOffset by remember { mutableFloatStateOf(0f) }

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
                    style.marginVerticalPx - top + dragOffset
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
 * Renders a PDF page full-bleed (cover): the page keeps its aspect ratio and
 * fills the screen edge to edge, the overflow is cropped — no letterbox bars,
 * no distortion. The bitmap is produced off the main thread by
 * [com.oceanreader.parse.pdf.PdfPageRenderer]; dark palettes invert it.
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
    // Centre offset of the (possibly larger than screen) bitmap.
    var originX by remember { mutableFloatStateOf(0f) }
    var originY by remember { mutableFloatStateOf(0f) }
    val renderer = remember(filePath) {
        com.oceanreader.parse.pdf.PdfPageRenderer(java.io.File(filePath))
    }

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
                // Pick the dimension the page must fill; the other follows the
                // page's own ratio so nothing is distorted.
                val screenAspect = screenW / screenH
                val bitmapW: Int
                val bitmapH: Int
                if (aspect >= screenAspect) {
                    bitmapH = screenH.toInt().coerceAtLeast(1)
                    bitmapW = (screenH * aspect).toInt().coerceAtLeast(1)
                } else {
                    bitmapW = screenW.toInt().coerceAtLeast(1)
                    bitmapH = (screenW / aspect).toInt().coerceAtLeast(1)
                }
                originX = (screenW - bitmapW) / 2f
                originY = (screenH - bitmapH) / 2f
                bitmap = renderer.render(page, bitmapW, bitmapH)?.let {
                    if (dark) com.oceanreader.parse.pdf.PdfTone.invert(it) else it
                }
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val bmp = bitmap ?: return@Canvas
            drawIntoCanvas { canvas ->
                val native: Canvas = canvas.nativeCanvas
                native.save()
                native.translate(originX, originY + dragOffset)
                native.drawBitmap(bmp, 0f, 0f, null)
                native.restore()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { renderer.close() }
    }
}

private fun withAlpha(color: Int, alpha: Int): Int {
    val a = ((color ushr 24) and 0xFF) * alpha / 255
    return (a shl 24) or (color and 0x00FFFFFF)
}
