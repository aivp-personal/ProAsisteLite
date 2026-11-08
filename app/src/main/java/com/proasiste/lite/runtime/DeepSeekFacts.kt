package com.proasiste.lite.runtime

import com.proasiste.lite.graph.NewFact
import com.proasiste.lite.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

/**
 * Turns a document's pages into graph facts with DeepSeek, reached through OpenRouter with the user's saved key.
 *
 * A court ruling goes through legal mode ([LegalFacts]): two passes over the whole ruling with fixed types,
 * relations and naming, and stricter checks that hold doubtful facts back for review. Any other document goes one
 * page per request, several side by side, and the app assigns the page: sent several at once, the model mislabels
 * pages and carries one page's language onto another. A fact is kept only if every number and most words in it are
 * on its page. Either way the model answers in a fixed JSON shape.
 * Like the app's other network code, the caller runs it only with a saved key and never for an incognito project.
 */
class DeepSeekFacts(private val send: suspend (key: String, body: JSONObject) -> JSONObject = ::post) {
    class PageText(val page: Int, val text: String)
    /** [review] facts were held back by legal mode's checks; [legal] says which mode ran; [failedParts] replies stayed unreadable after a retry. */
    class Result(val facts: List<NewFact>, val pagesSent: List<Int>, val dropped: Int, val cost: Double, val review: Int = 0, val legal: Boolean = false, val failedParts: Int = 0)
    private class Reply(val content: String?, val cost: Double)

    /** Sends a request, asking once more if the reply is cut off or isn't readable JSON. Network and account errors still throw. */
    private suspend fun ask(key: String, body: JSONObject): Reply {
        var spent = 0.0
        repeat(2) {
            val started = System.currentTimeMillis()
            val response = send(key, body)
            spent += cost(response)
            log(response, System.currentTimeMillis() - started)
            val text = content(response)
            if (finishReason(response) != "length" && text != null && runCatching { facts(text) }.isSuccess) return Reply(text, spent)
        }
        return Reply(null, spent)
    }

    suspend fun extract(key: String, title: String, pages: List<PageText>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Result {
        val readable = pages.filter { it.text.isNotBlank() }
        if (LegalFacts.isRuling(readable) && readable.sumOf { it.text.length } <= LEGAL_MAX_CHARS) return legal(key, title, readable, onProgress)
        val done = AtomicInteger()
        onProgress(0, readable.size)
        val slots = Semaphore(PARALLEL)
        val results = coroutineScope {
            readable.map { page ->
                async {
                    slots.withPermit {
                        val reply = ask(key, request(INSTRUCTIONS, SCHEMA, title, listOf(page)))
                        val (kept, rejected) = reply.content?.let { parse(it, page) } ?: (emptyList<NewFact>() to 0)
                        onProgress(done.incrementAndGet(), readable.size)
                        Triple(kept, rejected, reply)
                    }
                }
            }.awaitAll()
        }
        return Result(results.flatMap { it.first }, readable.map { it.page }, results.sumOf { it.second }, results.sumOf { it.third.cost },
            failedParts = results.count { it.third.content == null })
    }

    /** Legal mode: structure first, then content reusing its node names; every fact checked against the ruling. */
    private suspend fun legal(key: String, title: String, pages: List<PageText>, onProgress: (done: Int, total: Int) -> Unit): Result {
        val thisRuling = LegalFacts.thisRuling(pages, title)
        onProgress(0, 2)
        val first = ask(key, request(LegalFacts.structurePrompt(thisRuling), LegalFacts.SCHEMA, title, pages))
        // Pass 2 depends on pass 1's names, so nothing can be built without it.
        val structure = facts(first.content ?: error(tr("DeepSeek's reply couldn't be read, twice. Try again.", "La respuesta de DeepSeek no se pudo leer, dos veces. Inténtalo de nuevo.")))
        onProgress(1, 2)
        val nodes = structure.flatMap { listOf(it.subjectType to it.subject, it.objectType to it.obj) }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        val second = ask(key, request(LegalFacts.contentPrompt(thisRuling, nodes), LegalFacts.SCHEMA, title, pages))
        onProgress(2, 2)
        val court = LegalFacts.courtOf(pages.first().text) ?: LegalFacts.Court.TC
        val all = LegalFacts.dedupe(LegalFacts.mergeRulings(structure + second.content?.let { facts(it) }.orEmpty(), court))
        val checker = LegalFacts.Checker(pages, thisRuling)
        val verdicts = all.groupBy { checker.check(it) }
        val review = verdicts[LegalFacts.Verdict.REVIEW].orEmpty()
        review.forEach { debugLog("review p${it.page}: ${it.statement}") }
        return Result(verdicts[LegalFacts.Verdict.KEPT].orEmpty(), pages.map { it.page }, verdicts[LegalFacts.Verdict.DROPPED].orEmpty().size,
            first.cost + second.cost, review = review.size, legal = true, failedParts = if (second.content == null) 1 else 0)
    }

    companion object {
        const val MODEL = "deepseek/deepseek-v4-pro"
        private const val URL_CHAT = "https://openrouter.ai/api/v1/chat/completions"
        /** Pages sent at once for an ordinary document; the model's output speed, not the network, is the limit. */
        private const val PARALLEL = 6
        /** A ruling up to about 60,000 tokens goes whole to each legal pass; a longer one is read in batches instead. */
        const val LEGAL_MAX_CHARS = 240_000
        private const val TIMEOUT_MS = 600_000
        private const val MAX_TOKENS = 64_000
        private const val MIN_WORDS_TO_CHECK = 3
        private const val MIN_WORDS_ON_PAGE = 0.5
        private const val MAX_RETRIES = 2
        private val RETRYABLE = setOf(429, 500, 502, 503, 529)
        private val NUMBER = Regex("\\d+(?:[.,]\\d+)?")

        private const val INSTRUCTIONS = """You turn pages of a document into facts for a knowledge graph.
Each fact links two named things, from one to the other, and states, in one self-contained sentence, what the page says about them.
Rules:
- Use only what the pages state. Never add outside knowledge, advice or guesses.
- Copy every number and unit exactly as the page gives it.
- A table becomes one fact per row. The statement gives every value in that row, one for each column, with the row's and columns' labels, so it makes sense alone.
- Use the same name every time for the same thing. Types and relations are short; relations are UPPER_SNAKE_CASE.
- Never translate. Write each statement in the language of the page's text, using its own words.
- Cover everything on the page that has content, including each recipe's ingredients and steps. Don't summarise a page into one fact.
- page is the number in the page marker, not a number printed on the page.
- Skip tables of contents, page numbers, slogans, contact details and legal boilerplate.
Return JSON only."""

        private val SCHEMA: JSONObject get() = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray().put("facts"))
            .put("properties", JSONObject().put("facts", JSONObject().put("type", "array").put("items",
                JSONObject().put("type", "object").put("additionalProperties", false)
                    // Named so that a provider sorting the keys still has the model write the link's start before its end.
                    .put("required", JSONArray(listOf("from", "from_type", "page", "relation", "statement", "to", "to_type")))
                    .put("properties", JSONObject()
                        .put("page", JSONObject().put("type", "integer"))
                        .put("from_type", JSONObject().put("type", "string")).put("from", JSONObject().put("type", "string"))
                        .put("relation", JSONObject().put("type", "string"))
                        .put("to_type", JSONObject().put("type", "string")).put("to", JSONObject().put("type", "string"))
                        .put("statement", JSONObject().put("type", "string"))))))

        fun request(title: String, batch: List<PageText>) = request(INSTRUCTIONS, SCHEMA, title, batch)

        fun request(instructions: String, schema: JSONObject, title: String, batch: List<PageText>): JSONObject {
            val pages = batch.joinToString("\n\n") { "=== PAGE ${it.page} ===\n${it.text.trim()}" }
            // Extraction needs no hidden reasoning, which would be slower, cost more and use up max_tokens before the answer.
            return JSONObject().put("model", MODEL).put("temperature", 0).put("max_tokens", MAX_TOKENS)
                .put("reasoning", JSONObject().put("enabled", false))
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", instructions))
                    .put(JSONObject().put("role", "user").put("content", "Document: $title\n\n$pages")))
                .put("response_format", JSONObject().put("type", "json_schema")
                    .put("json_schema", JSONObject().put("name", "facts").put("strict", true).put("schema", schema)))
                // Only route to providers that honour the JSON schema, and report what the call cost.
                .put("provider", JSONObject().put("require_parameters", true))
                .put("usage", JSONObject().put("include", true))
        }

        private fun choice(response: JSONObject) = response.optJSONArray("choices")?.optJSONObject(0)

        private fun content(response: JSONObject) = choice(response)?.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }

        private fun finishReason(response: JSONObject) = choice(response)?.optString("finish_reason").orEmpty()

        /** What a request used, to diagnose cut-off or slow replies. Never the text or the key. */
        private fun log(response: JSONObject, millis: Long) {
            val usage = response.optJSONObject("usage")
            android.util.Log.i("ProAsisteGraph", "deepseek finish=${finishReason(response)} provider=${response.optString("provider")} ms=$millis " +
                "in=${usage?.optInt("prompt_tokens")} out=${usage?.optInt("completion_tokens")} " +
                "reasoning=${usage?.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens")} chars=${content(response)?.length}")
        }

        private fun cost(response: JSONObject) =
            response.optJSONObject("usage")?.optDouble("cost", 0.0)?.takeIf { it.isFinite() } ?: 0.0

        /** Every fact in a reply, unchecked. */
        fun facts(content: String): List<NewFact> {
            val items = JSONObject(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```")).getJSONArray("facts")
            return (0 until items.length()).mapNotNull { i ->
                items.optJSONObject(i)?.let {
                    NewFact(it.optInt("page", -1), it.optString("from_type"), it.optString("from"), it.optString("relation"),
                        it.optString("to_type"), it.optString("to"), it.optString("statement").trim())
                }
            }
        }

        /** The facts in a model reply about [page] that pass the checks, and how many were dropped. The page number is the app's, never the model's. */
        fun parse(content: String, page: PageText): Pair<List<NewFact>, Int> {
            val available = numbers(page.text)
            val vocabulary = words(page.text)
            val kept = mutableListOf<NewFact>(); var dropped = 0
            for (fact in facts(content).map { it.copy(page = page.page) }) {
                val reason = when {
                    !fact.complete() -> "incomplete"
                    !available.containsAll(numbers(fact.statement)) -> "number not on page"
                    !usesPageWords(fact.statement, vocabulary) -> "words not on page"
                    else -> null
                }
                if (reason == null) kept += fact else {
                    dropped++
                    debugLog("dropped ($reason) p${fact.page}: ${fact.statement}")
                }
            }
            return kept to dropped
        }

        /** The longer words of [text], lower case and without accents: the ones that carry its meaning. */
        fun words(text: String): Set<String> = Regex("\\p{L}{4,}")
            .findAll(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), ""))
            .map { it.value }.toSet()

        /** Most of a statement's words must be on its page, which catches a translated or invented fact. Very short statements pass. */
        fun usesPageWords(statement: String, page: Set<String>): Boolean {
            val own = words(statement)
            return own.size < MIN_WORDS_TO_CHECK || own.count { it in page } >= own.size * MIN_WORDS_ON_PAGE
        }

        /** Fact text can be confidential, so it is only ever logged in debug builds. */
        fun debugLog(message: String) {
            if (com.proasiste.lite.BuildConfig.DEBUG) android.util.Log.d("ProAsisteGraph", message)
        }

        /** Every number in [text], with fraction characters and superscripts normalised so "¼" matches "1/4". */
        fun numbers(text: String): Set<String> =
            NUMBER.findAll(Normalizer.normalize(text, Normalizer.Form.NFKC)).map { it.value.replace(',', '.') }.toSet()

        private suspend fun post(key: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
            var attempt = 0
            while (true) {
                val c = URL(URL_CHAT).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "POST"; c.doOutput = true
                    c.connectTimeout = 30_000; c.readTimeout = TIMEOUT_MS
                    c.setRequestProperty("Authorization", "Bearer $key")
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = c.responseCode
                    if (code == 200) return@withContext JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    if (code !in RETRYABLE || attempt >= MAX_RETRIES) throw JevClient.JevException(code, describe(code))
                } finally { c.disconnect() }
                attempt++
                delay(1500L shl attempt)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

        private fun describe(code: Int) = when (code) {
            401, 403 -> tr("OpenRouter rejected the key.", "OpenRouter rechazó la clave.")
            402 -> tr("The OpenRouter account has no credits.", "La cuenta de OpenRouter no tiene créditos.")
            404 -> tr("OpenRouter has no provider for $MODEL with structured output.", "OpenRouter no tiene un proveedor de $MODEL con salida estructurada.")
            429 -> tr("Too many requests to OpenRouter.", "Demasiadas solicitudes a OpenRouter.")
            else -> tr("OpenRouter returned error $code.", "OpenRouter devolvió el error $code.")
        }
    }
}
