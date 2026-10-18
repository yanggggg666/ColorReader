package com.oceanreader.parse

/**
 * A tolerant HTML/XHTML to plain-text converter.
 *
 * We deliberately do not use `Html.fromHtml` / Jsoup: EPUB content files are
 * frequently malformed XML, and we need heading levels and image alt text in
 * order to build a table of contents.
 */
object HtmlText {

    class Block(val text: String, val headingLevel: Int = 0, val imageAlt: String? = null)

    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "blockquote", "li", "ul", "ol", "dl", "dd", "dt",
        "pre", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "table", "figure", "figcaption",
        "hr", "br", "center", "body", "aside", "main", "header", "footer", "nav", "td", "th"
    )

    private val SKIP_CONTENT_TAGS = setOf("script", "style", "head", "title")

    /** Full document -> plain text, preserving paragraph breaks as \n\n. */
    fun toPlainText(html: String): String {
        val out = StringBuilder(html.length / 2)
        var i = 0
        var skipping: String? = null
        val n = html.length
        while (i < n) {
            val c = html[i]
            if (c == '<') {
                val end = html.indexOf('>', i + 1)
                if (end < 0) {
                    i = n
                    continue
                }
                val tag = html.substring(i + 1, end).trim()
                val name = tagName(tag)
                if (skipping != null) {
                    if (name == skipping && !tag.startsWith("/")) skipping = null
                    i = end + 1
                    continue
                }
                if (name in SKIP_CONTENT_TAGS && !tag.startsWith("/")) {
                    skipping = name
                    i = end + 1
                    continue
                }
                when {
                    name == "br" -> out.append('\n')
                    name == "hr" -> out.append("\n\n")
                    name == "img" -> {
                        altOf(tag)?.let { out.append(it) }
                    }
                    name in BLOCK_TAGS -> {
                        if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append('\n')
                    }
                }
                i = end + 1
            } else {
                if (c == '&') {
                    val semi = html.indexOf(';', i + 1)
                    if (semi in (i + 1)..(i + 12)) {
                        val entity = html.substring(i + 1, semi)
                        val decoded = decodeEntity(entity)
                        if (decoded != null) {
                            out.append(decoded)
                            i = semi + 1
                            continue
                        }
                    }
                }
                out.append(c)
                i++
            }
        }
        return tidy(out.toString())
    }

    /** Paragraph-oriented extraction used to build a table of contents. */
    fun blocks(html: String): List<Block> {
        val result = ArrayList<Block>()
        val buf = StringBuilder()
        var pendingImage: String? = null

        fun flush(level: Int) {
            val t = tidyInline(buf.toString())
            buf.setLength(0)
            if (t.isNotEmpty()) result.add(Block(t, level, pendingImage))
            pendingImage = null
        }

        var i = 0
        var skipping: String? = null
        val n = html.length
        var level = 0
        while (i < n) {
            val c = html[i]
            if (c == '<') {
                val end = html.indexOf('>', i + 1)
                if (end < 0) {
                    buf.append(html, i, n)
                    break
                }
                val tag = html.substring(i + 1, end).trim()
                val name = tagName(tag)
                if (skipping != null) {
                    if (name == skipping && !tag.startsWith("/")) skipping = null
                    i = end + 1
                    continue
                }
                if (name in SKIP_CONTENT_TAGS && !tag.startsWith("/")) {
                    skipping = name
                    i = end + 1
                    continue
                }
                if (name == "br") {
                    buf.append('\n')
                } else if (name == "img") {
                    altOf(tag)?.let { pendingImage = it; buf.append(it) }
                } else if (name.startsWith("h") && name.length == 2 && name[1] in '1'..'6') {
                    if (buf.isNotEmpty()) flush(level)
                    level = name[1] - '0'
                    val close = html.indexOf("</$name", end, ignoreCase = true)
                    if (close in 0 until n) {
                        val inner = html.substring(end + 1, close)
                        val text = tidyInline(HtmlText.toPlainText(inner))
                        if (text.isNotEmpty()) result.add(Block(text, level, pendingImage))
                        pendingImage = null
                        val gt = html.indexOf('>', close)
                        i = if (gt > 0) gt + 1 else n
                        level = 0
                        continue
                    }
                } else if (name in BLOCK_TAGS) {
                    flush(level)
                    level = 0
                }
                i = end + 1
            } else {
                if (c == '&') {
                    val semi = html.indexOf(';', i + 1)
                    if (semi in (i + 1)..(i + 12)) {
                        val decoded = decodeEntity(html.substring(i + 1, semi))
                        if (decoded != null) {
                            buf.append(decoded)
                            i = semi + 1
                            continue
                        }
                    }
                }
                buf.append(c)
                i++
            }
        }
        flush(level)
        return result
    }

    /** Reads <title> out of a document head. */
    fun title(html: String): String? {
        val m = Regex("(?is)<title[^>]*>(.*?)</title>").find(html) ?: return null
        val t = tidyInline(HtmlText.toPlainText(m.groupValues[1]))
        return t.ifBlank { null }
    }

    private fun tagName(tag: String): String {
        var s = tag
        if (s.startsWith("/")) s = s.substring(1)
        var i = 0
        while (i < s.length && (s[i].isLetterOrDigit())) i++
        return s.substring(0, i).lowercase()
    }

    private fun altOf(tag: String): String? {
        val m = Regex("(?i)alt\\s*=\\s*\"([^\"]*)\"").find(tag)
            ?: Regex("(?i)alt\\s*=\\s*'([^']*)'").find(tag)
            ?: return null
        return decodeEntity(m.groupValues[1])?.trim()?.ifBlank { null }
    }

    private fun decodeEntity(e: String): String? = when {
        e.isEmpty() -> null
        e.startsWith("#x") || e.startsWith("#X") ->
            e.drop(2).toIntOrNull(16)?.let { codePointToString(it) }
        e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { codePointToString(it) }
        else -> named(e)
    }

    private fun codePointToString(cp: Int): String? =
        if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else null

    private fun named(e: String): String? = when (e) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        "nbsp" -> " "
        "ensp" -> " "
        "emsp" -> " "
        "thinsp" -> " "
        "ndash" -> "–"
        "mdash" -> "—"
        "hellip" -> "…"
        "ldquo" -> "“"
        "rdquo" -> "”"
        "lsquo" -> "‘"
        "rsquo" -> "’"
        "copy" -> "©"
        "reg" -> "®"
        "trade" -> "™"
        "deg" -> "°"
        "middot" -> "·"
        "bull" -> "•"
        "dagger" -> "†"
        "laquo" -> "«"
        "raquo" -> "»"
        "times" -> "×"
        "divide" -> "÷"
        "alpha" -> "α"
        "beta" -> "β"
        "pi" -> "π"
        "euro" -> "€"
        "pound" -> "£"
        "yen" -> "¥"
        "sect" -> "§"
        "para" -> "¶"
        "shy" -> ""
        "zwj" -> ""
        "zwnj" -> ""
        else -> null
    }

    /** Collapses runs of spaces/tabs but keeps paragraph structure. */
    fun tidy(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        var newlines = 0
        while (i < n) {
            val c = text[i]
            when {
                c == '\r' -> {
                    i++
                    if (i < n && text[i] == '\n') i++
                    newlines++
                }
                c == '\n' -> {
                    newlines++
                    i++
                }
                c == ' ' || c == '\t' -> {
                    if (sb.isNotEmpty() && newlines == 0 && !sb.endsWith(" ")) sb.append(' ')
                    i++
                }
                else -> {
                    if (newlines > 0 && sb.isNotEmpty()) {
                        sb.append(if (newlines >= 2) "\n\n" else "\n")
                    }
                    newlines = 0
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString().trim()
    }

    fun tidyInline(text: String): String = tidy(text).replace('\n', ' ').trim()
}