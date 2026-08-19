package com.proxybrowser.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
            out.add(createScript(
                name = o.optString("name", "script-$i"),
                raw = o.optString("raw", ""),
                enabled = o.optBoolean("enabled", true)
            ))
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
        val script = createScript(name, raw, enabled)
        if (idx >= 0) list[idx] = script else list.add(script)
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
            list[idx] = list[idx].copy(enabled = enabled)
            saveAll(ctx, list)
        }
    }

    /**
     * Parse script metadata from raw user.js text.
     * Supports @match, @include, @exclude annotations.
     */
    fun createScript(name: String, raw: String, enabled: Boolean = true): Script {
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
        if (matches.isEmpty()) matches.add("*://*/*")
        return Script(
            name = name,
            raw = raw,
            enabled = enabled,
            matches = matches,
            excludes = excludes
        )
    }

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
                '.', '?', '+', '(', ')', '|', '^', '$', '{', '}', '[', ']', '\\' ->
                    regex.append('\\').append(c)
                else -> regex.append(c)
            }
        }
        regex.append('$')
        return runCatching { java.util.regex.Pattern.compile(regex.toString())
            .matcher(url).find() }.getOrDefault(false)
    }

    fun buildInjection(scripts: List<Script>): String {
        if (scripts.isEmpty()) return ""
        val gm = "(function(){\n" +
            "try {\n" +
            "  window.GM_setValue = function(k, v) { localStorage.setItem('__gm__:'+k, JSON.stringify(v)); };\n" +
            "  window.GM_getValue = function(k, d) { var v = localStorage.getItem('__gm__:'+k); if (v == null) return d; try { return JSON.parse(v); } catch(e) { return v; } };\n" +
            "  window.GM_deleteValue = function(k) { localStorage.removeItem('__gm__:'+k); };\n" +
            "} catch(e) {}\n" +
            "})();\n"
        val sb = StringBuilder(gm)
        for (s in scripts) {
            val code = stripMetadata(s.raw)
            sb.append("\n/* === ").append(s.name).append(" === */\n")
            sb.append("try{").append(code).append("}catch(e){console.error('user script error:',e);}\n")
        }
        return sb.toString()
    }

    private fun stripMetadata(raw: String): String {
        val start = raw.indexOf("// ==UserScript==")
        if (start < 0) return raw
        val end = raw.indexOf("// ==/UserScript==", start)
        if (end < 0) return raw
        val after = end + "// ==/UserScript==".length
        return raw.substring(0, start) + raw.substring(after)
    }
}
