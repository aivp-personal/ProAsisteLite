package com.proasiste.lite.runtime

import com.proasiste.lite.tags.TagDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Picks which of the user's described tags apply to a file, with the project's OpenRouter model. The model reads the
 * file's first pages and may only choose from the given list; anything else it returns is dropped.
 */
class CloudTagger {
    /** The tags chosen, by their exact names, and what the call cost in US dollars. */
    data class Result(val tags: List<String>, val cost: Double)

    suspend fun tag(key: String, model: String, title: String, text: String, defs: List<TagDef>): Result = withContext(Dispatchers.IO) {
        val c = URL(URL_CHAT).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 30_000; c.readTimeout = 180_000
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(request(model, title, text, defs).toString().toByteArray()) }
            val code = c.responseCode
            if (code != 200) throw CloudAnswerer.CloudException(code, CloudAnswerer.describe(code, model))
            val response = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            response.optJSONObject("error")?.let { throw CloudAnswerer.CloudException(it.optInt("code"), CloudAnswerer.describe(it.optInt("code"), model, it.optString("message"))) }
            val content = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            Result(parse(content, defs), response.optJSONObject("usage")?.optDouble("cost", 0.0)?.takeIf { it.isFinite() } ?: 0.0)
        } finally { c.disconnect() }
    }

    companion object {
        private const val URL_CHAT = "https://openrouter.ai/api/v1/chat/completions"
        /** Enough of a file to tell what it is about; most of a ruling's subject is in its first pages. */
        const val MAX_CHARS = 8000

        private const val INSTRUCTIONS = """You tag documents for the user's library.
Read the document's first pages below and decide which of the user's tags apply to it.
Rules:
- Choose only from the listed tags, writing each name exactly as listed.
- A tag applies only when the text clearly matches its description. When in doubt, leave it out.
- Return JSON only: {"tags": [...]}, with an empty list if none applies."""

        /**
         * The request body. The answer is constrained to the listed names where the provider supports it; no sampling
         * settings are sent, since some current models reject them.
         */
        fun request(model: String, title: String, text: String, defs: List<TagDef>): JSONObject {
            val list = defs.joinToString("\n") { "- ${it.name}: ${it.description}" }
            val schema = JSONObject().put("type", "object").put("additionalProperties", false).put("required", JSONArray().put("tags"))
                .put("properties", JSONObject().put("tags", JSONObject().put("type", "array")
                    .put("items", JSONObject().put("type", "string").put("enum", JSONArray(defs.map { it.name })))))
            return JSONObject().put("model", model).put("max_tokens", 2048)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", INSTRUCTIONS))
                    .put(JSONObject().put("role", "user").put("content", "Tags:\n$list\n\nDocument: $title\n\n${text.take(MAX_CHARS)}")))
                .put("response_format", JSONObject().put("type", "json_schema")
                    .put("json_schema", JSONObject().put("name", "tags").put("strict", true).put("schema", schema)))
                .put("usage", JSONObject().put("include", true))
        }

        /**
         * The listed tags in a reply, by their exact names. Accepts the JSON object, a fenced block or JSON within text,
         * ignores case and accents in the names, and drops anything not on the list.
         */
        fun parse(content: String, defs: List<TagDef>): List<String> {
            val start = content.indexOf('{'); val end = content.lastIndexOf('}')
            if (start < 0 || end <= start) return emptyList()
            val names = runCatching { JSONObject(content.substring(start, end + 1)).optJSONArray("tags") }.getOrNull() ?: return emptyList()
            val byKey = defs.associateBy { fold(it.name) }
            return (0 until names.length()).mapNotNull { byKey[fold(names.optString(it))]?.name }.distinct()
        }

        private fun fold(s: String) = java.text.Normalizer.normalize(s.trim().lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }
}
