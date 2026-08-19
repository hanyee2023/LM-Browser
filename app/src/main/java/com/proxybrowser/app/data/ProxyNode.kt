package com.proxybrowser.app.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 代理节点模型：覆盖 vless / vmess / trojan 主流类型。
 * 字段尽量对齐 xray-core outbound 配置，便于后续直接生成 xray config。
 */
data class ProxyNode(
    val name: String,
    val type: Type,            // VLESS / VMESS / TROJAN
    val address: String,
    val port: Int,
    val uuid: String,          // vmess/vless: id ; trojan: password
    val alterId: Int = 0,      // vmess 旧式，vless/trojan 忽略
    val encryption: String = "none",
    val security: String = "none", // none / tls
    val network: String = "tcp",   // tcp / ws / grpc
    val sni: String = "",
    val wsPath: String = "",
    val wsHost: String = "",
    val grpcServiceName: String = "",
    var latencyMs: Long = -1,  // 测速结果，-1 未测
    var valid: Boolean = true
) {
    enum class Type { VLESS, VMESS, TROJAN }
}

/**
 * 节点解析工具：单条 URI + 订阅链接（sub://）。
 * 订阅两种常见格式都兼容：
 *   1) base64( JSON 节点数组，v2rayN 风格 )
 *   2) base64( 每行一个 vmess:// / vless:// / trojan:// )
 *   3) 直接就是多行 URI（个别订阅）
 */
object NodeParser {

    fun parse(input: String): List<ProxyNode> {
        val text = input.trim()
        return when {
            text.startsWith("sub://") -> parseSubscription(text.removePrefix("sub://"))
            text.lines().any { it.trim().startsWith("vmess://") || it.trim().startsWith("vless://") || it.trim().startsWith("trojan://") } ->
                text.lines().mapNotNull { safe { parseSingle(it.trim()) } }
            else -> parseSubscription(text) // 当作裸 base64 订阅
        }
    }

    private fun parseSubscription(b64: String): List<ProxyNode> {
        val raw = tryDecodeBase64(b64) ?: return emptyList()
        return when {
            raw.trim().startsWith("[") || raw.trim().startsWith("{") -> parseV2rayNJson(raw)
            else -> raw.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { safe { parseSingle(it) } }
        }
    }

    private fun parseV2rayNJson(json: String): List<ProxyNode> {
        val arr = if (json.trim().startsWith("[")) JSONArray(json) else JSONArray("[$json]")
        val out = mutableListOf<ProxyNode>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // 兼容两种字段命名：ps/name, add/host, id/uuid, port
            val ps = o.optString("ps", o.optString("name", "node-$i"))
            val add = o.optString("add", o.optString("host", ""))
            val port = o.optInt("port", 0)
            val id = o.optString("id", o.optString("uuid", ""))
            val type = when (o.optString("type", "vmess").lowercase()) {
                "vless" -> ProxyNode.Type.VLESS
                "trojan" -> ProxyNode.Type.TROJAN
                else -> ProxyNode.Type.VMESS
            }
            if (add.isEmpty() || port == 0) continue
            out.add(
                ProxyNode(
                    name = ps,
                    type = type,
                    address = add,
                    port = port,
                    uuid = id,
                    alterId = o.optInt("aid", 0),
                    security = o.optString("tls", o.optString("scy", "none")),
                    network = o.optString("net", "tcp"),
                    wsPath = o.optString("path", ""),
                    wsHost = o.optString("host", ""),
                    sni = o.optString("sni", "")
                )
            )
        }
        return out
    }

    /** 解析单条 vless:// | vmess:// | trojan:// */
    fun parseSingle(uri: String): ProxyNode? {
        return when {
            uri.startsWith("vless://") -> parseVless(uri)
            uri.startsWith("vmess://") -> parseVmess(uri)
            uri.startsWith("trojan://") -> parseTrojan(uri)
            else -> null
        }
    }

    private fun parseVless(uri: String): ProxyNode {
        // vless://uuid@host:port?encryption=none&security=tls&type=ws&path=...&host=...#name
        val withoutScheme = uri.removePrefix("vless://")
        val (authority, queryAndFrag) = splitQueryFragment(withoutScheme)
        val (userInfo, hostPort) = authority.split("@", limit = 2).let {
            if (it.size == 2) it[0] to it[1] else "" to it[0]
        }
        val (host, port) = hostPort.split(":", limit = 2).let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 443) }
        val q = parseQuery(queryAndFrag)
        val frag = queryAndFrag.substringAfter('#', "").let { if (it.isEmpty()) "" else URLDecoder.decode(it, "UTF-8") }
        return ProxyNode(
            name = frag.ifEmpty { "$host:$port" },
            type = ProxyNode.Type.VLESS,
            address = host,
            port = port,
            uuid = userInfo,
            encryption = q["encryption"] ?: "none",
            security = q["security"] ?: "none",
            network = q["type"] ?: "tcp",
            sni = q["sni"] ?: q["peer"] ?: host,
            wsPath = q["path"] ?: "",
            wsHost = q["host"] ?: ""
        )
    }

    private fun parseTrojan(uri: String): ProxyNode {
        // trojan://password@host:port?security=tls&sni=...&type=ws&path=...#name
        val withoutScheme = uri.removePrefix("trojan://")
        val (authority, queryAndFrag) = splitQueryFragment(withoutScheme)
        val (userInfo, hostPort) = authority.split("@", limit = 2).let {
            if (it.size == 2) it[0] to it[1] else "" to it[0]
        }
        val (host, port) = hostPort.split(":", limit = 2).let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 443) }
        val q = parseQuery(queryAndFrag)
        val frag = queryAndFrag.substringAfter('#', "").let { if (it.isEmpty()) "" else URLDecoder.decode(it, "UTF-8") }
        return ProxyNode(
            name = frag.ifEmpty { "$host:$port" },
            type = ProxyNode.Type.TROJAN,
            address = host,
            port = port,
            uuid = userInfo,
            security = q["security"] ?: "tls",
            network = q["type"] ?: "tcp",
            sni = q["sni"] ?: host,
            wsPath = q["path"] ?: "",
            wsHost = q["host"] ?: ""
        )
    }

    private fun parseVmess(uri: String): ProxyNode {
        // vmess://<base64 of json>
        val b64 = uri.removePrefix("vmess://")
        val json = tryDecodeBase64(b64) ?: throw IllegalArgumentException("vmess base64 解码失败")
        val o = JSONObject(json)
        return ProxyNode(
            name = o.optString("ps", "vmess"),
            type = ProxyNode.Type.VMESS,
            address = o.optString("add", ""),
            port = o.optInt("port", 0),
            uuid = o.optString("id", ""),
            alterId = o.optInt("aid", 0),
            security = o.optString("scy", "auto"),
            network = o.optString("net", "tcp"),
            sni = o.optString("sni", o.optString("host", "")),
            wsPath = o.optString("path", ""),
            wsHost = o.optString("host", "")
        )
    }

    // ---------- 小工具 ----------

    private fun splitQueryFragment(s: String): Pair<String, String> {
        val hash = s.indexOf('#')
        val body = if (hash >= 0) s.substring(0, hash) else s
        val q = body.indexOf('?')
        return if (q >= 0) body.substring(0, q) to body.substring(q) else body to ""
    }

    private fun parseQuery(qf: String): Map<String, String> {
        val q = if (qf.startsWith("?")) qf.substring(1) else qf
        return q.split("&").filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split("=", limit = 2).let { p -> p[0] to (p.getOrNull(1) ?: "") }
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }

    private fun tryDecodeBase64(s: String): String? = runCatching {
        val norm = s.replace("-", "+").replace("_", "/")
            .let { if (it.length % 4 != 0) it.padEnd(it.length + (4 - it.length % 4), '=') else it }
        String(Base64.decode(norm, Base64.DEFAULT), StandardCharsets.UTF_8)
    }.getOrNull()

    private inline fun <T> safe(block: () -> T): T? = runCatching(block).getOrNull()
}
