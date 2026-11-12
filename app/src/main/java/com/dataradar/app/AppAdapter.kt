package com.dataradar.app

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter : RecyclerView.Adapter<AppAdapter.VH>() {

    private var items: List<AppUsage> = emptyList()
    private var max = 1L
    private val icons = HashMap<String, Drawable?>()

    fun submit(list: List<AppUsage>) {
        items = list
        max = list.firstOrNull()?.total?.coerceAtLeast(1L) ?: 1L
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val rank: TextView = v.findViewById(R.id.tvRank)
        val icon: ImageView = v.findViewById(R.id.ivIcon)
        val name: TextView = v.findViewById(R.id.tvName)
        val bar: ProgressBar = v.findViewById(R.id.bar)
        val sub: TextView = v.findViewById(R.id.tvSub)
        val total: TextView = v.findViewById(R.id.tvTotalItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        h.rank.text = "${position + 1}"
        h.name.text = it.label
        h.bar.progress = ((it.total * 1000) / max).toInt()
        h.sub.text = "↓ ${Fmt.bytes(it.rx)}    ↑ ${Fmt.bytes(it.tx)}"
        h.total.text = Fmt.bytes(it.total)

        val pm = h.itemView.context.packageManager
        val d = it.pkg?.let { p ->
            icons.getOrPut(p) {
                try { pm.getApplicationIcon(p) } catch (e: Exception) { null }
            }
        }
        h.icon.setImageDrawable(d ?: pm.defaultActivityIcon)
    }
}
