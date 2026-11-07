package com.proasiste.lite.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {
    @Test fun termsIgnoreCaseAccentsAndStopWords() {
        assertEquals(listOf("constitucion", "tribunal"), Tokenizer.terms("La Constitución del Tribunal"))
    }

    @Test fun termsTrimPluralsFromLongerWordsOnly() {
        assertEquals(listOf("hose", "gas"), Tokenizer.terms("hoses gas"))
    }

    @Test fun termsKeepSingleDigitsButNotSingleLetters() {
        assertEquals(listOf("5"), Tokenizer.terms("x 5"))
    }

    @Test fun blankPageHasNoChunks() {
        assertTrue(Chunker.chunk(PageText(1, " \n ")).isEmpty())
    }

    @Test fun shortPageIsOneChunkWithItsPage() {
        assertEquals(listOf(Chunk(7, "one two")), Chunker.chunk(PageText(7, " one\n two ")))
    }

    @Test fun longPageOverlapsAndLosesNoWord() {
        val words = (1..200).map { "w$it" }
        val chunks = Chunker.chunk(PageText(3, words.joinToString(" ")))
        assertTrue(chunks.all { it.page == 3 && it.text.split(" ").size <= Chunker.WORDS_PER_CHUNK })
        assertEquals(words.toSet(), chunks.flatMap { it.text.split(" ") }.toSet())
        val first = chunks[0].text.split(" ")
        val second = chunks[1].text.split(" ")
        assertEquals(first.takeLast(Chunker.OVERLAP_WORDS), second.take(Chunker.OVERLAP_WORDS))
    }
}
