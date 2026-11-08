package com.proasiste.lite.runtime

import com.proasiste.lite.ui.tr
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest

/**
 * Gemini Nano through Android AICore (ML Kit Prompt API). Runs on the phone; nothing leaves the device.
 * It only sees the passages it is given, so retrieval quality decides answer quality.
 */
class NanoAnswerer {
    data class Passage(val title: String, val page: Int, val text: String)

    private var client: GenerativeModel? = null
    private fun model() = client ?: Generation.getClient().also { client = it }

    /** Streams the growing answer text to [onText]. Downloads the model first if AICore offers it. */
    suspend fun answer(question: String, passages: List<Passage>, onStatus: (String) -> Unit, onText: (String) -> Unit) {
        val model = ready(onStatus)
        onStatus("Gemini Nano is reading ${passages.size} passages…")
        val request = generateContentRequest(TextPart(prompt(question, passages))) {
            temperature = 0.2f
            topK = 16
            maxOutputTokens = 256
        }
        val text = StringBuilder()
        model.generateContentStream(request).collect { chunk ->
            text.append(chunk.candidates.firstOrNull()?.text.orEmpty())
            onText(text.toString())
        }
        if (text.isBlank()) error(tr("Gemini Nano returned no text.", "Gemini Nano no devolvió texto."))
    }

    /** One deterministic, non-streaming completion. */
    suspend fun generate(prompt: String, maxTokens: Int = 512, onStatus: (String) -> Unit = {}): String {
        val request = generateContentRequest(TextPart(prompt)) { temperature = 0f; topK = 1; maxOutputTokens = maxTokens }
        return ready(onStatus).generateContent(request).candidates.firstOrNull()?.text.orEmpty()
    }

    /** Returns the client once Gemini Nano is usable, downloading it first if AICore offers it. */
    private suspend fun ready(onStatus: (String) -> Unit): GenerativeModel {
        val model = model()
        when (model.checkStatus()) {
            FeatureStatus.UNAVAILABLE -> error(tr("Gemini Nano is not available on this phone.", "Gemini Nano no está disponible en este teléfono."))
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                onStatus(tr("Downloading Gemini Nano to this phone…", "Descargando Gemini Nano en este teléfono…"))
                model.download().collect { s ->
                    when (s) {
                        is DownloadStatus.DownloadProgress -> onStatus(tr("Downloading Gemini Nano · ${s.totalBytesDownloaded / 1_000_000} MB", "Descargando Gemini Nano · ${s.totalBytesDownloaded / 1_000_000} MB"))
                        is DownloadStatus.DownloadFailed -> throw s.e
                        else -> {}
                    }
                }
            }
        }
        return model
    }

    fun close() { client?.close(); client = null }

    companion object {
        /** Exact reply the prompt asks for when the passages don't contain the answer. */
        const val NOT_FOUND = "NOT_FOUND"

        fun prompt(question: String, passages: List<Passage>) = buildString {
            appendLine("You answer questions about the user's documents using ONLY the numbered passages below.")
            appendLine("Rules:")
            appendLine("- Answer in the same language as the question, in 1 to 3 short sentences.")
            appendLine("- After each fact, cite the passage number in square brackets, like [2].")
            appendLine("- Do not use outside knowledge. If the passages do not contain the answer, reply exactly: $NOT_FOUND")
            appendLine()
            appendLine("Passages:")
            // On separate lines, table rows get counted and cited as if they were passages.
            passages.forEachIndexed { i, p -> appendLine("[${i + 1}] ${p.title}, page ${p.page}: ${p.text.replace("\n", " | ")}") }
            appendLine()
            appendLine("Question: ${question.trim()}")
            append("Answer:")
        }

        private val CITATION = Regex("\\[\\d+(?:\\s*,\\s*\\d+)*]")

        /** With a single passage every citation can only mean that passage, so a stray number can't escape the citation check. */
        fun fixCitations(text: String, passages: Int) = if (passages == 1) CITATION.replace(text, "[1]") else text
    }
}
