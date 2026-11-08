package com.proasiste.lite.runtime

import com.proasiste.lite.graph.NewFact
import com.proasiste.lite.runtime.DeepSeekFacts.PageText
import com.proasiste.lite.runtime.LegalFacts.Verdict
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Snippets from STC Rol 17.292-26, where the checks were tuned. */
@RunWith(RobolectricTestRunner::class)
class LegalFactsTest {
    private val pages = listOf(
        PageText(1, "REPÚBLICA DE CHILE\nTRIBUNAL CONSTITUCIONAL\nSentencia\nRol 17.292-2026\nREQUERIMIENTO DE INAPLICABILIDAD RESPECTO DEL ARTÍCULO 60 INCISO\nOCTAVO, SEGUNDA PARTE, DE LA LEY N° 18.695, ORGÁNICA\nCONSTITUCIONAL DE MUNICIPALIDADES\nQue, con fecha 19 de enero de 2026, Mario Meza Vásquez requiere la declaración de inaplicabilidad del artículo 60 inciso octavo, segunda parte, de la Ley N° 18.695, Orgánica\nConstitucional de Municipalidades, en el proceso Rol N° 29-2023."),
        PageText(2, "Se arguye que la aplicación de la norma impugnada vulnera el artículo 19 N°\n3° inciso sexto de la Constitución Política de la República, en relación con el artículo\n5° inciso segundo del mismo cuerpo normativo y con los artículos 8° y 9° de la\nConvención Americana sobre Derechos Humanos y artículos 14 N° 5 y 15 del Pacto"),
        PageText(4, "Los concejales fundan su oposición en el precedente contenido en la sentencia Rol N° 9.431-20-INA, de fecha 2 de\ndiciembre de 2021."),
        PageText(5, "TERCERO: Que, la Constitución, en su artículo 4°, establece que “Chile es una República democrática”."),
        PageText(7, "NOVENO: Que, esta norma fue incorporada a la Ley Orgánica Constitucional\nde Municipalidades por la Ley N° 19.737, en 2001."),
        PageText(8, "DECIMOCUARTO: Que, recogiendo lo razonado en el Rol N° 9.431, procede desestimar el requerimiento de fs. 1;"),
        PageText(9, "Y TENIENDO PRESENTE lo preceptuado en el artículo 93 incisos primero,\nN° 6°, y decimoprimero, y en las demás disposiciones citadas y pertinentes de la\nConstitución Política de la República y de la Ley N° 17.997, Orgánica Constitucional\ndel Tribunal Constitucional,\nSE RESUELVE: I. QUE SE RECHAZA EL REQUERIMIENTO. Rol N° 17.292-26-INA"),
    )
    private val ruling = LegalFacts.thisRuling(pages, "ruling.pdf")
    private val checker = LegalFacts.Checker(pages, ruling)

    private fun fact(page: Int, st: String, s: String, rel: String, ot: String, o: String, statement: String) = NewFact(page, st, s, rel, ot, o, statement)

    @Test fun detectsARulingAndItsOwnName() {
        assertTrue(LegalFacts.isRuling(pages))
        assertEquals("STC Rol 17.292", ruling)
        assertFalse(LegalFacts.isRuling(listOf(PageText(1, "DELUXE EGG COOKER Instruction Manual"))))
    }

    @Test fun keepsCorrectFactsIncludingIncisosInWordsAndAliases() {
        // "inciso octavo" has no digit 8; "LEY N°" is uppercase.
        assertEquals(Verdict.KEPT, checker.check(fact(1, "Parte", "Mario Meza Vásquez", "IMPUGNA", "Norma", "Ley N° 18.695, art. 60 inc. 8", "Mario Meza Vásquez impugna el artículo 60 inciso octavo de la Ley N° 18.695.")))
        // "del mismo cuerpo normativo" points back to the Constitution, past the Convention named after it.
        assertEquals(Verdict.KEPT, checker.check(fact(2, "Parte", "Mario Meza Vásquez", "INVOCA_VULNERACION", "Norma", "Constitución, art. 5 inc. 2", "Invoca el artículo 5 inciso segundo de la Constitución.")))
        assertEquals(Verdict.KEPT, checker.check(fact(2, "Parte", "Mario Meza Vásquez", "INVOCA_VULNERACION", "Norma", "CADH, art. 8", "Invoca el artículo 8 de la Convención Americana.")))
        // "Pacto" at the end of the page, its name continuing on the next.
        assertEquals(Verdict.KEPT, checker.check(fact(2, "Parte", "Mario Meza Vásquez", "INVOCA_VULNERACION", "Norma", "PIDCP, art. 15", "Invoca el artículo 15 del Pacto Internacional de Derechos Civiles y Políticos.")))
        // A two-digit year stands for four, and a cited ruling needs only its main role number on the page.
        assertEquals(Verdict.KEPT, checker.check(fact(8, "Fallo", ruling, "CITA_PRECEDENTE", "Fallo", "STC Rol 9.431-20", "El Tribunal recoge lo razonado en el STC Rol 9.431-20 para desestimar el requerimiento.")))
        assertEquals(Verdict.KEPT, checker.check(fact(9, "Fallo", ruling, "RESUELVE", "Gestion", "Rol 29-2023 ante Tribunal Electoral Regional del Maule", "El Tribunal rechaza el requerimiento.")))
    }

    @Test fun holdsBackAWrongBodyOrPage() {
        // The text gives article 93 to the Constitution, not to Ley N° 17.997.
        assertEquals(Verdict.REVIEW, checker.check(fact(9, "Fallo", ruling, "FUNDAMENTA_EN", "Norma", "Ley N° 17.997, art. 93 inc. 1 N° 6", "El Tribunal se apoya en el artículo 93 de la Ley N° 17.997.")))
        assertEquals(Verdict.KEPT, checker.check(fact(9, "Fallo", ruling, "FUNDAMENTA_EN", "Norma", "Constitución, art. 93 inc. 1 N° 6", "El Tribunal se apoya en el artículo 93 de la Constitución.")))
        // Article 4 is on page 5, not 8.
        assertEquals(Verdict.REVIEW, checker.check(fact(8, "Fallo", ruling, "FUNDAMENTA_EN", "Norma", "Constitución, art. 4", "El Tribunal se funda en el artículo 4 de la Constitución.")))
        assertEquals(Verdict.KEPT, checker.check(fact(5, "Fallo", ruling, "FUNDAMENTA_EN", "Norma", "Constitución, art. 4", "El Tribunal se funda en el artículo 4 de la Constitución.")))
    }

    @Test fun holdsBackLinksRunningTheWrongWay() {
        // The run that pointed every fact at the ruling itself.
        assertEquals(Verdict.REVIEW, checker.check(fact(1, "Parte", "Mario Meza Vásquez", "IMPUGNA", "Fallo", ruling, "Mario Meza Vásquez impugna el artículo 60 de la Ley N° 18.695.")))
        assertEquals(Verdict.REVIEW, checker.check(fact(5, "Norma", "Constitución, art. 4", "FUNDAMENTA_EN", "Fallo", ruling, "El Tribunal se funda en el artículo 4 de la Constitución.")))
        assertEquals(Verdict.REVIEW, checker.check(fact(9, "Fallo", ruling, "RESUELVE", "Fallo", ruling, "Se rechaza el requerimiento.")))
        assertEquals(Verdict.KEPT, checker.check(fact(9, "Ministro", "Héctor Mery Romero", "INTEGRA", "Fallo", ruling, "Héctor Mery Romero integró el Tribunal.")))
    }

    @Test fun everyRelationHasADirection() = assertEquals(LegalFacts.RELATIONS.keys, LegalFacts.DIRECTIONS.keys)

    @Test fun aVotesNameAndARolesYearAreNotCheckedAgainstThePage() {
        val vote = "Disidencia de Mery Romero en STC Rol 17.292"
        // The vote's name carries the ruling's number, which is on no dissent page; the statement names the ruling with its year.
        assertEquals(Verdict.KEPT, checker.check(fact(8, "Voto", vote, "RAZONA", "Concepto", "Debido proceso", "La disidencia razona que el requerimiento de fs. 1 debía acogerse.")))
        assertEquals(Verdict.KEPT, checker.check(fact(9, "Ministro", "Héctor Mery Romero", "INTEGRA", "Fallo", ruling, "Héctor Mery Romero integró el Tribunal que pronunció la sentencia Rol 17.292-26-INA.")))
        assertEquals("Rol 17.292 y STC Rol 9.431", LegalFacts.withoutRoleYears("Rol 17.292-26-INA y STC Rol 9.431-20"))
        assertEquals("Disidencia de Mery", DeepSeekFacts.withoutTypePrefix("Voto: Disidencia de Mery", "Voto"))
        assertEquals("Voto: algo", DeepSeekFacts.withoutTypePrefix("Voto: algo", "Concepto"))
    }

    @Test fun aProceduralStepIsTheCourtsActOnTheRuling() = runBlocking {
        val client = DeepSeekFacts { _, body ->
            if (body.getJSONArray("messages").getJSONObject(0).getString("content").contains("Una primera pasada")) reply(
                // Written the wrong way round by the model, on a page the test has (9), with no numbers to check.
                // Two distinct steps (different numbers), so dedupe keeps both.
                fact(9, "Fallo", ruling, "TRAMITA", "Tribunal", "Tribunal Constitucional", "El requerimiento fue acogido a trámite conforme al artículo 93 de la Constitución."),
                fact(9, "Fallo", ruling, "TRAMITA", "Fallo", ruling, "El requerimiento fue declarado admisible."),
            ) else reply(fact(1, "Parte", "Mario Meza Vásquez", "IMPUGNA", "Norma", "Ley N° 18.695, art. 60 inc. 8", "Mario Meza Vásquez impugna el artículo 60 inciso octavo de la Ley N° 18.695."))
        }
        val result = client.extract("test-key", "ruling.pdf", pages)
        val steps = result.facts.filter { it.relation == "TRAMITA" }
        assertEquals(2, steps.size)
        assertTrue(steps.all { it.subjectType == "Tribunal" && it.subject == "Tribunal Constitucional" && it.objectType == "Fallo" && it.obj == ruling })
        assertEquals(0, result.review)
    }

    @Test fun anArticleWithASubnumberStillMatchesItsBody() {
        val c = LegalFacts.Checker(listOf(PageText(2, "vulnera el artículo 8.2 letra h) de la Convención Americana sobre Derechos Humanos y el artículo 14.5 del Pacto Internacional de Derechos Civiles y Políticos. Rol 17.292-2026")), ruling)
        assertEquals(Verdict.KEPT, c.check(fact(2, "Parte", "ICH", "INVOCA_VULNERACION", "Norma", "CADH, art. 8.2 letra h", "Invoca el artículo 8.2 letra h de la Convención Americana.")))
        assertEquals(Verdict.KEPT, c.check(fact(2, "Parte", "ICH", "INVOCA_VULNERACION", "Norma", "PIDCP, art. 14.5", "Invoca el artículo 14.5 del Pacto.")))
    }

    @Test fun dropsNumbersFoundNowhere() {
        assertEquals(Verdict.DROPPED, checker.check(fact(9, "Fallo", ruling, "FUNDAMENTA_EN", "Norma", "Ley N° 17.997, art. 93 inc. 11", "El Tribunal se apoya en el artículo 93 inciso 11.")))
        assertEquals(Verdict.DROPPED, checker.check(fact(42, "Fallo", ruling, "RESUELVE", "Concepto", "Costas", "No condena en costas.")))
    }

    @Test fun aRulingWithTwoNamesBecomesOneNode() {
        val facts = LegalFacts.mergeRulings(listOf(
            fact(4, "Parte", "Myriam Hortensia Alarcón Castillo", "INVOCA_PRECEDENTE", "Fallo", "STC Rol 9.431-20-INA", "La parte cita el fallo."),
            fact(8, "Fallo", ruling, "CITA_PRECEDENTE", "Fallo", "Rol N° 9431", "El Tribunal recoge lo razonado."),
        ), LegalFacts.Court.TC)
        assertEquals(listOf("STC Rol 9.431", "STC Rol 9.431"), facts.map { it.obj })
        assertEquals(ruling, facts[1].subject)
    }

    @Test fun rulingKeysCarryTheCourtAndOnlyAYearWhereNumbersRestart() {
        val tc = LegalFacts.Court.TC
        assertEquals("TC:9431", LegalFacts.rulingKey("STC Rol 9.431-20-INA", tc))
        assertEquals("TC:9431", LegalFacts.rulingKey("Rol N° 9431", tc))
        assertEquals("TC:17292", LegalFacts.rulingKey("Rol 17.292-2026", tc))
        assertEquals("TC:10044", LegalFacts.rulingKey("STC 10044-21", tc)) // no thousands dot: all five digits count
        assertEquals("STC Rol 10.044", LegalFacts.canonicalRuling("Rol N° 10044-21-CPR", tc))
        assertEquals("CS:1234-2020", LegalFacts.rulingKey("Corte Suprema Rol 1234-2020", tc))
        assertEquals("CS:1234-2021", LegalFacts.rulingKey("SCS Rol 1.234-21", tc)) // not the same ruling as 1234-2020
        assertEquals("CS:1234", LegalFacts.rulingKey("Rol 1234", LegalFacts.Court.CS)) // no year known: can't be told apart yet
        assertEquals(null, LegalFacts.rulingKey("Corte Suprema", tc))
        assertEquals("SCS Rol 1.234-2020", LegalFacts.canonicalRuling("SCS Rol 1234-20", tc))
        assertEquals("STC Rol 244", LegalFacts.canonicalRuling("STC Roles N° 244", tc))
        assertEquals(LegalFacts.Court.TC, LegalFacts.courtOf("REPÚBLICA DE CHILE\nTRIBUNAL CONSTITUCIONAL"))
        assertEquals(LegalFacts.Court.CS, LegalFacts.courtOf("Santiago, veinte de marzo. Corte Suprema. Rol 1234-2020"))
    }

    @Test fun theSameLinkInOtherWordsIsOneFact() {
        val a = fact(10, "Ministro", "Héctor Mery Romero", "INTEGRA", "Fallo", ruling, "El Ministro integra el fallo STC Rol 17.292-2026.")
        val b = a.copy(statement = "Pronunciada por el Tribunal, integrada por el Ministro.")
        assertEquals(listOf(a), LegalFacts.dedupe(listOf(a, b)))
    }

    @Test fun dedupeKeepsDistinctReasoningAndProcedure() {
        val a = fact(10, "Ministro", "Héctor Mery Romero", "INTEGRA", "Fallo", ruling, "Héctor Mery Romero integró el Tribunal.")
        val b = a.copy(statement = "El Ministro Héctor Mery Romero integró el Tribunal que pronunció el fallo.")
        val r1 = fact(8, "Fallo", ruling, "RAZONA", "Concepto", "Medida cautelar", "La suspensión es una medida cautelar.")
        val r2 = r1.copy(statement = "La medida es provisional y excepcional.")
        val t1 = fact(3, "Tribunal", "Tribunal Constitucional", "TRAMITA", "Fallo", ruling, "Admitido a trámite el 3 de febrero de 2026, a fojas 60.")
        val t2 = t1.copy(statement = "Declarado admisible el 12 de marzo de 2026, a fojas 99.")
        assertEquals(listOf(a, r1, r2, t1, t2), LegalFacts.dedupe(listOf(a, b, r1, r2, t1, t2)))
    }

    private fun reply(vararg facts: NewFact) = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", JSONObject().put("facts", JSONArray(facts.map {
            JSONObject().put("page", it.page).put("from_type", it.subjectType).put("from", it.subject).put("relation", it.relation)
                .put("to_type", it.objectType).put("to", it.obj).put("statement", it.statement)
        })).toString()))))
        .put("usage", JSONObject().put("cost", 0.01))

    @Test fun aRulingRunsTwoPassesTheSecondSeeingTheFirstsNodes() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        val client = DeepSeekFacts { _, body ->
            sent += body
            if (sent.size == 1) reply(fact(1, "Parte", "Mario Meza Vásquez", "IMPUGNA", "Norma", "Ley N° 18.695, art. 60 inc. 8", "Mario Meza Vásquez impugna el artículo 60 inciso octavo de la Ley N° 18.695."))
            else reply(
                fact(9, "Fallo", ruling, "RESUELVE", "Gestion", "Rol 29-2023 ante Tribunal Electoral Regional del Maule", "El Tribunal rechaza el requerimiento."),
                fact(9, "Fallo", ruling, "FUNDAMENTA_EN", "Norma", "Ley N° 17.997, art. 93", "El Tribunal se apoya en el artículo 93 de la Ley N° 17.997."),
            )
        }
        val result = client.extract("test-key", "ruling.pdf", pages)
        assertTrue(result.legal)
        assertEquals(2, sent.size)
        val second = sent[1].getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(second.contains("- Parte: Mario Meza Vásquez") && second.contains("- Norma: Ley N° 18.695, art. 60 inc. 8"))
        assertEquals("RELATIONS enforced", "RESUELVE", sent[0].getJSONObject("response_format").getJSONObject("json_schema").getJSONObject("schema")
            .getJSONObject("properties").getJSONObject("facts").getJSONObject("items").getJSONObject("properties").getJSONObject("relation").getJSONArray("enum").getString(0))
        assertEquals(2, result.facts.count { it.relation != "TIPO" }) // plus the skeleton's TIPO fact from the caption
        assertEquals(1, result.review)
        assertEquals(0.02, result.cost, 1e-9)
    }
}
