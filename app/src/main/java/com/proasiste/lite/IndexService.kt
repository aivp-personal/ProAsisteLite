package com.proasiste.lite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.proasiste.lite.runtime.CloudAnswerer
import com.proasiste.lite.runtime.CloudTagger
import com.proasiste.lite.runtime.KeyVault
import com.proasiste.lite.tags.TagJob
import com.proasiste.lite.tags.Tags
import com.proasiste.lite.actions.Action
import com.proasiste.lite.actions.ActionJob
import com.proasiste.lite.actions.Actions
import com.proasiste.lite.runtime.CloudAction
import com.proasiste.lite.runtime.DocLang
import com.proasiste.lite.ui.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Imports and indexes in the background, so thousands of files can be processed with the screen off or the app closed.
 * Work is read from the database each time (the import queue, then unindexed sources), so nothing is lost if Android
 * stops the service: it restarts on its own when it can, and the app starts it again on its next launch. Only projects
 * the user asked to process are touched. Pause finishes the current file and stops.
 */
class IndexService:Service(){
    enum class Phase{IMPORT,INDEX,TAG,ACT}
    /**
     * Where a run stands. Copying, indexing and AI tagging are counted apart, in files: [step] of [steps] in the current
     * phase. [done] of [total] covers all of them, for the progress bar; totals are done plus still waiting, so they grow if
     * a folder is added.
     */
    data class Progress(val done:Int,val total:Int,val title:String,val phase:Phase,val pausing:Boolean=false,val step:Int=done+1,val steps:Int=total){
        val fraction:Float get()=if(total<=0)0f else done.toFloat()/total
        val label:String get()=when(phase){
            Phase.IMPORT->tr("Importing $step of $steps · $title","Importando $step de $steps · $title")
            Phase.INDEX->tr("Indexing $step of $steps · $title","Indexando $step de $steps · $title")
            Phase.TAG->tr("Tagging with AI $step of $steps · $title","Etiquetando con IA $step de $steps · $title")
            Phase.ACT->tr("Action $step of $steps · $title","Acción $step de $steps · $title")
        }
    }

    private val scope=CoroutineScope(SupervisorJob())
    private val thread=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var job:Job?=null
    private var wakeLock:PowerManager.WakeLock?=null
    private var lastNotified=0L

    override fun onBind(intent:Intent?):IBinder?=null

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==ACTION_PAUSE){pause(this);return START_NOT_STICKY}
        try{goForeground(notification(tr("Preparing…","Preparando…"),null))}
        // Android may refuse a restart from the background; the app picks the work up on its next launch.
        catch(e:Exception){stopSelf();return START_NOT_STICKY}
        if(job?.isActive!=true)job=scope.launch(thread){run()}
        return START_STICKY
    }

    override fun onDestroy(){scope.cancel();thread.close();wakeLock?.takeIf{it.isHeld}?.release();super.onDestroy()}

    private suspend fun run(){
        val engine=Engine.get(this);val store=engine.store;val tags=Tags(store);val actions=Actions(store)
        val acts=ActCount()
        pausing.set(false)
        wakeLock=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ProAsisteLite:index").apply{setReferenceCounted(false);acquire()}
        var imported=0;var duplicates=0;var importFailed=0;var indexed=0;var failed=0;var scans=0;var done=0
        val ai=AiCount()
        var modelMissing=false
        try{
            val modelReady=try{engine.ensureModel()}catch(e:Exception){false}
            while(!pausing.get()){
                val projects=requested(this)
                val q=store.nextImport(projects)
                if(q!=null){
                    publish(store,tags,projects,done,q.name,Phase.IMPORT,imported+duplicates+importFailed)
                    try{
                        val (status,note)=FolderImport.copyIn(this,store,q)
                        if(status==Store.IMPORTED){store.finishImport(q.id,status);tags.applyRules(q.building,listOf(note));imported++}
                        else{store.finishImport(q.id,status,note);duplicates++}
                    }catch(e:Exception){store.finishImport(q.id,Store.FAILED,e.message?:"");importFailed++}
                    done++;continue
                }
                val s=store.nextToIndex(projects)
                if(s!=null&&!modelReady)modelMissing=true
                if(s!=null&&modelReady){
                    publish(store,tags,projects,done,s.title,Phase.INDEX,indexed+failed+scans)
                    try{val e=engine.encode(s);store.saveChunks(s,e.chunks,e.pages,e.noTextPages);indexed++}
                    catch(e:Engine.NeedsOcr){store.markNeedsOcr(s.id,e.pages,e.message?:"");scans++}
                    catch(e:Exception){store.error(s.id,e.message?:tr("Could not process source","No se pudo procesar la fuente"));failed++}
                    done++;continue
                }
                // AI tagging after indexing: it reads indexed files and doesn't need the search model.
                val job=tags.nextForAi(projects)
                if(job!=null){
                    val title=store.sources(job.building).firstOrNull{it.id==job.sourceId}?.title?:job.sourceId
                    publish(store,tags,projects,done,title,Phase.TAG,ai.tagged+ai.failed)
                    tagWithAi(engine,tags,job,ai)
                    done++;continue
                }
                // Actions last: each file's result is added to its run's report.
                val act=actions.next(projects)?:break
                val title=store.sources(act.run.building).firstOrNull{it.id==act.sourceId}?.title?:act.sourceId
                publish(store,tags,projects,done,"${act.run.title} · $title",Phase.ACT,acts.done+acts.failed)
                runAction(engine,act,acts)
                done++
            }
            val paused=pausing.get()
            // Finished (or nothing can go on without the model): forget the request, so the next launch doesn't restart it.
            if(!paused)prefs(this).edit().remove(PROJECTS).apply()
            result.value=summary(paused,modelMissing,imported,duplicates,importFailed,indexed,failed,scans,ai,acts)
        }finally{
            wakeLock?.takeIf{it.isHeld}?.release()
            progress.value=null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** What AI tagging did in this run. */
    class AiCount{var tagged=0;var failed=0;var cost=0.0;var stopped=""}
    /** What actions did in this run. */
    class ActCount{var done=0;var failed=0;var cost=0.0;var stopped=""}
    private val cloudAction=CloudAction()

    /**
     * Runs one action on one file and adds the result to the run's report. Converting to DocLang writes the file into the
     * run's folder on the phone. AI actions need the project's OpenRouter model and the key, never run in an incognito
     * project, and stop the project's runs at once if OpenRouter refuses the key or the account has no credit.
     */
    private suspend fun runAction(engine:Engine,job:ActionJob,acts:ActCount){
        val actions=Actions(engine.store)
        val source=engine.store.sources(job.run.building).firstOrNull{it.id==job.sourceId}
        if(source==null){actions.finish(job,job.sourceId,tr("the file was deleted","el archivo fue eliminado"),failed=true);acts.failed++;return}
        if(job.run.action==Action.DOCLANG){
            try{
                if(source.type!="pdf")error(tr("not a PDF","no es un PDF"))
                val tree=android.net.Uri.parse(job.run.folder)
                val parent=android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,android.provider.DocumentsContract.getTreeDocumentId(tree))
                val name=com.proasiste.lite.reports.Export.fileName(source.title.removeSuffix(".pdf").removeSuffix(".PDF"),"doclang")
                val xml=com.tom_roush.pdfbox.pdmodel.PDDocument.load(java.io.File(source.path)).use{DocLang.toXml(DocLang.read(it))}
                val file=android.provider.DocumentsContract.createDocument(contentResolver,parent,"application/octet-stream",name)?:error(tr("couldn't create the file","no se pudo crear el archivo"))
                contentResolver.openOutputStream(file)!!.use{it.write(xml.toByteArray())}
                actions.finish(job,source.title,tr("Saved as $name","Guardado como $name"));acts.done++
            }catch(e:Exception){actions.finish(job,source.title,e.message?:"",failed=true);acts.failed++}
            return
        }
        val prefs=prefs(this)
        val project=PlantroomModel.projectsFromJson(prefs.getString("projects","")?:"")?.firstOrNull{it.id==job.run.building}
        val key=KeyVault(prefs).load()
        val problem=when{
            project==null->tr("project not found","no se encontró el proyecto")
            project.incognito->tr("${job.run.building} is incognito, so nothing is sent","${job.run.building} es incógnito, así que no se envía nada")
            project.answerModel.isEmpty()->tr("choose an OpenRouter model for ${job.run.building} first","primero elige un modelo de OpenRouter para ${job.run.building}")
            key==null->tr("save your OpenRouter key first","primero guarda tu clave de OpenRouter")
            else->null
        }
        if(problem!=null){actions.finish(job,source.title,problem,failed=true);actions.cancel(job.run.building);acts.stopped=problem;return}
        try{
            val pages=engine.pagesForAi(source)
            if(pages.none{it.text.isNotBlank()})error(tr("no text to read","no hay texto que leer"))
            val r=cloudAction.run(key!!,project!!.answerModel,job.run.instructions,source.title,pages)
            actions.finish(job,source.title,r.text);acts.done++;acts.cost+=r.cost
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){
            actions.finish(job,source.title,e.message?:"",failed=true);acts.failed++
            if((e as? CloudAnswerer.CloudException)?.code in setOf(401,402,403)){actions.cancel(job.run.building);acts.stopped=e.message?:""}
        }
    }

    /**
     * Tags one file with the project's OpenRouter model, choosing only from the project's described tags. A project that
     * is incognito, has no cloud model or no key, or whose key OpenRouter refuses, stops AI tagging and says why.
     */
    private suspend fun tagWithAi(engine:Engine,tags:Tags,job:TagJob,ai:AiCount){
        val prefs=prefs(this)
        val project=PlantroomModel.projectsFromJson(prefs.getString("projects","")?:"")?.firstOrNull{it.id==job.building}
        val key=KeyVault(prefs).load()
        val defs=tags.definitions(job.building)
        val problem=when{
            project==null->tr("project not found","no se encontró el proyecto")
            project.incognito->tr("${job.building} is incognito, so nothing is sent","${job.building} es incógnito, así que no se envía nada")
            project.answerModel.isEmpty()->tr("choose an OpenRouter model for ${job.building} first","primero elige un modelo de OpenRouter para ${job.building}")
            key==null->tr("save your OpenRouter key first","primero guarda tu clave de OpenRouter")
            defs.isEmpty()->tr("describe at least one tag first","primero describe al menos una etiqueta")
            else->null
        }
        if(problem!=null){tags.cancelAi(job.building);ai.stopped=problem;return}
        val source=engine.store.sources(job.building).firstOrNull{it.id==job.sourceId}
        if(source==null){tags.finishAi(job.sourceId,Store.FAILED,"gone");return}
        try{
            val text=engine.firstPages(source)
            val r=tagger.tag(key!!,project!!.answerModel,source.title,text,defs)
            tags.replace(source.id,job.building,Tags.AI,r.tags)
            tags.finishAi(source.id,Tags.DONE,r.tags.joinToString(", "))
            ai.tagged++;ai.cost+=r.cost
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){
            tags.finishAi(source.id,Store.FAILED,e.message?:"");ai.failed++
            // A refused key or an empty account fails every file the same way: stop instead of trying them all.
            if((e as? CloudAnswerer.CloudException)?.code in setOf(401,402,403)){tags.cancelAi(job.building);ai.stopped=e.message?:""}
        }
    }
    private val tagger=CloudTagger()

    /** [phaseDone] is how many files this run has finished in the current phase. */
    private fun publish(store:Store,tags:Tags,projects:Set<String>,done:Int,title:String,phase:Phase,phaseDone:Int){
        val (queued,unindexed)=store.pendingParts(projects)
        val aiWaiting=tags.pendingAi(projects);val actWaiting=Actions(store).pending(projects)
        val waiting=when(phase){Phase.IMPORT->queued;Phase.INDEX->unindexed;Phase.TAG->aiWaiting;Phase.ACT->actWaiting}
        val p=Progress(done,done+queued+unindexed+aiWaiting+actWaiting,title,phase,pausing.get(),phaseDone+1,phaseDone+waiting)
        progress.value=p
        val now=System.currentTimeMillis()
        if(now-lastNotified>2000){lastNotified=now
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(p.label,p))}
    }

    private fun goForeground(n:Notification){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.UPSIDE_DOWN_CAKE)startForeground(NOTIFICATION,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION,n)
    }

    private fun notification(text:String,p:Progress?):Notification{
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,tr("Importing and indexing","Importación e indexación"),NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE)
        val pause=PendingIntent.getService(this,1,Intent(this,IndexService::class.java).setAction(ACTION_PAUSE),PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("ProAsisteLite").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setProgress(1000,((p?.fraction?:0f)*1000).toInt(),p==null)
            .addAction(Notification.Action.Builder(null,tr("Pause","Pausar"),pause).build()).build()
    }

    companion object {
        private const val CHANNEL="indexing";private const val NOTIFICATION=7;private const val ACTION_PAUSE="pause"
        private const val PROJECTS="index-projects"
        private val pausing=AtomicBoolean(false)
        private val progress=MutableStateFlow<Progress?>(null)
        /** The current run, or null when nothing is running. */
        val state:StateFlow<Progress?> get()=progress
        /** What the last run did, in plain words, for the status line. */
        val result=MutableStateFlow<String?>(null)
        private fun prefs(c:Context)=c.getSharedPreferences("plantroom",0)
        /** Projects the user asked to import and index into, kept until their work is done or paused. */
        fun requested(c:Context):Set<String> =prefs(c).getStringSet(PROJECTS,emptySet())!!.toSet()
        /** Asks for [project]'s queued and unindexed files to be processed, and starts the service if it isn't running. */
        fun start(c:Context,project:String?=null){
            if(project!=null)prefs(c).edit().putStringSet(PROJECTS,requested(c)+project).apply()
            if(requested(c).isEmpty())return
            pausing.set(false);result.value=null
            c.startForegroundService(Intent(c,IndexService::class.java))
        }
        /** Finishes the current file, then stops. Nothing restarts it until the user asks again. */
        fun pause(c:Context){
            prefs(c).edit().remove(PROJECTS).apply()
            pausing.set(true)
            progress.value=progress.value?.copy(pausing=true)
        }
        fun summary(paused:Boolean,modelMissing:Boolean,imported:Int,duplicates:Int,importFailed:Int,indexed:Int,failed:Int,scans:Int,ai:AiCount=AiCount(),acts:ActCount=ActCount()):String{
            val parts=mutableListOf<String>()
            fun n(count:Int,en1:String,enN:String,es1:String,esN:String)=if(count==1)tr("1 $en1","1 $es1") else tr("$count $enN","$count $esN")
            if(imported>0)parts+=n(imported,"imported","imported","importado","importados")
            if(duplicates>0)parts+=n(duplicates,"duplicate skipped","duplicates skipped","duplicado omitido","duplicados omitidos")
            if(importFailed>0)parts+=n(importFailed,"couldn't be copied","couldn't be copied","no se pudo copiar","no se pudieron copiar")
            if(indexed>0)parts+=n(indexed,"indexed","indexed","indexado","indexados")
            if(scans>0)parts+=n(scans,"scanned PDF needs OCR","scanned PDFs need OCR","PDF escaneado necesita OCR","PDF escaneados necesitan OCR")
            if(failed>0)parts+=n(failed,"failed; the Library shows why","failed; the Library shows why","falló; la Biblioteca muestra el motivo","fallaron; la Biblioteca muestra el motivo")
            if(ai.tagged>0)parts+=n(ai.tagged,"tagged by AI","tagged by AI","etiquetado con IA","etiquetados con IA")+(if(ai.cost>0)" ($"+"%.4f".format(java.util.Locale.US,ai.cost)+")" else "")
            if(ai.failed>0)parts+=n(ai.failed,"couldn't be tagged by AI","couldn't be tagged by AI","no se pudo etiquetar con IA","no se pudieron etiquetar con IA")
            if(ai.stopped.isNotBlank())parts+=tr("AI tagging stopped: ${ai.stopped}","Etiquetado con IA detenido: ${ai.stopped}")
            if(acts.done>0)parts+=n(acts.done,"file done by an action","files done by actions","archivo hecho por una acción","archivos hechos por acciones")+(if(acts.cost>0)" ($"+"%.4f".format(java.util.Locale.US,acts.cost)+")" else "")+tr(" · see Reports"," · ver Informes")
            if(acts.failed>0)parts+=n(acts.failed,"failed in an action","failed in actions","falló en una acción","fallaron en acciones")
            if(acts.stopped.isNotBlank())parts+=tr("Actions stopped: ${acts.stopped}","Acciones detenidas: ${acts.stopped}")
            val head=when{paused->tr("Paused · finished files are saved","Pausado · los archivos terminados quedan guardados");modelMissing->tr("Import the model to index","Importa el modelo para indexar");else->tr("Done","Listo")}
            return (listOf(head)+parts).joinToString(" · ")
        }
    }
}
