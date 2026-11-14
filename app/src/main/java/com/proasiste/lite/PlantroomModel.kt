package com.proasiste.lite

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.*
import com.proasiste.lite.runtime.CloudAnswerer
import com.proasiste.lite.runtime.GemmaEmbedder
import com.proasiste.lite.runtime.JevClient
import com.proasiste.lite.runtime.KeyVault
import com.proasiste.lite.runtime.NanoAnswerer
import com.proasiste.lite.runtime.TableRows
import com.proasiste.lite.runtime.PdfText
import com.proasiste.lite.runtime.DeepSeekFacts
import com.proasiste.lite.runtime.DocLang
import com.proasiste.lite.runtime.DocPages
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
    // Shared with the background indexer: one database and one loaded model per process.
    private val engine=Engine.get(app)
    val store=engine.store
    val embedder=engine.embedder
    private val graph=com.proasiste.lite.graph.Graph(store)
    private val tags=com.proasiste.lite.tags.Tags(store)
    // Declared before init: refresh() runs during construction and reads them.
    private val reports=com.proasiste.lite.reports.Reports(store)
    private val actionsStore=com.proasiste.lite.actions.Actions(store)
    private var openReportId:Long?=null
    private val deepSeek=com.proasiste.lite.runtime.DeepSeekFacts()
    private val worker=engine.worker
    private val modelFile=engine.modelFile
    private val prefs=app.getSharedPreferences("plantroom",0)
    /** When the Library was last reloaded for background progress: at most every few seconds, since imports can run at several files a second. */
    private var lastProgressRefresh=0L
    private var lateRefresh:Job?=null
    private var unfilteredSearchItems: List<UiItem> = emptyList()
    private val nano=NanoAnswerer()
    private val pseudonymizer=Pseudonymizer(nano)
    private val jev=JevClient()
    private val cloud=CloudAnswerer()
    /** OpenRouter's catalog, once loaded; used for model names. */
    private var catalogModels:List<CloudAnswerer.Model> = emptyList()
    private val jevVault=KeyVault(prefs)
    private var answerJob:Job?=null
    private var shareJob:Job?=null
    init{
        PDFBoxResourceLoader.init(app)
        // First launch follows the phone's language; after that, the user's choice.
        L10n.spanish=prefs.getBoolean("spanish",java.util.Locale.getDefault().language=="es")
        state=state.copy(projects=loadProjects(),jevKeySaved=jevVault.load()!=null,jevEnabled=prefs.getBoolean("jev-enabled",false)).let{s->s.copy(buildingId=prefs.getString("project",null)?.takeIf{id->s.projects.any{it.id==id}}?:s.projects.first().id)}
        task(tr("Preparing your collection", "Preparando tu colección")){
            withContext(worker){
                store.ensureIndexVersion()
                if(!prefs.getBoolean("projects-v1",false)){Store.LEGACY_BUILDINGS.forEach{(from,to)->store.moveSources(from,to)};prefs.edit().putBoolean("projects-v1",true).apply()}
                if(!prefs.getBoolean("sample-v1",false)){
                    if(app.assets.list("")?.contains("sample-pack.zip")==true){app.assets.open("sample-pack.zip").use{store.importPack(it,projectIds(),"P01")};prefs.edit().putBoolean("sample-v1",true).apply()}
                }
                engine.ensureModel()
            }
            state=state.copy(modelReady=embedder.isLoaded,status=if(embedder.isLoaded)tr("Model ready · search runs on this phone", "Modelo listo · la búsqueda funciona en este teléfono") else tr("Import the model to enable search", "Importa el modelo para activar la búsqueda"))
            refresh()
            // A run Android stopped (not one the user paused) picks up where it left off.
            if(IndexService.state.value==null&&IndexService.requested(appContext).isNotEmpty())IndexService.start(appContext)
        }
        viewModelScope.launch{IndexService.state.collect{p->
            state=state.copy(indexing=p?.let{UiIndexing(
                if(it.pausing)tr("Pausing after this file · ${it.title}","Pausando después de este archivo · ${it.title}")
                else it.label,it.fraction,it.pausing)})
            // At most one Library reload every few seconds, but never drop the last one: a skipped update is reloaded late.
            val now=System.currentTimeMillis()
            if(p==null||now-lastProgressRefresh>PROGRESS_REFRESH_MS){lastProgressRefresh=now;lateRefresh?.cancel();lateRefresh=null;refresh()}
            else if(lateRefresh==null)lateRefresh=viewModelScope.launch{kotlinx.coroutines.delay(PROGRESS_REFRESH_MS);lateRefresh=null;lastProgressRefresh=System.currentTimeMillis();refresh()}
        }}
        viewModelScope.launch{IndexService.result.collect{r->if(r!=null){state=state.copy(status=r,modelReady=embedder.isLoaded);refresh()}}}
    }
    private fun projectIds()=state.projects.map{it.id}.toSet()
    private fun loadProjects():List<UiProject>{
        val raw=prefs.getString("projects",null)?:return SAMPLE_PROJECTS
        return projectsFromJson(raw)?:SAMPLE_PROJECTS
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
    private fun saveProjects(projects:List<UiProject>){prefs.edit().putString("projects",projectsToJson(projects)).apply()}
    /** Sets who writes this project's answers: an OpenRouter model id, or "" for Gemini Nano on the phone. */
    fun setAnswerModel(model:String){
        val id=state.buildingId
        val projects=state.projects.map{if(it.id==id)it.copy(answerModel=model.trim())else it};saveProjects(projects);stopAnswer()
        state=state.copy(projects=projects,status=if(model.isBlank())tr("$id answers are written by Gemini Nano on this phone","Las respuestas de $id las escribe Gemini Nano en este teléfono")
            else tr("$id answers are written by ${CloudAnswerer.displayName(model,catalogModels)} through OpenRouter","Las respuestas de $id las escribe ${CloudAnswerer.displayName(model,catalogModels)} a través de OpenRouter"))
    }
    /** Loads OpenRouter's model list once per session for the picker. It needs no key and sends nothing about the user. */
    fun loadModels(){
        if(state.catalog.loading||catalogModels.isNotEmpty())return
        state=state.copy(catalog=UiCatalog(loading=true))
        viewModelScope.launch{
            try{catalogModels=cloud.models();state=state.copy(catalog=UiCatalog(CloudAnswerer.suggestions(catalogModels).map{it.ui()},catalogModels.map{it.ui()}))}
            catch(e:CancellationException){throw e}
            catch(e:Exception){state=state.copy(catalog=UiCatalog(error=(e as? CloudAnswerer.CloudException)?.message?:tr("Couldn't load OpenRouter's model list. Check your connection.","No se pudo cargar la lista de modelos de OpenRouter. Revisa tu conexión.")))}
        }
    }
    private fun CloudAnswerer.Model.ui()=UiModel(id,name,inputPerMillion,outputPerMillion,context)

    // ---- Reports, decisions and export ----
    private fun pageOf(location:String)=Regex("\\d+").find(location)?.value?.toIntOrNull()?:1
    private fun uiReports(building:String):List<UiReport>{
        val sources=store.sources(building).associateBy{it.id}
        val history=reports.decisions(building).filter{it.target==com.proasiste.lite.reports.Reports.REPORT}.groupBy{it.targetId}
        return reports.list(building).map{r->
            val h=history[r.id.toString()].orEmpty().map{UiDecision(it.value,it.note,it.created)}
            UiReport(r.id,r.title,r.question,r.body,r.model,r.cited.mapNotNull{c->sources[c.sourceId]?.ui(null,c.excerpt,tr("Page ${c.page}","Página ${c.page}"))?.copy(highlight=c.place)},r.created,r.updated,h.firstOrNull(),h)
        }
    }
    private fun refreshReports(){
        val list=uiReports(state.buildingId)
        val latest=reports.latest(state.buildingId).filterKeys{it.startsWith(com.proasiste.lite.reports.Reports.FILE+":")}
            .map{(k,d)->k.substringAfter(':') to UiDecision(d.value,d.note,d.created)}.toMap()
        state=state.copy(reports=list,openReport=openReportId?.let{id->list.firstOrNull{it.id==id}},fileDecisions=latest)
    }
    /**
     * Saves the answer on screen as a report: the question, the answer as written, who wrote it, and the passages it was
     * given, in their order, so its [n] marks still point at the right one; each keeps its page and place so it opens
     * highlighted later.
     */
    fun saveAnswer(){
        val a=state.answer?:return;if(a.working||a.text.isBlank()||a.text.trim().startsWith("NOT_FOUND"))return
        val passages=a.sources.map{s->com.proasiste.lite.reports.Cited(s.id,s.title,pageOf(s.sourceLocation),s.excerpt,s.highlight)}
        val question=state.query.trim();val building=state.buildingId
        val model=if(a.fromGraph)"graph" else a.writer
        viewModelScope.launch{
            val id=withContext(worker){reports.save(com.proasiste.lite.reports.Report(0,building,question.take(120).ifEmpty{tr("Answer","Respuesta")},question,a.text.trim(),model,passages,0,0))}
            openReportId=id;refreshReports()
            state=state.copy(status=tr("Saved as a report in the Library","Guardado como informe en la Biblioteca"))
        }
    }
    fun openReport(id:Long){openReportId=id;refreshReports()}
    fun closeReport(){openReportId=null;state=state.copy(openReport=null)}
    fun saveReport(id:Long,title:String,body:String){
        val r=reports.get(id)?:return
        viewModelScope.launch{withContext(worker){reports.save(r.copy(title=title.ifBlank{r.title},body=body))};refreshReports()}
    }
    fun deleteReport(id:Long){viewModelScope.launch{withContext(worker){reports.delete(id)};if(openReportId==id)openReportId=null;refreshReports()}}
    /** A report written by hand, opened for editing. */
    fun newNote(){
        val building=state.buildingId
        viewModelScope.launch{val id=withContext(worker){reports.save(com.proasiste.lite.reports.Report(0,building,tr("Note","Nota")+" · "+java.time.LocalDate.now(),"","","",emptyList(),0,0))};openReportId=id;refreshReports()}
    }
    fun decide(target:String,id:String,value:String,note:String){
        val building=state.buildingId
        viewModelScope.launch{
            try{withContext(worker){reports.decide(building,target,id,value,note)}}catch(e:Exception){state=state.copy(error=e.message?.take(200))}
            refreshReports()
        }
    }
    /** What to write once the user has picked where; kept until then. */
    private var pendingExport:((java.io.OutputStream)->Unit)?=null
    private fun askToSave(name:String,mime:String,write:(java.io.OutputStream)->Unit){pendingExport=write;state=state.copy(export=UiExport(name,mime))}
    fun exportReport(id:Long,format:String){
        val r=reports.get(id)?:return;val d=reports.decisions(state.buildingId)
        if(format=="pdf")askToSave(com.proasiste.lite.reports.Export.fileName(r.title,"pdf"),"application/pdf"){com.proasiste.lite.reports.Export.pdf(r,d,it)}
        else askToSave(com.proasiste.lite.reports.Export.fileName(r.title,"md"),"text/markdown"){it.write(com.proasiste.lite.reports.Export.markdown(r,d).toByteArray())}
    }
    /**
     * Saves one PDF as DocLang 0.9: its blocks with their boxes, read on this phone. Saved as a generic file, since
     * declaring it XML makes Android add ".xml" after ".doclang".
     */
    fun exportDocLang(sourceId:String){
        val s=store.sources(state.buildingId).firstOrNull{it.id==sourceId&&it.type=="pdf"}?:return
        askToSave(com.proasiste.lite.reports.Export.fileName(s.title.removeSuffix(".pdf").removeSuffix(".PDF"),"doclang"),"application/octet-stream"){out->
            val xml=PDDocument.load(File(s.path)).use{DocLang.toXml(DocLang.read(it))}
            out.write(xml.toByteArray())
        }
    }
    fun exportDecisions(){
        val d=reports.decisions(state.buildingId)
        askToSave("decisiones-${state.buildingId}.csv","text/csv"){it.write(com.proasiste.lite.reports.Export.decisionsCsv(d).toByteArray())}
    }
    fun exportFacts(format:String){
        val f=com.proasiste.lite.reports.Export.facts(store,state.buildingId)
        if(f.isEmpty()){state=state.copy(error=tr("This project's graph has no facts yet.","El grafo de este proyecto aún no tiene datos."));return}
        if(format=="json")askToSave("grafo-${state.buildingId}.json","application/json"){it.write(com.proasiste.lite.reports.Export.factsJson(f).toByteArray())}
        else askToSave("grafo-${state.buildingId}.csv","text/csv"){it.write(com.proasiste.lite.reports.Export.factsCsv(f).toByteArray())}
    }
    /** Writes the waiting file where the user chose, or forgets it when they cancelled. */
    fun exportTo(uri:Uri?){
        val write=pendingExport;pendingExport=null;val name=state.export?.name.orEmpty();state=state.copy(export=null)
        if(uri==null||write==null)return
        viewModelScope.launch{
            try{withContext(Dispatchers.IO){appContext.contentResolver.openOutputStream(uri)!!.use(write)};state=state.copy(status=tr("Saved $name","Guardado $name"))}
            catch(e:Exception){state=state.copy(error=tr("Couldn't save $name: ${e.message?.take(120)}","No se pudo guardar $name: ${e.message?.take(120)}"))}
        }
    }

    // ---- Actions on files ----
    private fun refreshActions(){state=state.copy(actions=actionsStore.all().map{UiAction(it.key,it.name,it.instructions,it.skillId,it.local)})}
    /** A DocLang run waiting for its folder: the action and the files. */
    private var pendingDocLang:Pair<String,List<String>>?=null
    /** The indexed PDFs an action runs on: all of the project's, or those carrying [tag]. */
    private fun actionTargets(tag:String?):List<String>{
        val withTag=tag?.let{t->state.tagsBySource.filterValues{t in it}.keys}
        return store.sources(state.buildingId).filter{it.type=="pdf"&&it.indexed&&(withTag==null||it.id in withTag)}.map{it.id}
    }
    /**
     * Starts an action in the background on the project's indexed PDFs or a tag's. DocLang asks for a folder first; AI
     * actions need the project's OpenRouter model and the key, and never run in an incognito project.
     */
    fun runAction(key:String,tag:String?){
        val action=actionsStore.all().firstOrNull{it.key==key}?:return
        val targets=actionTargets(tag)
        if(targets.isEmpty()){state=state.copy(error=tr("No indexed PDFs here yet.","Aún no hay PDF indexados aquí."));return}
        val scope=tag?.let{"#$it"}?:state.buildingId
        if(action.local){pendingDocLang="${action.key}|$scope" to targets;state=state.copy(pickActionFolder=true);return}
        val project=state.projects.firstOrNull{it.id==state.buildingId}?:return
        val problem=when{
            project.incognito->tr("Actions that use AI are off in incognito projects. Nothing left this phone.","Las acciones con IA están desactivadas en proyectos incógnito. Nada salió de este teléfono.")
            project.answerModel.isEmpty()->tr("Choose an OpenRouter model for this project first, in settings under Who writes answers. Actions use that model.","Primero elige un modelo de OpenRouter para este proyecto, en ajustes, en Quién escribe las respuestas. Las acciones usan ese modelo.")
            !state.jevKeySaved->tr("Save your OpenRouter key in settings first.","Primero guarda tu clave de OpenRouter en ajustes.")
            else->null
        }
        if(problem!=null){state=state.copy(error=problem);return}
        startRun(action,scope,targets,CloudAnswerer.displayName(project.answerModel,catalogModels),"")
    }
    private fun startRun(action:com.proasiste.lite.actions.Action,scope:String,targets:List<String>,model:String,folder:String){
        val building=state.buildingId
        viewModelScope.launch{
            try{withContext(worker){actionsStore.start(building,action,scope,targets,model,folder)}}catch(e:Exception){state=state.copy(error=e.message?.take(200));return@launch}
            IndexService.start(appContext,building)
            refreshReports()
            state=state.copy(status=tr("${action.name} on ${targets.size} files · runs in the background; the result grows in Reports","${action.name} en ${targets.size} archivos · se ejecuta en segundo plano; el resultado crece en Informes"))
        }
    }
    /** The folder picked for a DocLang run: kept writable, then the run starts. */
    fun docLangFolder(tree:Uri?){
        state=state.copy(pickActionFolder=false)
        val (spec,targets)=pendingDocLang?:return;pendingDocLang=null
        if(tree==null)return
        try{appContext.contentResolver.takePersistableUriPermission(tree,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}catch(_:Exception){}
        val action=actionsStore.all().firstOrNull{it.key==spec.substringBefore('|')}?:return
        startRun(action,spec.substringAfter('|'),targets,"",tree.toString())
    }
    fun saveSkill(name:String,instructions:String){viewModelScope.launch{try{withContext(worker){actionsStore.saveSkill(name,instructions)}}catch(e:Exception){state=state.copy(error=e.message?.take(200))};refreshActions()}}
    fun deleteSkill(id:Long){viewModelScope.launch{withContext(worker){actionsStore.deleteSkill(id)};refreshActions()}}

    // ---- Graph types ----
    /** Changes the project's graph schema and saves it; facts already in the graph stay until the graph is built again. */
    private fun editSchema(change:(com.proasiste.lite.graph.GraphSchema)->com.proasiste.lite.graph.GraphSchema){
        val building=state.buildingId
        viewModelScope.launch{
            try{withContext(worker){store.saveGraphSchema(building,change(store.graphSchema(building)))}}catch(e:Exception){state=state.copy(error=e.message?.take(200))}
            refresh()
        }
    }
    fun setSchemaMode(mode:String)=editSchema{it.copy(mode=mode)}
    fun addNodeType(name:String,description:String){
        val n=com.proasiste.lite.graph.GraphSchema.cleanType(name);if(n.isEmpty())return
        editSchema{s->s.copy(nodes=s.nodes.filter{it.name!=n}+com.proasiste.lite.graph.NodeType(n,description.trim().take(200)))}
    }
    /** Removes a type and its place in every link; a link left with no type on one side is removed too. */
    fun removeNodeType(name:String)=editSchema{s->s.copy(nodes=s.nodes.filter{it.name!=name},
        links=s.links.map{it.copy(from=it.from-name,to=it.to-name)}.filter{it.from.isNotEmpty()&&it.to.isNotEmpty()})}
    fun addLinkType(name:String,description:String,from:Set<String>,to:Set<String>){
        val n=com.proasiste.lite.graph.GraphSchema.cleanLink(name);if(n.isEmpty()||from.isEmpty()||to.isEmpty())return
        editSchema{s->s.copy(links=s.links.filter{it.name!=n}+com.proasiste.lite.graph.LinkType(n,description.trim().take(300),from,to))}
    }
    fun removeLinkType(name:String)=editSchema{s->s.copy(links=s.links.filter{it.name!=name})}
    /** Replaces the custom types with a copy of the legal mode's, to edit. */
    fun useLegalPreset()=editSchema{com.proasiste.lite.graph.GraphSchema.legalPreset()}

    // ---- Tags ----
    private fun tagWork(block:()->Unit){viewModelScope.launch{try{withContext(worker){block()}}catch(e:Exception){state=state.copy(error=e.message?.take(200))};refresh()}}
    fun addTag(sourceId:String,name:String)=tagWork{tags.add(sourceId,state.buildingId,name)}
    fun removeTag(sourceId:String,name:String)=tagWork{tags.remove(sourceId,state.buildingId,name)}
    fun toggleTagFilter(name:String){val now=state.filters.tags;applyFilters(state.filters.copy(tags=if(name in now)now-name else now+name))}
    /** Adds a rule and runs the project's rules over all its files at once, so the new tags show immediately. */
    fun addTagRule(kind:String,pattern:String,tag:String){
        val building=state.buildingId
        viewModelScope.launch{
            try{val n=withContext(worker){tags.addRule(building,kind,pattern,tag);tags.applyRules(building)}
                state=state.copy(status=tr("Rule added · $n files have rule tags","Regla agregada · $n archivos tienen etiquetas de reglas"))}
            catch(e:Exception){state=state.copy(error=e.message?.take(200))}
            refresh()
        }
    }
    /** Deletes a rule; the tags only it gave are taken off. */
    fun deleteTagRule(id:Long){val building=state.buildingId;tagWork{tags.deleteRule(id);tags.applyRules(building)}}
    fun applyTagRules(){
        val building=state.buildingId
        viewModelScope.launch{val n=withContext(worker){tags.applyRules(building)};state=state.copy(status=tr("Rules applied · $n files have rule tags","Reglas aplicadas · $n archivos tienen etiquetas de reglas"));refresh()}
    }
    fun defineTag(name:String,description:String){val building=state.buildingId;tagWork{tags.define(building,name,description)}}
    /**
     * Queues the project's indexed PDFs for AI tagging in the background service, with the project's OpenRouter model.
     * Refuses up front when it couldn't run: incognito, no cloud model, no key, no described tags or nothing indexed.
     */
    fun tagWithAi(){
        val project=state.projects.firstOrNull{it.id==state.buildingId}?:return
        val problem=when{
            project.incognito->tr("AI tagging is off in incognito projects. Nothing left this phone.","El etiquetado con IA está desactivado en proyectos incógnito. Nada salió de este teléfono.")
            project.answerModel.isEmpty()->tr("Choose an OpenRouter model for this project first, in settings under Who writes answers. AI tagging uses that model.","Primero elige un modelo de OpenRouter para este proyecto, en ajustes, en Quién escribe las respuestas. El etiquetado con IA usa ese modelo.")
            !state.jevKeySaved->tr("Save your OpenRouter key in settings first.","Primero guarda tu clave de OpenRouter en ajustes.")
            state.aiTags.isEmpty()->tr("Describe at least one tag first, so AI knows what to look for.","Primero describe al menos una etiqueta, para que la IA sepa qué buscar.")
            else->null
        }
        if(problem!=null){state=state.copy(error=problem);return}
        val building=state.buildingId
        viewModelScope.launch{
            val n=withContext(worker){tags.queueForAi(building)}
            if(n==0){state=state.copy(error=tr("Index this project's PDFs first.","Primero indexa los PDF de este proyecto."));return@launch}
            IndexService.start(appContext,building)
            val model=catalogModels.firstOrNull{it.id==project.answerModel}
            // About 2,300 tokens in and 50 out per file.
            val estimate=model?.let{" · "+tr("about","unos")+" $"+"%.2f".format(java.util.Locale.US,n*(2300*it.inputPerMillion+50*it.outputPerMillion)/1_000_000)}.orEmpty()
            state=state.copy(status=tr("$n files queued for AI tagging with ${CloudAnswerer.displayName(project.answerModel,catalogModels)}$estimate · runs in the background","$n archivos en cola para etiquetar con IA con ${CloudAnswerer.displayName(project.answerModel,catalogModels)}$estimate · se ejecuta en segundo plano"))
        }
    }
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
            appendLine();append(when{
                answer.fromGraph->tr("Listed from the project graph, whose facts were extracted by AI.", "Lista del grafo del proyecto, cuyos datos fueron extraídos por IA.")
                answer.cloud->tr("Answer written by ${answer.writer} (through OpenRouter) from the cited passages.", "Respuesta escrita por ${answer.writer} (a través de OpenRouter) a partir de los pasajes citados.")
                else->tr("Answer written by Gemini Nano on the phone from the cited passages.", "Respuesta escrita por Gemini Nano en el teléfono a partir de los pasajes citados.")})
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
    fun setBuilding(id:String){if(state.busy||state.recording)return;require(id in projectIds());openReportId=null;prefs.edit().putString("project",id).apply();stopAnswer();unfilteredSearchItems=emptyList();state=state.copy(buildingId=id,query="",isSearching=false,searchMillis=null,filters=UiFilters(),filterError=null);refresh()}
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
        val bySource=tags.bySource(state.buildingId)
        unfilteredSearchItems=unfilteredSearchItems.map{it.copy(tags=bySource[it.id].orEmpty())}
        val candidates = if (state.isSearching) unfilteredSearchItems else sources.map{it.ui(excerpt=libraryNote(it)).copy(tags=bySource[it.id].orEmpty())}
        val docReview=graph.documentStatus(state.buildingId).mapValues{(_,s)->UiPageStatus(s.pending,s.facts,s.unchecked)}
        val pageReview=openDocumentId?.let{id->graph.pageStatus(id).mapValues{(_,s)->UiPageStatus(s.pending,s.facts,s.unchecked)}}?:emptyMap()
        state=state.copy(reviewCount=graph.reviewCount(state.buildingId),docReview=docReview,pageReview=pageReview,totalCount=sources.size,indexedCount=sources.count{it.indexed},photoCount=sources.count{it.type=="image"},audioCount=sources.count{it.type=="audio"},reportCount=sources.count{it.type=="pdf"},
            remainingCount=sources.count{!it.indexed&&!it.needsOcr},scanCount=sources.count{it.needsOcr},queuedCount=store.importCounts(state.buildingId).pending,
            tagsBySource=bySource,tagCounts=tags.counts(state.buildingId).map{(n,c)->UiTagCount(n,c)},
            graphSchema=store.graphSchema(state.buildingId),
            tagRules=tags.rules(state.buildingId).map{UiTagRule(it.id,it.kind,it.pattern,it.tag)},aiTags=tags.definitions(state.buildingId).map{UiTagDef(it.name,it.description)},
            availableAssetIds=sources.map{it.asset}.distinct().sorted(),items=filterItems(candidates,state.buildingId,state.filters))
        refreshReports();refreshActions()
    }
    /** What the Library says under a file: why it isn't indexed, or which of its pages couldn't be read. */
    private fun libraryNote(s:Source)=when{
        s.needsOcr->tr("Scanned PDF · needs OCR before it can be searched","PDF escaneado · necesita OCR antes de poder buscarse")
        s.error.isNotBlank()->tr("Not indexed: ${s.error}", "Sin indexar: ${s.error}")
        s.indexed&&s.noTextPages>0->tr("${s.noTextPages} of ${s.pages} pages have no text (scanned?) · those pages aren't searchable","${s.noTextPages} de ${s.pages} páginas no tienen texto (¿escaneadas?) · esas páginas no se pueden buscar")
        else->""
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
        check(IndexService.state.value==null){tr("Pause importing and indexing before replacing the model.","Pausa la importación y la indexación antes de reemplazar el modelo.")}
        withContext(worker){
            modelFile.parentFile!!.mkdirs();val temp=File(modelFile.parentFile,"model.partial")
            try{appContext.contentResolver.openInputStream(uri)!!.use{input->temp.outputStream().use{out->copyBounded(input,out,600_000_000)}};verifyModel(temp);embedder.close();temp.copyTo(modelFile,overwrite=true);embedder.load(modelFile.path)}finally{temp.delete()}
        }
        state=state.copy(modelReady=embedder.isLoaded,status=tr("Model ready · index your collection next", "Modelo listo · ahora indexa tu colección"))
    }
    private fun verifyModel(file:File)=Engine.verifyModel(file)
    fun importPack(uri:Uri)=task(tr("Importing project pack", "Importando paquete de proyecto")){
        val known=projectIds();val current=state.buildingId
        val count=withContext(worker){appContext.contentResolver.openInputStream(uri)!!.use{store.importPack(it,known,current)}.also{tags.applyRules(current)}}
        state=state.copy(isSearching=false,query="",searchMillis=null,status=tr("$count sources imported · ready to index", "$count fuentes importadas · listas para indexar"))
    }
    /**
     * Queues every supported file in a picked folder and its subfolders, then imports and indexes them in the background.
     * Read access to the folder is kept, so a long import can stop and resume. Files already queued are not queued again,
     * and a file already in the project is skipped when its turn comes.
     */
    fun importFolder(tree:Uri)=task(tr("Reading folder", "Leyendo carpeta")){
        val building=state.buildingId
        appContext.contentResolver.takePersistableUriPermission(tree,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val (found,added)=withContext(Dispatchers.IO){val files=FolderImport.walk(appContext,tree);files.size to store.enqueue(building,files)}
        check(found>0){tr("No PDFs, photos or recordings in that folder.","No hay PDF, fotos ni grabaciones en esa carpeta.")}
        IndexService.start(appContext,building)
        state=state.copy(query="",isSearching=false,searchMillis=null,status=tr("$found files found, $added new · importing and indexing in the background. You can keep using the app.","$found archivos encontrados, $added nuevos · se importan e indexan en segundo plano. Puedes seguir usando la app."))
    }
    fun importFiles(uris:List<Uri>)=task(tr("Importing files", "Importando archivos")){
        val building=state.buildingId;var count=0;val errors=mutableListOf<String>()
        withContext(worker){for(uri in uris.take(100)){try{importOne(uri,building);count++}catch(e:Exception){errors+=e.message?:tr("Unsupported file", "Archivo no compatible")}};tags.applyRules(building)}
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
    /**
     * Indexes this project's remaining files in the background service, so the app stays usable and the work goes on
     * with the screen off. Files that failed before are tried again; scans waiting for OCR are left alone.
     */
    fun index(){
        if(state.busy||state.recording)return
        if(!embedder.isLoaded){state=state.copy(error=tr("Import the model first", "Primero importa el modelo"));return}
        val building=state.buildingId
        viewModelScope.launch{withContext(worker){store.retryFailed(building)};IndexService.start(appContext,building);refresh()}
    }
    fun cancel(){IndexService.pause(appContext)}
    fun search() {if(state.query.isBlank()){state=state.copy(isSearching=false);refresh();return};val query=state.query
        task(tr("Searching locally", "Buscando en el teléfono")){
            val start=System.nanoTime()
            val result=withContext(worker){val vector=embedder.embedText(query,true);val count=if(jevKey()!=null)Store.RERANK_CANDIDATES else Store.PASSAGES
                withFacts(store.search(vector,state.buildingId,state.totalCount,query,count),vector,query,count)}
            val listed=withContext(worker){graph.factsWithRelations(state.buildingId,com.proasiste.lite.graph.ListQuestions.relations(query),LIST_LIMIT)}
            showHits(result.hits,(System.nanoTime()-start)/1_000_000)
            if(listed.isNotEmpty())listAnswer(listed) else answer(query,result)
        }
    }
    /**
     * Puts the project graph's best-matching facts ahead of the passages Nano answers from. Each fact keeps its source
     * and page, so it is cited and checked like any passage, and is marked as a graph fact, not the document's words.
     */
    private fun withFacts(result:SearchResult,vector:FloatArray,query:String,count:Int):SearchResult{
        val sources=store.sources(state.buildingId).associateBy{it.id}
        val facts=graph.facts(state.buildingId,vector,query,GRAPH_FACTS).mapNotNull{f->sources[f.sourceId]?.let{s->SearchHit(s,VectorChunk(s.id,GRAPH_FACT_ORDINAL,f.page,0,tr("Graph fact: ","Dato del grafo: ")+f.fact,FloatArray(0)),f.similarity)}}
        val merged=if(facts.isEmpty())result else result.copy(passages=(facts+result.passages).take(maxOf(count,facts.size)))
        merged.passages.forEachIndexed{i,h->DeepSeekFacts.debugLog("passage [${i+1}] p${h.chunk.page} score=${"%.3f".format(java.util.Locale.US,h.score)} ${h.chunk.text.take(110).replace("\n"," ")}")}
        return merged
    }
    /** Loads a graph file into the current project; its facts then join search answers. */
    fun importGraph(uri:Uri)=task(tr("Importing graph", "Importando grafo")){
        check(embedder.isLoaded){tr("Import the model first", "Primero importa el modelo")}
        val project=state.buildingId
        val added=withContext(worker){
            val json=appContext.contentResolver.openInputStream(uri)!!.use{input->java.io.ByteArrayOutputStream().also{copyBounded(input,it,10_000_000)}.toString("UTF-8")}
            graph.importFile(project,json){embedder.embedText(it)}
        }
        state=state.copy(status=tr("$added graph facts added to $project", "$added datos del grafo agregados a $project"))
    }
    /**
     * Builds the project's graph with DeepSeek: each indexed PDF's pages go to OpenRouter with the key saved for Jev,
     * and the facts that pass the number check replace that file's previous facts. Never runs for incognito projects.
     */
    fun buildGraph()=task(tr("Building graph with DeepSeek", "Construyendo el grafo con DeepSeek")){
        check(embedder.isLoaded){tr("Import the model first", "Primero importa el modelo")}
        val key=cloudKey()?:error(if(state.projects.firstOrNull{it.id==state.buildingId}?.incognito==true)tr("Graph building is off in incognito projects. Nothing left this phone.", "La construcción del grafo está desactivada en proyectos incógnito. Nada salió de este teléfono.") else tr("Save your OpenRouter key in settings first.", "Primero guarda tu clave de OpenRouter en la configuración."))
        val project=state.buildingId
        val pdfs=withContext(worker){store.sources(project).filter{it.type=="pdf"&&it.indexed}}
        val schema=withContext(worker){store.graphSchema(project)}
        check(schema.complete()){tr("Finish the project's graph types first: at least one type and one link, and links that name only listed types.","Primero completa los tipos del grafo del proyecto: al menos un tipo y un vínculo, y vínculos que solo nombren tipos de la lista.")}
        check(pdfs.isNotEmpty()){tr("Index this project's PDFs first.", "Primero indexa los PDF de este proyecto.")}
        var added=0;var dropped=0;var pages=0;var cost=0.0;var review=0;var legal=0;var failed=0;var kept=0
        for((i,s) in pdfs.withIndex()){
            val text=withContext(worker){graphText(s)}
            val alsoKnown=withContext(worker){graph.nodeNames(project,"Fallo")+graph.nodeNames(project,"Ministro")}
            val result=deepSeek.extract(key,s.title,text.pages,alsoKnown,{done,total->state=state.copy(status=tr("DeepSeek · ${s.title} · $done of $total parts done", "DeepSeek · ${s.title} · $done de $total partes listas"),progress=(i+done.toFloat()/total.coerceAtLeast(1))/pdfs.size)},text.checkPages,schema)
            // Each fact keeps the block it was found in, so it opens on its page with that paragraph highlighted.
            fun placed(f:com.proasiste.lite.graph.NewFact)=text.doc?.let{d->f.copy(place=DocPages.locate(d,f))}?:f
            // A partial result never replaces a complete one: if a part failed and the file has facts, they stay.
            val keepOld=result.failedParts>0&&withContext(worker){graph.factCounts(project)[s.id]?:0}>0
            if(keepOld)kept++ else added+=withContext(worker){graph.replaceFacts(project,s.id,result.facts.map(::placed)){embedder.embedText(it)}.also{graph.saveReview(project,s.id,result.reviewItems.map{(f,r)->placed(f) to r},result.extractor)}}
            failed+=result.failedParts
            dropped+=result.dropped;pages+=result.pagesSent.size;cost+=result.cost;review+=result.review;legal+=if(result.legal)1 else 0
        }
        state=state.copy(status=tr("$added graph facts from ${pdfs.size} PDFs${if(legal>0)" ($legal read as court rulings)" else ""} · $pages pages sent to DeepSeek${if(dropped>0)" · $dropped dropped by the checks" else ""}${if(review>0)" · $review held back for review" else ""}${if(failed>0)" · $failed parts couldn't be read" else ""}${if(kept>0)" · $kept files keep their previous facts" else ""} · $"+"%.4f".format(java.util.Locale.US,cost),
            "$added datos del grafo de ${pdfs.size} PDF${if(legal>0)" ($legal leídos como fallos)" else ""} · $pages páginas enviadas a DeepSeek${if(dropped>0)" · $dropped descartados por las revisiones" else ""}${if(review>0)" · $review retenidos para revisión" else ""}${if(failed>0)" · $failed partes no se pudieron leer" else ""}${if(kept>0)" · $kept archivos conservan sus datos anteriores" else ""} · $"+"%.4f".format(java.util.Locale.US,cost)))
    }
    /**
     * A PDF read for the graph. [pages] is what extraction reads: the DocLang blocks without running headers and
     * footers, in reading order. [checkPages] also gives a paragraph split by a page break whole on the page where it
     * starts, for checking facts. [doc] places facts on the page. A PDF the reader finds little text in keeps the old
     * page reader (prose, then each rebuilt table row on its own line), and its facts have no place.
     */
    private class GraphText(val doc:DocLang.Doc?,val pages:List<DeepSeekFacts.PageText>,val checkPages:List<DeepSeekFacts.PageText>)
    private fun graphText(s:Source):GraphText=PDDocument.load(File(s.path)).use{pdf->
        val d=runCatching{DocLang.read(pdf)}.getOrNull()?.takeIf{DocPages.usable(it)}
        if(d!=null)GraphText(d,DocPages.pages(d),DocPages.pages(d,joinThreads=true))
        else (1..pdf.numberOfPages).map{p->val read=PdfText.page(pdf,p);DeepSeekFacts.PageText(p,(listOf(read.prose)+read.tables.flatten()).joinToString("\n").trim())}.let{GraphText(null,it,it)}
    }
    /** The text facts are checked against and their evidence is found in. */
    private fun pageTexts(s:Source)=graphText(s).checkPages
    // ---- Review of held-back graph facts ----
    /**
     * [suggest]: for held-back page reasons, the page where the checks would pass. [evidence]: for kept AI facts, the
     * sentence on the page that best supports them. Either reads each document's text once.
     */
    private fun reviewUi(items:List<com.proasiste.lite.graph.Graph.ReviewItem>,suggest:Boolean,evidence:Boolean=suggest):List<UiReviewItem>{
        val titles=store.sources(state.buildingId).associate{it.id to it.title}
        val docs=if(!suggest&&!evidence)emptyMap() else items.map{it.sourceId}.distinct().mapNotNull{id->store.sources(state.buildingId).firstOrNull{it.id==id}}.associate{s->s.id to (s to pageTexts(s))}
        val checkers=if(!suggest)emptyMap() else docs.mapValues{(_,d)->val(s,pages)=d;com.proasiste.lite.runtime.LegalFacts.Checker(pages,com.proasiste.lite.runtime.LegalFacts.thisRuling(pages,s.title)) to pages.map{it.page}}
        return items.map{r->
            val suggested=checkers[r.sourceId]?.let{(checker,pages)->if ("page" !in r.reason && "página" !in r.reason) null else pages.firstOrNull{p->p!=r.fact.page&&checker.check(r.fact.copy(page=p))==com.proasiste.lite.runtime.LegalFacts.Verdict.KEPT}}
            val support=if(!evidence||r.edgeId==null||r.fact.extractor=="reglas")null else docs[r.sourceId]?.second?.firstOrNull{it.page==r.fact.page}?.let{com.proasiste.lite.runtime.LegalFacts.evidence(it.text,r.fact)?:""}
            UiReviewItem(r.id,r.sourceId,titles[r.sourceId]?:r.sourceId,r.fact.page,r.fact.subjectType,r.fact.subject,r.fact.relation,r.fact.objectType,r.fact.obj,r.fact.statement,r.reason,suggested,r.edgeId,r.fact.extractor,support,r.fact.place)
        }
    }
    /** The document open in the viewer, whose pages carry a review status. */
    private var openDocumentId:String?=null
    fun documentOpen(item:UiItem?){openDocumentId=item?.id;refresh()}
    /** Everything the graph says about one document, or one of its pages: held-back facts first, then the kept ones. */
    fun reviewDocument(sourceId:String)=reviewPage(sourceId,null)
    /** Only the facts the checks held back on a document or page: the red link. */
    fun reviewPending(sourceId:String,page:Int?)=reviewPage(sourceId,page,pendingOnly=true)
    /** What the open review covers, so it can be reloaded after a kept fact is replaced. */
    private data class ReviewScope(val sourceId:String,val page:Int?,val pendingOnly:Boolean)
    private var reviewScope:ReviewScope?=null
    fun reviewPage(sourceId:String,page:Int?)=reviewPage(sourceId,page,pendingOnly=false)
    private fun reviewPage(sourceId:String,page:Int?,pendingOnly:Boolean){
        if(state.busy)return
        reviewScope=ReviewScope(sourceId,page,pendingOnly)
        val title=store.sources(state.buildingId).firstOrNull{it.id==sourceId}?.title?:sourceId
        val heading=if(page==null)title else tr("$title · page $page","$title · página $page")
        state=state.copy(review=UiReview(emptyList(),loading=true,pageMode=true,pendingOnly=pendingOnly,title=heading))
        viewModelScope.launch{
            val items=withContext(worker){reviewUi(graph.pageItems(state.buildingId,sourceId,page,pendingOnly),suggest=true)}
            val pending=items.count{it.edgeId==null}
            val subtitle=if(pendingOnly)tr("$pending held back by the checks. Open the page, then approve, correct or reject.","$pending retenidos por las revisiones. Abre la página y aprueba, corrige o rechaza.")
                else if(pending>0)tr("$pending held back by the checks, then ${items.size-pending} facts in the graph. Approve what the page supports; edit or reject the rest.","$pending retenidos por las revisiones, luego ${items.size-pending} datos en el grafo. Aprueba lo que la página respalda; corrige o rechaza el resto.")
                else tr("${items.size} facts in the graph for this ${if(page==null)"document" else "page"}. Approve what the page supports; edit or reject the rest.","${items.size} datos en el grafo para ${if(page==null)"este documento" else "esta página"}. Aprueba lo que la página respalda; corrige o rechaza el resto.")
            state=state.copy(review=UiReview(items,pageMode=true,pendingOnly=pendingOnly,title=heading,subtitle=subtitle))
        }
    }
    /**
     * Approves every kept AI fact still unchecked on [page] of the open review, or in all of it (null). One decision is
     * recorded per fact, with reason "página" for a page just read and "documento" for the rest of a document.
     */
    fun approveRest(page:Int?){
        val targets=state.review?.items?.filter{it.edgeId!=null&&!it.checked&&!it.byRules&&(page==null||it.page==page)}.orEmpty()
        if(targets.isEmpty())return
        val reason=if(page==null)"documento" else "página"
        viewModelScope.launch{
            val done=try{withContext(worker){targets.filter{graph.approveKept(state.buildingId,it.edgeId!!,reason)}.map{it.id}.toSet()}}catch(e:Exception){state=state.copy(error=e.message?.take(200));return@launch}
            state=state.copy(review=state.review?.let{r->r.copy(items=r.items.map{if(it.id in done)it.copy(extractor="revisado") else it})})
        }
    }
    fun openReview(){
        if(state.busy)return
        state=state.copy(review=UiReview(emptyList(),loading=true))
        viewModelScope.launch{
            val items=withContext(worker){reviewUi(graph.pendingReviews(state.buildingId),suggest=true)}
            state=state.copy(review=UiReview(items))
        }
    }
    fun closeReview(){reviewScope=null;state=state.copy(review=null);refresh()}
    fun sampleReview(){
        state=state.copy(review=UiReview(emptyList(),sample=true,loading=true))
        viewModelScope.launch{
            val items=withContext(worker){reviewUi(graph.sampleKept(state.buildingId,REVIEW_SAMPLE),suggest=false,evidence=true)}
            state=state.copy(review=UiReview(items,sample=true))
        }
    }
    /** Runs a decision, then takes the item off the screen, or shows it as [replaced] (a kept fact that stays, now reviewed). */
    private fun decide(item:UiReviewItem,replaced:UiReviewItem?=null,block:()->Boolean){
        viewModelScope.launch{
            val ok=try{withContext(worker){block()}}catch(e:Exception){state=state.copy(error=e.message?.take(200));false}
            if(ok)state=state.copy(review=state.review?.let{r->r.copy(items=if(replaced!=null)r.items.map{if(it.id==item.id)replaced else it} else r.items.filter{it.id!=item.id})})
        }
    }
    private fun factOf(item:UiReviewItem)=com.proasiste.lite.graph.NewFact(item.page,item.fromType.trim(),item.from.trim(),item.relation.trim(),item.toType.trim(),item.to.trim(),item.statement.trim())
    fun approveReview(item:UiReviewItem)=
        if(item.edgeId!=null)decide(item,item.copy(extractor="revisado")){graph.approveKept(state.buildingId,item.edgeId)}
        else decide(item){graph.approveReview(state.buildingId,item.id,null){embedder.embedText(it)}}
    fun correctReview(item:UiReviewItem){
        if(item.edgeId==null){decide(item){graph.approveReview(state.buildingId,item.id,factOf(item)){embedder.embedText(it)}};return}
        // The corrected fact is a new link with a new id, so the page's list is read again rather than patched.
        viewModelScope.launch{
            try{withContext(worker){graph.correctKept(state.buildingId,item.edgeId,factOf(item)){embedder.embedText(it)}}}catch(e:Exception){state=state.copy(error=e.message?.take(200));return@launch}
            reviewScope?.let{reviewPage(it.sourceId,it.page,it.pendingOnly)}
        }
    }
    fun rejectReview(item:UiReviewItem)=decide(item){
        if(item.edgeId!=null)graph.rejectKept(state.buildingId,item.edgeId,reason=if(state.review?.sample==true)"muestra" else "página") else graph.rejectReview(item.id)
    }
    /** The item's document as the viewer shows it, open at the item's page. */
    /**
     * The item's document as the viewer shows it, open at the item's page. It highlights the fact's stored place, or else
     * the sentence that supports it, or else its own words. A place is dropped when the steward moved the fact to another page.
     */
    fun reviewPageItem(item:UiReviewItem):UiItem?=store.sources(state.buildingId).firstOrNull{it.id==item.sourceId}
        ?.ui(null,item.evidence?.takeIf{it.isNotBlank()}?:item.statement,tr("Page ${item.page}","Página ${item.page}"))
        ?.copy(highlight=item.place.takeIf{p->DocPages.parse(p).any{it.first==item.page}}.orEmpty())
    /** The key to use for the current project, or null when nothing may leave the phone. */
    /** The saved OpenRouter key for the current project, or null when nothing may leave the phone. */
    private fun cloudKey():String?=if(state.projects.firstOrNull{it.id==state.buildingId}?.incognito==true)null else jevVault.load()
    /** The key for Jev's checks: only when the user has also turned Jev on. Off by default. */
    private fun jevKey():String?=if(state.jevEnabled)cloudKey() else null
    fun setJevEnabled(on:Boolean){prefs.edit().putBoolean("jev-enabled",on).apply();stopAnswer();state=state.copy(jevEnabled=on)}
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
    /** Answers a list question with the graph's facts themselves. No model writes it, so nothing can be left out or added, and there is nothing for Jev to check. */
    private fun listAnswer(facts:List<com.proasiste.lite.graph.GraphFact>){
        stopAnswer()
        val all=store.sources(state.buildingId).associateBy{it.id}
        val usable=facts.filter{it.sourceId in all}
        val sources=usable.map{f->all.getValue(f.sourceId).ui(null,f.fact,tr("Page ${f.page} · graph fact","Página ${f.page} · dato del grafo")).copy(highlight=f.place)}
        state=state.copy(answer=UiAnswer(text=usable.withIndex().joinToString("\n"){(i,f)->"${f.fact} [${i+1}]"},sources=sources,working=false,fromGraph=true))
    }
    /** Runs after the search so the list is usable meanwhile: Jev reranks when allowed, Gemini Nano writes a short cited answer, then Jev checks its citations. */
    private fun answer(question:String,result:SearchResult){
        stopAnswer()
        if(result.passages.isEmpty())return
        val incognito=state.projects.firstOrNull{it.id==state.buildingId}?.incognito==true
        val offNote=if(incognito&&state.jevKeySaved&&state.jevEnabled)tr("Jev is off in incognito projects. Nothing left this phone.", "Jev está desactivado en proyectos incógnito. Nada salió de este teléfono.") else ""
        state=state.copy(answer=UiAnswer(jevNote=offNote))
        answerJob=viewModelScope.launch{
            val passages=rerank(question,result)?:return@launch
            // A graph fact was written from the page, not quoted from it, so its citation says so.
            val sources=passages.map{it.source.ui(it.score,it.chunk.text,if(it.chunk.ordinal==GRAPH_FACT_ORDINAL)tr("Page ${it.chunk.page} · graph fact", "Página ${it.chunk.page} · dato del grafo") else tr("Page ${it.chunk.page}", "Página ${it.chunk.page}"))}
            state=state.copy(answer=state.answer?.copy(sources=sources))
            // With Jev on, the answer stays hidden until it's checked, so a sentence that gets removed is never shown.
            val key=jevKey();var written=""
            val given=passages.map{NanoAnswerer.Passage(it.source.title,it.chunk.page,it.chunk.text)}
            val onText:(String)->Unit={t->written=NanoAnswerer.fixCitations(t,passages.size);if(key==null)state=state.copy(answer=state.answer?.copy(text=written))}
            // The project's chosen model writes the answer when it may: a key is saved and the project isn't incognito.
            // Otherwise, or if it fails, Gemini Nano answers on the phone and the card says why.
            val chosen=state.projects.firstOrNull{it.id==state.buildingId}?.answerModel.orEmpty()
            val cloudKey=if(chosen.isNotEmpty())cloudKey() else null
            var wroteInCloud=false
            if(chosen.isNotEmpty()&&cloudKey==null)state=state.copy(answer=state.answer?.copy(writerNote=
                if(incognito)tr("Incognito project: Gemini Nano answered on this phone and nothing left it.","Proyecto incógnito: Gemini Nano respondió en este teléfono y nada salió de él.")
                else tr("No OpenRouter key saved, so Gemini Nano answered on this phone.","No hay clave de OpenRouter guardada, así que Gemini Nano respondió en este teléfono.")))
            if(cloudKey!=null){
                val name=CloudAnswerer.displayName(chosen,catalogModels)
                state=state.copy(answer=state.answer?.copy(writer=name,cloud=true,status=tr("$name is reading ${passages.size} passages…", "$name está leyendo ${passages.size} pasajes…")))
                try{
                    val r=cloud.answer(cloudKey,chosen,question,given,onText)
                    wroteInCloud=true
                    val cost=if(r.cost>0)" · $"+"%.4f".format(java.util.Locale.US,r.cost) else ""
                    state=state.copy(answer=state.answer?.copy(writerNote=tr("Your question and ${passages.size} passages went to OpenRouter${if(r.provider.isNotBlank())" (${r.provider})" else ""}$cost","Tu pregunta y ${passages.size} pasajes pasaron por OpenRouter${if(r.provider.isNotBlank())" (${r.provider})" else ""}$cost")))
                }catch(e:CancellationException){throw e}
                catch(e:Exception){
                    written=""
                    state=state.copy(answer=state.answer?.copy(text="",writer="Gemini Nano",cloud=false,writerFailed=true,
                        writerNote=tr("$name couldn't answer (${e.message?.take(120)?:"unknown error"}), so Gemini Nano answered on this phone.","$name no pudo responder (${e.message?.take(120)?:"error desconocido"}), así que Gemini Nano respondió en este teléfono.")))
                }
            }
            if(!wroteInCloud){
                state=state.copy(answer=state.answer?.copy(status=tr("Gemini Nano is reading ${passages.size} passages…", "Gemini Nano está leyendo ${passages.size} pasajes…")))
                try{nano.answer(question,given,onStatus={s->state=state.copy(answer=state.answer?.copy(status=s))},onText=onText)}
                catch(e:CancellationException){throw e}
                catch(e:Exception){state=state.copy(answer=state.answer?.copy(working=false,status=tr("Gemini Nano could not answer: ","Gemini Nano no pudo responder: ")+(e.message?.take(160)?:tr("unknown error","error desconocido"))));return@launch}
            }
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
        val bySource=tags.bySource(state.buildingId)
        unfilteredSearchItems=hits.map{h->h.source.ui(h.score,sourceExcerpt(h.chunk.text,state.query),when(h.source.type){"pdf"->tr("Page ${h.chunk.page}", "Página ${h.chunk.page}");"audio"->"${h.chunk.startMs/60000}:${((h.chunk.startMs/1000)%60).toString().padStart(2,'0')}";else->tr("Original photo", "Foto original")}).copy(tags=bySource[h.source.id].orEmpty())}
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
    // The store, model and worker belong to the process (Engine), and the background indexer may still be using them.
    override fun onCleared(){answerJob?.cancel();nano.close();super.onCleared()}
    companion object {
        /** At most this many graph facts join the passages for one answer. */
        const val GRAPH_FACTS=3
        /** Background progress reloads the Library at most this often. */
        const val PROGRESS_REFRESH_MS=3000L
        /** How many kept AI facts a spot check draws. */
        const val REVIEW_SAMPLE=10
        /** The most facts a list answer shows. */
        const val LIST_LIMIT=40
        /** Marks a passage that is a graph fact rather than a chunk of the file. */
        const val GRAPH_FACT_ORDINAL=-1
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
                (filters.tags.isEmpty() || item.tags.containsAll(filters.tags)) &&
                ((filters.dateFrom.isEmpty() && filters.dateTo.isEmpty()) ||
                    (validDate(item.date) != null && (filters.dateFrom.isEmpty() || item.date >= filters.dateFrom) &&
                        (filters.dateTo.isEmpty() || item.date <= filters.dateTo)))
        }
        /** Projects as stored in preferences, or null when [raw] can't be read. */
        fun projectsFromJson(raw:String):List<UiProject>? =runCatching{val a=JSONArray(raw);(0 until a.length()).map{a.getJSONObject(it).let{j->UiProject(j.getString("id"),j.getString("name"),j.optBoolean("incognito",false),j.optString("answer_model",""))}}}.getOrNull()?.takeIf{it.isNotEmpty()}
        fun projectsToJson(projects:List<UiProject>):String =JSONArray(projects.map{JSONObject().put("id",it.id).put("name",it.name).put("incognito",it.incognito).put("answer_model",it.answerModel)}).toString()
        fun copyBounded(input:java.io.InputStream,out:java.io.OutputStream,limit:Long){val buf=ByteArray(65536);var count=0L;while(true){val n=input.read(buf);if(n<0)break;count+=n;require(count<=limit){tr("File exceeds import size limit", "El archivo supera el límite de tamaño de importación")};out.write(buf,0,n)}}
    }
}
