package com.proxybrowser.app.core

import android.content.Context

object Settings {

    private const val PREFS = "pb_settings"

    fun saveBoolean(ctx: Context, key: String, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key, value).apply()
    }

    fun loadBoolean(ctx: Context, key: String, default: Boolean = false) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, default)

    fun saveString(ctx: Context, key: String, value: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, value).apply()
    }

    fun loadString(ctx: Context, key: String, default: String = "") =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, default) ?: default

    // ============ Proxy settings ============
    fun isProxyEnabled(ctx: Context) = loadBoolean(ctx, "proxy_enabled", false)
    fun setProxyEnabled(ctx: Context, enabled: Boolean) = saveBoolean(ctx, "proxy_enabled", enabled)

    // ============ Search engine ============
    val SEARCH_ENGINES = listOf(
        "百度" to "https://www.baidu.com/s?wd=",
        "Google" to "https://www.google.com/search?q=",
        "Bing" to "https://www.bing.com/search?q=",
        "DuckDuckGo" to "https://duckduckgo.com/?q="
    )

    fun searchEngine(ctx: Context): String {
        val saved = loadString(ctx, "search_engine", "")
        return if (saved.isNotEmpty()) saved else SEARCH_ENGINES[0].second
    }
    fun searchEngineName(ctx: Context): String {
        val cur = searchEngine(ctx)
        return SEARCH_ENGINES.firstOrNull { it.second == cur }?.first ?: "百度"
    }
    fun setSearchEngine(ctx: Context, url: String) = saveString(ctx, "search_engine", url)

    // ============ Features toggles ============
    fun isAdBlockEnabled(ctx: Context) = loadBoolean(ctx, "ad_block_enabled", true)
    fun setAdBlockEnabled(ctx: Context, enabled: Boolean) = saveBoolean(ctx, "ad_block_enabled", enabled)

    fun isUserScript(ctx: Context) = loadBoolean(ctx, "user_script_enabled", false)
    fun setUserScript(ctx: Context, enabled: Boolean) = saveBoolean(ctx, "user_script_enabled", enabled)

    fun isSniffer(ctx: Context) = loadBoolean(ctx, "sniffer_enabled", false)
    fun setSniffer(ctx: Context, enabled: Boolean) = saveBoolean(ctx, "sniffer_enabled", enabled)

    // ============ Privacy ============
    fun isDnt(ctx: Context) = loadBoolean(ctx, "dnt_enabled", true)
    fun setDnt(ctx: Context, enabled: Boolean) = saveBoolean(ctx, "dnt_enabled", enabled)

    // ============ UserAgent ============
    fun userAgent(ctx: Context) = loadString(ctx, "user_agent", "")
    fun setUserAgent(ctx: Context, ua: String) = saveString(ctx, "user_agent", ua)
}
