package com.lectorocr

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Abre un PDF y dibuja sus páginas como imágenes (PdfRenderer solo admite una página abierta a la vez). */
class PdfSource(resolver: ContentResolver, uri: Uri) : AutoCloseable {

    private val pfd: ParcelFileDescriptor =
        resolver.openFileDescriptor(uri, "r") ?: error("No se pudo abrir el PDF")
    private val renderer = PdfRenderer(pfd)
    private val lock = Mutex()
    @Volatile private var closed = false

    val pageCount: Int = renderer.pageCount

    suspend fun render(index: Int, targetWidth: Int): Bitmap = withContext(Dispatchers.IO) {
        lock.withLock {
            check(!closed) { "PDF cerrado" }
            renderer.openPage(index).use { page ->
                val scale = targetWidth.toFloat() / page.width
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(targetWidth, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}
