package com.proxybrowser.app.core

import android.content.Context
import com.proxybrowser.app.data.ProxyNode
import libv2ray.Libv2ray
import java.io.File

/**
 * libv2ray（xray-core Android 绑定）的封装。
 *
 * 注意：libv2ray 的静态方法签名随版本略有差异。首次 CI 编译若报方法不存在，
 * 以你引入的 libv2ray 版本源码为准微调下方调用即可（initCoreEnv / startV2Ray /
 * stopV2Ray / isV2RayRunning / measureV2Ray）。
 */
object V2RayManager {

    private lateinit var appContext: Context
    private const val LOCAL_PORT = 10809

    fun init(context: Context) {
        appContext = context.applicationContext
        Libv2ray.initCoreEnv(appContext.filesDir.path)
    }

    /** 启动代理（仅服务 App 内 WebView） */
    fun start(node: ProxyNode): Boolean {
        val config = XrayConfig.build(node, LOCAL_PORT)
        val logPath = File(appContext.filesDir, "v2ray.log").path
        return Libv2ray.startV2Ray(config, logPath, false)
    }

    /** 切换到指定节点：若已在跑先停再起 */
    fun connect(node: ProxyNode): Boolean {
        if (Libv2ray.isV2RayRunning()) Libv2ray.stopV2Ray()
        return start(node)
    }

    fun stop() {
        if (Libv2ray.isV2RayRunning()) Libv2ray.stopV2Ray()
    }

    fun isRunning(): Boolean = Libv2ray.isV2RayRunning()

    /** 测速：返回延迟毫秒，失败 -1。基于 libv2ray 的单节点测延迟能力。 */
    fun measure(node: ProxyNode): Long {
        val config = XrayConfig.build(node, LOCAL_PORT)
        return runCatching { Libv2ray.measureV2Ray(config) }.getOrDefault(-1)
    }

    /** ProxyController 要连的本地地址 */
    val localProxyAddress: String get() = "127.0.0.1:$LOCAL_PORT"
}
