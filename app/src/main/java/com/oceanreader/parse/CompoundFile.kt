package com.oceanreader.parse

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal reader for the Compound File Binary (OLE2) container that legacy
 * .doc / .xls / .ppt files use.
 */
class CompoundFile private constructor(private val data: ByteArray) {

    private val sectorSize: Int
    private val miniSectorSize: Int
    private val fat: IntArray
    private val dirEntries: List<DirEntry>
    private val miniFat: IntArray
    private val miniStreamStart: Int
    private val miniStreamSize: Int

    class DirEntry(
        val name: String,
        val type: Int,
        val startSector: Int,
        val size: Long
    )

    companion object {
        private const val FREE_SECT = 0xFFFFFFFF.toInt()
        private const val END_OF_CHAIN = 0xFFFFFFFE.toInt()
        private const val DIFAT_SECT = 0xFFFFFFFD

        fun open(file: java.io.File): CompoundFile {
            val bytes = file.readBytes()
            return CompoundFile(bytes)
        }

        fun isCompound(data: ByteArray): Boolean =
            data.size >= 8 &&
                data[0] == 0xD0.toByte() && data[1] == 0xCF.toByte() &&
                data[2] == 0x11.toByte() && data[3] == 0xE0.toByte() &&
                data[4] == 0xA1.toByte() && data[5] == 0xB1.toByte() &&
                data[6] == 0x1A.toByte() && data[7] == 0xE1.toByte()
    }

    init {
        require(data.size >= 512) { "not a compound file" }
        val sectorShift = u16(0x1E)
        val miniSectorShift = u16(0x20)
        sectorSize = 1 shl sectorShift
        miniSectorSize = 1 shl miniSectorShift

        val fatSectorCount = u32(0x2C)
        val dirStartSector = u32(0x30)

        // DIFAT: first 109 entries live in the header.
        val difat = ArrayList<Int>()
        for (i in 0 until 109) {
            val v = u32(0x4C + i * 4)
            if (v != FREE_SECT && v < fatSectorCount + 16) difat.add(v)
        }
        // Remaining DIFAT sectors follow the FAT.
        var difatSector = u32(0x44)
        var guard = 0
        while (difatSector != END_OF_CHAIN && difatSector != FREE_SECT && guard++ < 1024) {
            val base = sectorOffset(difatSector)
            val entriesPerSector = sectorSize / 4 - 1
            for (i in 0 until entriesPerSector) {
                val v = intAt(base + i * 4)
                if (v != FREE_SECT) difat.add(v)
            }
            difatSector = intAt(base + entriesPerSector * 4)
        }

        fat = IntArray(fatSectorCount * (sectorSize / 4))
        for (s in 0 until fatSectorCount) {
            val sec = if (s < difat.size) difat[s] else FREE_SECT
            if (sec == FREE_SECT) continue
            val base = sectorOffset(sec)
            for (i in 0 until sectorSize / 4) {
                fat[s * (sectorSize / 4) + i] = intAt(base + i * 4)
            }
        }

        // Directory chain.
        val dirBytes = readChain(dirStartSector, -1)
        dirEntries = parseDirectory(dirBytes)

        // MiniFAT.
        val miniFatStart = u32(0x3C)
        val miniFatCount = u32(0x40)
        val miniFatBytes = if (miniFatCount > 0) readChain(miniFatStart, miniFatCount.toInt()) else ByteArray(0)
        miniFat = IntArray(miniFatBytes.size / 4) {
            intAt(miniFatBytes, it * 4)
        }

        val root = dirEntries.firstOrNull() ?: DirEntry("Root Entry", 5, 0, 0)
        miniStreamStart = root.startSector
        miniStreamSize = root.size.toInt()
    }

    fun streams(): Map<String, DirEntry> =
        dirEntries.filter { it.type == 2 }.associateBy { it.name }

    fun readStream(name: String): ByteArray? {
        val entry = dirEntries.firstOrNull { it.name.equals(name, true) && it.type == 2 } ?: return null
        return readEntry(entry)
    }

    private fun readEntry(entry: DirEntry): ByteArray {
        val size = entry.size.toInt()
        if (size <= 0) return ByteArray(0)
        return if (size < 4096) readMiniChain(entry.startSector, size) else readChain(entry.startSector, size)
    }

    private fun readChain(start: Int, size: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var sector = start
        var guard = 0
        val target = if (size >= 0) size else Int.MAX_VALUE
        while (sector != END_OF_CHAIN && sector != FREE_SECT && out.size() < target && guard++ < 1_000_000) {
            val off = sectorOffset(sector)
            if (off + sectorSize > data.size) break
            var len = sectorSize
            if (size >= 0 && out.size() + len > size) len = size - out.size()
            out.write(data, off, len)
            sector = if (sector < fat.size) fat[sector] else END_OF_CHAIN
        }
        return out.toByteArray()
    }

    private fun readMiniChain(start: Int, size: Int): ByteArray {
        val out = ByteArray(size)
        var sector = start
        var written = 0
        var guard = 0
        while (sector != END_OF_CHAIN && sector != FREE_SECT && written < size && guard++ < 1_000_000) {
            val off = miniStreamStart * sectorSize + sector * miniSectorSize
            if (off < 0 || off + miniSectorSize > data.size) break
            val len = minOf(miniSectorSize, size - written)
            System.arraycopy(data, off, out, written, len)
            written += len
            sector = if (sector < miniFat.size) miniFat[sector] else END_OF_CHAIN
        }
        return if (written == size) out else out.copyOf(written)
    }

    private fun parseDirectory(bytes: ByteArray): List<DirEntry> {
        val out = ArrayList<DirEntry>()
        var i = 0
        while (i + 128 <= bytes.size) {
            val nameLen = le16(bytes, i + 64)
            val type = bytes[i + 66].toInt() and 0xFF
            val startSector = intAt(bytes, i + 116)
            val size = longAt(bytes, i + 120)
            if (type == 0 && nameLen == 0) {
                i += 128
                continue
            }
            val name = if (nameLen in 2..64) {
                String(bytes, i, nameLen - 2, Charsets.UTF_16LE)
            } else ""
            out.add(DirEntry(name, type, startSector, size))
            i += 128
        }
        return out
    }

    private fun sectorOffset(sector: Int): Int = (sector + 1) * sectorSize

    private fun u16(off: Int): Int = le16(data, off)
    private fun u32(off: Int): Int = intAt(data, off)

    /** Reads a little-endian 32-bit integer from the file header. */
    private fun intAt(off: Int): Int = ByteBuffer.wrap(data, off, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun intAt(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return -1
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun longAt(b: ByteArray, off: Int): Long {
        if (off + 8 > b.size) return 0L
        return ByteBuffer.wrap(b, off, 8).order(ByteOrder.LITTLE_ENDIAN).long
    }
}