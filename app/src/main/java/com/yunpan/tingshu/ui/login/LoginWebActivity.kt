package com.yunpan.tingshu.ui.login

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.yunpan.tingshu.R
import com.yunpan.tingshu.YunpanApp
import kotlinx.coroutines.launch

/**
 * 一键登录（v1.4 再修）。
 *
 * v1.3 只改了桌面 UA，但真机 WebView 视口宽度仍是手机物理宽度（页面 viewport=width=device-width），
 * SPA 的响应式 CSS 按【视口宽度】走了窄屏布局——桌面版右侧登录卡片被隐藏，只剩左侧营销轮播，
 * 用户看到"不限速/免流量/安全/AI智能"营销页、没有输入框。
 *
 * v1.4 策略（WebView 桌面站点模式）：
 * - 桌面 Windows Chrome UA；
 * - useWideViewPort + loadWithOverviewMode：按桌面宽度布局并自动整体缩放适配窄屏；
 * - onPageFinished 注入：把 viewport 固定为 1280（强制走桌面 CSS 断点，登录卡片自然出现），
 *   并轮询手机号输入框，若仍被隐藏则逐祖先强制显示并滚动到视口；
 * - shouldInterceptRequest 抓 139 域请求头 Authorization；
 * - localStorage 全键枚举兜底 + cookie 兜底；
 * - 所有跳转留在内置 WebView；60s 未抓到给中文引导。
 */
class LoginWebActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var captured = false

    private val timeoutRunnable = Runnable {
        if (!captured) {
            Snackbar.make(
                web,
                "若已完成登录仍未自动获取凭证：请稍等几秒，或改用手动粘贴 Authorization（我的页 → 粘贴令牌）。",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login_web)

        web = findViewById(R.id.webView)
        findViewById<android.view.View>(R.id.btnClose).setOnClickListener { finish() }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // 手机版 UA：直接打开就是登录界面，没有桌面版营销轮播大图
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = MOBILE_CHROME_UA
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {

            /** 所有跳转都留在内置 WebView，绝不跳系统浏览器。 */
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (url.contains("yun.139.com") || url.contains("feixin.10086.cn") || url.contains("cmpassport")) {
                    val raw = request.requestHeaders?.entries
                        ?.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value
                    val normalized = AuthHeaderExtractor.normalize(raw)
                    if (normalized != null) onCaptured(normalized)
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (captured) return
                view?.evaluateJavascript(DESKTOP_LAYOUT_JS, null)
                // 1) 自动点击"短信登录"Tab（若页面提供）
                view?.evaluateJavascript(
                    "(function(){try{" +
                        "var els=document.querySelectorAll('div,span,a,li,button');" +
                        "for(var i=0;i<els.length;i++){var t=(els[i].textContent||'').trim();" +
                        "if(t.indexOf('短信登录')>=0&&t.length<=8){els[i].click();return 'clicked';}}" +
                        "return 'no-tab';}catch(e){return 'err';}})();"
                ) { }
                // 2) 兜底：枚举 localStorage 全部键，尝试每个值作为 Authorization
                view?.evaluateJavascript(
                    "(function(){try{" +
                        "var out='';for(var i=0;i<localStorage.length;i++){" +
                        "var k=localStorage.key(i);out+=k+'='+localStorage.getItem(k)+'\\n';}" +
                        "return out;}catch(e){return '';}})();"
                ) { result ->
                    if (captured) return@evaluateJavascript
                    val cleaned = result?.trim('"')?.replace("\\n", "\n").orEmpty()
                    val normalized = AuthHeaderExtractor.normalize(cleaned)
                    if (normalized != null) onCaptured(normalized)
                }
                // 3) cookie 兜底
                view?.let { v ->
                    val cookies = runCatching {
                        CookieManager.getInstance().getCookie(v.url.orEmpty()).orEmpty()
                    }.getOrDefault("")
                    if (cookies.isNotEmpty()) {
                        val normalized = AuthHeaderExtractor.normalize(cookies)
                        if (normalized != null) onCaptured(normalized)
                    }
                }
            }
        }

        web.loadUrl("https://yun.139.com/w/#/login")
        // 2 秒后若还没出现输入框，顶部提示用户手动点登录入口
        web.postDelayed({
            web.evaluateJavascript(
                "(function(){return document.querySelectorAll('input').length;})();"
            ) { r ->
                if (r?.trim('"') == "0" && !captured) showLoginHint()
            }
        }, 2000)
        handler.postDelayed(timeoutRunnable, 60_000)
    }

    /** 顶部悬浮提示：页面若还在营销/首页，引导用户自己点进登录。 */
    private fun showLoginHint() {
        val root = (web.parent as? android.view.ViewGroup) ?: return
        if (root.findViewWithTag<android.view.View>("loginHint") != null) return
        val tv = android.widget.TextView(this).apply {
            tag = "loginHint"
            text = "看不到登录框？请在页面上点击「登录」按钮进入"
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(24, 28, 24, 28)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { (this as? android.widget.FrameLayout.LayoutParams)?.topMargin = 40 }
        }
        root.addView(tv)
    }

    private fun onCaptured(normalized: String) {
        if (captured) return
        // Activity 已销毁则不处理，避免内存泄漏和崩溃
        if (isFinishing || isDestroyed) return
        captured = true
        handler.removeCallbacks(timeoutRunnable)
        lifecycleScope.launch {
            val ok = runCatching {
                (applicationContext as YunpanApp).locator.accountStore.addFromAuthorization(normalized)
            }
            // 协程恢复时再次检查，避免后台线程操作已销毁的Activity
            if (isFinishing || isDestroyed) return@launch
            ok.onSuccess { acc ->
                val phone = acc.phone.ifEmpty { acc.displayName }
                Toast.makeText(this@LoginWebActivity, "登录成功：$phone", Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
            }.onFailure { e ->
                captured = false
                Snackbar.make(
                    web,
                    "保存失败：${e.message ?: "未知错误"}，可改用手动粘贴",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    @Deprecated("系统返回键")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeoutRunnable)
        // WebView 在部分机型/已 detach 时 destroy 会抛异常，兜底避免崩溃
        runCatching {
            web.stopLoading()
            web.removeAllViews()
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        /** 桌面 Chrome UA。 */
        const val DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 手机版 Chrome UA（直接打开即登录界面，无营销轮播）。 */
        const val MOBILE_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * 登录页排版（v3 不再动登录卡片）。
         * 手机版 UA 下登录页本身就能正常显示、按钮事件正常。
         * 之前对卡片做 fixed 定位/缩放/改样式会破坏前端框架的事件监听，导致"获取验证码"点不动。
         * 现在只做：禁横向滚动 + 禁止缩放，完全不改登录卡片 DOM/样式/事件。
         */
        const val DESKTOP_LAYOUT_JS = """
(function(){try{
  document.body.style.overflowX='hidden';
  var m=document.querySelector('meta[name="viewport"]');
  if(m){m.setAttribute('content','width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no');}
}catch(e){}})();
"""
    }
}
