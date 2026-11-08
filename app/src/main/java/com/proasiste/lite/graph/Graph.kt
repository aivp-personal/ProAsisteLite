package com.proasiste.lite.graph

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.proasiste.lite.Store
import com.proasiste.lite.inTransaction
import java.text.Normalizer

/** [key] identifies a node within its project and type: the same name, ignoring case, accents and spacing. */
data class GraphNode(val id: Long, val type: String, val key: String, val name: String)

/** A link and where its claim comes from: a link can't exist without a source and page. [fact] states it in plain words. */
data class GraphEdge(val from: GraphNode, val to: GraphNode, val type: String, val sourceId: String, val page: Int, val fact: String = "")

/** A fact to add: a link between two named nodes, the sentence it states, and the page it comes from. */
data class NewFact(val page: Int, val subjectType: String, val subject: String, val relation: String, val objectType: String, val obj: String, val statement: String) {
    fun complete() = page >= 1 && listOf(subjectType, subject, relation, objectType, obj, statement).all { it.isNotBlank() } && statement.length <= 600
}

/** A link's fact ranked for a question. [similarity] is the fact sentence's cosine to the question. */
data class GraphFact(val sourceId: String, val page: Int, val fact: String, val similarity: Float)

data class Reached(val node: GraphNode, val hops: Int)

enum class Direction { OUT, IN, BOTH }

/**
 * Each project's graph, in the same SQLite database as its sources. Node and link types are plain text, so the
 * schema is whatever the caller uses, for example the types of a PC graph. Deleting a source removes its links,
 * and nodes left without any link go with them (see [Store.deleteSource]).
 */
class Graph(private val store: Store) {
    private val db: SQLiteDatabase get() = store.writableDatabase

    /** Returns the node for this name, creating it the first time. The first spelling seen is kept as its name. */
    fun upsertNode(project: String, type: String, name: String): GraphNode {
        val cleanType = type.trim()
        val cleanName = name.trim().replace(WHITESPACE, " ")
        require(cleanType.isNotEmpty() && cleanName.isNotEmpty()) { "A node needs a type and a name." }
        val key = keyOf(cleanName)
        require(key.isNotEmpty()) { "A node name needs letters or digits." }
        db.insertWithOnConflict("graph_nodes", null, ContentValues().apply {
            put("building", project); put("type", cleanType); put("key", key); put("name", cleanName)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return db.rawQuery("SELECT id, type, key, name FROM graph_nodes WHERE building = ? AND type = ? AND key = ?", arrayOf(project, cleanType, key))
            .use { it.moveToFirst(); node(it, 0) }
    }

    /**
     * Links [from] to [to], citing [sourceId] at [page]. Both nodes and the source must belong to [project].
     * Returns false when this exact link was already recorded.
     */
    fun addEdge(project: String, from: GraphNode, to: GraphNode, type: String, sourceId: String, page: Int, fact: String = "", vector: FloatArray? = null): Boolean {
        val cleanType = type.trim()
        require(cleanType.isNotEmpty()) { "A link needs a type." }
        return db.inTransaction {
            val nodes = rawQuery("SELECT COUNT(*) FROM graph_nodes WHERE building = ? AND id IN (?, ?)", arrayOf(project, from.id.toString(), to.id.toString()))
                .use { it.moveToFirst(); it.getInt(0) }
            require(nodes == if (from.id == to.id) 1 else 2) { "Both nodes must be in project $project." }
            val sourceOk = rawQuery("SELECT 1 FROM sources WHERE id = ? AND building = ?", arrayOf(sourceId, project)).use { it.moveToFirst() }
            require(sourceOk) { "Source $sourceId is not in project $project." }
            insertWithOnConflict("graph_edges", null, ContentValues().apply {
                put("from_id", from.id); put("to_id", to.id); put("type", cleanType); put("source", sourceId); put("page", page)
                put("fact", fact.trim()); vector?.let { put("vector", encode(it)) }
            }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        }
    }

    /** Nodes whose name contains [name], ignoring case and accents; exact matches first. */
    fun findNodes(project: String, name: String, type: String? = null): List<GraphNode> {
        val key = keyOf(name)
        if (key.isEmpty()) return emptyList()
        val pattern = "%" + key.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val sql = "SELECT id, type, key, name FROM graph_nodes WHERE building = ? AND key LIKE ? ESCAPE '\\'" +
            (if (type != null) " AND type = ?" else "") + " ORDER BY key <> ?, length(key), key LIMIT 200"
        val args = listOfNotNull(project, pattern, type?.trim(), key).toTypedArray()
        return db.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(node(c, 0)) } }
    }

    /** Every link touching [node], in either direction, with its source and page. */
    fun edgesOf(node: GraphNode): List<GraphEdge> = db.rawQuery(
        """SELECT f.id, f.type, f.key, f.name, t.id, t.type, t.key, t.name, e.type, e.source, e.page, e.fact
           FROM graph_edges e JOIN graph_nodes f ON f.id = e.from_id JOIN graph_nodes t ON t.id = e.to_id
           WHERE e.from_id = ? OR e.to_id = ? ORDER BY e.type, e.source, e.page""",
        arrayOf(node.id.toString(), node.id.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(GraphEdge(node(c, 0), node(c, 4), c.getString(8), c.getString(9), c.getInt(10), c.getString(11))) } }

    /**
     * Nodes reachable from [start] in 1 to [maxHops] steps, nearest first, following links of [linkType]
     * (any type when null) in [direction].
     *
     * A recursive CTE walks the links one step at a time. Each row carries the ids it has visited, and a step
     * that would revisit one is dropped, so a loop (A cites B, B cites A) ends instead of repeating forever.
     * [maxHops] and the row cap bound the work on a densely linked graph.
     */
    fun reach(start: GraphNode, maxHops: Int, linkType: String? = null, direction: Direction = Direction.BOTH): List<Reached> {
        require(maxHops in 1..MAX_HOPS) { "maxHops must be between 1 and $MAX_HOPS." }
        val out = if (direction != Direction.IN) 1 else 0
        val back = if (direction != Direction.OUT) 1 else 0
        // Android binds every argument as text, so numbers are cast back; a null can't be bound, so the type filter is optional SQL.
        val typeFilter = if (linkType != null) "AND e.type = ?5" else ""
        val sql = """
            WITH RECURSIVE walk(id, hops, path) AS (
                SELECT CAST(?1 AS INTEGER), 0, ',' || CAST(?1 AS INTEGER) || ','
                UNION ALL
                SELECT CASE WHEN e.from_id = w.id THEN e.to_id ELSE e.from_id END, w.hops + 1,
                       w.path || (CASE WHEN e.from_id = w.id THEN e.to_id ELSE e.from_id END) || ','
                FROM walk w JOIN graph_edges e ON (CAST(?3 AS INTEGER) = 1 AND e.from_id = w.id) OR (CAST(?4 AS INTEGER) = 1 AND e.to_id = w.id)
                WHERE w.hops < CAST(?2 AS INTEGER) $typeFilter
                  AND instr(w.path, ',' || (CASE WHEN e.from_id = w.id THEN e.to_id ELSE e.from_id END) || ',') = 0
                LIMIT $MAX_ROWS
            )
            SELECT n.id, n.type, n.key, n.name, MIN(w.hops) AS hops
            FROM walk w JOIN graph_nodes n ON n.id = w.id
            WHERE w.id <> CAST(?1 AS INTEGER)
            GROUP BY n.id ORDER BY hops, n.name"""
        val args = listOfNotNull(start.id.toString(), maxHops.toString(), out.toString(), back.toString(), linkType?.trim()).toTypedArray()
        return db.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(Reached(node(c, 0), c.getInt(4))) } }
    }

    /**
     * The project's facts that best match a question: meaning (the fact's embedding) and words (the fact and its
     * two node names) each rank them, fused by rank, as passages are. Facts are short and few, so all are scored.
     */
    fun facts(project: String, queryVector: FloatArray?, queryText: String, limit: Int): List<GraphFact> {
        data class Row(val sourceId: String, val page: Int, val fact: String, val words: List<String>, val similarity: Float)
        val rows = db.rawQuery(
            """SELECT e.source, e.page, e.fact, f.name, t.name, e.vector FROM graph_edges e
               JOIN graph_nodes f ON f.id = e.from_id JOIN graph_nodes t ON t.id = e.to_id
               WHERE f.building = ? AND e.fact <> ''""", arrayOf(project),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val fact = c.getString(2)
                    val similarity = c.getBlob(5)?.let { blob -> queryVector?.let { Store.cosine(it, decode(blob)) } } ?: Float.NaN
                    add(Row(c.getString(0), c.getInt(1), fact, stems("$fact ${c.getString(3)} ${c.getString(4)}"), similarity))
                }
            }
        }
        if (rows.isEmpty() || limit <= 0) return emptyList()
        val fused = DoubleArray(rows.size)
        rows.indices.filter { rows[it].similarity.isFinite() }.sortedByDescending { rows[it].similarity }
            .forEachIndexed { rank, i -> fused[i] += 1.0 / (FUSION_K + rank) }
        val terms = stems(queryText).toSet()
        if (terms.isNotEmpty()) {
            val average = maxOf(1.0, rows.map { it.words.size }.average())
            val withTerm = terms.associateWith { term -> rows.count { term in it.words } }
            val bm25 = rows.map { row ->
                val counts = row.words.groupingBy { it }.eachCount()
                terms.sumOf { term ->
                    val f = counts[term] ?: 0
                    if (f == 0) 0.0 else {
                        val n = withTerm.getValue(term)
                        kotlin.math.ln(1 + (rows.size - n + .5) / (n + .5)) * f * 2.2 / (f + 1.2 * (.25 + .75 * row.words.size / average))
                    }
                }
            }
            rows.indices.filter { bm25[it] > 0 }.sortedByDescending { bm25[it] }.forEachIndexed { rank, i -> fused[i] += 1.0 / (FUSION_K + rank) }
        }
        return rows.indices.filter { fused[it] > 0 }.sortedByDescending { fused[it] }.take(limit)
            .map { GraphFact(rows[it].sourceId, rows[it].page, rows[it].fact, rows[it].similarity) }
    }

    /** Every fact in [project] with one of [relations], in the order of the document: the graph's own answer to a list question. */
    fun factsWithRelations(project: String, relations: Set<String>, limit: Int): List<GraphFact> {
        if (relations.isEmpty()) return emptyList()
        val marks = relations.joinToString(",") { "?" }
        return db.rawQuery(
            """SELECT e.source, e.page, e.fact FROM graph_edges e JOIN graph_nodes f ON f.id = e.from_id
               WHERE f.building = ? AND e.fact <> '' AND e.type IN ($marks) ORDER BY e.source, e.page, e.id LIMIT $limit""",
            (listOf(project) + relations).toTypedArray(),
        ).use { c -> buildList { while (c.moveToNext()) add(GraphFact(c.getString(0), c.getInt(1), c.getString(2), Float.NaN)) } }.distinctBy { it.fact }
    }

    /**
     * Loads a graph file (format proasiste-graph-1) into [project]: facts about one source, each a link between two
     * named nodes with the sentence it states and its page. The source is matched by its contents' SHA-256, then by
     * title. [embed] turns each fact sentence into a vector so search can rank it. Replaces any facts the file already had, and returns how many it now has.
     */
    fun importFile(project: String, json: String, embed: (String) -> FloatArray): Int {
        val root = org.json.JSONObject(json)
        require(root.optString("format") == FORMAT) { "Not a graph file ($FORMAT)." }
        val sha = root.optString("source_sha256")
        val title = root.optString("source_title")
        val sourceId = db.rawQuery(
            "SELECT id FROM sources WHERE building = ? AND (sha256 = ? OR title = ?) ORDER BY sha256 = ? DESC LIMIT 1",
            arrayOf(project, sha, title, sha),
        ).use { if (it.moveToFirst()) it.getString(0) else null }
            ?: error("The file this graph describes ($title) is not in project $project.")
        val facts = root.getJSONArray("facts")
        require(facts.length() <= MAX_FACTS) { "A graph file can hold at most $MAX_FACTS facts." }
        val parsed = (0 until facts.length()).map { i ->
            val f = facts.getJSONObject(i)
            val subject = f.getJSONArray("subject")
            val obj = f.getJSONArray("object")
            NewFact(f.getInt("page"), subject.getString(0), subject.getString(1), f.getString("relation"), obj.getString(0), obj.getString(1), f.getString("statement").trim())
                .also { require(it.complete()) { "Fact ${i + 1} is missing a name, page or statement, or its statement is over 600 characters." } }
        }
        return replaceFacts(project, sourceId, parsed, embed)
    }

    /**
     * Makes [facts] the graph's facts about [sourceId]: its previous facts, and nodes they leave unlinked, go in the
     * same transaction, so a rebuilt or re-imported file never mixes two extractions. Each statement is embedded
     * first. Returns how many facts the file now has.
     */
    fun replaceFacts(project: String, sourceId: String, facts: List<NewFact>, embed: (String) -> FloatArray): Int {
        require(facts.size <= MAX_FACTS) { "At most $MAX_FACTS facts at a time." }
        val vectors = facts.map { embed(it.statement) } // Before the transaction: embedding is the slow part.
        return db.inTransaction {
            execSQL("DELETE FROM graph_edges WHERE source = ? AND fact <> ''", arrayOf(sourceId))
            execSQL("DELETE FROM graph_nodes WHERE building = ? AND NOT EXISTS (SELECT 1 FROM graph_edges e WHERE e.from_id = graph_nodes.id OR e.to_id = graph_nodes.id)", arrayOf(project))
            facts.zip(vectors).count { (f, vector) ->
                val from = upsertNode(project, f.subjectType, f.subject)
                val to = upsertNode(project, f.objectType, f.obj)
                addEdge(project, from, to, f.relation, sourceId, f.page, f.statement, vector)
            }
        }
    }

    /** How many facts [project]'s graph holds for each source. */
    fun factCounts(project: String): Map<String, Int> = db.rawQuery(
        "SELECT e.source, COUNT(*) FROM graph_edges e JOIN sources s ON s.id = e.source WHERE s.building = ? AND e.fact <> '' GROUP BY e.source",
        arrayOf(project),
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) } }

    private fun encode(vector: FloatArray): ByteArray =
        java.nio.ByteBuffer.allocate(vector.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { vector.forEach { putFloat(it) } }.array()

    private fun decode(blob: ByteArray): FloatArray =
        java.nio.ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }

    private fun node(c: Cursor, at: Int) = GraphNode(c.getLong(at), c.getString(at + 1), c.getString(at + 2), c.getString(at + 3))

    companion object {
        const val MAX_HOPS = 4
        /** Word endings removed so that Spanish forms of a word match: "citó", "cita" and "citaron". */
        private val ENDINGS = listOf("ieron", "aron", "iendo", "ando", "ado", "ada", "ido", "ida", "an", "en", "a", "e", "o", "i")

        /** A question's or fact's words, reduced so that forms of one word match. Words with digits stay whole. */
        fun stems(text: String): List<String> = Store.terms(text).map { word ->
            if (word.any(Char::isDigit)) word
            else ENDINGS.firstOrNull { word.endsWith(it) && word.length - it.length >= 3 }?.let { word.removeSuffix(it) } ?: word
        }

        const val FORMAT = "proasiste-graph-1"
        private const val MAX_FACTS = 5_000
        private const val FUSION_K = 60.0
        private const val MAX_ROWS = 50_000
        private val WHITESPACE = Regex("\\s+")
        private val MARKS = Regex("\\p{M}+")
        private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

        /** "Excma. Corte  Suprema" and "excma corte suprema" share a key. Punctuation separates words. */
        fun keyOf(name: String): String =
            Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").replace(NOT_WORD, " ").trim()
    }
}
