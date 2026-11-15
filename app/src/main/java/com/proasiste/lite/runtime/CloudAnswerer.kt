package com.proasiste.lite.runtime

import com.proasiste.lite.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Writes the cited answer with a model the user picked on OpenRouter, from the same passages and with the same prompt
 * and citation format Gemini Nano gets, so the citation fixes and Jev's checks work unchanged. Runs only with a saved
 * key, for a project that is not incognito; the caller decides that.
 */
class CloudAnswerer {
    class CloudException(val code: Int, message: String) : Exception(message)
    /** What a finished answer cost and who served it. [cost] is in US dollars, as OpenRouter reports it. */
    data class Result(val text: String, val cost: Double, val provider: String)
    /** A model in OpenRouter's catalog. Prices are US dollars per million tokens. */
    data class Model(val id: String, val name: String, val inputPerMillion: Double, val outputPerMillion: Double, val context: Int, val created: Long)

    /** Streams the growing answer to [onText], then returns it with its cost. */
    suspend fun answer(key: String, model: String, question: String, passages: List<NanoAnswerer.Passage>, onText: (String) -> Unit, notes: List<String> = emptyList()): Result = withContext(Dispatchers.IO) {
        val c = URL(URL_CHAT).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = CONNECT_TIMEOUT_MS; c.readTimeout = READ_TIMEOUT_MS
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "text/event-stream")
            c.outputStream.use { it.write(request(model, question, passages, notes).toString().toByteArray()) }
            val code = c.responseCode
            if (code != 200) throw CloudException(code, describe(code, model))
            val text = StringBuilder(); var cost = 0.0; var provider = ""
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    coroutineContext.ensureActive()
                    val event = event(line) ?: continue
                    event.optJSONObject("error")?.let { throw CloudException(it.optInt("code", 0), describe(it.optInt("code", 0), model, it.optString("message"))) }
                    if (provider.isEmpty()) provider = event.optString("provider")
                    delta(event)?.let { text.append(it); onText(text.toString()) }
                    event.optJSONObject("usage")?.optDouble("cost", 0.0)?.takeIf { it.isFinite() && it > 0 }?.let { cost = it }
                }
            }
            if (text.isBlank()) throw CloudException(0, tr("$model returned no text.", "$model no devolvió texto."))
            Result(text.toString(), cost, provider)
        } finally { c.disconnect() }
    }

    /** OpenRouter's model catalog. It needs no key. */
    suspend fun models(): List<Model> = withContext(Dispatchers.IO) {
        val c = URL(URL_MODELS).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = CONNECT_TIMEOUT_MS; c.readTimeout = CONNECT_TIMEOUT_MS
            val code = c.responseCode
            if (code != 200) throw CloudException(code, tr("OpenRouter's model list returned error $code.", "La lista de modelos de OpenRouter devolvió el error $code."))
            catalog(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    companion object {
        private const val URL_CHAT = "https://openrouter.ai/api/v1/chat/completions"
        private const val URL_MODELS = "https://openrouter.ai/api/v1/models"
        private const val CONNECT_TIMEOUT_MS = 30_000
        /** Between two streamed pieces; a model that thinks first can be quiet for a while. */
        private const val READ_TIMEOUT_MS = 180_000
        /** Room for a few sentences plus any reasoning a model does before writing. */
        private const val MAX_TOKENS = 4096
        /** How long a cloud answer may be; Nano keeps its own shorter limit. */
        const val SENTENCES = "1 to 5"

        /**
         * The request body. No temperature or other sampling setting: some current models (Claude Opus 5.5 among them)
         * reject them, and the prompt already pins the answer to the passages.
         */
        fun request(model: String, question: String, passages: List<NanoAnswerer.Passage>, notes: List<String> = emptyList()): JSONObject =
            JSONObject().put("model", model).put("stream", true).put("max_tokens", MAX_TOKENS)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", NanoAnswerer.prompt(question, passages, SENTENCES, notes))))
                .put("usage", JSONObject().put("include", true))

        /** The JSON of one server-sent event line, or null for comments, keep-alives and the closing [DONE]. */
        fun event(line: String): JSONObject? {
            if (!line.startsWith("data:")) return null
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") return null
            return runCatching { JSONObject(data) }.getOrNull()
        }

        /** The answer text a streamed event adds; a model's reasoning, sent apart, is not part of it. */
        fun delta(event: JSONObject): String? =
            event.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.let { if (it.isNull("content")) null else it.optString("content") }?.takeIf { it.isNotEmpty() }

        /** Text models from the catalog, newest first. Models without a price (or a negative "varies" price) are kept at 0. */
        fun catalog(json: String): List<Model> {
            val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
            return (0 until data.length()).mapNotNull { i ->
                val m = data.optJSONObject(i) ?: return@mapNotNull null
                val id = m.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val outputs = m.optJSONObject("architecture")?.optJSONArray("output_modalities")
                if (outputs != null && (0 until outputs.length()).none { outputs.optString(it) == "text" }) return@mapNotNull null
                val pricing = m.optJSONObject("pricing")
                fun perMillion(k: String) = (pricing?.optString(k)?.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0) * 1_000_000
                Model(id, m.optString("name").ifBlank { id }, perMillion("prompt"), perMillion("completion"), m.optInt("context_length"), m.optLong("created"))
            }.sortedByDescending { it.created }
        }

        /** Model families offered first, each by its newest entry in the catalog. */
        private val SUGGESTED = listOf("anthropic/claude-opus", "anthropic/claude-sonnet", "deepseek/deepseek", "qwen/qwen", "google/gemini")

        /** The newest catalog model of each suggested family, in the order above. */
        fun suggestions(catalog: List<Model>): List<Model> = SUGGESTED.mapNotNull { prefix ->
            catalog.filter { it.id.startsWith(prefix) && !it.id.endsWith(":free") }.maxByOrNull { it.created }
        }

        /** A model's readable name: the catalog's, or the id without its vendor. */
        fun displayName(id: String, catalog: List<Model> = emptyList()) =
            catalog.firstOrNull { it.id == id }?.name ?: id.substringAfter('/')

        fun describe(code: Int, model: String, detail: String = "") = when (code) {
            401, 403 -> tr("OpenRouter rejected the key.", "OpenRouter rechazó la clave.")
            402 -> tr("The OpenRouter account has no credits.", "La cuenta de OpenRouter no tiene créditos.")
            404 -> tr("OpenRouter has no provider for $model.", "OpenRouter no tiene un proveedor para $model.")
            408 -> tr("$model took too long to answer.", "$model tardó demasiado en responder.")
            429 -> tr("Too many requests to OpenRouter.", "Demasiadas solicitudes a OpenRouter.")
            else -> tr("OpenRouter returned error $code.", "OpenRouter devolvió el error $code.") + (if (detail.isNotBlank()) " ${detail.take(120)}" else "")
        }
    }
}
