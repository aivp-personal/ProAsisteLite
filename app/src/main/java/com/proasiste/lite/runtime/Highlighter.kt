package com.proasiste.lite.runtime

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.StringWriter
import kotlin.math.max
import kotlin.math.min

/**
 * Finds where a passage sits on a PDF page, so the viewer can highlight it. The passage's words are aligned with the
 * page's words in reading order (a local alignment, so a passage that starts or ends mid-line, or whose hyphenated word
 * was rejoined, still lines up), and the matched words become one rectangle per line. Works on any text PDF, tagged or
 * not, without re-indexing. Rectangles are fractions of the page (0–1, top-left origin).
 */
object Highlighter {
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)
    /** A word on the page and its box in page space. */
    data class Word(val key: String, val x0: Float, val top: Float, val x1: Float, val bottom: Float)

    /** Below this share of the passage's words found in order, nothing is highlighted rather than the wrong place. */
    const val MIN_SHARE = 0.6

    fun find(file: File, page: Int, passage: String): List<Rect> {
        val words = passageWords(passage); if (words.size < 2) return emptyList()
        return PDDocument.load(file).use { doc ->
            if (page !in 1..doc.numberOfPages) return emptyList()
            val box = doc.getPage(page - 1).cropBox
            val onPage = pageWords(doc, page)
            lines(onPage.slice(align(words, onPage.map { it.key }) ?: return emptyList()), box.width, box.height)
        }
    }

    /** Lower-cased, accent-folded words, so "Constitución" on the page matches "constitucion" in a passage. */
    fun key(word: String) = java.text.Normalizer.normalize(word.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").filter { it.isLetterOrDigit() }

    fun passageWords(text: String) = text.removePrefix("… ").removeSuffix(" …").split(Regex("\\s+")).map(::key).filter { it.isNotEmpty() }

    /**
     * The page word indices that the passage matches, or null when too few match. Smith–Waterman over words: a match
     * scores 2, a mismatch or a skipped word costs 1, so short gaps (a page number, a footnote mark) are bridged.
     */
    fun align(passage: List<String>, page: List<String>): IntRange? {
        val n = passage.size; val m = page.size
        if (n == 0 || m == 0) return null
        val score = Array(n + 1) { IntArray(m + 1) }
        var best = 0; var bi = 0; var bj = 0
        for (i in 1..n) for (j in 1..m) {
            val s = max(0, maxOf(score[i - 1][j - 1] + if (passage[i - 1] == page[j - 1]) 2 else -1, score[i - 1][j] - 1, score[i][j - 1] - 1))
            score[i][j] = s
            if (s > best) { best = s; bi = i; bj = j }
        }
        if (best == 0) return null
        // Walk back to where the alignment starts, counting the passage words matched on the way.
        var i = bi; var j = bj; var matched = 0
        while (i > 0 && j > 0 && score[i][j] > 0) {
            val s = score[i][j]
            if (s == score[i - 1][j - 1] + (if (passage[i - 1] == page[j - 1]) 2 else -1)) { if (passage[i - 1] == page[j - 1]) matched++; i--; j-- }
            else if (s == score[i - 1][j] - 1) i-- else j--
        }
        return if (matched >= MIN_SHARE * n) j until bj else null
    }

    /** One rectangle per line of matched words, slightly padded, as fractions of the page. */
    fun lines(words: List<Word>, width: Float, height: Float): List<Rect> {
        val out = mutableListOf<Rect>()
        var cur: Word? = null
        for (w in words) {
            val c = cur
            if (c != null && kotlin.math.abs(w.top - c.top) < 0.5f * (c.bottom - c.top) && w.x0 >= c.x0) cur = Word("", c.x0, min(c.top, w.top), max(c.x1, w.x1), max(c.bottom, w.bottom))
            else { c?.let { out += rect(it, width, height) }; cur = w }
        }
        cur?.let { out += rect(it, width, height) }
        return out
    }

    private fun rect(w: Word, width: Float, height: Float): Rect {
        val pad = (w.bottom - w.top) * 0.2f
        return Rect(((w.x0 - pad) / width).coerceIn(0f, 1f), ((w.top - pad) / height).coerceIn(0f, 1f), ((w.x1 + pad) / width).coerceIn(0f, 1f), ((w.bottom + pad) / height).coerceIn(0f, 1f))
    }

    /** The page's words in reading order, as the indexer read them (sorted by position). */
    private fun pageWords(doc: PDDocument, page: Int): List<Word> {
        val out = mutableListOf<Word>()
        object : PDFTextStripper() {
            override fun writeString(text: String, positions: List<TextPosition>) {
                var run = mutableListOf<TextPosition>()
                fun flush() {
                    if (run.isEmpty()) return
                    val k = key(run.joinToString("") { it.unicode ?: "" })
                    if (k.isNotEmpty()) out += Word(k, run.minOf { it.xDirAdj }, run.minOf { it.yDirAdj - it.heightDir }, run.maxOf { it.xDirAdj + it.widthDirAdj }, run.maxOf { it.yDirAdj })
                    run = mutableListOf()
                }
                for (p in positions) {
                    if (p.unicode.isNullOrBlank()) { flush(); continue }
                    val before = run.lastOrNull()
                    if (before != null && (p.xDirAdj - (before.xDirAdj + before.widthDirAdj) > 0.25f * p.fontSizeInPt || kotlin.math.abs(p.yDirAdj - before.yDirAdj) > 0.5f * p.heightDir)) flush()
                    run += p
                }
                flush()
            }
        }.apply { startPage = page; endPage = page; sortByPosition = true }.writeText(doc, StringWriter())
        return out
    }
}
