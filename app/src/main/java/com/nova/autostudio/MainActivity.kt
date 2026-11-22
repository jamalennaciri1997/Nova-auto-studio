package com.nova.autostudio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class StudioClip(val uri: Uri, val title: String, val type: String)

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContent { StudioScreen() }
 }
}

@Composable
fun StudioScreen() {
 val clips = remember { mutableStateListOf<StudioClip>() }
 var prompt by remember { mutableStateOf("") }
 var status by remember { mutableStateOf("Add media to start your project") }
 var selected by remember { mutableIntStateOf(-1) }
 val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
  uris.forEach { uri ->
   val type = when {
    uri.toString().contains("audio") -> "Audio"
    else -> "Media"
   }
   clips.add(StudioClip(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled", type))
  }
  if (uris.isNotEmpty()) status = "Imported ${uris.size} file(s)"
 }
 MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF9B86FF))) {
  Column(Modifier.fillMaxSize().background(Color(0xFF101118)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
   Text("NOVA AUTO STUDIO", fontSize = 23.sp, color = Color.White)
   Text("Android editor · early prototype", color = Color.LightGray)
   Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = { picker.launch(arrayOf("image/*", "video/*", "audio/*")) }) { Text("+ Import") }
    OutlinedButton(onClick = { clips.clear(); selected = -1; status = "Project cleared" }) { Text("New project") }
   }
   Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF1C1E29), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
     Text("PREVIEW", color = Color.White, fontSize = 20.sp)
     Text(if (selected in clips.indices) clips[selected].title else "Select a clip in timeline", color = Color.LightGray)
    }
   }
   Text("TIMELINE  •  ${clips.size} clips", color = Color.White)
   LazyColumn(Modifier.fillMaxWidth().heightIn(max = 150.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    itemsIndexed(clips) { index, clip ->
     Row(Modifier.fillMaxWidth().background(if (selected == index) Color(0xFF49416B) else Color(0xFF292C39), RoundedCornerShape(6.dp)).clickable { selected = index }.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("${index + 1}. ${clip.title.take(30)}", color = Color.White)
      Text("×", color = Color.White, modifier = Modifier.clickable { clips.removeAt(index); selected = -1 })
     }
    }
   }
   Text("AI EDITOR", color = Color.White)
   Row(verticalAlignment = Alignment.CenterVertically) {
    OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.weight(1f), placeholder = { Text("Describe the edit…") }, singleLine = true)
    Spacer(Modifier.width(8.dp))
    Button(onClick = { status = "AI editing is not connected yet"; prompt = "" }) { Text("Send") }
   }
   Text(status, color = Color.LightGray, fontSize = 12.sp)
  }
 }
}
