package com.proasiste.lite.embedding

import android.content.Context
import com.google.ai.edge.litertlm.ModelInfo
import com.google.mediapipe.tasks.components.containers.EmbeddingResult
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import java.io.File
import java.security.MessageDigest
import kotlin.math.sqrt

/** EmbeddingGemma 2 running on the phone's CPU. The model file is imported by the user and checked before use. */
class GemmaEmbedder(context: Context) : Embedder, AutoCloseable {
    private val appContext = context.applicationContext
    private var engine: UniversalEmbedder? = null

    override val version = "eg2-740m-${MODEL_SHA256.take(8)}-cpu-t$MAX_TOKENS-chunk60"

    val isLoaded: Boolean
        @Synchronized get() = engine != null

    @Synchronized
    fun load(modelFile: File) {
        close()
        var maxTokens = MAX_TOKENS
        ModelInfo.from(modelFile.path).use { info ->
            val supported = info.maxContextTokens()
            if (supported > 0) maxTokens = minOf(maxTokens, supported)
        }
        val cache = File(appContext.cacheDir, "embedder").apply { mkdirs() }
        val options = UniversalEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelFile.path).setDelegate(Delegate.CPU).build())
            .setTextDelegate(Delegate.CPU)
            .setL2Normalize(true)
            .setMaxInputLength(maxTokens)
            .setCacheDir(cache.path)
            .build()
        engine = UniversalEmbedder.createFromOptions(appContext, options)
    }

    @Synchronized
    override fun embedPassage(text: String) = embed("title: none | text: ${text.trim()}")

    @Synchronized
    override fun embedQuery(text: String) = embed("task: question answering | query: ${text.trim()}")

    @Synchronized
    override fun close() {
        engine?.close()
        engine = null
    }

    private fun embed(input: String): FloatArray {
        val loaded = checkNotNull(engine) { "Import the embedding model first." }
        return unitVector(loaded.embedText(input))
    }

    private fun unitVector(result: EmbeddingResult): FloatArray {
        val values = checkNotNull(result.embeddings().firstOrNull()?.floatEmbedding()) { "The model returned nothing." }
        check(values.size == DIMENSIONS && values.all(Float::isFinite)) { "The model returned an invalid vector." }
        val norm = sqrt(values.sumOf { it.toDouble() * it.toDouble() })
        check(norm > 0.0) { "The model returned an empty vector." }
        return FloatArray(values.size) { (values[it] / norm).toFloat() }
    }

    companion object {
        const val DIMENSIONS = 768
        const val MODEL_FILENAME = "embeddinggemma-2-740m.litertlm"
        const val MODEL_BYTES = 484_622_336L
        const val MODEL_SHA256 = "e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c"
        private const val MAX_TOKENS = 512

        /** True only for the exact release this app was built and tested against. */
        fun isSupportedModel(file: File): Boolean {
            if (file.length() != MODEL_BYTES) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } == MODEL_SHA256
        }
    }
}
