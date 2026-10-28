package com.v4atu.dopaminesuppressor.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.v4atu.dopaminesuppressor.R
import com.v4atu.dopaminesuppressor.data.AppLimit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Una fila de la lista: app con launcher, su límite (si tiene) y el uso de hoy (si se conoce). */
data class AppRow(
    val packageName: String,
    val label: String,
    val limit: AppLimit?,
    val usedTodayMs: Long?,
    /** Lo que ha contado el servicio (diagnóstico temporal), solo para apps con límite. */
    val countedMs: Long? = null,
)

class AppsAdapter(
    private val packageManager: PackageManager,
    private val scope: CoroutineScope,
    private val onClick: (AppRow) -> Unit,
) : ListAdapter<AppRow, AppsAdapter.Holder>(Diff) {

    // Los íconos se cargan al mostrar cada fila y se guardan en un caché pequeño.
    private val icons = LruCache<String, Drawable>(ICON_CACHE_SIZE)

    inner class Holder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.app_icon)
        private val name: TextView = view.findViewById(R.id.app_name)
        private val subtitle: TextView = view.findViewById(R.id.app_subtitle)

        fun bind(row: AppRow) {
            name.text = row.label
            subtitle.text = subtitleFor(row)
            itemView.setOnClickListener { onClick(row) }
            bindIcon(row.packageName)
        }

        private fun bindIcon(pkg: String) {
            icon.tag = pkg
            val cached = icons.get(pkg)
            if (cached != null) {
                icon.setImageDrawable(cached)
                return
            }
            icon.setImageDrawable(null)
            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
                } ?: return@launch
                icons.put(pkg, loaded)
                if (icon.tag == pkg) icon.setImageDrawable(loaded)
            }
        }

        private fun subtitleFor(row: AppRow): String {
            val ctx = itemView.context
            val limit = row.limit
            val used = row.usedTodayMs?.let { DurationFormat.millis(it) }
            return when {
                limit == null && used == null -> ctx.getString(R.string.app_no_limit)
                limit == null -> ctx.getString(R.string.app_row_unlimited_used, used)
                else -> {
                    val res = if (limit.enabled) R.string.app_row_limited else R.string.app_row_limited_off
                    val base = ctx.getString(res, used ?: "—", DurationFormat.minutes(limit.dailyLimitMinutes))
                    val counted = row.countedMs?.let {
                        ctx.getString(R.string.app_row_counted, DurationFormat.millis(it))
                    }.orEmpty()
                    base + counted
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    private object Diff : DiffUtil.ItemCallback<AppRow>() {
        override fun areItemsTheSame(a: AppRow, b: AppRow) = a.packageName == b.packageName
        override fun areContentsTheSame(a: AppRow, b: AppRow) = a == b
    }

    private companion object {
        const val ICON_CACHE_SIZE = 80
    }
}
