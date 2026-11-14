package com.proasiste.lite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.graph.Graph
import com.proasiste.lite.graph.NewFact
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** A graph fact keeps where it sits on its page, through extraction, review and correction. */
@RunWith(RobolectricTestRunner::class)
class GraphPlaceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: Store
    private lateinit var graph: Graph

    @Before fun open() {
        store = Store(context, null); graph = Graph(store)
        val file = File(store.library, "r.pdf").apply { writeText("ruling") }
        store.add(Source("R", file.path, "pdf", "P04", "", "2026-11-14", "STC Rol 2154-11", false))
    }
    @After fun close() = store.close()

    private fun embed(text: String) = FloatArray(Store.DIMENSIONS) { if (it == text.length % Store.DIMENSIONS) 1f else 0.001f }
    private val split = "5:80,400,430,490;6:80,60,430,120"
    private val kept = NewFact(5, "Fallo", "STC Rol 2154-11", "RAZONA", "Concepto", "Ley penal en blanco", "La ley penal en blanco debe describir el núcleo de la conducta.", "deepseek/x@legal-2", split)
    private val held = NewFact(7, "Fallo", "STC Rol 2154-11", "FUNDAMENTA_EN", "Norma", "Constitución, art. 19", "El Tribunal se funda en el artículo 19.", "deepseek/x@legal-2", "7:80,100,430,180")

    @Test fun keptFactsKeepTheirPlace() {
        graph.replaceFacts("P04", "R", listOf(kept), ::embed)
        assertEquals(split, graph.pageItems("P04", "R", 5).single().fact.place)
        assertEquals(split, graph.facts("P04", null, "ley penal en blanco", 5).single().place)
        assertEquals(split, graph.factsWithRelations("P04", setOf("RAZONA"), 5).single().place)
    }

    @Test fun heldBackFactsKeepTheirPlaceWhenApproved() {
        graph.saveReview("P04", "R", listOf(held to "not on page"), "deepseek/x@legal-2")
        val item = graph.pendingReviews("P04").single()
        assertEquals("7:80,100,430,180", item.fact.place)
        // The screen sends the fact back without its place: that is still an approval, not a correction.
        graph.approveReview("P04", item.id, item.fact.copy(place = ""), ::embed)
        assertEquals("aprobado", store.readableDatabase.rawQuery("SELECT status FROM graph_review WHERE id = ?", arrayOf(item.id.toString())).use { it.moveToFirst(); it.getString(0) })
        assertEquals("7:80,100,430,180", graph.pageItems("P04", "R", 7).single().fact.place)
    }

    @Test fun aCorrectionKeepsThePlaceOnlyOnTheSamePage() {
        graph.saveReview("P04", "R", listOf(held to "not on page", held.copy(statement = "Otra frase del tribunal sobre el artículo 19.") to "not on page"), "deepseek/x@legal-2")
        val (a, b) = graph.pendingReviews("P04")
        graph.approveReview("P04", a.id, a.fact.copy(obj = "Constitución, art. 19 N° 3", place = ""), ::embed)
        graph.approveReview("P04", b.id, b.fact.copy(page = 8, place = ""), ::embed)
        assertEquals("7:80,100,430,180", graph.pageItems("P04", "R", 7).single().fact.place)
        assertEquals("", graph.pageItems("P04", "R", 8).single().fact.place)
        // A kept fact corrected on its page keeps the place; moved to another page, it loses it.
        graph.replaceFacts("P04", "R", listOf(kept), ::embed)
        val edge = graph.pageItems("P04", "R", 5).single()
        graph.correctKept("P04", edge.edgeId!!, edge.fact.copy(statement = "La ley penal en blanco debe describir el núcleo esencial.", place = ""), ::embed)
        assertEquals(split, graph.pageItems("P04", "R", 5).single().fact.place)
    }

    @Test fun schemaSixUpgradesWithFactsKept() {
        val name = "place-upgrade.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            Store.MIGRATIONS.take(6).flatten().forEach(db::execSQL)
            db.execSQL("INSERT INTO sources(id,path,type,building,sha256) VALUES('R','/r.pdf','pdf','P04','abc')")
            db.execSQL("INSERT INTO graph_nodes(id,building,type,key,name) VALUES(1,'P04','Fallo','stc','STC'),(2,'P04','Concepto','x','X')")
            db.execSQL("INSERT INTO graph_edges(from_id,to_id,type,source,page,fact,extractor) VALUES(1,2,'RAZONA','R',3,'Un dato.','deepseek')")
            db.version = 6
        }
        Store(context, name).use { s ->
            val item = Graph(s).pageItems("P04", "R", 3).single()
            assertEquals("Un dato.", item.fact.statement); assertEquals("", item.fact.place)
        }
        context.deleteDatabase(name)
    }
}
