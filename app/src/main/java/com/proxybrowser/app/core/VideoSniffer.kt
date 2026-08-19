package com.proxybrowser.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * P4：视频嗅探器。
 *
 * 工作方式：
 *   - 通过 evaluateJavascript 注入 JS 钩子，劫持 HTMLMediaElement / fetch / XMLHttpRequest
 *   - 钩子捕获到 .mp4 / .m3u8 / .flv / .ts / .webm / .mov / .avi / 真实流媒体 m3u8 / video chunk
 *   - 通过 JsBridge（addJavascriptInterface）把结果写回 App
 *   - 这里只维护"已发现列表"，嗅探面板 UI 单独从这读
 */
object VideoSniffer {

    data class Media(
        val url: String,
        val type: String,         // video / m3u8 / blob / unknown
        val pageUrl: String,
        val title: String,        // 页面的 <title>，给用户看来源
        val size: Long = 0,       // 嗅探时拿不到就 0
        val ext: String = "",     // 文件扩展名
        val ts: Long = System.currentTimeMillis()
    )

    private val list = CopyOnWriteArrayList<Media>()
    private const val PREFS = "pb_sniffer"
    private const val KEY = "items"
    private const val MAX = 200  // 上限，避免无限增长

    fun all(): List<Media> = list.sortedByDescending { it.ts }

    fun clear() {
        list.clear()
        // 不主动清 prefs；让 UI 在关闭时显式 save
    }

    fun add(ctx: Context, m: Media) {
        // 去重（同一 url 不重复）
        if (list.any { it.url == m.url }) return
        list.add(0, m)
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(ctx)
    }

    fun load(ctx: Context) {
        list.clear()
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Media(
                        url = o.optString("url"),
                        type = o.optString("type", "video"),
                        pageUrl = o.optString("pageUrl"),
                        title = o.optString("title"),
                        size = o.optLong("size", 0),
                        ext = o.optString("ext"),
                        ts = o.optLong("ts", 0L)
                    )
                )
            }
        }
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        for (m in list) {
            arr.put(JSONObject().apply {
                put("url", m.url); put("type", m.type)
                put("pageUrl", m.pageUrl); put("title", m.title)
                put("size", m.size); put("ext", m.ext); put("ts", m.ts)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    /**
     * JS 钩子，注入到每个 WebView。
     *
     * 钩住：
     *   1) HTMLMediaElement.prototype.__lookupSetter__('src')
     *      → 任何 <video src="..."> 或 .src = "..." 都被截
     *   2) window.fetch / XMLHttpRequest.open → 拿到流媒体 URL
     *   3) 监听 'source' 标签
     */
    @JvmField val HOOK_JS: String = """
        (function(){
            if (window.__pb_sniffed) return;
            window.__pb_sniffed = true;
            window.__pb_media = [];

            function report(url, type) {
                if (!url) return;
                try {
                    url = String(url);
                } catch(_) { return; }
                if (!/^https?:\/\//i.test(url) && !url.startsWith('blob:')) return;
                // 只关心视频类
                var lc = url.toLowerCase();
                var isVid = /\.(mp4|m3u8|flv|ts|webm|mov|mkv|avi)(\?|$|#)/.test(lc)
                    || type === 'm3u8' || type === 'blob' || /\/videoplayback\?/.test(lc)
                    || /\.m3u8(\?|$)/.test(lc) || /\.mp4(\?|$)/.test(lc);
                if (!isVid) return;
                var item = { url: url, type: type || 'video', page: location.href, title: document.title || '' };
                window.__pb_media.push(item);
                if (window.__pb_report) window.__pb_report(JSON.stringify(item));
            }

            // 1) HTMLMediaElement.src setter
            try {
                var origSet = HTMLMediaElement.prototype.__lookupSetter__('src');
                HTMLMediaElement.prototype.__defineSetter__('src', function(v){
                    try { report(v, v && /\.m3u8(\?|$)/i.test(v) ? 'm3u8' : 'video'); } catch(_){}
                    if (origSet) origSet.call(this, v); else this.setAttribute('src', v);
                });
            } catch(_){}

            // 2) <source> 标签
            try {
                var origSrcSet = HTMLSourceElement.prototype.__lookupSetter__('src');
                HTMLSourceElement.prototype.__defineSetter__('src', function(v){
                    try { report(v, 'video'); } catch(_){}
                    if (origSrcSet) origSrcSet.call(this, v); else this.setAttribute('src', v);
                });
            } catch(_){}

            // 3) fetch
            try {
                var origFetch = window.fetch;
                window.fetch = function(input, init){
                    try {
                        var u = (typeof input === 'string') ? input : (input && input.url);
                        if (u) report(u, /\.m3u8/i.test(u) ? 'm3u8' : 'video');
                    } catch(_){}
                    return origFetch.apply(this, arguments);
                };
            } catch(_){}

            // 4) XMLHttpRequest
            try {
                var origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url){
                    try { report(url, /\.m3u8/i.test(url) ? 'm3u8' : 'video'); } catch(_){}
                    return origOpen.apply(this, arguments);
                };
            } catch(_){}

            // 5) performance observer for <video>.src 行为
            try {
                var po = new PerformanceObserver(function(list){
                    for (var i=0;i<list.getEntries().length;i++){
                        var e = list.getEntries()[i];
                        if (e && e.name) report(e.name, /\.m3u8/i.test(e.name) ? 'm3u8' : 'video');
                    }
                });
                po.observe({ type: 'resource', buffered: true });
            } catch(_){}
        })();
    """
}
