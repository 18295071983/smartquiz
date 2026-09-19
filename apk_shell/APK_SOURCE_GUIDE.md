# APK 壳源码指导（APK Source Guide）

> 面向对象：需要阅读/扩展 APK 导出壳源码的开发者与 Agent（`export_apk` 工具）。
> 本文件是**壳源码本身的指导**（工程结构、桥方法源码清单、扩展流程）；
> HTML 内容生成规则见同目录 `HTML_DESIGN_RULES.md`。
> 当前壳：**v8.1**（shell_version=v8.1，bridge_api=5，targetSdk 35 / minSdk 26，Android 15 强制边缘到边）。

---

## 一、源码工程结构

```
apk_shell/                                  # 壳源码工程（独立 Gradle 模块）
├── build.gradle                            # applicationId 占位 com.cjhtmldemo.xxxxxxxxxxxxx（18 字符，3+15 个 x）；versionCode 9 / versionName 8.1
├── src/main/
│   ├── AndroidManifest.xml                 # 壳清单（权限、FileProvider、MainActivity、ForegroundBridgeService）
│   ├── java/com/cjhtmldemo/apk/
│   │   ├── MainActivity.java               # ★ 核心：WebView 壳 + 全部 JS 桥（AppBridge，63 个 @JavascriptInterface）
│   │   ├── HtmlHttpServer.java             # 本地 HTTP 服务（localhost 候选端口加载 html，防跨目录）
│   │   └── ForegroundBridgeService.java    # 前台服务（桥 startForeground/stopForeground 的实现载体）
│   └── res/                                # 图标（res/RJ.png 等）、strings（应用名占位 SmartQuizExportAppName）
├── tools/gen_meta.py                       # 生成 assets/apk_shell_meta.json（重建模板后必须重跑）
├── samples/engine_demo/index.html          # 壳能力自测台（枚举全部桥方法/权限灯/v8 新能力实测）
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
  manifest.json 读取 → 本地模式：解密 dt.jet → ZIP 解压 filesDir/html
  → 注入内置库 assets/libs → 本地 HTTP 服务 → http://localhost:{port}/index.html
  远程模式：manifest.json 带 "url" → 直接加载远程地址（不注入 libs）
```


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

```## 三、JS 桥完整清单（63 个，源码为准）

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
| `getShellVersion()` | — | string | 壳版本，如 `"v8.1"`（编译进 dex） |
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

> 说明：上表为 v8 及以下（历史）能力；当前 v8.1 全量 **63 个方法**（以本清单为权威）；`HTML_DESIGN_RULES.md` 第四节为常用能力速查。

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
2. 构建：`cd apk_shell && .\gradlew.bat assembleRelease`（首次联网拉 androidx.core，之后可 `--offline`）。
3. 生成元数据：`python tools\gen_meta.py build\outputs\apk\release\apk_shell-release-unsigned.apk`
   （每次重建模板后必须重跑，否则 ApkPacker 无法定位图标/占位）。
4. 替换主项目模板：`Copy-Item build\outputs\apk\release\apk_shell-release-unsigned.apk ..\src\main\assets\apk_shell\base.apk -Force`。
5. 更新本清单（第三节）与 `HTML_DESIGN_RULES.md` 第四节、`ExportApkTool` 描述中的方法数。
6. 重新编译主项目（assembleDebug）→ 装机验证（可用 `apk_shell/samples/engine_demo/index.html` 或独立自测工程 `shell_cap_test/index.html` 全量跑一遍；后者含顶部「一键测试全部 28 项」按钮，一次覆盖版本/设备/状态栏/文件/剪贴板/TTS/截图/预缓存，弹窗与破坏性桥手动逐测）。

## 六、常见坑

- **回调桥缺参** → 页面报 `Method not found`：确认 callbackName 非空且函数挂在 `window` 上。
- **方法数口径**：桥方法数以本清单 **63 个**为准（v8.1 全量，源码提取）；勿按旧文档"40 个/59 个"猜测。
- **shareFile 仅本地模式**可用（远程 url 模式无壳内文件）。
- **本地模式**禁止 `file://` 绝对路径、禁止目录外访问（壳有路径穿越防护；`saveFile/readFile/listFiles/deleteFile` 同理，仅限 htmlDir 内）。
- **应用名超 22 UTF-8 字节**（中文约 7 字）会导出报错（arsc 占位等长补丁限制）。
- **改壳后忘记跑 gen_meta** → ApkPacker 无法定位图标/占位，导出异常。
- **全屏只走壳桥**：壳无 `onShowCustomView`，页面 `requestFullscreen()` 无效；全屏用 `enterFullscreen(true)`，失焦后壳自动重应用。
