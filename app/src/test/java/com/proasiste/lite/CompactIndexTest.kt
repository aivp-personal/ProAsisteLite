package com.proasiste.lite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.random.Random

/**
 * A project too large to hold every vector whole (Dr. García's 607,378 chunks needed 1.87 GB) searches on a compact
 * copy and rescores the best exactly. The same chunks must come first as with whole vectors.
 */
@RunWith(RobolectricTestRunner::class)
class CompactIndexTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: Store

    @Before fun open() { store = Store(context, null) }
    @After fun close() = store.close()

    /** Vectors whose meaning sits mostly in their first numbers, as with a Matryoshka model. */
    private fun vector(r: Random) = FloatArray(Store.DIMENSIONS) { j -> (r.nextFloat() - 0.5f) * (if (j < 128) 1f else 0.25f) }

    @Test fun aLargeProjectFindsTheSameBestChunks() {
        val r = Random(7)
        val n = ProjectIndexLimit + 2_000
        val vectors = List(n) { vector(r) }
        for (s in 0 until n / 1000) {
            val id = "S$s"
            store.add(Source(id, File(store.library, "$id.pdf").apply { writeText(id) }.path, "pdf", "P04", "", "2026-11-15", "$id.pdf", false), "h$s")
            store.saveChunks(store.sources("P04").first { it.id == id }, (0 until 1000).map { i -> VectorChunk(id, i, 1, 0, "texto $s $i", vectors[s * 1000 + i]) })
        }
        // A query near chunk 31,415: it must come first, and the top ten must match exact search.
        val target = 31_415
        val query = vectors[target].copyOf().also { q -> for (j in q.indices) q[j] += (r.nextFloat() - 0.5f) * 0.02f }
        val hits = store.search(query, "P04", limit = 10, passages = 10).hits
        assertEquals("S31", hits.first().source.id)
        fun cos(a: FloatArray, b: FloatArray) = Store.cosine(a, b)
        val exactTop = vectors.indices.sortedByDescending { cos(query, vectors[it]) }.take(10).toSet()
        val found = hits.map { it.source.id.removePrefix("S").toInt() * 1000 + it.chunk.ordinal }.toSet()
        assertTrue("found $found, exact $exactTop", (found intersect exactTop).size >= 9)
        assertEquals(target, hits.first().let { it.source.id.removePrefix("S").toInt() * 1000 + it.chunk.ordinal })
        // The compact copy was saved, so the next start reads it instead of every vector; deleting the project removes it.
        val saved = File(context.filesDir, "index-P04.bin")
        assertTrue(saved.length() > n * 128L)
        store.deleteProject("P04")
        assertTrue(!saved.exists())
    }

    @Test fun aQuestionNamingARulingSearchesInsideIt() {
        val r = Random(3)
        val titles = listOf("STC_Rol_N__17_292-26_INA.pdf", "STC Rol 16.000.pdf", "17174.pdf")
        titles.forEachIndexed { i, t ->
            val id = "R$i"
            store.add(Source(id, File(store.library, "$id.pdf").apply { writeText(id) }.path, "pdf", "P04", "", "2026-11-15", t, false), "r$i")
            store.saveChunks(store.sources("P04").first { it.id == id }, (0 until 20).map { k -> VectorChunk(id, k, k + 1, 0, "se rechaza el requerimiento $i $k", vector(r)) })
        }
        assertEquals(setOf("17292"), Store.titleNumbers("STC_Rol_N__17_292-26_INA.pdf") intersect setOf("17292"))
        assertEquals(setOf("R0"), store.sourcesNumbered("P04", "17292"))
        assertEquals(setOf("R2"), store.sourcesNumbered("P04", "17174"))
        assertEquals("17292", com.proasiste.lite.graph.SameName.caseNumber("que resolvio la STC 17.292"))
        val q = vector(r)
        val scoped = store.search(q, "P04", queryText = "se rechaza el requerimiento", only = setOf("R0"))
        assertTrue(scoped.passages.isNotEmpty() && scoped.passages.all { it.source.id == "R0" })
        assertTrue(store.search(q, "P04", queryText = "se rechaza el requerimiento").hits.map { it.source.id }.toSet().size > 1)
    }

    companion object { const val ProjectIndexLimit = 50_000 }
}
