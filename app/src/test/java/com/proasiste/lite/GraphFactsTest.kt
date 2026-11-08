package com.proasiste.lite

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.graph.Graph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class GraphFactsTest {
    private lateinit var store: Store
    private lateinit var graph: Graph
    private lateinit var manual: Source

    @Before fun open() {
        store = Store(ApplicationProvider.getApplicationContext(), null)
        graph = Graph(store)
        val file = File(store.library, "manual.pdf").apply { writeText("egg cooker manual") }
        manual = Source("MANUAL", file.path, "pdf", "P03", "", "2026-11-08", "Egg cooker.pdf", false).also(store::add)
    }
    @After fun close() = store.close()

    /** Stand-in embedding: one axis per topic word, so meaning ranking is predictable without the model. */
    private fun embed(text: String): FloatArray {
        val topics = listOf("hard", "soft", "water", "minute", "clean", "vinegar", "omelette")
        val folded = text.lowercase()
        return FloatArray(Store.DIMENSIONS) { i -> if (i < topics.size && topics[i] in folded) 1f else 0.001f }
    }

    private fun file(sha: String = Store.sha256(File(manual.path)), title: String = manual.title) = """
        {"format": "proasiste-graph-1", "source_sha256": "$sha", "source_title": "$title", "facts": [
          {"page": 6, "subject": ["Egg style", "Hard-boiled eggs"], "relation": "COOKING_GUIDE", "object": ["Batch", "12 hard-boiled eggs"], "statement": "12 hard-boiled eggs: 3.5 oz of water, about 20 minutes."},
          {"page": 6, "subject": ["Egg style", "Soft-boiled eggs"], "relation": "COOKING_GUIDE", "object": ["Batch", "6 soft-boiled eggs"], "statement": "6 soft-boiled eggs: 2.25 oz of water, about 8 minutes."},
          {"page": 9, "subject": ["Part", "Heating Plate"], "relation": "CLEAN_WITH", "object": ["Supply", "Water and vinegar"], "statement": "Clean the Heating Plate with 10 parts water to 1 part vinegar."}
        ]}""".trimIndent()

    @Test fun importsFactsAsLinksWithPagesAndFindsThem() {
        assertEquals(3, graph.importFile("P03", file(), ::embed))
        val hard = graph.findNodes("P03", "hard-boiled eggs", "Egg style").single()
        val link = graph.edgesOf(hard).single()
        assertEquals("COOKING_GUIDE", link.type)
        assertEquals(6, link.page)
        assertEquals("MANUAL", link.sourceId)
        assertTrue(link.fact.startsWith("12 hard-boiled eggs"))

        val best = graph.facts("P03", embed("hard boiled time"), "how long for 12 hard boiled eggs", 2)
        assertTrue(best.first().fact.startsWith("12 hard-boiled eggs"))
        assertEquals(9, graph.facts("P03", embed("vinegar"), "clean the plate with vinegar", 1).single().page)
    }

    @Test fun importingAgainReplacesTheFilesFacts() {
        assertEquals(3, graph.importFile("P03", file(), ::embed))
        assertEquals(3, graph.importFile("P03", file(), ::embed))
        assertEquals(3, graph.facts("P03", embed("water"), "water", 10).size)
        val oneFact = file().replace(Regex(""",\s*\{"page": 6, "subject": \["Egg style", "Soft-boiled eggs"].*""", RegexOption.DOT_MATCHES_ALL), "]}")
        assertEquals(1, graph.importFile("P03", oneFact, ::embed))
        assertEquals(listOf(6), graph.facts("P03", embed("water"), "water", 10).map { it.page })
        assertTrue(graph.findNodes("P03", "Heating Plate").isEmpty()) // left unlinked by the new file
    }

    @Test fun matchesTheSourceByTitleWhenTheHashDiffers() {
        assertEquals(3, graph.importFile("P03", file(sha = "0".repeat(64)), ::embed))
    }

    @Test fun refusesAFileNotInTheProject() {
        assertThrows(IllegalStateException::class.java) { graph.importFile("P03", file(sha = "0".repeat(64), title = "Other.pdf"), ::embed) }
        assertThrows(IllegalStateException::class.java) { graph.importFile("P01", file(), ::embed) }
        assertThrows(IllegalArgumentException::class.java) { graph.importFile("P03", """{"format": "other", "facts": []}""", ::embed) }
    }

    @Test fun aListQuestionGetsEveryFactOfItsRelation() {
        val facts = listOf(
            com.proasiste.lite.graph.NewFact(8, "Fallo", "STC Rol 1", "CITA_PRECEDENTE", "Fallo", "STC Rol 9.431", "El fallo cita el precedente STC Rol 9.431."),
            com.proasiste.lite.graph.NewFact(7, "Fallo", "STC Rol 1", "CITA_PRECEDENTE", "Fallo", "STC Rol 327", "El fallo cita el precedente STC Rol 327."),
            com.proasiste.lite.graph.NewFact(9, "Fallo", "STC Rol 1", "FUNDAMENTA_EN", "Norma", "Ley N° 17.997", "El fallo se funda en la Ley N° 17.997."),
        )
        graph.replaceFacts("P03", "MANUAL", facts, ::embed)
        val listed = graph.factsWithRelations("P03", setOf("CITA_PRECEDENTE"), 40)
        assertEquals(listOf(7, 8), listed.map { it.page }) // in page order, and nothing else
        assertEquals(emptyList<Any>(), graph.factsWithRelations("P03", emptySet(), 40))
        assertEquals(emptyList<Any>(), graph.factsWithRelations("P01", setOf("CITA_PRECEDENTE"), 40))
    }

    @Test fun formsOfAWordMatch() {
        assertEquals(Graph.stems("citó"), Graph.stems("cita"))
        assertEquals(Graph.stems("cito"), Graph.stems("citaron"))
        assertEquals(Graph.stems("fallos"), Graph.stems("fallo"))
        assertEquals(listOf("9", "431"), Graph.stems("9.431")) // digits are never shortened
    }

    @Test fun deletingTheManualRemovesItsFacts() {
        graph.importFile("P03", file(), ::embed)
        store.deleteSource("MANUAL")
        assertTrue(graph.facts("P03", embed("water"), "water", 5).isEmpty())
        assertTrue(graph.findNodes("P03", "eggs").isEmpty())
    }

    /** A phone with schema 1 keeps its graph links when the app upgrades to schema 2. */
    @Test fun upgradeFromVersionOneKeepsLinks() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "upgrade-test.db"
        context.deleteDatabase(name)
        object : SQLiteOpenHelper(context, name, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                Store.MIGRATIONS[0].forEach(db::execSQL)
                db.execSQL("INSERT INTO sources(id,path,type,building,sha256) VALUES ('S','/x','pdf','P01','abc')")
                db.execSQL("INSERT INTO graph_nodes(id,building,type,key,name) VALUES (1,'P01','T','a','A'), (2,'P01','T','b','B')")
                db.execSQL("INSERT INTO graph_edges(from_id,to_id,type,source,page) VALUES (1,2,'CITES','S',4)")
            }
            override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
        }.writableDatabase.close()

        val upgraded = Store(context, name)
        try {
            val upgradedGraph = Graph(upgraded)
            val a = upgradedGraph.findNodes("P01", "A").single()
            val link = upgradedGraph.edgesOf(a).single()
            assertEquals(4, link.page)
            assertEquals("", link.fact)
            assertEquals(Store.MIGRATIONS.size, upgraded.readableDatabase.version)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }
}
