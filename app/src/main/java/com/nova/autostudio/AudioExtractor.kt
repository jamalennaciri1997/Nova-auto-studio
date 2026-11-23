package com.nova.autostudio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/** Lossless AAC track extraction to an M4A file. */
object AudioExtractor {
 fun extract(context: Context, source: Uri, onProgress: (Float?) -> Unit = {}): File {
  val fd = context.contentResolver.openAssetFileDescriptor(source, "r")
   ?: error("Cannot open selected video")
  val extractor = MediaExtractor()
  try {
   extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
   val track = (0 until extractor.trackCount).firstOrNull {
    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
   } ?: error("Video contains no audio")
   val format = extractor.getTrackFormat(track)
   require(format.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm") {
    "This version can extract AAC audio only"
   }
   extractor.selectTrack(track)
   val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
   val origin = extractor.sampleTime.coerceAtLeast(0L)
   var lastUpdate = -1L
   val output = File(context.cacheDir, "nova_audio_${System.currentTimeMillis()}.m4a")
   val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
   try {
    val dest = muxer.addTrack(format)
    muxer.start()
    val buffer = ByteBuffer.allocateDirect(1024 * 1024)
    val info = MediaCodec.BufferInfo()
    while (true) {
     val timestamp = (extractor.sampleTime - origin).coerceAtLeast(0L)
     if (timestamp - lastUpdate >= 200_000 || lastUpdate == -1L) {
      onProgress(if (duration > 0L) (timestamp.toFloat() / duration).coerceIn(0f, 1f) else null)
      lastUpdate = timestamp
     }
     buffer.clear()
     val count = extractor.readSampleData(buffer, 0)
     if (count < 0) break
     info.set(0, count, timestamp, extractor.sampleFlags)
     muxer.writeSampleData(dest, buffer, info)
     extractor.advance()
    }
    muxer.stop()
   } catch (error: Exception) {
    output.delete()
    throw error
   } finally { muxer.release() }
   return output
  } finally { extractor.release(); fd.close() }
 }
}
