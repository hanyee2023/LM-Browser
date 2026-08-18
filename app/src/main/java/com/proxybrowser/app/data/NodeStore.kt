package com.proxybrowser.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 节点持久化：存到 SharedPreferences（JSON），重启不丢。
 * 同时记录“当前选中节点”的 key。
 */
object NodeStore {

    private const val PREFS = "pb_nodes"
    private const val KEY_LIST = "list"
    private const val KEY_ACTIVE = "active"

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
        return load(context).firstOrNull { keyOf(it) == k }
    }

    fun keyOf(n: ProxyNode) = "${n.name}|${n.address}:${n.port}"

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
        put("grpcServiceName", n.grpcServiceName)
        put("latencyMs", n.latencyMs)
        put("valid", n.valid)
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
        sni = o.optString("sni"),
        wsPath = o.optString("wsPath"),
        wsHost = o.optString("wsHost"),
        grpcServiceName = o.optString("grpcServiceName"),
        latencyMs = o.optLong("latencyMs", -1),
        valid = o.optBoolean("valid", true)
    )
}
