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
                            selected = hit
                        }, singleLine = true, placeholder = { Text(tr("Find a name", "Buscar un nombre")) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("plantroom.graph-search"))
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            colourOf.forEach { (t, c) -> Legend(c, t) }
                            if (p.types.size > colourOf.size) Legend(OTHER, tr("other", "otros"))
                        }
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val wide = maxWidth > 700.dp
                            val canvas: @Composable (Modifier) -> Unit = { m -> GraphCanvas(p, colourOf, selected, m) { selected = it } }
                            val facts: @Composable (Modifier) -> Unit = { m -> selected?.let { FactsPanel(p, it, g.titles, colourOf, actions, m) } ?: Hint(m) }
                            if (wide) Row(Modifier.fillMaxSize()) { canvas(Modifier.weight(1f).fillMaxHeight()); facts(Modifier.width(340.dp).fillMaxHeight()) }
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
        var merging by remember(p, node) { mutableStateOf(false) }
        Row(Modifier.padding(start = 34.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("${n.type} · ${links.size} facts", "${n.type} · ${links.size} datos"), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { merging = true }, modifier = Modifier.testTag("plantroom.graph-merge")) { Text(tr("Merge names…", "Unir nombres…"), color = Gold, fontSize = 13.sp) }
        }
        if (merging) MergeDialog(p, node, onDismiss = { merging = false }) { keys -> merging = false; actions.onMergeNames(n.key, keys) }
        HorizontalDivider(color = Line)
        LazyColumn(Modifier.fillMaxSize().testTag("plantroom.graph-facts")) {
            items(links) { l -> FactRow(p, l.edge, titles, actions) }
        }
    }
}

/**
 * Picks the names that mean the same as [node]: those sharing a word with it are listed first, and any other can be
 * found by typing. Merging keeps [node]'s name.
 */
@Composable private fun MergeDialog(p: GraphPicture, node: Int, onDismiss: () -> Unit, onMerge: (List<String>) -> Unit) {
    val n = p.nodes[node]
    var query by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<String>() }
    val words = remember(n) { n.key.split(' ').filter { it.length >= 3 }.toSet() }
    val shown = remember(query, chosen.size) {
        val q = Graph.keyOf(query)
        val others = p.nodes.filter { it.key != n.key }
        val hits = if (q.isEmpty()) others.filter { o -> o.key.split(' ').any { it in words } }.sortedWith(compareBy<GraphPicture.Node> { it.key.length }.thenByDescending { it.degree })
            else others.filter { it.key.contains(q) }.sortedByDescending { it.degree }
        (others.filter { it.key in chosen } + hits).distinctBy { it.key }.take(40)
    }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Steel,
        title = { Text(tr("Merge into “${n.name}”", "Unir en «${n.name}»"), fontSize = 18.sp) },
        text = {
            Column {
                Text(tr("Their facts move to “${n.name}” in the files that mention it. Rebuilding the graph keeps the merge; other files keep their own meaning of these words.",
                    "Sus datos pasan a «${n.name}» en los archivos que lo mencionan. Reconstruir el grafo mantiene la unión; los demás archivos conservan su propio sentido de estas palabras."),
                    color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(tr("Find another name", "Buscar otro nombre")) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("plantroom.merge-search"))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shown, key = { it.key }) { o ->
                        Row(Modifier.fillMaxWidth().clickable { if (o.key in chosen) chosen.remove(o.key) else chosen.add(o.key) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(o.key in chosen, { on -> if (on) chosen.add(o.key) else chosen.remove(o.key) })
                            Column { Text(o.name, fontSize = 14.sp); Text(tr("${o.type} · ${o.degree} facts", "${o.type} · ${o.degree} datos"), color = Muted, fontSize = 11.sp) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onMerge(chosen.toList()) }, enabled = chosen.isNotEmpty(), modifier = Modifier.testTag("plantroom.merge-confirm")) {
            Text(if (chosen.isEmpty()) tr("Merge", "Unir") else tr("Merge ${chosen.size}", "Unir ${chosen.size}"), color = Gold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "Cancelar")) } })
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
 * keeps them and their names the same size, so a crowded patch can be opened up. Names show for the most linked dots,
 * and for everything once zoomed in.
 *
 * A tapped dot is shown on its own, in the middle. Its relations come out of it, one labelled branch per relation
 * ("has part →" from the dot, "← must clean" towards it), and the names each relation leads to fan out at the end of
 * its branch. Tapping a name moves to that one; tapping empty space goes back to the whole graph.
 */
@Composable private fun GraphCanvas(p: GraphPicture, colourOf: Map<String, Color>, selected: Int?, m: Modifier, onTap: (Int?) -> Unit) {
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(p, selected) { mutableFloatStateOf(1f) }
    var offset by remember(p, selected) { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer(cacheSize = 400)
    val labelStyle = TextStyle(color = Mist, fontSize = 11.sp)
    val relationStyle = TextStyle(color = Gold, fontSize = 11.sp)
    val radius = remember(p, density) { FloatArray(p.nodes.size) { with(density) { (3f + sqrt(p.nodes[it].degree.toFloat()) * 1.8f).dp.toPx() } } }
    val labelled = remember(p) { p.nodes.indices.sortedByDescending { p.nodes[it].degree }.take(40).filter { p.nodes[it].degree >= 2 }.toSet() }
    val fans = remember(p, selected) { selected?.let { fansOf(p, it) } ?: emptyList() }
    val memberAngle = remember(fans) { fans.flatMap { f -> f.members }.toMap() }
    val pad = with(density) { 28.dp.toPx() }
    // Room for a name at the end of a branch: names are cut to [NAME_CHARS] characters.
    val nameRoom = remember(density) { measurer.measure("Stable, dry surf", labelStyle).size.width + with(density) { 16.dp.toPx() } }
    fun side() = (minOf(size.width, size.height) - 2 * pad).coerceAtLeast(1f)
    fun centre() = Offset(size.width / 2f, size.height / 2f) + offset
    fun reach() = maxOf(minOf(size.width / 2f - nameRoom, size.height / 2f - pad), minOf(size.width, size.height) * 0.26f) * scale
    fun polar(angle: Double, r: Float) = centre() + Offset(kotlin.math.cos(angle).toFloat() * r, kotlin.math.sin(angle).toFloat() * r)
    fun at(i: Int): Offset {
        if (selected != null) {
            if (i == selected) return centre()
            memberAngle[i]?.let { return polar(it, reach()) }
        }
        return Offset(size.width / 2f + (p.x[i] - 0.5f) * side() * scale + offset.x, size.height / 2f + (p.y[i] - 0.5f) * side() * scale + offset.y)
    }
    fun shown(i: Int) = selected == null || i == selected || i in memberAngle
    Canvas(m.clipToBounds().onSizeChanged { size = it }
        .pointerInput(p, selected) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val next = (scale * zoom).coerceIn(0.5f, 12f); val f = next / scale
                val c = Offset(size.width / 2f, size.height / 2f)
                offset = (offset - (centroid - c)) * f + (centroid - c) + pan; scale = next
            }
        }
        .pointerInput(p, selected) {
            detectTapGestures { tap ->
                val hit = p.nodes.indices.filter(::shown).minByOrNull { (at(it) - tap).getDistanceSquared() }
                    ?.takeIf { (at(it) - tap).getDistance() <= radius[it] + 24.dp.toPx() }
                onTap(hit)
            }
        }
        .testTag("plantroom.graph-canvas")) {
        val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
        fun text(t: String, style: TextStyle, x: Float, y: Float, alignEnd: Boolean = false, centred: Boolean = false, force: Boolean = false, backdrop: Boolean = false) {
            val layout = measurer.measure(t, style, softWrap = false, maxLines = 1)
            val left = when { centred -> x - layout.size.width / 2f; alignEnd -> x - layout.size.width; else -> x }
            val topLeft = Offset(left, y - layout.size.height / 2f)
            val box = androidx.compose.ui.geometry.Rect(topLeft, androidx.compose.ui.geometry.Size(layout.size.width.toFloat(), layout.size.height.toFloat())).inflate(2.dp.toPx())
            if (!force && placed.any { it.overlaps(box) }) return
            placed += box
            if (backdrop) drawRoundRect(Ink.copy(alpha = 0.85f), box.topLeft, box.size, androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            drawText(layout, topLeft = topLeft)
        }
        fun cut(s: String) = if (s.length > NAME_CHARS) s.take(NAME_CHARS - 1) + "…" else s
        if (selected == null) {
            for (l in p.links) drawLine(Mist.copy(alpha = 0.45f), at(l.from), at(l.to), strokeWidth = 1.2.dp.toPx())
            for (i in p.nodes.indices) drawCircle(colourOf[p.nodes[i].type] ?: OTHER, radius[i], at(i))
            // Names, most linked first, each only where it doesn't cover a name already drawn.
            for (i in p.nodes.indices.sortedByDescending { p.nodes[it].degree }.filter { it in labelled || scale >= 2.5f }) {
                val pos = at(i)
                if (pos.x < -200 || pos.y < -40 || pos.x > size.width + 10 || pos.y > size.height + 40) continue
                text(p.nodes[i].name.let { if (it.length > 32) it.take(31) + "…" else it }, labelStyle, pos.x + radius[i] + 4.dp.toPx(), pos.y)
            }
            return@Canvas
        }
        val c = centre(); val r = reach()
        // A branch per relation: middle to its point, then out to each name it leads to.
        for (f in fans) {
            val hub = polar(f.angle, r * HUB)
            drawLine(Gold, c, hub, strokeWidth = 2.dp.toPx())
            for ((i, _) in f.members) drawLine(Gold.copy(alpha = 0.45f), hub, at(i), strokeWidth = 1.dp.toPx())
            drawCircle(Gold, 3.dp.toPx(), hub)
        }
        for (f in fans) for ((i, _) in f.members) drawCircle(colourOf[p.nodes[i].type] ?: OTHER, radius[i].coerceAtMost(8.dp.toPx()), at(i))
        drawCircle(colourOf[p.nodes[selected].type] ?: OTHER, 14.dp.toPx(), c)
        drawCircle(Mist, 17.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        text(cut(p.nodes[selected].name), labelStyle.copy(color = Mist, fontSize = 13.sp), c.x, c.y + 30.dp.toPx(), centred = true, force = true, backdrop = true)
        // Relations first, biggest first, so they're never the ones left out; then the names at the ends.
        for (f in fans.sortedByDescending { it.members.size }) {
            val hub = polar(f.angle, r * HUB)
            text(f.label, relationStyle, hub.x, hub.y, centred = true, backdrop = true)
        }
        for (f in fans) for ((i, a) in f.members) {
            val pos = at(i); val gap = radius[i].coerceAtMost(8.dp.toPx()) + 5.dp.toPx()
            val right = kotlin.math.cos(a) >= 0
            text(cut(p.nodes[i].name), labelStyle, if (right) pos.x + gap else pos.x - gap, pos.y, alignEnd = !right)
        }
    }
}

private const val NAME_CHARS = 18
/** How far out a relation's point sits, as a share of the way to the names. */
private const val HUB = 0.5f

/** One relation from the tapped dot: its label, the direction of its branch, and the names it leads to with theirs. */
private data class Fan(val label: String, val angle: Double, val members: List<Pair<Int, Double>>)

/**
 * The tapped dot's relations as branches. Names linked the same way share a branch; each branch gets a slice of the
 * circle in proportion to how many names it leads to, and its names spread across that slice.
 */
private fun fansOf(p: GraphPicture, s: Int): List<Fan> {
    val rel = LinkedHashMap<Int, java.util.TreeSet<String>>()
    for (l in p.links) {
        if ((l.from == s) == (l.to == s)) continue
        if (l.from == s) rel.getOrPut(l.to) { java.util.TreeSet() } += relationLabel(l.edge.type) + " →"
        else rel.getOrPut(l.from) { java.util.TreeSet() } += "← " + relationLabel(l.edge.type)
    }
    val groups = rel.entries.groupBy({ it.value.joinToString(" · ") }, { it.key }).entries.sortedByDescending { it.value.size }
    val total = groups.sumOf { it.value.size }.coerceAtLeast(1)
    var start = -Math.PI / 2
    return groups.map { (label, members) ->
        val slice = 2 * Math.PI * members.size / total
        val angles = members.sortedBy { p.nodes[it].name.lowercase() }.mapIndexed { k, i -> i to start + slice * (k + 0.5) / members.size }
        val text = label.let { if (it.length > 32) it.take(31) + "…" else it } + if (members.size > 1) " ×${members.size}" else ""
        Fan(text, start + slice / 2, angles).also { start += slice }
    }
}
