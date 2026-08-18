package com.proxybrowser.app.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.proxybrowser.app.data.ProxyNode
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 直接拉起 xray-core 二进制（不依赖 libv2ray/JitPack）。
 *
 * 架构：
 *   - assets/xray/xray 由 CI 在编译期下载并打包进 APK
 *   - 首次启动从 assets 复制到 filesDir 并 chmod +x
 *   - 每次 start 写一份配置到 cacheDir，然后 ProcessBuilder 拉起 xray run -c <config>
 *
 * 通信：
 *   - SOCKS5 入站 127.0.0.1:10808（给 BrowserActivity 拦截 WebView 请求走代理 + 给测速握手）
 *   - HTTP   入站 127.0.0.1:10809（保留备用）
 */
object V2RayManager {

    private const val TAG = "V2RayManager"
    private const val HTTP_PORT = 10809
    private const val SOCKS_PORT = 10808

    @Volatile private var process: Process? = null
    @Volatile private var currentNode: ProxyNode? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "v2ray-io").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun isRunning(): Boolean = process?.isAlive == true
    fun currentNode(): ProxyNode? = currentNode
    fun localProxyAddress(): String = "127.0.0.1:$HTTP_PORT"

    /** 从 assets 复制 xray 到 filesDir 并赋予可执行权限。 */
    fun ensureInstalled(ctx: Context): File {
        val dir = File(ctx.filesDir, "xray")
        val exe = File(dir, "xray")
        if (exe.exists() && exe.canExecute()) return exe
        dir.mkdirs()
        ctx.assets.open("xray/xray").use { input ->
            FileOutputStream(exe).use { input.copyTo(it) }
        }
        exe.setExecutable(true, false)
        exe.setReadable(true, false)
        return exe
    }

    /** 启动 xray，承载传入节点的流量；替换节点时先 stop。 */
    fun start(ctx: Context, node: ProxyNode): Boolean {
        stop()
        val exe = try {
            ensureInstalled(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "ensureInstalled failed", e); return false
        }
        val configFile = File(ctx.cacheDir, "xray-config.json")
        try {
            configFile.writeText(XrayConfig.build(node, HTTP_PORT, SOCKS_PORT))
        } catch (e: Exception) {
            Log.e(TAG, "write config failed", e); return false
        }
        currentNode = node
        val p = try {
            ProcessBuilder(exe.absolutePath, "run", "-c", configFile.absolutePath)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "ProcessBuilder start failed", e); return false
        }
        process = p
        drainProcessOutput(p) // 防止 stdout 缓冲区满阻塞 xray
        return true
    }

    fun stop() {
        process?.let { p ->
            try { p.destroy() } catch (_: Exception) {}
            try { p.waitFor(2, TimeUnit.SECONDS) } catch (_: Exception) {}
            try { p.destroyForcibly() } catch (_: Exception) {}
        }
        process = null
        currentNode = null
    }

    private fun drainProcessOutput(p: Process) {
        Thread({
            try {
                p.inputStream.bufferedReader().use { it.readLines() }
            } catch (_: Exception) {}
        }, "v2ray-log-drain").apply { isDaemon = true }.start()
    }

    /**
     * 测速：临时启动一个 xray 实例，做一次 SOCKS5 CONNECT 到 1.1.1.1:80，测往返时延。
     * 结果通过 onResult 在主线程回调：Long 为毫秒数，null 表示该节点无效。
     */
    fun measure(ctx: Context, node: ProxyNode, timeoutMs: Long = 6000, onResult: (Long?) -> Unit) {
        io.execute {
            val r = measureSync(ctx, node, timeoutMs)
            main.post { onResult(r) }
        }
    }

    private fun measureSync(ctx: Context, node: ProxyNode, timeoutMs: Long): Long? {
        val exe = try { ensureInstalled(ctx) } catch (_: Exception) { return null }
        val testCfg = File(ctx.cacheDir, "xray-measure-${System.currentTimeMillis()}.json")
        val p: Process
        try {
            testCfg.writeText(XrayConfig.build(node, HTTP_PORT, SOCKS_PORT))
            p = ProcessBuilder(exe.absolutePath, "run", "-c", testCfg.absolutePath)
                .redirectErrorStream(true).start()
        } catch (_: Exception) {
            testCfg.delete(); return null
        }
        // 异步排干日志
        Thread({
            try { p.inputStream.bufferedReader().readLines() } catch (_: Exception) {}
        }, "v2ray-measure-drain").apply { isDaemon = true }.start()

        // 等本地 SOCKS 端口起来（最多 5 秒）
        val portDeadline = System.currentTimeMillis() + 5000
        var ready = false
        while (System.currentTimeMillis() < portDeadline) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", SOCKS_PORT), 300) }
                ready = true; break
            } catch (_: Exception) {
                Thread.sleep(150)
            }
        }
        if (!ready) {
            try { p.destroyForcibly() } catch (_: Exception) {}
            testCfg.delete(); return null
        }

        // SOCKS5 CONNECT 到 1.1.1.1:80（连通性强、对端无业务负载）
        val t0 = System.currentTimeMillis()
        val ok = try {
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", SOCKS_PORT), 2000)
            s.soTimeout = (timeoutMs - (System.currentTimeMillis() - t0)).toInt().coerceAtLeast(1500)
            val out = s.getOutputStream()
            val ins = s.inputStream
            val host = "1.1.1.1"
            val port = 80

            // Greeting: VER=5, NMETHODS=1, METHODS=[0]
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            val greet = ByteArray(2)
            if (ins.read(greet) != 2 || greet[1] != 0x00.toByte()) { s.close(); false }
            else {
                // CONNECT request：VER=5, CMD=1, RSV=0, ATYP=1 (IPv4), ADDR(4), PORT(2)
                val pkt = ByteArray(4 + 4 + 2)
                pkt[0] = 0x05; pkt[1] = 0x01; pkt[2] = 0x00; pkt[3] = 0x01
                val parts = host.split(".")
                for (i in 0..3) pkt[4 + i] = parts[i].toInt().toByte()
                pkt[8] = (port ushr 8).toByte(); pkt[9] = (port and 0xff).toByte()
                out.write(pkt); out.flush()
                val resp = ByteArray(4)
                if (ins.read(resp) != 4) { s.close(); false }
                else { s.close(); resp[1] == 0x00.toByte() }
            }
        } catch (_: Exception) { false }

        try { p.destroy() } catch (_: Exception) {}
        try { p.waitFor(1, TimeUnit.SECONDS); p.destroyForcibly() } catch (_: Exception) {}
        testCfg.delete()

        return if (ok) System.currentTimeMillis() - t0 else null
    }
}