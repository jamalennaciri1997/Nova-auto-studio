package com.nova.autostudio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StudioClip(
    val uri: Uri,
    val title: String,
    val mime: String,
    val durationMs: Long,
    val startMs: Long = 0,
    val endMs: Long = durationMs,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StudioScreen() }
    }
}

@Composable
private fun StudioScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clips = remember { mutableStateListOf<StudioClip>() }
    var selected by remember { mutableIntStateOf(-1) }
    var music by remember { mutableStateOf<StudioClip?>(null) }
    var rendered by remember { mutableStateOf<File?>(null) }
    var showResult by remember { mutableStateOf(false) }
    var portrait by remember { mutableStateOf(true) }
    var fps by remember { mutableIntStateOf(30) }
    var prompt by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var askNew by remember { mutableStateOf(false) }

    fun work(message: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = message
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status = error.message ?: "Operation failed"
            } finally {
                busy = false
            }
        }
    }

    fun edit() {
        showResult = false
        rendered = null
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) work("Reading media…") {
            val imported = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        val mime = context.contentResolver.getType(uri).orEmpty()
                        val duration = when {
                            mime.startsWith("image/") -> 3000L
                            mime.startsWith("video/") || mime.startsWith("audio/") ->
                                MediaInfo.durationMs(context, uri)
                            else -> 0L
                        }
                        require(duration > 0)
                        val title = context.contentResolver.query(
                            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                        )?.use { if (it.moveToFirst()) it.getString(0) else null }
                        StudioClip(uri, title ?: "Untitled", mime, duration)
                    }.getOrNull()
                }
            }
            val videosAndPhotos = imported.filterNot { it.mime.startsWith("audio/") }
            music = imported.lastOrNull { it.mime.startsWith("audio/") } ?: music
            if (videosAndPhotos.isNotEmpty()) {
                selected = clips.size
                clips.addAll(videosAndPhotos)
            }
            if (imported.isNotEmpty()) edit()
            status = "Imported ${imported.size} of ${uris.size} files"
        }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val file = rendered
        if (uri != null && file != null) work("Saving MP4…") {
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error("Cannot open the destination")
                output.use { stream -> file.inputStream().use { it.copyTo(stream) } }
            }
            status = "MP4 saved"
        }
    }

    fun share(file: File, type: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, null))
    }

    suspend fun export() {
        val result = ProjectExporter.render(
            context, clips.toList(), music?.uri,
            if (portrait) 720 else 1280, if (portrait) 1280 else 720, fps
        )
        rendered = result
        showResult = true
        status = "MP4 ready to preview, save or share"
    }

    val current = clips.getOrNull(selected)
    var from by remember(current) { mutableStateOf(current?.let { (it.startMs / 1000.0).toString() }.orEmpty()) }
    var to by remember(current) { mutableStateOf(current?.let { (it.endMs / 1000.0).toString() }.orEmpty()) }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFC6FF65),
        onPrimary = Color(0xFF152306),
        background = Color(0xFF101210),
        surface = Color(0xFF1B201C),
    )) {
        Scaffold(
            topBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("NOVA Studio", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                        TextButton(onClick = { askNew = true }, enabled = !busy) { Text("New") }
                        Button(onClick = { picker.launch(arrayOf("image/*", "video/*", "audio/*")) }, enabled = !busy) {
                            Text("Import")
                        }
                    }
                }
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        val result = rendered
                        if (result != null) {
                            TextButton(onClick = { share(result, "video/mp4") }, enabled = !busy) { Text("Share") }
                            Button(onClick = { save.launch("NOVA-Studio.mp4") }, enabled = !busy) { Text("Save MP4") }
                        } else {
                            Button(onClick = { work("Rendering MP4…") { export() } }, enabled = !busy && clips.isNotEmpty()) {
                                Text("Export MP4")
                            }
                        }
                    }
                }
            },
        ) { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets).imePadding()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val result = rendered?.takeIf { showResult }
                val preview = result?.let { Uri.fromFile(it) } ?: current?.uri
                Box(
                    Modifier.fillMaxWidth().height(265.dp).clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF080A09)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        preview == null -> Text("Import a photo or video to start")
                        result == null && current?.mime?.startsWith("image/") == true ->
                            key(preview) {
                                AndroidView(
                                    factory = { ImageView(it).apply {
                                        scaleType = ImageView.ScaleType.FIT_CENTER
                                        setImageURI(preview)
                                    } },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        else -> key(preview, current) {
                            AndroidView(
                                factory = { viewContext ->
                                    VideoView(viewContext).apply {
                                        val controller = MediaController(viewContext)
                                        setMediaController(controller)
                                        setOnPreparedListener {
                                            it.isLooping = result != null
                                            if (result == null) seekTo((current?.startMs ?: 0).toInt())
                                            start()
                                        }
                                        setOnErrorListener { _, _, _ ->
                                            status = "Cannot play this video"
                                            true
                                        }
                                        setVideoURI(preview)
                                    }
                                },
                                onRelease = { it.stopPlayback() },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(portrait, { portrait = true; edit() }, { Text("9:16") }, enabled = !busy)
                    FilterChip(!portrait, { portrait = false; edit() }, { Text("16:9") }, enabled = !busy)
                    FilterChip(fps == 60, { fps = if (fps == 60) 30 else 60; edit() },
                        { Text(if (fps == 60) "Photos: 60 fps" else "Photos: 30 fps") }, enabled = !busy)
                    if (rendered != null) {
                        TextButton(onClick = { showResult = !showResult }) {
                            Text(if (showResult) "Clip" else "Result")
                        }
                    }
                }

                Text("Timeline · ${clips.size}", style = MaterialTheme.typography.titleMedium)
                clips.forEachIndexed { index, clip ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected == index) Color(0xFF34442A) else MaterialTheme.colorScheme.surface,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                selected = index
                                showResult = false
                            }.padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${index + 1}. ${clip.title}", Modifier.weight(1f), maxLines = 2)
                            TextButton(enabled = !busy && index > 0, onClick = {
                                clips.add(index - 1, clips.removeAt(index))
                                selected = index - 1
                                edit()
                            }) { Text("↑") }
                            TextButton(enabled = !busy && index < clips.lastIndex, onClick = {
                                clips.add(index + 1, clips.removeAt(index))
                                selected = index + 1
                                edit()
                            }) { Text("↓") }
                            TextButton(enabled = !busy, onClick = {
                                clips.removeAt(index)
                                selected = index.coerceAtMost(clips.lastIndex)
                                edit()
                            }) { Text("Remove") }
                        }
                    }
                }

                if (current != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(from, { from = it }, Modifier.weight(1f), label = { Text("From (s)") },
                            enabled = !busy && current.mime.startsWith("video/"), singleLine = true)
                        OutlinedTextField(to, { to = it }, Modifier.weight(1f), label = { Text("End (s)") },
                            enabled = !busy, singleLine = true)
                        TextButton(enabled = !busy, onClick = {
                            val start = from.toDoubleOrNull()?.takeIf { it.isFinite() }?.times(1000)?.toLong()
                            val end = to.toDoubleOrNull()?.takeIf { it.isFinite() }?.times(1000)?.toLong()
                            val max = if (current.mime.startsWith("image/")) 180_000L else current.durationMs
                            if (start == null || end == null || start < 0 || end <= start || end > max) {
                                status = "Choose a range within ${max / 1000.0}s"
                            } else {
                                clips[selected] = current.copy(startMs = start, endMs = end)
                                edit()
                                status = "Clip updated"
                            }
                        }) { Text("Apply") }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(music?.let { "Music · ${it.title}" } ?: "Import audio to add music",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (music != null) {
                        TextButton(enabled = !busy, onClick = { music = null; edit() }) { Text("Remove") }
                    }
                    if (current?.mime?.startsWith("video/") == true) {
                        TextButton(enabled = !busy, onClick = {
                            work("Extracting audio…") {
                                val file = withContext(Dispatchers.IO) { AudioExtractor.extract(context, current.uri) }
                                share(file, "audio/mp4")
                                status = "Audio extracted"
                            }
                        }) { Text("Extract") }
                    }
                }

                Text("Automatic edit", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    prompt, { prompt = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text("دير ليا 15 ثانية بالطول مع الإيقاع") }, enabled = !busy,
                )
                FilledTonalButton(
                    enabled = !busy && clips.isNotEmpty() && prompt.isNotBlank(),
                    onClick = {
                        work("Preparing edit…") {
                            val command = EditCommand.parse(prompt)
                            val soundtrack = music
                            val beats = if (command.syncToBeats) {
                                require(soundtrack != null) { "Import music first to sync to beats" }
                                withContext(Dispatchers.IO) { BeatDetector.analyze(context, soundtrack.uri) }
                                    .also { require(it.isNotEmpty()) { "No clear beats found in the music" } }
                            } else emptyList()
                            val duration = command.durationMs
                            if (command.cutFirst && duration != null) {
                                val clip = clips.getOrNull(selected) ?: error("Select a clip to trim")
                                clips[selected] = clip.copy(endMs = minOf(clip.endMs, clip.startMs + duration))
                            } else if (duration != null || beats.isNotEmpty() || command.strongestFirst) {
                                val timeline = clips.toList()
                                val ordered = if (command.strongestFirst) {
                                    withContext(Dispatchers.IO) { OpeningShot.choose(context, timeline) }
                                } else timeline
                                val plan = if (duration != null || beats.isNotEmpty()) {
                                    planMontage(ordered, duration, beats)
                                } else ordered
                                clips.clear()
                                clips.addAll(plan)
                                selected = 0
                            }
                            portrait = command.portrait ?: portrait
                            fps = command.fps ?: fps
                            edit()
                            status = "Rendering MP4…"
                            export()
                        }
                    },
                ) { Text("Create and preview") }
            }
        }
        if (askNew) {
            AlertDialog(
                onDismissRequest = { askNew = false },
                title = { Text("Start a new project?") },
                text = { Text("This clears the current timeline.") },
                confirmButton = {
                    TextButton(onClick = {
                        askNew = false
                        clips.clear()
                        music = null
                        selected = -1
                        status = ""
                        edit()
                    }) { Text("Start new") }
                },
                dismissButton = {
                    TextButton(onClick = { askNew = false }) { Text("Cancel") }
                },
            )
        }
    }
}
