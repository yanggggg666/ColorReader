package com.oceanreader.parse

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.oceanreader.data.model.ChapterRef
import com.oceanreader.data.model.DocFormat
import com.oceanreader.data.model.ReaderKind
import com.oceanreader.parse.pdf.PdfPageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** Result of sniffing a file that the user just imported. */
data class FormatProbe(val format: DocFormat, val label: String, val supported: Boolean)

/**
 * The single entry point for opening a document. Every format is dispatched
 * here so the rest of the app never has to know which parser produced a book.
 */
object DocumentFactory {

    private val MAGIC_PDF = byteArrayOf(0x25, 0x50, 0x44, 0x46)          // %PDF
    private val MAGIC_OLE = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
    )

    /** Sniffs the real format from content, falling back to the extension. */
    fun probe(file: File): FormatProbe {
        val byExtension = DocFormat.fromExtension(file.name)
        val head = try {
            file.inputStream().use { input ->
                val buf = ByteArray(2048)
                val n = input.read(buf)
                if (n > 0) buf.copyOf(n) else ByteArray(0)
            }
        } catch (e: Exception) {
            ByteArray(0)
        }

        if (head.size >= 4 && head.take(4).toByteArray() == MAGIC_PDF) {
            return FormatProbe(DocFormat.PDF, "PDF", true)
        }
        if (head.size >= 8 && head.take(8).toByteArray() == MAGIC_OLE) {
            return FormatProbe(DocFormat.DOC, "Word 97-2003", true)
        }
        if (isZip(file)) {
            // ZIP could be EPUB, DOCX or FBZ — look inside.
            ZipFile(file).use { zip ->
                if (zip.getEntry("mimetype") != null) return FormatProbe(DocFormat.EPUB, "EPUB", true)
                if (zip.getEntry("META-INF/container.xml") != null) return FormatProbe(DocFormat.EPUB, "EPUB", true)
                if (zip.getEntry("word/document.xml") != null) return FormatProbe(DocFormat.DOCX, "Word", true)
                if (zip.entries().asSequence().any { it.name.endsWith(".fb2", true) }) {
                    return FormatProbe(DocFormat.FB2, "FictionBook", true)
                }
                return FormatProbe(DocFormat.UNKNOWN, "压缩包", false)
            }
        }
        val text = String(head, Charsets.LATIN1).trimStart()
        if (text.startsWith("{\\rtf")) return FormatProbe(DocFormat.RTF, "RTF", true)
        if (text.startsWith("<?xml") && (text.contains("FictionBook") || text.contains("<FictionBook"))) {
            return FormatProbe(DocFormat.FB2, "FictionBook", true)
        }
        if (text.startsWith("<?xml") && text.contains("<html", ignoreCase = true)) {
            return FormatProbe(DocFormat.HTML, "网页", true)
        }
        if (text.contains("BOOKMOBI", ignoreCase = true) || text.contains("TEXtREAd")) {
            return FormatProbe(DocFormat.MOBI, "MOBI", true)
        }
        // A PalmDOC record list starts with "BOOK"/"TEXt" at offset 60.
        if (head.size >= 64 && (String(head, 60, 4, Charsets.LATIN1) == "BOOK" ||
                String(head, 60, 4, Charsets.LATIN1) == "TEXt")) {
            return FormatProbe(DocFormat.MOBI, "MOBI", true)
        }
        val fallback = byExtension ?: DocFormat.TXT
        return FormatProbe(fallback, fallback.label, true)
    }

    private fun isZip(file: File): Boolean = try {
        file.inputStream().use { s ->
            val b = s.read()
            val c = s.read()
            b == 'P'.code && c == 'K'.code
        }
    } catch (e: Exception) {
        false
    }

    /** Parses [file] into a book. Runs off the main thread. */
    suspend fun parse(file: File): ParsedDoc = withContext(Dispatchers.IO) {
        val probe = probe(file)
        val fallback = file.nameWithoutExtension
        when (probe.format) {
            DocFormat.EPUB -> EpubParser.parse(file, fallback)
            DocFormat.MOBI -> MobiParser.parse(file, fallback)
            DocFormat.FB2 -> Fb2Parser.parse(file, fallback)
            DocFormat.DOCX -> OoxmlParser(file).parse(fallback)
            DocFormat.DOC -> DocParser.parse(file, fallback)
            DocFormat.PDF -> parsePdf(file, fallback)
            DocFormat.RTF -> TextParsers.parseRtf(file, fallback)
            DocFormat.MD -> TextParsers.parseMarkdown(file, fallback)
            DocFormat.HTML -> TextParsers.parseHtml(file, fallback)
            DocFormat.TXT, DocFormat.UNKNOWN -> TextParsers.parsePlain(file, fallback)
        }
    }

    /**
     * PDFs are page based: we render through PdfRenderer, but we also pull the
     * text layer so read-aloud and search work on text-based PDFs. Scanned PDFs
     * legitimately produce no text; the reader then disables TTS for them.
     */
    private suspend fun parsePdf(file: File, fallback: String): ParsedDoc {
        val renderer = PdfPageRenderer(file)
        try {
            val pages = renderer.pageCount
            if (pages <= 0) throw ParseException("PDF 没有可显示的页面")

            val extractor = com.oceanreader.parse.pdf.PdfTextExtractor(file)
            val info = extractor.readInfo()
            val pageTexts = try {
                (0 until pages).map { extractor.extractPage(it) }
            } catch (e: Throwable) {
                List(pages) { "" }
            }
            val body = pageTexts.joinToString("\n\n") { it.trim() }.trim()

            val chapters = buildPdfChapters(pageTexts)

            return ParsedDoc(
                title = info.first.ifBlank { fallback },
                author = info.second,
                description = info.third,
                body = body,
                chapters = chapters,
                readerKind = ReaderKind.PAGED,
                pageTexts = pageTexts
            )
        } finally {
            renderer.close()
        }
    }

    /** One chapter per few pages keeps the TOC usable and TTS chunks sane. */
    private fun buildPdfChapters(pageTexts: List<String>): List<ChapterRef> {
        val pagesPerChapter = 4
        val chapters = ArrayList<ChapterRef>()
        var offset = 0L
        for (i in pageTexts.indices step pagesPerChapter) {
            val end = minOf(i + pagesPerChapter, pageTexts.size)
            val slice = pageTexts.subList(i, end).joinToString("\n\n")
            chapters.add(
                ChapterRef(
                    index = chapters.size,
                    title = "第 ${i + 1}–$end 页",
                    startOffset = offset,
                    length = slice.length.toLong(),
                    page = i
                )
            )
            offset += slice.length + 2
        }
        return chapters.ifEmpty {
            listOf(ChapterRef(0, "全文", 0L, 0L, 0))
        }
    }

    /** Writes cover bytes to disk so the shelf can show it without re-parsing. */
    fun saveCover(bytes: ByteArray?, target: File): String? {
        if (bytes == null || bytes.size < 128) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outWidth > 8000) return null
        return try {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            target.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun decodeThumbnail(path: String, maxSize: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(path, maxSize) }
        BitmapFactory.decodeFile(path, opts)
    } catch (e: Throwable) {
        null
    }

    private fun sampleSizeFor(path: String, maxSize: Int): Int {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        var sample = 1
        var w = opts.outWidth
        var h = opts.outHeight
        while (maxOf(w, h) / sample > maxSize) sample *= 2
        return sample
    }
}