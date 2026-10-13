package com.yunpan.tingshu.ui.me

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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.yunpan.tingshu.R
import com.yunpan.tingshu.data.model.ScanDepth
import com.yunpan.tingshu.databinding.FragmentMeBinding
import com.yunpan.tingshu.ui.common.ScanDepths
import com.yunpan.tingshu.ui.me.MeViewModel
import com.google.android.material.snackbar.Snackbar
import android.widget.Toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeFragment : Fragment() {

    private var _b: FragmentMeBinding? = null
    private val b get() = _b!!
    private lateinit var vm: MeViewModel
    private lateinit var accountAdapter: AccountAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentMeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vm = ViewModelProvider(this)[MeViewModel::class.java]

        accountAdapter = AccountAdapter(
            onSetActive = { vm.setActive(it.id) },
            onRemove = { confirmRemove(it) }
        )
        b.accountList.layoutManager = LinearLayoutManager(context)
        b.accountList.adapter = accountAdapter

        // 粘贴时自动从整段文案拆分链接 + 提取码
        b.inputShareLink.addTextChangedListener(object : android.text.TextWatcher {
            private var backfilling = false
            override fun afterTextChanged(s: android.text.Editable?) {
                if (backfilling) return
                val text = s?.toString().orEmpty()
                if (text.length < 12) return
                val r = com.yunpan.tingshu.cloud139.share.ShareLinkExtractor.parse(text)
                if (r.link != null && r.link != text.trim()) {
                    backfilling = true
                    b.inputShareLink.setText(r.link)
                    b.inputShareLink.setSelection(r.link!!.length)
                    backfilling = false
                }
                if (r.passcode != null && b.inputSharePasswd.text?.toString().isNullOrEmpty()) {
                    b.inputSharePasswd.setText(r.passcode)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
        })

        // 打开分享（粘贴整段文案时已自动拆分链接/提取码）
        b.btnImportShare.setOnClickListener {
            vm.openShare(
                b.inputShareLink.text?.toString().orEmpty(),
                b.inputSharePasswd.text?.toString().orEmpty()
            )
        }

        // 默认递归深度
        b.cardScanDepth.setOnClickListener { showDepthSheet() }

        // 登录：全屏 WebView 手动登录（已修白屏），自动拦截 Authorization 存账号
        b.btnWebLogin.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), com.yunpan.tingshu.ui.login.LoginWebActivity::class.java))
        }

        // 启动自动续播开关
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                (requireContext().applicationContext as com.yunpan.tingshu.YunpanApp)
                    .locator.settingsStore.autoResumeOnLaunch.collect { enabled ->
                        b.switchAutoResume.setOnCheckedChangeListener(null)
                        b.switchAutoResume.isChecked = enabled
                        b.switchAutoResume.setOnCheckedChangeListener { _, checked ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                (requireContext().applicationContext as com.yunpan.tingshu.YunpanApp)
                                    .locator.settingsStore.setAutoResumeOnLaunch(checked)
                                (requireContext().applicationContext as com.yunpan.tingshu.YunpanApp)
                                    .locator.playbackManager.setAutoResume(checked)
                            }
                        }
                    }
            }
        }

        // 深色/浅色模式开关（默认浅色）
        val darkPrefs = requireContext().getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
        val isDark = darkPrefs.getBoolean("dark", false)
        b.switchDarkMode.setOnCheckedChangeListener(null)
        b.switchDarkMode.isChecked = isDark
        b.switchDarkMode.setOnCheckedChangeListener { _, checked ->
            darkPrefs.edit().putBoolean("dark", checked).apply()
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                if (checked) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        // 电池/后台运行引导
        b.cardBattery.setOnClickListener { showBatteryGuide() }

        // 听书分析入口
        b.cardAnalytics.setOnClickListener {
            findNavController().navigate(R.id.analyticsFragment)
        }

        // 查看崩溃日志（真机闪退定位）
        b.cardCrashLog.setOnClickListener { showCrashLog() }

        bindState()
    }

    private fun showBatteryGuide() {
        val ctx = requireContext()
        val ignoring = com.yunpan.tingshu.util.BatteryOptimize.isIgnoringBatteryOptimizations(ctx)
        val msgs = buildList {
            add("为保证后台/锁屏持续播放，建议允许本应用忽略电池优化。")
            if (!ignoring) add("即将弹出系统授权，请选择“允许”。")
            val autostart = com.yunpan.tingshu.util.BatteryOptimize.availableAutostartIntents(ctx)
            if (autostart.isNotEmpty()) {
                add("另请在系统设置中允许自启动：" + autostart.joinToString("、") { it.first })
            } else {
                add("如系统仍杀后台，请在最近任务里锁定本应用。")
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("后台播放引导")
            .setMessage(msgs.joinToString("\n"))
            .setPositiveButton(if (ignoring) "知道了" else "去允许") { _, _ ->
                if (!ignoring) {
                    runCatching {
                        startActivity(com.yunpan.tingshu.util.BatteryOptimize.requestIgnoreIntent(ctx))
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun bindState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collectLatest { s ->
                    accountAdapter.submit(s.accounts, s.activeId)
                    b.tvScanDepth.text = ScanDepths.label(s.defaultDepth)
                    b.btnImportShare.isEnabled = !s.busy

                    s.message?.let { msg ->
                        Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show()
                        vm.onMessageConsumed()
                    }

                    s.shareOpened?.let {
                        // 切到云盘 Tab 并由浏览页消费分享会话
                        findNavController().navigate(R.id.browseFragment)
                        vm.onShareOpenedConsumed()
                    }
                    s.needPasscodeLink?.let { link ->
                        vm.onNeedPasscodeConsumed()
                        showPasscodeDialog(link)
                    }
                }
            }
        }
    }

    private fun showCrashLog() {
        val ctx = requireContext()
        val files = com.yunpan.tingshu.util.CrashHandler.all(ctx)
        if (files.isEmpty()) {
            AlertDialog.Builder(ctx).setTitle("崩溃日志")
                .setMessage("暂无崩溃记录。")
                .setPositiveButton(R.string.common_ok, null).show()
            return
        }
        val latest = files.first()
        val text = runCatching { latest.readText() }.getOrElse { "读取失败" }
        val scroll = android.widget.ScrollView(ctx).apply {
            setPadding(36, 24, 36, 24)
            addView(android.widget.TextView(ctx).apply {
                this.text = text
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
            })
        }
        AlertDialog.Builder(ctx)
            .setTitle("崩溃日志（${files.size} 条，最新一条）")
            .setView(scroll)
            .setNeutralButton("复制全部") { _, _ ->
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash_log", text))
                Toast.makeText(ctx, "已复制崩溃日志，可粘贴发送", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.common_ok, null)
            .show()
    }

    /** 该分享需要提取码：弹窗输入后重试。 */
    private fun showPasscodeDialog(link: String) {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "请输入提取码"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(requireContext())
            .setTitle("需要提取码")
            .setView(input)
            .setPositiveButton("打开") { _, _ ->
                b.inputShareLink.setText(link)
                b.inputSharePasswd.setText(input.text.toString())
                vm.openShare(link, input.text.toString())
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmRemove(acc: com.yunpan.tingshu.data.model.CloudAccount) {        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.source_remove_confirm))
            .setPositiveButton(R.string.common_confirm) { _, _ -> vm.remove(acc.id) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showDepthSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheet = layoutInflater.inflate(R.layout.bottom_sheet_scan_depth, null)
        dialog.setContentView(sheet)

        val group = sheet.findViewById<android.widget.RadioGroup>(R.id.depthGroup)
        val current = vm.state.value.defaultDepth
        group.check(depthToRadioId(current))

        sheet.findViewById<View>(R.id.btnStartScan).setOnClickListener {
            val depth = radioIdToDepth(group.checkedRadioButtonId)
            vm.setDefaultDepth(depth)
            dialog.dismiss()
        }
        dialog.show()
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
        // 从 WebView 登录返回后刷新账号列表
        vm.refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
