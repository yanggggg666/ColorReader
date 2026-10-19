package com.oceanreader.parse

import com.oceanreader.data.model.ChapterRef

/**
 * Splits a flat body of text into navigable chapters.
 *
 * Strategy, in order of preference:
 *  1. explicit headings (Chinese numerals, "Chapter N", markdown ATX, numbered
 *     sections) that are surrounded by blank lines;
 *  2. a short-line heuristic for styles that put a bare title on its own line;
 *  3. fixed-size slices so navigation and TTS still work on unstructured files.
 */
object ChapterSplitter {

    private val CN_NUM = "[0-9０-９一二三四五六七八九十百千万零两壹贰叁肆伍陆柒捌玖拾佰仟]"

    private val PATTERNS: List<Regex> = listOf(
        Regex("^\\s*第\\s*$CN_NUM{1,12}\\s*[章节節回卷部篇集话話幕][^\\n]{0,40}$"),
        Regex("^\\s*第\\s*$CN_NUM{1,12}\\s*(?:章|节|回)[\\s:：.、]*[^\\n]{0,40}$"),
        Regex("^\\s*(?:序章?|序言|自序|前言|引子|楔子|后记|後記|尾声|尾聲|跋|附录|附錄|番外|外传|终章|終章)\\s*[^\\n]{0,40}$"),
        Regex("^\\s*(?:chapter|CHAPTER|Chapter|Chap\\.?|PART|Part)\\s+[0-9IVXLCivxlc]+\\b[^\\n]{0,40}$"),
        Regex("^\\s*[0-9]{1,4}[\\.、．]\\s*[^\\n]{0,40}$"),
        Regex("^\\s*[（(]\\s*$CN_NUM{1,6}\\s*[）)]\\s*[^\\n]{0,40}$"),
        Regex("^\\s*#{1,4}\\s+\\S[^\\n]{0,60}$")
    )

    private val NOT_HEADING_TAIL = Regex("[。！？!?，,；;：:]$")

    fun split(
        body: String,
        maxCharsPerChapter: Int = 12_000,
        forceMarkdown: Boolean = false
    ): List<ChapterRef> {
        val explicit = scanExplicit(body, forceMarkdown)
        if (explicit.size >= 2) return build(body, explicit)
        val heuristic = scanHeuristic(body)
        if (heuristic.size >= 3) return build(body, heuristic)
        return slice(body, maxCharsPerChapter)
    }

    private data class Hit(val line: Int, val title: String, val offset: Int)

    private fun scanExplicit(body: String, forceMarkdown: Boolean): List<Hit> {
        val hits = ArrayList<Hit>()
        var offset = 0
        var blankBefore = true
        var lineNo = 0
        var prevBlank = true
        var firstHeading = true
        for (line in body.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                prevBlank = true
                offset += line.length + 1
                lineNo++
                continue
            }
            val isMarkdown = trimmed.startsWith("#") && trimmed.length > 2
            val shortEnough = trimmed.length <= 50
            val looksHeading = shortEnough &&
                (PATTERNS.any { it.matches(trimmed) } ||
                    (isMarkdown && (forceMarkdown || PATTERNS.any { it.matches(trimmed) })))
            if (looksHeading && (prevBlank || firstHeading)) {
                // Reject a heading that is really a sentence ending with
                // punctuation — those rarely sit alone on a line in novels.
                if (!NOT_HEADING_TAIL.containsMatchIn(trimmed) || trimmed.length <= 8) {
                    val title = trimmed.trimStart('#').trim()
                    hits.add(Hit(lineNo, title, offset))
                    firstHeading = false
                }
            }
            firstHeading = false
            prevBlank = false
            offset += line.length + 1
            lineNo++
            if (hits.size > 4000) break
        }
        // Drop a "prelude" hit at offset 0 if it is the very first content line
        // only when a large amount of text follows the previous heading.
        return hits
    }

    private fun scanHeuristic(body: String): List<Hit> {
        val hits = ArrayList<Hit>()
        var offset = 0
        var prevBlank = false
        var lineNo = 0
        for (line in body.split('\n')) {
            val t = line.trim()
            if (t.isEmpty()) {
                prevBlank = offset > 0
                offset += line.length + 1
                lineNo++
                continue
            }
            val short = t.length in 2..24
            val notPunct = !NOT_HEADING_TAIL.containsMatchIn(t)
            val noDigits = !t.any { it.isDigit() } || t.length <= 6
            if (short && notPunct && noDigits && prevBlank) hits.add(Hit(lineNo, t, offset))
            prevBlank = false
            offset += line.length + 1
            lineNo++
            if (hits.size > 2000) break
        }
        return hits
    }

    private fun build(body: String, hits: List<Hit>): List<ChapterRef> {
        val total = body.length.toLong()
        val out = ArrayList<ChapterRef>(hits.size)
        val sorted = hits.sortedBy { it.offset }
        // Pre-chapter content becomes chapter 0 when it is substantial.
        if (sorted.first().offset > 400) {
            out.add(ChapterRef(0, "开始", 0L, sorted.first().offset.toLong()))
        }
        for ((i, hit) in sorted.withIndex()) {
            val start = hit.offset.toLong()
            val end = (if (i + 1 < sorted.size) sorted[i + 1].offset else total.toInt()).toLong()
            val len = (end - start).coerceAtLeast(0L)
            if (len <= 1) continue
            out.add(ChapterRef(out.size, hit.title, start, len))
        }
        return if (out.isEmpty()) listOf(ChapterRef(0, "全文", 0L, total)) else out
    }

    private fun slice(body: String, maxChars: Int): List<ChapterRef> {
        val total = body.length.toLong()
        if (total <= maxChars) return listOf(ChapterRef(0, "全文", 0L, total))
        val out = ArrayList<ChapterRef>()
        var start = 0L
        var index = 0
        while (start < total) {
            var end = (start + maxChars).coerceAtMost(total)
            if (end < total) {
                // Prefer to break on a paragraph boundary.
                val window = body.substring(end.toInt().coerceAtMost(body.length), (end + 600).toInt().coerceAtMost(body.length))
                val nl = window.indexOf("\n\n")
                if (nl > 0) end += nl + 2
            }
            val len = end - start
            if (len <= 0) break
            out.add(ChapterRef(index, "第 ${index + 1} 部分", start, len))
            index++
            start = end
        }
        return out
    }
}