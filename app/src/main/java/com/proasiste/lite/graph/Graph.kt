package com.proasiste.lite.graph

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.proasiste.lite.Store
import com.proasiste.lite.inTransaction
import java.text.Normalizer

/** [key] identifies a node within its project and type: the same name, ignoring case, accents and spacing. */
data class GraphNode(val id: Long, val type: String, val key: String, val name: String)

/** A link and where its claim comes from: a link can't exist without a source and page. */
data class GraphEdge(val from: GraphNode, val to: GraphNode, val type: String, val sourceId: String, val page: Int)

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
    fun addEdge(project: String, from: GraphNode, to: GraphNode, type: String, sourceId: String, page: Int): Boolean {
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
        """SELECT f.id, f.type, f.key, f.name, t.id, t.type, t.key, t.name, e.type, e.source, e.page
           FROM graph_edges e JOIN graph_nodes f ON f.id = e.from_id JOIN graph_nodes t ON t.id = e.to_id
           WHERE e.from_id = ? OR e.to_id = ? ORDER BY e.type, e.source, e.page""",
        arrayOf(node.id.toString(), node.id.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(GraphEdge(node(c, 0), node(c, 4), c.getString(8), c.getString(9), c.getInt(10))) } }

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

    private fun node(c: Cursor, at: Int) = GraphNode(c.getLong(at), c.getString(at + 1), c.getString(at + 2), c.getString(at + 3))

    companion object {
        const val MAX_HOPS = 4
        private const val MAX_ROWS = 50_000
        private val WHITESPACE = Regex("\\s+")
        private val MARKS = Regex("\\p{M}+")
        private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

        /** "Excma. Corte  Suprema" and "excma corte suprema" share a key. Punctuation separates words. */
        fun keyOf(name: String): String =
            Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").replace(NOT_WORD, " ").trim()
    }
}
