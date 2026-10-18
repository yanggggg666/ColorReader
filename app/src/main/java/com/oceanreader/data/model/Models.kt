package com.oceanreader.data.model

/** Supported document kinds. */
enum class DocFormat(val label: String, val extensions: Set<String>) {
    TXT("TXT", setOf("txt", "text", "log")),
    MD("Markdown", setOf("md", "markdown", "mdown")),
    EPUB("EPUB", setOf("epub")),
    PDF("PDF", setOf("pdf")),
    MOBI("MOBI", setOf("mobi", "azw", "azw3", "prc", "mobi8")),
    DOC("Word", setOf("doc")),
    DOCX("Word", setOf("docx")),
    RTF("RTF", setOf("rtf")),
    FB2("FictionBook", setOf("fb2", "fbz")),
    HTML("网页", setOf("html", "htm", "xhtml")),
    UNKNOWN("未知", emptySet());

    companion object {
        fun fromExtension(name: String): DocFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}

/** How a document is laid out and read. */
enum class ReaderKind {
    /** Paginated flowing text with chapters. */
    FLOW,

    /** Fixed pages rendered from a PDF. */
    PAGED
}

/** One navigable unit of a document. */
data class ChapterRef(
    val index: Int,
    val title: String,
    /** Character offset of the chapter inside the document body. */
    val startOffset: Long = 0L,
    val length: Long = 0L,
    /** Page index for PDF-like documents. */
    val page: Int = -1
)

/** Metadata parsed out of a document before it is added to the shelf. */
data class BookMeta(
    val title: String,
    val author: String = "",
    val format: DocFormat = DocFormat.UNKNOWN,
    val description: String = "",
    val language: String = "",
    val coverPath: String? = null,
    val totalChars: Long = 0L,
    val chapterCount: Int = 0,
    val readerKind: ReaderKind = ReaderKind.FLOW,
    val totalPages: Int = 0
)

/** Reading style knobs, persisted per app (and overridable per book). */
data class Typeface(
    val fontFamily: Int = 0,
    val fontSize: Int = 19,
    val lineSpacingPercent: Int = 170,
    val paragraphSpacing: Int = 10,
    val pageMarginHorizontal: Int = 22,
    val pageMarginVertical: Int = 20,
    val firstLineIndent: Int = 2,
    val boldWeight: Boolean = false,
    val letterSpacing: Float = 0f
)

enum class PageTurnMode(val label: String) {
    SLIDE("覆盖滑动"),
    CURL("仿真翻页"),
    NONE("无动画")
}

enum class Brightness(val label: String, val value: Float) {
    AUTO("跟随系统", -1f),
    NIGHT("夜间", 0.08f),
    DIM("微光", 0.3f),
    BRIGHT("明亮", 1f)
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色")
}

enum class DarkMode { SYSTEM, LIGHT, DARK }

enum class ShelfSort(val label: String) {
    RECENT("最近阅读"),
    ADDED("加入时间"),
    TITLE("书名"),
    AUTHOR("作者"),
    PROGRESS("阅读进度")
}

enum class FontFamilyOption(val key: String, val label: String) {
    SANS("sans", "无衬线"),
    SERIF("serif", "衬线"),
    ROUNDED("rounded", "圆体"),
    MONO("mono", "等宽");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: SERIF
    }
}

/** A parsed sentence plus its offsets — the unit the TTS engine speaks. */
data class SpeechSegment(
    val index: Int,
    val chapterIndex: Int,
    val startInChapter: Int,
    val text: String,
    val end: Int = startInChapter + text.length,
    val isHeading: Boolean = false
)