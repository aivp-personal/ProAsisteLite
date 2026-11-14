package com.proasiste.lite.runtime

import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.graph.NewFact
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DocPagesTest {
    @Before fun init() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    /** The six DocLang reference rulings, and STC 1810-10, whose court is named only in its letterhead. */
    private val names = listOf("abbyy1", "abbyy2", "abbyy3", "microsoft1", "untagged1", "untagged2", "tc1810")
    private fun open(name: String) = PDDocument.load(javaClass.classLoader!!.getResourceAsStream("doclang/$name.pdf")!!)

    /** What the ruling skeleton finds: facts per relation, and the ministers on the signature page. */
    private fun found(pages: List<DeepSeekFacts.PageText>, title: String): Map<String, Int> {
        val s = RulingSkeleton.of(pages, title) ?: return emptyMap()
        return s.facts.groupingBy { it.relation }.eachCount() + ("ministros" to s.ministers.size)
    }

    /** The court and the ruling's own name, which every other fact hangs on. */
    private fun identity(pages: List<DeepSeekFacts.PageText>, title: String) = RulingSkeleton.of(pages, title)?.let { "${it.court} · ${it.ruling}" }

    @Test fun blocksKeepTheCourtAndTheRulingsName() {
        val differ = mutableListOf<String>()
        for (n in names) open(n).use { doc ->
            val old = (1..doc.numberOfPages).map { p -> PdfText.page(doc, p).let { DeepSeekFacts.PageText(p, (listOf(it.prose) + it.tables.flatten()).joinToString("\n").trim()) } }
            val a = identity(old, n); val b = identity(DocPages.pages(DocLang.read(doc)), n)
            println("$n old=$a new=$b")
            if (a != null && a != b) differ += "$n: $b instead of $a"
        }
        assertTrue(differ.joinToString("; "), differ.isEmpty())
        // STC 1810-10 by its real file name: the Tribunal's own ruling, signed by nine ministers.
        open("tc1810").use { doc ->
            val s = RulingSkeleton.of(DocPages.pages(DocLang.read(doc)), "STC 1810-10 EFECTO RETROACTIVO DE LOS CONTRATOS.pdf")!!
            assertEquals(LegalFacts.Court.TC, s.court); assertEquals("STC Rol 1.810", s.ruling); assertEquals(9, s.ministers.size)
            assertTrue(s.facts.filter { it.relation == "INTEGRA" }.all { it.obj == "STC Rol 1.810" })
        }
    }

    @Test fun aRulingNamedByItsFileIsTheTribunalsOwn() {
        // Page 1 names the pending case's court and role, and the TC only as "esta Magistratura Constitucional".
        val page1 = DeepSeekFacts.PageText(1, "Que, con fecha 26 de agosto de 2010, Hernán Vicente Oróstegui Núñez ha requerido a esta Magistratura Constitucional para que declare la inaplicabilidad por inconstitucionalidad, en el marco del proceso de protección Rol N° 3868-2010, seguido ante la Corte de Apelaciones de Santiago")
        assertEquals(LegalFacts.Court.TC, LegalFacts.courtOf(page1.text))
        assertEquals("STC Rol 1.810", LegalFacts.thisRuling(listOf(page1), "STC 1810-10 EFECTO RETROACTIVO DE LOS CONTRATOS.pdf"))
        // Without a role in the file name, page 1 still decides.
        assertEquals("STC Rol 3.868", LegalFacts.thisRuling(listOf(page1), "fallo.pdf"))
    }

    @Test fun ministersJoinedByEAreTwo() {
        // STC 1810-10's signature, as written.
        val text = "Pronunciada por el Excmo. Tribunal Constitucional, integrado por su Presidente, Ministro señor Marcelo Venegas Palacios, los Ministros señores Hernán Vodanovic Schnake, Mario Fernández Baeza, la Ministra señora Marisol Peña Torres y los Ministros señores Enrique Navarro Beltrán, Francisco Fernández Fredes, Carlos Carmona Santander, José Antonio Viera-Gallo Quesney e Iván Aróstica Maldonado.\nAutoriza la Secretaria"
        assertEquals(listOf("Marcelo Venegas Palacios", "Hernán Vodanovic Schnake", "Mario Fernández Baeza", "Marisol Peña Torres", "Enrique Navarro Beltrán",
            "Francisco Fernández Fredes", "Carlos Carmona Santander", "José Antonio Viera-Gallo Quesney", "Iván Aróstica Maldonado"), RulingSkeleton.Signature.ministers(text))
    }

    /**
     * The skeleton reads DocLang page text at least as well as the old page text: on each ruling it finds every
     * relation at least as often. Printed side by side so a change can be judged.
     */
    @Test fun skeletonFindsAsMuchFromBlocks() {
        val worse = mutableListOf<String>()
        for (n in names) open(n).use { doc ->
            val old = (1..doc.numberOfPages).map { p -> PdfText.page(doc, p).let { DeepSeekFacts.PageText(p, (listOf(it.prose) + it.tables.flatten()).joinToString("\n").trim()) } }
            val d = DocLang.read(doc)
            val new = DocPages.pages(d)
            val a = found(old, n); val b = found(new, n)
            println("$n old=${a.toSortedMap()}\n$n new=${b.toSortedMap()}")
            for ((rel, count) in a) if ((b[rel] ?: 0) < count) worse += "$n: $rel ${b[rel] ?: 0} < $count"
        }
        assertTrue(worse.joinToString("; "), worse.isEmpty())
    }

    @Test fun pageTextLeavesOutHeadersAndFooters() {
        open("abbyy2").use { doc ->
            val d = DocLang.read(doc)
            val headers = d.blocks.filter { it.kind == "page_header" || it.kind == "page_footer" }.map { it.text }.filter { it.length > 8 }
            assertTrue(headers.isNotEmpty())
            val text = DocPages.pages(d).joinToString("\n") { it.text }
            headers.forEach { h -> assertFalse("header left in: $h", text.lines().any { it == h }) }
        }
    }

    @Test fun threadsAreJoinedOnTheirFirstPage() {
        for (n in names) open(n).use { doc ->
            val d = DocLang.read(doc)
            val thread = d.blocks.filter { it.thread > 0 }.groupBy { it.thread }.values.firstOrNull { it.size > 1 } ?: return@use
            val joined = DocPages.pages(d, joinThreads = true)[thread.first().page - 1].text
            assertTrue("$n: halves not joined", joined.contains(thread.joinToString(" ") { it.text }))
            return
        }
    }

    @Test fun aFactIsLocatedInItsBlock() {
        open("abbyy1").use { doc ->
            val d = DocLang.read(doc)
            val block = d.blocks.first { it.page == 2 && it.kind == "list" }
            val words = block.text.split(" ").filter { it.length > 4 }.take(12).joinToString(" ")
            val place = DocPages.locate(d, NewFact(2, "Fallo", "STC Rol 5293", "RAZONA", "Concepto", "apremio", words))
            assertEquals(listOf(2 to block.box!!), DocPages.parse(place).take(1))
            assertEquals("", DocPages.locate(d, NewFact(2, "A", "x", "R", "B", "y", "nada que ver con este fallo")))
        }
    }

    @Test fun placesParseAndSkipJunk() {
        assertEquals(listOf(5 to listOf(80, 400, 430, 490), 6 to listOf(80, 60, 430, 120)), DocPages.parse("5:80,400,430,490;6:80,60,430,120;x;7:1,2"))
    }
}
