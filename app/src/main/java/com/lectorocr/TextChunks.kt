package com.lectorocr

/** Un fragmento de texto para traducir y el separador que va después al rearmar el resultado. */
data class Chunk(val text: String, val sep: String)

/**
 * Parte un texto largo en fragmentos de hasta [max] caracteres, respetando las líneas.
 * Las líneas muy largas se cortan preferentemente al final de una oración.
 */
fun chunkText(text: String, max: Int = 800): List<Chunk> {
    val out = ArrayList<Chunk>()
    val cur = StringBuilder()
    var lines = 0

    fun flush() {
        if (lines > 0) out.add(Chunk(cur.toString(), "\n"))
        cur.setLength(0)
        lines = 0
    }

    for (line in text.split('\n')) {
        if (line.length > max) {
            flush()
            var rest = line
            while (rest.length > max) {
                val cut = cutPoint(rest, max)
                out.add(Chunk(rest.substring(0, cut).trimEnd(), " "))
                rest = rest.substring(cut).trimStart()
            }
            out.add(Chunk(rest, "\n"))
        } else {
            if (lines > 0 && cur.length + 1 + line.length > max) flush()
            if (lines > 0) cur.append('\n')
            cur.append(line)
            lines++
        }
    }
    flush()
    return out
}

private fun cutPoint(s: String, max: Int): Int {
    for (p in listOf(". ", "? ", "! ", "; ")) {
        val i = s.lastIndexOf(p, max - 1)
        if (i >= max / 2) return i + 1
    }
    val sp = s.lastIndexOf(' ', max - 1)
    if (sp >= max / 2) return sp
    return max
}

/** Rearma el texto traducido con los mismos separadores del original. */
fun joinChunks(chunks: List<Chunk>, translated: List<String>): String {
    val sb = StringBuilder()
    for (i in chunks.indices) sb.append(translated[i]).append(chunks[i].sep)
    return sb.toString().trimEnd('\n', ' ')
}

// ---------- Para traducir: rearmar párrafos ----------

private val LIST_ITEM = Regex("^([-•*·▪–]|\\d{1,3}[.)]|[a-zA-Z][.)])\\s+\\S")
private val COLUMNS = Regex("\\S {3,}\\S")
private const val SENTENCE_END = ".!?:;…”»\""

private fun isStandalone(line: String): Boolean =
    '\t' in line || COLUMNS.containsMatchIn(line.trim()) || line.trim().startsWith("— Página")

/**
 * El texto del OCR y de los PDF viene cortado en renglones a mitad de oración, y eso arruina la
 * traducción (se traduce cada renglón suelto, sin contexto). Acá se vuelven a unir los renglones
 * de un mismo párrafo (y las palabras cortadas con guion) antes de traducir, y se dejan aparte
 * los títulos, listas, filas de tablas y renglones en blanco.
 */
fun reflowChunks(text: String, max: Int = 500): List<Chunk> {
    val out = ArrayList<Chunk>()
    val buf = StringBuilder()
    var bufIsList = false
    var lastLen = 0

    fun addParagraph(p: String) {
        var rest = p
        while (rest.length > max) {
            val cut = cutPoint(rest, max)
            out.add(Chunk(rest.substring(0, cut).trimEnd(), " "))
            rest = rest.substring(cut).trimStart()
        }
        out.add(Chunk(rest, "\n"))
    }

    fun flush() {
        if (buf.isNotEmpty()) addParagraph(buf.toString())
        buf.setLength(0)
        bufIsList = false
        lastLen = 0
    }

    for (raw in text.split('\n')) {
        val t = raw.trim()
        if (t.isEmpty()) {
            flush()
            out.add(Chunk("", "\n"))
            continue
        }
        if (isStandalone(raw)) {
            flush()
            addParagraph(t)
            continue
        }
        val isList = LIST_ITEM.containsMatchIn(t)
        val joins = buf.isNotEmpty() && !isList && buf.last() !in SENTENCE_END &&
            (t[0].isLowerCase() || (!bufIsList && lastLen >= 45))
        if (joins) {
            if (buf.length > 1 && buf.last() == '-' && buf[buf.length - 2].isLetter() && t[0].isLowerCase()) {
                buf.setLength(buf.length - 1)
            } else {
                buf.append(' ')
            }
            buf.append(t)
        } else {
            flush()
            buf.append(t)
            bufIsList = isList
        }
        lastLen = t.length
    }
    flush()
    return out
}
