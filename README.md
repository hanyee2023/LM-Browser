# ProxyBrowser

轻量 Android 代理浏览器 App，**App 内 WebView + 内嵌 xray-core** 直接走自己的代理节点，无需 VpnService / 第三方代理 App。

## 特性

| 模块 | 实现 |
|---|---|
| **代理核心** | 内嵌官方 xray-core 二进制（arm64-v8a），本地起 SOCKS5 10808 |
| **节点** | vless / vmess / trojan 单条 + 订阅导入（sub:// / https://） |
| **测速** | TCP 探活 + 可选代理延迟，永远有结果 |
| **WebView** | `shouldInterceptRequest` 拦截所有请求走 SOCKS5，不依赖 ProxyController |
| **广告拦截** | 内置精简规则集（EasyList 风格），命中返回 204 |
| **油猴脚本** | 解析 @match / @include / @exclude，注入 + `GM_setValue` / `GM_getValue` 桥 |
| **视频嗅探** | JS 钩子捕获 `.mp4` / `.m3u8` / `.flv` / `.ts` 等媒体源 |
| **主页** | 内置搜索 + 8 个常用入口卡片 |
| **界面** | VIA 风格：白底黑字、4dp 圆角、单色蓝强调 |

## 架构

```
App 内 WebView
    └─ shouldInterceptRequest → SOCKS5 (127.0.0.1:10808)
                                  └─ xray-core (内置二进制)
                                        └─ 你的节点 (vless/vmess/trojan)
                                              └─ YouTube / X / Telegram / 任意站点
```

| 文件 | 作用 |
|---|---|
| `core/V2RayManager.kt` | xray 进程生命周期 + 测速握手 |
| `core/XrayConfig.kt` | ProxyNode → xray JSON 配置 |
| `core/AdBlocker.kt` | 内置规则集 + 命中判定 |
| `core/UserScriptEngine.kt` | 元数据解析 + 注入代码生成 |
| `core/VideoSniffer.kt` | 嗅探列表 + 持久化 + JS 钩子 |
| `core/Settings.kt` | 开关持久化（广告/油猴/嗅探/DNT/UA） |
| `data/ProxyNode.kt` + `NodeParser.kt` | 节点模型 + vless/vmess/trojan/subscription 解析 |
| `data/NodeStore.kt` | SharedPreferences 持久化 |
| `ui/NodesActivity.kt` | 节点管理（增删/订阅/测速/选用） |
| `ui/BrowserActivity.kt` | 浏览器主页 + 拦截 + 注入 + 嗅探 |
| `ui/SnifferActivity.kt` | 嗅探列表（下载/复制/删除） |
| `ui/SettingsActivity.kt` | 开关 + 脚本管理 + 自定义 UA |
| `assets/home.html` | 浏览器主页 HTML（搜索 + 书签卡片 + 状态） |
| `.github/workflows/build.yml` | CI：下载 xray 二进制 + 编译 APK |

## 上手

1. **下载** `ProxyBrowser.zip`，解压
2. GitHub 新建仓库 `ProxyBrowser`（别勾 README）→ 拖入解压后**所有根级内容**（含隐藏的 `.github/`）
3. 进入 **Actions** → `Build APK` 自动跑（5–15 分钟）
4. 下载 Artifacts 里的 `app-debug-apk`，安装到手机
5. 打开 App → 添加 / 订阅 节点 → 测速 → 选节点「使用」→ 自动打开浏览器

## 主题 (VIA 风格)

| 元素 | 值 |
|---|---|
| 背景 | `#FFFFFF` |
| 主文本 | `#1A1A1A` |
| 副文本 | `#6B7280` |
| 主色 | `#3B82F6` (蓝) |
| 成功 | `#10B981` (绿) |
| 危险 | `#EF4444` (红) |
| 圆角 | 4dp（按钮） / 8dp（卡片） / 20dp（地址栏） |

## 已知限制

- **xray 二进制只支持 arm64-v8a** —— 覆盖 99% 国产手机；armeabi-v7a / x86 设备需在 `build.yml` 追加其他 release zip
- **YouTube 视频嗅探**对加密流媒体（`googlevideo.com` 分段）成功率中等，能抓到主 m3u8 入口
- **油猴**只支持最小集（@match / @include / @exclude / GM_setValue / GM_getValue / GM_deleteValue），不支持 unsafeWindow / @require
- **WebView 拦截**对 100% 重定向或 blob URL 不支持
