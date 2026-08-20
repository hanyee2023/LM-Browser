package com.proxybrowser.app.core

import android.content.Context
import android.util.Log
import com.proxybrowser.app.data.ProxyNode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 通过 xray-core 二进制（随 APK 打包在 assets/xray/xray）启动本地 SOCKS5 代理，
 * WebView 的请求经 shouldInterceptRequest 走 127.0.0.1:10808 出去。
 *
 * 关键点：
 * 1. assets 里的二进制路径是 "xray/xray"（CI 下载后放在 assets/xray/ 目录下）。
 * 2. config 必须根据节点类型生成真实 outbound（vless / vmess / trojan），
 *    否则流量会走 freedom（直连），等于没代理。
 */
object V2RayManager {

    private const val TAG = "V2RayManager"
    const val PORT = 10808

    @Volatile private var process: Process? = null
    @Volatile private var activeNode: ProxyNode? = null
    @Volatile private var running = false

    fun start(ctx: Context, node: ProxyNode): Boolean {
        stop()
        val binaryPath = extractXray(ctx) ?: run {
            Log.e(TAG, "xray binary not found in assets")
            return false
        }
        val configFile = File(ctx.filesDir, "v2ray_config.json")
        try {
            configFile.writeText(buildConfig(node))
        } catch (e: Exception) {
            Log.e(TAG, "failed to write config", e)
            return false
        }

        activeNode = node
        return try {
            val cmd = listOf(binaryPath, "run", "-c", configFile.absolutePath)
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            process = pb.start()
            // 给一点时间让 xray 完成启动时解析；配置错误会立刻退出
            Thread.sleep(400)
            if (process?.isAlive != true) {
                Log.e(TAG, "xray exited immediately (config error?)")
                running = false
                false
            } else {
                running = true
                Log.i(TAG, "xray started for ${node.name}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to start xray", e)
            running = false
            false
        }
    }

    fun stop() {
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        activeNode = null
        running = false
    }

    fun isRunning(): Boolean = running && process?.isAlive == true

    /** 预热：把 assets 里的 xray 二进制解压到 filesDir 并赋可执行权限 */
    fun ensureInstalled(ctx: Context): Boolean = extractXray(ctx) != null

    fun activeNode(): ProxyNode? = activeNode
    fun port(): Int = PORT

    private fun extractXray(ctx: Context): String? {
        val dest = File(ctx.filesDir, "xray")
        if (dest.exists() && dest.canExecute()) return dest.absolutePath
        return try {
            // CI 把二进制放在 assets/xray/xray
            ctx.assets.open("xray/xray").use { inStream ->
                dest.outputStream().use { out -> inStream.copyTo(out) }
            }
            dest.setExecutable(true)
            if (!dest.canExecute()) {
                // 部分系统 setExecutable 不生效，尝试 chmod
                try {
                    Runtime.getRuntime().exec(arrayOf("chmod", "755", dest.absolutePath)).waitFor()
                } catch (_: Exception) {}
            }
            dest.absolutePath
        } catch (e: IOException) {
            Log.e(TAG, "extract xray failed", e)
            null
        }
    }

    // ============ xray 配置 ============
    private fun buildConfig(node: ProxyNode): String {
        val outbound = when (node.type) {
            ProxyNode.Type.VMESS -> JSONObject().apply {
                put("protocol", "vmess")
                put("settings", JSONObject().apply {
                    put("vnext", JSONArray().put(JSONObject().apply {
                        put("address", node.address)
                        put("port", node.port)
                        put("users", JSONArray().put(JSONObject().apply {
                            put("id", node.uuid)
                            put("alterId", node.alterId)
                            put("security", "auto")
                            put("level", 0)
                        }))
                    }))
                })
                put("streamSettings", buildStream(node))
            }
            ProxyNode.Type.VLESS -> JSONObject().apply {
                put("protocol", "vless")
                put("settings", JSONObject().apply {
                    put("vnext", JSONArray().put(JSONObject().apply {
                        put("address", node.address)
                        put("port", node.port)
                        put("users", JSONArray().put(JSONObject().apply {
                            put("id", node.uuid)
                            put("encryption", if (node.encryption.isNotEmpty()) node.encryption else "none")
                            put("level", 0)
                        }))
                    }))
                })
                put("streamSettings", buildStream(node))
            }
            ProxyNode.Type.TROJAN -> JSONObject().apply {
                put("protocol", "trojan")
                put("settings", JSONObject().apply {
                    put("servers", JSONArray().put(JSONObject().apply {
                        put("address", node.address)
                        put("port", node.port)
                        put("password", node.uuid)
                        put("level", 0)
                    }))
                })
                put("streamSettings", buildStream(node))
            }
        }

        return JSONObject().apply {
            put("log", JSONObject().apply {
                put("access", "")
                put("error", "")
                put("loglevel", "warning")
            })
            put("inbounds", JSONArray().put(JSONObject().apply {
                put("port", PORT)
                put("protocol", "socks")
                put("listen", "127.0.0.1")
                put("settings", JSONObject().apply { put("auth", "noauth") })
            }))
            put("outbounds", JSONArray().apply {
                put(outbound)
                put(JSONObject().apply { put("protocol", "freedom"); put("tag", "direct") })
                put(JSONObject().apply { put("protocol", "blackhole"); put("tag", "block") })
            })
            put("routing", JSONObject().apply {
                put("domainStrategy", "AsIs")
                put("rules", JSONArray())
            })
        }.toString()
    }

    private fun buildStream(node: ProxyNode): JSONObject {
        val net = if (node.network.isNotEmpty()) node.network else "tcp"
        val st = JSONObject().apply { put("network", net) }
        if (net == "ws") {
            st.put("wsSettings", JSONObject().apply {
                put("path", if (node.wsPath.isNotEmpty()) node.wsPath else "/")
                put("headers", JSONObject().apply {
                    if (node.wsHost.isNotEmpty()) put("Host", node.wsHost)
                })
            })
        }
        val security = when (node.type) {
            ProxyNode.Type.TROJAN -> "tls"
            ProxyNode.Type.VLESS -> if (node.security.isNotEmpty()) node.security
                else if (node.sni.isNotEmpty()) "tls" else "none"
            ProxyNode.Type.VMESS -> if (node.security.isNotEmpty()) node.security else "none"
        }
        if (security == "tls") {
            st.put("security", "tls")
            st.put("tlsSettings", JSONObject().apply {
                if (node.sni.isNotEmpty()) put("serverName", node.sni)
                put("allowInsecure", true)
            })
        } else {
            st.put("security", "none")
        }
        return st
    }
}
