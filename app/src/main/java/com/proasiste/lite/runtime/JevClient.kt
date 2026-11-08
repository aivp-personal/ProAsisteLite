package com.proasiste.lite.runtime

import com.proasiste.lite.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * TypeSafe's Jev decision model, reached through OpenRouter's System One API.
 * Like the rest of the app's network code, it runs only when the user has saved a key
 * and the project is not incognito.
 */
class JevClient {
    class JevException(val code: Int, message: String) : Exception(message)
    enum class Verdict { SUPPORTS, CONTRADICTS, SAYS_NOTHING }
    /** [text] is the sentence without citations, for checking; [raw] is it as written, for rebuilding the answer. */
    data class Claim(val text: String, val citations: List<Int>, val raw: String = "")
    data class Decision(val verdict: Verdict, val confidence: Float)

    /** For each passage, the probability from 0 to 1 that it helps answer the query, in input order. */
    suspend fun helpfulness(key: String, query: String, passages: List<String>): List<Float> = coroutineScope {
        val slots = Semaphore(CONCURRENCY)
        passages.map { passage ->
            async {
                slots.withPermit {
                    val state = JSONObject().put("query", query).put("candidate_passage", passage)
                    val question = JSONObject().put("type", "noul").put("instructions", RERANK_INSTRUCTIONS).put("criteria", RERANK_CRITERIA)
                    val answers = post(key, request(state, "helps", question)).getJSONObject("answers")
                    answers.getJSONObject("helps").getDouble("noul").toFloat()
                }
            }
        }.awaitAll()
    }

    /** Does [section] support [claim]? Sections are the passages the sentence cited. */
    suspend fun check(key: String, claim: String, section: String): Decision {
        val state = JSONObject().put("claim", claim).put("section", section)
        val question = JSONObject().put("type", "choice").put("instructions", CHECK_INSTRUCTIONS).put("criteria", CHECK_CRITERIA)
        val answer = post(key, request(state, "relation", question)).getJSONObject("answers").getJSONObject("relation")
        val verdict = when (answer.getString("choice")) {
            "supports" -> Verdict.SUPPORTS
            "contradicts" -> Verdict.CONTRADICTS
            else -> Verdict.SAYS_NOTHING
        }
        return Decision(verdict, answer.optDouble("confidence", 0.0).toFloat())
    }

    /** True for a key OpenRouter accepts, false for one it rejects. Other failures throw. */
    suspend fun keyAccepted(key: String): Boolean = withContext(Dispatchers.IO) {
        val c = URL(KEY_URL).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("Authorization", "Bearer $key")
            when (val code = c.responseCode) {
                200 -> true
                401, 403 -> false
                else -> throw JevException(code, describe(code))
            }
        } finally { c.disconnect() }
    }

    private fun request(state: JSONObject, name: String, question: JSONObject) =
        JSONObject().put("model", MODEL).put("state", state).put("questions", JSONObject().put(name, question))

    private suspend fun post(key: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val c = URL(SYSTEM_ONE_URL).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
                c.setRequestProperty("Authorization", "Bearer $key")
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                if (code == 200) return@withContext JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                if (code !in RETRYABLE || attempt >= MAX_RETRIES) throw JevException(code, describe(code))
            } finally { c.disconnect() }
            attempt++
            delay(800L shl attempt)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun describe(code: Int) = when (code) {
        401, 403 -> tr("OpenRouter rejected the key.", "OpenRouter rechazó la clave.")
        402 -> tr("The OpenRouter account has no credits.", "La cuenta de OpenRouter no tiene créditos.")
        429 -> tr("Too many requests to OpenRouter.", "Demasiadas solicitudes a OpenRouter.")
        else -> tr("OpenRouter returned error $code.", "OpenRouter devolvió el error $code.")
    }

    companion object {
        const val MODEL = "typesafe/jev-1.14"
        /** A passage needs at least this probability to count as helping. */
        const val HELPFUL = 0.5f
        /** Below this confidence a verdict is shown as unsure. TypeSafe's citation cookbook uses the same cut-off. */
        const val CONFIDENT = 0.8f
        private const val SYSTEM_ONE_URL = "https://openrouter.ai/api/v1/systemone"
        private const val KEY_URL = "https://openrouter.ai/api/v1/key"
        private const val TIMEOUT_MS = 30_000
        private const val CONCURRENCY = 6
        private const val MAX_RETRIES = 2
        private val RETRYABLE = setOf(429, 502, 529)

        private const val RERANK_INSTRUCTIONS = "The query is a question asked about the user's documents and the candidate passage is an excerpt from one of them. Does the candidate passage help answer the query?"
        private val RERANK_CRITERIA get() = JSONObject()
            .put("true", "The candidate passage states the facts that answer the query, or facts needed to answer it.")
            .put("false", "The candidate passage is merely on a similar topic; it does not supply what the query asks for.")
        private const val CHECK_INSTRUCTIONS = "How does the section relate to the claim?"
        private val CHECK_CRITERIA get() = JSONObject()
            .put("supports", "The section states the claim or directly implies that it is true")
            .put("contradicts", "The section states the opposite of the claim or implies it is false")
            .put("says_nothing", "The section does not address what the claim asserts, either way")

        private val CITE = Regex("\\[(\\d+(?:\\s*,\\s*\\d+)*)]")
        private val LEADING_CITES = Regex("^(?:\\s*\\[\\d+(?:\\s*,\\s*\\d+)*]\\s*)+")

        /** Passage numbers cited in [text], in either form: [1] or [1, 2]. */
        fun cites(text: String) = CITE.findAll(text)
            .flatMap { m -> m.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() } }.distinct().toList()

        /** Splits an answer into sentences with the passage numbers each one cites. */
        /**
         * Splits at a full stop, ! or ? followed by space, except when the next word is lowercase ("3.5 oz. of water")
         * or the stop ends a known abbreviation ("Art. 19", "Excma. Corte"), so a unit or title never cuts a claim.
         */
        fun sentences(text: String): List<String> {
            val out = mutableListOf<String>()
            for (piece in text.trim().split(Regex("(?<=[.!?])\\s+(?!\\p{Ll})"))) {
                val previous = out.lastOrNull()
                val lastWord = previous?.let { Regex("([\\p{L}°º]+)\\.$").find(it)?.groupValues?.get(1)?.lowercase() }
                if (previous != null && lastWord != null && lastWord in ABBREVIATIONS) out[out.lastIndex] = "$previous $piece"
                else out += piece
            }
            return out
        }
        private val ABBREVIATIONS = setOf(
            "oz", "min", "mins", "hr", "hrs", "sec", "tbsp", "tsp", "approx", "no", "nos", "vs", "fig", "dept",
            "art", "arts", "inc", "núm", "num", "n°", "nº", "sr", "sra", "sres", "dr", "dra", "excmo", "excma", "ilmo", "ilma",
            "sta", "sto", "pág", "págs", "cap", "lit", "ss", "cfr", "vid", "ej",
        )

        fun claims(answer: String): List<Claim> {
            val out = mutableListOf<Claim>()
            sentences(answer).forEach { raw ->
                var segment = raw
                // "Sentence. [1]" splits with the citation at the start of the next piece.
                val leading = LEADING_CITES.find(segment)?.value.orEmpty()
                if (leading.isNotEmpty() && out.isNotEmpty() && out.last().citations.isEmpty()) {
                    out[out.lastIndex] = out.last().let { it.copy(citations = cites(leading), raw = it.raw + " " + leading.trim()) }
                    segment = segment.removePrefix(leading)
                }
                val text = CITE.replace(segment, "").replace(Regex("\\s+([.,;:!?])"), "\$1").trim()
                if (text.any { it.isLetterOrDigit() }) out += Claim(text, cites(segment), segment.trim())
            }
            return out
        }
    }
}
