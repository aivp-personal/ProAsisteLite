package com.proasiste.lite

import com.proasiste.lite.embedding.Embedder
import com.proasiste.lite.pdf.PdfReader
import com.proasiste.lite.text.PageText
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

/** Pages by file name, so tests don't need real PDFs. */
class FakePdfReader(private val pagesFor: (File) -> List<String>, private val pageCounts: (File) -> Int = { pagesFor(it).size }) : PdfReader {
    override fun pageCount(file: File) = pageCounts(file)

    override fun forEachPage(file: File, onPage: (PageText) -> Unit) {
        pagesFor(file).forEachIndexed { i, text -> onPage(PageText(i + 1, text)) }
    }
}

/** A repeatable vector from the text, so identical text has identical vectors. */
class FakeEmbedder : Embedder {
    override val version = "fake-1"

    override fun embedPassage(text: String) = vectorFor(text)
    override fun embedQuery(text: String) = vectorFor(text)
}

fun vectorFor(text: String, dimensions: Int = 8): FloatArray = randomUnitVector(Random(text.hashCode()), dimensions)

fun randomUnitVector(random: Random, dimensions: Int): FloatArray {
    val values = FloatArray(dimensions) { random.nextFloat() - 0.5f }
    val norm = sqrt(values.sumOf { it.toDouble() * it }).toFloat()
    return FloatArray(dimensions) { values[it] / norm }
}
