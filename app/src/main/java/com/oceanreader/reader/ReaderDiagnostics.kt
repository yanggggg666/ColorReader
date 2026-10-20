package com.oceanreader.reader

import android.util.Log

/**
 * Field diagnostics for the reading surface.
 *
 * The same report goes two places: logcat (tag `OceanReader`, best effort —
 * Android blocks cross-app log reading on most devices) and an in-memory
 * buffer that the reading UI shows under 排版 → 诊断信息 with a copy button,
 * so a support session can be answered with data instead of guesses.
 */
object ReaderDiagnostics {

    private const val TAG = "OceanReader"

    /** Latest report of the current chapter; empty until a book is opened. */
    @Volatile
    var lastReport: String = ""
        private set

    private val buffer = StringBuilder()

    /** Drops the previous chapter's report before the next one is collected. */
    fun beginChapter() {
        buffer.setLength(0)
    }

    /** Snapshot of everything that decides the vertical rhythm. */
    data class StyleReport(
        val fontSizeSp: Int,
        val fontSizePx: Float,
        val lineSpacingMultiplier: Float,
        val paragraphSpacingPx: Float,
        val paragraphSpacingEm: Int,
        val indentPx: Float,
        val marginsPx: Pair<Float, Float>,
        val fontFamily: String,
        val bold: Boolean,
        val justify: Boolean,
        val palette: String
    )

    fun style(report: StyleReport) {
        val line =
            "STYLE fontSize=${report.fontSizeSp}sp(${report.fontSizePx}px) " +
                "lineMult=${report.lineSpacingMultiplier} " +
                "paraSpacing=${report.paragraphSpacingEm / 100f}em(${report.paragraphSpacingPx}px) " +
                "indent=${report.indentPx}px margins=${report.marginsPx.first}x${report.marginsPx.second} " +
                "font=${report.fontFamily} bold=${report.bold} justify=${report.justify} " +
                "palette=${report.palette}"
        append(line)
    }

    /** Per-chapter shape: how the typesetter saw the text. */
    fun chapter(
        bookId: String,
        chapterIndex: Int,
        chapterTitle: String,
        textLength: Int,
        blockCounts: Map<BlockKind, Int>,
        lineCount: Int
    ) {
        val blocks = BlockKind.entries.joinToString(",") { "${it.name}=${blockCounts[it] ?: 0}" }
        append(
            "CHAPTER book=$bookId idx=$chapterIndex title='$chapterTitle' " +
                "chars=$textLength lines=$lineCount blocks=[$blocks]"
        )
    }

    /**
     * Pagination result: page boundaries, lines per page and the first line
     * height — the numbers that explain "a few lines per page".
     */
    fun pagination(
        bookId: String,
        chapterIndex: Int,
        pageCount: Int,
        contentHeightPx: Int,
        layoutHeightPx: Int,
        linesPerPage: List<Int>,
        lineHeightsOfPage0: Pair<Int, Int>?
    ) {
        append(
            "PAGINATE book=$bookId idx=$chapterIndex pages=$pageCount " +
                "contentH=${contentHeightPx}px layoutH=${layoutHeightPx}px " +
                "linesPerPage=${linesPerPage.take(12)}" +
                (lineHeightsOfPage0?.let { " page0LineHeight=${it.first}..${it.second}px" } ?: "")
        )
    }

    /**
     * Raw line heights of the first lines of the layout — after removing the
     * custom spacing span these must all equal the natural pitch
     * (fontSize × lineSpacing multiplier), which proves no span is inflating
     * the vertical rhythm.
     */
    fun lineHeights(heights: List<Int>) {
        append("LINEHEIGHTS=${heights}")
    }

    /** Anything that goes wrong while reading, tagged for easy grep. */
    fun error(where: String, detail: String) {
        Log.e(TAG, "ERROR at $where: $detail")
        append("ERROR at $where: $detail")
    }

    private fun append(line: String) {
        Log.i(TAG, line)
        buffer.append(line).append('\n')
        lastReport = buffer.toString()
    }
}
