package com.oceanreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY lastReadAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun find(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE filePath = :path LIMIT 1")
    suspend fun findByPath(path: String): BookEntity?

    @Query(
        "SELECT * FROM books WHERE title LIKE '%' || :q || '%' " +
            "OR author LIKE '%' || :q || '%' OR fileName LIKE '%' || :q || '%' " +
            "ORDER BY lastReadAt DESC"
    )
    fun search(q: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE finished = 0 AND percent > 0.005 AND percent < 0.995 ORDER BY lastReadAt DESC LIMIT 1")
    fun observeContinueReading(): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE finished = 0 AND percent > 0.005 AND percent < 0.995 ORDER BY lastReadAt DESC LIMIT 12")
    fun observeContinueShelf(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE finished = 1 ORDER BY lastReadAt DESC")
    fun observeFinished(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE pinned = 1 ORDER BY lastReadAt DESC")
    fun observePinned(): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Update
    suspend fun update(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        "UPDATE books SET lastChapter = :chapter, lastOffset = :offset, lastPage = :page, " +
            "percent = :percent, lastReadAt = :timestamp WHERE id = :id"
    )
    suspend fun saveProgress(
        id: String,
        chapter: Int,
        offset: Int,
        page: Int,
        percent: Float,
        timestamp: Long
    )

    @Query("UPDATE books SET finished = :finished WHERE id = :id")
    suspend fun setFinished(id: String, finished: Boolean)

    @Query("UPDATE books SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)
}

@Dao
interface HighlightDao {

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterIndex, `start`")
    fun observeFor(bookId: String): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId")
    suspend fun allFor(bookId: String): List<HighlightEntity>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId AND chapterIndex = :chapter")
    suspend fun inChapter(bookId: String, chapter: Int): List<HighlightEntity>

    @Query("SELECT * FROM highlights WHERE id = :id")
    suspend fun find(id: String): HighlightEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(highlight: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM highlights WHERE bookId = :bookId")
    suspend fun deleteFor(bookId: String)
}

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeFor(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun find(id: String): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteFor(bookId: String)
}

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeFor(bookId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun find(id: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM notes WHERE bookId = :bookId")
    suspend fun deleteFor(bookId: String)
}

data class DayTotal(val day: String, val millis: Long)

data class BookTotal(val bookId: String, val millis: Long)

@Dao
interface StatDao {

    @Query("SELECT * FROM reading_days")
    fun observeAll(): Flow<List<ReadingDayEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(day: ReadingDayEntity)

    @Query("SELECT IFNULL(SUM(millis), 0) FROM reading_days")
    fun observeTotalMillis(): Flow<Long>

    @Query("SELECT day, SUM(millis) AS millis FROM reading_days GROUP BY day ORDER BY day DESC LIMIT 365")
    fun observeDaily(): Flow<List<DayTotal>>

    @Query("SELECT IFNULL(SUM(chars), 0) FROM reading_days")
    fun observeTotalChars(): Flow<Long>

    @Query("DELETE FROM reading_days")
    suspend fun clear()
}