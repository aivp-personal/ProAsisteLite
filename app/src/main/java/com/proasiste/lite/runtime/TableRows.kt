package com.proasiste.lite.runtime

import kotlin.math.abs

/**
 * Rebuilds tables whose row label is centred across several rows. Read line by line, such a label
 * lands between its rows and looks like it belongs to the numbers after it:
 *
 *              6   2.25 oz.   8 min
 * Soft-Boiled  12
 *                  1.75 oz.   10 min
 *
 * becomes "Soft-Boiled: 6, 2.25 oz., 8 min" and "Soft-Boiled: 12, 1.75 oz., 10 min", with the column
 * names added when a header line sits directly above. It works from positions alone because a
 * table's headers may not be text at all. Anything that doesn't clearly match is left alone, so
 * ordinary text is read as before: a wrong row is worse than a messy one.
 */
object TableRows {
    data class Word(val text: String, val x0: Float, val x1: Float, val y: Float, val size: Float)
    /** A table's rows, and the area of the page they came from so it isn't read twice. */
    data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        fun holds(x: Float, y: Float) = x in x0..x1 && y in y0..y1
    }
    data class Table(val rows: List<String>, val boxes: List<Box>)

    private class Cell(val words: List<Word>) {
        val text = words.joinToString(" ") { it.text }
        val x0 = words.first().x0
        val x1 = words.last().x1
        val hasDigit = text.any { it.isDigit() }
        val hasLetter = text.any { it.isLetter() }
    }
    private class Line(val y: Float, val size: Float, val cells: List<Cell>)
    private class Group(val index: Int, val label: Cell, val values: List<Cell>, val width: Int, val words: List<Word>)

    /** Words on one side of the page; a two-page spread must be split first or its lines interleave. */
    fun find(words: List<Word>): List<Table> {
        val lines = lines(words)
        val groups = lines.indices.mapNotNull { group(lines, it) }
        // A lone match could be chance. A real table repeats, with the same columns and aligned labels.
        val tables = mutableListOf<MutableList<Group>>()
        for (g in groups) {
            val last = tables.lastOrNull()?.last()
            if (last != null && last.width == g.width && abs(last.label.x0 - g.label.x0) <= 2 * g.label.words.first().size) tables.last() += g
            else tables += mutableListOf(g)
        }
        return tables.filter { it.size >= 2 }.map { table ->
            val header = header(lines, table.first())
            val names = header?.cells?.drop(1)?.map { it.text }
            val boxes = table.map { g -> box(g.words) } + listOfNotNull(header?.let { h -> box(h.cells.flatMap { it.words }) })
            Table(
                table.flatMap { g ->
                    g.values.chunked(g.width).map { row ->
                        g.label.text + ": " + row.mapIndexed { i, cell -> names?.let { "${it[i]} " }.orEmpty() + cell.text }.joinToString(", ")
                    }
                },
                boxes,
            )
        }
    }

    private fun box(words: List<Word>) = Box(words.minOf { it.x0 } - 1, words.minOf { it.y } - 1, words.maxOf { it.x1 } + 1, words.maxOf { it.y } + 1)

    /** The line above the first row, if it names exactly the label column and each value column. */
    private fun header(lines: List<Line>, first: Group): Line? {
        val line = lines.getOrNull(first.index - 2) ?: return null
        val top = lines[first.index - 1]
        if (top.y - line.y > 3 * top.size || line.cells.size != first.width + 1) return null
        return line.takeIf { h -> h.cells.all { it.hasLetter && !it.hasDigit } }
    }

    /** Whole rows joined into blocks of at most [maxWords], so a chunk boundary never cuts a row in half. */
    fun blocks(rows: List<String>, maxWords: Int = 80): List<String> {
        val out = mutableListOf<String>()
        var block = mutableListOf<String>()
        var count = 0
        for (row in rows) {
            val size = row.split(Regex("\\s+")).size
            if (block.isNotEmpty() && count + size > maxWords) { out += block.joinToString("\n"); block = mutableListOf(); count = 0 }
            block += row
            count += size
        }
        if (block.isNotEmpty()) out += block.joinToString("\n")
        return out
    }

    private fun group(lines: List<Line>, i: Int): Group? {
        val up = lines.getOrNull(i - 1) ?: return null
        val mid = lines[i]
        val down = lines.getOrNull(i + 1) ?: return null
        val label = mid.cells.first()
        if (!label.hasLetter || label.hasDigit) return null
        val reach = 2 * mid.size
        if (mid.y - up.y > reach || down.y - mid.y > reach) return null
        val values = up.cells + mid.cells.drop(1) + down.cells
        val width = up.cells.size
        if (width < 2 || values.size % width != 0 || values.size / width < 2) return null
        if (!values.all { it.hasDigit } || values.any { it.x0 <= label.x1 }) return null
        return Group(i, label, values, width, (listOf(label) + values).flatMap { it.words })
    }

    private fun lines(words: List<Word>): List<Line> {
        val out = mutableListOf<MutableList<Word>>()
        for (w in words.filter { it.text.isNotBlank() }.sortedBy { it.y }) {
            val line = out.lastOrNull()
            if (line != null && w.y - line.first().y <= 0.25f * w.size) line += w else out += mutableListOf(w)
        }
        return out.map { line ->
            val cells = mutableListOf<MutableList<Word>>()
            for (w in line.sortedBy { it.x0 }) {
                val cell = cells.lastOrNull()
                if (cell != null && w.x0 - cell.last().x1 <= 1.5f * w.size) cell += w else cells += mutableListOf(w)
            }
            Line(line.first().y, line.first().size, cells.map { Cell(it) })
        }
    }
}
