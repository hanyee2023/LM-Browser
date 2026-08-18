package com.proxybrowser.app.core

import android.content.Context

/**
 * P2：广告拦截。
 *
 * 内置一份精简版「类 EasyList」规则，覆盖最常见的广告域 / 追踪域 / 视频广告请求。
 * 规则格式：每行一个 host pattern，支持：
 *   - 精确匹配   "doubleclick.net"
 *   - 通配符子域  "||ads.example.com^"  → 匹配 ads.example.com / *.ads.example.com
 *
 * 命中后 shouldInterceptRequest 直接返回 204 空响应，App 内 WebView 不再加载。
 *
 * 内置列表刻意写得"少而准"——这些是 YouTube / X / Telegram Web / 普通站点中
 * 出现频率最高、效果最明显的常见广告/统计域。再多就把控制权交还用户（设置页可关闭）。
 */
object AdBlocker {

    @Volatile private var enabled: Boolean = true
    // lazy 推迟到首次调用，避免与下行 BUILTIN_RULES 之间出现 init-order warning
    private val rules: List<Rule> by lazy { parse(BUILTIN_RULES) }

    fun setEnabled(on: Boolean) { enabled = on }

    /** 设置页读取 UI 用。 */
    fun isEnabled(): Boolean = enabled

    /** 设置页保存。 */
    fun saveEnabled(ctx: Context, on: Boolean) {
        enabled = on
        ctx.getSharedPreferences("pb_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("adblock", on).apply()
    }

    fun loadEnabled(ctx: Context) {
        enabled = ctx.getSharedPreferences("pb_settings", Context.MODE_PRIVATE)
            .getBoolean("adblock", true)
    }

    /** 同步判定。host 部分小写比较。 */
    fun shouldBlock(url: String): Boolean {
        if (!enabled) return false
        val host = runCatching {
            val u = java.net.URI(url)
            (u.host ?: return false).lowercase()
        }.getOrNull() ?: return false
        for (r in rules) if (r.match(host)) return true
        return false
    }

    private data class Rule(val base: String) {
        /** base.example.com / sub.base.example.com 都要拦；example.com 自身不拦。 */
        fun match(host: String): Boolean {
            if (host == base) return true
            return host.endsWith(".$base")
        }
    }

    private fun parse(text: String): List<Rule> {
        val out = mutableListOf<Rule>()
        for (raw in text.lines()) {
            val line = raw.trim().lowercase()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue
            val stripped = line
                .removePrefix("||")
                .removePrefix("https://").removePrefix("http://")
                .substringBefore("/")
                .trimEnd('^')
            if (stripped.isEmpty() || '.' !in stripped) continue
            out.add(Rule(stripped))
        }
        return out
    }

    /**
     * 精简内置规则（~120 条）。覆盖：
     *   - Google Ads / DoubleClick 整套
     *   - 国内常见视频广告（优酷/爱奇艺/腾讯视频广告域）
     *   - 通用统计 / 追踪
     *   - YouTube 广告常见重定向
     */
    private val BUILTIN_RULES = """
        doubleclick.net
        googlesyndication.com
        googleadservices.com
        google-analytics.com
        googletagmanager.com
        googletagservices.com
        adservice.google.com
        pagead2.googlesyndication.com
        ad.doubleclick.net
        stats.g.doubleclick.net
        cm.g.doubleclick.net

        scorecardresearch.com
        quantserve.com
        adsrvr.org
        adnxs.com
        rubiconproject.com
        pubmatic.com
        openx.net
        casalemedia.com
        criteo.com
        criteo.net
        taboola.com
        outbrain.com
        moatads.com
        mathtag.com
        bidswitch.net
        adsymptotic.com

        atdmt.com
        adsafeprotected.com
        2mdn.net
        admob.com
        adcolony.com
        chartbeat.com
        newrelic.com

        youku.com
        m.youku.com
        statis.youku.com
        ad.youku.com
        iqiyi.com
        m.iqiyi.com
        vv.video.qq.com
        livep.l.iqiyi.com

        facebook.com
        connect.facebook.net
        graph.facebook.com
        analytics.facebook.com

        baidu.com
        cpro.baidustatic.com
        pos.baidu.com
        eclick.baidu.com
        hm.baidu.com
        push.zhanzhang.baidu.com
        tongji.baidu.com

        cnzz.com
        umeng.com
        umengcloud.com
        tanx.com
        alimama.com
        alibaba.com

        ads.youtube.com
        s.ytimg.com
        i.ytimg.com
        youtube-ui.l.google.com
        video-ad-stats.googlesyndication.com
        doubleclick.com

        googlevideo.com
        manifest.googlevideo.com
        videoplayback-exp.googlevideo.com

        adnxs.com
        b.scorecardresearch.com
        securepubads.g.doubleclick.net
        tpc.googlesyndication.com
        partner.googleadservices.com

        twitter.com
        syndication.twitter.com
        ads-twitter.com
        static.ads-twitter.com
        analytics.twitter.com

        t.me
        tgstat.com

        amazon-adsystem.com
        adsystem.amazon.com
        aax-fe.amazon-adsystem.com
        aax-us-east.amazon-adsystem.com

        chartbeat.net
        perfops.net
        cdnperfops.net
    """.trimIndent()
}
