package com.proasiste.lite

import com.proasiste.lite.graph.ListQuestions
import org.junit.Assert.assertEquals
import org.junit.Test

class ListQuestionsTest {
    private fun ask(q: String) = ListQuestions.relations(q)

    @Test fun citations() {
        assertEquals(setOf("CITA_PRECEDENTE"), ask("¿Qué fallos citó el tribunal?"))
        assertEquals(setOf("CITA_PRECEDENTE"), ask("que fallos cito el tribunal"))
        assertEquals(setOf("INVOCA_PRECEDENTE"), ask("¿Qué precedentes invocó la requirente?"))
        assertEquals(setOf("CITA_PRECEDENTE", "INVOCA_PRECEDENTE"), ask("¿Qué jurisprudencia se cita?"))
    }

    @Test fun people() {
        assertEquals(setOf("INTEGRA"), ask("¿Qué ministros integraron el tribunal?"))
        assertEquals(setOf("REDACTA"), ask("¿Quién redactó la sentencia?"))
        assertEquals(setOf("DISIENTE"), ask("¿Qué ministros disintieron?"))
        assertEquals(setOf("PREVIENE"), ask("¿Quién previno?"))
    }

    @Test fun normsDecisionAndProcedure() {
        assertEquals(setOf("IMPUGNA"), ask("¿Qué norma se impugna?"))
        assertEquals(setOf("INVOCA_VULNERACION"), ask("¿Qué artículos se dicen vulnerados?"))
        assertEquals(setOf("FUNDAMENTA_EN"), ask("¿En qué normas se funda el tribunal?"))
        assertEquals(setOf("RESUELVE"), ask("¿Qué resolvió el tribunal sobre el requerimiento?"))
        assertEquals(setOf("TRAMITA"), ask("¿Cómo fue la tramitación?"))
    }

    @Test fun otherQuestionsTakeTheNormalSearch() {
        assertEquals(emptySet<String>(), ask("¿Es la suspensión del alcalde una sanción?"))
        assertEquals(emptySet<String>(), ask("how long and how much water for 12 hard boiled eggs"))
        assertEquals(emptySet<String>(), ask("how much water to steam salmon"))
    }
}
