package com.proasiste.lite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.graph.ColumnDef
import com.proasiste.lite.graph.ColumnKind
import com.proasiste.lite.graph.Graph
import com.proasiste.lite.graph.GraphColumns
import com.proasiste.lite.graph.GraphSchema
import com.proasiste.lite.graph.NewFact
import com.proasiste.lite.graph.NewValue
import com.proasiste.lite.graph.TableQuestions
import com.proasiste.lite.runtime.DeepSeekFacts.PageText
import com.proasiste.lite.runtime.RulingColumns
import com.proasiste.lite.runtime.RulingSkeleton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Step 7a and 7c: column values on graph nodes, read from rulings by the rules, and questions answered from them. */
@RunWith(RobolectricTestRunner::class)
class TableColumnsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: Store
    private lateinit var graph: Graph
    private lateinit var columns: GraphColumns
    private val defs = RulingColumns.DEFS

    @Before fun open() {
        store = Store(context, null); graph = Graph(store); columns = GraphColumns(store, graph)
        for (id in listOf("A", "B", "C", "D")) store.add(Source(id, File(store.library, "$id.pdf").apply { writeText(id) }.path, "pdf", "P04", "", "2026-11-15", "$id.pdf", false))
    }
    @After fun close() = store.close()

    private fun ruling(source: String, name: String, date: String, outcome: String, judges: List<String>, dissent: List<String> = emptyList(), norms: List<String> = emptyList()) =
        columns.replace("P04", source, RulingColumns.ORIGIN, RulingColumns.TYPE, name, defs,
            listOf(NewValue(RulingColumns.DATE, date, 1), NewValue(RulingColumns.OUTCOME, outcome, 9), NewValue(RulingColumns.KIND, "Requerimiento de inaplicabilidad por inconstitucionalidad", 1)) +
                judges.map { NewValue(RulingColumns.JUDGES, it, 20) } + dissent.map { NewValue(RulingColumns.DISSENT, it, 12) } + norms.map { NewValue(RulingColumns.NORMS, it, 1) })

    private fun fill() {
        val bench = listOf("María Pía Silva Gallinato", "Daniela Beatriz Marzi Muñoz", "Raúl Eduardo Mera Muñoz")
        ruling("A", "STC Rol 17.292", "2026-09-02", "Rechazado", bench, norms = listOf("Ley N° 18.695, art. 60 inc. 8"))
        ruling("B", "STC Rol 17.221", "2026-07-10", "Acogido", bench, dissent = listOf("María Pía Silva Gallinato"), norms = listOf("Código del Trabajo, art. 472"))
        ruling("C", "STC Rol 16.900", "2025-12-01", "Rechazado", bench, dissent = listOf("Raúl Eduardo Mera Muñoz"))
        ruling("D", "STC Rol 16.950", "2025-11-20", "Acogido en parte", bench, dissent = listOf("María Pía Silva Gallinato"))
    }

    private fun ask(q: String, english: Boolean = false) =
        TableQuestions.answer(q, RulingColumns.TYPE, listOf("fallo", "sentencia", "ruling", "case", "requerimiento"), columns.rows("P04", RulingColumns.TYPE), defs, english)

    @Test fun valuesAreTypedKeptPerWriterAndKeepTheirNode() {
        fill()
        val rows = columns.rows("P04", RulingColumns.TYPE)
        assertEquals(4, rows.size)
        val a = rows.first { it.node.name == "STC Rol 17.292" }
        assertEquals(20260902.0, a.first(RulingColumns.DATE)!!.num!!, 0.0)
        assertEquals(3, a.values(RulingColumns.JUDGES).size)
        assertEquals("A", a.sourceId)
        // A node with values stays when the graph rebuilds that file with no facts.
        graph.replaceFacts("P04", "A", emptyList()) { FloatArray(Store.DIMENSIONS) }
        assertEquals(4, columns.rows("P04", RulingColumns.TYPE).size)
        // Another writer's values for the same file sit beside the rules' and aren't replaced by them.
        columns.replace("P04", "A", "planilla", RulingColumns.TYPE, "STC Rol 17.292", defs, listOf(NewValue(RulingColumns.OUTCOME, "Acogido", 0)))
        ruling("A", "STC Rol 17.292", "2026-09-02", "Rechazado", emptyList())
        assertEquals(setOf("Rechazado", "Acogido"), columns.rows("P04", RulingColumns.TYPE).first { it.node.name == "STC Rol 17.292" }.values(RulingColumns.OUTCOME).toSet())
        // A date that isn't a date is skipped; deleting the file takes its values and its node.
        assertEquals(0, columns.replace("P04", "C", RulingColumns.ORIGIN, RulingColumns.TYPE, "STC Rol 16.900", defs, listOf(NewValue(RulingColumns.DATE, "ayer", 1))))
        assertTrue(store.deleteSource("B"))
        assertEquals(setOf("STC Rol 17.292", "STC Rol 16.950"), columns.rows("P04", RulingColumns.TYPE).map { it.node.name }.toSet())
        assertEquals(listOf("Fallo" to 2), columns.types("P04"))
    }

    @Test fun countsFiltersAndGroups() {
        fill()
        val rejected = ask("¿Cuántos fallos fueron rechazados?")!!
        assertTrue(rejected.text, rejected.text.startsWith("2 fallos (Resultado: Rechazado)."))
        assertEquals(listOf("STC Rol 17.292", "STC Rol 16.900"), rejected.cited.map { it.first.node.name })
        assertEquals("Resultado", rejected.cited.first().second)

        // "acogidos en parte" picks the longer value; a year filters the date.
        assertEquals(listOf("STC Rol 16.950"), ask("¿Qué fallos fueron acogidos en parte?")!!.cited.map { it.first.node.name })
        assertEquals(listOf("STC Rol 16.900", "STC Rol 16.950"), ask("¿Cuántas sentencias de 2025 hay?")!!.cited.map { it.first.node.name }.sorted())

        // A surname in a person column: the role word picks the column.
        assertEquals(listOf("STC Rol 17.221", "STC Rol 16.950"), ask("¿En qué fallos disintió Silva?")!!.cited.map { it.first.node.name })
        // Article and law numbers find the challenged norm.
        assertEquals(listOf("STC Rol 17.292"), ask("¿Qué fallos impugnan el artículo 60 de la ley 18.695?")!!.cited.map { it.first.node.name })

        // Grouping, and "who dissents most".
        val byOutcome = ask("fallos por resultado")!!
        assertTrue(byOutcome.text, byOutcome.text.contains("Rechazado: 2") && byOutcome.text.contains("Acogido: 1"))
        val most = ask("¿Quién disiente más en los fallos?")!!
        assertEquals("Disidentes", most.groupedBy)
        assertTrue(most.text, most.text.contains("María Pía Silva Gallinato: 2"))
        assertTrue(ask("How many rulings were rejected?", english = true)!!.text.startsWith("2 rulings (Resultado: Rechazado)."))
    }

    @Test fun questionsTheTableShouldLeaveAlone() {
        fill()
        // About content, not counting: the normal search answers these.
        assertNull(ask("¿Qué dijo el tribunal sobre la proporcionalidad?"))
        assertNull(ask("¿Por qué se rechazó el requerimiento de Mario Meza?"))
        assertNull(ask("¿Cuántos días de plazo tiene el requirente?"))
        assertNull(TableQuestions.answer("¿Cuántos fallos hay?", RulingColumns.TYPE, listOf("fallo"), emptyList(), defs, false))
    }

    @Test fun columnsOfARealRuling() {
        val text = javaClass.getResourceAsStream("/rulings/stc-17292-26.txt")!!.bufferedReader().readText().replace("\r\n", "\n")
        val pages = Regex("===== PAGE (\\d+)\\n").split(text).drop(1).mapIndexed { i, body -> PageText(i + 1, body) }
        val (name, values) = RulingColumns.of(pages, "STC Rol 17.292-26 INA.pdf")!!
        assertEquals("STC Rol 17.292", name)
        fun v(c: String) = values.filter { it.column == c }.map { it.value }
        assertEquals(listOf("2026-09-02"), v(RulingColumns.DATE))
        assertEquals(listOf("Requerimiento de inaplicabilidad por inconstitucionalidad"), v(RulingColumns.KIND))
        assertEquals(listOf("Mario Meza Vásquez"), v(RulingColumns.FILER))
        assertEquals(listOf("Ley N° 18.695, art. 60 inc. 8"), v(RulingColumns.NORMS))
        assertEquals(8, v(RulingColumns.JUDGES).size)
        assertEquals(1, v(RulingColumns.OUTCOME).size)
        assertTrue(values.all { it.page >= 1 })
    }

    @Test fun decisionsReadAsOutcomes() {
        fun o(s: String) = RulingSkeleton.Decision.outcome(s)
        assertEquals("Rechazado", o("SE RESUELVE: I. QUE SE RECHAZA EL REQUERIMIENTO DEDUCIDO A FOJAS 1. II. ÁLCESE LA SUSPENSIÓN"))
        assertEquals("Acogido", o("SE RESUELVE: QUE, SE ACOGE EL REQUERIMIENTO DE INAPLICABILIDAD DEDUCIDO A LO PRINCIPAL DE FOJAS 1"))
        assertEquals("Acogido en parte", o("I. QUE SE ACOGE PARCIALMENTE EL REQUERIMIENTO, SÓLO RESPECTO DEL ARTÍCULO 5"))
        assertEquals("Conforme", o("SE DECLARA: 1. QUE LOS ARTÍCULOS 10 Y 15 DEL PROYECTO SON CONFORMES CON LA CONSTITUCIÓN POLÍTICA. 2. QUE NO SE EMITE PRONUNCIAMIENTO"))
        assertEquals("Conforme en parte", o("1. QUE EL ARTÍCULO 2 ES CONFORME CON LA CONSTITUCIÓN. 2. QUE EL ARTÍCULO 3 NO ES CONFORME CON LA CONSTITUCIÓN"))
        assertNull(o("SE DECLARA: QUE NO SE EMITE PRONUNCIAMIENTO, EN EXAMEN PREVENTIVO"))
        assertEquals(listOf("SE ACOGE EL REQUERIMIENTO DEDUCIDO A LO PRINCIPAL"),
            RulingSkeleton.Decision.clauses("SE RESUELVE:\nI. QUINIENTOS OCHENTA Y OCHO QUE SE ACOGE EL REQUERIMIENTO DEDUCIDO A LO PRINCIPAL\nII. ÁLCESE LA SUSPENSIÓN").take(1).map { it.first.removePrefix("QUE ") })
    }

    @Test fun typesDeclareColumnsThatSurviveSaving() {
        val schema = GraphSchema(GraphSchema.CUSTOM, listOf(com.proasiste.lite.graph.NodeType("Contrato", "Un contrato", listOf(ColumnDef("Monto", ColumnKind.NUMBER), ColumnDef("Firmado", ColumnKind.DATE)))))
        assertEquals(schema.nodes, GraphSchema.fromJson(schema.toJson()).nodes)
        assertEquals(RulingColumns.DEFS, GraphSchema().columnsOf("Fallo"))
        assertEquals(RulingColumns.DEFS, GraphSchema.legalPreset().columnsOf("Fallo"))
        assertEquals(1234.56, GraphColumns.numberOf(ColumnKind.NUMBER, "1.234,56")!!, 1e-9)
        assertEquals(1234.56, GraphColumns.numberOf(ColumnKind.NUMBER, "1,234.56")!!, 1e-9)
    }

    @Test fun schemaElevenUpgrades() {
        val name = "values-upgrade.db"
        context.deleteDatabase(name)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            Store.MIGRATIONS.take(11).flatten().forEach(db::execSQL); db.version = 11
        }
        Store(context, name).use { s -> assertEquals(emptyList<Pair<String, Int>>(), GraphColumns(s, Graph(s)).types("P04")) }
        context.deleteDatabase(name)
    }
}
