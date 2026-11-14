package com.proasiste.lite

import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.runtime.CloudTagger
import com.proasiste.lite.tags.TagDef
import com.proasiste.lite.tags.TagRule
import com.proasiste.lite.tags.Tags
import com.proasiste.lite.ui.UiFilters
import com.proasiste.lite.ui.UiItem
import com.proasiste.lite.ui.UiKind
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

@RunWith(RobolectricTestRunner::class)
class TagsTest {
    private lateinit var store: Store
    private lateinit var tags: Tags

    @Before fun open() { store = Store(ApplicationProvider.getApplicationContext(), null); tags = Tags(store) }
    @After fun close() = store.close()

    private fun source(id: String, title: String, folder: String = "", project: String = "P01", indexed: Boolean = false): Source {
        val file = File(store.library, "$id.pdf").apply { writeText(id) }
        val s = Source(id, file.path, "pdf", project, folder, "2026-11-13", title, false).also { store.add(it) }
        if (indexed) store.saveChunks(s, listOf(VectorChunk(s.id, 0, 1, 0, "texto", FloatArray(Store.DIMENSIONS) { if (it == 0) 1f else 0f })))
        return s
    }

    @Test fun yearsComeFromRolesDatesOrFullYears() {
        assertEquals("2011", Tags.year("STC 2154-11 LEY PENAL EN BLANCO"))
        assertEquals("1998", Tags.year("Rol 245-98"))
        assertEquals("2009", Tags.year("STC 1298-2009 SOBRE EL DECRETO LEY 2695"))
        assertEquals("2022", Tags.year("acta 2022-05-03 sesión"))
        assertEquals("2017", Tags.year("Inadmisibles 2017"))
        // Law numbers are not years.
        assertNull(Tags.year("Ley 19880 sobre procedimiento"))
        assertNull(Tags.year("DECRETO LEY 2695"))
    }

    @Test fun rulesTagByFolderNameAndYear() {
        val rules = listOf(TagRule(1, Tags.FOLDER), TagRule(2, Tags.NAME, "inaplicabilidad", "inaplicabilidad"), TagRule(3, Tags.YEAR))
        val s = Source("A", "", "pdf", "P01", "INA SENTENCIAS/CARLOS CARMONA", "", "STC 2154-11 Inaplicabilidad ley penal", false)
        assertEquals(listOf("INA SENTENCIAS", "CARLOS CARMONA", "inaplicabilidad", "2011"), Tags.ruleTags(s, rules))
        // Name rules ignore case and accents.
        val accents = Source("B", "", "pdf", "P01", "", "", "INAPLICABILIDAD — Artículo 19", false)
        assertEquals(listOf("inaplicabilidad"), Tags.ruleTags(accents, listOf(TagRule(1, Tags.NAME, "Inaplicabilidad", "inaplicabilidad"))))
    }

    @Test fun applyingRulesTagsTheProjectAndCountsThem() {
        source("A", "STC 2154-11", "Sentencias 2022"); source("B", "STC 2153-11", "Sentencias 2022"); source("C", "Inadmisible 2017", "Inadmisibles 2017")
        tags.addRule("P01", Tags.FOLDER); tags.addRule("P01", Tags.YEAR)
        assertEquals(3, tags.applyRules("P01"))
        assertEquals(listOf("2011" to 2, "Sentencias 2022" to 2, "2017" to 1, "Inadmisibles 2017" to 1), tags.counts("P01"))
        assertEquals(listOf("2011", "Sentencias 2022"), tags.bySource("P01").getValue("A"))
    }

    @Test fun deletingARuleTakesOffOnlyItsTags() {
        source("A", "STC 2154-11", "Sentencias")
        val folder = tags.addRule("P01", Tags.FOLDER); tags.addRule("P01", Tags.YEAR); tags.applyRules("P01")
        tags.deleteRule(folder); tags.applyRules("P01")
        assertEquals(listOf("2011"), tags.bySource("P01").getValue("A"))
    }

    @Test fun handTagsWinOverRulesAndAi() {
        source("A", "STC 2154-11", "Sentencias")
        tags.addRule("P01", Tags.FOLDER); tags.applyRules("P01")
        // Removed by hand: a later rule run doesn't put it back.
        tags.remove("A", "P01", "Sentencias"); tags.applyRules("P01")
        assertTrue(tags.bySource("P01")["A"].isNullOrEmpty())
        // Added by hand: an AI run that doesn't choose it doesn't take it off.
        tags.add("A", "P01", "  penal   económico ")
        tags.replace("A", "P01", Tags.AI, listOf("tributario"))
        tags.replace("A", "P01", Tags.AI, emptyList())
        assertEquals(listOf("penal económico"), tags.bySource("P01").getValue("A"))
        // Adding a removed tag by hand undoes the removal.
        tags.add("A", "P01", "Sentencias")
        assertEquals(listOf("penal económico", "Sentencias"), tags.bySource("P01").getValue("A"))
    }

    @Test fun aiQueueTakesIndexedPdfsOfTheProject() {
        source("A", "uno", indexed = true); source("B", "dos"); source("C", "tres", project = "P02", indexed = true)
        tags.define("P01", "penal", "Trata de derecho penal"); tags.define("P01", "sin uso", "")
        assertEquals(listOf(TagDef("penal", "Trata de derecho penal")), tags.definitions("P01"))
        assertEquals(1, tags.queueForAi("P01"))
        assertEquals("A", tags.nextForAi(setOf("P01"))!!.sourceId)
        assertNull(tags.nextForAi(setOf("P02")))
        tags.finishAi("A", Tags.DONE, "penal")
        assertEquals(0, tags.pendingAi(setOf("P01")))
        tags.queueForAi("P01"); tags.cancelAi("P01")
        assertNull(tags.nextForAi(setOf("P01")))
    }

    @Test fun deletingAProjectDropsItsTagsAndRules() {
        source("A", "STC 2154-11", "Sentencias", project = "P07")
        tags.addRule("P07", Tags.FOLDER); tags.applyRules("P07"); tags.define("P07", "penal", "x")
        store.deleteProject("P07")
        assertTrue(tags.counts("P07").isEmpty()); assertTrue(tags.rules("P07").isEmpty()); assertTrue(tags.definitions("P07").isEmpty())
    }

    @Test fun filterNeedsEveryChosenTag() {
        val a = UiItem("A", "a", UiKind.REPORT, "P01", tags = listOf("2011", "penal"))
        val b = UiItem("B", "b", UiKind.REPORT, "P01", tags = listOf("2011"))
        assertEquals(listOf("A", "B"), PlantroomModel.filterItems(listOf(a, b), "P01", UiFilters(tags = setOf("2011"))).map { it.id })
        assertEquals(listOf("A"), PlantroomModel.filterItems(listOf(a, b), "P01", UiFilters(tags = setOf("2011", "penal"))).map { it.id })
    }

    @Test fun aiRequestListsTheTagsAndAcceptsOnlyThem() {
        val defs = listOf(TagDef("penal", "Derecho penal"), TagDef("acoge", "El tribunal acoge el requerimiento"))
        val body = CloudTagger.request("deepseek/deepseek-v4.1-flash", "STC 2154-11", "=== PAGE 1 ===\ntexto", defs)
        assertFalse(body.has("temperature"))
        val enum = body.getJSONObject("response_format").getJSONObject("json_schema").getJSONObject("schema")
            .getJSONObject("properties").getJSONObject("tags").getJSONObject("items").getJSONArray("enum")
        assertEquals(listOf("penal", "acoge"), (0 until enum.length()).map { enum.getString(it) })
        assertTrue(body.getJSONArray("messages").getJSONObject(1).getString("content").contains("- penal: Derecho penal"))
        assertEquals(listOf("penal"), CloudTagger.parse("""{"tags":["Penal","tributario"]}""", defs))
        assertEquals(listOf("acoge", "penal"), CloudTagger.parse("```json\n{\"tags\": [\"acoge\", \"PENAL\", \"acoge\"]}\n```", defs))
        assertEquals(emptyList<String>(), CloudTagger.parse("No JSON here", defs))
    }
}
