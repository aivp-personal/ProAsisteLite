package com.proasiste.lite.actions

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.proasiste.lite.Store
import com.proasiste.lite.inTransaction
import com.proasiste.lite.reports.Cited
import com.proasiste.lite.reports.Report
import com.proasiste.lite.reports.Reports

/** An action the user can run on files: a built-in one, or a skill (their own name and instructions). */
data class Action(val key: String, val name: String, val instructions: String, val skillId: Long = 0) {
    /** Converting to DocLang reads the PDF on the phone and needs no model. */
    val local: Boolean get() = key == DOCLANG
    companion object { const val DOCLANG = "doclang" }
}
/** A run of one action over a set of files. */
data class ActionRun(val id: Long, val building: String, val action: String, val title: String, val instructions: String, val reportId: Long, val folder: String)
/** A file waiting in a run. */
data class ActionJob(val id: Long, val run: ActionRun, val sourceId: String)

/**
 * Actions on files. Built-in actions and the user's skills; a run applies one to a project or a tag: its files wait in a
 * queue that the background service works through, and every result is added to the run's own report as it comes in.
 */
class Actions(private val store: Store) {
    private val db: SQLiteDatabase get() = store.writableDatabase
    private val reports = Reports(store)

    // ---- Skills ----
    fun skills(): List<Action> = db.rawQuery("SELECT id,name,instructions FROM skills ORDER BY name COLLATE NOCASE", null).use { c ->
        buildList { while (c.moveToNext()) add(Action("skill:${c.getLong(0)}", c.getString(1), c.getString(2), c.getLong(0))) }
    }
    fun saveSkill(name: String, instructions: String): Long {
        require(name.isNotBlank() && instructions.isNotBlank()) { "A skill needs a name and instructions" }
        return db.insertOrThrow("skills", null, ContentValues().apply { put("name", name.trim().take(60)); put("instructions", instructions.trim().take(4000)); put("created", System.currentTimeMillis()) })
    }
    fun deleteSkill(id: Long) { db.delete("skills", "id=?", arrayOf(id.toString())) }

    /** Built-in actions first, then the user's skills. */
    fun all(): List<Action> = BUILT_IN + skills()

    // ---- Runs ----
    /**
     * Starts [action] over [sourceIds] of [building]: creates the run's report (empty, filled as files finish) and queues
     * the files. [folder] is the picked folder a DocLang run writes into. Returns the run's report id.
     */
    fun start(building: String, action: Action, scope: String, sourceIds: List<String>, model: String, folder: String = ""): Long {
        require(sourceIds.isNotEmpty()) { "No files to run on" }
        if (action.local) require(folder.isNotBlank()) { "Pick a folder for the DocLang files" }
        val title = "${action.name} · $scope"
        val reportId = reports.save(Report(0, building, title, "", "", if (action.local) "" else model, emptyList(), 0, 0))
        db.inTransaction {
            val run = insertOrThrow("action_runs", null, ContentValues().apply {
                put("building", building); put("action", action.key); put("title", title); put("instructions", action.instructions)
                put("report_id", reportId); put("folder", folder); put("created", System.currentTimeMillis())
            })
            sourceIds.distinct().forEach { insertOrThrow("action_queue", null, ContentValues().apply { put("run_id", run); put("source", it) }) }
        }
        return reportId
    }

    fun next(buildings: Set<String>): ActionJob? {
        if (buildings.isEmpty()) return null
        return db.rawQuery("""SELECT q.id,q.source,r.id,r.building,r.action,r.title,r.instructions,r.report_id,r.folder FROM action_queue q JOIN action_runs r ON r.id=q.run_id
               WHERE q.status='${Store.QUEUED}' AND r.building IN (${buildings.joinToString(",") { "?" }}) ORDER BY q.id LIMIT 1""", buildings.toTypedArray()).use { c ->
            if (c.moveToFirst()) ActionJob(c.getLong(0), ActionRun(c.getLong(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getLong(7), c.getString(8)), c.getString(1)) else null
        }
    }

    fun pending(buildings: Set<String>): Int {
        if (buildings.isEmpty()) return 0
        return db.rawQuery("SELECT COUNT(*) FROM action_queue q JOIN action_runs r ON r.id=q.run_id WHERE q.status='${Store.QUEUED}' AND r.building IN (${buildings.joinToString(",") { "?" }})", buildings.toTypedArray()).use { it.moveToFirst(); it.getInt(0) }
    }

    /**
     * Records a file's result: adds a section "## title" with [text] to the run's report, and the file to its sources so
     * it opens from there. A failure is added too, in one line, so the report says what is missing.
     */
    fun finish(job: ActionJob, title: String, text: String, failed: Boolean = false) {
        db.inTransaction {
            update("action_queue", ContentValues().apply { put("status", if (failed) Store.FAILED else DONE); put("note", if (failed) text.take(250) else "") }, "id=?", arrayOf(job.id.toString()))
            val r = reports.get(job.run.reportId) ?: return@inTransaction
            val section = "## $title\n\n" + if (failed) "_No se pudo: ${text.trim()}_" else text.trim()
            val cited = if (failed || r.cited.any { it.sourceId == job.sourceId }) r.cited else r.cited + Cited(job.sourceId, title, 1, text.lineSequence().firstOrNull { it.isNotBlank() }?.take(200).orEmpty())
            reports.save(r.copy(body = if (r.body.isBlank()) section else r.body.trimEnd() + "\n\n" + section, cited = cited))
        }
    }

    /** Drops the files still waiting in [building]'s runs: Pause keeps the request, this forgets it. */
    fun cancel(building: String) { db.execSQL("DELETE FROM action_queue WHERE status='${Store.QUEUED}' AND run_id IN (SELECT id FROM action_runs WHERE building=?)", arrayOf(building)) }

    companion object {
        const val DONE = "hecho"
        private const val ANSWER = "Answer in the document's own language. Use only what the document says; never add outside knowledge. Cite pages as (p. N), using the page markers."
        val BUILT_IN = listOf(
            Action("summary", "Resumen", "Summarise the document in 5 to 8 bullet points: what it is, who is involved, what is asked or decided, and why. $ANSWER"),
            Action("tables", "Tablas", "Copy every table in the document as a Markdown table, each preceded by its page and a one-line title. Keep every number exactly. If there is no table, say so in one line. $ANSWER"),
            Action("classify", "Clasificar", "In three short lines: the kind of document (for example a ruling, a contract, a report), its subject, and its outcome or purpose. $ANSWER"),
            Action("dates", "Fechas y partes", "List every date the document gives, each with what happened then, in order; then every party (person, company or body) with its role. Use two Markdown lists, Fechas and Partes. $ANSWER"),
            Action(Action.DOCLANG, "Convertir a DocLang", ""),
        )
    }
}
