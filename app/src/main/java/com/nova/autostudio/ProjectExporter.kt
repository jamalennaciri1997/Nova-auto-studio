package com.nova.autostudio

import android.content.Context
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

@OptIn(UnstableApi::class)
object ProjectExporter {
    suspend fun render(
        context: Context,
        clips: List<StudioClip>,
        music: Uri?,
        width: Int,
        height: Int,
        fps: Int,
        onProgress: (Float?) -> Unit = {},
    ): File = suspendCancellableCoroutine { continuation ->
        val visuals = clips.filterNot { it.mime.startsWith("audio/") }
        require(visuals.isNotEmpty()) { "Add a photo or video first" }
        val items = visuals.map { clip ->
            val media = MediaItem.Builder().setUri(clip.uri)
            val image = clip.mime.startsWith("image/")
            if (image) {
                media.setImageDurationMs(clip.endMs - clip.startMs)
            } else {
                media.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startMs)
                        .setEndPositionMs(clip.endMs)
                        .build()
                )
            }
            EditedMediaItem.Builder(media.build())
                .setFrameRate(fps)
                .setRemoveAudio(music != null || image)
                .setEffects(
                    Effects(emptyList(), buildList {
                        add(Presentation.createForWidthAndHeight(
                            width, height,
                            if (clip.animate) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                            else Presentation.LAYOUT_SCALE_TO_FIT
                        ))
                        if (clip.animate) add(motion(clip, image))
                        clip.glint?.let { add(sparkle(it)) }
                    })
                )
                .build()
        }
        val sequences = mutableListOf(EditedMediaItemSequence(items))
        if (music != null) {
            val soundtrack = EditedMediaItem.Builder(MediaItem.fromUri(music))
                .setRemoveVideo(true)
                .build()
            sequences.add(EditedMediaItemSequence(listOf(soundtrack), true))
        }
        val composition = Composition.Builder(sequences)
            .experimentalSetForceAudioTrack(music == null)
            .build()
        val output = File.createTempFile("nova_", ".mp4", context.cacheDir)
        output.delete()
        val main = Handler(Looper.getMainLooper())
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (continuation.isActive) continuation.resume(output)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    output.delete()
                    if (continuation.isActive) continuation.resumeWithException(exportException)
                }
            })
            .build()
        val holder = ProgressHolder()
        val monitor = object : Runnable {
            override fun run() {
                if (!continuation.isActive) return
                val available = transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE
                onProgress(if (available) holder.progress / 100f else null)
                main.postDelayed(this, 200)
            }
        }
        continuation.invokeOnCancellation {
            main.post {
                main.removeCallbacks(monitor)
                transformer.cancel()
                output.delete()
            }
        }
        try {
            transformer.start(composition, output.absolutePath)
            main.post(monitor)
        } catch (error: Exception) {
            output.delete()
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private fun motion(clip: StudioClip, image: Boolean) = MatrixTransformation { timestampUs ->
        val time = timestampUs.coerceAtLeast(0) / 1_000_000f
        val duration = (clip.endMs - clip.startMs) / 1000f
        val movement = (time / duration).coerceIn(0f, 1f)
        val pulse = if (time < 0.24f) exp(-time * 19) * clip.impact else 0f
        val easing = (1f - time / 0.22f).coerceIn(0f, 1f)
        val zoom = 1f + (if (image) 0.04f * movement else 0f) +
            0.025f * easing * easing + pulse * 0.12f
        Matrix().apply {
            setScale(zoom, zoom)
            postTranslate(
                (sin(time * 2 * PI * 13) * pulse * 0.065f).toFloat(),
                (sin(time * 2 * PI * 17) * pulse * 0.035f).toFloat()
            )
        }
    }
}
