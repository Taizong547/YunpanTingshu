package com.yunpan.tingshu.ui.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.yunpan.tingshu.R
import com.yunpan.tingshu.YunpanApp
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.ScanDepth
import com.yunpan.tingshu.databinding.DialogScanProgressBinding
import com.yunpan.tingshu.databinding.FragmentBrowseBinding
import com.yunpan.tingshu.ui.common.ScanDepths
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BrowseFragment : Fragment() {

    private var _b: FragmentBrowseBinding? = null
    private val b get() = _b!!
    private lateinit var vm: BrowseViewModel
    private lateinit var adapter: BrowseAdapter

    // 长按"设置为封面"：待设置封面的音频文件
    private var pendingCoverFile: com.yunpan.tingshu.data.model.CloudFile? = null
    private val pickImageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val file = pendingCoverFile ?: return@registerForActivityResult
        uri ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = runCatching {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: return@launch
                val store = (requireContext().applicationContext as com.yunpan.tingshu.YunpanApp).locator.coverStore
                val coverId = store.importFromBytes(bytes)
                store.setFileCover(file.id, coverId)
            }
            ok.onSuccess {
                com.google.android.material.snackbar.Snackbar.make(
                    b.root, "封面已设置", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                ).show()
            }.onFailure {
                com.google.android.material.snackbar.Snackbar.make(
                    b.root, "封面设置失败：${it.message}", com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    /** 长按音频文件：弹出操作菜单，含"设置为封面"。 */
    private fun onItemLongClick(file: com.yunpan.tingshu.data.model.CloudFile) {
        if (file.isDir) { vm.addToBookshelf(file); return }
        val options = arrayOf("设置为封面", "加入书架")
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        pendingCoverFile = file
                        pickImageLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
                    1 -> vm.addToBookshelf(file)
                }
            }
            .show()
    }
    private val pm by lazy { (requireContext().applicationContext as YunpanApp).locator.playbackManager }

    private var scanDialog: AlertDialog? = null

    // 排序/搜索状态（内存即可，列表是浏览态）
    private var query: String = ""
    private var sortKey: SortKey = SortKey.NAME
    private var ascending: Boolean = true

    private fun visibleItems(): List<CloudFile> =
        BrowseListSorter.apply(vm.state.value.items, query, sortKey, ascending)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentBrowseBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vm = ViewModelProvider(this)[BrowseViewModel::class.java]

        adapter = BrowseAdapter(
            onItemClick = { onItemClick(it) },
            onPlayAll = { showDepthSheet(it) },
            onCollect = { vm.addToBookshelf(it) },
            onLongClick = { onItemLongClick(it) }
        )
        val lm = LinearLayoutManager(context)
        b.recyclerView.layoutManager = lm
        b.recyclerView.adapter = adapter

        // 不分页：一次加载该文件夹全部内容，滚到底不再触发网络请求

        b.swipeRefresh.setOnRefreshListener { vm.refresh() }
        // 返回箭头：先回上一级文件夹，到根目录了再退出（不再跳到历史 tab）
        b.toolbar.setNavigationOnClickListener {
            val depth = vm.state.value.breadcrumb.size
            if (depth > 1) vm.navigateBreadcrumb(depth - 2)
            else requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        b.emptyAction.setOnClickListener {
            findNavController().navigate(R.id.meFragment)
        }

        vm.initialize()
        bindState()
        bindPlayback()
        bindSourceSwitcher()
        bindListControls()
    }

    /** 搜索框实时过滤 + 排序按钮循环切换（名称/大小/时间，升降序）。 */
    private fun bindListControls() {
        b.inputSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString().orEmpty()
                adapter.submit(visibleItems(), pm.state.value.current?.id)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
        })
        b.btnSort.setOnClickListener { cycleSort() }
    }

    private fun cycleSort() {
        // 名称↓ → 名称↑ → 大小↓ → 时间↓ → 名称↓
        when {
            sortKey == SortKey.NAME && ascending -> { sortKey = SortKey.NAME; ascending = false }
            sortKey == SortKey.NAME -> { sortKey = SortKey.SIZE; ascending = false }
            sortKey == SortKey.SIZE -> { sortKey = SortKey.TIME; ascending = false }
            else -> { sortKey = SortKey.NAME; ascending = true }
        }
        val arrow = if (ascending) "↑" else "↓"
        b.btnSort.text = when (sortKey) {
            SortKey.NAME -> "按名称$arrow"
            SortKey.SIZE -> "按大小$arrow"
            SortKey.TIME -> "按时间$arrow"
        }
        adapter.submit(visibleItems(), pm.state.value.current?.id)
    }

    /** 顶部来源切换：个人云盘 + 各已保存分享源。 */
    private fun bindSourceSwitcher() {
        val settings = (requireContext().applicationContext as com.yunpan.tingshu.YunpanApp).locator.settingsStore
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.shareSources.collect { saved ->
                    b.sourceGroup.removeAllViews()
                    val personal = com.google.android.material.chip.Chip(requireContext()).apply {
                        text = "个人云盘"
                        isCheckable = true
                        setOnClickListener { vm.switchToCloud() }
                    }
                    b.sourceGroup.addView(personal)
                    saved.forEach { s ->
                        val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                            text = s.name
                            isCheckable = true
                            setOnClickListener { vm.switchToShare(s) }
                        }
                        b.sourceGroup.addView(chip)
                    }
                }
            }
        }
    }

    private fun onItemClick(f: CloudFile) {
        if (f.isDir) {
            vm.enterFolder(f)
        } else {
            vm.onAudioClicked(f)
        }
    }

    private fun bindState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collectLatest { s ->
                    b.toolbar.title = s.source?.title ?: getString(R.string.title_browse)
                    renderBreadcrumb(s.breadcrumb)

                    adapter.submit(visibleItems(), pm.state.value.current?.id)

                    // 四态：加载/刷新/空/错
                    b.swipeRefresh.isRefreshing = s.refreshing
                    b.loadingView.visibility = if (s.loading) View.VISIBLE else View.GONE

                    val showEmpty = s.items.isEmpty() && !s.loading && !s.refreshing
                    b.emptyView.visibility = if (showEmpty) View.VISIBLE else View.GONE
                    if (s.needsAccount) {
                        b.emptyText.text = getString(R.string.browse_no_account)
                        b.emptyAction.visibility = View.VISIBLE
                    } else if (s.error != null) {
                        b.emptyText.text = s.error
                        b.emptyAction.visibility = View.GONE
                    } else {
                        b.emptyText.text = getString(R.string.browse_empty)
                        b.emptyAction.visibility = View.GONE
                    }

                    s.error?.let {
                        Snackbar.make(b.root, it, Snackbar.LENGTH_LONG).show()
                        vm.onErrorConsumed()
                    }

                    s.message?.let {
                        Snackbar.make(b.root, it, Snackbar.LENGTH_SHORT).show()
                        vm.onMessageConsumed()
                    }

                    // 扫描进度对话框
                    renderScanDialog(s.scan)

                    // 待播放：调播放引擎并进播放页
                    s.pendingPlay?.let { pp ->
                        pm.playScannedTracks(pp.tracks, 0, pp.albumTitle)
                        vm.onPendingPlayConsumed()
                        findNavController().navigate(R.id.action_browse_to_player)
                    }
                    s.pendingCurrentPlay?.let { pc ->
                        pm.playScannedTracks(pc.tracks, pc.startIndex, pc.albumTitle)
                        vm.onPendingCurrentPlayConsumed()
                        findNavController().navigate(R.id.action_browse_to_player)
                    }
                }
            }
        }
    }

    private fun bindPlayback() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pm.state.collectLatest { st ->
                    adapter.submit(visibleItems(), st.current?.id)
                }
            }
        }
    }

    private fun renderBreadcrumb(crumb: List<BreadcrumbEntry>) {
        b.breadcrumbContainer.removeAllViews()
        // 完整显示每一层路径（横向滚动，不再省略成 …）
        val displayNames = crumb.map { it.name }
        displayNames.forEachIndexed { index, name ->
            val isLast = index == displayNames.size - 1
            val tv = TextView(context).apply {
                text = name
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        context,
                        if (isLast) com.yunpan.tingshu.R.color.onSurface
                        else com.yunpan.tingshu.R.color.primary
                    )
                )
                textSize = 13f
                setPadding(8, 8, 8, 8)
                typeface = android.graphics.Typeface.SANS_SERIF
                if (name == "…") {
                    isClickable = false
                } else {
                    // 映射回原始层级索引（去重后通常一一对应）
                    val rawIndex = crumb.indexOfFirst { it.name.trim() == name }
                    isClickable = rawIndex in 0..<crumb.size - 1
                    setOnClickListener { vm.navigateBreadcrumb(rawIndex) }
                }
            }
            b.breadcrumbContainer.addView(tv)
            if (!isLast) {
                val sep = TextView(context).apply {
                    text = "›"
                    setTextColor(androidx.core.content.ContextCompat.getColor(context, com.yunpan.tingshu.R.color.onSurfaceVariant))
                    textSize = 13f
                    typeface = android.graphics.Typeface.SANS_SERIF
                }
                b.breadcrumbContainer.addView(sep)
            }
        }
    }

    private fun renderScanDialog(scan: ScanState?) {
        if (scan == null) {
            scanDialog?.dismiss()
            scanDialog = null
            return
        }
        if (scanDialog == null) {
            val dialogBinding = DialogScanProgressBinding.inflate(layoutInflater)
            dialogBinding.btnCancelScan.setOnClickListener { vm.cancelScan() }
            scanDialog = AlertDialog.Builder(requireContext())
                .setView(dialogBinding.root)
                .setCancelable(false)
                .create()
            scanDialog?.show()
        }
        scanDialog?.let { dlg ->
            dlg.findViewById<TextView>(R.id.tvScanCurrent)?.text = "当前：${scan.folderName}"
            dlg.findViewById<TextView>(R.id.tvScanCount)?.text =
                "已扫描 ${scan.visitedFolders} 个文件夹，发现 ${scan.foundTracks} 个音频"
        }
    }

    private fun showDepthSheet(folder: CloudFile?) {
        val dialog = BottomSheetDialog(requireContext())
        val sheet = layoutInflater.inflate(R.layout.bottom_sheet_scan_depth, null)
        dialog.setContentView(sheet)

        val group = sheet.findViewById<android.widget.RadioGroup>(R.id.depthGroup)
        // 默认勾选 SettingsStore 记忆值
        val default = runBlockingDefaultDepth()
        group.check(depthToRadioId(default))

        sheet.findViewById<View>(R.id.btnStartScan).setOnClickListener {
            val depth = radioIdToDepth(group.checkedRadioButtonId)
            if (sheet.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.chkSetDefault).isChecked) {
                vm.setDefaultDepthFromFragment(depth)
            }
            dialog.dismiss()
            vm.playWholeFolder(folder, depth)
        }
        dialog.show()
    }

    private var cachedDefault: ScanDepth? = null
    private fun runBlockingDefaultDepth(): ScanDepth {
        cachedDefault?.let { return it }
        val d = kotlinx.coroutines.runBlocking { vm.defaultDepth() }
        cachedDefault = d
        return d
    }

    private fun depthToRadioId(d: ScanDepth): Int = when (d) {
        ScanDepth.CURRENT -> R.id.depthCurrent
        ScanDepth.LEVEL1 -> R.id.depthLevel1
        ScanDepth.LEVEL2 -> R.id.depthLevel2
        ScanDepth.LEVEL3 -> R.id.depthLevel3
        ScanDepth.LEVEL5 -> R.id.depthLevel5
        ScanDepth.ALL -> R.id.depthAll
    }

    private fun radioIdToDepth(id: Int): ScanDepth = when (id) {
        R.id.depthCurrent -> ScanDepth.CURRENT
        R.id.depthLevel1 -> ScanDepth.LEVEL1
        R.id.depthLevel2 -> ScanDepth.LEVEL2
        R.id.depthLevel3 -> ScanDepth.LEVEL3
        R.id.depthLevel5 -> ScanDepth.LEVEL5
        else -> ScanDepth.ALL
    }

    override fun onResume() {
        super.onResume()
        vm.reloadIfNeeded()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scanDialog?.dismiss()
        scanDialog = null
        _b = null
    }
}
