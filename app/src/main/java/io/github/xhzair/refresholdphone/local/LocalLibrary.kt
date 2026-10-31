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
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.artist },
            )
            .toList()

    fun query(context: Context): List<LocalTrack> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
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
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                result += LocalTrack(
                    id = id,
                    title = cursor.getString(titleColumn)?.trim().orEmpty().ifBlank { UNKNOWN_TITLE },
                    artist = cursor.getString(artistColumn)?.trim().orEmpty().ifBlank { UNKNOWN_ARTIST },
                    album = cursor.getString(albumColumn)?.trim().orEmpty().ifBlank { UNKNOWN_ALBUM },
                    durationMs = cursor.getLong(durationColumn),
                )
            }
        }
        return sanitize(result)
    }

    fun loadByIds(context: Context, ids: LongArray): List<LocalTrack> {
        val byId = query(context).associateBy(LocalTrack::id)
        return ids.toList().mapNotNull(byId::get)
    }

    fun contentUri(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
}
