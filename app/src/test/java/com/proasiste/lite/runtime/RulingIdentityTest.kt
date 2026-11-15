package com.proasiste.lite.runtime

import com.proasiste.lite.graph.Cell
import com.proasiste.lite.graph.ColumnDef
import com.proasiste.lite.graph.ColumnKind
import com.proasiste.lite.graph.GraphNode
import com.proasiste.lite.graph.TableQuestions
import com.proasiste.lite.graph.TableRow
import com.proasiste.lite.runtime.DeepSeekFacts.PageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A ruling is named by its own number, not by one it cites: 188 of Dr. García's files were before this. */
class RulingIdentityTest {
    private fun id(title: String, vararg pages: String) = LegalFacts.identity(pages.mapIndexed { i, t -> PageText(i + 1, t) }, title)

    @Test fun anInadmissibilityResolutionIsNotNamedAfterTheRulingItCites() {
        // Case 16.231's resolution cites STC Rol 12.158 in its reasoning and states its own number at the end.
        val r = id("Inadmisible_-_84_N_6_-_16231.pdf",
            "Santiago, veinte de marzo de dos mil veinticinco.\nVISTOS Y CONSIDERANDO: 1°. Que, como se resolvió en STC Rol N° 12.158-21, …",
            "SE RESUELVE: Que se declara inadmisible el requerimiento.\nRol N° 16.231-25-INA\nPronunciada por el Excmo. Tribunal Constitucional")
        assertEquals("STC Rol 16.231", r.name)
        assertEquals(LegalFacts.FROM_CLOSING, r.from)
    }

    @Test fun theOtherWaysARulingStatesItsNumber() {
        assertEquals("STC Rol 17.292", id("STC Rol 17.292-26 INA.pdf", "texto que cita Rol N° 9.000").name)
        assertEquals(LegalFacts.FROM_TITLE, id("STC Rol 17.292-26 INA.pdf", "x").from)
        val heading = id("documento.pdf", "Sentencia Rol 17.089-25 INA\n[22 de julio de 2026]\nen el proceso Rol C-1753-2025", "Que se acoge, como en Rol N° 8.000-20.")
        assertEquals(LegalFacts.FROM_CLOSING, heading.from)  // the last role on the last pages wins over the heading when the file name says nothing
        // The file name's number is used when the text names it as a role anywhere.
        val named = id("16124.pdf", "TRIBUNAL CONSTITUCIONAL\nen el proceso Rol N° 19.199-24 ante la Corte de Apelaciones", "Rol N° 16.124-25-INA")
        assertEquals("STC Rol 16.124", named.name)
        // A file name's number the text never states is still used, rather than a number the text only cites.
        assertEquals("STC Rol 16.168", id("16168_Inadmisible.pdf", "Rol N° 161.586 de la Corte").name)
        assertEquals(LegalFacts.FROM_TITLE, id("16168_Inadmisible.pdf", "Rol N° 161.586 de la Corte").from)
    }

    @Test fun aListShowsTheDecisionThatMatched() {
        val node = GraphNode(1, "Fallo", "stc rol 12 158", "STC Rol 12.158")
        val row = TableRow(node, mapOf(
            "Resultado" to listOf(Cell("Rechazado", null, "A", 15), Cell("Inadmisible", null, "B", 1)),
            "Fecha" to listOf(Cell("2022-07-19", 20220719.0, "A", 1), Cell("2025-03-20", 20250320.0, "B", 1)),
        ))
        val defs = listOf(ColumnDef("Fecha", ColumnKind.DATE), ColumnDef("Resultado", ColumnKind.TEXT))
        val a = TableQuestions.answer("¿Cuántos fallos fueron inadmisibles?", "Fallo", listOf("fallo"), listOf(row), defs, false)!!
        assertTrue(a.text, a.text.contains("STC Rol 12.158 · 2025-03-20 · Inadmisible · 2 decisiones"))
        assertEquals("B", a.cited.single().first.first("Resultado")!!.sourceId)
    }
}
