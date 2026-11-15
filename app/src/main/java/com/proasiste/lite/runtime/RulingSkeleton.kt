package com.proasiste.lite.runtime

import com.proasiste.lite.graph.NewFact
import com.proasiste.lite.runtime.LegalFacts.Court
import java.text.Normalizer

/**
 * The rule-based skeleton of a Chilean court ruling: what its formulaic parts state, read with fixed patterns and
 * no model. The header gives the ruling, its date, type, requirente, challenged norms and pending case; the
 * considerandos give the court's reasoning paragraphs and the rulings each one cites; "SE RESUELVE" gives each
 * decided point; the separate votes give who dissented or concurred and their own numbered reasoning; the signature
 * page gives who sat and who drafted. Every fact is in Spanish and cites its page.
 *
 * The skeleton also maps each page to its sections, so an AI fact can be attached to the right one: the court's
 * reasoning, or a dissent, which must never be read as the court's view.
 */
class RulingSkeleton private constructor(
    val ruling: String,
    val court: Court,
    val facts: List<NewFact>,
    /** Full names from the signature page, as they appear there. */
    val ministers: List<String>,
    /** Every role number mentioned anywhere in the ruling, as canonical names: the Fallo nodes an AI fact may use. */
    val knownRulings: Set<String>,
    /** Other nodes the skeleton created, as (type, name): offered to the model so it reuses the names. */
    val knownNodes: List<Pair<String, String>>,
    private val votes: List<Vote>,
    /** The outcome read from the decision section ([Decision.outcome]), or null when it doesn't say it plainly. */
    val outcome: String? = null,
    /** Who filed the action, from the header or VISTOS; null for a preventive control, which has no filer. */
    val filer: String? = null,
    /** The date it was given, as ISO (2026-09-02), checked against its filing ([RulingDate]); null when none passes. */
    val date: String? = null,
    /** Where the ruling's own number was read ([LegalFacts.Identity.from]): heading, closing line, file name or text. */
    val rulingFrom: String = "",
) {
    class Vote(val name: String, val kind: String, val sense: String, val ministers: List<String>, val firstPage: Int, val lastPage: Int, val text: String)

    /** The separate vote a fact on [page] belongs to, when its words sit in that vote's text rather than the court's. */
    fun voteFor(page: Int, statement: String): Vote? {
        val candidates = votes.filter { page in it.firstPage..it.lastPage }
        if (candidates.isEmpty()) return null
        val words = LegalFacts.numbers(statement) + DeepSeekFacts.words(statement)
        if (words.size < 3) return candidates.firstOrNull { page > it.firstPage }
        return candidates.maxByOrNull { v -> words.count { it in fold(v.text) } }?.takeIf { v ->
            val inVote = words.count { it in fold(v.text) }
            // A page shared with the court's text: the fact goes to the vote only if clearly more of it is there.
            if (page == v.firstPage) inVote * 2 > words.size else inVote > 0
        }
    }

    companion object {
        fun of(pages: List<DeepSeekFacts.PageText>, title: String): RulingSkeleton? {
            if (pages.isEmpty()) return null
            val first = pages.first().text
            val court = LegalFacts.courtOf(first) ?: return null
            val identity = LegalFacts.identity(pages, title)
            val ruling = identity.name
            val doc = Document(pages)
            val facts = mutableListOf<NewFact>()
            val known = mutableListOf<Pair<String, String>>()

            // ---- Header: date, type, requirente, challenged norms, pending case ----
            val tribunal = court.tribunal
            val dated = RulingDate.find(pages, ruling)
            dated?.let { facts += fact(it.page, "Tribunal", tribunal, "DICTA", "Fallo", ruling, "El $tribunal dictó $ruling el ${it.text}.") }
            val header = Header.of(first)
            header.type?.let { facts += fact(1, "Fallo", ruling, "TIPO", "Concepto", it, "$ruling resuelve un ${it.lowercase()}.") }
            val requirente = header.requirente
            if (requirente != null) {
                known += "Parte" to requirente
                for (norm in header.norms) {
                    known += "Norma" to norm
                    facts += fact(1, "Parte", requirente, "IMPUGNA", "Norma", norm, "$requirente impugna la aplicación de la norma $norm.")
                }
                header.gestion?.let { (gestion, gestionCourt) ->
                    known += "Gestion" to gestion
                    facts += fact(1, "Parte", requirente, "REQUIERE_EN", "Gestion", gestion, "$requirente requiere para que ello incida en la gestión $gestion.")
                    if (gestionCourt != null) {
                        known += "Tribunal" to gestionCourt
                        facts += fact(1, "Gestion", gestion, "SEGUIDA_ANTE", "Tribunal", gestionCourt, "La gestión $gestion se sigue ante el $gestionCourt.")
                    }
                }
            }

            // ---- Signature page: who sat, who drafted ----
            val ministers = Signature.ministers(doc.text)
            val signaturePage = doc.pageAt(doc.text.lastIndexOf("integrad")).coerceAtLeast(1)
            ministers.forEachIndexed { i, name ->
                val role = if (i == 0 && Signature.PRESIDES.containsMatchIn(doc.text.substring(maxOf(0, doc.text.lastIndexOf("integrad")), minOf(doc.text.length, doc.text.lastIndexOf("integrad") + 120)))) " como Presidente o Presidenta" else ""
                facts += fact(signaturePage, "Ministro", name, "INTEGRA", "Fallo", ruling, "$name integró el $tribunal que pronunció $ruling$role.")
            }
            Signature.drafter(doc.text, ministers)?.let { (name, at) ->
                facts += fact(doc.pageAt(at), "Ministro", name, "REDACTA", "Fallo", ruling, "$name redactó la sentencia $ruling.")
            }

            // ---- Sections ----
            val considerandoStart = CONSIDERANDO_START.find(doc.text)?.range?.last?.plus(1) ?: -1
            // Preventive control rulings decide with "SE DECLARA:" instead.
            val resolveStart = (RESOLVE_START.find(doc.text, maxOf(0, considerandoStart)) ?: DECLARE_START.find(doc.text, maxOf(0, considerandoStart)))?.range?.first ?: -1
            val votesStart = resolveStart.takeIf { it >= 0 }?.let { VOTE_HEADING.find(doc.text, it)?.range?.first } ?: -1
            val closing = CLOSING.find(doc.text, maxOf(0, resolveStart))?.range?.first ?: doc.text.length
            val knownRulings = mutableSetOf(ruling)

            // Considerandos of the court, with headings and citations.
            if (considerandoStart >= 0) {
                val end = if (resolveStart >= 0) resolveStart else closing
                for (c in Considerandos.ofCourt(doc.text, considerandoStart, end)) {
                    val node = "Considerando ${c.number}° de $ruling"
                    val page = doc.pageAt(c.start)
                    val heading = c.heading?.let { ", bajo «$it»" } ?: ""
                    facts += fact(page, "Fallo", ruling, "CONSIDERA", "Considerando", node, "$node (pág. $page$heading): ${c.opening}")
                    for ((cited, at) in Citations.inText(c.text, court, ruling)) {
                        knownRulings += cited
                        facts += fact(doc.pageAt(c.start + at), "Considerando", node, "CITA", "Fallo", cited, "El considerando ${c.number}° de $ruling cita $cited.")
                    }
                }
                // One link per cited ruling for the court as a whole, so "qué fallos citó el tribunal" lists them.
                facts.filter { it.relation == "CITA" && it.subjectType == "Considerando" }.groupBy { it.obj }.forEach { (cited, byConsiderando) ->
                    val numbers = byConsiderando.map { it.subject.substringAfter("Considerando ").substringBefore(" de") }.distinct()
                    facts += fact(byConsiderando.first().page, "Fallo", ruling, "CITA_PRECEDENTE", "Fallo", cited,
                        "$ruling cita $cited en ${if (numbers.size == 1) "el considerando" else "los considerandos"} ${numbers.joinToString(", ")}.")
                }
            }

            // SE RESUELVE: one fact per decided point.
            var outcome: String? = null
            if (resolveStart >= 0) {
                val end = if (votesStart >= 0) votesStart else closing
                outcome = Decision.outcome(doc.text.substring(resolveStart, end))
                for ((clause, at) in Decision.clauses(doc.text.substring(resolveStart, end))) {
                    val concept = Decision.concept(clause)
                    known += "Concepto" to concept
                    facts += fact(doc.pageAt(resolveStart + at), "Fallo", ruling, "RESUELVE", "Concepto", concept, "$ruling resuelve: ${Decision.sentence(clause)}")
                }
            }

            // Separate votes: dissents and concurrences, each with its ministers, sense, author and numbered reasoning.
            val votes = mutableListOf<Vote>()
            if (votesStart >= 0) {
                // The vote's author is named after "Redactó la sentencia", past the vote's own text.
                val author = Signature.VOTE_AUTHOR.find(doc.text, votesStart)?.let { Signature.match(it.groupValues[1], ministers) to it.range.first }
                for (v in Votes.of(doc.text.substring(votesStart, closing), ministers)) {
                    val start = votesStart + v.start
                    val firstPage = doc.pageAt(start); val lastPage = doc.pageAt(start + v.text.length - 1)
                    val who = v.ministers.joinToString(", ") { Signature.surnames(it) }
                    val name = "${v.kind} de $who en $ruling"
                    votes += Vote(name, v.kind, v.sense, v.ministers, firstPage, lastPage, v.text)
                    known += "Voto" to name
                    val sense = if (v.sense.isNotEmpty()) ", ${v.sense}" else ""
                    facts += fact(firstPage, "Fallo", ruling, "VOTO_SEPARADO", "Voto", name, "$ruling tiene una ${v.kind.lowercase()} de ${v.ministers.joinToString(", ")}$sense (págs. $firstPage-$lastPage).")
                    for (m in v.ministers) {
                        val rel = if (v.kind == "Disidencia") "DISIENTE" else "PREVIENE"
                        val verb = if (rel == "DISIENTE") "disiente" else "previene"
                        facts += fact(firstPage, "Ministro", m, rel, "Fallo", ruling, "$m $verb en $ruling$sense (pág. $firstPage).")
                        facts += fact(firstPage, "Ministro", m, "SUSCRIBE", "Voto", name, "$m suscribe la ${v.kind.lowercase()} en $ruling.")
                    }
                    author?.let { (who, at) -> facts += fact(doc.pageAt(at), "Ministro", who, "REDACTA", "Voto", name, "$who redactó la ${v.kind.lowercase()} en $ruling.") }
                    for (c in Considerandos.ofVote(v.text)) {
                        val node = "Considerando ${c.number}° de la ${v.kind.lowercase()} en $ruling"
                        val page = doc.pageAt(start + c.start)
                        facts += fact(page, "Voto", name, "CONSIDERA", "Considerando", node, "$node (pág. $page): ${c.opening}")
                        for ((cited, at) in Citations.inText(c.text, court, ruling)) {
                            knownRulings += cited
                            facts += fact(doc.pageAt(start + c.start + at), "Considerando", node, "CITA", "Fallo", cited, "El considerando ${c.number}° de la ${v.kind.lowercase()} en $ruling cita $cited.")
                        }
                    }
                }
            }

            // Every role anywhere in the text counts as known, so an AI citation the rules missed isn't held back.
            knownRulings += Citations.allRoles(doc.text, court, ruling)
            known += ministers.map { "Ministro" to it }
            known += knownRulings.map { "Fallo" to it }
            return RulingSkeleton(ruling, court, facts.distinctBy { listOf(it.subject, it.relation, it.obj, it.statement) }, ministers, knownRulings, known.distinct(), votes, outcome, requirente, dated?.iso, identity.from)
        }

        private fun fact(page: Int, st: String, s: String, rel: String, ot: String, o: String, statement: String) =
            NewFact(page, st, s, rel, ot, o, statement.replace(Regex("\\s+"), " ").trim().take(600))

        fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

        private val DATE = Regex("\\[\\s*(\\d{1,2}\\s+de\\s+[a-záéíóú]+\\s+de\\s+\\d{4})\\s*]", RegexOption.IGNORE_CASE)
        private val CONSIDERANDO_START = Regex("\\bY\\s+CONSIDERANDO\\s*:", RegexOption.IGNORE_CASE)
        private val RESOLVE_START = Regex("\\bSE\\s+RESUELVE\\s*:")
        private val DECLARE_START = Regex("\\bSE\\s+DECLARA\\s*:")
        val VOTE_HEADING = Regex("(?m)^\\s*(?:DISIDENCIAS?|PREVENCI[ÓO]N(?:ES)?|VOTOS?\\s+(?:EN\\s+CONTRA|PARTICULAR(?:ES)?|DISIDENTES?|POR\\s+(?:ACOGER|RECHAZAR)))\\s*$|\\bAcordada\\s+con\\s+el\\s+voto\\s+en\\s+contra|\\bSe\\s+previene\\s+que\\b|\\bLos?\\s+Ministr[oa]s?\\s+(?:señor(?:a|es)?\\s+)?[A-ZÁÉÍÓÚ][^.]{3,160}?\\s+(?:estuv(?:o|ieron)|previene[n]?|concurre[n]?)\\b")
        private val CLOSING = Regex("\\bRedact[óo]\\s+la\\s+sentencia|\\bPronunciada\\s+por\\b|\\bComun[íi]quese\\b")
    }

    /** The pages joined into one text, with a map from any offset back to its page number. */
    private class Document(pages: List<DeepSeekFacts.PageText>) {
        val text: String
        private val starts: List<Pair<Int, Int>>
        init {
            val sb = StringBuilder(); val s = mutableListOf<Pair<Int, Int>>()
            for (p in pages) { s += sb.length to p.page; sb.append(p.text.replace("\r\n", "\n").replace('\r', '\n')).append('\n') }
            text = sb.toString(); starts = s
        }
        fun pageAt(offset: Int): Int = starts.lastOrNull { it.first <= offset }?.second ?: starts.first().second
    }

    /** Page 1: the type of action, the requirente, the challenged norms and the pending case. */
    internal class Header(val type: String?, val requirente: String?, val norms: List<String>, val gestion: Pair<String, String?>?) {
        companion object {
            private val TYPES = listOf(
                Regex("REQUERIMIENTO\\s+DE\\s+INAPLICABILIDAD", RegexOption.IGNORE_CASE) to "Requerimiento de inaplicabilidad por inconstitucionalidad",
                Regex("CONTROL\\s+(?:PREVENTIVO\\s+)?DE\\s+CONSTITUCIONALIDAD", RegexOption.IGNORE_CASE) to "Control preventivo de constitucionalidad",
                Regex("REQUERIMIENTO\\s+DE\\s+INCONSTITUCIONALIDAD", RegexOption.IGNORE_CASE) to "Requerimiento de inconstitucionalidad",
                Regex("CONTIENDA\\s+DE\\s+COMPETENCIA", RegexOption.IGNORE_CASE) to "Contienda de competencia",
            )
            private val CAPTION_END = Regex("\\n\\s*\\n")
            // A court's name may wrap onto the next line; it ends at punctuation, a blank line, "EN ACTUAL", "BAJO" or "VISTOS".
            private val GESTION = Regex("EN\\s+(?:EL|LA|LOS|LAS)\\s+(?:PROCESO|CAUSA|AUTOS|JUICIO|GESTI[ÓO]N)\\s+(.{3,120}?),?\\s+SEGUID[OA]S?\\s+ANTE\\s+(?:EL|LA)\\s+(.{5,120}?)(?:[,.;]|\\n\\s*\\n|\\s+EN\\s+ACTUAL\\b|\\s+BAJO\\b|\\s+VISTOS\\b|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            private val ARTICLES = Regex("ART[ÍI]CULOS?\\s+(.+?)\\s+(?:DE\\s+(?:LA|EL|LOS|LAS)|DEL)\\s+(.+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            private val VISTOS = Regex("\\bVISTOS\\b")
            /** ", NUMERALES 4° Y 5°", ", N° 2)", ", LETRA C)": the numbered or lettered parts of an article. */
            private val SUBPART = Regex(",?\\s*(NUMERALES|NUMERAL|N[ÚU]MEROS?|N[°º]S?|LETRAS?)\\s*((?:(?:\\d{1,2}(?!\\d)\\s*[°º)]?|[A-Za-z]\\)|[A-Za-z](?=[\\s,]|$))\\s*(?:(?:,|Y|E)\\s*(?=\\d{1,2}(?!\\d)|[A-Za-z][)\\s,]|[A-Za-z]$))?)+)", RegexOption.IGNORE_CASE)
            private val FILER = Regex("VISTOS\\s*:?\\s*(?:Que,?\\s*)?(?:(?:con\\s+)?fecha\\s+\\d{1,2}\\s+de\\s+\\p{L}+(?:\\s+de)?\\s+\\d{4},?\\s*)?(.{3,200}?),?\\s+(?:acciona|deduce|dedujo|interpone|interpuso|requiere|requirió|formula|formuló|presenta|presentó|ha\\s+(?:deducido|interpuesto|presentado|formulado|requerido|accionado)|solicita|solicitó)\\b", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            private val GESTION_START = Regex("\\bEN\\s+(?:EL|LA|LOS|LAS)\\s+(?:PROCESO|CAUSA|AUTOS|JUICIO|GESTI[ÓO]N)\\b", RegexOption.IGNORE_CASE)
            private val ARTICLE = Regex("(\\d{1,4})\\s*(BIS|TER|QU[ÁA]TER|QUINQUIES)?(?:,?\\s*INCISOS?\\s+((?:\\w+\\s*(?:,|Y|E)?\\s*)+?)(?=,|\\s+N[°º]|\\s+LETRA|\\s+SEGUNDA|\\s+PRIMERA|\\s+PARTE|$|\\s+DE\\b))?", RegexOption.IGNORE_CASE)

            fun of(first: String): Header {
                val type = TYPES.firstOrNull { it.first.containsMatchIn(first) }?.second
                val captionStart = TYPES.firstNotNullOfOrNull { it.first.find(first)?.range?.first } ?: return Header(type, null, emptyList(), null)
                val rest = first.substring(captionStart)
                val blocks = rest.split(CAPTION_END).map { it.trim() }.filter { it.isNotEmpty() }
                // The requirente's line often runs straight into "EN EL PROCESO ..." with no blank line between.
                var requirente = blocks.getOrNull(1)?.let { b -> GESTION_START.find(b)?.let { b.substring(0, it.range.first) } ?: b }
                    ?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("VISTOS") }?.let { Names.titleCase(it.replace(Regex("\\s+"), " ")) }
                var caption = blocks.getOrNull(0).orEmpty()
                // Text read from the PDF's tags has no blank lines between the caption, the filer and the case: the
                // caption then ends at the case or at VISTOS, and the filer comes from VISTOS' first sentence.
                if (blocks.size < 2) {
                    val end = listOfNotNull(GESTION_START.find(rest)?.range?.first, VISTOS.find(rest)?.range?.first).minOrNull() ?: rest.length
                    caption = rest.substring(0, end)
                    requirente = FILER.find(first)?.groupValues?.get(1)?.let { Names.filer(it) }
                }
                val gestion = GESTION.find(rest)?.let { m ->
                    val id = m.groupValues[1].replace(Regex("\\s+"), " ").trim().trimEnd(',')
                    val courtName = Names.titleCase(m.groupValues[2].replace(Regex("\\s+"), " ").trim())
                    "${Names.caseId(id)} ante $courtName" to courtName
                }
                return Header(type, requirente, norms(caption), gestion)
            }

            fun norms(caption: String): List<String> {
                val after = Regex("RESPECTO\\s+DE(?:L|\\s+LA|\\s+LOS|\\s+LAS)?\\s+", RegexOption.IGNORE_CASE).find(caption)?.range?.last?.plus(1) ?: return emptyList()
                val scope = caption.substring(after).replace(Regex("\\s+"), " ")
                return scope.split(";").mapNotNull { segment ->
                    val m = ARTICLES.find(segment) ?: return@mapNotNull null
                    val body = Norms.body(m.groupValues[2]) ?: return@mapNotNull null
                    var list = m.groupValues[1].replace(Regex(",?\\s*(?:SEGUNDA|PRIMERA|TERCERA)\\s+PARTE", RegexOption.IGNORE_CASE), "")
                    // "358, NUMERALES 4° Y 5°" and "46, LETRA C)" are parts of one article, not articles 4 and 5: each
                    // becomes a marker without digits ("§a§") that the article before it picks up.
                    val parts = mutableListOf<String>()
                    list = SUBPART.replace(list) { p ->
                        val items = Regex("\\d{1,3}|\\b[A-Za-z]\\b").findAll(p.groupValues[2]).map { it.value.lowercase() }.filter { it != "y" && it != "e" }.toList()
                        if (items.isEmpty()) "" else {
                            parts += (if (p.groupValues[1].uppercase().startsWith("LETRA")) " letra " else " N° ") + items.joinToString(" y ")
                            "§${'a' + parts.size - 1}§"
                        }
                    }
                    ARTICLE.findAll(list).map { a ->
                        val suffix = a.groupValues[2].takeIf { it.isNotEmpty() }?.let { " ${it.lowercase()}" } ?: ""
                        val incisos = a.groupValues[3].takeIf { it.isNotBlank() }?.let { Norms.incisos(it) } ?: ""
                        val part = Regex("^[°º]?\\s*§([a-z])§").find(list.substring(a.range.last + 1))?.let { parts[it.groupValues[1][0] - 'a'] } ?: ""
                        "$body, art. ${a.groupValues[1]}$suffix$part$incisos"
                    }.toList()
                }.flatten().distinct()
            }
        }
    }

    object Norms {
        private val ORDINALS = mapOf("primero" to 1, "segundo" to 2, "tercero" to 3, "cuarto" to 4, "quinto" to 5, "sexto" to 6, "septimo" to 7, "octavo" to 8, "noveno" to 9,
            "decimo" to 10, "undecimo" to 11, "duodecimo" to 12, "final" to -1)

        private val CODE = Regex("C[ÓO]DIGO\\s+(DE\\s+PROCEDIMIENTO\\s+CIVIL|DE\\s+PROCEDIMIENTO\\s+PENAL|PROCESAL\\s+PENAL|ORG[ÁA]NICO\\s+DE\\s+TRIBUNALES|DEL?\\s+TRABAJO|DE\\s+COMERCIO|DE\\s+AGUAS|DE\\s+MINER[ÍI]A|DE\\s+JUSTICIA\\s+MILITAR|DE\\s+DERECHO\\s+INTERNACIONAL\\s+PRIVADO|CIVIL|PENAL|TRIBUTARIO|SANITARIO|AERON[ÁA]UTICO)\\b", RegexOption.IGNORE_CASE)

        /** "LA LEY N° 18.695, ORGÁNICA CONSTITUCIONAL DE MUNICIPALIDADES" → "Ley N° 18.695"; "CÓDIGO DEL TRABAJO" → "Código del Trabajo". */
        fun body(raw: String): String? {
            val t = raw.replace(Regex("\\s+"), " ").trim()
            Regex("LEY\\s*N?[°º]?\\s*([\\d.]+)", RegexOption.IGNORE_CASE).find(t)?.let { return "Ley N° ${dotted(it.groupValues[1])}" }
            Regex("(?:D\\.?\\s?F\\.?\\s?L\\.?|DECRETO\\s+CON\\s+FUERZA\\s+DE\\s+LEY)\\s*N?[°º]?\\s*([\\d.]+)", RegexOption.IGNORE_CASE).find(t)?.let { return "DFL N° ${dotted(it.groupValues[1])}" }
            Regex("(?:D\\.?\\s?L\\.?|DECRETO\\s+LEY)\\s*N?[°º]?\\s*([\\d.]+)", RegexOption.IGNORE_CASE).find(t)?.let { return "DL N° ${dotted(it.groupValues[1])}" }
            // A known code's name ends where the name ends: the caption may run straight into the filer's name.
            CODE.find(t)?.let { return Names.titleCase("CÓDIGO " + it.groupValues[1].replace(Regex("\\s+"), " ").uppercase().replace("DE TRABAJO", "DEL TRABAJO")) }
            Regex("C[ÓO]DIGO\\s+(?:DE\\s+|DEL\\s+)?[A-ZÁÉÍÓÚÑ ]+?(?=[,;.(]|\\s+EN\\s|$)", RegexOption.IGNORE_CASE).find(t)?.let { return Names.titleCase(it.value.trim()) }
            if (Regex("CONSTITUCI[ÓO]N", RegexOption.IGNORE_CASE).containsMatchIn(t)) return "Constitución"
            Regex("LEY\\s+(?:ORG[ÁA]NICA\\s+CONSTITUCIONAL\\s+)?(?:DE\\s+|DEL\\s+)?[A-ZÁÉÍÓÚÑ ]{4,}?(?=[,;.(]|$)", RegexOption.IGNORE_CASE).find(t)?.let { return Names.titleCase(it.value.trim()) }
            return null
        }

        /** "OCTAVO" → " inc. 8"; "PRIMERO Y SEGUNDO" → " inc. 1 y 2". */
        fun incisos(raw: String): String {
            val n = Regex("[A-Za-zÁÉÍÓÚáéíóú]+").findAll(raw).mapNotNull { ORDINALS[fold(it.value)] }.filter { it > 0 }.toList()
            return if (n.isEmpty()) "" else " inc. " + n.joinToString(" y ")
        }

        fun dotted(digits: String): String {
            val d = digits.replace(".", "")
            return if (d.length <= 3) d else d.reversed().chunked(3).joinToString(".").reversed()
        }
    }

    object Names {
        private val PARTICLES = setOf("de", "del", "la", "las", "los", "y", "e", "en", "a", "el", "por", "para", "con")

        private val NOT_NAME_WORDS = setOf("que", "presente", "teniendo", "preceptuado", "articulo", "articulos", "certifico", "certifica", "presidenta", "presidente",
            "redacto", "redactor", "sentencia", "tribunal", "constitucional", "ministro", "ministra", "ministros", "secretario", "secretaria", "suplente",
            "abogado", "abogada", "integrado", "integrada", "pleno", "sala", "rol", "autoriza", "considerando", "vistos", "resuelve", "conforme", "acordada", "voto", "firman", "todas", "todos", "sus", "partes", "parte", "requerimiento", "recurso", "ley", "codigo")

        /**
         * Whether [s] reads as a person's name: two to seven words, no digits or punctuation such as ":" or "°", every word
         * but particles capitalised, and none of the words that show up when a signature block runs into the text around
         * it ("Y TENIENDO PRESENTE lo preceptuado", "CERTIFICO: Que Presidenta").
         */
        fun isPerson(s: String): Boolean {
            val t = s.trim()
            if (t.length > 70 || t.any { it.isDigit() || it in ":;°º()[]\"«»" }) return false
            val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.size !in 2..7) return false
            if (words.any { fold(it).trim('.', ',') in NOT_NAME_WORDS }) return false
            return words.all { w -> fold(w) in PARTICLES || w.first().isUpperCase() }
        }

        /** What a ruling says before naming its filer: "Y CONSIDERANDO: 1°. Que,", "Introducción A fojas 1,", "con fecha 13 de enero de 2025,". */
        private val FILER_LEAD = Regex("^[^,]{0,80}?(?:\\bQue\\s*,|\\bfojas\\s*\\d+(?:\\s*y\\s*\\d+)?\\s*,|\\bfecha\\s+[^,]{3,60}?(?:\\d{4}|\\bmil(?:\\s+(?!de\\b)\\p{L}+){0,2})\\s*,?|\\bSolicitud(?:\\s+de\\s+inaplicabilidad)?\\s*\\.)\\s*", RegexOption.IGNORE_CASE)
        private val FILER_TITLE = Regex("^(?:['\"‘’]\\s*)?(?:(?:el|la|los|las|don|doña|señor|señora|sr\\.|sra\\.|abogad[oa]|requirente)\\s+)+", RegexOption.IGNORE_CASE)
        private val FILER_TAIL = Regex("\\s*(?:,\\s*con\\s+fecha\\b|,?\\s*-?\\s*en\\s+adelante\\b|,\\s*por\\s+s[ií]\\b|,?\\s*(?:representad[oa]s?|en\\s+presentaci[óo]n)\\b).*$", RegexOption.IGNORE_CASE)

        /**
         * A filer's name as VISTOS gives it, without what comes before it ("Y CONSIDERANDO: 1°. Que, con fecha 13 de enero
         * de 2025, la Compañía Minera Manto de Oro" → "Compañía Minera Manto de Oro") or after it (", con fecha…", "-en
         * adelante…"). Null when nothing like a name is left.
         */
        fun filer(raw: String): String? {
            var s = raw.replace(Regex("\\s+"), " ").trim()
            while (true) { val m = FILER_LEAD.find(s) ?: break; s = s.substring(m.range.last + 1) }
            s = s.replace(FILER_TITLE, "").replace(FILER_TAIL, "").trim().trimEnd(',', ';', ' ')
            if (s.endsWith(".") && !Regex("\\b\\p{L}\\.$").containsMatchIn(s)) s = s.dropLast(1)  // "S.A." keeps its dot
            if (s.length < 3 || !s.first().isLetter() || !s.contains(' ') && s.length < 5) return null
            return if (s == s.uppercase()) titleCase(s) else s
        }

        /** "MARIO MEZA VÁSQUEZ" → "Mario Meza Vásquez"; keeps acronyms such as "S.A." and "ICH". */
        fun titleCase(upper: String): String = upper.trim().split(" ").filter { it.isNotEmpty() }.mapIndexed { i, w ->
            val lower = w.lowercase()
            when {
                w.contains('.') || (w.length <= 3 && w == w.uppercase() && w.any(Char::isLetter) && lower !in PARTICLES) -> w
                lower in PARTICLES && i > 0 -> lower
                else -> lower.replaceFirstChar { it.titlecase() }
            }
        }.joinToString(" ")

        /** "ROL N° 29-2023" → "Rol 29-2023"; "RIT O-29-2024, RUC 24-4-0609379-0" → "RIT O-29-2024". */
        fun caseId(raw: String): String {
            val t = raw.replace(Regex("\\s+"), " ")
            Regex("ROL\\s*N?[°º]?\\s*([\\w.-]+)", RegexOption.IGNORE_CASE).find(t)?.let { return "Rol ${it.groupValues[1]}" }
            Regex("RIT\\s*N?[°º]?\\s*([\\w.-]+)", RegexOption.IGNORE_CASE).find(t)?.let { return "RIT ${it.groupValues[1]}" }
            return t.substringBefore(",").trim()
        }
    }

    /** The signature page: "Pronunciada por ..., integrada por su Presidenta, Ministra señora X, y por sus Ministros señor Y, ...". */
    object Signature {
        private val BLOCK = Regex("integrad[ao]\\s+por\\s+(.{20,900}?)\\.\\s*(?:Autoriza|Firman|Se\\s+certifica|$)", RegexOption.DOT_MATCHES_ALL)
        private val TITLES = Regex("su\\s+President[ae]s?(?:\\s+Subrogante)?,?|(?:por\\s+)?(?:sus\\s+)?Ministr[oa]s?\\b|se[ñn]or(?:a|es|as)?\\b|\\bSr[a]?\\.|\\bdon\\b|\\bdoña\\b|\\bel\\b|\\bla\\b|\\(S\\)", RegexOption.IGNORE_CASE)
        val PRESIDES = Regex("President", RegexOption.IGNORE_CASE)
        private val DRAFTER = Regex("Redact[óo]\\s+la\\s+sentencia\\s+(?:el|la)\\s+Ministr[oa]\\s+se[ñn]ora?\\s+([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ ]{5,80}?)\\s*[.,]")
        val VOTE_AUTHOR = Regex("(?:La\\s+(?:disidencia|prevenci[óo]n)|El\\s+voto(?:\\s+\\w+)?)\\s+(?:corresponde|fue\\s+redactad[ao]\\s+por)\\s+(?:al|a\\s+la|por\\s+(?:el|la))\\s+Ministr[oa]\\s+se[ñn]ora?\\s+([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ ]{5,80}?)\\s*[.,]")

        fun ministers(text: String): List<String> {
            val m = BLOCK.findAll(text).lastOrNull() ?: return emptyList()
            val raw = TITLES.replace(m.groupValues[1].replace(Regex("\\s+"), " "), " ")
            // "y" before an i-sound is written "e" ("… Quesney e Iván …"); a piece left starting with "los"/"las" lost its
            // title only ("los Ministros señores Hernán …"): names never start with a lowercase word.
            return raw.split(Regex(",|\\s+[ye]\\s+")).map { it.trim(' ', '.', ',', ';').replace(Regex("^(?:los|las)\\s+"), "") }
                .filter { it.split(" ").size >= 2 && it.first().isUpperCase() }
                .map { it.replace(Regex("\\s+"), " ") }
                .filter(Names::isPerson)
        }

        /** The drafter, matched to a signature name when possible, and where the sentence is. */
        fun drafter(text: String, ministers: List<String>): Pair<String, Int>? {
            val m = DRAFTER.findAll(text).lastOrNull() ?: return null
            return match(m.groupValues[1], ministers) to m.range.first
        }

        /** The signature-page spelling of a name written in capitals elsewhere, or the name in title case. */
        fun match(raw: String, ministers: List<String>): String {
            val f = fold(raw).replace(Regex("\\s+"), " ").trim()
            return ministers.firstOrNull { fold(it) == f }
                ?: ministers.firstOrNull { surnamesOf(fold(it)) == surnamesOf(f) }
                ?: Names.titleCase(raw)
        }

        /** "Miguel Ángel Fernández González" → "Fernández González": the last two words, which is how rulings refer to a justice. */
        fun surnames(name: String) = name.split(" ").takeLast(2).joinToString(" ")
        private fun surnamesOf(folded: String) = folded.split(" ").takeLast(2).joinToString(" ")
    }

    /** Numbered reasoning paragraphs: the court's are ordinal words ("DECIMOCUARTO: Que"), a vote's are "14°. Que". */
    object Considerandos {
        class Considerando(val number: Int, val heading: String?, val start: Int, val text: String) {
            val opening: String get() = text.replace(Regex("\\s+"), " ").let { t -> t.removePrefix(t.substringBefore("Que").takeIf { it.length < 40 } ?: "").trim() }.take(260).let { if (it.length == 260) "$it…" else it }
        }

        // The mark's words sit on one line: across a line break, a heading above ("…CONSTITUCIONALIDAD") would join the mark.
        private val ORDINAL_MARK = Regex("(?m)^[ \\t]*((?:[A-ZÁÉÍÓÚ]{4,}[ \\t]?){1,3}):\\s*(?=Que\\b|QUE\\b)")
        private val NUMBER_MARK = Regex("(?m)^[ \\t]*(\\d{1,3})\\s*[°º]\\s*[.)]?\\s*[-–]?\\s*(?=Que\\b|QUE\\b)")
        private val HEADING = Regex("(?m)^[ \\t]*([IVX]{1,5})\\.\\s*-?\\s*([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ ,;:()'\\-]{3,}?)\\s*$")
        private val UNITS = mapOf("primero" to 1, "segundo" to 2, "tercero" to 3, "cuarto" to 4, "quinto" to 5, "sexto" to 6, "septimo" to 7, "octavo" to 8, "noveno" to 9)
        private val TENS = mapOf("decimo" to 10, "vigesimo" to 20, "trigesimo" to 30, "cuadragesimo" to 40, "quincuagesimo" to 50, "sexagesimo" to 60, "septuagesimo" to 70, "octogesimo" to 80, "nonagesimo" to 90, "centesimo" to 100)
        private val SPECIAL = mapOf("undecimo" to 11, "duodecimo" to 12)

        fun ordinal(words: String): Int? {
            val w = fold(words).replace(Regex("\\s+"), "")
            SPECIAL[w]?.let { return it }
            UNITS[w]?.let { return it }
            TENS[w]?.let { return it }
            for ((tens, tv) in TENS) if (w.startsWith(tens)) {
                val rest = w.removePrefix(tens)
                UNITS[rest]?.let { return tv + it }
                UNITS["o$rest"]?.let { return tv + it } // "DECIMOCTAVO": the two o's elide into one
                SPECIAL[rest]?.let { return tv + it }
            }
            return null
        }

        fun ofCourt(text: String, from: Int, to: Int): List<Considerando> {
            val region = text.substring(from, to)
            val headings = HEADING.findAll(region).map { it.range.first to "${it.groupValues[1]}. ${it.groupValues[2].replace(Regex("\\s+"), " ").trim()}" }.toList()
            val marks = ORDINAL_MARK.findAll(region).mapNotNull { m -> ordinal(m.groupValues[1])?.let { Triple(it, m.range.first, m.range.last + 1) } }.toList()
            return marks.mapIndexed { i, (n, start, bodyStart) ->
                val end = marks.getOrNull(i + 1)?.second ?: region.length
                val heading = headings.lastOrNull { it.first < start }?.second
                Considerando(n, heading, from + start, region.substring(bodyStart, end).let { strip(it, headings.filter { h -> h.first in bodyStart until end }.map { it.second }) })
            }
        }

        fun ofVote(voteText: String): List<Considerando> {
            val marks = NUMBER_MARK.findAll(voteText).map { Triple(it.groupValues[1].toInt(), it.range.first, it.range.last + 1) }.toList()
            return marks.mapIndexed { i, (n, start, bodyStart) ->
                val end = marks.getOrNull(i + 1)?.second ?: voteText.length
                Considerando(n, null, start, voteText.substring(bodyStart, end))
            }
        }

        /** Removes section headings that fall inside a considerando's text, so they don't read as its words. */
        private fun strip(text: String, headings: List<String>) = headings.fold(text) { t, h -> t.replace(h.substringAfter(". "), " ") }
    }

    /** "SE RESUELVE: I. QUE SE RECHAZA ... II. QUE SE ALZA ..." */
    object Decision {
        // A fojas stamp ("0000588 QUINIENTOS OCHENTA Y OCHO") may sit between a clause's number and its first word.
        private val CLAUSE = Regex("(?m)^[ \\t]*(?:[IVX]{1,5}|\\d{1,2})\\s*[.)\\-]\\s*(?:\\d{4,}\\s+)?(?:[A-ZÁÉÍÓÚÑ]+\\s+){0,7}?(?=QUE\\b|SE\\b|ÁLCESE\\b|OF[ÍI]CIESE\\b|HABI)")
        private val SINGLE = Regex("^\\s*SE\\s+(?:RESUELVE|DECLARA)\\s*:\\s*(?:\\d{4,}\\s+)?(?=QUE\\b|SE\\b)")

        fun clauses(section: String): List<Pair<String, Int>> {
            val marks = CLAUSE.findAll(section).toList()
            // A single decided point has no number: "SE RESUELVE: QUE, SE ACOGE EL REQUERIMIENTO…"
            if (marks.isEmpty()) return SINGLE.find(section)?.let { m -> listOf(section.substring(m.range.last + 1).replace(Regex("\\s+"), " ").trim() to m.range.last + 1) }.orEmpty().filter { it.first.length > 8 }
            return marks.mapIndexed { i, m ->
                val end = marks.getOrNull(i + 1)?.range?.first ?: section.length
                section.substring(m.range.last + 1, end).replace(Regex("\\s+"), " ").trim() to m.range.first
            }.filter { it.first.length > 8 }
        }

        fun concept(clause: String): String {
            val u = fold(clause)
            return when {
                "inadmisible" in u -> "Inadmisibilidad del requerimiento"
                Regex("se\\s+rechaza|desestima").containsMatchIn(u) -> "Rechazo del requerimiento"
                Regex("se\\s+acoge|acoge").containsMatchIn(u) -> "Acogimiento del requerimiento"
                "alza" in u && "suspension" in u -> "Alzamiento de la suspensión del procedimiento"
                "costas" in u -> "Costas"
                "constitucional" in u && "declara" in u -> "Declaración de constitucionalidad"
                else -> Names.titleCase(clause.take(60)).substringBefore(".")
            }
        }

        /**
         * The outcome in one word, from the decision section's own wording: Acogido, Acogido en parte, Rechazado,
         * Inadmisible, or for a preventive control Conforme, Conforme en parte, No conforme. Null when it doesn't say
         * it plainly: an outcome is never guessed.
         */
        fun outcome(section: String): String? {
            val u = fold(section).replace(Regex("\\s+"), " ")
            val upheld = Regex("\\bse\\s+acoge|\\bacogese\\b|(?<!\\bno )\\bha\\s+lugar\\b").containsMatchIn(u)
            val rejected = Regex("\\bse\\s+rechaza|\\brechazase\\b|\\bse\\s+desestima|\\bno\\s+ha\\s+lugar\\b").containsMatchIn(u)
            val partly = Regex("acoge\\s+parcialmente|\\ben\\s+lo\\s+demas\\b").containsMatchIn(u)
            val conform = Regex("(?<!\\bno )\\b(?:es|son)\\s+conformes?\\s+con\\s+la\\s+constitucion").containsMatchIn(u)
            val against = Regex("\\bno\\s+(?:es|son)\\s+conformes?\\s+con|\\b(?:es|son)\\s+contrari[oa]s?\\s+a\\s+la\\s+constitucion|\\b(?:es|son)\\s+inconstitucional").containsMatchIn(u)
            return when {
                upheld && (rejected || partly) -> "Acogido en parte"
                upheld -> "Acogido"
                rejected -> "Rechazado"
                "inadmisible" in u -> "Inadmisible"
                conform && against -> "Conforme en parte"
                against -> "No conforme"
                conform -> "Conforme"
                else -> null
            }
        }

        fun sentence(clause: String) = clause.lowercase().replaceFirstChar { it.titlecase() }.take(400)
    }

    /** Separate votes after the decision. */
    object Votes {
        class Found(val kind: String, val sense: String, val ministers: List<String>, val author: String?, val authorAt: Int, val start: Int, val text: String)

        private val OPENING = Regex("(?:voto\\s+en\\s+contra\\s+d\\w+|Se\\s+previene\\s+que\\s+|\\b(?:El|La|Los|Las)\\s+(?:President[ae]\\b[^,]{0,40},?\\s*)?(?:del\\s+Tribunal,?\\s*)?Ministr[oa]s?\\b)(.{3,500}?)(?:\\bquien(?:es)?\\s+)?\\b(estuv(?:o|ieron)|votaron|vot[oó]|previene[n]?|concurre[n]?)\\b(.{0,80})", RegexOption.DOT_MATCHES_ALL)
        private val COSTS = Regex("costas", RegexOption.IGNORE_CASE)

        fun of(section: String, ministers: List<String>): List<Found> {
            val headings = RulingSkeleton.VOTE_HEADING.findAll(section).map { it.range.first }.toList()
            val starts = (headings + 0).distinct().sorted()
            val found = mutableListOf<Found>()
            for ((i, start) in starts.withIndex()) {
                val end = starts.getOrNull(i + 1) ?: section.length
                val block = section.substring(start, end)
                val open = OPENING.find(block) ?: continue
                if (COSTS.containsMatchIn(block.substring(maxOf(0, open.range.first - 90), open.range.first))) continue
                val named = fold(open.groupValues[1])
                val who = ministers.filter { fold(Signature.surnames(it)) in named }
                if (who.isEmpty()) continue
                val kind = if (Regex("previen|concurr|prevenci", RegexOption.IGNORE_CASE).containsMatchIn(open.groupValues[2] + block.take(40))) "Prevención" else "Disidencia"
                val tail = fold(open.groupValues[3])
                val sense = when {
                    "acoger" in tail -> "por acoger el requerimiento"
                    "rechazar" in tail -> "por rechazar el requerimiento"
                    "inadmisib" in tail -> "por declarar inadmisible el requerimiento"
                    else -> ""
                }
                val author = Signature.VOTE_AUTHOR.find(section)?.let { Signature.match(it.groupValues[1], ministers) to it.range.first }
                val existing = found.indexOfFirst { it.kind == kind && it.ministers == who }
                if (existing >= 0) continue
                found += Found(kind, sense, who, author?.first, author?.second ?: 0, start, block)
            }
            return found
        }
    }

    /** Rulings cited in a stretch of text: "STC 1838, c. 10", "Rol N° 9.431", "STC Roles N° 244, 1413, 2682". */
    object Citations {
        private val ANCHOR = Regex("\\bSTC\\b|\\(Roles\\s+N|\\b(?:sentencias?|fallos?|causas?|pronunciamientos?|precedentes?)\\s+(?:de\\s+)?(?:(?:tales\\s+como\\s+)?(?:las?\\s+)?(?:de\\s+)?(?:los\\s+)?(?:esta\\s+Magistratura\\s+)?(?:este\\s+Tribunal\\s+)?)?[Rr]oles?\\b|\\b[Rr]ol(?:es)?\\s*N[°º]?s?\\s*(?=\\d)", RegexOption.IGNORE_CASE)
        private val OTHER_COURT = Regex("corte\\s+de\\s+apelaciones|juzgado|tribunal\\s+electoral|\\bRIT\\b|\\bRUC\\b|seguid[oa]s?\\s+ante|laboral|cobranza", RegexOption.IGNORE_CASE)
        private val REFERENCE = Regex("\\bcc?\\.\\s*\\d+\\s*°?(?:\\s*(?:a|y|-|–|,)\\s*\\d+\\s*°?(?!\\s*[-.]?\\d))*", RegexOption.IGNORE_CASE)
        private val YEAR = Regex("\\bde\\s+\\d{4}\\b", RegexOption.IGNORE_CASE)
        private val TOKEN = Regex("\\d{1,2}\\.\\d{3}(?:\\s*[-/]\\s*\\d{2,4})?(?:-[A-Z]{3,4})?(?!\\d)|\\d{6,}|[1-9]\\d{2,4}(?:\\s*[-/]\\s*\\d{2,4})?(?:-[A-Z]{3,4})?(?!\\d)|[1-9]\\d?\\s*-\\s*\\d{2,4}(?!\\d)|[A-Za-zÁÉÍÓÚÑáéíóúñ°º]+|\\d+|[,;.()]")
        private val ALLOWED = setOf("rol", "roles", "n", "ns", "nos", "no", "y", "e", "stc", "ina", "caa", "cpr", "cpt", "c", "cc", "a", "de", "s", "los", "las", "en", "el", "mismo", "sentido", "entre", "otras", "otros")

        /** (canonical ruling, offset) for each citation of this court's rulings found in [text]. */
        fun inText(text: String, court: Court, own: String): List<Pair<String, Int>> = scan(text, court, own, otherCourtsToo = false)

        /** Every role anywhere in the text, this court's or not: a broad net for the known-entity check. */
        fun allRoles(text: String, court: Court, own: String): Set<String> = scan(text, court, own, otherCourtsToo = true).map { it.first }.toSet()

        private fun scan(text: String, court: Court, own: String, otherCourtsToo: Boolean): List<Pair<String, Int>> {
            val out = mutableListOf<Pair<String, Int>>()
            for (m in ANCHOR.findAll(text)) {
                val window = text.substring(m.range.last + 1 - (if (m.value.endsWith("N")) 1 else 0), minOf(text.length, m.range.last + 1 + 600))
                if (!otherCourtsToo) {
                    // A role before another court's name is the pending case's, not a ruling of this court.
                    if (OTHER_COURT.containsMatchIn(text.substring(maxOf(0, m.range.first - 120), m.range.first))) continue
                    if (OTHER_COURT.containsMatchIn(window.take(80))) continue
                }
                for (raw in numbers(window)) {
                    val name = LegalFacts.canonicalRuling("Rol $raw", court)
                    if (name != own && LegalFacts.rulingKey(name, court) != null) out += name to m.range.first
                }
            }
            return out.distinct()
        }

        private fun numbers(window: String): List<String> {
            val cleaned = YEAR.replace(REFERENCE.replace(window, " "), " ")
            val out = mutableListOf<String>()
            for (t in TOKEN.findAll(cleaned).map { it.value }) {
                when {
                    t[0].isDigit() -> {
                        if (t.length >= 6 && t.all(Char::isDigit)) break // a page mark
                        if (t.all(Char::isDigit) && t.length < 3) continue
                        out += t.replace(Regex("\\s+"), "")
                    }
                    t == ")" -> break
                    t[0].isLetter() || t[0] == '°' || t[0] == 'º' -> { if (fold(t).replace("°", "").replace("º", "").trim() !in ALLOWED) break }
                    t == "." && out.isNotEmpty() -> break
                }
            }
            return out
        }
    }
}
