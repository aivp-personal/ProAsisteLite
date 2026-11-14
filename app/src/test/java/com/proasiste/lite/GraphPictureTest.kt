package com.proasiste.lite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.graph.Graph
import com.proasiste.lite.graph.GraphPicture
import com.proasiste.lite.graph.NewFact
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
class GraphPictureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: Store
    private lateinit var graph: Graph

    @Before fun open() {
        store = Store(context, null); graph = Graph(store)
        for (id in listOf("A", "B")) store.add(Source(id, File(store.library, "$id.pdf").apply { writeText(id) }.path, "pdf", "P03", "", "2026-11-14", "$id.pdf", false))
    }
    @After fun close() = store.close()

    private fun embed(t: String) = FloatArray(Store.DIMENSIONS) { if (it == t.length % Store.DIMENSIONS) 1f else 0.001f }
    private fun fact(page: Int, st: String, s: String, rel: String, ot: String, o: String, place: String = "") = NewFact(page, st, s, rel, ot, o, "$s $rel $o.", "x", place)

    @Test fun sameNameIsOneDotAndAFileShowsOnlyItsLinks() {
        graph.replaceFacts("P03", "A", listOf(
            fact(4, "Appliance", "DASH egg cooker", "HAS_PART", "part", "Lid", "4:10,10,200,40"),
            fact(4, "appliance", "Dash Egg Cooker", "HAS_PART", "part", "Heating plate"),
            fact(5, "product", "DASH egg cooker", "MADE_BY", "brand", "StoreBound"),
        ), ::embed)
        graph.replaceFacts("P03", "B", listOf(fact(1, "recipe", "Egg salad", "HAS_INGREDIENT", "food", "Mayonnaise")), ::embed)

        val all = GraphPicture.build(graph.view("P03"))
        assertEquals(6, all.nodes.size); assertEquals(4, all.links.size)
        val cooker = all.nodes.first()
        assertEquals("dash egg cooker", cooker.key); assertEquals(3, cooker.degree); assertEquals("appliance", cooker.type)
        assertEquals(listOf("part", "appliance"), all.types.take(2))

        val a = GraphPicture.build(graph.view("P03", "A"))
        assertEquals(4, a.nodes.size); assertTrue(a.links.all { it.edge.sourceId == "A" })
        assertEquals("4:10,10,200,40", a.links.first { it.edge.to.name == "Lid" }.edge.place)
        assertTrue(graph.view("P05").isEmpty())
    }

    @Test fun aBigGraphKeepsItsMostLinkedNodes() {
        graph.replaceFacts("P03", "A", (1..40).map { fact(1, "hub", "Hub", "LINKS", "leaf", "Leaf $it") } + fact(2, "hub", "Second hub", "LINKS", "leaf", "Leaf 1"), ::embed)
        val p = GraphPicture.build(graph.view("P03"), maxNodes = 10)
        assertEquals(10, p.nodes.size); assertEquals(42, p.totalNodes); assertEquals(41, p.totalLinks)
        assertEquals("hub", p.nodes[0].key); assertEquals("leaf 1", p.nodes[1].key)
        assertTrue(p.links.all { it.from < 10 && it.to < 10 })
    }

    @Test fun mergingStaysInItsFilesAndSurvivesARebuild() {
        val egg = listOf(
            fact(4, "appliance", "DASH egg cooker", "HAS_PART", "part", "Lid"),
            fact(4, "product", "Deluxe Egg Cooker", "HAS_PART", "part", "Heating plate"),
            fact(4, "Appliance", "Appliance", "MUST_BE_PLACED_ON", "surface", "Stable, dry surface"),
            fact(5, "part", "Egg Cooker", "IS_PART_OF", "product", "Deluxe Egg Cooker"),
            fact(6, "product", "Deluxe Egg Cooker", "HAS_PART", "part", "Lid"),
        )
        graph.replaceFacts("P03", "A", egg, ::embed)
        graph.replaceFacts("P03", "B", listOf(fact(2, "Appliance", "Appliance", "HAS_PART", "part", "Dust cup")), ::embed)

        val moved = graph.merge("P03", "dash egg cooker", listOf("deluxe egg cooker", "appliance", "egg cooker"))
        assertTrue(moved > 0)
        fun keys(src: String) = GraphPicture.build(graph.view("P03", src)).nodes.map { it.key }.toSet()
        assertEquals(setOf("dash egg cooker", "lid", "heating plate", "stable dry surface"), keys("A"))
        // The vacuum's "Appliance" is still the vacuum.
        assertEquals(setOf("appliance", "dust cup"), keys("B"))
        val a = GraphPicture.build(graph.view("P03", "A"))
        // The self-link is gone; "has part Lid" on two pages stays two facts.
        assertEquals(4, a.links.size); assertTrue(a.links.none { it.from == it.to })

        // Rebuilding file A applies the merge again; file B is untouched.
        graph.replaceFacts("P03", "A", egg, ::embed)
        assertEquals(setOf("dash egg cooker", "lid", "heating plate", "stable dry surface"), keys("A"))
        assertEquals("DASH egg cooker", GraphPicture.build(graph.view("P03", "A")).nodes.first().name)
        assertEquals(setOf("appliance", "dust cup"), keys("B"))
        assertEquals(0, graph.merge("P03", "nothing here", listOf("lid")))
    }

    @Test fun schemaTenUpgrades() {
        val name = "aliases-upgrade.db"
        context.deleteDatabase(name)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            Store.MIGRATIONS.take(10).flatten().forEach(db::execSQL); db.version = 10
        }
        Store(context, name).use { s -> assertEquals(0, Graph(s).merge("P01", "a", listOf("b"))) }
        context.deleteDatabase(name)
    }

    @Test fun theLayoutIsRepeatableFillsTheSquareAndKeepsLinksShort() {
        // Two triangles joined by one link.
        val links = listOf(0 to 1, 1 to 2, 2 to 0, 3 to 4, 4 to 5, 5 to 3, 2 to 3)
        val (x, y) = GraphPicture.layout(6, links)
        val (x2, y2) = GraphPicture.layout(6, links)
        assertArrayEquals(x, x2, 0f); assertArrayEquals(y, y2, 0f)
        assertTrue((x + y).all { it in 0f..1f })
        assertTrue(x.max() - x.min() > 0.99f || y.max() - y.min() > 0.99f)
        fun d(a: Int, b: Int) = hypot(x[a] - x[b], y[a] - y[b])
        val linked = links.map { (a, b) -> d(a, b) }.average()
        assertTrue("linked $linked vs far ${d(0, 5)}", linked < d(0, 5))
        assertEquals(0.5f, GraphPicture.layout(1, emptyList()).first[0], 0f)
    }
}
