package io.github.xhzair.refresholdphone.ui

import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.xhzair.refresholdphone.R
import io.github.xhzair.refresholdphone.databinding.ItemLauncherAppBinding

class LauncherAppAdapter(
    private val apps: List<LauncherApp>,
    private val onClick: (LauncherApp) -> Unit,
) : RecyclerView.Adapter<LauncherAppAdapter.Holder>() {

    class Holder(val binding: ItemLauncherAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemLauncherAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = apps.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = apps[position]
        with(holder.binding) {
            appLabel.text = app.label
            appIcon.setImageResource(R.drawable.ic_artwork)
            try {
                appIcon.setImageDrawable(root.context.packageManager.getApplicationIcon(app.packageName))
            } catch (_: PackageManager.NameNotFoundException) {
                // Package removed between refresh and bind; keep the fallback glyph.
            } catch (_: SecurityException) {
                // Visibility changed after refresh.
            }
            root.contentDescription = app.label
            root.setOnClickListener { onClick(app) }
        }
    }
}
