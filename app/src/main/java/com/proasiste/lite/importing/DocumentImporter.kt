package com.proasiste.lite.importing

import com.proasiste.lite.data.Document
import com.proasiste.lite.data.Library
import com.proasiste.lite.pdf.PdfReader
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** Why a file wasn't added, in words to show the user. */
class ImportRejected(message: String) : Exception(message)

sealed interface ImportResult {
    data class Added(val documentId: Long) : ImportResult
    data class AlreadyInProject(val existing: Document) : ImportResult
}

/** Copies a PDF into the app, enforcing the size and page limits before anything is saved. */
class DocumentImporter(
    private val library: Library,
    private val reader: PdfReader,
    private val documentsDir: File,
) {
    fun import(projectId: Long, title: String, open: () -> InputStream): ImportResult {
        documentsDir.mkdirs()
        val partial = File(documentsDir, "${UUID.randomUUID()}.partial")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val size = open().use { input -> partial.outputStream().use { copyWithinLimit(input, it, digest) } }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }

            library.findBySha256(projectId, sha256)?.let { return ImportResult.AlreadyInProject(it) }

            val pages = readPageCount(partial, title)
            if (pages > MAX_PAGES) throw ImportRejected("\"$title\" has $pages pages. The limit is $MAX_PAGES.")

            val file = File(documentsDir, "${UUID.randomUUID()}.pdf")
            check(partial.renameTo(file)) { "Could not save \"$title\"." }
            try {
                return ImportResult.Added(library.addDocument(projectId, title, file, sha256, size, pages))
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        } finally {
            partial.delete()
        }
    }

    private fun copyWithinLimit(input: InputStream, output: OutputStream, digest: MessageDigest): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > MAX_BYTES) throw ImportRejected("This file is over the ${MAX_BYTES / 1_000_000} MB limit.")
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
    }

    private fun readPageCount(file: File, title: String): Int =
        try {
            reader.pageCount(file)
        } catch (error: Exception) {
            throw ImportRejected("\"$title\" couldn't be opened as a PDF. It may be damaged or password protected.")
        }

    companion object {
        const val MAX_BYTES = 100_000_000L
        const val MAX_PAGES = 1000
        private const val BUFFER_BYTES = 64 * 1024
    }
}
