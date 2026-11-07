package com.proasiste.lite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.data.AppDatabase
import com.proasiste.lite.data.Library
import com.proasiste.lite.data.NewPassage
import com.proasiste.lite.search.Ranking
import com.proasiste.lite.search.SearchEngine
import com.proasiste.lite.text.Tokenizer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = AppDatabase(context, null)
    private val library = Library(database)
    private val engine = SearchEngine(database)

    @After fun close() = database.close()

    private fun document(projectId: Long, name: String) =
        library.addDocument(projectId, name, File(name), name, 1, 1)

    private fun passage(text: String, page: Int = 1, vector: FloatArray = vectorFor(text)) =
        NewPassage(page, text, Tokenizer.terms(text), vector)

    @Test fun fusionRewardsAgreementBetweenLists() {
        val scores = Ranking.fuse(3, listOf(listOf(0, 1, 2), listOf(1, 0)))
        assertTrue(scores[1] > scores[2])
        assertEquals(scores[0], scores[1], 1e-9)
        assertTrue(scores[0] > scores[2])
    }

    @Test fun rareTermsAndHigherFrequencyScoreHigher() {
        val rare = Ranking.bm25(1, 1, 100, 50, 50.0)
        val common = Ranking.bm25(1, 90, 100, 50, 50.0)
        assertTrue(rare > common)
        assertTrue(Ranking.bm25(5, 1, 100, 50, 50.0) > rare)
    }

    @Test fun exactWordMatchRisesEvenWhenTheVectorIsPoor() {
        val project = library.createProject("P")
        val query = "buzzer"
        val queryVector = vectorFor(query)
        val opposite = FloatArray(queryVector.size) { -queryVector[it] }
        library.savePassages(
            document(project.id, "a"),
            List(20) { passage("filler text number $it", it + 1) } + passage("the buzzer sounds twice", 21, opposite),
        )
        val hits = engine.search(project.id, query, queryVector)
        assertEquals(21, hits.first { "buzzer" in it.text }.page)
        assertTrue(hits.indexOfFirst { "buzzer" in it.text } < 3)
    }

    @Test fun similarVectorIsFoundWithoutAnyMatchingWord() {
        val project = library.createProject("P")
        val queryVector = vectorFor("question")
        library.savePassages(
            document(project.id, "a"),
            listOf(passage("unrelated", 1), passage("close meaning", 2, queryVector), passage("other", 3)),
        )
        assertEquals(2, engine.search(project.id, "question", queryVector).first().page)
    }

    @Test fun resultsStayInsideTheProjectAndSayWhereTheyCameFrom() {
        val one = library.createProject("One")
        val two = library.createProject("Two")
        library.savePassages(document(one.id, "Report one"), listOf(passage("shared words", 4)))
        library.savePassages(document(two.id, "Report two"), listOf(passage("shared words", 9)))
        val hit = engine.search(two.id, "shared", vectorFor("shared words")).single()
        assertEquals("Report two", hit.documentTitle)
        assertEquals(9, hit.page)
        assertEquals("shared words", hit.text)
    }

    @Test fun emptyProjectReturnsNothing() {
        val project = library.createProject("P")
        assertTrue(engine.search(project.id, "anything", vectorFor("anything")).isEmpty())
    }

    @Test fun limitIsRespected() {
        val project = library.createProject("P")
        library.savePassages(document(project.id, "a"), List(30) { passage("word number $it") })
        assertEquals(5, engine.search(project.id, "word", vectorFor("word"), limit = 5).size)
    }

    /**
     * A quick check with 3,000 passages of real text and 768-dimension vectors, so index memory per passage matches the
     * phone's. It doesn't prove the app handles a project of 33,000: that needs a test on the phone.
     */
    @Test fun worksAtThe3000PassageScale() {
        val project = library.createProject("Large")
        val random = Random(1)
        val common = List(2000) { "word$it" }
        val target = randomUnitVector(random, 768)
        val total = 3_000
        var saved = 0
        var documentNumber = 0
        while (saved < total) {
            val passages = List(minOf(2000, total - saved)) { i ->
                val text = (1..60).joinToString(" ") { common[random.nextInt(common.size)] }
                NewPassage(i / 10 + 1, text, Tokenizer.terms(text), randomUnitVector(random, 768))
            }
            library.savePassages(document(project.id, "doc${documentNumber++}"), passages)
            saved += passages.size
        }

        val runtime = Runtime.getRuntime()
        System.gc()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val firstStart = System.nanoTime()
        val first = engine.search(project.id, "word7 word99", target)
        val firstMs = (System.nanoTime() - firstStart) / 1_000_000
        System.gc()
        val heldMb = (runtime.totalMemory() - runtime.freeMemory() - before) / 1_000_000
        val nextStart = System.nanoTime()
        engine.search(project.id, "word12 word500", target)
        val nextMs = (System.nanoTime() - nextStart) / 1_000_000
        println("SIZE-TEST passages=$total firstSearchMs=$firstMs nextSearchMs=$nextMs heldMb=$heldMb")

        assertEquals(SearchEngine.DEFAULT_LIMIT, first.size)
        // The vectors alone take about 9 MB. Well above that would mean passage text is being kept in memory.
        assertTrue("The index holds $heldMb MB for $total passages", heldMb < 30)
    }
}
