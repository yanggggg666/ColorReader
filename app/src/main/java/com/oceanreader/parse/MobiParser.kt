package com.oceanreader.parse

import com.oceanreader.data.model.ChapterRef
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * MOBI / AZW / AZW3 / PRC reader.
 *
 * A MOBI book is a PalmDB container: record 0 holds the PalmDOC header plus the
 * MOBI header (title, author, cover offset, compression), records 1..N hold
 * the HTML text split into ~4 KB pieces. We reassemble the text, undo the
 * PalmDOC LZ77 compression and hand the HTML to the common flattener, so
 * chapters, search and TTS behave exactly as they do for EPUB.
 *
 * Compression type 1 (none) and 2 (PalmDOC LZ77) are supported. The rarer
 * HUFF/CDIC scheme (17480, used by pre-2005 Mobipocket files) is reported as
 * unsupported rather than silently producing garbled text — convert those with
 * Calibre first.
 */
object MobiParser {

    private const val MAX_TEXT = 24 * 1024 * 1024
    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_LZ77 = 2
    private const val COMPRESSION_HUFF_CDIC = 17480

    fun parse(file: File, fallbackTitle: String): ParsedDoc {
        RandomAccessFile(file, "r").use { raf ->
            val bytes = readAll(raf)
            if (bytes.size < 80) throw ParseException("MOBI 文件过小或已损坏")

            // PalmDB: 32-byte name, ... , record count at 76, records from 78.
            val recordCount = u16(bytes, 76)
            if (recordCount < 2) throw ParseException("MOBI 文件没有正文记录")

            val offsets = IntArray(recordCount)
            for (i in 0 until recordCount) {
                offsets[i] = u32(bytes, 78 + i * 8)
            }

            val header = bytes.copyOfRange(
                offsets[0].coerceIn(0, bytes.size),
                bytes.size
            )
            if (header.size < 20) throw ParseException("MOBI 头部损坏")

            val compression = u16(header, 0)
            val textLength = u32(header, 4)
            val textRecordCount = u16(header, 8)
            val encryption = u16(header, 12)

            if (encryption != 0) throw ParseException("该 MOBI/AZW 带有 DRM 加密，无法打开")
            if (compression == COMPRESSION_HUFF_CDIC) {
                throw ParseException(
                    "该 MOBI 使用 HUFF/CDIC 压缩（早期 Mobipocket 格式）。" +
                        "请先用 Calibre 另存为 EPUB 或 FB2 再导入。"
                )
            }
            if (compression != COMPRESSION_NONE && compression != COMPRESSION_LZ77) {
                throw ParseException("不支持的 MOBI 压缩格式：$compression")
            }

            var encoding = Charsets.UTF_8
            var title = ""
            var author = ""
            var coverOffset = 0

            if (header.size >= 20 && String(header, 16, 4, Charsets.ISO_8859_1) == "MOBI") {
                encoding = encodingOf(u32(header, 28))
                val fullNameOffset = u32(header, 72)
                val fullNameLength = u32(header, 76)
                val exthFlags = u32(header, 116)

                if (fullNameLength in 1..1024 &&
                    16 + fullNameOffset + fullNameLength <= header.size
                ) {
                    title = String(header, 16 + fullNameOffset, fullNameLength, encoding)
                }
                if (exthFlags and 0x40 != 0) {
                    val exth = readExth(header, encoding)
                    author = exth[100] ?: exth[101] ?: exth[103] ?: ""
                    exth[503]?.let { title = it }
                }
                // MOBI 6/7 keep the cover offset in the EXTH record 201.
                if (coverOffset == 0) coverOffset = exthCoverOffset(header, encoding)
            }

            if (title.isBlank()) title = fallbackTitle

            // ---- Reassemble the text records
            val sink = ByteBuf()
            val count = if (textRecordCount in 1 until recordCount) textRecordCount else recordCount - 1
            for (i in 1..count) {
                if (i >= offsets.size) break
                val start = offsets[i]
                val end = if (i + 1 < offsets.size) offsets[i + 1] else bytes.size
                if (start >= bytes.size || end <= start) continue
                sink.write(bytes, start, (end - start).coerceAtMost(bytes.size - start))
                if (sink.size > MAX_TEXT) break
            }

            val packed = sink.toByteArray()
            val raw = if (compression == COMPRESSION_LZ77) palmDocDecompress(packed) else packed
            val payload = if (textLength in 1..raw.size) raw.copyOf(textLength) else raw

            val html = String(payload, encoding)
            val body = TextParsers.normalize(HtmlText.toPlainText(html))
            if (body.isBlank()) throw ParseException("MOBI 正文为空")

            return ParsedDoc(
                title = title.take(200),
                author = author,
                body = body,
                chapters = splitChapters(html, body),
                coverBytes = extractCover(bytes, coverOffset) ?: scanRecordsForImage(bytes, offsets)
            )
        }
    }

    /** Chapter boundaries come from the <h*> tags in the MOBI HTML. */
    private fun splitChapters(html: String, body: String): List<ChapterRef> {
        val blocks = HtmlText.blocks(html)
        val headings = blocks.filter { it.headingLevel in 1..3 && it.text.length in 1..60 }
        if (headings.size < 2) return ChapterSplitter.split(body)

        val chapters = ArrayList<ChapterRef>()
        var searchFrom = 0
        for ((i, h) in headings.withIndex()) {
            val needle = h.text.trim()
            val pos = body.indexOf(needle, searchFrom)
            if (pos < 0) continue
            val end = if (i + 1 < headings.size) {
                val nextPos = body.indexOf(headings[i + 1].text.trim(), pos + needle.length)
                if (nextPos > pos) nextPos else body.length
            } else body.length
            if (end <= pos) continue
            chapters.add(ChapterRef(chapters.size, needle.take(80), pos.toLong(), (end - pos).toLong()))
            searchFrom = pos + needle.length
        }
        return chapters.ifEmpty { ChapterSplitter.split(body) }
    }

    // -------------------------------------------------------------- EXTH

    private fun readExth(header: ByteArray, encoding: java.nio.charset.Charset): Map<Int, String> {
        val pos = findExth(header) ?: return emptyMap()
        val count = u32(header, pos + 4)
        val out = HashMap<Int, String>()
        var p = pos + 12
        for (i in 0 until count.coerceIn(0, 4096)) {
            if (p + 8 > header.size) break
            val type = u32(header, p)
            val length = u32(header, p + 4)
            if (length < 8 || p + length > header.size) break
            val value = String(header, p + 8, length - 8, encoding)
                .trim('\u0000', ' ', '\n', '\r', '\t')
                .replace("\u0000", "")
            if (value.isNotBlank()) out[type] = value
            p += length
        }
        return out
    }

    private fun exthCoverOffset(header: ByteArray, encoding: java.nio.charset.Charset): Int {
        val raw = readExth(header, encoding)[201] ?: return 0
        return raw.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            .toLongOrNull(16)?.toInt() ?: 0
    }

    private fun findExth(header: ByteArray): Int? {
        var i = 16
        while (i + 4 <= header.size) {
            if (header[i] == 'E'.code.toByte() && header[i + 1] == 'X'.code.toByte() &&
                header[i + 2] == 'T'.code.toByte() && header[i + 3] == 'H'.code.toByte()
            ) return i
            i += 4
        }
        return null
    }

    // ----------------------------------------------------------- LZ77

    /** PalmDOC LZ77 — the same scheme Kindle used for its text records. */
    fun palmDocDecompress(input: ByteArray): ByteArray {
        val out = ByteBuf(input.size * 3)
        var i = 0
        while (i < input.size && out.size < MAX_TEXT) {
            val c = input[i].toInt() and 0xFF
            when {
                c == 0 -> {
                    out.write(0); i++
                }
                c in 1..8 -> {
                    val n = minOf(c, input.size - i - 1)
                    for (k in 1..n) out.write(input[i + k].toInt() and 0xFF)
                    i += 1 + n
                }
                c == 9 -> {
                    if (i + 1 >= input.size) break
                    val n = input[i + 1].toInt() and 0xFF
                    repeat(n) { out.write(0) }
                    i += 2
                }
                c == 10 -> {
                    if (i + 1 >= input.size) break
                    val n = input[i + 1].toInt() and 0xFF
                    repeat(n) { out.write(0x20) }
                    i += 2
                }
                else -> {
                    if (i + 2 >= input.size) break
                    val b1 = input[i + 1].toInt() and 0xFF
                    val b2 = input[i + 2].toInt() and 0xFF
                    i += 3
                    val distance = (c shl 8) or b1
                    val length = b2 and 0x7F
                    val actual = if (b2 and 0x80 != 0) length + 2 else length + 3
                    val start = out.size - distance
                    if (start < 0) break
                    for (k in 0 until actual) out.write(out.get(start + k))
                }
            }
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------ cover

    private fun extractCover(bytes: ByteArray, coverOffset: Int): ByteArray? {
        if (coverOffset <= 0 || coverOffset + 12 > bytes.size) return null
        var i = coverOffset
        val limit = minOf(bytes.size, coverOffset + 8 * 1024 * 1024)
        while (i + 12 <= limit) {
            if (String(bytes, i, 8, Charsets.ISO_8859_1) == "BOUNDARY") {
                val len = u32(bytes, i + 8)
                val begin = i + 12
                if (len in 1..(limit - begin)) {
                    val payload = bytes.copyOfRange(begin, begin + len)
                    if (payload.size > 64) return payload
                }
                return null
            }
            i += 8
        }
        return null
    }

    // ---------------------------------------------------------- helpers

    private fun readAll(raf: RandomAccessFile): ByteArray {
        raf.seek(0)
        val length = raf.length()
        if (length <= 0) return ByteArray(0)
        val buf = ByteArray(length.toInt())
        raf.readFully(buf)
        return buf
    }

    private fun encodingOf(code: Int): java.nio.charset.Charset = when (code) {
        65001 -> Charsets.UTF_8
        1252 -> Charsets.ISO_8859_1
        936 -> Charsets.GB18030
        950 -> Charsets.BIG5
        932 -> Charsets.forName("Shift_JIS")
        949 -> Charsets.forName("EUC-KR")
        else -> Charsets.UTF_8
    }

    private fun u16(b: ByteArray, off: Int): Int {
        if (off + 2 > b.size) return 0
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return 0
        return (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)
    }
}

/** Growable byte buffer with random access — required by LZ77 back-references. */
internal class ByteBuf(initial: Int = 4096) {
    private var data = ByteArray(maxOf(16, initial))
    private var count = 0

    val size: Int get() = count

    fun write(b: Int) {
        ensure(1)
        data[count++] = b.toByte()
    }

    fun write(src: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        ensure(len)
        System.arraycopy(src, off, data, count, len)
        count += len
    }

    fun get(i: Int): Int = data[i].toInt() and 0xFF

    fun toByteArray(): ByteArray = data.copyOf(count)

    private fun ensure(extra: Int) {
        if (count + extra <= data.size) return
        var cap = data.size * 2
        while (cap < count + extra) cap *= 2
        data = data.copyOf(cap)
    }
}

/** Convenience alias so the reader can stream large text to a StringBuilder. */
internal fun bytesOf(stream: java.io.InputStream, limit: Int = MAX_TEXT): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(32 * 1024)
    var total = 0
    while (total < limit) {
        val n = stream.read(buf)
        if (n <= 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}