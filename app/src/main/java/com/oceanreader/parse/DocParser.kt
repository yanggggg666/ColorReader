package com.oceanreader.parse

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Legacy Word 97-2003 (.doc) text extraction.
 *
 * A .doc file is an OLE2 compound file. All we need is the text, which lives
 * in the `WordDocument` stream; the mapping from logical character positions to
 * byte offsets is described by the piece table (a `PlcPcd`) referenced by the
 * FIB. Reading the piece table (rather than just dumping the stream) is what
 * makes non-ASCII, mixed-script documents come out correct.
 */
object DocParser {

    private const val FIB_MAGIC = 0xA5EC

    fun parse(file: File, fallbackTitle: String): ParsedDoc {
        val cfb = CompoundFile.open(file)
        val word = cfb.readStream("WordDocument")
            ?: cfb.streams().keys.firstNotNullOfOrNull {
                if (it.startsWith("WordDocument")) cfb.readStream(it) else null
            }
            ?: throw ParseException("DOC 文件缺少 WordDocument 流")

        val buffer = ByteBuffer.wrap(word).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getShort(0).toInt() and 0xFFFF != FIB_MAGIC) {
            throw ParseException("DOC 文件头异常，可能已损坏")
        }

        val flags = buffer.getShort(0x0A).toInt() and 0xFFFF
        if (flags and 0x0100 != 0) throw ParseException("该 DOC 已加密，无法打开")
        if (flags and 0x0200 != 0) throw ParseException("该 DOC 已加密（旧式保护），无法打开")

        val nFib = buffer.getShort(2).toInt() and 0xFFFF
        if (nFib < 101) throw ParseException("暂不支持 Word 6.0/95 格式，请另存为 .docx 或 .rtf")

        // Walk the variable-length FIB sections to find FibRgFcLcb97.
        val csw = buffer.getShort(32).toInt() and 0xFFFF
        val fcLcbStart = 32 + 2 + csw * 2 + 2 + 4 * 4 + 2 // +cslw(2) +cbRgFcLcb(2)
        val fcClxIndex = fcLcbStart + 33 * 8

        val fcClx = buffer.getInt(fcClxIndex).toLong() and 0xFFFFFFFFL
        val lcbClx = buffer.getInt(fcClxIndex + 4).toLong() and 0xFFFFFFFFL
        if (lcbClx <= 0 || fcClx <= 0 || fcClx + lcbClx > word.size) {
            throw ParseException("DOC 分段表损坏，无法提取正文")
        }

        val clx = word.copyOfRange(fcClx.toInt(), (fcClx + lcbClx).toInt())
        val plc = findPieceTable(clx) ?: throw ParseException("DOC 缺少正文分段表")
        val text = readPieces(plc, word)

        val body = TextParsers.normalize(text)
        if (body.isBlank()) throw ParseException("DOC 正文为空")

        val title = readSummaryTitle(cfb).ifBlank { fallbackTitle }
        val author = readSummaryAuthor(cfb)

        return ParsedDoc(
            title = title.take(200),
            author = author,
            body = body,
            chapters = ChapterSplitter.split(body)
        )
    }

    /** PlcPcd: n+1 CPs (4 bytes each) followed by n PCDs (8 bytes each). */
    private fun findPieceTable(clx: ByteArray): ByteArray? {
        var i = 0
        while (i < clx.size) {
            when (clx[i].toInt() and 0xFF) {
                0x01 -> {
                    if (i + 3 > clx.size) return null
                    val cb = u16(clx, i + 1)
                    i += 3 + cb
                }
                0x02 -> {
                    if (i + 5 > clx.size) return null
                    val lcb = u32(clx, i + 1)
                    if (lcb <= 0 || i + 5 + lcb > clx.size) return null
                    return clx.copyOfRange(i + 5, i + 5 + lcb)
                }
                else -> return null
            }
        }
        return null
    }

    private fun readPieces(plc: ByteArray, word: ByteArray): String {
        val n = (plc.size - 4) / 12
        if (n <= 0) return ""
        val out = StringBuilder()
        for (i in 0 until n) {
            val cpStart = u32(plc, i * 4)
            val cpEnd = u32(plc, (i + 1) * 4)
            val pcdBase = (n + 1) * 4 + i * 8
            val fc = u32(plc, pcdBase + 2)
            val compressed = (fc and 0x40000000) != 0
            val offset = if (compressed) (fc and 0x3FFFFFFF) shr 1 else (fc and 0x3FFFFFFF)
            val chars = cpEnd - cpStart
            if (chars <= 0 || offset <= 0) continue

            if (compressed) {
                val len = chars.coerceAtMost(word.size - offset)
                if (len > 0) out.append(decodeCp1252(word, offset, len))
            } else {
                val bytes = (chars * 2).coerceAtMost(word.size - offset)
                if (bytes > 0) out.append(String(word, offset, bytes, Charsets.UTF_16LE))
            }
        }
        return sanitize(out.toString())
    }

    /**
     * Word uses the low control-character range for structural markers:
     * 0x07 = cell, 0x0D = paragraph, 0x0B = line break, 0x0C = page break,
     * 0x1E = non-breaking hyphen, 0x1F = optional hyphen, 0x13/0x14/0x15 field
     * boundaries, 0x01 = inline picture, 0x08 = drawn object.
     */
    private fun sanitize(text: String): String {
        val sb = StringBuilder(text.length)
        var inField = false
        for (c in text) {
            when (c.code) {
                0x0D, 0x0B, 0x0C -> sb.append('\n')   // paragraph / line / page break
                0x07 -> sb.append('\t')                // table cell mark
                0x01 -> sb.append("[插图]")            // inline picture
                0x08 -> Unit                           // drawn object
                0x13 -> inField = true                 // field begin
                0x14 -> inField = false                // field separator
                0x15 -> Unit                           // field end
                0x1E -> sb.append('-')                 // non-breaking hyphen
                0x1F -> Unit                           // optional hyphen
                0x02 -> Unit                           // footnote reference
                0x00 -> Unit
                else -> if (!inField) sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun decodeCp1252(bytes: ByteArray, offset: Int, len: Int): String {
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            val v = bytes[offset + i].toInt() and 0xFF
            sb.append(if (v in 0x80..0x9F) CP1252[v - 0x80] else v.toChar())
        }
        return sb.toString()
    }

    // -------------------------------------------------- summary information

    private fun readSummaryTitle(cfb: CompoundFile): String = summaryString(cfb, 2)

    private fun readSummaryAuthor(cfb: CompoundFile): String = summaryString(cfb, 3)

    /**
     * Reads one property out of the OLE property-set stream. The stream is a
     * header followed by sections of typed property slots; we locate the slot
     * by property id and decode the value according to its type.
     */
    private fun summaryString(cfb: CompoundFile, propertyId: Int): String {
        val data = cfb.readStream("SummaryInformation") ?: return ""
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val sectionOffset = buf.getInt(48)
        if (sectionOffset <= 0 || sectionOffset + 6 > data.size) return ""
        val propertyCount = buf.getInt(sectionOffset + 4)
        if (propertyCount <= 0 || propertyCount > 512) return ""

        for (i in 0 until propertyCount) {
            val slot = sectionOffset + 8 + i * 8
            if (slot + 8 > data.size) break
            val pid = buf.getInt(slot)
            val offset = buf.getInt(slot + 4)
            if (pid != propertyId) continue
            val at = sectionOffset + offset
            if (at + 8 > data.size) continue
            return when (buf.getInt(at)) {
                30 -> { // VT_LPWSTR
                    val len = buf.getInt(at + 4)
                    val start = at + 8
                    if (len <= 0 || start + len * 2 > data.size) "" else {
                        String(data, start, len * 2, Charsets.UTF_16LE)
                            .trimEnd(' ', '\u0000')
                    }
                }
                31 -> { // VT_LPWSTR (variant)
                    val len = buf.getInt(at + 4)
                    val start = at + 8
                    if (len <= 0 || start + len > data.size) "" else {
                        String(data, start, len, Charsets.LATIN1)
                            .trimEnd(' ', '\u0000')
                    }
                }
                else -> ""
            }
        }
        return ""
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)

    private val CP1252 = charArrayOf(
        '€', '�', '‚', 'ƒ', '„', '…', '†', '‡',
        'ˆ', '‰', 'Š', '‹', 'Œ', '�', 'Ž', '�',
        '�', '‘', '’', '“', '”', '•', '–', '—',
        '˜', '™', 'š', '›', 'œ', '�', 'ž', 'Ÿ'
    )
}
