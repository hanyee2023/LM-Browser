package com.proxybrowser.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ProxyController
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.proxybrowser.app.core.V2RayManager
import com.proxybrowser.app.data.NodeStore
import java.util.concurrent.Executors

/**
 * 浏览器页：读取已选中节点 -> 启动本地 xray 代理 -> 仅本 WebView 走代理。
 * 没选节点时自动回到节点列表。
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val active = NodeStore.getActive(this)
        if (active == null) {
            startActivity(Intent(this, NodesActivity::class.java))
            finish()
            return
        }

        urlBar = EditText(this).apply {
            hint = "输入网址，如 https://www.google.com"
            setSingleLine()
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false // 允许网页内视频播放
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    view.loadUrl(url)
                    return true
                }
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlBar)
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        // 启动 xray，并把代理指向本 WebView（系统全局代理不动，只动 ProxyController）
        if (V2RayManager.start(this, active)) {
            applyProxy()
        }
        go("https://www.google.com")
    }

    private fun go(url: String) {
        val u = if (url.startsWith("http")) url else "https://$url"
        urlBar.setText(u)
        webView.loadUrl(u)
    }

    /** 让本 WebView 走本地 xray 代理（API 26+） */
    private fun applyProxy() {
        if (Build.VERSION.SDK_INT >= 26) {
            ProxyController.getInstance().setProxyOverride(
                Executors.newSingleThreadExecutor(),
                listOf("PROXY ${V2RayManager.localProxyAddress()}"),
                emptyList(),
                { },
                { }
            )
        }
    }

    override fun onDestroy() {
        webView.destroy()
        V2RayManager.stop()
        super.onDestroy()
    }
}