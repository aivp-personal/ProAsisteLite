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

    @Test fun whatARulingDecidedComesFromItsDecisionSection() {
        val a = GraphNode(1, "Fallo", "stc rol 12 158", "STC Rol 12.158")
        val b = GraphNode(2, "Fallo", "stc rol 16 231", "STC Rol 16.231")
        val rows = listOf(
            TableRow(a, mapOf("Resultado" to listOf(Cell("Rechazado", null, "A", 15)), "Fecha" to listOf(Cell("2022-07-19", 20220719.0, "A", 1)),
                "Tipo" to listOf(Cell("Requerimiento de inaplicabilidad por inconstitucionalidad", null, "A", 1)))),
            TableRow(b, mapOf("Resultado" to listOf(Cell("Inadmisible", null, "B", 3), Cell("Rechazado", null, "C", 20)),
                "Fecha" to listOf(Cell("2025-03-20", 20250320.0, "B", 1), Cell("2026-01-10", 20260110.0, "C", 1)))),
        )
        val one = TableQuestions.decision("¿Qué resolvió la STC 12.158?", rows, false)!!
        assertEquals("STC Rol 12.158: Rechazado (Requerimiento de inaplicabilidad por inconstitucionalidad, 2022-07-19) [1]", one.text)
        assertEquals(15, one.cited.single().first.first("Resultado")!!.page)
        val two = TableQuestions.decision("que se resolvio en el Rol 16.231", rows, false)!!
        assertTrue(two.text, two.text.startsWith("2 decisiones:") && "Inadmisible (2025-03-20) [1]" in two.text && "Rechazado (2026-01-10) [2]" in two.text)
        assertEquals(listOf("B", "C"), two.cited.map { it.first.first("Resultado")!!.sourceId })
        // Not asking what was decided, a ruling the table doesn't know, or no number: search answers.
        assertEquals(null, TableQuestions.decision("¿Quién redactó la STC 12.158?", rows, false))
        assertEquals(null, TableQuestions.decision("¿Qué resolvió la STC 9.999?", rows, false))
        assertEquals(null, TableQuestions.decision("¿Qué resolvió el tribunal sobre la proporcionalidad?", rows, false))
    }

    @Test fun signatureJunkIsNotAName() {
        val n = RulingSkeleton.Names
        for (ok in listOf("Daniela Beatriz Marzi Muñoz", "MARÍA PÍA SILVA GALLINATO", "Mario Adolfo del Carmen Fernández Baeza", "José Luis Cea Egaña"))
            assertTrue(ok, n.isPerson(ok))
        for (junk in listOf("Y TENIENDO PRESENTE lo preceptuado en artículo 93", "Rodrigo 19°. Que", "CERTIFICO: Que Presidenta", "Redactó sentencia", "Maria Luisa Brahm Barril. CERTIFICO", "Tribunal"))
            assertTrue(junk, !n.isPerson(junk))
    }

    @Test fun aPartyNamedInTheQuestionPicksItsRulings() {
        fun row(id: Long, name: String, filer: String, outcome: String, date: String) = TableRow(GraphNode(id, "Fallo", name.lowercase(), name),
            mapOf("Requirente" to listOf(Cell(filer, null, "S$id", 1)), "Resultado" to listOf(Cell(outcome, null, "S$id", 9)), "Fecha" to listOf(Cell(date, null, "S$id", 1))))
        val rows = listOf(row(1, "STC Rol 16.892", "Salcobrand S.A.", "Rechazado", "2026-01-10"), row(2, "STC Rol 16.923", "Sociedad Kimen Montessori SpA", "Rechazado", "2025-12-01"),
            row(3, "STC Rol 17.063", "Patricia Quiroz Riffo", "Rechazado", "2026-02-02"), row(4, "STC Rol 17.264", "ICH Ingeniería S.A.", "Acogido", "2026-03-03"),
            row(5, "STC Rol 17.265", "ICH Ingeniería S.A.", "Acogido", "2026-03-04"), row(6, "STC Rol 16.930", "Miguel Eduardo Vargas Correa", "Acogido", "2026-05-13"),
            row(7, "STC Rol 1.000", "Ilustre Municipalidad de Osorno", "Rechazado", "2010-01-01"), row(8, "STC Rol 1.001", "Ilustre Municipalidad de Temuco", "Rechazado", "2010-01-02"))
        assertEquals(listOf("STC Rol 16.892"), TableQuestions.byParty("¿Cómo resolvió el Tribunal el caso de Salcobrand?", rows).map { it.node.name })
        assertEquals(listOf("STC Rol 16.923"), TableQuestions.byParty("¿Qué norma impugnó Kimen Montessori?", rows).map { it.node.name })
        assertEquals(listOf("STC Rol 17.264", "STC Rol 17.265"), TableQuestions.byParty("¿El Tribunal acogió el requerimiento de ICH Ingeniería?", rows).map { it.node.name })
        assertTrue(TableQuestions.byParty("¿Qué dijeron las municipalidades?", rows).isEmpty())
        assertEquals("STC Rol 17.063: Rechazado (2026-02-02) [1]", TableQuestions.decision("¿Cómo resolvió el Tribunal el caso de Patricia Quiroz Riffo?", rows, false)!!.text)
        assertEquals("STC Rol 16.930: 2026-05-13 [1]", TableQuestions.decision("¿Cuándo se dictó la sentencia de Miguel Eduardo Vargas Correa?", rows, false)!!.text)
        // Several outcomes named: a breakdown by outcome, not a filter on one.
        val defs = listOf(ColumnDef("Resultado", ColumnKind.TEXT))
        val g = TableQuestions.answer("¿Cuántas sentencias acogieron, rechazaron o declararon inadmisible el requerimiento?", "Fallo", listOf("sentencia"), rows, defs, false)!!
        assertEquals("Resultado", g.groupedBy)
    }

    @Test fun aFactAboutOneRulingComesFromItsRow() {
        fun row(id: Long, name: String, vararg cols: Pair<String, List<String>>) = TableRow(GraphNode(id, "Fallo", name.lowercase(), name),
            cols.associate { (c, vs) -> c to vs.map { Cell(it, null, "S$id", 3) } })
        val rows = listOf(
            row(1, "STC Rol 16.708", "Requirente" to listOf("Conjunto Residencial Las Terrazas de Cerrillos"), "Disidentes" to listOf("Miguel Ángel Fernández González", "Héctor Mery Romero", "Marcela Inés Peredo Rojas", "Mario René Gómez Montoya")),
            row(2, "STC Rol 17.089", "Requirente" to listOf("David Orlando Sepúlveda Cid, Juez Suplente del Tercer Juzgado de Letras de Iquique"), "Resultado" to listOf("Acogido"), "Normas impugnadas" to listOf("Ley N° 18.101, art. 8 N° 2")),
            row(3, "STC Rol 11.825", "Requirente" to listOf("David Andrés Vega Vega"), "Resultado" to listOf("Acogido")),
            row(4, "STC Rol 16.923", "Requirente" to listOf("Sociedad Kimen Montessori SpA"), "Normas impugnadas" to listOf("Código de Procedimiento Civil, art. 358 N° 4 y 5")),
            row(5, "STC Rol 17.063", "Requirente" to listOf("Patricia Quiroz Riffo"), "Normas impugnadas" to listOf("Ley N° 19.947, art. 46 letra c")),
            row(6, "STC Rol 16.878", "Requirente" to listOf("Agrícola Llahuen S.A."), "Disidentes" to listOf("Héctor Mery Romero")),
            row(7, "STC Rol 16.930", "Requirente" to listOf("Miguel Eduardo Vargas Correa"), "Fecha" to listOf("2026-05-13")),
            row(8, "STC Rol 16.361", "Requirente" to listOf("Norma Del Carmen Caamaño Rodríguez"), "Normas impugnadas" to listOf("DL N° 2.695, art. 15"), "Resultado" to listOf("Rechazado")),
        ) + (10L..17L).map { row(it, "STC Rol $it", "Requirente" to listOf("Miguel Vargas Soto $it", "Eduardo Correa Pinto")) }  // the same words in many filers
        fun ask(q: String) = TableQuestions.decision(q, rows, false)?.text
        assertEquals("STC Rol 16.708: Miguel Ángel Fernández González, Héctor Mery Romero, Marcela Inés Peredo Rojas, Mario René Gómez Montoya [1]", ask("¿Qué ministros votaron en contra en la sentencia Rol 16.708?"))
        assertEquals("STC Rol 16.878: Héctor Mery Romero [1]", ask("¿Qué ministros disintieron en el caso de Agrícola Llahuen?"))
        assertEquals("STC Rol 17.089: David Orlando Sepúlveda Cid, Juez Suplente del Tercer Juzgado de Letras de Iquique [1]", ask("¿Quién presentó el requerimiento sobre arrendamiento en Iquique?"))
        assertEquals("STC Rol 17.089: Acogido [1]", ask("¿Cómo resolvió el Tribunal el requerimiento de David Sepúlveda Cid?"))
        assertEquals("STC Rol 16.923: Código de Procedimiento Civil, art. 358 N° 4 y 5 [1]", ask("¿Qué norma impugnó Kimen Montessori?"))
        assertEquals("STC Rol 17.063: Ley N° 19.947, art. 46 letra c [1]", ask("¿Qué norma de la Ley de Matrimonio Civil fue impugnada?"))
        assertEquals("STC Rol 16.930: 2026-05-13 [1]", ask("¿Cuándo se dictó la sentencia de Miguel Eduardo Vargas Correa?"))
        assertEquals("STC Rol 16.361: Rechazado [1]", ask("¿Qué resolvió el Tribunal en el caso de Norma Caamaño?"))
        // A common word that is also in a filer's name doesn't pick that filer.
        val withCompany = rows + row(20, "STC Rol 15.183", "Requirente" to listOf("Transportes Tecnología y Giros EGT Limitada"))
        assertTrue(TableQuestions.byParty("¿Qué boletín corresponde al proyecto de ley sobre tecnología y conocimiento?", withCompany).isEmpty())
        // What was argued or said, a question about a topic, not a ruling: search answers.
        assertEquals(null, ask("¿Qué alegó la requirente en la sentencia Rol 16.708?"))
        assertEquals(null, ask("¿Qué dijo el voto en contra en la sentencia Rol 16.708?"))
        assertEquals(null, ask("¿Por qué los ministros disidentes querían acoger el requerimiento de Agrícola Llahuen?"))
        assertEquals(null, ask("¿Qué norma se impugna con más frecuencia?"))
        assertEquals(null, ask("¿Qué ministros votaron en contra en los casos de pensiones?"))
    }

    @Test fun aFilersNameLosesWhatVistosSaysBeforeAndAfterIt() {
        val f = RulingSkeleton.Names::filer
        assertEquals("Compañía Minera Manto de Oro", f("Y CONSIDERANDO: 1°. Que, con fecha 24 de enero de 2025, la Compañía Minera Manto de Oro"))
        assertEquals("Cesar Valenzuela Saavedra", f("Y CONSIDERANDO: 1º. Que, a fojas 1, Cesar Valenzuela Saavedra"))
        assertEquals("LOI Chile SpA", f("Introducción A fojas 1, con fecha 15 de septiembre de 2022, LOI Chile SpA."))
        assertEquals("Paulina Celedón González", f("Introducciónypreceptivalegalimpugnada A fojas 1, con fecha 25 de octubre de 2021, doña Paulina Celedón González"))
        assertEquals("Claro Chile S.A.", f("Introducción y preceptiva legal impugnada A fojas 1 y 140, Claro Chile S.A."))
        assertEquals("Eduardo Alfonso Hartwig Iturriaga", f("Solicitud de inaplicabilidad. Con fecha 14 de septiembre de 2015, don Eduardo Alfonso Hartwig Iturriaga"))
        assertEquals("Raúl Martínez Castillo", f("señor Raúl Martínez Castillo, con fecha 11 de diciembre de 2009"))
        assertEquals("Inversiones e Inmobiliaria Océano S.A. y Tenglo S.A.", f("Inversiones e Inmobiliaria Océano S.A. y Tenglo S.A. -en adelante, la parte requirente-"))
        assertEquals("Salcobrand S.A.", f("SALCOBRAND S.A."))
        assertEquals("Jilberto Isac Ayacady Asencio", f("con fecha 14 de mayo de 2025 Jilberto Isac Ayacady Asencio"))
        assertEquals("Iván Meíéndez Lados", f("con fecha ii de diciembre de dos mil dieciocho, Iván Meíéndez Lados"))
        assertEquals("Nexxo S.A", f("Nexxo S.A, representada convencionalmente por Javiera Vega Vilicic"))
        assertEquals("Negocios de Familia SpA", f("Negocios de Familia SpA, en presentación rectificada a fojas 70 del día 29 del mismo mes y año"))
    }

    @Test fun theChallengedNormsKeepTheirPartsAndStopAtTheCodesName() {
        fun norms(caption: String) = RulingSkeleton.Header.norms(caption)
        assertEquals(listOf("Código de Procedimiento Civil, art. 358 N° 4 y 5"),
            norms("REQUERIMIENTO DE INAPLICABILIDAD POR INCONSTITUCIONALIDAD RESPECTO DEL ARTÍCULO 358, NUMERALES 4° Y 5°, DEL CÓDIGO DE PROCEDIMIENTO CIVIL SOCIEDAD KIMEN MONTESSORI SPA"))
        assertEquals(listOf("Ley N° 18.101, art. 8 N° 2"), norms("REQUERIMIENTO DE INAPLICABILIDAD RESPECTO DEL ARTÍCULO 8°, NUMERAL 2), DE LA LEY N° 18.101"))
        assertEquals(listOf("Ley N° 19.947, art. 46 letra c"), norms("REQUERIMIENTO DE INAPLICABILIDAD RESPECTO DEL ARTÍCULO 46, LETRA C), DE LA LEY N° 19.947, DE MATRIMONIO CIVIL"))
        assertEquals(listOf("Código del Trabajo, art. 429 inc. 1"), norms("REQUERIMIENTO DE INAPLICABILIDAD RESPECTO DEL ARTÍCULO 429, INCISO PRIMERO, DEL CÓDIGO DEL TRABAJO"))
        assertEquals(listOf("Código de Procedimiento Civil, art. 499 N° 2", "Código de Procedimiento Civil, art. 500"), norms("REQUERIMIENTO DE INAPLICABILIDAD RESPECTO DE LOS ARTÍCULOS 499, N° 2 Y 500 DEL CÓDIGO DE PROCEDIMIENTO CIVIL"))
        assertEquals(listOf("Código del Trabajo, art. 472"), norms("REQUERIMIENTO DE INAPLICABILIDAD RESPECTO DEL ARTÍCULO 472 DEL CÓDIGO DE TRABAJO"))
        assertEquals(listOf("Ley N° 19.886, art. 4", "Ley N° 19.886, art. 35"),norms("REQUERIMIENTO DE INAPLICABILIDAD RESPECTO DE LOS ARTÍCULOS 4 Y 35 DE LA LEY N° 19.886"))
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
