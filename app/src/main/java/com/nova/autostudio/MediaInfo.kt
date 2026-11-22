package com.nova.autostudio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

object MediaInfo {
 fun durationMs(context: Context, uri: Uri): Long {
  val retriever = MediaMetadataRetriever()
  return try {
   retriever.setDataSource(context, uri)
   retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
  } finally { retriever.release() }
 }
}
