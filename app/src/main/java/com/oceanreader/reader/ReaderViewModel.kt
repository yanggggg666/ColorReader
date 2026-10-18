package com.oceanreader.reader

import android.app.Application
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oceanreader.AppContainer
import com.oceanreader.data.db.BookEntity
import com.oceanreader.data.db.BookmarkEntity
import com.oceanreader.data.db.HighlightEntity
import com.oceanreader.data.model.ChapterRef
import com.oceanreader.data.model.DocFormat
import com.oceanreader.data.model.Typeface
import com.oceanreader.parse.DocumentSource
import com.oceanreader.tts.SpeechController
import com.oceanreader.tts.SpeechSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which panel is open on top of the reading surface. */
enum class ReaderPanel { NONE, TOC, APPEARANCE, SPEECH, SEARCH, BOOKMARK, NOTES }

/** Everything the reading screen renders from. */
data class ReaderUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val book: BookEntity? = null,
    val chapters: List<ChapterRef> = emptyList(),
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val chapterText: String = "",
    val isPaged: Boolean = false,
    val pageCount: Int = 0,
    val pageIndex: Int = 0,
    val percent: Float = 0f,
    val chromeVisible: Boolean = true,
    val panel: ReaderPanel = ReaderPanel.NONE,
    val selection: String = "",
    val selectionStart: Int = -1,
    val selectionEnd: Int = -1,
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val highlights: List<HighlightEntity> = emptyList(),
    val searchQuery: String = "",
    val searchHits: List<Triple<Int, Int, Int>> = emptyList(),
    val searchIndex: Int = 0,
    val fontOverride: Typeface? = null
)

/**
 * Owns reading state for one book.
 *
 * Responsibilities: open the document, paginate, move between chapters and
 * pages, keep highlights/bookmarks in sync, and drive the TTS controller so the
 * spoken position and the reading position never diverge.
 */
class ReaderViewModel(
    app: Application,
    private val bookId: String
) : AndroidViewModel(app) {

    private val repo = AppContainer.from(app).repository
    private val settings = AppContainer.from(app).settings

    private val _ui = MutableStateFlow(ReaderUiState())
    val ui: StateFlow<ReaderUiState> = _ui.asStateFlow()

    val speech: SpeechController = AppContainer.from(app).speech

    private var source: DocumentSource? = null
    private var bookEntity: BookEntity? = null
    private var segments: List<com.oceanreader.data.model.SpeechSegment> = emptyList()
    private var chapterTextCache = HashMap<Int, String>()
    private var statJob: Job? = null
    private var lastSavedChapter = -1
    private var lastSavedPage = -1
    private var lastSavedOffset = -1L

    val prefs: StateFlow<com.oceanreader.data.Settings> = settings.prefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.oceanreader.data.Settings())

    init {
        viewModelScope.launch { open() }
        observeAnnotations()
        speech.cursor.collect { /* cursor is read directly by the renderer */ }
    }

    private fun observeAnnotations() {
        viewModelScope.launch {
            combine(repo.bookmarks(bookId), repo.highlights(bookId)) { b, h -> b to h }
                .collect { (b, h) -> _ui.value = _ui.value.copy(bookmarks = b, highlights = h) }
        }
    }

    // ------------------------------------------------------------------ open

    private suspend fun open() {
        _ui.value = ReaderUiState(loading = true)
        val book = repo.bookOnce(bookId)
        if (book == null) {
            _ui.value = ReaderUiState(loading = false, error = "找不到这本书")
            return
        }
        bookEntity = book

        val result = runCatching { repo.parse(book) }
        val doc = result.getOrElse { e ->
            _ui.value = ReaderUiState(
                loading = false,
                error = e.message ?: "无法打开该文档"
            )
            return
        }

        val kind = doc.readerKind
        val built = withContext(Dispatchers.IO) {
            when (kind) {
                com.oceanreader.data.model.ReaderKind.PAGED -> {
                    val meta = com.oceanreader.data.model.BookMeta(
                        title = doc.title,
                        author = doc.author,
                        format = DocFormat.PDF,
                        description = doc.description,
                        totalChars = doc.body.length.toLong(),
                        chapterCount = doc.chapters.size,
                        readerKind = kind,
                        totalPages = doc.pageTexts.size
                    )
                    com.oceanreader.parse.PdfDocument(
                        File_(book.filePath), meta, doc.chapters, doc.pageTexts
                    ) as DocumentSource
                }
                else -> {
                    val meta = com.oceanreader.data.model.BookMeta(
                        title = doc.title,
                        author = doc.author,
                        format = repo.formatOf(book),
                        description = doc.description,
                        language = doc.language,
                        totalChars = doc.body.length.toLong(),
                        chapterCount = doc.chapters.size,
                        readerKind = kind
                    )
                    com.oceanreader.parse.FlowDocument(meta, doc.body, doc.chapters) as DocumentSource
                }
            }
        }
        source = built

        // Pre-compute the sentence list used by TTS (lazily, off the main thread).
        viewModelScope.launch(Dispatchers.Default) {
            val texts = doc.chapters.map { i -> withContext(Dispatchers.IO) { docText(doc, i) } }
            segments = SentenceSegmenter.segmentAll(texts)
            speech.load(bookId, segments)
        }

        val startChapter = book.lastChapter.coerceIn(0, doc.chapters.lastIndex.coerceAtLeast(0))
        val text = withContext(Dispatchers.IO) { docText(doc, startChapter) }
        chapterTextCache[startChapter] = text

        _ui.value = _ui.value.copy(
            loading = false,
            book = book,
            chapters = doc.chapters,
            chapterIndex = startChapter,
            chapterTitle = doc.chapters.getOrNull(startChapter)?.title.orEmpty(),
            chapterText = text,
            isPaged = kind == com.oceanreader.data.model.ReaderKind.PAGED,
            pageCount = if (kind == com.oceanreader.data.model.ReaderKind.PAGED) doc.pageTexts.size else 0,
            pageIndex = book.lastPage,
            percent = book.percent
        )
        startStatTicker()
    }

    private fun docText(doc: com.oceanreader.parse.ParsedDoc, index: Int): String {
        chapterTextCache[index]?.let { return it }
        val ref = doc.chapters.getOrNull(index) ?: return ""
        val start = ref.startOffset.toInt().coerceIn(0, doc.body.length)
        val end = (ref.startOffset + ref.length).toInt().coerceIn(start, doc.body.length)
        val text = doc.body.substring(start, end)
        chapterTextCache[index] = text
        return text
    }

    private fun File_(path: String) = java.io.File(path)

    // ------------------------------------------------------------ navigation

    fun nextPage() {
        val s = _ui.value
        if (s.isPaged) {
            if (s.pageIndex + 1 < s.pageCount) {
                _ui.value = s.copy(pageIndex = s.pageIndex + 1)
                onPageChanged(s.pageIndex + 1)
            } else {
                nextChapter()
            }
        } else {
            nextChapter()
        }
    }

    fun previousPage() {
        val s = _ui.value
        if (s.isPaged && s.pageIndex > 0) {
            _ui.value = s.copy(pageIndex = s.pageIndex - 1)
            onPageChanged(s.pageIndex - 1)
        } else {
            previousChapter()
        }
    }

    fun nextChapter() {
        val s = _ui.value
        val next = s.chapterIndex + 1
        if (next > s.chapters.lastIndex) return
        goToChapter(next, 0)
    }

    fun previousChapter() {
        val s = _ui.value
        if (s.chapterIndex <= 0) return
        goToChapter(s.chapterIndex - 1, if (s.isPaged) (s.pageCount - 1).coerceAtLeast(0) else 0)
    }

    fun goToChapter(index: Int, page: Int = 0, offset: Int = 0) {
        val s = _ui.value
        val clamped = index.coerceIn(0, s.chapters.lastIndex.coerceAtLeast(0))
        val text = chapterTextCache[clamped] ?: source?.let {
            val t = it.textOf(clamped)
            chapterTextCache[clamped] = t
            t
        } ?: s.chapterText

        _ui.value = s.copy(
            chapterIndex = clamped,
            chapterTitle = s.chapters.getOrNull(clamped)?.title.orEmpty(),
            chapterText = text,
            pageIndex = if (s.isPaged) page.coerceAtMost(s.pageCount - 1) else 0,
            panel = ReaderPanel.NONE,
            selection = "",
            selectionStart = -1,
            selectionEnd = -1
        )
        persist(percentFor(clamped, page, offset))
    }

    fun goToPage(page: Int) {
        val s = _ui.value
        val clamped = page.coerceIn(0, (s.pageCount - 1).coerceAtLeast(0))
        _ui.value = s.copy(pageIndex = clamped)
        onPageChanged(clamped)
    }

    /** Called by the text renderer whenever the visible page changes. */
    fun onPageRendered(page: Int, offsetInChapter: Int) {
        val s = _ui.value
        if (s.isPaged) return
        lastSavedOffset = -1L
        persist(percentFor(s.chapterIndex, 0, offsetInChapter))
    }

    private fun onPageChanged(page: Int) {
        persist(percentFor(_ui.value.chapterIndex, page, 0))
    }

    private fun percentFor(chapter: Int, page: Int, offset: Int): Float {
        val s = _ui.value
        val book = s.book ?: return 0f
        if (s.isPaged) {
            if (s.pageCount <= 0) return 0f
            return (page.toFloat() / s.pageCount).coerceIn(0f, 1f)
        }
        val total = book.totalChars.coerceAtLeast(1L)
        val consumed = s.chapters.take(chapter).sumOf { it.length }
        val current = s.chapters.getOrNull(chapter)?.length ?: 0L
        val position = consumed + offset.coerceAtMost(current.toInt())
        return (position.toFloat() / total).coerceIn(0f, 1f)
    }

    private fun persist(percent: Float) {
        val s = _ui.value
        val book = s.book ?: return
        if (s.chapterIndex == lastSavedChapter && s.pageIndex == lastSavedPage &&
            Math.abs(s.pageIndex) == Math.abs(lastSavedPage) && Math.abs(percent * 100 - (lastSavedOffset ?: 0L)) < 0.5
        ) return

        _ui.value = s.copy(percent = percent)
        lastSavedChapter = s.chapterIndex
        lastSavedPage = s.pageIndex
        lastSavedOffset = (percent * 100).toLong()

        viewModelScope.launch {
            repo.saveProgress(
                book = book,
                chapter = s.chapterIndex,
                offset = 0,
                page = s.pageIndex,
                percent = percent
            )
        }
    }

    // ---------------------------------------------------------- annotations

    fun onSelectionChanged(text: String, start: Int, end: Int) {
        _ui.value = _ui.value.copy(selection = text, selectionStart = start, selectionEnd = end)
    }

    fun clearSelection() {
        _ui.value = _ui.value.copy(selection = "", selectionStart = -1, selectionEnd = -1)
    }

    fun highlightSelection(colorIndex: Int = 0, underline: Boolean = false) {
        val s = _ui.value
        val book = s.book ?: return
        if (s.selectionStart < 0 || s.selectionEnd <= s.selectionStart) return
        viewModelScope.launch {
            repo.addHighlight(
                book = book,
                chapter = s.chapterIndex,
                start = s.selectionStart,
                end = s.selectionEnd,
                quote = s.selection,
                kind = if (underline) 1 else 0,
                colorIndex = colorIndex
            )
            clearSelection()
        }
    }

    fun deleteHighlight(id: String) {
        viewModelScope.launch { repo.deleteHighlight(id) }
    }

    fun updateHighlightNote(id: String, note: String) {
        viewModelScope.launch { repo.updateHighlightNote(id, note) }
    }

    fun toggleBookmark() {
        val s = _ui.value
        val book = s.book ?: return
        viewModelScope.launch {
            repo.addBookmark(
                book = book,
                chapter = s.chapterIndex,
                offset = if (s.isPaged) s.pageIndex else 0,
                title = s.chapterTitle,
                excerpt = if (s.isPaged) "第 ${s.pageIndex + 1} 页" else s.chapterText.take(80),
                percent = s.percent
            )
        }
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch { repo.deleteBookmark(id) }
    }

    fun isBookmarked(): Boolean {
        val s = _ui.value
        return s.bookmarks.any {
            it.chapterIndex == s.chapterIndex &&
                (s.isPaged && it.offset == s.pageIndex)
        }
    }

    // -------------------------------------------------------------- search

    fun search(query: String) {
        val s = _ui.value
        _ui.value = s.copy(searchQuery = query, searchIndex = 0)
        if (query.isBlank()) {
            _ui.value = _ui.value.copy(searchHits = emptyList())
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            val hits = buildList {
                for (c in s.chapters) {
                    val text = chapterTextCache[c.index] ?: source?.textOf(c.index).orEmpty()
                    var from = 0
                    while (true) {
                        val at = text.indexOf(query, from, ignoreCase = true)
                        if (at < 0) break
                        add(Triple(c.index, at, at + query.length))
                        if (size >= 300) return@buildList
                        from = at + query.length
                    }
                }
            }
            _ui.value = _ui.value.copy(searchHits = hits)
        }
    }

    fun jumpToSearchHit(index: Int) {
        val s = _ui.value
        val hit = s.searchHits.getOrNull(index) ?: return
        _ui.value = s.copy(panel = ReaderPanel.NONE, searchIndex = index)
        goToChapter(hit.first, 0, hit.second)
    }

    // --------------------------------------------------------------- panels

    fun setPanel(panel: ReaderPanel) {
        _ui.value = _ui.value.copy(panel = panel)
    }

    fun toggleChrome() {
        _ui.value = _ui.value.copy(chromeVisible = !_ui.value.chromeVisible)
    }

    fun setChrome(visible: Boolean) {
        _ui.value = _ui.value.copy(chromeVisible = visible)
    }

    // ------------------------------------------------------------------ tts

    fun startSpeaking() {
        val s = _ui.value
        val offset = if (s.isPaged) 0 else 0
        speech.playFrom(s.chapterIndex, offset)
        AppContainer.from(getApplication()).startSpeechService()
    }

    fun stopSpeaking() {
        speech.stop()
    }

    fun currentText(): String = _ui.value.chapterText

    fun segmentCount(): Int = segments.size

    // ------------------------------------------------------------- settings

    fun setFont(block: suspend (com.oceanreader.data.SettingsStore) -> Unit) {
        viewModelScope.launch { block(settings) }
    }

    private fun startStatTicker() {
        statJob?.cancel()
        statJob = viewModelScope.launch {
            var elapsed = 0L
            while (true) {
                delay(30_000)
                elapsed += 30_000
                val book = _ui.value.book ?: continue
                if (!_ui.value.chromeVisible && _ui.value.panel == ReaderPanel.NONE) {
                    repo.recordReading(book.id, 30_000, 0)
                }
            }
        }
    }

    override fun onCleared() {
        statJob?.cancel()
        source?.close()
        super.onCleared()
    }
}