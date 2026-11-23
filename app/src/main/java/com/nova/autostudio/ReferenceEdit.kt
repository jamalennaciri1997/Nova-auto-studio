package com.nova.autostudio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

internal data class ReferenceShot(val durationMs: Long, val impact: Float, val glint: Glint? = null)
internal data class ReferenceStyle(
    val source: Uri,
    val hasAudio: Boolean,
    val portrait: Boolean,
    val shots: List<ReferenceShot>,
)

internal object ReferenceEdit {
    private const val stepMs = 80L
    private const val minimumShotMs = 240L

    suspend fun analyze(context: Context, uri: Uri, progress: suspend (Float) -> Unit): ReferenceStyle {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: error("Cannot read reference duration")
            require(duration in 400..180_000) { "Choose a reference between 0.4 seconds and 3 minutes" }
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: error("Reference must contain a video track")
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: error("Cannot read video dimensions")
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val portrait = if (rotation % 180 == 0) height >= width else width >= height
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val changes = mutableListOf<Pair<Long, Float>>()
            var previous: IntArray? = null
            var preceding: IntArray? = null
            val flashes = mutableListOf<Glint>()
            for (time in 0 until duration step stepMs) {
                currentCoroutineContext().ensureActive()
                val frame = if (Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 64, 64)
                } else {
                    retriever.getFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                }
                if (frame != null) {
                    val pixels = IntArray(64 * 64)
                    val sample = android.graphics.Bitmap.createScaledBitmap(frame, 64, 64, true)
                    sample.getPixels(pixels, 0, 64, 0, 0, 64, 64)
                    if (sample !== frame) sample.recycle()
                    frame.recycle()
                    val middle = previous
                    val first = preceding
                    if (middle != null) {
                        changes.add(time to difference(middle, pixels))
                        if (first != null) detectFlash(first, middle, pixels, time - stepMs)?.let(flashes::add)
                    }
                    preceding = middle
                    previous = pixels
                }
                if (time % (stepMs * 4) == 0L) progress(0.9f * time / duration)
            }
            require(changes.isNotEmpty()) { "Cannot decode the reference video" }
            progress(0.9f)
            val coroutine = currentCoroutineContext()
            val bass = if (hasAudio) {
                BeatDetector.analyze(context, uri, bassOnly = true, checkCancelled = { coroutine.ensureActive() })
            } else emptyList()
            val boundaries = mutableListOf(0L)
            for (index in changes.indices) {
                val (time, value) = changes[index]
                val neighbor = changes.getOrNull(index + 1)?.second ?: 0f
                val before = changes.getOrNull(index - 1)?.second ?: 0f
                if (value >= 0.105f && value >= neighbor && value > before &&
                    time - boundaries.last() >= minimumShotMs && duration - time >= minimumShotMs
                ) boundaries.add(time)
            }
            boundaries.add(duration)
            val shots = boundaries.zipWithNext().map { (start, end) ->
                val impact = if (start == 0L) 0f else {
                    val distance = bass.minOfOrNull { abs(it - start) } ?: Long.MAX_VALUE
                    if (distance <= 140L) (1f - distance / 220f).coerceIn(0f, 1f) else 0f
                }
                val flash = flashes.firstOrNull { it.timeMs - start >= 120 && end - it.timeMs >= 120 }
                ReferenceShot(end - start, impact, flash?.copy(timeMs = flash.timeMs - start))
            }
            progress(1f)
            return ReferenceStyle(uri, hasAudio, portrait, shots)
        } finally {
            retriever.release()
        }
    }

    private fun luminance(pixel: Int) =
        (3 * (pixel shr 16 and 255) + 6 * (pixel shr 8 and 255) + (pixel and 255)) / 10

    private fun detectFlash(first: IntArray, middle: IntArray, last: IntArray, time: Long): Glint? {
        if (difference(first, last) > 0.065f) return null
        var count = 0
        var x = 0f
        var y = 0f
        for (index in middle.indices) {
            val level = luminance(middle[index])
            if (level >= 210 && level - maxOf(luminance(first[index]), luminance(last[index])) >= 85) {
                count++
                x += index % 64
                y += index / 64
            }
        }
        if (count !in 8..100) return null
        val centerX = x / count
        val centerY = y / count
        return Glint(time, centerX / 63, centerY / 63)
    }

    private fun difference(first: IntArray, second: IntArray): Float {
        var total = 0
        for (index in first.indices) {
            val a = first[index]
            val b = second[index]
            total += abs((a shr 16 and 255) - (b shr 16 and 255))
            total += abs((a shr 8 and 255) - (b shr 8 and 255))
            total += abs((a and 255) - (b and 255))
        }
        return total / (first.size * 765f)
    }

    fun match(style: ReferenceStyle, sources: List<StudioClip>): List<StudioClip> {
        require(sources.isNotEmpty()) { "Import photos or videos first" }
        val inputs = sources.filter { it.mime.startsWith("image/") || it.endMs - it.startMs >= 180 }
        require(inputs.isNotEmpty()) { "Choose longer video clips or add photos" }
        val useCount = IntArray(inputs.size)
        return buildList {
            var next = 0
            style.shots.forEach { shot ->
                var remaining = shot.durationMs
                while (remaining > 0) {
                    val index = next++ % inputs.size
                    val clip = inputs[index]
                    val capacity = clip.endMs - clip.startMs
                    val image = clip.mime.startsWith("image/")
                    val length = if (image) remaining else minOf(remaining, capacity)
                    val room = (capacity - length).coerceAtLeast(0)
                    val fraction = listOf(0.4, 0.72, 0.1, 0.55)[useCount[index]++ % 4]
                    val start = if (image) 0 else clip.startMs + (room * fraction).toLong()
                    val offset = shot.durationMs - remaining
                    val flash = shot.glint?.takeIf { it.timeMs in offset until offset + length }
                    add(clip.copy(startMs = start, endMs = start + length,
                        glint = flash?.copy(timeMs = flash.timeMs - offset),
                        impact = if (remaining == shot.durationMs) shot.impact else 0f,
                        animate = true))
                    remaining -= length
                }
            }
        }
    }
}
