package com.oceanreader.parse.pdf

/**
 * A compact PDF object model — just enough to walk dictionaries, arrays and
 * streams while extracting text. Third-party PDF libraries are avoided on
 * purpose so the app stays dependency-light.
 */
sealed class PdfObj {

    data object Null : PdfObj()

    data class Bool(val value: Boolean) : PdfObj()

    data class Num(val value: Double) : PdfObj() {
        constructor(v: Int) : this(v.toDouble())
        constructor(v: Long) : this(v.toDouble())
        val int: Int get() = value.toInt()
    }

    /** Byte strings, decoded through UTF-16BE / PDFDocEncoding on demand. */
    data class Str(val value: String) : PdfObj()

    data class Name(val name: String) : PdfObj()

    data class Arr(val items: List<PdfObj>) : PdfObj()

    data class Ref(val num: Int, val gen: Int = 0) : PdfObj()

    /** Bare keyword such as `obj`, `stream`, `endobj`, or an operator name. */
    data class Kw(val text: String) : PdfObj()

    data class Dict(val entries: Map<String, PdfObj> = emptyMap()) : PdfObj() {
        operator fun get(key: String): PdfObj? = entries[key]
        fun name(key: String): String? = (entries[key] as? Name)?.name
        fun num(key: String): Double? = (entries[key] as? Num)?.value
        fun int(key: String): Int? = (entries[key] as? Num)?.int
        fun stream(): PdfStream? = entries["stream"] as? PdfStream
    }

    data class PdfStream(val bytes: ByteArray, val dict: Dict) : PdfObj() {
        operator fun get(key: String): PdfObj? = dict[key]
    }

    /** Attach a stream payload to the dictionary that describes it. */
    fun withStream(bytes: ByteArray): PdfObj = when (this) {
        is Dict -> Dict(entries + ("stream" to PdfStream(bytes, this)))
        else -> this
    }

    companion object {
        /** Builds a dictionary from loose key/value pairs. */
        fun dictOf(vararg pairs: Pair<String, PdfObj>): Dict {
            val map = LinkedHashMap<String, PdfObj>()
            for ((k, v) in pairs) map[k] = v
            return Dict(map)
        }
    }
}

/** Numeric helper shared by the extractor. */
fun PdfObj.asIntOrNull(): Int? = (this as? PdfObj.Num)?.int

fun PdfObj.asDoubleOrNull(): Double? = (this as? PdfObj.Num)?.value

fun PdfObj.asStringOrNull(): String? = (this as? PdfObj.Str)?.value

fun PdfObj.asNameOrNull(): String? = (this as? PdfObj.Name)?.name

fun PdfObj.asArrayOrNull(): List<PdfObj>? = (this as? PdfObj.Arr)?.items

/** True for the PDFDocEncoding/UTF-16 decision used by `Str`. */
fun decodePdfTextString(raw: String): String {
    if (raw.isEmpty()) return ""
    val first = raw[0]
    if (first == '\uFEFF') {
        val bytes = ByteArray(raw.length - 1)
        for (i in 1 until raw.length) bytes[i - 1] = (raw[i].code shr 8).toByte()
        return String(bytes, Charsets.UTF_16BE)
    }
    if (first == '\uFFFE') {
        val bytes = ByteArray(raw.length - 1)
        for (i in 1 until raw.length) bytes[i - 1] = (raw[i].code and 0xFF).toByte()
        return String(bytes, Charsets.UTF_16LE)
    }
    return raw
}