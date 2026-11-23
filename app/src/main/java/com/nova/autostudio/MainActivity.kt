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
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StudioClip(
    val uri: Uri,
    val title: String,
    val mime: String,
    val durationMs: Long,
    val startMs: Long = 0,
    val endMs: Long = durationMs,
    val impact: Float = 0f,
    val animate: Boolean = false,
    val glint: Glint? = null,
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
    val storage = remember(context) { StudioStorage(context) }
    val restored = remember(storage) { storage.restore() }
    val clips = remember { mutableStateListOf<StudioClip>().apply { addAll(restored.clips) } }
    val resources = remember { mutableStateListOf<StudioClip>().apply { addAll(restored.resources) } }
    var reference by remember { mutableStateOf(restored.reference) }
    var referenceAudio by remember { mutableStateOf(restored.referenceAudio) }
    var styleNames by remember { mutableStateOf(storage.styleNames()) }
    var styleMenu by remember { mutableStateOf(false) }
    var nameStyle by remember { mutableStateOf(false) }
    var styleName by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var selected by remember { mutableIntStateOf(if (clips.isEmpty()) -1 else 0) }
    var music by remember { mutableStateOf(restored.music) }
    var rendered by remember { mutableStateOf<File?>(null) }
    var showResult by remember { mutableStateOf(false) }
    var portrait by remember { mutableStateOf(restored.portrait) }
    var fps by remember { mutableIntStateOf(restored.fps) }
    var prompt by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var askNew by remember { mutableStateOf(false) }

    LaunchedEffect(storage) {
        snapshotFlow { StudioProject(clips.toList(), resources.toList(), music, reference, portrait, fps, referenceAudio) }
            .collect { storage.save(it) }
    }

    fun work(message: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = message
        progress = null
        job = scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                status = "Cancelled"
                throw cancelled
            } catch (error: Exception) {
                status = error.message ?: "Operation failed"
            } finally {
                busy = false
                progress = null
            }
        }
    }

    fun edit() {
        showResult = false
        rendered = null
    }

    fun readClip(uri: Uri): StudioClip {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val mime = context.contentResolver.getType(uri).orEmpty()
        val duration = when {
            mime.startsWith("image/") -> 3000L
            mime.startsWith("video/") || mime.startsWith("audio/") -> MediaInfo.durationMs(context, uri)
            else -> 0L
        }
        require(duration >= 80L) { "Unsupported or empty media" }
        val title = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        return StudioClip(uri, title ?: "Untitled", mime, duration)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) work("Reading media…") {
            val imported = mutableListOf<StudioClip>()
            uris.forEachIndexed { index, uri ->
                withContext(Dispatchers.IO) { runCatching { readClip(uri) }.getOrNull() }?.let(imported::add)
                progress = (index + 1f) / uris.size
            }
            val visuals = imported.filterNot { it.mime.startsWith("audio/") }
            music = imported.lastOrNull { it.mime.startsWith("audio/") } ?: music
            if (visuals.isNotEmpty()) {
                selected = clips.size
                clips.addAll(visuals)
                resources.addAll(visuals)
            }
            if (imported.isNotEmpty()) edit()
            status = "Imported ${imported.size} of ${uris.size} files"
        }
    }

    val referencePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work("Analyzing reference…") {
            val analyzed = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                ReferenceEdit.analyze(context, uri) { value -> withContext(Dispatchers.Main) { progress = value } }
            }
            reference = analyzed
            status = "Reference analyzed · ${analyzed.shots.size} shots"
        }
    }

    val replacement = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val index = selected
        val before = clips.getOrNull(index)
        if (uri != null && before != null) work("Reading replacement…") {
            val media = withContext(Dispatchers.IO) { readClip(uri) }
            val length = before.endMs - before.startMs
            val image = media.mime.startsWith("image/")
            require(image || (media.mime.startsWith("video/") && media.durationMs >= length)) {
                "Choose a photo or a video at least ${length / 1000.0}s long"
            }
            val start = if (image) 0L else (media.durationMs - length) / 3
            clips[index] = media.copy(startMs = start, endMs = start + length,
                impact = before.impact, animate = before.animate, glint = before.glint)
            edit()
            status = "Shot replaced"
        }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val file = rendered
        if (uri != null && file != null) work("Saving MP4…") {
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error("Cannot open the destination")
                val total = file.length().coerceAtLeast(1)
                output.use { stream ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var saved = 0L
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            stream.write(buffer, 0, count)
                            saved += count
                            withContext(Dispatchers.Main) { progress = saved.toFloat() / total }
                        }
                    }
                }
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
        progress = null
        val soundtrack = music?.uri ?: referenceAudio
        val result = ProjectExporter.render(
            context, clips.toList(), soundtrack,
            if (portrait) 720 else 1280, if (portrait) 1280 else 720, fps,
            onProgress = { progress = it },
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
                    Column(Modifier.fillMaxWidth()) {
                        if (busy) {
                            val fraction = progress
                            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        }
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val label = if (busy) progress?.let { "$status · ${(it * 100).toInt()}%" } ?: status else status
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        val result = rendered
                        if (busy) {
                            TextButton(onClick = { job?.cancel() }) { Text("Cancel") }
                        } else if (result != null) {
                            TextButton(onClick = { share(result, "video/mp4") }, enabled = !busy) { Text("Share") }
                            Button(onClick = { save.launch("NOVA-Studio.mp4") }, enabled = !busy) { Text("Save MP4") }
                        } else {
                            Button(onClick = { work("Rendering MP4…") { export() } }, enabled = !busy && clips.isNotEmpty()) {
                                Text("Export MP4")
                            }
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

                Text("Video reference", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { referencePicker.launch(arrayOf("video/*")) }) {
                        Text(if (reference == null) "Choose video" else "Change video")
                    }
                    Box {
                        OutlinedButton(enabled = !busy && styleNames.isNotEmpty(), onClick = { styleMenu = true }) {
                            Text("Saved styles")
                        }
                        DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                            styleNames.forEach { name ->
                                DropdownMenuItem(text = { Text(name) }, onClick = {
                                    reference = storage.loadStyle(name)
                                    styleMenu = false
                                    status = "Style loaded"
                                })
                            }
                        }
                    }
                }
                reference?.let { style ->
                    Text("${style.shots.size} shots · ${style.shots.sumOf { it.durationMs } / 1000.0}s")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && clips.isNotEmpty(), onClick = {
                            work("Matching shots…") {
                                val inputs = resources.toList().ifEmpty { clips.toList() }
                                val plan = withContext(Dispatchers.Default) { ReferenceEdit.match(style, inputs) }
                                clips.clear()
                                clips.addAll(plan)
                                selected = 0
                                portrait = style.portrait
                                music = null
                                referenceAudio = style.source.takeIf { style.hasAudio }
                                edit()
                                status = "Rendering preview…"
                                export()
                            }
                        }) { Text("Match & preview") }
                        TextButton(enabled = !busy, onClick = { styleName = ""; nameStyle = true }) {
                            Text("Save style")
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
                    TextButton(enabled = !busy, onClick = { replacement.launch(arrayOf("image/*", "video/*")) }) {
                        Text("Replace selected shot")
                    }
                    if (current.animate) {
                        Text("Impact")
                        Slider(value = current.impact, enabled = !busy, onValueChange = { amount ->
                            clips[selected] = current.copy(impact = amount)
                            edit()
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sparkle", Modifier.weight(1f))
                        Switch(checked = current.glint != null, enabled = !busy, onCheckedChange = { enabled ->
                            clips[selected] = current.copy(glint = if (enabled) {
                                Glint(minOf(220L, (current.endMs - current.startMs) / 2), 0.5f, 0.5f)
                            } else null)
                            edit()
                        })
                    }
                    current.glint?.let { glint ->
                        Text("Sparkle position")
                        Slider(value = glint.x, enabled = !busy, onValueChange = {
                            clips[selected] = current.copy(glint = glint.copy(x = it))
                            edit()
                        })
                        Slider(value = glint.y, enabled = !busy, onValueChange = {
                            clips[selected] = current.copy(glint = glint.copy(y = it))
                            edit()
                        })
                    }
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
                    Text(music?.let { "Music · ${it.title}" }
                        ?: if (referenceAudio != null) "Reference audio" else "Import audio to add music",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (music != null || referenceAudio != null) {
                        TextButton(enabled = !busy, onClick = {
                            if (music != null) music = null else referenceAudio = null
                            edit()
                        }) { Text("Remove") }
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
        if (nameStyle) {
            AlertDialog(
                onDismissRequest = { nameStyle = false },
                title = { Text("Save reference style") },
                text = { OutlinedTextField(styleName, { styleName = it }, label = { Text("Style name") }) },
                confirmButton = {
                    val name = styleName.trim()
                    TextButton(enabled = name.isNotEmpty() && name !in styleNames, onClick = {
                        reference?.let { storage.saveStyle(name, it) }
                        styleNames = storage.styleNames()
                        nameStyle = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { nameStyle = false }) { Text("Cancel") } },
            )
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
                        resources.clear()
                        reference = null
                        referenceAudio = null
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
