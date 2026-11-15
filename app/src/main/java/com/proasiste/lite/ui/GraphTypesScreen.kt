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
import com.proasiste.lite.graph.GraphSchema

/**
 * What the project's graph extracts: automatic (Chilean rulings read in legal mode, other files free-form), or the
 * project's own node and link types, which can start from a copy of the legal ones.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun GraphTypesSheet(state: UiState, actions: UiActions, onClose: () -> Unit) {
    val locked = state.busy || state.recording
    val schema = state.graphSchema
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Steel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().testTag("plantroom.graph-types")) {
            SheetHeader(tr("Graph types · ${state.buildingId}", "Tipos del grafo · ${state.buildingId}"),
                tr("What Build graph with DeepSeek extracts from this project's PDFs. Facts already in the graph stay until you build it again.",
                    "Lo que Construir el grafo con DeepSeek extrae de los PDF de este proyecto. Los datos que ya están en el grafo se mantienen hasta que lo construyas de nuevo."))
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                ModeRow(tr("Automatic", "Automático"), tr("Chilean court rulings are read in legal mode: rule-based facts first, then fixed legal types. Other files get free-form facts. Best for rulings.",
                    "Los fallos chilenos se leen en modo legal: primero datos por reglas, luego tipos legales fijos. Los demás archivos reciben datos libres. Lo mejor para fallos."), !schema.custom, !locked) { actions.onSchemaMode(GraphSchema.AUTO) }
                ModeRow(tr("My types", "Mis tipos"), tr("Every PDF is read with the types and links below, and nothing else. Rulings skip the rule-based facts.",
                    "Cada PDF se lee con los tipos y vínculos de abajo, y nada más. Los fallos no reciben los datos por reglas."), schema.custom, !locked) { actions.onSchemaMode(GraphSchema.CUSTOM) }
            }
            if (schema.custom) {
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), color = Line)
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Eyebrow(tr("NODE TYPES", "TIPOS DE NODO")) }
                        TextButton(onClick = actions.onLegalPreset, enabled = !locked, modifier = Modifier.testTag("plantroom.legal-preset")) { Text(tr("Start from Chilean rulings", "Partir de fallos chilenos"), fontSize = 12.sp) }
                    }
                    if (schema.nodes.isEmpty()) Text(tr("No types yet.", "Aún sin tipos."), color = Muted, fontSize = 12.sp)
                    schema.nodes.forEach { n ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(n.name, color = Gold, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                if (n.description.isNotBlank()) Text(n.description, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                            }
                            TextButton(onClick = { actions.onRemoveNodeType(n.name) }, enabled = !locked) { Text(tr("Remove", "Quitar"), color = Warning, fontSize = 12.sp) }
                        }
                        // Columns: values each node of this type has (a contract's amount, a ruling's date), read from the files by the model.
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            n.columns.forEach { c -> InputChip(selected = false, onClick = { actions.onRemoveColumn(n.name, c.name) }, enabled = !locked,
                                label = { Text("${c.name} · ${columnKind(c.kind)}  ✕", fontSize = 11.sp) }) }
                            var col by remember(n.name) { mutableStateOf("") }; var kind by remember(n.name) { mutableStateOf(com.proasiste.lite.graph.ColumnKind.TEXT) }
                            OutlinedTextField(col, { col = it.take(40) }, singleLine = true, enabled = !locked, placeholder = { Text(tr("Column", "Columna"), fontSize = 11.sp) },
                                modifier = Modifier.width(130.dp).testTag("plantroom.column-name-${n.name}"), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Mist))
                            val kinds = com.proasiste.lite.graph.ColumnKind.entries
                            AssistChip(onClick = { kind = kinds[(kinds.indexOf(kind) + 1) % kinds.size] }, enabled = !locked, label = { Text(columnKind(kind), fontSize = 11.sp) })
                            TextButton(onClick = { actions.onAddColumn(n.name, col, kind); col = "" }, enabled = !locked && col.isNotBlank(), modifier = Modifier.testTag("plantroom.column-add-${n.name}")) { Text(tr("Add column", "Agregar columna"), fontSize = 12.sp) }
                        }
                    }
                    var name by remember { mutableStateOf("") }; var description by remember { mutableStateOf("") }
                    Field(name, { name = it.take(40) }, tr("Type", "Tipo"), "Contrato", "plantroom.node-name", !locked)
                    Field(description, { description = it.take(200) }, tr("What it is", "Qué es"), tr("A contract between two parties", "Un contrato entre dos partes"), "plantroom.node-description", !locked)
                    TextButton(onClick = { actions.onAddNodeType(name, description); name = ""; description = "" }, enabled = !locked && name.isNotBlank(), modifier = Modifier.testTag("plantroom.node-add")) { Text(tr("Add type", "Agregar tipo")) }
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), color = Line)
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Eyebrow(tr("LINKS", "VÍNCULOS"))
                    Spacer(Modifier.height(6.dp))
                    if (schema.links.isEmpty()) Text(tr("No links yet.", "Aún sin vínculos."), color = Muted, fontSize = 12.sp)
                    schema.links.forEach { l ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${l.name}  ·  ${l.from.sorted().joinToString(" / ")} → ${l.to.sorted().joinToString(" / ")}", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                if (l.description.isNotBlank()) Text(l.description, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                            }
                            TextButton(onClick = { actions.onRemoveLinkType(l.name) }, enabled = !locked) { Text(tr("Remove", "Quitar"), color = Warning, fontSize = 12.sp) }
                        }
                    }
                    if (schema.nodes.isEmpty()) Text(tr("Add node types first; a link runs between them.", "Primero agrega tipos de nodo; un vínculo va entre ellos."), color = Muted, fontSize = 12.sp)
                    else {
                        var name by remember { mutableStateOf("") }; var description by remember { mutableStateOf("") }
                        var from by remember { mutableStateOf(setOf<String>()) }; var to by remember { mutableStateOf(setOf<String>()) }
                        Field(name, { name = it.take(40) }, tr("Link", "Vínculo"), "FIRMA", "plantroom.link-name", !locked)
                        Field(description, { description = it.take(300) }, tr("What it means", "Qué significa"), tr("The party that signs the contract", "La parte que firma el contrato"), "plantroom.link-description", !locked)
                        Text(tr("From", "Desde"), color = Mist, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        TypeChips(schema.nodes.map { it.name }, from, !locked, "from") { t -> from = if (t in from) from - t else from + t }
                        Text(tr("To", "Hacia"), color = Mist, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        TypeChips(schema.nodes.map { it.name }, to, !locked, "to") { t -> to = if (t in to) to - t else to + t }
                        TextButton(onClick = { actions.onAddLinkType(name, description, from, to); name = ""; description = ""; from = emptySet(); to = emptySet() },
                            enabled = !locked && name.isNotBlank() && from.isNotEmpty() && to.isNotEmpty(), modifier = Modifier.testTag("plantroom.link-add")) { Text(tr("Add link", "Agregar vínculo")) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(if (schema.complete()) tr("Ready: the next graph build uses these ${schema.nodes.size} types and ${schema.links.size} links.", "Listo: la próxima construcción del grafo usa estos ${schema.nodes.size} tipos y ${schema.links.size} vínculos.")
                        else tr("Add at least one type and one link before building the graph.", "Agrega al menos un tipo y un vínculo antes de construir el grafo."),
                        color = if (schema.complete()) Muted else Warning, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

private fun columnKind(k: com.proasiste.lite.graph.ColumnKind) = when (k) {
    com.proasiste.lite.graph.ColumnKind.TEXT -> tr("text", "texto")
    com.proasiste.lite.graph.ColumnKind.NUMBER -> tr("number", "número")
    com.proasiste.lite.graph.ColumnKind.DATE -> tr("date", "fecha")
    com.proasiste.lite.graph.ColumnKind.BOOL -> tr("yes/no", "sí/no")
    com.proasiste.lite.graph.ColumnKind.LIST -> tr("list", "lista")
}

@Composable
private fun ModeRow(title: String, detail: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    // The whole row selects the mode, not only the round button.
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled, modifier = Modifier.testTag("plantroom.mode.$title"))
        Column(Modifier.padding(top = 10.dp)) {
            Text(title, fontSize = 15.sp, color = if (selected) Gold else Mist, fontWeight = FontWeight.Medium)
            Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, hint: String, tag: String, enabled: Boolean) {
    OutlinedTextField(value = value, onValueChange = onChange, singleLine = true, enabled = enabled, label = { Text(label) }, placeholder = { Text(hint, color = Muted, fontSize = 13.sp) },
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag(tag), shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Line))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeChips(types: List<String>, chosen: Set<String>, enabled: Boolean, side: String, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        types.forEach { t ->
            FilterChip(selected = t in chosen, onClick = { onToggle(t) }, enabled = enabled, label = { Text(t, fontSize = 12.sp) }, modifier = Modifier.testTag("plantroom.$side.$t"),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Raised, selectedLabelColor = Gold))
        }
    }
}
