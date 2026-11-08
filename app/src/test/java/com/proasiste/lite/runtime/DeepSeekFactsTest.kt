package com.proasiste.lite.runtime

import com.proasiste.lite.runtime.DeepSeekFacts.PageText
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeepSeekFactsTest {
    private val page6 = PageText(6, "Hard-Boiled: # of Eggs 12, amount of water 3.5 oz., Time 20 min\nTIP: add up to 25% more water")
    private val page16 = PageText(16, "1-3 small corn/flour tortillas ... 8 min · ² ∕3 cup mayonnaise ¼ cup mustard")

    private fun fact(page: Int, statement: String, subject: String = "Hard-boiled eggs") = JSONObject()
        .put("page", page).put("from_type", "Egg style").put("from", subject).put("relation", "COOKING_GUIDE")
        .put("to_type", "Batch").put("to", "12 eggs").put("statement", statement)

    private fun reply(vararg facts: JSONObject, cost: Double = 0.0012) = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", JSONObject().put("facts", JSONArray(facts.toList())).toString()))))
        .put("usage", JSONObject().put("cost", cost))

    private fun content(vararg facts: JSONObject) = JSONObject().put("facts", JSONArray(facts.toList())).toString()

    @Test fun keepsFactsWhoseNumbersAreOnTheirPage() {
        val (kept, dropped) = DeepSeekFacts.parse(content(
            fact(6, "12 hard-boiled eggs: 3.5 oz of water, about 20 minutes."),
            fact(6, "12 hard-boiled eggs: 4 oz of water, about 20 minutes."), // 4 is not on page 6
            fact(6, ""),
        ), page6)
        assertEquals(listOf(6), kept.map { it.page })
        assertEquals(2, dropped)
        val (fractions, _) = DeepSeekFacts.parse(content(fact(16, "Use 2/3 cup mayonnaise and 1/4 cup mustard.", subject = "Recipe")), page16)
        assertEquals(1, fractions.size) // fraction characters match
    }

    @Test fun thePageNumberIsTheAppsNotTheModels() {
        val (kept, _) = DeepSeekFacts.parse(content(fact(10, "12 hard-boiled eggs: 3.5 oz of water, about 20 minutes.")), page6)
        assertEquals(listOf(6), kept.map { it.page })
    }

    @Test fun numbersNormaliseFractionsAndCommas() {
        assertEquals(setOf("2", "3", "1", "4"), DeepSeekFacts.numbers("² ∕3 cup, ¼ cup"))
        assertEquals(setOf("3.5"), DeepSeekFacts.numbers("3,5 oz"))
    }

    @Test fun requestAsksForTheSchemaAndMarksPages() {
        val body = DeepSeekFacts.request("Manual.pdf", listOf(page6))
        assertEquals(DeepSeekFacts.MODEL, body.getString("model"))
        assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
        assertTrue(body.getJSONObject("provider").getBoolean("require_parameters"))
        assertTrue(body.getJSONArray("messages").getJSONObject(1).getString("content").contains("=== PAGE 6 ==="))
    }

    @Test fun aFactTranslatedFromItsPageIsDropped() {
        val page = PageText(6, "Hard-Boiled: # of Eggs 12, amount of water 3.5 oz., Time 20 min")
        val content = JSONObject().put("facts", JSONArray()
            .put(fact(6, "Pour 12 œufs durs, la quantité d'eau est de 3.5 oz et le temps de cuisson est de 20 min."))
            .put(fact(6, "Hard-Boiled, 12 eggs: amount of water 3.5 oz., time 20 min."))).toString()
        val (kept, dropped) = DeepSeekFacts.parse(content, page)
        assertEquals(listOf("Hard-Boiled, 12 eggs: amount of water 3.5 oz., time 20 min."), kept.map { it.statement })
        assertEquals(1, dropped)
    }

    @Test fun requestForbidsTranslating() {
        val system = DeepSeekFacts.request("Manual.pdf", listOf(page6)).getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("Never translate"))
    }

    @Test fun requestTurnsReasoningOff() {
        val body = DeepSeekFacts.request("Manual.pdf", listOf(page6))
        assertEquals(false, body.getJSONObject("reasoning").getBoolean("enabled"))
    }

    private fun cutOff() = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("finish_reason", "length").put("message", JSONObject().put("content", "{\"facts\": [{\"object\": \"Pack"))))
        .put("usage", JSONObject().put("cost", 0.001))

    @Test fun aCutOffReplyIsAskedForAgain() = runBlocking {
        var calls = 0
        val client = DeepSeekFacts { _, _ -> if (++calls == 1) cutOff() else reply(fact(6, "12 hard-boiled eggs: 3.5 oz of water, about 20 minutes.")) }
        val result = client.extract("test-key", "Manual.pdf", listOf(page6))
        assertEquals(2, calls)
        assertEquals(1, result.facts.size)
        assertEquals(0, result.failedParts)
        assertEquals(0.0022, result.cost, 1e-9)
    }

    @Test fun aPartThatStaysUnreadableDoesNotLoseTheOthers() = runBlocking {
        val long = (1..10).map { PageText(it, if (it == 6) page6.text + " " + "x".repeat(4_990) else "y".repeat(5_000)) }
        val client = DeepSeekFacts { _, body ->
            if (body.getJSONArray("messages").getJSONObject(1).getString("content").contains("=== PAGE 6 ===")) reply(fact(6, "12 hard-boiled eggs: 3.5 oz of water, about 20 minutes."))
            else cutOff()
        }
        val result = client.extract("test-key", "Manual.pdf", long)
        assertEquals(1, result.facts.size)
        assertEquals(9, result.failedParts)
    }

    @Test fun extractSendsEachPageAddsUpCostAndSkipsBlankPages() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        val client = DeepSeekFacts { key, body ->
            assertEquals("test-key", key)
            sent += body
            reply(fact(6, "12 hard-boiled eggs: 3.5 oz of water, about 20 minutes."))
        }
        val result = client.extract("test-key", "Manual.pdf", listOf(page6, PageText(7, "  ")))
        assertEquals(1, sent.size)
        assertEquals(listOf(6), result.pagesSent)
        assertEquals(1, result.facts.size)
        assertEquals(0.0012, result.cost, 1e-9)
    }
}
