package com.proasiste.lite.runtime

import com.proasiste.lite.graph.ColumnDef
import com.proasiste.lite.graph.ColumnKind
import com.proasiste.lite.graph.NewValue

/**
 * A Chilean court ruling's columns, read by the rule-based skeleton with no model: date, type, outcome, filer, pending
 * case, challenged norms, who sat, who drafted, who dissented and who concurred. A column the skeleton can't read stays
 * empty; nothing is guessed. Each value cites the page it was read on.
 */
object RulingColumns {
    const val TYPE = "Fallo"
    /** What wrote these values, so filling again replaces only them. */
    const val ORIGIN = "reglas"

    const val DATE = "Fecha"; const val KIND = "Tipo"; const val OUTCOME = "Resultado"; const val FILER = "Requirente"
    const val CASE = "Gestión"; const val NORMS = "Normas impugnadas"; const val JUDGES = "Ministros"; const val DRAFTER = "Redactor"
    const val DISSENT = "Disidentes"; const val CONCUR = "Previenen"
    /** Where the ruling's number was read: a row read from the text rather than the file name is worth a look. */
    const val NUMBER_FROM = "Rol leído de"

    val DEFS = listOf(
        ColumnDef(DATE, ColumnKind.DATE, "Fecha de la sentencia"),
        ColumnDef(KIND, ColumnKind.TEXT, "Tipo de acción"),
        ColumnDef(OUTCOME, ColumnKind.TEXT, "Acogido, Acogido en parte, Rechazado, Inadmisible, Conforme, Conforme en parte o No conforme"),
        ColumnDef(FILER, ColumnKind.TEXT, "Quien presentó la acción"),
        ColumnDef(CASE, ColumnKind.TEXT, "La gestión pendiente y su tribunal"),
        ColumnDef(NORMS, ColumnKind.LIST, "Las normas cuya aplicación se impugna"),
        ColumnDef(JUDGES, ColumnKind.LIST, "Ministros que integraron el tribunal"),
        ColumnDef(DRAFTER, ColumnKind.TEXT, "Ministro redactor de la sentencia"),
        ColumnDef(DISSENT, ColumnKind.LIST, "Ministros que disintieron"),
        ColumnDef(CONCUR, ColumnKind.LIST, "Ministros que previnieron"),
        ColumnDef(NUMBER_FROM, ColumnKind.TEXT, "Dónde se leyó el número del fallo: encabezado, cierre, nombre del archivo o texto"),
    )

    /** The ruling's name and its column values, or null when the pages aren't a ruling the skeleton reads. */
    fun of(pages: List<DeepSeekFacts.PageText>, title: String): Pair<String, List<NewValue>>? {
        val s = RulingSkeleton.of(pages, title) ?: return null
        val f = s.facts
        val out = mutableListOf<NewValue>()
        s.date?.let { iso -> out += NewValue(DATE, iso, f.firstOrNull { it.relation == "DICTA" }?.page ?: 1) }
        f.firstOrNull { it.relation == "TIPO" }?.let { out += NewValue(KIND, it.obj, it.page) }
        s.outcome?.let { o -> out += NewValue(OUTCOME, o, f.firstOrNull { it.relation == "RESUELVE" }?.page ?: 1) }
        s.filer?.let { out += NewValue(FILER, it, 1) }
        if (s.rulingFrom.isNotEmpty()) out += NewValue(NUMBER_FROM, s.rulingFrom, 1)
        f.firstOrNull { it.relation == "REQUIERE_EN" }?.let { out += NewValue(CASE, it.obj, it.page) }
        f.filter { it.relation == "IMPUGNA" }.forEach { out += NewValue(NORMS, it.obj, it.page) }
        // Judges' columns hold people only: a signature block that ran into other text gives no name here.
        val person = RulingSkeleton.Names::isPerson
        f.filter { it.relation == "INTEGRA" && person(it.subject) }.forEach { out += NewValue(JUDGES, it.subject, it.page) }
        f.firstOrNull { it.relation == "REDACTA" && it.objectType == "Fallo" && person(it.subject) }?.let { out += NewValue(DRAFTER, it.subject, it.page) }
        f.filter { it.relation == "DISIENTE" && person(it.subject) }.forEach { out += NewValue(DISSENT, it.subject, it.page) }
        f.filter { it.relation == "PREVIENE" && person(it.subject) }.forEach { out += NewValue(CONCUR, it.subject, it.page) }
        return s.ruling to out
    }

    private val MONTHS = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")

    /** "… el 2 de septiembre de 2026." → 2026-09-02; also "setiembre". Null when there's no such date. */
    fun isoDate(text: String): String? {
        val m = Regex("(\\d{1,2})\\s+de\\s+(\\p{L}+)\\s+(?:de(?:l)?\\s+)?(\\d{4})", RegexOption.IGNORE_CASE).find(text) ?: return null
        val month = MONTHS.indexOf(m.groupValues[2].lowercase().replace("setiembre", "septiembre")) + 1
        val day = m.groupValues[1].toInt()
        if (month == 0 || day !in 1..31) return null
        return "%s-%02d-%02d".format(m.groupValues[3], month, day)
    }
}
