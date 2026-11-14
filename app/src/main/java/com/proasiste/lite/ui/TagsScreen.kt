package com.proasiste.lite.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A file's tags on its detail sheet: tap a tag to remove it; type to add one, with the project's tags suggested. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceTags(tags: List<String>, known: List<String>, enabled: Boolean, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    Column(Modifier.testTag("plantroom.source-tags")) {
        Eyebrow(tr("TAGS", "ETIQUETAS"))
        Spacer(Modifier.height(8.dp))
        if (tags.isEmpty()) Text(tr("No tags yet.", "Aún sin etiquetas."), color = Muted, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            tags.forEach { t ->
                InputChip(selected = true, onClick = { onRemove(t) }, enabled = enabled, label = { Text(t, fontSize = 12.sp) },
                    trailingIcon = { Text("✕", fontSize = 11.sp, color = Muted) }, modifier = Modifier.testTag("plantroom.source-tag.$t"),
                    colors = InputChipDefaults.inputChipColors(selectedContainerColor = Raised, selectedLabelColor = Gold))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = draft, onValueChange = { draft = it.take(60) }, singleLine = true, enabled = enabled,
                placeholder = { Text(tr("Add a tag", "Agregar etiqueta"), color = Muted, fontSize = 13.sp) },
                modifier = Modifier.weight(1f).testTag("plantroom.new-tag"), shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Line))
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { onAdd(draft); draft = "" }, enabled = enabled && draft.isNotBlank()) { Text(tr("Add", "Agregar")) }
        }
        val suggestions = known.filter { it !in tags && (draft.isBlank() || it.contains(draft.trim(), ignoreCase = true)) }.take(12)
        if (suggestions.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            suggestions.forEach { s -> SuggestionChip(onClick = { onAdd(s); draft = "" }, enabled = enabled, label = { Text("+ $s", fontSize = 12.sp) }) }
        }
        Text(tr("Tags you add or remove here stay that way: rules and AI never change them.", "Las etiquetas que agregas o quitas aquí se mantienen: las reglas y la IA no las cambian."),
            color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** The project's tag rules and the tags AI may choose, with the buttons that run them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagsSheet(state: UiState, actions: UiActions, onClose: () -> Unit) {
    val locked = state.busy || state.recording
    val project = state.projects.firstOrNull { it.id == state.buildingId }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Steel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().testTag("plantroom.tags-sheet")) {
            SheetHeader(tr("Tags · ${state.buildingId}", "Etiquetas · ${state.buildingId}"),
                tr("Rules tag every file at once, on this phone, and new imports as they arrive. AI tags what a file name can't tell.", "Las reglas etiquetan todos los archivos de una vez, en este teléfono, y las importaciones nuevas al llegar. La IA etiqueta lo que el nombre no dice."))

            // ---- Rules ----
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Eyebrow(tr("RULES · ON THIS PHONE", "REGLAS · EN ESTE TELÉFONO"))
                Spacer(Modifier.height(8.dp))
                if (state.tagRules.isEmpty()) Text(tr("No rules yet.", "Aún sin reglas."), color = Muted, fontSize = 12.sp)
                state.tagRules.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ruleText(r), fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { actions.onDeleteTagRule(r.id) }, enabled = !locked) { Text(tr("Delete", "Eliminar"), color = Warning, fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val has = state.tagRules.map { it.kind }.toSet()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if ("carpeta" !in has) OutlinedButton(onClick = { actions.onAddTagRule("carpeta", "", "") }, enabled = !locked, shape = RoundedCornerShape(12.dp), modifier = Modifier.testTag("plantroom.rule-folder")) { Text(tr("+ Folder names", "+ Nombres de carpeta"), fontSize = 12.sp) }
                    if ("año" !in has) OutlinedButton(onClick = { actions.onAddTagRule("año", "", "") }, enabled = !locked, shape = RoundedCornerShape(12.dp), modifier = Modifier.testTag("plantroom.rule-year")) { Text(tr("+ Year in file name", "+ Año en el nombre"), fontSize = 12.sp) }
                }
                Spacer(Modifier.height(12.dp))
                var words by remember { mutableStateOf("") }
                var tag by remember { mutableStateOf("") }
                Text(tr("When a file name contains…", "Cuando el nombre del archivo contiene…"), fontSize = 13.sp, color = Mist)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = words, onValueChange = { words = it.take(60) }, singleLine = true, enabled = !locked, placeholder = { Text("STC", color = Muted, fontSize = 13.sp) },
                        modifier = Modifier.weight(1f).testTag("plantroom.rule-words"), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Line))
                    Text("  →  ", color = Muted)
                    OutlinedTextField(value = tag, onValueChange = { tag = it.take(60) }, singleLine = true, enabled = !locked, placeholder = { Text(tr("tag", "etiqueta"), color = Muted, fontSize = 13.sp) },
                        modifier = Modifier.weight(1f).testTag("plantroom.rule-tag"), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Line))
                }
                Row {
                    TextButton(onClick = { actions.onAddTagRule("nombre", words, tag); words = ""; tag = "" }, enabled = !locked && words.isNotBlank() && tag.isNotBlank(), modifier = Modifier.testTag("plantroom.rule-add")) { Text(tr("Add rule", "Agregar regla")) }
                    Spacer(Modifier.weight(1f))
                    if (state.tagRules.isNotEmpty()) TextButton(onClick = actions.onApplyTagRules, enabled = !locked) { Text(tr("Run rules again", "Aplicar reglas de nuevo")) }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = Line)

            // ---- AI ----
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Eyebrow(tr("TAGS AI CHOOSES FROM", "ETIQUETAS QUE ELIGE LA IA"))
                Spacer(Modifier.height(8.dp))
                Text(tr("AI reads each indexed PDF's first pages and picks which of these apply. It never invents other tags.", "La IA lee las primeras páginas de cada PDF indexado y elige cuáles de estas aplican. Nunca inventa otras etiquetas."),
                    color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(8.dp))
                state.aiTags.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontSize = 14.sp, color = Gold, fontWeight = FontWeight.Medium)
                            Text(d.description, fontSize = 12.sp, color = Muted, lineHeight = 17.sp)
                        }
                        TextButton(onClick = { actions.onDefineTag(d.name, "") }, enabled = !locked) { Text(tr("Remove", "Quitar"), color = Warning, fontSize = 12.sp) }
                    }
                }
                var name by remember { mutableStateOf("") }
                var description by remember { mutableStateOf("") }
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, enabled = !locked, label = { Text(tr("Tag", "Etiqueta")) }, placeholder = { Text("penal", color = Muted, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("plantroom.ai-tag-name"), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Line))
                OutlinedTextField(value = description, onValueChange = { description = it.take(200) }, enabled = !locked, label = { Text(tr("When it applies", "Cuándo aplica")) },
                    placeholder = { Text(tr("The case is about criminal law", "El caso trata de derecho penal"), color = Muted, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("plantroom.ai-tag-description"), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Line))
                TextButton(onClick = { actions.onDefineTag(name, description); name = ""; description = "" }, enabled = !locked && name.isNotBlank() && description.isNotBlank(), modifier = Modifier.testTag("plantroom.ai-tag-add")) { Text(tr("Add to AI's list", "Agregar a la lista de la IA")) }
                Spacer(Modifier.height(6.dp))
                val model = project?.answerModel.orEmpty()
                Text(when {
                    project?.incognito == true -> tr("Incognito project: AI tagging is off and nothing leaves this phone.", "Proyecto incógnito: el etiquetado con IA está desactivado y nada sale de este teléfono.")
                    model.isEmpty() -> tr("AI tagging uses the project's OpenRouter model. Choose one in settings, under Who writes answers.", "El etiquetado con IA usa el modelo de OpenRouter del proyecto. Elige uno en ajustes, en Quién escribe las respuestas.")
                    else -> tr("Uses ${state.catalog.all.firstOrNull { it.id == model }?.name ?: model.substringAfter('/')} through OpenRouter: each PDF's first pages and this list are sent. Runs in the background; tags you set by hand are kept.",
                        "Usa ${state.catalog.all.firstOrNull { it.id == model }?.name ?: model.substringAfter('/')} a través de OpenRouter: se envían las primeras páginas de cada PDF y esta lista. Se ejecuta en segundo plano; las etiquetas que pusiste a mano se mantienen.")
                }, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { actions.onTagWithAi(); onClose() }, enabled = !locked && state.indexing == null && state.aiTags.isNotEmpty() && project?.incognito != true && model.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.testTag("plantroom.tag-with-ai")) { Text(tr("Tag all indexed PDFs with AI", "Etiquetar con IA todos los PDF indexados")) }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

private fun ruleText(r: UiTagRule) = when (r.kind) {
    "carpeta" -> tr("Each subfolder's name becomes a tag", "El nombre de cada subcarpeta es una etiqueta")
    "año" -> tr("The year in the file name becomes a tag (2154-11 → 2011)", "El año del nombre del archivo es una etiqueta (2154-11 → 2011)")
    else -> tr("Name contains “${r.pattern}” → ${r.tag}", "El nombre contiene “${r.pattern}” → ${r.tag}")
}
