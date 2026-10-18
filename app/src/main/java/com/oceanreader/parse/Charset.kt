package com.oceanreader.parse

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/** Charsets the app relies on; resolved once so lookups stay cheap. */
object Charsets {
    val UTF_8: Charset = Charset.forName("UTF-8")
    val UTF_16LE: Charset = Charset.forName("UTF-16LE")
    val UTF_16BE: Charset = Charset.forName("UTF-16BE")
    val UTF_32LE: Charset = Charset.forName("UTF-32LE")
    val UTF_32BE: Charset = Charset.forName("UTF-32BE")
    val GB18030: Charset = Charset.forName("GB18030")
    val BIG5: Charset = Charset.forName("Big5")
    val LATIN1: Charset = Charset.forName("ISO-8859-1")
}

/**
 * BOM-aware charset sniffing for legacy text formats.
 * UTF-8 wins when the sample decodes cleanly; otherwise we score the CJK
 * legacy encodings and take the best explanation of the byte stream.
 */
object CharsetSniffer {

    private val LEGACY_CANDIDATES = listOf("GB18030", "Big5", "Shift_JIS", "EUC-KR")

    fun decode(bytes: ByteArray): String {
        bomOf(bytes)?.let { (cs, offset) ->
            return String(bytes, offset, bytes.size - offset, cs)
        }
        val sampleLen = minOf(bytes.size, 512 * 1024)
        val sample = if (bytes.size > sampleLen) bytes.copyOfRange(0, sampleLen) else bytes
        if (tryDecodeStrict(sample, Charsets.UTF_8) != null) return String(sample, Charsets.UTF_8)

        val trimmed = trimPartialTail(sample)
        return tryDecode(trimmed, guess(trimmed))
    }

    fun decode(bytes: ByteArray, charset: Charset): String = tryDecode(bytes, charset)

    private fun bomOf(bytes: ByteArray): Pair<Charset, Int>? {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) return Charsets.UTF_8 to 3
        if (bytes.size >= 4 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
            bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
        ) return Charsets.UTF_32LE to 4
        if (bytes.size >= 4 &&
            bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
            bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()
        ) return Charsets.UTF_32BE to 4
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return Charsets.UTF_16LE to 2
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return Charsets.UTF_16BE to 2
        return null
    }

    private fun trimPartialTail(sample: ByteArray): ByteArray {
        var end = sample.size
        var back = 0
        while (end > 0 && back < 4) {
            val b = sample[end - 1].toInt() and 0xFF
            if (b and 0xC0 == 0x80) {
                end--
                back++
            } else break
        }
        return if (end == sample.size) sample else sample.copyOfRange(0, end)
    }

    /** Scores each legacy CJK encoding by how plausible its decode looks. */
    private fun guess(sample: ByteArray): Charset {
        var best = Charsets.UTF_8
        var bestScore = Int.MIN_VALUE
        for (name in LEGACY_CANDIDATES) {
            val cs = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            val decoded = tryDecodeStrict(sample, cs) ?: continue
            var score = 0
            for (ch in decoded) {
                when {
                    ch == '\uFFFD' -> score -= 8
                    ch.code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t' -> score -= 8
                    isCjk(ch) -> score += 3
                    ch.isLetterOrDigit() -> score += 1
                    else -> score += 1
                }
            }
            if (score > bestScore) {
                bestScore = score
                best = cs
            }
        }
        return if (bestScore < 0) Charsets.GB18030 else best
    }

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0xF900..0xFAFF ||
                c in 0x3040..0x30FF || c in 0xAC00..0xD7AF
    }

    private fun tryDecodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        val decoder: CharsetDecoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    } catch (e: Exception) {
        null
    }

    private fun tryDecode(bytes: ByteArray, charset: Charset): String = try {
        String(bytes, charset)
    } catch (e: Exception) {
        String(bytes, Charsets.UTF_8)
    }
}