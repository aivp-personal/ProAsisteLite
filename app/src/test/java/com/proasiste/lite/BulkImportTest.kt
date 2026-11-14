package com.proasiste.lite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BulkImportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: Store

    @Before fun open() { store = Store(context, null) }
    @After fun close() = store.close()

    private fun queued(name: String, folder: String = "") = QueuedFile(0, "content://tree/doc/$name", "", folder, name, "application/pdf", 0)
    private fun source(id: String, project: String = "P01", contents: String = id): Source {
        val file = File(store.library, "$id.pdf").apply { writeText(contents) }
        return Source(id, file.path, "pdf", project, "", "2026-11-13", "Title $id", false).also { store.add(it) }
    }
    private fun chunk(s: Source) = VectorChunk(s.id, 0, 1, 0, "some words", FloatArray(Store.DIMENSIONS) { if (it == 0) 1f else 0f })

    @Test fun aFileIsQueuedOncePerProject() {
        assertEquals(2, store.enqueue("P01", listOf(queued("a.pdf"), queued("b.pdf"))))
        assertEquals(1, store.enqueue("P01", listOf(queued("a.pdf"), queued("c.pdf"))))
        assertEquals(1, store.enqueue("P02", listOf(queued("a.pdf"))))
        assertEquals(3, store.importCounts("P01").pending)
    }

    @Test fun queueRunsInOrderAndOnlyForRequestedProjects() {
        store.enqueue("P01", listOf(queued("a.pdf"), queued("b.pdf")))
        store.enqueue("P02", listOf(queued("z.pdf")))
        assertNull(store.nextImport(emptySet()))
        val first = store.nextImport(setOf("P01"))!!
        assertEquals("a.pdf", first.name); assertEquals("P01", first.building)
        store.finishImport(first.id, Store.IMPORTED)
        val second = store.nextImport(setOf("P01"))!!
        store.finishImport(second.id, Store.DUPLICATE, "Title a")
        assertNull(store.nextImport(setOf("P01")))
        assertEquals(ImportCounts(0, 1, 1, 0), store.importCounts("P01"))
        assertEquals("z.pdf", store.nextImport(setOf("P01", "P02"))!!.name)
    }

    @Test fun pendingWorkCountsQueuedAndUnindexed() {
        store.enqueue("P01", listOf(queued("a.pdf")))
        val s = source("S1"); source("S2"); val bad = source("S3")
        store.saveChunks(s, listOf(chunk(s)))
        store.error(bad.id, "broken")
        // One queued file + S2 waiting; S1 is done and S3 failed.
        assertEquals(2, store.pendingWork(setOf("P01")))
        assertEquals("S2", store.nextToIndex(setOf("P01"))!!.id)
        assertEquals(0, store.pendingWork(setOf("P02")))
    }

    @Test fun indexingFollowsImportOrderAndSkipsFailures() {
        val a = source("A"); source("B"); source("C")
        store.error("B", "broken")
        assertEquals("A", store.nextToIndex(setOf("P01"))!!.id)
        store.saveChunks(a, listOf(chunk(a)))
        assertEquals("C", store.nextToIndex(setOf("P01"))!!.id)
    }

    @Test fun retryClearsFailuresButNotScans() {
        source("FAILED"); source("SCAN")
        store.error("FAILED", "broken")
        store.markNeedsOcr("SCAN", 12, "needs OCR")
        assertEquals(1, store.retryFailed("P01"))
        val all = store.sources("P01").associateBy { it.id }
        assertEquals("", all.getValue("FAILED").error)
        assertTrue(all.getValue("SCAN").needsOcr)
        assertEquals("needs OCR", all.getValue("SCAN").error)
        assertEquals(listOf("FAILED"), listOfNotNull(store.nextToIndex(setOf("P01"))?.id))
    }

    @Test fun pagesWithoutTextAreRecorded() {
        val s = source("PART"); source("SCAN")
        store.saveChunks(s, listOf(chunk(s)), pages = 40, noTextPages = 12)
        store.markNeedsOcr("SCAN", 7, "needs OCR")
        val all = store.sources("P01").associateBy { it.id }
        assertEquals(40, all.getValue("PART").pages); assertEquals(12, all.getValue("PART").noTextPages)
        assertFalse(all.getValue("PART").needsOcr)
        assertTrue(all.getValue("SCAN").needsOcr)
        assertEquals(1 to 1, store.scanCounts("P01"))
    }

    @Test fun duplicateIsFoundByHashWithinTheProject() {
        val s = source("ONE", contents = "same bytes")
        val hash = Store.sha256(File(s.path))
        assertEquals("ONE", store.duplicateOf(hash, "P01")?.id)
        assertNull(store.duplicateOf(hash, "P02"))
        // add() with a known hash stores it as given.
        val other = File(store.library, "TWO.pdf").apply { writeText("different") }
        store.add(Source("TWO", other.path, "pdf", "P02", "", "", "Two", false), hash)
        assertEquals("TWO", store.duplicateOf(hash, "P02")?.id)
    }

    @Test fun deletingAProjectEmptiesItsQueue() {
        store.enqueue("P03", listOf(queued("a.pdf")))
        source("X", project = "P03")
        store.deleteProject("P03")
        assertEquals(ImportCounts(), store.importCounts("P03"))
    }

    @Test fun schemaFourUpgradesWithSourcesKept() {
        val name = "upgrade-test.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            Store.MIGRATIONS.take(4).flatten().forEach(db::execSQL)
            db.execSQL("INSERT INTO sources(id,path,type,building,sha256) VALUES('OLD','/x.pdf','pdf','P01','abc')")
            db.version = 4
        }
        Store(context, name).use { upgraded ->
            val old = upgraded.sources("P01").single()
            assertEquals("OLD", old.id); assertEquals(0, old.pages); assertFalse(old.needsOcr)
            assertEquals(1, upgraded.enqueue("P01", listOf(queued("a.pdf"))))
        }
        context.deleteDatabase(name)
    }

    @Test fun aPageNeedsLettersToCountAsText() {
        assertFalse(Engine.hasText("   12   "))
        assertFalse(Engine.hasText("Página 3 de 40"))
        assertTrue(Engine.hasText("SENTENCIA ROL 1288-08 sobre control preventivo de constitucionalidad"))
    }

    @Test fun onlySupportedFilesAreQueued() {
        assertEquals("pdf", FolderImport.typeOf("application/octet-stream", "Fallo 2022.PDF"))
        assertEquals("image", FolderImport.typeOf("image/jpeg", "foto"))
        assertEquals("audio", FolderImport.typeOf("audio/mp4", "nota.m4a"))
        assertNull(FolderImport.typeOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "escrito.docx"))
        assertNotNull(FolderImport.typeOf("application/pdf", "x"))
    }
}
