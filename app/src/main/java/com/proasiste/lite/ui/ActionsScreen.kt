package com.proasiste.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DESCRIPTIONS = mapOf(
    "summary" to ("Key points with their pages" to "Puntos clave con sus páginas"),
    "tables" to ("Every table, as a table, with its page" to "Cada tabla, como tabla, con su página"),
    "classify" to ("Kind of document, subject, outcome" to "Tipo de documento, tema, resultado"),
    "dates" to ("Every date and party, with their role" to "Cada fecha y parte, con su rol"),
    "doclang" to ("A .doclang file for each PDF, in a folder you pick · on this phone, free" to "Un archivo .doclang por cada PDF, en una carpeta que elijas · en este teléfono, gratis"),
)

/** Runs an action on the project's indexed PDFs or a tag's, and keeps the user's own actions (skills). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionsSheet(state: UiState, actions: UiActions, onClose: () -> Unit) {
    val locked = state.busy || state.recording
    var tag by remember { mutableStateOf<String?>(null) }
    val project = state.projects.firstOrNull { it.id == state.buildingId }
    val model = project?.answerModel.orEmpty()
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Steel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().testTag("plantroom.actions")) {
            SheetHeader(tr("Actions · ${state.buildingId}", "Acciones · ${state.buildingId}"),
                tr("Run on many files in the background. Each run's results go into one report, in Reports.", "Se ejecutan sobre muchos archivos en segundo plano. Los resultados de cada ejecución van a un informe, en Informes."))
            Column(Modifier.padding(horizontal = 24.dp)) {
                Eyebrow(tr("ON", "SOBRE"))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilterChip(selected = tag == null, onClick = { tag = null }, label = { Text(tr("Whole project", "Todo el proyecto"), fontSize = 12.sp) }, modifier = Modifier.testTag("plantroom.scope.all"),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Raised, selectedLabelColor = Gold))
                    state.tagCounts.forEach { t ->
                        FilterChip(selected = tag == t.name, onClick = { tag = t.name }, label = { Text("#${t.name} · ${t.count}", fontSize = 12.sp) }, modifier = Modifier.testTag("plantroom.scope.${t.name}"),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Raised, selectedLabelColor = Gold))
                    }
                }
                Text(when {
                    project?.incognito == true -> tr("Incognito project: only Convert to DocLang runs; nothing leaves this phone.", "Proyecto incógnito: solo se ejecuta Convertir a DocLang; nada sale de este teléfono.")
                    model.isEmpty() -> tr("Actions that use AI need an OpenRouter model for this project (settings, Who writes answers). Convert to DocLang needs none.", "Las acciones con IA necesitan un modelo de OpenRouter para este proyecto (ajustes, Quién escribe las respuestas). Convertir a DocLang no lo necesita.")
                    else -> tr("AI actions use ${state.catalog.all.firstOrNull { it.id == model }?.name ?: model.substringAfter('/')} through OpenRouter: each PDF's text (up to about 60 pages) and the instructions are sent.", "Las acciones con IA usan ${state.catalog.all.firstOrNull { it.id == model }?.name ?: model.substringAfter('/')} a través de OpenRouter: se envía el texto de cada PDF (hasta unas 60 páginas) y las instrucciones.")
                }, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(horizontal = 20.dp)) {
                state.actions.forEach { a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Raised, RoundedCornerShape(12.dp)).padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Mist)
                            val d = DESCRIPTIONS[a.key]
                            Text(if (d != null) tr(d.first, d.second) else a.instructions, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (a.skillId > 0) TextButton(onClick = { actions.onDeleteSkill(a.skillId) }, enabled = !locked) { Text(tr("Delete", "Eliminar"), color = Warning, fontSize = 12.sp) }
                        Button(onClick = { actions.onRunAction(a.key, tag); onClose() }, enabled = !locked && state.indexing == null && (a.local || (project?.incognito != true && model.isNotEmpty())),
                            shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.testTag("plantroom.run.${a.key}")) { Text(tr("Run", "Ejecutar"), fontSize = 12.sp) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 10.dp), color = Line)
            Column(Modifier.padding(horizontal = 24.dp)) {
                Eyebrow(tr("NEW SKILL · YOUR OWN ACTION, FOR EVERY PROJECT", "NUEVA HABILIDAD · TU PROPIA ACCIÓN, PARA TODOS LOS PROYECTOS"))
                var name by remember { mutableStateOf("") }; var instructions by remember { mutableStateOf("") }
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, enabled = !locked, label = { Text(tr("Name", "Nombre")) },
                    placeholder = { Text(tr("Votes and dissents", "Votos y disidencias"), color = Muted, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("plantroom.skill-name"), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Line))
                OutlinedTextField(value = instructions, onValueChange = { instructions = it.take(4000) }, enabled = !locked, minLines = 3, label = { Text(tr("What to do with each file", "Qué hacer con cada archivo")) },
                    placeholder = { Text(tr("List who voted against and why, citing pages.", "Indica quiénes votaron en contra y por qué, citando páginas."), color = Muted, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("plantroom.skill-instructions"), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Line))
                TextButton(onClick = { actions.onSaveSkill(name, instructions); name = ""; instructions = "" }, enabled = !locked && name.isNotBlank() && instructions.isNotBlank(), modifier = Modifier.testTag("plantroom.skill-save")) { Text(tr("Save skill", "Guardar habilidad")) }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}
