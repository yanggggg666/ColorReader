package com.oceanreader.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.oceanreader.data.db.AppDatabase
import com.oceanreader.data.db.BookEntity
import com.oceanreader.data.db.BookmarkEntity
import com.oceanreader.data.db.HighlightEntity
import com.oceanreader.data.db.NoteEntity
import com.oceanreader.data.db.ReadingDayEntity
import com.oceanreader.data.model.DocFormat
import com.oceanreader.data.model.ReaderKind
import com.oceanreader.data.model.Typeface
import com.oceanreader.parse.DocumentFactory
import com.oceanreader.parse.ParsedDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Single gateway to books, annotations and statistics.
 *
 * The UI never touches Room or the filesystem directly; everything funnels
 * through here so imports, progress saving and deletions stay consistent.
 */
class BookRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val importer: BookImporter
) {

    private val bookDao = db.bookDao()
    private val highlightDao = db.highlightDao()
    private val bookmarkDao = db.bookmarkDao()
    private val noteDao = db.noteDao()
    private val statDao = db.statDao()

    // --------------------------------------------------------------- shelf

    fun books(): Flow<List<BookEntity>> = bookDao.observeAll()
    fun continueReading(): Flow<BookEntity?> = bookDao.observeContinueReading()
    fun continueShelf(): Flow<List<BookEntity>> = bookDao.observeContinueShelf()
    fun pinned(): Flow<List<BookEntity>> = bookDao.observePinned()
    fun finished(): Flow<List<BookEntity>> = bookDao.observeFinished()
    fun searchShelf(query: String): Flow<List<BookEntity>> = bookDao.search(query)
    fun book(id: String): Flow<BookEntity?> = bookDao.observe(id)
    suspend fun bookOnce(id: String): BookEntity? = bookDao.find(id)

    // -------------------------------------------------------------- import

    suspend fun import(uri: Uri, onStep: (ImportStep) -> Unit = {}): ImportResult =
        importer.import(uri, onStep)

    suspend fun refresh(book: BookEntity): BookEntity = importer.refresh(book)

    suspend fun delete(book: BookEntity, deleteFile: Boolean = true) =
        importer.delete(book, deleteFile)

    suspend fun setPinned(book: BookEntity, pinned: Boolean) = bookDao.setPinned(book.id, pinned)

    suspend fun rename(book: BookEntity, title: String, author: String) =
        bookDao.updateMeta(book.id, title, author)

    // ------------------------------------------------------------ progress

    suspend fun saveProgress(book: BookEntity, chapter: Int, offset: Int, page: Int, percent: Float) =
        withContext(Dispatchers.IO) {
            bookDao.saveProgress(
                id = book.id,
                chapter = chapter,
                offset = offset,
                page = page,
                percent = percent.coerceIn(0f, 1f),
                timestamp = System.currentTimeMillis()
            )
        }

    suspend fun markFinished(book: BookEntity, finished: Boolean) =
        bookDao.setFinished(book.id, finished)

    // ---------------------------------------------------------- highlights

    fun highlights(bookId: String): Flow<List<HighlightEntity>> = highlightDao.observeFor(bookId)

    suspend fun highlightsInChapter(bookId: String, chapter: Int): List<HighlightEntity> =
        withContext(Dispatchers.IO) { highlightDao.inChapter(bookId, chapter) }

    suspend fun addHighlight(
        book: BookEntity,
        chapter: Int,
        start: Int,
        end: Int,
        quote: String,
        kind: Int,
        colorIndex: Int,
        note: String = ""
    ): HighlightEntity = withContext(Dispatchers.IO) {
        val entity = HighlightEntity(
            id = "h_" + UUID.randomUUID().toString().take(12),
            bookId = book.id,
            chapterIndex = chapter,
            start = start,
            end = end,
            kind = kind,
            colorIndex = colorIndex,
            quote = quote.take(400),
            note = note,
            createdAt = System.currentTimeMillis()
        )
        highlightDao.upsert(entity)
        entity
    }

    suspend fun updateHighlightNote(id: String, note: String) = highlightDao.setNote(id, note)

    suspend fun deleteHighlight(id: String) = highlightDao.delete(id)

    // ----------------------------------------------------------- bookmarks

    fun bookmarks(bookId: String): Flow<List<BookmarkEntity>> = bookmarkDao.observeFor(bookId)

    suspend fun addBookmark(book: BookEntity, chapter: Int, offset: Int, title: String, excerpt: String, percent: Float) =
        withContext(Dispatchers.IO) {
            val entity = BookmarkEntity(
                id = "b_" + UUID.randomUUID().toString().take(12),
                bookId = book.id,
                chapterIndex = chapter,
                offset = offset,
                title = title,
                excerpt = excerpt.take(120),
                percent = percent,
                createdAt = System.currentTimeMillis()
            )
            bookmarkDao.upsert(entity)
            entity
        }

    suspend fun deleteBookmark(id: String) = bookmarkDao.delete(id)

    // --------------------------------------------------------------- notes

    fun notes(bookId: String): Flow<List<NoteEntity>> = noteDao.observeFor(bookId)
    fun allNotes(): Flow<List<NoteEntity>> = noteDao.observeAll()

    suspend fun addNote(book: BookEntity, chapter: Int, offset: Int, content: String, quote: String) =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val entity = NoteEntity(
                id = "n_" + UUID.randomUUID().toString().take(12),
                bookId = book.id,
                chapterIndex = chapter,
                offset = offset,
                content = content,
                quote = quote.take(200),
                createdAt = now,
                updatedAt = now
            )
            noteDao.upsert(entity)
            entity
        }

    suspend fun updateNote(id: String, content: String) =
        noteDao.update(id, content, System.currentTimeMillis())

    suspend fun deleteNote(id: String) = noteDao.delete(id)

    // --------------------------------------------------------------- stats

    suspend fun recordReading(bookId: String, millis: Long, chars: Long) = withContext(Dispatchers.IO) {
        if (millis <= 0L) return@withContext
        val day = today()
        val existing = statDao.day(day)
        statDao.upsert(
            ReadingDayEntity(
                day = day,
                millis = (existing?.millis ?: 0L) + millis,
                chars = (existing?.chars ?: 0L) + chars
            )
        )
    }

    fun stats(): Flow<List<ReadingDayEntity>> = statDao.observeAll()

    fun formatOf(book: BookEntity): DocFormat =
        runCatching { DocFormat.valueOf(book.format) }.getOrDefault(DocFormat.UNKNOWN)

    fun readerKind(book: BookEntity): ReaderKind =
        if (formatOf(book) == DocFormat.PDF) ReaderKind.PAGED else ReaderKind.FLOW

    fun isPaged(book: BookEntity): Boolean = readerKind(book) == ReaderKind.PAGED

    fun coverBitmap(book: BookEntity, maxSize: Int = 512): android.graphics.Bitmap? {
        val path = book.coverPath ?: return null
        val f = java.io.File(path)
        if (!f.exists()) return null
        return com.oceanreader.parse.DocumentFactory.decodeThumbnail(path, maxSize)
    }

    fun fileOf(book: BookEntity): java.io.File = java.io.File(book.filePath)

    fun appContext(): Context = context
    fun totalReadingMillis(): Flow<Long> = statDao.observeTotalMillis()

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    // ------------------------------------------------------------- reading

    suspend fun parse(book: BookEntity): ParsedDoc = withContext(Dispatchers.IO) {
        DocumentFactory.parse(File(book.filePath))
    }

    fun formatOf(book: BookEntity): DocFormat =
        runCatching { DocFormat.valueOf(book.format) }.getOrDefault(DocFormat.UNKNOWN)

    fun isPaged(book: BookEntity): Boolean =
        formatOf(book) == DocFormat.PDF || runCatching { DocFormat.valueOf(book.format) } == null

    fun readerKind(book: BookEntity): ReaderKind =
        if (formatOf(book) == DocFormat.PDF) ReaderKind.PAGED else ReaderKind.FLOW

    fun coverBitmap(book: BookEntity, maxSize: Int = 512): Bitmap? {
        val path = book.coverPath ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return DocumentFactory.decodeThumbnail(path, maxSize)
    }

    fun fileOf(book: BookEntity): File = File(book.filePath)

    fun appContext(): Context = context
}