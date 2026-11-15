package com.proasiste.lite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.memory.HistorySource
import com.proasiste.lite.memory.Memory
import com.proasiste.lite.runtime.NanoAnswerer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Step 8: the history of questions, the person's notes for answers, and requests asked again and again. */
@RunWith(RobolectricTestRunner::class)
class MemoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: Store
    private lateinit var memory: Memory

    @Before fun open() { store = Store(context, null); memory = Memory(store) }
    @After fun close() = store.close()

    @Test fun historyKeepsAnswersWithTheirSourcesAndWhatWasDoneNext() {
        val id = memory.record("P04", "¿Quién redactó la sentencia 17.292?", "Raúl Mera [1].", "Gemini Nano", listOf(HistorySource("S1", "Redactó la sentencia…", "Page 20", "20:1,2,3,4")), now = 1000)
        memory.record("P04", "¿Qué norma se impugna?", "El art. 60 [1].", "Table", emptyList(), now = 2000)
        memory.record("P05", "Otra pregunta", "Otra respuesta", "Gemini Nano", emptyList())
        val h = memory.history("P04")
        assertEquals(listOf("¿Qué norma se impugna?", "¿Quién redactó la sentencia 17.292?"), h.map { it.question })  // newest first
        assertEquals("20:1,2,3,4", h[1].sources.single().highlight)
        memory.opened(id, "S1"); memory.opened(id, "S1"); memory.savedAsReport(id, 7)
        val e = memory.history(id)!!
        assertEquals(listOf("S1"), e.opened); assertEquals(7L, e.reportId)
        // Searching finds words in the question or the answer, accents ignored.
        assertEquals(listOf(id), memory.history("P04", "redacto mera").map { it.id })
        memory.deleteHistory(id)
        assertEquals(1, memory.historyCount("P04"))
        memory.clearHistory("P04")
        assertEquals(0, memory.historyCount("P04")); assertEquals(1, memory.historyCount("P05"))
        assertEquals(0L, memory.record("P04", "", "x", "w", emptyList()))
    }

    @Test fun notesAreForOneProjectOrEveryProjectAndGoInThePromptUncited() {
        memory.addNote("P04", Memory.PREFERENCE, "Responde en español, en máximo cinco líneas")
        memory.addNote(null, Memory.FACT, "El ministro Mera también aparece como Raúl Eduardo Mera Muñoz")
        memory.addNote("P05", Memory.PREFERENCE, "Answer in English")
        assertEquals(0L, memory.addNote("P04", Memory.PREFERENCE, "responde en espanol,  en maximo cinco lineas"))  // already kept
        assertEquals(listOf("Responde en español, en máximo cinco líneas", "Fact: El ministro Mera también aparece como Raúl Eduardo Mera Muñoz"), memory.forPrompt("P04"))
        assertTrue(memory.forPrompt("P05").none { "español" in it })
        val prompt = NanoAnswerer.prompt("¿Quién redactó?", listOf(NanoAnswerer.Passage("a.pdf", 3, "Redactó el ministro Mera.")), notes = memory.forPrompt("P04"))
        assertTrue(prompt.contains("User's notes:") && prompt.contains("* Responde en español"))
        assertTrue("notes are never numbered like passages", prompt.lines().count { it.startsWith("[") } == 1)
        assertTrue(!NanoAnswerer.prompt("q", emptyList()).contains("User's notes"))
        val id = memory.notes("P04").first().id
        memory.editNote(id, "Responde en español"); memory.deleteNote(memory.notes("P04").last().id)
        assertEquals(listOf("Responde en español"), memory.forPrompt("P04"))
        // Deleting a project takes its history and notes, not other projects' or the shared ones.
        memory.addNote(null, Memory.FACT, "Compartida")
        memory.record("P04", "q", "a", "w", emptyList())
        store.deleteProject("P04")
        assertEquals(0, memory.historyCount("P04"))
        assertEquals(listOf("Compartida"), memory.notes("P04").map { it.text })
    }

    @Test fun instructionsInAQuestionAreOfferedAsNotes() {
        assertEquals("Responde en español", Memory.instructionIn("¿Qué resolvió el tribunal? responde en español"))
        assertEquals("En máximo 5 líneas", Memory.instructionIn("Resume el fallo en máximo 5 líneas"))
        assertEquals("Always cite the page", Memory.instructionIn("What did it decide? Always cite the page."))
        assertNull(Memory.instructionIn("¿Qué norma se impugna en el requerimiento?"))
    }

    @Test fun aRequestAskedThreeTimesIsOfferedAsASkill() {
        for (r in listOf("17.292", "17.221", "16.900")) memory.record("P04", "Lista los votos disidentes del fallo $r con sus páginas", "…", "Gemini Nano", emptyList())
        memory.record("P04", "¿Quién es el requirente?", "…", "Gemini Nano", emptyList())
        assertEquals(listOf("Lista los votos disidentes del fallo 16.900 con sus páginas"), memory.habits("P04", emptyList()))
        assertTrue(memory.habits("P04", listOf("Votos disidentes Lista los votos disidentes del fallo con sus páginas")).isEmpty())
    }

    @Test fun schemaFourteenUpgrades() {
        val name = "memory-upgrade.db"
        context.deleteDatabase(name)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            Store.MIGRATIONS.take(14).flatten().forEach(db::execSQL); db.version = 14
        }
        Store(context, name).use { assertEquals(0, Memory(it).historyCount("P01")) }
        context.deleteDatabase(name)
    }
}
