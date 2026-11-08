package com.proasiste.lite.runtime

import com.proasiste.lite.graph.NewFact
import com.proasiste.lite.runtime.DeepSeekFacts.PageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule-based skeleton on two real rulings (text extracted with pypdf), one with a dissent. */
class RulingSkeletonTest {
    private fun pages(resource: String): List<PageText> {
        val text = javaClass.getResourceAsStream("/rulings/$resource")!!.bufferedReader().readText().replace("\r\n", "\n")
        val pages = Regex("===== PAGE (\\d+)\\n").split(text).drop(1).mapIndexed { i, body -> PageText(i + 1, body) }
        check(pages.size >= 10) { "resource $resource read as ${pages.size} pages" }
        return pages
    }

    private val unanimous by lazy { RulingSkeleton.of(pages("stc-17292-26.txt"), "STC Rol 17.292-26 INA.pdf")!! }
    private val dissented by lazy { RulingSkeleton.of(pages("stc-17221-25.txt"), "STC Rol 17.221-26 INA.pdf")!! }

    private fun List<NewFact>.with(relation: String) = filter { it.relation == relation }
    private fun List<NewFact>.statements(relation: String) = with(relation).map { it.statement }

    @Test fun headerOfTheUnanimousRuling() {
        val f = unanimous.facts
        assertEquals("STC Rol 17.292", unanimous.ruling)
        assertEquals(listOf("El Tribunal Constitucional dictó STC Rol 17.292 el 2 de septiembre de 2026."), f.statements("DICTA"))
        assertEquals("Requerimiento de inaplicabilidad por inconstitucionalidad", f.with("TIPO").single().obj)
        val impugna = f.with("IMPUGNA").single()
        assertEquals("Mario Meza Vásquez", impugna.subject)
        assertEquals("Ley N° 18.695, art. 60 inc. 8", impugna.obj)
        assertEquals("Rol 29-2023 ante Tribunal Electoral Regional del Maule", f.with("REQUIERE_EN").single().obj)
        assertEquals("Tribunal Electoral Regional del Maule", f.with("SEGUIDA_ANTE").single().obj)
    }

    @Test fun signaturesAndDrafter() {
        assertEquals(listOf("María Pía Silva Gallinato", "Daniela Beatriz Marzi Muñoz", "Nancy Adriana Yáñez Fuenzalida", "Miguel Ángel Fernández González",
            "Raúl Eduardo Mera Muñoz", "Catalina Adriana Lagos Tschorne", "Héctor Mery Romero", "Alejandra Precht Rorris"), unanimous.ministers)
        assertEquals(8, unanimous.facts.with("INTEGRA").size)
        assertTrue(unanimous.facts.with("INTEGRA").first().statement.contains("Presidente"))
        val drafter = unanimous.facts.with("REDACTA").single()
        assertEquals("Miguel Ángel Fernández González", drafter.subject) // matched to the signature spelling, not the capitals
        assertEquals(9, drafter.page)
        assertEquals(9, dissented.ministers.size)
        assertEquals("Daniela Beatriz Marzi Muñoz", dissented.facts.with("REDACTA").first { it.objectType == "Fallo" }.subject)
    }

    @Test fun considerandosOfTheCourtWithHeadingsAndCitations() {
        val c = unanimous.facts.with("CONSIDERA")
        assertEquals((1..20).map { "Considerando $it° de STC Rol 17.292" }, c.map { it.obj })
        assertTrue(c[13].statement, c[13].statement.contains("bajo «III. RECHAZO DEL REQUERIMIENTO»"))
        assertEquals(8, c[13].page)
        val cited = unanimous.facts.with("CITA")
        assertEquals(setOf("STC Rol 327", "STC Rol 9.431"), cited.map { it.obj }.toSet())
        assertEquals("Considerando 10° de STC Rol 17.292", cited.first { it.obj == "STC Rol 327" }.subject)
        assertEquals(listOf("STC Rol 17.292 cita STC Rol 327 en el considerando 10°.", "STC Rol 17.292 cita STC Rol 9.431 en el considerando 14°."), unanimous.facts.statements("CITA_PRECEDENTE").sorted())
        // The requirente's citations ("STC Roles N° 244, 1413, 2682") are in VISTOS, not the considerandos, so not the court's.
        assertTrue(unanimous.knownRulings.containsAll(setOf("STC Rol 244", "STC Rol 1.413", "STC Rol 2.682", "STC Rol 9.431", "STC Rol 327")))
    }

    @Test fun decidedPoints() {
        val r = unanimous.facts.with("RESUELVE").map { it.obj }
        assertEquals(listOf("Rechazo del requerimiento", "Alzamiento de la suspensión del procedimiento", "Costas"), r)
        assertTrue(unanimous.facts.with("RESUELVE").all { it.page == 9 })
        assertEquals(r, dissented.facts.with("RESUELVE").map { it.obj })
    }

    @Test fun theUnanimousRulingHasNoVotes() {
        assertTrue(unanimous.facts.with("VOTO_SEPARADO").isEmpty())
        assertNull(unanimous.voteFor(8, "El Tribunal razona que la suspensión es una medida cautelar."))
    }

    @Test fun theDissentWithItsMinistersSenseAuthorAndReasoning() {
        val f = dissented.facts
        val vote = f.with("VOTO_SEPARADO").single()
        assertEquals("Disidencia de Fernández González, Mery Romero, Peredo Rojas, Gómez Montoya en STC Rol 17.221", vote.obj)
        assertTrue(vote.statement.contains("por acoger el requerimiento"))
        assertEquals(setOf("Miguel Ángel Fernández González", "Héctor Mery Romero", "Marcela Inés Peredo Rojas", "Mario René Gómez Montoya"), f.with("DISIENTE").map { it.subject }.toSet())
        assertTrue(f.with("DISIENTE").all { it.statement.contains("por acoger") && it.page == 9 })
        assertEquals("Héctor Mery Romero", f.with("REDACTA").first { it.objectType == "Voto" }.subject)
        val reasoning = f.with("CONSIDERA").filter { it.subjectType == "Voto" }
        assertTrue(reasoning.size >= 10)
        assertEquals("Considerando 1° de la disidencia en STC Rol 17.221", reasoning.first().obj)
        assertEquals(9, reasoning.first().page)
        // The dissent's citations belong to its considerandos, never to the court.
        val cited = f.with("CITA").filter { it.subject.contains("disidencia") }.map { it.obj }.toSet()
        assertTrue(cited.containsAll(setOf("STC Rol 1.838", "STC Rol 2.204", "STC Rol 2.259", "STC Rol 2.452")))
        // STC 2.204 is cited only by the dissent (the court cites 1.838 too, in its considerando 5°).
        assertTrue(f.with("CITA_PRECEDENTE").none { it.obj == "STC Rol 2.204" })
        // The pending case's roles before the Corte de Apelaciones are not citations.
        assertTrue(cited.none { it.contains("276") || it.contains("286") })
    }

    @Test fun aFactOnADissentPageGoesToTheVote() {
        val vote = dissented.voteFor(12, "Esta Judicatura ha dicho que el procedimiento legal debe ser racional y justo.")
        assertTrue(vote != null && vote.kind == "Disidencia")
        // Page 9 holds both the decision and the start of the dissent: the court's words stay with the court.
        assertNull(dissented.voteFor(9, "Se rechaza el requerimiento deducido a lo principal de fojas 1."))
    }

    @Test fun headerOfTheDissentedRuling() {
        val f = dissented.facts
        assertEquals("ICH Ingeniería S.A.", f.with("IMPUGNA").single().subject)
        assertEquals("Código del Trabajo, art. 476", f.with("IMPUGNA").single().obj)
        assertEquals("RIT O-29-2024 ante Segundo Juzgado de Letras y Garantía de Chañaral", f.with("REQUIERE_EN").single().obj)
    }

    @Test fun ordinalsAndNorms() {
        assertEquals(14, RulingSkeleton.Considerandos.ordinal("DECIMOCUARTO"))
        assertEquals(14, RulingSkeleton.Considerandos.ordinal("DÉCIMO CUARTO"))
        assertEquals(21, RulingSkeleton.Considerandos.ordinal("VIGESIMOPRIMERO"))
        assertEquals(11, RulingSkeleton.Considerandos.ordinal("UNDÉCIMO"))
        assertNull(RulingSkeleton.Considerandos.ordinal("VISTOS"))
        assertEquals("Ley N° 18.695", RulingSkeleton.Norms.body("LA LEY N° 18.695, ORGÁNICA CONSTITUCIONAL DE MUNICIPALIDADES"))
        assertEquals("Código Procesal Penal", RulingSkeleton.Norms.body("CÓDIGO PROCESAL PENAL"))
        assertEquals("DL N° 3.500", RulingSkeleton.Norms.body("D.L. N° 3.500"))
        assertEquals(" inc. 8", RulingSkeleton.Norms.incisos("OCTAVO"))
        assertEquals("Mario Meza Vásquez", RulingSkeleton.Names.titleCase("MARIO MEZA VÁSQUEZ"))
        assertEquals("ICH Ingeniería S.A.", RulingSkeleton.Names.titleCase("ICH INGENIERÍA S.A."))
    }
}
