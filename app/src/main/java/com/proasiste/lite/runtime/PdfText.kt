package com.proasiste.lite.runtime

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.PDFTextStripperByArea
import com.tom_roush.pdfbox.text.TextPosition
import java.io.StringWriter

/**
 * Reads a PDF page in visual order, so a table's cells stay beside their row instead of
 * coming out in the order they were drawn. Text is only sorted by position, so a page with
 * two columns or a two-page spread has to be read one side at a time or its lines interleave.
 */
object PdfText {
    /** [prose] excludes the tables that were rebuilt; each entry of [tables] is one table's rows. */
    class Page(val prose: String, val tables: List<List<String>>)

    /** The share of the page width, around its centre, that must be empty to count as a gutter. */
    private const val GUTTER = 0.02f

    fun page(doc: PDDocument, number: Int): Page {
        val probe = Probe().apply { startPage = number; endPage = number; sortByPosition = true }
        probe.writeText(doc, StringWriter())
        val sides = if (probe.hasGutter) probe.words.partition { it.x1 < probe.pageWidth / 2 }.toList() else listOf(probe.words)
        val tables = sides.flatMap { TableRows.find(it) }
        val boxes = tables.flatMap { it.boxes }
        val rows = tables.map { it.rows }
        if (!probe.hasGutter) return Page(Prose(boxes).apply { startPage = number; endPage = number; sortByPosition = true }.getText(doc), rows)
        val area = ProseByArea(boxes).apply {
            sortByPosition = true
            addRegion("left", RectF(0f, 0f, probe.pageWidth / 2, probe.pageHeight))
            addRegion("right", RectF(probe.pageWidth / 2, 0f, probe.pageWidth, probe.pageHeight))
            extractRegions(doc.getPage(number - 1))
        }
        return Page(area.getTextForRegion("left") + "\n" + area.getTextForRegion("right"), rows)
    }

    /** The text left after removing glyphs inside a table. Checked per glyph because one run can span a table and what's beside it. */
    private fun outside(text: String, positions: List<TextPosition>, skip: List<TableRows.Box>): String {
        if (skip.isEmpty()) return text
        val kept = positions.filter { p -> skip.none { it.holds(p.x, p.y) } }
        return if (kept.size == positions.size) text else kept.joinToString("") { it.unicode }
    }

    private class Prose(val skip: List<TableRows.Box>) : PDFTextStripper() {
        override fun writeString(text: String, textPositions: List<TextPosition>) {
            super.writeString(outside(text, textPositions, skip), textPositions)
        }
    }

    private class ProseByArea(val skip: List<TableRows.Box>) : PDFTextStripperByArea() {
        override fun writeString(text: String, textPositions: List<TextPosition>) {
            super.writeString(outside(text, textPositions, skip), textPositions)
        }
    }

    /** Collects the page's words with their positions, and notes whether its centre is empty. */
    private class Probe : PDFTextStripper() {
        val words = mutableListOf<TableRows.Word>()
        var pageWidth = 0f; var pageHeight = 0f
        private var left = 0; private var right = 0; private var centre = 0
        val hasGutter get() = centre == 0 && left > 0 && right > 0

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            for (p in textPositions) {
                pageWidth = p.pageWidth; pageHeight = p.pageHeight
                val mid = pageWidth / 2; val half = pageWidth * GUTTER
                val from = p.x; val to = p.x + p.width
                when {
                    to < mid - half -> left++
                    from > mid + half -> right++
                    else -> if (text.isNotBlank()) centre++
                }
            }
            collect(textPositions)
        }

        /** Rebuilds words from glyphs: a run from the stripper can hold several words, even across a wide gap. */
        private fun collect(positions: List<TextPosition>) {
            var run = mutableListOf<TextPosition>()
            fun flush() {
                val first = run.firstOrNull() ?: return
                val last = run.last()
                words += TableRows.Word(run.joinToString("") { it.unicode }, first.x, last.x + last.width, first.y, first.fontSizeInPt)
                run = mutableListOf()
            }
            for (p in positions) {
                if (p.unicode.isBlank()) { flush(); continue }
                val before = run.lastOrNull()
                if (before != null && (p.x - (before.x + before.width) > 0.5f * p.fontSizeInPt || p.y != before.y)) flush()
                run += p
            }
            flush()
        }
    }
}
