package com.proasiste.lite

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.proasiste.lite.falkor.FalkorClient
import com.proasiste.lite.falkor.FalkorServer
import com.proasiste.lite.graph.FalkorGraphStore
import com.proasiste.lite.graph.GraphSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real FalkorDB, in the real app sandbox, on the phone. */
@RunWith(AndroidJUnit4::class)
class FalkorGraphStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val server = FalkorServer(context)
    private lateinit var client: FalkorClient
    private lateinit var graph: FalkorGraphStore
    private val source = GraphSource(11, 3)

    @Before fun start() {
        server.start()
        client = FalkorClient(server)
        graph = newStore()
        graph.removeProject(PROJECT)
        graph.removeProject(OTHER)
    }

    @After fun stop() {
        graph.removeProject(PROJECT)
        graph.removeProject(OTHER)
        client.close()
        server.stop()
    }

    private fun newStore() = FalkorGraphStore({ arguments -> client.call(*arguments) }, { _, _ -> true })

    @Test fun sameNameIgnoringCaseAndAccentsIsOneNode() {
        val first = graph.upsertNode(PROJECT, "person", "José Pérez")
        val second = graph.upsertNode(PROJECT, "person", " jose perez ")
        assertEquals(first, second)
        assertEquals("José Pérez", second.name)
        assertEquals(listOf(first), graph.findNodes(PROJECT, "JOSE PEREZ"))
    }

    @Test fun anotherTypeIsAnotherNodeAndProjectsAreSeparate() {
        val person = graph.upsertNode(PROJECT, "person", "Lima")
        val place = graph.upsertNode(PROJECT, "place", "Lima")
        assertTrue(person.key != place.key)
        assertEquals(listOf(place), graph.findNodes(PROJECT, "Lima", "place"))
        assertTrue(graph.findNodes(OTHER, "Lima").isEmpty())
    }

    @Test fun textFromDocumentsCannotChangeAQuery() {
        val hostile = "x\" }) DETACH DELETE n // \\ 'ñ' \n \$key"
        val node = graph.upsertNode(PROJECT, "note", hostile)
        val other = graph.upsertNode(PROJECT, "note", "safe")
        assertEquals(hostile.trim(), node.name)
        assertEquals(listOf(node), graph.findNodes(PROJECT, hostile))
        assertEquals(listOf(other), graph.findNodes(PROJECT, "safe"))
    }

    @Test fun edgesKeepTheirSourceAndDuplicatesCollapse() {
        val a = graph.upsertNode(PROJECT, "ruling", "A")
        val b = graph.upsertNode(PROJECT, "article", "B")
        val edge = graph.addEdge(PROJECT, a.key, b.key, "cites", source)
        graph.addEdge(PROJECT, a.key, b.key, "cites", source)
        assertEquals(listOf(edge), graph.edgesOf(PROJECT, b.key))
    }

    @Test fun anEdgeNeedsItsNodes() {
        val a = graph.upsertNode(PROJECT, "x", "A")
        assertThrows(IllegalArgumentException::class.java) { graph.addEdge(PROJECT, a.key, "x:missing", "rel", source) }
    }

    @Test fun neighborsWalkBothWaysNearestFirstAndSurviveCycles() {
        val a = graph.upsertNode(PROJECT, "x", "A")
        val b = graph.upsertNode(PROJECT, "x", "B")
        val c = graph.upsertNode(PROJECT, "x", "C")
        val d = graph.upsertNode(PROJECT, "x", "D")
        graph.addEdge(PROJECT, a.key, b.key, "rel", source)
        graph.addEdge(PROJECT, c.key, b.key, "rel", source)
        graph.addEdge(PROJECT, c.key, d.key, "rel", source)
        graph.addEdge(PROJECT, d.key, a.key, "rel", source)
        assertEquals(listOf("B" to 1, "D" to 1), graph.neighbors(PROJECT, a.key, 1).map { it.node.name to it.hops })
        assertEquals(listOf("B" to 1, "D" to 1, "C" to 2), graph.neighbors(PROJECT, a.key, 2).map { it.node.name to it.hops })
    }

    @Test fun removingADocumentRemovesItsEdgesAndTheNodesOnlyItSupported() {
        val shared = graph.upsertNode(PROJECT, "article", "Art. 1")
        val kept = graph.upsertNode(PROJECT, "ruling", "Rol 6")
        val gone = graph.upsertNode(PROJECT, "ruling", "Rol 5")
        graph.addEdge(PROJECT, gone.key, shared.key, "cites", GraphSource(1, 1))
        graph.addEdge(PROJECT, kept.key, shared.key, "cites", GraphSource(2, 1))

        graph.removeDocument(PROJECT, 1)

        assertEquals(listOf(GraphSource(2, 1)), graph.edgesOf(PROJECT, shared.key).map { it.source })
        assertTrue(graph.findNodes(PROJECT, "Rol 5").isEmpty())
        assertEquals(1, graph.findNodes(PROJECT, "Rol 6").size)
    }

    @Test fun removingAProjectLeavesTheOthers() {
        graph.upsertNode(PROJECT, "x", "A")
        graph.upsertNode(OTHER, "x", "A")
        graph.removeProject(PROJECT)
        assertTrue(graph.findNodes(PROJECT, "A").isEmpty())
        assertEquals(1, graph.findNodes(OTHER, "A").size)
        graph.upsertNode(PROJECT, "x", "Back")
        assertEquals(1, graph.findNodes(PROJECT, "Back").size)
    }

    @Test fun dataSurvivesACleanStop() {
        val a = graph.upsertNode(PROJECT, "x", "A")
        val b = graph.upsertNode(PROJECT, "x", "B")
        graph.addEdge(PROJECT, a.key, b.key, "rel", source)
        client.close()
        server.stop()

        server.start()
        assertEquals(1, graph.edgesOf(PROJECT, a.key).size)
    }

    @Test fun dataSurvivesACrashOnceItHasReachedDisk() {
        val a = graph.upsertNode(PROJECT, "x", "A")
        val b = graph.upsertNode(PROJECT, "x", "B")
        graph.addEdge(PROJECT, a.key, b.key, "rel", source)
        Thread.sleep(APPEND_FILE_FLUSH_MILLIS)
        server.kill()

        // The client notices the lost connection and brings the server back by itself.
        assertEquals(1, graph.edgesOf(PROJECT, a.key).size)
    }

    private companion object {
        const val PROJECT = 9001L
        const val OTHER = 9002L
        const val APPEND_FILE_FLUSH_MILLIS = 2_500L
    }
}
