package com.yunpan.tingshu.ui.browse

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.yunpan.tingshu.R
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.databinding.ItemBrowseBinding
import com.yunpan.tingshu.ui.common.Fmt

/** 浏览列表适配器：文件夹在前、音频在后；文件夹行尾「加入书架」「播放整个文件夹」。 */
class BrowseAdapter(
    private val onItemClick: (CloudFile) -> Unit,
    private val onPlayAll: (CloudFile) -> Unit,
    private val onCollect: (CloudFile) -> Unit,
    private val onLongClick: (CloudFile) -> Unit = {}
) : RecyclerView.Adapter<BrowseAdapter.VH>() {

    private val items = mutableListOf<CloudFile>()
    private var playingId: String? = null

    fun submit(list: List<CloudFile>, playingId: String?) {
        this.items.clear()
        this.items.addAll(list)
        this.playingId = playingId
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemBrowseBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBrowseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = items[position]
        val b = holder.binding
        b.itemName.text = f.name
        if (f.isDir) {
            b.itemIcon.setImageResource(R.drawable.ic_folder)
            b.itemSub.text = ""
            // 文件夹右侧只留星星收藏，去掉音符
            b.btnPlayAll.visibility = ViewGroup.GONE
            b.btnCollect.visibility = ViewGroup.VISIBLE
            b.itemPlaying.visibility = ViewGroup.GONE
        } else {
            b.itemIcon.setImageResource(R.drawable.ic_music_note)
            b.itemSub.text = Fmt.audioSub(f.durationSec, f.size)
            b.btnPlayAll.visibility = ViewGroup.GONE
            b.btnCollect.visibility = ViewGroup.GONE
            b.itemPlaying.visibility = if (f.id == playingId) ViewGroup.VISIBLE else ViewGroup.GONE
        }
        b.root.setOnClickListener { onItemClick(f) }
        b.root.setOnLongClickListener { onLongClick(f); true }
        b.btnPlayAll.setOnClickListener { onPlayAll(f) }
        b.btnCollect.setOnClickListener { onCollect(f) }
    }

    override fun getItemCount(): Int = items.size
}
