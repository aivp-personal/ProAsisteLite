package com.proasiste.lite.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TableRowsTest {
    private val size = 9.4f
    private fun word(text: String, x: Float, y: Float) = TableRows.Word(text, x, x + text.length * size * 0.5f, y, size)
    /** One word per space in [text], laid out from x. */
    private fun cell(text: String, x: Float, y: Float): List<TableRows.Word> {
        var at = x
        return text.split(" ").map { t -> word(t, at, y).also { at = it.x1 + 2f } }
    }

    /** The real positions from the egg cooker manual's table. */
    private val eggTable = listOf(
        cell("6", 165.9f, 210.5f), cell("2.25 oz.", 302.2f, 210.5f), cell("8 min", 396.8f, 210.5f),
        cell("12", 243.5f, 221.8f), cell("Soft-Boiled", 45.6f, 222.7f),
        cell("1.75 oz.", 315.8f, 234.8f), cell("10 min", 414.3f, 234.8f),
        cell("6", 165.9f, 254.8f), cell("2.75 oz.", 302.2f, 254.8f), cell("11 min", 396.8f, 254.8f),
        cell("12", 243.5f, 266.1f), cell("Medium Egg", 46.5f, 267.0f),
        cell("2 oz.", 329.1f, 279.1f), cell("13 min", 414.3f, 279.1f),
        cell("6", 165.9f, 299.1f), cell("5 oz.", 302.2f, 299.1f), cell("18 min", 396.8f, 299.1f),
        cell("12", 243.5f, 310.3f), cell("Hard-Boiled", 46.5f, 311.2f),
        cell("3.5 oz.", 321.3f, 323.4f), cell("20 min", 414.3f, 323.4f),
    ).flatten()

    @Test fun eggTableBecomesLabelledRows() {
        val tables = TableRows.find(eggTable)
        assertEquals(1, tables.size)
        assertEquals(
            listOf(
                "Soft-Boiled: 6, 2.25 oz., 8 min", "Soft-Boiled: 12, 1.75 oz., 10 min",
                "Medium Egg: 6, 2.75 oz., 11 min", "Medium Egg: 12, 2 oz., 13 min",
                "Hard-Boiled: 6, 5 oz., 18 min", "Hard-Boiled: 12, 3.5 oz., 20 min",
            ),
            tables.single().rows,
        )
    }

    private val header = listOf(
        cell("Style", 72.8f, 190.9f), cell("# of Eggs", 179.6f, 190.9f),
        cell("amount of water", 287f, 190.9f), cell("Time", 407.7f, 190.9f),
    ).flatten()

    @Test fun headerNamesEachValue() {
        val table = TableRows.find(header + eggTable).single()
        assertEquals("Soft-Boiled: # of Eggs 6, amount of water 2.25 oz., Time 8 min", table.rows.first())
        assertEquals("Hard-Boiled: # of Eggs 12, amount of water 3.5 oz., Time 20 min", table.rows.last())
        assertTrue(table.boxes.any { it.holds(72.8f, 190.9f) })
    }

    @Test fun headerWithTheWrongColumnsIsIgnored() {
        val table = TableRows.find(cell("Style", 72.8f, 190.9f) + cell("Time", 407.7f, 190.9f) + eggTable).single()
        assertEquals("Soft-Boiled: 6, 2.25 oz., 8 min", table.rows.first())
    }

    @Test fun surroundingProseIsNotPartOfTheTable() {
        val prose = cell("large or jumbo eggs may require additional water and time", 36f, 123.6f) +
            cell("TIP: If you live at a higher altitude, add up to 25% more water", 36f, 361.8f)
        assertEquals(6, TableRows.find(prose + eggTable).single().rows.size)
    }

    @Test fun boxesCoverTheTableButNotProse() {
        val boxes = TableRows.find(eggTable).single().boxes
        eggTable.forEach { w -> assertTrue(w.text, boxes.any { it.holds(w.x0, w.y) }) }
        assertTrue(boxes.none { it.holds(36f, 361.8f) })
    }

    @Test fun aLoneMatchIsLeftAlone() = assertEquals(
        emptyList<TableRows.Table>(),
        TableRows.find(eggTable.filter { it.y < 240f }),
    )

    @Test fun paragraphsAreLeftAlone() {
        val page = (0 until 12).flatMap { cell("Fill the cup with 3 oz. of water and press start", 36f, 90f + it * 11f) }
        assertEquals(emptyList<TableRows.Table>(), TableRows.find(page))
    }

    @Test fun tableWithLabelsOnTheirOwnRowIsLeftAlone() {
        val page = listOf(
            cell("4 poached eggs + 4 soft boiled eggs", 550f, 222.3f), cell("3 oz.", 830f, 219f),
            cell("7 mini omelettes", 551f, 264.6f), cell("4 oz.", 830f, 264.6f),
            cell("7 mini omelettes + 4 hard boiled eggs", 551f, 310.2f), cell("4 oz.", 830f, 310.2f),
        ).flatten()
        assertEquals(emptyList<TableRows.Table>(), TableRows.find(page))
    }

    @Test fun oddCellCountIsLeftAlone() {
        val broken = eggTable.filterNot { it.text == "10" || it.text == "13" }
        assertEquals(emptyList<TableRows.Table>(), TableRows.find(broken.filterNot { it.y == 323.4f && it.x0 > 400f }))
    }

    @Test fun blocksKeepRowsWhole() = assertEquals(
        listOf("a b c\nd e f", "g h i"),
        TableRows.blocks(listOf("a b c", "d e f", "g h i"), maxWords = 6),
    )

    @Test fun blocksKeepAnOversizedRow() =
        assertEquals(listOf("a b c d"), TableRows.blocks(listOf("a b c d"), maxWords = 2))
}
