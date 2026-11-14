package com.proasiste.lite.runtime

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Checks the Kotlin DocLang reader against to_doclang.py's output on six public rulings (test resources doclang/):
 * three FineReader-tagged, one Word-tagged and two untagged. The two use different PDF libraries, so letters' boxes and
 * line grouping differ a little; the words, the block types of tagged files, and the block positions must agree.
 */
@RunWith(RobolectricTestRunner::class)
class DocLangTest {
    @Before fun init() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    private class Parsed(val blocks: List<Pair<String, String>>, val boxes: List<Pair<String, List<Int>>>, val pageBreaks: Int)
    private val block = Regex("<(text|heading|list|table|page_header|page_footer|footnote|picture|formula)(?:\\s[^>]*)?>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
    private fun parse(xml: String): Parsed {
        val blocks = block.findAll(xml).map { m -> m.groupValues[1] to m.groupValues[2] }.toList()
        val boxes = blocks.map { (k, body) -> k to Regex("<location value=\"(\\d+)\"/>").findAll(body).take(4).map { it.groupValues[1].toInt() }.toList() }
        return Parsed(blocks.map { (k, body) -> k to Regex("<[^>]+>").replace(body, " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&") }, boxes, Regex("<page_break/>").findAll(xml).count())
    }
    private fun words(p: Parsed) = p.blocks.flatMap { (_, t) -> Regex("[\\p{L}\\p{N}]+").findAll(t.lowercase()).map { it.value } }.groupingBy { it }.eachCount()

    /** Share of the reference's words (with multiplicity) that the port also has. */
    private fun recall(ref: Map<String, Int>, got: Map<String, Int>): Double =
        ref.entries.sumOf { (w, n) -> minOf(n, got[w] ?: 0) }.toDouble() / ref.values.sum().coerceAtLeast(1)

    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("doclang/$name")!!

    private fun compare(name: String): String {
        val ref = parse(resource("$name.doclang").bufferedReader().readText())
        val doc = PDDocument.load(resource("$name.pdf")).use { DocLang.read(it) }
        val got = parse(DocLang.toXml(doc))
        val r = recall(words(ref), words(got)); val p = recall(words(got), words(ref))
        val kinds = { x: Parsed -> x.blocks.groupingBy { it.first }.eachCount().toSortedMap() }
        val line = "$name tagged=${doc.tagged} pages=${doc.pages} words recall=${"%.3f".format(r)} precision=${"%.3f".format(p)} ref=${kinds(ref)} got=${kinds(got)}"
        println(line)
        assertEquals("$name page breaks", ref.pageBreaks, got.pageBreaks)
        assertTrue("$name word recall $r", r >= 0.97)
        assertTrue("$name word precision $p", p >= 0.97)
        if (doc.tagged) {
            // Tagged files: the tag tree decides the block types, so headings, lists and tables match closely.
            for (k in listOf("heading", "list", "table")) assertTrue("$name $k: $line", kotlin.math.abs((kinds(ref)[k] ?: 0) - (kinds(got)[k] ?: 0)) <= 1)
            // Block boxes: each reference heading's box is found in the port within a few grid units.
            ref.boxes.filter { it.first == "heading" && it.second.size == 4 }.forEach { (_, b) ->
                assertTrue("$name heading box $b", got.boxes.any { (k, g) -> k == "heading" && g.size == 4 && g.zip(b).all { (x, y) -> kotlin.math.abs(x - y) <= 6 } })
            }
        }
        return line
    }

    @Test fun abbyyTagged() { listOf("abbyy1", "abbyy2", "abbyy3").forEach { compare(it) } }
    @Test fun wordTagged() { compare("microsoft1") }
    @Test fun untagged() { listOf("untagged1", "untagged2").forEach { compare(it) } }

    @Test fun joinKeepsLayoutSpacesOnly() {
        fun c(t: String, x: Float, top: Float = 0f) = DocLang.Ch(t, x, top, x + 5f, top + 10f)
        // Letters side by side join; a gap or a new line gives one space; a hyphen at a line end is rejoined.
        assertEquals("ab cd", DocLang.join(listOf(c("a", 0f), c("b", 5f), c("c", 20f), c("d", 25f))))
        assertEquals("constitucional", DocLang.join(listOf(c("c", 0f), c("o", 5f), c("n", 10f), c("s", 15f), c("-", 20f), c("t", 0f, 12f), c("itucional", 5f, 12f))).replace("  ", " "))
    }
}
