package com.proasiste.lite.text

/** Text from one page of a document. Pages are numbered from 1. */
data class PageText(val page: Int, val text: String)

/** A short piece of a page, small enough to be about one idea. It always knows its page. */
data class Chunk(val page: Int, val text: String)

object Chunker {
    const val WORDS_PER_CHUNK = 60
    const val OVERLAP_WORDS = 15
    private val whitespace = Regex("\\s+")

    fun chunk(page: PageText): List<Chunk> {
        val trimmed = page.text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val words = trimmed.split(whitespace)
        val chunks = mutableListOf<Chunk>()
        var start = 0
        while (true) {
            val end = minOf(start + WORDS_PER_CHUNK, words.size)
            chunks += Chunk(page.page, words.subList(start, end).joinToString(" "))
            if (end == words.size) return chunks
            start += WORDS_PER_CHUNK - OVERLAP_WORDS
        }
    }
}
