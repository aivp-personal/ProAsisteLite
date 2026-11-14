package com.proasiste.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.proasiste.lite.graph.Graph
import com.proasiste.lite.graph.GraphPicture
import com.proasiste.lite.graph.ViewEdge
import kotlin.math.sqrt

/** Colours for the commonest node types; the rest are grey. */
private val TYPE_COLOURS = listOf(Color(0xFF7F77DD), Color(0xFF1D9E75), Color(0xFFD85A30), Color(0xFFD4537E), Color(0xFF378ADD), Color(0xFFBA7517))
private val OTHER = Color(0xFF888780)

internal fun relationLabel(type: String) = type.replace('_', ' ').lowercase()

/**
 * The graph as dots and lines: drag to move, pinch to zoom, tap a dot to see what the files say about it. Each fact
 * opens its page with the paragraph highlighted. Wide screens put the facts beside the graph, narrow ones below it.
 */
@Composable
internal fun GraphScreen(g: UiGraph, actions: UiActions) {
    BackHandler { actions.onCloseGraph() }
    Dialog(onDismissRequest = actions.onCloseGraph, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MaterialTheme(colorScheme = darkColorScheme(), typography = BrandTypography) {
          CompositionLocalProvider(LocalContentColor provides Mist) {
            Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding().testTag("plantroom.graph")) {
                val p = g.picture
                var selected by remember(p) { mutableStateOf<Int?>(null) }
                var query by remember(p) { mutableStateOf("") }
                var focus by remember(p) { mutableStateOf<Int?>(null) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = actions.onCloseGraph) { Text(tr("Close", "Cerrar")) }
                    Column(Modifier.weight(1f)) {
                        Text(g.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p != null) Text(
                            if (p.nodes.size < p.totalNodes) tr("The ${p.nodes.size} most linked of ${p.totalNodes} names · ${p.totalLinks} facts", "Los ${p.nodes.size} nombres más enlazados de ${p.totalNodes} · ${p.totalLinks} datos")
                            else tr("${p.nodes.size} names · ${p.totalLinks} facts", "${p.nodes.size} nombres · ${p.totalLinks} datos"), color = Muted, fontSize = 12.sp)
                    }
                }
                when {
                    p == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Gold) }
                    p.nodes.isEmpty() -> Text(tr("No graph yet. Build one with “Build graph with DeepSeek” in the menu.", "Aún no hay grafo. Constrúyelo con «Construir el grafo con DeepSeek» en el menú."),
                        color = Muted, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
                    else -> {
                        val colourOf = remember(p) { p.types.take(TYPE_COLOURS.size).withIndex().associate { (i, t) -> t to TYPE_COLOURS[i] } }
                        OutlinedTextField(query, { q ->
                            query = q
                            val key = Graph.keyOf(q)
                            val hit = if (key.isEmpty()) null else p.nodes.indexOfFirst { it.key.startsWith(key) }.takeIf { it >= 0 } ?: p.nodes.indexOfFirst { it.key.contains(key) }.takeIf { it >= 0 }
                            selected = hit; focus = hit
                        }, singleLine = true, placeholder = { Text(tr("Find a name", "Buscar un nombre")) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("plantroom.graph-search"))
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            colourOf.forEach { (t, c) -> Legend(c, t) }
                            if (p.types.size > colourOf.size) Legend(OTHER, tr("other", "otros"))
                        }
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val wide = maxWidth > 700.dp
                            val canvas: @Composable (Modifier) -> Unit = { m -> GraphCanvas(p, colourOf, selected, focus, m) { selected = it; focus = null } }
                            val facts: @Composable (Modifier) -> Unit = { m -> selected?.let { FactsPanel(p, it, g.titles, colourOf, actions, m) } ?: Hint(m) }
                            if (wide) Row(Modifier.fillMaxSize()) { canvas(Modifier.weight(1f).fillMaxHeight()); facts(Modifier.width(380.dp).fillMaxHeight()) }
                            else Column(Modifier.fillMaxSize()) { canvas(Modifier.weight(1.3f).fillMaxWidth()); facts(Modifier.weight(1f).fillMaxWidth()) }
                        }
                    }
                }
            }
          }
        }
    }
}

@Composable private fun Legend(c: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(9.dp).background(c, CircleShape)); Spacer(Modifier.width(5.dp)); Text(label, color = Muted, fontSize = 12.sp)
}

@Composable private fun Hint(m: Modifier) = Column(m.background(Steel).padding(16.dp)) {
    Text(tr("Tap a dot", "Toca un punto"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
    Text(tr("Its facts appear here, each with the file and page it came from. Drag to move, pinch to zoom.", "Sus datos aparecen aquí, cada uno con el archivo y la página de donde salió. Arrastra para mover, pellizca para acercar."),
        color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
}

/** The facts that touch one node, by file and page; tapping one opens its page. */
@Composable private fun FactsPanel(p: GraphPicture, node: Int, titles: Map<String, String>, colourOf: Map<String, Color>, actions: UiActions, m: Modifier) {
    val n = p.nodes[node]
    val links = remember(p, node) { p.links.filter { it.from == node || it.to == node }.sortedWith(compareBy({ titles[it.edge.sourceId] ?: "" }, { it.edge.page })) }
    Column(m.background(Steel)) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(colourOf[n.type] ?: OTHER, CircleShape)); Spacer(Modifier.width(8.dp))
            Text(n.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(tr("${n.type} · ${links.size} facts", "${n.type} · ${links.size} datos"), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 34.dp, bottom = 6.dp))
        HorizontalDivider(color = Line)
        LazyColumn(Modifier.fillMaxSize().testTag("plantroom.graph-facts")) {
            items(links) { l -> FactRow(p, l.edge, titles, actions) }
        }
    }
}

@Composable private fun FactRow(p: GraphPicture, e: ViewEdge, titles: Map<String, String>, actions: UiActions) {
    Column(Modifier.fillMaxWidth().clickable { actions.onOpenGraphFact(e) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("${e.from.name} → ${relationLabel(e.type)} → ${e.to.name}", color = Gold, fontSize = 12.sp, lineHeight = 16.sp)
        if (e.fact.isNotBlank()) Text(e.fact, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = NotoSerif, modifier = Modifier.padding(vertical = 3.dp))
        Text(listOfNotNull(titles[e.sourceId], tr("page ${e.page}", "página ${e.page}"), if (e.place.isNotBlank()) tr("highlighted", "resaltado") else null).joinToString("  ·  "),
            color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 16.dp))
}

/**
 * Dots and lines on a canvas. Positions are 0..1 from the layout, fitted to the canvas; zoom moves the dots apart but
 * keeps them and their names the same size, so a crowded patch can be opened up. Names show for the most linked
 * dots, for everything once zoomed in, and for the tapped dot and its neighbours.
 */
@Composable private fun GraphCanvas(p: GraphPicture, colourOf: Map<String, Color>, selected: Int?, focus: Int?, m: Modifier, onTap: (Int?) -> Unit) {
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(p) { mutableFloatStateOf(1f) }
    var offset by remember(p) { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer(cacheSize = 400)
    val labelStyle = TextStyle(color = Mist, fontSize = 11.sp)
    val radius = remember(p, density) { FloatArray(p.nodes.size) { with(density) { (3f + sqrt(p.nodes[it].degree.toFloat()) * 1.8f).dp.toPx() } } }
    val labelled = remember(p) { p.nodes.indices.sortedByDescending { p.nodes[it].degree }.take(40).filter { p.nodes[it].degree >= 2 }.toSet() }
    val neighbours = remember(p, selected) { selected?.let { s -> p.links.filter { it.from == s || it.to == s }.flatMap { listOf(it.from, it.to) }.toSet() } ?: emptySet() }
    val pad = with(density) { 28.dp.toPx() }
    fun side() = (minOf(size.width, size.height) - 2 * pad).coerceAtLeast(1f)
    fun at(i: Int) = Offset(size.width / 2f + (p.x[i] - 0.5f) * side() * scale + offset.x, size.height / 2f + (p.y[i] - 0.5f) * side() * scale + offset.y)
    // Searching moves the found dot to the middle.
    LaunchedEffect(focus, size) { focus?.let { f -> if (size != IntSize.Zero) { scale = maxOf(scale, 1.8f); offset = Offset(-(p.x[f] - 0.5f) * side() * scale, -(p.y[f] - 0.5f) * side() * scale) } } }
    Canvas(m.clipToBounds().onSizeChanged { size = it }
        .pointerInput(p) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val next = (scale * zoom).coerceIn(0.5f, 12f); val f = next / scale
                val c = Offset(size.width / 2f, size.height / 2f)
                offset = (offset - (centroid - c)) * f + (centroid - c) + pan; scale = next
            }
        }
        .pointerInput(p) {
            detectTapGestures { tap ->
                val hit = p.nodes.indices.minByOrNull { (at(it) - tap).getDistanceSquared() }
                    ?.takeIf { (at(it) - tap).getDistance() <= radius[it] + 24.dp.toPx() }
                onTap(hit)
            }
        }
        .testTag("plantroom.graph-canvas")) {
        val dim = selected != null
        for (l in p.links) {
            val on = selected != null && (l.from == selected || l.to == selected)
            drawLine(if (on) Gold else Muted.copy(alpha = if (dim) 0.08f else 0.35f), at(l.from), at(l.to), strokeWidth = if (on) 2.dp.toPx() else 1.dp.toPx())
        }
        for (i in p.nodes.indices) {
            val faded = dim && i !in neighbours
            val c = (colourOf[p.nodes[i].type] ?: OTHER).copy(alpha = if (faded) 0.2f else 1f)
            drawCircle(c, radius[i], at(i))
            if (i == selected) drawCircle(Mist, radius[i] + 3.dp.toPx(), at(i), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
        // Names, most linked first, each only where it doesn't cover a name already drawn.
        val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
        val order = if (dim) neighbours.sortedWith(compareBy<Int> { it != selected }.thenByDescending { p.nodes[it].degree })
            else p.nodes.indices.sortedByDescending { p.nodes[it].degree }.filter { it in labelled || scale >= 2.5f }
        for (i in order) {
            val pos = at(i)
            if (pos.x < -200 || pos.y < -40 || pos.x > size.width + 10 || pos.y > size.height + 40) continue
            val text = p.nodes[i].name.let { if (it.length > 32) it.take(31) + "…" else it }
            val layout = measurer.measure(text, labelStyle.copy(color = if (i == selected) Gold else Mist), softWrap = false, maxLines = 1)
            val topLeft = Offset(pos.x + radius[i] + 4.dp.toPx(), pos.y - layout.size.height / 2f)
            val box = androidx.compose.ui.geometry.Rect(topLeft, androidx.compose.ui.geometry.Size(layout.size.width.toFloat(), layout.size.height.toFloat())).inflate(2.dp.toPx())
            if (i != selected && placed.any { it.overlaps(box) }) continue
            placed += box
            drawText(layout, topLeft = topLeft)
        }
    }
}
