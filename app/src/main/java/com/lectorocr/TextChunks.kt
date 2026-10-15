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
