package com.lectorocr

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** Leer el texto en voz alta con el motor de voz de Android (gratis y sin internet). */
class Speaker(context: Context) {

    private val app = context.applicationContext
    private var ready = false
    private var failed = false
    private var queued: (() -> Unit)? = null
    private var gen = 0
    private var lastId = ""

    var speaking by mutableStateOf(false)
        private set

    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            queued?.invoke()
        } else {
            failed = true
            toast("Tu celular no tiene un motor de voz instalado")
        }
        queued = null
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            private fun mine(id: String?) = id?.startsWith("$gen-") == true
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (mine(utteranceId) && utteranceId == lastId) speaking = false
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (mine(utteranceId)) speaking = false
            }
        })
    }

    private fun toast(msg: String) = Toast.makeText(app, msg, Toast.LENGTH_LONG).show()

    fun speak(text: String, langTag: String?) {
        if (failed) {
            toast("Tu celular no tiene un motor de voz instalado")
            return
        }
        val run = {
            val locale = langTag?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
            val r = tts.setLanguage(locale)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                toast("Falta la voz de este idioma. Instalala en Ajustes > Idioma > Salida de texto a voz.")
            } else {
                val chunks = chunkText(text.take(MAX_CHARS), 1500).filter { it.text.isNotBlank() }
                if (chunks.isNotEmpty()) {
                    gen++
                    val g = gen
                    lastId = "$g-${chunks.lastIndex}"
                    speaking = true
                    chunks.forEachIndexed { i, c ->
                        tts.speak(
                            c.text,
                            if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                            null,
                            "$g-$i",
                        )
                    }
                }
            }
        }
        if (ready) run() else queued = run
    }

    fun stop() {
        gen++
        queued = null
        if (ready) tts.stop()
        speaking = false
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private companion object {
        const val MAX_CHARS = 200_000
    }
}

@Composable
fun rememberSpeaker(): Speaker {
    val context = LocalContext.current
    val speaker = remember { Speaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    return speaker
}
