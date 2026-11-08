package com.proasiste.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The steward's review of graph facts: the ones the checks held back, grouped by document and reason, each with the
 * fact as the model wrote it, why it was held, and four decisions. Approving or correcting puts the fact into the
 * graph; rejecting records it so it isn't proposed again. The same screen draws a sample of kept facts, since the
 * errors that got through matter more than the ones that were caught.
 */
@Composable
internal fun ReviewScreen(review: UiReview, actions: UiActions) {
    var editing by remember { mutableStateOf<UiReviewItem?>(null) }
    BackHandler { actions.onCloseReview() }
    Dialog(onDismissRequest = actions.onCloseReview, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MaterialTheme(colorScheme = darkColorScheme(), typography = BrandTypography) {
            Column(Modifier.fillMaxSize().background(Color(0xFF0A0A0A)).statusBarsPadding().navigationBarsPadding().testTag("plantroom.review")) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = actions.onCloseReview) { Text(tr("Close", "Cerrar")) }
                    Spacer(Modifier.weight(1f))
                    if (!review.sample) TextButton(onClick = actions.onSampleReview, modifier = Modifier.testTag("plantroom.review-sample")) { Text(tr("Spot check", "Muestra"), color = Gold) }
                }
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(if (review.sample) tr("Spot check of kept facts", "Muestra de datos aceptados") else tr("Graph review", "Revisión del grafo"), color = Mist, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(if (review.sample) tr("${review.items.size} facts the checks let through, drawn at random. Reject any that the page doesn't support.", "${review.items.size} datos que pasaron las revisiones, al azar. Rechaza los que la página no respalde.")
                        else tr("${review.items.size} facts the checks held back. Open the page, then approve, correct or reject.", "${review.items.size} datos retenidos por las revisiones. Abre la página y aprueba, corrige o rechaza."),
                        color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Spacer(Modifier.height(8.dp))
                if (review.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(2.dp), color = Gold, trackColor = Line)
                else if (review.items.isEmpty()) Text(tr("Nothing pending.", "Nada pendiente."), color = Muted, modifier = Modifier.padding(20.dp))
                val grouped = review.items.groupBy { it.title }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    grouped.forEach { (title, items) ->
                        item { Text(title, color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp)) }
                        items(items, key = { it.id }) { item ->
                            ReviewCard(item, review.sample, onOpen = { actions.onOpenReviewPage(item) }, onApprove = { actions.onApproveReview(item) },
                                onCorrectPage = { page -> actions.onCorrectReview(item.copy(page = page)) }, onEdit = { editing = item }, onReject = { actions.onRejectReview(item) })
                        }
                    }
                }
            }
        }
    }
    editing?.let { item -> EditFactDialog(item, onDismiss = { editing = null }, onSave = { edited -> editing = null; actions.onCorrectReview(edited) }) }
}

@Composable
private fun ReviewCard(item: UiReviewItem, sample: Boolean, onOpen: () -> Unit, onApprove: () -> Unit, onCorrectPage: (Int) -> Unit, onEdit: () -> Unit, onReject: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).background(Steel, RoundedCornerShape(14.dp)).padding(14.dp)) {
        // Stacked, since a node name can be a whole line long: from, then the relation, then to.
        Chip(item.fromType, item.from)
        Text("↓ ${item.relation}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp))
        Chip(item.toType, item.to)
        Spacer(Modifier.height(8.dp))
        Text(item.statement, color = Mist, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = NotoSerif)
        Spacer(Modifier.height(8.dp))
        if (!sample) Text(reasonText(item), color = Warning, fontSize = 12.sp, lineHeight = 17.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpen, contentPadding = PaddingValues(0.dp)) { Text(tr("Page ${item.page}", "Página ${item.page}"), color = Gold, fontSize = 13.sp) }
            Spacer(Modifier.weight(1f))
            Text(item.reason.takeIf { sample }?.let { "" } ?: "", fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!sample) {
                OutlinedButton(onClick = onApprove, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.testTag("plantroom.review-approve")) { Text(tr("Approve", "Aprobar"), fontSize = 12.sp) }
                item.suggestedPage?.let { page ->
                    OutlinedButton(onClick = { onCorrectPage(page) }, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(tr("Page $page", "Página $page"), fontSize = 12.sp) }
                }
                OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(tr("Edit", "Editar"), fontSize = 12.sp) }
            }
            OutlinedButton(onClick = onReject, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.testTag("plantroom.review-reject")) { Text(tr("Reject", "Rechazar"), fontSize = 12.sp, color = Warning) }
        }
    }
}

@Composable
private fun Chip(type: String, name: String) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF1C1F24), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(type, color = Gold, fontSize = 10.sp); Spacer(Modifier.width(6.dp)); Text(name, color = Mist, fontSize = 12.sp, maxLines = 3, modifier = Modifier.weight(1f))
    }
}

/** The checker's reason, said in the user's language. */
private fun reasonText(item: UiReviewItem): String {
    val r = item.reason
    return when {
        r.startsWith("numbers not on page") -> tr("Numbers in the statement aren't on page ${item.page}: ${r.substringAfter(':').trim()}", "Hay números de la oración que no están en la página ${item.page}: ${r.substringAfter(':').trim()}")
        r.contains("another body") -> tr("The page gives this article to another body of law, or doesn't mention it.", "La página atribuye este artículo a otro cuerpo legal, o no lo menciona.")
        r.startsWith("relation") -> tr("The link runs the wrong way for this relation (${item.fromType} → ${item.toType}).", "El enlace va al revés para esta relación (${item.fromType} → ${item.toType}).")
        r.contains("not on page") -> tr("A name in the fact isn't on page ${item.page}.", "Un nombre del dato no está en la página ${item.page}.")
        r.startsWith("unknown ruling") -> tr("Names a ruling or minister the text never mentions.", "Nombra un fallo o ministro que el texto no menciona.")
        else -> r
    }
}

@Composable
private fun EditFactDialog(item: UiReviewItem, onDismiss: () -> Unit, onSave: (UiReviewItem) -> Unit) {
    var fromType by remember { mutableStateOf(item.fromType) }; var from by remember { mutableStateOf(item.from) }
    var relation by remember { mutableStateOf(item.relation) }
    var toType by remember { mutableStateOf(item.toType) }; var to by remember { mutableStateOf(item.to) }
    var page by remember { mutableStateOf(item.page.toString()) }
    MaterialTheme(colorScheme = darkColorScheme(), typography = BrandTypography) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("Correct the fact", "Corregir el dato")) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(fromType, { fromType = it }, label = { Text(tr("From type", "Tipo origen")) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(page, { page = it.filter(Char::isDigit) }, label = { Text(tr("Page", "Página")) }, singleLine = true, modifier = Modifier.width(90.dp))
                }
                OutlinedTextField(from, { from = it }, label = { Text(tr("From", "Origen")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(relation, { relation = it.uppercase() }, label = { Text(tr("Relation", "Relación")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(toType, { toType = it }, label = { Text(tr("To type", "Tipo destino")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(to, { to = it }, label = { Text(tr("To", "Destino")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = {
            TextButton(onClick = { onSave(item.copy(fromType = fromType, from = from, relation = relation, toType = toType, to = to, page = page.toIntOrNull() ?: item.page)) },
                enabled = listOf(fromType, from, relation, toType, to).all { it.isNotBlank() } && (page.toIntOrNull() ?: 0) >= 1) { Text(tr("Approve as corrected", "Aprobar corregido")) }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "Cancelar")) } })
    }
}
