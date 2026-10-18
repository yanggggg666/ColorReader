package com.oceanreader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val filePath: String,
    val fileName: String,
    val format: String,
    val fileSize: Long,
    val coverPath: String?,
    val addedAt: Long,
    val lastReadAt: Long,
    val totalChars: Long,
    val totalPages: Int,
    val chapterCount: Int,
    val lastChapter: Int,
    val lastOffset: Long,
    val lastPage: Int,
    val percent: Float,
    val finished: Boolean,
    val pinned: Boolean,
    val sourceUri: String?
)

@Entity(tableName = "highlights", indices = [Index("bookId")])
data class HighlightEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    /** Offset inside the chapter body. */
    val start: Int,
    val end: Int,
    /** 0 = background wash, 1 = underline. */
    val kind: Int,
    val colorIndex: Int,
    val quote: String,
    val note: String,
    val createdAt: Long
)

@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val offset: Int,
    val title: String,
    val excerpt: String,
    val percent: Float,
    val createdAt: Long
)

@Entity(tableName = "notes", indices = [Index("bookId")])
data class NoteEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val offset: Int,
    val content: String,
    val quote: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "reading_days")
data class ReadingDayEntity(
    /** yyyy-MM-dd in the device time zone. */
    @PrimaryKey val day: String,
    val millis: Long,
    val chars: Long
)

@Entity(tableName = "voice_settings")
data class VoiceEntity(
    @PrimaryKey val bookId: String,
    val engine: String?,
    val voice: String?,
    val speed: Float,
    val pitch: Float,
    val highlightWord: Boolean,
    val autoNextChapter: Boolean
)