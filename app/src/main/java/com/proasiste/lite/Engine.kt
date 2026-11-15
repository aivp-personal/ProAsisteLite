package com.proasiste.lite

import android.content.Context
import com.proasiste.lite.runtime.GemmaEmbedder
import com.proasiste.lite.runtime.PdfText
import com.proasiste.lite.runtime.TableRows
import com.proasiste.lite.ui.tr
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The parts the screen and the background indexer share: one database and one loaded model per process, so the 500 MB
 * model is never loaded twice. [worker] is the screen's thread for database and model work; the indexer uses its own
 * thread, and the embedder's lock lets a search slip in between two chunks of a long file.
 */
class Engine private constructor(context:Context){
    val store=Store(context)
    val embedder=GemmaEmbedder(context)
    val worker=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val modelFile=File(context.filesDir,"models/${GemmaEmbedder.MODEL_FILENAME}")
    init{PDFBoxResourceLoader.init(context)}

    /** Loads the imported model if it isn't loaded yet. Call off the main thread. Returns whether a model is ready. */
    @Synchronized fun ensureModel():Boolean{
        if(embedder.isLoaded)return true
        if(!modelFile.isFile)return false
        verifyModel(modelFile);embedder.load(modelFile.path);return true
    }

    /** A PDF whose pages have no selectable text at all: a scan that needs OCR before it can be searched. */
    class NeedsOcr(val pages:Int):Exception(tr("Scanned PDF: none of its $pages pages has text. OCR it, then import it again.","PDF escaneado: ninguna de sus $pages páginas tiene texto. Pásalo por OCR e impórtalo de nuevo."))
    /** A file's chunks, and for PDFs how many pages it has and how many had no text. */
    class Encoded(val chunks:List<VectorChunk>,val pages:Int=0,val noTextPages:Int=0)

    /** Reads and embeds one source. Throws [NeedsOcr] for a PDF with no text on any page. */
    fun encode(s:Source):Encoded{
        val file=File(s.path)
        return when(s.type){
            "image"->{val bitmap=MediaInputs.bitmap(file);try{Encoded(listOf(VectorChunk(s.id,0,0,0,"",embedder.embedImage(bitmap))))}finally{bitmap.recycle()}}
            "audio"->{val pcm=MediaInputs.audio(file);val size=12*16000;val step=10*16000;val parts=mutableListOf<VectorChunk>();var from=0
                while(from<pcm.size){val until=minOf(from+size,pcm.size);parts+=VectorChunk(s.id,parts.size,0,from/16,"",embedder.embedAudio(pcm.copyOfRange(from,until).map{it.coerceIn(-1f,1f)}.toFloatArray()));if(until==pcm.size)break;from+=step}
                Encoded(parts)}
            else->{val p=pdfPieces(file)
                Encoded(p.pieces.map{VectorChunk(s.id,it.ordinal,it.page,0,it.text,embedder.embedText(it.text))},p.pages,p.noTextPages)}
        }
    }

    /**
     * A PDF's pages as text for AI: the DocLang blocks without running headers and footers, in reading order, or the old
     * page reader when the DocLang reader finds little text. Empty for photos and recordings.
     */
    fun pagesForAi(s:Source):List<com.proasiste.lite.runtime.DeepSeekFacts.PageText>{
        if(s.type!="pdf")return emptyList()
        return PDDocument.load(File(s.path)).use{pdf->
            val d=runCatching{com.proasiste.lite.runtime.DocLang.read(pdf)}.getOrNull()?.takeIf{com.proasiste.lite.runtime.DocPages.usable(it)}
            d?.let{com.proasiste.lite.runtime.DocPages.pages(it)}
                ?:(1..pdf.numberOfPages).map{p->val read=PdfText.page(pdf,p);com.proasiste.lite.runtime.DeepSeekFacts.PageText(p,(listOf(read.prose)+read.tables.flatten()).joinToString("\n").trim())}
        }
    }

    /** The text of a PDF's first pages, up to [maxChars], for AI tagging. Empty for photos and recordings. */
    fun firstPages(s:Source,maxChars:Int=com.proasiste.lite.runtime.CloudTagger.MAX_CHARS):String{
        if(s.type!="pdf")return ""
        val out=StringBuilder()
        PDDocument.load(File(s.path)).use{doc->
            for(page in 1..doc.numberOfPages){
                if(out.length>=maxChars)break
                val read=PdfText.page(doc,page)
                out.append("=== PAGE $page ===\n").append((listOf(read.prose)+read.tables.flatten()).joinToString("\n").trim()).append("\n\n")
            }
        }
        return out.take(maxChars).toString()
    }

    /** One searchable piece of a PDF before it is embedded. */
    data class Piece(val ordinal:Int,val page:Int,val text:String)
    /** A PDF read and split for the index, and how many of its pages had no text. */
    class PdfPieces(val pieces:List<Piece>,val pages:Int,val noTextPages:Int)

    companion object {
        /**
         * Reads a PDF and splits it into the pieces the index embeds, in order: each page's prose in ~60-word blocks with
         * 15 words of overlap, then its rebuilt table rows. The desktop indexer calls this too, so a pack made there splits
         * text exactly as the phone does. Throws [NeedsOcr] for a PDF with no text on any page.
         */
        fun pdfPieces(file:File):PdfPieces{
            val parts=mutableListOf<Piece>();var pages=0;var noText=0
            PDDocument.load(file).use{doc->require(doc.numberOfPages<=1000){tr("PDF limit is 1000 pages", "El límite de PDF es de 1000 páginas")}
                pages=doc.numberOfPages
                for(page in 1..pages){val read=PdfText.page(doc,page);val text=read.prose.trim()
                    if(!hasText(text+read.tables.flatten().joinToString(" ")))noText++
                    if(text.isNotBlank()){val words=text.split(Regex("\\s+"));var offset=0
                        // ~60-word blocks with 15 words of overlap: small enough that each block is about one idea.
                        while(offset<words.size){val end=minOf(offset+60,words.size);parts+=Piece(parts.size,page,words.subList(offset,end).joinToString(" "));if(end==words.size)break;offset+=45}
                    }
                    // Rebuilt tables keep their line breaks and whole rows: a row cut in half loses its label.
                    for(rows in read.tables)for(chunk in TableRows.blocks(rows))parts+=Piece(parts.size,page,chunk)
                }}
            if(pages>0&&noText==pages)throw NeedsOcr(pages)
            require(parts.isNotEmpty()){tr("No selectable text in this PDF.", "Este PDF no tiene texto seleccionable.")}
            return PdfPieces(parts,pages,noText)
        }
        @Volatile private var instance:Engine?=null
        fun get(context:Context):Engine=instance?:synchronized(this){instance?:Engine(context.applicationContext).also{instance=it}}
        /** A page with fewer letters than this is taken as having no text: a scan, or only a stamp or page number. */
        const val MIN_LETTERS=25
        fun hasText(text:String)=text.count{it.isLetter()}>=MIN_LETTERS
        fun verifyModel(file:File){
            require(file.length()==GemmaEmbedder.MODEL_BYTES){tr("Choose the full EmbeddingGemma 2 CPU/GPU model (485 MB).", "Elige el modelo completo EmbeddingGemma 2 CPU/GPU (485 MB).")}
            val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val b=ByteArray(1024*1024);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
            require(digest.digest().joinToString(""){"%02x".format(it)}==GemmaEmbedder.MODEL_SHA256){tr("Model checksum does not match the supported release.", "La suma de verificación del modelo no coincide con la versión compatible.")}
        }
    }
}
