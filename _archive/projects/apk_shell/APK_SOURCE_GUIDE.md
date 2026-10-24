# APK 壳源码指导（APK Source Guide）

> 面向对象：需要阅读/扩展 APK 导出壳源码的开发者与 Agent（`export_apk` 工具）。
> 本文件是**壳源码本身的指导**（工程结构、桥方法源码清单、扩展流程）；
> HTML 内容生成规则见同目录 `HTML_DESIGN_RULES.md`。
> 当前壳：**v9.15**（shell_version=v9.15，bridge_api=6，targetSdk 35 / minSdk 26，Android 15 强制边缘到边）。v9.11 新增**电视直播**（原生 ExoPlayer + HLS）；v9.12 新增**一键直播**（manifest `live` 配置直接进原生直播首页，无需 HTML）；v9.13 修复 **HLS 302 跳转后相对分片拼到旧主机** 导致的 `ERROR_CODE_IO_BAD_HTTP_STATUS`；v9.14 支持**包内 m3u 相对流地址**（localhost 静态服务）并重做直播首页/播放器 UI；v9.15 支持 **tvg-logo/配置目录/自适应台标**。

---

## 一、源码工程结构

```
apk_shell/                                  # 壳源码工程（独立 Gradle 模块）
├── build.gradle                            # applicationId 占位 com.cjhtmldemo.xxxxxxxxxxxxx（28 字符）；versionCode 29 / versionName 9.15
├── src/main/
│   ├── AndroidManifest.xml                 # 壳清单（权限、FileProvider、MainActivity、LiveTvHomeActivity、LivePlayerActivity、ForegroundBridgeService）
│   ├── java/com/cjhtmldemo/apk/
│   │   ├── MainActivity.java               # ★ 核心：WebView 壳 + 全部 JS 桥（AppBridge，81 个 @JavascriptInterface / 80 个方法名）
│   │   ├── HtmlHttpServer.java             # 本地 HTTP 服务（localhost 候选端口加载 html，防跨目录）
│   │   ├── ForegroundBridgeService.java    # 前台服务（桥 startForeground/stopForeground 的实现载体）
│   │   └── tv/                             # ★ v9.11/v9.12 电视直播
│   │       ├── LiveChannel.java            # 频道模型（id/name/group/logo/url/headers）
│   │       ├── M3uParser.java              # M3U/M3U8 解析（EXTINF/tvg-*/EXTGRP/EXTVLCOPT/EXTHTTP/相对地址）
│   │       ├── LivePlaylistRepository.java # 抓取（重定向/超时/UA/gzip/8MB）+ 解析 + 磁盘缓存回退
│   │       ├── LiveStore.java              # 频道源/收藏/上次播放（SharedPreferences）
│   │       ├── LiveTvHomeActivity.java     # ★ v9.12 原生直播首页（频道列表/搜索/收藏/刷新/换源/错误重试）
│   │       ├── LogoLoader.java              # ★ v9.15 台标加载（http/file/content + 采样 + 内存缓存）
│   │       ├── ChannelLogoView.java         # ★ v9.15 台标/自适应占位（CCTV数字/分组图标）
│   │       ├── LivePlayerActivity.java     # 全屏 ExoPlayer 播放页（HLS 直播窗口/重连/看门狗/遥控器）
│   │       ├── LiveSessionLauncher.java    # 统一起播入口（频道列表走内部文件，避开 Binder 大传输）
│   │       ├── LiveTvBridge.java           # 桥业务实现（两套壳共用）
│   │       └── LiveTvEvents.java           # 进程内事件总线（started/error/closed → liveevent）
│   └── res/                                # 图标（res/RJ.png 等）、strings（应用名占位 SmartQuizExportAppName）
├── tools/gen_meta.py                       # 生成 assets/apk_shell_meta.json（重建模板后必须重跑）
├── samples/engine_demo/index.html          # 壳能力自测台（枚举全部桥方法/权限灯/新能力实测）
├── samples/live_tv/index.html              # 电视直播完整自测页（频道/播放/收藏/源管理）
├── guides/live_tv_template.html            # 电视直播最小模板（随 App 恢复到工作区 files/，Agent 可直接复制）
├── guides/CONFIG_GUIDE.md                   # ★ v9.15 配置目录（config/channels.m3u + config/logos）与变更方式
├── HTML_DESIGN_RULES.md                    # HTML 生成规则（Agent 生成内容时遵循）
└── README.md                               # 工程说明与构建命令
```

构建产物：`build/outputs/apk/release/apk_shell-release-unsigned.apk`
→ 替换主项目 `src/main/assets/apk_shell/base.apk`（导出时 ApkPacker 以此为模板）。

## 二、导出链路（源码级）

```
Agent 调用 export_apk（ExportApkTool.java）
  → HTML 来源四选一：url / html / html_file / html_dir
  → ApkPacker.buildApkFromUrl / buildApkFromDir / buildApk（com.oilquiz.app.util.export.ApkPacker）
      ├─ HTML 内容 → ZIP → AES-128-CBC 加密（key=MyHtmlEditorKey1，IV=文件头 16 字节）→ dt.jet
      ├─ 替换进壳模板 base.apk（assets/dt.jet + assets/manifest.json）
      ├─ resources.arsc 原位改应用名（SmartQuizExportAppName → 指定应用名，UTF-8 字节 ≤22）
      ├─ res 图标替换（按 apk_shell_meta.json 的 icon_entry）
      ├─ AXML 字符串池等长替换包名（com.cjhtmldemo.xxxxxxxxxxxxx → com.cjhtmldemo.p<12位sha1hex>）
      └─ export.keystore 重签名
  → 输出 APK（工作区 apk_export/）+ 返回 路径/包名/SHA-256

壳运行时（MainActivity.onCreate → bootstrap）：
  manifest.json 读取 → **有 live.url：跳过 WebView，直接 LiveTvHomeActivity**
  （远程源直接用；相对路径从 dt.jet 解压后用进程级 localhost 静态服务提供，相对流地址可解析到包内文件）
  → 频道首页点播/autoplay → LivePlayerActivity
  否则本地模式：解密 dt.jet → ZIP 解压 filesDir/html
  → 注入内置库 assets/libs → 本地 HTTP 服务 → http://localhost:{port}/index.html
  远程模式：manifest.json 带 "url" → 直接加载远程地址（不注入 libs）
```

**一键直播 manifest 示例**：

```json
{"main":"index.html","targver":1,
 "live":{"url":"https://example.com/live.m3u","name":"我的电视","start":"CCTV1","autoplay":true}}
```

- `live.url` 为 http(s) 远程地址，或包内相对路径（如 `live.m3u`，需 HTML 包里包含该文件）。
- `live.autoplay=true` 或频道源只有一路时，首页加载完直接进播放器。
- `MainActivity` 在 live 模式下完成解压/读取后 `finish()`，`LiveTvHomeActivity` 是任务根；返回键退出。
- 原生首页提供搜索、收藏筛选、刷新、换源、错误重试；不会再回到 WebView。


### 桌面导出 CLI（ExportCli.java，无需 App 内工具）

桌面侧（Windows/JDK 21+）可用 `tools/ExportCli.java` 直接导出，与 App 内 `ApkPacker.buildApkFromDir` 完全同一链路
（dir→ZIP→AES→模板替换→arsc 对齐自检→apksig v1+v2+v3），仅把 3 处 Context 依赖替换为本地文件。

```
编译：javac -encoding UTF-8 -cp "<SDK>/build-tools/35.0.0/lib/apksigner.jar" ExportCli.java
运行（PowerShell）：
  $env:SHELL_TEMPLATE = "D:\qzq\smartquiz\src\main\assets\apk_shell\base.apk"
  $env:SHELL_KEYSTORE  = "D:\qzq\smartquiz\src\main\assets\apk_shell\export.keystore"
  java -cp ".;<SDK>\build-tools\35.0.0\lib\apksigner.jar" ExportCli `
      <html_dir> <out.apk> [--label 应用名] [--icon icon.png] [--seed 包名种子]
```

- `html_dir`：含 index.html 的多文件目录（css/js/子目录原样打包）——与 App 内 html_dir 语义一致
- `--label`：应用名（UTF-8 ≤22 字节，中文约 7 字）；`--icon`：PNG 图标；`--seed`：派生独立包名
  `com.cjhtmldemo.p<12位sha1hex>`（同一 seed 重复导出 = 升级覆盖，不同 seed 可共存）
- 环境变量可省略：默认相对路径 `src/main/assets/apk_shell/base.apk` 与同目录 `export.keystore`
- 输出关键日志：`label patched` / `package patched` / `arsc self-check` / `signed APK`

```

## 三、JS 桥完整清单（80 个方法名 / 81 个 @JavascriptInterface，源码为准）

暴露对象：`webView.addJavascriptInterface(new AppBridge(), "AndroidApp")` →
页面内使用 `window.AndroidApp.<method>(...)`，调用前建议判空：

```js
function bridge() { return window.AndroidApp || null; }
```

### 基础 / 系统（10）

| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `showToast(msg)` | string | — | 底部提示 |
| `vibrate(ms)` | number | — | 振动（需硬件） |
| `haptic()` | — | — | 轻触反馈（20ms） |
| `exit()` | — | — | 退出应用 |
| `copyText(text)` | string | — | 复制到剪贴板 + 提示 |
| `shareText(text)` | string | — | 系统分享文本 |
| `shareFile(relPath)` | string | — | 分享壳内文件（相对 filesDir/html，仅本地模式） |
| `openBrowser(url)` | string | — | 系统浏览器打开 |
| `setKeepScreenOn(keepOn)` | boolean | — | 屏幕常亮开关 |
| `openAppSettings()` | — | — | 打开本应用系统设置页 |

### 信息探测（8）

| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `getVersion()` | — | string(JSON) | `{shellVersion, bridgeApi, versionName, versionCode}` |
| `getShellVersion()` | — | string | 壳版本，如 `"v9.11"`（编译进 dex） |
| `getBridgeApi()` | — | number | 桥 API 版本（当前 5） |
| `getDeviceInfo()` | — | string(JSON) | `{screenWidth, screenHeight, density, densityDpi, sdkInt, model, manufacturer, launchMode}` |
| `getNetworkType()` | — | string | `wifi`/`mobile`/`none`/`other`/`unknown` |
| `getBatteryLevel()` | — | string | 电量百分比 0-100 |
| `isCharging()` | — | string | `"true"`/`"false"` |
| `getStorageInfo()` | — | string(JSON) | `{internalTotalMB, internalFreeMB, externalTotalMB, externalFreeMB}` |

### 权限（3）

| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `checkPermission(name)` | string | string | 是否已授权（`"true"`/`"false"`） |
| `requestPermission(name, callbackName)` | string×2 | — | 请求权限；回调 `window[cb]({permission, granted})` |
| `openPermissionPanel()` | — | — | 直接打开系统权限管理页 |

权限 name 取值：`camera`(CAMERA) / `mic`|`microphone`(RECORD_AUDIO) /
`storage`|`media`(33+: READ_MEDIA_*；26-32: READ_EXTERNAL_STORAGE) /
`notification`(33+: POST_NOTIFICATIONS) / `location`(ACCESS_FINE_LOCATION)。

### 网络代理（1）

| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `request(url, method, headersJson, body, callbackName)` | string×5 | — | **无 CORS 网络代理**；回调 `window[cb]({status, body})`；网络错误时 status=0 且带 error |

### 文件 / 截图 / 剪贴板（5）

| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `openFilePicker(callbackName)` | string | — | 系统文件选择（SAF）；回调 `window[cb]({name,size,mimeType,dataBase64})`，≤2MB |
| `screenshot(callbackName)` | string | — | 当前页面截图；回调 `window[cb]({dataBase64,width,height})`（PNG base64） |
| `readClipboard()` | — | string | 读取剪贴板文本（Android 13+ 会显示系统提示条） |
| `startClipboardWatch(callbackName)` | string | — | 剪贴板监听；内容变化回调 `window[cb]({text})` |
| `stopClipboardWatch()` | — | — | 停止剪贴板监听 |

### 通知 / 前台服务 / 深链 / 注入 / 缓存（13）

| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `showNotification(title, body)` | string×2 | — | 发通知（NOTIFY_ID_BASE 自增） |
| `cancelNotification(id)` | number | — | 取消指定通知 |
| `cancelAllNotifications()` | — | — | 取消全部通知 |
| `startForeground(title, text)` | string×2 | — | 启动前台服务（常驻通知，防后台杀） |
| `stopForeground()` | — | — | 停止前台服务 |
| `getDeepLink()` | — | string | 启动时深链（onNewIntent 更新） |
| `injectNow(code)` | string | — | 立即向当前页面注入 JS |
| `addScriptInjector(code)` | string | — | 注册 JS 注入器（每次页面加载完成注入） |
| `clearScriptInjectors()` | — | — | 清空注入器 |
| `setCacheMode(mode)` | number | — | 缓存模式：0=默认 / 1=本地优先 / 2=离线 |
| `getCacheMode()` | — | number | 当前缓存模式 |
| `clearCache()` | — | — | 清空缓存（含缓存文件） |
| `precacheUrl(url, callbackName)` | string×2 | — | 预缓存远程 URL；回调 `window[cb]({url, ok})` |

### v8/v8.1 新增：全屏 / 方向 / 亮度 / TTS / 壳内文件 / 选择器 / 对话框 / 系统 / 应用（19）

| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `enterFullscreen(on)` | boolean | — | 沉浸全屏：true=隐藏系统状态栏/导航栏；false=恢复。失焦后自动重新应用；通知页面 `window.__shellFullscreen` + `shellfullscreenchange` 事件 |
| `isFullscreen()` | — | boolean | 当前是否沉浸全屏（页面初始化对齐用） |
| `setOrientation(mode)` | string | — | 屏幕方向：`landscape` / `portrait` / `auto` |
| `getOrientation()` | — | string | 当前物理方向：`landscape` / `portrait` |
| `setBrightness(value)` | number | — | 系统亮度 0-255；`-1` 恢复跟随系统 |
| `getBrightness()` | — | number | 当前亮度 0-255；`-1`=跟随系统 |
| `speakText(text)` | string | — | TTS 中文朗读（重复调用打断上一次；引擎异步初始化期间待播文本自动补播；中文引擎缺失自动回退系统语言） |
| `stopSpeak()` | — | — | 停止朗读 |
| `saveFile(relPath, dataBase64)` | string×2 | string(JSON) | 保存文件到壳内目录（相对 htmlDir，防穿越，≤8MB）；`{ok, size}` |
| `readFile(relPath)` | string | string(JSON) | 读壳内文件（≤8MB）；`{ok, size, dataBase64}` |
| `listFiles(relDir)` | string | string(JSON) | 列壳内目录（空/缺省=根）；`{ok, files:[{name,isDir,size}]}` |
| `deleteFile(relPath)` | string | string(JSON) | 删壳内文件/空目录；`{ok}` 或 `{ok:false,error}` |
| `pickDate(callbackName)` | string | — | 原生日期选择器；回调 `window[cb]({year,month,day})` |
| `pickTime(callbackName)` | string | — | 原生时间选择器；回调 `window[cb]({hour,minute})` |
| `showDialog(title, msg, callbackName)` | string×3 | — | 原生确认框；确定→`window[cb]({"result":"ok"})`，取消→`{"result":"cancel"}` |
| `getSystemInfo()` | — | string(JSON) | `{language, timeZone, androidVersion, brand, device, statusBarHeight, navigationBarHeight}` |
| `isAppInstalled(packageName)` | string | boolean | 系统是否安装指定应用 |
| `openApp(packageName)` | string | — | 打开其他应用（按包名） |
| `openInApp(url)` | string | — | 壳内打开指定 URL（路由到主 WebView） |

> 说明：上表为历史能力；当前 v9.11 全量 **80 个方法名 / 81 个 @JavascriptInterface**（`speakText` 有两个重载），`HTML_DESIGN_RULES.md` 第四节为常用能力速查。
>
> **v9.11 新增 13 个直播方法**（详见下一节）：
> `openLive` / `openLiveTv` / `loadLiveChannels` / `parseLiveChannels` / `openLiveChannels` /
> `stopLive` / `saveLiveSource` / `deleteLiveSource` / `getLiveSources` / `getLiveFavorites` /
> `toggleLiveFavorite` / `getLastLiveChannel` / `clearLiveHistory`。

### v9.11 新增：电视直播（13 个桥 + 1 个原生播放页）

原生 ExoPlayer + HLS，支持 M3U/M3U8 频道源、UA/Referer、频道切换、直播窗口自愈/卡死看门狗、遥控器左右切台。**只支持 HTTP(S)**；RTSP/RTMP/DASH 未引入对应模块，会明确报错。

| 方法 | 参数 | 返回/回调 | 说明 |
|---|---|---|---|
| `openLive(url,title,headersJson)` | string×3 | string(JSON) | 单路播放；headers 可空 |
| `openLiveTv(playlistUrl,sourceName,startChannel,cb)` | string×4 | 回调 `{ok,count,cached,warning,error}` | 抓取频道源并打开播放器 |
| `loadLiveChannels(playlistUrl,cb)` | string×2 | 回调 `{ok,count,channels,cached,singleStream,truncated,warning}` | 只加载列表（channels 最多 3000 个） |
| `parseLiveChannels(text,baseUrl,cb)` | string×3 | 同 load | 解析页面已有文本 |
| `openLiveChannels(channelsJson,index,sourceName)` | string,int,string | — | 播放页面构造的频道数组 |
| `stopLive()` | — | — | 关闭播放器 |
| `saveLiveSource(name,url)` | string×2 | `{ok,source}` | 显式保存源 |
| `deleteLiveSource(id)` | string | `{ok,deleted}` | 删除源 |
| `getLiveSources()` | — | `{ok,sources}` | 已保存源 |
| `getLiveFavorites()` | — | `{ok,channels}` | 收藏 |
| `toggleLiveFavorite(channelJson)` | string | `{ok,favorite,channel}` | 切换收藏 |
| `getLastLiveChannel()` | — | `{ok,last}` | 上次播放 |
| `clearLiveHistory()` | — | `{ok}` | 清除上次播放 |

播放器事件走 DOM：`window.addEventListener('liveevent', e => ...)`，`e.detail.action ∈ started/error/closed`。播放器是独立 `LivePlayerActivity`，与 WebView 同进程；事件在页面后台时排队，回前台补发。频道列表通过内部文件传给播放器，避免上千频道触发 Binder 大传输。

源格式：`#EXTINF` 的 `tvg-name/tvg-logo/group-title`、`#EXTGRP`、`#EXTVLCOPT:http-user-agent/http-referrer`、`#EXTHTTP:{json}`、相对流地址；相对地址按清单 URL 解析。`LivePlaylistRepository` 抓取失败会回退磁盘缓存并在结果里带 `cached=true` + `warning`。

体积：media3 HLS + PlayerView 让每个导出 APK 增加约 **2.6MB**（模板 2.4MB → 5.1MB）。

### v8.1 新增：状态栏适配 / 沉浸高度（3）——targetSdk 35 强制边缘到边

壳 **v8.1 = targetSdk 35**（Android 15 强制边缘到边，替代 v8 的手动 setStatusBarColor/LAYOUT_FULLSCREEN 方案）：
状态栏/导航栏透明，HTML 内容延伸到系统栏后面，深色页面与状态栏融为一体；
**全屏（enterFullscreen(true)）时壳同时把状态栏/导航栏背景设为透明**，配合 hide() 无黑条（竖屏顶部/横屏左侧）。
页面用 `--sa-t/--sa-b` 或桥高度撑开避免内容重叠；**高度桥返回物理 px，JS 侧必须除以 `devicePixelRatio` 再当 CSS px 用**（极光时钟 syncSafe 已实现：桥优先、÷DPR、上限 64px 防御）。

| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `setStatusBarStyle(style)` | "light"\|"dark" | — | 状态栏/导航栏图标明暗；light=浅色图标（深色页面，默认），dark=深色图标（浅色页面） |
| `getStatusBarHeight()` | — | string(物理px) | 状态栏高度（**物理像素，JS 需 ÷DPR**）；HTML 沉浸适配优先用它（env(safe-area) 在部分 WebView 不可靠） |
| `getNavBarHeight()` | — | string(物理px) | 导航栏高度（**物理像素，JS 需 ÷DPR**；手势导航=0，三键返回实体高度） |

权限回调新增 `reason` + `human` 字段（防重复弹窗/永久拒绝引导 + **人性化中文描述**）：
`requestPermission` 结果 `{permission, granted, reason, human}`，reason ∈ `granted | denied | denied_forever | busy | unknown`；
`human` 为可直接展示的中文提示（如"麦克风权限被拒绝，相关功能将不可用"），JS 无需自己拼错误文案；
`busy`=已有待授权请求（防叠加系统权限框）；`denied_forever`=用户此前选"不再询问"（JS 应引导打开权限设置页 openPermissionPanel）。
## 四、回调桥契约（callbackName 必填）

带回调的方法，**回调函数名参数必填**，缺参页面会报 `Method not found`：
**回调参数一律为 JSON 对象字面量**（`window[cb]({...})` 直接传入对象；历史版本曾把整个 JSON 再包一层引号导致 JS 收到字符串而非对象——`r.dataBase64`/`r.ok` 等属性取不到——v8.1 已全部修复为传对象）：

| 方法 | 回调签名 |
|---|---|
| `request` | `window[cb]({status, body})`；网络错误 status=0 且带 error |
| `requestPermission` | `window[cb]({permission, granted, reason, human})`（reason ∈ granted/denied/denied_forever/busy/unknown；human 为中文提示） |
| `openFilePicker` | `window[cb]({name,size,mimeType,dataBase64})` |
| `screenshot` | `window[cb]({dataBase64,width,height})` |
| `startClipboardWatch` | `window[cb]({text})` |
| `precacheUrl` | `window[cb]({url, ok})` |
| `pickDate` | `window[cb]({year,month,day})` |
| `pickTime` | `window[cb]({hour,minute})` |
| `showDialog` | `window[cb]({"result":"ok"})` / `{"result":"cancel"}` |

回调实现需挂在 `window` 上（壳按名查找）：

```js
window.cbPerm = function (r) { console.log(r.permission, r.granted, r.human); };
window.AndroidApp.requestPermission("camera", "cbPerm");
```

## 五、扩展壳源码流程（新增/修改桥方法）

1. 修改 `MainActivity.java` 的 `AppBridge` 内部类，新增 `@JavascriptInterface` 方法
   （注意线程：UI 操作需 `runOnUiThread`；耗时操作放子线程后回调 JS）。
2. 构建：`cd apk_shell && .\gradlew.bat assembleRelease`（首次联网拉 androidx.core + androidx.media3 1.3.1，之后可 `--offline`）。v9.11 起依赖 media3-exoplayer / media3-exoplayer-hls / media3-ui；清单必须保留 `WAKE_LOCK` 权限（ExoPlayer `setWakeMode(NETWORK)` 需要，缺失会在起播时崩溃）。3. 生成元数据：`python tools\gen_meta.py build\outputs\apk\release\apk_shell-release-unsigned.apk`
   （每次重建模板后必须重跑，否则 ApkPacker 无法定位图标/占位）。
4. 替换主项目模板：`Copy-Item build\outputs\apk\release\apk_shell-release-unsigned.apk ..\src\main\assets\apk_shell\base.apk -Force`。
5. 更新本清单（第三节）与 `HTML_DESIGN_RULES.md` 第四节、`ExportApkTool` 描述中的方法数。
6. 重新编译主项目（assembleDebug）→ 装机验证（可用 `apk_shell/samples/engine_demo/index.html`、`apk_shell/samples/live_tv/index.html` 或独立自测工程 `shell_cap_test/index.html` 全量跑一遍；后者含顶部「一键测试全部 28 项」按钮，一次覆盖版本/设备/状态栏/文件/剪贴板/TTS/截图/预缓存，弹窗与破坏性桥手动逐测；直播页覆盖频道列表→播放→切台→收藏→源管理）。

## 六、常见坑

- **HLS 302 + 相对分片**：源 `http://A/live/cctv2hd.m3u8` 302 到 `http://B:81/live/cctv2md.m3u8?token`，清单里的 `cctv2md/segment_x.ts` 是相对路径。media3 默认按**原始 A 主机**拼分片 → 404。v9.13 播放器会先跟随跳转拿到最终清单地址再 prepare，同时抓到 HTTP 错误会自动按原始地址重解析重试 2 次。若仍报错，先 `curl -L` 看分片是否 403/404/过期；外网源可能需要 UA/Referer（M3U 里用 `#EXTVLCOPT`）。
- **回调桥缺参** → 页面报 `Method not found`：确认 callbackName 非空且函数挂在 `window` 上。
- **方法数口径**：桥方法数以源码为准——v9.11 全量 **80 个方法名 / 81 个 @JavascriptInterface**（`speakText` 重载）；勿按旧文档"63 个"猜测。
- **shareFile 仅本地模式**可用（远程 url 模式无壳内文件）。
- **本地模式**禁止 `file://` 绝对路径、禁止目录外访问（壳有路径穿越防护；`saveFile/readFile/listFiles/deleteFile` 同理，仅限 htmlDir 内）。
- **应用名超 22 UTF-8 字节**（中文约 7 字）会导出报错（arsc 占位等长补丁限制）。
- **改壳后忘记跑 gen_meta** → ApkPacker 无法定位图标/占位，导出异常。
- **全屏只走壳桥**：壳无 `onShowCustomView`，页面 `requestFullscreen()` 无效；全屏用 `enterFullscreen(true)`，失焦后壳自动重应用。
