package com.proasiste.lite

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.*
import com.proasiste.lite.runtime.GemmaEmbedder
import com.proasiste.lite.runtime.JevClient
import com.proasiste.lite.runtime.KeyVault
import com.proasiste.lite.runtime.NanoAnswerer
import com.proasiste.lite.runtime.TableRows
import com.proasiste.lite.runtime.PdfText
import com.proasiste.lite.runtime.Pseudonymizer
import com.proasiste.lite.ui.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PlantroomModel(app:Application):AndroidViewModel(app){
    var state by mutableStateOf(UiState());private set
    private val appContext=app.applicationContext
    val store=Store(app)
    val embedder=GemmaEmbedder(app)
    private val worker=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val cancelIndex=AtomicBoolean(false)
    private val modelFile=File(app.filesDir,"models/${GemmaEmbedder.MODEL_FILENAME}")
    private val prefs=app.getSharedPreferences("plantroom",0)
    private var unfilteredSearchItems: List<UiItem> = emptyList()
    private val nano=NanoAnswerer()
    private val pseudonymizer=Pseudonymizer(nano)
    private val jev=JevClient()
    private val jevVault=KeyVault(prefs)
    private var answerJob:Job?=null
    private var shareJob:Job?=null
    init{
        PDFBoxResourceLoader.init(app)
        // First launch follows the phone's language; after that, the user's choice.
        L10n.spanish=prefs.getBoolean("spanish",java.util.Locale.getDefault().language=="es")
        state=state.copy(projects=loadProjects(),jevKeySaved=jevVault.load()!=null).let{s->s.copy(buildingId=prefs.getString("project",null)?.takeIf{id->s.projects.any{it.id==id}}?:s.projects.first().id)}
        task(tr("Preparing your collection", "Preparando tu colección")){
            withContext(worker){
                store.ensureIndexVersion()
                if(!prefs.getBoolean("projects-v1",false)){Store.LEGACY_BUILDINGS.forEach{(from,to)->store.moveSources(from,to)};prefs.edit().putBoolean("projects-v1",true).apply()}
                if(!prefs.getBoolean("sample-v1",false)){
                    if(app.assets.list("")?.contains("sample-pack.zip")==true){app.assets.open("sample-pack.zip").use{store.importPack(it,projectIds(),"P01")};prefs.edit().putBoolean("sample-v1",true).apply()}
                }
                if(modelFile.isFile){verifyModel(modelFile);embedder.load(modelFile.path)}
            }
            state=state.copy(modelReady=embedder.isLoaded,status=if(embedder.isLoaded)tr("Model ready · search runs on this phone", "Modelo listo · la búsqueda funciona en este teléfono") else tr("Import the model to enable search", "Importa el modelo para activar la búsqueda"))
            refresh()
        }
    }
    private fun projectIds()=state.projects.map{it.id}.toSet()
    private fun loadProjects():List<UiProject>{
        val raw=prefs.getString("projects",null)?:return SAMPLE_PROJECTS
        return runCatching{val a=JSONArray(raw);(0 until a.length()).map{a.getJSONObject(it).let{j->UiProject(j.getString("id"),j.getString("name"),j.optBoolean("incognito",false))}}}.getOrNull()?.takeIf{it.isNotEmpty()}?:SAMPLE_PROJECTS
    }
    /** Returns an error message, or null when the project was created and selected. */
    fun createProject(number:String,name:String):String?{
        if(state.busy||state.recording)return tr("Wait for the current task to finish.", "Espera a que termine la tarea actual.")
        val id=number.trim().uppercase();val title=name.trim()
        if(!Regex("P\\d{2,3}").matches(id))return tr("Project number must look like P01.", "El número de proyecto debe ser como P01.")
        if(id in projectIds())return tr("$id already exists.", "$id ya existe.")
        if(title.isEmpty())return tr("Enter a project name.", "Ingresa un nombre de proyecto.")
        if(title.length>60)return tr("Keep the project name under 60 characters.", "El nombre del proyecto debe tener menos de 60 caracteres.")
        val projects=(state.projects+UiProject(id,title)).sortedBy{it.id.drop(1).toInt()}
        saveProjects(projects);state=state.copy(projects=projects);setBuilding(id);return null
    }
    private fun saveProjects(projects:List<UiProject>){prefs.edit().putString("projects",JSONArray(projects.map{JSONObject().put("id",it.id).put("name",it.name).put("incognito",it.incognito)}).toString()).apply()}
    fun setSpanish(on:Boolean){L10n.spanish=on;prefs.edit().putBoolean("spanish",on).apply();state=state.copy(status="")}
    fun toggleIncognito(id:String){
        val projects=state.projects.map{if(it.id==id)it.copy(incognito=!it.incognito)else it};saveProjects(projects)
        val on=projects.first{it.id==id}.incognito
        state=state.copy(projects=projects,status=if(on)tr("$id incognito · shared text will have personal data masked", "$id incógnito · el texto compartido tendrá los datos personales enmascarados") else tr("$id incognito off · shared text is not masked", "$id incógnito desactivado · el texto compartido no se enmascara"))
    }
    /** Deletes one source with its index and the app's copy of the file; originals elsewhere on the phone stay. */
    fun deleteSource(id:String)=task(tr("Deleting file", "Eliminando archivo")){
        val title=withContext(worker){val s=store.sources().firstOrNull{it.id==id};if(store.deleteSource(id))s?.title else null}?:error(tr("That file was already removed.", "Ese archivo ya fue eliminado."))
        unfilteredSearchItems=unfilteredSearchItems.filter{it.id!=id}
        if(state.answer?.sources?.any{it.id==id}==true)stopAnswer()
        state=state.copy(status=tr("Deleted $title", "Eliminado: $title"))
    }
    /** Builds the text to share for the current answer; masks it first when the project is incognito. */
    fun shareAnswer(){
        val answer=state.answer?:return;if(answer.working||answer.text.isBlank())return
        val incognito=state.projects.firstOrNull{it.id==state.buildingId}?.incognito==true
        val cited=JevClient.cites(answer.text).filter{it in 1..answer.sources.size}.sorted().ifEmpty{(1..answer.sources.size).toList()}
        val plain=buildString{
            appendLine(tr("Q: ${state.query.trim()}", "P: ${state.query.trim()}"));appendLine();appendLine(answer.text.trim());appendLine()
            appendLine(tr("Sources:", "Fuentes:"));cited.forEach{n->val s=answer.sources[n-1];appendLine("[$n] ${s.title}, ${s.sourceLocation}");appendLine("    \"${s.excerpt.trim()}\"")}
            appendLine();append(tr("Answer written by Gemini Nano on the phone from the cited passages.", "Respuesta escrita por Gemini Nano en el teléfono a partir de los pasajes citados."))
        }
        if(!incognito){state=state.copy(share=UiShare(plain,preparing=false));return}
        state=state.copy(share=UiShare(incognito=true))
        shareJob?.cancel()
        shareJob=viewModelScope.launch{
            try{
                val r=pseudonymizer.mask(plain)
                state=state.copy(share=UiShare(r.text,r.replacements.size,true,if(r.nanoChecked)"" else tr("Names and addresses were not checked: Gemini Nano is unavailable. Only fixed patterns (RUT, email, phone, card, account) were masked.", "No se revisaron nombres ni direcciones: Gemini Nano no está disponible. Solo se enmascararon patrones fijos (RUT, correo, teléfono, tarjeta, cuenta)."),false))
            }catch(e:CancellationException){throw e}
            catch(e:Exception){state=state.copy(share=null,error=tr("Could not prepare masked text: ${e.message?.take(160)}", "No se pudo preparar el texto enmascarado: ${e.message?.take(160)}"))}
        }
    }
    fun dismissShare(){shareJob?.cancel();shareJob=null;state=state.copy(share=null)}
    /** Deletes the project with its sources, index and stored files. The last remaining project cannot be deleted. */
    fun deleteProject(id:String)=task(tr("Deleting project", "Eliminando proyecto")){
        require(id in projectIds()){tr("Unknown project", "Proyecto desconocido")};require(state.projects.size>1){tr("Keep at least one project.", "Debe quedar al menos un proyecto.")}
        val name=state.projects.first{it.id==id}.name
        val count=withContext(worker){store.deleteProject(id)}
        val projects=state.projects.filter{it.id!=id};saveProjects(projects)
        if(state.buildingId==id){unfilteredSearchItems=emptyList();prefs.edit().putString("project",projects.first().id).apply()}
        state=state.copy(projects=projects,buildingId=if(state.buildingId==id)projects.first().id else state.buildingId,
            query="",isSearching=false,searchMillis=null,filters=UiFilters(),filterError=null,status=tr("Deleted $id $name · $count sources removed", "Eliminado $id $name · $count fuentes quitadas"))
    }
    fun setBuilding(id:String){if(state.busy||state.recording)return;require(id in projectIds());prefs.edit().putString("project",id).apply();stopAnswer();unfilteredSearchItems=emptyList();state=state.copy(buildingId=id,query="",isSearching=false,searchMillis=null,filters=UiFilters(),filterError=null);refresh()}
    fun setQuery(q:String){if(state.busy||state.recording)return;state=state.copy(query=q.take(600),isSearching=false,searchMillis=null);refresh()}
    fun dismissError(){state=state.copy(error=null)}
    fun playback(id:String?){state=state.copy(playingId=id)}
    fun recording(active:Boolean){state=state.copy(recording=active)}
    fun messageError(message:String){state=state.copy(error=message)}
    /** Apply all constraints atomically. Invalid drafts never replace the current filters. */
    fun applyFilters(filters: UiFilters): Boolean {
        if (state.busy || state.recording) return false
        val normalized = filters.copy(dateFrom = filters.dateFrom.trim(), dateTo = filters.dateTo.trim())
        val validation = validateFilters(normalized, state.availableAssetIds)
        if (validation != null) { state = state.copy(filterError = validation); return false }
        state = state.copy(filters = normalized, filterError = null)
        refresh()
        return true
    }
    fun setKindFilter(kind: UiKind?) { applyFilters(state.filters.copy(kind = kind)) }
    fun resetFilters() { applyFilters(UiFilters()) }
    fun refresh(){
        val sources=store.sources(state.buildingId)
        val candidates = if (state.isSearching) unfilteredSearchItems else sources.map{it.ui(excerpt=if(it.error.isNotBlank())tr("Not indexed: ${it.error}", "Sin indexar: ${it.error}") else "")}
        state=state.copy(totalCount=sources.size,indexedCount=sources.count{it.indexed},photoCount=sources.count{it.type=="image"},audioCount=sources.count{it.type=="audio"},reportCount=sources.count{it.type=="pdf"},
            availableAssetIds=sources.map{it.asset}.distinct().sorted(),items=filterItems(candidates,state.buildingId,state.filters))
    }
    private fun task(label:String,block:suspend()->Unit){
        if(state.busy||state.recording)return
        viewModelScope.launch{
            state=state.copy(busy=true,status=label,error=null)
            try{block()}catch(e:Exception){state=state.copy(error=e.message?.take(300)?:tr("Operation failed", "La operación falló"),status=tr("Needs attention", "Requiere atención"))}
            finally{state=state.copy(busy=false,progress=null,modelReady=embedder.isLoaded);refresh()}
        }
    }
    fun importModel(uri:Uri)=task(tr("Importing model · this may take a moment", "Importando modelo · puede tardar un momento")){
        withContext(worker){
            modelFile.parentFile!!.mkdirs();val temp=File(modelFile.parentFile,"model.partial")
            try{appContext.contentResolver.openInputStream(uri)!!.use{input->temp.outputStream().use{out->copyBounded(input,out,600_000_000)}};verifyModel(temp);embedder.close();temp.copyTo(modelFile,overwrite=true);embedder.load(modelFile.path)}finally{temp.delete()}
        }
        state=state.copy(modelReady=embedder.isLoaded,status=tr("Model ready · index your collection next", "Modelo listo · ahora indexa tu colección"))
    }
    private fun verifyModel(file:File){
        require(file.length()==GemmaEmbedder.MODEL_BYTES){tr("Choose the full EmbeddingGemma 2 CPU/GPU model (485 MB).", "Elige el modelo completo EmbeddingGemma 2 CPU/GPU (485 MB).")}
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val b=ByteArray(1024*1024);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
        require(digest.digest().joinToString(""){"%02x".format(it)}==GemmaEmbedder.MODEL_SHA256){tr("Model checksum does not match the supported release.", "La suma de verificación del modelo no coincide con la versión compatible.")}
    }
    fun importPack(uri:Uri)=task(tr("Importing project pack", "Importando paquete de proyecto")){
        val known=projectIds();val current=state.buildingId
        val count=withContext(worker){appContext.contentResolver.openInputStream(uri)!!.use{store.importPack(it,known,current)}}
        state=state.copy(isSearching=false,query="",searchMillis=null,status=tr("$count sources imported · ready to index", "$count fuentes importadas · listas para indexar"))
    }
    fun importFiles(uris:List<Uri>)=task(tr("Importing files", "Importando archivos")){
        val building=state.buildingId;var count=0;val errors=mutableListOf<String>()
        withContext(worker){for(uri in uris.take(100)){try{importOne(uri,building);count++}catch(e:Exception){errors+=e.message?:tr("Unsupported file", "Archivo no compatible")}}}
        state=state.copy(query="",isSearching=false,searchMillis=null,status=tr("$count files imported", "$count archivos importados"),error=errors.takeIf{it.isNotEmpty()}?.joinToString("; ")?.take(300))
    }
    private fun importOne(uri:Uri,building:String){
        val resolver=appContext.contentResolver
        val displayName=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}?:"Imported file"
        val mime=resolver.getType(uri)?:""
        val type=when{mime.startsWith("image/")->"image";mime.startsWith("audio/")->"audio";mime=="application/pdf"||displayName.endsWith(".pdf",true)->"pdf";else->error(tr("Unsupported file: $displayName", "Archivo no compatible: $displayName"))}
        val suffix=when(type){"image"->".img";"audio"->".audio";else->".pdf"};val id="USER-${UUID.randomUUID()}";val f=File(store.library,id+suffix)
        try{resolver.openInputStream(uri)!!.use{input->f.outputStream().use{out->copyBounded(input,out,100_000_000)}}
            store.duplicateOf(f,building)?.let{error(tr("Already in this project: ${it.title}", "Ya está en este proyecto: ${it.title}"))}
            store.add(Source(id,f.path,type,building,"",LocalDate.now().toString(),displayName,false))}catch(e:Exception){f.delete();throw e}
    }
    fun importRecording(file:File)=task(tr("Saving voice note", "Guardando nota de voz")){
        val building=state.buildingId
        withContext(worker){val id="USER-${UUID.randomUUID()}";val dest=File(store.library,"$id.m4a");file.copyTo(dest);store.add(Source(id,dest.path,"audio",building,"",LocalDate.now().toString(),tr("Voice note · ${LocalDate.now()}", "Nota de voz · ${LocalDate.now()}"),false));file.delete()}
        state=state.copy(recording=false,isSearching=false,query="",searchMillis=null,status=tr("Voice note saved · ready to index", "Nota de voz guardada · lista para indexar"))
    }
    fun index()=task(tr("Indexing on this phone", "Indexando en este teléfono")){
        check(embedder.isLoaded){tr("Import the model first", "Primero importa el modelo")};cancelIndex.set(false)
        val pending=withContext(worker){store.sources(state.buildingId).filter{!it.indexed}}
        var failed=0;var completed=0
        for((i,s) in pending.withIndex()){
            if(cancelIndex.get())break
            state=state.copy(status=tr("Indexing ${i+1} of ${pending.size} · ${s.title}", "Indexando ${i+1} de ${pending.size} · ${s.title}"),progress=i.toFloat()/pending.size.coerceAtLeast(1))
            try{withContext(worker){store.saveChunks(s,encodeSource(s))};completed++}catch(e:Exception){failed++;withContext(worker){store.error(s.id,e.message?:tr("Could not process source", "No se pudo procesar la fuente"))}}
            refresh()
        }
        state=state.copy(status=if(cancelIndex.get())tr("Indexing paused · completed files are saved", "Indexación pausada · los archivos terminados quedan guardados") else tr("$completed sources indexed${if(failed>0)" · $failed failed; inspect files and retry" else " · ready to search"}",
            "$completed fuentes indexadas${if(failed>0)" · $failed fallaron; revisa los archivos y reintenta" else " · listo para buscar"}"),error=if(failed>0)tr("$failed files could not be indexed. Their library entries show the reason.", "$failed archivos no se pudieron indexar. Su entrada en la biblioteca muestra el motivo.") else null)
    }
    fun cancel(){cancelIndex.set(true);state=state.copy(status=tr("Finishing current file, then pausing", "Terminando el archivo actual y luego pausando"))}
    private fun encodeSource(s:Source):List<VectorChunk>{
        val file=File(s.path)
        return when(s.type){
            "image"->{val bitmap=MediaInputs.bitmap(file);try{listOf(VectorChunk(s.id,0,0,0,"",embedder.embedImage(bitmap)))}finally{bitmap.recycle()}}
            "audio"->{val pcm=MediaInputs.audio(file);val size=12*16000;val step=10*16000;val parts=mutableListOf<VectorChunk>();var from=0
                while(from<pcm.size){val until=minOf(from+size,pcm.size);parts+=VectorChunk(s.id,parts.size,0,from/16,"",embedder.embedAudio(pcm.copyOfRange(from,until).map{it.coerceIn(-1f,1f)}.toFloatArray()));if(until==pcm.size)break;from+=step};parts}
            else->{val parts=mutableListOf<VectorChunk>();PDDocument.load(file).use{doc->require(doc.numberOfPages<=1000){tr("PDF limit is 1000 pages", "El límite de PDF es de 1000 páginas")}
                for(page in 1..doc.numberOfPages){val read=PdfText.page(doc,page);val text=read.prose.trim()
                    if(text.isNotBlank()){val words=text.split(Regex("\\s+"));var offset=0
                        // ~60-word blocks with 15 words of overlap: small enough that each block is about one idea.
                        while(offset<words.size){val end=minOf(offset+60,words.size);val chunk=words.subList(offset,end).joinToString(" ");parts+=VectorChunk(s.id,parts.size,page,0,chunk,embedder.embedText(chunk));if(end==words.size)break;offset+=45}
                    }
                    // Rebuilt tables keep their line breaks and whole rows: a row cut in half loses its label.
                    for(rows in read.tables)for(chunk in TableRows.blocks(rows))parts+=VectorChunk(s.id,parts.size,page,0,chunk,embedder.embedText(chunk))
                }};require(parts.isNotEmpty()){tr("No selectable text in this PDF. Scanned PDFs are not supported yet.", "Este PDF no tiene texto seleccionable. Los PDF escaneados aún no son compatibles.")};parts}
        }
    }
    fun search() {if(state.query.isBlank()){state=state.copy(isSearching=false);refresh();return};val query=state.query
        task(tr("Searching locally", "Buscando en el teléfono")){
            val start=System.nanoTime()
            val result=withContext(worker){store.search(embedder.embedText(query,true),state.buildingId,state.totalCount,query,if(jevKey()!=null)Store.RERANK_CANDIDATES else Store.PASSAGES)}
            showHits(result.hits,(System.nanoTime()-start)/1_000_000)
            answer(query,result)
        }
    }
    /** The key to use for the current project, or null when nothing may leave the phone. */
    private fun jevKey():String?=if(state.projects.firstOrNull{it.id==state.buildingId}?.incognito==true)null else jevVault.load()
    fun saveJevKey(raw:String){
        val key=raw.trim();if(key.isEmpty()||state.jevKeyChecking)return
        state=state.copy(jevKeyChecking=true,jevKeyError=null)
        viewModelScope.launch{
            val problem=try{if(jev.keyAccepted(key))null else tr("OpenRouter rejected this key. It was not saved.", "OpenRouter rechazó esta clave. No se guardó.")}
                catch(e:CancellationException){throw e}
                catch(e:Exception){tr("Couldn't check the key with OpenRouter, so it was not saved. Check your connection.", "No se pudo comprobar la clave con OpenRouter, así que no se guardó. Revisa tu conexión.")}
            if(problem==null)jevVault.save(key)
            state=state.copy(jevKeyChecking=false,jevKeyError=problem,jevKeySaved=jevVault.load()!=null)
        }
    }
    fun removeJevKey(){jevVault.clear();stopAnswer();state=state.copy(jevKeySaved=false,jevKeyError=null)}
    /** Runs after the search so the list is usable meanwhile: Jev reranks when allowed, Gemini Nano writes a short cited answer, then Jev checks its citations. */
    private fun answer(question:String,result:SearchResult){
        stopAnswer()
        if(result.passages.isEmpty())return
        val incognito=state.projects.firstOrNull{it.id==state.buildingId}?.incognito==true
        val offNote=if(incognito&&state.jevKeySaved)tr("Jev is off in incognito projects. Nothing left this phone.", "Jev está desactivado en proyectos incógnito. Nada salió de este teléfono.") else ""
        state=state.copy(answer=UiAnswer(jevNote=offNote))
        answerJob=viewModelScope.launch{
            val passages=rerank(question,result)?:return@launch
            val sources=passages.map{it.source.ui(it.score,it.chunk.text,tr("Page ${it.chunk.page}", "Página ${it.chunk.page}"))}
            state=state.copy(answer=state.answer?.copy(status=tr("Gemini Nano is reading ${passages.size} passages…", "Gemini Nano está leyendo ${passages.size} pasajes…"),sources=sources))
            // With Jev on, the answer stays hidden until it's checked, so a sentence that gets removed is never shown.
            val key=jevKey();var written=""
            try{
                nano.answer(question,passages.map{NanoAnswerer.Passage(it.source.title,it.chunk.page,it.chunk.text)},
                    onStatus={s->state=state.copy(answer=state.answer?.copy(status=s))},
                    onText={t->written=NanoAnswerer.fixCitations(t,passages.size);if(key==null)state=state.copy(answer=state.answer?.copy(text=written))})
            }catch(e:CancellationException){throw e}
            catch(e:Exception){state=state.copy(answer=state.answer?.copy(working=false,status=tr("Gemini Nano could not answer: ","Gemini Nano no pudo responder: ")+(e.message?.take(160)?:tr("unknown error","error desconocido"))));return@launch}
            if(key==null||written.trim().startsWith("NOT_FOUND")){state=state.copy(answer=state.answer?.copy(text=written,working=false,status=""));return@launch}
            state=state.copy(answer=state.answer?.copy(status=tr("Jev is checking the citations…", "Jev está comprobando las citas…")))
            val (text,check)=checkCitations(key,written,passages,sources)
            state=state.copy(answer=state.answer?.copy(text=text,check=check,working=false,status=""))
        }
    }
    /** The passages to answer from, best first. Null means Jev judged that none helps, which is already shown. Falls back to the local order if Jev can't be reached. */
    private suspend fun rerank(question:String,result:SearchResult):List<SearchHit>?{
        val local=result.passages.take(Store.PASSAGES)
        val key=jevKey()?:return local
        state=state.copy(answer=state.answer?.copy(status=tr("Jev is judging ${result.passages.size} passages…", "Jev está evaluando ${result.passages.size} pasajes…")))
        val scores=try{jev.helpfulness(key,question,result.passages.map{it.chunk.text})}
            catch(e:CancellationException){throw e}
            catch(e:Exception){
                state=state.copy(answer=state.answer?.copy(jevFailed=true,jevNote=tr("Jev unavailable, so results keep the local order. ","Jev no disponible: los resultados mantienen el orden local. ")+jevProblem(e)))
                return local
            }
        val ranked=result.passages.zip(scores).sortedByDescending{it.second}
        // The list follows Jev's order too, showing each source's best passage, unless the user has moved on.
        if(state.isSearching&&state.query==question){
            val judged=ranked.map{it.first}.distinctBy{it.source.id}
            val ids=judged.map{it.source.id}.toSet()
            showHits(judged+result.hits.filter{it.source.id !in ids},state.searchMillis?:0)
        }
        val helpful=ranked.filter{it.second>=JevClient.HELPFUL}.map{it.first}.take(Store.PASSAGES)
        val note=tr("Jev judged ${result.passages.size} passages and kept ${helpful.size}. Your question and those passages went to OpenRouter and TypeSafe.", "Jev evaluó ${result.passages.size} pasajes y conservó ${helpful.size}. Tu pregunta y esos pasajes pasaron por OpenRouter y TypeSafe.")
        if(helpful.isEmpty()){state=state.copy(answer=state.answer?.copy(text="NOT_FOUND",working=false,status="",jevNote=note));return null}
        state=state.copy(answer=state.answer?.copy(jevNote=note))
        return helpful
    }
    /** Returns the answer to show, without any sentence its source contradicts, and the check result. If Jev can't be reached the answer stays whole and the check says so. */
    private suspend fun checkCitations(key:String,written:String,passages:List<SearchHit>,sources:List<UiItem>):Pair<String,UiCitationCheck>{
        val claims=JevClient.claims(written).map{c->c.copy(citations=c.citations.filter{it in 1..passages.size})}
        val cited=claims.filter{it.citations.isNotEmpty()}
        val uncited=claims.size-cited.size
        return try{
            val decisions=coroutineScope{cited.map{c->async{jev.check(key,c.text,c.citations.joinToString("\n\n"){passages[it-1].chunk.text})}}.awaitAll()}
            val judged=cited.zip(decisions).map{(c,d)->
                // Contradicted comes first: even a low-confidence contradiction is removed, since its source is shown in its place.
                c to when{
                    d.verdict==JevClient.Verdict.CONTRADICTS->UiClaimProblem.CONTRADICTED
                    d.confidence<JevClient.CONFIDENT->UiClaimProblem.UNSURE
                    d.verdict==JevClient.Verdict.SUPPORTS->null
                    else->UiClaimProblem.NOT_SUPPORTED
                }
            }
            val issues=judged.mapNotNull{(c,problem)->problem?.let{UiClaimIssue(c.text,c.citations,it,c.citations.map{n->sources[n-1]})}}
            val removed=judged.filter{it.second==UiClaimProblem.CONTRADICTED}.map{it.first}
            val text=if(removed.isEmpty())written else claims.filter{it !in removed}.joinToString(" "){it.raw}
            text to UiCitationCheck(cited.size-issues.size,issues,uncited)
        }catch(e:CancellationException){throw e}
        catch(e:Exception){written to UiCitationCheck(uncited=uncited,error=tr("Jev couldn't check the citations, so read this answer with care. ","Jev no pudo comprobar las citas: lee esta respuesta con cuidado. ")+jevProblem(e))}
    }
    private fun jevProblem(e:Exception)=(e as? JevClient.JevException)?.message?:tr("Check your connection.","Revisa tu conexión.")
    private fun stopAnswer(){answerJob?.cancel();answerJob=null;state=state.copy(answer=null)}
    fun imageSearch(uri:Uri)=task(tr("Finding similar evidence", "Buscando evidencia similar")){
        stopAnswer()
        val start=System.nanoTime();val temp=File(appContext.cacheDir,"query-image-${UUID.randomUUID()}")
        val hits=withContext(worker){try{appContext.contentResolver.openInputStream(uri)!!.use{input->temp.outputStream().use{out->copyBounded(input,out,30_000_000)}};val bitmap=MediaInputs.bitmap(temp);try{store.search(embedder.embedImage(bitmap),state.buildingId,state.totalCount).hits}finally{bitmap.recycle()}}finally{temp.delete()}}
        state=state.copy(query=tr("Photo search", "Búsqueda por foto"));showHits(hits,(System.nanoTime()-start)/1_000_000)
    }
    private fun showHits(hits:List<SearchHit>,ms:Long){
        unfilteredSearchItems=hits.map{h->h.source.ui(h.score,sourceExcerpt(h.chunk.text,state.query),when(h.source.type){"pdf"->tr("Page ${h.chunk.page}", "Página ${h.chunk.page}");"audio"->"${h.chunk.startMs/60000}:${((h.chunk.startMs/1000)%60).toString().padStart(2,'0')}";else->tr("Original photo", "Foto original")})}
        state=state.copy(isSearching=true,items=filterItems(unfilteredSearchItems,state.buildingId,state.filters),searchMillis=ms,status=if(hits.isEmpty())tr("No indexed files in this project", "No hay archivos indexados en este proyecto") else tr("Closest matches · inspect the original evidence", "Coincidencias más cercanas · revisa la evidencia original"))
    }
    // A literal window within the retrieved chunk, never a generated summary.
    // Ranking still uses the complete embedding; the original page remains one tap away.
    private fun sourceExcerpt(text:String,query:String):String {
        val words=text.split(Regex("\\s+")).filter{it.isNotEmpty()}
        val width=42
        if(words.size<=width)return text
        val terms=Store.terms(query).toSet()
        val hits=words.map{word->if(Store.terms(word).any{it in terms})1 else 0}
        var best=0;var score=hits.take(width).sum();var bestScore=score
        for(start in 1..words.size-width){score+=hits[start+width-1]-hits[start-1];if(score>bestScore){best=start;bestScore=score}}
        val firstMatch=(best until minOf(best+width,words.size)).firstOrNull{hits[it]>0}
        if(firstMatch!=null)best=maxOf(best,firstMatch-5)
        val end=minOf(best+width,words.size)
        return (if(best>0)"… " else "")+words.subList(best,end).joinToString(" ")+(if(end<words.size)" …" else "")
    }
    override fun onCleared(){cancelIndex.set(true);answerJob?.cancel();nano.close();worker.executor.execute{embedder.close();store.close()};worker.close();super.onCleared()}
    companion object {
        private fun validDate(value: String): LocalDate? = if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) null else runCatching { LocalDate.parse(value) }.getOrNull()
        fun validateFilters(filters: UiFilters, availableAssetIds: List<String>): String? {
            if (filters.assetId != null && filters.assetId !in availableAssetIds) return tr("Choose an asset in the selected project.", "Elige un activo del proyecto seleccionado.")
            if (filters.dateFrom.isNotEmpty() && validDate(filters.dateFrom) == null) return tr("From date must be a real date in YYYY-MM-DD format.", "La fecha Desde debe ser una fecha real en formato AAAA-MM-DD.")
            if (filters.dateTo.isNotEmpty() && validDate(filters.dateTo) == null) return tr("To date must be a real date in YYYY-MM-DD format.", "La fecha Hasta debe ser una fecha real en formato AAAA-MM-DD.")
            if (filters.dateFrom.isNotEmpty() && filters.dateTo.isNotEmpty() && filters.dateFrom > filters.dateTo) return tr("From date must be on or before the To date.", "La fecha Desde debe ser igual o anterior a la fecha Hasta.")
            return null
        }
        /** Stable filtering preserves the original similarity order; date bounds are inclusive. */
        fun filterItems(items: List<UiItem>, building: String, filters: UiFilters): List<UiItem> = items.filter { item ->
            item.buildingId == building &&
                (filters.assetId == null || item.assetId == filters.assetId) &&
                (filters.kind == null || item.kind == filters.kind) &&
                ((filters.dateFrom.isEmpty() && filters.dateTo.isEmpty()) ||
                    (validDate(item.date) != null && (filters.dateFrom.isEmpty() || item.date >= filters.dateFrom) &&
                        (filters.dateTo.isEmpty() || item.date <= filters.dateTo)))
        }
        fun copyBounded(input:java.io.InputStream,out:java.io.OutputStream,limit:Long){val buf=ByteArray(65536);var count=0L;while(true){val n=input.read(buf);if(n<0)break;count+=n;require(count<=limit){tr("File exceeds import size limit", "El archivo supera el límite de tamaño de importación")};out.write(buf,0,n)}}
    }
}
