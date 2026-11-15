package com.proasiste.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A full-screen page with a Close button and a title, in the app's dark style. */
@Composable
private fun Page(title: String, subtitle: String, onClose: () -> Unit, tag: String, content: @Composable ColumnScope.() -> Unit) {
    BackHandler { onClose() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MaterialTheme(colorScheme = darkColorScheme(), typography = BrandTypography) {
            CompositionLocalProvider(LocalContentColor provides Mist) {
                Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding().testTag(tag)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onClose) { Text(tr("Close", "Cerrar")) }
                        Column(Modifier.weight(1f)) {
                            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                    content()
                }
            }
        }
    }
}

/**
 * The project's history: every answered question, newest first, searchable. Tapping one shows it again in Search with
 * its sources; each can be deleted, or all of them. Incognito projects keep none.
 */
@Composable
internal fun HistoryScreen(entries: List<UiHistory>, query: String, actions: UiActions) {
    var confirmAll by remember { mutableStateOf(false) }
    Page(tr("History · ${entries.size}", "Historial · ${entries.size}"), tr("Questions asked in this project, kept on this phone. Incognito projects keep none.", "Preguntas hechas en este proyecto, guardadas en este teléfono. Los proyectos incógnito no guardan ninguna."), actions.onCloseHistory, "plantroom.history") {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, actions.onSearchHistory, singleLine = true, placeholder = { Text(tr("Find in history", "Buscar en el historial")) }, modifier = Modifier.weight(1f).testTag("plantroom.history-search"))
            if (entries.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text(tr("Delete all", "Borrar todo"), color = Warning, fontSize = 12.sp) }
        }
        if (entries.isEmpty()) Text(if (query.isEmpty()) tr("Nothing yet. Every answer you get is kept here.", "Aún nada. Cada respuesta que obtengas se guarda aquí.") else tr("Nothing matches.", "Nada coincide."), color = Muted, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            items(entries, key = { it.id }) { e ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Steel, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable { actions.onReopenHistory(e.id) }.padding(14.dp).testTag("plantroom.history-entry")) {
                    Text(e.question, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(e.answer, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(listOfNotNull(e.date, e.writer, tr("${e.sources} sources", "${e.sources} fuentes"), tr("${e.opened} opened", "${e.opened} abiertas").takeIf { e.opened > 0 }, tr("saved as a report", "guardada como informe").takeIf { e.report }).joinToString(" · "),
                            color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { actions.onDeleteHistory(e.id) }) { Text(tr("Delete", "Borrar"), color = Warning, fontSize = 11.sp) }
                    }
                }
            }
        }
    }
    if (confirmAll) AlertDialog(onDismissRequest = { confirmAll = false },
        title = { Text(tr("Delete this project's history?", "¿Borrar el historial de este proyecto?")) },
        text = { Text(tr("Every question and answer kept here is deleted. Reports saved from them stay.", "Se borran todas las preguntas y respuestas guardadas aquí. Los informes guardados a partir de ellas se mantienen.")) },
        confirmButton = { TextButton(onClick = { confirmAll = false; actions.onClearHistory() }) { Text(tr("Delete all", "Borrar todo"), color = Warning) } },
        dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(tr("Cancel", "Cancelar")) } })
}

/**
 * The person's notes for answers: preferences ("answer in Spanish") and standing facts ("Minister X is also spelled Y"),
 * for this project or every project. They go with each question as instructions and are never cited. An incognito
 * project reads the notes for every project but can't add to them.
 */
@Composable
internal fun MemoryScreen(notes: List<UiNote>, incognito: Boolean, actions: UiActions) {
    Page(tr("Notes for answers · ${notes.size}", "Notas para las respuestas · ${notes.size}"),
        tr("They go with every question, as your instructions; answers never cite them. With an OpenRouter model they're sent with the question.", "Acompañan cada pregunta, como tus instrucciones; las respuestas nunca las citan. Con un modelo de OpenRouter se envían con la pregunta."),
        actions.onCloseMemory, "plantroom.memory") {
        var text by remember { mutableStateOf("") }; var fact by remember { mutableStateOf(false) }; var all by remember { mutableStateOf(false) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(text, { text = it.take(300) }, placeholder = { Text(tr("Answer in Spanish, in at most five lines", "Responde en español, en máximo cinco líneas")) }, modifier = Modifier.fillMaxWidth().testTag("plantroom.note-text"))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !fact, onClick = { fact = false }, label = { Text(tr("How to answer", "Cómo responder"), fontSize = 12.sp) })
                FilterChip(selected = fact, onClick = { fact = true }, label = { Text(tr("A fact to keep", "Un dato a recordar"), fontSize = 12.sp) })
                FilterChip(selected = all, onClick = { all = !all }, enabled = !incognito, label = { Text(tr("All projects", "Todos los proyectos"), fontSize = 12.sp) })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { actions.onAddNote(text, fact, all); text = "" }, enabled = text.isNotBlank(), modifier = Modifier.testTag("plantroom.note-add")) { Text(tr("Add", "Agregar"), color = Gold) }
            }
            if (incognito) Text(tr("Incognito project: notes for every project are used here but can't be added.", "Proyecto incógnito: aquí se usan las notas para todos los proyectos, pero no se pueden agregar."), color = Muted, fontSize = 11.sp)
        }
        if (notes.isEmpty()) Text(tr("No notes yet. Add one, or say “Remember” when an answer offers to keep an instruction from your question.", "Aún no hay notas. Agrega una, o acepta cuando una respuesta ofrezca guardar una instrucción de tu pregunta."), color = Muted, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            items(notes, key = { it.id }) { n ->
                var editing by remember(n.id) { mutableStateOf(false) }; var draft by remember(n.id) { mutableStateOf(n.text) }
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).background(Steel, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(12.dp).testTag("plantroom.note")) {
                    if (editing) OutlinedTextField(draft, { draft = it.take(300) }, modifier = Modifier.fillMaxWidth())
                    else Text(n.text, fontSize = 14.sp, lineHeight = 19.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(listOf(if (n.fact) tr("fact", "dato") else tr("how to answer", "cómo responder"), if (n.allProjects) tr("all projects", "todos los proyectos") else tr("this project", "este proyecto")).joinToString(" · "),
                            color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        if (editing) TextButton(onClick = { actions.onEditNote(n.id, draft); editing = false }) { Text(tr("Save", "Guardar"), color = Gold, fontSize = 11.sp) }
                        else TextButton(onClick = { editing = true }, enabled = !(incognito && n.allProjects)) { Text(tr("Edit", "Editar"), fontSize = 11.sp) }
                        TextButton(onClick = { actions.onDeleteNote(n.id) }, enabled = !(incognito && n.allProjects)) { Text(tr("Delete", "Borrar"), color = Warning, fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}
