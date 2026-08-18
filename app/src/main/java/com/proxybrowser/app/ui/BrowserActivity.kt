package com.proxybrowser.app.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.proxybrowser.app.R
import com.proxybrowser.app.core.AdBlocker
import com.proxybrowser.app.core.Settings
import com.proxybrowser.app.core.UserScriptEngine
import com.proxybrowser.app.core.V2RayManager
import com.proxybrowser.app.core.VideoSniffer
import com.proxybrowser.app.data.NodeStore
import com.proxybrowser.app.data.ProxyNode
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors

/**
 * 浏览器主页（P1 + P2 + P3 + P4 + P5 重写）
 *
 * 视图结构（VIA 风格）：
 *   ┌─────────────────────────────────────┐
 *   │  ⌕ [https://..........]  ✕  📁     │   顶栏：搜索/地址 + 关闭 + 嗅探
 *   ├─────────────────────────────────────┤
 *   │  ▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░  36%   │   进度条
 *   ├─────────────────────────────────────┤
 *   │                                     │
 *   │           WebView 内容               │
 *   │                                     │
 *   ├─────────────────────────────────────┤
 *   │  ← → ⟳ 🏠 📡 ⚙                       │   底部：后退/前进/刷新/主页/嗅探/设置
 *   └─────────────────────────────────────┘
 *
 * 关键链路：
 *   1) onCreate：取当前选中节点 → 启动 xray → loadUrl(home.html)
 *   2) ProxyingClient.shouldInterceptRequest 拦截 → 走 SOCKS5 走 xray
 *   3) onPageStarted 注入油猴脚本（document_start 阶段）
 *   4) onPageFinished 注入视频嗅探钩子
 *   5) @JavascriptInterface 把嗅探到的视频 URL 写回 VideoSniffer
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var btnBack: ImageView
    private lateinit var btnForward: ImageView
    private lateinit var btnRefresh: ImageView
    private lateinit var btnHome: ImageView
    private lateinit var btnSniffer: ImageView
    private lateinit var btnSettings: ImageView
    private lateinit var btnClose: ImageView
    private lateinit var btnNodeLabel: TextView

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "pb-browser-io").apply { isDaemon = true } }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdBlocker.loadEnabled(this)
        VideoSniffer.load(this)

        val active = NodeStore.getActive(this)
        if (active == null) {
            startActivity(Intent(this, NodesActivity::class.java))
            finish()
            return
        }

        // xray 启动（容错：失败也不阻塞进入主页）
        val ok = V2RayManager.start(this, active)
        if (!ok) {
            Toast.makeText(this, getString(R.string.toast_xray_failed), Toast.LENGTH_LONG).show()
        }

        // ============== 视图构建 ==============
        urlBar = EditText(this).apply {
            hint = getString(R.string.hint_url)
            setSingleLine()
            setBackgroundResource(R.drawable.bg_url_bar)
            setPadding(36, 18, 36, 18)
            textSize = 14f
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ ->
                navigateTo(text.toString())
                true
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun afterTextChanged(s: Editable?) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                    refreshButtons()
                }
            })
        }

        // 在 EditText 内嵌入一个 search icon (左边)
        urlBar.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
        urlBar.compoundDrawablePadding = 6

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }

        btnClose = iconButton(R.drawable.ic_close) { urlBar.setText("") }
        btnSniffer = iconButton(R.drawable.ic_sniffer) { openSniffer() }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 12, 12, 4)
            addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnSniffer, lp(40, 40, 6, 0, 0, 0))
            addView(btnClose, lp(40, 40, 0, 0, 0, 0))
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            val customUa = Settings.userAgent(this@BrowserActivity)
            settings.userAgentString = customUa.ifEmpty {
                "Mozilla/5.0 (Linux; Android 13; ProxyBrowser) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }
            webViewClient = ProxyingClient(active)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }
            addJavascriptInterface(JsBridge(this@BrowserActivity), "PB")
        }

        // 底部工具栏
        btnBack = iconButton(R.drawable.ic_back) { if (webView.canGoBack()) webView.goBack() }
        btnForward = iconButton(R.drawable.ic_forward) { if (webView.canGoForward()) webView.goForward() }
        btnRefresh = iconButton(R.drawable.ic_refresh) { webView.reload() }
        btnHome = iconButton(R.drawable.ic_home) { loadHome() }
        btnSettings = iconButton(R.drawable.ic_settings) { openSettings() }

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            setBackgroundColor(getColor(R.color.bg))
            addView(btnBack, lp(0, 44, 1f, 0, 0, 0))
            addView(btnForward, lp(0, 44, 1f, 0, 0, 0))
            addView(btnRefresh, lp(0, 44, 1f, 0, 0, 0))
            addView(btnHome, lp(0, 44, 1f, 0, 0, 0))
            addView(btnSniffer, lp(0, 44, 1f, 0, 0, 0))
            addView(btnSettings, lp(0, 44, 1f, 0, 0, 0))
        }

        btnNodeLabel = TextView(this).apply {
            setPadding(16, 0, 16, 8)
            textSize = 11f
            setTextColor(getColor(R.color.text_tertiary))
            text = "代理：${active.name}"
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            addView(topBar)
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4))
            addView(btnNodeLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(View(this@BrowserActivity).apply {
                setBackgroundColor(getColor(R.color.divider))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })
            addView(bottomBar)
        }
        setContentView(root)
        refreshButtons()
        loadHome()
    }

    private fun iconButton(resId: Int, onClick: () -> Unit): ImageView {
        return ImageView(this).apply {
            setImageResource(resId)
            setBackgroundResource(R.drawable.bg_btn_ghost)
            setPadding(8, 8, 8, 8)
            setOnClickListener { onClick() }
        }
    }

    private fun lp(w: Int, h: Int, weight: Float, l: Int, t: Int, r: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            if (w == 0) 0 else dp(w),
            if (h == 0) 0 else dp(h),
            weight
        ).apply { setMargins(dp(l), dp(t), dp(r), 0) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun loadHome() {
        // 主页内嵌到 String 直接走 loadDataWithBaseURL，避免 file:// 的 JS Bridge / 拦截限制
        val html = assets.open("home.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        webView.loadDataWithBaseURL("https://pb.local/", html, "text/html", "utf-8", null)
    }

    private fun navigateTo(input: String) {
        val raw = input.trim()
        if (raw.isEmpty()) return
        val u = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.contains(' ') || !raw.contains('.') -> Settings.searchEngine() + Uri.encode(raw)
            else -> "https://" + raw
        }
        webView.loadUrl(u)
    }

    private fun refreshButtons() {
        btnBack.isEnabled = webView.canGoBack()
        btnBack.alpha = if (webView.canGoBack()) 1f else 0.3f
        btnForward.isEnabled = webView.canGoForward()
        btnForward.alpha = if (webView.canGoForward()) 1f else 0.3f
    }

    private fun openSniffer() {
        startActivity(Intent(this, SnifferActivity::class.java))
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        // 重新读设置（用户在设置页改了开关，回到这里生效）
        AdBlocker.loadEnabled(this)
        refreshButtons()
    }

    override fun onDestroy() {
        webView.destroy()
        V2RayManager.stop()
        super.onDestroy()
    }

    // ============================================================
    // WebViewClient：拦截所有请求走 xray
    // ============================================================
    private inner class ProxyingClient(private val node: ProxyNode) : WebViewClient() {

        private val proxy: Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808))
        private val tag = "ProxyingClient"

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            // 外部 scheme 不接管
            if (url.startsWith("mailto:") || url.startsWith("tel:") ||
                url.startsWith("intent:") || url.startsWith("magnet:") ||
                url.endsWith(".apk", true)
            ) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                return true
            }
            return false
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (url.startsWith("data:") || url.startsWith("blob:") ||
                url.startsWith("about:") || url.startsWith("javascript:")
            ) return null
            // 1) 广告拦截
            if (AdBlocker.shouldBlock(url)) {
                return emptyResponse()
            }
            // 2) 走代理
            return try {
                fetchViaSocks(request, url)
            } catch (e: Exception) {
                android.util.Log.w(tag, "fetch failed: $url  err=${e.message}")
                null
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (url != null) {
                urlBar.setText(url)
                urlBar.setSelection(url.length)
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            refreshButtons()
            // 油猴注入（如果开关打开）
            if (Settings.isUserScript(this@BrowserActivity)) {
                val scripts = UserScriptEngine.loadAll(this@BrowserActivity)
                val matched = UserScriptEngine.matches(url ?: "", scripts)
                if (matched.isNotEmpty()) {
                    val js = UserScriptEngine.buildInjection(matched)
                    if (js.isNotEmpty()) {
                        view.evaluateJavascript("(function(){$js})()", null)
                    }
                }
            }
            // 视频嗅探钩子
            if (Settings.isSniffer(this@BrowserActivity)) {
                view.evaluateJavascript(VideoSniffer.HOOK_JS, null)
            }
        }

        private fun fetchViaSocks(
            req: WebResourceRequest,
            urlStr: String
        ): WebResourceResponse? {
            val conn: HttpURLConnection = try {
                (URL(urlStr).openConnection(proxy) as HttpURLConnection).apply {
                    requestMethod = req.method ?: "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    val headers = req.requestHeaders
                    headers?.forEach { (k, v) ->
                        if (k.equals("Host", true) || k.equals("Connection", true) ||
                            k.equals("Accept-Encoding", true)) return@forEach
                        v?.let { setRequestProperty(k, it) }
                    }
                    if (getRequestProperty("User-Agent") == null) {
                        setRequestProperty(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 13; ProxyBrowser) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                        )
                    }
                    if (Settings.isDnt(this@BrowserActivity)) {
                        setRequestProperty("DNT", "1")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(tag, "openConnection failed: $urlStr  err=${e.message}")
                return null
            }
            return try {
                val code = conn.responseCode
                val stream: InputStream = try { conn.inputStream } catch (_: Exception) { conn.errorStream ?: return null }
                val mime = conn.contentType ?: "application/octet-stream"
                val charset = runCatching {
                    mime.substringAfter("charset=", "").ifBlank { "utf-8" }
                }.getOrDefault("utf-8")
                val reason = conn.responseMessage ?: ""
                val headers: Map<String, List<String>> = try { conn.headerFields ?: emptyMap() } catch (_: Exception) { emptyMap() }
                val safeHeaders: MutableMap<String, String> = headers
                    .mapNotNull { (k, v) -> if (k == null) null else k to v.joinToString(", ") }
                    .toMap()
                    .toMutableMap()
                WebResourceResponse(
                    mime.substringBefore(";"),
                    charset,
                    code,
                    reason,
                    safeHeaders,
                    stream
                )
            } catch (e: Exception) {
                android.util.Log.w(tag, "fetch error: $urlStr  err=${e.message}")
                try { conn.errorStream?.close() } catch (_: Exception) {}
                try { conn.inputStream?.close() } catch (_: Exception) {}
                null
            }
        }

        private fun emptyResponse(): WebResourceResponse {
            val empty: InputStream = "".byteInputStream()
            return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), empty)
        }
    }

    // ============================================================
    // JS Bridge：嗅探到的视频 URL 通过它回到 Java
    // ============================================================
    private inner class JsBridge(private val ctx: Context) {

        @JavascriptInterface
        fun report(json: String) {
            // 在 IO 线程上解析 + 落盘
            io.execute {
                runCatching {
                    val o = org.json.JSONObject(json)
                    val url = o.optString("url")
                    val type = o.optString("type", "video")
                    val pageUrl = o.optString("page", "")
                    val title = o.optString("title", "")
                    val ext = runCatching {
                        val u = Uri.parse(url)
                        (MimeMap.ext(u.path ?: "")) ?: ".bin"
                    }.getOrDefault(".bin")
                    if (url.isNotEmpty()) {
                        VideoSniffer.add(ctx, VideoSniffer.Media(url, type, pageUrl, title, 0, ext))
                        main.post { Toast.makeText(ctx, "嗅到视频：${ext}", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }

        @JavascriptInterface
        fun status() {
            val active = NodeStore.getActive(ctx) ?: return
            val json = "{\"name\":${quote(active.name)},\"type\":${quote(active.type.name)}}"
            webView.evaluateJavascript("document.getElementById('proxyLabel').textContent='代理：${escJs(active.name)}';" +
                "document.getElementById('proxyDetail').textContent='${active.type.name} · ${escJs(active.address)}:${active.port}';" +
                "document.getElementById('proxyDot').className='proxy-status on';", null)
        }

        @JavascriptInterface
        fun copyToClipboard(s: String) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("url", s))
            main.post { Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun escJs(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")
}

/** Content-Type 推测扩展名（仅用于嗅探展示，不做下载）。 */
private object MimeMap {
    fun ext(path: String): String? {
        val i = path.lastIndexOf('.')
        if (i < 0 || i < path.length - 6) return null
        return path.substring(i).lowercase()
    }
}
