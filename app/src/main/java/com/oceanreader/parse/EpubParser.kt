package com.oceanreader.parse

import com.oceanreader.data.model.ChapterRef
import com.oceanreader.data.model.DocFormat
import com.oceanreader.data.model.ReaderKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * EPUB 2/3 reader: reads the OPF package document, follows the spine and
 * flattens every content document into one continuous body so the reader can
 * paginate the whole book as a single flow.
 */
object EpubParser {

    data class TocNode(val title: String, val href: String, val level: Int)

    private class Entry(val href: String, val mediaType: String, val properties: String)

    private class Metadata(
        var title: String = "",
        var author: String = "",
        var description: String = "",
        var language: String = ""
    )

    fun parse(file: File, fallbackTitle: String): ParsedDoc {
        ZipFile(file).use { zip ->
            val opfPath = findPackage(zip) ?: throw ParseException("EPUB 缺少 OPF 目录")
            val opf = zip.getInputStream(zip.getEntry(opfPath)!!)
                .use { String(it.readBytes(), Charsets.UTF_8) }
            val baseDir = opfPath.substringBeforeLast('/', "")
            val meta = readMetadata(opf, fallbackTitle)
            val manifest = readManifest(opf)
            val spine = readSpine(opf)

            val body = StringBuilder()
            val chapters = ArrayList<ChapterRef>()

            for ((i, id) in spine.withIndex()) {
                val entry = manifest[id] ?: continue
                if (!isReadable(entry.mediaType)) continue
                val zipEntry = zip.getEntry(resolve(baseDir, entry.href)) ?: continue
                val html = zip.getInputStream(zipEntry).use { String(it.readBytes(), Charsets.UTF_8) }

                val blocks = HtmlText.blocks(html)
                val chunk = blocks.joinToString("\n\n") { it.text }.trim()
                if (chunk.isBlank()) continue

                val title = cleanTitle(
                    blocks.firstOrNull { it.headingLevel in 1..3 && it.text.length <= 60 }?.text
                        ?: HtmlText.title(html)
                        ?: meta.title,
                    i
                )
                val start = body.length.toLong()
                body.append(chunk).append("\n\n")
                chapters.add(ChapterRef(chapters.size, title, start, chunk.length.toLong()))
            }

            if (chapters.isEmpty()) throw ParseException("EPUB 没有可读内容")

            // One spine item per chapter can mean a few lines per chapter;
            // merge those up so pages stay full.
            val merged = ChapterSplitter.mergeUp(body.toString(), chapters)

            return ParsedDoc(
                title = meta.title.ifBlank { fallbackTitle },
                author = meta.author,
                description = meta.description,
                body = body.toString(),
                chapters = merged,
                coverBytes = extractCover(zip, manifest, baseDir, opf),
                language = meta.language,
                readerKind = ReaderKind.FLOW
            )
        }
    }

    /** Reads the EPUB navigation document (nav.xhtml or toc.ncx). */
    fun readOutline(file: File): List<TocNode> = ZipFile(file).use { zip ->
        val opfPath = findPackage(zip) ?: return@use emptyList<TocNode>()
        val opf = zip.getInputStream(zip.getEntry(opfPath)!!)
            .use { String(it.readBytes(), Charsets.UTF_8) }
        val baseDir = opfPath.substringBeforeLast('/', "")
        val manifest = readManifest(opf)

        val navHref = Regex("""<item[^>]*properties=["'][^"']*nav[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
            .find(opf)?.value?.let { attr(it, "href") }
        val navPath = navHref?.let { resolve(baseDir, it) }
            ?: zip.getEntry(resolve(baseDir, "toc.ncx"))?.name
            ?: zip.entries().asSequence()
                .map { it.name }
                .firstOrNull { it.endsWith("toc.ncx", true) || it.endsWith("nav.xhtml", true) }
            ?: return@use emptyList()

        val entry = zip.getEntry(navPath) ?: return@use emptyList<TocNode>()
        val xml = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
        if (navPath.endsWith(".ncx", true)) parseNcx(xml) else parseNav(xml)
    }

    private fun parseNcx(xml: String): List<TocNode> {
        val out = ArrayList<TocNode>()
        val re = Regex("""<navPoint[\s\S]*?</navPoint>""", RegexOption.IGNORE_CASE)
        for (m in re.findAll(xml)) {
            val block = m.value
            val label = Regex("""<text[^>]*>([\s\S]*?)</text>""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1)
                ?.let { HtmlText.tidyInline(HtmlText.toPlainText(it)) }.orEmpty()
            val src = Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1).orEmpty()
            if (label.isNotBlank()) out.add(TocNode(label, src, 0))
        }
        return out
    }

    private fun parseNav(xml: String): List<TocNode> {
        val out = ArrayList<TocNode>()
        val re = Regex("""<a\s[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        for (m in re.findAll(xml)) {
            val title = HtmlText.tidyInline(HtmlText.toPlainText(m.groupValues[2]))
            if (title.isBlank()) continue
            out.add(TocNode(title, m.groupValues[1], 0))
        }
        return out
    }

    // ------------------------------------------------------------- helpers

    private fun isReadable(mediaType: String): Boolean =
        mediaType.contains("xhtml") || mediaType.contains("html") || mediaType.contains("xml")

    private fun cleanTitle(title: String, index: Int): String {
        val t = HtmlText.tidyInline(HtmlText.toPlainText(title))
        return t.ifBlank { "第 ${index + 1} 章" }.take(80)
    }

    private fun findPackage(zip: ZipFile): String? {
        zip.getEntry("META-INF/container.xml")?.let { entry ->
            val xml = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
            val path = Regex("""full-path=["']([^"']+)["']""").find(xml)?.groupValues?.get(1)
            if (path != null) return path
        }
        return zip.entries().asSequence()
            .map { it.name }
            .firstOrNull { it.endsWith(".opf", ignoreCase = true) }
    }

    private fun readManifest(opf: String): MutableMap<String, Entry> {
        val map = HashMap<String, Entry>()
        val re = Regex("""<item\b[^>]*/?>""", RegexOption.IGNORE_CASE)
        for (m in re.findAll(opf)) {
            val tag = m.value
            val id = attr(tag, "id") ?: continue
            val href = attr(tag, "href") ?: continue
            map[id] = Entry(
                href,
                attr(tag, "media-type") ?: "",
                attr(tag, "properties") ?: ""
            )
        }
        return map
    }

    private fun readSpine(opf: String): List<String> {
        val block = Regex("""<spine[\s\S]*?</spine>""", RegexOption.IGNORE_CASE).find(opf)?.value
            ?: Regex("""<spine[^>]*/?>""", RegexOption.IGNORE_CASE).find(opf)?.value
            ?: return emptyList()
        val re = Regex("""<itemref\b[^>]*/?>""", RegexOption.IGNORE_CASE)
        return re.findAll(block).mapNotNull { attr(it.value, "idref") }.toList()
    }

    private fun readMetadata(opf: String, fallback: String): Metadata {
        val m = Metadata()
        m.title = dcTag(opf, "title") ?: fallback
        m.author = dcTag(opf, "creator") ?: ""
        m.description = dcTag(opf, "description") ?: ""
        m.language = Regex("""<dc:language\b[^>]*>([\s\S]*?)</dc:language>""", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.get(1)?.trim().orEmpty()
        return m
    }

    private fun dcTag(opf: String, tag: String): String? =
        Regex("""<dc:$tag\b[^>]*>([\s\S]*?)</dc:$tag>""", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.get(1)
            ?.let { HtmlText.tidyInline(HtmlText.toPlainText(it)) }
            ?.ifBlank { null }

    private fun attr(tag: String, name: String): String? =
        Regex("""\s$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)

    private fun resolve(baseDir: String, href: String): String {
        val clean = href.substringBefore('#')
        if (baseDir.isEmpty()) return clean
        val parts = ArrayList<String>()
        for (seg in baseDir.split('/')) if (seg.isNotEmpty() && seg != ".") parts.add(seg)
        for (seg in clean.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun extractCover(
        zip: ZipFile,
        manifest: Map<String, Entry>,
        baseDir: String,
        opf: String
    ): ByteArray? {
        val candidates = ArrayList<String>()
        Regex("""<meta[^>]+name=["']cover["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.get(1)?.let { candidates.add(it) }
        manifest.values.filter { it.properties.contains("cover-image") }.forEach { candidates.add(it.href) }
        manifest.values.filter { it.mediaType.startsWith("image/") && it.href.contains("cover", true) }
            .forEach { candidates.add(it.href) }

        for (href in candidates) {
            val entry = zip.getEntry(resolve(baseDir, href)) ?: continue
            val bytes = zip.getInputStream(entry).use { readAll(it) }
            if (bytes.size > 256) return bytes
        }
        return zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.contains("cover", true) }
            .mapNotNull { entry -> zip.getInputStream(entry).use { readAll(it) }.takeIf { it.size > 512 } }
            .firstOrNull()
    }

    private fun readAll(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            if (out.size() > 8 * 1024 * 1024) break
        }
        return out.toByteArray()
    }

    fun formatOf(file: File): DocFormat = DocFormat.EPUB
}

/** Raised when a document cannot be parsed; [message] is user facing. */
class ParseException(message: String) : Exception(message)