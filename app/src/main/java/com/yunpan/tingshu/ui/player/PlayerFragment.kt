package com.yunpan.tingshu.ui.player

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.yunpan.tingshu.R
import com.yunpan.tingshu.YunpanApp
import com.yunpan.tingshu.databinding.FragmentPlayerBinding
import android.net.Uri
import coil.load
import coil.request.ImageRequest
import com.yunpan.tingshu.data.cover.ArtworkRef
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.player.PlayMode
import com.yunpan.tingshu.player.TimerConfig
import com.yunpan.tingshu.ui.common.Fmt
import com.yunpan.tingshu.ui.common.TimerMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerFragment : Fragment() {

    private var _b: FragmentPlayerBinding? = null
    private val b get() = _b!!
    private val pm by lazy { (requireContext().applicationContext as YunpanApp).locator.playbackManager }

    private var userSeeking = false
    private var vinylAnimator: ObjectAnimator? = null

    private var pickedScope = com.yunpan.tingshu.ui.common.CoverScope.THIS_ALBUM

    private val pickImageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = runCatching {
                val resolver = requireContext().contentResolver
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: return@launch
                val store = (requireContext().applicationContext as YunpanApp).locator.coverStore
                val file = pm.state.value.current ?: return@launch
                val coverId = store.importFromBytes(bytes)
                val (scope, keys) = com.yunpan.tingshu.ui.common.CoverScopeKeys.resolve(
                    pickedScope, file.id,
                    file.parentToken?.takeIf { it.isNotBlank() } ?: file.parentId,
                    pm.queueSnapshot().map { it.parentToken ?: it.parentId }
                )
                store.batchSet(scope, keys, coverId)
            }
            ok.onSuccess {
                lastCoveredFileId = null
                pm.state.value.current?.let { loadCover(it) }
                Snackbar.make(b.root, "封面已设置", Snackbar.LENGTH_SHORT).show()
            }.onFailure {
                Snackbar.make(b.root, "封面设置失败：${it.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentPlayerBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.btnCollapse.setOnClickListener { findNavController().popBackStack() }
        b.btnPrev.setOnClickListener { pm.prev() }
        b.btnPlayPause.setOnClickListener { pm.toggle() }
        b.btnNext.setOnClickListener { pm.next() }
        b.btnMode.setOnClickListener { pm.cyclePlayMode() }
        b.btnTimer.setOnClickListener { showSleepSheet() }
        b.btnSpeed.setOnClickListener { showSpeedSheet() }
        b.btnQueueBottom.setOnClickListener { showQueueSheet() }
        b.btnMore.setOnClickListener { showMoreSheet() }
        b.btnCancelTimer.setOnClickListener { pm.cancelSleep() }
        b.btnExtend.setOnClickListener { pm.extendSleep() }

        b.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && b.seekBar.max > 0) {
                    val total = b.seekBar.max.toLong()
                    val dur = lastDurationMs
                    if (dur > 0) b.tvCurrent.text = Fmt.clock(progress * dur / total)
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                userSeeking = false
                val dur = lastDurationMs
                if (dur > 0) pm.seekTo(b.seekBar.progress * dur / b.seekBar.max.toLong())
            }
        })

        setupVinyl()
        bindState()
    }

    @Volatile private var lastDurationMs: Long = 0L
    private var lastError: String? = null
    private var lastCoveredFileId: String? = null

    /** 用 coverArtProvider 解析后 Coil 加载大封面；null 保持主题占位。 */
    private fun loadCover(file: CloudFile) {
        if (file.id == lastCoveredFileId) return
        lastCoveredFileId = file.id
        val coverArt = (requireContext().applicationContext as YunpanApp).locator.coverArtProvider
        viewLifecycleOwner.lifecycleScope.launch {
            val ref = withContext(Dispatchers.IO) { runCatching { coverArt.artworkFor(file) }.getOrNull() }
            when (ref) {
                is ArtworkRef.Local -> {
                    b.ivCover.visibility = View.VISIBLE
                    b.ivCover.load(Uri.parse("file://${ref.path}"))
                }
                is ArtworkRef.Remote -> {
                    b.ivCover.visibility = View.VISIBLE
                    b.ivCover.load(ref.url) {
                        ref.headers.forEach { (k, v) -> addHeader(k, v) }
                    }
                }
                null -> b.ivCover.visibility = View.GONE
            }
        }
    }

    private fun setupVinyl() {
        vinylAnimator = ObjectAnimator.ofFloat(b.ivVinyl, "rotation", 0f, 360f).apply {
            duration = 20_000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }
    }

    private fun bindState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pm.state.collectLatest { st ->
                    b.playerTitle.text = st.current?.name ?: getString(R.string.player_no_track)
                    b.tvAlbum.text = st.albumTitle
                    b.tvLyrics.text = "暂无歌词"
                    b.btnSpeed.text = speedLabel(st.speed)
                    updateSkipHint()
                    st.current?.let { loadCover(it) }
                    b.btnPlayPause.setImageResource(
                        if (st.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )

                    // 黑胶旋转：播放时转，暂停即停
                    if (st.isPlaying) vinylAnimator?.start() else vinylAnimator?.pause()

                    lastDurationMs = st.durationMs
                    if (!userSeeking && st.durationMs > 0) {
                        b.seekBar.progress = (st.positionMs * b.seekBar.max / st.durationMs).toInt()
                    }
                    b.tvCurrent.text = Fmt.clock(st.positionMs)
                    b.tvTotal.text = Fmt.clock(st.durationMs)

                    // 循环三态图标（底排按钮）
                    when (st.playMode) {
                        PlayMode.SEQUENTIAL -> {
                            b.btnMode.setImageResource(R.drawable.ic_repeat)
                        }
                        PlayMode.REPEAT_ONE -> {
                            b.btnMode.setImageResource(R.drawable.ic_repeat_one)
                        }
                        PlayMode.SHUFFLE -> {
                            b.btnMode.setImageResource(R.drawable.ic_shuffle)
                        }
                    }

                    // 定时 chip
                    val timerVisible = TimerMapper.chipVisible(st.timer)
                    b.timerChipRow.visibility = if (timerVisible) View.VISIBLE else View.GONE
                    if (timerVisible) {
                        b.chipTimer.text = TimerMapper.chipText(st.timer)
                        b.btnExtend.visibility = if (TimerMapper.canExtend(st.timer)) View.VISIBLE else View.GONE
                    }

                    // 常驻错误条 + 瞬时 Snackbar（直链/播放失败不再静默）
                    if (st.error != null) {
                        b.tvError.visibility = View.VISIBLE
                        b.tvError.text = st.error
                        if (st.error != lastError) {
                            lastError = st.error
                            Snackbar.make(b.root, st.error, Snackbar.LENGTH_LONG).show()
                        }
                    } else {
                        b.tvError.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun speedLabel(speed: Float): String = when (speed) {
        0.75f -> "0.75x"
        1.0f -> "1.0x"
        1.25f -> "1.25x"
        1.5f -> "1.5x"
        2.0f -> "2.0x"
        else -> String.format("%.2fx", speed)
    }

    /** 顶部跳过提示条（配置了跳过才显示）。 */
    private fun updateSkipHint() {
        val intro = pm.skipIntroMs.value
        val outro = pm.skipOutroMs.value
        if (intro > 0 || outro > 0) {
            b.tvSkipHint.visibility = View.VISIBLE
            b.tvSkipHint.text = buildString {
                if (intro > 0) append("片头跳过 ${intro / 1000}s")
                if (outro > 0) { if (isNotEmpty()) append("   ·   "); append("片尾跳过 ${outro / 1000}s") }
            }
        } else {
            b.tvSkipHint.visibility = View.GONE
        }
    }

    /** 智能跳过设置：分别选片头/片尾跳过秒数（档位 0/15/30/60/90）。 */
    private fun showSkipSettings() {
        val ctx = requireContext()
        val settings = (ctx.applicationContext as YunpanApp).locator.settingsStore
        val presets = com.yunpan.tingshu.player.SkipTiming.PRESETS_SEC
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 32, 40, 16)
        }
        fun group(title: String, currentSec: Int, onPick: (Int) -> Unit) {
            val label = android.widget.TextView(ctx).apply {
                text = title
                textSize = 15f
                setPadding(0, 16, 0, 8)
            }
            container.addView(label)
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            presets.forEach { sec ->
                val btn = com.google.android.material.button.MaterialButton(
                    ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = if (sec == 0) "不跳过" else "${sec}s"
                    minWidth = 0
                    insetTop = 0; insetBottom = 0
                    setPadding(24, 14, 24, 14)
                    if (sec == currentSec) {
                        backgroundTintList = android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(ctx, R.color.primary)
                        )
                        setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.onPrimary))
                    }
                    setOnClickListener {
                        onPick(sec)
                        updateSkipHint()
                    }
                }
                row.addView(btn)
            }
            container.addView(row)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val introCur = settings.skipIntroSec.first()
            val outroCur = settings.skipOutroSec.first()
            group("片头跳过（每集开头自动跳过）", introCur) { sec ->
                viewLifecycleOwner.lifecycleScope.launch { settings.setSkipIntroSec(sec) }
            }
            group("片尾跳过（接近结尾自动切下一集）", outroCur) { sec ->
                viewLifecycleOwner.lifecycleScope.launch { settings.setSkipOutroSec(sec) }
            }
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("智能跳过片头/片尾")
                .setView(container)
                .setPositiveButton(R.string.common_ok, null)
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    private fun showSpeedSheet() {
        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val dialog = BottomSheetDialog(requireContext())
        val container = LinearLayout(requireContext()).apply {            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
        }
        val group = RadioGroup(context)
        speeds.forEach { s ->
            val rb = RadioButton(context).apply {
                text = speedLabel(s)
                textSize = 16f
                setPadding(16, 24, 16, 24)
                isChecked = s == pm.state.value.speed
                setOnClickListener { pm.setSpeed(s); dialog.dismiss() }
            }
            group.addView(rb)
        }
        container.addView(group)
        dialog.setContentView(container)
        dialog.show()
    }

    private fun showSleepSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheet = layoutInflater.inflate(R.layout.bottom_sheet_sleep_timer, null)
        dialog.setContentView(sheet)

        fun apply(cfg: TimerConfig) { pm.setSleep(cfg); dialog.dismiss() }

        // 自定义时长（滚轮）入口。sheet 根是 ScrollView，只能有一个直接子 View，
        // 因此把按钮插到内部 LinearLayout 顶部，避免 "ScrollView can host only one direct child" 崩溃。
        val container = (sheet as android.view.ViewGroup).getChildAt(0) as? android.view.ViewGroup
        val customBtn = com.google.android.material.button.MaterialButton(
            requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "⏱ 自定义时长（滚轮选择）"
        }
        container?.addView(customBtn, 0)
        customBtn.setOnClickListener {
            dialog.dismiss()
            showWheelTimer { minutes -> pm.setSleep(TimerConfig.Minutes(minutes)) }
        }

        sheet.findViewById<View>(R.id.min15).setOnClickListener { apply(TimerMapper.minutes(15)) }
        sheet.findViewById<View>(R.id.min30).setOnClickListener { apply(TimerMapper.minutes(30)) }
        sheet.findViewById<View>(R.id.min45).setOnClickListener { apply(TimerMapper.minutes(45)) }
        sheet.findViewById<View>(R.id.min60).setOnClickListener { apply(TimerMapper.minutes(60)) }
        sheet.findViewById<View>(R.id.min90).setOnClickListener { apply(TimerMapper.minutes(90)) }
        sheet.findViewById<View>(R.id.btnEndEpisode).setOnClickListener { apply(TimerConfig.EndOfEpisode) }
        sheet.findViewById<View>(R.id.ep1).setOnClickListener { apply(TimerMapper.episodes(1)) }
        sheet.findViewById<View>(R.id.ep2).setOnClickListener { apply(TimerMapper.episodes(2)) }
        sheet.findViewById<View>(R.id.ep3).setOnClickListener { apply(TimerMapper.episodes(3)) }
        sheet.findViewById<View>(R.id.ep5).setOnClickListener { apply(TimerMapper.episodes(5)) }
        sheet.findViewById<View>(R.id.ep10).setOnClickListener { apply(TimerMapper.episodes(10)) }
        sheet.findViewById<View>(R.id.btnEpCustom).setOnClickListener {
            val n = sheet.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.epCustomInput)
                .text?.toString()?.toIntOrNull()
            if (n != null && n in 1..99) apply(TimerMapper.episodes(n))
            else Snackbar.make(b.root, "请输入 1-99 之间的集数", Snackbar.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    /** 播放队列弹层：完整队列、当前项高亮、点击跳转。 */
    private fun showQueueSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 24, 16, 32)
        }
        val title = android.widget.TextView(requireContext()).apply {
            val st = pm.state.value
            text = "播放队列（${st.albumTitle} · 共 ${st.size} 集）"
            textSize = 18f
            setPadding(0, 0, 0, 16)
        }
        container.addView(title)

        val baseItems = pm.queueSnapshot()
        var sortedByName = false
        fun currentItems(): List<com.yunpan.tingshu.data.model.CloudFile> =
            if (sortedByName) baseItems.sortedBy { it.name } else baseItems
        var shownItems = currentItems()
        val cur = pm.state.value.position

        val rv = androidx.recyclerview.widget.RecyclerView(requireContext()).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.72f).toInt()
            )
        }

        // 按名称排序切换
        val sortBtn = android.widget.TextView(requireContext()).apply {
            text = "⇅ 按名称排序"
            setPadding(0, 0, 0, 12)
            setTextColor(androidx.core.content.ContextCompat.getColor(context, com.yunpan.tingshu.R.color.playerAccent))
            setOnClickListener {
                sortedByName = !sortedByName
                text = if (sortedByName) "⇅ 恢复原始顺序" else "⇅ 按名称排序"
                shownItems = currentItems()
                rv.adapter?.notifyDataSetChanged()
            }
        }
        container.addView(sortBtn)
        rv.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            inner class VH(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
                val root: android.widget.LinearLayout = v.findViewById(R.id.queueItemRoot)
                val indicator: View = v.findViewById(R.id.queueIndicator)
                val idx: android.widget.TextView = v.findViewById(R.id.tvQueueIndex)
                val name: android.widget.TextView = v.findViewById(R.id.tvQueueName)
            }
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
                val v = layoutInflater.inflate(R.layout.item_queue_track, parent, false)
                return VH(v)
            }
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val h = holder as VH
                val f = shownItems[position]
                h.idx.text = "${position + 1}"
                h.name.text = f.name
                val isCur = position == cur
                h.indicator.visibility = if (isCur) View.VISIBLE else View.INVISIBLE
                h.root.setOnClickListener { pm.jumpTo(position); dialog.dismiss() }
            }
            override fun getItemCount() = shownItems.size
        }
        container.addView(rv)
        dialog.setContentView(container)
        dialog.show()
    }

    /** 封面设置：先选作用范围（本集/本专辑/队列），再选来源（相册/清除）。 */
    private fun showCoverSheet() {
        val scopes = arrayOf("本集", "本专辑（整个文件夹，多集共用）", "当前播放队列")
        val scopeKeys = com.yunpan.tingshu.ui.common.CoverScope.values()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("选择作用范围")
            .setItems(scopes) { _, which ->
                pickedScope = scopeKeys[which]
                showCoverSource()
            }
            .show()
    }

    private fun showCoverSource() {
        val sources = arrayOf("从手机相册选择", "清除封面")
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("封面来源")
            .setItems(arrayOf("从手机相册选择", "从云盘选图", "清除封面")) { _, which ->
                when (which) {
                    0 -> pickImageLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest.Builder()
                            .setMediaType(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)
                            .build()
                    )
                    1 -> showCloudImagePicker()
                    2 -> viewLifecycleOwner.lifecycleScope.launch {
                        val file = pm.state.value.current ?: return@launch
                        val store = (requireContext().applicationContext as YunpanApp).locator.coverStore
                        val (scope, keys) = com.yunpan.tingshu.ui.common.CoverScopeKeys.resolve(
                            pickedScope, file.id,
                            file.parentToken?.takeIf { it.isNotBlank() } ?: file.parentId,
                            pm.queueSnapshot().map { it.parentToken ?: it.parentId }
                        )
                        keys.forEach { store.clear(scope, it) }
                        lastCoveredFileId = null
                        loadCover(file)
                        Snackbar.make(b.root, "封面已清除", Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    /** 续播设置：切换自动续播开关。 */
    private fun showResumeSettings() {
        val settings = (requireContext().applicationContext as YunpanApp).locator.settingsStore
        lifecycleScope.launch {
            val current = settings.autoResumeOnLaunch.first()
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("续播设置")
                .setMessage("启动时自动恢复上次播放（默认开）")
                .setPositiveButton(if (current) "关闭自动续播" else "开启自动续播") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        settings.setAutoResumeOnLaunch(!current)
                        pm.setAutoResume(!current)
                        Snackbar.make(b.root, "已" + if (!current) "开启" else "关闭", Snackbar.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    /** 云盘选图：列当前曲目所在文件夹的图片，选中后下载字节→import→按范围设置封面。 */
    private fun showCloudImagePicker() {
        val ctx = requireContext()
        val app = ctx.applicationContext as YunpanApp
        val file = pm.state.value.current ?: return
        val loading = android.widget.ProgressBar(ctx)
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("从云盘选图")
            .setView(loading)
            .setNegativeButton(R.string.common_cancel, null)
            .show()
        viewLifecycleOwner.lifecycleScope.launch {
            val images = withContext(Dispatchers.IO) {
                runCatching {
                    if (file.source == com.yunpan.tingshu.data.model.FileSource.SHARE) {
                        val pca = file.parentToken ?: "root"
                        app.locator.shareRepository.listShareFolder(
                            file.shareLinkId ?: "", pca, file.sharePasswd ?: ""
                        ).items.filter { !it.isDir && it.thumbnailUrl.isNotBlank() }
                    } else {
                        val active = app.locator.accountStore.active() ?: return@withContext emptyList()
                        app.locator.cloud139Repository
                            .listFolder(active, file.parentToken ?: file.parentId)
                            .items.filter { !it.isDir && com.yunpan.tingshu.ui.common.ImageFiles.isImageName(it.name) }
                    }
                }.getOrElse { emptyList() }
            }
            if (images.isEmpty()) {
                Snackbar.make(b.root, "当前文件夹没有图片", Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            // 网格选图
            val rv = androidx.recyclerview.widget.RecyclerView(ctx).apply {
                layoutManager = androidx.recyclerview.widget.GridLayoutManager(ctx, 3)
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.5f).toInt()
                )
            }
            rv.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                inner class VH(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
                    val iv = v as android.widget.ImageView
                }
                override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
                    val iv = android.widget.ImageView(ctx).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        layoutParams = androidx.recyclerview.widget.GridLayoutManager.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 220
                        )
                    }
                    return VH(iv)
                }
                override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                    val img = images[position]
                    val headers = if (img.source == com.yunpan.tingshu.data.model.FileSource.SHARE) emptyMap()
                    else app.locator.cloud139Repository.streamHeaders()
                    (holder as VH).iv.load(img.thumbnailUrl) {
                        crossfade(true)
                        headers.forEach { (k, v) -> addHeader(k, v) }
                    }
                    holder.itemView.setOnClickListener {
                        viewLifecycleOwner.lifecycleScope.launch {
                            applyCloudImageCover(img)
                        }
                    }
                }
                override fun getItemCount() = images.size
            }
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("选一张作为封面")
                .setView(rv)
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    /** 下载选中云盘图片字节 → import → 按已选作用范围写封面。 */
    private suspend fun applyCloudImageCover(img: CloudFile) {
        val ctx = requireContext()
        val app = ctx.applicationContext as YunpanApp
        val current = pm.state.value.current ?: return
        runCatching {
            val url = if (img.source == com.yunpan.tingshu.data.model.FileSource.SHARE) {
                img.thumbnailUrl
            } else {
                val active = app.locator.accountStore.active() ?: error("未登录")
                app.locator.cloud139Repository.getPlayUrl(active, img.id)
            }
            val headers = if (img.source == com.yunpan.tingshu.data.model.FileSource.SHARE) emptyMap()
            else app.locator.cloud139Repository.streamHeaders()
            val bytes = withContext(Dispatchers.IO) {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    connectTimeout = 15000; readTimeout = 15000
                }
                conn.inputStream.use { it.readBytes() }
            }
            val store = app.locator.coverStore
            val coverId = store.importFromBytes(bytes, com.yunpan.tingshu.data.cover.CoverStore.SOURCE_CLOUD)
            val (scope, keys) = com.yunpan.tingshu.ui.common.CoverScopeKeys.resolve(
                pickedScope, current.id,
                current.parentToken?.takeIf { it.isNotBlank() } ?: current.parentId,
                pm.queueSnapshot().map { it.parentToken ?: it.parentId }
            )
            store.batchSet(scope, keys, coverId)
            lastCoveredFileId = null
            loadCover(current)
        }.onSuccess {
            Snackbar.make(b.root, "封面已设置", Snackbar.LENGTH_SHORT).show()
        }.onFailure {
            Snackbar.make(b.root, "封面设置失败：${it.message}", Snackbar.LENGTH_LONG).show()
        }
    }

    /** 反射设置 NumberPicker 分割线颜色（跟随主题 primary），深色模式下可读。 */
    @android.annotation.SuppressLint("SoonBlockedPrivateApi")
    private fun tintNumberPicker(np: android.widget.NumberPicker) {
        runCatching {
            val divider = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primary)
            val f = android.widget.NumberPicker::class.java.getDeclaredField("mSelectionDivider")
            f.isAccessible = true
            f.set(np, android.graphics.drawable.ColorDrawable(divider))
        }
    }

    /** 滚轮定时：时(0–3)+分(0–59)双 NumberPicker，每跳一格音效+触感（尊重系统开关）。 */
    private fun showWheelTimer(onPick: (Int) -> Unit) {
        val ctx = requireContext()
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(48, 32, 48, 16)
        }
        val hours = android.widget.NumberPicker(ctx).apply {
            minValue = 0; maxValue = 3; value = 0; displayedValues = (0..3).map { "${it} 时" }.toTypedArray()
            wrapSelectorWheel = false
        }
        val mins = android.widget.NumberPicker(ctx).apply {
            minValue = 0; maxValue = 59; value = 30; displayedValues = (0..59).map { String.format("%02d 分", it) }.toTypedArray()
            wrapSelectorWheel = true
        }
        fun attachTick(np: android.widget.NumberPicker) {
            np.setOnValueChangedListener { view, oldVal, newVal ->
                if (newVal != oldVal) {
                    view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    view.performHapticFeedback(
                        android.view.HapticFeedbackConstants.CLOCK_TICK,
                        android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                    )
                }
            }
            tintNumberPicker(np)
        }
        attachTick(hours); attachTick(mins)
        row.addView(hours); row.addView(mins)

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("自定义停止时间")
            .setView(row)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton("确定") { _, _ ->
                val total = com.yunpan.tingshu.ui.common.SleepWheel.toMinutes(hours.value, mins.value)
                onPick(total)
            }
            .show()
    }
    private fun showMoreSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val options = listOf(
            "定时关闭" to { showSleepSheet() },
            "倍速播放" to { showSpeedSheet() },
            "跳过片头/片尾" to { showSkipSettings() },
            "循环模式" to { pm.cyclePlayMode() },
            "播放队列" to { showQueueSheet() },
            "封面设置" to { showCoverSheet() },
            "续播设置" to { showResumeSettings() },
            "音量统一（人声增强）" to {
                pm.cycleVolumeGain()
                com.google.android.material.snackbar.Snackbar.make(
                    b.root,
                    "音量增益：" + com.yunpan.tingshu.ui.common.VolumeGainMapper.label(pm.state.value.volumeGainDb),
                    com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                ).show()
            },
            "关于" to {
                Snackbar.make(b.root, "云盘听书 v2", Snackbar.LENGTH_SHORT).show()
            }
        )
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 48)
        }
        options.forEach { (label, action) ->
            val tv = TextView(requireContext()).apply {
                text = label
                textSize = 16f
                setPadding(16, 20, 16, 20)
                setOnClickListener { action(); dialog.dismiss() }
            }
            container.addView(tv)
        }
        dialog.setContentView(container)
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        vinylAnimator?.cancel()
        _b = null
    }
}
