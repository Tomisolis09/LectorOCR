package com.lectorocr

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Lang(val name: String, val tag: String)

suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

/**
 * Traductor dentro del celular con ML Kit (gratis, sin claves).
 * Cada idioma se descarga una sola vez (con internet); después funciona sin conexión.
 */
object TranslatorEngine {

    /** Para no tardar demasiado: se traducen los primeros 50.000 caracteres. */
    const val MAX_CHARS = 50_000

    val languages = listOf(
        Lang("Español", TranslateLanguage.SPANISH),
        Lang("Inglés", TranslateLanguage.ENGLISH),
        Lang("Portugués", TranslateLanguage.PORTUGUESE),
        Lang("Francés", TranslateLanguage.FRENCH),
        Lang("Italiano", TranslateLanguage.ITALIAN),
        Lang("Alemán", TranslateLanguage.GERMAN),
        Lang("Japonés", TranslateLanguage.JAPANESE),
        Lang("Chino", TranslateLanguage.CHINESE),
        Lang("Coreano", TranslateLanguage.KOREAN),
        Lang("Ruso", TranslateLanguage.RUSSIAN),
        Lang("Árabe", TranslateLanguage.ARABIC),
    )

    /** Idioma del texto, o null si no se pudo determinar o no es traducible. */
    suspend fun detect(sample: String): String? {
        val client = LanguageIdentification.getClient()
        return try {
            val tag = client.identifyLanguage(sample).awaitTask()
            if (tag == "und") null else TranslateLanguage.fromLanguageTag(tag)
        } finally {
            client.close()
        }
    }

    suspend fun translate(
        text: String,
        source: String,
        target: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ): String {
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
        )
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTask()
            val chunks = chunkText(text)
            val out = ArrayList<String>(chunks.size)
            for ((i, c) in chunks.withIndex()) {
                onProgress(i + 1, chunks.size)
                out.add(if (c.text.isBlank()) c.text else translator.translate(c.text).awaitTask())
            }
            return joinChunks(chunks, out)
        } finally {
            translator.close()
        }
    }
}
