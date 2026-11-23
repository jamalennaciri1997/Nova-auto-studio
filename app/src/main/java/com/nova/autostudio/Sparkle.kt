package com.nova.autostudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import kotlin.math.abs

data class Glint(val timeMs: Long, val x: Float, val y: Float)

@OptIn(UnstableApi::class)
internal fun sparkle(glint: Glint): OverlayEffect {
    val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = RadialGradient(64f, 64f, 55f,
        intArrayOf(0xEFFFFFFF.toInt(), 0x55D8EAFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    canvas.drawCircle(64f, 64f, 55f, paint)
    paint.shader = null
    paint.color = android.graphics.Color.WHITE
    val path = Path().apply {
        moveTo(64f, 5f)
        lineTo(69f, 58f)
        lineTo(123f, 64f)
        lineTo(69f, 70f)
        lineTo(64f, 123f)
        lineTo(59f, 70f)
        lineTo(5f, 64f)
        lineTo(59f, 58f)
        close()
    }
    canvas.drawPath(path, paint)
    return OverlayEffect(listOf(object : BitmapOverlay() {
        override fun getBitmap(presentationTimeUs: Long) = bitmap

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val opacity = (1f - abs(presentationTimeUs / 1000f - glint.timeMs) / 110f).coerceIn(0f, 1f)
            return OverlaySettings.Builder()
                .setBackgroundFrameAnchor(glint.x * 2f - 1f, 1f - glint.y * 2f)
                .setAlphaScale(opacity * opacity)
                .setScale(0.35f + 0.28f * opacity, 0.35f + 0.28f * opacity)
                .build()
        }
    }))
}
