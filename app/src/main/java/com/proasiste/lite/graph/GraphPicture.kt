package com.proasiste.lite.graph

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A graph laid out for drawing. Nodes with the same name are one dot, whatever their type: a model may call the same
 * thing "Appliance" in one fact and "appliance" in the next. A large graph keeps its most linked nodes, so the picture
 * stays readable and quick to lay out; [totalNodes] says how many there were.
 */
data class GraphPicture(
    val nodes: List<Node>,
    val links: List<Link>,
    /** Where each node sits, from 0 to 1 on both axes. */
    val x: FloatArray,
    val y: FloatArray,
    val totalNodes: Int,
    val totalLinks: Int,
    /** Node types, commonest first, lower case. */
    val types: List<String>,
) {
    data class Node(val key: String, val name: String, val type: String, val degree: Int)
    data class Link(val from: Int, val to: Int, val edge: ViewEdge)

    companion object {
        const val MAX_NODES = 300

        fun build(edges: List<ViewEdge>, maxNodes: Int = MAX_NODES, iterations: Int = 300): GraphPicture {
            // One dot per name; its type is the one its facts use most.
            val types = HashMap<String, HashMap<String, Int>>()
            val names = HashMap<String, String>()
            val degree = HashMap<String, Int>()
            fun see(n: GraphNode): String {
                val key = Graph.keyOf(n.name).ifEmpty { n.name.trim().lowercase() }
                names.putIfAbsent(key, n.name.trim())
                types.getOrPut(key) { HashMap() }.merge(n.type.trim().lowercase(), 1, Int::plus)
                degree.merge(key, 1, Int::plus)
                return key
            }
            val ends = edges.map { see(it.from) to see(it.to) }
            val kept = degree.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(maxNodes).map { it.key }
            val index = kept.withIndex().associate { (i, k) -> k to i }
            val links = edges.indices.mapNotNull { i ->
                val a = index[ends[i].first] ?: return@mapNotNull null
                val b = index[ends[i].second] ?: return@mapNotNull null
                Link(a, b, edges[i])
            }
            val shownDegree = IntArray(kept.size).also { d -> links.forEach { d[it.from]++; d[it.to]++ } }
            val nodes = kept.mapIndexed { i, k -> Node(k, names.getValue(k), types.getValue(k).maxBy { it.value }.key, shownDegree[i]) }
            val (x, y) = layout(nodes.size, links.map { it.from to it.to }, iterations)
            return GraphPicture(nodes, links, x, y, degree.size, edges.size,
                nodes.groupingBy { it.type }.eachCount().entries.sortedByDescending { it.value }.map { it.key })
        }

        /**
         * Lays out each connected piece of the graph on its own, then packs the pieces in rows, largest first, so a
         * graph made of one big cluster and many small pairs doesn't spread its pairs to the edges and shrink every
         * line to nothing. Within a piece, Fruchterman–Reingold: every pair of nodes pushes apart and every link pulls
         * toward a length of one unit. Nodes start on a sunflower spiral, so the same graph always comes out the same.
         * The result is scaled to fit 0..1 on both axes, keeping its shape.
         */
        fun layout(n: Int, links: List<Pair<Int, Int>>, iterations: Int = 300): Pair<FloatArray, FloatArray> {
            val x = FloatArray(n); val y = FloatArray(n)
            if (n == 0) return x to y
            // Several facts between the same two names pull like one link.
            val pairs = links.filter { it.first != it.second }.map { (a, b) -> minOf(a, b) to maxOf(a, b) }.distinct()
            // The pieces: nodes joined by links, biggest first.
            val root = IntArray(n) { it }
            fun find(i: Int): Int { var r = i; while (root[r] != r) r = root[r]; var j = i; while (root[j] != r) { val next = root[j]; root[j] = r; j = next }; return r }
            for ((a, b) in pairs) root[find(a)] = find(b)
            val pieces = (0 until n).groupBy(::find).values.sortedWith(compareByDescending<List<Int>> { it.size }.thenBy { it.first() })
            val gap = 1.2f
            val boxes = pieces.map { nodes ->
                val local = nodes.withIndex().associate { (k, i) -> i to k }
                val (px, py) = piece(nodes.size, pairs.filter { it.first in local }.map { local.getValue(it.first) to local.getValue(it.second) }, iterations)
                Triple(nodes, px, py)
            }
            // Shelf packing: fill a row up to a width that makes the whole roughly square, then start the next.
            val area = boxes.sumOf { (_, px, py) -> ((px.max() - px.min() + gap) * (py.max() - py.min() + gap)).toDouble() }
            val rowWidth = max(sqrt(area).toFloat(), boxes.maxOf { (_, px, _) -> px.max() - px.min() + gap })
            var cx = 0f; var cy = 0f; var rowHeight = 0f
            for ((nodes, px, py) in boxes) {
                val w = px.max() - px.min(); val h = py.max() - py.min()
                if (cx > 0f && cx + w > rowWidth) { cx = 0f; cy += rowHeight + gap; rowHeight = 0f }
                nodes.forEachIndexed { k, i -> x[i] = cx + px[k] - px.min(); y[i] = cy + py[k] - py.min() }
                cx += w + gap; rowHeight = max(rowHeight, h)
            }
            if (n == 1) { x[0] = 0.5f; y[0] = 0.5f; return x to y }
            val minX = x.min(); val maxX = x.max(); val minY = y.min(); val maxY = y.max()
            val span = max(max(maxX - minX, maxY - minY), 1e-6f)
            for (i in 0 until n) { x[i] = (x[i] - minX) / span + (1f - (maxX - minX) / span) / 2f; y[i] = (y[i] - minY) / span + (1f - (maxY - minY) / span) / 2f }
            return x to y
        }

        /** One connected piece, links of about one unit long. */
        private fun piece(n: Int, pairs: List<Pair<Int, Int>>, iterations: Int): Pair<FloatArray, FloatArray> {
            val x = FloatArray(n); val y = FloatArray(n)
            if (n == 1) return x to y
            val golden = 2.39996323f
            val spread = sqrt(n.toFloat())
            for (i in 0 until n) { val r = spread * sqrt((i + 0.5f) / n); x[i] = r * cos(i * golden); y[i] = r * sin(i * golden) }
            val dx = FloatArray(n); val dy = FloatArray(n)
            for (step in 0 until iterations) {
                val temperature = spread * 0.15f * (1f - step.toFloat() / iterations) + 0.01f
                dx.fill(0f); dy.fill(0f)
                for (i in 0 until n) for (j in i + 1 until n) {
                    var ex = x[i] - x[j]; var ey = y[i] - y[j]
                    var d2 = ex * ex + ey * ey
                    if (d2 < 1e-6f) { ex = 0.01f * ((i * 7 + j) % 5 - 2); ey = 0.01f; d2 = ex * ex + ey * ey }
                    val f = 1f / d2 // repulsion 1/d along the unit vector
                    dx[i] += ex * f; dy[i] += ey * f; dx[j] -= ex * f; dy[j] -= ey * f
                }
                for ((a, b) in pairs) {
                    val ex = x[a] - x[b]; val ey = y[a] - y[b]
                    val d = sqrt(ex * ex + ey * ey).coerceAtLeast(1e-4f)
                    // Attraction d² − 1: pulls beyond one unit and pushes inside it, so linked dots never sit on each other.
                    val f = (d * d - 1f) / d
                    dx[a] -= ex * f; dy[a] -= ey * f; dx[b] += ex * f; dy[b] += ey * f
                }
                for (i in 0 until n) {
                    dx[i] -= x[i] * 0.02f; dy[i] -= y[i] * 0.02f
                    val d = sqrt(dx[i] * dx[i] + dy[i] * dy[i])
                    if (d > 0f) { val m = min(d, temperature) / d; x[i] += dx[i] * m; y[i] += dy[i] * m }
                }
            }
            return x to y
        }
    }
}
