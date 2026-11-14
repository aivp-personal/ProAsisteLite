package com.proasiste.lite

import com.proasiste.lite.ui.tr
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.proasiste.lite.ui.*
import com.proasiste.lite.runtime.GemmaEmbedder
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/** [pages] and [noTextPages] are set for indexed PDFs: pages with no selectable text were probably scanned and need OCR. */
data class Source(val id:String,val path:String,val type:String,val building:String,val asset:String,val date:String,val title:String,val synthetic:Boolean,val indexed:Boolean=false,val error:String="",val pages:Int=0,val noTextPages:Int=0) {
    /** Every page lacks text: the whole file is a scan and can't be searched until it is OCR'd. */
    val needsOcr:Boolean get()=pages>0&&noTextPages==pages
    fun ui(score:Float?=null, excerpt:String="", location:String="")=UiItem(id,title,when(type){"image"->UiKind.PHOTO;"audio"->UiKind.AUDIO;else->UiKind.REPORT},building,asset,date,path,excerpt,location,synthetic,score)
}
/** A file waiting in the import queue: found in a folder the user picked, not yet copied into the library. */
data class QueuedFile(val id:Long,val uri:String,val building:String,val folder:String,val name:String,val mime:String,val modified:Long)
/** Import queue totals for a project: [pending] to copy, [imported], [duplicates] skipped, [failed]. */
data class ImportCounts(val pending:Int=0,val imported:Int=0,val duplicates:Int=0,val failed:Int=0)
/** A searchable piece of a source. Search results leave [vector] empty: only stored chunks carry one. */
data class VectorChunk(val sourceId:String,val ordinal:Int,val page:Int,val startMs:Int,val text:String,val vector:FloatArray)
data class SearchHit(val source:Source,val chunk:VectorChunk,val score:Float)
/** [hits] has one row per source for the list; [passages] are the best chunks overall, best first, for answering. */
data class SearchResult(val hits:List<SearchHit>,val passages:List<SearchHit>)

/**
 * The one SQLite database: sources, their searchable chunks, a word index and the project graph.
 * Each entry in [MIGRATIONS] moves the schema up one version, and the list's length is the current version,
 * so a schema change is always a new entry. Never edit an entry that has shipped.
 * Foreign keys are on: deleting a source removes its chunks, words and graph links with it.
 */
class Store(private val context:Context,name:String?="proasiste.db"):SQLiteOpenHelper(context,name,null,MIGRATIONS.size) {
    val library=File(context.filesDir,"library").apply{mkdirs()}
    private val generation=AtomicLong()
    private var cached:ProjectIndex?=null

    override fun onConfigure(db:SQLiteDatabase){db.setForeignKeyConstraintsEnabled(true)}
    override fun onCreate(db:SQLiteDatabase)=migrate(db,0,MIGRATIONS.size)
    override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int)=migrate(db,old,new)
    private fun migrate(db:SQLiteDatabase,from:Int,to:Int){for(version in from until to)MIGRATIONS[version].forEach(db::execSQL)}

    /** Searchable chunks changed, so a cached project index is out of date. */
    private fun indexChanged(){generation.incrementAndGet()}

    fun ensureIndexVersion(){
        val db=writableDatabase
        val old=db.rawQuery("SELECT value FROM index_meta WHERE name='version'",null).use{if(it.moveToFirst())it.getString(0)else null}
        if(old!=GemmaEmbedder.INDEX_VERSION){db.inTransaction{delete("chunks",null,null);execSQL("UPDATE sources SET indexed=0,error=''");insertWithOnConflict("index_meta",null,ContentValues().apply{put("name","version");put("value",GemmaEmbedder.INDEX_VERSION)},SQLiteDatabase.CONFLICT_REPLACE)};indexChanged()}
    }
    fun sources(building:String?=null):List<Source>{
        val list=mutableListOf<Source>()
        readableDatabase.query("sources",SOURCE_COLUMNS,if(building!=null)"building=?" else null,building?.let{arrayOf(it)},null,null,"date DESC,id ASC").use{c->
            while(c.moveToNext())list+=sourceAt(c)
        };return list
    }
    private fun sourceAt(c:android.database.Cursor)=Source(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4)?:"",c.getString(5)?:"",c.getString(6)?:"",c.getInt(7)==1,c.getInt(8)==1,c.getString(9)?:"",c.getInt(10),c.getInt(11))
    /** The source in [building] whose file has the same contents as [file], if any. */
    fun duplicateOf(file:File,building:String):Source?=duplicateOf(sha256(file),building)
    /** The source in [building] whose file has this SHA-256, if any. */
    fun duplicateOf(hash:String,building:String):Source?=
        readableDatabase.query("sources",SOURCE_COLUMNS,"building=? AND sha256=?",arrayOf(building,hash),null,null,null,"1").use{if(it.moveToFirst())sourceAt(it)else null}
    /** Adds a source. Pass [hash] when it is already known, so a large file isn't read twice. */
    fun add(s:Source,hash:String?=null){
        val v=ContentValues().apply{put("id",s.id);put("path",s.path);put("type",s.type);put("building",s.building);put("asset",s.asset);put("date",s.date);put("title",s.title);put("synthetic",if(s.synthetic)1 else 0);put("sha256",hash?:sha256(File(s.path)))}
        writableDatabase.insertWithOnConflict("sources",null,v,SQLiteDatabase.CONFLICT_IGNORE)
    }

    // ---- Import queue: files found in a picked folder, copied in one at a time so a long import can stop and resume ----
    /** Queues files for [building]; a file already queued for it (same document URI) is not queued again. Returns how many were new. */
    fun enqueue(building:String,files:List<QueuedFile>):Int=writableDatabase.inTransaction{
        files.count{f->insertWithOnConflict("import_queue",null,ContentValues().apply{put("uri",f.uri);put("building",building);put("folder",f.folder);put("name",f.name);put("mime",f.mime);put("modified",f.modified)},SQLiteDatabase.CONFLICT_IGNORE)!=-1L}
    }
    /** The oldest file still waiting to be copied into one of [buildings], or null. */
    fun nextImport(buildings:Set<String>):QueuedFile?{
        if(buildings.isEmpty())return null
        return readableDatabase.rawQuery("SELECT id,uri,building,folder,name,mime,modified FROM import_queue WHERE status='$QUEUED' AND building IN (${buildings.joinToString(","){"?"}}) ORDER BY id LIMIT 1",buildings.toTypedArray()).use{c->
            if(c.moveToFirst())QueuedFile(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getLong(6)) else null}
    }
    /** Records what happened to a queued file: [IMPORTED], [DUPLICATE] or [FAILED] with its reason. */
    fun finishImport(id:Long,status:String,note:String=""){writableDatabase.update("import_queue",ContentValues().apply{put("status",status);put("note",note.take(250))},"id=?",arrayOf(id.toString()))}
    fun importCounts(building:String):ImportCounts{
        val counts=HashMap<String,Int>()
        readableDatabase.rawQuery("SELECT status,COUNT(*) FROM import_queue WHERE building=? GROUP BY status",arrayOf(building)).use{c->while(c.moveToNext())counts[c.getString(0)]=c.getInt(1)}
        return ImportCounts(counts[QUEUED]?:0,counts[IMPORTED]?:0,counts[DUPLICATE]?:0,counts[FAILED]?:0)
    }
    /** Files waiting to be copied, plus imported files waiting to be indexed, across [buildings]. */
    fun pendingWork(buildings:Set<String>):Int=pendingParts(buildings).let{it.first+it.second}
    /** Across [buildings]: files waiting to be copied, and imported files waiting to be indexed. */
    fun pendingParts(buildings:Set<String>):Pair<Int,Int>{
        if(buildings.isEmpty())return 0 to 0
        val args=buildings.toTypedArray();val list=buildings.joinToString(","){"?"}
        val queued=readableDatabase.rawQuery("SELECT COUNT(*) FROM import_queue WHERE status='$QUEUED' AND building IN ($list)",args).use{it.moveToFirst();it.getInt(0)}
        val unindexed=readableDatabase.rawQuery("SELECT COUNT(*) FROM sources WHERE indexed=0 AND error='' AND building IN ($list)",args).use{it.moveToFirst();it.getInt(0)}
        return queued to unindexed
    }
    /** The next source to index in [buildings]: not indexed and not failed, in the order it was added. */
    fun nextToIndex(buildings:Set<String>):Source?{
        if(buildings.isEmpty())return null
        return readableDatabase.query("sources",SOURCE_COLUMNS,"indexed=0 AND error='' AND building IN (${buildings.joinToString(","){"?"}})",buildings.toTypedArray(),null,null,"rowid","1").use{if(it.moveToFirst())sourceAt(it)else null}
    }
    /** Clears the errors of [building]'s unindexed sources so they are tried again; scans waiting for OCR stay as they are. */
    fun retryFailed(building:String):Int=writableDatabase.update("sources",ContentValues().apply{put("error","")},"building=? AND indexed=0 AND error!='' AND NOT (pages>0 AND no_text_pages=pages)",arrayOf(building))
    /** Marks a PDF with no selectable text on any page: it stays unindexed with [message] until it is OCR'd and imported again. */
    fun markNeedsOcr(id:String,pages:Int,message:String){writableDatabase.update("sources",ContentValues().apply{put("pages",pages);put("no_text_pages",pages);put("error",message.take(250))},"id=?",arrayOf(id))}
    /** How many of [building]'s PDFs are scans waiting for OCR, and how many indexed ones have some pages without text. */
    fun scanCounts(building:String):Pair<Int,Int> =readableDatabase.rawQuery("SELECT SUM(pages>0 AND no_text_pages=pages),SUM(indexed=1 AND no_text_pages>0) FROM sources WHERE building=?",arrayOf(building)).use{it.moveToFirst();it.getInt(0) to it.getInt(1)}

    /** The project's graph schema; automatic when none was saved. */
    fun graphSchema(building:String):com.proasiste.lite.graph.GraphSchema=
        readableDatabase.rawQuery("SELECT json FROM graph_schemas WHERE building=?",arrayOf(building)).use{if(it.moveToFirst())com.proasiste.lite.graph.GraphSchema.fromJson(it.getString(0)) else com.proasiste.lite.graph.GraphSchema()}
    fun saveGraphSchema(building:String,schema:com.proasiste.lite.graph.GraphSchema){
        writableDatabase.insertWithOnConflict("graph_schemas",null,ContentValues().apply{put("building",building);put("json",schema.toJson())},SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Saves a source's chunks and marks it indexed. For PDFs, [pages] and [noTextPages] record how much of it had text. */
    fun saveChunks(source:Source,chunks:List<VectorChunk>,pages:Int=0,noTextPages:Int=0){
        require(chunks.isNotEmpty()){ tr("No searchable content found", "No se encontró contenido para buscar") }
        writableDatabase.inTransaction{
            delete("chunks","source=?",arrayOf(source.id))
            chunks.forEach{ch->
                require(ch.vector.size==DIMENSIONS && ch.vector.all{it.isFinite()}){"Invalid embedding"}
                val bytes=ByteBuffer.allocate(ch.vector.size*4).order(ByteOrder.LITTLE_ENDIAN);ch.vector.forEach{bytes.putFloat(it)}
                val words=terms(ch.text)
                val id=insertOrThrow("chunks",null,ContentValues().apply{put("source",source.id);put("ordinal",ch.ordinal);put("page",ch.page);put("start_ms",ch.startMs);put("text",ch.text);put("word_count",words.size);put("vector",bytes.array())})
                words.groupingBy{it}.eachCount().forEach{(term,count)->insertOrThrow("terms",null,ContentValues().apply{put("term",term);put("chunk_id",id);put("frequency",count)})}
            }
            update("sources",ContentValues().apply{put("indexed",1);put("error","");put("pages",pages);put("no_text_pages",noTextPages)},"id=?",arrayOf(source.id))
        }
        indexChanged()
    }
    fun error(id:String,message:String){writableDatabase.update("sources",ContentValues().apply{put("error",message.take(250))},"id=?",arrayOf(id))}

    /**
     * Hybrid retrieval: cosine rank on the embedding plus BM25 rank on the chunk words, fused with
     * reciprocal-rank fusion. Keywords catch exact terms ("buzzer"); the embedding catches paraphrase.
     * Without [queryText] (photo search) only the embedding ranks.
     *
     * Chunk text is never loaded to rank. Only vectors and a few numbers stay in memory, in flat arrays, and
     * word counts come from the terms table. Text is read only for the chunks that are returned.
     */
    @Synchronized
    fun search(query:FloatArray,building:String,limit:Int=40,queryText:String?=null,passages:Int=PASSAGES):SearchResult{
        val index=indexFor(building);val n=index.size
        if(n==0||query.size!=DIMENSIONS)return SearchResult(emptyList(),emptyList())
        val similarity=index.similarities(query)
        val fused=DoubleArray(n)
        (0 until n).filter{similarity[it].isFinite()}.sortedByDescending{similarity[it]}.forEachIndexed{rank,i->fused[i]+=1.0/(RRF_K+rank)}
        val words=queryText?.let{terms(it).toSet()}.orEmpty()
        if(words.isNotEmpty()){val bm25=wordScores(index,words);(0 until n).filter{bm25[it]>0}.sortedByDescending{bm25[it]}.forEachIndexed{rank,i->fused[i]+=1.0/(RRF_K+rank)}}
        val ranked=(0 until n).filter{fused[it]>0}.sortedByDescending{fused[it]}
        val perSource=ranked.distinctBy{index.sourceOf[it]}.take(limit)
        val best=ranked.filter{index.hasText[it]}.take(passages)
        val loaded=loadChunks(index,(perSource+best).distinct(),building)
        fun hit(i:Int)=loaded[i]?.let{(source,chunk)->SearchHit(source,chunk,similarity[i])}
        return SearchResult(perSource.mapNotNull(::hit),best.mapNotNull(::hit))
    }
    private fun wordScores(index:ProjectIndex,words:Set<String>):DoubleArray{
        val scores=DoubleArray(index.size)
        for(term in words){
            val positions=ArrayList<Int>();val counts=ArrayList<Int>()
            readableDatabase.rawQuery("SELECT chunk_id,frequency FROM terms WHERE term=?",arrayOf(term)).use{c->while(c.moveToNext()){val p=index.positionOf(c.getLong(0));if(p>=0){positions+=p;counts+=c.getInt(1)}}}
            val n=positions.size
            positions.forEachIndexed{k,p->val f=counts[k];scores[p]+=kotlin.math.ln(1+(index.size-n+.5)/(n+.5))*f*2.2/(f+1.2*(.25+.75*index.wordCounts[p]/index.averageWords))}
        }
        return scores
    }
    private fun loadChunks(index:ProjectIndex,positions:List<Int>,building:String):Map<Int,Pair<Source,VectorChunk>>{
        val sources=sources(building).associateBy{it.id};val out=HashMap<Int,Pair<Source,VectorChunk>>()
        for(batch in positions.chunked(500)){
            val byId=batch.associateBy{index.chunkIds[it]}
            readableDatabase.rawQuery("SELECT id,source,ordinal,page,start_ms,text FROM chunks WHERE id IN (${batch.joinToString(","){"?"}})",batch.map{index.chunkIds[it].toString()}.toTypedArray()).use{c->
                while(c.moveToNext()){val source=sources[c.getString(1)]?:continue;out[byId.getValue(c.getLong(0))]=source to VectorChunk(source.id,c.getInt(2),c.getInt(3),c.getInt(4),c.getString(5)?:"",FloatArray(0))}
            }
        }
        return out
    }
    private fun indexFor(building:String):ProjectIndex{
        val now=generation.get()
        cached?.takeIf{it.building==building&&it.generation==now}?.let{return it}
        cached=null // Free the old arrays before loading new ones: two at once might not fit.
        return ProjectIndex.load(readableDatabase,building,now).also{cached=it}
    }

    /** Remove a project's sources, their search index, graph and the app's private copies of their files. Returns the source count. */
    fun deleteProject(project:String):Int{
        val doomed=sources(project)
        writableDatabase.inTransaction{delete("import_queue","building=?",arrayOf(project));delete("graph_schemas","building=?",arrayOf(project));delete("decisions","building=?",arrayOf(project));delete("reports","building=?",arrayOf(project));delete("action_runs","building=?",arrayOf(project));delete("tag_rules","building=?",arrayOf(project));delete("tags","building=?",arrayOf(project));delete("graph_review","building=?",arrayOf(project));delete("graph_aliases","building=?",arrayOf(project));delete("graph_nodes","building=?",arrayOf(project));delete("sources","building=?",arrayOf(project))}
        indexChanged()
        doomed.forEach{deleteCopy(it.path)}
        return doomed.size
    }
    /** Remove one source, its index and graph links, and the app's private copy of its file. Returns false if it didn't exist. */
    fun deleteSource(id:String):Boolean{
        val (path,building)=readableDatabase.query("sources",arrayOf("path","building"),"id=?",arrayOf(id),null,null,null).use{if(it.moveToFirst())it.getString(0) to it.getString(1) else null}?:return false
        writableDatabase.inTransaction{delete("decisions","target='archivo' AND target_id=?",arrayOf(id));delete("sources","id=?",arrayOf(id));execSQL(DELETE_UNLINKED_NODES,arrayOf(building))}
        indexChanged()
        deleteCopy(path)
        return true
    }
    private fun deleteCopy(path:String){
        val root=library.canonicalPath+File.separator;val f=File(path).canonicalFile
        if(f.path.startsWith(root)){f.delete();var dir=f.parentFile
            while(dir!=null&&dir.canonicalPath+File.separator!=root&&dir.list()?.isEmpty()==true){dir.delete();dir=dir.parentFile}}
    }
    /** Reassign sources from one project key to another (used to migrate the original building IDs). */
    fun moveSources(from:String,to:String){writableDatabase.update("sources",ContentValues().apply{put("building",to)},"building=?",arrayOf(from));indexChanged()}
    /** Pack sources keep a known project number; original building IDs map to P01/P02; anything else lands in [fallback]. */
    fun importPack(input:InputStream,known:Set<String>,fallback:String):Int{
        val stage=File(context.cacheDir,"pack-${UUID.randomUUID()}").apply{mkdirs()}
        try{
            var count=0;var bytes=0L
            ZipInputStream(input).use{zip->
                while(true){val entry=zip.nextEntry?:break;require(++count<=1000){"Too many pack entries"}
                    val name=entry.name.replace('\\','/');require(!name.startsWith('/')&&!name.contains(':')){"Invalid pack path"}
                    val target=File(stage,name).canonicalFile;require(target.path.startsWith(stage.canonicalPath+File.separator)){"Invalid pack path"}
                    if(entry.isDirectory){target.mkdirs();continue}
                    target.parentFile?.mkdirs();var fileBytes=0L
                    target.outputStream().use{out->val buffer=ByteArray(65536);while(true){val n=zip.read(buffer);if(n<0)break;fileBytes+=n;bytes+=n;require(fileBytes<=60_000_000&&bytes<=600_000_000){tr("Pack exceeds import limits", "El paquete supera los límites de importación")};out.write(buffer,0,n)}}
                }
            }
            val manifest=File(stage,"manifest.json");require(manifest.isFile){tr("Pack must contain manifest.json at its root", "El paquete debe tener manifest.json en la raíz")}
            val items=JSONObject(manifest.readText()).getJSONArray("items");require(items.length()<=500){"Too many sources"}
            val parsed=(0 until items.length()).map{i->
                val j=items.getJSONObject(i);val relative=j.getString("path");val f=File(stage,relative).canonicalFile
                require(f.path.startsWith(stage.canonicalPath+File.separator)&&f.isFile){"Missing or invalid source"}
                require(j.getString("type") in listOf("image","audio","pdf")){"Unsupported source type"}
                j to f
            }
            val existing=sources().map{it.id}.toSet();val fresh=parsed.filter{it.first.getString("id") !in existing}
            require(fresh.map{it.first.getString("id")}.distinct().size==fresh.size){"Duplicate source IDs in pack"}
            if(fresh.isEmpty())return 0
            val dest=File(library,"pack-${UUID.randomUUID()}").apply{mkdirs()};val db=writableDatabase;db.beginTransaction();var succeeded=false
            try{
                fresh.forEach{(j,f)->val target=File(dest,j.getString("path"));target.parentFile?.mkdirs();f.copyTo(target)
                    val raw=j.optString("project_id").ifBlank{j.optString("building_id")}.uppercase();val project=(LEGACY_BUILDINGS[raw]?:raw).takeIf{it in known}?:fallback
                    add(Source(j.getString("id"),target.absolutePath,j.getString("type"),project,j.optString("asset_id"),j.optString("date"),j.optString("title",f.name),j.optBoolean("synthetic",false)))
                };db.setTransactionSuccessful();succeeded=true
            }finally{db.endTransaction();if(!succeeded)dest.deleteRecursively()}
            return fresh.size
        }finally{stage.deleteRecursively()}
    }
    companion object {
        private const val RRF_K=60.0
        const val DIMENSIONS=768
        const val PASSAGES=6
        /** How many chunks Jev judges when reranking; the best [PASSAGES] of them go to the answer. */
        const val RERANK_CANDIDATES=20
        /** Graph nodes exist only to be linked, so a deleted source also takes the nodes it leaves with no links. */
        private const val DELETE_UNLINKED_NODES="DELETE FROM graph_nodes WHERE building=? AND NOT EXISTS (SELECT 1 FROM graph_edges e WHERE e.from_id=graph_nodes.id OR e.to_id=graph_nodes.id)"
        val MIGRATIONS:List<List<String>> = listOf(
            listOf(
                "CREATE TABLE sources(id TEXT PRIMARY KEY,path TEXT NOT NULL,type TEXT NOT NULL,building TEXT NOT NULL,asset TEXT,date TEXT,title TEXT,synthetic INTEGER,indexed INTEGER DEFAULT 0,error TEXT DEFAULT '',sha256 TEXT NOT NULL)",
                "CREATE INDEX sources_building ON sources(building,sha256)",
                "CREATE TABLE chunks(id INTEGER PRIMARY KEY,source TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,ordinal INTEGER NOT NULL,page INTEGER,start_ms INTEGER,text TEXT,word_count INTEGER NOT NULL,vector BLOB NOT NULL,UNIQUE(source,ordinal))",
                "CREATE TABLE terms(term TEXT NOT NULL,chunk_id INTEGER NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,frequency INTEGER NOT NULL,PRIMARY KEY(term,chunk_id)) WITHOUT ROWID",
                "CREATE INDEX terms_chunk ON terms(chunk_id)",
                "CREATE TABLE index_meta(name TEXT PRIMARY KEY,value TEXT)",
                // The graph. Types are free text, so any schema fits; every link names the source and page it came from.
                "CREATE TABLE graph_nodes(id INTEGER PRIMARY KEY,building TEXT NOT NULL,type TEXT NOT NULL,key TEXT NOT NULL,name TEXT NOT NULL,UNIQUE(building,type,key))",
                "CREATE TABLE graph_edges(id INTEGER PRIMARY KEY,from_id INTEGER NOT NULL REFERENCES graph_nodes(id) ON DELETE CASCADE,to_id INTEGER NOT NULL REFERENCES graph_nodes(id) ON DELETE CASCADE,type TEXT NOT NULL,source TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,page INTEGER NOT NULL,UNIQUE(from_id,to_id,type,source,page))",
                "CREATE INDEX graph_edges_from ON graph_edges(from_id,type)",
                "CREATE INDEX graph_edges_to ON graph_edges(to_id,type)",
                "CREATE INDEX graph_edges_source ON graph_edges(source)",
            ),
            // 2: a link can carry the fact it states, in plain words, with that sentence's embedding for search.
            listOf(
                "CREATE TABLE graph_edges_v2(id INTEGER PRIMARY KEY,from_id INTEGER NOT NULL REFERENCES graph_nodes(id) ON DELETE CASCADE,to_id INTEGER NOT NULL REFERENCES graph_nodes(id) ON DELETE CASCADE,type TEXT NOT NULL,source TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,page INTEGER NOT NULL,fact TEXT NOT NULL DEFAULT '',vector BLOB,UNIQUE(from_id,to_id,type,source,page,fact))",
                "INSERT INTO graph_edges_v2(id,from_id,to_id,type,source,page) SELECT id,from_id,to_id,type,source,page FROM graph_edges",
                "DROP TABLE graph_edges",
                "ALTER TABLE graph_edges_v2 RENAME TO graph_edges",
                "CREATE INDEX graph_edges_from ON graph_edges(from_id,type)",
                "CREATE INDEX graph_edges_to ON graph_edges(to_id,type)",
                "CREATE INDEX graph_edges_source ON graph_edges(source)",
            ),
            // 3: provenance on each fact (which extractor wrote it), and the review queue for facts the checks held back.
            listOf(
                "ALTER TABLE graph_edges ADD COLUMN extractor TEXT NOT NULL DEFAULT ''",
                "CREATE TABLE graph_review(id INTEGER PRIMARY KEY,building TEXT NOT NULL,source TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,page INTEGER NOT NULL,from_type TEXT NOT NULL,from_name TEXT NOT NULL,relation TEXT NOT NULL,to_type TEXT NOT NULL,to_name TEXT NOT NULL,statement TEXT NOT NULL,reason TEXT NOT NULL,extractor TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'pendiente',decided_at INTEGER,note TEXT)",
                "CREATE INDEX graph_review_pending ON graph_review(building,status)",
            ),
            // 4: the fact as it was proposed (page|subject|relation|object, normalised), so a decision on it can be replayed
            // when the document is rebuilt and the extractor proposes the same fact again, even after a correction.
            listOf(
                "ALTER TABLE graph_review ADD COLUMN original_key TEXT",
                "CREATE INDEX graph_review_source ON graph_review(source,status)",
            ),
            // 5: bulk import. Files found in a picked folder wait in a queue and are copied in one at a time, so an import
            // of thousands can stop and resume. Each PDF records its page count and how many pages had no text (scans).
            listOf(
                "CREATE TABLE import_queue(id INTEGER PRIMARY KEY,uri TEXT NOT NULL,building TEXT NOT NULL,folder TEXT NOT NULL,name TEXT NOT NULL,mime TEXT NOT NULL,modified INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL DEFAULT 'pendiente',note TEXT NOT NULL DEFAULT '',UNIQUE(building,uri))",
                "CREATE INDEX import_queue_status ON import_queue(status,building)",
                "ALTER TABLE sources ADD COLUMN pages INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE sources ADD COLUMN no_text_pages INTEGER NOT NULL DEFAULT 0",
            ),
            // 6: tags. A file's tags each record who set them ("mano", "regla", "ia"); "quitada" marks a tag the user
            // removed by hand, so a rule or an AI run never puts it back. Tags with a description are the list AI picks from.
            listOf(
                "CREATE TABLE tags(id INTEGER PRIMARY KEY,building TEXT NOT NULL,name TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',UNIQUE(building,name))",
                "CREATE TABLE source_tags(source TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,origin TEXT NOT NULL,PRIMARY KEY(source,tag_id)) WITHOUT ROWID",
                "CREATE INDEX source_tags_tag ON source_tags(tag_id,origin)",
                "CREATE TABLE tag_rules(id INTEGER PRIMARY KEY,building TEXT NOT NULL,kind TEXT NOT NULL,pattern TEXT NOT NULL DEFAULT '',tag TEXT NOT NULL DEFAULT '')",
                "CREATE TABLE tag_queue(source TEXT PRIMARY KEY REFERENCES sources(id) ON DELETE CASCADE,building TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'pendiente',note TEXT NOT NULL DEFAULT '')",
                "CREATE INDEX tag_queue_status ON tag_queue(status,building)",
            ),
            // 7: where each graph fact sits on its page, from the DocLang block it was found in ("page:x0,y0,x1,y1;…" on
            // the 500 grid, both halves of a paragraph split by a page break), so a fact opens with its paragraph highlighted.
            listOf(
                "ALTER TABLE graph_edges ADD COLUMN place TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE graph_review ADD COLUMN place TEXT NOT NULL DEFAULT ''",
            ),
            // 8: each project's graph schema (its node and link types), as JSON; a project without a row is automatic.
            listOf(
                "CREATE TABLE graph_schemas(building TEXT PRIMARY KEY,json TEXT NOT NULL)",
            ),
            // 9: saved reports (an answer with its question and cited passages, or a note) and the user's dated decisions
            // on files and reports. Decisions are only ever added, so their history stays.
            listOf(
                "CREATE TABLE reports(id INTEGER PRIMARY KEY,building TEXT NOT NULL,title TEXT NOT NULL,question TEXT NOT NULL DEFAULT '',body TEXT NOT NULL DEFAULT '',model TEXT NOT NULL DEFAULT '',cited TEXT NOT NULL DEFAULT '[]',created INTEGER NOT NULL,updated INTEGER NOT NULL)",
                "CREATE INDEX reports_building ON reports(building,updated)",
                "CREATE TABLE decisions(id INTEGER PRIMARY KEY,building TEXT NOT NULL,target TEXT NOT NULL,target_id TEXT NOT NULL,value TEXT NOT NULL,note TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL)",
                "CREATE INDEX decisions_target ON decisions(building,target,target_id)",
            ),
            // 10: actions on files. Skills are the user's own actions, for every project. A run applies one action to a
            // project or tag; its files wait in a queue the background service works through, and each result is added
            // to the run's report. [folder] is where a DocLang run writes its files.
            listOf(
                "CREATE TABLE skills(id INTEGER PRIMARY KEY,name TEXT NOT NULL,instructions TEXT NOT NULL,created INTEGER NOT NULL)",
                "CREATE TABLE action_runs(id INTEGER PRIMARY KEY,building TEXT NOT NULL,action TEXT NOT NULL,title TEXT NOT NULL,instructions TEXT NOT NULL DEFAULT '',report_id INTEGER NOT NULL,folder TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL)",
                "CREATE TABLE action_queue(id INTEGER PRIMARY KEY,run_id INTEGER NOT NULL REFERENCES action_runs(id) ON DELETE CASCADE,source TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,status TEXT NOT NULL DEFAULT 'pendiente',note TEXT NOT NULL DEFAULT '')",
                "CREATE INDEX action_queue_status ON action_queue(status,run_id)",
            ),
            // 11: names the user merged in the graph. Within one file, a fact naming [key] is linked to the node
            // [type]/[name] instead, so a merge survives rebuilding the graph. Per file, because a word like
            // "Appliance" means a different thing in each manual.
            listOf(
                "CREATE TABLE graph_aliases(building TEXT NOT NULL,source TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,key TEXT NOT NULL,type TEXT NOT NULL,name TEXT NOT NULL,PRIMARY KEY(building,source,key))",
            ),
        )
        private val SOURCE_COLUMNS=arrayOf("id","path","type","building","asset","date","title","synthetic","indexed","error","pages","no_text_pages")
        /** Import queue statuses. */
        const val QUEUED="pendiente"; const val IMPORTED="importado"; const val DUPLICATE="duplicado"; const val FAILED="fallido"
        private val STOP=setOf("the","and","for","are","how","what","when","where","which","who","why","does","do","did","can","you","your","with","this","that","from","into","about","have","has","was","were","will","should","there","they","them","a","an","of","to","in","on","is","it","be","or","as","at","by","if","i","my","me",
            "el","la","los","las","un","una","unos","unas","de","del","que","y","en","por","para","con","como","cual","cuando","donde","es","son","se","su","sus","al","lo","le","les","mi","yo","no","si","o")
        /** Lower-cased, accent-folded words with plural "s" trimmed, minus stop words; shared by queries and chunks. */
        fun terms(text:String):List<String> = Regex("[\\p{L}\\p{N}]+").findAll(java.text.Normalizer.normalize(text.lowercase(),java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"),""))
            .map{it.value}.filter{it !in STOP&&(it.length>1||it[0].isDigit())}.map{if(it.length>4&&it.endsWith("s"))it.dropLast(1) else it}.toList()
        /** The original app's building IDs and the sample projects they became. */
        val LEGACY_BUILDINGS=mapOf("MC01" to "P01","CH02" to "P02")
        fun cosine(a:FloatArray,b:FloatArray):Float{if(a.size!=b.size)return Float.NaN;var dot=0.0;var aa=0.0;var bb=0.0;for(i in a.indices){dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i]};return if(aa>0&&bb>0)(dot/kotlin.math.sqrt(aa*bb)).toFloat() else Float.NaN}
        fun sha256(file:File):String{val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val b=ByteArray(1024*1024);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}};return digest.digest().joinToString(""){"%02x".format(it)}}
    }
}

/**
 * One project's chunks in flat arrays: ids sorted for lookup, the source each belongs to, word counts and
 * unit-length vectors, so a dot product is the cosine. Nothing else is held between searches.
 */
private class ProjectIndex(val building:String,val generation:Long,val chunkIds:LongArray,val sourceOf:IntArray,val wordCounts:IntArray,val hasText:BooleanArray,val vectors:FloatArray){
    val size get()=chunkIds.size
    val averageWords=if(size==0)1.0 else maxOf(1.0,wordCounts.average())
    fun positionOf(chunkId:Long)=chunkIds.binarySearch(chunkId)
    fun similarities(query:FloatArray):FloatArray{
        var norm=0.0;for(x in query)norm+=x*x
        if(norm<=0||!norm.isFinite())return FloatArray(size){Float.NaN}
        val scale=(1/kotlin.math.sqrt(norm)).toFloat();val d=Store.DIMENSIONS
        return FloatArray(size){p->val start=p*d;var dot=0f;for(j in 0 until d)dot+=query[j]*vectors[start+j];dot*scale}
    }
    companion object {
        private const val BATCH=500
        fun load(db:SQLiteDatabase,building:String,generation:Long):ProjectIndex{
            val count=db.rawQuery("SELECT COUNT(*) FROM chunks c JOIN sources s ON s.id=c.source WHERE s.building=?",arrayOf(building)).use{it.moveToFirst();it.getInt(0)}
            val d=Store.DIMENSIONS;val ids=LongArray(count);val sources=IntArray(count);val words=IntArray(count);val text=BooleanArray(count);val vectors=FloatArray(count*d)
            val sourceNumbers=HashMap<String,Int>();var filled=0;var lastId=0L
            while(true){
                // Fetched in batches by id: each query starts where the last ended instead of skipping from the top.
                var read=0
                db.rawQuery("SELECT c.id,c.source,c.word_count,length(trim(c.text))>0,c.vector FROM chunks c JOIN sources s ON s.id=c.source WHERE s.building=? AND c.id>? ORDER BY c.id LIMIT $BATCH",arrayOf(building,lastId.toString())).use{c->
                    while(c.moveToNext()){
                        read++;lastId=c.getLong(0)
                        check(filled<count){"The search index changed while loading."}
                        val buffer=ByteBuffer.wrap(c.getBlob(4)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        if(buffer.remaining()!=d)continue
                        buffer.get(vectors,filled*d,d)
                        var norm=0.0;for(j in 0 until d){val x=vectors[filled*d+j];norm+=x*x}
                        if(norm<=0||!norm.isFinite())continue // Unusable vector: leave it out, as the old search skipped it.
                        val scale=(1/kotlin.math.sqrt(norm)).toFloat();for(j in 0 until d)vectors[filled*d+j]*=scale
                        ids[filled]=lastId;sources[filled]=sourceNumbers.getOrPut(c.getString(1)){sourceNumbers.size};words[filled]=c.getInt(2);text[filled]=c.getInt(3)==1;filled++
                    }
                }
                if(read<BATCH)break
            }
            return if(filled==count)ProjectIndex(building,generation,ids,sources,words,text,vectors)
            else ProjectIndex(building,generation,ids.copyOf(filled),sources.copyOf(filled),words.copyOf(filled),text.copyOf(filled),vectors.copyOf(filled*d))
        }
    }
}

/** Runs [block] in a transaction: either all of it is saved, or none of it. */
inline fun <T> SQLiteDatabase.inTransaction(block:SQLiteDatabase.()->T):T{
    beginTransaction()
    try{return block().also{setTransactionSuccessful()}}finally{endTransaction()}
}
