package com.proasiste.lite

import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.graph.Direction
import com.proasiste.lite.graph.Graph
import com.proasiste.lite.graph.GraphNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class GraphTest {
    private lateinit var store: Store
    private lateinit var graph: Graph

    @Before fun open() {
        store = Store(ApplicationProvider.getApplicationContext(), null)
        graph = Graph(store)
        for ((id, project) in listOf("DOC" to "P01", "OTHER" to "P02")) {
            val file = File(store.library, "$id.pdf").apply { writeText(id) }
            store.add(Source(id, file.path, "pdf", project, "", "2026-11-07", id, false))
        }
    }
    @After fun close() = store.close()

    private fun node(name: String, type: String = "Ruling") = graph.upsertNode("P01", type, name)
    private fun link(from: GraphNode, to: GraphNode, type: String = "CITES") = graph.addEdge("P01", from, to, type, "DOC", 1)
    private fun names(reached: List<com.proasiste.lite.graph.Reached>) = reached.map { it.node.name to it.hops }

    @Test fun sameNameIgnoringCaseAccentsAndPunctuationIsOneNode() {
        val a = graph.upsertNode("P01", "Court", "Excma. Corte  Suprema")
        val b = graph.upsertNode("P01", "Court", "excma corte suprema")
        assertEquals(a.id, b.id)
        assertEquals("Excma. Corte Suprema", b.name)
        assertEquals(a.id, graph.upsertNode("P01", "Court", "EXCMA CORTE SUPREMA").id)
        assertTrue(a.id != graph.upsertNode("P01", "Ruling", "Excma Corte Suprema").id) // type is part of identity
        assertEquals(listOf(a.id), graph.findNodes("P01", "córte", "Court").map { it.id })
    }

    @Test fun typesAreFreeText() {
        val a = graph.upsertNode("P01", "Fallo", "Rol 1234-2020")
        val b = graph.upsertNode("P01", "Norma", "Ley 17.322")
        assertTrue(graph.addEdge("P01", a, b, "CITA", "DOC", 3))
        assertEquals("CITA", graph.edgesOf(a).single().type)
        assertEquals(3, graph.edgesOf(b).single().page)
    }

    @Test fun duplicateLinksAreRecordedOnce() {
        val a = node("A"); val b = node("B")
        assertTrue(link(a, b))
        assertFalse(link(a, b))
        assertEquals(1, graph.edgesOf(a).size)
    }

    @Test fun linksMustStayInsideTheirProject() {
        val a = node("A"); val elsewhere = graph.upsertNode("P02", "Ruling", "B")
        assertThrows(IllegalArgumentException::class.java) { graph.addEdge("P01", a, elsewhere, "CITES", "DOC", 1) }
        assertThrows(IllegalArgumentException::class.java) { graph.addEdge("P01", a, node("C"), "CITES", "OTHER", 1) }
    }

    @Test fun multiStepWalkFindsNearestDistance() {
        val a = node("A"); val b = node("B"); val c = node("C"); val d = node("D")
        link(a, b); link(b, c); link(c, d); link(a, c) // C is reachable in 1 and in 2 steps
        assertEquals(listOf("B" to 1, "C" to 1, "D" to 2), names(graph.reach(a, 2, direction = Direction.OUT)))
        assertEquals(listOf("B" to 1, "C" to 1), names(graph.reach(a, 1, direction = Direction.OUT)))
    }

    @Test fun loopsEnd() {
        val a = node("A"); val b = node("B"); val c = node("C")
        link(a, b); link(b, c); link(c, a); link(b, a)
        assertEquals(listOf("B" to 1, "C" to 1), names(graph.reach(a, Graph.MAX_HOPS)))
        assertEquals(listOf("B" to 1, "C" to 2), names(graph.reach(a, Graph.MAX_HOPS, direction = Direction.OUT)))
    }

    @Test fun directionAndLinkType() {
        val a = node("A"); val b = node("B"); val law = node("Ley 1", "Law")
        link(a, b); link(b, law, "APPLIES")
        assertEquals(listOf("A" to 1), names(graph.reach(b, 1, direction = Direction.IN)))
        assertEquals(listOf("Ley 1" to 1), names(graph.reach(b, 1, linkType = "APPLIES")))
        assertEquals(listOf("B" to 1), names(graph.reach(a, 3, linkType = "CITES")))
        assertThrows(IllegalArgumentException::class.java) { graph.reach(a, Graph.MAX_HOPS + 1) }
    }

    @Test fun denseGraphStaysBounded() {
        val nodes = (1..40).map { node("N$it") }
        for (x in nodes) for (y in nodes) if (x != y) link(x, y)
        val start = System.nanoTime()
        val reached = graph.reach(nodes.first(), 3)
        println("dense 40-node graph, 3 hops: ${(System.nanoTime() - start) / 1_000_000} ms")
        assertEquals(39, reached.size)
        assertTrue(reached.all { it.hops == 1 })
    }
}
