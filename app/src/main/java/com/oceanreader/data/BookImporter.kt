package com.oceanreader.data

import android.content.Context
import android.net.Uri
import com.oceanreader.data.db.AppDatabase
import com.oceanreader.data.db.BookEntity
import com.oceanreader.data.model.DocFormat
import com.oceanreader.parse.DocumentFactory
import com.oceanreader.parse.ParseException
import com.oceanreader.parse.ParsedDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** How far an import got, for the progress UI. */
sealed class ImportStep {
    data object Copying : ImportStep()
    data object Parsing : ImportStep()
    data object ExtractingCover : ImportStep()
    data object Done : ImportStep()
}

sealed class ImportResult {
    data class Success(val book: BookEntity) : ImportResult()
    data class Failure(val fileName: String, val reason: String) : ImportResult()
}

/**
 * Copies user-picked files into app storage, sniffs the format, parses the
 * metadata and registers the book.
 *
 * Books are copied rather than referenced so they survive the source being
 * moved, unmounted or deleted — the same trade-off Kindle and most Chinese
 * readers make.
 */
class BookImporter(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore
) {

    val booksDir: File get() = File(context.filesDir, "books").apply { mkdirs() }
    private val coversDir: File get() = File(context.filesDir, "covers").apply { mkdirs() }
    private val cacheDir: File get() = File(context.cacheDir, "parse").apply { mkdirs() }

    suspend fun import(uri: Uri, onStep: (ImportStep) -> Unit = {}): ImportResult =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val displayName = displayNameOf(uri) ?: "未命名"
            try {
                val probe = DocumentFactory.probeByName(displayName)
                if (!probe.supported) {
                    return@withContext ImportResult.Failure(displayName, "暂不支持该格式")
                }

                onStep(ImportStep.Copying)
                val target = uniqueTarget(displayName)
                copyTo(uri, target)

                onStep(ImportStep.Parsing)
                val parsed: ParsedDoc = try {
                    DocumentFactory.parse(target)
                } catch (e: ParseException) {
                    target.delete()
                    return@withContext ImportResult.Failure(displayName, e.message ?: "解析失败")
                } catch (e: Throwable) {
                    target.delete()
                    return@withContext ImportResult.Failure(
                        displayName,
                        e.message?.takeIf { it.isNotBlank() } ?: "文件已损坏或格式不受支持"
                    )
                }

                onStep(ImportStep.ExtractingCover)
                val id = idFor(target.absolutePath)
                val coverPath = DocumentFactory.saveCover(
                    parsed.coverBytes,
                    File(coversDir, "$id.img")
                )

                val existing = db.bookDao().findByPath(target.absolutePath)
                val now = System.currentTimeMillis()
                val entity = BookEntity(
                    id = existing?.id ?: id,
                    title = parsed.title.takeIf { it.isNotBlank() } ?: displayName.substringBeforeLast('.'),
                    author = parsed.author,
                    filePath = target.absolutePath,
                    fileName = displayName,
                    format = probe.format.name,
                    fileSize = target.length(),
                    coverPath = coverPath,
                    addedAt = existing?.addedAt ?: now,
                    lastReadAt = existing?.lastReadAt ?: now,
                    totalChars = parsed.body.length.toLong(),
                    totalPages = if (parsed.readerKind == com.oceanreader.data.model.ReaderKind.PAGED) {
                        parsed.pageTexts.size
                    } else 0,
                    chapterCount = parsed.chapters.size,
                    lastChapter = existing?.lastChapter ?: 0,
                    lastOffset = existing?.lastOffset ?: 0L,
                    lastPage = existing?.lastPage ?: 0,
                    percent = existing?.percent ?: 0f,
                    finished = existing?.finished ?: false,
                    pinned = existing?.pinned ?: false,
                    sourceUri = uri.toString()
                )
                db.bookDao().upsert(entity)

                // Persist the parse result so the reader opens instantly next time.
                parsedCache(target).writeBytes(
                    ParseCacheIO.encode(parsed, id).toByteArray(Charsets.UTF_8)
                )

                onStep(ImportStep.Done)
                ImportResult.Success(entity)
            } catch (e: SecurityException) {
                ImportResult.Failure(displayName, "没有读取权限")
            } catch (e: Exception) {
                ImportResult.Failure(displayName, e.message ?: "导入失败")
            }
        }

    /** Re-runs metadata parsing, e.g. after a manual title edit. */
    suspend fun refresh(book: BookEntity): BookEntity = withContext(Dispatchers.IO) {
        val file = File(book.filePath)
        if (!file.exists()) return@withContext book
        val parsed = runCatching { DocumentFactory.parse(file) }.getOrNull() ?: return@withContext book
        val coverPath = DocumentFactory.saveCover(parsed.coverBytes, File(coversDir, "${book.id}.img"))
            ?: book.coverPath
        val updated = book.copy(
            title = parsed.title.ifBlank { book.title },
            author = parsed.author.ifBlank { book.author },
            description = parsed.description,
            chapterCount = parsed.chapters.size,
            totalChars = parsed.body.length.toLong(),
            coverPath = coverPath,
            totalPages = if (parsed.pageTexts.isNotEmpty()) parsed.pageTexts.size else book.totalPages
        )
        db.bookDao().upsert(updated)
        parsedCache(file).writeBytes(ParseCacheIO.encode(parsed, book.id).toByteArray(Charsets.UTF_8))
        updated
    }

    suspend fun delete(book: BookEntity, deleteFile: Boolean = true) = withContext(Dispatchers.IO) {
        if (deleteFile) runCatching { File(book.filePath).delete() }
        runCatching { File(book.coverPath.orEmpty()).delete() }
        runCatching { parsedCache(File(book.filePath)).delete() }
        db.highlightDao().deleteFor(book.id)
        db.bookmarkDao().deleteFor(book.id)
        db.noteDao().deleteFor(book.id)
        db.bookDao().delete(book.id)
    }

    fun parsedCache(file: File): File =
        File(cacheDir, idFor(file.absolutePath) + ".cache")

    private fun uniqueTarget(displayName: String): File {
        val safe = displayName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        var candidate = File(booksDir, safe)
        if (!candidate.exists()) return candidate
        val base = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "")
        var n = 1
        while (candidate.exists() && n < 999) {
            candidate = File(booksDir, "$base($n).${if (ext.isEmpty()) "txt" else ext}")
            n++
        }
        return candidate
    }

    private fun copyTo(uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ParseException("无法读取所选文件")
        input.use { ins ->
            target.outputStream().use { outs ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    outs.write(buf, 0, n)
                }
            }
        }
    }

    private fun displayNameOf(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment ?: uri.path
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            } ?: uri.lastPathSegment
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }

    companion object {
        fun idFor(path: String): String {
            val md = MessageDigest.getInstance("SHA-1")
            return md.digest(path.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        }
    }
}

/** Zero-copy access to a document's cached parse result. */
private fun DocumentFactory.probeByName(name: String): com.oceanreader.parse.FormatProbe {
    val format = DocFormat.fromExtension(name) ?: DocFormat.TXT
    return com.oceanreader.parse.FormatProbe(format, format.label, format != DocFormat.UNKNOWN)
}

private fun ParseCacheIO.encode(parsed: ParsedDoc, bookId: String): String {
    val sb = StringBuilder()
    sb.append("OCRD1\n")
    sb.append(bookId).append('\n')
    sb.append(parsed.title.replace("\n", " ")).append('\n')
    sb.append(parsed.author.replace("\n", " ")).append('\n')
    sb.append(parsed.description.replace("\n", " ")).append('\n')
    sb.append(parsed.readerKind.name).append('\n')
    sb.append(parsed.chapters.size).append('\n')
    for (c in parsed.chapters) {
        sb.append(c.index).append('\t')
            .append(c.startOffset).append('\t')
            .append(c.length).append('\t')
            .append(c.page).append('\t')
            .append(c.title.replace("\n", " ").replace("\t", " ")).append('\n')
    }
    sb.append("BODY\n")
    sb.append(parsed.body)
    return sb.toString()
}