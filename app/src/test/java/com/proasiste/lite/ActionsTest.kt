package com.proasiste.lite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.actions.Action
import com.proasiste.lite.actions.Actions
import com.proasiste.lite.reports.Reports
import com.proasiste.lite.runtime.CloudAction
import com.proasiste.lite.runtime.DeepSeekFacts
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
class ActionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: Store
    private lateinit var actions: Actions

    @Before fun open() {
        store = Store(context, null); actions = Actions(store)
        for ((id, title) in listOf("A" to "STC 2154-11.pdf", "B" to "STC 2153-11.pdf")) {
            val f = File(store.library, "$id.pdf").apply { writeText(id) }
            store.add(Source(id, f.path, "pdf", "P04", "", "2026-11-14", title, false))
        }
    }
    @After fun close() = store.close()

    private val summary get() = actions.all().first { it.key == "summary" }

    @Test fun aRunQueuesItsFilesAndItsReportGrows() {
        val reportId = actions.start("P04", summary, "#penal", listOf("A", "B", "A"), "DeepSeek V4.1 Flash")
        assertEquals(2, actions.pending(setOf("P04"))); assertEquals(0, actions.pending(setOf("P05")))
        val first = actions.next(setOf("P04"))!!
        assertEquals("A", first.sourceId); assertEquals("Resumen · #penal", first.run.title); assertTrue(first.run.instructions.contains("bullet"))
        actions.finish(first, "STC 2154-11.pdf", "- La ley penal en blanco exige describir el núcleo (p. 14).")
        val second = actions.next(setOf("P04"))!!
        actions.finish(second, "STC 2153-11.pdf", "OpenRouter returned error 500.", failed = true)
        assertNull(actions.next(setOf("P04")))
        val r = Reports(store).get(reportId)!!
        assertEquals("DeepSeek V4.1 Flash", r.model)
        assertTrue(r.body.startsWith("## STC 2154-11.pdf\n\n- La ley penal en blanco"))
        assertTrue(r.body.contains("## STC 2153-11.pdf\n\n_No se pudo: OpenRouter returned error 500._"))
        assertEquals(listOf("A"), r.cited.map { it.sourceId }) // a failed file isn't listed as a source
    }

    @Test fun cancellingDropsWhatWaitsAndDeletingAFileDropsItsJob() {
        actions.start("P04", summary, "P04", listOf("A", "B"), "m")
        store.deleteSource("A")
        assertEquals(1, actions.pending(setOf("P04")))
        actions.cancel("P04")
        assertEquals(0, actions.pending(setOf("P04")))
    }

    @Test fun docLangNeedsAFolderAndNoModel() {
        val doclang = actions.all().first { it.key == Action.DOCLANG }
        assertTrue(doclang.local)
        assertTrue(runCatching { actions.start("P04", doclang, "P04", listOf("A"), "") }.isFailure)
        val id = actions.start("P04", doclang, "P04", listOf("A"), "", folder = "content://tree/x")
        assertEquals("", Reports(store).get(id)!!.model)
        assertEquals("content://tree/x", actions.next(setOf("P04"))!!.run.folder)
    }

    @Test fun skillsAreKeptForEveryProject() {
        val id = actions.saveSkill("  Votos y disidencias ", "Indica quiénes votaron en contra y por qué.")
        val skill = actions.all().last()
        assertEquals("Votos y disidencias", skill.name); assertEquals(id, skill.skillId); assertFalse(skill.local)
        actions.start("P04", skill, "P04", listOf("B"), "m")
        assertEquals("Indica quiénes votaron en contra y por qué.", actions.next(setOf("P04"))!!.run.instructions)
        actions.deleteSkill(id)
        assertEquals(Actions.BUILT_IN.size, actions.all().size)
    }

    @Test fun theDocumentIsPageMarkedAndCutWithANote() {
        val pages = (1..30).map { DeepSeekFacts.PageText(it, "palabra ".repeat(400)) }
        val doc = CloudAction.document(pages)
        assertTrue(doc.startsWith("=== PAGE 1 ===")); assertTrue(doc.length <= CloudAction.MAX_CHARS + 120)
        assertTrue(doc.trimEnd().endsWith("were included.]"))
        val body = CloudAction.request("deepseek/x", "Resume.", "STC 2154-11.pdf", pages.take(2))
        assertFalse(body.has("temperature"))
        assertEquals("Resume.", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertTrue(body.getJSONArray("messages").getJSONObject(1).getString("content").startsWith("Document: STC 2154-11.pdf\n\n=== PAGE 1 ==="))
    }

    @Test fun schemaNineUpgrades() {
        val name = "actions-upgrade.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            Store.MIGRATIONS.take(9).flatten().forEach(db::execSQL); db.version = 9
        }
        Store(context, name).use { s -> assertEquals(Actions.BUILT_IN.size, Actions(s).all().size) }
        context.deleteDatabase(name)
    }
}
