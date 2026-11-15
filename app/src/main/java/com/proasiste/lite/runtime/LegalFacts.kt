package com.proasiste.lite.runtime

import com.proasiste.lite.graph.NewFact
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Legal mode for graph building: prompts, schema and checks for a Chilean court ruling.
 *
 * Two passes over the whole ruling: the first records structure (parties, provisions, citations, who decided), the
 * second the content (each part of the decision, dates, procedure, routes of appeal, the court's reasoning), reusing
 * the first pass's node names. Types and relations are fixed by the schema, and naming rules make each ruling,
 * provision or person one node. [Checker] then sorts every fact into kept, held for review, or dropped.
 */
object LegalFacts {
    /** Bumped whenever a prompt or a check changes, so facts and review decisions can be read against it. */
    const val PROMPT_VERSION = "legal-2"
    val TYPES = listOf("Fallo", "Norma", "Tribunal", "Ministro", "Parte", "Abogado", "Gestion", "Concepto", "Voto", "Considerando")
    val RELATIONS = linkedMapOf(
        "RESUELVE" to "Fallo -> Gestion, Norma o Concepto: cada cosa que el fallo decide (acoge, rechaza, declara inadmisible, alza suspensión, costas).",
        "IMPUGNA" to "Parte -> Norma: la norma cuya aplicación la parte impugna.",
        "INVOCA_VULNERACION" to "Parte -> Norma: norma constitucional o tratado que la parte dice vulnerado.",
        "INVOCA_PRECEDENTE" to "Parte -> Fallo: fallo que una parte cita en su favor.",
        "CITA_PRECEDENTE" to "Fallo -> Fallo: fallo anterior que el propio Tribunal cita, sigue o en el que se apoya.",
        "FUNDAMENTA_EN" to "Fallo -> Norma: norma en la que el Tribunal apoya su razonamiento o su decisión.",
        "CALIFICA" to "Fallo -> Norma o Concepto: cómo el Tribunal caracteriza una norma o institución (por ejemplo, que no es sanción).",
        "RAZONA" to "Fallo -> Concepto: un argumento central del Tribunal para decidir.",
        "DISPONE" to "Norma -> Concepto: lo que una norma establece.",
        "INCORPORADA_POR" to "Norma -> Norma: ley que introdujo o modificó la norma.",
        "CONTROLA" to "Fallo -> Norma: control preventivo o represivo de una norma en ese fallo.",
        "REQUIERE_EN" to "Parte -> Gestion: la parte requirente y la gestión pendiente en que incide el requerimiento.",
        "SOLICITA" to "Parte -> Concepto: lo que una parte pide expresamente (en la gestión o ante el Tribunal).",
        "SEGUIDA_ANTE" to "Gestion -> Tribunal: tribunal donde se sigue la gestión.",
        "IMPUGNABLE_ANTE" to "Gestion -> Tribunal: tribunal ante el cual puede recurrirse lo que se resuelva en la gestión.",
        "DEDUCIDA_POR" to "Gestion -> Parte: quien inició la gestión.",
        "DIRIGIDA_CONTRA" to "Gestion -> Parte: contra quien se dirige la gestión.",
        "INTEGRA" to "Ministro -> Fallo: ministro que integró la sala o el pleno.",
        "REDACTA" to "Ministro -> Fallo: ministro redactor de la sentencia.",
        "DISIENTE" to "Ministro -> Fallo: ministro que vota en contra (disidencia).",
        "PREVIENE" to "Ministro -> Fallo: ministro que concurre con prevención.",
        "ALEGA" to "Abogado -> Parte: abogado que alegó por una parte.",
        "TRAMITA" to "Tribunal -> Fallo: un paso de tramitación con su fecha y fojas (admisión a trámite, admisibilidad, autos en relación, vista de la causa, acuerdo, sentencia).",
    )

    private val FALLO = setOf("Fallo")
    /** The court's view or a separate vote's: reasoning, citations and characterisations can come from either. */
    private val FALLO_O_VOTO = setOf("Fallo", "Voto")
    private val NORMA_TYPE = setOf("Norma")
    private val PARTE = setOf("Parte")
    private val CONCEPTO = setOf("Concepto")
    private val MINISTRO = setOf("Ministro")
    private val GESTION = setOf("Gestion")
    private val TRIBUNAL = setOf("Tribunal")

    /** The direction each relation must run, as described in [RELATIONS]: the types allowed at its start and end. */
    val DIRECTIONS: Map<String, Pair<Set<String>, Set<String>>> = mapOf(
        "RESUELVE" to (FALLO to setOf("Gestion", "Norma", "Concepto")),
        "IMPUGNA" to (PARTE to NORMA_TYPE),
        "INVOCA_VULNERACION" to (PARTE to NORMA_TYPE),
        "INVOCA_PRECEDENTE" to (PARTE to FALLO),
        "CITA_PRECEDENTE" to (FALLO_O_VOTO to FALLO),
        "FUNDAMENTA_EN" to (FALLO_O_VOTO to NORMA_TYPE),
        "CALIFICA" to (FALLO_O_VOTO to setOf("Norma", "Concepto")),
        "RAZONA" to (FALLO_O_VOTO to CONCEPTO),
        "DISPONE" to (NORMA_TYPE to CONCEPTO),
        "INCORPORADA_POR" to (NORMA_TYPE to NORMA_TYPE),
        "CONTROLA" to (FALLO to NORMA_TYPE),
        "REQUIERE_EN" to (PARTE to GESTION),
        "SOLICITA" to (PARTE to CONCEPTO),
        "SEGUIDA_ANTE" to (GESTION to TRIBUNAL),
        "IMPUGNABLE_ANTE" to (GESTION to TRIBUNAL),
        "DEDUCIDA_POR" to (GESTION to PARTE),
        "DIRIGIDA_CONTRA" to (GESTION to PARTE),
        "INTEGRA" to (MINISTRO to FALLO),
        "REDACTA" to (MINISTRO to FALLO_O_VOTO),
        "DISIENTE" to (MINISTRO to FALLO),
        "PREVIENE" to (MINISTRO to FALLO),
        "ALEGA" to (setOf("Abogado") to PARTE),
        "TRAMITA" to (TRIBUNAL to FALLO),
    )

    /** Relations only the rule-based skeleton writes; never offered to the model. */
    val SKELETON_DIRECTIONS: Map<String, Pair<Set<String>, Set<String>>> = mapOf(
        "DICTA" to (TRIBUNAL to FALLO),
        "TIPO" to (FALLO to CONCEPTO),
        "CONSIDERA" to (FALLO_O_VOTO to setOf("Considerando")),
        "CITA" to (setOf("Considerando") to FALLO),
        "VOTO_SEPARADO" to (FALLO to setOf("Voto")),
        "SUSCRIBE" to (MINISTRO to setOf("Voto")),
    )

    /** True when the fact runs the way its relation says and doesn't point at itself. */
    fun runsTheRightWay(f: NewFact): Boolean {
        val (from, to) = DIRECTIONS[f.relation] ?: SKELETON_DIRECTIONS[f.relation] ?: return false
        return f.subjectType in from && f.objectType in to && !(f.subjectType == f.objectType && f.subject == f.obj)
    }

    /**
     * The courts whose rulings the app knows how to name. [yearly] courts restart their role numbers every year, so
     * the year is part of a ruling's identity there; the Tribunal Constitucional numbers rulings in one sequence.
     */
    enum class Court(val code: String, val prefix: String, val tribunal: String, val yearly: Boolean, pattern: String) {
        // A TC ruling may name itself only as "esta Magistratura Constitucional" on its first page, where it also names the
        // court of the pending case ("Corte de Apelaciones"): the TC's own words must win.
        TC("TC", "STC Rol", "Tribunal Constitucional", false, "tribunal\\s+constitucional|\\bSTC\\b|magistratura\\s+constitucional|inaplicabilidad\\s+por\\s+inconstitucionalidad"),
        CS("CS", "SCS Rol", "Corte Suprema", true, "corte\\s+suprema|\\bSCS\\b"),
        CA("CA", "Corte de Apelaciones Rol", "Corte de Apelaciones", true, "corte\\s+de\\s+apelaciones"),
        TER("TER", "Tribunal Electoral Regional Rol", "Tribunal Electoral Regional", true, "tribunal\\s+electoral\\s+regional"),
        TRICEL("TRICEL", "TRICEL Rol", "Tribunal Calificador de Elecciones", true, "tribunal\\s+calificador|\\bTRICEL\\b"),
        OTRO("OTRO", "Rol", "Tribunal", true, "juzgado|tribunal");

        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
    }

    /**
     * The sentence on a page that best supports a fact: the one sharing the most words (four letters or longer, or
     * a number) with the statement and the two names. Shown under a kept fact so the steward can judge it without
     * opening the PDF. Null when no sentence shares at least two such words: the fact has nothing standing behind
     * it on that page, which is itself worth seeing.
     */
    fun evidence(pageText: String, f: NewFact): String? {
        fun terms(s: String) = Regex("[\\p{L}\\p{N}.]+").findAll(fold(s).lowercase()).map { it.value.trim('.') }.filter { it.length >= 4 || it.any(Char::isDigit) }.toSet()
        val wanted = terms(f.statement) + terms(f.subject) + terms(f.obj)
        if (wanted.isEmpty()) return null
        val sentences = fold(pageText).replace(Regex("\\s+"), " ").split(Regex("(?<=[.;:])\\s+(?=[A-ZÁÉÍÓÚÑ¿¡\"“(0-9])")).map { it.trim() }.filter { it.length >= 20 }
        val best = sentences.maxByOrNull { s -> terms(s).count { it in wanted } } ?: return null
        if (terms(best).count { it in wanted } < 2) return null
        return if (best.length <= 320) best else best.take(317).trimEnd() + "…"
    }

    /** The court that issued a document, from its first page: the first court named, taken in the order above. */
    fun courtOf(firstPage: String): Court? = Court.entries.firstOrNull { it.regex.containsMatchIn(firstPage) }

    /** A first page that names a Chilean court and a role number: legal mode applies. */
    fun isRuling(pages: List<DeepSeekFacts.PageText>): Boolean {
        val first = pages.firstOrNull()?.text.orEmpty()
        return courtOf(first) != null && ROLE.containsMatchIn(first)
    }

    /** The ruling's own node, in canonical form ("STC Rol 17.292"), from the court and role number on its first page. */
    fun thisRuling(pages: List<DeepSeekFacts.PageText>, title: String): String = identity(pages, title).name

    /** A ruling's own name and where it was read: [FROM_TITLE], [FROM_HEADING], [FROM_CLOSING], [FROM_TEXT], or [FROM_NONE]. */
    data class Identity(val name: String, val from: String, val titleNumber: String?)
    const val FROM_TITLE = "nombre del archivo"; const val FROM_HEADING = "encabezado"; const val FROM_CLOSING = "cierre"
    const val FROM_TEXT = "texto"; const val FROM_NONE = "sin número"

    /**
     * The ruling's own number. A file named "STC 1810-10 …" gives it. Otherwise the ruling states it in its heading
     * ("Sentencia Rol 17.292-26 INA", first lines of page 1) or its closing line ("Rol N° 16.231-25-INA", before the
     * signatures); a number only in the reasoning is usually a ruling it cites, and the first one on page 1 is often the
     * pending case's ("Rol N° 3868-2010, seguido ante la Corte"). Where the file's own name holds a number ("16231_
     * Inadmisible….pdf") and the text names that number as a role anywhere, that number wins; failing that the file
     * name's number all the same (the collection was filed by case number), then the closing line, then the heading,
     * and only then the first role in the text.
     */
    fun identity(pages: List<DeepSeekFacts.PageText>, title: String): Identity {
        val first = pages.firstOrNull()?.text.orEmpty()
        val court = courtOf(first) ?: Court.TC
        TITLE_ROLE.find(title)?.let { m -> return Identity(canonicalRuling(m.value, if (m.groupValues[1].uppercase() == "SCS") Court.CS else Court.TC), FROM_TITLE, null) }
        fun number(role: String) = ROLE_PARTS.find(role)?.groupValues?.get(1)?.replace(".", "")
        // A Tribunal case number: no leading zero, and below 25,000 ("091024" in a bulletin's name is a date).
        val titleNumbers = com.proasiste.lite.Store.titleNumbers(title).filter { it.length in 3..6 && !it.startsWith("0") && it.toInt() <= 25_000 }
        val heading = ROLE.find(first.take(HEADING_CHARS))?.value
        val tail = pages.takeLast(2).joinToString("\n") { it.text }
        val closing = ROLE.findAll(tail).lastOrNull()?.value
        val inText = pages.flatMap { p -> ROLE.findAll(p.text).map { it.value }.toList() }
        val fromTitle = titleNumbers.firstOrNull { n -> inText.any { number(it) == n } }
        val titleNumber = titleNumbers.firstOrNull()
        return when {
            fromTitle != null -> Identity(canonicalRuling(inText.first { number(it) == fromTitle }, court), when (fromTitle) { closing?.let(::number) -> FROM_CLOSING; heading?.let(::number) -> FROM_HEADING; else -> FROM_TITLE }, titleNumber)
            // A file the collection named by its case number is trusted over a number the text may only cite.
            titleNumber != null -> Identity(canonicalRuling("Rol $titleNumber", court), FROM_TITLE, titleNumber)
            closing != null -> Identity(canonicalRuling(closing, court), FROM_CLOSING, titleNumber)
            heading != null -> Identity(canonicalRuling(heading, court), FROM_HEADING, titleNumber)
            inText.isNotEmpty() -> Identity(canonicalRuling(inText.first(), court), FROM_TEXT, null)
            else -> Identity(title, FROM_NONE, null)
        }
    }
    private const val HEADING_CHARS = 400
    private val TITLE_ROLE = Regex("\\b(STC|SCS)\\s+(?:Rol\\s*)?(?:N[°º]\\s*)?\\d[\\d.]*(?:-\\d{2,4})?", RegexOption.IGNORE_CASE)

    /**
     * A ruling's identity: `TC:17292` for the Tribunal Constitucional, whose numbers never repeat, and `CS:1234-2020`
     * for courts that restart numbering each year. [court] is the one the text gives ("SCS", "Corte Suprema") or,
     * failing that, [default], normally the court of the document being read. Null when [name] holds no role number.
     */
    fun rulingKey(name: String, default: Court): String? {
        val m = ROLE_PARTS.find(name) ?: return null
        val court = Court.entries.firstOrNull { it != Court.OTRO && it.regex.containsMatchIn(name) } ?: default
        val number = m.groupValues[1].replace(".", "")
        val year = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { if (it.length == 2) "20$it" else it }
        return if (court.yearly && year != null) "${court.code}:$number-$year" else "${court.code}:$number"
    }

    /** The one name a ruling has in the graph: "STC Rol 9.431", or "SCS Rol 1.234-2020" where the year counts. */
    fun canonicalRuling(name: String, default: Court): String {
        val key = rulingKey(name, default) ?: return name
        val court = Court.entries.first { it.code == key.substringBefore(':') }
        val rest = key.substringAfter(':')
        val number = rest.substringBefore('-').reversed().chunked(3).joinToString(".").reversed()
        return if (rest.contains('-')) "${court.prefix} $number-${rest.substringAfter('-')}" else "${court.prefix} $number"
    }

    private val ROLE = Regex("Rol\\s*N?°?\\s*([\\d.]+(?:-\\d+)?)", RegexOption.IGNORE_CASE)
    /** The number and the optional year in a role, however it is written: "17.292-26-INA", "9.431", "1234-2020". */
    // The dotted form first, else all the digits: "10044" must not be read as "100".
    private val ROLE_PARTS = Regex("(\\d{1,3}(?:\\.\\d{3})+|\\d+)(?:-(\\d{2}|\\d{4}))?(?![\\d.])")

    private fun common(thisRuling: String) = """Tipos de entidad (usa solo estos): ${TYPES.joinToString(", ")}.
Cada hecho va de "from" a "to". Relaciones (usa solo estas, con el sentido indicado; el tipo a la izquierda de -> es el de from y el de la derecha es el de to):
${RELATIONS.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }}

Nombres (cada entidad debe tener siempre exactamente el mismo nombre):
- Fallo: "STC Rol " seguido del número de rol sin el año ni el sufijo, con puntos de miles, por ejemplo "STC Rol 17.292" o "STC Rol 244" (el Tribunal Constitucional numera sus fallos en una sola serie). Un fallo de otro tribunal lleva su tribunal y el año: "SCS Rol 1.234-2020". El fallo que estás leyendo es "$thisRuling".
- Norma: "<cuerpo>, art. <número>" más inciso, número o letra si se indican. Cuerpos: "Constitución", "Ley N° <número>", "CADH" (Convención Americana sobre Derechos Humanos), "PIDCP" (Pacto Internacional de Derechos Civiles y Políticos). Ejemplos: "Ley N° 18.695, art. 60 inc. 8", "Constitución, art. 19 N° 3 inc. 6", "CADH, art. 8". Un nodo por artículo citado. Una ley completa: "Ley N° 19.737". El cuerpo es el que el texto indica para ese artículo: lee con cuidado a qué cuerpo pertenece cada artículo.
- Ministro, Parte y Abogado: el nombre completo más largo que aparezca en el fallo, sin títulos (sin "señor", "Ministra", "don"). Una sola persona por nombre: si son varias, un hecho para cada una.
- Tribunal: nombre oficial sin tratamientos ("Tribunal Electoral Regional del Maule", "Tribunal Calificador de Elecciones", "Tribunal Constitucional"). Nunca uses "STC" para el Tribunal: STC es un fallo.
- Gestion: "Rol <número> ante <tribunal>", por ejemplo "Rol 29-2023 ante Tribunal Electoral Regional del Maule".
- Concepto: una frase nominal breve y precisa, por ejemplo "Suspensión del alcalde (Ley N° 18.695, art. 60 inc. 8)".
- Voto: una disidencia o prevención ya registrada, por ejemplo "Disidencia de Mery Romero en STC Rol 17.221". Lo que razona una disidencia va con el Voto como "from", nunca con el Fallo: el fallo es la opinión de la mayoría.

Reglas:
- Usa solo lo que dice el fallo. Nada de conocimiento externo, suposiciones ni inferencias: si el fallo no dice que una parte pidió algo, no lo afirmes.
- Copia exactamente fechas, números de rol, artículos, fojas y nombres.
- "to" es la entidad concreta de la que trata el hecho: la norma impugnada, el fallo citado, el tribunal. El fallo que lees solo va en "to" en INTEGRA, REDACTA, DISIENTE, PREVIENE y TRAMITA.
- page es el número de página donde el texto dice lo que afirma el hecho, según las marcas de página.
- No registres citas de doctrina (autores y libros), ni hechos triviales como que el fallo se identifica por su rol.
Devuelve solo JSON."""

    fun structurePrompt(thisRuling: String, known: List<Pair<String, String>> = emptyList()) = """Conviertes un fallo judicial chileno en hechos para un grafo de conocimiento.
Cada hecho une dos entidades con una relación y lo explica en una oración autosuficiente, en español.
En esta pasada registra la estructura: partes, gestión pendiente, normas impugnadas e invocadas, cada fallo citado
(indicando si lo cita una parte, INVOCA_PRECEDENTE, o el propio Tribunal, CITA_PRECEDENTE), normas en que se funda
el Tribunal, cada ministro que integró y quién redactó, disintió o previno.
${knownBlock(known)}
""" + common(thisRuling)

    /** Entities the rules already found, so the model reuses their exact names and types. */
    private fun knownBlock(known: List<Pair<String, String>>) = if (known.isEmpty()) "" else
        "\nEstas entidades ya están registradas con estos nombres y tipos exactos; úsalos tal cual cuando hables de ellas:\n" +
            known.joinToString("\n") { "- ${it.first}: ${it.second}" } + "\n"

    fun contentPrompt(thisRuling: String, nodes: List<Pair<String, String>>) = """Conviertes un fallo judicial chileno en hechos para un grafo de conocimiento.
Cada hecho une dos entidades con una relación y lo explica en una oración autosuficiente, en español.
Una primera pasada ya registró la estructura. Estas son sus entidades; reutiliza exactamente estos nombres y tipos
cuando hables de ellas:
${nodes.joinToString("\n") { "- ${it.first}: ${it.second}" }}

En esta pasada registra solo lo que falta:
- Cada punto de lo resuelto, por separado (por ejemplo: rechazo o acogida, alzamiento de la suspensión, costas), con RESUELVE.
- Cada paso de tramitación con su fecha y fojas (TRAMITA), y la fecha de la sentencia.
- Las fechas del requerimiento y de la gestión, lo que las partes pidieron expresamente (SOLICITA) y ante quién se puede recurrir (IMPUGNABLE_ANTE).
- Los argumentos centrales del Tribunal para decidir (RAZONA, CALIFICA), cada uno en un hecho.
- Si hay disidencias o prevenciones, registra también sus argumentos centrales (RAZONA, CALIFICA) y los fallos que citan (CITA_PRECEDENTE), con el Voto ya registrado como "from", nunca con el Fallo.
No repitas hechos de la primera pasada.

""" + common(thisRuling)

    val SCHEMA: JSONObject get() = JSONObject().put("type", "object").put("additionalProperties", false)
        .put("required", JSONArray().put("facts"))
        .put("properties", JSONObject().put("facts", JSONObject().put("type", "array").put("items",
            JSONObject().put("type", "object").put("additionalProperties", false)
                // Named so that a provider sorting the keys still has the model write the link's start before its end.
                .put("required", JSONArray(listOf("from", "from_type", "page", "relation", "statement", "to", "to_type")))
                .put("properties", JSONObject()
                    .put("page", JSONObject().put("type", "integer"))
                    .put("from_type", JSONObject().put("type", "string").put("enum", JSONArray(TYPES)))
                    .put("from", JSONObject().put("type", "string"))
                    .put("relation", JSONObject().put("type", "string").put("enum", JSONArray(RELATIONS.keys.toList())))
                    .put("to_type", JSONObject().put("type", "string").put("enum", JSONArray(TYPES)))
                    .put("to", JSONObject().put("type", "string"))
                    .put("statement", JSONObject().put("type", "string"))))))

    enum class Verdict { KEPT, REVIEW, DROPPED }

    /**
     * Checks facts against the ruling's text:
     * - every number in a statement or node name must appear somewhere in the ruling, or the fact is dropped;
     * - numbers in the statement that aren't part of a node name must be on the cited page;
     * - node names must be on the cited page too, except the ruling itself and its pending case (every page is
     *   about them); a ruling needs only its main role number there;
     * - a Norma named "<body>, art. N" must match the text: on that page, the body named for "artículo N" is that body.
     * - it must run the way its relation says ([runsTheRightWay]).
     * Failing any of the last four holds the fact for review instead of keeping it.
     */
    class Checker(pages: List<DeepSeekFacts.PageText>, private val thisRuling: String) {
        private val pageText = pages.associate { it.page to fold(it.text) }
        private val aliases: Map<String, String>
        private val bodies: List<Pair<Regex, (MatchResult) -> String>>
        private val pageNumbers: Map<Int, Set<String>>
        private val documentNumbers: Set<String>

        init {
            val whole = pageText.values.joinToString(" ")
            aliases = ALIAS.findAll(whole).associate { it.groupValues[2].replace(Regex("\\s+"), " ").trim() to it.groupValues[1] }
            bodies = BASE_BODIES + aliases.map { (name, number) -> Regex("Ley\\s+" + words(name), RegexOption.IGNORE_CASE) to { _: MatchResult -> "Ley N° $number" } }
            pageNumbers = pageText.mapValues { (_, text) ->
                withYears(numbers(text)) + aliases.filter { (name, _) -> Regex("Ley\\s+" + words(name), RegexOption.IGNORE_CASE).containsMatchIn(text) }.values
            }
            documentNumbers = pageNumbers.values.flatten().toSet()
        }

        fun check(f: NewFact, subjectType: String = f.subjectType, objectType: String = f.objectType): Verdict = checkWithReason(f, subjectType, objectType).first

        /** Why a fact was held back or dropped, for the log and, later, the review screen. */
        fun reason(f: NewFact): String = checkWithReason(f).second

        fun checkWithReason(f: NewFact, subjectType: String = f.subjectType, objectType: String = f.objectType): Pair<Verdict, String> {
            val onPage = pageNumbers[f.page] ?: return Verdict.DROPPED to "page ${f.page} is not in the ruling"
            if (!f.complete()) return Verdict.DROPPED to "incomplete fact"
            val stated = numbers(withoutRoleYears(f.statement))
            val inNames = numbers(withoutRoleYears(f.subject)) + numbers(withoutRoleYears(f.obj))
            // A ruling's number is checked as digits below; other numbers in names and statements must be in the ruling.
            val rulingNumbers = listOf(subjectType to f.subject, objectType to f.obj).filter { it.first == "Fallo" }.flatMap { numbers(it.second) }.toSet()
            val nowhere = (stated + inNames - rulingNumbers) - documentNumbers
            if (nowhere.isNotEmpty()) return Verdict.DROPPED to "numbers found nowhere in the ruling: $nowhere"
            if (!runsTheRightWay(f)) return Verdict.REVIEW to "relation ${f.relation} doesn't run $subjectType -> $objectType"
            val notOnPage = (stated - inNames) - onPage
            if (notOnPage.isNotEmpty()) return Verdict.REVIEW to "numbers not on page ${f.page}: $notOnPage"
            for ((type, name) in listOf(subjectType to f.subject, objectType to f.obj)) {
                // The ruling, its pending case and its votes are what every page is about; a Norma is checked by
                // normaMatches, since incisos are written as words; a Concepto is the model's own label.
                if (name == thisRuling || type in NAMES_NOT_NUMBER_CHECKED) continue
                if (type == "Fallo") {
                    val number = rulingKey(name, Court.TC)?.substringAfter(':')?.substringBefore('-') ?: continue
                    if (!Regex("(?<!\\d)$number(?!\\d)").containsMatchIn(pageText.getValue(f.page).replace(".", ""))) return Verdict.REVIEW to "'$name' is not on page ${f.page}"
                    continue
                }
                if (!onPage.containsAll(numbers(name))) return Verdict.REVIEW to "'$name' has numbers not on page ${f.page}"
            }
            for ((type, name) in listOf(subjectType to f.subject, objectType to f.obj)) {
                if (type == "Norma" && !normaMatches(name, f.page)) return Verdict.REVIEW to "'$name': the text gives this article another body, or it isn't on page ${f.page}"
            }
            return Verdict.KEPT to ""
        }

        private fun normaMatches(name: String, page: Int): Boolean {
            val m = NORMA.find(name) ?: return true // A whole law or another kind of name: the number checks cover it.
            val (body, article) = m.destructured
            val text = pageText[page] ?: return false
            // "artículo 8.2 letra h)" still names article 8, so a following ".digit" is allowed; "18.695" is not article 8.
            val places = Regex("(?<![\\d.])${Regex.escape(article)}(?!\\d)(?!\\.\\d{3})").findAll(text)
                .filter { Regex("art[íi.]", RegexOption.IGNORE_CASE).containsMatchIn(text.substring(maxOf(0, it.range.first - 80), it.range.first)) }
                .map { it.range.last + 1 }
            return places.any { bodyOf(text, it) == body }
        }

        /** The body an article mentioned just before [at] belongs to: the first named after it, or the last named before
         * it when the text says "del mismo cuerpo" or names none after. */
        private fun bodyOf(text: String, at: Int): String? {
            val after = bodiesIn(text.substring(at, minOf(text.length, at + 300)))
            val before = bodiesIn(text.substring(maxOf(0, at - 600), at))
            return if (SAME_BODY.containsMatchIn(text.substring(at, minOf(text.length, at + 120))) || after.isEmpty()) before.lastOrNull()?.second
            else after.first().second
        }

        private fun bodiesIn(text: String) =
            bodies.flatMap { (pattern, make) -> pattern.findAll(text).map { it.range.first to make(it) }.toList() }.sortedBy { it.first }
    }

    /** Relations where a link is one fact however it's worded: who integrated, who cited what. */
    private val ONE_PER_LINK = setOf(
        "INTEGRA", "REDACTA", "DISIENTE", "PREVIENE", "ALEGA", "IMPUGNA", "INVOCA_VULNERACION", "INVOCA_PRECEDENTE",
        "CITA_PRECEDENTE", "FUNDAMENTA_EN", "SEGUIDA_ANTE", "IMPUGNABLE_ANTE", "DEDUCIDA_POR", "DIRIGIDA_CONTRA",
    )

    /**
     * A second pass often repeats the first in other words. One fact per link for [ONE_PER_LINK]; per link, page and
     * numbers for the rest, so each dated step stays; and reasoning is kept whole, since several distinct arguments
     * can link the same two nodes.
     */
    fun dedupe(facts: List<NewFact>): List<NewFact> = facts.distinctBy {
        when (it.relation) {
            "RAZONA", "CALIFICA" -> listOf(it.subject, it.relation, it.obj, it.statement)
            in ONE_PER_LINK -> listOf(it.subject, it.relation, it.obj)
            else -> listOf(it.subject, it.relation, it.obj, it.page, numbers(it.statement))
        }
    }

    /** Gives every ruling its one canonical name, so "Rol 9.431", "STC Rol 9.431-20-INA" and "9431" are one node. */
    fun mergeRulings(facts: List<NewFact>, court: Court): List<NewFact> {
        fun canonical(type: String, name: String) = if (type == "Fallo") canonicalRuling(name, court) else name
        return facts.map { it.copy(subject = canonical(it.subjectType, it.subject), obj = canonical(it.objectType, it.obj)) }
    }

    fun numbers(text: String) = DeepSeekFacts.numbers(text)

    private val NAMES_NOT_NUMBER_CHECKED = setOf("Gestion", "Norma", "Voto", "Concepto")
    private val ROLE_YEAR = Regex("(\\d{1,3}(?:\\.\\d{3})+|\\d{3,6})-(?:\\d{2}|\\d{4})(?:-[A-Z]{2,4})?")

    /** "Rol 17.292-26-INA" → "Rol 17.292": a role's year and suffix are not facts to check against a page. */
    fun withoutRoleYears(text: String) = ROLE_YEAR.replace(text) { it.groupValues[1] }

    /** A two-digit year in a role ("9.431-20") also stands for its four-digit form ("2020"). */
    fun withYears(found: Set<String>): Set<String> = found + found.filter { it.length == 2 }.flatMap { listOf("19$it", "20$it") }

    private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
    private fun words(name: String) = name.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("\\s+") { Regex.escape(it) }

    private val ALIAS = Regex("Ley\\s*N°\\s*([\\d.]+),?\\s*(Org[áa]nica\\s+Constitucional\\s+de(?:\\s+[A-ZÁÉÍÓÚ][\\wáéíóúñ]+|\\s+del?|\\s+la|\\s+los)+)")
    private val NORMA = Regex("^(Constitución|CADH|PIDCP|Ley N° [\\d.]+), art\\. (\\d+)")
    private val SAME_BODY = Regex("^\\W*(?:\\w+\\W+){0,6}?(?:del\\s+mismo\\s+cuerpo|de\\s+la\\s+misma)", RegexOption.IGNORE_CASE)
    private val BASE_BODIES: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("Constituci[óo]n|Carta\\s+Fundamental", RegexOption.IGNORE_CASE) to { _ -> "Constitución" },
        Regex("Convenci[óo]n\\s+Americana", RegexOption.IGNORE_CASE) to { _ -> "CADH" },
        // "Pacto" alone too: a page break can split "Pacto | Internacional".
        Regex("\\bPacto\\b", RegexOption.IGNORE_CASE) to { _ -> "PIDCP" },
        Regex("Ley\\s*N°\\s*([\\d.]+)", RegexOption.IGNORE_CASE) to { m -> "Ley N° ${m.groupValues[1]}" },
    )
}
