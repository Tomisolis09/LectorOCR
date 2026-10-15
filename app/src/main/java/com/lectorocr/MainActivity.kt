package com.lectorocr

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.compose.material3.TextButton
import androidx.core.content.IntentCompat
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        Ads.init(applicationContext)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { MaterialTheme { App(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Abrir con…" (VIEW) o "Compartir" (SEND) desde otra app. Con Compartir se extrae el texto enseguida. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val shared = intent.action == Intent.ACTION_SEND
        val uri: Uri? = intent.data
            ?: if (shared) IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) else null
        uri?.let { vm.open(it, autoExtract = shared) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.open(uri)
            tab = 0
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(state.text.toByteArray()) }
            Toast.makeText(context, "Guardado", Toast.LENGTH_SHORT).show()
        }
    }

    // Escáner de documentos de Google (gratis): recorta y endereza la hoja con la cámara
    val scanner = remember {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(10)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
        )
    }
    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                ?.pages?.map { it.imageUri }.orEmpty()
            if (uris.isNotEmpty()) {
                vm.scan(uris)
                tab = 0
            }
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }
    // Al terminar la extracción, mostrar la pestaña de texto y (si toca) el anuncio completo
    LaunchedEffect(state.text) {
        if (state.text.isNotEmpty()) {
            tab = 1
            (context as? Activity)?.let { Ads.maybeShow(it) }
        }
    }
    // Al abrir un archivo nuevo también es un buen momento (si pasaron 5 minutos)
    LaunchedEffect(state.doc) {
        if (state.doc != null) (context as? Activity)?.let { Ads.maybeShow(it) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("¿Borrar y empezar otra lectura?") },
            text = { Text("Se quita el documento y el texto que ves ahora. Si querés conservarlo, antes copialo o guardalo.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clear()
                    tab = 0
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lector OCR") },
                actions = {
                    if (state.doc != null || state.text.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }, enabled = !state.busy) { Text("Borrar") }
                    }
                },
            )
        },
        bottomBar = { BannerAd(Modifier.fillMaxWidth()) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val btnPad = PaddingValues(horizontal = 8.dp)
                Button(
                    onClick = { openLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f),
                    contentPadding = btnPad,
                ) { Text("Abrir", maxLines = 1) }
                Button(
                    onClick = {
                        val activity = context as? Activity
                        if (activity != null) {
                            scanner.getStartScanIntent(activity)
                                .addOnSuccessListener { sender ->
                                    scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
                                }
                                .addOnFailureListener {
                                    Toast.makeText(
                                        context,
                                        "No se pudo abrir el escáner. Revisá tu conexión y Google Play Services.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                        }
                    },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = btnPad,
                ) { Text("Escanear", maxLines = 1) }
                FilledTonalButton(
                    onClick = vm::extractText,
                    enabled = state.doc != null && state.doc !is Doc.Plain && !state.busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = btnPad,
                ) { Text("Extraer texto", maxLines = 1) }
            }

            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.progress, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Documento") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Texto") })
            }

            if (tab == 0) {
                DocumentView(state.doc)
            } else {
                TextView(
                    text = state.text,
                    hasDoc = state.doc != null,
                    translatedTo = state.translatedTo,
                    busy = state.busy,
                    onTranslate = { lang -> vm.translate(lang.tag, lang.name) },
                    onShowOriginal = vm::showOriginal,
                    onCopy = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("texto", state.text))
                        Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show()
                    },
                    onShare = {
                        if (state.text.length > 200_000) {
                            Toast.makeText(
                                context,
                                "Texto muy largo para compartir: usá Guardar .txt",
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, state.text)
                            }
                            context.startActivity(Intent.createChooser(send, "Compartir texto"))
                        }
                    },
                    onSave = {
                        val base = state.doc?.name?.substringBeforeLast('.') ?: "texto"
                        saveLauncher.launch("$base.txt")
                    },
                )
            }
        }
    }
}

// ---------------- Visor ----------------

@Composable
fun DocumentView(doc: Doc?) {
    when (doc) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Abrí un PDF, una imagen, un archivo de texto o una hoja de cálculo para empezar",
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }

        is Doc.Plain -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "${doc.kind}: ${doc.name}\n\nEl contenido está en la pestaña «Texto».",
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }

        is Doc.Img -> Zoomable {
            Image(
                bitmap = doc.bitmap.asImageBitmap(),
                contentDescription = doc.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        is Doc.Pdf -> BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFFE0E0E0))) {
            val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(400, 2000)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items((0 until doc.source.pageCount).toList()) { i ->
                    PdfPage(doc.source, i, widthPx)
                }
            }
        }
    }
}

@Composable
fun PdfPage(source: PdfSource, index: Int, widthPx: Int) {
    val bmp by produceState<Bitmap?>(null, source, index, widthPx) {
        value = runCatching { source.render(index, widthPx) }.getOrNull()
    }
    val current = bmp
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = "Página ${index + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color.White),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
    }
}

/** Zoom con pellizco y arrastre (para imágenes). */
@Composable
fun Zoomable(content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            ),
    ) { content() }
}

// ---------------- Texto extraído ----------------

@Composable
fun TextView(
    text: String,
    hasDoc: Boolean,
    translatedTo: String?,
    busy: Boolean,
    onTranslate: (Lang) -> Unit,
    onShowOriginal: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    if (text.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (hasDoc) "Tocá «Extraer texto» para obtener el texto" else "Todavía no hay texto",
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    } else {
        // Por líneas y en lista perezosa: aguanta textos y planillas grandes
        val lines = remember(text) { text.lines() }
        val folded = remember(lines) { lines.map { fold(it) } }
        var searching by rememberSaveable { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        var current by rememberSaveable { mutableIntStateOf(0) }
        val q = remember(query) { fold(query.trim()) }
        val matches = remember(folded, q) { findMatches(folded, q) }
        val byLine = remember(matches) { matches.groupBy({ it.line }, { it.start }) }
        val currentMatch = matches.getOrNull(current)
        val listState = rememberLazyListState()
        var showData by rememberSaveable { mutableStateOf(false) }
        var showLang by rememberSaveable { mutableStateOf(false) }
        val found by produceState(emptyList<FoundData>(), text) {
            value = withContext(Dispatchers.Default) { DataDetector.detect(text) }
        }

        LaunchedEffect(text) { current = 0 }
        LaunchedEffect(currentMatch) {
            currentMatch?.let { listState.animateScrollToItem(it.line) }
        }

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val pad = PaddingValues(horizontal = 12.dp)
                OutlinedButton(onClick = onCopy, contentPadding = pad) { Text("Copiar") }
                OutlinedButton(onClick = onShare, contentPadding = pad) { Text("Compartir") }
                OutlinedButton(onClick = onSave, contentPadding = pad) { Text("Guardar .txt") }
                IconButton(onClick = { searching = !searching }) {
                    Icon(Icons.Filled.Search, contentDescription = "Buscar en el texto")
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { showLang = true }, enabled = !busy) { Text("Traducir") }
                if (translatedTo != null) {
                    TextButton(onClick = onShowOriginal, enabled = !busy) { Text("Ver original") }
                    Text("Traducido a $translatedTo", style = MaterialTheme.typography.labelMedium)
                }
                if (found.isNotEmpty()) {
                    TextButton(onClick = { showData = true }) { Text("Datos detectados (${found.size})") }
                }
            }
            if (showLang) LanguageDialog(
                onPick = {
                    showLang = false
                    onTranslate(it)
                },
                onClose = { showLang = false },
            )
            if (showData) DataDialog(found) { showData = false }
            if (searching) {
                SearchBar(
                    query = query,
                    onQuery = {
                        query = it
                        current = 0
                    },
                    counter = when {
                        q.isEmpty() -> ""
                        matches.isEmpty() -> "0"
                        else -> "${current + 1}/${matches.size}"
                    },
                    onPrev = {
                        if (matches.isNotEmpty()) current = (current - 1 + matches.size) % matches.size
                    },
                    onNext = {
                        if (matches.isNotEmpty()) current = (current + 1) % matches.size
                    },
                    onClose = {
                        searching = false
                        query = ""
                    },
                )
            }
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState) {
                    itemsIndexed(lines) { i, line ->
                        Text(
                            highlight(line, byLine[i], q.length, currentMatch?.takeIf { it.line == i }?.start)
                        )
                    }
                }
            }
        }
    }
}
