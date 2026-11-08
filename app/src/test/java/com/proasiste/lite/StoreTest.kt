package com.proasiste.lite

import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.graph.Graph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class StoreTest {
    private lateinit var store: Store

    @Before fun open() { store = Store(ApplicationProvider.getApplicationContext(), null) }
    @After fun close() = store.close()

    /** A vector pointing mostly along [axis], so the closest chunk to a query is predictable. */
    private fun vector(axis: Int, noise: Long = axis.toLong()): FloatArray {
        val random = Random(noise)
        return FloatArray(Store.DIMENSIONS) { if (it == axis) 1f else random.nextFloat() * 0.01f }
    }

    private fun source(id: String, project: String = "P01", contents: String = id): Source {
        val file = File(store.library, "$id.pdf").apply { writeText(contents) }
        return Source(id, file.path, "pdf", project, "", "2026-11-07", "Title $id", false).also(store::add)
    }

    private fun chunk(source: Source, ordinal: Int, text: String, axis: Int) = VectorChunk(source.id, ordinal, ordinal + 1, 0, text, vector(axis))

    @Test fun meaningAndWordsBothRank() {
        val a = source("A"); val b = source("B")
        store.saveChunks(a, listOf(chunk(a, 0, "the filter must be cleaned monthly", 1), chunk(a, 1, "warranty terms", 2)))
        store.saveChunks(b, listOf(chunk(b, 0, "pump model XR-200 serial number", 3)))

        val byMeaning = store.search(vector(3, 99), "P01", queryText = null)
        assertEquals("B", byMeaning.hits.first().source.id)

        val byWords = store.search(vector(2, 99), "P01", queryText = "clean the filter")
        assertEquals("the filter must be cleaned monthly", byWords.passages.first().chunk.text)
        assertEquals(1, byWords.passages.first().chunk.page)
    }

    @Test fun hitsHaveOneRowPerSourceAndPassagesNeedText() {
        val a = source("A"); val photo = source("PHOTO")
        store.saveChunks(a, listOf(chunk(a, 0, "first", 1), chunk(a, 1, "second", 1)))
        store.saveChunks(photo, listOf(chunk(photo, 0, "", 1)))
        val result = store.search(vector(1, 99), "P01")
        assertEquals(listOf("A", "PHOTO"), result.hits.map { it.source.id }.sorted())
        assertTrue(result.passages.none { it.chunk.text.isBlank() })
        assertEquals(2, result.passages.size)
    }

    @Test fun searchSeesNewChunksAndOnlyItsProject() {
        val a = source("A"); val other = source("X", project = "P02")
        store.saveChunks(other, listOf(chunk(other, 0, "other project", 1)))
        assertTrue(store.search(vector(1), "P01").hits.isEmpty())
        store.saveChunks(a, listOf(chunk(a, 0, "now here", 1)))
        assertEquals(listOf("A"), store.search(vector(1), "P01").hits.map { it.source.id })
    }

    @Test fun duplicatesAreFoundByContentWithinAProject() {
        source("A", contents = "same bytes")
        val copy = File(store.library, "copy.pdf").apply { writeText("same bytes") }
        assertEquals("A", store.duplicateOf(copy, "P01")?.id)
        assertNull(store.duplicateOf(copy, "P02"))
    }

    @Test fun deletingASourceRemovesChunksWordsLinksAndUnlinkedNodes() {
        val a = source("A"); val b = source("B")
        store.saveChunks(a, listOf(chunk(a, 0, "filter cleaning", 1)))
        store.saveChunks(b, listOf(chunk(b, 0, "filter warranty", 2)))
        val graph = Graph(store)
        val court = graph.upsertNode("P01", "Court", "Corte Suprema")
        val ruling = graph.upsertNode("P01", "Ruling", "Rol 1234-2020")
        val law = graph.upsertNode("P01", "Law", "Ley 17.322")
        graph.addEdge("P01", ruling, court, "DECIDED_BY", "A", 1)
        graph.addEdge("P01", ruling, law, "CITES", "B", 1)

        assertTrue(store.deleteSource("A"))
        assertFalse(File(store.library, "A.pdf").exists())
        assertEquals(listOf("B"), store.search(vector(1), "P01", queryText = "filter").hits.map { it.source.id })
        assertTrue(graph.findNodes("P01", "Corte Suprema").isEmpty()) // lost its only link
        assertEquals(listOf("CITES"), graph.edgesOf(ruling).map { it.type })
        assertEquals(0, count("SELECT COUNT(*) FROM terms t JOIN chunks c ON c.id = t.chunk_id WHERE c.source = 'A'"))
        assertEquals(0, count("SELECT COUNT(*) FROM chunks WHERE source = 'A'"))
    }

    @Test fun deletingAProjectRemovesItsGraphAndLeavesOthers() {
        val a = source("A"); val x = source("X", project = "P02")
        val graph = Graph(store)
        graph.addEdge("P01", graph.upsertNode("P01", "T", "one"), graph.upsertNode("P01", "T", "two"), "L", a.id, 1)
        graph.addEdge("P02", graph.upsertNode("P02", "T", "one"), graph.upsertNode("P02", "T", "two"), "L", x.id, 1)
        assertEquals(1, store.deleteProject("P01"))
        assertEquals(0, count("SELECT COUNT(*) FROM graph_nodes WHERE building = 'P01'"))
        assertEquals(2, count("SELECT COUNT(*) FROM graph_nodes WHERE building = 'P02'"))
    }

    /** 3,000 chunks: the flat index keeps memory small and results correct. */
    @Test fun threeThousandChunks() {
        val sources = (0 until 30).map { source("S$it") }
        sources.forEachIndexed { s, src ->
            store.saveChunks(src, (0 until 100).map { i -> VectorChunk(src.id, i, i / 3 + 1, 0, "passage $s-$i about topic${i % 50}", vector((s * 100 + i) % Store.DIMENSIONS, (s * 100 + i).toLong())) })
        }
        val runtime = Runtime.getRuntime()
        System.gc(); val before = runtime.totalMemory() - runtime.freeMemory()
        val start = System.nanoTime()
        val result = store.search(vector(1234 % Store.DIMENSIONS, 9), "P01", limit = 30, queryText = "topic17")
        val millis = (System.nanoTime() - start) / 1_000_000
        System.gc(); val held = (runtime.totalMemory() - runtime.freeMemory() - before) / 1_000_000
        assertEquals(30, result.hits.size)
        assertTrue(result.passages.count { "topic17" in it.chunk.text } >= 3)
        assertTrue(result.passages.any { it.score > 0.9f }) // the chunk pointing the query's way
        assertTrue("held ${held} MB", held < 30)
        println("3000 chunks: first search $millis ms, about $held MB held")
    }

    private fun count(sql: String) = store.readableDatabase.rawQuery(sql, null).use { it.moveToFirst(); it.getInt(0) }
}
