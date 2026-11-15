package com.proasiste.lite.memory

import android.content.ContentValues
import com.proasiste.lite.Store
import com.proasiste.lite.graph.Graph
import com.proasiste.lite.inTransaction
import org.json.JSONArray
import org.json.JSONObject

/** One answered question in a project's history, and the sources it used. */
data class HistoryEntry(val id: Long, val building: String, val question: String, val answer: String, val writer: String, val sources: List<HistorySource>,
                        val opened: List<String>, val reportId: Long?, val created: Long)
data class HistorySource(val sourceId: String, val excerpt: String, val location: String, val highlight: String)

/** Something to keep in mind for every answer: a [PREFERENCE] ("answer in Spanish") or a standing [FACT]. Null [building] means every project. */
data class MemoryNote(val id: Long, val building: String?, val kind: String, val text: String, val created: Long)

/**
 * The app's memory (step 8, schema 15), for one person on one phone.
 * - History (episodic): every answered question with its answer, writer and sources, and what was done next (sources
 *   opened, saved as a report). Never kept for incognito projects; deleted one by one or all at once.
 * - Notes (semantic): preferences and standing facts, for one project or every project, added or accepted by the person.
 *   They go with the question when an answer is written, as instructions, never as passages, so they are never cited.
 * - Habits (procedural): questions asked again and again, offered as skills.
 */
class Memory(private val store: Store) {
    private val db get() = store.writableDatabase

    // ---- History ----

    fun record(building: String, question: String, answer: String, writer: String, sources: List<HistorySource>, now: Long = System.currentTimeMillis()): Long {
        if (question.isBlank() || answer.isBlank()) return 0
        return db.insert("history", null, ContentValues().apply {
            put("building", building); put("question", question.trim().take(1000)); put("answer", answer.trim().take(8000)); put("writer", writer.take(80))
            put("sources", JSONArray(sources.map { JSONObject().put("id", it.sourceId).put("excerpt", it.excerpt.take(600)).put("location", it.location).put("highlight", it.highlight) }).toString())
            put("opened", ""); put("created", now)
        })
    }

    /** Notes that a source was opened from this answer. */
    fun opened(id: Long, sourceId: String) {
        val now = history(id)?.opened ?: return
        if (sourceId in now) return
        db.update("history", ContentValues().apply { put("opened", (now + sourceId).joinToString(",")) }, "id = ?", arrayOf(id.toString()))
    }

    fun savedAsReport(id: Long, reportId: Long) { db.update("history", ContentValues().apply { put("report_id", reportId) }, "id = ?", arrayOf(id.toString())) }

    fun history(building: String, query: String = "", limit: Int = 300): List<HistoryEntry> {
        val words = Graph.keyOf(query).split(" ").filter { it.isNotEmpty() }
        return db.rawQuery("SELECT id, building, question, answer, writer, sources, opened, report_id, created FROM history WHERE building = ? ORDER BY created DESC, id DESC LIMIT ${if (words.isEmpty()) limit else 5000}", arrayOf(building))
            .use { c -> buildList { while (c.moveToNext()) add(entry(c)) } }
            .filter { e -> words.isEmpty() || Graph.keyOf(e.question + " " + e.answer).let { k -> words.all { it in k } } }.take(limit)
    }

    fun history(id: Long): HistoryEntry? = db.rawQuery("SELECT id, building, question, answer, writer, sources, opened, report_id, created FROM history WHERE id = ?", arrayOf(id.toString()))
        .use { if (it.moveToFirst()) entry(it) else null }

    private fun entry(c: android.database.Cursor): HistoryEntry {
        val s = JSONArray(c.getString(5))
        return HistoryEntry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
            (0 until s.length()).map { i -> s.getJSONObject(i).let { HistorySource(it.getString("id"), it.optString("excerpt"), it.optString("location"), it.optString("highlight")) } },
            c.getString(6).split(",").filter { it.isNotEmpty() }, if (c.isNull(7)) null else c.getLong(7), c.getLong(8))
    }

    fun deleteHistory(id: Long) = db.delete("history", "id = ?", arrayOf(id.toString()))
    /** Deletes [building]'s history, or only what was asked between [from] and [until] (epoch ms). */
    fun clearHistory(building: String, from: Long = 0, until: Long = Long.MAX_VALUE) = db.delete("history", "building = ? AND created >= ? AND created <= ?", arrayOf(building, from.toString(), until.toString()))
    fun historyCount(building: String): Int = db.rawQuery("SELECT COUNT(*) FROM history WHERE building = ?", arrayOf(building)).use { it.moveToFirst(); it.getInt(0) }

    // ---- Notes ----

    fun addNote(building: String?, kind: String, text: String): Long {
        val clean = text.replace(Regex("\\s+"), " ").trim().take(300)
        require(clean.isNotEmpty())
        if (notes(building ?: "").any { Graph.keyOf(it.text) == Graph.keyOf(clean) }) return 0
        return db.insert("memories", null, ContentValues().apply { if (building != null) put("building", building) else putNull("building"); put("kind", kind); put("text", clean); put("created", System.currentTimeMillis()) })
    }

    fun editNote(id: Long, text: String) { db.update("memories", ContentValues().apply { put("text", text.replace(Regex("\\s+"), " ").trim().take(300)) }, "id = ?", arrayOf(id.toString())) }
    fun deleteNote(id: Long) = db.delete("memories", "id = ?", arrayOf(id.toString()))

    /** [building]'s own notes, then the notes for every project. */
    fun notes(building: String): List<MemoryNote> = db.rawQuery("SELECT id, building, kind, text, created FROM memories WHERE building = ? OR building IS NULL ORDER BY building IS NULL, created", arrayOf(building))
        .use { c -> buildList { while (c.moveToNext()) add(MemoryNote(c.getLong(0), if (c.isNull(1)) null else c.getString(1), c.getString(2), c.getString(3), c.getLong(4))) } }

    /**
     * The notes that go with a question, as lines for the answer prompt. A project's own note wins over a note for every
     * project of the same kind and subject (it comes first, and the prompt says the first one wins).
     */
    fun forPrompt(building: String): List<String> = notes(building).map { (if (it.kind == FACT) "Fact: " else "") + it.text }

    // ---- Habits ----

    /**
     * Requests asked at least [times] times in [building]'s history: questions whose words, without names and numbers,
     * mostly overlap ("lista los votos disidentes de STC 17.292", "… de STC 17.221"). Each comes as the latest wording,
     * for a skill drafted from the person's own words. Requests already saved as skills ([skills]) are left out.
     */
    fun habits(building: String, skills: List<String>, times: Int = 3): List<String> {
        fun shape(q: String) = Graph.keyOf(q).split(" ").filter { it.length >= 4 && it.none(Char::isDigit) && it !in COMMON }.toSet()
        val asked = history(building, limit = 400).map { it.question to shape(it.question) }.filter { it.second.size >= 2 }
        val known = skills.map(::shape)
        val groups = mutableListOf<MutableList<Pair<String, Set<String>>>>()
        for (q in asked) groups.firstOrNull { g -> jaccard(g.first().second, q.second) >= 0.6 }?.add(q) ?: groups.add(mutableListOf(q))
        return groups.filter { it.size >= times && known.none { k -> jaccard(k, it.first().second) >= 0.6 } }.map { it.first().first }
    }

    private fun jaccard(a: Set<String>, b: Set<String>) = if (a.isEmpty() && b.isEmpty()) 0.0 else (a intersect b).size.toDouble() / (a union b).size

    companion object {
        const val PREFERENCE = "preferencia"; const val FACT = "dato"
        private val COMMON = setOf("cual", "cuales", "como", "donde", "cuando", "quien", "quienes", "sobre", "para", "este", "esta", "estos", "estas", "what", "which", "where", "when", "about", "this", "that", "with", "from")

        private val INSTRUCTIONS = listOf(
            Regex("(?i)\\b(?:responde|contesta|escribe|answer|reply|write)(?:me)?\\s+(?:siempre\\s+|always\\s+)?(?:en|in)\\s+(?:español|castellano|inglés|ingles|english|spanish)\\b"),
            Regex("(?i)\\b(?:en|in)\\s+(?:máximo|maximo|a lo más|at most|no more than)\\s+\\d+\\s+(?:líneas|lineas|frases|oraciones|párrafos|palabras|lines|sentences|paragraphs|words)\\b"),
            Regex("(?i)\\b(?:siempre|always)\\s+[^.?!]{6,80}"),
            Regex("(?i)\\b(?:sin|without)\\s+(?:citas largas|tecnicismos|jerga|jargon|long quotes)\\b"),
            Regex("(?i)\\b(?:usa|use)\\s+(?:viñetas|vinetas|bullet points|una tabla|a table)\\b"),
        )

        /** An instruction about how to answer inside a question ("…, responde en español"), worth keeping as a preference; null if none. */
        fun instructionIn(question: String): String? = INSTRUCTIONS.firstNotNullOfOrNull { it.find(question)?.value?.trim()?.trimEnd(',', ';') }
            ?.replaceFirstChar { it.uppercase() }
    }
}
