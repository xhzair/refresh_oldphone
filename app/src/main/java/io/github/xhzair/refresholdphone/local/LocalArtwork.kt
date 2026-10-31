package io.github.xhzair.refresholdphone.local

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size

object LocalArtwork {
    private const val MAX_DIMENSION = 512

    private val cache = object : LruCache<Long, Bitmap>(maxCacheBytes()) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    private fun maxCacheBytes(): Int =
        (Runtime.getRuntime().maxMemory() / 8)
            .coerceIn(4L * 1024 * 1024, Int.MAX_VALUE.toLong())
            .toInt()

    fun cached(id: Long): Bitmap? = cache.get(id)

    /** Blocking retriever/decode/cache; call off the main thread. */
    fun load(context: Context, track: LocalTrack): Bitmap? {
        cache.get(track.id)?.let { return it }
        val uri = LocalLibrary.contentUri(track.id)
        val bitmap = embeddedPicture(context, uri)
            ?: if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                legacyAlbumArt(context, track.albumId)
            } else {
                thumbnail(context, uri)
            }
        if (bitmap != null) cache.put(track.id, bitmap)
        return bitmap
    }

    private fun embeddedPicture(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null
            decodeBytes(bytes)
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun legacyAlbumArt(context: Context, albumId: Long): Bitmap? {
        if (albumId <= 0L) return null
        val uri = ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            albumId,
        )
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                decodeBytes(input.readBytes())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun thumbnail(context: Context, uri: Uri): Bitmap? =
        try {
            context.contentResolver.loadThumbnail(uri, Size(MAX_DIMENSION, MAX_DIMENSION), null)
        } catch (_: Exception) {
            null
        }

    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        var sampleSize = 1
        while (width / sampleSize > MAX_DIMENSION || height / sampleSize > MAX_DIMENSION) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
