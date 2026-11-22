package com.nova.autostudio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/** Remuxes supported MP4 tracks without transcoding. Cuts begin at a sync sample. */
object VideoExporter {
 fun trim(context: Context, uri: Uri, fromUs: Long, toUs: Long): File {
  require(fromUs >= 0 && toUs > fromUs) { "Invalid trim range" }
  val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: error("Cannot open video")
  val extractor = MediaExtractor()
  try {
   extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
   val out = File(context.cacheDir, "nova_trim_${System.currentTimeMillis()}.mp4")
   val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
   try {
    val mappings = mutableMapOf<Int, Int>()
    for (i in 0 until extractor.trackCount) {
     val format = extractor.getTrackFormat(i)
     val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
     if (mime.startsWith("video/") || mime.startsWith("audio/")) {
      mappings[i] = muxer.addTrack(format)
     }
    }
    require(mappings.isNotEmpty()) { "No supported tracks" }
    muxer.start()
    val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
    val info = MediaCodec.BufferInfo()
    for ((sourceTrack, targetTrack) in mappings) {
     extractor.selectTrack(sourceTrack)
     extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
     var firstSampleUs = -1L
     while (true) {
      val trackIndex = extractor.sampleTrackIndex
      if (trackIndex < 0) break
      if (trackIndex != sourceTrack) {
       extractor.advance()
       continue
      }
      val timestamp = extractor.sampleTime
      if (timestamp < 0 || timestamp > toUs) break
      buffer.clear()
      val size = extractor.readSampleData(buffer, 0)
      if (size < 0) break
      if (firstSampleUs < 0) firstSampleUs = timestamp
      info.set(0, size, timestamp - firstSampleUs, extractor.sampleFlags)
      muxer.writeSampleData(targetTrack, buffer, info)
      extractor.advance()
     }
     extractor.unselectTrack(sourceTrack)
    }
    muxer.stop()
   } finally { muxer.release() }
   return out
  } finally { extractor.release(); descriptor.close() }
 }
}
