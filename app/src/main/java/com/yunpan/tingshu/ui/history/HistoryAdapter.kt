package com.yunpan.tingshu.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.yunpan.tingshu.data.db.HistoryEntity
import com.yunpan.tingshu.databinding.ItemHistoryBinding
import com.yunpan.tingshu.ui.common.Fmt
import com.yunpan.tingshu.ui.common.HistoryMapper

/** 历史列表适配器：封面、文件名、专辑·相对时间、进度、已听完、删除。 */
class HistoryAdapter(
    private val onClick: (HistoryEntity) -> Unit,
    private val onDelete: (HistoryEntity) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val items = mutableListOf<HistoryEntity>()
    private var nowMs: Long = System.currentTimeMillis()

    fun submit(list: List<HistoryEntity>) {
        this.items.clear()
        this.items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val h = items[position]
        val b = holder.binding
        b.itemName.text = h.fileName
        val rel = Fmt.relativeDay(h.lastPlayedAt, nowMs)
        b.itemAlbum.text = listOf(h.folderName, rel).filter { it.isNotBlank() }.joinToString(" · ")
        b.itemProgress.progress = HistoryMapper.progressPercent(h)
        b.itemProgressText.text =
            "${Fmt.clock(h.positionMs)} / ${Fmt.clock(h.durationMs)}"
        b.itemFinished.visibility = if (h.finished) android.view.View.VISIBLE else android.view.View.GONE
        b.root.setOnClickListener { onClick(h) }
        b.btnDelete.setOnClickListener { onDelete(h) }
    }

    override fun getItemCount(): Int = items.size
}
