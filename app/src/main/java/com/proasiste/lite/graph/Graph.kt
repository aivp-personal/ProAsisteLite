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
data class NewFact(val page: Int, val subjectType: String, val subject: String, val relation: String, val objectType: String, val obj: String, val statement: String,
                   /** Who wrote the fact: "reglas", "deepseek/<model>@<prompt version>", "revisado", or "" for an import. */
                   val extractor: String = "",
                   /** Where the fact sits: "page:x0,y0,x1,y1;…" on DocLang's 500 grid (both halves of a split paragraph), or "" when unknown. */
                   val place: String = "") {
    fun complete() = page >= 1 && listOf(subjectType, subject, relation, objectType, obj, statement).all { it.isNotBlank() } && statement.length <= 600
}

/** A link's fact ranked for a question. [similarity] is the fact sentence's cosine to the question. */
data class GraphFact(val sourceId: String, val page: Int, val fact: String, val similarity: Float, val place: String = "")

/** A link as drawn: both ends, its type, where it came from, and the fact with its place on the page. */
data class ViewEdge(val from: GraphNode, val to: GraphNode, val type: String, val sourceId: String, val page: Int, val fact: String, val place: String)

data class Reached(val node: GraphNode, val hops: Int)

enum class Direction { OUT, IN, BOTH }

/**
 * Each project's graph, in the same SQLite database as its sources. Node and link types are plain text, so the
 * schema is whatever the caller uses, for example the types of a PC graph. Deleting a source removes its links,
 * and nodes left without any link go with them (see [Store.deleteSource]).
 */
class Graph(private val store: Store) {
    private val db: SQLiteDatabase get() = store.writableDatabase

    /**
     * Returns the node for this name, creating it the first time. The first spelling seen is kept as its name.
     * In [sourceId], a name the user merged into another gives that other node.
     */
    fun upsertNode(project: String, type: String, name: String, sourceId: String? = null): GraphNode {
        var cleanType = type.trim()
        var cleanName = name.trim().replace(WHITESPACE, " ")
        require(cleanType.isNotEmpty() && cleanName.isNotEmpty()) { "A node needs a type and a name." }
        if (sourceId != null) db.rawQuery("SELECT type, name FROM graph_aliases WHERE building = ? AND source = ? AND key = ?", arrayOf(project, sourceId, keyOf(cleanName)))
            .use { if (it.moveToFirst()) { cleanType = it.getString(0); cleanName = it.getString(1) } }
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
    fun addEdge(project: String, from: GraphNode, to: GraphNode, type: String, sourceId: String, page: Int, fact: String = "", vector: FloatArray? = null, extractor: String = "", place: String = ""): Boolean {
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
                put("fact", fact.trim()); put("extractor", extractor); put("place", place); vector?.let { put("vector", encode(it)) }
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
    /**
     * Merges the names [others] into [keep] (all as [keyOf] keys): in each file that mentions [keep], facts about any of
     * them now point at [keep]'s node, and the file remembers the merge so rebuilding its graph keeps it. Files that
     * don't mention [keep] are left alone, since a name like "Appliance" means something else there. Spellings of
     * [keep] under other types join it too. Returns how many facts moved.
     */
    fun merge(project: String, keep: String, others: Collection<String>): Int = db.inTransaction {
        fun ids(sql: String, vararg args: String) = rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        val target = rawQuery(
            """SELECT n.id, n.type, n.key, n.name FROM graph_nodes n WHERE n.building = ? AND n.key = ?
               ORDER BY (SELECT COUNT(*) FROM graph_edges e WHERE e.from_id = n.id OR e.to_id = n.id) DESC, n.id LIMIT 1""", arrayOf(project, keep),
        ).use { if (it.moveToFirst()) node(it, 0) else null } ?: return@inTransaction 0
        val keys = (others + keep).toSet()
        val marks = keys.joinToString(",") { "?" }
        val victims = ids("SELECT id FROM graph_nodes WHERE building = ? AND key IN ($marks) AND id <> ?", project, *keys.toTypedArray(), target.id.toString())
        val sources = rawQuery("SELECT DISTINCT e.source FROM graph_edges e JOIN graph_nodes n ON n.id IN (e.from_id, e.to_id) WHERE n.building = ? AND n.key = ?", arrayOf(project, keep))
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        var moved = 0
        if (victims.isNotEmpty()) for (s in sources) {
            val v = victims.joinToString(",")
            for (end in listOf("from_id", "to_id")) {
                execSQL("UPDATE OR IGNORE graph_edges SET $end = ? WHERE source = ? AND $end IN ($v)", arrayOf(target.id, s))
                moved += rawQuery("SELECT changes()", null).use { it.moveToFirst(); it.getInt(0) }
            }
            // Facts that now say the same thing twice, and a name linked to itself ("Egg Cooker is part of Deluxe Egg Cooker").
            execSQL("DELETE FROM graph_edges WHERE source = ? AND (from_id IN ($v) OR to_id IN ($v))", arrayOf(s))
            execSQL("DELETE FROM graph_edges WHERE source = ? AND from_id = ? AND to_id = ?", arrayOf(s, target.id, target.id))
            // Column values the file gave the merged names now belong to the name kept.
            execSQL("UPDATE graph_values SET node_id = ? WHERE source = ? AND node_id IN ($v)", arrayOf(target.id, s))
        }
        for (s in sources) for (k in keys) insertWithOnConflict("graph_aliases", null, ContentValues().apply {
            put("building", project); put("source", s); put("key", k); put("type", target.type); put("name", target.name)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        execSQL(DELETE_UNLINKED, arrayOf(project))
        moved
    }

    /** Every link in a project, or in one of its files when [sourceId] is given, for drawing the graph. */
    fun view(project: String, sourceId: String? = null): List<ViewEdge> = db.rawQuery(
        """SELECT f.id, f.type, f.key, f.name, t.id, t.type, t.key, t.name, e.type, e.source, e.page, e.fact, e.place
           FROM graph_edges e JOIN graph_nodes f ON f.id = e.from_id JOIN graph_nodes t ON t.id = e.to_id
           WHERE f.building = ?${if (sourceId != null) " AND e.source = ?" else ""} ORDER BY e.source, e.page, e.id LIMIT $MAX_ROWS""",
        listOfNotNull(project, sourceId).toTypedArray(),
    ).use { c -> buildList { while (c.moveToNext()) add(ViewEdge(node(c, 0), node(c, 4), c.getString(8), c.getString(9), c.getInt(10), c.getString(11), c.getString(12) ?: "")) } }

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
        data class Row(val sourceId: String, val page: Int, val fact: String, val words: List<String>, val similarity: Float, val place: String)
        val rows = db.rawQuery(
            """SELECT e.source, e.page, e.fact, f.name, t.name, e.vector, e.place FROM graph_edges e
               JOIN graph_nodes f ON f.id = e.from_id JOIN graph_nodes t ON t.id = e.to_id
               WHERE f.building = ? AND e.fact <> ''""", arrayOf(project),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val fact = c.getString(2)
                    val similarity = c.getBlob(5)?.let { blob -> queryVector?.let { Store.cosine(it, decode(blob)) } } ?: Float.NaN
                    add(Row(c.getString(0), c.getInt(1), fact, stems("$fact ${c.getString(3)} ${c.getString(4)}"), similarity, c.getString(6) ?: ""))
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
            .map { GraphFact(rows[it].sourceId, rows[it].page, rows[it].fact, rows[it].similarity, rows[it].place) }
    }

    /** Every fact in [project] with one of [relations], in the order of the document: the graph's own answer to a list question. */
    fun factsWithRelations(project: String, relations: Set<String>, limit: Int): List<GraphFact> {
        if (relations.isEmpty()) return emptyList()
        val marks = relations.joinToString(",") { "?" }
        return db.rawQuery(
            """SELECT e.source, e.page, e.fact, e.place FROM graph_edges e JOIN graph_nodes f ON f.id = e.from_id
               WHERE f.building = ? AND e.fact <> '' AND e.type IN ($marks) ORDER BY e.source, e.page, e.id LIMIT $limit""",
            (listOf(project) + relations).toTypedArray(),
        ).use { c -> buildList { while (c.moveToNext()) add(GraphFact(c.getString(0), c.getInt(1), c.getString(2), Float.NaN, c.getString(3) ?: "")) } }.distinctBy { it.fact }
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
     *
     * The steward's earlier decisions on this file are replayed: a fact proposed again that was rejected stays out,
     * and every approved or corrected fact comes back as the steward left it, so a rebuild never undoes a review.
     */
    fun replaceFacts(project: String, sourceId: String, facts: List<NewFact>, embed: (String) -> FloatArray): Int {
        require(facts.size <= MAX_FACTS) { "At most $MAX_FACTS facts at a time." }
        val decided = decisions(sourceId)
        val decidedKeys = decided.map { it.key }.toSet()
        val all = facts.filter { decisionKey(it) !in decidedKeys } +
            decided.filter { it.status != "rechazado" && it.fact.complete() }.map { it.fact.copy(extractor = "revisado") }
        val vectors = all.map { embed(it.statement) } // Before the transaction: embedding is the slow part.
        return db.inTransaction {
            execSQL("DELETE FROM graph_edges WHERE source = ? AND fact <> ''", arrayOf(sourceId))
            execSQL(DELETE_UNLINKED, arrayOf(project))
            // A new extraction supersedes what the old one left to review; decided items stay as the record.
            execSQL("DELETE FROM graph_review WHERE source = ? AND status = 'pendiente'", arrayOf(sourceId))
            all.zip(vectors).count { (f, vector) ->
                val from = upsertNode(project, f.subjectType, f.subject, sourceId)
                val to = upsertNode(project, f.objectType, f.obj, sourceId)
                addEdge(project, from, to, f.relation, sourceId, f.page, f.statement, vector, f.extractor, f.place)
            }
        }
    }

    // ---- Review: facts the checks held back, waiting for the steward ----

    /**
     * A fact waiting for a decision ([status] "pendiente"), a kept fact in the graph ([edgeId] set, [status] "grafo"),
     * or a kept fact drawn for a spot check ([edgeId] set, [reason] "muestra").
     */
    data class ReviewItem(val id: Long, val sourceId: String, val fact: NewFact, val reason: String, val status: String, val edgeId: Long? = null)

    /** A decision already taken on a file: the fact as the steward left it, and the key of the fact as it was proposed. */
    private data class Decision(val status: String, val fact: NewFact, val key: String)

    private fun decisions(sourceId: String): List<Decision> = db.rawQuery(
        "SELECT status, page, from_type, from_name, relation, to_type, to_name, statement, original_key FROM graph_review WHERE source = ? AND status <> 'pendiente'",
        arrayOf(sourceId),
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                val fact = NewFact(c.getInt(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7))
                add(Decision(c.getString(0), fact, c.getString(8) ?: decisionKey(fact)))
            }
        }
    }

    /** Queues facts the checks held back, each with its reason. Facts the steward already decided on are not asked again. */
    fun saveReview(project: String, sourceId: String, items: List<Pair<NewFact, String>>, extractor: String) {
        if (items.isEmpty()) return
        val decidedKeys = decisions(sourceId).map { it.key }.toSet()
        db.inTransaction {
            for ((f, reason) in items) {
                if (decisionKey(f) in decidedKeys) continue
                insertOrThrow("graph_review", null, ContentValues().apply {
                    put("building", project); put("source", sourceId); put("page", f.page)
                    put("from_type", f.subjectType); put("from_name", f.subject); put("relation", f.relation)
                    put("to_type", f.objectType); put("to_name", f.obj); put("statement", f.statement)
                    put("reason", reason); put("extractor", extractor); put("original_key", decisionKey(f)); put("place", f.place)
                })
            }
        }
    }

    fun reviewCount(project: String): Int = db.rawQuery("SELECT COUNT(*) FROM graph_review WHERE building = ? AND status = 'pendiente'", arrayOf(project))
        .use { it.moveToFirst(); it.getInt(0) }

    fun pendingReviews(project: String): List<ReviewItem> = db.rawQuery(
        "SELECT id, source, page, from_type, from_name, relation, to_type, to_name, statement, reason, extractor, place FROM graph_review WHERE building = ? AND status = 'pendiente' ORDER BY source, reason, page, id",
        arrayOf(project),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(ReviewItem(c.getLong(0), c.getString(1),
                NewFact(c.getInt(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(10), c.getString(11) ?: ""), c.getString(9), "pendiente"))
        }
    }

    private fun reviewItem(id: Long): ReviewItem? = db.rawQuery(
        "SELECT id, source, page, from_type, from_name, relation, to_type, to_name, statement, reason, extractor, status, place FROM graph_review WHERE id = ?", arrayOf(id.toString()),
    ).use { c -> if (c.moveToFirst()) ReviewItem(c.getLong(0), c.getString(1), NewFact(c.getInt(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(10), c.getString(12) ?: ""), c.getString(9), c.getString(11)) else null }

    /**
     * Puts a held-back fact into the graph, as the model wrote it or as corrected, and records the decision.
     * The fact's extractor becomes "revisado": a steward stood behind it.
     */
    fun approveReview(project: String, id: Long, corrected: NewFact?, embed: (String) -> FloatArray): Boolean {
        val item = reviewItem(id)?.takeIf { it.status == "pendiente" } ?: return false
        val edited = corrected?.copy(place = item.fact.place)?.takeIf { it != item.fact }
        val fact = (edited ?: item.fact).copy(extractor = "revisado", place = if (edited == null || edited.page == item.fact.page) item.fact.place else "")
        require(fact.complete()) { "An approved fact needs a page, both names and a statement." }
        val vector = embed(fact.statement)
        db.inTransaction {
            val from = upsertNode(project, fact.subjectType, fact.subject, item.sourceId)
            val to = upsertNode(project, fact.objectType, fact.obj, item.sourceId)
            addEdge(project, from, to, fact.relation, item.sourceId, fact.page, fact.statement, vector, fact.extractor, fact.place)
            update("graph_review", ContentValues().apply {
                put("status", if (edited == null) "aprobado" else "corregido"); put("decided_at", System.currentTimeMillis())
                if (edited != null) { put("page", fact.page); put("from_type", fact.subjectType); put("from_name", fact.subject); put("relation", fact.relation); put("to_type", fact.objectType); put("to_name", fact.obj); put("statement", fact.statement) }
            }, "id = ?", arrayOf(id.toString()))
        }
        return true
    }

    fun rejectReview(id: Long, note: String = ""): Boolean = db.update("graph_review", ContentValues().apply {
        put("status", "rechazado"); put("decided_at", System.currentTimeMillis()); put("note", note)
    }, "id = ? AND status = 'pendiente'", arrayOf(id.toString())) == 1

    /** [n] kept AI facts of [project] drawn at random, to measure what the checks let through. */
    fun sampleKept(project: String, n: Int): List<ReviewItem> = db.rawQuery(
        """SELECT e.id, e.source, e.page, f.type, f.name, e.type, t.type, t.name, e.fact, e.extractor FROM graph_edges e
           JOIN graph_nodes f ON f.id = e.from_id JOIN graph_nodes t ON t.id = e.to_id
           WHERE f.building = ? AND e.fact <> '' AND e.extractor LIKE 'deepseek%' ORDER BY RANDOM() LIMIT ?""",
        arrayOf(project, n.toString()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(ReviewItem(-c.getLong(0), c.getString(1),
                NewFact(c.getInt(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(9)), "muestra", "muestra", edgeId = c.getLong(0)))
        }
    }

    /**
     * Removes a kept fact the steward found wrong, and records it so the error rate can be read back and a rebuild
     * leaves the fact out. [reason] says where the steward met it: "muestra" (spot check) or "página" (page review).
     */
    fun rejectKept(project: String, edgeId: Long, note: String = "", reason: String = "muestra"): Boolean {
        val item = sampleItem(edgeId) ?: return false
        db.inTransaction {
            delete("graph_edges", "id = ?", arrayOf(edgeId.toString()))
            execSQL(DELETE_UNLINKED, arrayOf(project))
            recordDecision(project, item, reason, "rechazado", item.fact, note)
        }
        return true
    }

    /**
     * The steward looked at a kept fact and stands behind it: its extractor becomes "revisado" and the decision is kept.
     * [reason] says how: "página" (one by one, or the rest of a page just read), "documento" (the rest of a document, from
     * the button at the end of the list), so a read page can later be told from a bulk approval.
     */
    fun approveKept(project: String, edgeId: Long, reason: String = "página"): Boolean {
        val item = sampleItem(edgeId) ?: return false
        db.inTransaction {
            update("graph_edges", ContentValues().apply { put("extractor", "revisado") }, "id = ?", arrayOf(edgeId.toString()))
            recordDecision(project, item, reason, "aprobado", item.fact)
        }
        return true
    }

    /** Replaces a kept fact with the steward's [corrected] version and records the correction against the original. */
    fun correctKept(project: String, edgeId: Long, corrected: NewFact, embed: (String) -> FloatArray): Boolean {
        val item = sampleItem(edgeId) ?: return false
        val fact = corrected.copy(extractor = "revisado", place = if (corrected.page == item.fact.page) item.fact.place else "")
        require(fact.complete()) { "A corrected fact needs a page, both names and a statement." }
        val vector = embed(fact.statement)
        db.inTransaction {
            delete("graph_edges", "id = ?", arrayOf(edgeId.toString()))
            val from = upsertNode(project, fact.subjectType, fact.subject, item.sourceId)
            val to = upsertNode(project, fact.objectType, fact.obj, item.sourceId)
            addEdge(project, from, to, fact.relation, item.sourceId, fact.page, fact.statement, vector, fact.extractor, fact.place)
            execSQL(DELETE_UNLINKED, arrayOf(project))
            recordDecision(project, item, "página", "corregido", fact)
        }
        return true
    }

    /** Records a decision on a kept fact: [stored] is the fact as the steward left it, keyed by the fact as it was. */
    private fun SQLiteDatabase.recordDecision(project: String, item: ReviewItem, reason: String, status: String, stored: NewFact, note: String = "") {
        insertOrThrow("graph_review", null, ContentValues().apply {
            put("building", project); put("source", item.sourceId); put("page", stored.page)
            put("from_type", stored.subjectType); put("from_name", stored.subject); put("relation", stored.relation)
            put("to_type", stored.objectType); put("to_name", stored.obj); put("statement", stored.statement)
            put("reason", reason); put("extractor", item.fact.extractor); put("status", status); put("decided_at", System.currentTimeMillis()); put("note", note)
            put("original_key", decisionKey(item.fact))
        })
    }

    private fun sampleItem(edgeId: Long): ReviewItem? = db.rawQuery(
        """SELECT e.source, e.page, f.type, f.name, e.type, t.type, t.name, e.fact, e.extractor, e.place FROM graph_edges e
           JOIN graph_nodes f ON f.id = e.from_id JOIN graph_nodes t ON t.id = e.to_id WHERE e.id = ?""", arrayOf(edgeId.toString()),
    ).use { c -> if (c.moveToFirst()) ReviewItem(-edgeId, c.getString(0), NewFact(c.getInt(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(9) ?: ""), "muestra", "muestra", edgeId) else null }

    // ---- Review by document and page: everything the graph says about a page, right or wrong ----

    /** What a page (or a whole document) holds for review: [pending] held-back facts, [facts] in the graph, of which [unchecked] nobody has stood behind yet. */
    data class PageStatus(val pending: Int = 0, val facts: Int = 0, val unchecked: Int = 0) {
        operator fun plus(o: PageStatus) = PageStatus(pending + o.pending, facts + o.facts, unchecked + o.unchecked)
    }

    /** Each page of [sourceId] that has anything to review. Rule facts and reviewed facts count as checked. */
    fun pageStatus(sourceId: String): Map<Int, PageStatus> {
        val out = sortedMapOf<Int, PageStatus>()
        db.rawQuery("SELECT page, COUNT(*), SUM(extractor NOT IN ('revisado', 'reglas')) FROM graph_edges WHERE source = ? AND fact <> '' GROUP BY page", arrayOf(sourceId))
            .use { c -> while (c.moveToNext()) out[c.getInt(0)] = PageStatus(facts = c.getInt(1), unchecked = c.getInt(2)) }
        db.rawQuery("SELECT page, COUNT(*) FROM graph_review WHERE source = ? AND status = 'pendiente' GROUP BY page", arrayOf(sourceId))
            .use { c -> while (c.moveToNext()) out[c.getInt(0)] = (out[c.getInt(0)] ?: PageStatus()) + PageStatus(pending = c.getInt(1)) }
        return out
    }

    /** The same, summed per document of [project]. */
    fun documentStatus(project: String): Map<String, PageStatus> {
        val out = mutableMapOf<String, PageStatus>()
        db.rawQuery("SELECT e.source, COUNT(*), SUM(e.extractor NOT IN ('revisado', 'reglas')) FROM graph_edges e JOIN sources s ON s.id = e.source WHERE s.building = ? AND e.fact <> '' GROUP BY e.source", arrayOf(project))
            .use { c -> while (c.moveToNext()) out[c.getString(0)] = PageStatus(facts = c.getInt(1), unchecked = c.getInt(2)) }
        db.rawQuery("SELECT source, COUNT(*) FROM graph_review WHERE building = ? AND status = 'pendiente' GROUP BY source", arrayOf(project))
            .use { c -> while (c.moveToNext()) out[c.getString(0)] = (out[c.getString(0)] ?: PageStatus()) + PageStatus(pending = c.getInt(1)) }
        return out
    }

    /**
     * Everything to review on [page] of [sourceId] (every page when null): held-back facts first, then the facts in
     * the graph, page by page. Kept items carry their [ReviewItem.edgeId] and status "grafo". [pendingOnly] leaves the
     * kept facts out: the red link.
     */
    fun pageItems(project: String, sourceId: String, page: Int?, pendingOnly: Boolean = false): List<ReviewItem> {
        val pageFilter = if (page != null) " AND page = ?" else ""
        val args = listOfNotNull(sourceId, page?.toString()).toTypedArray()
        val pending = db.rawQuery(
            "SELECT id, page, from_type, from_name, relation, to_type, to_name, statement, reason, extractor, place FROM graph_review WHERE source = ? AND status = 'pendiente'$pageFilter ORDER BY page, id", args,
        ).use { c -> buildList { while (c.moveToNext()) add(ReviewItem(c.getLong(0), sourceId, NewFact(c.getInt(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(9), c.getString(10) ?: ""), c.getString(8), "pendiente")) } }
        if (pendingOnly) return pending
        val kept = db.rawQuery(
            """SELECT e.id, e.page, f.type, f.name, e.type, t.type, t.name, e.fact, e.extractor, e.place FROM graph_edges e
               JOIN graph_nodes f ON f.id = e.from_id JOIN graph_nodes t ON t.id = e.to_id
               WHERE e.source = ? AND e.fact <> ''${pageFilter.replace("page", "e.page")} ORDER BY e.page, e.extractor = 'reglas', e.id""", args,
        ).use { c -> buildList { while (c.moveToNext()) add(ReviewItem(-c.getLong(0), sourceId, NewFact(c.getInt(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(9) ?: ""), "", "grafo", c.getLong(0))) } }
        return (pending + kept).sortedBy { it.fact.page } // stable: within a page, pending stays first
    }

    /** Names of a type already in the project graph: entities earlier extractions or approvals established. */
    fun nodeNames(project: String, type: String): Set<String> = db.rawQuery("SELECT name FROM graph_nodes WHERE building = ? AND type = ?", arrayOf(project, type))
        .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

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
        /** Nodes left with no links and no column values are removed: a ruling known only by its columns stays. */
        const val DELETE_UNLINKED = "DELETE FROM graph_nodes WHERE building = ? AND NOT EXISTS (SELECT 1 FROM graph_edges e WHERE e.from_id = graph_nodes.id OR e.to_id = graph_nodes.id) AND NOT EXISTS (SELECT 1 FROM graph_values v WHERE v.node_id = graph_nodes.id)"

        fun keyOf(name: String): String =
            Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").replace(NOT_WORD, " ").trim()

        /** What makes a proposed fact "the same fact" across extractions: its page, both names and the relation; not its wording. */
        fun decisionKey(f: NewFact): String = "${f.page}|${keyOf(f.subject)}|${f.relation.trim().uppercase()}|${keyOf(f.obj)}"
    }
}
