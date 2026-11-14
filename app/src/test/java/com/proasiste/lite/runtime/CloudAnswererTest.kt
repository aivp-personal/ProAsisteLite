package com.proasiste.lite.runtime

import com.proasiste.lite.PlantroomModel
import com.proasiste.lite.ui.UiProject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudAnswererTest {
    private val passages = listOf(NanoAnswerer.Passage("STC 2154-11", 4, "La ley penal en blanco debe describir el núcleo de la conducta."))

    @Test fun requestStreamsWithoutSamplingSettings() {
        val body = CloudAnswerer.request("anthropic/claude-opus-5.5", "¿Qué exige la ley penal en blanco?", passages)
        assertEquals("anthropic/claude-opus-5.5", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertTrue(body.getJSONObject("usage").getBoolean("include"))
        // Some current models reject sampling settings with a 400.
        for (k in listOf("temperature", "top_p", "top_k")) assertFalse(k, body.has(k))
        val prompt = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(prompt.contains("1 to 5 short sentences"))
        assertTrue(prompt.contains("[1] STC 2154-11, page 4:"))
        assertTrue(prompt.contains(NanoAnswerer.NOT_FOUND))
    }

    @Test fun nanoKeepsItsShortAnswer() {
        assertTrue(NanoAnswerer.prompt("q", passages).contains("1 to 3 short sentences"))
    }

    @Test fun streamedEventsGiveOnlyAnswerText() {
        assertNull(CloudAnswerer.event(": OPENROUTER PROCESSING"))
        assertNull(CloudAnswerer.event("data: [DONE]"))
        assertNull(CloudAnswerer.event(""))
        val piece = CloudAnswerer.event("""data: {"provider":"Anthropic","choices":[{"delta":{"content":"La ley "}}]}""")!!
        assertEquals("La ley ", CloudAnswerer.delta(piece))
        // Reasoning arrives apart from the content and is not part of the answer.
        val thinking = CloudAnswerer.event("""data: {"choices":[{"delta":{"content":null,"reasoning":"pensando"}}]}""")!!
        assertNull(CloudAnswerer.delta(thinking))
        val last = CloudAnswerer.event("""data: {"choices":[{"delta":{}}],"usage":{"cost":0.0031}}""")!!
        assertNull(CloudAnswerer.delta(last))
        assertEquals(0.0031, last.getJSONObject("usage").getDouble("cost"), 1e-9)
    }

    private fun model(id: String, created: Long, prompt: String = "0.000002", out: List<String> = listOf("text")) = JSONObject()
        .put("id", id).put("name", id.uppercase()).put("created", created).put("context_length", 1_000_000)
        .put("pricing", JSONObject().put("prompt", prompt).put("completion", "0.00001"))
        .put("architecture", JSONObject().put("output_modalities", JSONArray(out)))

    @Test fun catalogKeepsTextModelsNewestFirst() {
        val json = JSONObject().put("data", JSONArray()
            .put(model("deepseek/deepseek-v4-pro", 200))
            .put(model("anthropic/claude-opus-5.5", 300))
            .put(model("someone/image-maker", 400, out = listOf("image")))
            .put(model("openrouter/auto", 100, prompt = "-1"))).toString()
        val catalog = CloudAnswerer.catalog(json)
        assertEquals(listOf("anthropic/claude-opus-5.5", "deepseek/deepseek-v4-pro", "openrouter/auto"), catalog.map { it.id })
        assertEquals(2.0, catalog.first().inputPerMillion, 1e-9)
        assertEquals(10.0, catalog.first().outputPerMillion, 1e-9)
        assertEquals(0.0, catalog.last().inputPerMillion, 1e-9)
    }

    @Test fun suggestionsTakeTheNewestOfEachFamily() {
        val catalog = CloudAnswerer.catalog(JSONObject().put("data", JSONArray()
            .put(model("anthropic/claude-opus-5", 100)).put(model("anthropic/claude-opus-5.5", 300))
            .put(model("deepseek/deepseek-v4-pro", 200)).put(model("deepseek/deepseek-v4-pro:free", 250))
            .put(model("mistralai/mistral-large", 500))).toString())
        assertEquals(listOf("anthropic/claude-opus-5.5", "deepseek/deepseek-v4-pro"), CloudAnswerer.suggestions(catalog).map { it.id })
        assertEquals("ANTHROPIC/CLAUDE-OPUS-5.5", CloudAnswerer.displayName("anthropic/claude-opus-5.5", catalog))
        assertEquals("qwen3-max", CloudAnswerer.displayName("qwen/qwen3-max"))
    }

    @Test fun projectsKeepTheirAnswerModel() {
        val projects = listOf(UiProject("P01", "Legal", answerModel = "deepseek/deepseek-v4-pro"), UiProject("P02", "Personal", incognito = true))
        assertEquals(projects, PlantroomModel.projectsFromJson(PlantroomModel.projectsToJson(projects)))
        // Projects saved before this setting existed answer with Nano.
        val old = PlantroomModel.projectsFromJson("""[{"id":"P04","name":"Legal","incognito":false}]""")!!
        assertEquals("", old.single().answerModel)
    }
}
