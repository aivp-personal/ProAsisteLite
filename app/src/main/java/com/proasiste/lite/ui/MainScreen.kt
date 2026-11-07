package com.proasiste.lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.proasiste.lite.data.Document
import com.proasiste.lite.data.DocumentStatus
import com.proasiste.lite.data.Project
import com.proasiste.lite.embedding.GemmaEmbedder
import com.proasiste.lite.search.SearchHit

class ScreenActions(
    val createProject: (String) -> Unit,
    val selectProject: (Project) -> Unit,
    val deleteProject: (Project) -> Unit,
    val importModel: () -> Unit,
    val addPdfs: () -> Unit,
    val deleteDocument: (Document) -> Unit,
    val openDocument: (Document) -> Unit,
    val index: () -> Unit,
    val stopIndexing: () -> Unit,
    val search: (String) -> Unit,
    val dismissError: () -> Unit,
)

@Composable
fun MainScreen(state: UiState, actions: ScreenActions) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Header(state, actions) }
                if (!state.modelReady) item { ModelCard(state, actions) }
                val project = state.project
                if (project == null) {
                    item { NewProject(state, actions) }
                } else {
                    if (state.modelReady) item { SearchBox(state, actions) }
                    if (state.searched) {
                        if (state.hits.isEmpty()) item { Text("No results in ${project.name}.") }
                        items(state.hits, key = { "hit-${it.passageId}" }) { hit ->
                            HitCard(hit) { state.documents.firstOrNull { it.id == hit.documentId }?.let(actions.openDocument) }
                        }
                        item { HorizontalDivider() }
                    }
                    item { DocumentControls(state, actions) }
                    items(state.documents, key = { "doc-${it.id}" }) { document ->
                        DocumentRow(document, state.busy, actions)
                    }
                }
            }
        }
        state.error?.let { message ->
            AlertDialog(
                onDismissRequest = actions.dismissError,
                text = { Text(message) },
                confirmButton = { TextButton(onClick = actions.dismissError) { Text("OK") } },
            )
        }
    }
}

@Composable
private fun Header(state: UiState, actions: ScreenActions) {
    var picking by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 12.dp)) {
        Text("ProAsisteLite", style = MaterialTheme.typography.titleLarge)
        state.project?.let { project ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { picking = true }, enabled = !state.busy) {
                        Text("${project.name} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                        state.projects.forEach { other ->
                            DropdownMenuItem(
                                text = { Text(other.name) },
                                onClick = {
                                    picking = false
                                    actions.selectProject(other)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("New project") },
                            onClick = {
                                picking = false
                                creating = true
                            },
                        )
                    }
                }
                TextButton(onClick = { deleting = true }, enabled = !state.busy) { Text("Delete") }
            }
            if (deleting) {
                ConfirmDelete(
                    title = "Delete ${project.name}?",
                    body = "This permanently removes its ${state.documents.size} documents and index from the app. Your original files elsewhere aren't touched.",
                    onConfirm = { actions.deleteProject(project) },
                    onDismiss = { deleting = false },
                )
            }
        }
        if (creating) {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { creating = false },
                title = { Text("New project") },
                text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            actions.createProject(name)
                            creating = false
                        },
                        enabled = name.isNotBlank(),
                    ) { Text("Create") }
                },
                dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
            )
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        if (state.status.isNotEmpty()) Text(state.status, style = MaterialTheme.typography.bodySmall)
        if (state.graphStatus.isNotEmpty()) Text(state.graphStatus, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ModelCard(state: UiState, actions: ScreenActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Embedding model needed", fontWeight = FontWeight.Medium)
            Text("Search needs ${GemmaEmbedder.MODEL_FILENAME} (485 MB) on this phone. It's checked before use.")
            Button(onClick = actions.importModel, enabled = !state.busy) { Text("Choose model file") }
        }
    }
}

@Composable
private fun NewProject(state: UiState, actions: ScreenActions) {
    var name by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Create a project to hold your documents.")
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Project name") })
        Button(onClick = { actions.createProject(name) }, enabled = !state.busy && name.isNotBlank()) { Text("Create project") }
    }
}

@Composable
private fun SearchBox(state: UiState, actions: ScreenActions) {
    var query by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("Search ${state.indexedCount} indexed documents") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.search(query) }),
        )
        Button(onClick = { actions.search(query) }, enabled = !state.busy && query.isNotBlank()) { Text("Search") }
    }
}

@Composable
private fun HitCard(hit: SearchHit, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp)) {
            Text("${hit.documentTitle} · page ${hit.page}", fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(hit.text, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DocumentControls(state: UiState, actions: ScreenActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = actions.addPdfs, enabled = !state.busy) { Text("Add PDFs") }
        when {
            state.indexing -> Button(onClick = actions.stopIndexing) { Text("Stop") }
            state.pendingCount > 0 -> Button(onClick = actions.index, enabled = !state.busy && state.modelReady) {
                Text("Index ${state.pendingCount}")
            }
        }
    }
    Text(
        "Up to ${com.proasiste.lite.importing.DocumentImporter.MAX_BYTES / 1_000_000} MB and " +
            "${com.proasiste.lite.importing.DocumentImporter.MAX_PAGES} pages per PDF, with selectable text.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun DocumentRow(document: Document, busy: Boolean, actions: ScreenActions) {
    var deleting by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable { actions.openDocument(document) }) {
            Text(document.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val state = when (document.status) {
                DocumentStatus.PENDING -> "Not indexed"
                DocumentStatus.INDEXED -> "Indexed"
                DocumentStatus.FAILED -> document.error ?: "Failed"
            }
            Text("${document.pageCount} pages · ${document.byteSize / 1_000_000} MB · $state", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { deleting = true }, enabled = !busy) { Text("Delete") }
    }
    if (deleting) {
        ConfirmDelete(
            title = "Delete ${document.title}?",
            body = "This permanently removes the app's copy, its index and its graph links. Your original file elsewhere isn't touched.",
            onConfirm = { actions.deleteDocument(document) },
            onDismiss = { deleting = false },
        )
    }
}

@Composable
private fun ConfirmDelete(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
