package com.oceanreader.parse

import com.oceanreader.data.model.ChapterRef
import com.oceanreader.data.model.DocFormat
import java.io.File
import java.io.InputStream

/** Plain-text family: txt / log / nfo / markdown / rtf / html. */
object TextParsers {

    fun isTextFormat(format: DocFormat): Boolean = when (format) {
        DocFormat.TXT, DocFormat.MD, DocFormat.RTF, DocFormat.HTML -> true
        else -> false
    }

    fun parse(file: File, format: DocFormat, fallbackTitle: String): ParsedDoc = when (format) {
        DocFormat.RTF -> parseRtf(file, fallbackTitle)
        DocFormat.HTML -> parseHtml(file, fallbackTitle)
        DocFormat.MD -> parseMarkdown(file, fallbackTitle)
        else -> parsePlain(file, fallbackTitle)
    }

    fun parseStream(stream: InputStream, format: DocFormat, fallbackTitle: String): ParsedDoc {
        val bytes = stream.use { it.readBytes() }
        val text = when (format) {
            DocFormat.RTF -> Rtf.toText(String(bytes, Charsets.GB18030))
            DocFormat.HTML -> HtmlText.toPlainText(String(bytes, Charsets.UTF_8))
            else -> CharsetSniffer.decode(bytes)
        }
        val cleaned = normalize(text)
        return ParsedDoc(
            title = fallbackTitle,
            body = cleaned,
            chapters = ChapterSplitter.split(cleaned, forceMarkdown = format == DocFormat.MD)
        )
    }

    fun parsePlain(file: File, fallbackTitle: String): ParsedDoc {
        val bytes = file.readBytes()
        val text = CharsetSniffer.decode(bytes)
        val cleaned = normalize(text)
        return ParsedDoc(
            title = titleFromBody(cleaned, fallbackTitle),
            body = cleaned,
            chapters = ChapterSplitter.split(cleaned)
        )
    }

    fun parseMarkdown(file: File, fallbackTitle: String): ParsedDoc {
        val raw = CharsetSniffer.decode(file.readBytes())
        val flat = flattenMarkdown(raw)
        return ParsedDoc(
            title = titleFromBody(flat, fallbackTitle),
            body = flat,
            chapters = ChapterSplitter.split(flat, forceMarkdown = true)
        )
    }

    fun parseHtml(file: File, fallbackTitle: String): ParsedDoc {
        val raw = String(file.readBytes(), Charsets.UTF_8)
        val blocks = HtmlText.blocks(raw)
        val body = HtmlText.toPlainText(raw)
        val htmlTitle = HtmlText.title(raw)
        val chapters = buildChaptersFromBlocks(blocks, body)
        return ParsedDoc(
            title = htmlTitle ?: titleFromBody(body, fallbackTitle),
            body = body,
            chapters = chapters
        )
    }

    fun parseRtf(file: File, fallbackTitle: String): ParsedDoc {
        val raw = Rtf.toText(String(file.readBytes(), Charsets.GB18030))
        val cleaned = normalize(raw)
        return ParsedDoc(
            title = titleFromBody(cleaned, fallbackTitle),
            body = cleaned,
            chapters = ChapterSplitter.split(cleaned)
        )
    }

    /** Normalises line endings and strips control characters. */
    fun normalize(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '\r' -> {
                    sb.append('\n')
                    if (i + 1 < n && text[i + 1] == '\n') i++
                }
                c == '\t' -> sb.append("    ")
                c == '\u0000' -> {}
                c.code in 0x01..0x08 -> {}
                c.code == 0x0B -> sb.append('\n')
                c.code in 0x0E..0x1F -> {}
                c == '\u00A0' -> sb.append(' ')
                c.isSurrogate() -> {
                    // keep well-formed pairs, drop lone surrogates
                    if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(text[i + 1])) {
                        sb.append(c).append(text[i + 1])
                        i++
                    } else {
                        sb.append('\uFFFD')
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        // collapse 3+ blank lines into one
        val out = StringBuilder(sb.length)
        var blanks = 0
        for (line in sb.toString().split('\n')) {
            if (line.isBlank()) {
                blanks++
                if (blanks > 1) continue
            } else {
                blanks = 0
            }
            out.append(line.trimEnd()).append('\n')
        }
        return out.toString().trim()
    }

    /** Uses a short leading line as the title when it looks like one. */
    fun titleFromBody(body: String, fallback: String): String {
        val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        val candidate = firstLine.trim('#', '*', ' ', '：', ':')
        return if (candidate.length in 1..40 && !candidate.contains('\n')) candidate else fallback
    }

    fun buildChaptersFromBlocks(blocks: List<HtmlText.Block>, fallbackBody: String): List<ChapterRef> {
        if (blocks.isEmpty()) return ChapterSplitter.split(fallbackBody)
        val headings = blocks.filter { it.headingLevel in 1..2 }
        val body = blocks.joinToString("\n\n") { it.text }
        if (headings.size >= 2) {
            val refs = ArrayList<ChapterRef>()
            val positions = ArrayList<Int>()
            var cursor = 0
            var i = 0
            while (i < headings.size) {
                var start = cursor
                while (start < body.length && !body.startsWith(headings[i].text, start)) start++
                if (start >= body.length) break
                positions.add(start)
                cursor = start + headings[i].text.length
                i++
            }
            if (positions.size >= 2) {
                for (k in positions.indices) {
                    val start = positions[k]
                    val end = if (k + 1 < positions.size) positions[k + 1] else body.length
                    refs.add(ChapterRef(k, headings[k].text, start.toLong(), (end - start).toLong()))
                }
                return refs
            }
        }
        return ChapterSplitter.split(body)
    }

    /**
     * Turns markdown source into flowing prose: structural markers are dropped,
     * links keep their label, lists gain a bullet, code fences keep their code.
     */
    fun flattenMarkdown(raw: String): String {
        val out = StringBuilder(raw.length)
        var inFence = false
        for (line in raw.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
                out.append('\n')
                continue
            }
            if (inFence) {
                out.append(trimmed).append('\n')
                continue
            }
            when {
                trimmed.startsWith("#") -> {
                    val level = trimmed.takeWhile { it == '#' }.length
                    val text = trimmed.drop(level).trim()
                    out.append(text).append('\n').append('\n')
                }
                trimmed.isEmpty() -> out.append('\n')
                Regex("^([-*_])\\1{2,}$").matches(trimmed) -> out.append('\n')
                Regex("^>\\s?").containsMatchIn(trimmed) -> out.append(trimmed.replaceFirst("^>\\s?", ""))
                Regex("^\\s*[-*+]\\s+").containsMatchIn(trimmed) -> out.append("• " + trimmed.replaceFirst("^\\s*[-*+]\\s+", ""))
                Regex("^\\s*\\d+[.)]\\s+").containsMatchIn(trimmed) -> out.append(trimmed.trim())
                trimmed.startsWith("|") -> out.append(cleanTableRow(trimmed))
                else -> out.append(stripInline(trimmed))
            }
        }
        return normalize(out.toString())
    }

    private fun cleanTableRow(line: String): String =
        line.trim('|').split('|').joinToString("\t") { it.trim() }

    private fun stripInline(line: String): String {
        var s = line
        s = Regex("!\\[([^\\]]*)]\\([^)]*\\)").replace(s) { "［${it.groupValues[1]}］" }
        s = Regex("\\[([^\\]]+)]\\([^)]*\\)").replace(s) { it.groupValues[1] }
        s = s.replace("**", "").replace("__", "")
        s = Regex("(?<!\\*)\\*(?!\\*)").replace(s, "")
        s = s.replace("~~", "")
        s = Regex("`([^`]*)`").replace(s) { it.groupValues[1] }
        return s
    }
}

/** Minimal RTF → text converter (enough for Word-generated .rtf books). */
object Rtf {

    private val IGNORED_DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object",
        "themedata", "colorschememapping", "latentstyles", "datastore",
        "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl",
        "filetbl", "revtbl", "upr", "header", "footer", "footnote", "ftnsep"
    )

    fun toText(rtf: String): String {
        val out = StringBuilder(rtf.length / 2)
        var i = 0
        val n = rtf.length
        var skipDepth = 0
        var groupDepth = 0
        var pendingSkip = false

        fun emit(s: String) {
            if (skipDepth == 0 && pendingSkip) return
            out.append(s)
        }

        while (i < n) {
            val c = rtf[i]
            when {
                c == '{' -> {
                    groupDepth++
                    if (pendingSkip) skipDepth = groupDepth
                    pendingSkip = false
                    i++
                }
                c == '}' -> {
                    if (skipDepth == groupDepth) skipDepth = 0
                    groupDepth--
                    i++
                }
                c == '\\' -> {
                    val next = if (i + 1 < n) rtf[i + 1] else return out.toString()
                    if (next == '\'') {
                        val hex = if (i + 3 < n) rtf.substring(i + 2, i + 4) else return out.toString()
                        val code = hex.toIntOrNull(16)
                        if (code != null && skipDepth == 0) out.append(code.toChar())
                        i += 4
                    } else if (next == '\\' || next == '{' || next == '}') {
                        if (skipDepth == 0) out.append(next)
                        i += 2
                    } else if (next == '\n' || next == '\r') {
                        if (skipDepth == 0) out.append('\n')
                        i += 2
                    } else if (next == '*') {
                        pendingSkip = true
                        i += 2
                    } else if (next.isLetter()) {
                        var j = i + 1
                        while (j < n && rtf[j].isLetter()) j++
                        val word = rtf.substring(i + 1, j)
                        var param: Int? = null
                        if (j < n && (rtf[j] == '-' || rtf[j].isDigit())) {
                            var k = j
                            if (rtf[k] == '-') k++
                            val digitsStart = k
                            while (k < n && rtf[k].isDigit()) k++
                            param = rtf.substring(j, k).toIntOrNull()
                            j = k
                            if (digitsStart == k) param = null
                        }
                        if (j < n && rtf[j] == ' ') j++
                        i = j
                        if (skipDepth > 0) continue
                        when (word) {
                            "par", "sect", "line", "page" -> emit("\n")
                            "tab" -> emit("\t")
                            "cell", "row" -> emit("\t")
                            "emdash" -> emit("—")
                            "endash" -> emit("–")
                            "lquote" -> emit("‘")
                            "rquote" -> emit("’")
                            "ldblquote" -> emit("“")
                            "rdblquote" -> emit("”")
                            "bullet" -> emit("•")
                            "u" -> if (param != null) {
                                val cp = if (param < 0) param + 65536 else param
                                emit(String(Character.toChars(cp)))
                            }
                            in IGNORED_DESTINATIONS -> pendingSkip = true
                        }
                    } else {
                        i += 2
                    }
                }
                c == '\r' || c == '\n' -> i++
                else -> {
                    if (skipDepth == 0) out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}