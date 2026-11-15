package com.proasiste.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A type's table: one row per ruling with the columns the rules read from it (date, outcome, judges…). Tapping a row
 * opens its file. "Read from files" reads the project's PDFs on the phone, with no model; the table fills as it goes.
 */
@Composable
internal fun TableScreen(t: UiTable, tables: List<Pair<String, Int>>, actions: UiActions) {
    BackHandler { actions.onCloseTable() }
    Dialog(onDismissRequest = actions.onCloseTable, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MaterialTheme(colorScheme = darkColorScheme(), typography = BrandTypography) {
          CompositionLocalProvider(LocalContentColor provides Mist) {
            Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding().testTag("plantroom.table")) {
                val rulings = t.type == com.proasiste.lite.runtime.RulingColumns.TYPE
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = actions.onCloseTable) { Text(tr("Close", "Cerrar")) }
                    Column(Modifier.weight(1f)) {
                        Text("${t.type} · ${t.rows.size}", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(if (rulings) tr("Read from each ruling by the app's rules, with no AI", "Leída de cada fallo por las reglas de la app, sin IA") else tr("From imported tables and documents", "De tablas importadas y documentos"), color = Muted, fontSize = 12.sp)
                    }
                    if (t.rows.isNotEmpty()) TextButton(onClick = actions.onExportTable, modifier = Modifier.testTag("plantroom.table-export")) { Text(tr("Save as .xlsx", "Guardar .xlsx"), color = Gold) }
                    if (rulings) TextButton(onClick = actions.onFillColumns, enabled = !t.filling, modifier = Modifier.testTag("plantroom.table-fill")) {
                        Text(tr("Read from files", "Leer de los archivos"), color = if (t.filling) Muted else Gold)
                    }
                }
                if (tables.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tables.forEach { (type, n) -> FilterChip(selected = type == t.type, onClick = { actions.onShowTable(type) }, label = { Text("$type · $n", fontSize = 12.sp) }) }
                }
                if (t.filling) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = Gold)
                if (t.note.isNotEmpty()) Text(t.note, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("plantroom.table-note"))
                if (t.rows.isEmpty() && !t.filling) Text(
                    tr("No rulings read yet. Tap “Read from files”: the app reads each indexed PDF on this phone and keeps its date, type, outcome, filer, challenged norms and judges. Nothing is sent.",
                        "Aún no hay fallos leídos. Toca «Leer de los archivos»: la app lee cada PDF indexado en este teléfono y guarda su fecha, tipo, resultado, requirente, normas impugnadas y ministros. No se envía nada."),
                    color = Muted, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
                else {
                    val widths = listOf(130.dp) + t.columns.map { c -> if (c in WIDE) 220.dp else 120.dp }
                    Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                        LazyColumn(Modifier.width(widths.fold(0.dp) { a, b -> a + b } + 32.dp).fillMaxHeight().testTag("plantroom.table-rows"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                            item {
                                Row(Modifier.padding(vertical = 6.dp)) {
                                    (listOf(if (rulings) tr("Ruling", "Fallo") else t.type) + t.columns).forEachIndexed { i, c -> Text(c, Modifier.width(widths[i]).padding(end = 10.dp), color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                                }
                                HorizontalDivider(color = Steel)
                            }
                            items(t.rows, key = { it.name + it.sourceId }) { r ->
                                Row(Modifier.clickable { actions.onOpenTableRow(r) }.padding(vertical = 8.dp)) {
                                    (listOf(r.name) + r.cells).forEachIndexed { i, c ->
                                        Text(c.ifEmpty { "—" }, Modifier.width(widths[i]).padding(end = 10.dp), color = if (c.isEmpty()) Muted else Mist,
                                            fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                HorizontalDivider(color = Steel)
                            }
                        }
                    }
                }
            }
          }
        }
    }
}

/** Columns whose values run long: lists of names and norms, the pending case. */
private val WIDE = setOf("Ministros", "Normas impugnadas", "Gestión", "Requirente", "Tipo", "Disidentes", "Previenen")

/**
 * Confirms how a table goes into the graph before anything is written: which sheet, what each row is (its type), which
 * column names it, and for each column whether it goes in, what it holds (tap to change) and whether it links to another
 * type's names. What would stop the import is listed, and Import waits until there is nothing.
 */
@Composable
internal fun TableImportDialog(t: UiTableImport, actions: UiActions) {
    val m = t.map
    AlertDialog(onDismissRequest = actions.onCancelTableImport, modifier = Modifier.testTag("plantroom.table-import"),
        title = { Text(if (t.replacing) tr("Update ${t.fileName}", "Actualizar ${t.fileName}") else tr("Import ${t.fileName}", "Importar ${t.fileName}"), fontSize = 18.sp) },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                item {
                    if (t.replacing) Text(tr("Imported before: rows that changed update, new rows arrive, rows no longer there go.", "Importada antes: las filas que cambiaron se actualizan, las nuevas llegan y las que ya no están se van."), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                    if (t.sheets.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        t.sheets.forEachIndexed { i, s -> FilterChip(selected = i == t.sheet, onClick = { actions.onTableSheet(i) }, label = { Text(s, fontSize = 12.sp) }) }
                    }
                    OutlinedTextField(m.type, { v -> actions.onTableMap(m.copy(type = v.take(40))) }, singleLine = true,
                        label = { Text(tr("Each row is a…", "Cada fila es un…")) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("plantroom.table-type"))
                    if (t.types.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        t.types.take(12).forEach { type -> AssistChip(onClick = { actions.onTableMap(m.copy(type = type)) }, label = { Text(type, fontSize = 11.sp) }) }
                    }
                    Text(tr("${t.rows} rows · tap a column's kind to change it", "${t.rows} filas · toca el tipo de una columna para cambiarlo"), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                }
                items(m.columns, key = { it.index }) { c ->
                    val isName = c.index == m.nameColumn
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = c.include || isName, enabled = !isName, onCheckedChange = { on -> actions.onTableMap(m.copy(columns = m.columns.map { if (it.index == c.index) it.copy(include = on) else it })) })
                            Text(c.header, Modifier.weight(1f), fontSize = 14.sp, fontWeight = if (isName) FontWeight.Medium else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (isName) Text(tr("names the row", "nombra la fila"), color = Gold, fontSize = 12.sp)
                            else TextButton(onClick = { actions.onTableMap(m.copy(nameColumn = c.index)) }) { Text(tr("Use as name", "Usar como nombre"), fontSize = 11.sp) }
                        }
                        if (!isName && c.include) Row(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val kinds = com.proasiste.lite.graph.ColumnKind.entries
                            AssistChip(onClick = { actions.onTableMap(m.copy(columns = m.columns.map { if (it.index == c.index) it.copy(kind = kinds[(kinds.indexOf(c.kind) + 1) % kinds.size], linkTo = null) else it })) },
                                label = { Text(if (c.linkTo != null) tr("link", "enlace") else kindLabel(c.kind), fontSize = 11.sp) })
                            val targets = listOf<String?>(null) + t.types.filter { it != m.type }
                            if (targets.size > 1) AssistChip(onClick = { actions.onTableMap(m.copy(columns = m.columns.map { if (it.index == c.index) it.copy(linkTo = targets[(targets.indexOf(c.linkTo) + 1) % targets.size]) else it })) },
                                label = { Text(c.linkTo?.let { "→ $it" } ?: tr("no link", "sin enlace"), fontSize = 11.sp) })
                        }
                        t.samples[c.index]?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 48.dp)) }
                    }
                }
                if (t.problems.isNotEmpty()) item {
                    Column(Modifier.padding(top = 8.dp).testTag("plantroom.table-problems")) { t.problems.forEach { Text(it, color = Color(0xFFE57373), fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp)) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = actions.onConfirmTableImport, enabled = t.problems.isEmpty() && m.type.isNotBlank(), modifier = Modifier.testTag("plantroom.table-import-confirm")) {
            Text(if (t.replacing) tr("Update", "Actualizar") else tr("Import ${t.rows} rows", "Importar ${t.rows} filas")) } },
        dismissButton = { TextButton(onClick = actions.onCancelTableImport) { Text(tr("Cancel", "Cancelar")) } },
    )
}

private fun kindLabel(k: com.proasiste.lite.graph.ColumnKind) = when (k) {
    com.proasiste.lite.graph.ColumnKind.TEXT -> tr("text", "texto")
    com.proasiste.lite.graph.ColumnKind.NUMBER -> tr("number", "número")
    com.proasiste.lite.graph.ColumnKind.DATE -> tr("date", "fecha")
    com.proasiste.lite.graph.ColumnKind.BOOL -> tr("yes/no", "sí/no")
    com.proasiste.lite.graph.ColumnKind.LIST -> tr("list", "lista")
}
