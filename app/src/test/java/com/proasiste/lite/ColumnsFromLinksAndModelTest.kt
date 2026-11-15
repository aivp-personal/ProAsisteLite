package com.proasiste.lite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.graph.ColumnDef
import com.proasiste.lite.graph.ColumnKind
import com.proasiste.lite.graph.Graph
import com.proasiste.lite.graph.GraphColumns
import com.proasiste.lite.graph.GraphSchema
import com.proasiste.lite.graph.LinkType
import com.proasiste.lite.graph.NewFact
import com.proasiste.lite.graph.NewValue
import com.proasiste.lite.graph.NodeType
import com.proasiste.lite.runtime.DeepSeekFacts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Step 7a: columns that come from links, and columns the model reads for the project's own types. */
@RunWith(RobolectricTestRunner::class)
class ColumnsFromLinksAndModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: Store
    private lateinit var graph: Graph
    private lateinit var columns: GraphColumns

    @Before fun open() {
        store = Store(context, null); graph = Graph(store); columns = GraphColumns(store, graph)
        store.add(Source("A", File(store.library, "a.pdf").apply { writeText("a") }.path, "pdf", "P05", "", "2026-11-15", "a.pdf", false))
    }
    @After fun close() = store.close()

    @Test fun linksBecomeListColumnsUnlessTheyRunLong() {
        columns.replace("P05", "A", "reglas", "Contrato", "Contrato de arriendo", listOf(ColumnDef("Monto", ColumnKind.NUMBER)), listOf(NewValue("Monto", "500000", 1)))
        val facts = listOf(NewFact(2, "Contrato", "Contrato de arriendo", "FIRMA_CON", "Parte", "Juan Pérez", "x"), NewFact(2, "Parte", "Ana Soto", "FIRMA", "Contrato", "Contrato de arriendo", "y")) +
            (1..12).map { NewFact(3, "Contrato", "Contrato de arriendo", "CLAUSULA", "Clausula", "Cláusula $it", "c$it") }
        graph.replaceFacts("P05", "A", facts) { FloatArray(Store.DIMENSIONS) }
        val row = columns.rows("P05", "Contrato").single()
        assertEquals(listOf("500000"), row.values("Monto"))
        assertEquals(listOf("Juan Pérez"), row.values("FIRMA_CON →"))
        assertEquals(listOf("Ana Soto"), row.values("← FIRMA"))
        assertTrue(row.cells.keys.none { "CLAUSULA" in it })  // twelve clauses: too many for a column
        assertEquals(2, row.first("FIRMA_CON →")!!.page)
        assertEquals(setOf("FIRMA_CON →", "← FIRMA"), columns.linkColumns("P05", "Contrato").map { it.name }.toSet())
    }

    @Test fun theModelsValuesAreKeptOnlyWhenThePageShowsThem() {
        val schema = GraphSchema(GraphSchema.CUSTOM, listOf(NodeType("Contrato", "", listOf(ColumnDef("Monto", ColumnKind.NUMBER), ColumnDef("Firmado", ColumnKind.DATE), ColumnDef("Moneda", ColumnKind.TEXT))), NodeType("Parte")),
            listOf(LinkType("FIRMA", "", setOf("Parte"), setOf("Contrato"))))
        val page = DeepSeekFacts.PageText(4, "El contrato de arriendo, firmado el 3 de marzo de 2025, fija una renta de $ 500.000 en pesos chilenos.")
        val reply = """{"facts":[],"values":[
            {"node":"Contrato de arriendo","node_type":"Contrato","column":"Monto","value":"500.000","page":4},
            {"node":"Contrato de arriendo","node_type":"Contrato","column":"Firmado","value":"2025-03-03","page":4},
            {"node":"Contrato de arriendo","node_type":"Contrato","column":"Moneda","value":"pesos chilenos","page":4},
            {"node":"Contrato de arriendo","node_type":"Contrato","column":"Monto","value":"750000","page":4},
            {"node":"Contrato de arriendo","node_type":"Contrato","column":"Firmado","value":"ayer","page":4},
            {"node":"Contrato de arriendo","node_type":"Parte","column":"Monto","value":"500000","page":4}]}"""
        val v = DeepSeekFacts.values(reply, schema, page)
        assertEquals(listOf("Monto" to "500.000", "Firmado" to "2025-03-03", "Moneda" to "pesos chilenos"), v.map { it.column to it.value })
        // The model is told the columns and must answer them in the reply format.
        assertTrue("Contrato: Monto (number)" in DeepSeekFacts.customInstructions(schema))
        assertTrue(DeepSeekFacts.customSchema(schema).getJSONArray("required").toString().contains("values"))
        assertTrue(!DeepSeekFacts.customSchema(GraphSchema(GraphSchema.CUSTOM, listOf(NodeType("Parte")), emptyList())).getJSONArray("required").toString().contains("values"))
    }
}
