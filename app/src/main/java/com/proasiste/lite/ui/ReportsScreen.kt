package com.proasiste.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun day(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
/** The one-tap decisions, as stored and as shown. */
private val DECISIONS = listOf("relevante" to ("Relevant" to "Relevante"), "no relevante" to ("Not relevant" to "No relevante"),
    "aceptado" to ("Accepted" to "Aceptado"), "rechazado" to ("Rejected" to "Rechazado"), "por revisar" to ("To check" to "Por revisar"))
internal fun decisionLabel(value: String) = DECISIONS.firstOrNull { it.first == value }?.second?.let { tr(it.first, it.second) } ?: value

/** The project's reports, and the project-wide exports. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportsSheet(state: UiState, actions: UiActions, onClose: () -> Unit) {
    val locked = state.busy || state.recording
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Steel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("plantroom.reports")) {
            SheetHeader(tr("Reports · ${state.buildingId}", "Informes · ${state.buildingId}"), tr("Answers you saved and notes you wrote, with their sources and your decisions.", "Respuestas que guardaste y notas que escribiste, con sus fuentes y tus decisiones."))
            Column(Modifier.padding(horizontal = 20.dp)) {
                if (state.reports.isEmpty()) Text(tr("No reports yet. Save an answer with “Save as report”, or write a note.", "Aún no hay informes. Guarda una respuesta con «Guardar como informe», o escribe una nota."), color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(4.dp))
                state.reports.forEach { r ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Raised, RoundedCornerShape(12.dp)).clickable(enabled = !locked) { actions.onOpenReport(r.id); onClose() }
                        .padding(horizontal = 14.dp, vertical = 11.dp).testTag("plantroom.report.${r.id}")) {
                        Text(r.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(day(r.updated), byLine(r.model), tr("${r.cited.size} sources", "${r.cited.size} fuentes"), r.decision?.let { decisionLabel(it.value) }).joinToString("  ·  "), color = Muted, fontSize = 11.sp)
                    }
                }
                TextButton(onClick = { actions.onNewNote(); onClose() }, enabled = !locked, modifier = Modifier.testTag("plantroom.new-note")) { Text(tr("+ Write a note", "+ Escribir una nota")) }
            }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = Line)
            Column(Modifier.padding(horizontal = 24.dp)) {
                Eyebrow(tr("SAVE TO A FILE", "GUARDAR EN UN ARCHIVO"))
                Text(tr("You choose where: a folder on the phone or Drive. Nothing is sent anywhere else.", "Tú eliges dónde: una carpeta del teléfono o Drive. Nada se envía a otro lugar."), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                TextButton(onClick = actions.onExportDecisions, enabled = !locked, modifier = Modifier.testTag("plantroom.export-decisions")) { Text(tr("All decisions · CSV", "Todas las decisiones · CSV")) }
                TextButton(onClick = { actions.onExportFacts("csv") }, enabled = !locked, modifier = Modifier.testTag("plantroom.export-facts-csv")) { Text(tr("Graph facts · CSV (spreadsheet)", "Datos del grafo · CSV (planilla)")) }
                TextButton(onClick = { actions.onExportFacts("json") }, enabled = !locked, modifier = Modifier.testTag("plantroom.export-facts-json")) { Text(tr("Graph facts · JSON (for tools; re-importable)", "Datos del grafo · JSON (para herramientas; reimportable)")) }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

private fun byLine(model: String) = when (model) { "" -> tr("note", "nota"); "graph" -> tr("from the graph", "del grafo"); else -> model }

/** One report, full screen: edit its title and text, open its sources, decide on it, save it as a file, delete it. */
@Composable
internal fun ReportScreen(report: UiReport, state: UiState, actions: UiActions, onOpenSource: (UiItem) -> Unit) {
    val locked = state.busy || state.recording
    var title by remember(report.id) { mutableStateOf(report.title) }
    var body by remember(report.id) { mutableStateOf(report.body) }
    var confirmDelete by remember { mutableStateOf(false) }
    val changed = title != report.title || body != report.body
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (changed) actions.onSaveReport(report.id, title, body); actions.onCloseReport() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        MaterialTheme(colorScheme = darkColorScheme(), typography = BrandTypography) {
            Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp).verticalScroll(rememberScrollState()).testTag("plantroom.report-screen")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (changed) actions.onSaveReport(report.id, title, body); actions.onCloseReport() }) { Text(tr("Close", "Cerrar")) }
                    Spacer(Modifier.weight(1f))
                    if (changed) TextButton(onClick = { actions.onSaveReport(report.id, title, body) }, modifier = Modifier.testTag("plantroom.report-save")) { Text(tr("Save", "Guardar"), color = Gold) }
                }
                Eyebrow(listOf(byLine(report.model), day(report.created)).joinToString("  ·  ").uppercase(), Gold)
                OutlinedTextField(value = title, onValueChange = { title = it.take(200) }, enabled = !locked, textStyle = LocalTextStyle.current.copy(fontSize = 20.sp, color = Mist),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("plantroom.report-title"), shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Line))
                if (report.question.isNotBlank()) Text(tr("Question: ", "Pregunta: ") + report.question, color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp))
                OutlinedTextField(value = body, onValueChange = { body = it }, enabled = !locked, minLines = 4,
                    placeholder = { Text(tr("Write here", "Escribe aquí"), color = Muted) },
                    textStyle = LocalTextStyle.current.copy(fontFamily = NotoSerif, fontSize = 15.sp, lineHeight = 23.sp, color = Mist),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("plantroom.report-body"), shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Line))
                if (report.cited.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp)); Eyebrow(tr("SOURCES · TAP TO OPEN HIGHLIGHTED", "FUENTES · TOCA PARA ABRIR RESALTADA"))
                    report.cited.forEachIndexed { i, s ->
                        Column(Modifier.fillMaxWidth().clickable { onOpenSource(s) }.padding(vertical = 7.dp)) {
                            Text("[${i + 1}] ${s.title} · ${s.sourceLocation}", color = Gold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("“${s.excerpt.trim()}”", fontFamily = NotoSerif, color = Mist, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                DecisionRow(report.decision, report.history, !locked) { value, note -> actions.onDecide("informe", report.id.toString(), value, note) }
                Spacer(Modifier.height(16.dp)); Eyebrow(tr("SAVE TO A FILE", "GUARDAR EN UN ARCHIVO"))
                Row {
                    TextButton(onClick = { if (changed) actions.onSaveReport(report.id, title, body); actions.onExportReport(report.id, "pdf") }, enabled = !locked, modifier = Modifier.testTag("plantroom.report-pdf")) { Text("PDF") }
                    TextButton(onClick = { if (changed) actions.onSaveReport(report.id, title, body); actions.onExportReport(report.id, "md") }, enabled = !locked, modifier = Modifier.testTag("plantroom.report-md")) { Text("Markdown") }
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { confirmDelete = true }, enabled = !locked) { Text(tr("Delete report", "Eliminar informe"), color = Warning) }
                Spacer(Modifier.height(24.dp))
            }
            if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(tr("Delete this report?", "¿Eliminar este informe?")) },
                text = { Text(tr("The report and the decisions taken on it are deleted. The files it cites stay.", "Se eliminan el informe y las decisiones tomadas sobre él. Los archivos que cita se mantienen.")) },
                confirmButton = { TextButton(onClick = { confirmDelete = false; actions.onDeleteReport(report.id) }) { Text(tr("Delete", "Eliminar"), color = Warning) } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel", "Cancelar")) } })
        }
    }
}

/** The current decision, one-tap choices with an optional note, and the history. Every decision is kept. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DecisionRow(current: UiDecision?, history: List<UiDecision>, enabled: Boolean, onDecide: (String, String) -> Unit) {
    var note by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }
    Column(Modifier.testTag("plantroom.decision")) {
        Eyebrow(tr("YOUR DECISION", "TU DECISIÓN"))
        Text(current?.let { "${decisionLabel(it.value)} · ${day(it.created)}" + if (it.note.isNotBlank()) " — ${it.note}" else "" } ?: tr("None yet.", "Ninguna todavía."),
            color = if (current != null) Gold else Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            DECISIONS.forEach { (value, label) ->
                AssistChip(onClick = { onDecide(value, note); note = "" }, enabled = enabled, label = { Text(tr(label.first, label.second), fontSize = 12.sp) }, modifier = Modifier.testTag("plantroom.decide.$value"))
            }
        }
        OutlinedTextField(value = note, onValueChange = { note = it.take(1000) }, enabled = enabled, placeholder = { Text(tr("Note (optional), then tap a decision", "Nota (opcional), luego toca una decisión"), color = Muted, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("plantroom.decision-note"), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Line))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = custom, onValueChange = { custom = it.take(80) }, enabled = enabled, singleLine = true, placeholder = { Text(tr("Or your own words", "O tus propias palabras"), color = Muted, fontSize = 12.sp) },
                modifier = Modifier.weight(1f).padding(top = 6.dp), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Line))
            TextButton(onClick = { onDecide(custom, note); custom = ""; note = "" }, enabled = enabled && custom.isNotBlank()) { Text(tr("Decide", "Decidir")) }
        }
        if (history.size > 1) {
            Text(tr("Earlier", "Antes"), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            history.drop(1).take(10).forEach { d -> Text("${day(d.created)} · ${decisionLabel(d.value)}" + if (d.note.isNotBlank()) " — ${d.note}" else "", color = Muted, fontSize = 11.sp, lineHeight = 16.sp) }
        }
    }
}
