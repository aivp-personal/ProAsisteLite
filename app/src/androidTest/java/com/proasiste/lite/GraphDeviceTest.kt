package com.proasiste.lite

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.proasiste.lite.graph.Direction
import com.proasiste.lite.graph.Graph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The graph's recursive queries on the phone's own SQLite, which can be older than the one unit tests use. */
@RunWith(AndroidJUnit4::class)
class GraphDeviceTest {
    private lateinit var store: Store
    private lateinit var graph: Graph

    @Before fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = Store(context, null)
        graph = Graph(store)
        val file = File(context.cacheDir, "graph-test.pdf").apply { writeText("graph test") }
        store.add(Source("DOC", file.path, "pdf", "T01", "", "2026-11-07", "Doc", false))
    }
    @After fun close() = store.close()

    @Test fun walksLoopsAndDeletes() {
        val (a, b, c, d) = listOf("A", "B", "C", "D").map { graph.upsertNode("T01", "Fallo", it) }
        for ((x, y) in listOf(a to b, b to c, c to d, c to a)) graph.addEdge("T01", x, y, "CITA", "DOC", 1)
        val out = graph.reach(a, Graph.MAX_HOPS, direction = Direction.OUT).map { it.node.name to it.hops }
        assertEquals(listOf("B" to 1, "C" to 2, "D" to 3), out)
        assertEquals(listOf("C" to 1), graph.reach(a, 1, direction = Direction.IN).map { it.node.name to it.hops })
        assertEquals(3, graph.reach(a, 2, linkType = "CITA").size)

        val nodes = (1..40).map { graph.upsertNode("T01", "Ruling", "N$it") }
        for (x in nodes) for (y in nodes) if (x != y) graph.addEdge("T01", x, y, "CITA", "DOC", 1)
        val start = System.nanoTime()
        assertEquals(39, graph.reach(nodes.first(), 3).size)
        println("GRAPH_DEVICE dense 3-hop walk ${(System.nanoTime() - start) / 1_000_000} ms")

        assertTrue(store.deleteSource("DOC"))
        assertTrue(graph.findNodes("T01", "A").isEmpty())
    }
}
