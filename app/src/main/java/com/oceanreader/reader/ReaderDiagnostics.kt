package com.oceanreader.reader

import android.util.Log

/**
 * Field diagnostics for the reading surface.
 *
 * Every build logs the paginator's view of the world under the `OceanReader`
 * tag so a support session can be answered with data instead of guesses:
 * what the style resolved to, how the chapter was split into blocks, how many
 * lines each page holds and where the spacing spans landed. Call [dump] after
 * pagination; pull it with `logcat -d | grep OceanReader`.
 */
object ReaderDiagnostics {

    private const val TAG = "OceanReader"

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
        Log.i(
            TAG,
            "STYLE fontSize=${report.fontSizeSp}sp(${report.fontSizePx}px) " +
                "lineMult=${report.lineSpacingMultiplier} " +
                "paraSpacing=${report.paragraphSpacingEm / 100f}em(${report.paragraphSpacingPx}px) " +
                "indent=${report.indentPx}px margins=${report.marginsPx.first}x${report.marginsPx.second} " +
                "font=${report.fontFamily} bold=${report.bold} justify=${report.justify} " +
                "palette=${report.palette}"
        )
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
        Log.i(
            TAG,
            "CHAPTER book=$bookId idx=$chapterIndex title='$chapterTitle' " +
                "chars=$textLength lines=$lineCount blocks=[$blocks]"
        )
    }

    /**
     * Pagination result: page boundaries, lines per page and the first/last
     * line height of a page — the numbers that explain "a few lines per page".
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
        Log.i(
            TAG,
            "PAGINATE book=$bookId idx=$chapterIndex pages=$pageCount " +
                "contentH=${contentHeightPx}px layoutH=${layoutHeightPx}px " +
                "linesPerPage=${linesPerPage.take(12)}" +
                (lineHeightsOfPage0?.let { " page0LineHeight=${it.first}..${it.second}px" } ?: "")
        )
    }

    /** Anything that goes wrong while reading, tagged for easy grep. */
    fun error(where: String, detail: String) {
        Log.e(TAG, "ERROR at $where: $detail")
    }
}
