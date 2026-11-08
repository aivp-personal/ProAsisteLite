package com.proasiste.lite.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NanoPromptTest {
    @Test fun aStrayCitationMeansTheOnlyPassage() =
        assertEquals("Use 2 oz [1]. Wait [1].", NanoAnswerer.fixCitations("Use 2 oz [3]. Wait [4, 5].", 1))

    @Test fun citationsAreLeftAloneWithSeveralPassages() =
        assertEquals("Use 2 oz [3].", NanoAnswerer.fixCitations("Use 2 oz [3].", 2))

    @Test fun tableRowsStayOnThePassageLine() {
        val prompt = NanoAnswerer.prompt("q", listOf(NanoAnswerer.Passage("Manual", 6, "row one\nrow two")))
        assertTrue(prompt, prompt.contains("[1] Manual, page 6: row one | row two"))
    }
}
