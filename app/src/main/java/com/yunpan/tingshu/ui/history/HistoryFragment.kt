package com.yunpan.tingshu.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.yunpan.tingshu.R
import com.yunpan.tingshu.YunpanApp
import com.yunpan.tingshu.data.db.HistoryEntity
import com.yunpan.tingshu.data.model.FileSource
import com.yunpan.tingshu.databinding.FragmentHistoryBinding
import com.yunpan.tingshu.player.PlayOrigin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _b: FragmentHistoryBinding? = null
    private val b get() = _b!!
    private lateinit var vm: HistoryViewModel
    private lateinit var adapter: HistoryAdapter
    private val pm by lazy { (requireContext().applicationContext as YunpanApp).locator.playbackManager }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentHistoryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vm = ViewModelProvider(this)[HistoryViewModel::class.java]

        adapter = HistoryAdapter(
            onClick = { vm.onItemClicked(it) },
            onDelete = { confirmDelete(it) }
        )
        b.historyList.layoutManager = LinearLayoutManager(context)
        b.historyList.adapter = adapter

        b.btnClear.setOnClickListener { confirmClear() }
        // 长按清除按钮：按时间范围清除（1小时/1天/7天）
        b.btnClear.setOnLongClickListener {
            val ranges = arrayOf("清除 1 小时前", "清除 1 天前", "清除 7 天前")
            AlertDialog.Builder(requireContext())
                .setTitle("按时间范围清除")
                .setItems(ranges) { _, which ->
                    val ms = when (which) {
                        0 -> 60 * 60 * 1000L
                        1 -> 24 * 60 * 60 * 1000L
                        else -> 7 * 24 * 60 * 60 * 1000L
                    }
                    vm.clearOlderThan(System.currentTimeMillis() - ms)
                }
                .show()
            true
        }

        bindState()
        bindResumeCard()
    }

    /** 冷启动可恢复会话 → 显示“继续收听 ▶”大按钮。 */
    private fun bindResumeCard() {
        b.btnResume.setOnClickListener {
            pm.resumeLast(autoplay = true)
            findNavController().navigate(R.id.action_history_to_player)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pm.restoredSession.collect { info ->
                    if (info == null) {
                        b.cardResume.visibility = View.GONE
                        return@collect
                    }
                    b.cardResume.visibility = View.VISIBLE
                    val mins = (info.positionMs / 60_000L)
                    val remain = info.positionMs % 60_000L / 1000L
                    b.tvResumeInfo.text =
                        "${info.albumTitle} · ${info.firstTrackName}（已听 ${mins}分${remain}秒，共 ${info.total} 集）"
                }
            }
        }
    }

    private fun bindState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collectLatest { s ->
                    b.tvToday.text = s.todayText
                    b.tvTotal.text = s.totalText
                    adapter.submit(s.items)
                    b.emptyView.visibility = if (s.items.isEmpty() && !s.loading) View.VISIBLE else View.GONE

                    s.message?.let {
                        Snackbar.make(b.root, it, Snackbar.LENGTH_LONG).show()
                        vm.onMessageConsumed()
                    }

                    s.pendingResume?.let { item ->
                        resume(item)
                        vm.onPendingResumeConsumed()
                    }
                }
            }
        }
    }

    private fun resume(item: HistoryEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.prepareResumeAccount(item)
            val file = vm.toResumeFile(item)
            val origin = if (item.source == FileSource.SHARE.name) {
                PlayOrigin.Share(item.shareLinkId ?: "", item.sharePasswd ?: "", item.folderName)
            } else {
                PlayOrigin.Cloud
            }
            pm.playTracks(listOf(file), 0, item.folderName, origin)
            findNavController().navigate(R.id.action_history_to_player)
        }
    }

    private fun confirmDelete(item: HistoryEntity) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.history_delete) + "？")
            .setPositiveButton(R.string.common_confirm) { _, _ -> vm.delete(item) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.history_clear_confirm)
            .setPositiveButton(R.string.common_confirm) { _, _ -> vm.clearAll() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        vm.load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
