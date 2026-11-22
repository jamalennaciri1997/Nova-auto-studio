package com.nova.autostudio

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(UnstableApi::class)
object ProjectExporter {
    suspend fun render(
        context: Context,
        clips: List<StudioClip>,
        music: Uri?,
        width: Int,
        height: Int,
        fps: Int,
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
                    Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(
                        width, height, Presentation.LAYOUT_SCALE_TO_FIT
                    )))
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
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        continuation.invokeOnCancellation {
            main.post {
                transformer.cancel()
                output.delete()
            }
        }
        try {
            transformer.start(composition, output.absolutePath)
        } catch (error: Exception) {
            output.delete()
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}
