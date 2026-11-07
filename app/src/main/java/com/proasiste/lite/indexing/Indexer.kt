package com.proasiste.lite.indexing

import com.proasiste.lite.data.Document
import com.proasiste.lite.data.DocumentStatus
import com.proasiste.lite.data.Library
import com.proasiste.lite.data.NewPassage
import com.proasiste.lite.embedding.Embedder
import com.proasiste.lite.pdf.PdfReader
import com.proasiste.lite.text.Chunker
import com.proasiste.lite.text.Tokenizer
import java.util.concurrent.CancellationException

data class IndexProgress(val documentTitle: String, val page: Int, val pageCount: Int, val documentsLeft: Int)
data class IndexSummary(val indexed: Int, val failed: Int)

/** Reads, splits and embeds a project's pending documents. Each document is saved whole, so stopping loses only the current one. */
class Indexer(
    private val library: Library,
    private val reader: PdfReader,
    private val embedder: Embedder,
) {
    fun indexPending(
        projectId: Long,
        isCancelled: () -> Boolean = { false },
        onProgress: (IndexProgress) -> Unit = {},
    ): IndexSummary {
        val pending = library.documents(projectId).filter { it.status != DocumentStatus.INDEXED }
        var indexed = 0
        var failed = 0
        pending.forEachIndexed { position, document ->
            if (isCancelled()) return IndexSummary(indexed, failed)
            try {
                val passages = readPassages(document, isCancelled) { page ->
                    onProgress(IndexProgress(document.title, page, document.pageCount, pending.size - position))
                }
                if (passages.isEmpty()) {
                    library.markFailed(document.id, NO_TEXT)
                    failed++
                } else {
                    library.savePassages(document.id, passages)
                    indexed++
                }
            } catch (stopped: CancellationException) {
                return IndexSummary(indexed, failed)
            } catch (error: Exception) {
                library.markFailed(document.id, error.message ?: "This document couldn't be read.")
                failed++
            }
        }
        return IndexSummary(indexed, failed)
    }

    private fun readPassages(
        document: Document,
        isCancelled: () -> Boolean,
        onPage: (Int) -> Unit,
    ): List<NewPassage> {
        val passages = mutableListOf<NewPassage>()
        reader.forEachPage(document.file) { page ->
            if (isCancelled()) throw CancellationException()
            onPage(page.page)
            for (chunk in Chunker.chunk(page)) {
                passages += NewPassage(chunk.page, chunk.text, Tokenizer.terms(chunk.text), embedder.embedPassage(chunk.text))
            }
        }
        return passages
    }

    companion object {
        const val NO_TEXT = "No selectable text. Scanned PDFs aren't supported yet."
    }
}
