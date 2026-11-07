package com.proasiste.lite.embedding

/** Turns text into unit-length vectors. Implementations aren't safe to call from the main thread. */
interface Embedder {
    /** Identifies the model and settings. If it changes, stored vectors can no longer be compared with new ones. */
    val version: String

    fun embedPassage(text: String): FloatArray
    fun embedQuery(text: String): FloatArray
}
