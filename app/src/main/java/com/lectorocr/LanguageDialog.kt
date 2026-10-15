package com.lectorocr

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Elegir a qué idioma traducir. El idioma de origen se detecta solo. */
@Composable
fun LanguageDialog(onPick: (Lang) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Traducir a…") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(TranslatorEngine.languages) { lang ->
                    TextButton(onClick = { onPick(lang) }, modifier = Modifier.fillMaxWidth()) {
                        Text(lang.name, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
    )
}
