package com.proasiste.lite.text

import java.text.Normalizer

/** Turns text into search terms. Queries and passages must use the same rules, or they stop matching. */
object Tokenizer {
    private val word = Regex("[\\p{L}\\p{N}]+")
    private val accent = Regex("\\p{M}+")
    private val stopWords = setOf(
        "the", "and", "for", "are", "how", "what", "when", "where", "which", "who", "why", "does", "did",
        "can", "you", "your", "with", "this", "that", "from", "into", "about", "have", "has", "was", "were",
        "el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "que", "en", "por", "para",
        "con", "como", "cual", "cuando", "donde", "es", "son", "se", "su", "sus", "al", "lo", "le", "les",
    )

    /** Lower case, no accents, no stop words, and a plural "s" trimmed from longer words. */
    fun terms(text: String): List<String> =
        word.findAll(fold(text))
            .map { it.value }
            .filter { it !in stopWords && (it.length > 1 || it[0].isDigit()) }
            .map { if (it.length > 4 && it.endsWith("s")) it.dropLast(1) else it }
            .toList()

    /** Lower case without accents, so "Constitución" and "constitucion" are the same. */
    fun fold(text: String): String =
        accent.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")
}
