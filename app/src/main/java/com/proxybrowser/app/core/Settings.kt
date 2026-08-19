package com.proxybrowser.app.core

import android.content.Context

/**
 * 通用设置：所有开关 / 用户偏好集中保存。
 * 不直接读取 SharedPreferences，由各模块自行 refresh。
 */
object Settings {

    private const val PREFS = "pb_settings"

    // 键
    private const val K_ADBLOCK = "adblock"
    private const val K_USERSCRIPT = "userscript"
    private const val K_SNIFFER = "sniffer"
    private const val K_DARK = "dark"
    private const val K_DNT = "dnt"
    private const val K_UA = "ua"

    // 默认值
    fun adblockDefault() = true
    fun userscriptDefault() = true
    fun snifferDefault() = true
    fun darkDefault() = false
    fun dntDefault() = true

    fun isAdblock(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ADBLOCK, adblockDefault())

    fun setAdblock(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_ADBLOCK, on).apply()

    fun isUserScript(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_USERSCRIPT, userscriptDefault())

    fun setUserScript(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_USERSCRIPT, on).apply()

    fun isSniffer(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_SNIFFER, snifferDefault())

    fun setSniffer(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_SNIFFER, on).apply()

    fun isDark(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_DARK, darkDefault())

    fun setDark(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_DARK, on).apply()

    fun isDnt(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_DNT, dntDefault())

    fun setDnt(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_DNT, on).apply()

    /** 自定义 UA（空字符串=用默认） */
    fun userAgent(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_UA, "") ?: ""

    fun setUserAgent(ctx: Context, v: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_UA, v).apply()

    fun searchEngine(): String =
        // 默认 Google 搜索（结果丰富）；可改成 baidu / duckduckgo / bing
        "https://www.google.com/search?q="
}
