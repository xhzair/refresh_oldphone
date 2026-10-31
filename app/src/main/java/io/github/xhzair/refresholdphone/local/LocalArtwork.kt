package io.github.xhzair.refresholdphone.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size

object LocalArtwork {
    private val cache = object : LruCache<Long, Bitmap>(32) {}

    fun cached(id: Long): Bitmap? = cache.get(id)

    fun load(context: Context, track: LocalTrack): Bitmap? {
        cache.get(track.id)?.let { return it }
        val uri = LocalLibrary.contentUri(track.id)
        val bitmap = embeddedPicture(context, uri) ?: thumbnail(context, uri)
        if (bitmap != null) cache.put(track.id, bitmap)
        return bitmap
    }

    private fun embeddedPicture(context: Context, uri: android.net.Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun thumbnail(context: Context, uri: android.net.Uri): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
        } catch (_: Exception) {
            null
        }
    }
}
