package com.proasiste.lite.reports

import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.proasiste.lite.Store
import com.proasiste.lite.graph.Graph
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reports, decisions and graph facts as files: Markdown and PDF for reading, CSV for spreadsheets, JSON for tools. */
object Export {
    private fun date(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    /** A report as Markdown: title, question, answer, who wrote it and when, the cited passages, and decisions taken on it. */
    fun markdown(r: Report, decisions: List<Decision> = emptyList()): String = buildString {
        append("# ").append(r.title).append("\n\n")
        if (r.question.isNotBlank()) append("**Pregunta:** ").append(r.question.trim()).append("\n\n")
        append(r.body.trim()).append("\n\n")
        val by = when (r.model) { "" -> "Nota"; "graph" -> "Listado del grafo del proyecto"; else -> "Escrito por ${r.model}" }
        append("_${by} · ${date(r.created)}${if (r.updated > r.created + 1000) " · editado ${date(r.updated)}" else ""}_\n")
        if (r.cited.isNotEmpty()) {
            append("\n## Fuentes\n\n")
            r.cited.forEachIndexed { i, c -> append("${i + 1}. ${c.title}, página ${c.page}: «${c.excerpt.trim().replace("\n", " ")}»\n") }
        }
        val mine = decisions.filter { it.target == Reports.REPORT && it.targetId == r.id.toString() }
        if (mine.isNotEmpty()) {
            append("\n## Decisiones\n\n")
            mine.forEach { d -> append("- ${date(d.created)}: **${d.value}**${if (d.note.isNotBlank()) " — ${d.note}" else ""}\n") }
        }
    }

    /** One CSV field, quoted when it holds a comma, a quote or a line break (RFC 4180). */
    fun csv(field: String): String = if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field
    private fun row(vararg fields: Any) = fields.joinToString(",") { csv(it.toString()) } + "\r\n"

    /** Every decision in a project, newest first. The byte-order mark lets Excel read the accents. */
    fun decisionsCsv(decisions: List<Decision>): String = buildString {
        append('﻿').append(row("fecha", "tipo", "titulo", "decision", "nota"))
        decisions.forEach { d -> append(row(date(d.created), if (d.target == Reports.FILE) "archivo" else "informe", d.targetTitle, d.value, d.note)) }
    }

    /** A graph fact as exported. */
    data class Fact(val sourceId: String, val title: String, val sha: String, val page: Int, val fromType: String, val from: String, val relation: String,
                    val toType: String, val to: String, val statement: String, val extractor: String, val place: String)

    fun facts(store: Store, building: String): List<Fact> = store.readableDatabase.rawQuery(
        """SELECT e.source, s.title, s.sha256, e.page, f.type, f.name, e.type, t.type, t.name, e.fact, e.extractor, e.place FROM graph_edges e
           JOIN graph_nodes f ON f.id = e.from_id JOIN graph_nodes t ON t.id = e.to_id JOIN sources s ON s.id = e.source
           WHERE s.building = ? AND e.fact <> '' ORDER BY s.title, e.page, e.id""", arrayOf(building),
    ).use { c -> buildList { while (c.moveToNext()) add(Fact(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(9), c.getString(10) ?: "", c.getString(11) ?: "")) } }

    fun factsCsv(facts: List<Fact>): String = buildString {
        append('﻿').append(row("archivo", "pagina", "tipo_origen", "origen", "vinculo", "tipo_destino", "destino", "dato", "extractor"))
        facts.forEach { f -> append(row(f.title, f.page, f.fromType, f.from, f.relation, f.toType, f.to, f.statement, f.extractor)) }
    }

    /** The graph as one "proasiste-graph-1" object per file: each can be loaded back with Import a graph file. */
    fun factsJson(facts: List<Fact>): String = JSONArray(facts.groupBy { it.sourceId }.values.map { fs ->
        JSONObject().put("format", Graph.FORMAT).put("source_title", fs.first().title).put("source_sha256", fs.first().sha)
            .put("facts", JSONArray(fs.map { f ->
                JSONObject().put("page", f.page).put("subject", JSONArray(listOf(f.fromType, f.from))).put("relation", f.relation)
                    .put("object", JSONArray(listOf(f.toType, f.to))).put("statement", f.statement).put("extractor", f.extractor).put("place", f.place)
            }))
    }).toString(2)

    /**
     * A report as a PDF: A4 pages, the title, then the same text as the Markdown without its marks, wrapped and split over
     * as many pages as it needs. Drawn with Android's own PDF writer, so nothing leaves the phone.
     */
    fun pdf(r: Report, decisions: List<Decision>, out: OutputStream) {
        val text = markdown(r, decisions).lines().filter { !it.startsWith("# ") }.joinToString("\n") { line ->
            line.removePrefix("## ").replace("**", "").replace("_", "")
        }.trim()
        val width = 595; val height = 842; val margin = 56
        val titlePaint = TextPaint().apply { isAntiAlias = true; textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
        val bodyPaint = TextPaint().apply { isAntiAlias = true; textSize = 10.5f }
        fun layout(s: String, p: TextPaint) = StaticLayout.Builder.obtain(s, 0, s.length, p, width - 2 * margin).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()
        val title = layout(r.title, titlePaint); val body = layout(text, bodyPaint)
        val doc = PdfDocument()
        try {
            var line = 0; var pageNo = 1
            while (line < body.lineCount || pageNo == 1) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, pageNo).create())
                val canvas = page.canvas
                var top = margin.toFloat()
                if (pageNo == 1) { canvas.save(); canvas.translate(margin.toFloat(), top); title.draw(canvas); canvas.restore(); top += title.height + 16f }
                // As many whole lines as fit below [top].
                val first = line; val startY = body.getLineTop(first)
                var last = first
                while (last < body.lineCount && body.getLineBottom(last) - startY <= height - margin - top) last++
                canvas.save(); canvas.translate(margin.toFloat(), top - startY)
                canvas.clipRect(0f, startY.toFloat(), (width - 2 * margin).toFloat(), if (last > first) body.getLineBottom(last - 1).toFloat() else startY.toFloat())
                body.draw(canvas); canvas.restore()
                doc.finishPage(page)
                line = last; pageNo++
                if (last == first && line < body.lineCount) line++ // a single line taller than a page: never loop forever
            }
            doc.writeTo(out)
        } finally { doc.close() }
    }

    /**
     * A file name from a title: letters, digits and spaces, at most 60 characters. Accents written as separate marks
     * ("O" + "́", as some PDF file names are) are joined to their letter first, or "RETENCIÓN" would become "RETENCIO N".
     */
    fun fileName(title: String, extension: String) = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFC)
        .replace(Regex("[^\\p{L}\\p{M}\\p{N} _-]+"), " ").replace(Regex("\\s+"), " ").trim().take(60).ifEmpty { "informe" } + ".$extension"
}
