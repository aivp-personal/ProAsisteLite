package com.proasiste.lite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.data.AppDatabase
import com.proasiste.lite.data.Library
import com.proasiste.lite.falkor.RedisError
import com.proasiste.lite.falkor.RespClient
import com.proasiste.lite.graph.Cypher
import com.proasiste.lite.graph.FalkorGraphStore
import com.proasiste.lite.graph.GraphCleaner
import com.proasiste.lite.graph.GraphEdge
import com.proasiste.lite.graph.GraphNeighbor
import com.proasiste.lite.graph.GraphNode
import com.proasiste.lite.graph.GraphSource
import com.proasiste.lite.graph.GraphStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

/** The parts of the graph that need no server. The real FalkorDB is tested on the phone, in androidTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GraphLogicTest {
    private fun client(reply: String, sent: ByteArrayOutputStream = ByteArrayOutputStream()) =
        RespClient(ByteArrayInputStream(reply.toByteArray()), sent)

    @Test fun commandsAreSentInTheRedisFormatCountingBytes() {
        val sent = ByteArrayOutputStream()
        client("+OK\r\n", sent).call("SET", "ñ")
        assertEquals("*2\r\n$3\r\nSET\r\n$2\r\nñ\r\n", sent.toString())
    }

    @Test fun repliesBecomeKotlinValues() {
        assertEquals("OK", client("+OK\r\n").call("X"))
        assertEquals(42L, client(":42\r\n").call("X"))
        assertEquals("héllo", client("$6\r\nhéllo\r\n").call("X"))
        assertEquals(null, client("$-1\r\n").call("X"))
        assertEquals(listOf("a", listOf(1L, null)), client("*2\r\n$1\r\na\r\n*2\r\n:1\r\n$-1\r\n").call("X"))
    }

    @Test fun errorRepliesAreErrorsAndAClosedConnectionIsNot() {
        val error = assertThrows(RedisError::class.java) { client("-ERR nope\r\n").call("X") }
        assertEquals("ERR nope", error.message)
        assertThrows(EOFException::class.java) { client("").call("X") }
        assertThrows(EOFException::class.java) { client("$5\r\nab").call("X") }
    }

    @Test fun anErrorInsideAnArrayLeavesTheNextReplyInLine() {
        val client = client("*2\r\n-ERR inner\r\n:1\r\n+NEXT\r\n")
        assertThrows(RedisError::class.java) { client.call("X") }
        assertEquals("NEXT", client.call("Y"))
    }

    @Test fun parametersAreEscapedSoTextCannotChangeAQuery() {
        assertEquals("MATCH (n)", Cypher.query("MATCH (n)"))
        val query = Cypher.query("RETURN \$a, \$b", mapOf("a" to "x\" DELETE n \\ \n", "b" to 7))
        assertEquals("CYPHER a=\"x\\\" DELETE n \\\\ \\n\" b=7 RETURN \$a, \$b", query)
        assertThrows(IllegalArgumentException::class.java) { Cypher.query("x", mapOf("a" to 1.5)) }
    }

    @Test fun repliesWithoutRowsHaveNoRows() {
        assertTrue(FalkorGraphStore.rowsOf(listOf(listOf("stats"))).isEmpty())
        assertEquals(listOf(listOf("a", 1L)), FalkorGraphStore.rowsOf(listOf(listOf("h"), listOf(listOf("a", 1L)), listOf("stats"))))
    }

    @Test fun anEdgeCannotCiteADocumentOutsideItsProject() {
        val store = FalkorGraphStore({ error("must not reach the server") }, { _, _ -> false })
        assertThrows(IllegalArgumentException::class.java) { store.addEdge(1, "a", "b", "cites", GraphSource(9, 1)) }
        assertThrows(IllegalArgumentException::class.java) { store.neighbors(1, "a", 99) }
    }

    @Test fun aGraphThatDoesNotExistYetHasNoRows() {
        val store = FalkorGraphStore({ throw RedisError("ERR Invalid graph operation on empty key") }, { _, _ -> true })
        assertTrue(store.findNodes(1, "x").isEmpty())
    }

    @Test fun cleanupsWaitWhileTheServerIsDownAndRunInOrderOnceItIsBack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppDatabase(context, null).use { database ->
            val library = Library(database)
            val project = library.createProject("P")
            val other = library.createProject("Other")
            val document = library.addDocument(project.id, "D", File("d"), "sha", 1, 1)
            library.deleteDocument(document)
            library.deleteProject(other.id)
            val graph = RecordingGraph()

            graph.available = false
            assertFalse(GraphCleaner(library, graph).flush())
            assertEquals(2, library.pendingGraphCleanups().size)

            graph.available = true
            assertTrue(GraphCleaner(library, graph).flush())
            assertTrue(library.pendingGraphCleanups().isEmpty())
            assertEquals(listOf("document ${project.id} $document", "project ${other.id}"), graph.removed)
        }
    }

    private class RecordingGraph : GraphStore {
        var available = true
        val removed = mutableListOf<String>()

        override fun removeDocument(projectId: Long, documentId: Long) {
            if (!available) throw IOException("down")
            removed += "document $projectId $documentId"
        }

        override fun removeProject(projectId: Long) {
            if (!available) throw IOException("down")
            removed += "project $projectId"
        }

        override fun upsertNode(projectId: Long, type: String, name: String): GraphNode = error("unused")
        override fun addEdge(projectId: Long, fromKey: String, toKey: String, type: String, source: GraphSource): GraphEdge = error("unused")
        override fun findNodes(projectId: Long, name: String, type: String?): List<GraphNode> = error("unused")
        override fun edgesOf(projectId: Long, key: String): List<GraphEdge> = error("unused")
        override fun neighbors(projectId: Long, key: String, maxHops: Int): List<GraphNeighbor> = error("unused")
    }
}
