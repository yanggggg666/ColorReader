package com.oceanreader.tts

import com.oceanreader.data.model.SpeechSegment

/**
 * Splits a chapter into speakable sentences.
 *
 * Chinese and English need different treatment: CJK terminates on full-width
 * punctuation, English on `.!?` but never inside an abbreviation, a decimal or
 * an ellipsis. Each sentence keeps its offset inside the chapter so the reader
 * can highlight exactly the range being spoken.
 */
object SentenceSegmenter {

    private val HARD = setOf(
        '。', '！', '？', '!', '?', '…', '\n', '\r'
    )

    private val SOFT = setOf('；', ';')

    /** Sentence-ish breaks we do not honour inside quotes. */
    private val QUOTES = setOf('"', '“', '”', '‘', '’', '「', '」', '『', '』', '《', '》')

    private val ABBREVIATIONS = setOf(
        "Mr", "Mrs", "Ms", "Dr", "Prof", "Sr", "Jr", "St", "vs", "etc", "e.g", "i.e",
        "No", "Fig", "Vol", "pp", "Inc", "Ltd", "Co", "Dept", "Est", "approx"
    )

    /** Longest sentence we hand to the engine in one go. */
    private const val MAX_SENTENCE = 260

    /** Minimum; anything shorter is merged forward to avoid choppy playback. */
    private const val MIN_SENTENCE = 2

    fun segment(chapterIndex: Int, text: String): List<SpeechSegment> {
        if (text.isBlank()) return emptyList()
        val result = ArrayList<SpeechSegment>()
        val n = text.length
        var start = 0
        var i = 0

        fun flush(upTo: Int, mergeWithPrevious: Boolean) {
            var from = start
            var to = upTo
            while (from < to && text[from].isWhitespace()) from++
            while (to > from && text[to - 1].isWhitespace()) to--
            if (to <= from) {
                start = upTo
                return
            }
            val body = text.substring(from, to)
            if (mergeWithPrevious && result.isNotEmpty()) {
                val prev = result.removeAt(result.size - 1)
                val merged = prev.text + body
                result.add(
                    prev.copy(
                        text = merged,
                        startInChapter = prev.startInChapter,
                        end = prev.startInChapter + merged.length
                    )
                )
            } else {
                result.add(
                    SpeechSegment(
                        index = result.size,
                        chapterIndex = chapterIndex,
                        startInChapter = from,
                        text = body,
                        end = from + body.length
                    )
                )
            }
            start = upTo
        }

        while (i < n) {
            val c = text[i]

            if (c in HARD) {
                // Consume runs like "!!!" or "……"
                var end = i + 1
                while (end < n && text[end] in HARD) end++
                while (end < n && text[end] in QUOTES) end++
                val merged = (end - start) < MIN_SENTENCE && result.isNotEmpty()
                flush(end, merged)
                i = end
                continue
            }

            if (c in SOFT) {
                val end = i + 1
                val merged = (end - start) < MIN_SENTENCE && result.isNotEmpty()
                flush(end, merged)
                i = end
                continue
            }

            if (c == '.') {
                // 3.14 / 1.5 / e.g. / Mr. / ...
                val prev = if (i > 0) text[i - 1] else ' '
                val next = if (i + 1 < n) text[i + 1] else ' '
                val isDecimal = prev.isDigit() && next.isDigit()
                val isAbbrev = endsWithAbbreviation(text, i)
                if (!isDecimal && !isAbbrev) {
                    var end = i + 1
                    while (end < n && text[end] in QUOTES) end++
                    val merged = (end - start) < MIN_SENTENCE && result.isNotEmpty()
                    flush(end, merged)
                    i = end
                    continue
                }
            }

            // Hard cap so a wall of text still produces usable sentences.
            if (i - start >= MAX_SENTENCE) {
                var end = i
                // Prefer to cut at the last space.
                val space = text.lastIndexOf(' ', i)
                if (space > start + MAX_SENTENCE / 2) end = space
                val merged = (end - start) < MIN_SENTENCE && result.isNotEmpty()
                flush(end, merged)
                continue
            }

            i++
        }

        if (start < n) {
            val merged = (n - start) < MIN_SENTENCE && result.isNotEmpty()
            flush(n, merged)
        }

        return result.mapIndexed { idx, seg -> seg.copy(index = idx) }
    }

    /** Segments a whole book: chapter index per sentence. */
    fun segmentAll(chapters: List<String>): List<SpeechSegment> {
        val out = ArrayList<SpeechSegment>()
        for ((chapterIndex, text) in chapters.withIndex()) {
            out.addAll(segment(chapterIndex, text))
        }
        return out.mapIndexed { idx, seg -> seg.copy(index = idx) }
    }

    private fun endsWithAbbreviation(text: String, dotIndex: Int): Boolean {
        var end = dotIndex
        var start = dotIndex - 1
        while (start >= 0 && (text[start].isLetter() || text[start] == '.')) start--
        val word = text.substring(start + 1, end)
        if (word.isEmpty()) return false
        return ABBREVIATIONS.any { it.equals(word, ignoreCase = true) }
    }
}