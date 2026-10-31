package io.github.xhzair.refresholdphone.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.xhzair.refresholdphone.R
import io.github.xhzair.refresholdphone.databinding.ItemLocalTrackBinding
import io.github.xhzair.refresholdphone.local.LocalArtwork
import io.github.xhzair.refresholdphone.local.LocalNowPlaying
import io.github.xhzair.refresholdphone.local.LocalTrack
import java.util.concurrent.Executors

/** Lists the device's local tracks and highlights the one the playback service reports. */
class LocalTrackAdapter(
    private val onClick: (LocalTrack, Int) -> Unit,
) : RecyclerView.Adapter<LocalTrackAdapter.Holder>() {

    class Holder(val binding: ItemLocalTrackBinding) : RecyclerView.ViewHolder(binding.root)

    private val artworkExecutor = Executors.newFixedThreadPool(2)

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemLocalTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = tracks.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = tracks[position]
        with(holder.binding) {
            root.tag = track.id
            trackTitle.text = track.title
            trackSubtitle.text = subtitle(track)
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
     * the row still shows the same track. The application context keeps a late decode from
     * retaining an Activity.
     */
    private fun loadArtwork(holder: Holder, track: LocalTrack) {
        val binding = holder.binding
        val appContext = binding.root.context.applicationContext
        artworkExecutor.execute {
            val bitmap = try {
                LocalArtwork.load(appContext, track)
            } catch (_: Exception) {
                null
            }
            if (bitmap == null) return@execute
            binding.root.post {
                if (binding.root.tag == track.id) {
                    binding.artwork.setImageBitmap(bitmap)
                }
            }
        }
    }
}
