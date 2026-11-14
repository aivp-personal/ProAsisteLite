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
    /**
     * Where a run stands. Copying and indexing are counted apart, in files: [step] of [steps] in the current phase.
     * [done] of [total] covers both, for the progress bar; totals are done plus still waiting, so they grow if a folder is added.
     */
    data class Progress(val done:Int,val total:Int,val title:String,val importing:Boolean,val pausing:Boolean=false,val step:Int=done+1,val steps:Int=total){
        val fraction:Float get()=if(total<=0)0f else done.toFloat()/total
        val label:String get()=if(importing)tr("Importing $step of $steps · $title","Importando $step de $steps · $title") else tr("Indexing $step of $steps · $title","Indexando $step de $steps · $title")
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

    private fun run(){
        val engine=Engine.get(this);val store=engine.store
        pausing.set(false)
        wakeLock=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ProAsisteLite:index").apply{setReferenceCounted(false);acquire()}
        var imported=0;var duplicates=0;var importFailed=0;var indexed=0;var failed=0;var scans=0;var done=0
        var modelMissing=false
        try{
            val modelReady=try{engine.ensureModel()}catch(e:Exception){false}
            while(!pausing.get()){
                val projects=requested(this)
                val q=store.nextImport(projects)
                if(q!=null){
                    publish(store,projects,done,q.name,importing=true,imported+duplicates+importFailed)
                    try{val (status,note)=FolderImport.copyIn(this,store,q);store.finishImport(q.id,status,note);if(status==Store.IMPORTED)imported++ else duplicates++}
                    catch(e:Exception){store.finishImport(q.id,Store.FAILED,e.message?:"");importFailed++}
                    done++;continue
                }
                val s=store.nextToIndex(projects)?:break
                if(!modelReady){modelMissing=true;break}
                publish(store,projects,done,s.title,importing=false,indexed+failed+scans)
                try{val e=engine.encode(s);store.saveChunks(s,e.chunks,e.pages,e.noTextPages);indexed++}
                catch(e:Engine.NeedsOcr){store.markNeedsOcr(s.id,e.pages,e.message?:"");scans++}
                catch(e:Exception){store.error(s.id,e.message?:tr("Could not process source","No se pudo procesar la fuente"));failed++}
                done++
            }
            val paused=pausing.get()
            // Finished (or nothing can go on without the model): forget the request, so the next launch doesn't restart it.
            if(!paused)prefs(this).edit().remove(PROJECTS).apply()
            result.value=summary(paused,modelMissing,imported,duplicates,importFailed,indexed,failed,scans)
        }finally{
            wakeLock?.takeIf{it.isHeld}?.release()
            progress.value=null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** [phaseDone] is how many files this run has finished in the current phase (copying or indexing). */
    private fun publish(store:Store,projects:Set<String>,done:Int,title:String,importing:Boolean,phaseDone:Int){
        val (queued,unindexed)=store.pendingParts(projects)
        val p=Progress(done,done+queued+unindexed,title,importing,pausing.get(),phaseDone+1,phaseDone+if(importing)queued else unindexed)
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
        fun summary(paused:Boolean,modelMissing:Boolean,imported:Int,duplicates:Int,importFailed:Int,indexed:Int,failed:Int,scans:Int):String{
            val parts=mutableListOf<String>()
            fun n(count:Int,en1:String,enN:String,es1:String,esN:String)=if(count==1)tr("1 $en1","1 $es1") else tr("$count $enN","$count $esN")
            if(imported>0)parts+=n(imported,"imported","imported","importado","importados")
            if(duplicates>0)parts+=n(duplicates,"duplicate skipped","duplicates skipped","duplicado omitido","duplicados omitidos")
            if(importFailed>0)parts+=n(importFailed,"couldn't be copied","couldn't be copied","no se pudo copiar","no se pudieron copiar")
            if(indexed>0)parts+=n(indexed,"indexed","indexed","indexado","indexados")
            if(scans>0)parts+=n(scans,"scanned PDF needs OCR","scanned PDFs need OCR","PDF escaneado necesita OCR","PDF escaneados necesitan OCR")
            if(failed>0)parts+=n(failed,"failed; the Library shows why","failed; the Library shows why","falló; la Biblioteca muestra el motivo","fallaron; la Biblioteca muestra el motivo")
            val head=when{paused->tr("Paused · finished files are saved","Pausado · los archivos terminados quedan guardados");modelMissing->tr("Import the model to index","Importa el modelo para indexar");else->tr("Done","Listo")}
            return (listOf(head)+parts).joinToString(" · ")
        }
    }
}
