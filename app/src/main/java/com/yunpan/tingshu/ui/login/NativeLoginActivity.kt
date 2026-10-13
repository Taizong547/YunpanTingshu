package com.yunpan.tingshu.ui.login

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.CountDownTimer
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.yunpan.tingshu.YunpanApp
import com.yunpan.tingshu.databinding.ActivityNativeLoginBinding
import com.yunpan.tingshu.ui.login.AuthHeaderExtractor
import kotlinx.coroutines.launch

/**
 * 原生手机号+验证码登录页（不硬逆向 139 接口）。
 *
 * 思路：原生 UI 收集手机号/验证码；界面背后藏一个 WebView 加载 139 登录页，
 * 由页面自己处理加密与签名。用户点"获取验证码/登录"时，用 JS 把号码/验证码填进页面表单并点击，
 * 再复用 [AuthHeaderExtractor] 拦截请求头里的 Authorization，保存到 AccountStore。
 *
 * 注意：139 登录页 DOM 可能变化；JS 选择器为尽力匹配，页面改版时需更新 [JS_FILL_PHONE] 等常量。
 */
class NativeLoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityNativeLoginBinding
    @Volatile private var captured = false
    private var countdown: CountDownTimer? = null
    private var captchaDialog: android.app.Dialog? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityNativeLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnClose.setOnClickListener { finish() }

        // 输入合法才激活登录按钮
        val watcher = { _: android.text.Editable? -> refreshLoginEnabled() }
        b.inputPhone.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = refreshLoginEnabled()
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
        })
        b.inputCode.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = refreshLoginEnabled()
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
        })

        b.btnSendCode.setOnClickListener { onSendCode() }
        b.btnLogin.setOnClickListener { onLogin() }
        b.btnManualLogin.setOnClickListener { showManualWeb() }

        setupHiddenWeb()
    }

    /** 5 秒内没等到 token/短信信号，视为自动填表失败，提示并给出手动入口。 */
    private fun armSendTimeout() {
        b.btnSendCode.postDelayed({
            if (!captured) {
                b.tipText.text = "发送超时，可能页面结构已变化，请点下方手动登录"
                b.btnManualLogin.visibility = android.view.View.VISIBLE
                b.btnSendCode.isEnabled = true
                b.btnSendCode.text = "获取验证码"
            }
        }, 5000)
    }

    /** 检测到滑块/人机验证：把后台 WebView 临时装进一个小 Dialog，让用户手动拖滑块，过完关闭继续。 */
    private fun showCaptchaDialog() {
        if (captchaDialog != null) return
        b.tipText.text = "请在弹出的小窗口完成滑块验证"
        val ctx = this
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }
        // 把后台 WebView 重新挂进 Dialog
        (b.hiddenWeb.parent as? android.view.ViewGroup)?.removeView(b.hiddenWeb)
        b.hiddenWeb.visibility = android.view.View.VISIBLE
        container.addView(
            b.hiddenWeb,
            android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.5f).toInt()
            )
        )
        val done = com.google.android.material.button.MaterialButton(
            ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "我已完成验证，继续"
        }
        container.addView(done)

        captchaDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setView(container)
            .setCancelable(false)
            .create()
        done.setOnClickListener {
            captchaDialog?.dismiss()
            captchaDialog = null
            b.tipText.text = "验证完成，正在发送验证码…"
        }
        captchaDialog?.show()
    }

    /** 发送验证码后探测页面是否弹出了滑块/人机验证。 */
    private fun probeCaptcha() {
        b.hiddenWeb.postDelayed({
            b.hiddenWeb.evaluateJavascript(JS_DETECT_CAPTCHA) { r ->
                if (r?.trim('"') == "captcha" && !captured) {
                    runOnUiThread { showCaptchaDialog() }
                }
            }
        }, 1800)
    }

    private fun showManualWeb() {
        b.hiddenWeb.visibility = android.view.View.VISIBLE
        val lp = b.hiddenWeb.layoutParams
        lp.height = (resources.displayMetrics.heightPixels * 0.8f).toInt()
        b.hiddenWeb.layoutParams = lp
    }

    private fun refreshLoginEnabled() {
        val phone = b.inputPhone.text?.toString().orEmpty()
        val code = b.inputCode.text?.toString().orEmpty()
        b.btnLogin.isEnabled = phone.length == 11 && code.length == 6
    }

    private fun onSendCode() {
        val phone = b.inputPhone.text?.toString().orEmpty()
        if (phone.length != 11) {
            b.tipText.text = "请先输入11位手机号"
            return
        }
        b.tipText.text = "正在发送验证码…"
        // 等 WebView 页面就绪
        b.hiddenWeb.postDelayed({
            b.hiddenWeb.evaluateJavascript(JS_FILL_PHONE.replace("__PHONE__", phone), null)
            probeCaptcha()
        }, 1000)
        startCountdown()
        armSendTimeout()
    }

    private fun startCountdown() {
        b.btnSendCode.isEnabled = false
        countdown = object : CountDownTimer(60_000, 1000) {
            override fun onTick(ms: Long) {
                b.btnSendCode.text = "${ms / 1000 + 1}s后重发"
            }
            override fun onFinish() {
                b.btnSendCode.isEnabled = true
                b.btnSendCode.text = "获取验证码"
            }
        }.start()
    }

    private fun onLogin() {
        val phone = b.inputPhone.text?.toString().orEmpty()
        val code = b.inputCode.text?.toString().orEmpty()
        if (phone.length != 11 || code.length != 6) {
            b.tipText.text = "请填写完整手机号和验证码"
            return
        }
        b.tipText.text = "正在登录…"
        b.hiddenWeb.postDelayed({
            b.hiddenWeb.evaluateJavascript(JS_FILL_CODE.replace("__CODE__", code), null)
        }, 800)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupHiddenWeb() {
        b.hiddenWeb.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        WebView.setWebContentsDebuggingEnabled(true)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(b.hiddenWeb, true)

        b.hiddenWeb.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

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
                // 打印页面所有 input 占位符/类型，便于用 chrome://inspect 核对选择器
                view?.evaluateJavascript(
                    "(function(){var out=[];var els=document.querySelectorAll('input');" +
                        "for(var i=0;i<els.length;i++){out.push(els[i].type+'|'+(els[i].placeholder||'')+'|'+(els[i].name||''));}" +
                        "return out.join(' ;; ');})();"
                ) { dom -> android.util.Log.d("NativeLogin", "DOM inputs: $dom") }
                // 页面加载完成后兜底枚举 localStorage（与原 WebView 登录一致）
                view?.evaluateJavascript(
                    "(function(){try{var out='';for(var i=0;i<localStorage.length;i++){" +
                        "var k=localStorage.key(i);out+=k+'='+localStorage.getItem(k)+'\\n';}return out;}catch(e){return '';}})();"
                ) { result ->
                    if (captured) return@evaluateJavascript
                    val cleaned = result?.trim('"')?.replace("\\n", "\n").orEmpty()
                    val normalized = AuthHeaderExtractor.normalize(cleaned)
                    if (normalized != null) onCaptured(normalized)
                }
            }
        }
        b.hiddenWeb.loadUrl("https://yun.139.com/w/#/login")
    }

    private fun onCaptured(normalized: String) {
        if (captured) return
        captured = true
        lifecycleScope.launch {
            val ok = runCatching {
                (applicationContext as YunpanApp).locator.accountStore.addFromAuthorization(normalized)
            }
            ok.onSuccess { acc ->
                val phone = acc.phone.ifEmpty { acc.displayName }
                Toast.makeText(this@NativeLoginActivity, "登录成功：$phone", Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
            }.onFailure { e ->
                captured = false
                b.tipText.text = "保存失败：${e.message ?: "未知错误"}，请重试"
            }
        }
    }

    override fun onDestroy() {
        countdown?.cancel()
        runCatching { captchaDialog?.dismiss() }
        runCatching {
            b.hiddenWeb.stopLoading()
            b.hiddenWeb.removeAllViews()
            b.hiddenWeb.destroy()
        }
        super.onDestroy()
    }

    companion object {
        // 探测页面是否弹出滑块/人机验证（常见 class/id 关键字）。
        private const val JS_DETECT_CAPTCHA = """
(function(){try{
  var sel=['.nc_wrapper','.nc-container','.slider','.captcha','[class*="slider" i]','[class*="captcha" i]','[id*="captcha" i]','[id*="slider" i]'];
  for(var i=0;i<sel.length;i++){var n=document.querySelector(sel[i]);
    if(n&&n.offsetWidth>0&&n.offsetHeight>0) return 'captcha';}
  return 'none';
}catch(e){return 'none';}})();
"""
        // TODO：以下两个 JS 选择器按 139 登录页实际 DOM 微调；先做尽力匹配。
        private const val JS_FILL_PHONE = """
(function(){try{
  var el=document.querySelector('input[placeholder*="手机号"],input[type="tel"],input[name*="mobile" i]');
  if(!el) return 'no-phone';
  var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
  setter.call(el,'__PHONE__');
  el.dispatchEvent(new Event('input',{bubbles:true}));
  var btns=document.querySelectorAll('button,div,span,a');
  for(var i=0;i<btns.length;i++){var t=(btns[i].textContent||'').trim();
    if(/获取验证码|发送验证码|获取短信验证码/.test(t)&&t.length<=12){btns[i].click();return 'sent';}}
  return 'no-send-btn';
}catch(e){return 'err';}})();
"""
        private const val JS_FILL_CODE = """
(function(){try{
  var els=document.querySelectorAll('input');
  var code=null;
  for(var i=0;i<els.length;i++){var ph=els[i].placeholder||'';
    if(/验证码/.test(ph)&&!/获取/.test(ph)){code=els[i];break;}}
  if(!code) return 'no-code';
  var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
  setter.call(code,'__CODE__');
  code.dispatchEvent(new Event('input',{bubbles:true}));
  var btns=document.querySelectorAll('button,div,span,a');
  for(var j=0;j<btns.length;j++){var t=(btns[j].textContent||'').trim();
    if(/^登录$|登 录|立即登录/.test(t)&&t.length<=8){btns[j].click();return 'logging';}}
  return 'no-login-btn';
}catch(e){return 'err';}})();
"""
    }
}
