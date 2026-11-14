package com.proasiste.lite.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Runs an action's instructions on one document with the project's OpenRouter model. */
class CloudAction {
    data class Result(val text: String, val cost: Double)

    suspend fun run(key: String, model: String, instructions: String, title: String, pages: List<DeepSeekFacts.PageText>): Result = withContext(Dispatchers.IO) {
        val c = URL(URL_CHAT).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 30_000; c.readTimeout = 300_000
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(request(model, instructions, title, pages).toString().toByteArray()) }
            val code = c.responseCode
            if (code != 200) throw CloudAnswerer.CloudException(code, CloudAnswerer.describe(code, model))
            val response = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            response.optJSONObject("error")?.let { throw CloudAnswerer.CloudException(it.optInt("code"), CloudAnswerer.describe(it.optInt("code"), model, it.optString("message"))) }
            val text = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
            if (text.isBlank()) throw CloudAnswerer.CloudException(0, "$model returned no text.")
            Result(text, response.optJSONObject("usage")?.optDouble("cost", 0.0)?.takeIf { it.isFinite() } ?: 0.0)
        } finally { c.disconnect() }
    }

    companion object {
        private const val URL_CHAT = "https://openrouter.ai/api/v1/chat/completions"
        /** About 15,000 tokens of document: a long ruling's first sixty or so pages. */
        const val MAX_CHARS = 60_000

        /** The document as the model reads it: page-marked, cut at [MAX_CHARS] with a note saying where. */
        fun document(pages: List<DeepSeekFacts.PageText>): String {
            val out = StringBuilder(); var last = 0
            for (p in pages.filter { it.text.isNotBlank() }) {
                val part = "=== PAGE ${p.page} ===\n${p.text.trim()}\n\n"
                if (out.length + part.length > MAX_CHARS) {
                    out.append("[The document continues after page $last; only pages up to $last were included.]\n"); break
                }
                out.append(part); last = p.page
            }
            return out.toString().trim()
        }

        /** The request: the action's instructions as the system message, the document as the user's. No sampling settings, which some models reject. */
        fun request(model: String, instructions: String, title: String, pages: List<DeepSeekFacts.PageText>): JSONObject =
            JSONObject().put("model", model).put("max_tokens", 4096)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", instructions))
                    .put(JSONObject().put("role", "user").put("content", "Document: $title\n\n${document(pages)}")))
                .put("usage", JSONObject().put("include", true))
    }
}
