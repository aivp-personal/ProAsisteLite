package com.proasiste.lite.runtime

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Not a check: a measurement. With SKELETON_DIR set to a folder of rulings, reads each PDF the way the graph does and
 * writes skeleton-coverage.tsv there: which header fields, signatures, decided points and votes the rule-based skeleton
 * found. Skipped when SKELETON_DIR isn't set.
 */
@RunWith(RobolectricTestRunner::class)
class SkeletonCoverageTest {
    @Before fun init() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    @Test fun measure() {
        val dir = System.getenv("SKELETON_DIR").orEmpty()
        assumeTrue(dir.isNotEmpty())
        val pdfs = File(dir).walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) }.sortedBy { it.path }.toList()
        val rows = mutableListOf(listOf("file", "reader", "ruling", "court", "date", "type", "requirente", "norms", "gestion", "ministers", "drafter", "resuelve", "outcome", "votes", "error"))
        for (f in pdfs) {
            rows += runCatching {
                PDDocument.load(f).use { pdf ->
                    val d = runCatching { DocLang.read(pdf) }.getOrNull()?.takeIf { DocPages.usable(it) }
                    val pages = if (d != null) DocPages.pages(d)
                        else (1..pdf.numberOfPages).map { p -> val r = PdfText.page(pdf, p); DeepSeekFacts.PageText(p, (listOf(r.prose) + r.tables.flatten()).joinToString("\n").trim()) }
                    val s = RulingSkeleton.of(pages, f.name)
                    if (System.getenv("SKELETON_DUMP") != null) {
                        val all = pages.joinToString("\n") { it.text }
                        val at = Regex("SE\\s+RESUELVE").find(all)?.range?.first
                        File(dir, "dump").mkdirs()
                        File(File(dir, "dump"), f.nameWithoutExtension + ".txt").writeText("== PAGE 1\n" + pages.first().text.take(2500) + "\n\n== RESUELVE\n" + (at?.let { all.substring(it, minOf(all.length, it + 1500)) } ?: "(none)"))
                    }
                    if (s == null) listOf(f.name, if (d != null) "doclang" else "pages", "", "", "", "", "", "", "", "", "", "", "", "", "not a ruling")
                    else {
                        fun objs(rel: String) = s.facts.filter { it.relation == rel }.map { it.obj }
                        fun subs(rel: String) = s.facts.filter { it.relation == rel }.map { it.subject }
                        val date = s.facts.firstOrNull { it.relation == "DICTA" }?.statement?.substringAfterLast(" el ")?.removeSuffix(".").orEmpty()
                        val resuelve = objs("RESUELVE")
                        listOf(f.name, if (d != null) "doclang" else "pages", s.ruling, s.court.name, date, objs("TIPO").firstOrNull().orEmpty(),
                            subs("IMPUGNA").firstOrNull() ?: subs("REQUIERE_EN").firstOrNull().orEmpty(), objs("IMPUGNA").size.toString(),
                            objs("REQUIERE_EN").firstOrNull().orEmpty(), s.ministers.size.toString(), subs("REDACTA").firstOrNull().orEmpty(),
                            resuelve.joinToString(" | "), s.outcome.orEmpty(), objs("VOTO_SEPARADO").size.toString(), "")
                    }
                }
            }.getOrElse { listOf(f.name, "", "", "", "", "", "", "", "", "", "", "", "", "", (it.message ?: it.javaClass.simpleName).take(120)) }
        }
        File(dir, "skeleton-coverage.tsv").writeText(rows.joinToString("\n") { r -> r.joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') } })
    }
}
