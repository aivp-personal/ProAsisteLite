package com.proasiste.lite

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.runtime.GemmaEmbedder
import com.proasiste.lite.runtime.RulingColumns
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Not a check: the desktop's way to fill the rulings table. With COLS_SRC (a folder of rulings) and COLS_OUT (a pack's
 * index file) set, reads every PDF under COLS_SRC on all cores as the phone's "Read from files" does (the app's own
 * [Engine.pagesForAi] and [RulingColumns]) and writes each ruling's values to COLS_OUT's `values` table, keyed by the
 * file's SHA-256. On the phone, Import a folder on a folder holding that file loads them, for files already in the
 * project and for files arriving with it. Skipped when COLS_SRC isn't set.
 *
 * Run: COLS_SRC=... COLS_OUT=.../proasiste-index.db gradle testDebugUnitTest --tests '*DesktopColumnsBuild' --rerun
 */
@RunWith(RobolectricTestRunner::class)
class DesktopColumnsBuild {
    @Test fun build() {
        val src = System.getenv("COLS_SRC").orEmpty(); val out = System.getenv("COLS_OUT").orEmpty()
        assumeTrue(src.isNotEmpty() && out.isNotEmpty())
        val log = File("$out.log")
        fun println(line: String) { log.appendText("${java.time.LocalTime.now().withNano(0)} $line\n") }
        val engine = Engine.get(ApplicationProvider.getApplicationContext())
        File(out).parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(File(out), null)
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS files(sha256 TEXT PRIMARY KEY, name TEXT NOT NULL, pages INTEGER NOT NULL, no_text_pages INTEGER NOT NULL, error TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS chunks(sha256 TEXT NOT NULL, ordinal INTEGER NOT NULL, page INTEGER NOT NULL, start_ms INTEGER NOT NULL, text TEXT NOT NULL, vector BLOB, PRIMARY KEY(sha256, ordinal))")
        db.execSQL("CREATE TABLE IF NOT EXISTS \"values\"(sha256 TEXT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, col TEXT NOT NULL, value TEXT NOT NULL, page INTEGER NOT NULL, origin TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS values_sha ON \"values\"(sha256)")
        db.execSQL("CREATE TABLE IF NOT EXISTS values_read(sha256 TEXT PRIMARY KEY)")
        for ((k, v) in listOf("index_version" to GemmaEmbedder.INDEX_VERSION, "model_sha256" to GemmaEmbedder.MODEL_SHA256))
            db.insertWithOnConflict("meta", null, ContentValues().apply { put("name", k); put("value", v) }, SQLiteDatabase.CONFLICT_REPLACE)
        val done = db.rawQuery("SELECT sha256 FROM values_read", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

        val pdfs = File(src).walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) }.sortedBy { it.path }.toList()
        val byHash = LinkedHashMap<String, File>()
        for (f in pdfs) byHash.putIfAbsent(Store.sha256(f), f)
        val todo = byHash.filterKeys { it !in done }
        println("COLUMNS: ${pdfs.size} PDFs, ${byHash.size} distinct, ${todo.size} to read")
        val pool = Executors.newFixedThreadPool(maxOf(1, Runtime.getRuntime().availableProcessors() - 2))
        val started = System.currentTimeMillis(); var n = 0; var rulings = 0; var failed = 0
        try {
            val futures = todo.map { (hash, f) -> Callable { hash to runCatching { RulingColumns.of(engine.pagesForAi(Source(hash, f.path, "pdf", "", "", "", f.name, false)), f.name) } } }.map(pool::submit)
            for (fut in futures) {
                val (hash, result) = fut.get()
                db.beginTransaction()
                try {
                    db.delete("\"values\"", "sha256 = ?", arrayOf(hash))
                    result.getOrNull()?.let { (name, values) ->
                        for (v in values) db.insertOrThrow("\"values\"", null, ContentValues().apply {
                            put("sha256", hash); put("type", RulingColumns.TYPE); put("name", name); put("col", v.column); put("value", v.value); put("page", v.page); put("origin", RulingColumns.ORIGIN)
                        })
                        rulings++
                    }
                    db.insertWithOnConflict("values_read", null, ContentValues().apply { put("sha256", hash) }, SQLiteDatabase.CONFLICT_REPLACE)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                n++; if (result.isFailure) failed++
                if (n % 100 == 0 || n == todo.size) println("COLUMNS: $n/${todo.size} files · $rulings rulings · $failed not read · ${(System.currentTimeMillis() - started) / 1000} s")
            }
        } finally { pool.shutdownNow(); db.close() }
    }
}
