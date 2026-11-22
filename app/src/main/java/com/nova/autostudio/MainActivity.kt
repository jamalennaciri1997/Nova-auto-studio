package com.nova.autostudio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StudioClip(val uri: Uri, val title: String, val mime: String)

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContent { StudioScreen() }
 }
}

@Composable
fun StudioScreen() {
 val context = LocalContext.current
 val scope = rememberCoroutineScope()
 val clips = remember { mutableStateListOf<StudioClip>() }
 var prompt by remember { mutableStateOf("") }
 var status by remember { mutableStateOf("Import media to begin") }
 var selected by remember { mutableIntStateOf(-1) }
 var trimStart by remember { mutableStateOf("0") }
 var trimEnd by remember { mutableStateOf("10") }
 var beats by remember { mutableStateOf<List<Long>>(emptyList()) }
 val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
  uris.forEach { uri ->
   val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
   clips.add(StudioClip(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled", mime))
  }
  if (uris.isNotEmpty()) status = "Imported ${uris.size} files"
 }
 val current = clips.getOrNull(selected)
 MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF9B86FF))) {
  Column(Modifier.fillMaxSize().background(Color(0xFF101118)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
   Text("NOVA AUTO STUDIO", fontSize = 22.sp, color = Color.White)
   Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = { picker.launch(arrayOf("image/*", "video/*", "audio/*")) }) { Text("Import") }
    OutlinedButton(onClick = { clips.clear(); selected = -1; status = "Project cleared" }) { Text("New") }
    OutlinedButton(onClick = {
     val clip = current
     if (clip == null || !clip.mime.startsWith("video/")) status = "Select a video first"
     else scope.launch {
      status = "Extracting audio…"
      try {
       val file = withContext(Dispatchers.IO) { AudioExtractor.extract(context, clip.uri) }
       val shareUri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
       val share = Intent(Intent.ACTION_SEND).apply {
        type = "audio/mp4"
        putExtra(Intent.EXTRA_STREAM, shareUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
       }
       context.startActivity(Intent.createChooser(share, "Save or share extracted audio"))
       status = "Audio extracted"
      } catch (e: Exception) { status = e.message ?: "Audio extraction failed" }
     }
    }) { Text("Extract") }
   }
   Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF1C1E29), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
    when {
     current?.mime?.startsWith("video/") == true ->
      AndroidView(factory = { VideoView(it) }, update = { view ->
       if (view.tag != current!!.uri) {
        view.tag = current!!.uri
        view.setVideoURI(current!!.uri)
        view.setOnPreparedListener { player -> player.isLooping = true; view.start() }
       }
      }, modifier = Modifier.fillMaxSize())
     current?.mime?.startsWith("image/") == true ->
      AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
       update = { it.setImageURI(current!!.uri) }, modifier = Modifier.fillMaxSize())
     else -> Text(if (current == null) "SELECT A CLIP TO PREVIEW" else "Audio selected", color = Color.White)
    }
   }
   Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    OutlinedTextField(trimStart, { trimStart = it }, Modifier.weight(1f), label = { Text("From (s)") }, singleLine = true)
    OutlinedTextField(trimEnd, { trimEnd = it }, Modifier.weight(1f), label = { Text("To (s)") }, singleLine = true)
    Button(onClick = {
     val clip = current
     val from = trimStart.toDoubleOrNull()
     val to = trimEnd.toDoubleOrNull()
     if (clip == null || !clip.mime.startsWith("video/") || from == null || to == null || from < 0 || to <= from) status = "Select video and valid times"
     else scope.launch {
      status = "Exporting trimmed MP4…"
      try {
       val file = withContext(Dispatchers.IO) { VideoExporter.trim(context, clip.uri, (from * 1_000_000).toLong(), (to * 1_000_000).toLong()) }
       val shareUri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
       context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, shareUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
       }, "Save or share trimmed video"))
       status = "Video exported"
      } catch (e: Exception) { status = e.message ?: "Export failed" }
     }
    }) { Text("Trim") }
   }
   Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(onClick = {
     val clip = current
     if (clip == null || (!clip.mime.startsWith("audio/") && !clip.mime.startsWith("video/"))) status = "Select audio or video"
     else scope.launch {
      status = "Analyzing beats…"
      try {
       beats = withContext(Dispatchers.IO) { BeatDetector.analyze(context, clip.uri) }
       status = "Detected ${beats.size} beat candidates"
      } catch (e: Exception) { status = e.message ?: "Beat analysis failed" }
     }
    }) { Text("Find beats") }
    Text(beats.take(8).joinToString(" · ") { "${it / 1000.0}s" }, color = Color.LightGray, fontSize = 11.sp)
   }
   Text("TIMELINE · ${clips.size} clips", color = Color.White)
   LazyColumn(Modifier.fillMaxWidth().heightIn(max = 135.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    itemsIndexed(clips) { index, clip ->
     Row(Modifier.fillMaxWidth().background(if (selected == index) Color(0xFF49416B) else Color(0xFF292C39), RoundedCornerShape(6.dp)).clickable { selected = index }.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("${index + 1}. ${clip.title.take(28)}", color = Color.White)
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
       Text("↑", color = Color.White, modifier = Modifier.clickable {
        if (index > 0) { val item = clips.removeAt(index); clips.add(index - 1, item); selected = index - 1 }
       })
       Text("↓", color = Color.White, modifier = Modifier.clickable {
        if (index < clips.lastIndex) { val item = clips.removeAt(index); clips.add(index + 1, item); selected = index + 1 }
       })
       Text("Remove", color = Color.White, modifier = Modifier.clickable { clips.removeAt(index); selected = -1 })
      }
     }
    }
   }
   Text("AI EDITOR", color = Color.White)
   Row(verticalAlignment = Alignment.CenterVertically) {
    OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.weight(1f), placeholder = { Text("Describe an edit…") }, singleLine = true)
    Spacer(Modifier.width(8.dp))
    Button(onClick = {
     val command = prompt.trim().lowercase()
     when {
      command.contains("beat") || command.contains("إيقاع") || command.contains("ايقاع") -> status = "Use Find beats to analyze selected media"
      command.contains("trim") || command.contains("قص") -> status = "Set From/To seconds and tap Trim"
      command.contains("extract") || command.contains("صوت") -> status = "Select a video and tap Extract"
      command.contains("import") || command.contains("زيد") -> picker.launch(arrayOf("image/*", "video/*", "audio/*"))
      else -> status = "Available: import, trim, extract audio, detect beats. Cloud AI is not connected."
     }
     prompt = ""
    }) { Text("Send") }
   }
   Text(status, color = Color.LightGray, fontSize = 12.sp)
  }
 }
}
