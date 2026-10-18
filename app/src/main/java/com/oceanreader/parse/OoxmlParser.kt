package com.oceanreader.parse

import android.util.Xml
import com.oceanreader.data.model.ChapterRef
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/**
 * Reads Office Open XML word-processing documents (.docx).
 *
 * Paragraph-level text is streamed out of `word/document.xml`; heading styles
 * recognised through `w:pStyle w:val="HeadingN"` become chapters. Images are
 * skipped (a reader has no use for inline artwork that would break the flow).
 */
class OoxmlParser(private val file: File) {

    fun parse(fallbackTitle: String): ParsedDoc {
        ZipFile(file).use { zip ->
            val docEntry = zip.getEntry("word/document.xml")
                ?: zip.entries().asSequence().firstOrNull { it.name.endsWith("document.xml") }
                ?: throw ParseException("DOCX 缺少 word/document.xml")
            val xml = zip.getInputStream(docEntry).use { String(it.readBytes(), Charsets.UTF_8) }

            val body = StringBuilder()
            val chapters = ArrayList<ChapterRef>()

            var paragraph = StringBuilder()
            var headingLevel = 0
            var inText = false
            var inHeadingCapture = false
            var headingText = StringBuilder()
            var headingLevelPending = 0

            fun flush() {
                val text = paragraph.toString().trim()
                paragraph.setLength(0)
                if (text.isEmpty()) return
                val start = body.length.toLong()
                body.append(text).append("\n\n")
                if (headingLevel in 1..6) {
                    chapters.add(ChapterRef(chapters.size, text.take(80), start, text.length.toLong()))
                }
                headingLevel = 0
            }

            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(xml.reader())
            var event = parser.eventType
            var depth = 0
            var paraDepth = -1

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "p" -> {
                                flush()
                                depth++
                                paraDepth = depth
                            }
                            "pStyle" -> {
                                val v = parser.getAttributeValue(null, "val") ?: ""
                                headingLevelPending = headingLevelOf(v)
                            }
                            "r" -> {
                                if (paraDepth == depth) {
                                    // entering a run does not change depth
                                }
                            }
                            "t" -> {
                                inText = true
                                if (headingLevelPending > 0) {
                                    inHeadingCapture = true
                                    headingText.setLength(0)
                                }
                            }
                            "tab" -> paragraph.append("　")
                            "br", "cr" -> paragraph.append('\n')
                            "drawing", "pict", "object" -> Unit
                        }
                        depth++
                    }

                    XmlPullParser.TEXT -> {
                        if (inText) {
                            val t = parser.text.orEmpty()
                            paragraph.append(t)
                            if (inHeadingCapture) headingText.append(t)
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        depth--
                        when (parser.name) {
                            "t" -> {
                                inText = false
                                if (inHeadingCapture && headingLevelPending > 0) {
                                    headingLevel = headingLevelPending
                                    inHeadingCapture = false
                                }
                            }
                            "p" -> {
                                if (paraDepth == depth + 1) {
                                    flush()
                                    headingLevelPending = 0
                                    paraDepth = -1
                                }
                            }
                            "drawing", "pict", "object" -> Unit
                        }
                    }
                }
                event = parser.next()
            }
            flush()

            val core = readCore(zip)
            val text = body.toString().trim()
            val chaptersFinal = if (chapters.isNotEmpty()) chapters else ChapterSplitter.split(text)

            return ParsedDoc(
                title = core.title.ifBlank { fallbackTitle },
                author = core.author,
                description = core.description,
                body = text,
                chapters = chaptersFinal
            )
        }
    }

    private class Core(val title: String, val author: String, val description: String)

    private fun headingLevelOf(styleId: String): Int {
        val s = styleId.trim()
        if (s.isEmpty()) return 0
        val lower = s.lowercase()
        if (lower.startsWith("heading")) {
            return lower.removePrefix("heading").toIntOrNull() ?: 0
        }
        if (s.startsWith("标题")) {
            return s.removePrefix("标题").toIntOrNull() ?: 0
        }
        return 0
    }

    private fun readCore(zip: ZipFile): Core {
        val entry = zip.getEntry("docProps/core.xml")
        if (entry == null) return Core("", "", "")
        val xml = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
        return Core(
            title = tagValue(xml, "dc:title"),
            author = tagValue(xml, "dc:creator"),
            description = tagValue(xml, "dc:description")
        )
    }

    private fun tagValue(xml: String, tag: String): String =
        Regex("<$tag\\b[^>]*>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)
            ?.let { HtmlText.tidyInline(HtmlText.toPlainText(it)) }.orEmpty()
}