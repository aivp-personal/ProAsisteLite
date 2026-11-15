package com.proasiste.lite

import android.database.sqlite.SQLiteDatabase
import com.proasiste.lite.graph.Cell
import com.proasiste.lite.graph.GraphNode
import com.proasiste.lite.graph.TableQuestions
import com.proasiste.lite.graph.TableRow
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Not a check: asks questions of a pack's rulings table on the desktop, the way the phone's table answers them, to try a
 * change before installing it. With ASK_DB (a pack's index file), ASK_IN (questions, one per line, "ID<TAB>question")
 * and ASK_OUT set, writes each question's table answer, or "(search)" when the table doesn't answer it, to ASK_OUT.
 *
 * Run: ASK_DB=... ASK_IN=... ASK_OUT=... gradle testDebugUnitTest --tests '*DesktopTableAsk' --rerun
 */
@RunWith(RobolectricTestRunner::class)
class DesktopTableAsk {
    @Test fun ask() {
        val dbPath = System.getenv("ASK_DB").orEmpty(); val input = System.getenv("ASK_IN").orEmpty(); val out = System.getenv("ASK_OUT").orEmpty()
        assumeTrue(dbPath.isNotEmpty() && input.isNotEmpty() && out.isNotEmpty())
        val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
        val cells = linkedMapOf<String, MutableMap<String, MutableList<Cell>>>()
        db.rawQuery("SELECT sha256, name, col, value, page FROM \"values\" WHERE type = 'Fallo' ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) {
                val value = c.getString(3)
                val num = if (c.getString(2) == "Fecha") value.replace("-", "").toDoubleOrNull() else null
                cells.getOrPut(c.getString(1)) { linkedMapOf() }.getOrPut(c.getString(2)) { mutableListOf() } += Cell(value, num, c.getString(0), c.getInt(4))
            }
        }
        val rows = cells.entries.mapIndexed { i, (name, cs) -> TableRow(GraphNode(i + 1L, "Fallo", name.lowercase(), name), cs) }
        val lines = File(input).readLines().filter { it.isNotBlank() }.map { line ->
            val (id, q) = line.split("\t", limit = 2)
            val a = TableQuestions.decision(q, rows, false)
            "$id\t$q\n" + (a?.text ?: "(search)") + "\n"
        }
        File(out).writeText(lines.joinToString("\n"))
    }
}
