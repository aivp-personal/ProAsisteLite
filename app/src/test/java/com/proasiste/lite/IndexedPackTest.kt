package com.proasiste.lite

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.runtime.GemmaEmbedder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** An indexed pack: chunks and vectors made on a computer, used when a file with the same contents is indexed. */
@RunWith(RobolectricTestRunner::class)
class IndexedPackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: Store

    @Before fun open() { store = Store(context, null) }
    @After fun close() = store.close()

    private fun blob(seed: Float) = ByteBuffer.allocate(Store.DIMENSIONS * 4).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(Store.DIMENSIONS) { putFloat(if (it == 0) seed else 0.01f) } }.array()

    /** A pack with file "a" fully embedded, file "b" missing one vector, and file "c" that couldn't be read. */
    private fun pack(version: String = GemmaEmbedder.INDEX_VERSION, model: String = GemmaEmbedder.MODEL_SHA256): File {
        val f = File(context.cacheDir, "pack-${System.nanoTime()}.db")
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE meta(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE files(sha256 TEXT PRIMARY KEY, name TEXT NOT NULL, pages INTEGER NOT NULL, no_text_pages INTEGER NOT NULL, error TEXT NOT NULL)")
            db.execSQL("CREATE TABLE chunks(sha256 TEXT NOT NULL, ordinal INTEGER NOT NULL, page INTEGER NOT NULL, start_ms INTEGER NOT NULL, text TEXT NOT NULL, vector BLOB, PRIMARY KEY(sha256, ordinal))")
            db.execSQL("INSERT INTO meta VALUES ('index_version', ?), ('model_sha256', ?)", arrayOf(version, model))
            for ((hash, err) in listOf("a" to "", "b" to "", "c" to "Scanned PDF")) db.execSQL("INSERT INTO files VALUES (?, ?, 3, 1, ?)", arrayOf(hash, "$hash.pdf", err))
            fun chunk(hash: String, ordinal: Int, page: Int, text: String, vector: ByteArray?) =
                db.insert("chunks", null, ContentValues().apply { put("sha256", hash); put("ordinal", ordinal); put("page", page); put("start_ms", 0); put("text", text); put("vector", vector) })
            chunk("a", 0, 1, "primer trozo", blob(0.5f)); chunk("a", 1, 2, "segundo trozo", blob(0.7f))
            chunk("b", 0, 1, "sin vector todavía", null)
        }
        return f
    }

    private fun source(id: String, hash: String) = Source(id, File(store.library, "$id.pdf").apply { writeText(id) }.path, "pdf", "P05", "", "2026-11-15", "$id.pdf", false)
        .also { store.add(it, hash) }

    @Test fun aMatchingFileTakesItsChunksFromThePack() {
        assertEquals(1, store.importPrebuilt(pack()))
        assertEquals(1, store.prebuiltCount())
        val s = source("S1", "a")
        val e = store.prebuilt(s)!!
        assertEquals(listOf("primer trozo", "segundo trozo"), e.chunks.map { it.text })
        assertEquals(listOf(1, 2), e.chunks.map { it.page })
        assertEquals(0.7f, e.chunks[1].vector[0], 0f)
        assertEquals(3, e.pages); assertEquals(1, e.noTextPages)
        store.saveChunks(s, e.chunks, e.pages, e.noTextPages); store.dropPrebuilt(s)
        assertEquals(0, store.prebuiltCount())
        assertTrue(store.sources("P05").single().indexed)
        // Search finds it by its words, as if the phone had indexed it.
        val found = store.search(e.chunks[0].vector, "P05", queryText = "segundo trozo")
        assertEquals("S1", found.passages.first().source.id)
    }

    @Test fun incompleteOrUnreadFilesAndOtherFilesAreIndexedAsUsual() {
        store.importPrebuilt(pack())
        assertNull(store.prebuilt(source("S2", "b")))
        assertNull(store.prebuilt(source("S3", "c")))
        assertNull(store.prebuilt(source("S4", "not-in-the-pack")))
    }

    @Test fun aPackForAnotherIndexOrModelIsRefused() {
        assertTrue(runCatching { store.importPrebuilt(pack(version = "eg2-old")) }.isFailure)
        assertTrue(runCatching { store.importPrebuilt(pack(model = "0".repeat(64))) }.isFailure)
        assertEquals(0, store.prebuiltCount())
    }

    @Test fun columnValuesFromAPackReachFilesAlreadyInAndFilesStillToCome() {
        val f = pack()
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TABLE \"values\"(sha256 TEXT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, col TEXT NOT NULL, value TEXT NOT NULL, page INTEGER NOT NULL, origin TEXT NOT NULL)")
            for ((sha, rol) in listOf("x" to "STC Rol 17.292", "y" to "STC Rol 17.221"))
                for ((col, v) in listOf("Fecha" to "2026-09-02", "Resultado" to "Rechazado")) db.execSQL("INSERT INTO \"values\" VALUES (?, 'Fallo', ?, ?, ?, 9, 'reglas')", arrayOf(sha, rol, col, v))
        }
        val graph = com.proasiste.lite.graph.Graph(store); val columns = com.proasiste.lite.graph.GraphColumns(store, graph)
        source("S1", "x")  // already in the project
        assertEquals(Store.PackLoad(1, 2), store.importPack(f))
        assertEquals(1, columns.applyPrebuilt("P05"))
        assertEquals(listOf("STC Rol 17.292"), columns.rows("P05", "Fallo").map { it.node.name })
        assertEquals(listOf("Rechazado"), columns.rows("P05", "Fallo").single().values("Resultado"))
        source("S2", "y")  // arrives later, as when it is indexed
        assertEquals(1, columns.applyPrebuilt("P05"))
        assertEquals(setOf("STC Rol 17.292", "STC Rol 17.221"), columns.rows("P05", "Fallo").map { it.node.name }.toSet())
        assertEquals(0, columns.applyPrebuilt("P05"))
    }

    @Test fun schemaTwelveUpgrades() {
        val name = "pack-upgrade.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            Store.MIGRATIONS.take(12).flatten().forEach(db::execSQL); db.version = 12
        }
        Store(context, name).use { assertEquals(0, it.prebuiltCount()) }
        context.deleteDatabase(name)
    }
}
