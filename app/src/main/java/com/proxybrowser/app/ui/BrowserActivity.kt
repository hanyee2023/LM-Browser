package com.proxybrowser.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.proxybrowser.app.core.V2RayManager
import com.proxybrowser.app.data.NodeStore
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * 浏览器页（P5 + P1 重写）
 *
 * 关键设计：Android 11+ 已不允许多普通 App 通过 ProxyController 强制 WebView 走代理。
 * 这里改用 WebViewClient.shouldInterceptRequest：拦截 WebView 所有网络请求，
 * 用 HttpURLConnection + java.net.Proxy(SOCKS5) 走本地 xray (127.0.0.1:10808) 拉取，
 * 再包装成 WebResourceResponse 返回给 WebView 渲染。无需任何特殊权限。
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText

    /** SOCKS5 本地代理地址，跟 XrayConfig/V2RayManager 保持一致。 */
    private val socksHost = "127.0.0.1"
    private val socksPort = 10808

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val active = NodeStore.getActive(this)
        if (active == null) {
            startActivity(Intent(this, NodesActivity::class.java))
            finish()
            return
        }

        urlBar = EditText(this).apply {
            hint = "输入网址，如 https://www.youtube.com"
            setSingleLine()
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            webViewClient = ProxyingClient(socksHost, socksPort)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlBar)
            addView(
                webView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
        setContentView(root)

        // 启动 xray，然后打开 google（可改）
        V2RayManager.start(this, active)
        go("https://www.google.com")
    }

    private fun go(url: String) {
        val u = if (url.startsWith("http")) url else "https://$url"
        urlBar.setText(u)
        webView.loadUrl(u)
    }

    override fun onDestroy() {
        webView.destroy()
        V2RayManager.stop()
        super.onDestroy()
    }
}

/**
 * 把 WebView 所有请求用 SOCKS5 代理走本地 xray。
 * 请求/响应头 Content-Type、状态码、body 完整透传。
 */
class ProxyingClient(
    private val proxyHost: String,
    private val proxyPort: Int
) : WebViewClient() {

    private val proxy: Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyHost, proxyPort))
    private val tag = "ProxyingClient"

    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        view.loadUrl(url)
        return true
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url.toString()
        // 一些 data: / blob: / about: 不能走代理
        if (url.startsWith("data:") || url.startsWith("blob:") ||
            url.startsWith("about:") || url.startsWith("javascript:")
        ) return null
        return try {
            fetchViaSocks(request, url)
        } catch (e: Exception) {
            android.util.Log.w(tag, "fetch failed: $url  err=${e.message}")
            null // 失败时退回 WebView 默认行为
        }
    }

    private fun fetchViaSocks(
        req: WebResourceRequest,
        urlStr: String
    ): WebResourceResponse? {
        // 不在 finally 中 disconnect：WebView 异步消费 InputStream，过早断开会半路断流。
        // 让 HttpURLConnection 的 keep-alive 池和 GC 自行管理连接生命周期。
        val conn: HttpURLConnection = try {
            (URL(urlStr).openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = req.method ?: "GET"
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
                val headers = req.requestHeaders
                headers?.forEach { (k, v) ->
                    // Hop-by-hop 头不能转发；Host 由 HttpURLConnection 自己管理
                    if (k.equals("Host", true) || k.equals("Connection", true) ||
                        k.equals("Accept-Encoding", true)) return@forEach
                    v?.let { setRequestProperty(k, it) }
                }
                if (getRequestProperty("User-Agent") == null) {
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(tag, "openConnection failed: $urlStr  err=${e.message}")
            return null
        }
        return try {
            val code = conn.responseCode
            val stream: InputStream = try {
                conn.inputStream
            } catch (_: Exception) {
                conn.errorStream ?: return null
            }
            val mime = conn.contentType ?: "application/octet-stream"
            val charset = runCatching {
                mime.substringAfter("charset=", "").ifBlank { "utf-8" }
            }.getOrDefault("utf-8")
            // WebResourceResponse 没有 4 参版本，必须用 5 参：
            // (mimeType, encoding, statusCode, reasonPhrase, responseHeaders, data)
            val reason = conn.responseMessage ?: ""
            // 拿响应头失败也不能让整个 fetch 挂掉
            val headers: Map<String, List<String>> = try {
                conn.headerFields ?: emptyMap()
            } catch (_: Exception) { emptyMap() }
            // headerFields 的 key 可能为 null（HTTP/0.9），过滤掉；同时 value 是 List<String>，合并成单 String
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
}
