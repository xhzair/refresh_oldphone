package io.github.xhzair.refresholdphone.local

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

object LocalLibrary {
    private const val UNKNOWN_TITLE = "未知歌曲"
    private const val UNKNOWN_ARTIST = "未知歌手"
    private const val UNKNOWN_ALBUM = "未知专辑"

    internal fun sanitize(tracks: List<LocalTrack>): List<LocalTrack> =
        tracks.asSequence()
            .filter { it.durationMs > 0 && it.title.isNotBlank() }
            .distinctBy(LocalTrack::id)
            .sortedWith(
                compareBy<LocalTrack, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.artist }
                    .thenBy { it.id },
            )
            .toList()

    /**
     * Blocking MediaStore I/O; call off the main thread. Requires READ_MEDIA_AUDIO (33+) or
     * READ_EXTERNAL_STORAGE (<=32); throws SecurityException if revoked.
     */
    fun query(context: Context): List<LocalTrack> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
        )
        val selection =
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0"
        val result = mutableListOf<LocalTrack>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                result += LocalTrack(
                    id = id,
                    title = normalize(cursor.getString(titleColumn), UNKNOWN_TITLE),
                    artist = normalize(cursor.getString(artistColumn), UNKNOWN_ARTIST),
                    album = normalize(cursor.getString(albumColumn), UNKNOWN_ALBUM),
                    durationMs = cursor.getLong(durationColumn),
                    albumId = cursor.getLong(albumIdColumn),
                )
            }
        }
        return sanitize(result)
    }

    /**
     * Blocking MediaStore I/O; call off the main thread. Requires READ_MEDIA_AUDIO (33+) or
     * READ_EXTERNAL_STORAGE (<=32); throws SecurityException if revoked.
     */
    fun loadByIds(context: Context, ids: LongArray): List<LocalTrack> {
        if (ids.isEmpty()) return emptyList()
        val byId = query(context).associateBy(LocalTrack::id)
        return ids.toList().mapNotNull(byId::get)
    }

    fun contentUri(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    private fun normalize(value: String?, fallback: String): String =
        value?.trim()?.takeUnless { it.isEmpty() || it == MediaStore.UNKNOWN_STRING } ?: fallback
}
