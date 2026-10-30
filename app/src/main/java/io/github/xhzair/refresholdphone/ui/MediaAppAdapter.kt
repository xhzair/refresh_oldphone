package io.github.xhzair.refresholdphone.ui

import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.xhzair.refresholdphone.R
import io.github.xhzair.refresholdphone.apps.MediaApp
import io.github.xhzair.refresholdphone.databinding.ItemMediaAppBinding

class MediaAppAdapter(private val words: HomeWords, private val onClick: (MediaApp) -> Unit) :
    ListAdapter<MediaApp, MediaAppAdapter.Holder>(object : DiffUtil.ItemCallback<MediaApp>() {
        override fun areItemsTheSame(old: MediaApp, new: MediaApp) = old.packageName == new.packageName
        override fun areContentsTheSame(old: MediaApp, new: MediaApp) = old == new
    }) {
    class Holder(val binding: ItemMediaAppBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemMediaAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = getItem(position)
        with(holder.binding) {
            appLabel.text = app.label
            val status = HomeUiModelMapper.appStatus(app, words)
            appStatus.text = status
            appIcon.setImageResource(R.drawable.ic_artwork)
            if (app.installed) {
                try { appIcon.setImageDrawable(root.context.packageManager.getApplicationIcon(app.packageName)) }
                catch (_: PackageManager.NameNotFoundException) { /* Package removed since catalog refresh. */ }
                catch (_: SecurityException) { /* Package visibility changed. */ }
            }
            root.contentDescription = root.context.getString(R.string.app_description, app.label, status)
            root.setOnClickListener { onClick(app) }
        }
    }
}
