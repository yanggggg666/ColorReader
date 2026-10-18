package com.oceanreader.parse.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Rasterises PDF pages with the platform [PdfRenderer].
 *
 * Pages are cached because re-rendering a page costs tens of milliseconds; the
 * cache is an LRU sized from the device's available heap.
 */
class PdfPageRenderer(file: File) {

    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    val pageCount: Int

    init {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        descriptor = fd
        val r = PdfRenderer(fd)
        renderer = r
        pageCount = r.pageCount
    }

    suspend fun render(page: Int, width: Int, height: Int): Bitmap? = withContext(Dispatchers.IO) {
        val r = renderer ?: return@withContext null
        if (page !in 0 until r.pageCount) return@withContext null
        try {
            r.openPage(page).use { pdfPage ->
                val w = width.coerceAtLeast(1)
                val h = height.coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                pdfPage.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                )
                bitmap
            }
        } catch (e: Throwable) {
            null
        }
    }

    /** Page size in points (1/72 inch), used to preserve aspect ratio. */
    fun pageAspect(page: Int): Float {
        val r = renderer ?: return 0.707f
        return try {
            r.openPage(page.coerceIn(0, (r.pageCount - 1).coerceAtLeast(0))).use {
                if (it.height == 0) 0.707f else it.width.toFloat() / it.height.toFloat()
            }
        } catch (e: Throwable) {
            0.707f
        }
    }

    fun close() {
        try { renderer?.close() } catch (_: Throwable) {}
        try { descriptor?.close() } catch (_: Throwable) {}
        renderer = null
        descriptor = null
    }
}

/** Small LRU cache for decoded page bitmaps. */
class BitmapCache(private val maxBytes: Int) {

    private val entries = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            var total = 0
            entries.values.forEach { total += it.byteCount }
            return total > maxBytes
        }
    }

    @Synchronized
    fun get(key: String): Bitmap? = entries[key]

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        entries[key] = bitmap
    }

    @Synchronized
    fun clear() {
        entries.values.forEach { if (!it.isRecycled) it.recycle() }
        entries.clear()
    }

    @Synchronized
    fun trim() {
        while (entries.size > 3) {
            val it = entries.entries.iterator()
            it.next()
            val bmp = it.value
            it.remove()
            if (!bmp.isRecycled) bmp.recycle()
        }
    }
}

/** Applies the reader's tint/invert mode to a freshly rendered page. */
object PdfTone {

    fun darken(bitmap: Bitmap, factor: Float): Bitmap {
        val cm = ColorMatrix().apply { setScale(factor, factor, factor, 1f) }
        return remap(bitmap, cm)
    }

    fun invert(bitmap: Bitmap): Bitmap {
        val cm = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        ))
        return remap(bitmap, cm)
    }

    private fun remap(src: Bitmap, cm: ColorMatrix): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val filter = ColorMatrixColorFilter(cm)
        val canvas = Canvas(out)
        canvas.drawBitmap(out, 0f, 0f, android.graphics.Paint().apply {
            colorFilter = filter
            isAntiAlias = false
            isFilterBitmap = false
        })
        if (out !== src) src.recycle()
        return out
    }
}