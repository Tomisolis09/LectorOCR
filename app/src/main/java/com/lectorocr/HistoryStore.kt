package com.lectorocr

import java.io.File

data class HistoryItem(val id: Long, val title: String, val preview: String)

/**
 * Historial de lecturas guardado en el celular (un archivo por lectura, sin base de datos).
 * Formato del archivo: línea 1 = título, línea 2 = vista previa, el resto = texto.
 */
class HistoryStore(private val dir: File, private val maxItems: Int = 30) {

    init { dir.mkdirs() }

    private fun file(id: Long) = File(dir, "$id.txt")

    @Synchronized
    fun save(title: String, text: String) {
        if (text.isBlank()) return
        val body = text.take(MAX_TEXT)
        val cleanTitle = title.replace('\n', ' ').trim().ifEmpty { "Lectura" }
        val preview = body.trimStart().take(90).replace(Regex("\\s+"), " ")
        // Si es la misma lectura guardada otra vez, se reemplaza en vez de repetirla
        list().firstOrNull { it.title == cleanTitle && it.preview == preview }?.let { delete(it.id) }
        var id = System.currentTimeMillis()
        while (file(id).exists()) id++
        file(id).writeText("$cleanTitle\n$preview\n$body")
        list().drop(maxItems).forEach { delete(it.id) }
    }

    /** Más recientes primero. */
    @Synchronized
    fun list(): List<HistoryItem> =
        (dir.listFiles() ?: emptyArray()).mapNotNull { f ->
            val id = f.nameWithoutExtension.toLongOrNull() ?: return@mapNotNull null
            runCatching {
                f.bufferedReader().use { r -> HistoryItem(id, r.readLine().orEmpty(), r.readLine().orEmpty()) }
            }.getOrNull()
        }.sortedByDescending { it.id }

    @Synchronized
    fun load(id: Long): String? =
        runCatching { file(id).readText().split("\n", limit = 3).getOrNull(2) }.getOrNull()

    @Synchronized
    fun delete(id: Long) { file(id).delete() }

    @Synchronized
    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private companion object {
        const val MAX_TEXT = 200_000
    }
}
