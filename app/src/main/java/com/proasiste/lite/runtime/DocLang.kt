package com.proasiste.lite.runtime

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent
import com.tom_roush.pdfbox.text.PDFMarkedContentExtractor
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.StringWriter
import kotlin.math.max
import kotlin.math.min

/**
 * Reads a text PDF as DocLang (spec 0.9, doclang.ai): typed blocks — headings, paragraphs, lists, tables, footnotes,
 * page headers and footers, pictures — in reading order, each with its box on a 500 × 500 grid from the page's top left.
 *
 * A port of plantroom-mvp/fineparser/to_doclang.py. The primary source is the PDF's tag tree (FineReader's "Create PDF
 * tags", Word's tagged export): it gives the blocks in reading order, and each tag's marked content gives its letters and
 * so its box. Text left out of the tree (validation codes, folio stamps) is placed by its position on the page. Untagged
 * PDFs fall back to text blocks built from the letters' positions. Not ported: text drawn outside the page content
 * (some signature stamps), and picture blocks in untagged PDFs.
 */
object DocLang {
    const val RES = 500
    const val GENERATOR = "ProAsisteLite DocLang reader (port of to_doclang.py)"

    /** One block. [box] is x_min, y_min, x_max, y_max on the 500 grid; [thread] links the halves of a split element (0 = none). */
    data class Block(val page: Int, val kind: String, val text: String, val box: List<Int>?, val level: Int = 0, val thread: Int = 0, internal val body: String? = null)
    /** A read document: its blocks in order, page count, whether the tag tree was used, and what was found. */
    class Doc(val blocks: List<Block>, val pages: Int, val tagged: Boolean, val stats: Map<String, Int>)

    /** A letter with its position in page space, top-left origin. */
    internal class Ch(val text: String, val x0: Float, val top: Float, val x1: Float, val bottom: Float) {
        val h get() = bottom - top
    }

    private val BAD_XML = Regex("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\ufffe\\uffff]")
    internal fun clean(s: String) = BAD_XML.replace(Regex("[ \\t\\r\\n]+").replace(s, " "), "").trim()

    /**
     * Joins letters in content order, adding a space only where the layout shows one (a new line, or a horizontal gap),
     * so superscripts and punctuation drawn apart stay attached. A word broken by a hyphen at a line end is rejoined.
     */
    internal fun join(chars: List<Ch>): String {
        val out = StringBuilder(); var prev: Ch? = null
        for (c in chars) {
            val t = c.text
            val p = prev
            if (p != null && t != " " && out.isNotEmpty() && out.last() != ' ') {
                val h = max(1f, min(p.h, c.h))
                val newLine = c.top > p.bottom - 0.2f * h || c.x0 < p.x0 - h
                val gap = c.x0 - p.x1
                if (newLine || gap > 0.25f * h) {
                    if (newLine && out.last() == '-' && out.length > 1 && out[out.length - 2].isLetter() && t.firstOrNull()?.isLowerCase() == true) out.setLength(out.length - 1)
                    else out.append(' ')
                }
            }
            out.append(t); prev = c
        }
        return clean(out.toString())
    }

    private fun Ch(p: TextPosition) = Ch(p.unicode ?: "", p.xDirAdj, p.yDirAdj - p.heightDir, p.xDirAdj + p.widthDirAdj, p.yDirAdj)

    /** Letters and box of one element on one page. */
    private class Frag { val chars = mutableListOf<Ch>(); var box: FloatArray? = null
        fun addChars(cs: List<Ch>) { if (cs.isEmpty()) return; chars += cs
            addBox(floatArrayOf(cs.minOf { it.x0 }, cs.minOf { it.top }, cs.maxOf { it.x1 }, cs.maxOf { it.bottom })) }
        fun addBox(b: FloatArray) { val o = box; box = if (o == null) b else floatArrayOf(min(o[0], b[0]), min(o[1], b[1]), max(o[2], b[2]), max(o[3], b[3])) }
        val text get() = join(chars)
    }

    /** Reads [doc]. Never throws for a bad tag tree: it falls back to text blocks. */
    fun read(doc: PDDocument): Doc {
        val root = runCatching { doc.documentCatalog.structureTreeRoot }.getOrNull()
        if (root != null) runCatching { Reader(doc).tagged(root) }.getOrNull()?.takeIf { it.blocks.isNotEmpty() }?.let { return it }
        return Reader(doc).flat()
    }

    private class Reader(val doc: PDDocument) {
        val pageCount = doc.numberOfPages
        val stats = HashMap<String, Int>()
        fun count(k: String, n: Int = 1) { stats[k] = (stats[k] ?: 0) + n }
        private val pageIndex = HashMap<PDPage, Int>().apply { doc.pages.forEachIndexed { i, p -> put(p, i + 1) } }
        private fun size(page: Int) = doc.getPage(page - 1).cropBox.let { it.width to it.height }

        fun norm(page: Int, x0: Float, top: Float, x1: Float, bottom: Float): List<Int> {
            val (w, h) = size(page)
            fun nx(v: Float) = (v / w * RES).toInt().coerceIn(0, RES - 1)
            fun ny(v: Float) = (v / h * RES).toInt().coerceIn(0, RES - 1)
            return listOf(nx(x0), ny(top), nx(x1), ny(bottom))
        }
        fun norm(page: Int, b: FloatArray) = norm(page, b[0], b[1], b[2], b[3])

        // ---- Letters by marked-content id, per page ----
        private val byMcid = HashMap<Int, Map<Int, List<Ch>>>()
        val used = HashSet<Pair<Int, Int>>()
        fun chars(page: Int, mcid: Int): List<Ch> {
            used += page to mcid
            val index = byMcid.getOrPut(page) {
                val out = HashMap<Int, MutableList<Ch>>()
                val ex = PDFMarkedContentExtractor(); ex.processPage(doc.getPage(page - 1))
                fun walk(mc: PDMarkedContent, inherited: Int) {
                    val id = if (mc.mcid >= 0) mc.mcid else inherited
                    for (c in mc.contents) when (c) {
                        is TextPosition -> if (id >= 0) out.getOrPut(id) { mutableListOf() } += Ch(c)
                        is PDMarkedContent -> walk(c, id)
                    }
                }
                ex.markedContents.forEach { walk(it, -1) }
                out
            }
            return index[mcid].orEmpty()
        }

        private fun pageOf(el: PDStructureElement): Int? = runCatching { el.page?.let { pageIndex[it] } }.getOrNull()
        private fun typeOf(el: PDStructureElement) = (runCatching { el.standardStructureType }.getOrNull() ?: el.structureType ?: "")

        /** An element's letters (descendants included) per page, in content order. */
        fun collect(el: PDStructureElement, page: Int?, out: MutableMap<Int, Frag> = sortedMapOf()): MutableMap<Int, Frag> {
            val pg = pageOf(el) ?: page
            for (kid in el.kids) when (kid) {
                is Int -> if (pg != null) out.getOrPut(pg) { Frag() }.addChars(chars(pg, kid))
                is PDMarkedContentReference -> { val kp = runCatching { kid.page?.let { pageIndex[it] } }.getOrNull() ?: pg
                    if (kp != null) out.getOrPut(kp) { Frag() }.addChars(chars(kp, kid.mcid)) }
                is PDStructureElement -> collect(kid, pg, out)
            }
            return out
        }
        fun textOf(el: PDStructureElement, page: Int?) = join(collect(el, page).values.flatMap { it.chars })
        private fun children(el: PDStructureElement) = el.kids.filterIsInstance<PDStructureElement>()

        /** An attribute from the element's /A entry (a dictionary, or an array of them). */
        private fun attr(el: PDStructureElement, name: String): com.tom_roush.pdfbox.cos.COSBase? {
            val a = el.cosObject.getDictionaryObject(COSName.A)
            val dicts = when (a) { is COSDictionary -> listOf(a); is COSArray -> (0 until a.size()).mapNotNull { a.getObject(it) as? COSDictionary }; else -> emptyList() }
            return dicts.firstNotNullOfOrNull { it.getDictionaryObject(COSName.getPDFName(name)) }
        }
        private fun intAttr(el: PDStructureElement, name: String) = (attr(el, name) as? COSNumber)?.intValue()?.coerceAtLeast(1) ?: 1

        // ---- Blocks: rendered ones, and text blocks still open to paragraph repair ----
        private class Pending(val page: Int, val kind: String, val attrs: String, var box: List<Int>, var text: String, var group: Int?, val level: Int)
        private val blocks = mutableListOf<Any>() // Block or Pending
        private var group = 0

        private fun emit(b: Block, kind: String) { blocks += b; count(kind) }

        private fun simple(kind: String, el: PDStructureElement, page: Int?, level: Int = 0) {
            val frags = collect(el, page).filter { it.value.text.isNotEmpty() && it.value.box != null }
            if (frags.isEmpty()) { count("empty_$kind"); return }
            var grp: Int? = null
            if (frags.size > 1) { group++; grp = group }
            for ((p, f) in frags) blocks += Pending(p, kind, if (kind == "heading") " level=\"$level\"" else "", norm(p, f.box!!), f.text, grp, level)
        }

        fun walk(el: PDStructureElement, page: Int?) {
            val t = typeOf(el); val pg = pageOf(el) ?: page
            when {
                t in setOf("P", "Span", "Quote", "BlockQuote", "Code", "Index", "TOCI", "Reference", "Link", "Caption") -> simple("text", el, pg)
                t == "H" || Regex("H[1-6]").matches(t) -> simple("heading", el, pg, if (t.length == 2) t[1].digitToInt() else 1)
                t == "RunningTitle" -> {
                    val frags = collect(el, pg); val p = frags.keys.firstOrNull() ?: pg
                    val box = p?.let { frags[it]?.box }
                    val h = if (p != null) size(p).second else 1f
                    simple(if (box != null && (box[1] + box[3]) / 2 > h / 2) "page_footer" else "page_header", el, pg)
                }
                t == "Note" || t == "FENote" -> simple("footnote", el, pg)
                t == "Formula" -> simple("formula", el, pg)
                t == "L" -> list(el, pg)
                t == "Table" -> table(el, pg)
                t == "Figure" -> figure(el, pg)
                else -> children(el).forEach { walk(it, pg) }
            }
        }

        private fun figure(el: PDStructureElement, page: Int?) {
            val p = pageOf(el) ?: page
            val bb = attr(el, "BBox") as? COSArray
            val box = if (bb != null && bb.size() == 4 && p != null) {
                val v = (0 until 4).map { (bb.getObject(it) as? COSNumber)?.floatValue() ?: 0f }
                val h = size(p).second
                norm(p, v[0], h - v[3], v[2], h - v[1]) to p
            } else {
                val frags = collect(el, page).filter { it.value.box != null }
                if (frags.isEmpty() || p == null) { count("figure_without_box"); return }
                val q = frags.keys.min(); norm(q, frags[q]!!.box!!) to q
            }
            emit(Block(box.second, "picture", "", box.first, body = "<picture class=\"undefined\">${loc(box.first)}</picture>"), "picture")
        }

        private fun list(el: PDStructureElement, page: Int?) {
            class Item(val page: Int, val box: FloatArray?, val marker: String, val text: String)
            val items = mutableListOf<Item>()
            fun walkItems(l: PDStructureElement, page: Int?) {
                for (li in children(l)) {
                    val lp = pageOf(li) ?: page
                    val lt = typeOf(li)
                    if (lt == "L") { walkItems(li, lp); continue }
                    if (lt != "LI") { walk(li, lp); continue }
                    val marker = children(li).filter { typeOf(it) == "Lbl" }.joinToString("") { textOf(it, lp) }
                    val frags = sortedMapOf<Int, Frag>()
                    children(li).filter { typeOf(it) != "Lbl" }.forEach { collect(it, lp, frags) }
                    children(li).filter { typeOf(it) == "Lbl" }.forEach { c -> collect(c, lp).forEach { (p, f) -> f.box?.let { frags.getOrPut(p) { Frag() }.addBox(it) } } }
                    if (frags.isEmpty()) continue
                    val text = join(frags.values.flatMap { it.chars })
                    val p = frags.keys.min()
                    items += Item(p, frags[p]?.box, marker, text)
                }
            }
            walkItems(el, page)
            if (items.isEmpty()) return
            val ordered = items.count { Regex("^\\(?[0-9ivxlcdmIVXLCDMa-zA-Z]{1,4}[.)°º]").containsMatchIn(it.marker) } > items.size / 2.0
            val cls = if (ordered) " class=\"ordered\"" else ""
            for ((p, its) in items.groupBy { it.page }.toSortedMap()) {
                val boxes = its.mapNotNull { it.box }
                val box = if (boxes.isEmpty()) null else norm(p, boxes.minOf { it[0] }, boxes.minOf { it[1] }, boxes.maxOf { it[2] }, boxes.maxOf { it[3] })
                val body = its.joinToString("") { (if (it.marker.isNotBlank()) "<ldiv><marker>${esc(clean(it.marker))}</marker></ldiv>" else "<ldiv/>") + esc(it.text) }
                emit(Block(p, "list", its.joinToString("\n") { listOf(clean(it.marker), it.text).filter { s -> s.isNotEmpty() }.joinToString(" ") }, box,
                    body = "<list$cls>${box?.let(::loc).orEmpty()}$body</list>"), "list")
                count("list_item", its.size)
            }
        }

        private fun table(el: PDStructureElement, page: Int?) {
            val rows = mutableListOf<List<PDStructureElement>>(); val captions = mutableListOf<PDStructureElement>()
            fun walkRows(e: PDStructureElement) {
                for (c in children(e)) when (typeOf(c)) {
                    "TR" -> rows += children(c).filter { typeOf(it) == "TD" || typeOf(it) == "TH" }
                    "THead", "TBody", "TFoot" -> walkRows(c)
                    "Caption" -> captions += c
                }
            }
            walkRows(el)
            val frags = collect(el, page).filter { it.value.box != null }
            if (frags.isEmpty()) { count("empty_table"); return }
            val p = frags.keys.min()
            val grid = HashMap<Pair<Int, Int>, String>(); val cells = HashMap<Pair<Int, Int>, String>()
            rows.forEachIndexed { r, row ->
                var c = 0
                for (cell in row) {
                    while ((r to c) in grid) c++
                    val cs = intAttr(cell, "ColSpan"); val rs = intAttr(cell, "RowSpan")
                    val txt = textOf(cell, page)
                    val tok = if (typeOf(cell) == "TH") "ched" else if (txt.isNotEmpty()) "fcel" else "ecel"
                    for (dr in 0 until rs) for (dc in 0 until cs) {
                        grid[(r + dr) to (c + dc)] = if (dr == 0 && dc == 0) { cells[r to c] = txt; "<$tok/>" + esc(txt) } else if (dr > 0 && dc > 0) "<xcel/>" else if (dc > 0) "<lcel/>" else "<ucel/>"
                    }
                    c += cs
                }
            }
            val nrows = (grid.keys.maxOfOrNull { it.first } ?: -1) + 1; val ncols = (grid.keys.maxOfOrNull { it.second } ?: -1) + 1
            val body = (0 until nrows).joinToString("") { r -> (0 until ncols).joinToString("") { c -> grid[r to c] ?: "<ecel/>" } + "<nl/>" }
            val cap = captions.joinToString("") { c -> collect(c, page).filter { it.value.box != null }.entries.firstOrNull()?.let { (cp, f) -> "<caption>${loc(norm(cp, f.box!!))}${esc(f.text)}</caption>" }.orEmpty() }
            val text = (0 until nrows).joinToString("\n") { r -> (0 until ncols).mapNotNull { c -> cells[r to c] }.filter { it.isNotEmpty() }.joinToString(" | ") }
            val box = norm(p, frags[p]!!.box!!)
            emit(Block(p, "table", text, box, body = "<table>${loc(box)}$cap$body</table>"), "table")
            if (frags.size > 1) count("table_spans_pages")
        }

        /**
         * FineReader often tags an indented first line, or the tail of a paragraph after a page break, as its own
         * paragraph. A text block joins the previous one when that one doesn't end a sentence and this one starts in
         * lowercase (or the previous ends in a lowercase word or a comma): on the same page they become one block, across
         * a page break they share a thread.
         */
        fun finish(): List<Block> {
            val ends = Regex("[.:;!?»\"”)\\]]$"); val mid = Regex("(?:\\b[a-záéíóúüñ]+|,)$")
            val out = mutableListOf<Any>(); var last: Pending? = null
            for (b in blocks) {
                if (b !is Pending) { out += b; continue }
                val l = last
                if (b.kind == "text" && l != null && !ends.containsMatchIn(l.text) && (b.text.firstOrNull()?.isLowerCase() == true || mid.containsMatchIn(l.text))) {
                    if (l.page == b.page && b.box[1] - l.box[3] < 15) {
                        l.box = listOf(min(l.box[0], b.box[0]), min(l.box[1], b.box[1]), max(l.box[2], b.box[2]), max(l.box[3], b.box[3]))
                        l.text += " " + b.text; count("merged_same_page"); continue
                    }
                    if (b.page == l.page + 1) {
                        if (l.group == null) { group++; l.group = group }
                        b.group = l.group; count("linked_across_pages")
                    }
                }
                out += b
                if (b.kind == "text") last = b else if (b.kind != "page_header" && b.kind != "page_footer") last = null
            }
            val ids = HashMap<Int, Int>()
            return out.map { b ->
                if (b is Block) b else { b as Pending
                    val th = b.group?.let { ids.getOrPut(it) { ids.size + 1 } } ?: 0
                    count(b.kind)
                    Block(b.page, b.kind, b.text, b.box, b.level, th, "<${b.kind}${b.attrs}>${if (th > 0) "<thread thread_id=\"$th\"/>" else ""}${loc(b.box)}${esc(b.text)}</${b.kind}>")
                }
            }
        }

        // ---- Letters outside the tag tree ----
        private fun pageChars(page: Int): List<Ch> {
            val out = mutableListOf<Ch>()
            object : PDFTextStripper() {
                override fun writeString(text: String, positions: List<TextPosition>) { positions.forEach { if (!it.unicode.isNullOrBlank()) out += Ch(it) } }
            }.apply { startPage = page; endPage = page; sortByPosition = false }.writeText(doc, StringWriter())
            return out
        }

        /** Lines from loose letters, then lines grouped into blocks the way to_doclang groups text-layer lines. */
        fun lineGroups(chars: List<Ch>): List<Pair<FloatArray, String>> {
            val lines = mutableListOf<MutableList<Ch>>()
            for (c in chars.sortedWith(compareBy({ it.top }, { it.x0 }))) {
                val line = lines.lastOrNull { l -> val f = l.first(); kotlin.math.abs(f.top - c.top) < 0.5f * max(1f, f.h) }
                if (line != null) line += c else lines += mutableListOf(c)
            }
            data class G(var x0: Float, var top: Float, var x1: Float, var bottom: Float, var text: String)
            val groups = mutableListOf<G>()
            for (l in lines.map { it.sortedBy { c -> c.x0 } }.sortedWith(compareBy({ it.first().top }, { it.first().x0 }))) {
                val t = join(l); if (t.isEmpty()) continue
                val x0 = l.minOf { it.x0 }; val x1 = l.maxOf { it.x1 }; val top = l.minOf { it.top }; val bottom = l.maxOf { it.bottom }; val h = bottom - top
                val g = groups.lastOrNull()
                if (g != null && top - g.bottom < 0.8f * h && kotlin.math.abs(x0 - g.x0) < 4 * h) { g.text += " $t"; g.x0 = min(g.x0, x0); g.x1 = max(g.x1, x1); g.bottom = max(g.bottom, bottom) }
                else groups += G(x0, top, x1, bottom, t)
            }
            return groups.map { floatArrayOf(it.x0, it.top, it.x1, it.bottom) to clean(it.text) }
        }

        /** Text the tag tree left out, by page and zone (header, footer, body) from its position. */
        fun untagged(): Map<Int, Map<String, List<Block>>> {
            val out = HashMap<Int, HashMap<String, MutableList<Block>>>()
            for (page in 1..pageCount) {
                // A letter is loose when no used marked content on this page holds one at the same place.
                val tagged = HashSet<Triple<String, Int, Int>>()
                byMcid[page]?.forEach { (id, cs) -> if ((page to id) in used) cs.forEach { tagged += Triple(it.text, (it.x0 * 4).toInt(), (it.bottom * 4).toInt()) } }
                val loose = pageChars(page).filter { Triple(it.text, (it.x0 * 4).toInt(), (it.bottom * 4).toInt()) !in tagged }
                val h = size(page).second
                for ((box, text) in lineGroups(loose)) {
                    val m = (box[1] + box[3]) / 2 / h
                    val (zone, kind) = if (m < 0.15f) "header" to "page_header" else if (m > 0.85f) "footer" to "page_footer" else "body" to "text"
                    val b = norm(page, box)
                    out.getOrPut(page) { HashMap() }.getOrPut(zone) { mutableListOf() } += Block(page, kind, text, b, body = "<$kind>${loc(b)}${esc(text)}</$kind>")
                    count("untagged_$kind")
                }
            }
            return out
        }

        fun tagged(root: com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot): Doc {
            for (k in root.kids) if (k is PDStructureElement) walk(k, null)
            // Pages are read for loose letters only after the whole tree, so every page's marked content is indexed.
            for (p in 1..pageCount) chars(p, Int.MIN_VALUE).also { used -= p to Int.MIN_VALUE }
            val main = finish()
            count("mode:tags")
            return Doc(order(main, untagged()), pageCount, true, stats)
        }

        /** Untagged PDFs: text blocks from the letters' positions, headers and footers by position on the page. */
        fun flat(): Doc {
            val blocks = mutableListOf<Block>()
            for (page in 1..pageCount) {
                val h = size(page).second
                for ((box, text) in lineGroups(pageChars(page))) {
                    val kind = if (box[3] < 0.08f * h) "page_header" else if (box[1] > 0.92f * h) "page_footer" else "text"
                    val b = norm(page, box)
                    blocks += Block(page, kind, text, b, body = "<$kind>${loc(b)}${esc(text)}</$kind>"); count(kind)
                }
            }
            count("mode:flat")
            return Doc(blocks, pageCount, false, stats)
        }

        /**
         * Reading order with page breaks: each page's loose header text first, then its blocks, then its loose body and
         * footer text. A block tagged on an earlier page after a later one stays where it is (counted).
         */
        fun order(blocks: List<Block>, extra: Map<Int, Map<String, List<Block>>>): List<Block> {
            val out = mutableListOf<Block>()
            fun zone(p: Int, z: String) = extra[p]?.get(z).orEmpty()
            fun close(p: Int) { out += zone(p, "body"); out += zone(p, "footer") }
            var cur = 1
            out += zone(1, "header")
            for (b in blocks) {
                if (b.page > cur) { for (next in cur + 1..b.page) { close(next - 1); out += zone(next, "header") }; cur = b.page }
                else if (b.page < cur) count("out_of_page_order")
                out += b
            }
            for (next in cur + 1..pageCount) { close(next - 1); out += zone(next, "header") }
            close(max(cur, pageCount))
            return out
        }
    }

    private fun loc(box: List<Int>) = box.joinToString("") { "<location value=\"$it\"/>" }
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** The document as DocLang 0.9 XML, with a page break between pages. */
    fun toXml(d: Doc): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<doclang xmlns=\"https://www.doclang.ai/ns/v0\" version=\"0.9\">\n")
        append("<head><default_resolution width=\"$RES\" height=\"$RES\"/><generated_by>${esc(GENERATOR)}</generated_by></head>\n")
        var cur = 1
        for (b in d.blocks) {
            while (b.page > cur) { append("<page_break/>\n"); cur++ }
            append(b.body ?: "<${b.kind}>${b.box?.let(::loc).orEmpty()}${esc(b.text)}</${b.kind}>").append('\n')
        }
        while (cur < d.pages) { append("<page_break/>\n"); cur++ }
        append("</doclang>\n")
    }
}
