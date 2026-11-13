@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.proasiste.lite.ui

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.proasiste.lite.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.proasiste.lite.Store
import com.proasiste.lite.runtime.JevClient
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal val Ink = Color(0xFF0A0A0A)
internal val Steel = Color(0xFF161616)
private val Raised = Color(0xFF232323)
internal val Line = Color(0xFF2F2F2F)
internal val Mist = Color(0xFFF5F5F5)
internal val Muted = Color(0xFF9B9B9B)
internal val Gold = Color(0xFFCFB382)
internal val Warning = Color(0xFFF28B82)

// Brand type from aivalueprospector.com: Open Sauce Sans for interface and headings, Noto Serif for reading text.
internal val OpenSauce = FontFamily(
    Font(R.font.open_sauce_sans_light, FontWeight.Light),
    Font(R.font.open_sauce_sans_regular, FontWeight.Normal),
    Font(R.font.open_sauce_sans_medium, FontWeight.Medium),
    Font(R.font.open_sauce_sans_semibold, FontWeight.SemiBold),
)
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
internal val NotoSerif = FontFamily(
    Font(R.font.noto_serif, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.noto_serif, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

/** Material type scale with every style set in Open Sauce Sans. */
val BrandTypography: Typography = Typography().run {
    Typography(
        displayLarge = displayLarge.copy(fontFamily = OpenSauce), displayMedium = displayMedium.copy(fontFamily = OpenSauce), displaySmall = displaySmall.copy(fontFamily = OpenSauce),
        headlineLarge = headlineLarge.copy(fontFamily = OpenSauce), headlineMedium = headlineMedium.copy(fontFamily = OpenSauce), headlineSmall = headlineSmall.copy(fontFamily = OpenSauce),
        titleLarge = titleLarge.copy(fontFamily = OpenSauce), titleMedium = titleMedium.copy(fontFamily = OpenSauce), titleSmall = titleSmall.copy(fontFamily = OpenSauce),
        bodyLarge = bodyLarge.copy(fontFamily = OpenSauce), bodyMedium = bodyMedium.copy(fontFamily = OpenSauce), bodySmall = bodySmall.copy(fontFamily = OpenSauce),
        labelLarge = labelLarge.copy(fontFamily = OpenSauce), labelMedium = labelMedium.copy(fontFamily = OpenSauce), labelSmall = labelSmall.copy(fontFamily = OpenSauce),
    )
}

@Composable
fun PlantroomScreen(state: UiState, actions: UiActions) {
    val scheme = darkColorScheme(primary = Gold, onPrimary = Ink, background = Ink, surface = Steel,
        onSurface = Mist, onBackground = Mist, surfaceVariant = Raised, onSurfaceVariant = Muted,
        outline = Line, error = Warning)
    MaterialTheme(colorScheme = scheme, typography = BrandTypography) {
        var setup by rememberSaveable { mutableStateOf(false) }
        var capture by rememberSaveable { mutableStateOf(false) }
        var buildings by rememberSaveable { mutableStateOf(false) }
        var newProject by rememberSaveable { mutableStateOf(false) }
        var deleteProject by rememberSaveable { mutableStateOf<String?>(null) }
        var filterSheet by rememberSaveable { mutableStateOf(false) }
        var inspector by remember { mutableStateOf<UiItem?>(null) }
        var deleteSource by remember { mutableStateOf<UiItem?>(null) }
        var library by rememberSaveable { mutableStateOf(false) }
        val listState = rememberLazyListState()
        val filter = state.filters.kind?.name ?: "All"
        val keyboard = LocalSoftwareKeyboardController.current
        val locked = state.busy || state.recording
        val searching = !library && (state.isSearching || state.searchMillis != null)
        val showEvidence = library || searching
        val buildingName = projectName(state, state.buildingId)
        val visible = state.items.filter { it.buildingId == state.buildingId && (filter == "All" || it.kind.name == filter) }
        val cover = state.items.firstOrNull { it.buildingId == state.buildingId && it.kind == UiKind.PHOTO }
        // Successful-result guidance is already beside the result filters. Keep progress/errors
        // here without repeating the same advisory in a second full-width notice.
        val showStatus = state.status.isNotBlank() && !state.status.startsWith("Closest matches") && !state.status.startsWith("Coincidencias más cercanas") &&
            (library || state.busy || state.error != null || state.recording)
        LaunchedEffect(state.buildingId) { inspector = null }
        LaunchedEffect(library, searching, state.buildingId) { listState.scrollToItem(0) }
        fun navigate(toLibrary: Boolean) {
            actions.onStopAudio()
            actions.onQuery("")
            actions.onResetFilters()
            inspector = null
            library = toLibrary
            keyboard?.hide()
        }
        BackHandler(enabled = !locked && !setup && !capture && !buildings && !newProject && deleteProject == null && !filterSheet && inspector == null && (library || searching)) {
            navigate(false)
        }

        Scaffold(
            containerColor = Ink,
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 22.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BrandMark()
                    Spacer(Modifier.width(10.dp))
                    Text("ProAsisteLite", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Steel).clickable { actions.onLanguage(!L10n.spanish) }
                        .padding(horizontal = 10.dp, vertical = 7.dp).testTag("plantroom.language")
                        .semantics { contentDescription = tr("Language: English. Tap for Spanish.", "Idioma: español. Toca para inglés.") },
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("EN", color = if (L10n.spanish) Muted else Gold, fontSize = 12.sp, fontFamily = OpenSauce, fontWeight = if (L10n.spanish) FontWeight.Normal else FontWeight.SemiBold)
                        Text("  |  ", color = Line, fontSize = 12.sp)
                        Text("ES", color = if (L10n.spanish) Gold else Muted, fontSize = 12.sp, fontFamily = OpenSauce, fontWeight = if (L10n.spanish) FontWeight.SemiBold else FontWeight.Normal)
                    }
                    IconButton(onClick = { setup = true }, modifier = Modifier.semantics { contentDescription = tr("Collection and model settings", "Ajustes de colección y modelo") }) {
                        Glyph("settings", tint = Muted)
                    }
                }
            },
            bottomBar = {
                Column(Modifier.background(Ink).navigationBarsPadding()) {
                    HorizontalDivider(color = Line)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { navigate(false) }, enabled = !locked, modifier = Modifier.weight(1f).testTag("plantroom.nav-search")) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Glyph("search", tint = if (!library) Gold else Muted, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.height(3.dp)); Text(tr("Search", "Buscar"), color = if (!library) Gold else Muted, fontSize = 11.sp)
                            }
                        }
                        TextButton(onClick = { navigate(true) }, enabled = !locked, modifier = Modifier.weight(1f).testTag("plantroom.nav-library")) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Glyph("library", tint = if (library) Gold else Muted, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.height(3.dp)); Text(tr("Library", "Biblioteca"), color = if (library) Gold else Muted, fontSize = 11.sp)
                            }
                        }
                        Button(onClick = { capture = true }, enabled = !locked,
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1.6f).height(48.dp)) {
                            Glyph("plus", tint = Ink, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text(tr("Add evidence", "Agregar evidencia"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("plantroom.evidence-list"), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().testTag("plantroom.building-selector").clickable(enabled = !locked) { buildings = true }.padding(horizontal = 22.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(state.buildingId, color = Gold, fontSize = 11.sp, fontFamily = OpenSauce,
                            modifier = Modifier.background(Steel, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 5.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(buildingName, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(6.dp)); Glyph("down", Muted, Modifier.size(15.dp))
                        if (state.projects.firstOrNull { it.id == state.buildingId }?.incognito == true) {
                            Spacer(Modifier.width(10.dp)); Glyph("incognito-on", Gold, Modifier.size(17.dp).semantics { contentDescription = tr("Incognito on", "Incógnito activado") })
                        }
                        Spacer(Modifier.weight(1f))
                        Glyph("device", Muted, Modifier.size(13.dp)); Spacer(Modifier.width(5.dp))
                        Text(tr("On device", "En el teléfono"), color = Muted, fontSize = 11.sp)
                    }
                }
                item {
                    AnimatedVisibility(library && cover != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Box(Modifier.padding(top = 8.dp, bottom = 18.dp).fillMaxWidth().height(174.dp)) {
                            LocalPhoto(cover?.localPath.orEmpty(), tr("Collection photograph", "Foto de la colección"), Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = .93f)))))
                            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp, vertical = 16.dp)) {
                                Eyebrow(tr("PROJECT LIBRARY", "BIBLIOTECA DEL PROYECTO"), Gold)
                                Spacer(Modifier.height(4.dp))
                                Text(tr("Inspection evidence", "Evidencia de inspección"), fontSize = 25.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.7).sp)
                                Text(tr("${state.totalCount} sources stored on this phone", "${state.totalCount} fuentes guardadas en este teléfono"), color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (!library) item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Spacer(Modifier.height(if (searching) 12.dp else 76.dp))
                        OutlinedTextField(value = state.query, onValueChange = actions.onQuery, enabled = !locked,
                            placeholder = { Text(tr("Describe what you're looking for", "Describe lo que buscas"), color = Muted, fontSize = 14.sp) },
                            leadingIcon = { Glyph("search", Gold, Modifier.size(21.dp)) },
                            trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { actions.onQuery("") }, enabled = !locked, modifier = Modifier.semantics { contentDescription = tr("Clear search", "Borrar búsqueda") }) { Glyph("close", Muted) } },
                            modifier = Modifier.fillMaxWidth().testTag("plantroom.query"), shape = RoundedCornerShape(16.dp), maxLines = 3,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Line,
                                focusedContainerColor = Steel, unfocusedContainerColor = Steel),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { if (state.modelReady && !locked && state.query.isNotBlank()) { keyboard?.hide(); actions.onSearch() } }))
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = actions.onImageSearch, enabled = state.modelReady && !locked, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Glyph("image", if (state.modelReady && !locked) Gold else Muted, Modifier.size(19.dp))
                                Spacer(Modifier.width(7.dp)); Text(tr("Search with a photo", "Buscar con una foto"), fontSize = 12.sp)
                            }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { keyboard?.hide(); actions.onSearch() },
                                enabled = state.modelReady && !locked && state.query.isNotBlank(), shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 19.dp), modifier = Modifier.height(43.dp).testTag("plantroom.submit-search")) {
                                Text(tr("Search", "Buscar"), fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(7.dp)); Glyph("arrow", Ink, Modifier.size(16.dp))
                            }
                        }
                        if (!state.modelReady) {
                            TextButton(onClick = { setup = true }, contentPadding = PaddingValues(0.dp)) {
                                Text(tr("Set up local search", "Configurar búsqueda local"), color = Gold); Spacer(Modifier.width(8.dp)); Glyph("arrow", Gold, Modifier.size(15.dp))
                            }
                        } else if (state.indexedCount < state.totalCount && !locked && state.indexedCount == 0) {
                            TextButton(onClick = { navigate(true) }, contentPadding = PaddingValues(0.dp)) {
                                Text(tr("Prepare your library for search", "Prepara tu biblioteca para buscar"), color = Gold)
                            }
                        }
                    }
                }
                if (library && state.modelReady && state.indexedCount < state.totalCount && !locked) item {
                    TextButton(onClick = actions.onIndex, modifier = Modifier.padding(horizontal = 22.dp).testTag("plantroom.index"), contentPadding = PaddingValues(0.dp)) {
                        Text(tr("Index ${state.totalCount - state.indexedCount} remaining sources", "Indexar ${state.totalCount - state.indexedCount} fuentes pendientes"), color = Gold)
                        Spacer(Modifier.width(8.dp)); Glyph("arrow", Gold, Modifier.size(15.dp))
                    }
                }
                item {
                    AnimatedVisibility(state.busy || showStatus || state.error != null || state.recording) {
                        Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp).animateContentSize()) {
                            if (state.recording) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).background(Warning, CircleShape)); Spacer(Modifier.width(8.dp))
                                    Text(tr("Recording voice note", "Grabando nota de voz"), color = Warning, fontSize = 13.sp)
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = actions.onRecordAudio) { Text(tr("Stop & save", "Detener y guardar"), color = Gold) }
                                }
                            }
                            if (state.busy) {
                                if (state.progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = Gold, trackColor = Line)
                                else LinearProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Gold, trackColor = Line)
                                Spacer(Modifier.height(9.dp))
                                if (state.progress != null) TextButton(onClick = actions.onCancelIndex, contentPadding = PaddingValues(0.dp)) { Text(tr("Pause indexing", "Pausar indexación"), color = Muted, fontSize = 12.sp) }
                            }
                            if (showStatus) Text(state.status, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                            state.error?.let { error ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    Text(error, color = Warning, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    IconButton(onClick = actions.onDismissError, modifier = Modifier.semantics { contentDescription = tr("Dismiss error", "Descartar error") }) { Glyph("close", Warning) }
                                }
                            }
                        }
                    }
                }
                val answer = state.answer
                if (searching && answer != null) item {
                    AnswerCard(answer, incognito = state.projects.firstOrNull { it.id == state.buildingId }?.incognito == true,
                        onSource = { inspector = it }, onShare = actions.onShareAnswer)
                }
                if (showEvidence && !searching && state.reviewCount > 0) item {
                    // Graph facts the checks held back wait here for the steward: its own row, not a kind of file.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).background(Steel, RoundedCornerShape(14.dp))
                        .clickable(enabled = !locked) { actions.onOpenReview() }.padding(horizontal = 16.dp, vertical = 14.dp).testTag("plantroom.review-row"), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Graph review · ${state.reviewCount} facts pending", "Revisión del grafo · ${state.reviewCount} datos pendientes"), color = Mist, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Glyph("arrow", Gold, Modifier.size(14.dp))
                    }
                }
                if (showEvidence) item {
                    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (searching) tr("Search results", "Resultados") else tr("Your collection", "Tu colección"), fontSize = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.4).sp)
                        Spacer(Modifier.weight(1f))
                        if (searching && state.searchMillis != null) Text("${String.format(Locale.UK, "%.2f", state.searchMillis / 1000.0)} s", color = Muted, fontSize = 12.sp, fontFamily = OpenSauce)
                        else Text(tr("${state.indexedCount} indexed", "${state.indexedCount} indexados"), color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.width(5.dp))
                        IconButton(onClick = { keyboard?.hide(); filterSheet = true }, enabled = !locked,
                            modifier = Modifier.testTag("plantroom.open-filters").semantics { contentDescription = tr("Filter by asset and date", "Filtrar por activo y fecha") }) {
                            Box(contentAlignment = Alignment.Center) {
                                Glyph("settings", if (state.filters.metadataCount > 0) Gold else Muted, Modifier.size(21.dp))
                                if (state.filters.metadataCount > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-5).dp).size(6.dp).background(Gold, CircleShape))
                            }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("All" to tr("All sources", "Todo"), "PHOTO" to tr("Photos", "Fotos"), "AUDIO" to tr("Voice notes", "Notas de voz"), "REPORT" to tr("Reports", "Informes")).forEach { (key, label) ->
                            FilterChip(selected = filter == key, onClick = { actions.onKindFilter(if (key == "All") null else UiKind.valueOf(key)) }, enabled = !locked,
                                label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.testTag("plantroom.filter.$key"),
                                shape = RoundedCornerShape(9.dp), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Raised, selectedLabelColor = Gold),
                                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filter == key, borderColor = Line, selectedBorderColor = Raised))
                        }
                    }
                    if (state.filters.metadataCount > 0) Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(listOfNotNull(state.filters.assetId?.let { if (it.isBlank()) tr("Untagged", "Sin etiqueta") else it },
                            state.filters.dateFrom.takeIf { it.isNotEmpty() }?.let { tr("From $it", "Desde $it") },
                            state.filters.dateTo.takeIf { it.isNotEmpty() }?.let { tr("To $it", "Hasta $it") }).joinToString(" · "),
                            color = Gold, fontSize = 11.sp, modifier = Modifier.weight(1f), lineHeight = 17.sp)
                        TextButton(onClick = actions.onResetFilters, enabled = !locked, contentPadding = PaddingValues(horizontal = 5.dp), modifier = Modifier.testTag("plantroom.clear-filters")) {
                            Text(tr("Clear", "Borrar"), fontSize = 11.sp)
                        }
                    }
                    if (searching && visible.isNotEmpty()) Text(tr("Closest matches · inspect the source to confirm", "Coincidencias más cercanas · revisa la fuente para confirmar"), Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp), color = Muted, fontSize = 11.sp)
                }
                if (showEvidence && visible.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 25.dp)) {
                        Glyph(if (searching) "search" else "library", Muted, Modifier.size(32.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(if (state.busy) tr("Working locally…", "Trabajando en el teléfono…") else if (state.filters.activeCount > 0) tr("No sources match these filters", "Ninguna fuente coincide con estos filtros") else if (searching) tr("No sources to show", "No hay fuentes para mostrar") else if (state.totalCount == 0) tr("Your project library starts here", "Tu biblioteca del proyecto empieza aquí") else tr("No ${filterLabel(filter).lowercase()} here yet", "Aún no hay ${filterLabel(filter).lowercase()} aquí"),
                            fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Text(if (state.filters.activeCount > 0) tr("Change the asset or dates, or clear filters to see more sources.", "Cambia el activo o las fechas, o borra los filtros para ver más fuentes.") else if (searching) tr("Try another description or choose a different source type.", "Prueba otra descripción o elige otro tipo de fuente.") else tr("Import a project pack, or add your own photos, reports and short recordings.", "Importa un paquete de proyecto o agrega tus propias fotos, informes y grabaciones cortas."), fontFamily = NotoSerif, color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
                        if (state.filters.activeCount > 0) TextButton(onClick = actions.onResetFilters, enabled = !locked, contentPadding = PaddingValues(0.dp)) { Text(tr("Clear all filters", "Borrar todos los filtros")) }
                        if (!searching && state.totalCount == 0) {
                            Spacer(Modifier.height(15.dp))
                            OutlinedButton(onClick = actions.onImportCorpus, enabled = !locked, shape = RoundedCornerShape(12.dp)) { Text(tr("Import project pack", "Importar paquete de proyecto")) }
                        }
                    }
                } else if (showEvidence) items(visible, key = { it.id }) { item ->
                    EvidenceRow(item, searching, onClick = { inspector = item }, review = if (library) state.docReview[item.id] else null, reviewEnabled = !locked,
                        onReviewPending = { actions.onReviewPending(item.id, null) }, onReviewAll = { actions.onReviewDocument(item.id) })
                }
                if (library && visible.isNotEmpty()) item {
                    Text(tr("Original sources remain available on this phone.", "Las fuentes originales siguen disponibles en este teléfono."),
                        Modifier.padding(horizontal = 22.dp, vertical = 18.dp), fontFamily = NotoSerif, color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                }
            }
        }

        if (filterSheet) FilterSheet(state, actions, onClose = { filterSheet = false })

        if (buildings) ModalBottomSheet(onDismissRequest = { buildings = false }, containerColor = Steel) {
            SheetHeader(tr("Projects", "Proyectos"), tr("Choose the project you're working in.", "Elige el proyecto en el que trabajas."))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                state.projects.forEach { project ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !locked) { actions.onBuilding(project.id); buildings = false }.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(project.id, color = Gold, fontSize = 13.sp, fontFamily = OpenSauce,
                            modifier = Modifier.background(Raised, RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 7.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(project.name, fontWeight = FontWeight.Medium, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        if (state.buildingId == project.id) Glyph("check", Gold)
                        IconButton(onClick = { actions.onToggleIncognito(project.id) }, enabled = !locked,
                            modifier = Modifier.testTag("plantroom.incognito.${project.id}").semantics {
                                contentDescription = tr("Incognito ${if (project.incognito) "on" else "off"} for ${project.id}: masks personal data in shared text",
                                    "Incógnito ${if (project.incognito) "activado" else "desactivado"} en ${project.id}: enmascara datos personales al compartir") }) {
                            Glyph(if (project.incognito) "incognito-on" else "incognito", if (project.incognito) Gold else Muted, Modifier.size(22.dp))
                        }
                        if (state.projects.size > 1) IconButton(onClick = { buildings = false; deleteProject = project.id }, enabled = !locked,
                            modifier = Modifier.testTag("plantroom.delete-project.${project.id}").semantics { contentDescription = tr("Delete ${project.id} ${project.name}", "Eliminar ${project.id} ${project.name}") }) {
                            Glyph("trash", Muted, Modifier.size(20.dp))
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = Line)
                ActionRow(tr("New project", "Nuevo proyecto"), tr("Add a project number and name", "Agrega un número y un nombre de proyecto"), "plus", enabled = !locked) { buildings = false; newProject = true }
                Spacer(Modifier.height(28.dp))
            }
        }

        if (newProject) NewProjectDialog(state, actions, onClose = { newProject = false })

        deleteProject?.let { id ->
            val count = if (state.buildingId == id) tr(" (${state.totalCount} sources)", " (${state.totalCount} fuentes)") else ""
            AlertDialog(onDismissRequest = { deleteProject = null }, containerColor = Steel,
                title = { Text(tr("Delete $id ${projectName(state, id)}?", "¿Eliminar $id ${projectName(state, id)}?")) },
                text = { Text(tr("This removes the project, its search index and the app's copies of its files$count. Originals elsewhere on your phone are not touched. This can't be undone.", "Esto elimina el proyecto, su índice de búsqueda y las copias de sus archivos en la app$count. Los originales en otras partes de tu teléfono no se tocan. No se puede deshacer."), lineHeight = 21.sp, fontFamily = NotoSerif) },
                confirmButton = { TextButton(onClick = { actions.onDeleteProject(id); deleteProject = null }, enabled = !locked) { Text(tr("Delete", "Eliminar"), color = Warning) } },
                dismissButton = { TextButton(onClick = { deleteProject = null }) { Text(tr("Cancel", "Cancelar")) } })
        }

        deleteSource?.let { item ->
            AlertDialog(onDismissRequest = { deleteSource = null }, containerColor = Steel,
                title = { Text(tr("Delete ${item.title}?", "¿Eliminar ${item.title}?")) },
                text = { Text(tr("This removes the file from ${item.buildingId} ${projectName(state, item.buildingId)}, with its search index and the app's copy. The original elsewhere on your phone is not touched. This can't be undone.", "Esto quita el archivo de ${item.buildingId} ${projectName(state, item.buildingId)}, junto con su índice de búsqueda y la copia de la app. El original en otras partes de tu teléfono no se toca. No se puede deshacer."), lineHeight = 21.sp, fontFamily = NotoSerif) },
                confirmButton = { TextButton(onClick = { actions.onDeleteSource(item.id); deleteSource = null; inspector = null }, enabled = !locked) { Text(tr("Delete", "Eliminar"), color = Warning) } },
                dismissButton = { TextButton(onClick = { deleteSource = null }) { Text(tr("Cancel", "Cancelar")) } })
        }

        state.share?.let { share ->
            AlertDialog(onDismissRequest = actions.onDismissShare, containerColor = Steel,
                title = { Text(if (share.incognito) tr("Share masked answer", "Compartir respuesta enmascarada") else tr("Share answer", "Compartir respuesta")) },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        if (share.preparing) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Gold, trackColor = Line)
                            Spacer(Modifier.height(9.dp)); Text(tr("Masking personal data on this phone…", "Enmascarando datos personales en este teléfono…"), color = Muted, fontSize = 12.sp)
                        } else {
                            if (share.incognito) Text(if (share.masked == 0) tr("Nothing to mask was found. Check the text before sharing.", "No se encontró nada que enmascarar. Revisa el texto antes de compartir.") else tr("${share.masked} item${if (share.masked == 1) "" else "s"} masked, shown in gold. Check for anything missed.",
                                "${share.masked} dato${if (share.masked == 1) "" else "s"} enmascarado${if (share.masked == 1) "" else "s"}, en dorado. Revisa si falta alguno."),
                                color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                            if (share.note.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(share.note, color = Warning, fontSize = 12.sp, lineHeight = 18.sp) }
                            Spacer(Modifier.height(10.dp))
                            Text(buildAnnotatedString {
                                var last = 0
                                Regex("\\[[A-Z]+_\\d+]").findAll(share.text).forEach { m ->
                                    append(share.text.substring(last, m.range.first))
                                    withStyle(SpanStyle(color = Gold, fontWeight = FontWeight.SemiBold)) { append(m.value) }
                                    last = m.range.last + 1
                                }
                                append(share.text.substring(last))
                            }, fontFamily = NotoSerif, fontSize = 13.sp, lineHeight = 20.sp)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { actions.onConfirmShare(share.text) }, enabled = !share.preparing) { Text(tr("Share", "Compartir")) } },
                dismissButton = { TextButton(onClick = actions.onDismissShare) { Text(tr("Cancel", "Cancelar")) } })
        }

        state.review?.let { ReviewScreen(it, actions) }

        if (capture) ModalBottomSheet(onDismissRequest = { capture = false }, containerColor = Steel) {
            SheetHeader(tr("Add evidence", "Agregar evidencia"), tr("Saved to $buildingName on this phone.", "Se guarda en $buildingName en este teléfono."))
            ActionRow(tr("Take a photo", "Tomar una foto"), tr("Save a new observation to this project", "Guarda una nueva observación en este proyecto"), "image", enabled = !locked) { capture = false; navigate(true); actions.onCapturePhoto() }
            ActionRow(tr("Import files", "Importar archivos"), tr("Photos, PDF reports and short recordings", "Fotos, informes PDF y grabaciones cortas"), "plus", enabled = !locked) { capture = false; navigate(true); actions.onImportFiles() }
            ActionRow(if (state.recording) tr("Stop & save recording", "Detener y guardar grabación") else tr("Record a voice note", "Grabar una nota de voz"), tr("Capture an observation during your visit", "Registra una observación durante tu visita"), "audio", enabled = !state.busy) { capture = false; navigate(true); actions.onRecordAudio() }
            ActionRow(tr("Build graph with DeepSeek", "Construir el grafo con DeepSeek"), tr("Sends this project's PDF text to OpenRouter and DeepSeek with your saved key. Never for incognito projects.", "Envía el texto de los PDF de este proyecto a OpenRouter y DeepSeek con tu clave guardada. Nunca en proyectos incógnito."), "library", enabled = !locked && state.jevKeySaved) { capture = false; navigate(true); actions.onBuildGraph() }
            ActionRow(tr("Import a graph file", "Importar un archivo de grafo"), tr("Facts about a file already in this project", "Datos sobre un archivo que ya está en este proyecto"), "library", enabled = !locked) { capture = false; navigate(true); actions.onImportGraph() }
            ActionRow(tr("Import a project pack", "Importar un paquete de proyecto"), tr("Load a prepared collection from local storage", "Carga una colección preparada desde el almacenamiento local"), "library", enabled = !locked) { capture = false; navigate(true); actions.onImportCorpus() }
            Spacer(Modifier.height(26.dp))
        }

        if (setup) ModalBottomSheet(onDismissRequest = { setup = false }, containerColor = Steel,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SheetHeader(tr("On this phone", "En este teléfono"), tr("Your collection and local search model.", "Tu colección y el modelo de búsqueda local."))
                Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp).fillMaxWidth()) {
                    Count(tr("PHOTOS", "FOTOS"), state.photoCount, Modifier.weight(1f)); Count(tr("VOICE NOTES", "NOTAS DE VOZ"), state.audioCount, Modifier.weight(1f)); Count(tr("REPORTS", "INFORMES"), state.reportCount, Modifier.weight(1f))
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = Line)
                Column(Modifier.padding(24.dp)) {
                    Eyebrow(tr("LOCAL SEARCH MODEL", "MODELO DE BÚSQUEDA LOCAL"))
                    Spacer(Modifier.height(10.dp))
                    Text(state.modelLabel, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(if (state.modelReady) Gold else Muted, CircleShape)); Spacer(Modifier.width(8.dp))
                        Text(if (state.modelReady) tr("Ready on this phone", "Listo en este teléfono") else tr("Import a model to enable search", "Importa un modelo para activar la búsqueda"), color = if (state.modelReady) Gold else Muted, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    OutlinedButton(onClick = { setup = false; actions.onImportModel() }, enabled = !locked, shape = RoundedCornerShape(12.dp)) {
                        Text(if (state.modelReady) tr("Replace model", "Reemplazar modelo") else tr("Import model file", "Importar archivo del modelo"))
                    }
                    if (state.modelReady && state.indexedCount < state.totalCount && !locked) {
                        TextButton(onClick = { setup = false; actions.onIndex() }) { Text(tr("Index remaining sources", "Indexar fuentes pendientes")) }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = Line)
                JevKeySection(state, actions)
                HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = Line)
                ActionRow(tr("Import project pack", "Importar paquete de proyecto"), tr("Add a prepared collection from local storage", "Agrega una colección preparada desde el almacenamiento local"), "library", enabled = !locked) { setup = false; actions.onImportCorpus() }
                Text(tr("Search retrieves original evidence. It does not diagnose faults or generate maintenance instructions.", "La búsqueda recupera la evidencia original. No diagnostica fallas ni genera instrucciones de mantenimiento."), Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 35.dp), fontFamily = NotoSerif, color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
            }
        }

        inspector?.let { item ->
            ModalBottomSheet(onDismissRequest = { actions.onStopAudio(); inspector = null }, containerColor = Steel,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("plantroom.inspector")) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (item.kind == UiKind.PHOTO) LocalPhoto(item.localPath, item.title, Modifier.fillMaxWidth().height(285.dp))
                    Column(Modifier.padding(24.dp)) {
                        Eyebrow(item.kind.label.uppercase() + if (item.sourceLocation.isNotBlank()) " · ${item.sourceLocation}" else "")
                        Spacer(Modifier.height(12.dp))
                        Text(item.title, fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.5).sp)
                        Spacer(Modifier.height(12.dp))
                        Text(listOf(item.buildingId + " " + projectName(state, item.buildingId), item.assetId, item.date).filter { it.isNotBlank() }.joinToString("  ·  "), fontSize = 12.sp, color = Muted, lineHeight = 19.sp)
                        if (item.excerpt.isNotBlank()) {
                            Spacer(Modifier.height(22.dp))
                            Text(item.excerpt, fontSize = 15.sp, lineHeight = 24.sp, fontFamily = NotoSerif)
                        }
                        Spacer(Modifier.height(24.dp))
                        if (item.kind == UiKind.AUDIO) {
                            Button(onClick = { if (state.playingId == item.id) actions.onStopAudio() else actions.onPlayAudio(item) }, enabled = !state.recording,
                                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                                Glyph(if (state.playingId == item.id) "stop" else "play", Ink, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                                Text(if (state.playingId == item.id) tr("Stop recording playback", "Detener reproducción") else tr("Play original recording", "Reproducir grabación original"))
                            }
                        } else Button(onClick = { actions.onOpenSource(item) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                            Text(if (item.kind == UiKind.REPORT) tr("Open original report", "Abrir informe original") else tr("Open original photo", "Abrir foto original")); Spacer(Modifier.width(9.dp)); Glyph("arrow", Ink, Modifier.size(18.dp))
                        }
                        if (item.synthetic) {
                            Spacer(Modifier.height(18.dp))
                            Text(tr("FICTIONAL DEMO SOURCE", "FUENTE FICTICIA DE DEMOSTRACIÓN"), fontFamily = OpenSauce, color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                            Text(tr("Created for this demonstration. Not a real property record.", "Creada para esta demostración. No es un registro real de una propiedad."), fontFamily = NotoSerif, color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { deleteSource = item }, enabled = !locked, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("plantroom.delete-source")) {
                            Glyph("trash", Warning, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text(tr("Delete from project", "Eliminar del proyecto"), color = Warning, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSheet(state: UiState, actions: UiActions, onClose: () -> Unit) {
    var asset by remember { mutableStateOf(state.filters.assetId) }
    var from by remember { mutableStateOf(state.filters.dateFrom) }
    var to by remember { mutableStateOf(state.filters.dateTo) }
    var assetMenu by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    val locked = state.busy || state.recording
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Steel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("plantroom.filter-sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()) {
            SheetHeader(tr("Filter evidence", "Filtrar evidencia"), tr("Exact asset and record dates in ${projectName(state, state.buildingId)}.", "Activo exacto y fechas de registro en ${projectName(state, state.buildingId)}."))
            Column(Modifier.padding(horizontal = 24.dp)) {
                Eyebrow(tr("ASSET", "ACTIVO"))
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedButton(onClick = { assetMenu = true }, enabled = !locked, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("plantroom.asset-picker"), shape = RoundedCornerShape(12.dp)) {
                        Text(asset?.ifBlank { tr("Without an asset tag", "Sin etiqueta de activo") } ?: tr("All assets, including untagged", "Todos los activos, incluidos los sin etiqueta"), modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Glyph("down", Gold, Modifier.size(17.dp))
                    }
                    DropdownMenu(expanded = assetMenu, onDismissRequest = { assetMenu = false }) {
                        DropdownMenuItem(text = { Text(tr("All assets, including untagged", "Todos los activos, incluidos los sin etiqueta")) }, onClick = { asset = null; assetMenu = false; attempted = false })
                        state.availableAssetIds.forEach { id ->
                            DropdownMenuItem(text = { Text(id.ifBlank { tr("Without an asset tag", "Sin etiqueta de activo") }) }, onClick = { asset = id; assetMenu = false; attempted = false }, modifier = Modifier.testTag("plantroom.asset-option.$id"))
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                Eyebrow(tr("RECORD DATE · INCLUSIVE", "FECHA DE REGISTRO · INCLUSIVA"))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = from, onValueChange = { from = it; attempted = false }, enabled = !locked, singleLine = true,
                        label = { Text(tr("From", "Desde")) }, placeholder = { Text(tr("YYYY-MM-DD", "AAAA-MM-DD"), fontSize = 12.sp) },
                        modifier = Modifier.weight(1f).testTag("plantroom.date-from"), shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                    OutlinedTextField(value = to, onValueChange = { to = it; attempted = false }, enabled = !locked, singleLine = true,
                        label = { Text(tr("To", "Hasta")) }, placeholder = { Text(tr("YYYY-MM-DD", "AAAA-MM-DD"), fontSize = 12.sp) },
                        modifier = Modifier.weight(1f).testTag("plantroom.date-to"), shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                }
                Spacer(Modifier.height(8.dp))
                Text(tr("Leave either date blank for an open range.", "Deja una fecha en blanco para un rango abierto."), fontSize = 12.sp, color = Muted, lineHeight = 18.sp)
                if (attempted && state.filterError != null) Text(state.filterError, color = Warning, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp).testTag("plantroom.filter-error"), lineHeight = 19.sp)
                Spacer(Modifier.height(22.dp))
                Button(onClick = { attempted = true; if (actions.onApplyFilters(state.filters.copy(assetId = asset, dateFrom = from, dateTo = to))) onClose() },
                    enabled = !locked, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("plantroom.apply-filters"), shape = RoundedCornerShape(14.dp)) { Text(tr("Apply filters", "Aplicar filtros")) }
                TextButton(onClick = { actions.onResetFilters(); onClose() }, enabled = !locked, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text(tr("Reset all filters", "Restablecer filtros")) }
                Spacer(Modifier.height(22.dp))
            }
        }
    }
}

/** Gemini Nano's cited answer. Lists only the passages it cites (all of them if it cites none); each opens its source. */
/** The OpenRouter key for Jev. The key itself never enters UiState and the field is cleared once it's saved. */
@Composable
private fun JevKeySection(state: UiState, actions: UiActions) {
    var key by remember { mutableStateOf("") }
    LaunchedEffect(state.jevKeySaved) { if (state.jevKeySaved) key = "" }
    Column(Modifier.padding(24.dp).testTag("plantroom.jev")) {
        Eyebrow(tr("CLOUD CHECKS · JEV", "REVISIÓN EN LA NUBE · JEV"))
        Spacer(Modifier.height(10.dp))
        Text(tr("OpenRouter key", "Clave de OpenRouter"), fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val on = state.jevKeySaved && state.jevEnabled
            Box(Modifier.size(6.dp).background(if (on) Gold else Muted, CircleShape)); Spacer(Modifier.width(8.dp))
            Text(when {
                on -> tr("Saved · Jev checks are on", "Guardada · revisión con Jev activada")
                state.jevKeySaved -> tr("Saved · Jev checks are off", "Guardada · revisión con Jev desactivada")
                else -> tr("Optional · without a key, Jev stays off", "Opcional · sin clave, Jev queda desactivado")
            }, color = if (on) Gold else Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(tr("Jev judges which passages help answer your question and checks that the answer's citations hold up. With a key saved and Jev on, each search sends your question, up to ${Store.RERANK_CANDIDATES} passages and the answer's sentences to OpenRouter and TypeSafe. Incognito projects are never sent.",
            "Jev evalúa qué pasajes ayudan a responder tu pregunta y comprueba que las citas de la respuesta sean correctas. Con una clave guardada y Jev activado, cada búsqueda envía tu pregunta, hasta ${Store.RERANK_CANDIDATES} pasajes y las frases de la respuesta a OpenRouter y TypeSafe. Los proyectos incógnito nunca se envían."),
            color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(14.dp))
        if (state.jevKeySaved) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Check answers with Jev", "Revisar respuestas con Jev"), Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.width(12.dp))
                Switch(state.jevEnabled, actions.onJevEnabled, enabled = !state.busy, modifier = Modifier.testTag("plantroom.jev-enabled"))
            }
            Text(tr("Off: searches send nothing. The key is still used when you build a graph with DeepSeek.", "Desactivado: las búsquedas no envían nada. La clave se sigue usando al construir un grafo con DeepSeek."), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = actions.onRemoveJevKey, shape = RoundedCornerShape(12.dp), modifier = Modifier.testTag("plantroom.jev-remove")) { Text(tr("Remove key", "Eliminar clave")) }
        } else {
            OutlinedTextField(value = key, onValueChange = { key = it }, enabled = !state.jevKeyChecking, singleLine = true,
                label = { Text(tr("OpenRouter key", "Clave de OpenRouter")) }, placeholder = { Text("sk-or-…", fontSize = 13.sp) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag("plantroom.jev-key"), shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Line))
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { actions.onSaveJevKey(key) }, enabled = key.isNotBlank() && !state.jevKeyChecking, shape = RoundedCornerShape(12.dp), modifier = Modifier.testTag("plantroom.jev-save")) {
                Text(if (state.jevKeyChecking) tr("Checking key…", "Comprobando clave…") else tr("Save key", "Guardar clave"))
            }
            Spacer(Modifier.height(8.dp))
            Text(tr("Create a key at openrouter.ai/settings/keys. It's stored encrypted on this phone.", "Crea una clave en openrouter.ai/settings/keys. Se guarda cifrada en este teléfono."), color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        state.jevKeyError?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Warning, fontSize = 12.sp, lineHeight = 18.sp) }
    }
}

@Composable
private fun CitationCheck(check: UiCitationCheck, onSource: (UiItem) -> Unit) {
    Spacer(Modifier.height(10.dp))
    val checked = check.supported + check.issues.size
    check.error?.let { Text(it, color = Warning, fontSize = 12.sp, lineHeight = 18.sp) }
    if (check.error == null && checked > 0) {
        val summary = tr("Jev · ${check.supported} of $checked cited sentences supported", "Jev · ${check.supported} de $checked frases citadas respaldadas")
        Text(if (check.issues.isEmpty()) summary else tr("Don't rely on this yet. $summary", "No te fíes de esto todavía. $summary"),
            color = if (check.issues.isEmpty()) Gold else Warning, fontSize = 12.sp, fontFamily = OpenSauce, modifier = Modifier.testTag("plantroom.citation-check"))
    }
    check.issues.filter { it.problem == UiClaimProblem.CONTRADICTED }.forEach { issue ->
        val cites = issue.citations.joinToString("") { "[$it]" }
        Spacer(Modifier.height(8.dp))
        Text(tr("Removed a sentence that $cites contradicts. What the source says:", "Se quitó una frase que $cites contradice. Lo que dice la fuente:"),
            color = Warning, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.testTag("plantroom.claim-removed"))
        issue.passages.forEach { item ->
            Column(Modifier.fillMaxWidth().clickable { onSource(item) }.padding(vertical = 5.dp)) {
                Text("\"${item.excerpt.trim()}\"", fontFamily = NotoSerif, color = Mist, fontSize = 13.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(3.dp))
                Text("${item.title} · ${item.sourceLocation}", color = Gold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    check.issues.filter { it.problem != UiClaimProblem.CONTRADICTED }.forEach { issue ->
        val label = when (issue.problem) {
            UiClaimProblem.CONTRADICTED -> ""
            UiClaimProblem.NOT_SUPPORTED -> tr("Not supported", "Sin respaldo")
            UiClaimProblem.UNSURE -> tr("Unsure, check it", "Dudosa, revísala")
        }
        val cites = issue.citations.joinToString("") { "[$it]" }
        Spacer(Modifier.height(5.dp))
        Text("$label $cites: ${issue.text}", color = Warning, fontSize = 12.sp, lineHeight = 18.sp)
    }
    if (check.uncited > 0) {
        Spacer(Modifier.height(5.dp))
        Text(tr("${check.uncited} sentence(s) cite nothing, so Jev didn't check them.", "${check.uncited} frase(s) sin cita: Jev no las comprobó."), color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun AnswerCard(answer: UiAnswer, incognito: Boolean, onSource: (UiItem) -> Unit, onShare: () -> Unit) {
    val notFound = answer.text.trim().startsWith("NOT_FOUND")
    val cited = JevClient.cites(answer.text).filter { it in 1..answer.sources.size }.sorted()
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 6.dp)
        .background(Steel, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 14.dp).testTag("plantroom.answer")) {
        Eyebrow(if (answer.fromGraph) tr("Answer · from the project graph", "Respuesta · del grafo del proyecto") else tr("Answer · Gemini Nano on this phone", "Respuesta · Gemini Nano en este teléfono"), Gold)
        Spacer(Modifier.height(9.dp))
        // A flagged answer shows its warning first, so it's read before the sentence it's about.
        val flagged = answer.check?.takeIf { it.issues.isNotEmpty() }
        flagged?.let { CitationCheck(it, onSource); Spacer(Modifier.height(12.dp)) }
        if (answer.text.isNotBlank()) {
            Text(if (notFound) tr("The passages found don't answer this. Check the results below or rephrase the question.", "Los pasajes encontrados no responden esto. Revisa los resultados abajo o reformula la pregunta.") else answer.text.trim(),
                fontFamily = NotoSerif, fontSize = 15.sp, lineHeight = 23.sp, color = if (notFound) Muted else Mist)
        }
        if (answer.working) {
            if (answer.text.isBlank()) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = Gold, trackColor = Line)
            if (answer.status.isNotBlank()) { Spacer(Modifier.height(7.dp)); Text(answer.status, color = Muted, fontSize = 11.sp) }
        } else if (answer.status.isNotBlank()) Text(answer.status, color = Warning, fontSize = 12.sp, lineHeight = 18.sp)
        if (flagged == null) answer.check?.let { CitationCheck(it, onSource) }
        if (answer.jevNote.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(answer.jevNote, color = if (answer.jevFailed) Warning else Muted, fontSize = 11.sp, lineHeight = 16.sp) }
        if (!answer.working && !notFound && answer.text.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            (cited.ifEmpty { (1..answer.sources.size).toList() }).forEach { n ->
                val item = answer.sources[n - 1]
                Row(Modifier.fillMaxWidth().clickable { onSource(item) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("[$n]", color = Gold, fontSize = 12.sp, fontFamily = OpenSauce, softWrap = false, modifier = Modifier.widthIn(min = 36.dp))
                    Text("${item.title} · ${item.sourceLocation}", color = Mist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Glyph("arrow", Muted, Modifier.size(13.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(if (answer.fromGraph) tr("Listed from the project graph, whose facts were extracted by AI. Check the cited pages.", "Lista del grafo del proyecto, cuyos datos fueron extraídos por IA. Revisa las páginas citadas.") else tr("Written by AI from your files. Check the cited pages.", "Escrito por IA a partir de tus archivos. Revisa las páginas citadas."), fontFamily = NotoSerif, color = Muted, fontSize = 11.sp)
            TextButton(onClick = onShare, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("plantroom.share-answer")) {
                if (incognito) { Glyph("incognito-on", Gold, Modifier.size(16.dp)); Spacer(Modifier.width(7.dp)) }
                Text(if (incognito) tr("Share masked", "Compartir enmascarado") else tr("Share", "Compartir"), color = Gold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun EvidenceRow(item: UiItem, searching: Boolean, onClick: () -> Unit, review: UiPageStatus? = null, reviewEnabled: Boolean = true, onReviewPending: () -> Unit = {}, onReviewAll: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().testTag("plantroom.source.${item.id}").clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 9.dp)) {
        if (item.kind == UiKind.PHOTO) {
            Box(Modifier.fillMaxWidth().height(if (searching) 160.dp else 164.dp).clip(RoundedCornerShape(13.dp))) {
                LocalPhoto(item.localPath, item.title, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .44f)), startY = 150f)))
                Row(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("image", Mist, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text(tr("PHOTO", "FOTO"), color = Mist, fontSize = 10.sp, fontFamily = OpenSauce, letterSpacing = 1.sp)
                }
                if (item.assetId.isNotBlank()) Text(item.assetId, color = Mist, fontSize = 10.sp, fontFamily = OpenSauce,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            SourceMeta(item)
        } else {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 3.dp).size(45.dp).background(Steel, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                    Glyph(if (item.kind == UiKind.AUDIO) "audio" else "report", Gold)
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Eyebrow(item.kind.label.uppercase())
                    Spacer(Modifier.height(6.dp))
                    Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.kind == UiKind.REPORT && item.excerpt.isNotBlank()) {
                        Spacer(Modifier.height(6.dp)); Text(item.excerpt, fontSize = 12.sp, fontFamily = NotoSerif, color = Muted, maxLines = 2, lineHeight = 18.sp, overflow = TextOverflow.Ellipsis)
                    }
                    SourceMeta(item)
                }
                Spacer(Modifier.width(7.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Glyph(if (item.kind == UiKind.AUDIO) "play" else "arrow", Muted, Modifier.padding(top = 19.dp).size(17.dp))
                    // What the graph says about this document, and whether anyone has checked it.
                    if (item.kind == UiKind.REPORT) ReviewLabel(review, reviewEnabled, Modifier.padding(top = 10.dp), onPending = onReviewPending, onAll = onReviewAll)
                }
            }
            Spacer(Modifier.height(18.dp)); HorizontalDivider(color = Line.copy(alpha = .65f))
        }
    }
}

@Composable
private fun SourceMeta(item: UiItem) {
    val text = listOf(item.date, item.assetId.takeIf { item.kind != UiKind.PHOTO }.orEmpty(), item.sourceLocation).filter { it.isNotBlank() }.joinToString("  ·  ")
    if (text.isNotBlank()) Text(text, Modifier.padding(top = 5.dp), color = Muted, fontSize = 11.sp, maxLines = 2, lineHeight = 16.sp, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun LocalPhoto(path: String, description: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (path.isBlank() || !File(path).isFile) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1400 || bounds.outHeight / sample > 1400) sample *= 2
                val raw = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
                val orientation = runCatching { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                val matrix = Matrix().apply {
                    when (orientation) {
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                    }
                }
                val oriented = if (matrix.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                if (oriented !== raw) raw.recycle()
                oriented.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(Raised), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Glyph("image", Muted, Modifier.size(28.dp).semantics { contentDescription = tr("Photo preview unavailable", "Vista previa no disponible") })
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 14.dp)) {
        Text(title, fontSize = 27.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.7).sp)
        Spacer(Modifier.height(7.dp)); Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 20.sp, fontFamily = NotoSerif)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, icon: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp, vertical = 19.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, if (enabled) Gold else Muted, Modifier.size(24.dp)); Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) { Text(title, color = if (enabled) Mist else Muted, fontSize = 16.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(4.dp)); Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 17.sp) }
        Spacer(Modifier.width(10.dp)); Glyph("arrow", Muted, Modifier.size(17.dp))
    }
}

@Composable
private fun Count(label: String, count: Int, modifier: Modifier) {
    Column(modifier) { Text(count.toString(), fontSize = 30.sp, fontWeight = FontWeight.Light, color = Mist); Spacer(Modifier.height(5.dp)); Eyebrow(label) }
}

@Composable
private fun Eyebrow(text: String, color: Color = Muted) {
    Text(text.uppercase(), color = color, fontSize = 10.sp, fontFamily = OpenSauce, fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp)
}

private fun projectName(state: UiState, id: String) = state.projects.firstOrNull { it.id == id }?.name ?: id

private fun nextProjectNumber(projects: List<UiProject>): String =
    "P%02d".format((projects.mapNotNull { it.id.drop(1).toIntOrNull() }.maxOrNull() ?: 0) + 1)

@Composable
private fun NewProjectDialog(state: UiState, actions: UiActions, onClose: () -> Unit) {
    var number by rememberSaveable { mutableStateOf(nextProjectNumber(state.projects)) }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun create() { error = actions.onCreateProject(number, name); if (error == null) onClose() }
    AlertDialog(onDismissRequest = onClose, containerColor = Steel,
        title = { Text(tr("New project", "Nuevo proyecto")) },
        text = {
            Column {
                OutlinedTextField(value = number, onValueChange = { number = it.uppercase().take(4); error = null }, singleLine = true,
                    label = { Text(tr("Number (PXX)", "Número (PXX)")) }, modifier = Modifier.fillMaxWidth().testTag("plantroom.project-number"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = name, onValueChange = { name = it.take(60); error = null }, singleLine = true,
                    label = { Text(tr("Project name", "Nombre del proyecto")) }, modifier = Modifier.fillMaxWidth().testTag("plantroom.project-name"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { create() }))
                error?.let { Text(it, color = Warning, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = ::create) { Text(tr("Create", "Crear")) } },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel", "Cancelar")) } })
}
private fun filterLabel(filter: String) = when (filter) { "PHOTO" -> tr("Photos", "Fotos"); "AUDIO" -> tr("Voice notes", "Notas de voz"); "REPORT" -> tr("Reports", "Informes"); else -> tr("Sources", "Fuentes") }

@Composable
private fun BrandMark() {
    Image(painterResource(R.drawable.brand_logo), contentDescription = "AI Value Prospector", modifier = Modifier.size(34.dp))
}

@Composable
private fun Glyph(name: String, tint: Color = Gold, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val u = size.width / 24f
        val stroke = 1.7f * u
        fun point(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, point(x1, y1), point(x2, y2), stroke, StrokeCap.Round)
        fun box(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(tint, point(x, y), Size(w * u, h * u), androidx.compose.ui.geometry.CornerRadius(2 * u), style = Stroke(stroke))
        when (name) {
            "search" -> { drawCircle(tint, 6.5f * u, point(10f, 10f), style = Stroke(stroke)); line(15f, 15f, 21f, 21f) }
            "plus" -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
            "close" -> { line(6f, 6f, 18f, 18f); line(6f, 18f, 18f, 6f) }
            "trash" -> { line(4f, 6f, 20f, 6f); line(9f, 6f, 10f, 3f); line(10f, 3f, 14f, 3f); line(14f, 3f, 15f, 6f); box(6f, 6f, 12f, 15f); line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f) }
            "arrow" -> { line(4f, 12f, 20f, 12f); line(14f, 6f, 20f, 12f); line(14f, 18f, 20f, 12f) }
            "down" -> { line(6f, 9f, 12f, 15f); line(12f, 15f, 18f, 9f) }
            "check" -> { line(5f, 12f, 10f, 17f); line(10f, 17f, 20f, 6f) }
            "device" -> { box(6f, 2f, 12f, 20f); line(10f, 18f, 14f, 18f) }
            "settings" -> { line(4f, 7f, 20f, 7f); line(4f, 17f, 20f, 17f); drawCircle(Ink, 3 * u, point(9f, 7f)); drawCircle(tint, 3 * u, point(9f, 7f), style = Stroke(stroke)); drawCircle(Ink, 3 * u, point(16f, 17f)); drawCircle(tint, 3 * u, point(16f, 17f), style = Stroke(stroke)) }
            "image" -> { box(3f, 3f, 18f, 18f); drawCircle(tint, 1.7f * u, point(15.5f, 8f)); line(3f, 17f, 9f, 11f); line(9f, 11f, 18f, 21f) }
            "library" -> { box(3f, 5f, 6f, 16f); box(12f, 5f, 8f, 16f); line(5f, 2f, 18f, 2f) }
            "report" -> { box(5f, 2f, 14f, 20f); line(9f, 8f, 15f, 8f); line(9f, 12f, 15f, 12f); line(9f, 16f, 13f, 16f) }
            "audio" -> { listOf(7f, 14f, 20f, 12f, 6f).forEachIndexed { index, height -> line(4f + index * 4, 12f - height / 2, 4f + index * 4, 12f + height / 2) } }
            "play" -> { val path = Path().apply { moveTo(8 * u, 4 * u); lineTo(20 * u, 12 * u); lineTo(8 * u, 20 * u); close() }; drawPath(path, tint) }
            "stop" -> drawRoundRect(tint, point(5f, 5f), Size(14 * u, 14 * u), androidx.compose.ui.geometry.CornerRadius(2 * u))
            // Hat over round glasses; "incognito-on" fills them.
            "incognito", "incognito-on" -> {
                val filled = name == "incognito-on"
                val crown = Path().apply { moveTo(6.5f * u, 10f * u); lineTo(8f * u, 4f * u); lineTo(16f * u, 4f * u); lineTo(17.5f * u, 10f * u); close() }
                if (filled) drawPath(crown, tint) else drawPath(crown, tint, style = Stroke(stroke))
                line(2f, 10.5f, 22f, 10.5f)
                listOf(7f, 17f).forEach { x -> if (filled) drawCircle(tint, 3.2f * u, point(x, 16.5f)) else drawCircle(tint, 3.2f * u, point(x, 16.5f), style = Stroke(stroke)) }
                line(10.2f, 16.5f, 13.8f, 16.5f)
            }
            "building" -> { box(4f, 2f, 16f, 20f); line(9f, 7f, 9f, 9f); line(15f, 7f, 15f, 9f); line(9f, 13f, 9f, 15f); line(15f, 13f, 15f, 15f) }
        }
    }
}
