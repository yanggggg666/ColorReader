package com.oceanreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oceanreader.data.Settings
import com.oceanreader.reader.ChapterAnnotator
import com.oceanreader.reader.ReaderStyle
import com.oceanreader.reader.ReaderTypesetter
import com.oceanreader.reader.TypedBlock
import com.oceanreader.tts.SpeechCursor

/**
 * Continuous vertical scrolling reader (WeRead-style).
 *
 * The whole chapter is one Compose text block: swiping up/down scrolls it
 * line by line, the spoken sentence is followed into view during 跟读, and
 * the chapter ends with a tappable 下一章 (plus a 回到顶部 hint) so a short
 * chapter never leaves you stranded.
 */
@Composable
fun ScrollReaderView(
    chapterText: String,
    chapterTitle: String,
    chapterIndex: Int,
    chapterCount: Int,
    style: ReaderStyle,
    prefs: Settings,
    speechCursor: SpeechCursor,
    onNextChapter: () -> Unit,
    onProgress: (Float) -> Unit
) {
    var viewportHeight by remember { mutableStateOf(1f) }
    val scrollState = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val blocks: List<TypedBlock> = remember(chapterText, chapterIndex, style) {
        ReaderTypesetter.blocks(chapterText, style, chapterTitle)
    }
    val annotated = remember(blocks, style, prefs, chapterText) {
        ChapterAnnotator.annotate(chapterText, blocks, style, prefs)
    }

    // A new chapter always starts at the top.
    LaunchedEffect(chapterIndex) {
        scrollState.scrollTo(0)
        onProgress(0f)
    }

    // Report progress as the reader moves.
    LaunchedEffect(scrollState.value, scrollState.maxValue) {
        if (scrollState.maxValue > 0) {
            onProgress((scrollState.value.toFloat() / scrollState.maxValue).coerceIn(0f, 1f))
        }
    }

    // 跟读: keep the spoken sentence in the middle of the viewport.
    LaunchedEffect(speechCursor.sentenceStart, speechCursor.chapterIndex) {
        if (!speechCursor.hasSentence || speechCursor.chapterIndex != chapterIndex) return@LaunchedEffect
        val textLayout = layout ?: return@LaunchedEffect
        val offset = speechCursor.sentenceStart.coerceIn(0, annotated.length)
        val y = textLayout.getBoundingBox(offset).top
        val viewport = viewportHeight.coerceAtLeast(1f)
        scrollState.animateScrollTo(
            (y - viewport / 2f).toInt().coerceIn(0, scrollState.maxValue)
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        viewportHeight = with(LocalDensity.current) { maxHeight.toPx() }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color(style.backgroundColor))
                .verticalScroll(scrollState)
                .padding(
                    horizontal = with(LocalDensity.current) { style.marginHorizontalPx.toDp() },
                    vertical = with(LocalDensity.current) { style.marginVerticalPx.toDp() }
                )
        ) {
        Text(
            text = annotated,
            style = TextStyle(
                fontSize = ChapterAnnotator.fontSizeSp(prefs).sp,
                lineHeight = ChapterAnnotator.lineHeightSp(style, prefs),
                fontFamily = ChapterAnnotator.fontFamilyOf(prefs.fontFamily),
                color = androidx.compose.ui.graphics.Color(style.textColor),
                textAlign = TextAlign.Start
            ),
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth()
        )

        // Chapter footer: continue to the next chapter with one tap.
        Text(
            text = if (chapterIndex + 1 < chapterCount) "—— 本章完 ——" else "—— 全书完 ——",
            style = MaterialTheme.typography.bodySmall,
            color = androidx.compose.ui.graphics.Color(style.textColor).copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            textAlign = TextAlign.Center
        )
        if (chapterIndex + 1 < chapterCount) {
            TextButton(
                onClick = onNextChapter,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("下一章")
            }
        }
        }
    }
}
