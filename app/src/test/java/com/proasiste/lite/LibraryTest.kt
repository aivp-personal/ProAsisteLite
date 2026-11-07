package com.proasiste.lite

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.proasiste.lite.data.AppDatabase
import com.proasiste.lite.data.DocumentStatus
import com.proasiste.lite.data.Library
import com.proasiste.lite.importing.DocumentImporter
import com.proasiste.lite.importing.ImportRejected
import com.proasiste.lite.importing.ImportResult
import com.proasiste.lite.indexing.Indexer
import com.proasiste.lite.search.SearchEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryTest {
    @get:Rule val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = AppDatabase(context, null)
    private val library = Library(database)
    private var pages: List<String> = listOf("Some text about a contract")
    private var reportedPages: Int? = null
    private val reader = FakePdfReader({ pages }, { reportedPages ?: pages.size })
    private val embedder = FakeEmbedder()
    private val importer by lazy { DocumentImporter(library, reader, folder.newFolder("documents")) }
    private val indexer = Indexer(library, reader, embedder)

    @After fun close() = database.close()

    private fun add(projectId: Long, content: String = "pdf", title: String = "Doc"): Long =
        (importer.import(projectId, title) { ByteArrayInputStream(content.toByteArray()) } as ImportResult.Added).documentId

    private fun count(table: String): Int =
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use {
            it.moveToFirst()
            it.getInt(0)
        }

    @Test fun databaseVersionMatchesTheMigrationList() {
        assertEquals(AppDatabase.MIGRATIONS.size, database.readableDatabase.version)
    }

    @Test fun projectNamesAreTrimmedAndUnique() {
        library.createProject(" Legal ")
        assertEquals("Legal", library.projects().single().name)
        assertThrows(IllegalArgumentException::class.java) { library.createProject("Legal") }
        assertThrows(IllegalArgumentException::class.java) { library.createProject("  ") }
    }

    @Test fun fileOverTheSizeLimitIsRefusedAndLeavesNothing() {
        val project = library.createProject("P")
        val tooBig = object : InputStream() {
            private var left = DocumentImporter.MAX_BYTES + 1
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                left -= n
                return n
            }
        }
        assertThrows(ImportRejected::class.java) { importer.import(project.id, "Big") { tooBig } }
        assertTrue(library.documents(project.id).isEmpty())
        assertEquals(0, folder.root.resolve("documents").listFiles()!!.size)
    }

    @Test fun exactlyTheSizeLimitIsAccepted() {
        val project = library.createProject("P")
        val atLimit = object : InputStream() {
            private var left = DocumentImporter.MAX_BYTES
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                left -= n
                return n
            }
        }
        assertTrue(importer.import(project.id, "Edge") { atLimit } is ImportResult.Added)
    }

    @Test fun pageLimitAllows1000AndRefuses1001() {
        val project = library.createProject("P")
        reportedPages = DocumentImporter.MAX_PAGES
        add(project.id, "a")
        reportedPages = DocumentImporter.MAX_PAGES + 1
        assertThrows(ImportRejected::class.java) { add(project.id, "b") }
        assertEquals(1, library.documents(project.id).size)
        assertEquals(1, folder.root.resolve("documents").listFiles()!!.size)
    }

    @Test fun sameFileIsOnlyAddedOncePerProject() {
        val one = library.createProject("One")
        val two = library.createProject("Two")
        add(one.id)
        assertTrue(importer.import(one.id, "Again") { ByteArrayInputStream("pdf".toByteArray()) } is ImportResult.AlreadyInProject)
        add(two.id)
        assertEquals(1, library.documents(one.id).size)
        assertEquals(1, library.documents(two.id).size)
    }

    @Test fun indexingSavesPassagesWithTheirPages() {
        val project = library.createProject("P")
        pages = listOf("first page words", "", "third page words")
        add(project.id)
        val summary = indexer.indexPending(project.id)
        assertEquals(1, summary.indexed)
        assertEquals(DocumentStatus.INDEXED, library.documents(project.id).single().status)
        val hits = SearchEngine(database).search(project.id, "third", embedder.embedQuery("third"))
        assertEquals(3, hits.first().page)
    }

    @Test fun documentWithoutTextFailsWithAReason() {
        val project = library.createProject("P")
        pages = listOf("  ", "")
        reportedPages = 2
        add(project.id)
        assertEquals(1, indexer.indexPending(project.id).failed)
        val document = library.documents(project.id).single()
        assertEquals(DocumentStatus.FAILED, document.status)
        assertEquals(Indexer.NO_TEXT, document.error)
    }

    @Test fun stoppingBeforeADocumentLeavesItPending() {
        val project = library.createProject("P")
        add(project.id)
        assertEquals(0, indexer.indexPending(project.id, isCancelled = { true }).indexed)
        assertEquals(DocumentStatus.PENDING, library.documents(project.id).single().status)
        assertEquals(0, count("passages"))
    }

    @Test fun deletingADocumentRemovesItsDataAndQueuesItsGraphCleanup() {
        val project = library.createProject("P")
        val kept = add(project.id, "kept", "Kept")
        val gone = add(project.id, "gone", "Gone")
        indexer.indexPending(project.id)
        val goneFile = library.document(gone)!!.file

        library.deleteDocument(gone)

        assertFalse(goneFile.exists())
        assertEquals(listOf(kept), library.documents(project.id).map { it.id })
        assertEquals(1, count("passages"))
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM terms WHERE passage_id NOT IN (SELECT id FROM passages)",
            null,
        ).use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        val cleanup = library.pendingGraphCleanups().single()
        assertEquals(project.id, cleanup.projectId)
        assertEquals(gone, cleanup.documentId)
    }

    @Test fun deletingAProjectLeavesNothingBehindButItsGraphCleanup() {
        val project = library.createProject("P")
        val document = add(project.id)
        indexer.indexPending(project.id)
        val file = library.document(document)!!.file

        library.deleteProject(project.id)

        assertFalse(file.exists())
        for (table in listOf("projects", "documents", "passages", "terms")) {
            assertEquals(table, 0, count(table))
        }
        val cleanup = library.pendingGraphCleanups().single()
        assertEquals(project.id, cleanup.projectId)
        assertEquals(null, cleanup.documentId)
    }

    @Test fun deletedDocumentsLeaveSearchAtOnce() {
        val project = library.createProject("P")
        val document = add(project.id)
        indexer.indexPending(project.id)
        val search = SearchEngine(database)
        assertEquals(1, search.search(project.id, "contract", embedder.embedQuery("contract")).size)
        library.deleteDocument(document)
        assertTrue(search.search(project.id, "contract", embedder.embedQuery("contract")).isEmpty())
    }

    @Test fun newIndexVersionQueuesEverythingAgain() {
        val project = library.createProject("P")
        add(project.id)
        indexer.indexPending(project.id)
        database.ensureIndexVersion("a")
        assertEquals(0, count("passages"))
        assertEquals(0, count("terms"))
        assertEquals(DocumentStatus.PENDING, library.documents(project.id).single().status)
        indexer.indexPending(project.id)
        database.ensureIndexVersion("a")
        assertEquals(1, count("passages"))
    }
}
