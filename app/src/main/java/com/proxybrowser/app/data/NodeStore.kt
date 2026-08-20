package com.proxybrowser.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 节点持久化：存到 SharedPreferences（JSON），重启不丢。
 * 同时记录“当前选中节点”的 key 与订阅链接列表。
 */
object NodeStore {

    private const val PREFS = "pb_nodes"
    private const val KEY_LIST = "list"
    private const val KEY_ACTIVE = "active"
    private const val PREFS_SUB = "pb_subs"
    private const val KEY_SUBS = "subs"

    fun load(context: Context): MutableList<ProxyNode> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_LIST, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<ProxyNode>()
        for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
        return out
    }

    fun save(context: Context, nodes: List<ProxyNode>) {
        val arr = JSONArray()
        nodes.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun setActive(context: Context, node: ProxyNode?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE, node?.let { keyOf(it) } ?: "").apply()
    }

    fun getActive(context: Context): ProxyNode? {
        val k = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE, "") ?: return null
        if (k.isEmpty()) return null
        return load(context).firstOrNull { keyOf(it) == k }
    }

    fun keyOf(n: ProxyNode) = "${n.name}|${n.address}:${n.port}|${n.subscription}"

    // ============ 订阅链接管理 ============
    fun loadSubs(context: Context): MutableList<String> {
        val sp = context.getSharedPreferences(PREFS_SUB, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_SUBS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) out.add(arr.getString(i))
        return out
    }

    fun saveSubs(context: Context, subs: List<String>) {
        val arr = JSONArray()
        subs.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS_SUB, Context.MODE_PRIVATE)
            .edit().putString(KEY_SUBS, arr.toString()).apply()
    }

    fun addSub(context: Context, url: String) {
        val list = loadSubs(context).toMutableList()
        if (!list.contains(url)) list.add(url)
        saveSubs(context, list)
    }

    /** 删除某个订阅链接，以及所有归属于它的节点 */
    fun deleteSub(context: Context, url: String) {
        val subs = loadSubs(context).toMutableList()
        subs.remove(url)
        saveSubs(context, subs)
        val nodes = load(context).filter { it.subscription != url }
        save(context, nodes)
        val active = getActive(context)
        if (active != null && active.subscription == url) setActive(context, null)
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
        uuid = o.getString("uuid"),
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
