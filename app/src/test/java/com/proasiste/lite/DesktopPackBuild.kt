package com.proasiste.lite

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.runtime.GemmaEmbedder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Not a check: the desktop half of an indexed pack. With PACK_SRC (a folder of documents) and PACK_OUT (the pack's
 * index file) set, reads every PDF under PACK_SRC with the app's own [Engine.pdfPieces], on all cores, and writes each
 * file's pieces to PACK_OUT with empty vectors; desktop/embed_pack.py then embeds them with the app's model file. Files
 * already in PACK_OUT are skipped, so a stopped run resumes. Skipped when PACK_SRC isn't set.
 *
 * Run: PACK_SRC=... PACK_OUT=.../proasiste-index.db gradle testDebugUnitTest --tests '*DesktopPackBuild'
 */
@RunWith(RobolectricTestRunner::class)
class DesktopPackBuild {
    @Test fun build() {
        val src = System.getenv("PACK_SRC").orEmpty(); val out = System.getenv("PACK_OUT").orEmpty()
        assumeTrue(src.isNotEmpty() && out.isNotEmpty())
        val log = File("$out.log")
        fun println(line: String) { log.appendText("${java.time.LocalTime.now().withNano(0)} $line\n") }
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
        File(out).parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(File(out), null)
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS files(sha256 TEXT PRIMARY KEY, name TEXT NOT NULL, pages INTEGER NOT NULL, no_text_pages INTEGER NOT NULL, error TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS chunks(sha256 TEXT NOT NULL, ordinal INTEGER NOT NULL, page INTEGER NOT NULL, start_ms INTEGER NOT NULL, text TEXT NOT NULL, vector BLOB, PRIMARY KEY(sha256, ordinal))")
        db.insertWithOnConflict("meta", null, ContentValues().apply { put("name", "index_version"); put("value", GemmaEmbedder.INDEX_VERSION) }, SQLiteDatabase.CONFLICT_REPLACE)
        val done = db.rawQuery("SELECT sha256 FROM files", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

        // One file per content: copies under another name are read once.
        val pdfs = File(src).walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) }.sortedBy { it.path }.toList()
        val byHash = LinkedHashMap<String, File>()
        for (f in pdfs) byHash.putIfAbsent(Store.sha256(f), f)
        val todo = byHash.filterKeys { it !in done }
        println("PACK: ${pdfs.size} PDFs, ${byHash.size} distinct, ${todo.size} to read")

        val pool = Executors.newFixedThreadPool(maxOf(1, Runtime.getRuntime().availableProcessors() - 2))
        val started = System.currentTimeMillis(); var n = 0; var pages = 0; var pieces = 0; var failed = 0
        try {
            val futures = todo.map { (hash, f) -> Callable { hash to f to runCatching { Engine.pdfPieces(f) } } }.map(pool::submit)
            for (fut in futures) {
                val (hf, result) = fut.get(); val (hash, f) = hf
                db.beginTransaction()
                try {
                    val p = result.getOrNull()
                    db.insertWithOnConflict("files", null, ContentValues().apply {
                        put("sha256", hash); put("name", f.name); put("pages", p?.pages ?: (result.exceptionOrNull() as? Engine.NeedsOcr)?.pages ?: 0)
                        put("no_text_pages", p?.noTextPages ?: (result.exceptionOrNull() as? Engine.NeedsOcr)?.pages ?: 0)
                        put("error", result.exceptionOrNull()?.let { (it.message ?: it.javaClass.simpleName).take(250) } ?: "")
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                    db.delete("chunks", "sha256 = ?", arrayOf(hash))
                    p?.pieces?.forEach { piece ->
                        db.insertOrThrow("chunks", null, ContentValues().apply { put("sha256", hash); put("ordinal", piece.ordinal); put("page", piece.page); put("start_ms", 0); put("text", piece.text) })
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                n++; if (result.isSuccess) { pages += result.getOrThrow().pages; pieces += result.getOrThrow().pieces.size } else failed++
                if (n % 50 == 0 || n == todo.size) println("PACK: $n/${todo.size} files · $pages pages · $pieces pieces · $failed not read · ${(System.currentTimeMillis() - started) / 1000} s")
            }
        } finally { pool.shutdownNow(); db.close() }
    }
}
