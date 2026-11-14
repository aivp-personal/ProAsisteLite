package com.proasiste.lite.runtime

import com.proasiste.lite.graph.NewFact
import java.text.Normalizer

/**
 * DocLang blocks as the graph's input, and where a fact sits. Page text comes from a document's blocks, without running
 * headers, footers and pictures, one block per line, footnotes at the page's end, tables a row per line with " | " between
 * cells. [locate] finds the block a fact states, so the fact can open on its page highlighted, both halves of a paragraph
 * split by a page break included.
 */
object DocPages {
    private val SKIPPED = setOf("page_header", "page_footer", "picture")

    /**
     * One text per page. With [joinThreads], a paragraph split by a page break is also given whole at the end of the page
     * where it starts, so a fact whose words straddle the break can be checked against that page.
     */
    fun pages(d: DocLang.Doc, joinThreads: Boolean = false): List<DeepSeekFacts.PageText> {
        val byThread = d.blocks.filter { it.thread > 0 }.groupBy { it.thread }
        return (1..d.pages).map { p ->
            val blocks = inPositionOrder(d.blocks.filter { it.page == p && it.kind !in SKIPPED && it.text.isNotBlank() })
            val body = blocks.filter { it.kind != "footnote" } + blocks.filter { it.kind == "footnote" }
            val lines = body.map { it.text }.toMutableList()
            if (joinThreads) for (b in body) {
                val halves = byThread[b.thread] ?: continue
                if (b.thread > 0 && halves.first() === b && halves.size > 1) lines += halves.joinToString(" ") { it.text }
            }
            DeepSeekFacts.PageText(p, lines.joinToString("\n"))
        }
    }

    /**
     * A page's blocks in the tag tree's order, with text the tree left out put back where it sits: before the first block
     * that starts lower on the page. DocLang output keeps to_doclang's order (loose text last); the graph needs the
     * reading order, or a signature line can land after the text that follows it.
     */
    fun inPositionOrder(blocks: List<DocLang.Block>): List<DocLang.Block> {
        val out = blocks.filter { !it.loose }.toMutableList()
        for (l in blocks.filter { it.loose }.sortedBy { it.box?.get(1) ?: Int.MAX_VALUE }) {
            val y = l.box?.get(1) ?: Int.MAX_VALUE
            val at = out.indexOfFirst { !it.loose && (it.box?.get(1) ?: -1) > y }
            if (at < 0) out += l else out.add(at, l)
        }
        return out
    }

    /** True when the reader found text on most pages: otherwise the old page reader is kept. */
    fun usable(d: DocLang.Doc) = d.pages > 0 && pages(d).count { it.text.length > 40 } * 2 >= d.pages

    // ---- Where a fact sits ----

    private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    private fun terms(s: String) = Regex("[\\p{L}\\p{N}]+").findAll(fold(s)).map { it.value }.filter { it.length >= 4 || it.any(Char::isDigit) }.toSet()

    /**
     * The block on the fact's page that best holds its words, with the other halves when the paragraph continues across
     * a page break, as "page:x0,y0,x1,y1;…" on DocLang's 500 grid. Empty when no block shares enough words with the fact.
     */
    fun locate(d: DocLang.Doc, f: NewFact): String {
        val want = terms(f.statement) + terms(f.subject) + terms(f.obj)
        if (want.isEmpty()) return ""
        val candidates = d.blocks.filter { it.page == f.page && it.kind !in SKIPPED && it.box != null }
        val best = candidates.map { b ->
            // A paragraph split by a page break counts as a whole.
            val whole = if (b.thread > 0) d.blocks.filter { it.thread == b.thread }.joinToString(" ") { it.text } else b.text
            b to terms(whole).count { it in want }
        }.maxWithOrNull(compareBy<Pair<DocLang.Block, Int>> { it.second }.thenBy { -it.first.text.length }) ?: return ""
        if (best.second < minOf(3, want.size) && best.second * 2 < want.size) return ""
        val parts = if (best.first.thread > 0) d.blocks.filter { it.thread == best.first.thread && it.box != null } else listOf(best.first)
        return parts.joinToString(";") { "${it.page}:${it.box!!.joinToString(",")}" }
    }

    /** A stored place: (page, x0, y0, x1, y1) boxes on the 500 grid. Malformed entries are skipped. */
    fun parse(place: String): List<Pair<Int, List<Int>>> = place.split(';').mapNotNull { part ->
        val page = part.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
        val box = part.substringAfter(':', "").split(',').mapNotNull { it.toIntOrNull() }
        if (box.size == 4) page to box else null
    }
}
