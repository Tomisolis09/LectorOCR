package com.lectorocr

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lecturas anteriores guardadas en el celular: tocar una para volver a abrirla. */
@Composable
fun HistoryDialog(
    items: List<HistoryItem>,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    var sure by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Historial") },
        text = {
            if (items.isEmpty()) {
                Text("Todavía no hay lecturas guardadas. Cada vez que extraigas, escanees o traduzcas, se guarda acá.")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(items, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(item.id) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(fmt.format(Date(item.id)), style = MaterialTheme.typography.labelSmall)
                                Text(item.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { onDelete(item.id) }) { Text("Borrar") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cerrar") } },
        dismissButton = {
            if (items.isNotEmpty()) {
                TextButton(onClick = {
                    if (sure) {
                        sure = false
                        onClearAll()
                    } else {
                        sure = true
                    }
                }) { Text(if (sure) "¿Seguro? Tocá otra vez" else "Vaciar todo") }
            }
        },
    )
}
