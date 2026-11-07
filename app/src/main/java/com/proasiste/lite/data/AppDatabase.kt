package com.proasiste.lite.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.atomic.AtomicLong

/**
 * The one SQLite database. Each entry in [MIGRATIONS] moves the schema up one version, and the list's length is the
 * current version, so a schema change is always a new entry. Never edit an entry that has shipped.
 * Foreign keys are on: deleting a project or document removes everything that depends on it.
 */
class AppDatabase(context: Context, name: String? = "proasiste-lite.db") :
    SQLiteOpenHelper(context, name, null, MIGRATIONS.size) {

    private val indexGeneration = AtomicLong()

    /** Changes whenever searchable passages change, so a cached search index knows it is out of date. */
    val currentIndexGeneration: Long get() = indexGeneration.get()

    fun markIndexChanged() {
        indexGeneration.incrementAndGet()
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) = migrate(db, 0, MIGRATIONS.size)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = migrate(db, oldVersion, newVersion)

    private fun migrate(db: SQLiteDatabase, from: Int, to: Int) {
        for (version in from until to) MIGRATIONS[version].forEach(db::execSQL)
    }

    /**
     * Vectors from another embedding setup can't be compared with new ones. When [version] changes, this clears them
     * and queues every document to be indexed again.
     */
    fun ensureIndexVersion(version: String) {
        val db = writableDatabase
        val stored = db.rawQuery("SELECT value FROM index_meta WHERE name = 'version'", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        if (stored == version) return
        db.inTransaction {
            execSQL("DELETE FROM passages")
            execSQL("UPDATE documents SET status = 'pending', error = NULL")
            execSQL("INSERT OR REPLACE INTO index_meta(name, value) VALUES ('version', ?)", arrayOf(version))
        }
        markIndexChanged()
    }

    companion object {
        val MIGRATIONS: List<List<String>> = listOf(
            listOf(
                """CREATE TABLE projects(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE
                )""",
                """CREATE TABLE documents(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                    title TEXT NOT NULL,
                    path TEXT NOT NULL,
                    sha256 TEXT NOT NULL,
                    byte_size INTEGER NOT NULL,
                    page_count INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'indexed', 'failed')),
                    error TEXT,
                    UNIQUE (project_id, sha256)
                )""",
                """CREATE TABLE passages(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    document_id INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                    page INTEGER NOT NULL CHECK (page >= 1),
                    text TEXT NOT NULL,
                    word_count INTEGER NOT NULL,
                    vector BLOB NOT NULL
                )""",
                "CREATE INDEX passages_document ON passages(document_id)",
                """CREATE TABLE terms(
                    term TEXT NOT NULL,
                    passage_id INTEGER NOT NULL REFERENCES passages(id) ON DELETE CASCADE,
                    frequency INTEGER NOT NULL,
                    PRIMARY KEY (term, passage_id)
                ) WITHOUT ROWID""",
                "CREATE INDEX terms_passage ON terms(passage_id)",
                """CREATE TABLE graph_cleanups(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_id INTEGER NOT NULL,
                    document_id INTEGER
                )""",
                "CREATE TABLE index_meta(name TEXT PRIMARY KEY, value TEXT NOT NULL)",
            ),
        )
    }
}

/** Runs [block] in a transaction: either all of it is saved, or none of it. */
inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        return block().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}
