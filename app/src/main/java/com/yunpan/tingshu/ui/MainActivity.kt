package com.yunpan.tingshu.ui

import android.Manifest
import android.content.pm.PackageManager
import coil.load
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import com.yunpan.tingshu.R
import com.yunpan.tingshu.YunpanApp
import com.yunpan.tingshu.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 单 Activity：NavHost + 悬浮迷你播放条 + 底部三 Tab。
 * - 进入全屏 playerFragment 时隐藏底部导航与迷你条，返回恢复。
 * - 启动时（API33+）申请 POST_NOTIFICATIONS。
 * - 迷你条观察 PlaybackManager.state：无播放时整条隐藏。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val pm by lazy { (applicationContext as YunpanApp).locator.playbackManager }

    /** 是否位于全屏播放页：此页同时隐藏底部导航与迷你播放条。 */
    private var onPlayerDestination = false

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                // 记住用户的拒绝选择：以后启动不再反复请求（仅"我的"页手动触发）
                notifPrefs().edit().putBoolean("denied", true).apply()
            }
        }

    private fun notifPrefs() =
        getSharedPreferences("notif_prefs", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        val bottomNav: BottomNavigationView = binding.bottomNav

        // 手动 tab 导航：每个 tab 固定 destination，绕开 setupWithNavController 的栈重建/串台问题
        val tabDest = mapOf(
            R.id.bookshelfFragment to R.id.bookshelfFragment,
            R.id.historyFragment to R.id.historyFragment,
            R.id.browseFragment to R.id.browseFragment,
            R.id.meFragment to R.id.meFragment
        )
        bottomNav.setOnItemSelectedListener { item ->
            val dest = tabDest[item.itemId] ?: return@setOnItemSelectedListener false
            runCatching {
                if (navController.currentDestination?.id != dest) {
                    navController.navigate(dest, null,
                        androidx.navigation.NavOptions.Builder()
                            .setLaunchSingleTop(true)
                            .setPopUpTo(navController.graph.id, inclusive = false, saveState = false)
                            .build())
                }
            }
            true
        }
        // 目的地变化时同步选中态
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val id = destination.id
            if (id in tabDest.values) {
                runCatching { bottomNav.menu.findItem(id)?.isChecked = true }
            }
            onPlayerDestination = id == R.id.playerFragment
            bottomNav.visibility = if (onPlayerDestination) View.GONE else View.VISIBLE
            binding.miniPlayerBar.root.visibility = View.GONE
        }

        setupMiniBar()
        setupColdResume()
        checkAccountExpiry()
    }

    /** 启动时温和提示账号有效期：24h 内将到期提醒，已到期引导去重新登录（本地数据不丢）。 */
    private fun checkAccountExpiry() {
        lifecycleScope.launch {
            runCatching {
                val acc = (applicationContext as YunpanApp).locator.accountStore.active()
                if (acc == null) return@launch
                when (val st = com.yunpan.tingshu.data.model.AccountExpiry.evaluate(
                    acc.expireAtMs, System.currentTimeMillis()
                )) {
                    is com.yunpan.tingshu.data.model.AccountExpiry.State.ExpiringSoon ->
                        Snackbar.make(
                            binding.root,
                            "登录凭证约 ${st.hoursLeft} 小时后到期，建议近期去「我的」页重新登录续期",
                            Snackbar.LENGTH_LONG
                        ).show()
                    is com.yunpan.tingshu.data.model.AccountExpiry.State.Expired ->
                        Snackbar.make(
                            binding.root,
                            "登录已过期，去「我的」页重新登录即可（歌单/历史不会丢失）",
                            Snackbar.LENGTH_LONG
                        ).show()
                    else -> {}
                }
            }
        }
    }

    /** 冷启动：有可恢复会话且开关开启时，prepare 到暂停态（迷你条出现，不自动出声）。 */
    private fun setupColdResume() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                // 同步"启动自动续播"开关设置（默认关）到播放管理器
                kotlinx.coroutines.flow.combine(
                    (application as com.yunpan.tingshu.YunpanApp).locator.settingsStore.autoResumeOnLaunch,
                    pm.restoredSession
                ) { enabled, info -> enabled to info }
                    .collect { (enabled, info) ->
                        pm.setAutoResume(enabled)
                        // 开关开且有可恢复会话且当前无队列：自动恢复上次播放（含进度）
                        if (info != null && enabled && !pm.state.value.hasQueue) {
                            pm.resumeLast(autoplay = true)
                            // 开关开：冷启动自动打开全屏播放器页面
                            runCatching {
                                androidx.core.os.HandlerCompat.createAsync(android.os.Looper.getMainLooper())
                                    .postDelayed({
                                        findNavController(R.id.nav_host_fragment)
                                            .navigate(R.id.playerFragment, null,
                                                androidx.navigation.NavOptions.Builder()
                                                    .setLaunchSingleTop(true).build())
                                    }, 400)
                            }
                        }
                    }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                // 用户曾拒绝过：不再每次启动都弹，尊重选择
                if (notifPrefs().getBoolean("denied", false)) return
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupMiniBar() {
        val mini = binding.miniPlayerBar

        // 整条点击 → 进全屏播放页
        mini.miniPlayerRoot.setOnClickListener {
            if (pm.state.value.hasQueue) {
                findNavController(R.id.nav_host_fragment).navigate(R.id.playerFragment)
            }
        }
        mini.miniPlayPause.setOnClickListener { pm.toggle() }
        mini.miniNext.setOnClickListener { pm.next() }

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                pm.state.collectLatest { st ->
                    val hasQueue = st.hasQueue && !onPlayerDestination
                    mini.root.visibility = if (hasQueue) View.VISIBLE else View.GONE
                    if (hasQueue) {
                        mini.miniTitle.text = st.current?.name ?: getString(R.string.mini_now_playing)
                        mini.miniAlbum.text = st.albumTitle
                        mini.miniPlayPause.setImageResource(
                            if (st.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        )
                        mini.miniTimer.visibility =
                            if (com.yunpan.tingshu.ui.common.TimerMapper.chipVisible(st.timer)) View.VISIBLE
                            else View.GONE
                        st.current?.let { loadMiniCover(it) }
                    }
                }
            }
        }
    }

    private var lastMiniCoverId: String? = null
    private fun loadMiniCover(file: com.yunpan.tingshu.data.model.CloudFile) {
        if (file.id == lastMiniCoverId) return
        lastMiniCoverId = file.id
        lifecycleScope.launch {
            val ref = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { (applicationContext as YunpanApp).locator.coverArtProvider.artworkFor(file) }.getOrNull()
            }
            when (ref) {
                is com.yunpan.tingshu.data.cover.ArtworkRef.Local -> {
                    binding.miniPlayerBar.miniCover.visibility = View.VISIBLE
                    binding.miniPlayerBar.miniCoverIcon.visibility = View.GONE
                    binding.miniPlayerBar.miniCover.load(android.net.Uri.parse("file://${ref.path}"))
                }
                is com.yunpan.tingshu.data.cover.ArtworkRef.Remote -> {
                    binding.miniPlayerBar.miniCover.visibility = View.VISIBLE
                    binding.miniPlayerBar.miniCoverIcon.visibility = View.GONE
                    binding.miniPlayerBar.miniCover.load(ref.url) {
                        ref.headers.forEach { (k, v) -> addHeader(k, v) }
                    }
                }
                null -> {
                    binding.miniPlayerBar.miniCover.visibility = View.GONE
                    binding.miniPlayerBar.miniCoverIcon.visibility = View.VISIBLE
                }
            }
        }
    }
}
