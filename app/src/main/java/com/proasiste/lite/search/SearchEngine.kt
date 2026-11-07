package com.proasiste.lite.search

import android.database.sqlite.SQLiteDatabase
import com.proasiste.lite.data.AppDatabase
import com.proasiste.lite.text.Tokenizer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One search result, always traceable to a document and page. */
data class SearchHit(
    val passageId: Long,
    val documentId: Long,
    val documentTitle: String,
    val page: Int,
    val text: String,
    val similarity: Float,
)

/**
 * Hybrid search: embedding similarity and word matches (BM25), combined by rank.
 *
 * Passage text is never loaded to rank. Only vectors and a few numbers stay in memory, in flat arrays, and term
 * frequencies come from the terms table. That keeps a project of tens of thousands of passages inside the app's memory.
 */
class SearchEngine(private val database: AppDatabase) {
    private var cached: ProjectIndex? = null

    @Synchronized
    fun search(projectId: Long, query: String, queryVector: FloatArray, limit: Int = DEFAULT_LIMIT): List<SearchHit> {
        val index = indexFor(projectId)
        if (index.size == 0) return emptyList()
        require(queryVector.size == index.dimensions) { "The query vector doesn't match the index." }

        val similarity = index.similarities(queryVector)
        val byMeaning = (0 until index.size).sortedByDescending { similarity[it] }
        val byWords = wordRanking(index, Tokenizer.terms(query).toSet())
        val fused = Ranking.fuse(index.size, listOf(byMeaning, byWords))
        val best = (0 until index.size).sortedByDescending { fused[it] }.take(limit)
        return loadHits(index, best, similarity)
    }

    private fun wordRanking(index: ProjectIndex, terms: Set<String>): List<Int> {
        if (terms.isEmpty()) return emptyList()
        val scores = DoubleArray(index.size)
        val db = database.readableDatabase
        for (term in terms) {
            val positions = ArrayList<Int>()
            val frequencies = ArrayList<Int>()
            db.rawQuery("SELECT passage_id, frequency FROM terms WHERE term = ?", arrayOf(term)).use { cursor ->
                while (cursor.moveToNext()) {
                    val position = index.positionOf(cursor.getLong(0))
                    if (position >= 0) {
                        positions += position
                        frequencies += cursor.getInt(1)
                    }
                }
            }
            positions.forEachIndexed { i, position ->
                scores[position] += Ranking.bm25(
                    termFrequency = frequencies[i],
                    passagesWithTerm = positions.size,
                    totalPassages = index.size,
                    passageWords = index.wordCounts[position],
                    averageWords = index.averageWords,
                )
            }
        }
        return (0 until index.size).filter { scores[it] > 0 }.sortedByDescending { scores[it] }
    }

    private fun loadHits(index: ProjectIndex, positions: List<Int>, similarity: FloatArray): List<SearchHit> {
        val rankOf = positions.withIndex().associate { (rank, position) -> index.passageIds[position] to rank }
        val placeholders = positions.joinToString(",") { "?" }
        val args = positions.map { index.passageIds[it].toString() }.toTypedArray()
        val hits = database.readableDatabase.rawQuery(
            """SELECT p.id, p.document_id, d.title, p.page, p.text
               FROM passages p JOIN documents d ON d.id = p.document_id
               WHERE p.id IN ($placeholders)""",
            args,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    add(
                        SearchHit(
                            passageId = id,
                            documentId = cursor.getLong(1),
                            documentTitle = cursor.getString(2),
                            page = cursor.getInt(3),
                            text = cursor.getString(4),
                            similarity = similarity[index.positionOf(id)],
                        ),
                    )
                }
            }
        }
        return hits.sortedBy { rankOf.getValue(it.passageId) }
    }

    private fun indexFor(projectId: Long): ProjectIndex {
        val generation = database.currentIndexGeneration
        cached?.takeIf { it.projectId == projectId && it.generation == generation }?.let { return it }
        cached = null // Free the old block before allocating a new one: two at once might not fit.
        return ProjectIndex.load(database.readableDatabase, projectId, generation).also { cached = it }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
    }
}

/** A project's vectors in one flat array, with passage ids sorted so a position lookup needs no extra memory. */
private class ProjectIndex(
    val projectId: Long,
    val generation: Long,
    val passageIds: LongArray,
    val wordCounts: IntArray,
    val vectors: FloatArray,
    val dimensions: Int,
) {
    val size: Int get() = passageIds.size
    val averageWords: Double = if (size == 0) 1.0 else maxOf(1.0, wordCounts.average())

    fun positionOf(passageId: Long): Int = passageIds.binarySearch(passageId)

    /** Stored and query vectors are unit length, so a dot product is their cosine similarity. */
    fun similarities(query: FloatArray): FloatArray =
        FloatArray(size) { position ->
            val start = position * dimensions
            var dot = 0f
            for (j in 0 until dimensions) dot += query[j] * vectors[start + j]
            dot
        }

    companion object {
        private const val BATCH = 500

        fun load(db: SQLiteDatabase, projectId: Long, generation: Long): ProjectIndex {
            val count = db.rawQuery(
                "SELECT COUNT(*) FROM passages p JOIN documents d ON d.id = p.document_id WHERE d.project_id = ?",
                arrayOf(projectId.toString()),
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            val ids = LongArray(count)
            val words = IntArray(count)
            var vectors = FloatArray(0)
            var dimensions = 0
            var filled = 0
            var lastId = 0L
            while (filled < count) {
                // Fetched in batches by id: each query starts where the last ended instead of skipping from the top.
                val before = filled
                db.rawQuery(
                    """SELECT p.id, p.word_count, p.vector
                       FROM passages p JOIN documents d ON d.id = p.document_id
                       WHERE d.project_id = ? AND p.id > ?
                       ORDER BY p.id LIMIT $BATCH""",
                    arrayOf(projectId.toString(), lastId.toString()),
                ).use { cursor ->
                    while (cursor.moveToNext() && filled < count) {
                        val blob = cursor.getBlob(2)
                        if (filled == 0) {
                            dimensions = blob.size / Float.SIZE_BYTES
                            vectors = FloatArray(count * dimensions)
                        }
                        ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                            .get(vectors, filled * dimensions, dimensions)
                        lastId = cursor.getLong(0)
                        ids[filled] = lastId
                        words[filled] = cursor.getInt(1)
                        filled++
                    }
                }
                check(filled > before) { "The search index changed while loading." }
            }
            return ProjectIndex(projectId, generation, ids, words, vectors, dimensions)
        }
    }
}
