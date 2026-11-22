package com.nova.autostudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import kotlin.math.abs

internal object OpeningShot {
    fun choose(context: Context, clips: List<StudioClip>): List<StudioClip> {
        val best = clips.indices.maxByOrNull { index ->
            runCatching { score(context, clips[index]) }.getOrDefault(-1.0)
        } ?: return clips
        if (best == 0) return clips
        return listOf(clips[best]) + clips.filterIndexed { index, _ -> index != best }
    }

    private fun score(context: Context, clip: StudioClip): Double {
        val image = if (clip.mime.startsWith("image/")) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(clip.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 384) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(clip.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, clip.uri)
                val timestampUs = (clip.startMs + minOf(500L, (clip.endMs - clip.startMs) / 2)) * 1000
                if (android.os.Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 96, 96)
                } else {
                    retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            } finally {
                retriever.release()
            }
        } ?: return -1.0
        val sample = Bitmap.createScaledBitmap(image, 64, 64, true)
        return try {
            var brightness = 0L
            var detail = 0L
            for (y in 0 until sample.height) {
                var previous = 0
                for (x in 0 until sample.width) {
                    val pixel = sample.getPixel(x, y)
                    val luminance = (77 * (pixel shr 16 and 255) + 150 * (pixel shr 8 and 255) + 29 * (pixel and 255)) shr 8
                    brightness += luminance
                    if (x > 0) detail += abs(luminance - previous)
                    previous = luminance
                }
            }
            val average = brightness.toDouble() / (sample.width * sample.height)
            detail / (1.0 + abs(average - 128.0) / 128.0)
        } finally {
            if (sample !== image) sample.recycle()
            image.recycle()
        }
    }
}
