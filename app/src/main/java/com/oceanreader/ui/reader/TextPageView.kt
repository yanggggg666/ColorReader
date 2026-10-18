package com.oceanreader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.oceanreader.reader.TextPaginator
import com.oceanreader.tts.SpeechCursor

/**
 * Draws the current page of flowing text through [TextPaginator].
 *
 * The paginator owns the `StaticLayout`; we only ask for the page's character
 * range and paint it, which keeps recomposition cheap on every TTS cursor
 * update.
 */
@Composable
fun TextPageView(
    paginator: TextPaginator,
    page: Int,
    speechCursor: SpeechCursor,
    onPageChanged: (Int, Int) -> Unit,
    onSelection: (String, Int, Int) -> Unit,
    onLongPress: (Int) -> Unit
) {
    val pageCount = paginator.pageCount
    var dragOffset by remember { mutableFloatStateOf(0f) }

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
                        val localY = offset.y - dragOffset
                        val line = l.getLineForVertical(localY.toInt().coerceAtLeast(0))
                        val charOffset = l.getOffsetForHorizontal(line, offset.x)
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

            drawContext.canvas.nativeCanvas.apply {
                save()
                translate(top = -top + dragOffset)

                // Sentence highlight
                if (sentenceEnd > sentenceStart) {
                    val sLine = l.getLineForOffset(sentenceStart)
                    val eLine = l.getLineForOffset(sentenceEnd - 1)
                    for (line in sLine..eLine.coerceAtMost(l.lineCount - 1)) {
                        val lineStart = l.getLineStart(line).coerceAtLeast(sentenceStart)
                        val lineEnd = l.getLineEnd(line).coerceAtMost(sentenceEnd)
                        val x1 = l.getPrimaryHorizontal(lineStart)
                        val x2 = if (lineEnd > lineStart) {
                            l.getSecondaryHorizontal(lineEnd)
                        } else x1
                        drawRect(
                            x1, l.getLineTop(line).toFloat(),
                            x2, l.getLineBottom(line).toFloat(),
                            android.graphics.Paint().apply {
                                color = 0x33FFB74D
                                style = android.graphics.Paint.Style.FILL
                            }
                        )
                    }
                }

                // Word highlight
                if (wordEnd > wordStart) {
                    val line = l.getLineForOffset(wordStart)
                    if (line in 0 until l.lineCount) {
                        val x1 = l.getPrimaryHorizontal(wordStart)
                        val x2 = l.getSecondaryHorizontal(wordEnd.coerceAtMost(l.getLineEnd(line)))
                        drawRect(
                            x1, l.getLineTop(line).toFloat(),
                            x2, l.getLineBottom(line).toFloat(),
                            android.graphics.Paint().apply {
                                color = 0x66FF9800
                                style = android.graphics.Paint.Style.FILL
                            }
                        )
                    }
                }

                l.draw(this)
                restore()
            }
        }
    }
}

/**
 * Renders a PDF page through the platform renderer.
 *
 * The bitmap is produced off the main thread by [PdfPageRenderer] and drawn
 * into the canvas with the page's aspect ratio preserved.
 */
@Composable
fun PdfPageView(
    filePath: String,
    page: Int,
    pageCount: Int,
    onPageChanged: (Int) -> Unit
) {
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val renderer = remember(filePath) { com.oceanreader.parse.pdf.PdfPageRenderer(java.io.File(filePath)) }

    LaunchedEffect(page, filePath) {
        bitmap = renderer.render(page, 1080, 1920)
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
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bmp = bitmap ?: return@Canvas
            val scale = minOf(size.width / bmp.width, size.height / bmp.height)
            val w = bmp.width * scale
            val h = bmp.height * scale
            val dx = (size.width - w) / 2f
            val dy = (size.height - h) / 2f
            drawContext.canvas.nativeCanvas.apply {
                save()
                translate(dx, dy + dragOffset)
                drawBitmap(bmp, 0f, 0f, null)
                restore()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { renderer.close() }
    }
}