package io.github.xhzair.refresholdphone.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.xhzair.refresholdphone.R
import io.github.xhzair.refresholdphone.databinding.ItemLocalTrackBinding
import io.github.xhzair.refresholdphone.local.LocalArtwork
import io.github.xhzair.refresholdphone.local.LocalNowPlaying
import io.github.xhzair.refresholdphone.local.LocalTrack
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Formats a track duration as m:ss, or h:mm:ss for tracks of an hour or longer. */
internal fun formatTrackDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** Lists the device's local tracks and highlights the one the playback service reports. */
class LocalTrackAdapter(
    private val onClick: (LocalTrack, Int) -> Unit,
) : RecyclerView.Adapter<LocalTrackAdapter.Holder>() {

    class Holder(val binding: ItemLocalTrackBinding) : RecyclerView.ViewHolder(binding.root)

    private val artworkExecutor = Executors.newFixedThreadPool(2)

    @Volatile
    private var closed = false

    private var tracks: List<LocalTrack> = emptyList()
    private var nowPlayingId: Long? = null
    private var playing = false

    fun submit(tracks: List<LocalTrack>) {
        this.tracks = tracks
        notifyDataSetChanged()
    }

    fun setNowPlaying(value: LocalNowPlaying) {
        val previousId = nowPlayingId
        val currentId = value.track?.id
        nowPlayingId = currentId
        playing = value.playing
        if (previousId != null) notifyTrackChanged(previousId)
        if (currentId != null && currentId != previousId) notifyTrackChanged(currentId)
    }

    /** Stops the artwork pool; late decodes must not touch the rows after this. */
    fun shutdown() {
        closed = true
        artworkExecutor.shutdownNow()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemLocalTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = tracks.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = tracks[position]
        with(holder.binding) {
            root.tag = track.id
            trackTitle.text = track.title
            trackSubtitle.text = subtitle(track)
            trackDuration.text = formatTrackDuration(track.durationMs)
            root.contentDescription = listOf(track.title, track.artist)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .joinToString(" ")
            ViewCompat.setScreenReaderFocusable(root, true)
            val isCurrent = track.id == nowPlayingId
            trackStatus.visibility = if (isCurrent) View.VISIBLE else View.GONE
            trackStatus.text = if (isCurrent) {
                root.context.getString(
                    if (playing) R.string.local_music_playing else R.string.local_music_paused,
                )
            } else {
                null
            }

            val artwork = LocalArtwork.cached(track.id)
            if (artwork != null) {
                this.artwork.setImageBitmap(artwork)
            } else {
                this.artwork.setImageResource(R.drawable.ic_artwork)
                loadArtwork(holder, track)
            }

            root.setOnClickListener {
                val adapterPosition = holder.bindingAdapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) onClick(track, adapterPosition)
            }
        }
    }

    private fun subtitle(track: LocalTrack): String =
        listOf(track.artist, track.album)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(" · ")

    private fun notifyTrackChanged(id: Long) {
        val position = tracks.indexOfFirst { it.id == id }
        if (position >= 0) notifyItemChanged(position)
    }

    /**
     * Artwork decoding is slow, so it runs on an adapter-owned pool and is posted back only when
     * the row still shows the same track and the adapter has not been shut down. The application
     * context keeps a late decode from retaining an Activity.
     */
    private fun loadArtwork(holder: Holder, track: LocalTrack) {
        if (closed) return
        val binding = holder.binding
        val appContext = binding.root.context.applicationContext
        try {
            artworkExecutor.execute {
                if (closed) return@execute
                val bitmap = try {
                    LocalArtwork.load(appContext, track)
                } catch (_: Exception) {
                    null
                }
                if (bitmap == null || closed) return@execute
                binding.root.post {
                    if (closed) return@post
                    if (binding.root.tag == track.id) {
                        binding.artwork.setImageBitmap(bitmap)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Shut down between the flag check and submission; nothing to draw.
        }
    }
}
