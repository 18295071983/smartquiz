# APK 壳源码指导（APK Source Guide）

> 面向对象：需要阅读/扩展 APK 导出壳源码的开发者与 Agent（`export_apk` 工具）。
> 本文件是**壳源码本身的指导**（工程结构、桥方法源码清单、扩展流程）；
> HTML 内容生成规则见同目录 `HTML_DESIGN_RULES.md`。
> 当前壳：**v7**（shell_version=v7，bridge_api=3，targetSdk 34 / minSdk 26）。

---

## 一、源码工程结构

```
apk_shell/                                  # 壳源码工程（独立 Gradle 模块）
├── build.gradle                            # applicationId 占位 com.cjhtmldemo.xxxxxxxxxxxxx（18 字符，3+15 个 x）
├── src/main/
│   ├── AndroidManifest.xml                 # 壳清单（权限、FileProvider、MainActivity、ForegroundBridgeService）
│   ├── java/com/cjhtmldemo/apk/
│   │   ├── MainActivity.java               # ★ 核心：WebView 壳 + 全部 JS 桥（AppBridge，40 个 @JavascriptInterface）
│   │   ├── HtmlHttpServer.java             # 本地 HTTP 服务（localhost 候选端口加载 html，防跨目录）
│   │   └── ForegroundBridgeService.java    # 前台服务（桥 startForeground/stopForeground 的实现载体）
│   └── res/                                # 图标（res/RJ.png 等）、strings（应用名占位 SmartQuizExportAppName）
├── tools/gen_meta.py                       # 生成 assets/apk_shell_meta.json（重建模板后必须重跑）
├── samples/engine_demo/index.html          # 壳能力自测台（枚举全部桥方法/权限灯/v7 新能力实测）
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
      ├─ resources.arsc 原位改应用名（SmartQuizExportAppName → app_name，UTF-8 字节 ≤22）
      ├─ res 图标替换（按 apk_shell_meta.json 的 icon_entry）
      ├─ AXML 字符串池等长替换包名（com.cjhtmldemo.xxxxxxxxxxxxx → com.cjhtmldemo.p<12位sha1hex>）
      └─ export.keystore 重签名
  → 输出 APK（工作区 apk_export/）+ 返回 路径/包名/SHA-256

壳运行时（MainActivity.onCreate → bootstrap）：
  manifest.json 读取 → 本地模式：解密 dt.jet → ZIP 解压 filesDir/html
  → 注入内置库 assets/libs → 本地 HTTP 服务 → http://localhost:{port}/index.html
  远程模式：manifest.json 带 "url" → 直接加载远程地址（不注入 libs）
```

## 三、JS 桥完整清单（40 个，源码为准）

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
| `getShellVersion()` | — | string | 壳版本，如 `"v7"`（编译进 dex） |
| `getBridgeApi()` | — | number | 桥 API 版本（当前 3） |
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

> 说明：上表共 40 个方法（v7 全量，以本清单为权威）；`HTML_DESIGN_RULES.md` 第四节为常用能力速查。

## 四、回调桥契约（callbackName 必填）

带回调的方法，**回调函数名参数必填**，缺参页面会报 `Method not found`：

| 方法 | 回调签名 |
|---|---|
| `request` | `window[cb]({status, body})`；网络错误 status=0 且带 error |
| `requestPermission` | `window[cb]({permission, granted})` |
| `openFilePicker` | `window[cb]({name,size,mimeType,dataBase64})` |
| `screenshot` | `window[cb]({dataBase64,width,height})` |
| `startClipboardWatch` | `window[cb]({text})` |
| `precacheUrl` | `window[cb]({url, ok})` |

回调实现需挂在 `window` 上（壳按名查找）：

```js
window.cbPerm = function (r) { console.log(r.permission, r.granted); };
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
6. 重新编译主项目（assembleDebug）→ 装机验证（可用 `samples/engine_demo/index.html` 自测台全量跑一遍）。

## 六、常见坑

- **回调桥缺参** → 页面报 `Method not found`：确认 callbackName 非空且函数挂在 `window` 上。
- **方法数口径**：桥方法数以本清单 **40 个**为准（v7 全量，源码提取）；勿按旧文档"22 个"猜测。
- **shareFile 仅本地模式**可用（远程 url 模式无壳内文件）。
- **本地模式**禁止 `file://` 绝对路径、禁止目录外访问（壳有路径穿越防护）。
- **应用名超 22 UTF-8 字节**（中文约 7 字）会导出报错（arsc 占位等长补丁限制）。
- **改壳后忘记跑 gen_meta** → ApkPacker 无法定位图标/占位，导出异常。
