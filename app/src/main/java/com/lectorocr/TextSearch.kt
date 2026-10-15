package com.lectorocr

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.text.Normalizer

/** Una coincidencia: número de línea y posición dentro de la línea. */
data class Match(val line: Int, val start: Int)

/**
 * Pasa a minúsculas y quita tildes, manteniendo el MISMO largo que el original
 * (carácter por carácter), para que las posiciones coincidan al resaltar.
 */
fun fold(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) {
        if (c.code < 128) {
            sb.append(c.lowercaseChar())
        } else {
            val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            sb.append((if (d.isNotEmpty()) d[0] else c).lowercaseChar())
        }
    }
    return sb.toString()
}

/** Busca [query] (ya normalizada con [fold]) en las líneas ya normalizadas. */
fun findMatches(folded: List<String>, query: String, limit: Int = 10_000): List<Match> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<Match>()
    for (i in folded.indices) {
        var idx = folded[i].indexOf(query)
        while (idx >= 0) {
            out.add(Match(i, idx))
            if (out.size >= limit) return out
            idx = folded[i].indexOf(query, idx + query.length)
        }
    }
    return out
}

/** Línea con las coincidencias resaltadas; la actual en naranja y el resto en amarillo. */
fun highlight(line: String, starts: List<Int>?, length: Int, currentStart: Int?): AnnotatedString {
    if (starts.isNullOrEmpty() || length <= 0) return AnnotatedString(line.ifEmpty { " " })
    return buildAnnotatedString {
        append(line)
        for (s in starts) {
            val e = minOf(s + length, line.length)
            if (s < e) {
                addStyle(
                    SpanStyle(
                        background = if (s == currentStart) Color(0xFFFFB84D) else Color(0xFFFFE9A8),
                        color = Color.Black,
                    ),
                    s,
                    e,
                )
            }
        }
    }
}

@Composable
fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    counter: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text("Buscar palabra") },
            modifier = Modifier.weight(1f).focusRequester(focus),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                onNext()
                keyboard?.hide()
            }),
        )
        Text(
            counter,
            modifier = Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
        IconButton(onClick = onPrev) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Coincidencia anterior")
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Coincidencia siguiente")
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Cerrar búsqueda")
        }
    }
}
