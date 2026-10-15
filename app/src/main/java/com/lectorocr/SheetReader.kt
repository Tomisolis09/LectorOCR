package com.lectorocr

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Lee hojas de cálculo .xlsx y .ods sin librerías externas (son ZIP con XML adentro).
 * Devuelve el contenido como texto: una fila por línea, celdas separadas por tabulaciones
 * (se puede pegar directo en Excel / Calc / Google Sheets).
 *
 * Limitaciones: muestra el valor guardado de cada celda (no recalcula fórmulas) y las
 * fechas de .xlsx aparecen como número de serie.
 */
object SheetReader {

    private const val MAX_ROWS = 100_000
    private const val MAX_COLS = 1_000

    private class Budget {
        var rows = 0
        var truncated = false
        fun take(): Boolean {
            if (rows >= MAX_ROWS) {
                truncated = true
                return false
            }
            rows++
            return true
        }
    }

    fun read(context: Context, uri: Uri, isOds: Boolean): String {
        val tmp = File.createTempFile("hoja", if (isOds) ".ods" else ".xlsx", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("No se pudo abrir el archivo")
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            return try {
                ZipFile(tmp).use { zip -> if (isOds) readOds(zip) else readXlsx(zip) }
            } catch (e: ZipException) {
                error("El archivo está dañado o protegido con contraseña")
            }
        } finally {
            tmp.delete()
        }
    }

    // ---------------- XLSX ----------------

    private fun readXlsx(zip: ZipFile): String {
        val wb = zip.getEntry("xl/workbook.xml") ?: error("No parece un archivo .xlsx válido")

        val names = ArrayList<String>()
        val rids = ArrayList<String>()
        zip.getInputStream(wb).use { s ->
            val p = parser(s)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && local(p) == "sheet") {
                    names.add(attr(p, "name") ?: "Hoja ${names.size + 1}")
                    rids.add(attr(p, "id") ?: "")
                }
                ev = p.next()
            }
        }

        val targets = HashMap<String, String>()
        zip.getEntry("xl/_rels/workbook.xml.rels")?.let { e ->
            zip.getInputStream(e).use { s ->
                val p = parser(s)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && local(p) == "Relationship") {
                        val id = attr(p, "Id")
                        val target = attr(p, "Target")
                        if (id != null && target != null) targets[id] = target
                    }
                    ev = p.next()
                }
            }
        }

        val shared = ArrayList<String>()
        zip.getEntry("xl/sharedStrings.xml")?.let { e ->
            zip.getInputStream(e).use { s ->
                val p = parser(s)
                val sb = StringBuilder()
                var inSi = false
                var phonetic = 0
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        when (local(p)) {
                            "si" -> { inSi = true; sb.setLength(0) }
                            "rPh" -> phonetic++
                            "t" -> if (inSi && phonetic == 0) sb.append(p.nextText())
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        when (local(p)) {
                            "si" -> { shared.add(sb.toString()); inSi = false }
                            "rPh" -> phonetic--
                        }
                    }
                    ev = p.next()
                }
            }
        }

        val budget = Budget()
        val sheets = ArrayList<Pair<String, String>>()
        for (i in names.indices) {
            val target = targets[rids[i]]
            val path = when {
                target == null -> "xl/worksheets/sheet${i + 1}.xml"
                target.startsWith("/") -> target.substring(1)
                else -> "xl/$target"
            }
            val entry = zip.getEntry(path) ?: continue
            sheets.add(names[i] to readXlsxSheet(zip.getInputStream(entry), shared, budget))
            if (budget.truncated) break
        }
        return assemble(sheets, budget.truncated)
    }

    private fun readXlsxSheet(stream: InputStream, shared: List<String>, budget: Budget): String {
        val out = StringBuilder()
        stream.use { s ->
            val p = parser(s)
            val row = ArrayList<String>()
            val inlineText = StringBuilder()
            var ref = ""
            var type = ""
            var v = ""
            var inIs = false
            var phonetic = 0
            var lastCol = -1
            var ev = p.eventType
            loop@ while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (local(p)) {
                        "row" -> { row.clear(); lastCol = -1 }
                        "c" -> {
                            ref = attr(p, "r") ?: ""
                            type = attr(p, "t") ?: ""
                            v = ""
                            inlineText.setLength(0)
                        }
                        "v" -> v = p.nextText()
                        "is" -> inIs = true
                        "rPh" -> phonetic++
                        "t" -> if (inIs && phonetic == 0) inlineText.append(p.nextText())
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (local(p)) {
                        "is" -> inIs = false
                        "rPh" -> phonetic--
                        "c" -> {
                            val value = when (type) {
                                "s" -> v.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                                "b" -> if (v.trim() == "1") "TRUE" else "FALSE"
                                "inlineStr" -> inlineText.toString()
                                else -> v
                            }.oneLine()
                            val idx = if (ref.isNotEmpty()) colIndex(ref) else lastCol + 1
                            lastCol = idx
                            if (value.isNotEmpty() && idx in 0 until MAX_COLS) {
                                while (row.size <= idx) row.add("")
                                row[idx] = value
                            }
                        }
                        "row" -> {
                            while (row.isNotEmpty() && row[row.lastIndex].isEmpty()) row.removeAt(row.lastIndex)
                            if (row.isNotEmpty()) {
                                out.append(row.joinToString("\t")).append('\n')
                                if (!budget.take()) break@loop
                            }
                        }
                    }
                }
                ev = p.next()
            }
        }
        return out.toString()
    }

    // ---------------- ODS ----------------

    private fun readOds(zip: ZipFile): String {
        val entry = zip.getEntry("content.xml") ?: error("No parece un archivo .ods válido")
        val sheets = ArrayList<Pair<String, StringBuilder>>()
        val budget = Budget()

        zip.getInputStream(entry).use { s ->
            val p = parser(s)
            val row = ArrayList<String>()
            var cur: StringBuilder? = null
            var rowRepeat = 1
            var pendingBlank = 0
            var ev = p.eventType
            loop@ while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (local(p)) {
                        "table" -> {
                            val sb = StringBuilder()
                            sheets.add((attr(p, "name") ?: "Hoja ${sheets.size + 1}") to sb)
                            cur = sb
                            pendingBlank = 0
                        }
                        "table-row" -> {
                            row.clear()
                            rowRepeat = attr(p, "number-rows-repeated")?.toIntOrNull() ?: 1
                        }
                        "table-cell", "covered-table-cell" -> {
                            val vt = attr(p, "value-type")
                            val raw = attr(p, "value")
                            val bool = attr(p, "boolean-value")
                            val date = attr(p, "date-value")
                            val time = attr(p, "time-value")
                            val rep = attr(p, "number-columns-repeated")?.toIntOrNull() ?: 1
                            val text = readCellText(p) // consume hasta el cierre de la celda
                            val value = when (vt) {
                                "float", "percentage", "currency" -> raw ?: text
                                "boolean" -> (bool ?: text).uppercase()
                                "date" -> date ?: text
                                "time" -> time ?: text
                                else -> text
                            }.oneLine()
                            val times = if (value.isEmpty()) minOf(rep, 256) else minOf(rep, 100)
                            var k = 0
                            while (k < times && row.size < MAX_COLS) {
                                row.add(value)
                                k++
                            }
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && local(p) == "table-row") {
                    while (row.isNotEmpty() && row[row.lastIndex].isEmpty()) row.removeAt(row.lastIndex)
                    val sb = cur
                    if (sb != null) {
                        if (row.isEmpty()) {
                            pendingBlank = minOf(pendingBlank + rowRepeat, 3)
                        } else {
                            if (sb.isNotEmpty()) repeat(pendingBlank) { sb.append('\n') }
                            pendingBlank = 0
                            val line = row.joinToString("\t")
                            var k = 0
                            val times = minOf(rowRepeat, 1000)
                            while (k < times) {
                                sb.append(line).append('\n')
                                k++
                                if (!budget.take()) break@loop
                            }
                        }
                    }
                }
                ev = p.next()
            }
        }
        return assemble(sheets.map { it.first to it.second.toString() }, budget.truncated)
    }

    /** Lee el texto visible de una celda ODS (ignora comentarios) y deja el parser en el cierre de la celda. */
    private fun readCellText(p: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        var annotation = 0
        var paragraphs = 0
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (local(p)) {
                        "annotation" -> annotation++
                        "p" -> if (annotation == 0) {
                            if (paragraphs > 0) sb.append(' ')
                            paragraphs++
                        }
                        "s" -> if (annotation == 0) {
                            val c = attr(p, "c")?.toIntOrNull() ?: 1
                            repeat(c.coerceIn(1, 100)) { sb.append(' ') }
                        }
                        "tab", "line-break" -> if (annotation == 0) sb.append(' ')
                    }
                }
                XmlPullParser.END_TAG -> {
                    depth--
                    if (local(p) == "annotation") annotation--
                }
                XmlPullParser.TEXT -> if (annotation == 0) sb.append(p.text)
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString()
    }

    // ---------------- Utilidades ----------------

    private fun assemble(sheets: List<Pair<String, String>>, truncated: Boolean): String {
        val withData = sheets.filter { it.second.isNotBlank() }
        val body = if (withData.size <= 1) {
            withData.firstOrNull()?.second?.trimEnd() ?: ""
        } else {
            withData.joinToString("\n\n") { "=== ${it.first} ===\n${it.second.trimEnd()}" }
        }
        return if (truncated) "$body\n\n[… recortado a $MAX_ROWS filas …]" else body
    }

    private fun parser(stream: InputStream): XmlPullParser =
        Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(stream, null)
        }

    /** Nombre de la etiqueta sin prefijo ("table:table-row" -> "table-row"). */
    private fun local(p: XmlPullParser): String = (p.name ?: "").substringAfter(':')

    /** Valor de un atributo ignorando el prefijo ("office:value-type" -> "value-type"). */
    private fun attr(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i).substringAfter(':') == name) return p.getAttributeValue(i)
        }
        return null
    }

    /** "AB12" -> 27 (índice de columna, base 0). */
    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break
        }
        return n - 1
    }

    private fun String.oneLine(): String = replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
