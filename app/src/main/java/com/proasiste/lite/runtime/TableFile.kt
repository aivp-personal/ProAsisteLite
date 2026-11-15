package com.proasiste.lite.runtime

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

/**
 * Spreadsheets as rows of text, read with no library: CSV (comma, semicolon, tab or pipe, found by counting) and .xlsx
 * (a zip of XML parts). In an .xlsx every sheet is its own table; a number in a date-formatted cell becomes an ISO date,
 * a formula gives the value Excel last saved, and a merged cell's value fills every cell it covers. Old .xls files and
 * password-protected workbooks are refused with a reason.
 */
object TableFile {
    /** One sheet: its name and its rows, the first being the headers. */
    data class Sheet(val name: String, val rows: List<List<String>>) {
        val headers: List<String> get() = rows.firstOrNull().orEmpty()
        val body: List<List<String>> get() = rows.drop(1).filter { r -> r.any { it.isNotBlank() } }
    }

    const val MAX_ROWS = 20_000

    fun read(name: String, bytes: ByteArray): List<Sheet> = when {
        name.endsWith(".xlsx", true) -> xlsx(bytes)
        name.endsWith(".csv", true) || name.endsWith(".tsv", true) || name.endsWith(".txt", true) -> listOf(Sheet(name.substringBeforeLast('.'), csv(String(bytes, charset(bytes)))))
        name.endsWith(".xls", true) -> error(tr("Old .xls files can't be read. Open it in Excel and save it as .xlsx.", "Los archivos .xls antiguos no se pueden leer. Ábrelo en Excel y guárdalo como .xlsx."))
        else -> error(tr("Only CSV and .xlsx tables can be imported.", "Solo se pueden importar tablas CSV y .xlsx."))
    }

    // ---- CSV ----

    private fun charset(bytes: ByteArray) = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) Charsets.UTF_8
        else runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)); Charsets.UTF_8 }.getOrDefault(Charsets.ISO_8859_1)

    /** The delimiter that splits the first lines most evenly: comma, semicolon, tab or pipe. */
    fun delimiter(text: String): Char {
        val lines = text.lineSequence().filter { it.isNotBlank() }.take(20).toList()
        return listOf(',', ';', '\t', '|').maxByOrNull { d ->
            val counts = lines.map { l -> split(l, d).size }
            if (counts.isEmpty() || counts.first() < 2) 0.0 else counts.count { it == counts.first() }.toDouble() / counts.size * counts.first()
        } ?: ','
    }

    fun csv(raw: String): List<List<String>> {
        val text = raw.removePrefix("﻿")
        val d = delimiter(text)
        val rows = mutableListOf<List<String>>()
        var field = StringBuilder(); var row = mutableListOf<String>(); var quoted = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' && (quoted || field.isEmpty()) -> quoted = !quoted
                !quoted && c == d -> { row += field.toString().trim(); field = StringBuilder() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += field.toString().trim(); field = StringBuilder(); rows += row; row = mutableListOf()
                    require(rows.size <= MAX_ROWS + 1) { tooMany() }
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString().trim(); rows += row }
        val width = rows.maxOfOrNull { it.size } ?: 0
        return rows.filter { r -> r.any { it.isNotBlank() } }.map { it + List(width - it.size) { "" } }
    }

    private fun split(line: String, d: Char): List<String> {
        val out = mutableListOf<String>(); var quoted = false; var start = 0
        for ((i, c) in line.withIndex()) { if (c == '"') quoted = !quoted else if (c == d && !quoted) { out += line.substring(start, i); start = i + 1 } }
        out += line.substring(start); return out
    }

    private fun tooMany() = tr("This table has more than $MAX_ROWS rows.", "Esta tabla tiene más de $MAX_ROWS filas.")

    // ---- .xlsx ----

    fun xlsx(bytes: ByteArray): List<Sheet> {
        val parts = HashMap<String, ByteArray>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory && e.name.endsWith(".xml") || e.name.endsWith(".rels")) parts[e.name.removePrefix("/")] = z.readBytes() }
            }
        }
        require(parts.containsKey("xl/workbook.xml")) { tr("This isn't a readable .xlsx file (it may be password-protected).", "Este no es un archivo .xlsx legible (puede estar protegido con contraseña).") }
        val shared = parts["xl/sharedStrings.xml"]?.let { sharedStrings(it) }.orEmpty()
        val dateStyles = parts["xl/styles.xml"]?.let { dateStyles(it) }.orEmpty()
        val targets = parts["xl/_rels/workbook.xml.rels"]?.let { rels(it) }.orEmpty()
        val sheets = mutableListOf<Sheet>()
        parse(parts.getValue("xl/workbook.xml")) { p ->
            if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                val name = p.getAttributeValue(null, "name") ?: "Sheet"
                val rid = (0 until p.attributeCount).firstOrNull { p.getAttributeName(it) == "r:id" || p.getAttributeName(it).endsWith(":id") || p.getAttributeName(it) == "id" }?.let { p.getAttributeValue(it) }
                val target = targets[rid]?.let { t -> if (t.startsWith("/")) t.removePrefix("/") else "xl/" + t.removePrefix("./") }
                target?.let { parts[it] }?.let { sheets += Sheet(name, sheet(it, shared, dateStyles)) }
            }
        }
        return sheets.filter { it.rows.isNotEmpty() }
    }

    private fun parse(xml: ByteArray, on: (XmlPullParser) -> Unit) {
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        p.setInput(ByteArrayInputStream(xml) as InputStream, "UTF-8")
        while (p.next() != XmlPullParser.END_DOCUMENT) on(p)
    }

    private fun sharedStrings(xml: ByteArray): List<String> {
        val out = mutableListOf<String>(); var cur: StringBuilder? = null; var inT = false; var inRph = false
        parse(xml) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) { "si" -> cur = StringBuilder(); "t" -> inT = true; "rPh" -> inRph = true }
                XmlPullParser.END_TAG -> when (p.name) { "si" -> { out += cur.toString(); cur = null }; "t" -> inT = false; "rPh" -> inRph = false }
                XmlPullParser.TEXT -> if (inT && !inRph) cur?.append(p.text)
            }
        }
        return out
    }

    private val BUILT_IN_DATES = (14..22).toSet() + (45..47)

    /** The cell styles (by index) whose number format is a date. */
    private fun dateStyles(xml: ByteArray): Set<Int> {
        val custom = HashMap<Int, String>(); val styles = mutableListOf<Int>(); var inXfs = false
        parse(xml) { p ->
            if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                "numFmt" -> custom[p.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: -1] = p.getAttributeValue(null, "formatCode").orEmpty()
                "cellXfs" -> inXfs = true
                "xf" -> if (inXfs) styles += p.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0
            } else if (p.eventType == XmlPullParser.END_TAG && p.name == "cellXfs") inXfs = false
        }
        fun isDate(fmt: Int) = fmt in BUILT_IN_DATES || custom[fmt]?.replace(Regex("\"[^\"]*\"|\\[[^]]*]"), "")?.lowercase()?.let { f -> ('d' in f || 'y' in f) && !f.contains("0.0") } == true
        return styles.withIndex().filter { isDate(it.value) }.map { it.index }.toSet()
    }

    private fun rels(xml: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        parse(xml) { p -> if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") out[p.getAttributeValue(null, "Id")] = p.getAttributeValue(null, "Target") }
        return out
    }

    /** "C12" → (11, 2): zero-based row and column. */
    private fun ref(r: String): Pair<Int, Int> {
        val letters = r.takeWhile { it.isLetter() }.uppercase()
        val col = letters.fold(0) { a, c -> a * 26 + (c - 'A' + 1) } - 1
        return (r.drop(letters.length).toIntOrNull() ?: 1) - 1 to col
    }

    private fun sheet(xml: ByteArray, shared: List<String>, dateStyles: Set<Int>): List<List<String>> {
        val cells = HashMap<Pair<Int, Int>, String>(); val merges = mutableListOf<Pair<Pair<Int, Int>, Pair<Int, Int>>>()
        var at: Pair<Int, Int>? = null; var type = ""; var style = -1; var value = StringBuilder(); var inV = false; var inT = false
        var nextRow = 0; var nextCol = 0
        parse(xml) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> { nextRow = (p.getAttributeValue(null, "r")?.toIntOrNull() ?: (nextRow + 1)) - 1; nextCol = 0 }
                    "c" -> {
                        at = p.getAttributeValue(null, "r")?.let(::ref) ?: (nextRow to nextCol)
                        nextCol = at!!.second + 1
                        type = p.getAttributeValue(null, "t").orEmpty(); style = p.getAttributeValue(null, "s")?.toIntOrNull() ?: -1; value = StringBuilder()
                    }
                    "v" -> inV = true
                    "t" -> inT = true
                    "mergeCell" -> p.getAttributeValue(null, "ref")?.split(":")?.takeIf { it.size == 2 }?.let { merges += ref(it[0]) to ref(it[1]) }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val raw = value.toString()
                        val text = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            "inlineStr", "str" -> raw
                            "e" -> ""
                            else -> raw.trim().toDoubleOrNull()?.let { n -> if (style in dateStyles) excelDate(n) else number(n) } ?: raw
                        }
                        if (text.isNotEmpty()) at?.let { cells[it] = text.trim() }
                        require(nextRow <= MAX_ROWS) { tooMany() }
                    }
                }
                XmlPullParser.TEXT -> if (inV || inT) value.append(p.text)
            }
        }
        // A merged cell's value fills every cell it covers, so grouped rows keep their label.
        for ((from, to) in merges) cells[from]?.let { v -> for (r in from.first..to.first) for (c in from.second..to.second) cells.putIfAbsent(r to c, v) }
        if (cells.isEmpty()) return emptyList()
        val rows = cells.keys.maxOf { it.first } + 1; val cols = cells.keys.maxOf { it.second } + 1
        return (0 until rows).map { r -> (0 until cols).map { c -> cells[r to c].orEmpty() } }.dropWhile { r -> r.all { it.isBlank() } }
    }

    /** Excel's day number (1900 system) as an ISO date: 45612 → 2024-11-15. */
    fun excelDate(n: Double): String = LocalDate.of(1899, 12, 30).plusDays(n.toLong()).toString()

    private fun number(n: Double) = if (n == Math.floor(n) && kotlin.math.abs(n) < 1e15) n.toLong().toString() else n.toString()

    private fun tr(en: String, es: String) = com.proasiste.lite.ui.tr(en, es)

    // ---- Writing .xlsx ----

    /** A one-sheet .xlsx: the headers, then the rows, every cell as text. */
    fun writeXlsx(out: java.io.OutputStream, sheetName: String, headers: List<String>, rows: List<List<String>>) {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").filter { it == '\t' || it == '\n' || it >= ' ' }
        fun col(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val m = (n - 1) % 26; sb.insert(0, 'A' + m); n = (n - 1) / 26 }; return sb.toString() }
        val sheet = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
        (listOf(headers) + rows).forEachIndexed { r, row ->
            sheet.append("<row r=\"${r + 1}\">")
            row.forEachIndexed { c, v -> if (v.isNotEmpty()) sheet.append("<c r=\"${col(c)}${r + 1}\" t=\"inlineStr\"${if (r == 0) " s=\"1\"" else ""}><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>") }
            sheet.append("</row>")
        }
        sheet.append("</sheetData></worksheet>")
        val files = linkedMapOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>",
            "_rels/.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
            "xl/workbook.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"${esc(sheetName.take(31).replace(Regex("[\\\\/?*\\[\\]:]"), " "))}\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>",
            "xl/styles.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"2\"><xf fontId=\"0\"/><xf fontId=\"1\" applyFont=\"1\"/></cellXfs></styleSheet>",
            "xl/worksheets/sheet1.xml" to sheet.toString(),
        )
        java.util.zip.ZipOutputStream(out).use { z -> files.forEach { (name, xml) -> z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(xml.toByteArray(Charsets.UTF_8)); z.closeEntry() } }
    }
}
