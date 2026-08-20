package com.proxybrowser.app.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

data class ProxyNode(
    val name: String,
    val type: Type,
    val address: String,
    val port: Int,
    val uuid: String = "",
    val alterId: Int = 0,
    val encryption: String = "none",
    val security: String = "none",
    val network: String = "tcp",
    val sni: String = "",
    val wsPath: String = "",
    val wsHost: String = "",
    var latencyMs: Long = -1,
    var valid: Boolean = true,
    /** 来源订阅链接；手动添加的为空字符串，归类到「未分组」 */
    var subscription: String = ""
) {
    enum class Type { VLESS, VMESS, TROJAN }
}

object NodeStore {
    private const val PREFS = "pb_nodes"
    private const val KEY_LIST = "list"
    private const val KEY_ACTIVE = "active"
    private const val PREFS_SUB = "pb_subs"
    private const val KEY_SUBS = "subs"

    fun load(ctx: Context): MutableList<ProxyNode> {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_LIST, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<ProxyNode>()
        for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
        return out
    }

    fun save(ctx: Context, nodes: List<ProxyNode>) {
        val arr = JSONArray()
        nodes.forEach { arr.put(toJson(it)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun setActive(ctx: Context, node: ProxyNode?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE, node?.let { keyOf(it) } ?: "").apply()
    }

    fun getActive(ctx: Context): ProxyNode? {
        val k = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE, "") ?: return null
        return load(ctx).firstOrNull { keyOf(it) == k }
    }

    fun keyOf(n: ProxyNode) = "${n.name}|${n.address}:${n.port}|${n.subscription}"

    // ============ 订阅链接管理 ============
    fun loadSubs(ctx: Context): MutableList<String> {
        val sp = ctx.getSharedPreferences(PREFS_SUB, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_SUBS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) out.add(arr.getString(i))
        return out
    }

    fun saveSubs(ctx: Context, subs: List<String>) {
        val arr = JSONArray()
        subs.forEach { arr.put(it) }
        ctx.getSharedPreferences(PREFS_SUB, Context.MODE_PRIVATE)
            .edit().putString(KEY_SUBS, arr.toString()).apply()
    }

    fun addSub(ctx: Context, url: String) {
        val list = loadSubs(ctx).toMutableList()
        if (!list.contains(url)) list.add(url)
        saveSubs(ctx, list)
    }

    /** 删除某个订阅链接，以及所有归属于它的节点 */
    fun deleteSub(ctx: Context, url: String) {
        val subs = loadSubs(ctx).toMutableList()
        subs.remove(url)
        saveSubs(ctx, subs)
        val nodes = load(ctx).filter { it.subscription != url }
        save(ctx, nodes)
        val active = getActive(ctx)
        if (active != null && active.subscription == url) setActive(ctx, null)
    }

    private fun toJson(n: ProxyNode) = JSONObject().apply {
        put("name", n.name)
        put("type", n.type.name)
        put("address", n.address)
        put("port", n.port)
        put("uuid", n.uuid)
        put("alterId", n.alterId)
        put("encryption", n.encryption)
        put("security", n.security)
        put("network", n.network)
        put("sni", n.sni)
        put("wsPath", n.wsPath)
        put("wsHost", n.wsHost)
        put("latencyMs", n.latencyMs)
        put("valid", n.valid)
        put("subscription", n.subscription)
    }

    private fun fromJson(o: JSONObject) = ProxyNode(
        name = o.getString("name"),
        type = ProxyNode.Type.valueOf(o.getString("type")),
        address = o.getString("address"),
        port = o.getInt("port"),
        uuid = o.optString("uuid", ""),
        alterId = o.optInt("alterId"),
        encryption = o.optString("encryption", "none"),
        security = o.optString("security", "none"),
        network = o.optString("network", "tcp"),
        sni = o.optString("sni", ""),
        wsPath = o.optString("wsPath", ""),
        wsHost = o.optString("wsHost", ""),
        latencyMs = o.optLong("latencyMs", -1),
        valid = o.optBoolean("valid", true),
        subscription = o.optString("subscription", "")
    )
}

object NodeParser {
    fun parseSingle(uri: String): ProxyNode? {
        return when {
            uri.startsWith("vless://") -> parseVless(uri)
            uri.startsWith("vmess://") -> parseVmess(uri)
            uri.startsWith("trojan://") -> parseTrojan(uri)
            else -> null
        }
    }

    fun parse(body: String): List<ProxyNode> {
        val out = mutableListOf<ProxyNode>()
        // 逐行尝试；也兼容整段 base64
        for (line in body.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue
            parseSingle(t)?.let { out.add(it) }
        }
        return out
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun parseVless(uri: String): ProxyNode? {
        return runCatching {
            val noScheme = uri.removePrefix("vless://")
            val hashIdx = noScheme.indexOf('#')
            val name = if (hashIdx >= 0) decode(noScheme.substring(hashIdx + 1)) else ""
            val core = if (hashIdx >= 0) noScheme.substring(0, hashIdx) else noScheme
            val qIdx = core.indexOf('?')
            val query = if (qIdx >= 0) core.substring(qIdx + 1) else ""
            val authority = if (qIdx >= 0) core.substring(0, qIdx) else core
            val atIdx = authority.indexOf('@')
            val uuid = if (atIdx >= 0) authority.substring(0, atIdx) else authority
            val hp = if (atIdx >= 0) authority.substring(atIdx + 1) else authority
            val host = hp.substringBefore(':')
            val port = hp.substringAfter(':', "443").toIntOrNull() ?: 443

            var security = ""
            var sni = ""
            var network = "tcp"
            var wsPath = ""
            var wsHost = ""
            var encryption = "none"
            for (kv in query.split('&')) {
                val eq = kv.indexOf('=')
                if (eq < 0) continue
                val k = kv.substring(0, eq)
                val v = decode(kv.substring(eq + 1))
                when (k) {
                    "security" -> security = v
                    "sni" -> sni = v
                    "type" -> network = v
                    "path" -> wsPath = v
                    "host" -> wsHost = v
                    "encryption" -> encryption = v
                }
            }
            val finalName = name.ifEmpty { "$host:$port" }
            ProxyNode(
                name = finalName, type = ProxyNode.Type.VLESS, address = host, port = port,
                uuid = uuid, encryption = encryption, security = security,
                network = network, sni = sni, wsPath = wsPath, wsHost = wsHost
            )
        }.getOrNull()
    }

    private fun parseTrojan(uri: String): ProxyNode? {
        return runCatching {
            val noScheme = uri.removePrefix("trojan://")
            val hashIdx = noScheme.indexOf('#')
            val name = if (hashIdx >= 0) decode(noScheme.substring(hashIdx + 1)) else ""
            val core = if (hashIdx >= 0) noScheme.substring(0, hashIdx) else noScheme
            val qIdx = core.indexOf('?')
            val query = if (qIdx >= 0) core.substring(qIdx + 1) else ""
            val authority = if (qIdx >= 0) core.substring(0, qIdx) else core
            val atIdx = authority.indexOf('@')
            val password = if (atIdx >= 0) authority.substring(0, atIdx) else authority
            val hp = if (atIdx >= 0) authority.substring(atIdx + 1) else authority
            val host = hp.substringBefore(':')
            val port = hp.substringAfter(':', "443").toIntOrNull() ?: 443
            var sni = ""
            for (kv in query.split('&')) {
                val eq = kv.indexOf('=')
                if (eq < 0) continue
                if (kv.substring(0, eq) == "sni") sni = decode(kv.substring(eq + 1))
            }
            val finalName = name.ifEmpty { "$host:$port" }
            ProxyNode(
                name = finalName, type = ProxyNode.Type.TROJAN, address = host, port = port,
                uuid = password, sni = sni, security = "tls"
            )
        }.getOrNull()
    }

    private fun parseVmess(uri: String): ProxyNode? {
        return runCatching {
            val b64 = uri.removePrefix("vmess://")
            val decoded = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            val o = JSONObject(decoded)
            val net = o.optString("net", "tcp")
            val tls = o.optString("tls", "")
            val security = if (tls.equals("tls", true) || tls.equals("reality", true)) "tls" else "none"
            ProxyNode(
                name = o.optString("ps", "vmess"),
                type = ProxyNode.Type.VMESS,
                address = o.optString("add", ""),
                port = o.optInt("port", 0),
                uuid = o.optString("id", ""),
                alterId = o.optInt("aid", 0),
                network = net,
                sni = o.optString("sni", ""),
                wsPath = o.optString("path", ""),
                wsHost = o.optString("host", ""),
                security = security
            )
        }.getOrNull()
    }
}
