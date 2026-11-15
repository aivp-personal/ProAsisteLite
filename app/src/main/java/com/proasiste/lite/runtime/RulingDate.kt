package com.proasiste.lite.runtime

import java.text.Normalizer

/**
 * The date a Chilean court ruling was given, as the ruling itself writes it:
 * - bracketed under the heading, as the Tribunal's recent rulings do: "[2 de septiembre de 2026]", also when the PDF's
 *   text has lost its spaces: "[13dejuliode2023]";
 * - after "Santiago," in digits: "Santiago, 21 de abril de 2015";
 * - after "Santiago," in words, as older rulings and inadmissibility resolutions do: "Santiago, seis de junio de dos mil
 *   veinticinco", "treinta y uno de diciembre de dos mil nueve".
 * A date after "con fecha" is when something was filed or decided earlier, never the ruling's: only the bracketed date
 * and the one after "Santiago" count.
 */
object RulingDate {
    /** The date as ISO (2026-09-02), the words it was read from, and its page. */
    data class Found(val iso: String, val text: String, val page: Int)

    private val MONTHS = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
    private val BRACKET = Regex("\\[\\s*(\\d{1,2})\\s*de\\s*(\\p{L}+?)\\s*de(?:l)?\\s*(\\d{4})\\s*]", RegexOption.IGNORE_CASE)
    private val DIGITS = Regex("Santiago\\s*,?\\s+(\\d{1,2})\\s+de\\s+(\\p{L}+)\\s+(?:de(?:l)?\\s+)?(\\d{4})", RegexOption.IGNORE_CASE)
    private val WORDS = Regex("Santiago\\s*,?\\s+([\\p{L} ]{2,30}?)\\s+de\\s+(\\p{L}+)\\s+de(?:l)?\\s+((?:mil|dos mil)[\\p{L} ]{0,40}?)\\s*(?=[.,;:\\n)]|$)", RegexOption.IGNORE_CASE)

    /**
     * The ruling's date, checked against what else the ruling says: it can't come before the case was filed (the year in
     * its case number, "Rol 17.292-26" → 2026, or the first "con fecha" date of VISTOS), nor more than [MAX_YEARS] after.
     * A candidate that fails is passed over; when none passes there is no date, rather than a wrong one.
     */
    fun find(pages: List<DeepSeekFacts.PageText>, ruling: String = ""): Found? {
        val filedYear = filedYear(ruling, pages)
        val filedOn = FILED.find(pages.firstOrNull()?.text.orEmpty())?.let { m -> iso(m.groupValues[1].toIntOrNull(), m.groupValues[2], m.groupValues[3].toIntOrNull()) }
        fun plausible(iso: String): Boolean {
            val year = iso.take(4).toInt()
            if (filedYear != null && year !in filedYear..filedYear + MAX_YEARS) return false
            if (filedOn != null && iso < filedOn) return false
            return true
        }
        val candidates = sequence {
            // The heading's bracketed date, on the first page.
            pages.firstOrNull()?.let { p -> BRACKET.findAll(p.text).forEach { m -> iso(m.groupValues[1].toIntOrNull(), m.groupValues[2], m.groupValues[3].toIntOrNull())?.let { yield(Found(it, m.value.trim('[', ']', ' '), p.page)) } } }
            // Then each "Santiago, <date>", in digits or in words, in reading order.
            for (p in pages) {
                val found = DIGITS.findAll(p.text).mapNotNull { m -> iso(m.groupValues[1].toIntOrNull(), m.groupValues[2], m.groupValues[3].toIntOrNull())?.let { m.range.first to Found(it, m.value.substringAfter(",").trim(), p.page) } } +
                    WORDS.findAll(p.text).mapNotNull { m -> iso(number(m.groupValues[1]), m.groupValues[2], number(m.groupValues[3]))?.let { m.range.first to Found(it, m.value.substringAfter(",").trim(), p.page) } }
                found.sortedBy { it.first }.forEach { yield(it.second) }
            }
        }
        return candidates.firstOrNull { plausible(it.iso) }
    }

    /** How many years after filing a ruling may still come. */
    const val MAX_YEARS = 4
    private val FILED = Regex("con\\s+fecha\\s+(\\d{1,2})\\s+de\\s+(\\p{L}+)\\s+(?:de(?:l)?\\s+)?(\\d{4})", RegexOption.IGNORE_CASE)
    private val ROL_YEAR = Regex("(?i)rol[^\\d]{0,8}\\d{1,3}(?:[.,]?\\d{3})?\\s*-\\s*(\\d{2,4})\\b")

    /** The year a case was filed, from the two- or four-digit suffix of its case number ("Rol 2776-15" → 2015). */
    fun filedYear(ruling: String, pages: List<DeepSeekFacts.PageText>): Int? {
        val m = ROL_YEAR.find(ruling) ?: pages.firstOrNull()?.text?.let { ROL_YEAR.find(it) } ?: return null
        val y = m.groupValues[1].toInt()
        return when { y >= 1950 -> y; y in 0..99 -> if (y >= 50) 1900 + y else 2000 + y; else -> null }
    }

    private fun iso(day: Int?, month: String, year: Int?): String? {
        val m = MONTHS.indexOf(fold(month).replace("setiembre", "septiembre")) + 1
        if (day == null || year == null || m == 0 || year !in 1950..2100) return null
        return runCatching { java.time.LocalDate.of(year, m, day).toString() }.getOrNull()
    }

    private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    private val UNITS = mapOf(
        "un" to 1, "uno" to 1, "primero" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9,
        "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14, "quince" to 15, "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18, "diecinueve" to 19,
        "veinte" to 20, "veintiun" to 21, "veintiuno" to 21, "veintidos" to 22, "veintitres" to 23, "veinticuatro" to 24, "veinticinco" to 25, "veintiseis" to 26,
        "veintisiete" to 27, "veintiocho" to 28, "veintinueve" to 29, "treinta" to 30, "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60, "setenta" to 70,
        "ochenta" to 80, "noventa" to 90, "cien" to 100, "ciento" to 100, "doscientos" to 200, "trescientos" to 300, "cuatrocientos" to 400, "quinientos" to 500,
        "seiscientos" to 600, "setecientos" to 700, "ochocientos" to 800, "novecientos" to 900,
    )

    /** A number written in Spanish words: "treinta y uno" → 31, "dos mil veinticinco" → 2025, "mil novecientos noventa y ocho" → 1998. Null if any word isn't a number. */
    fun number(words: String): Int? {
        val tokens = fold(words).split(Regex("\\s+")).filter { it.isNotEmpty() && it != "y" }
        if (tokens.isEmpty()) return null
        var total = 0; var current = 0
        for (t in tokens) {
            if (t == "mil") { total += (if (current == 0) 1 else current) * 1000; current = 0; continue }
            current += UNITS[t] ?: return null
        }
        return total + current
    }
}
