package com.proasiste.lite.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.proasiste.lite.data.AppDatabase
import com.proasiste.lite.data.Document
import com.proasiste.lite.data.DocumentStatus
import com.proasiste.lite.data.Library
import com.proasiste.lite.data.Project
import com.proasiste.lite.embedding.GemmaEmbedder
import com.proasiste.lite.falkor.FalkorClient
import com.proasiste.lite.falkor.FalkorServer
import com.proasiste.lite.graph.FalkorGraphStore
import com.proasiste.lite.graph.GraphCleaner
import com.proasiste.lite.importing.DocumentImporter
import com.proasiste.lite.importing.ImportRejected
import com.proasiste.lite.importing.ImportResult
import com.proasiste.lite.indexing.Indexer
import com.proasiste.lite.pdf.PdfBoxReader
import com.proasiste.lite.search.SearchEngine
import com.proasiste.lite.search.SearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class UiState(
    val projects: List<Project> = emptyList(),
    val project: Project? = null,
    val documents: List<Document> = emptyList(),
    val modelReady: Boolean = false,
    val busy: Boolean = false,
    val indexing: Boolean = false,
    val status: String = "",
    val error: String? = null,
    val hits: List<SearchHit> = emptyList(),
    val searched: Boolean = false,
    /** Empty while starting. Search doesn't depend on the graph, so a failure here only shows as a note. */
    val graphStatus: String = "",
) {
    val pendingCount: Int get() = documents.count { it.status != DocumentStatus.INDEXED }
    val indexedCount: Int get() = documents.count { it.status == DocumentStatus.INDEXED }
}

/** Connects the screen to the app's parts. All database and model work runs on one background thread, one job at a time. */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val filesDir = application.filesDir
    private val database = AppDatabase(application)
    private val library = Library(database)
    private val reader = PdfBoxReader(application)
    private val embedder = GemmaEmbedder(application)
    private val importer = DocumentImporter(library, reader, File(filesDir, "documents"))
    private val indexer = Indexer(library, reader, embedder)
    private val searchEngine = SearchEngine(database)
    private val falkorServer = FalkorServer(application)
    private val falkor = FalkorClient(falkorServer)
    private val graph = FalkorGraphStore({ arguments -> falkor.call(*arguments) }, library::documentInProject)
    private val graphCleaner = GraphCleaner(library, graph)
    private val modelFile = File(filesDir, "model/${GemmaEmbedder.MODEL_FILENAME}")
    private val stopIndexing = AtomicBoolean()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)

    var state by mutableStateOf(UiState())
        private set

    init {
        run("Getting ready") {
            database.ensureIndexVersion(embedder.version)
            if (modelFile.isFile) embedder.load(modelFile)
            reload(state.project?.id)
            startGraph()
        }
    }

    fun createProject(name: String) = run("Creating project") {
        reload(library.createProject(name).id)
    }

    fun selectProject(project: Project) = run("Opening project") {
        reload(project.id)
    }

    fun deleteProject(project: Project) = run("Deleting project") {
        library.deleteProject(project.id)
        cleanGraph()
        reload(null)
        post { copy(status = "Deleted ${project.name}") }
    }

    fun importModel(uri: Uri) = run("Copying the model. This takes a few minutes.") {
        val app = getApplication<Application>()
        val partial = File(filesDir, "model.partial")
        try {
            checkNotNull(app.contentResolver.openInputStream(uri)) { "Couldn't open that file." }.use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            require(GemmaEmbedder.isSupportedModel(partial)) {
                "That isn't the supported model. Choose ${GemmaEmbedder.MODEL_FILENAME}."
            }
            embedder.close()
            modelFile.parentFile?.mkdirs()
            check(partial.renameTo(modelFile)) { "Couldn't save the model." }
            embedder.load(modelFile)
        } finally {
            partial.delete()
        }
        post { copy(modelReady = true, status = "Model ready") }
    }

    fun importDocuments(uris: List<Uri>) = run("Adding PDFs") {
        val project = checkNotNull(state.project) { "Create a project first." }
        val app = getApplication<Application>()
        var added = 0
        var duplicates = 0
        val problems = mutableListOf<String>()
        uris.forEachIndexed { i, uri ->
            post { copy(status = "Adding PDF ${i + 1} of ${uris.size}") }
            val title = displayName(uri)
            try {
                val result = importer.import(project.id, title) {
                    checkNotNull(app.contentResolver.openInputStream(uri)) { "Couldn't open \"$title\"." }
                }
                if (result is ImportResult.Added) added++ else duplicates++
            } catch (error: ImportRejected) {
                problems += error.message.orEmpty()
            }
        }
        reload(project.id)
        val summary = "$added added" + if (duplicates > 0) " · $duplicates already here" else ""
        post { copy(status = summary, error = problems.takeIf { it.isNotEmpty() }?.joinToString("\n")) }
    }

    fun deleteDocument(document: Document) = run("Deleting document") {
        library.deleteDocument(document.id)
        cleanGraph()
        reload(document.projectId)
        post { copy(status = "Deleted ${document.title}", hits = emptyList(), searched = false) }
    }

    fun index() {
        val project = state.project ?: return
        stopIndexing.set(false)
        post { copy(indexing = true) }
        run("Indexing") {
            try {
                val summary = indexer.indexPending(project.id, stopIndexing::get) { progress ->
                    post {
                        copy(status = "${progress.documentTitle} · page ${progress.page} of ${progress.pageCount} · ${progress.documentsLeft} left")
                    }
                }
                reload(project.id)
                post { copy(status = "${summary.indexed} indexed" + if (summary.failed > 0) " · ${summary.failed} failed" else "") }
            } finally {
                post { copy(indexing = false) }
            }
        }
    }

    fun stopIndexing() {
        stopIndexing.set(true)
        post { copy(status = "Finishing the current page, then stopping") }
    }

    fun search(query: String) {
        val project = state.project ?: return
        if (query.isBlank()) return
        run("Searching") {
            val started = System.nanoTime()
            val hits = searchEngine.search(project.id, query, embedder.embedQuery(query))
            val millis = (System.nanoTime() - started) / 1_000_000
            post { copy(hits = hits, searched = true, status = "${hits.size} results · $millis ms") }
        }
    }

    fun dismissError() = post { copy(error = null) }

    override fun onCleared() {
        stopIndexing.set(true)
        viewModelScope.launch(worker + kotlinx.coroutines.NonCancellable) {
            embedder.close()
            falkor.close()
            falkorServer.stop()
            database.close()
        }
    }

    private fun startGraph() {
        val status = try {
            falkorServer.start()
            if (graphCleaner.flush()) "Graph ready" else "Graph ready · cleanup waiting"
        } catch (error: Exception) {
            graphProblem(error)
        }
        post { copy(graphStatus = status) }
    }

    /** A failure leaves the cleanup queued for the next start, so it never blocks a delete. */
    private fun cleanGraph() {
        try {
            graphCleaner.flush()
        } catch (error: Exception) {
            post { copy(graphStatus = graphProblem(error)) }
        }
    }

    private fun graphProblem(error: Exception) =
        "Graph unavailable: ${error.message ?: error.javaClass.simpleName}. Search still works."

    private fun displayName(uri: Uri): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
        return getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: fallback
    }

    /** Re-reads projects and documents, keeping [projectId] selected when it still exists. */
    private fun reload(projectId: Long?) {
        val projects = library.projects()
        val project = projects.firstOrNull { it.id == projectId } ?: projects.firstOrNull()
        val documents = project?.let { library.documents(it.id) }.orEmpty()
        val changedProject = project?.id != state.project?.id
        val ready = embedder.isLoaded
        post {
            copy(
                projects = projects,
                project = project,
                documents = documents,
                modelReady = ready,
                hits = if (changedProject) emptyList() else hits,
                searched = searched && !changedProject,
            )
        }
    }

    /** Runs one job at a time on the worker, and shows any failure instead of crashing. */
    private fun run(label: String, job: suspend () -> Unit) {
        if (state.busy) return
        state = state.copy(busy = true, status = label, error = null)
        viewModelScope.launch {
            try {
                withContext(worker) { job() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = state.copy(error = error.message ?: "Something went wrong.", status = "")
            } finally {
                state = state.copy(busy = false, status = state.status.takeUnless { it == label }.orEmpty())
            }
        }
    }

    /** Applies a state change on the main thread, wherever it was requested. */
    private fun post(change: UiState.() -> UiState) {
        viewModelScope.launch(Dispatchers.Main.immediate) { state = state.change() }
    }
}
