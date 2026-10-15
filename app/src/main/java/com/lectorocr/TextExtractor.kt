package com.lectorocr

import android.content.ContentResolver
import android.net.Uri
import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParser.END_DOCUMENT
import org.xmlpull.v1.XmlPullParser.END_TAG
import org.xmlpull.v1.XmlPullParser.START_TAG
import org.xmlpull.v1.XmlPullParser.TEXT
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/**
 * Extrae texto de archivos que no son PDF ni imagen, sin librerías externas:
 *  - Texto plano (txt, csv, md, json, xml, log, código, etc.) y HTML
 *  - Hojas de cálculo: .xlsx / .xlsm / .ods
 *  - Documentos: .docx / .odt
 *  - Presentaciones: .pptx / .odp
 */
object TextExtractor {

    private const val MAX_FILE = 40L * 1024 * 1024
    private const val MAX_ENTRY = 60L * 1024 * 1024

    fun extract(resolver: ContentResolver, uri: Uri, name: String, mime: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val result = when (format(ext, mime.lowercase())) {
            "xlsx" -> resolver.withStream(uri) { xlsx(it) }
            "docx" -> resolver.withStream(uri) { docx(it) }
            "pptx" -> resolver.withStream(uri) { pptx(it) }
            "odf" -> resolver.withStream(uri) { odf(it) }
            "legacy" -> error(
                "Los formatos viejos (.xls, .doc, .ppt) no están soportados. " +
                    "Guardalo como .xlsx, .docx o .pptx e intentá de nuevo."
            )
            "html" -> {
                val bytes = resolver.withStream(uri) { it.readLimited(MAX_FILE) }
                Html.fromHtml(decode(bytes), Html.FROM_HTML_MODE_LEGACY).toString()
            }
            else -> {
                val bytes = resolver.withStream(uri) { it.readLimited(MAX_FILE) }
                val known = mime.startsWith("text/") || ext in TEXT_EXTS ||
                    mime in setOf("application/json", "application/xml", "application/javascript")
                if (!known && !looksLikeText(bytes)) error("Formato no soportado")
                decode(bytes)
            }
        }
        val text = result.trim()
        if (text.isEmpty()) error("No se encontró texto en el archivo")
        return text
    }

    private val TEXT_EXTS = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "log", "ini", "cfg", "conf",
        "yaml", "yml", "properties", "sql", "srt", "vtt", "tex", "rtf",
    )

    private fun format(ext: String, mime: String): String = when {
        ext in setOf("xlsx", "xlsm") || mime.contains("spreadsheetml") -> "xlsx"
        ext == "docx" || mime.contains("wordprocessingml") -> "docx"
        ext == "pptx" || mime.contains("presentationml") -> "pptx"
        ext in setOf("odt", "ods", "odp") || mime.startsWith("application/vnd.oasis.opendocument") -> "odf"
        ext in setOf("xls", "doc", "ppt") ||
            (ext.isEmpty() && mime in setOf(
                "application/vnd.ms-excel", "application/msword", "application/vnd.ms-powerpoint"
            )) -> "legacy"
        ext in setOf("html", "htm") || mime == "text/html" -> "html"
        else -> "text"
    }

    // ---------------- Texto plano ----------------

    private fun looksLikeText(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 4096)
        for (i in 0 until n) if (bytes[i].toInt() == 0) return false
        return true
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        var b = bytes
        if (b.size >= 3 && (b[0].toInt() and 0xFF) == 0xEF &&
            (b[1].toInt() and 0xFF) == 0xBB && (b[2].toInt() and 0xFF) == 0xBF
        ) b = b.copyOfRange(3, b.size)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (e: CharacterCodingException) {
            // Muchos CSV de Windows en español vienen en ISO-8859-1 / Windows-1252
            String(b, Charsets.ISO_8859_1)
        }
    }

    // ---------------- Utilidades ----------------

    private fun <T> ContentResolver.withStream(uri: Uri, block: (InputStream) -> T): T =
        (openInputStream(uri) ?: error("No se pudo leer el archivo")).use(block)

    private fun InputStream.readLimited(max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > max) error("El archivo es demasiado grande")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readZip(stream: InputStream, want: (String) -> Boolean): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        try {
            ZipInputStream(stream).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory && want(e.name)) out[e.name] = zip.readLimited(MAX_ENTRY)
                    e = zip.nextEntry
                }
            }
        } catch (e: java.util.zip.ZipException) {
            error("El archivo está dañado o no es válido")
        }
        return out
    }

    private fun parser(bytes: ByteArray): XmlPullParser =
        Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), null) }

    private fun XmlPullParser.attr(name: String): String = getAttributeValue(null, name) ?: ""

    /** Atributo por nombre local, ignorando el prefijo de namespace (r:id, w:val, etc.). */
    private fun XmlPullParser.attrLocal(local: String): String {
        for (i in 0 until attributeCount) {
            val n = getAttributeName(i)
            if (n == local || n.endsWith(":$local")) return getAttributeValue(i)
        }
        return ""
    }

    private fun local(name: String) = name.substringAfter(':')

    private fun colIndex(ref: String): Int {
        var n = 0
        for (c in ref) {
            n = when (c) {
                in 'A'..'Z' -> n * 26 + (c - 'A' + 1)
                in 'a'..'z' -> n * 26 + (c - 'a' + 1)
                else -> break
            }
        }
        return (n - 1).coerceIn(0, 16383)
    }

    // ---------------- XLSX ----------------

    private fun xlsx(stream: InputStream): String {
        val files = readZip(stream) {
            it == "xl/workbook.xml" || it == "xl/_rels/workbook.xml.rels" ||
                it == "xl/sharedStrings.xml" ||
                (it.startsWith("xl/worksheets/") && it.endsWith(".xml"))
        }
        val shared = files["xl/sharedStrings.xml"]?.let { sharedStrings(it) } ?: emptyList()
        val sb = StringBuilder()
        for ((name, path) in sheetList(files)) {
            val data = files[path] ?: continue
            sb.append("=== ").append(name).append(" ===\n")
            sb.append(sheetText(data, shared)).append('\n')
        }
        return sb.toString()
    }

    private fun sheetList(files: Map<String, ByteArray>): List<Pair<String, String>> {
        val wb = files["xl/workbook.xml"]
        val rels = files["xl/_rels/workbook.xml.rels"]
        val fallback = files.keys.filter { it.startsWith("xl/worksheets/") }.sorted()
            .mapIndexed { i, k -> "Hoja ${i + 1}" to k }
        if (wb == null || rels == null) return fallback

        val targets = HashMap<String, String>()
        parser(rels).let { p ->
            var ev = p.eventType
            while (ev != END_DOCUMENT) {
                if (ev == START_TAG && p.name == "Relationship") targets[p.attr("Id")] = p.attr("Target")
                ev = p.next()
            }
        }
        val list = mutableListOf<Pair<String, String>>()
        parser(wb).let { p ->
            var ev = p.eventType
            while (ev != END_DOCUMENT) {
                if (ev == START_TAG && p.name == "sheet") {
                    val t = targets[p.attrLocal("id")]
                    if (t != null) {
                        val path = if (t.startsWith("/")) t.trimStart('/') else "xl/$t"
                        list += p.attr("name") to path
                    }
                }
                ev = p.next()
            }
        }
        return list.ifEmpty { fallback }
    }

    private fun sharedStrings(bytes: ByteArray): List<String> {
        val p = parser(bytes)
        val out = ArrayList<String>()
        var sb: StringBuilder? = null
        var inT = false
        var inPhonetic = false
        var ev = p.eventType
        while (ev != END_DOCUMENT) {
            when (ev) {
                START_TAG -> when (p.name) {
                    "si" -> sb = StringBuilder()
                    "rPh" -> inPhonetic = true
                    "t" -> inT = !inPhonetic
                }
                TEXT -> if (inT) sb?.append(p.text)
                END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "rPh" -> inPhonetic = false
                    "si" -> {
                        out.add(sb?.toString() ?: "")
                        sb = null
                    }
                }
            }
            ev = p.next()
        }
        return out
    }

    /** Una fila por línea, celdas separadas por tabulación. */
    private fun sheetText(bytes: ByteArray, shared: List<String>): String {
        val p = parser(bytes)
        val out = StringBuilder()
        var cells = ArrayList<String>()
        var type = ""
        var ref = ""
        var value = StringBuilder()
        var capture = false
        var nextCol = 0
        var ev = p.eventType
        while (ev != END_DOCUMENT) {
            when (ev) {
                START_TAG -> when (p.name) {
                    "row" -> {
                        cells = ArrayList()
                        nextCol = 0
                    }
                    "c" -> {
                        type = p.attr("t")
                        ref = p.attr("r")
                        value = StringBuilder()
                    }
                    "v", "t" -> capture = true
                }
                TEXT -> if (capture) value.append(p.text)
                END_TAG -> when (p.name) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val col = if (ref.isNotEmpty()) colIndex(ref) else nextCol
                        val raw = value.toString()
                        val text = when (type) {
                            "s" -> shared.getOrNull(raw.trim().toIntOrNull() ?: -1).orEmpty()
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            else -> raw
                        }
                        while (cells.size <= col) cells.add("")
                        cells[col] = text.replace("\r", "").replace('\n', ' ').replace('\t', ' ')
                        nextCol = col + 1
                    }
                    "row" -> {
                        val line = cells.joinToString("\t").trimEnd('\t')
                        if (line.isNotBlank()) out.append(line).append('\n')
                    }
                }
            }
            ev = p.next()
        }
        return out.toString()
    }

    // ---------------- DOCX ----------------

    private fun docx(stream: InputStream): String {
        val data = readZip(stream) { it == "word/document.xml" }["word/document.xml"]
            ?: error("El archivo .docx no es válido")
        val p = parser(data)
        val sb = StringBuilder()
        var inT = false
        var inRun = false
        var ev = p.eventType
        while (ev != END_DOCUMENT) {
            when (ev) {
                START_TAG -> when (local(p.name)) {
                    "r" -> inRun = true
                    "t" -> inT = true
                    "tab" -> if (inRun) sb.append('\t')
                    "br", "cr" -> if (inRun) sb.append('\n')
                }
                TEXT -> if (inT) sb.append(p.text)
                END_TAG -> when (local(p.name)) {
                    "r" -> inRun = false
                    "t" -> inT = false
                    "p" -> sb.append('\n')
                    "tc" -> {
                        if (sb.isNotEmpty() && sb.last() == '\n') sb.setLength(sb.length - 1)
                        sb.append('\t')
                    }
                    "tr" -> {
                        if (sb.isNotEmpty() && sb.last() == '\t') sb.setLength(sb.length - 1)
                        sb.append('\n')
                    }
                }
            }
            ev = p.next()
        }
        return sb.toString().replace(Regex("\n{3,}"), "\n\n")
    }

    // ---------------- PPTX ----------------

    private fun pptx(stream: InputStream): String {
        val slideRegex = Regex("""ppt/slides/slide(\d+)\.xml""")
        val files = readZip(stream) { slideRegex.matches(it) }
        val slides = files.entries
            .sortedBy { slideRegex.matchEntire(it.key)!!.groupValues[1].toInt() }
        if (slides.isEmpty()) error("El archivo .pptx no es válido")

        val out = StringBuilder()
        for ((i, entry) in slides.withIndex()) {
            out.append("=== Diapositiva ${i + 1} ===\n")
            val p = parser(entry.value)
            var inT = false
            var ev = p.eventType
            while (ev != END_DOCUMENT) {
                when (ev) {
                    START_TAG -> if (local(p.name) == "t") inT = true
                    TEXT -> if (inT) out.append(p.text)
                    END_TAG -> when (local(p.name)) {
                        "t" -> inT = false
                        "p" -> out.append('\n')
                    }
                }
                ev = p.next()
            }
            out.append('\n')
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n")
    }

    // ---------------- OpenDocument (odt / ods / odp) ----------------

    private fun odf(stream: InputStream): String {
        val data = readZip(stream) { it == "content.xml" }["content.xml"]
            ?: error("El archivo no es un documento OpenDocument válido")
        val p = parser(data)
        val out = StringBuilder()
        var cell: StringBuilder? = null
        var reps = 1
        var depth = 0
        var ev = p.eventType
        while (ev != END_DOCUMENT) {
            when (ev) {
                START_TAG -> when (p.name) {
                    "table:table" -> out.append("=== ").append(p.attr("table:name")).append(" ===\n")
                    "table:table-cell", "table:covered-table-cell" -> {
                        cell = StringBuilder()
                        reps = p.attr("table:number-columns-repeated").toIntOrNull() ?: 1
                    }
                    "text:p", "text:h" -> depth++
                    "text:s" -> (cell ?: out).append(
                        " ".repeat((p.attr("text:c").toIntOrNull() ?: 1).coerceIn(1, 200))
                    )
                    "text:tab" -> (cell ?: out).append(if (cell != null) ' ' else '\t')
                    "text:line-break" -> (cell ?: out).append(if (cell != null) ' ' else '\n')
                }
                TEXT -> if (depth > 0) (cell ?: out).append(p.text)
                END_TAG -> when (p.name) {
                    "text:p", "text:h" -> {
                        depth--
                        if (cell != null) cell.append(' ') else out.append('\n')
                    }
                    "table:table-cell", "table:covered-table-cell" -> {
                        val v = cell?.toString()?.trim() ?: ""
                        cell = null
                        out.append(v)
                        out.append("\t".repeat(if (v.isEmpty()) reps.coerceIn(1, 30) else 1))
                    }
                    "table:table-row", "table:table" -> out.append('\n')
                }
            }
            ev = p.next()
        }
        return out.toString().lines().joinToString("\n") { it.trimEnd('\t', ' ') }
            .replace(Regex("\n{3,}"), "\n\n")
    }
}
