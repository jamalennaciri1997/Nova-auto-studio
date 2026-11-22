package com.nova.autostudio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** On-device beat candidates from decoded PCM energy peaks. No network required. */
object BeatDetector {
 fun analyze(context: Context, uri: Uri, maxDurationSeconds: Int = 180): List<Long> {
  val fd = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: error("Cannot open audio")
  val extractor = MediaExtractor()
  try {
   extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
   val track = (0 until extractor.trackCount).firstOrNull {
    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
   } ?: error("No audio track")
   val format = extractor.getTrackFormat(track)
   val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Unknown audio codec")
   val codec = MediaCodec.createDecoderByType(mime)
   extractor.selectTrack(track)
   val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
   val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
   val samplesPerWindow = (sampleRate * channels / 20).coerceAtLeast(1)
   val energies = mutableListOf<Double>()
   val times = mutableListOf<Long>()
   try {
    codec.configure(format, null, null, 0)
    codec.start()
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    var outputDone = false
    var accumulated = 0.0
    var sampleCount = 0
    var windowIndex = 0L
    while (!outputDone) {
     if (!inputDone) {
      val index = codec.dequeueInputBuffer(10_000)
      if (index >= 0) {
       val input = codec.getInputBuffer(index)!!
       val size = extractor.readSampleData(input, 0)
       if (size < 0 || extractor.sampleTime > maxDurationSeconds * 1_000_000L) {
        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        inputDone = true
       } else {
        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
        extractor.advance()
       }
      }
     }
     val index = codec.dequeueOutputBuffer(info, 10_000)
     if (index >= 0) {
      val output = codec.getOutputBuffer(index)!!
      val pcm = output.duplicate().order(ByteOrder.LITTLE_ENDIAN)
      pcm.position(info.offset)
      pcm.limit(info.offset + info.size)
      while (pcm.remaining() >= 2) {
       val value = pcm.short.toDouble() / 32768.0
       accumulated += value * value
       sampleCount++
       if (sampleCount >= samplesPerWindow) {
        energies.add(sqrt(accumulated / sampleCount))
        times.add(windowIndex * 50L)
        windowIndex++
        sampleCount = 0
        accumulated = 0.0
       }
      }
      codec.releaseOutputBuffer(index, false)
      if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
     }
    }
   } finally { codec.stop(); codec.release() }
   val beats = mutableListOf<Long>()
   for (i in 4 until energies.size - 4) {
    val avg = energies.subList(i - 4, i + 5).average()
    if (energies[i] > avg * 1.45 && energies[i] > energies[i-1] && energies[i] >= energies[i+1] &&
        (beats.isEmpty() || times[i] - beats.last() >= 180L)) beats.add(times[i])
   }
   return beats
  } finally { extractor.release(); fd.close() }
 }
}
