package com.yunpan.tingshu.ui.analytics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yunpan.tingshu.R
import com.yunpan.tingshu.YunpanApp
import com.yunpan.tingshu.data.AlyticsFormat
import com.yunpan.tingshu.data.db.AlbumRank
import com.yunpan.tingshu.databinding.FragmentAnalyticsBinding
import kotlinx.coroutines.launch

class AnalyticsFragment : Fragment() {

    private var _b: FragmentAnalyticsBinding? = null
    private val b get() = _b!!
    private val repo by lazy { (requireContext().applicationContext as YunpanApp).locator.analyticsRepository }
    private var days = 7
    private val accountId = "default"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.rankList.layoutManager = LinearLayoutManager(context)
        b.chip7.setOnCheckedChangeListener { _, checked -> if (checked) { days = 7; reload() } }
        b.chip30.setOnCheckedChangeListener { _, checked -> if (checked) { days = 30; reload() } }
        b.chip7.isChecked = true
        reload()
    }

    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val ov = repo.overview(accountId)
                b.tvTotal.text = AlyticsFormat.duration(ov.totalMs)
                b.tvToday.text = AlyticsFormat.duration(ov.todayMs)
                b.tvLast7.text = AlyticsFormat.duration(ov.last7Ms)
                b.tvLast30.text = AlyticsFormat.duration(ov.last30Ms)

                val series = repo.dailySeries(accountId, days)
                b.barChart.bars = series.map {
                    BarChartView.Bar(it.date.takeLast(2), it.playedMs / 3_600_000f)
                }

                val comp = repo.completion(accountId, days)
                b.tvCompletion.text = "完播：${comp.completedEpisodes} 集 / 共 ${comp.totalEpisodes} 集（${(comp.rate * 100).toInt()}%）"

                val ranks = repo.topAlbums(accountId, days, 10)
                (b.rankList.adapter as? RankAdapter)?.submit(ranks) ?: run {
                    b.rankList.adapter = RankAdapter(ranks)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}

class RankAdapter(private var items: List<AlbumRank>) : RecyclerView.Adapter<RankAdapter.VH>() {
    fun submit(items: List<AlbumRank>) { this.items = items; notifyDataSetChanged() }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val rank: TextView = v.findViewById(R.id.itemRank)
        val name: TextView = v.findViewById(R.id.itemAlbumName)
        val meta: TextView = v.findViewById(R.id.itemAlbumMeta)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_album_rank, parent, false)
        return VH(v)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        holder.rank.text = "${position + 1}"
        holder.name.text = r.albumPath
        holder.meta.text = "${AlyticsFormat.duration(r.totalMs)} · ${r.totalEpisodes} 集"
    }
    override fun getItemCount() = items.size
}
