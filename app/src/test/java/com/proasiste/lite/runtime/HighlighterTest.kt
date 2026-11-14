package com.proasiste.lite.runtime

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class HighlighterTest {
    private fun w(s: String) = s.split(" ").map(Highlighter::key)

    @Test fun alignsAPassageInsideThePage() {
        val page = w("Santiago once de junio de dos mil nueve VISTOS Y CONSIDERANDO PRIMERO Que por oficio N° 454 el Senado ha enviado el proyecto")
        assertEquals(10..19, Highlighter.align(w("CONSIDERANDO: PRIMERO.- Que por oficio N° 454, el Senado ha"), page))
    }

    @Test fun bridgesSmallGapsAndAccents() {
        // A page number between two lines, and accents on the page but not in the passage.
        val page = w("la Constitución Política de la República 12 establece que el Tribunal Constitucional ejerce el control")
        val range = Highlighter.align(w("Constitucion Politica de la Republica establece que el Tribunal"), page)!!
        assertEquals(1, range.first); assertTrue(range.last in 9..11)
    }

    @Test fun refusesAPassageThatIsNotThere() {
        assertNull(Highlighter.align(w("Dato del grafo: el Tribunal acoge el requerimiento de inaplicabilidad"), w("Santiago once de junio de dos mil nueve VISTOS")))
    }

    @Test fun findsAPassageInARealRuling() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
        val loader = javaClass.classLoader!!
        val pdf = File.createTempFile("ruling", ".pdf").apply { deleteOnExit(); outputStream().use { o -> loader.getResourceAsStream("doclang/abbyy1.pdf")!!.copyTo(o) } }
        // Take a passage from the reference DocLang of page 2 and find it again on that page.
        val xml = javaClass.classLoader!!.getResourceAsStream("doclang/abbyy1.doclang")!!.bufferedReader().readText()
        val page2 = xml.split("<page_break/>")[1]
        val text = Regex(">([^<]{120,})<").find(page2)!!.groupValues[1]
        val passage = text.split(" ").take(30).joinToString(" ")
        val rects = Highlighter.find(pdf, 2, passage)
        assertTrue("no highlight for: $passage", rects.isNotEmpty())
        rects.forEach { assertTrue(it.left < it.right && it.top < it.bottom && it.right <= 1f && it.bottom <= 1f) }
        assertTrue(Highlighter.find(pdf, 2, "palabras que no aparecen en absoluto en esta página del fallo").isEmpty())
    }
}
