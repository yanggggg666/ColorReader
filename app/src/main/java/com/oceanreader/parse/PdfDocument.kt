package com.oceanreader.parse

import android.graphics.Bitmap
import com.oceanreader.data.model.BookMeta
import com.oceanreader.data.model.ChapterRef
import com.oceanreader.data.model.ReaderKind
import com.oceanreader.parse.pdf.BitmapCache
import com.oceanreader.parse.pdf.PdfPageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Page-based source. Rendering always goes through the platform PDF engine;
 * the extracted text layer (when present) feeds TTS, search and chapter
 * structure so a text PDF behaves like an e-book.
 */
class PdfDocument(
    private val file: File,
    override val meta: BookMeta,
    override val chapters: List<ChapterRef>,
    private val pageTexts: List<String>
) : DocumentSource() {

    override val kind: ReaderKind = ReaderKind.PAGED

    private val renderer: PdfPageRenderer by lazy { PdfPageRenderer(file) }
    private val cache = BitmapCache(cacheBytes())

    override val totalChars: Long = pageTexts.sumOf { it.length }.toLong()

    val pageCount: Int get() = renderer.pageCount

    override fun textOf(chapter: Int): String {
        val ref = chapters.getOrNull(chapter) ?: return ""
        val firstPage = ref.page.coerceAtLeast(0)
        val pageStep = chapters.firstOrNull()?.let { 8 } ?: 8
        val endPage = minOf(firstPage + pageStep, pageTexts.size)
        return pageTexts.subList(firstPage.coerceAtMost(pageTexts.size), endPage)
            .joinToString("\n\n")
    }

    fun textOfPage(page: Int): String = pageTexts.getOrNull(page).orEmpty()

    fun pageOf(chapter: Int): Int = chapters.getOrNull(chapter)?.page ?: 0

    suspend fun renderPage(page: Int, widthPx: Int, heightPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "$page@$widthPx"
            cache.get(key)?.let { return@withContext it }
            val bitmap = renderer.render(page, widthPx, heightPx) ?: return@withContext null
            cache.put(key, bitmap)
            bitmap
        }

    override fun close() {
        cache.clear()
        runCatching { renderer.close() }
    }

    private fun cacheBytes(): Int {
        val max = (Runtime.getRuntime().maxMemory() / 12).coerceIn(8L * 1024 * 1024, 96L * 1024 * 1024)
        return max.toInt()
    }
}