package com.proxybrowser.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * P3：油猴引擎（最小可用）。
 *
 * 设计目标：
 *   - 不引入完整的油猴运行时（太大）
 *   - 只做最常用 3 件事：
 *       1) 解析 @match / @include / @exclude
 *       2) 在 document_start 阶段注入用户脚本代码
 *       3) 暴露 GM_setValue / GM_getValue 桥（持久化到 SharedPreferences）
 *
 * 脚本存储：每条脚本一个 JSON 对象：{ name, enabled, raw, matches[], excludes[] }
 *   "raw"  保留原 .user.js 文本。
 *
 * 注入点：WebViewClient.onPageStarted 时把"该页匹配且 enabled 的脚本"插入 <head>。
 * 用 document_start（页面开始解析前）以拿到 @run-at document-start 的脚本语义。
 */
object UserScriptEngine {

    private const val PREFS = "pb_scripts"
    private const val KEY_LIST = "list"

    data class Script(
        val name: String,
        val raw: String,
        val enabled: Boolean = true,
        val matches: List<String> = emptyList(),
        val excludes: List<String> = emptyList()
    )

    fun loadAll(ctx: Context): MutableList<Script> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LIST, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<Script>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val s = parse(
                o.optString("name", "user-script-$i"),
                o.optString("raw", ""),
                o.optBoolean("enabled", true)
            )
            out.add(s)
        }
        return out
    }

    fun saveAll(ctx: Context, list: List<Script>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("raw", s.raw)
                put("enabled", s.enabled)
                put("matches", JSONArray(s.matches))
                put("excludes", JSONArray(s.excludes))
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun addOrReplace(ctx: Context, name: String, raw: String, enabled: Boolean = true) {
        val list = loadAll(ctx)
        val idx = list.indexOfFirst { it.name == name }
        val parsed = parse(name, raw, enabled)
        if (idx >= 0) list[idx] = parsed else list.add(parsed)
        saveAll(ctx, list)
    }

    fun remove(ctx: Context, name: String) {
        val list = loadAll(ctx)
        list.removeAll { it.name == name }
        saveAll(ctx, list)
    }

    fun setEnabled(ctx: Context, name: String, enabled: Boolean) {
        val list = loadAll(ctx)
        val idx = list.indexOfFirst { it.name == name }
        if (idx >= 0) {
            val s = list[idx]
            list[idx] = s.copy(enabled = enabled)
            saveAll(ctx, list)
        }
    }

    /**
     * 解析脚本元数据。匹配规则取自油猴/篡改猴标准：
     *   // @match       https://*.example.com/*
     *   // @include     *://example.com/*
     *   // @exclude     *://example.com/ad/*
     *
     * 不支持更复杂的 `*` / `||` 通配语义，只把 * 视为通配符（足够 99% 场景）。
     */
    fun parse(name: String, raw: String, enabled: Boolean = true): Script {
        val matches = mutableListOf<String>()
        val excludes = mutableListOf<String>()
        for (line in raw.lines()) {
            val t = line.trim()
            if (!t.startsWith("//")) continue
            val body = t.removePrefix("/").trim()
            if (body.startsWith("@match", ignoreCase = true)) {
                val v = body.substringAfter(' ', "").trim()
                if (v.isNotEmpty()) matches.add(v)
            } else if (body.startsWith("@include", ignoreCase = true)) {
                val v = body.substringAfter(' ', "").trim()
                if (v.isNotEmpty()) matches.add(v)
            } else if (body.startsWith("@exclude", ignoreCase = true)) {
                val v = body.substringAfter(' ', "").trim()
                if (v.isNotEmpty()) excludes.add(v)
            }
        }
        if (matches.isEmpty()) matches.add("*://*/*")  // 兜底：未声明 @match 时注入所有
        return Script(
            name = name,
            raw = raw,
            enabled = enabled,
            matches = matches,
            excludes = excludes
        )
    }

    /**
     * 计算 url 命中的脚本列表（按注册顺序）。命中条件：
     *   1) enabled = true
     *   2) matches 中任一模式匹配 url
     *   3) excludes 中没有一项匹配 url
     */
    fun matches(url: String, scripts: List<Script>): List<Script> {
        return scripts.filter { s ->
            s.enabled && s.matches.any { matchPattern(it, url) } &&
                s.excludes.none { matchPattern(it, url) }
        }
    }

    private fun matchPattern(pat: String, url: String): Boolean {
        val regex = StringBuilder("^")
        for (c in pat) {
            when (c) {
                '*' -> regex.append(".*")
                '.', '?', '+', '(', ')', '|', '^', '$', '{', '}', '[', ']', '\\' -> regex.append('\\').append(c)
                else -> regex.append(c)
            }
        }
        regex.append('$')
        return runCatching { java.util.regex.Pattern.compile(regex.toString()).matcher(url).find() }
            .getOrDefault(false)
    }

    /**
     * 拼出要注入的完整 <script> 字符串。
     *   - 包含一个 GM_* 桥：GM_setValue / GM_getValue / GM_deleteValue（持久化在 localStorage）
     *   - 包含用户脚本原始代码（去掉 metadata 行）
     */
    fun buildInjection(scripts: List<Script>): String {
        if (scripts.isEmpty()) return ""
        val gm = """
            (function(){
                try {
                    window.GM_setValue = function(k, v) {
                        localStorage.setItem('__gm__:'+k, JSON.stringify(v));
                    };
                    window.GM_getValue = function(k, d) {
                        const v = localStorage.getItem('__gm__:'+k);
                        if (v == null) return d;
                        try { return JSON.parse(v); } catch(_) { return v; }
                    };
                    window.GM_deleteValue = function(k) {
                        localStorage.removeItem('__gm__:'+k);
                    };
                } catch(e) {}
            })();
        """.trimIndent()

        val sb = StringBuilder(gm)
        for (s in scripts) {
            val code = stripMetadata(s.raw)
            sb.append("\n/* === ").append(s.name).append(" === */\n")
            sb.append("try{").append(code).append("}catch(e){console.error('userscript error:',e);}\n")
        }
        return sb.toString()
    }

    private fun stripMetadata(raw: String): String {
        // 油猴脚本头部 metadata block 形如：// ==UserScript== ... // ==/UserScript==
        val start = raw.indexOf("// ==UserScript==")
        if (start < 0) return raw
        val end = raw.indexOf("// ==/UserScript==", start)
        if (end < 0) return raw
        val after = end + "// ==/UserScript==".length
        return raw.substring(0, start) + raw.substring(after)
    }
}
