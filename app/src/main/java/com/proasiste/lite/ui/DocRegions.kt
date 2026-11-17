package com.proasiste.lite.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import com.proasiste.lite.runtime.DocLang

/**
 * Draws a page's DocLang blocks onto its rendered image, so the viewer can show every region the reader found: one
 * outline per block, coloured by kind, labelled with the kind and its place in the page's reading order. Text the tag
 * tree left out (placed by position) is dashed; the halves of a paragraph split by a page break carry their thread number.
 */
object DocRegions {
    /** Kinds in legend order, with their colour (ARGB) and Spanish name. */
    val KINDS = linkedMapOf(
        "heading" to (0xFFE0533D.toInt() to "título"),
        "text" to (0xFF3D8BE0.toInt() to "texto"),
        "list" to (0xFF35B07A.toInt() to "lista"),
        "table" to (0xFFB05FD6.toInt() to "tabla"),
        "footnote" to (0xFFE0A23D.toInt() to "nota al pie"),
        "formula" to (0xFF2BB5B5.toInt() to "fórmula"),
        "picture" to (0xFFD6569B.toInt() to "imagen"),
        "page_header" to (0xFF8A8A8A.toInt() to "encabezado"),
        "page_footer" to (0xFF6E6E6E.toInt() to "pie de página"),
    )
    private const val OTHER = 0xFFC8C83C.toInt()
    fun color(kind: String) = KINDS[kind]?.first ?: OTHER

    /** The page's blocks that have a box, in the reader's reading order (loose text where it sits). */
    fun onPage(d: DocLang.Doc, page: Int) = com.proasiste.lite.runtime.DocPages.inPositionOrder(d.blocks.filter { it.page == page && it.box != null })

    /** A copy of [base] with [blocks] outlined; boxes are on DocLang's 500 grid. */
    fun paint(base: Bitmap, blocks: List<DocLang.Block>): Bitmap {
        val b = if (base.isMutable) base else base.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(b)
        val sx = b.width / DocLang.RES.toFloat(); val sy = b.height / DocLang.RES.toFloat()
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        val fill = Paint().apply { style = Paint.Style.FILL }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 20f; isFakeBoldText = true }
        val tag = Paint().apply { style = Paint.Style.FILL }
        blocks.forEachIndexed { i, blk ->
            val (x0, y0, x1, y1) = blk.box!!
            val l = x0 * sx; val t = y0 * sy; val r = (x1 + 1) * sx; val bt = (y1 + 1) * sy
            val col = color(blk.kind)
            fill.color = (col and 0x00FFFFFF) or (0x22 shl 24)
            c.drawRect(l, t, r, bt, fill)
            stroke.color = col; stroke.pathEffect = if (blk.loose) DashPathEffect(floatArrayOf(10f, 6f), 0f) else null
            c.drawRect(l, t, r, bt, stroke)
            val text = buildString {
                append(i + 1).append(' ').append(blk.kind)
                if (blk.kind == "heading" && blk.level > 0) append(' ').append(blk.level)
                if (blk.thread > 0) append(" ↔").append(blk.thread)
            }
            val w = label.measureText(text) + 8f
            val top = if (t >= 24f) t - 24f else t
            tag.color = col; c.drawRect(l, top, l + w, top + 24f, tag)
            c.drawText(text, l + 4f, top + 19f, label)
        }
        return b
    }
}
