package com.proasiste.lite.pdf

import android.content.Context
import com.proasiste.lite.text.PageText
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

interface PdfReader {
    fun pageCount(file: File): Int

    /** Calls [onPage] once per page, so a long document never has to fit in memory at once. */
    fun forEachPage(file: File, onPage: (PageText) -> Unit)
}

class PdfBoxReader(context: Context) : PdfReader {
    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    override fun pageCount(file: File): Int = open(file).use { it.numberOfPages }

    override fun forEachPage(file: File, onPage: (PageText) -> Unit) {
        open(file).use { document ->
            val stripper = PDFTextStripper()
            for (page in 1..document.numberOfPages) {
                stripper.startPage = page
                stripper.endPage = page
                onPage(PageText(page, stripper.getText(document)))
            }
        }
    }

    /** Large PDFs buffer to a temporary file rather than the app's limited memory. */
    private fun open(file: File) = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
}
