package com.proasiste.lite.data

import android.content.ContentValues
import android.database.Cursor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Project(val id: Long, val name: String)

enum class DocumentStatus(val stored: String) {
    PENDING("pending"), INDEXED("indexed"), FAILED("failed");

    companion object {
        fun from(stored: String) = entries.first { it.stored == stored }
    }
}

data class Document(
    val id: Long,
    val projectId: Long,
    val title: String,
    val file: File,
    val sha256: String,
    val byteSize: Long,
    val pageCount: Int,
    val status: DocumentStatus,
    val error: String?,
)

/** A deletion the graph hasn't applied yet. With no [documentId], the whole project's graph goes. */
data class GraphCleanup(val id: Long, val projectId: Long, val documentId: Long?)

/** A passage ready to save: its text, the page it came from, and its embedding. */
data class NewPassage(val page: Int, val text: String, val terms: List<String>, val vector: FloatArray)

/** Projects, documents and their passages. Deleting removes both the database rows and the app's copy of each file. */
class Library(private val database: AppDatabase) {

    fun projects(): List<Project> =
        database.readableDatabase.rawQuery("SELECT id, name FROM projects ORDER BY name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Project(cursor.getLong(0), cursor.getString(1))) }
        }

    fun createProject(name: String): Project {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Enter a project name." }
        val values = ContentValues().apply { put("name", trimmed) }
        val id = database.writableDatabase.insert("projects", null, values)
        require(id != -1L) { "A project named \"$trimmed\" already exists." }
        return Project(id, trimmed)
    }

    fun deleteProject(id: Long) {
        val files = documents(id).map { it.file }
        database.writableDatabase.inTransaction {
            execSQL("INSERT INTO graph_cleanups(project_id) VALUES (?)", arrayOf(id))
            delete("projects", "id = ?", arrayOf(id.toString()))
        }
        files.forEach(File::delete)
        database.markIndexChanged()
    }

    fun documents(projectId: Long): List<Document> =
        database.readableDatabase.rawQuery(
            "$SELECT_DOCUMENT WHERE project_id = ? ORDER BY title, id",
            arrayOf(projectId.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toDocument()) } }

    fun document(id: Long): Document? =
        database.readableDatabase.rawQuery("$SELECT_DOCUMENT WHERE id = ?", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursor.toDocument() else null
        }

    fun findBySha256(projectId: Long, sha256: String): Document? =
        database.readableDatabase.rawQuery(
            "$SELECT_DOCUMENT WHERE project_id = ? AND sha256 = ?",
            arrayOf(projectId.toString(), sha256),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toDocument() else null }

    fun addDocument(projectId: Long, title: String, file: File, sha256: String, byteSize: Long, pageCount: Int): Long {
        val values = ContentValues().apply {
            put("project_id", projectId)
            put("title", title)
            put("path", file.path)
            put("sha256", sha256)
            put("byte_size", byteSize)
            put("page_count", pageCount)
        }
        return database.writableDatabase.insertOrThrow("documents", null, values)
    }

    /** Removes a document with its passages and terms, and queues the removal of what it put in the graph. */
    fun deleteDocument(id: Long) {
        val document = document(id) ?: return
        database.writableDatabase.inTransaction {
            execSQL("INSERT INTO graph_cleanups(project_id, document_id) VALUES (?, ?)", arrayOf(document.projectId, id))
            delete("documents", "id = ?", arrayOf(id.toString()))
        }
        document.file.delete()
        database.markIndexChanged()
    }

    /** Replaces a document's passages and marks it indexed, all together, so a failure leaves the old ones in place. */
    fun savePassages(documentId: Long, passages: List<NewPassage>) {
        require(passages.isNotEmpty()) { "A document needs at least one passage." }
        database.writableDatabase.inTransaction {
            delete("passages", "document_id = ?", arrayOf(documentId.toString()))
            val insertTerm = compileStatement("INSERT INTO terms(term, passage_id, frequency) VALUES (?, ?, ?)")
            insertTerm.use { statement ->
                for (passage in passages) {
                    val values = ContentValues().apply {
                        put("document_id", documentId)
                        put("page", passage.page)
                        put("text", passage.text)
                        put("word_count", passage.terms.size)
                        put("vector", encodeVector(passage.vector))
                    }
                    val passageId = insertOrThrow("passages", null, values)
                    passage.terms.groupingBy { it }.eachCount().forEach { (term, frequency) ->
                        statement.bindString(1, term)
                        statement.bindLong(2, passageId)
                        statement.bindLong(3, frequency.toLong())
                        statement.executeInsert()
                    }
                }
            }
            update(
                "documents",
                ContentValues().apply {
                    put("status", DocumentStatus.INDEXED.stored)
                    putNull("error")
                },
                "id = ?",
                arrayOf(documentId.toString()),
            )
        }
        database.markIndexChanged()
    }

    fun pendingGraphCleanups(): List<GraphCleanup> =
        database.readableDatabase.rawQuery("SELECT id, project_id, document_id FROM graph_cleanups ORDER BY id", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(GraphCleanup(cursor.getLong(0), cursor.getLong(1), if (cursor.isNull(2)) null else cursor.getLong(2)))
                }
            }
        }

    fun finishGraphCleanup(id: Long) {
        database.writableDatabase.delete("graph_cleanups", "id = ?", arrayOf(id.toString()))
    }

    fun documentInProject(projectId: Long, documentId: Long): Boolean = document(documentId)?.projectId == projectId

    fun markFailed(documentId: Long, message: String) {
        val values = ContentValues().apply {
            put("status", DocumentStatus.FAILED.stored)
            put("error", message.take(MAX_ERROR_LENGTH))
        }
        database.writableDatabase.update("documents", values, "id = ?", arrayOf(documentId.toString()))
    }

    private fun Cursor.toDocument() = Document(
        id = getLong(0),
        projectId = getLong(1),
        title = getString(2),
        file = File(getString(3)),
        sha256 = getString(4),
        byteSize = getLong(5),
        pageCount = getInt(6),
        status = DocumentStatus.from(getString(7)),
        error = getString(8),
    )

    private companion object {
        const val MAX_ERROR_LENGTH = 300
        const val SELECT_DOCUMENT =
            "SELECT id, project_id, title, path, sha256, byte_size, page_count, status, error FROM documents"
    }
}

fun encodeVector(vector: FloatArray): ByteArray {
    val bytes = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    vector.forEach(bytes::putFloat)
    return bytes.array()
}
