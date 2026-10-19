package com.oceanreader.reader

import android.graphics.FontMetricsInt
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.annotation.ColorInt

/**
 * Resolved visual style for the flowing-text renderer.
 *
 * Everything is already converted to pixels: Compose dp/sp never reaches the
 * [TextPaginator], which works purely on [android.text.TextPaint].
 */
data class ReaderStyle(
    val fontSizePx: Float,
    val lineSpacing: Float,
    val marginHorizontalPx: Float,
    val marginVerticalPx: Float,
    val indentPx: Float,
    val bold: Boolean,
    val justify: Boolean,
    @ColorInt val backgroundColor: Int,
    @ColorInt val textColor: Int,
    @ColorInt val sentenceColor: Int,
    @ColorInt val wordColor: Int,
    val typeface: Typeface? = null,
    /** Extra space between paragraphs / after headings, in pixels. */
    val paragraphSpacingPx: Float = 0f,
    /** Structural hierarchy: 1.28x for headings, 1.5x for the chapter title. */
    val headingScale: Float = 1.28f,
    val titleScale: Float = 1.5f,
    @ColorInt val headingColor: Int = textColor,
    @ColorInt val codeBackground: Int = 0x14000000
)

/** Block kinds the typesetter recognises while flattening a chapter. */
enum class BlockKind {
    /** Chapter title: largest, bold, extra space around it. */
    TITLE,

    /** Section heading inside the chapter: enlarged, bold. */
    HEADING,

    /** Body prose: first-line indent, paragraph spacing after it. */
    PARAGRAPH,

    /** Code fence or indented code: monospace, tinted background, no indent. */
    CODE
}

/**
 * Adds vertical space under (or above) a line — the paragraph "margin-bottom"
 * that Android's span set lacks. Applied to the last character of a block so
 * the space lands between blocks only, exactly like the CSS margin used by
 * WeRead-style engines.
 */
class BlockSpacingSpan(
    private val extraPx: Int,
    private val above: Boolean = false
) : LineHeightSpan {

    override fun chooseHeight(
        text: CharSequence?,
        start: Int,
        end: Int,
        lineHeight: Int,
        fm: FontMetricsInt
    ) {
        if (extraPx <= 0) return
        if (above) {
            fm.top -= extraPx
            fm.ascent -= extraPx
        } else {
            fm.bottom += extraPx
            fm.descent += extraPx
        }
    }
}

/**
 * Turns a chapter's flat text into a styled block stream.
 *
 * Architecture (the same shape WeRead's engine uses): the parser hands us a
 * text stream, the typesetter attaches style ranges over immutable character
 * offsets, and the paginator slices that stream into pages. Because spans
 * never change the text length, TTS offsets, highlights, bookmarks and search
 * results all keep pointing at the same characters.
 *
 * Structure is re-derived per line (no parser changes), so it works for every
 * format: TXT novels get their "第 3 章" headings, Markdown keeps code fences
 * and ATX headings, HTML/TXT keeps indented code.
 */
object ReaderTypesetter {

    private val HEADING_PATTERNS: List<Regex> = listOf(
        Regex("^\\s*第\\s*[0-9０-９一二三四五六七八九十百千万零两]{1,12}\\s*[章节節回卷部篇集话話幕][^\\n]{0,40}$"),
        Regex("^\\s*第\\s*[0-9０-９一二三四五六七八九十百千万零两]{1,12}\\s*(?:章|节|回)[\\s:：.、]*[^\\n]{0,40}$"),
        Regex("^\\s*(?:序章?|序言|自序|前言|引子|楔子|后记|後記|尾声|尾聲|跋|附录|附錄|番外|外传|终章|終章)\\s*[^\\n]{0,40}$"),
        Regex("^\\s*(?:chapter|CHAPTER|Chapter|Chap\\.?|PART|Part)\\s+[0-9IVXLCivxlc]+\\b[^\\n]{0,40}$"),
        Regex("^\\s*[0-9]{1,4}[\\.、．]\\s*[^\\n]{0,40}$"),
        Regex("^\\s*[（(]\\s*[0-9一二三四五六七八九十]{1,6}\\s*[）)]\\s*[^\\n]{0,40}$"),
        Regex("^\\s*#{1,4}\\s+\\S[^\\n]{0,60}$")
    )

    /** Symbol characters that betray source code. */
    private const val CODE_SYMBOLS = "{}();=<>[]|&"

    /**
     * Styles [text] in place (the returned value has exactly the same length).
     *
     * @param chapterTitle title of the surrounding chapter; the first line is
     *   promoted to a chapter title when it matches it.
     */
    fun layout(
        text: CharSequence,
        style: ReaderStyle,
        chapterTitle: String = ""
    ): CharSequence {
        if (text.isEmpty() || style.fontSizePx <= 0f) return text
        val out = SpannableStringBuilder(text)
        val title = chapterTitle.trim()

        var cursor = 0
        var firstContentLineSeen = false
        var codeStart = -1
        var codeEnd = -1

        fun closeCode() {
            if (codeStart >= 0 && codeEnd > codeStart) applyCode(out, codeStart, codeEnd, style)
            codeStart = -1
            codeEnd = -1
        }

        while (cursor <= text.length) {
            if (cursor == text.length) break
            var lineEnd = -1
            for (i in cursor until text.length) {
                if (text[i] == '\n') { lineEnd = i; break }
            }
            if (lineEnd < 0) lineEnd = text.length
            val line = text.subSequence(cursor, lineEnd).toString()
            val trimmed = line.trim()

            when {
                trimmed.isEmpty() -> closeCode()

                looksLikeCode(trimmed) -> {
                    if (codeStart < 0) codeStart = cursor
                    codeEnd = lineEnd
                }

                else -> {
                    closeCode()
                    when {
                        !firstContentLineSeen && title.isNotEmpty() && trimmed == title ->
                            applyChapterTitle(out, cursor, lineEnd, style)

                        headingLevel(trimmed) > 0 ->
                            applyHeading(out, cursor, lineEnd, style)

                        else ->
                            applyParagraph(out, cursor, lineEnd, style)
                    }
                    firstContentLineSeen = true
                }
            }
            cursor = lineEnd + 1
        }
        closeCode()
        return out
    }

    /** True when a line reads like code rather than prose. */
    fun looksLikeCode(trimmedLine: String): Boolean {
        if (trimmedLine.length < 3) return false
        if (trimmedLine.startsWith("    ") || trimmedLine.startsWith("\t")) return true
        val symbols = trimmedLine.count { it in CODE_SYMBOLS }
        val cjk = trimmedLine.count { it.code in 0x2E80..0x9FFF }
        val hasSentencePunct = trimmedLine.any { it in "。，！？；：、" }
        // Pure-ASCII line dense in code symbols, no CJK sentence punctuation.
        return symbols >= 3 && cjk == 0 && !hasSentencePunct
    }

    /** 1 for a heading line, 0 for ordinary prose. */
    fun headingLevel(trimmedLine: String): Int {
        if (trimmedLine.length > 50) return 0
        return if (HEADING_PATTERNS.any { it.matches(trimmedLine) }) 1 else 0
    }

    // ---------------------------------------------------------------- spans

    private fun applyChapterTitle(out: SpannableStringBuilder, start: Int, end: Int, style: ReaderStyle) {
        if (end <= start) return
        out.setSpan(RelativeSizeSpan(style.titleScale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(style.headingColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        blockSpacing(out, start, end, style, after = style.paragraphSpacingPx * 2.2f, before = style.paragraphSpacingPx * 0.6f)
    }

    private fun applyHeading(out: SpannableStringBuilder, start: Int, end: Int, style: ReaderStyle) {
        if (end <= start) return
        out.setSpan(RelativeSizeSpan(style.headingScale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(style.headingColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        blockSpacing(out, start, end, style, after = style.paragraphSpacingPx * 1.6f, before = style.paragraphSpacingPx * 0.6f)
    }

    private fun applyParagraph(out: SpannableStringBuilder, start: Int, end: Int, style: ReaderStyle) {
        if (end <= start) return
        if (style.indentPx > 0f) {
            out.setSpan(
                LeadingMarginSpan.Stardard(style.indentPx.toInt(), 0),
                start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        blockSpacing(out, start, end, style, after = style.paragraphSpacingPx, before = 0f)
    }

    private fun applyCode(out: SpannableStringBuilder, start: Int, end: Int, style: ReaderStyle) {
        if (end <= start) return
        out.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(BackgroundColorSpan(style.codeBackground), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        blockSpacing(out, start, end, style, after = style.paragraphSpacingPx, before = style.paragraphSpacingPx * 0.5f)
    }

    /**
     * Adds breathing room around a block by spacing its first and last
     * characters. Length is never touched, so offsets stay stable.
     */
    private fun blockSpacing(
        out: SpannableStringBuilder,
        start: Int,
        end: Int,
        style: ReaderStyle,
        after: Float,
        before: Float
    ) {
        if (before > 0f) {
            val first = firstNonSpace(out, start, end)
            if (first >= 0) {
                out.setSpan(
                    BlockSpacingSpan(before.toInt().coerceAtLeast(1), above = true),
                    first, first + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        if (after > 0f) {
            val last = lastNonSpace(out, start, end)
            if (last >= 0) {
                out.setSpan(
                    BlockSpacingSpan(after.toInt().coerceAtLeast(1)),
                    last, last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun firstNonSpace(sb: SpannableStringBuilder, start: Int, end: Int): Int {
        var i = start
        while (i < end && sb[i].isWhitespace()) i++
        return if (i < end) i else -1
    }

    private fun lastNonSpace(sb: SpannableStringBuilder, start: Int, end: Int): Int {
        var i = end - 1
        while (i >= start && sb[i].isWhitespace()) i--
        return if (i >= start) i else -1
    }
}
