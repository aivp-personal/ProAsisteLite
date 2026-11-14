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
         * Fruchterman–Reingold: every pair of nodes pushes apart, every link pulls its ends together, and a weak pull
         * toward the middle keeps separate pieces in view. Nodes start on a sunflower spiral, so the same graph always
         * comes out the same. The result is scaled to fill 0..1 on both axes.
         */
        fun layout(n: Int, links: List<Pair<Int, Int>>, iterations: Int = 300): Pair<FloatArray, FloatArray> {
            val x = FloatArray(n); val y = FloatArray(n)
            if (n == 0) return x to y
            val golden = 2.39996323f
            for (i in 0 until n) { val r = sqrt((i + 0.5f) / n); x[i] = r * cos(i * golden); y[i] = r * sin(i * golden) }
            if (n == 1) { x[0] = 0.5f; y[0] = 0.5f; return x to y }
            val k = 1.6f * sqrt(4f / n) // an ideal distance for n nodes in a 2 × 2 square
            val dx = FloatArray(n); val dy = FloatArray(n)
            for (step in 0 until iterations) {
                val temperature = 0.1f * (1f - step.toFloat() / iterations) + 0.002f
                dx.fill(0f); dy.fill(0f)
                for (i in 0 until n) for (j in i + 1 until n) {
                    var ex = x[i] - x[j]; var ey = y[i] - y[j]
                    var d2 = ex * ex + ey * ey
                    if (d2 < 1e-8f) { ex = 0.01f * ((i * 7 + j) % 5 - 2); ey = 0.01f; d2 = ex * ex + ey * ey }
                    val f = k * k / d2 // repulsion k²/d, along the unit vector: k²/d² times the difference
                    dx[i] += ex * f; dy[i] += ey * f; dx[j] -= ex * f; dy[j] -= ey * f
                }
                for ((a, b) in links) {
                    if (a == b) continue
                    val ex = x[a] - x[b]; val ey = y[a] - y[b]
                    val d = sqrt(ex * ex + ey * ey)
                    val f = d / k // attraction d²/k, along the unit vector
                    dx[a] -= ex * f; dy[a] -= ey * f; dx[b] += ex * f; dy[b] += ey * f
                }
                for (i in 0 until n) {
                    dx[i] -= x[i] * 0.25f * k; dy[i] -= y[i] * 0.25f * k
                    val d = sqrt(dx[i] * dx[i] + dy[i] * dy[i])
                    if (d > 0f) { val m = min(d, temperature) / d; x[i] += dx[i] * m; y[i] += dy[i] * m }
                }
            }
            val minX = x.min(); val maxX = x.max(); val minY = y.min(); val maxY = y.max()
            val span = max(max(maxX - minX, maxY - minY), 1e-6f)
            for (i in 0 until n) { x[i] = (x[i] - minX) / span + (1f - (maxX - minX) / span) / 2f; y[i] = (y[i] - minY) / span + (1f - (maxY - minY) / span) / 2f }
            return x to y
        }
    }
}
