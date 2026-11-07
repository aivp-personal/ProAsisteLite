package com.proasiste.lite.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.FileProvider
import com.proasiste.lite.data.Document

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }
    private val pickPdfs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importDocuments(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state = viewModel.state
            // Android can stop background work once the screen is off, so stay awake while indexing.
            LaunchedEffect(state.indexing) {
                if (state.indexing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            MainScreen(
                state = state,
                actions = ScreenActions(
                    createProject = viewModel::createProject,
                    selectProject = viewModel::selectProject,
                    deleteProject = viewModel::deleteProject,
                    importModel = { pickModel.launch(arrayOf("*/*")) },
                    addPdfs = { pickPdfs.launch(arrayOf("application/pdf")) },
                    deleteDocument = viewModel::deleteDocument,
                    openDocument = ::open,
                    index = viewModel::index,
                    stopIndexing = viewModel::stopIndexing,
                    search = viewModel::search,
                    dismissError = viewModel::dismissError,
                ),
            )
        }
    }

    private fun open(document: Document) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", document.file)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(view, document.title))
    }
}
