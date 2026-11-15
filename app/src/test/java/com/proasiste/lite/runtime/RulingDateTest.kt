package com.proasiste.lite.runtime

import com.proasiste.lite.runtime.DeepSeekFacts.PageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The forms in which Dr. García's rulings write their date (sampled from the 1,731 the old pattern missed). */
class RulingDateTest {
    private fun date(vararg pages: String, ruling: String = "") = RulingDate.find(pages.mapIndexed { i, t -> PageText(i + 1, t) }, ruling)?.iso

    @Test fun spanishNumberWords() {
        assertEquals(31, RulingDate.number("treinta y uno"))
        assertEquals(1, RulingDate.number("primero"))
        assertEquals(2025, RulingDate.number("dos mil veinticinco"))
        assertEquals(2009, RulingDate.number("dos mil nueve"))
        assertEquals(1998, RulingDate.number("mil novecientos noventa y ocho"))
        assertEquals(26, RulingDate.number("veintiséis"))
        assertNull(RulingDate.number("del año en curso"))
    }

    @Test fun theFormsTheRulingsUse() {
        assertEquals("2026-09-02", date("Sentencia Rol 17.292-26 INA\n[2 de septiembre de 2026]\nREQUERIMIENTO"))
        assertEquals("2023-07-13", date("Sentencia | Rol13.716-2022 | [13dejuliode2023] | REQUERIMIENTO"))
        assertEquals("2025-06-06", date("Rol N° 16.457-25\nSantiago, seis de junio de dos mil veinticinco.\nVISTOS"))
        assertEquals("2009-12-31", date("Rol N° 1490-09\nSantiago, treinta y uno de diciembre de dos mil nueve."))
        assertEquals("2015-04-21", date("ROL N° 2776-15-CPR\nSantiago, 21 de abril de 2015"))
        assertEquals("2011-01-13", date("Rol N° 1.638-10-INA", "texto", "Santiago 13 de enero de 2011"))
        assertEquals("2018-07-05", date("STC Rol N°4727-18-CDC\nSantiago, cinco de julio de dos mil dieciocho.\nCon fecha lo de junio de 2018, el Presidente"))
    }

    @Test fun filingDatesAndQuotedDatesAreNotTheRulingsDate() {
        // Only a "con fecha" date: no ruling date at all, rather than the filing date.
        assertNull(date("Rol N° 13.078-22-INA\nA fojas 1, con fecha 25 de marzo de 2022, Universidad Austral de Chile deduce"))
        // A date quoted from an older ruling comes before the filing: passed over for the real one.
        assertEquals("2023-03-09", date("Rol N° 13.500-22\nVISTOS: con fecha 4 de mayo de 2022, X acciona…", "como se dijo en sentencia de Santiago, 3 de marzo de 2010, …", "Santiago, nueve de marzo de dos mil veintitrés."))
        // Too long after filing to be this case's ruling.
        assertNull(date("Rol N° 2776-15\nSantiago, 21 de abril de 2025"))
        assertEquals(2015, RulingDate.filedYear("STC Rol 2776-15", emptyList()))
        assertEquals(2026, RulingDate.filedYear("", listOf(PageText(1, "Sentencia Rol 17.292-26 INA"))))
        assertEquals(2022, RulingDate.filedYear("", listOf(PageText(1, "Rol13.716-2022"))))
    }
}
