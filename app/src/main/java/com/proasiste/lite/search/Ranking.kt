package com.proasiste.lite.search

import kotlin.math.ln

/** The scoring formulas behind search, kept apart from storage so they can be tested alone. */
object Ranking {
    private const val FUSION_K = 60.0
    private const val BM25_K1 = 1.2
    private const val BM25_B = 0.75

    /** Reciprocal rank fusion: items ranked well by several lists rise. Returns a score per item. */
    fun fuse(size: Int, rankings: List<List<Int>>): DoubleArray {
        val scores = DoubleArray(size)
        for (ranking in rankings) {
            ranking.forEachIndexed { rank, item -> scores[item] += 1.0 / (FUSION_K + rank) }
        }
        return scores
    }

    /** One term's BM25 score in one passage. Rare terms and short passages score higher. */
    fun bm25(
        termFrequency: Int,
        passagesWithTerm: Int,
        totalPassages: Int,
        passageWords: Int,
        averageWords: Double,
    ): Double {
        val rarity = ln(1 + (totalPassages - passagesWithTerm + 0.5) / (passagesWithTerm + 0.5))
        val lengthFactor = 1 - BM25_B + BM25_B * passageWords / averageWords
        return rarity * termFrequency * (BM25_K1 + 1) / (termFrequency + BM25_K1 * lengthFactor)
    }
}
