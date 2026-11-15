package com.proasiste.lite.graph

/**
 * Answers a question that counts, lists or groups a type's nodes by their columns, such as "¿cuántos fallos de 2026
 * fueron rechazados?", "¿en qué fallos disintió Silva?" or "fallos por resultado", straight from the column values: no
 * model reads or writes anything, so nothing is left out or added. A question it doesn't recognise returns null and
 * takes the normal search.
 *
 * Filters come from the values themselves: a value whose distinctive words appear in the question ("rechazados" →
 * Resultado = Rechazado), a person's surname in a person column, an article or law number in a list of norms, a year in
 * a date column. A word naming a column after "por", "per" or "by" groups by it.
 */
object TableQuestions {
    data class Filter(val column: String, val value: String)

    /** [lines] is the answer; [cited] are the rows it names, each with the column its citation points at. */
    data class Answer(val text: String, val cited: List<Pair<TableRow, String?>>, val filters: List<Filter>, val groupedBy: String?)

    private val COUNT = listOf("cuant", "how many", "numero de", "number of", "cantidad")
    /** Words that ask for the items themselves when the type's name follows them: "qué fallos", "which rulings". */
    private val ASK = setOf("que", "cual", "cuales", "which", "what")
    private val LIST = listOf("list", "lista", "enumera", "muestra", "show me")
    private val MOST = listOf("mas ", "most", "mayor")

    /** Words that point at a column besides its own name. Lower case, accent-folded, matched as word starts. */
    private val HINTS = mapOf(
        "Resultado" to listOf("resultado", "outcome", "result", "decision", "decidi"),
        "Fecha" to listOf("ano", "year", "fecha", "date", "mes"),
        "Tipo" to listOf("tipo", "type", "accion"),
        "Ministros" to listOf("ministr", "integr", "judge", "justice", "sat"),
        "Redactor" to listOf("redact", "drafted", "drafter", "wrote"),
        "Disidentes" to listOf("disid", "disie", "disint", "disent", "dissent"),
        "Previenen" to listOf("previn", "prevencion", "concurr"),
        "Normas impugnadas" to listOf("norma", "articulo", "art", "ley", "precepto", "law", "article"),
        "Requirente" to listOf("requirente", "filer", "filed", "quien present"),
        "Gestión" to listOf("gestion", "juzgado", "tribunal de", "corte", "pending case"),
    )
    /** English words for outcome values, so "rejected" finds Rechazado. */
    private val SYNONYMS = mapOf("rejected" to "rechaz", "dismissed" to "rechaz", "upheld" to "acog", "granted" to "acog", "accepted" to "acog",
        "inadmissible" to "inadmis", "partly" to "parte", "partially" to "parte", "parcialmente" to "parte", "conformity" to "conform")

    private val PEOPLE = setOf("Ministros", "Redactor", "Disidentes", "Previenen")

    fun answer(question: String, typeName: String, typeWords: List<String>, rows: List<TableRow>, defs: List<ColumnDef>, english: Boolean): Answer? {
        if (rows.isEmpty()) return null
        val folded = " " + Graph.keyOf(question) + " "
        val words = folded.trim().split(" ").filter { it.isNotEmpty() }.map { SYNONYMS[it] ?: it }
        fun has(starts: List<String>) = starts.any { s -> if (' ' in s) folded.contains(" $s") else words.any { it.startsWith(s) } }
        if (!has(typeWords)) return null
        val counting = has(COUNT)
        val listing = has(LIST) || words.zipWithNext().any { (a, b) -> a in ASK && typeWords.any { b.startsWith(it) } }
        val most = has(MOST)

        // Which column the question's words point at, for grouping and for choosing among person columns.
        fun pointed(column: String) = has((HINTS[column] ?: emptyList()) + Graph.keyOf(column).split(" ").filter { it.length >= 4 })
        val personColumn = listOf("Disidentes", "Redactor", "Previenen").firstOrNull { it in defs.map { d -> d.name } && pointed(it) } ?: "Ministros".takeIf { c -> defs.any { it.name == c } }

        val filters = mutableListOf<Filter>()
        val tests = mutableListOf<(TableRow) -> Boolean>()
        // A year in a date column.
        val dateColumn = defs.firstOrNull { it.kind == ColumnKind.DATE }?.name
        val years = Regex("\\b(19|20)\\d{2}\\b").findAll(folded).map { it.value }.toSet()
        if (dateColumn != null && years.isNotEmpty()) {
            filters += Filter(dateColumn, years.sorted().joinToString(", "))
            tests += { r -> r.values(dateColumn).any { it.take(4) in years } }
        }
        // From the question as typed: folding would split "18.695" into two numbers.
        val numbers = NUMBER.findAll(question).map { it.value.replace(".", "") }.filter { it !in years && it.length <= 6 }.toSet()
        for (d in defs) {
            if (d.kind == ColumnKind.DATE || d.kind == ColumnKind.NUMBER || d.kind == ColumnKind.BOOL) continue
            val distinct = rows.flatMap { it.values(d.name) }.distinct()
            val chosen: String? = when {
                d.name in PEOPLE -> if (d.name != personColumn) null else distinct.filter { v -> surnames(v).any { it in words } }.let { found ->
                    // One person asked about: their surnames picked them out. Several matches mean a shared surname: all of them.
                    if (found.isEmpty()) null else found.joinToString(" | ")
                }
                distinct.any { Regex("\\d").containsMatchIn(it) } && numbers.isNotEmpty() && pointed(d.name) ->
                    distinct.filter { v -> val vn = NUMBER.findAll(v).map { it.value.replace(".", "") }.toSet(); numbers.all { it in vn } }
                        .takeIf { it.isNotEmpty() }?.joinToString(" | ")
                distinct.size <= 15 -> best(distinct, words)
                else -> null
            }
            if (chosen != null) {
                val wanted = chosen.split(" | ").toSet()
                filters += Filter(d.name, chosen.split(" | ").let { if (it.size > 3) "${it.size} valores" else it.joinToString(", ") })
                tests += { r -> r.values(d.name).any { it in wanted } }
            }
        }

        // Grouping: "por resultado", "by year", or "who dissents most".
        val groupWords = Regex("\\b(?:por|per|by|segun|each|cada)\\s+(\\p{L}+)").findAll(folded).map { it.groupValues[1] }.toList()
        var groupBy: String? = defs.map { it.name }.firstOrNull { c -> groupWords.any { g -> ((HINTS[c] ?: emptyList()) + Graph.keyOf(c).split(" ")).any { h -> h.length >= 3 && g.startsWith(h) } } }
        if (groupBy == null && most && personColumn != null && filters.none { it.column == personColumn }) groupBy = personColumn
        if (!counting && !listing && groupBy == null) return null
        if (filters.isEmpty() && groupBy == null && !counting) return null

        val matching = rows.filter { r -> tests.all { it(r) } }
        val noun = if (typeName == "Fallo") (if (english) "rulings" else "fallos") else typeName.lowercase() + "s"
        val about = if (filters.isEmpty()) "" else filters.joinToString("; ", " (", ")") { "${it.column}: ${it.value}" }

        if (groupBy != null) {
            val g = groupBy
            val counts = HashMap<String, Int>()
            for (r in matching) {
                val keys = if (g == dateColumn) r.values(g).map { it.take(4) }.distinct() else r.values(g).distinct()
                (keys.ifEmpty { listOf(if (english) "(not read)" else "(sin leer)") }).forEach { counts.merge(it, 1, Int::plus) }
            }
            val sorted = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            val head = if (english) "${matching.size} $noun$about by $g:" else "${matching.size} $noun$about por $g:"
            return Answer(head + "\n" + sorted.take(25).joinToString("\n") { "${it.key}: ${it.value}" }, emptyList(), filters, g)
        }

        // A case can hold several decisions (an admissibility resolution and the judgment are both "Rol X"): each row is
        // shown and cited through the file whose values matched, its date and outcome from that same file.
        val wantedOutcome = filters.firstOrNull { it.column == "Resultado" }?.value?.split(", ")?.toSet()
        fun focus(r: TableRow): TableRow {
            val outcomes = r.cells["Resultado"].orEmpty()
            val source = (wantedOutcome?.let { w -> outcomes.firstOrNull { it.value in w } } ?: outcomes.firstOrNull())?.sourceId
                ?: years.takeIf { it.isNotEmpty() }?.let { ys -> dateColumn?.let { c -> r.cells[c]?.firstOrNull { it.value.take(4) in ys } }?.sourceId }
                ?: return r
            return r.copy(cells = r.cells.mapValues { (_, cs) -> cs.sortedBy { if (it.sourceId == source) 0 else 1 } })
        }
        val sorted = matching.map(::focus).sortedWith(compareByDescending<TableRow> { r -> dateColumn?.let { r.first(it)?.num } ?: 0.0 }.thenBy { it.node.name })
        val head = if (english) "${matching.size} $noun$about." else "${matching.size} $noun$about."
        if (matching.isEmpty()) return Answer(if (english) "No $noun$about." else "Ningún ${typeName.lowercase()}$about.", emptyList(), filters, null)
        val shown = sorted.take(LIMIT)
        val citeColumn = filters.lastOrNull { it.column != dateColumn }?.column ?: dateColumn
        val lines = shown.mapIndexed { i, r ->
            val decisions = r.cells["Resultado"].orEmpty().map { it.sourceId }.distinct().size
            val parts = listOfNotNull(r.node.name, dateColumn?.let { r.values(it).firstOrNull() }, r.values("Resultado").firstOrNull(),
                (if (english) "$decisions decisions" else "$decisions decisiones").takeIf { decisions > 1 })
            parts.joinToString(" · ") + " [${i + 1}]"
        }
        val more = if (sorted.size > LIMIT) (if (english) "\n…and ${sorted.size - LIMIT} more." else "\n…y ${sorted.size - LIMIT} más.") else ""
        return Answer(head + "\n" + lines.joinToString("\n") + more, shown.map { it to citeColumn }, filters, null)
    }

    const val LIMIT = 40
    /** A number as written, thousands dots included: "18.695", "60". */
    private val NUMBER = Regex("\\d[\\d.]*\\d|\\d")

    /** The words of a person's name that pick them out: the last two, as Spanish names end with both surnames. */
    fun surnames(name: String): List<String> = Graph.keyOf(name).split(" ").filter { it.length >= 3 && it !in setOf("del", "las", "los") }.takeLast(2)

    /**
     * Among a column's few distinct values, the one the question names: every distinctive word of the value (a word no
     * other value has, or all its words when it has none) starts a word of the question, or the other way round. The
     * value matching the most words wins, so "acogidos en parte" picks "Acogido en parte" over "Acogido".
     */
    private fun best(values: List<String>, words: List<String>): String? {
        val split = values.associateWith { v -> Graph.keyOf(v).split(" ").filter { it.length >= 4 } }
        val all = split.values.flatten().groupingBy { it }.eachCount()
        fun hit(w: String) = words.any { q -> q.startsWith(w.take(5)) || (q.length >= 5 && w.startsWith(q)) }
        return split.entries.mapNotNull { (v, ws) ->
            if (ws.isEmpty()) return@mapNotNull null
            val distinctive = ws.filter { all[it] == 1 }.ifEmpty { ws }
            if (distinctive.all(::hit)) v to ws.count(::hit) else null
        }.maxByOrNull { it.second }?.first
    }
}
