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
        var several: String? = null
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
                // Several outcomes named ("acogieron, rechazaron o declararon inadmisible") ask for a breakdown, not a filter.
                distinct.size <= 15 && namesSeveral(distinct, words) -> { several = several ?: d.name; null }
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
        if (groupBy == null && several != null) groupBy = several
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
        // Nothing found through a name, a norm or another free-text column may mean the question was read wrongly: search
        // answers instead. A zero from the year, outcome or kind alone is a real answer.
        if (matching.isEmpty() && filters.any { it.column != dateColumn && it.column != "Resultado" && it.column != "Tipo" }) return null
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

    /**
     * "¿Qué resolvió la STC 17.292?": the outcome the rules read in the decision section of the ruling the question names
     * by its number, with its date and kind, citing the page of the decision. Each decision of the case is given when it
     * has several (an admissibility resolution and the judgment). Null when the question names no ruling, doesn't ask
     * what was decided, or the table has no outcome for it: then search answers.
     */
    fun decision(question: String, rows: List<TableRow>, english: Boolean): Answer? {
        val folded = " " + Graph.keyOf(question) + " "
        val asksDate = WHEN.any { folded.contains(it) }
        if (DECIDED.none { folded.contains(it) } && !asksDate) return null
        val number = SameName.caseNumber(question)
        val named = if (number != null) rows.filter { SameName.caseNumber(it.node.name) == number } else byParty(question, rows)
        if (asksDate) {
            val dated = named.filter { it.cells["Fecha"].orEmpty().isNotEmpty() }
            if (dated.isEmpty()) return null
            return Answer(dated.mapIndexed { i, r -> "${r.node.name}: ${r.first("Fecha")!!.value} [${i + 1}]" }.joinToString("\n"), dated.map { it to "Fecha" }, emptyList(), null)
        }
        val matches = named.filter { it.cells["Resultado"].orEmpty().isNotEmpty() }
        if (matches.isEmpty()) return null
        val lines = mutableListOf<String>(); val cited = mutableListOf<Pair<TableRow, String?>>()
        for (r in matches) for (outcome in r.cells.getValue("Resultado").distinctBy { it.sourceId }) {
            val focused = r.copy(cells = r.cells.mapValues { (_, cs) -> cs.sortedBy { if (it.sourceId == outcome.sourceId) 0 else 1 } })
            val date = focused.first("Fecha")?.takeIf { it.sourceId == outcome.sourceId }?.value
            val kind = focused.first("Tipo")?.takeIf { it.sourceId == outcome.sourceId }?.value
            cited += focused to "Resultado"
            lines += "${r.node.name}: ${outcome.value}" + listOfNotNull(kind, date).joinToString(", ", " (", ")").takeIf { kind != null || date != null }.orEmpty() + " [${cited.size}]"
        }
        val head = if (lines.size > 1) (if (english) "${lines.size} decisions:" else "${lines.size} decisiones:") + "\n" else ""
        return Answer(head + lines.joinToString("\n"), cited, listOf(Filter("Resultado", number ?: matches.joinToString(", ") { it.node.name })), null)
    }
    /** Words that ask when a ruling was given. */
    private val WHEN = listOf(" cuando se dict", " fecha de la sentencia", " fecha del fallo", " when was")
    /** Words in company and institution names that don't pick one out. */
    private val GENERIC = setOf("sociedad", "spa", "ltda", "limitada", "empresa", "empresas", "compania", "servicios", "inversiones", "comercial", "ilustre",
        "municipalidad", "chile", "nacional", "juez", "jueza", "juzgado", "letras", "tribunal", "corte", "apelaciones", "suplente", "abogado", "presidente", "republica")

    /**
     * The rows whose filer (Requirente) the question names: "el caso de Salcobrand", "Patricia Quiroz Riffo", "Kimen
     * Montessori". A filer's distinctive words are those few other filers share, leaving out words like "Sociedad" or
     * "Municipalidad"; it is named when the question holds two of them, or its only one when that is a long word.
     * More than ten matches means the words weren't distinctive after all: none.
     */
    fun byParty(question: String, rows: List<TableRow>): List<TableRow> {
        val words = Graph.keyOf(question).split(" ").toSet()
        fun tokens(v: String) = Graph.keyOf(v).split(" ").filter { it.length >= 3 && it !in GENERIC && it !in setOf("del", "las", "los", "con", "por") && it.none(Char::isDigit) }.toSet()
        val filers = rows.associateWith { r -> r.values("Requirente").flatMap(::tokens).toSet() }
        val df = filers.values.flatten().groupingBy { it }.eachCount()
        val found = filers.filter { (_, ts) ->
            val distinctive = ts.filter { (df[it] ?: 0) <= 5 }
            val hits = distinctive.count { it in words }
            hits >= 2 || (distinctive.size == 1 && hits == 1 && distinctive.single().length >= 5)
        }.keys.toList()
        return if (found.size in 1..10) found else emptyList()
    }

    /** Words that ask what a ruling decided. */
    private val DECIDED = listOf(" resolv", " resuelv", " decid", " decision", " resultado", " outcome", " ruled ", " acog", " rechaz", " fallo de la", " como termin")

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
    private fun best(values: List<String>, words: List<String>): String? = named(values, words).maxByOrNull { it.second }?.first

    /** Every value of the few the question names, with how many of its words it matches. */
    private fun named(values: List<String>, words: List<String>): List<Pair<String, Int>> {
        val split = values.associateWith { v -> Graph.keyOf(v).split(" ").filter { it.length >= 4 } }
        val all = split.values.flatten().groupingBy { it }.eachCount()
        fun hit(w: String) = words.any { q -> q.startsWith(w.take(5)) || (q.length >= 5 && w.startsWith(q)) }
        return split.entries.mapNotNull { (v, ws) ->
            if (ws.isEmpty()) return@mapNotNull null
            val distinctive = ws.filter { all[it] == 1 }.ifEmpty { ws }
            if (distinctive.all(::hit)) v to ws.count(::hit) else null
        }
    }

    /** Whether the question names two or more different values of a column ("acogieron, rechazaron o inadmisible"). */
    private fun namesSeveral(values: List<String>, words: List<String>) = named(values, words).map { it.first.substringBefore(" ") }.distinct().size >= 2
}
