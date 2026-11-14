package com.proasiste.lite.tags

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.proasiste.lite.Source
import com.proasiste.lite.Store
import com.proasiste.lite.inTransaction

/** A tag rule: [kind] is [FOLDER] (each subfolder name), [NAME] ([pattern] in the file name gives [tag]) or [YEAR]. */
data class TagRule(val id: Long, val kind: String, val pattern: String = "", val tag: String = "")
/** A tag AI may choose, with the description it reads to decide. */
data class TagDef(val name: String, val description: String)
/** A file waiting for AI tagging. */
data class TagJob(val sourceId: String, val building: String)

/**
 * Tags on files, per project. Each file tag records who set it: by hand ([MANUAL]), by a rule ([RULE]) or by AI ([AI]).
 * A tag the user removes by hand is kept as [REMOVED], hidden, so no rule or AI run puts it back; a hand-set tag is
 * never replaced by a rule or AI run either.
 */
class Tags(private val store: Store) {
    private val db: SQLiteDatabase get() = store.writableDatabase

    /** The tag's id in [building], created if new. */
    private fun SQLiteDatabase.tagId(building: String, name: String): Long {
        insertWithOnConflict("tags", null, ContentValues().apply { put("building", building); put("name", name) }, SQLiteDatabase.CONFLICT_IGNORE)
        return rawQuery("SELECT id FROM tags WHERE building=? AND name=?", arrayOf(building, name)).use { it.moveToFirst(); it.getLong(0) }
    }

    /** Visible tags of each file in [building]. */
    fun bySource(building: String): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        db.rawQuery("SELECT st.source,t.name FROM source_tags st JOIN tags t ON t.id=st.tag_id WHERE t.building=? AND st.origin!='$REMOVED' ORDER BY t.name COLLATE NOCASE", arrayOf(building)).use { c ->
            while (c.moveToNext()) out.getOrPut(c.getString(0)) { mutableListOf() } += c.getString(1)
        }
        return out
    }

    /** Each tag in use in [building] with how many files carry it, most used first. */
    fun counts(building: String): List<Pair<String, Int>> =
        db.rawQuery("SELECT t.name,COUNT(*) n FROM source_tags st JOIN tags t ON t.id=st.tag_id WHERE t.building=? AND st.origin!='$REMOVED' GROUP BY t.id ORDER BY n DESC,t.name COLLATE NOCASE", arrayOf(building)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
        }

    /** Adds a tag by hand. It replaces a rule or AI tag of the same name, and undoes an earlier removal. */
    fun add(sourceId: String, building: String, name: String) {
        val clean = clean(name); if (clean.isEmpty()) return
        db.inTransaction { insertWithOnConflict("source_tags", null, ContentValues().apply { put("source", sourceId); put("tag_id", tagId(building, clean)); put("origin", MANUAL) }, SQLiteDatabase.CONFLICT_REPLACE) }
    }

    /** Removes a tag by hand; it stays removed for later rule and AI runs. */
    fun remove(sourceId: String, building: String, name: String) {
        db.inTransaction { insertWithOnConflict("source_tags", null, ContentValues().apply { put("source", sourceId); put("tag_id", tagId(building, name)); put("origin", REMOVED) }, SQLiteDatabase.CONFLICT_REPLACE) }
    }

    /**
     * Replaces the [origin] tags of a file with [names]. Tags set by hand and tags removed by hand are left alone, and a
     * name already set by the other automatic origin keeps that origin.
     */
    fun replace(sourceId: String, building: String, origin: String, names: Collection<String>) {
        db.inTransaction { replaceIn(this, sourceId, building, origin, names) }
    }

    private fun replaceIn(db: SQLiteDatabase, sourceId: String, building: String, origin: String, names: Collection<String>) {
        db.delete("source_tags", "source=? AND origin=?", arrayOf(sourceId, origin))
        for (name in names.map(::clean).filter { it.isNotEmpty() }.distinct()) {
            db.insertWithOnConflict("source_tags", null, ContentValues().apply { put("source", sourceId); put("tag_id", db.tagId(building, name)); put("origin", origin) }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    // ---- Rules ----
    fun rules(building: String): List<TagRule> =
        db.rawQuery("SELECT id,kind,pattern,tag FROM tag_rules WHERE building=? ORDER BY id", arrayOf(building)).use { c ->
            buildList { while (c.moveToNext()) add(TagRule(c.getLong(0), c.getString(1), c.getString(2), c.getString(3))) }
        }

    fun addRule(building: String, kind: String, pattern: String = "", tag: String = ""): Long {
        require(kind in setOf(FOLDER, NAME, YEAR)) { "Unknown rule kind" }
        if (kind == NAME) require(pattern.isNotBlank() && clean(tag).isNotEmpty()) { "A name rule needs words to look for and a tag" }
        return db.insert("tag_rules", null, ContentValues().apply { put("building", building); put("kind", kind); put("pattern", pattern.trim()); put("tag", clean(tag)) })
    }

    fun deleteRule(id: Long) { db.delete("tag_rules", "id=?", arrayOf(id.toString())) }

    /** Runs [building]'s rules over its files ([only] those when given). Returns how many files have rule tags afterwards. */
    fun applyRules(building: String, only: Collection<String>? = null): Int {
        val rules = rules(building)
        val sources = store.sources(building).let { all -> if (only == null) all else all.filter { it.id in only } }
        var tagged = 0
        db.inTransaction {
            for (s in sources) {
                val names = ruleTags(s, rules)
                replaceIn(this, s.id, building, RULE, names)
                if (names.isNotEmpty()) tagged++
            }
        }
        return tagged
    }

    // ---- AI ----
    /** Tags AI chooses from: those with a description, in name order. */
    fun definitions(building: String): List<TagDef> =
        db.rawQuery("SELECT name,description FROM tags WHERE building=? AND description!='' ORDER BY name COLLATE NOCASE", arrayOf(building)).use { c ->
            buildList { while (c.moveToNext()) add(TagDef(c.getString(0), c.getString(1))) }
        }

    /** Adds or changes a tag AI may choose; an empty description takes it off AI's list. */
    fun define(building: String, name: String, description: String) {
        val clean = clean(name); require(clean.isNotEmpty()) { "A tag needs a name" }
        db.inTransaction { val id = tagId(building, clean); update("tags", ContentValues().apply { put("description", description.trim().take(200)) }, "id=?", arrayOf(id.toString())) }
    }

    /** Queues [building]'s indexed PDFs for AI tagging; a file already queued is queued again. Returns how many. */
    fun queueForAi(building: String): Int = db.inTransaction {
        val ids = store.sources(building).filter { it.type == "pdf" && it.indexed }.map { it.id }
        ids.forEach { insertWithOnConflict("tag_queue", null, ContentValues().apply { put("source", it); put("building", building); put("status", Store.QUEUED); put("note", "") }, SQLiteDatabase.CONFLICT_REPLACE) }
        ids.size
    }

    fun nextForAi(buildings: Set<String>): TagJob? {
        if (buildings.isEmpty()) return null
        return db.rawQuery("SELECT source,building FROM tag_queue WHERE status='${Store.QUEUED}' AND building IN (${buildings.joinToString(",") { "?" }}) ORDER BY rowid LIMIT 1", buildings.toTypedArray()).use {
            if (it.moveToFirst()) TagJob(it.getString(0), it.getString(1)) else null
        }
    }

    fun finishAi(sourceId: String, status: String, note: String = "") {
        db.update("tag_queue", ContentValues().apply { put("status", status); put("note", note.take(250)) }, "source=?", arrayOf(sourceId))
    }

    fun pendingAi(buildings: Set<String>): Int {
        if (buildings.isEmpty()) return 0
        return db.rawQuery("SELECT COUNT(*) FROM tag_queue WHERE status='${Store.QUEUED}' AND building IN (${buildings.joinToString(",") { "?" }})", buildings.toTypedArray()).use { it.moveToFirst(); it.getInt(0) }
    }

    /** Stops AI tagging of [building]: files still waiting are dropped from the queue. */
    fun cancelAi(building: String) { db.delete("tag_queue", "building=? AND status='${Store.QUEUED}'", arrayOf(building)) }

    companion object {
        const val MANUAL = "mano"; const val RULE = "regla"; const val AI = "ia"; const val REMOVED = "quitada"
        /** AI tagging status of a file in the queue once it's done. */
        const val DONE = "hecho"
        const val FOLDER = "carpeta"; const val NAME = "nombre"; const val YEAR = "año"

        /** Tags are short single-line labels. */
        fun clean(name: String) = name.replace(Regex("\\s+"), " ").trim().take(60)

        private fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

        /** The tags [rules] give a file. */
        fun ruleTags(s: Source, rules: List<TagRule>): List<String> = rules.flatMap { r ->
            when (r.kind) {
                FOLDER -> s.asset.split('/').map(::clean).filter { it.isNotEmpty() }
                NAME -> if (fold(s.title).contains(fold(r.pattern.trim()))) listOf(r.tag) else emptyList()
                YEAR -> listOfNotNull(year(s.title))
                else -> emptyList()
            }
        }.distinct()

        private val FULL_YEAR = Regex("(?<!\\d)(19[5-9]\\d|20[0-4]\\d)(?!\\d)")
        /** A ruling's role, "2154-11" or "1298-2009": the part after the dash is its year. */
        private val ROLE = Regex("(?<!\\d)\\d{1,5}-(\\d{2}|\\d{4})(?!\\d)")

        /** "2022-05-03" or "2022_05_03": a date, whose year comes first. */
        private val DATE = Regex("(?<!\\d)(19[5-9]\\d|20[0-4]\\d)[-_.](0[1-9]|1[0-2])[-_.](0[1-9]|[12]\\d|3[01])(?!\\d)")

        /** The year a file name gives: a date's, else a role's ("2154-11" → 2011), else a four-digit year, else null. */
        fun year(name: String): String? {
            DATE.find(name)?.let { return it.groupValues[1] }
            ROLE.find(name)?.groupValues?.get(1)?.let { y ->
                if (y.length == 4) return y.takeIf { FULL_YEAR.matches(it) }
                val n = y.toInt(); return (if (n >= 50) 1900 + n else 2000 + n).toString()
            }
            return FULL_YEAR.find(name)?.value
        }
    }
}
