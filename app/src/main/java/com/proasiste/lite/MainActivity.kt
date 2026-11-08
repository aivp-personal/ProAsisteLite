package com.proasiste.lite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.proasiste.lite.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity:ComponentActivity(){
    private val model:PlantroomModel by viewModels()
    private var player:MediaPlayer?=null
    private var recorder:MediaRecorder?=null
    private var recordingFile:File?=null
    private var photoUri:Uri?=null
    private var photoIsImport=false
    private var photoChoices by mutableStateOf(false)
    private var document by mutableStateOf<UiItem?>(null)
    private var originalPhoto by mutableStateOf<UiItem?>(null)
    private val modelPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(model::importModel)}
    private val packPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(model::importPack)}
    private val filesPicker=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){if(it.isNotEmpty())model.importFiles(it)}
    private val photoPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(model::imageSearch)}
    private val camera=registerForActivityResult(ActivityResultContracts.TakePicture()){ok->if(ok)photoUri?.let{if(photoIsImport)model.importFiles(listOf(it))else model.imageSearch(it)}}
    private val microphone=registerForActivityResult(ActivityResultContracts.RequestPermission()){if(it)startRecording()else model.messageError(tr("Microphone permission is needed to record a note. You can still import recordings.", "Se necesita permiso del micrófono para grabar una nota. Aún puedes importar grabaciones."))}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        photoUri=savedInstanceState?.getString("photoUri")?.let(Uri::parse)
        photoIsImport=savedInstanceState?.getBoolean("photoIsImport")?:false
        setContent{
            PlantroomScreen(model.state,UiActions(
                onBuilding={stopAudio();model.setBuilding(it)},onCreateProject={number,name->stopAudio();model.createProject(number,name)},onDeleteProject={stopAudio();model.deleteProject(it)},
                onToggleIncognito=model::toggleIncognito,onLanguage=model::setSpanish,onDeleteSource={stopAudio();model.deleteSource(it)},
                onShareAnswer=model::shareAnswer,onDismissShare=model::dismissShare,
                onSaveJevKey=model::saveJevKey,onRemoveJevKey=model::removeJevKey,
                
                onConfirmShare={text->model.dismissShare();startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),tr("Share answer", "Compartir respuesta")))},onQuery=model::setQuery,onSearch=model::search,
                onImportModel={modelPicker.launch(arrayOf("*/*"))},onImportCorpus={packPicker.launch(arrayOf("application/zip","application/octet-stream"))},
                onImportFiles={filesPicker.launch(arrayOf("image/*","audio/*","application/pdf"))},
                onImageSearch={photoChoices=true},onRecordAudio={if(recorder!=null)stopRecording()else if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startRecording()else microphone.launch(Manifest.permission.RECORD_AUDIO)},
                onCapturePhoto={takePhoto(true)},
                onOpenSource={if(it.kind==UiKind.REPORT)document=it else if(it.kind==UiKind.AUDIO)playAudio(it) else originalPhoto=it},
                onPlayAudio=::playAudio,onStopAudio=::stopAudio,onDismissError=model::dismissError,
                onIndex=model::index,onCancelIndex=model::cancel,
                onApplyFilters=model::applyFilters,onKindFilter=model::setKindFilter,onResetFilters=model::resetFilters
            ))
            if(photoChoices)MaterialTheme(colorScheme=darkColorScheme(),typography=BrandTypography){
                AlertDialog(onDismissRequest={photoChoices=false},title={Text(tr("Search with a photo", "Buscar con una foto"))},text={Text(tr("Find visually related evidence in the selected project.", "Encuentra evidencia visualmente similar en el proyecto seleccionado."))},confirmButton={TextButton(onClick={photoChoices=false;photoPicker.launch(arrayOf("image/*"))}){Text(tr("Choose photo", "Elegir foto"))}},dismissButton={TextButton(onClick={photoChoices=false;takePhoto()}){Text(tr("Use camera", "Usar cámara"))}})
            }
            document?.let{item->PdfViewer(item){document=null}}
            originalPhoto?.let{item->PhotoViewer(item){originalPhoto=null}}
            LaunchedEffect(model.state.recording){if(model.state.recording){delay(55_000);if(recorder!=null)stopRecording()}}
        }
    }
    private fun takePhoto(saveToLibrary:Boolean=false){
        try{photoIsImport=saveToLibrary;val dir=File(cacheDir,"capture").apply{mkdirs()};val file=File(dir,"photo-${System.currentTimeMillis()}.jpg");photoUri=FileProvider.getUriForFile(this,"$packageName.files",file);camera.launch(photoUri!!)}catch(e:Exception){model.messageError(tr("Camera unavailable. Choose an existing photo instead.", "Cámara no disponible. Elige una foto existente."))}
    }
    private fun startRecording(){
        if(model.state.busy)return
        try{stopAudio();val f=File(cacheDir,"note-${System.currentTimeMillis()}.m4a");recordingFile=f
            recorder=MediaRecorder(this).apply{setAudioSource(MediaRecorder.AudioSource.MIC);setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);setAudioEncoder(MediaRecorder.AudioEncoder.AAC);setAudioSamplingRate(16000);setAudioChannels(1);setAudioEncodingBitRate(64000);setOutputFile(f.path);prepare();start()};model.recording(true)
        }catch(e:Exception){recorder?.release();recorder=null;model.recording(false);model.messageError(tr("Could not start recording: ${e.message}", "No se pudo iniciar la grabación: ${e.message}"))}
    }
    private fun stopRecording(){
        val active=recorder?:return;recorder=null
        try{active.stop();active.release();model.recording(false);recordingFile?.let(model::importRecording)}catch(e:Exception){try{active.release()}catch(_:Exception){};recordingFile?.delete();model.recording(false);model.messageError(tr("Recording was too short. Please try again.", "La grabación fue demasiado corta. Inténtalo de nuevo."))}
        recordingFile=null
    }
    private fun playAudio(item:UiItem){
        if(model.state.playingId==item.id){stopAudio();return}
        try{stopAudio();player=MediaPlayer().apply{setDataSource(item.localPath);setOnPreparedListener{p->val parts=item.sourceLocation.split(':');val seconds=if(parts.size==2)(parts[0].toIntOrNull()?:0)*60+(parts[1].toIntOrNull()?:0)else 0;p.seekTo(seconds*1000);p.start();model.playback(item.id)};setOnCompletionListener{stopAudio()};setOnErrorListener{_,_,_->stopAudio();model.messageError(tr("This recording could not be played.", "No se pudo reproducir esta grabación."));true};prepareAsync()}}catch(e:Exception){model.messageError(tr("Could not open recording: ${e.message}", "No se pudo abrir la grabación: ${e.message}"))}
    }
    private fun stopAudio(){player?.release();player=null;model.playback(null)}
    override fun onStop(){super.onStop();stopAudio();if(recorder!=null)stopRecording()}
    override fun onSaveInstanceState(outState:Bundle){photoUri?.let{outState.putString("photoUri",it.toString())};outState.putBoolean("photoIsImport",photoIsImport);super.onSaveInstanceState(outState)}
    override fun onDestroy(){player?.release();recorder?.release();super.onDestroy()}
}

@Composable private fun PhotoViewer(item:UiItem,onClose:()->Unit){
    var bitmap by remember(item.id){mutableStateOf<Bitmap?>(null)}
    var error by remember{mutableStateOf<String?>(null)}
    var scale by remember{mutableFloatStateOf(1f)}
    var offset by remember{mutableStateOf(Offset.Zero)}
    LaunchedEffect(item.id){try{bitmap=withContext(Dispatchers.IO){MediaInputs.bitmap(File(item.localPath),2048)}}catch(e:Exception){error=e.message}}
    val transform=rememberTransformableState{zoom,pan,_->scale=(scale*zoom).coerceIn(1f,5f);offset=if(scale==1f)Offset.Zero else offset+pan}
    androidx.compose.ui.window.Dialog(onDismissRequest=onClose,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){
        MaterialTheme(colorScheme=darkColorScheme(),typography=BrandTypography){
            Column(Modifier.fillMaxSize().background(Color(0xFF0A0A0A)).statusBarsPadding().navigationBarsPadding().padding(16.dp)){
                TextButton(onClick=onClose){Text(tr("Close", "Cerrar"))};Text(item.title,style=MaterialTheme.typography.titleMedium);Text("${item.date} · ${item.assetId}")
                Box(Modifier.weight(1f).fillMaxWidth().transformable(transform)){bitmap?.let{Image(it.asImageBitmap(),tr("Original photograph", "Fotografía original"),Modifier.fillMaxSize().graphicsLayer(scaleX=scale,scaleY=scale,translationX=offset.x,translationY=offset.y))}?:if(error==null)CircularProgressIndicator()else Text(error!!)}
                Text(tr("Pinch to inspect · original source", "Pellizca para ampliar · fuente original"),Modifier.padding(vertical=12.dp))
            }
        }
    }
}

@Composable private fun PdfViewer(item:UiItem,onClose:()->Unit){
    var page by remember(item.id){mutableIntStateOf(Regex("\\d+").find(item.sourceLocation)?.value?.toIntOrNull()?.minus(1)?:0)}
    var pages by remember{mutableIntStateOf(1)}
    var image by remember{mutableStateOf<Bitmap?>(null)}
    var error by remember{mutableStateOf<String?>(null)}
    var scale by remember(page){mutableFloatStateOf(1f)}
    var offset by remember(page){mutableStateOf(Offset.Zero)}
    val transform=rememberTransformableState{zoom,pan,_->scale=(scale*zoom).coerceIn(1f,5f);offset=if(scale==1f)Offset.Zero else offset+pan}
    LaunchedEffect(item.id,page){
        image=null;error=null
        try{val result=withContext(Dispatchers.IO){ParcelFileDescriptor.open(File(item.localPath),ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{renderer->val total=renderer.pageCount;val p=page.coerceIn(0,total-1);renderer.openPage(p).use{pdf->val b=Bitmap.createBitmap(1100,(1100f*pdf.height/pdf.width).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888);b.eraseColor(android.graphics.Color.WHITE);pdf.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);Pair(total,b)}}}};pages=result.first;image=result.second}catch(e:Exception){error=e.message}
    }
    androidx.compose.ui.window.Dialog(onDismissRequest=onClose,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){
        MaterialTheme(colorScheme=darkColorScheme(),typography=BrandTypography){
            Column(Modifier.fillMaxSize().background(Color(0xFF0A0A0A)).statusBarsPadding().navigationBarsPadding().padding(16.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick=onClose){Text(tr("Close", "Cerrar"))};Text("${page+1} / $pages",Modifier.padding(12.dp));Text("${item.assetId}",Modifier.padding(12.dp))}
                Text(item.title,style=MaterialTheme.typography.titleMedium)
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical=12.dp).transformable(transform)){image?.let{Image(it.asImageBitmap(),tr("Report page ${page+1}", "Página ${page+1} del informe"),Modifier.fillMaxSize().graphicsLayer(scaleX=scale,scaleY=scale,translationX=offset.x,translationY=offset.y))}?:if(error==null)CircularProgressIndicator()else Text(error!!)}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={page--},enabled=page>0){Text(tr("Previous", "Anterior"))};TextButton(onClick={page++},enabled=page+1<pages){Text(tr("Next", "Siguiente"))}}
            }
        }
    }
}
