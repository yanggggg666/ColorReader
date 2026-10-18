package com.oceanreader.reader

import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.annotation.ColorInt

/** A resolved user highlight to paint onto the page. */
data class HighlightSpan(
    val start: Int,
    val end: Int,
    @ColorInt val color: Int,
    val underline: Boolean,
    val alpha: Int = 110
)

/**
 * Paginates a chapter using [StaticLayout] and paints user highlights.
 *
 * StaticLayout does the hard part — CJK line breaking, justification, hyphen
 * gaps — and we derive page boundaries from `getLineForVertical`, which is
 * exact regardless of font or screen size.
 */
class TextPaginator {

    /** Character offsets of the first character of each page. */
    val pageStarts = ArrayList<Int>()

    private var layout: StaticLayout? = null
    private var pageHeight = 0
    private var textLength = 0

    /** Rebuilds the layout and computes page boundaries. */
    fun paginate(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        height: Int,
        spacingMultiplier: Float,
        spacingAdd: Int,
        includePad: Boolean = false
    ) {
        pageStarts.clear()
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val sl = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(spacingAdd.toFloat(), spacingMultiplier)
            .setIncludePad(includePad)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        layout = sl
        textLength = text.length
        pageHeight = h

        if (sl.lineCount <= 1 || textLength == 0) {
            pageStarts.add(0)
            return
        }

        var offset = 0
        var guard = 0
        while (offset < textLength && guard++ < 20_000) {
            val line = sl.getLineForVertical((guard - 1) * h)
            if (line >= sl.lineCount) break
            offset = sl.getLineEnd(line)
            pageStarts.add(offset)
            if (offset >= textLength) break
        }
        if (pageStarts.isEmpty()) pageStarts.add(0)
    }

    val pageCount: Int get() = pageStarts.size.coerceAtLeast(1)

    fun layout(): StaticLayout? = layout

    fun pageHeight(): Int = pageHeight

    /** Y offset of the first line of [page] inside the layout. */
    fun topOf(page: Int): Int {
        val sl = layout ?: return 0
        val offset = pageStarts.getOrNull(page) ?: return 0
        if (offset >= textLength) return sl.height
        return sl.getLineTop(sl.getLineForOffset(offset))
    }

    /** Character offset where [page] starts. */
    fun startOf(page: Int): Int = pageStarts.getOrNull(page) ?: 0

    /** Character offset where [page] ends. */
    fun endOf(page: Int): Int {
        val sl = layout ?: return 0
        if (page + 1 < pageStarts.size) {
            val next = pageStarts[page + 1]
            return if (next <= 0) textLength else next - 1
        }
        return textLength
    }

    /** Page containing [offset], or the closest one. */
    fun pageOf(offset: Int): Int {
        if (pageStarts.isEmpty()) return 0
        var result = 0
        for (i in pageStarts.indices) {
            if (pageStarts[i] <= offset) result = i else break
        }
        return result
    }

    fun isLastPage(page: Int): Boolean = page >= pageCount - 1

    /**
     * Copies the page's characters, replacing runs covered by [highlight] with
     * spans. The TTS cursor (sentence + word) is drawn the same way so it
     * composites over user highlights.
     */
    fun renderPage(
        page: Int,
        highlights: List<HighlightSpan>,
        sentenceStart: Int = -1,
        sentenceEnd: Int = -1,
        wordStart: Int = -1,
        wordEnd: Int = -1,
        @ColorInt sentenceColor: Int = 0,
        @ColorInt wordColor: Int = 0,
        boldSentences: Boolean = true
    ): CharSequence? {
        val sl = layout ?: return null
        val start = startOf(page)
        val end = endOf(page).coerceIn(start, textLength)
        if (end <= start) return null

        val base = sl.text.subSequence(start, end)
        if (highlights.isEmpty() && sentenceStart < 0 && wordStart < 0) return base

        val out = SpannableStringBuilder(base)
        for (h in highlights) {
            val s = (h.start - start).coerceIn(0, out.length)
            val e = (h.end - start).coerceIn(s, out.length)
            if (e <= s) continue
            if (h.underline) {
                out.setSpan(
                    android.text.style.UnderlineSpan(),
                    s, e,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else {
                out.setSpan(
                    BackgroundColorSpan(withAlpha(h.color, h.alpha)),
                    s, e,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        val sStart = sentenceStart - start
        val sEnd = sentenceEnd - start
        if (sStart >= 0 && sEnd > sStart && sEnd <= out.length) {
            out.setSpan(
                BackgroundColorSpan(withAlpha(sentenceColor, 58)),
                sStart, sEnd,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (boldSentences) {
                out.setSpan(
                    ForegroundColorSpan(withAlpha(sentenceColor, 210)),
                    sStart, sEnd,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        val wStart = wordStart - start
        val wEnd = wordEnd - start
        if (wStart >= 0 && wEnd > wStart && wEnd <= out.length) {
            out.setSpan(
                BackgroundColorSpan(withAlpha(wordColor, 150)),
                wStart, wEnd,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            out.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                wStart, wEnd,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return out
    }

    /** Vertical position (px, layout space) of a character offset. */
    fun yOf(offset: Int): Int {
        val sl = layout ?: return 0
        if (textLength == 0) return 0
        val clamped = offset.coerceIn(0, textLength - 1)
        return sl.getLineTop(sl.getLineForOffset(clamped))
    }

    fun lineOf(offset: Int): Int {
        val sl = layout ?: return 0
        if (textLength == 0) return 0
        return sl.getLineForOffset(offset.coerceIn(0, textLength - 1))
    }

    fun totalHeight(): Int = layout?.height ?: 0

    private fun withAlpha(color: Int, alpha: Int): Int {
        val a = ((color ushr 24) and 0xFF) * alpha / 255
        return (a shl 24) or (color and 0x00FFFFFF)
    }
}