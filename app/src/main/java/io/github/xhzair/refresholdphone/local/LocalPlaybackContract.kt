package io.github.xhzair.refresholdphone.local

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object LocalPlaybackContract {
    const val ACTION_PLAY_QUEUE = "io.github.xhzair.refresholdphone.local.action.PLAY_QUEUE"
    const val ACTION_TOGGLE = "io.github.xhzair.refresholdphone.local.action.TOGGLE"
    const val ACTION_NEXT = "io.github.xhzair.refresholdphone.local.action.NEXT"
    const val ACTION_PREVIOUS = "io.github.xhzair.refresholdphone.local.action.PREVIOUS"
    const val ACTION_STOP = "io.github.xhzair.refresholdphone.local.action.STOP"

    const val EXTRA_TRACK_IDS = "track_ids"
    const val EXTRA_START_INDEX = "start_index"

    fun playQueue(context: Context, trackIds: LongArray, startIndex: Int) {
        val intent = Intent(context, LocalPlaybackService::class.java)
            .setAction(ACTION_PLAY_QUEUE)
            .putExtra(EXTRA_TRACK_IDS, trackIds)
            .putExtra(EXTRA_START_INDEX, startIndex)
        ContextCompat.startForegroundService(context, intent)
    }

    fun toggle(context: Context) = send(context, ACTION_TOGGLE)

    fun next(context: Context) = send(context, ACTION_NEXT)

    fun previous(context: Context) = send(context, ACTION_PREVIOUS)

    fun stop(context: Context) = send(context, ACTION_STOP)

    private fun send(context: Context, action: String) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, LocalPlaybackService::class.java).setAction(action),
        )
    }
}
