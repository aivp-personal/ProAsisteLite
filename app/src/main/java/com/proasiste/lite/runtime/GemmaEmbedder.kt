package com.proasiste.lite.runtime

import com.proasiste.lite.ui.tr
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import com.google.ai.edge.litertlm.ModelInfo
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.components.containers.EmbeddingResult
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import java.io.File
import kotlin.math.sqrt

/**
 * Real, synchronous on-device inference. Call from a background dispatcher.
 * All native calls are serialized. CPU is deliberate for S21 Exynos/Mali compatibility.
 * No HTTP client, synthetic vectors, text captions for images, or transcript fallback.
 * API checked against MediaPipe tasks-retrieval 1.1.0 / LiteRT-LM 0.18.0.
 */
class GemmaEmbedder(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    @Volatile private var engine: UniversalEmbedder? = null
    @Volatile
    var supportsAudio: Boolean = false
        private set
    @Volatile
    var supportsVision: Boolean = false
        private set
    val isLoaded: Boolean get() = engine != null
    val backend: String get() = "CPU"

    @Synchronized
    fun load(modelPath: String) {
        requireWorkerThread()
        require(File(modelPath).isFile) { tr("Choose an EmbeddingGemma 2 .litertlm model file first.", "Primero elige un archivo de modelo EmbeddingGemma 2 .litertlm.") }
        close()
        var maxTokens = 512
        var visionTokens = 70
        ModelInfo.from(modelPath).use { info ->
            val supportedMax = info.maxContextTokens()
            if (supportedMax > 0) maxTokens = minOf(maxTokens, supportedMax)
            val supportedVision = info.maxVisionTokenBudget()
            supportsVision = supportedVision > 0
            if (supportsVision) visionTokens = minOf(visionTokens, supportedVision)
            supportsAudio = info.inputModalities().audio
        }
        val cache = File(appContext.cacheDir, "gemma2_cpu_v${visionTokens}_t${maxTokens}")
        check(cache.isDirectory || cache.mkdirs()) { "Cannot create model cache." }
        val options = UniversalEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelPath).setDelegate(Delegate.CPU).build())
            .setTextDelegate(Delegate.CPU)
            .setVisionDelegate(Delegate.CPU)
            .setAudioDelegate(Delegate.CPU)
            .setL2Normalize(true)
            .setMaxInputLength(maxTokens)
            .setCacheDir(cache.absolutePath)
        if (supportsVision) options.setVisionTokensPerImage(visionTokens)
        try {
            engine = UniversalEmbedder.createFromOptions(appContext, options.build())
        } catch (error: Throwable) {
            supportsAudio = false
            supportsVision = false
            throw error
        }
    }

    @Synchronized
    fun embedText(text: String, isQuery: Boolean = false): FloatArray {
        requireWorkerThread()
        require(text.isNotBlank()) { tr("Enter some text to search.", "Escribe algo para buscar.") }
        // Official model-card asymmetric retrieval instructions; images/audio get no prefix.
        val prefix = if (isQuery) "task: question answering | query: " else "title: none | text: "
        // A piece over the model's 512 tokens (a long table row, a run of symbols) is embedded from its first 85% of
        // words, again until it fits, instead of failing its whole file. desktop/embed_pack.py uses the same rule.
        var t = text.trim()
        repeat(12) {
            try { return vector(requireEngine().embedText(prefix + t)) }
            catch (e: Exception) {
                if (e.message?.contains("exceeds maximum supported") != true) throw e
                val words = t.split(Regex("\\s+"))
                t = words.take(maxOf(1, (words.size * 0.85).toInt())).joinToString(" ")
            }
        }
        error("A piece is still too long for the model after cutting it down.")
    }

    @Synchronized
    fun embedImage(bitmap: Bitmap): FloatArray {
        requireWorkerThread()
        check(supportsVision) { "The loaded model does not contain the image encoder." }
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "Invalid image." }
        // Bound memory before MPImage's internal pixel copy; retain caller's original bitmap.
        val scale = minOf(1.0, 1280.0 / maxOf(bitmap.width, bitmap.height))
        // MPImage.close() recycles its backing bitmap. Give it an owned bounded copy.
        val scaled = Bitmap.createBitmap(
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(scaled).drawBitmap(bitmap, null,
            Rect(0, 0, scaled.width, scaled.height), Paint(Paint.FILTER_BITMAP_FLAG))
        val image = BitmapImageBuilder(scaled).build()
        try {
            return vector(requireEngine().embedImage(image))
        } finally {
            image.close()
        }
    }

    /** Float PCM in [-1,1], mono, exactly 16kHz. Decode/resample files before calling. */
    @Synchronized
    fun embedAudio(floatArrayPCM16k: FloatArray): FloatArray {
        requireWorkerThread()
        check(supportsAudio) { "The loaded model does not contain the audio encoder." }
        require(floatArrayPCM16k.isNotEmpty() && floatArrayPCM16k.size <= MAX_AUDIO_SECONDS * SAMPLE_RATE) {
            "Embed voice notes in non-empty segments of at most $MAX_AUDIO_SECONDS seconds."
        }
        require(floatArrayPCM16k.all { it.isFinite() && it >= -1f && it <= 1f }) {
            "Audio must be normalized mono PCM at 16kHz."
        }
        val format = AudioData.AudioDataFormat.builder().setNumOfChannels(1)
            .setSampleRate(SAMPLE_RATE.toFloat()).build()
        // Exact sample count avoids ring-buffer rotation and leading silence.
        val audio = AudioData.create(format, floatArrayPCM16k.size)
        audio.load(floatArrayPCM16k)
        return vector(requireEngine().embedAudio(audio))
    }

    @Synchronized
    override fun close() {
        try { engine?.close() } finally {
            engine = null
            supportsAudio = false
            supportsVision = false
        }
    }

    private fun requireEngine() = checkNotNull(engine) { tr("Load the embedding model before searching.", "Carga el modelo de embeddings antes de buscar.") }

    private fun requireWorkerThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Embedding inference must run off the UI thread." }
    }

    private fun vector(result: EmbeddingResult): FloatArray {
        val values = result.embeddings().firstOrNull()?.floatEmbedding()
            ?: error("The model returned no embedding.")
        require(values.size == DIMENSIONS && values.all(Float::isFinite)) { "Invalid model embedding." }
        val norm = sqrt(values.sumOf { it.toDouble() * it.toDouble() })
        require(norm > 0.0) { "The model returned a zero vector." }
        return FloatArray(values.size) { (values[it] / norm).toFloat() }
    }

    companion object {
        const val DIMENSIONS = 768
        const val SAMPLE_RATE = 16_000
        const val MAX_AUDIO_SECONDS = 12
        const val MODEL_FILENAME = "embeddinggemma-2-740m.litertlm"
        const val MODEL_SHA256 = "e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c"
        const val MODEL_BYTES = 484_622_336L
        // Bumping this clears every stored vector and marks all sources for re-indexing.
        const val INDEX_VERSION = "eg2-740m-e7a8a220-cpu512-v70-qaprompt-chunk60-v3-layout-tables-v2"
    }
}
