# ProxyBrowser —— 轻量代理浏览器 App（内嵌 xray-core）

自己有代理节点、不想装 v2ray 类独立 App、只在手机浏览器里访问 YouTube / X / Telegram。
本工程 = 系统 WebView 浏览器 + 内嵌 xray-core 本地代理，**不走 VpnService**，
仅代理 App 内浏览器，免费、稳定度由你节点决定。无任何本地 Android 环境要求，APK 由 GitHub Actions 云端编译。

## 架构

```
App 内 WebView（轻量浏览器 + 地址栏）
   └─> 本地 HTTP 代理 127.0.0.1:10809   （ProxyController 仅代理此 WebView）
         └─> xray-core 进程（官方二进制，assets/xray/xray）
               └─> 节点 vless / vmess / trojan（单条 or 订阅）
                     └─> 目标站点

测速另起一个临时 xray + SOCKS 127.0.0.1:10808，握手测延迟。
```

> **不依赖 libv2ray / JitPack**。xray-core 官方二进制由 CI 在编译时下载到 `app/src/main/assets/xray/xray`，App 通过 `ProcessBuilder` 直接拉起。

## 功能路线

| 功能 | 状态 | 实现 |
|---|---|---|
| 轻量 WebView 浏览器 + 地址栏 | ✅ P1 | 系统 WebView + `ProxyController` |
| 单条节点 vless/vmess/trojan | ✅ P1 | `NodeParser.parseSingle` |
| 订阅导入（sub:// 或 https） | ✅ P1 | `NodeParser.parse` |
| 节点持久化 | ✅ P5 | `NodeStore`（SharedPreferences） |
| 节点列表 / 选用 / 删除 | ✅ P5 | `NodesActivity` |
| 实时测速 / 有效性 | ✅ P5 | `V2RayManager.measure`（SOCKS5 CONNECT） |
| 广告拦截 | ⬜ P2 | `shouldInterceptRequest` + EasyList |
| 油猴脚本 | ⬜ P3 | 解析 `@match`/`@run-at` + `GM_*` 桥 |
| 视频嗅探 | ⬜ P4 | 注入 JS 钩 `HTMLMediaElement`/`fetch` |

## 使用流程

1. 打开 App → 进入「代理节点」页。
2. 「＋ 单节点」粘贴 `vless://` / `vmess://` / `trojan://`；或「⇩ 订阅」粘贴 `sub://` / `https` 订阅链接。
3. 「测速」批量测延迟，挑快的。
4. 点节点右侧「使用」→ 自动连节点并打开浏览器，直接访问 YouTube / X / Telegram。

## 构建（CI 出 APK）

1. 把本目录内容（注意 `.github/` 是隐藏文件夹）推到 GitHub 仓库 `ProxyBrowser`。
2. GitHub Actions 自动用 JDK17 + Gradle 编译 `app:assembleDebug`，CI 中会先下载 xray-core 二进制到 `assets/xray/`，再打包进 APK。
3. 产物为 `app-debug.apk`，侧载安装即可（个人使用，无需上架）。

> 上传时若 .github 漏传：到 Actions 页 → set up a workflow yourself → 粘贴本仓 `.github/workflows/build.yml` 内容即可。

## 目录

- `data/ProxyNode.kt` —— 节点模型 + 单条/订阅解析
- `data/NodeStore.kt` —— 节点持久化（SharedPreferences）
- `core/XrayConfig.kt` —— 节点 → xray 配置（HTTP + SOCKS 双入站）
- `core/V2RayManager.kt` —— `ProcessBuilder` 拉起 xray，含测速
- `ui/NodesActivity.kt` + `res/layout/activity_nodes.xml` + `item_node.xml` —— 节点管理 UI
- `ui/BrowserActivity.kt` —— WebView 浏览器 + 地址栏 + 本地代理
- `App.kt` —— 应用入口，预热 xray 二进制
- `.github/workflows/build.yml` —— 云端编译 APK（含 xray-core 下载）
