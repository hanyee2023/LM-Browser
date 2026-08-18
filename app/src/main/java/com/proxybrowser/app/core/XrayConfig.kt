package com.proxybrowser.app.core

import com.proxybrowser.app.data.ProxyNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 ProxyNode 转成 xray-core 配置 JSON。
 *
 * 入站：
 *   - HTTP  127.0.0.1:10809  →  给 WebView 走 ProxyController 用
 *   - SOCKS 127.0.0.1:10808  →  给节点测速用（做 SOCKS5 CONNECT 握手）
 *
 * 出站：按节点类型生成 vless/vmess/trojan outbound + streamSettings。
 */
object XrayConfig {

    fun build(node: ProxyNode, httpPort: Int = 10809, socksPort: Int = 10808): String {
        val httpInbound = JSONObject().apply {
            put("tag", "http-in")
            put("port", httpPort)
            put("listen", "127.0.0.1")
            put("protocol", "http")
            put("settings", JSONObject())
        }
        val socksInbound = JSONObject().apply {
            put("tag", "socks-in")
            put("port", socksPort)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("udp", false)
            })
        }

        val root = JSONObject().apply {
            put("log", JSONObject().put("loglevel", "warning"))
            put("inbounds", JSONArray().put(httpInbound).put(socksInbound))
            put("outbounds", JSONArray().put(buildOutbound(node)))
            put("routing", JSONObject().put("domainStrategy", "AsIs"))
        }
        return root.toString()
    }

    private fun buildOutbound(node: ProxyNode): JSONObject {
        val settings = JSONObject()
        when (node.type) {
            ProxyNode.Type.VMESS -> settings.put("vnext", JSONArray().put(JSONObject().apply {
                put("address", node.address); put("port", node.port)
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", node.uuid); put("alterId", node.alterId); put("security", node.security)
                }))
            }))
            ProxyNode.Type.VLESS -> settings.put("vnext", JSONArray().put(JSONObject().apply {
                put("address", node.address); put("port", node.port)
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", node.uuid); put("encryption", node.encryption); put("flow", "")
                }))
            }))
            ProxyNode.Type.TROJAN -> settings.put("servers", JSONArray().put(JSONObject().apply {
                put("address", node.address); put("port", node.port); put("password", node.uuid)
            }))
        }

        val streamSettings = JSONObject().apply {
            put("network", node.network)
            val useTls = node.type == ProxyNode.Type.TROJAN || node.security == "tls"
            put("security", if (useTls) "tls" else "none")
            if (useTls) {
                put("tlsSettings", JSONObject().apply {
                    if (node.sni.isNotEmpty()) put("serverName", node.sni)
                })
            }
            if (node.network == "ws") {
                put("wsSettings", JSONObject().apply {
                    put("path", node.wsPath)
                    put("headers", JSONObject().put("Host", node.wsHost.ifEmpty { node.address }))
                })
            }
            if (node.network == "grpc") {
                put("grpcSettings", JSONObject().apply { put("serviceName", node.grpcServiceName) })
            }
        }

        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", node.type.name.lowercase())
            put("settings", settings)
            put("streamSettings", streamSettings)
        }
    }
}