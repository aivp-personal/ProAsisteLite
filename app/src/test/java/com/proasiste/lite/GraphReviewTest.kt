package com.proasiste.lite

import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.graph.Graph
import com.proasiste.lite.graph.NewFact
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class GraphReviewTest {
    private lateinit var store: Store
    private lateinit var graph: Graph

    @Before fun open() {
        store = Store(ApplicationProvider.getApplicationContext(), null)
        graph = Graph(store)
        val file = File(store.library, "r.pdf").apply { writeText("ruling") }
        store.add(Source("R", file.path, "pdf", "P04", "", "2026-11-08", "STC Rol 17.292", false))
    }
    @After fun close() = store.close()

    private fun embed(text: String) = FloatArray(Store.DIMENSIONS) { if (it == text.length % Store.DIMENSIONS) 1f else 0.001f }
    private val held = NewFact(7, "Fallo", "STC Rol 17.292", "FUNDAMENTA_EN", "Norma", "Constitución, art. 4", "El Tribunal se funda en el artículo 4 de la Constitución.")
    private val ai = NewFact(8, "Fallo", "STC Rol 17.292", "RAZONA", "Concepto", "Medida cautelar", "La suspensión es una medida cautelar.", "deepseek/x@legal-2")
    private val rule = NewFact(9, "Fallo", "STC Rol 17.292", "RESUELVE", "Concepto", "Costas", "No se condena en costas.", "reglas")

    @Test fun heldBackFactsWaitThenApprovalPutsThemInTheGraphAsReviewed() {
        graph.replaceFacts("P04", "R", listOf(ai, rule), ::embed)
        graph.saveReview("P04", "R", listOf(held to "'Constitución, art. 4': not on page 7"), "deepseek/x@legal-2")
        assertEquals(1, graph.reviewCount("P04"))
        val item = graph.pendingReviews("P04").single()
        assertEquals(held, item.fact.copy(extractor = ""))
        assertEquals("deepseek/x@legal-2", item.fact.extractor)
        assertTrue(graph.facts("P04", null, "artículo 4", 10).isEmpty())

        assertTrue(graph.approveReview("P04", item.id, null, ::embed))
        assertEquals(0, graph.reviewCount("P04"))
        assertEquals(1, graph.facts("P04", null, "artículo 4 Constitución", 10).size)
        assertEquals("revisado", store.readableDatabase.rawQuery("SELECT extractor FROM graph_edges WHERE fact LIKE 'El Tribunal se funda%'", null).use { it.moveToFirst(); it.getString(0) })
        assertEquals("aprobado", status(item.id))
        assertFalse(graph.approveReview("P04", item.id, null, ::embed)) // decided once
    }

    @Test fun correctingChangesThePageAndRecordsCorrected() {
        graph.saveReview("P04", "R", listOf(held to "numbers not on page 7: [4]"), "deepseek/x@legal-2")
        val item = graph.pendingReviews("P04").single()
        assertTrue(graph.approveReview("P04", item.id, held.copy(page = 5), ::embed))
        assertEquals("corregido", status(item.id))
        assertEquals(5, graph.facts("P04", null, "artículo 4 Constitución", 10).single().page)
    }

    @Test fun rejectingKeepsTheRecordAndNothingEntersTheGraph() {
        graph.saveReview("P04", "R", listOf(held to "r"), "x")
        val item = graph.pendingReviews("P04").single()
        assertTrue(graph.rejectReview(item.id, "wrong article"))
        assertEquals("rechazado", status(item.id))
        assertEquals(0, graph.reviewCount("P04"))
        assertTrue(graph.facts("P04", null, "artículo 4", 10).isEmpty())
    }

    @Test fun aNewExtractionReplacesPendingItemsButNotDecisions() {
        graph.saveReview("P04", "R", listOf(held to "r", held.copy(page = 2) to "r2"), "x")
        val first = graph.pendingReviews("P04").first()
        graph.rejectReview(first.id)
        graph.replaceFacts("P04", "R", listOf(ai), ::embed)
        assertEquals(0, graph.reviewCount("P04"))
        assertEquals("rechazado", status(first.id))
    }

    @Test fun theSampleDrawsOnlyAiFactsAndRejectingRemovesOne() {
        graph.replaceFacts("P04", "R", listOf(ai, rule, ai.copy(statement = "Otra razón.", obj = "Debido proceso")), ::embed)
        val sample = graph.sampleKept("P04", 10)
        assertEquals(2, sample.size)
        assertTrue(sample.all { it.fact.extractor.startsWith("deepseek") && it.edgeId != null })
        assertTrue(graph.rejectKept("P04", sample.first().edgeId!!, "not on the page"))
        assertEquals(2, graph.facts("P04", null, "costas medida proceso razón", 10).size) // the rule fact and the other AI fact remain
        assertEquals(1, store.readableDatabase.rawQuery("SELECT COUNT(*) FROM graph_review WHERE reason = 'muestra' AND status = 'rechazado'", null).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun knownNamesComeFromTheProjectGraph() {
        graph.replaceFacts("P04", "R", listOf(ai, NewFact(1, "Fallo", "STC Rol 17.292", "CITA_PRECEDENTE", "Fallo", "STC Rol 9.431", "Cita el 9.431.", "reglas")), ::embed)
        assertEquals(setOf("STC Rol 17.292", "STC Rol 9.431"), graph.nodeNames("P04", "Fallo"))
    }

    @Test fun deletingTheProjectClearsItsReviews() {
        graph.saveReview("P04", "R", listOf(held to "r"), "x")
        store.deleteProject("P04")
        assertEquals(0, store.readableDatabase.rawQuery("SELECT COUNT(*) FROM graph_review", null).use { it.moveToFirst(); it.getInt(0) })
    }

    private fun status(id: Long) = store.readableDatabase.rawQuery("SELECT status FROM graph_review WHERE id = ?", arrayOf(id.toString())).use { it.moveToFirst(); it.getString(0) }
}
