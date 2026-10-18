package com.oceanreader.parse

import android.util.Base64
import android.util.Xml
import com.oceanreader.data.model.ChapterRef
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/**
 * FictionBook 2 (.fb2 / .fbz) reader.
 *
 * A single XmlPullParser pass collects <description> metadata and walks
 * <body>, emitting one chapter per top-level <section>. Block level elements
 * (<p>, <v>, <subtitle>, <cite>…) become paragraphs; binary artwork inside
 * <coverpage> is extracted for the shelf.
 */
object Fb2Parser {

    private val BLOCK_TAGS = setOf("p", "v", "subtitle", "text-author", "poem", "epigraph", "cite")
    private val IGNORE_TAGS = setOf("empty-line", "binary", "binary-content")

    fun parse(file: File, fallbackTitle: String): ParsedDoc {
        val head = file.inputStream().use { ByteArray(4).also { s -> s.read(it) } }
        val isZip = head.size > 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        return if (isZip) parseArchive(file, fallbackTitle) else parsePlain(file, fallbackTitle)
    }

    private fun parsePlain(file: File, fallbackTitle: String): ParsedDoc {
        val parser = newParser(file.readBytes().toString(Charsets.UTF_8))
        return consume(parser, fallbackTitle, null, null)
    }

    private fun parseArchive(file: File, fallbackTitle: String): ParsedDoc = ZipFile(file).use { zip ->
        val entry = zip.entries().asSequence().firstOrNull {
            !it.isDirectory && (it.name.endsWith(".fb2", true) || it.name.endsWith(".xml", true))
        } ?: throw ParseException("FBZ 压缩包内没有找到正文文件")
        val xml = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
        consume(newParser(xml), fallbackTitle, zip, entry.name)
    }

    private fun newParser(xml: String): XmlPullParser =
        Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(xml.reader())
        }

    private fun consume(
        parser: XmlPullParser,
        fallbackTitle: String,
        zip: ZipFile?,
        archiveName: String?
    ): ParsedDoc {
        var title = ""
        val authors = ArrayList<String>()
        val description = StringBuilder()
        val body = StringBuilder()
        val chapters = ArrayList<ChapterRef>()

        var sectionDepth = 0
        var chapterStart = 0L
        var chapterTitle = StringBuilder()

        var blockTag: String? = null
        val buffer = StringBuilder()
        var inTextTag = false
        var inBookTitle = false
        var inAnnotation = false
        var inDescription = false
        var inAuthorField = false
        var language = ""
        var seriesTitle = ""

        // cover handling
        var inCoverPage = false
        var coverBase64: String? = null
        var coverBuffer: StringBuilder? = null
        var coverId: String? = null
        var pendingImageRef: String? = null

        fun flushBlock() {
            val tag = blockTag ?: return
            val text = HtmlText.tidyInline(buffer.toString())
            buffer.setLength(0)
            blockTag = null
            if (text.isBlank()) return
            when (tag) {
                "v" -> {
                    body.append("　").append(text).append('\n')
                }
                else -> {
                    if (sectionDepth == 1 && chapters.isEmpty() && chapterTitle.isBlank() && tag == "subtitle") {
                        chapterTitle.append(text)
                    }
                    body.append(text).append("\n\n")
                }
            }
        }

        fun closeChapter(index: Int) {
            val end = body.length.toLong()
            if (end <= chapterStart) return
            chapters.add(
                ChapterRef(
                    index,
                    chapterTitle.toString().trim().ifBlank { "第 ${index + 1} 节" },
                    chapterStart,
                    end - chapterStart
                )
            )
            chapterTitle = StringBuilder()
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "FictionBook" -> language = parser.getAttributeValue(null, "lang") ?: language
                    "body" -> Unit
                    "section" -> {
                        if (sectionDepth == 0) {
                            if (body.isNotEmpty()) closeChapter(chapters.size)
                            chapterStart = body.length.toLong()
                        }
                        sectionDepth++
                    }
                    "book-title" -> inBookTitle = true
                    "annotation" -> inAnnotation = true
                    "description" -> inDescription = true
                    "nickname", "first-name", "last-name" -> inAuthorField = true
                    "coverpage" -> inCoverPage = true
                    "image" -> if (inCoverPage) {
                        pendingImageRef = parser.getAttributeValue(null, "href")
                    }
                    "binary" -> if (inCoverPage && coverBase64 == null) {
                        val id = parser.getAttributeValue(null, "id")
                        val type = parser.getAttributeValue(null, "content-type") ?: ""
                        if (type.startsWith("image", true) && id != null) {
                            coverId = id
                            coverBuffer = StringBuilder()
                        }
                    }
                    else -> if (parser.name in BLOCK_TAGS) {
                        flushBlock()
                        blockTag = parser.name
                        buffer.setLength(0)
                    }
                }

                XmlPullParser.TEXT -> {
                    val t = parser.text ?: continue
                    when {
                        coverBuffer != null && inCoverPage -> coverBuffer.append(t)
                        inBookTitle -> title += t
                        inAuthorField -> authors.add(t.trim())
                        inAnnotation -> description.append(t)
                        blockTag != null -> buffer.append(t)
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "book-title" -> inBookTitle = false
                    "annotation" -> inAnnotation = false
                    "description" -> inDescription = false
                    "nickname", "first-name", "last-name" -> inAuthorField = false
                    "coverpage" -> {
                        inCoverPage = false
                        if (coverBuffer != null && coverBase64 == null) {
                            coverBase64 = coverBuffer.toString()
                            coverBuffer = null
                        }
                    }
                    "section" -> {
                        if (sectionDepth > 0) sectionDepth--
                        if (sectionDepth == 0) closeChapter(chapters.size)
                    }
                    "binary" -> Unit
                    "image" -> pendingImageRef = null
                    in IGNORE_TAGS -> Unit
                    else -> if (parser.name == blockTag) flushBlock()
                }
            }
            event = parser.next()
        }
        if (body.isNotEmpty()) closeChapter(chapters.size)

        if (chapters.isEmpty()) chapters.add(ChapterRef(0, "全文", 0L, body.length.toLong()))
        if (title.isBlank()) title = fallbackTitle

        return ParsedDoc(
            title = title.trim().take(120),
            author = authors.joinToString(" ").trim(),
            description = description.toString().trim().take(1500),
            body = body.toString(),
            chapters = chapters,
            coverBytes = extractCover(coverBase64, pendingImageRef, zip, archiveName),
            language = language,
            seriesTitle = seriesTitle
        )
    }

    private fun extractCover(
        base64: String?,
        fileRef: String?,
        zip: ZipFile?,
        archiveName: String?
    ): ByteArray? {
        if (base64 != null) {
            val cleaned = base64.filterNot { it.isWhitespace() }
            if (cleaned.length > 200) {
                runCatching { Base64.decode(cleaned, Base64.DEFAULT) }
                    .getOrNull()
                    ?.takeIf { it.size > 256 }
                    ?.let { return it }
            }
        }
        if (zip != null && !fileRef.isNullOrBlank()) {
            zip.getEntry(fileRef)?.let { entry ->
                runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull()
                    ?.takeIf { it.size > 256 }
                    ?.let { return it }
            }
        }
        return null
    }
}