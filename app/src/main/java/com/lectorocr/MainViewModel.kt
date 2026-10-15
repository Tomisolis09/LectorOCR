package com.lectorocr

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

sealed interface Doc {
    val name: String

    class Pdf(val source: PdfSource, override val name: String, val uri: Uri) : Doc
    class Img(val bitmap: Bitmap, override val name: String) : Doc

    /** Archivo de texto u hoja de cálculo: el contenido ya se extrajo al abrirlo. */
    class Plain(override val name: String, val kind: String) : Doc
}

data class UiState(
    val doc: Doc? = null,
    val text: String = "",
    val busy: Boolean = false,
    val progress: String = "",
    val error: String? = null,
    /** Texto original, mientras se muestra una traducción. */
    val original: String? = null,
    /** Nombre del idioma al que está traducido el texto que se ve. */
    val translatedTo: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val ODS_MIME = "application/vnd.oasis.opendocument.spreadsheet"
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
    }

    private class Loaded(val doc: Doc, val text: String = "")

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val resolver get() = getApplication<Application>().contentResolver

    fun clearError() = _state.update { it.copy(error = null) }

    // ---------- Abrir archivo ----------

    fun open(uri: Uri, autoExtract: Boolean = false) {
        viewModelScope.launch {
            try {
                val name = displayName(uri)
                val mime = resolver.getType(uri).orEmpty()
                val loaded = withContext(Dispatchers.IO) { load(uri, name, mime) }
                (_state.value.doc as? Doc.Pdf)?.source?.close()
                _state.value = UiState(doc = loaded.doc, text = loaded.text)
                // Archivos recibidos con "Compartir": se extrae el texto directamente
                if (autoExtract && (loaded.doc is Doc.Img || loaded.doc is Doc.Pdf)) extractText()
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(error = "El archivo es demasiado grande para abrirlo") }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "No se pudo abrir el archivo") }
            }
        }
    }

    private fun load(uri: Uri, name: String, mime: String): Loaded {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime == "application/pdf" || ext == "pdf" ->
                Loaded(Doc.Pdf(PdfSource(resolver, uri), name, uri))

            mime.startsWith("image/") || ext in IMAGE_EXT ->
                Loaded(Doc.Img(loadBitmap(uri), name))

            ext == "xls" || ext == "xlsb" ->
                error("El formato .$ext no es compatible. Guardalo como .xlsx o .csv y probá de nuevo.")

            ext == "xlsx" || ext == "xlsm" || mime == XLSX_MIME ->
                plain(name, "Hoja de cálculo", SheetReader.read(getApplication(), uri, isOds = false))

            ext == "ods" || mime == ODS_MIME ->
                plain(name, "Hoja de cálculo", SheetReader.read(getApplication(), uri, isOds = true))

            else -> plain(name, kindOf(ext), TextExtractor.extract(resolver, uri, name, mime))
        }
    }

    private fun kindOf(ext: String): String = when (ext) {
        "docx", "odt" -> "Documento"
        "pptx", "odp" -> "Presentación"
        else -> "Archivo de texto"
    }

    private fun plain(name: String, kind: String, text: String): Loaded {
        if (text.isBlank()) error("No se encontró contenido de texto en el archivo")
        return Loaded(Doc.Plain(name, kind), text)
    }

    private fun displayName(uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "archivo"

    private fun loadBitmap(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val biggest = max(info.size.width, info.size.height)
            if (biggest > 3000) {
                val f = 3000f / biggest
                decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
            }
        }
    }

    // ---------- Borrar / empezar otra lectura ----------

    fun clear() {
        if (_state.value.busy) return
        (_state.value.doc as? Doc.Pdf)?.source?.close()
        _state.value = UiState()
    }

    // ---------- Traducir ----------

    fun translate(targetTag: String, targetName: String) {
        val current = _state.value
        val source = current.original ?: current.text
        if (source.isBlank() || current.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, progress = "Detectando el idioma…") }
            try {
                val sample = source.take(2000)
                val sourceTag = TranslatorEngine.detect(sample)
                    ?: error("No se pudo detectar el idioma del texto")
                if (sourceTag == targetTag) error("El texto ya está en ese idioma")

                _state.update { it.copy(progress = "Preparando el idioma (la primera vez se descarga)…") }
                val limited = source.take(TranslatorEngine.MAX_CHARS)
                var result = TranslatorEngine.translate(limited, sourceTag, targetTag) { done, total ->
                    _state.update { it.copy(progress = "Traduciendo $done de $total") }
                }
                if (source.length > TranslatorEngine.MAX_CHARS) {
                    result += "\n\n[… se tradujeron los primeros ${TranslatorEngine.MAX_CHARS} caracteres …]"
                }
                _state.update {
                    it.copy(
                        text = result,
                        original = source,
                        translatedTo = targetName,
                        busy = false,
                        progress = "",
                    )
                }
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(busy = false, progress = "", error = "El texto es demasiado grande para traducirlo") }
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                val friendly = when {
                    msg.startsWith("No se pudo detectar") || msg.startsWith("El texto ya está") -> msg
                    else -> "No se pudo traducir. La primera vez de cada idioma hace falta internet para descargarlo."
                }
                _state.update { it.copy(busy = false, progress = "", error = friendly) }
            }
        }
    }

    fun showOriginal() {
        val original = _state.value.original ?: return
        _state.update { it.copy(text = original, original = null, translatedTo = null) }
    }

    // ---------- Escaneo con la cámara ----------

    /** Recibe las páginas escaneadas (imágenes), muestra la primera y extrae el texto de todas. */
    fun scan(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, progress = "Leyendo el escaneo…") }
            try {
                val first = withContext(Dispatchers.IO) { loadBitmap(uris.first()) }
                (_state.value.doc as? Doc.Pdf)?.source?.close()
                _state.update {
                    it.copy(doc = Doc.Img(first, "Escaneo de cámara"), text = "", original = null, translatedTo = null)
                }

                val sb = StringBuilder()
                for ((i, u) in uris.withIndex()) {
                    _state.update { it.copy(progress = "Página ${i + 1} de ${uris.size}") }
                    val bmp = if (i == 0) first else withContext(Dispatchers.IO) { loadBitmap(u) }
                    val t = OcrEngine.recognize(bmp).trim()
                    if (uris.size > 1) sb.append("— Página ${i + 1} —\n")
                    sb.append(t).append("\n\n")
                }
                val text = sb.toString().trim()
                _state.update {
                    it.copy(
                        text = text,
                        busy = false,
                        progress = "",
                        error = if (text.isBlank()) "No se detectó texto en el escaneo" else null,
                    )
                }
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(busy = false, progress = "", error = "El escaneo es demasiado grande") }
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, progress = "", error = e.message ?: "No se pudo leer el escaneo")
                }
            }
        }
    }

    // ---------- Extraer texto (imágenes y PDF) ----------

    fun extractText() {
        val doc = _state.value.doc ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, progress = "Procesando…") }
            try {
                val text = when (doc) {
                    is Doc.Img -> OcrEngine.recognize(doc.bitmap)
                    is Doc.Pdf -> extractPdf(doc)
                    is Doc.Plain -> _state.value.text
                }
                _state.update {
                    it.copy(text = text.trim(), busy = false, progress = "", original = null, translatedTo = null)
                }
            } catch (e: OutOfMemoryError) {
                _state.update {
                    it.copy(busy = false, progress = "", error = "El archivo es demasiado grande para procesarlo")
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, progress = "", error = e.message ?: "Error al extraer el texto")
                }
            }
        }
    }

    /**
     * Por cada página: si el PDF ya trae texto se lee directo (gratis y offline);
     * si la página está vacía (escaneada) se dibuja y se manda al OCR.
     */
    private suspend fun extractPdf(doc: Doc.Pdf): String {
        val n = doc.source.pageCount
        val sb = StringBuilder()
        val pdf = withContext(Dispatchers.IO) {
            resolver.openInputStream(doc.uri)!!.use { PDDocument.load(it) }
        }
        try {
            val stripper = PDFTextStripper()
            for (i in 0 until n) {
                _state.update { it.copy(progress = "Página ${i + 1} de $n") }
                var t = withContext(Dispatchers.IO) {
                    stripper.startPage = i + 1
                    stripper.endPage = i + 1
                    stripper.getText(pdf)
                }.trim()
                if (t.length < 15) {
                    val bmp = doc.source.render(i, 2000)
                    t = OcrEngine.recognize(bmp).trim()
                    bmp.recycle()
                }
                if (n > 1) sb.append("— Página ${i + 1} —\n")
                sb.append(t).append("\n\n")
            }
        } finally {
            pdf.close()
        }
        return sb.toString()
    }

    override fun onCleared() {
        (_state.value.doc as? Doc.Pdf)?.source?.close()
    }
}
