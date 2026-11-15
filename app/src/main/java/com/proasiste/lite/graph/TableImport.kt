package com.proasiste.lite.graph

import com.proasiste.lite.runtime.TableFile
import org.json.JSONArray
import org.json.JSONObject

/** How one column of a sheet goes into the graph: under [name], read as [kind], or as a link to [linkTo]'s nodes. */
data class MappedColumn(val index: Int, val header: String, val name: String, val kind: ColumnKind, val linkTo: String? = null, val include: Boolean = true)

/**
 * How a sheet becomes graph nodes: each row is a node of [type] named by column [nameColumn]; the other included
 * columns are its values, or links when they name another type's nodes. Saved with the table, so importing the same file
 * again re-syncs it the same way.
 */
data class TableMap(val type: String, val sheet: String, val nameColumn: Int, val columns: List<MappedColumn>) {
    val defs: List<ColumnDef> get() = columns.filter { it.include && it.index != nameColumn && it.linkTo == null }.map { ColumnDef(it.name, it.kind) }

    fun toJson(): String = JSONObject().put("type", type).put("sheet", sheet).put("name", nameColumn).put("columns", JSONArray(columns.map {
        JSONObject().put("index", it.index).put("header", it.header).put("name", it.name).put("kind", it.kind.name).put("include", it.include).also { o -> it.linkTo?.let { t -> o.put("link", t) } }
    })).toString()

    companion object {
        fun fromJson(json: String): TableMap {
            val o = JSONObject(json); val a = o.getJSONArray("columns")
            return TableMap(o.getString("type"), o.optString("sheet"), o.getInt("name"), (0 until a.length()).map { i ->
                a.getJSONObject(i).let { c -> MappedColumn(c.getInt("index"), c.optString("header"), c.getString("name"), ColumnKind.valueOf(c.getString("kind")), c.optString("link").ifEmpty { null }, c.optBoolean("include", true)) }
            })
        }
    }
}

/** A column measured before mapping it: what its cells read as, how full it is and whether its values are unique. */
data class ColumnStats(val index: Int, val header: String, val kind: ColumnKind, val filled: Double, val unique: Boolean, val samples: List<String>)

object TableImport {
    /** What wrote values and links from an imported table. */
    const val ORIGIN = "tabla"

    /** Measures each column: a column is a date, number or yes/no only when every filled cell reads as one. */
    fun measure(sheet: TableFile.Sheet): List<ColumnStats> = sheet.headers.indices.map { i ->
        val cells = sheet.body.map { it.getOrElse(i) { "" }.trim() }
        val filled = cells.filter { it.isNotEmpty() }
        val kind = when {
            filled.isEmpty() -> ColumnKind.TEXT
            filled.all { date(it) != null } -> ColumnKind.DATE
            filled.all { GraphColumns.numberOf(ColumnKind.NUMBER, it) != null } -> ColumnKind.NUMBER
            filled.all { GraphColumns.numberOf(ColumnKind.BOOL, it) != null } && filled.distinct().size <= 2 -> ColumnKind.BOOL
            else -> ColumnKind.TEXT
        }
        ColumnStats(i, sheet.headers[i].ifBlank { "Columna ${i + 1}" }, kind, if (cells.isEmpty()) 0.0 else filled.size.toDouble() / cells.size,
            filled.size == cells.size && filled.map(Graph::keyOf).distinct().size == filled.size, filled.distinct().take(3))
    }

    /**
     * A first mapping, for the person to confirm: the type is the existing type whose names the sheet's column matches
     * best (so a list of rulings lands on the rulings the documents made), else the sheet's name; the name column is
     * that matching column, else the first column that is full and unique; any other column whose values are mostly
     * names of an existing type becomes a link to it.
     */
    fun propose(sheet: TableFile.Sheet, fileName: String, known: Map<String, Set<String>>): TableMap {
        val stats = measure(sheet)
        fun overlap(i: Int, keys: Set<String>): Double {
            val v = sheet.body.map { Graph.keyOf(it.getOrElse(i) { "" }) }.filter { it.isNotEmpty() }
            return if (v.isEmpty()) 0.0 else v.count { it in keys }.toDouble() / v.size
        }
        val best = stats.filter { it.kind == ColumnKind.TEXT }.flatMap { s -> known.map { (t, keys) -> Triple(s.index, t, overlap(s.index, keys)) } }.maxByOrNull { it.third }
        val joined = best?.takeIf { it.third >= 0.3 }
        val nameColumn = joined?.first ?: stats.firstOrNull { it.unique && it.kind == ColumnKind.TEXT }?.index ?: stats.firstOrNull { it.unique }?.index ?: 0
        val defaultName = sheet.name.takeUnless { Regex("^(sheet|hoja)\\s*\\d*$", RegexOption.IGNORE_CASE).matches(it.trim()) } ?: fileName.substringBeforeLast('.')
        val type = joined?.second ?: GraphSchema.cleanType(defaultName.replaceFirstChar { it.titlecase() })
        val columns = stats.map { s ->
            val link = if (s.index == nameColumn || s.kind != ColumnKind.TEXT) null
                else known.filterKeys { it != type }.mapValues { overlap(s.index, it.value) }.filterValues { it >= 0.5 }.maxByOrNull { it.value }?.key
            MappedColumn(s.index, s.header, GraphSchema.cleanType(s.header), s.kind, link)
        }
        return TableMap(type, sheet.name, nameColumn, columns)
    }

    /** What would stop the import: a row with no name, or a cell that doesn't read as its column's kind. Empty when it can go in. */
    fun problems(sheet: TableFile.Sheet, map: TableMap, english: Boolean): List<String> {
        val out = mutableListOf<String>()
        val unnamed = sheet.body.withIndex().filter { (_, r) -> Graph.keyOf(r.getOrElse(map.nameColumn) { "" }).isEmpty() }.map { it.index + 2 }
        if (unnamed.isNotEmpty()) out += if (english) "Rows with no name in “${sheet.headers.getOrElse(map.nameColumn) { "" }}”: ${rows(unnamed)}" else "Filas sin nombre en «${sheet.headers.getOrElse(map.nameColumn) { "" }}»: ${rows(unnamed)}"
        for (c in map.columns.filter { it.include && it.index != map.nameColumn && it.linkTo == null && (it.kind == ColumnKind.DATE || it.kind == ColumnKind.NUMBER || it.kind == ColumnKind.BOOL) }) {
            val bad = sheet.body.withIndex().filter { (_, r) -> val v = r.getOrElse(c.index) { "" }.trim(); v.isNotEmpty() && cell(c.kind, v) == null }.map { it.index + 2 }
            if (bad.isNotEmpty()) out += if (english) "“${c.name}” isn't a ${c.kind.name.lowercase()} in rows ${rows(bad)}: read it as text, or fix those cells" else "«${c.name}» no es ${kindEs(c.kind)} en las filas ${rows(bad)}: léela como texto, o corrige esas celdas"
        }
        return out
    }

    private fun rows(n: List<Int>) = n.take(6).joinToString(", ") + if (n.size > 6) " … (${n.size})" else ""
    private fun kindEs(k: ColumnKind) = when (k) { ColumnKind.DATE -> "una fecha"; ColumnKind.NUMBER -> "un número"; ColumnKind.BOOL -> "sí/no"; else -> "texto" }

    /** A cell as stored: dates as ISO, numbers and yes/no as written; null when it doesn't read as [kind]. */
    fun cell(kind: ColumnKind, v: String): String? = when (kind) {
        ColumnKind.DATE -> date(v)
        ColumnKind.NUMBER -> v.takeIf { GraphColumns.numberOf(kind, it) != null }
        ColumnKind.BOOL -> v.takeIf { GraphColumns.numberOf(kind, it) != null }
        else -> v
    }

    /** "2026-09-02", "02/09/2026", "2-9-2026" or "2026/09/02" as ISO; null otherwise. Day before month, as in Chile. */
    fun date(v: String): String? {
        val t = v.trim()
        Regex("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})$").find(t)?.destructured?.let { (y, m, d) -> return iso(y.toInt(), m.toInt(), d.toInt()) }
        Regex("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})$").find(t)?.destructured?.let { (d, m, y) -> return iso(y.toInt(), m.toInt(), d.toInt()) }
        return com.proasiste.lite.runtime.RulingColumns.isoDate(t)?.takeIf { Regex("^\\d{1,2}\\s+de\\s+\\p{L}+\\s+(?:de(?:l)?\\s+)?\\d{4}$", RegexOption.IGNORE_CASE).matches(t) }
    }

    private fun iso(y: Int, m: Int, d: Int) = runCatching { java.time.LocalDate.of(y, m, d).toString() }.getOrNull()

    /** The rows as nodes with their values, and the links they make: what [GraphColumns.replaceTable] writes. */
    fun rowsOf(sheet: TableFile.Sheet, map: TableMap): List<TableRowIn> = sheet.body.mapIndexedNotNull { i, r ->
        val name = r.getOrElse(map.nameColumn) { "" }.trim().replace(Regex("\\s+"), " ")
        if (Graph.keyOf(name).isEmpty()) return@mapIndexedNotNull null
        val values = mutableListOf<NewValue>(); val links = mutableListOf<Triple<String, String, String>>()
        for (c in map.columns.filter { it.include && it.index != map.nameColumn }) {
            val raw = r.getOrElse(c.index) { "" }.trim(); if (raw.isEmpty()) continue
            if (c.linkTo != null) raw.split(Regex("\\s*[;|]\\s*")).filter { it.isNotBlank() }.forEach { links += Triple(c.name, c.linkTo, it) }
            else cell(c.kind, raw)?.let { values += NewValue(c.name, it, i + 2) }
        }
        TableRowIn(name, i + 2, values, links)
    }
}

/**
 * Names spelled two ways: "Daniela Marzi Muñoz" in a list and "Daniela Beatriz Marzi Muñoz" in a ruling's signature.
 * Two names are taken as the same person when one's words all appear in the other, in order, and they share the first
 * word and the last two (both surnames). Used to join a table's rows to the names already in the graph, and to suggest
 * names to merge.
 */
object SameName {
    private fun words(n: String) = Graph.keyOf(n).split(" ").filter { it.isNotEmpty() && it !in setOf("del", "de", "la", "las", "los", "y") }

    fun same(a: String, b: String): Boolean {
        val x = words(a); val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val (short, long) = if (x.size <= y.size) x to y else y to x
        if (short.size < 2 || short.size == long.size) return false
        // "Silva Gallinato" alone: both surnames, no first name.
        if (short.size == 2) return long.takeLast(2) == short
        if (short.first() != long.first() || short.takeLast(2) != long.takeLast(2)) return false
        var i = 0; for (w in long) if (i < short.size && w == short[i]) i++
        return i == short.size
    }

    /** The one name in [known] that is the same as [name] spelled another way, or null when none or several are. */
    fun match(name: String, known: Collection<String>): String? {
        if (known.any { Graph.keyOf(it) == Graph.keyOf(name) }) return null
        return known.filter { same(name, it) }.distinctBy(Graph::keyOf).singleOrNull()
    }

    /** The main number of a case name: "STC Rol 17.292", "Rol N° 17.292-26" and "17292-2026" all give 17292. */
    fun caseNumber(name: String): String? = Regex("(?i)(?:rol\\s*(?:n[°º.]?\\s*)?)?\\b(\\d{1,3}(?:\\.\\d{3})+|\\d{3,6})(?:\\s*-\\s*\\d{2,4})?\\b").find(name)
        ?.takeIf { Regex("(?i)\\brol\\b").containsMatchIn(name) || Regex("^\\s*\\d").containsMatchIn(name) }?.groupValues?.get(1)?.replace(".", "")

    /**
     * Pairs of names in the graph that are probably one thing: the same person spelled two ways ([same]), or the same case
     * number written two ways. Each pair keeps the more linked name. Suggestions only: nothing merges without a yes.
     */
    fun likely(nodes: List<GraphPicture.Node>): List<Pair<GraphPicture.Node, GraphPicture.Node>> {
        val out = mutableListOf<Pair<GraphPicture.Node, GraphPicture.Node>>()
        val byCase = nodes.mapNotNull { n -> caseNumber(n.name)?.let { it to n } }.groupBy({ it.first }, { it.second })
        for (group in byCase.values) if (group.size > 1) { val keep = group.maxBy { it.degree }; group.filter { it !== keep }.forEach { out += keep to it } }
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val a = nodes[i]; val b = nodes[j]
            if (a.key == b.key || out.any { (x, y) -> setOf(x.key, y.key) == setOf(a.key, b.key) }) continue
            if (caseNumber(a.name) == null && caseNumber(b.name) == null && same(a.name, b.name)) out += if (a.degree >= b.degree) a to b else b to a
        }
        return out
    }

    /**
     * [rows] with each row name, and each link's name, replaced by the graph's own spelling when [match] finds one.
     * [known] gives each type's names. Returns the rows and how many names were matched.
     */
    fun resolve(rows: List<TableRowIn>, type: String, known: Map<String, List<String>>): Pair<List<TableRowIn>, Int> {
        var matched = 0
        val out = rows.map { r ->
            val name = known[type]?.let { match(r.name, it) }?.also { matched++ } ?: r.name
            val links = r.links.map { (c, t, n) -> Triple(c, t, known[t]?.let { match(n, it) }?.also { matched++ } ?: n) }
            r.copy(name = name, links = links)
        }
        return out to matched
    }
}

/** One row on its way in: its node's name, its row number (the "page" its values cite), its values and its links as (column, type, name). */
data class TableRowIn(val name: String, val row: Int, val values: List<NewValue>, val links: List<Triple<String, String, String>>)
