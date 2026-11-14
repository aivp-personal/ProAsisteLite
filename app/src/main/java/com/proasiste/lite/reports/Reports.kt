package com.proasiste.lite.reports

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.proasiste.lite.Store
import com.proasiste.lite.inTransaction
import org.json.JSONArray
import org.json.JSONObject

/** A passage a report cites: the file, the page, the words, and where they sit ("page:x0,y0,x1,y1;…", or ""). */
data class Cited(val sourceId: String, val title: String, val page: Int, val excerpt: String, val place: String = "")

/**
 * A saved report: an answer kept with its question and cited passages, or a note. [model] says who wrote it
 * ("Gemini Nano", an OpenRouter model, "graph" for a list from the graph, or "" for a note written by hand).
 */
data class Report(val id: Long, val building: String, val title: String, val question: String, val body: String, val model: String,
                  val cited: List<Cited>, val created: Long, val updated: Long)

/** A dated decision the user took on a file or a report. [target] is "archivo" or "informe"; [value] is a short label. */
data class Decision(val id: Long, val building: String, val target: String, val targetId: String, val targetTitle: String, val value: String, val note: String, val created: Long)

/** Reports and decisions, per project, in the app's database. */
class Reports(private val store: Store) {
    private val db: SQLiteDatabase get() = store.writableDatabase

    // ---- Reports ----
    fun save(r: Report): Long {
        val now = System.currentTimeMillis()
        val v = ContentValues().apply {
            put("building", r.building); put("title", r.title.trim().take(200)); put("question", r.question); put("body", r.body)
            put("model", r.model); put("cited", citedJson(r.cited)); put("updated", now)
        }
        return if (r.id > 0) { db.update("reports", v, "id=?", arrayOf(r.id.toString())); r.id }
        else db.insertOrThrow("reports", null, v.apply { put("created", now) })
    }

    fun list(building: String): List<Report> = query("building=?", arrayOf(building))
    fun get(id: Long): Report? = query("id=?", arrayOf(id.toString())).firstOrNull()

    /** Deletes a report and the decisions taken on it. */
    fun delete(id: Long) = db.inTransaction {
        delete("decisions", "target='$REPORT' AND target_id=?", arrayOf(id.toString()))
        delete("reports", "id=?", arrayOf(id.toString()))
    }

    private fun query(where: String, args: Array<String>): List<Report> =
        db.rawQuery("SELECT id,building,title,question,body,model,cited,created,updated FROM reports WHERE $where ORDER BY updated DESC", args).use { c ->
            buildList { while (c.moveToNext()) add(Report(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), cited(c.getString(6)), c.getLong(7), c.getLong(8))) }
        }

    // ---- Decisions ----
    /** Records a decision; every decision is kept, so the latest one per target is the current one. */
    fun decide(building: String, target: String, targetId: String, value: String, note: String = ""): Long {
        require(target == FILE || target == REPORT) { "Unknown decision target" }
        require(value.isNotBlank()) { "A decision needs a value" }
        return db.insertOrThrow("decisions", null, ContentValues().apply {
            put("building", building); put("target", target); put("target_id", targetId); put("value", value.trim().take(80)); put("note", note.trim().take(1000)); put("created", System.currentTimeMillis())
        })
    }

    /** The latest decision on each target of [building], by "target:id". */
    fun latest(building: String): Map<String, Decision> = decisions(building).groupBy { "${it.target}:${it.targetId}" }.mapValues { it.value.first() } // newest first

    /** Every decision in [building], newest first, with the title of what it is about. */
    fun decisions(building: String): List<Decision> = db.rawQuery(
        """SELECT d.id,d.building,d.target,d.target_id,COALESCE(s.title,r.title,''),d.value,d.note,d.created FROM decisions d
           LEFT JOIN sources s ON d.target='$FILE' AND s.id=d.target_id LEFT JOIN reports r ON d.target='$REPORT' AND r.id=d.target_id
           WHERE d.building=? ORDER BY d.created DESC,d.id DESC""", arrayOf(building),
    ).use { c -> buildList { while (c.moveToNext()) add(Decision(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getLong(7))) } }

    companion object {
        const val FILE = "archivo"; const val REPORT = "informe"
        /** Decisions offered with one tap; any other wording can be typed. */
        val VALUES = listOf("relevante", "no relevante", "aceptado", "rechazado", "por revisar")

        fun citedJson(cited: List<Cited>): String = JSONArray(cited.map { JSONObject().put("source", it.sourceId).put("title", it.title).put("page", it.page).put("excerpt", it.excerpt).put("place", it.place) }).toString()
        fun cited(json: String?): List<Cited> = runCatching {
            val a = JSONArray(json ?: "[]")
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Cited(o.getString("source"), o.optString("title"), o.optInt("page"), o.optString("excerpt"), o.optString("place")) } }
        }.getOrDefault(emptyList())
    }
}
