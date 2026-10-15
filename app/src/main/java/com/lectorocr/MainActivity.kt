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
import androidx.compose.foundation.text.selection.SelectionContainer
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
        if (savedInstanceState == null) intent?.data?.let { vm.open(it) }
        setContent { MaterialTheme { App(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { vm.open(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

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

    Scaffold(
        topBar = { TopAppBar(title = { Text("Lector OCR") }) },
        bottomBar = { BannerAd(Modifier.fillMaxWidth()) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { openLauncher.launch(arrayOf("*/*")) }) {
                    Text("Abrir archivo")
                }
                FilledTonalButton(
                    onClick = vm::extractText,
                    enabled = state.doc != null && state.doc !is Doc.Plain &&!state.busy,
                ) { Text("Extraer texto") }
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
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onCopy) { Text("Copiar") }
                OutlinedButton(onClick = onShare) { Text("Compartir") }
                OutlinedButton(onClick = onSave) { Text("Guardar .txt") }
            }
            // Por líneas y en lista perezosa: aguanta textos y planillas grandes
            val lines = remember(text) { text.lines() }
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(lines) { line -> Text(line.ifEmpty { " " }) }
                }
            }
        }
    }
}
