package com.oceanreader.parse.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * PDF text extraction, scoped to what read-aloud and in-book search need.
 *
 * Objects are located by scanning for `N G obj` rather than trusting the
 * cross-reference table, because stale xref offsets are the norm in the wild.
 * Filters supported: FlateDecode (with PNG/TIFF predictors), LZW, ASCIIHex,
 * ASCII85, RunLength. Fonts decode through /ToUnicode CMaps, falling back to
 * WinAnsi for simple Latin fonts.
 *
 * Scanned PDFs have no text layer; [extractPage] then returns "" and the
 * caller disables read-aloud rather than showing garbled output.
 */
class PdfTextExtractor(private val file: java.io.File) {

    private val objects = HashMap<Int, PdfObj>()
    private var trailer: PdfObj.Dict? = null
    private var loaded = false
    private val fontCache = HashMap<PdfObj, Font>()

    // ------------------------------------------------------------- loading

    private fun load() {
        val bytes = file.readBytes()
        var i = 0
        while (i + 3 < bytes.size) {
            if (bytes[i] == 'o'.code.toByte() && bytes[i + 1] == 'b'.code.toByte() &&
                bytes[i + 2] == 'j'.code.toByte()
            ) {
                var j = i - 1
                while (j >= 0 && bytes[j].toInt() in 48..57) j--
                if (j < i - 1) {
                    var num = 0
                    for (k in j + 1 until i) num = num * 10 + (bytes[k].toInt() - 48)
                    var m = i + 3
                    while (m < bytes.size && bytes[m].toInt() in 9..32) m++
                    if (m < bytes.size) {
                        val lx = Lexer(bytes, m)
                        val value = lx.readValue()
                        if (value is PdfObj.Dict) {
                            val stream = lx.readStreamIfPresent(value.entries)
                            val obj = stream ?: value
                            if (!objects.containsKey(num)) {
                                objects[num] = obj
                                if (obj is PdfObj.PdfStream) expandObjectStream(num, obj)
                            }
                        } else if (value != null && !objects.containsKey(num)) {
                            objects[num] = value
                        }
                    }
                }
            }
            i++
        }
        trailer = findTrailer(bytes)
        loaded = true
    }

    private fun ensureLoaded() {
        if (!loaded) {
            loaded = true
            runCatching { load() }
        }
    }

    private fun expandObjectStream(streamNum: Int, stream: PdfObj.PdfStream) {
        if (stream.dict.name("Type") != "ObjStm") return
        val data = decodeStream(stream) ?: return
        val count = stream.dict.int("N") ?: return
        val first = stream.dict.int("First") ?: return
        val header = Lexer(data, 0)
        val pairs = ArrayList<IntArray>(count)
        for (k in 0 until count) {
            val num = (header.readValue() as? PdfObj.Num)?.int ?: break
            val off = (header.readValue() as? PdfObj.Num)?.int ?: break
            pairs.add(intArrayOf(num, off))
        }
        for (p in pairs) {
            if (objects.containsKey(p[0])) continue
            val lx = Lexer(data, first + p[1])
            lx.readValue()?.let { objects[p[0]] = it }
        }
    }

    private fun findTrailer(bytes: ByteArray): PdfObj.Dict? {
        val tailStart = maxOf(0, bytes.size - 8192)
        val tail = String(bytes, tailStart, bytes.size - tailStart, Charsets.ISO_8859_1)
        var idx = tail.lastIndexOf("trailer")
        while (idx >= 0) {
            val lx = Lexer(bytes, tailStart + idx + 7)
            val d = lx.readValue()
            if (d is PdfObj.Dict) return d
            idx = tail.lastIndexOf("trailer", idx - 1)
        }
        objects.values.filterIsInstance<PdfObj.PdfStream>().forEach {
            if (it.dict.name("Type") == "XRef") {
                val root = resolve(it.dict["Root"])
                if (root is PdfObj.Dict) return root
            }
        }
        return null
    }

    fun resolve(o: PdfObj?): PdfObj =
        if (o is PdfObj.Ref) objects[o.num] ?: PdfObj.Null else (o ?: PdfObj.Null)

    // ---------------------------------------------------------------- pages

    private var cachedPages: List<PdfObj>? = null

    private fun pageList(): List<PdfObj> {
        ensureLoaded()
        cachedPages?.let { return it }
        val out = ArrayList<PdfObj>()
        val root = trailer?.get("Root")?.let { resolve(it) }
        val pagesNode = (root as? PdfObj.Dict)?.get("Pages")
        if (pagesNode != null) walkPages(resolve(pagesNode), out, HashSet(), 0)
        if (out.isEmpty()) {
            objects.values.forEach { o ->
                if ((o as? PdfObj.Dict)?.name("Type") == "Page") out.add(o)
            }
        }
        cachedPages = out
        return out
    }

    private fun walkPages(node: PdfObj, out: MutableList<PdfObj>, seen: MutableSet<Int>, depth: Int) {
        if (depth > 24 || out.size > 20000) return
        val dict = node as? PdfObj.Dict ?: return
        if (!seen.add(System.identityHashCode(dict))) return
        if (dict.name("Type") == "Page") {
            out.add(node)
            return
        }
        val kids = resolve(dict["Kids"])
        if (kids is PdfObj.Arr) {
            for (kid in kids.items) walkPages(resolve(kid), out, seen, depth + 1)
        }
    }

    fun pageCount(): Int = pageList().size

    fun extractPage(index: Int): String {
        val page = pageList().getOrNull(index) ?: return ""
        val dict = page as? PdfObj.Dict ?: return ""
        val content = pageContent(dict) ?: return ""
        val fonts = pageFonts(dict)
        return runContent(content, fonts)
    }

    fun extractAll(): List<String> = (0 until pageCount()).map { extractPage(it) }

    private fun pageContent(dict: PdfObj.Dict): ByteArray? {
        val contents = resolve(dict["Contents"]) ?: return null
        if (contents is PdfObj.PdfStream) return decodeStream(contents)
        if (contents is PdfObj.Arr) {
            val out = ByteArrayOutputStream()
            for (item in contents.items) {
                val s = resolve(item)
                if (s is PdfObj.PdfStream) decodeStream(s)?.let { out.write(it); out.write('\n'.code) }
            }
            return out.toByteArray().takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun pageFonts(dict: PdfObj.Dict): Map<String, PdfObj> {
        val out = LinkedHashMap<String, PdfObj>()
        val resources = resolve(dict["Resources"])
        val fonts = resolve((resources as? PdfObj.Dict)?.get("Font"))
        if (fonts is PdfObj.Dict) {
            for ((k, v) in fonts.entries) out[k] = v
        }
        return out
    }

    // -------------------------------------------------------------- fonts

    private fun fontOf(ref: PdfObj): Font {
        fontCache[ref]?.let { return it }
        val font = Font(resolve(ref))
        fontCache[ref] = font
        return font
    }

    private inner class Font(val obj: PdfObj) {
        val twoByte: Boolean
        val toUnicode = HashMap<Int, String>()
        val differences = HashMap<Int, String>()
        val widths = HashMap<Int, Int>()
        val firstChar: Int
        var defaultWidth = 500

        init {
            val dict = obj as? PdfObj.Dict
            twoByte = dict?.name("Subtype") == "Type0"
            firstChar = dict?.int("FirstChar") ?: 0
            val w = resolve(dict?.get("Widths"))
            if (w is PdfObj.Arr) {
                w.items.forEachIndexed { i, item ->
                    (item as? PdfObj.Num)?.let { widths[firstChar + i] = it.int }
                }
            }
            val descriptor = resolve(dict?.get("FontDescriptor")) as? PdfObj.Dict
            descriptor?.int("MissingWidth")?.let { defaultWidth = it }
            parseEncoding(resolve(dict?.get("Encoding")))
            parseToUnicode(resolve(dict?.get("ToUnicode")))
            if (toUnicode.isEmpty() && twoByte) {
                val descendants = resolve(dict?.get("DescendantFonts"))
                (descendants as? PdfObj.Arr)?.items?.firstOrNull()?.let { d ->
                    parseToUnicode(resolve((d as? PdfObj.Dict)?.get("ToUnicode")))
                }
            }
        }

        private fun parseEncoding(encoding: PdfObj) {
            if (encoding is PdfObj.Name) {
                if (encoding.name == "WinAnsiEncoding") applyWinAnsi()
                return
            }
            val dict = encoding as? PdfObj.Dict ?: return
            if (dict.name("BaseEncoding") == "WinAnsiEncoding") applyWinAnsi()
            val diffs = resolve(dict["Differences"])
            if (diffs is PdfObj.Arr) {
                var code = 0
                for (item in diffs.items) {
                    when (item) {
                        is PdfObj.Num -> code = item.int
                        is PdfObj.Name -> {
                            glyphToChar(item.name)?.let { differences[code] = it.toString() }
                            code++
                        }
                        else -> Unit
                    }
                }
            }
        }

        private fun applyWinAnsi() {
            for (code in 32..255) {
                winAnsi(code)?.let { differences[code] = it.toString() }
            }
        }

        private fun parseToUnicode(ref: PdfObj?) {
            val stream = resolve(ref) as? PdfObj.PdfStream ?: return
            val data = decodeStream(stream) ?: return
            parseCmap(String(data, Charsets.ISO_8859_1))
        }

        private fun parseCmap(text: String) {
            var i = 0
            while (true) {
                val bf = text.indexOf("beginbfchar", i)
                val br = text.indexOf("beginbfrange", i)
                if (bf < 0 && br < 0) return
                if (bf >= 0 && (br < 0 || bf <= br)) {
                    val end = text.indexOf("endbfchar", bf)
                    if (end < 0) return
                    Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")
                        .findAll(text.substring(bf, end))
                        .forEach { m ->
                            toUnicode[m.groupValues[1].toInt(16)] =
                                hexToString(m.groupValues[2])
                        }
                    i = end + 8
                } else {
                    val end = text.indexOf("endbfrange", br)
                    if (end < 0) return
                    Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(<[0-9A-Fa-f]+>|\\[[^\\]]*])")
                        .findAll(text.substring(br, end))
                        .forEach { m -> applyRange(m.groupValues) }
                    i = end + 10
                }
            }
        }

        private fun applyRange(g: List<String>) {
            val lo = g[0].toInt(16)
            val hi = g[1].toInt(16)
            val dst = g[2]
            if (hi < lo || hi - lo > 65535) return
            if (dst.startsWith("<")) {
                val base = dst.trim('<', '>').toLongOrNull(16) ?: return
                for (code in lo..hi) toUnicode[code] = codePoint(base + (code - lo))
            } else {
                var k = 0
                Regex("<([0-9A-Fa-f]+)>").findAll(dst).forEach { m ->
                    toUnicode[lo + k] = hexToString(m.groupValues[1])
                    k++
                }
            }
        }

        fun decode(code: Int): String {
            toUnicode[code]?.let { return it }
            differences[code]?.let { return it }
            if (twoByte) {
                if (code in 32..126) return code.toChar().toString()
                return ""
            }
            return if (code in 32..126) code.toChar().toString() else ""
        }

        fun widthOf(code: Int): Int = widths[code] ?: defaultWidth

        private fun glyphToChar(name: String): Char? = when (name) {
            "space" -> ' '
            "hyphen" -> '-'
            "period" -> '.'
            "comma" -> ','
            "colon" -> ':'
            "semicolon" -> ';'
            "quotesingle" -> '\''
            "quotedbl" -> '"'
            "parenleft" -> '('
            "parenright" -> ')'
            "slash" -> '/'
            "zero" -> '0'; "one" -> '1'; "two" -> '2'; "three" -> '3'; "four" -> '4'
            "five" -> '5'; "six" -> '6'; "seven" -> '7'; "eight" -> '8'; "nine" -> '9'
            else -> null
        }

        private fun winAnsi(code: Int): Char? = when {
            code in 32..126 -> code.toChar()
            code == 0x80 -> '€'
            code == 0x82 -> '‚'; code == 0x83 -> 'ƒ'; code == 0x84 -> '„'
            code == 0x85 -> '…'; code == 0x86 -> '†'; code == 0x87 -> '‡'
            code == 0x88 -> 'ˆ'; code == 0x89 -> '‰'; code == 0x8A -> 'Š'
            code == 0x8B -> '‹'; code == 0x8C -> 'Œ'
            code == 0x91 -> '‘'; code == 0x92 -> '’'; code == 0x93 -> '“'
            code == 0x94 -> '”'; code == 0x95 -> '•'; code == 0x96 -> '–'
            code == 0x97 -> '—'; code == 0x98 -> '˜'; code == 0x99 -> '™'
            code == 0x9A -> 'š'; code == 0x9B -> '›'; code == 0x9C -> 'œ'
            code == 0x9E -> 'ž'; code == 0x9F -> 'Ÿ'
            code in 160..255 -> code.toChar()
            else -> null
        }

        companion object {
            fun hexToString(hex: String): String {
                val sb = StringBuilder()
                var i = 0
                while (i + 4 <= hex.length) {
                    hex.substring(i, i + 4).toIntOrNull(16)?.let { sb.append(codePoint(it)) }
                    i += 4
                }
                if (sb.isEmpty() && hex.isNotEmpty()) {
                    for (j in 0 until hex.length step 2) {
                        hex.substring(j, j + 2).toIntOrNull(16)?.let { sb.append(codePoint(it)) }
                    }
                }
                return sb.toString()
            }

            fun codePoint(v: Int): String = when {
                v <= 0 -> ""
                v < 0x10000 -> String(Character.toChars(v))
                v < 0x110000 -> {
                    val x = v - 0x10000
                    String(Character.toChars(0xD800 + (x shr 10), 0xDC00 + (x and 0x3FF)))
                }
                else -> ""
            }
        }
    }

    // ---------------------------------------------------- content streams

    private fun runContent(content: ByteArray, fonts: Map<String, PdfObj>): String {
        val out = StringBuilder(content.size / 2)
        val lx = Lexer(content, 0)
        val operands = ArrayList<PdfObj>(8)

        var font: Font? = null
        var fontSize = 12f
        var charSpacing = 0f
        var wordSpacing = 0f
        var leading = 0f
        var lineBreak = false

        fun emit(s: String) {
            if (s.isEmpty()) return
            if (lineBreak) {
                if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                lineBreak = false
            }
            out.append(s)
        }

        fun showText(text: String) {
            val f = font ?: return
            val sb = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                if (f.twoByte) {
                    if (i + 2 > text.length) break
                    sb.append(f.decode(((text[i].code and 0xFF) shl 8) or (text[i + 1].code and 0xFF)))
                    i += 2
                } else {
                    val code = text[i].code and 0xFF
                    val d = f.decode(code)
                    sb.append(if (d.isNotEmpty()) d else if (code >= 32) code.toChar().toString() else "")
                    i++
                }
            }
            emit(sb.toString())
        }

        while (true) {
            val token = lx.nextToken() ?: break
            if (token !is PdfObj.Kw) {
                if (operands.size < 12) operands.add(token)
                continue
            }
            when (token.text) {
                "BT" -> { lineBreak = out.isNotEmpty(); operands.clear() }
                "ET" -> { if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n'); operands.clear() }
                "Tf" -> {
                    val name = operands.lastOrNull { it is PdfObj.Name } as? PdfObj.Name
                    if (name != null) fonts[name.name]?.let { font = fontOf(it) }
                    operands.lastOrNull { it is PdfObj.Num }?.let { fontSize = it.value.toFloat() }
                    operands.clear()
                }
                "Tc" -> { operands.lastOrNull { it is PdfObj.Num }?.let { charSpacing = it.value.toFloat() }; operands.clear() }
                "Tw" -> { operands.lastOrNull { it is PdfObj.Num }?.let { wordSpacing = it.value.toFloat() }; operands.clear() }
                "TL" -> { operands.lastOrNull { it is PdfObj.Num }?.let { leading = it.value.toFloat() }; operands.clear() }
                "Td", "TD" -> {
                    val nums = operands.filterIsInstance<PdfObj.Num>()
                    if (nums.size >= 2) {
                        if (token.text == "TD") leading = -nums[1].value.toFloat()
                        lineBreak = true
                    }
                    operands.clear()
                }
                "Tm" -> {
                    if (operands.count { it is PdfObj.Num } >= 6) lineBreak = true
                    operands.clear()
                }
                "T*" -> { lineBreak = true; operands.clear() }
                "Tj" -> { (operands.lastOrNull() as? PdfObj.Str)?.let { showText(it.value) }; operands.clear() }
                "'" -> {
                    lineBreak = true
                    (operands.lastOrNull() as? PdfObj.Str)?.let { showText(it.value) }
                    operands.clear()
                }
                "\"" -> {
                    val nums = operands.filterIsInstance<PdfObj.Num>()
                    if (nums.size >= 2) {
                        wordSpacing = nums[0].value.toFloat()
                        charSpacing = nums[1].value.toFloat()
                    }
                    lineBreak = true
                    (operands.lastOrNull() as? PdfObj.Str)?.let { showText(it.value) }
                    operands.clear()
                }
                "TJ" -> {
                    val arr = operands.lastOrNull()
                    if (arr is PdfObj.Arr) {
                        val sb = StringBuilder()
                        for (item in arr.items) {
                            when (item) {
                                is PdfObj.Str -> {
                                    val f = font
                                    if (f != null) {
                                        var i = 0
                                        val s = item.value
                                        while (i < s.length) {
                                            if (f.twoByte) {
                                                if (i + 2 > s.length) break
                                                sb.append(f.decode(((s[i].code and 0xFF) shl 8) or (s[i + 1].code and 0xFF)))
                                                i += 2
                                            } else {
                                                val code = s[i].code and 0xFF
                                                val d = f.decode(code)
                                                sb.append(if (d.isNotEmpty()) d else if (code >= 32) code.toChar().toString() else "")
                                                i++
                                            }
                                        }
                                    }
                                }
                                is PdfObj.Num -> {
                                    val gap = -item.value.toFloat() / 1000f * fontSize
                                    if (gap > fontSize * 0.18f) sb.append(' ')
                                }
                                else -> Unit
                            }
                        }
                        emit(sb.toString())
                    }
                    operands.clear()
                }
                "q", "Q", "Do" -> operands.clear()
                "BI" -> { lx.skipInlineImage(); operands.clear() }
                else -> operands.clear()
            }
        }
        return out.toString().trim()
    }

    // ---------------------------------------------------------- structure

    fun decodeStream(stream: PdfObj.PdfStream): ByteArray? {
        var data = stream.bytes
        val filters = ArrayList<String>()
        when (val f = stream.dict["Filter"]) {
            is PdfObj.Name -> filters.add(f.name)
            is PdfObj.Arr -> f.items.forEach { (it as? PdfObj.Name)?.let { n -> filters.add(n.name) } }
            else -> Unit
        }
        val parms = when (val p = stream.dict["DecodeParms"] ?: stream.dict["DP"]) {
            is PdfObj.Arr -> p.items
            else -> listOf(p)
        }
        for ((i, filter) in filters.withIndex()) {
            val parm = parms.getOrNull(i)?.let { resolve(it) } as? PdfObj.Dict
            data = when (filter) {
                "FlateDecode", "Fl" -> applyPredictor(inflate(data) ?: return null, parm)
                "LZWDecode", "LZW" -> applyPredictor(lzwDecode(data), parm)
                "ASCIIHexDecode", "AHx" -> asciiHexDecode(data)
                "ASCII85Decode", "A85" -> ascii85Decode(data)
                "RunLengthDecode", "RL" -> runLengthDecode(data)
                "DCTDecode", "DCT", "JPXDecode", "CCITTFaxDecode", "JBIG2Decode" -> return null
                else -> data
            }
        }
        return data
    }

    private fun applyPredictor(data: ByteArray, parms: PdfObj.Dict?): ByteArray {
        val predictor = parms?.int("Predictor") ?: return data
        if (predictor < 10) return data
        val colors = parms.int("Colors") ?: 1
        val bpc = parms.int("BitsPerComponent") ?: 8
        val columns = parms.int("Columns") ?: 1
        val bpp = maxOf(1, (colors * bpc + 7) / 8)
        val rowLen = (columns * colors * bpc + 7) / 8
        if (rowLen <= 0) return data

        val out = ByteArrayOutputStream(data.size)
        val prev = ByteArray(rowLen)
        var pos = 0
        while (pos + 1 <= data.size) {
            val type = data[pos].toInt() and 0xFF
            pos++
            val len = minOf(rowLen, data.size - pos)
            if (len <= 0) break
            val row = data.copyOfRange(pos, pos + len)
            pos += len
            for (i in 0 until len) {
                val a = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                val b = prev.getOrElse(i) { 0 }.toInt() and 0xFF
                val c = if (i >= bpp) prev.getOrElse(i - bpp) { 0 }.toInt() and 0xFF else 0
                row[i] = when (type) {
                    0 -> row[i]
                    1 -> ((row[i].toInt() + a) and 0xFF).toByte()
                    2 -> ((row[i].toInt() + b) and 0xFF).toByte()
                    3 -> ((row[i].toInt() + (a + b) / 2) and 0xFF).toByte()
                    4 -> ((row[i].toInt() + paeth(a, b, c)) and 0xFF).toByte()
                    else -> row[i]
                }
            }
            out.write(row)
            if (row.size < rowLen) System.arraycopy(row, 0, prev, 0, row.size)
        }
        return out.toByteArray()
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    private fun inflate(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return data
        for (skip in intArrayOf(0, 1, 2)) {
            try {
                val inf = Inflater()
                inf.setInput(data, skip, data.size - skip)
                val out = ByteArrayOutputStream(data.size * 3)
                val buf = ByteArray(32 * 1024)
                while (!inf.finished() && out.size() < 64 * 1024 * 1024) {
                    val n = inf.inflate(buf)
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    out.write(buf, 0, n)
                }
                inf.end()
                if (out.size() > 0) return out.toByteArray()
            } catch (e: Exception) {
                // try the next offset
            }
        }
        return ByteArray(0)
    }

    private fun asciiHexDecode(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var hi = -1
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c == '>'.code) break
            val v = when (c) {
                in 48..57 -> c - 48
                in 97..102 -> c - 87
                in 65..70 -> c - 55
                else -> -1
            }
            if (v < 0) continue
            if (hi < 0) hi = v else { out.write((hi shl 4) or v); hi = -1 }
        }
        return out.toByteArray()
    }

    private fun ascii85Decode(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var tuple = 0L
        var count = 0
        var i = 0
        while (i < data.size) {
            val c = data[i].toInt() and 0xFF
            i++
            if (c == '~'.code) break
            if (c == 'z'.code && count == 0) {
                out.write(0); out.write(0); out.write(0); out.write(0)
                continue
            }
            if (c < '!'.code || c > 'u'.code) continue
            tuple = tuple * 85 + (c - '!'.code)
            count++
            if (count == 5) {
                out.write(((tuple shr 24) and 0xFF).toInt())
                out.write(((tuple shr 16) and 0xFF).toInt())
                out.write(((tuple shr 8) and 0xFF).toInt())
                out.write((tuple and 0xFF).toInt())
                tuple = 0L; count = 0
            }
        }
        if (count in 2..4) {
            repeat(5 - count) { tuple = tuple * 85 + 84 }
            for (k in 0 until count - 1) out.write(((tuple shr (24 - k * 8)) and 0xFF).toInt())
        }
        return out.toByteArray()
    }

    private fun runLengthDecode(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            val len = data[i].toInt() and 0xFF
            i++
            when {
                len == 128 -> return out.toByteArray()
                len < 128 -> {
                    val n = minOf(len + 1, data.size - i)
                    out.write(data, i, n); i += n
                }
                else -> {
                    if (i >= data.size) break
                    val b = data[i].toInt()
                    i++
                    repeat(257 - len) { out.write(b) }
                }
            }
        }
        return out.toByteArray()
    }

    private fun lzwDecode(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 3)
        val table = ArrayList<ByteArray>()
        fun reset() {
            table.clear()
            for (i in 0 until 256) table.add(byteArrayOf(i.toByte()))
            table.add(ByteArray(0))
            table.add(ByteArray(0))
        }
        reset()
        var codeLen = 9
        var prev: ByteArray? = null
        var bitPos = 0
        val total = data.size * 8

        while (bitPos + codeLen <= total) {
            var v = 0
            for (k in 0 until codeLen) {
                val byte = data[bitPos ushr 3].toInt() and 0xFF
                v = (v shl 1) or ((byte shr (7 - (bitPos and 7))) and 1)
                bitPos++
            }
            when (v) {
                256 -> { reset(); codeLen = 9; prev = null }
                257 -> return out.toByteArray()
                else -> {
                    val entry = when {
                        v < table.size -> table[v]
                        prev != null -> prev + prev[0]
                        else -> return out.toByteArray()
                    }
                    out.write(entry)
                    prev?.let { table.add(it + entry[0]) }
                    prev = entry
                    if (table.size + 1 >= (1 shl codeLen) && codeLen < 12) codeLen++
                }
            }
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------ metadata

    fun readInfo(): Triple<String, String, String> {
        ensureLoaded()
        val info = trailer?.get("Info")?.let { resolve(it) } as? PdfObj.Dict
            ?: return Triple("", "", "")
        fun field(key: String): String {
            val v = resolve(info[key]) as? PdfObj.Str ?: return ""
            return decodePdfTextString(v.value)
        }
        return Triple(field("Title"), field("Author"), field("Subject"))
    }

    // ---------------------------------------------------------------- lexer

    /** Tokenizer for PDF syntax. */
    class Lexer(private val data: ByteArray, var pos: Int) {

        fun readValue(): PdfObj? = parse()

        fun nextToken(): PdfObj? {
            if (pos >= data.size) return null
            skipSpaces()
            if (pos >= data.size) return null
            val c = data[pos].toInt() and 0xFF
            return when {
                c == '/'.code -> { pos++; readName() }
                c == '['.code -> { pos++; readArray() }
                c == '('.code -> { pos++; PdfObj.Str(readLiteral()) }
                c == '<'.code -> {
                    if (pos + 1 < data.size && data[pos + 1].toInt() == '<'.code) {
                        pos += 2
                        readDictOrStream()
                    } else {
                        pos++
                        PdfObj.Str(readHex())
                    }
                }
                c == ']' .code || c == '>' .code || c == ')'.code -> { pos++; nextToken() }
                c == '{'.code || c == '}'.code -> { pos++; PdfObj.Kw((c.toChar()).toString()) }
                isDigit(c) || c == '+'.code || c == '-'.code || c == '.'.code -> readNumberOrRef()
                else -> {
                    val word = readWord()
                    when (word) {
                        "true" -> PdfObj.Bool(true)
                        "false" -> PdfObj.Bool(false)
                        "null" -> PdfObj.Null
                        else -> PdfObj.Kw(word)
                    }
                }
            }
        }

        fun skipInlineImage() {
            while (pos + 2 < data.size) {
                if (data[pos] == 'E'.code.toByte() && data[pos + 1] == 'I'.code.toByte() &&
                    isDelimiter(data[pos + 2].toInt() and 0xFF)
                ) {
                    pos += 2
                    return
                }
                pos++
            }
        }

        /** If a `stream` keyword follows the dictionary, consume it. */
        fun readStreamIfPresent(dict: Map<String, PdfObj>): PdfObj.PdfStream? {
            val save = pos
            skipSpaces()
            if (pos + 6 <= data.size && String(data, pos, 6, Charsets.ISO_8859_1) == "stream") {
                pos += 6
                if (pos < data.size && data[pos].toInt() == '\r'.code) pos++
                if (pos < data.size && data[pos].toInt() == '\n'.code) pos++
                val start = pos
                var length = -1
                (dict["Length"] as? PdfObj.Num)?.let { length = it.int }
                var end = if (length in 0..(data.size - start)) start + length else findEndstream(start)
                if (end < 0) end = data.size
                val bytes = data.copyOfRange(start, minOf(end, data.size))
                val after = findEndstream(end)
                pos = if (after >= 0) after + 9 else end
                return PdfObj.PdfStream(bytes, PdfObj.Dict(dict))
            }
            pos = save
            return null
        }

        private fun parse(): PdfObj? {
            skipSpaces()
            if (pos >= data.size) return null
            val token = nextToken()
            if (token is PdfObj.Kw) {
                when (token.text) {
                    "R" -> return PdfObj.Ref(refNum, refGen)
                    "obj", "endobj", "stream", "endstream", "xref", "trailer", "startxref" -> return null
                }
            }
            return token
        }

        private var refNum = 0
        private var refGen = 0

        private fun readNumberOrRef(): PdfObj {
            val start = pos
            if (pos < data.size && (data[pos].toInt() == '+'.code || data[pos].toInt() == '-'.code)) pos++
            while (pos < data.size) {
                val c = data[pos].toInt() and 0xFF
                if (isDigit(c) || c == '.'.code) pos++ else break
            }
            val text = String(data, start, pos - start, Charsets.ISO_8859_1)
            val value = text.toDoubleOrNull() ?: 0.0
            val save = pos
            skipSpaces()
            if (pos < data.size && isDigit(data[pos].toInt() and 0xFF)) {
                val genStart = pos
                while (pos < data.size && isDigit(data[pos].toInt() and 0xFF)) pos++
                val gen = String(data, genStart, pos - genStart, Charsets.ISO_8859_1).toIntOrNull() ?: 0
                val after = pos
                skipSpaces()
                if (pos < data.size && data[pos].toInt() == 'R'.code.toByte().toInt() &&
                    isDelimiterOrSpace(pos + 1)
                ) {
                    pos++
                    refNum = value.toInt()
                    refGen = gen
                    return PdfObj.Kw("__REF__")
                }
                pos = after
            }
            pos = save
            return PdfObj.Num(value)
        }

        private fun isDelimiterOrSpace(index: Int): Boolean {
            if (index >= data.size) return true
            val c = data[index].toInt() and 0xFF
            return isWhitespace(c) || isDelimiter(c)
        }

        private fun readName(): PdfObj.Name {
            val sb = StringBuilder()
            while (pos < data.size) {
                val c = data[pos].toInt() and 0xFF
                if (isWhitespace(c) || isDelimiter(c)) break
                if (c == '#'.code && pos + 2 < data.size) {
                    val hex = String(data, pos + 1, 2, Charsets.ISO_8859_1)
                    sb.append(hex.toIntOrNull(16)?.toChar() ?: '#')
                    pos += 3
                    continue
                }
                sb.append(c.toChar())
                pos++
            }
            return PdfObj.Name(sb.toString())
        }

        private fun readWord(): String {
            val start = pos
            while (pos < data.size) {
                val c = data[pos].toInt() and 0xFF
                if (isWhitespace(c) || isDelimiter(c)) break
                pos++
            }
            return if (pos == start) { pos++; "" } else String(data, start, pos - start, Charsets.ISO_8859_1)
        }

        private fun readArray(): PdfObj {
            val items = ArrayList<PdfObj>(8)
            while (pos < data.size) {
                skipSpaces()
                if (pos >= data.size) break
                if (data[pos].toInt() == ']'.code) { pos++; break }
                val before = pos
                val v = nextToken() ?: break
                if (pos == before) { pos++; continue }
                if (v is PdfObj.Kw && v.text == "__REF__") {
                    items.add(PdfObj.Ref(refNum, refGen))
                } else if (v is PdfObj.Kw && v.text.isEmpty()) {
                    continue
                } else {
                    items.add(v)
                }
                if (items.size > 65535) break
            }
            return PdfObj.Arr(items)
        }

        private fun readDictOrStream(): PdfObj {
            val dict = LinkedHashMap<String, PdfObj>()
            while (pos < data.size) {
                skipSpaces()
                if (pos + 1 < data.size && data[pos].toInt() == '>'.code &&
                    data[pos + 1].toInt() == '>'.code
                ) {
                    pos += 2
                    break
                }
                if (data[pos].toInt() != '/'.code) {
                    val before = pos
                    val skip = nextToken()
                    if (pos == before) pos++
                    if (skip == null) break
                    continue
                }
                val key = readName()
                skipSpaces()
                val before = pos
                val value = nextToken() ?: break
                if (pos == before) { pos++; continue }
                dict[key.name] = if (value is PdfObj.Kw && value.text == "__REF__") {
                    PdfObj.Ref(refNum, refGen)
                } else value
                if (dict.size > 4096) break
            }
            return PdfObj.Dict(dict)
        }

        private fun findEndstream(from: Int): Int {
            var i = from
            while (i + 8 < data.size) {
                if (data[i] == 'e'.code.toByte() && data[i + 1] == 'n'.code.toByte() &&
                    data[i + 2] == 'd'.code.toByte() && data[i + 3] == 's'.code.toByte() &&
                    data[i + 4] == 't'.code.toByte() && data[i + 5] == 'r'.code.toByte() &&
                    data[i + 6] == 'e'.code.toByte() && data[i + 7] == 'a'.code.toByte()
                ) return i
                i++
            }
            return -1
        }

        private fun readHex(): String {
            val sb = StringBuilder()
            var hi = -1
            while (pos < data.size) {
                val c = data[pos].toInt() and 0xFF
                pos++
                val v = when (c) {
                    in 48..57 -> c - 48
                    in 97..102 -> c - 87
                    in 65..70 -> c - 55
                    '>'.code -> break
                    else -> -1
                }
                if (v < 0) continue
                if (hi < 0) hi = v else { sb.append(((hi shl 8) or v).toChar()); hi = -1 }
            }
            if (hi >= 0) sb.append((hi shl 4).toChar())
            return sb.toString()
        }

        private fun readLiteral(): String {
            val sb = StringBuilder()
            var depth = 1
            while (pos < data.size) {
                val c = data[pos].toInt() and 0xFF
                pos++
                when (c) {
                    '\\'.code -> {
                        if (pos >= data.size) break
                        val e = data[pos].toInt() and 0xFF
                        pos++
                        when (e) {
                            'n'.code -> sb.append('\n')
                            'r'.code -> sb.append('\r')
                            't'.code -> sb.append('\t')
                            'b'.code -> sb.append('\b')
                            'f'.code -> sb.append('')
                            '('.code -> sb.append('(')
                            ')'.code -> sb.append(')')
                            '\\'.code -> sb.append('\\')
                            '\r'.code -> if (pos < data.size && data[pos].toInt() == '\n'.code) pos++
                            '\n'.code -> Unit
                            in 48..55 -> {
                                var oct = e - 48
                                var k = 0
                                while (k < 2 && pos < data.size) {
                                    val d = data[pos].toInt() and 0xFF
                                    if (d !in 48..55) break
                                    oct = oct * 8 + (d - 48)
                                    pos++
                                    k++
                                }
                                sb.append(oct.toChar())
                            }
                            else -> sb.append(e.toChar())
                        }
                    }
                    '('.code -> { depth++; sb.append('(') }
                    ')'.code -> {
                        depth--
                        if (depth == 0) break
                        sb.append(')')
                    }
                    else -> sb.append(c.toChar())
                }
            }
            return sb.toString()
        }

        fun skipSpaces() {
            while (pos < data.size && isWhitespace(data[pos].toInt() and 0xFF)) pos++
        }

        companion object {
            fun isWhitespace(c: Int): Boolean = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
            fun isDigit(c: Int): Boolean = c in 48..57
            fun isDelimiter(c: Int): Boolean =
                c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
                        c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code ||
                        c == '/'.code || c == '%'.code
        }
    }
}