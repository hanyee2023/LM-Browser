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
import androidx.appcompat.app.AlertDialog
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

class BrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var proxyToggle: ImageView
    private lateinit var btnSearch: ImageView
    private lateinit var btnBack: ImageView
    private lateinit var btnForward: ImageView
    private lateinit var btnHome: ImageView
    private lateinit var btnTabs: ImageView
    private lateinit var btnSettings: ImageView
    private lateinit var btnRefresh: ImageView
    private lateinit var btnSniffer: ImageView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, "pb-io").apply { isDaemon = true } }

    // Tab management (simplified single-tab for now, structure ready for multi-tab)
    private var currentUrl = ""
    private val tabStack = mutableListOf<String>()

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

        val ok = V2RayManager.start(this, active)
        if (!ok) {
            Toast.makeText(this, "Proxy start failed", Toast.LENGTH_LONG).show()
        }

        // ============ Top bar ============
        urlBar = EditText(this).apply {
            hint = "Search or enter URL"
            setSingleLine()
            setBackgroundResource(R.drawable.bg_url_bar)
            setPadding(36, 14, 40, 14)
            textSize = 14f
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ ->
                navigateTo(text.toString())
                true
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { refreshNavButtons() }
            })
        }
        urlBar.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
        urlBar.compoundDrawablePadding = 6

        proxyToggle = makeIconBtn(R.drawable.dot_on) { toggleProxy() }
        btnSearch = makeIconBtn(R.drawable.ic_search) { searchText() }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 4)
            addView(proxyToggle, lp(36, 36, 0, 0, 0, 2))
            addView(urlBar, lp(0, 40, 1, 2, 0, 2))
            addView(btnSearch, lp(36, 36, 0, 2, 0, 0))
        }

        // ============ Progress bar ============
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }

        // ============ WebView ============
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.userAgentString = Settings.userAgent(this@BrowserActivity)
                .ifEmpty { "Mozilla/5.0 (Linux; Android 13; ProxyBrowser) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36" }
            webViewClient = ProxyingClient(active)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (title != null) currentUrl = title
                }
            }
            addJavascriptInterface(JsBridge(this@BrowserActivity), "PB")
        }

        // ============ Bottom bar ============
        btnBack = makeIconBtn(R.drawable.ic_back) { if (webView.canGoBack()) webView.goBack() }
        btnForward = makeIconBtn(R.drawable.ic_forward) { if (webView.canGoForward()) webView.goForward() }
        btnRefresh = makeIconBtn(R.drawable.ic_refresh) { webView.reload() }
        btnHome = makeIconBtn(R.drawable.ic_home) { loadHome() }
        btnSniffer = makeIconBtn(R.drawable.ic_sniffer) { startActivity(Intent(this@BrowserActivity, SnifferActivity::class.java)) }
        btnTabs = makeIconBtn(R.drawable.ic_bookmark) { showTabsDialog() }
        btnSettings = makeIconBtn(R.drawable.ic_settings) { startActivity(Intent(this@BrowserActivity, SettingsActivity::class.java)) }

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(4, 8, 4, 8)
            setBackgroundColor(getColor(R.color.bg))
            addView(btnBack, lp(0, 44, 1, 0, 0, 0))
            addView(btnForward, lp(0, 44, 1, 0, 0, 0))
            addView(btnRefresh, lp(0, 44, 1, 0, 0, 0))
            addView(btnHome, lp(0, 44, 1, 0, 0, 0))
            addView(btnSniffer, lp(0, 44, 1, 0, 0, 0))
            addView(btnTabs, lp(0, 44, 1, 0, 0, 0))
            addView(btnSettings, lp(0, 44, 1, 0, 0, 0))
        }

        // ============ Root layout ============
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            addView(topBar)
            addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(View(this@BrowserActivity).apply {
                setBackgroundColor(getColor(R.color.divider))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            })
            addView(bottomBar)
        }
        setContentView(root)
        refreshNavButtons()
        loadHome()
    }

    private fun makeIconBtn(resId: Int, onClick: () -> Unit): ImageView {
        return ImageView(this).apply {
            setImageResource(resId)
            setBackgroundResource(R.drawable.bg_btn_ghost)
            setPadding(10, 10, 10, 10)
            setOnClickListener { onClick() }
        }
    }

    private fun lp(w: Int, h: Int, weight: Int, l: Int, t: Int, r: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            if (w == 0) 0 else dp(w),
            if (h == 0) 0 else dp(h),
            weight.toFloat()
        ).apply { setMargins(dp(l), dp(t), dp(r), 0) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun loadHome() {
        val html = assets.open("home.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        webView.loadDataWithBaseURL("https://pb.local/", html, "text/html", "utf-8", null)
        currentUrl = ""
        urlBar.setText("")
    }

    private fun navigateTo(input: String) {
        val raw = input.trim()
        if (raw.isEmpty()) return
        val u = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.contains(' ') || !raw.contains('.') -> Settings.searchEngine() + Uri.encode(raw)
            else -> "https://" + raw
        }
        tabStack.add(u)
        webView.loadUrl(u)
    }

    private fun searchText() {
        val q = urlBar.text.toString().trim()
        if (q.isNotEmpty()) {
            navigateTo(q)
        } else {
            // Open search page
            navigateTo("")
        }
    }

    private fun refreshNavButtons() {
        btnBack.isEnabled = webView.canGoBack()
        btnBack.alpha = if (webView.canGoBack()) 1f else 0.3f
        btnForward.isEnabled = webView.canGoForward()
        btnForward.alpha = if (webView.canGoForward()) 1f else 0.3f
    }

    private fun toggleProxy() {
        val active = NodeStore.getActive(this) ?: return
        if (V2RayManager.isRunning()) {
            V2RayManager.stop()
            proxyToggle.setImageResource(R.drawable.dot_off)
            Toast.makeText(this, "Proxy disabled", Toast.LENGTH_SHORT).show()
        } else {
            if (V2RayManager.start(this, active)) {
                proxyToggle.setImageResource(R.drawable.dot_on)
                Toast.makeText(this, "Proxy enabled: ${active.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to start proxy", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showTabsDialog() {
        if (tabStack.isEmpty()) {
            Toast.makeText(this, "No tabs yet", Toast.LENGTH_SHORT).show()
            return
        }
        val items = tabStack.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Open Tabs")
            .setItems(items) { _, which ->
                val url = items[which]
                webView.loadUrl(url)
            }
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        AdBlocker.loadEnabled(this)
        refreshNavButtons()
    }

    override fun onDestroy() {
        webView.destroy()
        V2RayManager.stop()
        super.onDestroy()
    }

    // ============ WebViewClient ============
    private inner class ProxyingClient(private val node: ProxyNode) : WebViewClient() {
        private val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808))
        private val tag = "ProxyClient"

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (url.startsWith("mailto:") || url.startsWith("tel:") ||
                url.startsWith("intent:") || url.startsWith("magnet:") ||
                url.endsWith(".apk", true)
            ) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                return true
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            if (url.startsWith("data:") || url.startsWith("blob:") ||
                url.startsWith("about:") || url.startsWith("javascript:")
            ) return null
            if (AdBlocker.shouldBlock(url)) return emptyResponse()
            return try { fetchViaSocks(request, url) } catch (e: Exception) { null }
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
            refreshNavButtons()
            if (Settings.isUserScript(this@BrowserActivity)) {
                val scripts = UserScriptEngine.loadAll(this@BrowserActivity)
                val matched = UserScriptEngine.matches(url ?: "", scripts)
                if (matched.isNotEmpty()) {
                    val js = UserScriptEngine.buildInjection(matched)
                    if (js.isNotEmpty()) view.evaluateJavascript("(function(){$js})()", null)
                }
            }
            if (Settings.isSniffer(this@BrowserActivity)) {
                view.evaluateJavascript(VideoSniffer.HOOK_JS, null)
            }
        }

        private fun fetchViaSocks(req: WebResourceRequest, urlStr: String): WebResourceResponse? {
            val conn: HttpURLConnection = try {
                (URL(urlStr).openConnection(proxy) as HttpURLConnection).apply {
                    requestMethod = req.method ?: "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    req.requestHeaders?.forEach { (k, v) ->
                        if (k.equals("Host", true) || k.equals("Connection", true) || k.equals("Accept-Encoding", true)) return@forEach
                        v?.let { setRequestProperty(k, it) }
                    }
                    if (getRequestProperty("User-Agent") == null) {
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; ProxyBrowser) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                    }
                    if (Settings.isDnt(this@BrowserActivity)) setRequestProperty("DNT", "1")
                }
            } catch (e: Exception) { return null }
            return try {
                val code = conn.responseCode
                val stream: InputStream = try { conn.inputStream } catch (_: Exception) { conn.errorStream ?: return null }
                val mime = conn.contentType ?: "application/octet-stream"
                val charset = runCatching { mime.substringAfter("charset=", "").ifBlank { "utf-8" } }.getOrDefault("utf-8")
                val reason = conn.responseMessage ?: ""
                val headers: Map<String, List<String>> = try { conn.headerFields ?: emptyMap() } catch (_: Exception) { emptyMap() }
                val safeHeaders: MutableMap<String, String> = headers.mapNotNull { (k, v) -> if (k == null) null else k to v.joinToString(", ") }.toMap().toMutableMap()
                WebResourceResponse(mime.substringBefore(";"), charset, code, reason, safeHeaders, stream)
            } catch (e: Exception) {
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

    // ============ JS Bridge ============
    private inner class JsBridge(private val ctx: Context) {
        @JavascriptInterface
        fun report(json: String) {
            ioExecutor.execute {
                runCatching {
                    val o = org.json.JSONObject(json)
                    val url = o.optString("url")
                    val type = o.optString("type", "video")
                    val pageUrl = o.optString("page", "")
                    val title = o.optString("title", "")
                    val ext = runCatching {
                        val u = Uri.parse(url)
                        MimeMap.ext(u.path ?: "") ?: ".bin"
                    }.getOrDefault(".bin")
                    if (url.isNotEmpty()) {
                        VideoSniffer.add(ctx, VideoSniffer.Media(url, type, pageUrl, title, 0, ext))
                        mainHandler.post { Toast.makeText(ctx, "Found: $ext", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }

        @JavascriptInterface
        fun status() {
            val active = NodeStore.getActive(ctx) ?: return
            val nameEsc = active.name.replace("\\", "\\\\").replace("'", "\\'")
            val addrEsc = active.address.replace("\\", "\\\\").replace("'", "\\'")
            val js = "document.getElementById('proxyLabel').textContent='Proxy: ${nameEsc}';" +
                "document.getElementById('proxyDetail').textContent='${active.type.name} - ${addrEsc}:${active.port}';" +
                "document.getElementById('proxyDot').className='proxy-status on';"
            webView.evaluateJavascript(js, null)
        }

        @JavascriptInterface
        fun copyToClipboard(s: String) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("url", s))
            mainHandler.post { Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show() }
        }
    }
}

private object MimeMap {
    fun ext(path: String): String? {
        val i = path.lastIndexOf('.')
        if (i < 0 || i < path.length - 6) return null
        return path.substring(i).lowercase()
    }
}
