package com.proasiste.lite.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class JevClaimsTest {
    private fun claims(text: String) = JevClient.claims(text).map { it.text to it.citations }

    @Test fun citationInsideSentence() =
        assertEquals(listOf("Use 90 ml of water." to listOf(2)), claims("Use 90 ml of water [2]."))

    @Test fun citationAfterFullStopBelongsToThatSentence() =
        assertEquals(listOf("Use water." to listOf(1), "Press start." to listOf(2)), claims("Use water. [1] Press start. [2]"))

    @Test fun severalCitationFormats() =
        assertEquals(listOf("It beeps." to listOf(1, 3)), claims("It beeps [1, 3]."))

    @Test fun adjacentCitations() =
        assertEquals(listOf("It beeps." to listOf(1, 2)), claims("It beeps [1][2]."))

    @Test fun uncitedSentenceHasNoCitations() =
        assertEquals(listOf("Hard to say." to emptyList<Int>(), "It beeps." to listOf(1)), claims("Hard to say. It beeps [1]."))

    @Test fun rawKeepsTheCitationsForRebuildingTheAnswer() =
        assertEquals(listOf("Use water. [1]", "Press start [2]."), JevClient.claims("Use water. [1] Press start [2].").map { it.raw })

    @Test fun onlyCitationsIsNotAClaim() = assertEquals(emptyList<Pair<String, List<Int>>>(), claims("[1]"))
}
