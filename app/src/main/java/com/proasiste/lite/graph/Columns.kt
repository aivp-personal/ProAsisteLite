package com.proasiste.lite.graph

import android.content.ContentValues
import com.proasiste.lite.inTransaction
import com.proasiste.lite.Store

/** What a column holds. A list column holds several values per node, one row each. */
enum class ColumnKind { TEXT, NUMBER, DATE, BOOL, LIST }

/** A column a node type declares, such as a ruling's date or a contract's amount. */
data class ColumnDef(val name: String, val kind: ColumnKind, val description: String = "")

/** A value about one node, as read from one page of its file. Dates are ISO (2026-09-02). */
data class NewValue(val column: String, val value: String, val page: Int)

/** A stored value: the value, its number for sorting and comparing, and where it came from. */
data class Cell(val value: String, val num: Double?, val sourceId: String, val page: Int)

/** One node of a type with its column values. */
data class TableRow(val node: GraphNode, val cells: Map<String, List<Cell>>) {
    fun first(column: String): Cell? = cells[column]?.firstOrNull()
    fun values(column: String): List<String> = cells[column].orEmpty().map { it.value }
    /** The file most of the row's values come from: the row opens there. */
    val sourceId: String get() = cells.values.flatten().groupingBy { it.sourceId }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
}

/**
 * Column values on graph nodes (schema 12, `graph_values`). Each value belongs to a node, a file and a writer
 * ([origin]); a writer replaces only its own values for a file, so a later import or a person's correction can sit beside
 * what the rules read. A node with values stays even with no links.
 */
class GraphColumns(private val store: Store, private val graph: Graph) {
    private val db get() = store.writableDatabase

    /**
     * Replaces what [origin] wrote for [sourceId] with [values] on the node [type]/[name], typed by [defs]. A value whose
     * column isn't in [defs], or that doesn't read as its kind, is skipped. Returns how many values were written.
     */
    fun replace(project: String, sourceId: String, origin: String, type: String, name: String, defs: List<ColumnDef>, values: List<NewValue>): Int {
        val kinds = defs.associate { it.name to it.kind }
        var written = 0
        db.inTransaction {
            delete("graph_values", "source = ? AND origin = ?", arrayOf(sourceId, origin))
            if (values.isNotEmpty()) {
                val node = graph.upsertNode(project, type, name, sourceId)
                for (v in values.distinctBy { it.column to it.value }) {
                    val kind = kinds[v.column] ?: continue
                    val clean = v.value.replace(Regex("\\s+"), " ").trim()
                    if (clean.isEmpty()) continue
                    val num = numberOf(kind, clean)
                    if ((kind == ColumnKind.NUMBER || kind == ColumnKind.DATE) && num == null) continue
                    insert("graph_values", null, ContentValues().apply {
                        put("node_id", node.id); put("col", v.column); put("value", clean); if (num != null) put("num", num)
                        put("source", sourceId); put("page", v.page.coerceAtLeast(0)); put("origin", origin)
                    })
                    written++
                }
            }
            execSQL(Graph.DELETE_UNLINKED, arrayOf(project))
        }
        return written
    }

    /**
     * Re-syncs an imported table: everything [origin] wrote from [sourceId] is replaced by [rows] in one transaction, so a
     * changed row takes its new values, a new row arrives and a removed row goes (its node too, unless a document still
     * links it). Links become graph facts from the table, citing their row; a name not in the graph yet becomes a node
     * that the other table, or a document, fills in later. Returns how many rows were written.
     */
    fun replaceTable(project: String, sourceId: String, origin: String, type: String, defs: List<ColumnDef>, rows: List<TableRowIn>): Int {
        val kinds = defs.associate { it.name to it.kind }
        return db.inTransaction {
            delete("graph_values", "source = ? AND origin = ?", arrayOf(sourceId, origin))
            delete("graph_edges", "source = ? AND extractor = ?", arrayOf(sourceId, origin))
            for (r in rows) {
                val node = graph.upsertNode(project, type, r.name, sourceId)
                for (v in r.values.distinctBy { it.column to it.value }) {
                    val kind = kinds[v.column] ?: continue
                    insert("graph_values", null, ContentValues().apply {
                        put("node_id", node.id); put("col", v.column); put("value", v.value); numberOf(kind, v.value)?.let { put("num", it) }
                        put("source", sourceId); put("page", v.page); put("origin", origin)
                    })
                }
                for ((column, linkType, name) in r.links) {
                    if (Graph.keyOf(name).isEmpty()) continue
                    val target = graph.upsertNode(project, linkType, name, sourceId)
                    graph.addEdge(project, node, target, GraphSchema.cleanLink(column), sourceId, r.row, "${r.name} · $column: ${target.name}", extractor = origin)
                }
            }
            execSQL(Graph.DELETE_UNLINKED, arrayOf(project))
            rows.size
        }
    }

    /**
     * Applies column values a pack brought (`prebuilt_values`) to every file of [project] with the same contents, as
     * their writer would have (the rules' values replace the rules' values), then drops them. Returns how many files got values.
     */
    fun applyPrebuilt(project: String): Int {
        val pending = db.rawQuery("SELECT COUNT(*) FROM prebuilt_values", null).use { it.moveToFirst(); it.getInt(0) }
        if (pending == 0) return 0
        val rows = db.rawQuery(
            """SELECT s.id, v.sha256, v.type, v.name, v.col, v.value, v.page, v.origin FROM prebuilt_values v JOIN sources s ON s.sha256 = v.sha256
               WHERE s.building = ? ORDER BY s.id, v.rowid""", arrayOf(project),
        ).use { c -> buildList { while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getInt(6).toString(), c.getString(7))) } }
        val bySource = rows.groupBy { it[0] }
        for ((sourceId, vs) in bySource) {
            val (type, name, origin) = Triple(vs[0][2], vs[0][3], vs[0][7])
            val defs = if (type == com.proasiste.lite.runtime.RulingColumns.TYPE) com.proasiste.lite.runtime.RulingColumns.DEFS else vs.map { it[4] }.distinct().map { ColumnDef(it, ColumnKind.TEXT) }
            replace(project, sourceId, origin, type, name, defs, vs.map { NewValue(it[4], it[5], it[6].toInt()) })
        }
        val applied = rows.map { it[1] }.distinct()
        db.inTransaction { applied.forEach { delete("prebuilt_values", "sha256 = ?", arrayOf(it)) } }
        return bySource.size
    }

    /** Every node of [type] in [project] that has values, with its values in file and page order. */
    fun rows(project: String, type: String, links: Boolean = true): List<TableRow> {
        val byNode = LinkedHashMap<Long, Pair<GraphNode, LinkedHashMap<String, MutableList<Cell>>>>()
        db.rawQuery(
            """SELECT n.id, n.type, n.key, n.name, v.col, v.value, v.num, v.source, v.page FROM graph_values v JOIN graph_nodes n ON n.id = v.node_id
               WHERE n.building = ? AND n.type = ? ORDER BY n.key, v.id""", arrayOf(project, type),
        ).use { c ->
            while (c.moveToNext()) {
                val (_, cells) = byNode.getOrPut(c.getLong(0)) { GraphNode(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)) to LinkedHashMap() }
                cells.getOrPut(c.getString(4)) { mutableListOf() } += Cell(c.getString(5), if (c.isNull(6)) null else c.getDouble(6), c.getString(7), c.getInt(8))
            }
        }
        if (links) for ((id, byColumn) in linkCells(project, type)) byNode[id]?.second?.let { cells -> byColumn.forEach { (c, v) -> cells.getOrPut(c) { mutableListOf() } += v } }
        return byNode.values.map { (node, cells) -> TableRow(node, cells) }
    }

    /**
     * Columns that come from the graph's links instead of values: for each relation a node of [type] takes part in, the
     * names it leads to ("CITA_PRECEDENTE →") or comes from ("← INTEGRA"), each citing its fact's file and page. A
     * relation with more than [MAX_LINKS] names a node on average (a ruling's considerandos) is left out: too long for a
     * column. By node id, then column.
     */
    fun linkCells(project: String, type: String): Map<Long, Map<String, List<Cell>>> {
        val out = HashMap<Long, HashMap<String, MutableList<Cell>>>()
        db.rawQuery(
            """SELECT e.from_id, e.to_id, e.type, f.type, t.type, f.name, t.name, e.source, e.page FROM graph_edges e
               JOIN graph_nodes f ON f.id = e.from_id JOIN graph_nodes t ON t.id = e.to_id
               WHERE f.building = ? AND (f.type = ? OR t.type = ?) AND e.from_id <> e.to_id""", arrayOf(project, type, type),
        ).use { c ->
            while (c.moveToNext()) {
                if (c.getString(3) == type) out.getOrPut(c.getLong(0)) { HashMap() }.getOrPut("${c.getString(2)} →") { mutableListOf() } += Cell(c.getString(6), null, c.getString(7), c.getInt(8))
                if (c.getString(4) == type) out.getOrPut(c.getLong(1)) { HashMap() }.getOrPut("← ${c.getString(2)}") { mutableListOf() } += Cell(c.getString(5), null, c.getString(7), c.getInt(8))
            }
        }
        val tooLong = out.values.flatMap { it.entries }.groupBy({ it.key }, { it.value.map { v -> v.value }.distinct().size }).filterValues { it.average() > MAX_LINKS }.keys
        return out.mapValues { (_, byColumn) -> byColumn.filterKeys { it !in tooLong }.mapValues { (_, v) -> v.distinctBy { it.value } } }
    }

    /** The link columns [type] has, as list columns, commonest first. */
    fun linkColumns(project: String, type: String): List<ColumnDef> =
        linkCells(project, type).values.flatMap { it.keys }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { ColumnDef(it.key, ColumnKind.LIST, "") }

    /** Every node name of each type, as keys: what an imported table's columns are matched against. */
    fun typeKeys(project: String): Map<String, Set<String>> = db.rawQuery("SELECT type, key FROM graph_nodes WHERE building = ?", arrayOf(project)).use { c ->
        val out = HashMap<String, MutableSet<String>>(); while (c.moveToNext()) out.getOrPut(c.getString(0)) { HashSet() } += c.getString(1); out
    }

    /** Every node name of each type, as spelled in the graph. */
    fun typeNames(project: String): Map<String, List<String>> = db.rawQuery("SELECT type, name FROM graph_nodes WHERE building = ?", arrayOf(project)).use { c ->
        val out = HashMap<String, MutableList<String>>(); while (c.moveToNext()) out.getOrPut(c.getString(0)) { mutableListOf() } += c.getString(1); out
    }

    /** How many nodes of each type have values, commonest first. */
    fun types(project: String): List<Pair<String, Int>> = db.rawQuery(
        "SELECT n.type, COUNT(DISTINCT n.id) FROM graph_values v JOIN graph_nodes n ON n.id = v.node_id WHERE n.building = ? GROUP BY n.type ORDER BY 2 DESC",
        arrayOf(project),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }

    /** The files [origin] has already written values for. */
    fun sourcesDone(project: String, origin: String): Set<String> = db.rawQuery(
        "SELECT DISTINCT v.source FROM graph_values v JOIN sources s ON s.id = v.source WHERE s.building = ? AND v.origin = ?", arrayOf(project, origin),
    ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    companion object {
        /** A relation naming more than this many names per node on average isn't shown as a column. */
        const val MAX_LINKS = 8

        /** A value as a number: the number itself, a date as yyyymmdd, yes/no as 1/0. Null for text. */
        fun numberOf(kind: ColumnKind, value: String): Double? = when (kind) {
            ColumnKind.NUMBER -> value.replace(" ", "").let { v ->
                // "1.234,56" and "1,234.56" both mean 1234.56; a lone comma is a decimal comma.
                when {
                    v.contains(',') && v.contains('.') -> if (v.lastIndexOf(',') > v.lastIndexOf('.')) v.replace(".", "").replace(',', '.') else v.replace(",", "")
                    v.contains(',') -> v.replace(',', '.')
                    else -> v
                }
            }.toDoubleOrNull()
            ColumnKind.DATE -> Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(value)?.destructured?.let { (y, m, d) -> (y + m + d).toDouble() }
            ColumnKind.BOOL -> when (value.lowercase()) { "sí", "si", "yes", "true", "1" -> 1.0; "no", "false", "0" -> 0.0; else -> null }
            else -> null
        }
    }
}
