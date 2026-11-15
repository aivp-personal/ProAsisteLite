package com.proasiste.lite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.graph.ColumnKind
import com.proasiste.lite.graph.Graph
import com.proasiste.lite.graph.GraphColumns
import com.proasiste.lite.graph.NewFact
import com.proasiste.lite.graph.TableImport
import com.proasiste.lite.graph.TableQuestions
import com.proasiste.lite.runtime.TableFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Step 7b: CSV and .xlsx tables read, mapped onto the graph, checked, and re-synced. */
@RunWith(RobolectricTestRunner::class)
class TableImportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: Store
    private lateinit var graph: Graph
    private lateinit var columns: GraphColumns

    @Before fun open() {
        store = Store(context, null); graph = Graph(store); columns = GraphColumns(store, graph)
        store.add(Source("DOC", File(store.library, "doc.pdf").apply { writeText("doc") }.path, "pdf", "P04", "", "2026-11-15", "doc.pdf", false))
        store.add(Source("T1", File(store.library, "t1.csv").apply { writeText("t") }.path, "table", "P04", "", "2026-11-15", "fallos.csv", false))
    }
    @After fun close() = store.close()

    @Test fun csvFindsItsDelimiterAndKeepsQuotedFields() {
        val rows = TableFile.csv("﻿Rol;Fecha;Materia\n\"17.292\";02/09/2026;\"Ley; Municipalidades\"\n17.221;22-09-2026;\"Dice \"\"no\"\"\"\n")
        assertEquals(listOf("Rol", "Fecha", "Materia"), rows[0])
        assertEquals(listOf("17.292", "02/09/2026", "Ley; Municipalidades"), rows[1])
        assertEquals("Dice \"no\"", rows[2][2])
        assertEquals('\t', TableFile.delimiter("a\tb\tc\n1\t2\t3"))
        assertEquals(',', TableFile.delimiter("a,b\n\"1,5\",2"))
    }

    /** A hand-made .xlsx: shared strings, a date-formatted number, a formula's saved value, and a merged cell. */
    private fun workbook(): ByteArray {
        val parts = mapOf(
            "xl/workbook.xml" to """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Fallos" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Type="worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to """<sst><si><t>Rol</t></si><si><t>Fecha</t></si><si><t>Ministro</t></si><si><r><t>STC Rol </t></r><r><t>17.292</t></r></si><si><t>Silva</t></si><si><t>STC Rol 17.221</t></si></sst>""",
            "xl/styles.xml" to """<styleSheet><numFmts count="1"><numFmt numFmtId="164" formatCode="dd/mm/yyyy"/></numFmts><cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="164"/></cellXfs></styleSheet>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="inlineStr"><is><t>Votos</t></is></c></row>
                <row r="2"><c r="A2" t="s"><v>3</v></c><c r="B2" s="1"><v>46267</v></c><c r="C2" t="s"><v>4</v></c><c r="D2"><f>SUM(1,6)</f><v>7</v></c></row>
                <row r="3"><c r="A3" t="s"><v>5</v></c><c r="B3" s="1"><v>46287</v></c><c r="D3"><v>9</v></c></row>
                </sheetData><mergeCells count="1"><mergeCell ref="C2:C3"/></mergeCells></worksheet>""",
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> parts.forEach { (n, x) -> z.putNextEntry(ZipEntry(n)); z.write(x.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun xlsxReadsDatesFormulasAndMergedCells() {
        val sheet = TableFile.read("fallos.xlsx", workbook()).single()
        assertEquals("Fallos", sheet.name)
        assertEquals(listOf("Rol", "Fecha", "Ministro", "Votos"), sheet.headers)
        assertEquals(listOf("STC Rol 17.292", "2026-09-02", "Silva", "7"), sheet.body[0])
        assertEquals(listOf("STC Rol 17.221", "2026-09-22", "Silva", "9"), sheet.body[1])
        assertTrue(runCatching { TableFile.read("viejo.xls", ByteArray(10)) }.isFailure)
        // What the app writes, it reads back.
        val out = ByteArrayOutputStream()
        TableFile.writeXlsx(out, "Fallo", listOf("Fallo", "Resultado"), listOf(listOf("STC Rol 17.292", "Rechazado <total> & más")))
        assertEquals(listOf("STC Rol 17.292", "Rechazado <total> & más"), TableFile.read("t.xlsx", out.toByteArray()).single().body.single())
    }

    @Test fun aSheetJoinsTheNamesDocumentsMadeAndIsChecked() {
        graph.replaceFacts("P04", "DOC", listOf(NewFact(1, "Ministro", "María Pía Silva", "INTEGRA", "Fallo", "STC Rol 17.292", "x")), { FloatArray(Store.DIMENSIONS) })
        val sheet = TableFile.Sheet("Hoja1", listOf(listOf("Causa", "Fecha", "Monto", "Ministro"), listOf("STC Rol 17.292", "02/09/2026", "1.234,5", "María Pía Silva"), listOf("STC Rol 17.221", "22/09/2026", "10", "Otro")))
        val map = TableImport.propose(sheet, "fallos.csv", columns.typeKeys("P04"))
        assertEquals("Fallo", map.type)  // its first column names a ruling the documents made
        assertEquals(0, map.nameColumn)
        assertEquals(listOf(ColumnKind.TEXT, ColumnKind.DATE, ColumnKind.NUMBER, ColumnKind.TEXT), map.columns.map { it.kind })
        assertEquals("Ministro", map.columns[3].linkTo)  // half its names are ministers the documents made: proposed as a link
        assertTrue(TableImport.problems(sheet, map, true).isEmpty())
        val bad = TableFile.Sheet("Hoja1", sheet.rows + listOf(listOf("STC Rol 1", "ayer", "x", "")))
        val problems = TableImport.problems(bad, map, true)
        assertTrue(problems.toString(), problems.any { "Fecha" in it && "4" in it } && problems.any { "Monto" in it })
        assertEquals("2026-09-02", TableImport.date("2 de septiembre de 2026"))
        assertNull(TableImport.date("31/02/2026"))
    }

    @Test fun reimportingResyncsAndLinksBecomeFacts() {
        graph.replaceFacts("P04", "DOC", listOf(NewFact(1, "Fallo", "STC Rol 17.221", "CITA_PRECEDENTE", "Fallo", "STC Rol 9.000", "x")), { FloatArray(Store.DIMENSIONS) })
        fun load(rows: List<List<String>>) {
            val sheet = TableFile.Sheet("Fallos", listOf(listOf("Rol", "Resultado", "Ministro")) + rows)
            val map = TableImport.propose(sheet, "fallos.csv", emptyMap()).let { m -> m.copy(type = "Fallo", columns = m.columns.map { if (it.header == "Ministro") it.copy(linkTo = "Ministro") else it }) }
            columns.replaceTable("P04", "T1", TableImport.ORIGIN, map.type, map.defs, TableImport.rowsOf(sheet, map))
        }
        load(listOf(listOf("STC Rol 17.292", "Rechazado", "Silva"), listOf("STC Rol 17.221", "Acogido", "Silva; Marzi"), listOf("STC Rol 16.000", "Rechazado", "")))
        assertEquals(3, columns.rows("P04", "Fallo").size)
        // Links are facts from the table, citing their row; the ministers are nodes now, ready for a ministers table.
        val facts = graph.view("P04", "T1")
        assertEquals(3, facts.size)
        assertTrue(facts.all { it.type == "MINISTRO" && it.page in 2..3 })
        // Friday's export: 17.292 changed, 17.221 left the file, 17.400 is new.
        load(listOf(listOf("STC Rol 17.292", "Acogido", "Silva"), listOf("STC Rol 16.000", "Rechazado", ""), listOf("STC Rol 17.400", "Rechazado", "Mera")))
        val rows = columns.rows("P04", "Fallo").associateBy { it.node.name }
        assertEquals(setOf("STC Rol 17.292", "STC Rol 16.000", "STC Rol 17.400"), rows.keys)
        assertEquals(listOf("Acogido"), rows.getValue("STC Rol 17.292").values("Resultado"))
        // The ruling that left the table keeps its node: a document still cites it.
        assertTrue(graph.findNodes("P04", "17.221").isNotEmpty())
        assertEquals(setOf("Silva", "Mera"), graph.view("P04", "T1").map { it.to.name }.toSet())
        // Questions work on an imported type too.
        val a = TableQuestions.answer("¿Cuántos fallos fueron rechazados?", "Fallo", listOf("fallo"), columns.rows("P04", "Fallo"), listOf(com.proasiste.lite.graph.ColumnDef("Resultado", ColumnKind.TEXT)), false)!!
        assertEquals(2, a.cited.size)
    }

    @Test fun namesSpelledTwoWaysAreTheSameOnlyWhenTheyShouldBe() {
        val S = com.proasiste.lite.graph.SameName
        // Signatures in the rulings against the Tribunal's own list.
        assertTrue(S.same("Daniela Marzi Muñoz", "Daniela Beatriz Marzi Muñoz"))
        assertTrue(S.same("Raúl Mera Muñoz", "Raúl Eduardo Mera Muñoz"))
        assertTrue(S.same("Silva Gallinato", "María Pía Silva Gallinato"))
        assertTrue(S.same("MARIO GÓMEZ MONTOYA", "Mario Gómez Montoya"))
        assertTrue(!S.same("Raúl Mera Muñoz", "Daniela Marzi Muñoz"))
        assertTrue(!S.same("Miguel Ángel Fernández González", "Francisco Fernández Fredes"))
        assertTrue(!S.same("Mario Fernández Baeza", "Miguel Ángel Fernández González"))
        // A row joins the one name in the graph spelled another way, and only when there is exactly one.
        val graphNames = listOf("Daniela Beatriz Marzi Muñoz", "Raúl Eduardo Mera Muñoz", "María Pía Silva Gallinato")
        assertEquals("Daniela Beatriz Marzi Muñoz", S.match("Daniela Marzi Muñoz", graphNames))
        assertNull(S.match("María Pía Silva Gallinato", graphNames))  // already spelled the same
        assertNull(S.match("Nancy Yáñez Fuenzalida", graphNames))
        val (rows, matched) = S.resolve(listOf(com.proasiste.lite.graph.TableRowIn("Raúl Mera Muñoz", 2, emptyList(), emptyList())), "Ministro", mapOf("Ministro" to graphNames))
        assertEquals("Raúl Eduardo Mera Muñoz", rows.single().name); assertEquals(1, matched)
        // Case numbers written two ways.
        assertEquals("17292", S.caseNumber("STC Rol 17.292"))
        assertEquals("17292", S.caseNumber("Rol N° 17.292-26"))
        assertEquals("17292", S.caseNumber("17292-2026"))
        assertNull(S.caseNumber("Ley N° 18.695"))
        fun n(name: String, d: Int) = com.proasiste.lite.graph.GraphPicture.Node(Graph.keyOf(name), name, "x", d)
        val pairs = S.likely(listOf(n("STC Rol 17.292", 9), n("Rol N° 17.292-26", 2), n("Daniela Beatriz Marzi Muñoz", 5), n("Daniela Marzi Muñoz", 1), n("Ley N° 18.695", 4)))
        assertEquals(setOf("STC Rol 17.292" to "Rol N° 17.292-26", "Daniela Beatriz Marzi Muñoz" to "Daniela Marzi Muñoz"), pairs.map { it.first.name to it.second.name }.toSet())
    }

    @Test fun theTribunalsMinistersListImportsAsATable() {
        // The list as given: Nombre and RUT, semicolon-separated. RUTs are text (dots and a K), not numbers.
        val csv = "Nombre;RUT\nIván Aróstica Maldonado;6.400.980-K\nDaniela Marzi Muñoz;13.765.795-3\nNelson Pozo Silva;5.431.230-k\n"
        val sheet = TableFile.read("ministros-tc.csv", csv.toByteArray()).single()
        val map = TableImport.propose(sheet, "ministros-tc.csv", emptyMap())
        assertEquals(0, map.nameColumn)
        assertEquals(ColumnKind.TEXT, map.columns[1].kind)
        assertTrue(TableImport.problems(sheet, map, true).isEmpty())
    }
}
