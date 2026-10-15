package com.lectorocr

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Lista de datos detectados: cada uno se copia con un toque y, según el tipo, se puede llamar, escribir o abrir. */
@Composable
fun DataDialog(found: List<FoundData>, onClose: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Cerrar") } },
        title = { Text("Datos detectados") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(found) { f ->
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(f.kind.label, style = MaterialTheme.typography.labelMedium)
                        Text(f.value, style = MaterialTheme.typography.bodyLarge)
                        Row {
                            TextButton(onClick = { copyToClipboard(context, f.value) }) { Text("Copiar") }
                            actionFor(f)?.let { (label, intent) ->
                                TextButton(onClick = { launch(context, intent) }) { Text(label) }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
    )
}

private fun actionFor(f: FoundData): Pair<String, Intent>? = when (f.kind) {
    DataKind.PHONE ->
        "Llamar" to Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + DataDetector.dialable(f.value)))

    DataKind.EMAIL ->
        "Escribir" to Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + f.value))

    DataKind.LINK -> {
        val url = if (f.value.startsWith("www.", ignoreCase = true)) "https://${f.value}" else f.value
        "Abrir" to Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    else -> null
}

private fun copyToClipboard(context: Context, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("dato", value))
    Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show()
}

private fun launch(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No hay una app para abrir esto", Toast.LENGTH_SHORT).show()
    }
}
