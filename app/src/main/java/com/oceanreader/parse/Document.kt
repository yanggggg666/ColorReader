package com.oceanreader.parse

import com.oceanreader.data.model.BookMeta
import com.oceanreader.data.model.ChapterRef
import com.oceanreader.data.model.ReaderKind

/** Result of parsing a document, before it is turned into a page source. */
data class ParsedDoc(
    val title: String,
    val author: String = "",
    val description: String = "",
    val body: String = "",
    val chapters: List<ChapterRef> = emptyList(),
    val coverBytes: ByteArray? = null,
    val language: String = "",
    val seriesTitle: String = "",
    val readerKind: ReaderKind = ReaderKind.FLOW,
    val pageTexts: List<String> = emptyList()
)

/**
 * Uniform read model over every supported format. The reader UI only ever
 * talks to this, so adding a format never touches the UI.
 */
abstract class DocumentSource {

    abstract val meta: BookMeta
    abstract val chapters: List<ChapterRef>
    abstract val kind: ReaderKind

    /** Total readable characters, used for progress percentages. */
    open val totalChars: Long get() = meta.totalChars

    abstract fun textOf(chapter: Int): String

    fun chapterCount(): Int = chapters.size

    fun titleOf(chapter: Int): String =
        chapters.getOrNull(chapter)?.title ?: "全文"

    /** Global offset of a chapter inside the whole document. */
    fun offsetOf(chapter: Int): Long =
        chapters.getOrNull(chapter)?.startOffset ?: 0L

    /** Maps a global offset back to (chapter, offset inside chapter). */
    fun locate(globalOffset: Long): Pair<Int, Int> {
        if (chapters.isEmpty()) return 0 to globalOffset.toInt()
        for (c in chapters) {
            if (globalOffset < c.startOffset + c.length) {
                val inside = (globalOffset - c.startOffset).coerceIn(0L, c.length)
                return c.index to inside.toInt()
            }
        }
        val last = chapters.last()
        return last.index to last.length.toInt()
    }

    open fun close() {}

    /** Searching is linear but chapter-bounded, which is fast enough. */
    fun search(query: String, limit: Int = 300): List<Triple<Int, Int, Int>> {
        if (query.isBlank()) return emptyList()
        val out = ArrayList<Triple<Int, Int, Int>>()
        val needle = query.trim()
        for (c in chapters) {
            val text = textOf(c.index)
            var from = 0
            while (true) {
                val hit = text.indexOf(needle, from, ignoreCase = true)
                if (hit < 0) break
                out.add(Triple(c.index, hit, hit + needle.length))
                if (out.size >= limit) return out
                from = hit + needle.length
            }
        }
        return out
    }
}

/** In-memory implementation backed by a single decoded body string. */
class FlowDocument(
    override val meta: BookMeta,
    val body: String,
    override val chapters: List<ChapterRef>
) : DocumentSource() {

    override val kind: ReaderKind = ReaderKind.FLOW
    override val totalChars: Long = body.length.toLong()

    override fun textOf(chapter: Int): String {
        val ref = chapters.getOrNull(chapter) ?: return ""
        val start = ref.startOffset.toInt().coerceIn(0, body.length)
        val end = (ref.startOffset + ref.length).toInt().coerceIn(start, body.length)
        return body.substring(start, end)
    }

    fun slice(globalStart: Int, globalEnd: Int): String {
        val s = globalStart.coerceIn(0, body.length)
        val e = globalEnd.coerceIn(s, body.length)
        return body.substring(s, e)
    }
}