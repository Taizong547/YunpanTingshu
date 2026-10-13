package com.yunpan.tingshu.ui.bookshelf

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.yunpan.tingshu.R
import com.yunpan.tingshu.YunpanApp
import com.yunpan.tingshu.data.db.BookshelfEntity
import com.yunpan.tingshu.databinding.FragmentBookshelfBinding
import com.yunpan.tingshu.databinding.ItemBookshelfBinding
import com.yunpan.tingshu.ui.common.Fmt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 书架页：列表 + 点击播放 + 长按（重命名/删除）+ 下拉刷新。 */
class BookshelfFragment : Fragment() {

    private var _b: FragmentBookshelfBinding? = null
    private val b get() = _b!!
    private lateinit var vm: BookshelfViewModel
    private lateinit var adapter: BookshelfAdapter
    private val pm by lazy { (requireContext().applicationContext as YunpanApp).locator.playbackManager }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentBookshelfBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vm = ViewModelProvider(this)[BookshelfViewModel::class.java]

        adapter = BookshelfAdapter(
            onPlay = { vm.play(it) },
            onLongClick = { showItemMenu(it) }
        )
        b.bookshelfList.layoutManager = LinearLayoutManager(context)
        b.bookshelfList.adapter = adapter

        b.swipeRefresh.setOnRefreshListener { vm.refresh() }

        bindState()
    }

    private fun bindState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collectLatest { s ->
                    currentItems = s.items
                    adapter.submit(s.items)
                    b.emptyView.visibility = if (s.items.isEmpty() && !s.loading) View.VISIBLE else View.GONE
                    b.swipeRefresh.isRefreshing = s.loading

                    s.message?.let {
                        Snackbar.make(b.root, it, Snackbar.LENGTH_SHORT).show()
                        vm.onMessageConsumed()
                    }
                    s.error?.let {
                        Snackbar.make(b.root, it, Snackbar.LENGTH_LONG).show()
                        vm.onErrorConsumed()
                    }
                    s.pendingPlay?.let { pp ->
                        pm.playScannedTracks(pp.tracks, 0, pp.albumTitle, pp.origin)
                        vm.onPendingPlayConsumed()
                        findNavController().navigate(R.id.action_bookshelf_to_player)
                    }
                }
            }
        }
    }

    private fun showItemMenu(item: BookshelfEntity) {
        val options = arrayOf(
            getString(R.string.bookshelf_rename),
            "加入歌单",
            getString(R.string.bookshelf_delete),
            "批量选择删除",
            "清空书架（全部）"
        )
        AlertDialog.Builder(requireContext())
            .setTitle(item.folderName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(item)
                    1 -> showAddToPlaylist(item)
                    2 -> confirmDelete(item)
                    3 -> showBatchSelect()
                    4 -> confirmClearAll()
                }
            }
            .show()
    }

    private var currentItems: List<BookshelfEntity> = emptyList()

    /** 批量多选删除（checkbox 列表 + 全选 + 删除）。 */
    private fun showBatchSelect() {
        val list = currentItems
        if (list.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setMessage("书架为空").setPositiveButton(R.string.common_confirm, null).show()
            return
        }
        val labels = list.map { it.folderName }.toTypedArray()
        val checked = BooleanArray(list.size)
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("勾选要删除的条目")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("删除选中") { _, _ ->
                val ids = list.filterIndexed { i, _ -> checked[i] }.map { it.id }
                if (ids.isNotEmpty()) vm.deleteByIds(ids)
            }
            .setNeutralButton("全选") { _, _ ->
                checked.indices.forEach { checked[it] = true }
                showBatchSelectRefill(checked)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBatchSelectRefill(checked: BooleanArray) {
        val list = currentItems
        val labels = list.map { it.folderName }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("已选 ${checked.count { it }} 项（再点删除）")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("删除选中") { _, _ ->
                val ids = list.filterIndexed { i, _ -> checked[i] }.map { it.id }
                if (ids.isNotEmpty()) vm.deleteByIds(ids)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 一键清空书架（弹确认框）。 */
    private fun confirmClearAll() {
        AlertDialog.Builder(requireContext())
            .setMessage("确定清空整个书架？该操作不可撤销。")
            .setPositiveButton(R.string.common_confirm) { _, _ -> vm.clearAll() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 把本书加入某歌单：列出已有歌单 + 新建歌单入口。 */
    private fun showAddToPlaylist(item: BookshelfEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            val playlists = vm.listPlaylists()
            val labels = playlists.map { it.name }.toMutableList()
            labels.add("＋ 新建歌单")
            val arr = labels.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("加入歌单")
                .setItems(arr) { _, which ->
                    if (which == arr.size - 1) {
                        showCreatePlaylistDialog(item)
                    } else {
                        val pl = playlists[which]
                        vm.addToPlaylist(pl.id, item)
                    }
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    private fun showCreatePlaylistDialog(item: BookshelfEntity) {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "歌单名称，如：言情小说"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("新建歌单")
            .setView(input)
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val id = vm.createPlaylist(input.text.toString())
                    if (id != null) vm.addToPlaylist(id, item)
                    else Snackbar.make(b.root, "歌单名称不能为空", Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showRenameDialog(item: BookshelfEntity) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(item.folderName)
            setSelection(text.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.bookshelf_rename)
            .setView(input)
            .setPositiveButton(R.string.common_confirm) { _, _ -> vm.rename(item, input.text.toString()) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmDelete(item: BookshelfEntity) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.bookshelf_delete_confirm) + "「${item.folderName}」？")
            .setPositiveButton(R.string.common_confirm) { _, _ -> vm.delete(item) }
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

/** 书架列表适配器。 */
class BookshelfAdapter(
    private val onPlay: (BookshelfEntity) -> Unit,
    private val onLongClick: (BookshelfEntity) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<BookshelfAdapter.VH>() {

    private val items = mutableListOf<BookshelfEntity>()

    fun submit(list: List<BookshelfEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemBookshelfBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBookshelfBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding
        b.itemName.text = item.folderName
        val sourceLabel = if (item.source == "SHARE") "分享" else "云盘"
        val progress = ((item.positionMs * 100.0) / (item.totalMs.coerceAtLeast(1))).toInt().coerceIn(0, 100)
        b.itemSub.text = if (item.totalMs > 0) {
            "$sourceLabel · ${Fmt.stats(item.totalMs)} · ${progress}%"
        } else {
            "$sourceLabel · 未开始"
        }
        b.itemProgress.progress = progress
        b.root.setOnClickListener { onPlay(item) }
        b.btnPlay.setOnClickListener { onPlay(item) }
        b.root.setOnLongClickListener { onLongClick(item); true }
    }

    override fun getItemCount(): Int = items.size
}
