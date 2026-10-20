package com.oceanreader.reader

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
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
    /** Extra space between paragraphs, in pixels (already em × font size). */
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
 * Paragraph separation is deliberately *not* a custom span.
 *
 * A `LineHeightSpan` that mutates `FontMetricsInt` incrementally
 * (`fm.descent += n`) is unsafe: StaticLayout reuses one metrics object across
 * the lines it measures and does not reset it between them, so the extra
 * pixels stack up down a multi-line paragraph (and the line-spacing
 * multiplier then amplifies the whole box) — long paragraphs ended up with
 * several times their intended pitch while single-line ones looked fine.
 * Blank lines from the source text are the separation instead, which is what
 * mainstream readers (Kindle included) do.
 */

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
     * A *paragraph* is a run of non-blank lines bounded by blank lines — never
     * a single line. Wrapped lines inside a paragraph share one indent and one
     * trailing block gap; treating each line as its own paragraph (as this did
     * before) put a first-line indent and spacing after every wrap, which is
     * what made pages look half empty.
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
        val total = text.length
        val blockCounts = HashMap<BlockKind, Int>()

        // Line boundaries: starts[i]..starts[i+1)-1, last line ends at total.
        val lineStarts = ArrayList<Int>()
        run {
            var i = text.indexOf('\n')
            var from = 0
            while (i >= 0) {
                lineStarts.add(from)
                from = i + 1
                i = text.indexOf('\n', from)
            }
            if (from < total || lineStarts.isEmpty()) lineStarts.add(from)
        }
        fun lineEndOf(index: Int): Int {
            val next = lineStarts.getOrNull(index + 1) ?: return total
            return (next - 1).coerceAtMost(total)
        }
        fun trimmedOf(index: Int): String {
            val s = lineStarts[index]
            val e = lineEndOf(index)
            if (e <= s) return ""
            return text.subSequence(s, e).toString().trim()
        }

        var li = 0
        var firstContentLineSeen = false
        while (li < lineStarts.size) {
            val lineStart = lineStarts[li]
            val trimmed = trimmedOf(li)
            if (trimmed.isEmpty()) {
                li++
                continue
            }

            // Code fence: gather the whole run of code-looking lines.
            if (looksLikeCode(trimmed)) {
                var lj = li
                while (lj + 1 < lineStarts.size) {
                    val next = trimmedOf(lj + 1)
                    if (next.isEmpty() || !looksLikeCode(next)) break
                    lj++
                }
                applyCode(out, lineStart, lineEndOf(lj), style)
                blockCounts[BlockKind.CODE] = (blockCounts[BlockKind.CODE] ?: 0) + 1
                li = lj + 1
                firstContentLineSeen = true
                continue
            }

            // Paragraph: gather every following line that is neither blank nor
            // code, so a wrapped paragraph keeps a single indent and gap.
            var lj = li
            while (lj + 1 < lineStarts.size) {
                val next = trimmedOf(lj + 1)
                if (next.isEmpty() || looksLikeCode(next)) break
                lj++
            }
            val runEnd = lineEndOf(lj)
            val singleLine = (lj == li)

            when {
                !firstContentLineSeen && title.isNotEmpty() && trimmed == title -> {
                    applyChapterTitle(out, lineStart, runEnd, style)
                    blockCounts[BlockKind.TITLE] = (blockCounts[BlockKind.TITLE] ?: 0) + 1
                }

                headingLevel(trimmed) > 0 && singleLine -> {
                    applyHeading(out, lineStart, runEnd, style)
                    blockCounts[BlockKind.HEADING] = (blockCounts[BlockKind.HEADING] ?: 0) + 1
                }

                headingLevel(trimmed) > 0 -> {
                    // A heading fused with its body (no blank line between the
                    // two): style the first line, typeset the rest as prose.
                    applyHeading(out, lineStart, lineEndOf(li), style)
                    applyParagraph(out, lineStarts[li + 1], runEnd, lineEndOf(li + 1), style)
                    blockCounts[BlockKind.HEADING] = (blockCounts[BlockKind.HEADING] ?: 0) + 1
                    blockCounts[BlockKind.PARAGRAPH] = (blockCounts[BlockKind.PARAGRAPH] ?: 0) + 1
                }

                else -> {
                    applyParagraph(out, lineStart, runEnd, lineEndOf(li), style)
                    blockCounts[BlockKind.PARAGRAPH] = (blockCounts[BlockKind.PARAGRAPH] ?: 0) + 1
                }
            }
            firstContentLineSeen = true
            li = lj + 1
        }
        lastBlockCounts = blockCounts
        return out
    }

    /** Block census of the last [layout] call, for diagnostics. */
    var lastBlockCounts: Map<BlockKind, Int> = emptyMap()
        private set

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
    }

    private fun applyHeading(out: SpannableStringBuilder, start: Int, end: Int, style: ReaderStyle) {
        if (end <= start) return
        out.setSpan(RelativeSizeSpan(style.headingScale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(style.headingColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * @param firstLineEnd end of the paragraph's first line — the indent span
     *   is confined to it, so a wrapped line never picks up an indent of its
     *   own no matter how StaticLayout slices the span.
     */
    private fun applyParagraph(
        out: SpannableStringBuilder,
        start: Int,
        end: Int,
        firstLineEnd: Int,
        style: ReaderStyle
    ) {
        if (end <= start) return
        if (style.indentPx > 0f && firstLineEnd > start) {
            out.setSpan(
                LeadingMarginSpan.Standard(style.indentPx.toInt(), 0),
                start, minOf(firstLineEnd, end),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun applyCode(out: SpannableStringBuilder, start: Int, end: Int, style: ReaderStyle) {
        if (end <= start) return
        out.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(BackgroundColorSpan(style.codeBackground), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
