package com.proasiste.lite

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.proasiste.lite.ui.tr
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Bulk import from a folder the user picked. [walk] lists every supported file under it (all subfolders) without
 * copying anything; [copyIn] then copies one queued file into the library, hashing it on the way so a file already in
 * the project is caught without reading it twice.
 */
object FolderImport {
    /** The most files one folder pick may queue. */
    const val MAX_FILES=20_000

    /** Supported files under [tree], with their folder relative to it ("" for the top). [onFound] reports progress. */
    fun walk(context:Context,tree:Uri,onFound:(Int)->Unit={}):List<QueuedFile>{
        val resolver=context.contentResolver;val out=ArrayList<QueuedFile>()
        val columns=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        // Breadth first, with an explicit stack: deep folder trees never overflow the call stack.
        val pending=ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree) to ""))
        while(pending.isNotEmpty()){
            val (parent,folder)=pending.removeFirst()
            resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent),columns,null,null,null)?.use{c->
                while(c.moveToNext()){
                    val id=c.getString(0);val name=c.getString(1)?:continue;val mime=c.getString(2)?:""
                    if(mime==DocumentsContract.Document.MIME_TYPE_DIR){if(!name.startsWith("."))pending.addLast(id to (if(folder.isEmpty())name else "$folder/$name"));continue}
                    if(typeOf(mime,name)==null)continue
                    require(out.size<MAX_FILES){tr("This folder has more than $MAX_FILES files. Pick a smaller folder.","Esta carpeta tiene más de $MAX_FILES archivos. Elige una carpeta más pequeña.")}
                    out+=QueuedFile(0,DocumentsContract.buildDocumentUriUsingTree(tree,id).toString(),"",folder,name,mime,c.getLong(3))
                    if(out.size%100==0)onFound(out.size)
                }
            }
        }
        return out
    }

    /** "pdf", "image", "audio", or null for a file the app can't index. */
    fun typeOf(mime:String,name:String):String?=when{
        mime=="application/pdf"||name.endsWith(".pdf",true)->"pdf"
        mime.startsWith("image/")->"image"
        mime.startsWith("audio/")->"audio"
        else->null
    }

    /**
     * Copies [q] into the library as a new source of its project. Returns [Store.IMPORTED] with the new source's id, or
     * [Store.DUPLICATE] with the title already holding the same contents. The subfolder becomes the source's asset, so search can be filtered by it,
     * and the file's own date (last modified) becomes the source's date.
     */
    fun copyIn(context:Context,store:Store,q:QueuedFile):Pair<String,String>{
        val type=typeOf(q.mime,q.name)?:error(tr("Unsupported file: ${q.name}","Archivo no compatible: ${q.name}"))
        val id="USER-${UUID.randomUUID()}";val f=File(store.library,id+when(type){"image"->".img";"audio"->".audio";else->".pdf"})
        try{
            val digest=MessageDigest.getInstance("SHA-256")
            (context.contentResolver.openInputStream(Uri.parse(q.uri))?:error(tr("Can't open ${q.name}","No se puede abrir ${q.name}"))).use{input->
                DigestOutputStream(f.outputStream(),digest).use{out->PlantroomModel.copyBounded(input,out,100_000_000)}}
            val hash=digest.digest().joinToString(""){"%02x".format(it)}
            store.duplicateOf(hash,q.building)?.let{f.delete();return Store.DUPLICATE to it.title}
            val date=if(q.modified>0)Instant.ofEpochMilli(q.modified).atZone(ZoneId.systemDefault()).toLocalDate() else LocalDate.now()
            store.add(Source(id,f.path,type,q.building,q.folder.take(80),date.toString(),q.name,false),hash)
            return Store.IMPORTED to id
        }catch(e:Exception){f.delete();throw e}
    }
}
