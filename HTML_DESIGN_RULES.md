# HTML 设计规则 —— 面向 SmartQuiz 壳 APK（v8.1 专业版）

> 适用对象：应用内 agent（`export_apk` 工具）与开发者在生成"要导出为 APK 的 HTML"时应遵守的约定。
> 目标：让内容用满壳的原生能力（**内置引擎库**/JS 桥/权限/分享/网络代理），避免踩 WebView 与安卓兼容坑。
> 规则分级：**【必须】** 违反会导致打不开或功能异常；**【建议】** 体验优化；**【禁止】** 会造成安全/兼容问题。
> 壳版本：**v8.1** = targetSdk 35 / **minSdk 26（不兼容 Android 8.0 以下旧机）**（Android 15 强制边缘到边）。
> **v8.1 变更**（v8 基础上 + targetSdk 35）：沉浸全屏（enterFullscreen/isFullscreen，失焦自动重应用）、屏幕方向（setOrientation/getOrientation）、系统亮度（setBrightness/getBrightness）、TTS 朗读（speakText/stopSpeak）、壳内文件（saveFile/readFile/listFiles/deleteFile，仅 htmlDir 内防穿越）、原生日期/时间选择器（pickDate/pickTime）、原生确认框（showDialog）、系统信息（getSystemInfo）、应用探测/打开（isAppInstalled/openApp/openInApp）；壳版本号编译进 dex（`getShellVersion()`=`"v8.1"`、`getBridgeApi()`=`5`）；剪贴板监听、通知栏、前台服务、深链、权限状态面板、JS 注入通道、离线缓存、渲染进程崩溃保护；内置库 highlight.js / DOMPurify。

---

## 〇、导出参数（agent 调用 `export_apk` 时）

### 独立包名（每个导出包可共存安装）
- **每个导出 APK 自动派生独立包名** `com.cjhtmldemo.p<12位sha1hex>`（按 app_name+apk_name 派生，同一参数重复导出仍是同一包名 → 走"升级覆盖"，不同参数 → 新包共存）
- 实现：壳模板占位包名 `com.cjhtmldemo.xxxxxxxxxxxxx`（28 字符）在 AXML 字符串池做**等长替换**（UTF-8/UTF-16 双编码），FileProvider authority、动态权限等派生串随前缀一并替换
- **多个导出包可同时安装、互不覆盖**（安装第二个不再静默覆盖第一个）
- 应用内直接安装：`adb install -r <apk>` 或文件管理器

### 自定义应用名与图标
- **应用名**：`export_apk` 参数 `app_name` —— **上限按 UTF-8 字节 ≤22（中文约 7 字）**，默认"背题"；超限报错会提示当前字符数/字节数与上限。实现为 resources.arsc 原位补丁
- **图标（三种方式，按优先级）**：
  1. `icon_path`：PNG **或 JPEG** 路径（JPEG 自动转 PNG；按 IHDR 校验尺寸，**<192×192 拒绝**，192-511 警告建议 512）
  2. `icon_emoji` + `icon_bg`：**用 emoji 自动生成图标**（无需图片文件；512×512 渐变圆角 + 居中 emoji，壳端 Canvas 生成）
  3. 自动识别 `html_dir/icon.png`，其次 `html_dir/app.json` 的 `icon` 字段
  都不传用壳自带图标
- **app.json 配置约定**（html_dir 根目录，可选）：`{"name":"应用名","icon":"icon.png"}` —— 参数优先级高于配置文件
- agent 生成 HTML 时如需自定义图标，把 PNG 写入 html_dir 根目录命名 `icon.png`（或直接用 icon_emoji 参数）

### HTML 来源参数（必须四选一、互斥）
- `url`（远程 http/https 地址）/ `html`（内容字符串）/ `html_file`（单文件路径）/ `html_dir`（含 index.html 的目录）
- **同时传多个来源会直接报错**（列出冲突参数），不再静默拼接
- `url` 模式**自动剔除内置库**（assets/libs/ 不打包，远程页面用不到，减少 ~2MB 体积）

---

## 一、入口与结构【必须】

| 规则 | 说明 |
|---|---|
| 入口必须是 `index.html` | 壳只认 manifest.json 的 `main` 字段（默认 index.html） |
| 所有资源用**相对路径** | `./css/style.css`、`js/app.js`、`images/logo.png`；**禁止** `file:///` 绝对路径 |
| 目录结构扁平合理 | 建议 `index.html + css/ + js/ + assets/`；子目录会被原样保留 |
| 单页优先（SPA） | 多页用 `location.hash` 或内部跳转，壳内导航可用返回键回退历史 |
| 页内跳转用普通 `<a href>` | 壳已接管返回键历史；`target="_blank"`/`window.open` 会路由回主视图 |

**完整目录示例：**
```
app/
├── index.html
├── css/style.css
├── js/app.js
└── assets/logo.png
```


## 一·B、多文件工程与模块化表盘（外置协议）【建议】

> 适用：体量较大的页面（如多表盘时钟/工具集）。把单文件拆成 `index.html + css/ + js/` 多文件工程，
> 用 `export_apk` 的 `html_dir` 参数导出（目录原样打包进 APK，相对路径全部保留）。
> 参考实现：极光时钟多文件工程（表盘全部外置，见手机工作区 `files/aurora_clock_v5_web/`）。

### 目录规范
```
app/                              # 导出时传该目录给 html_dir
├── index.html                    # 入口：<link> 引 css，<script> 按依赖顺序引 js
├── css/
│   ├── base.css                  # 主题变量 / 布局 / 顶栏 / 导航
│   ├── controls.css              # 通用控件
│   ├── app.css                   # 各功能页样式
│   └── faces/                    # 表盘样式（外置：每表盘一个文件）
│       ├── flip.css / nixie.css / digital.css / analog.css
│       └── ring.css / dot.css / neon.css / word.css
└── js/
    ├── core.js                   # 数据 / 通用工具 / 全局状态
    ├── audio.js / fx.js          # 音频引擎 / 背景动效
    ├── faces/
    │   ├── registry.js           # 表盘注册表 + FACE 调度器（外置协议核心）
    │   └── flip.js / nixie.js / …（每表盘一个模块文件）
    ├── alarm.js / timer.js / world.js / settings.js
    └── app.js                    # 主逻辑 + 初始化（最后加载）
```

### 表盘外置协议（新增 / 删除表盘零改动主程序）
- 注册：`ACFace(k, {n, i, build(host), paint(n, S), relayout?})`
  - `build(host)` 构建表盘 DOM；`paint(n, S)` 每秒渲染；`relayout()` 横竖屏回调（可选）
- 调度：`FACE.build(k) / FACE.paint(now) / FACE.relayout() / FACE.list()`
- **新增表盘** = 新建 `js/faces/xx.js`（调 `ACFace`）+ `css/faces/xx.css` + `index.html` 加两个引用；
  **删除表盘** = 删文件去引用即可
- 表盘清单由 `registry.js` 的 `FACES`（init 时 `refreshFaces()` 刷新）驱动，设置网格/顶部切换自动跟随

### 多文件注意事项【必须】
- `index.html` 的 `<script>` **严格按依赖顺序**：core → audio → fx → faces/registry → faces/* → 功能模块 → app（init 在最后）
- 全局变量跨文件共享（`var S`、`function B()` 等），模块间通过全局函数/注册表协作，避免在文件内重复 `var`
- 资源引用全部相对路径；HTML 中不要出现 `file:///` 绝对路径
- 打包验证：`html_dir` 模式下整个目录进 dt.jet，子目录（css/js/faces）原样保留

---
## 二、视口与适配【必须】

```html
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover, user-scalable=no">
<meta name="theme-color" content="#4338ca">   <!-- 状态栏配色（现代安卓有效） -->
<title>应用名</title>
```

- **刘海/安全区**：`viewport-fit=cover` 时用 `env(safe-area-inset-top/right/bottom/left)` 撑开固定定位元素
- **壳 v8.1（targetSdk 35）强制边缘到边【必须适配】**：Android 15 起系统强制，状态栏/导航栏透明，HTML 内容延伸到系统栏后面
  - 顶栏/底栏必须用安全区撑开，推荐 CSS 变量：`padding-top:calc(var(--sa-t,0px) + 10px)`、`padding-bottom:calc(var(--sa-b,0px) + 5px)`；**--sa-t/--sa-b 由桥高度计算（物理 px ÷DPR，上限 64px 防御），桥不可用才回退 env(safe-area)**（参照极光时钟 syncSafe）
  - 优先用壳桥高度（`AndroidApp.getStatusBarHeight()/getNavBarHeight()`，比 env() 更可靠），env() 兜底取大值
  - 深色页面状态栏图标用 `AndroidApp.setStatusBarStyle("light")`（浅色图标）；浅色页面用 `"dark"`
- 尺寸单位建议 `vw/vh/rem` + `flex/grid` 响应式；少用固定 px 做布局
- 深色模式：可用 `prefers-color-scheme`，壳不强制

---

## 三、资源与网络【必须/建议】

### 内置前端库（本地模式自动可用，无需网络）
壳本地模式启动时把内置库注入 `htmlDir/libs/`（HTML 里直接相对路径引用）：

```html
<!-- 常用库，开箱即用 -->
<link rel="stylesheet" href="libs/normalize.min.css">
<link rel="stylesheet" href="libs/animate.min.css">
<script src="libs/jquery.min.js"></script>
<script src="libs/vue.global.prod.js"></script>   <!-- Vue 3 全量构建，createApp 直接可用 -->
<script src="libs/axios.min.js"></script>
<script src="libs/dayjs.min.js"></script>
```

| 库 | 版本 | 说明 |
|---|---|---|
| jQuery | 3.7.1 | DOM/事件/Ajax |
| Vue 3 | 3.4.38 | 全量构建（含模板编译器） |
| Axios | 1.7.7 | HTTP；跨域受限优先用 `AndroidApp.request()` |
| Day.js | 1.11.13 | 日期 |
| **ECharts** | **5.5.0** | **专业数据可视化引擎（图表/统计/成绩曲线）** |
| **KaTeX** | **0.16.11** | **数学公式渲染引擎（背题场景必备）** |
| **Marked** | **12.0.1** | **Markdown 渲染引擎** |
| **Lodash** | **4.17.21** | **实用工具函数库** |
| **Highlight.js** | **11.9.0** | **代码高亮** |
| **DOMPurify** | **3.1.6** | **XSS 净化：`DOMPurify.sanitize(html)` 渲染不可信内容** |
| Animate.css | 4.1.1 | CSS 动画 |
| Normalize.css | 8.0.1 | 样式重置 |

### 渲染引擎库使用（ECharts / KaTeX / Marked）
```html
<!-- ① 图表（ECharts）：成绩统计、错题分布、趋势 -->
<script src="libs/echarts.min.js"></script>
<div id="chart" style="width:100%;height:240px"></div>
<script>
  var chart = echarts.init(document.getElementById("chart"));
  chart.setOption({ xAxis: {...}, yAxis: {...}, series: [{type:"bar", data:[...]}] });
</script>

<!-- ② 数学公式（KaTeX）：需同时引 css + js，字体走相对路径自动加载 -->
<link rel="stylesheet" href="libs/katex/katex.min.css">
<script src="libs/katex/katex.min.js"></script>
<div id="f"></div>
<script>katex.render("x = \\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}", document.getElementById("f"));</script>

<!-- ③ Markdown（Marked）：题库说明、学习笔记 -->
<script src="libs/marked.min.js"></script>
<div id="md"></div>
<script>document.getElementById("md").innerHTML = marked.parse("# 标题\n- 列表\n```js\ncode\n```");</script>
```
- KaTeX 字体在 `libs/katex/fonts/`（19 个 woff2），**相对路径自动加载，无需额外配置**
- ECharts 与 Vue 可组合使用：`echarts.init` 在 Vue `mounted` 钩子里调用
- 所有引擎库**完全离线可用**，不依赖任何 CDN

清单见 `libs/index.json`。

### 动态加载网络依赖（可行性）
- **可以**：WebView 支持 `<script src="https://cdn...">` 动态加载；壳已放开明文/混合内容兼容。
- **注意**：离线或无网时加载会失败且无法回退 → 关键库务必用内置 libs/；网络库只作渐进增强
  （如 `<script>` 动态插入 + onerror 回退到内置版本）。国内网络 CDN 不稳定，优先内置。

### 本地模式（默认）
- 页面经 `http://localhost:8099` 提供 → **相对路径 fetch/XHR/ES 模块全部可用**，无 CORS 问题
- 远程资源（CDN/API）可用，但**离线不可用** → 关键样式/脚本务必打包进目录

### 远程 API 调用（两选一）
1. **跨域受限时用原生网络代理（推荐）** —— 壳的 `window.AndroidApp.request()` 走原生 HttpURLConnection，
   **没有 CORS 限制**，可带自定义请求头（如 Authorization），适用于背题/答题类应用调自己的后端：

   ```js
   window.callbackApi = function (res) {
     console.log(res.status, res.body);   // res = {status: 200, body: "..."} 或 {status:0, error:"..."}
   };
   window.AndroidApp.request(
     "https://your-api.com/quiz/submit",
     "POST",
     JSON.stringify({ "Content-Type": "application/json", "Authorization": "Bearer xxx" }),
     JSON.stringify({ answer: "A" }),
     "callbackApi"
   );
   ```
2. **浏览器同源 fetch**：仅当目标 API 允许壳来源（`http://localhost:8099` 或远程模式域名）的 CORS 时使用

### 【禁止】
- 依赖 Service Worker/PWA 缓存（WebView 不支持）
- 页面加载时同步阻塞主线程 > 2s（会触发 ANR 提示感）
- 明文密码/密钥硬编码进 HTML（壳无法加密内容，懂行用户可解包）

---

## 四、原生能力桥 `window.AndroidApp`【能力清单】

壳 v8.1 注入 `window.AndroidApp`（JS 桥，**62 个方法**；完整源码清单见同目录 `APK_SOURCE_GUIDE.md` 第三节）。调用前做可用性检查：

```js
function bridge() { return window.AndroidApp || null; }
```

| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `showToast(msg)` | string | — | 底部提示 |
| `vibrate(ms)` | number | — | 振动（1~5000ms，需要硬件） |
| `haptic()` | — | — | 轻触反馈（20ms） |
| `exit()` | — | — | 退出应用 |
| `copyText(text)` | string | — | 复制到剪贴板 + 提示 |
| `shareText(text)` | string | — | 系统分享文本 |
| `shareFile(relPath)` | string | — | 分享壳内文件（相对 filesDir/html，如 `result/score.png`；仅本地模式） |
| `openBrowser(url)` | string | — | 用系统浏览器打开 |
| `getVersion()` | — | string(JSON) | `{shellVersion, bridgeApi, versionName, versionCode}` |
| `getDeviceInfo()` | — | string(JSON) | `{screenWidth, screenHeight, density, densityDpi, sdkInt, model, manufacturer, launchMode}` |
| `getNetworkType()` | — | string | `wifi` / `mobile` / `none` / `other` / `unknown` |
| `request(url, method, headersJson, body, callbackName)` | string×5 | — | **无 CORS 网络代理**；回调 `window[callbackName]({status, body})` |
| `checkPermission(name)` | string | string | 权限是否已授权（`"true"`/`"false"`） |
| `requestPermission(name, callbackName)` | string×2 | — | 请求运行时权限；回调 `window[callbackName]({permission, granted})` |
| `openAppSettings()` | — | — | 打开本应用系统设置页 |
| `getBatteryLevel()` | — | string | 电量百分比（0-100） |
| `isCharging()` | — | string | `"true"`/`"false"` |
| `getStorageInfo()` | — | string(JSON) | `{internalTotalMB, internalFreeMB, externalTotalMB, externalFreeMB}` |
| `setKeepScreenOn(keepOn)` | boolean | — | 屏幕常亮开关 |
| `openFilePicker(callbackName)` | string | — | 系统文件选择（SAF）；回调 `window[callbackName]({name,size,mimeType,dataBase64})`，≤2MB |
| `screenshot(callbackName)` | string | — | **当前页面截图**；回调 `window[callbackName]({dataBase64,width,height})`（PNG base64，可显示/保存/分享） |
| `readClipboard()` | — | string | 读取剪贴板文本（Android 13+ 系统会显示剪贴板提示条） |

### 权限管理（name 取值）

| name | 实际权限 | 说明 |
|---|---|---|
| `camera` | CAMERA | getUserMedia 也走系统授权 |
| `mic` / `microphone` | RECORD_AUDIO | |
| `storage` / `media` | 33+：READ_MEDIA_IMAGES/VIDEO/AUDIO；26-32：READ_EXTERNAL_STORAGE | 读媒体文件 |
| `notification` | 33+：POST_NOTIFICATIONS | 8.0-12 恒为已授权 |
| `location` | ACCESS_FINE_LOCATION | WebView 定位已自动授权，此权限供 HTML 自行判断/请求 |

**典型组合用法（背题场景）：**
```js
// 启动时检测网络
if (window.AndroidApp && window.AndroidApp.getNetworkType() === "none") {
  document.body.innerHTML = "<p style='text-align:center;margin-top:40%'>无网络，请在联网后使用</p>";
}
// 答题结束分享成绩
window.AndroidApp.shareText("我这次背题得分 90 分！");
// 导出成绩图片并分享
window.AndroidApp.shareFile("result/score.png");
// 权限：请求相机并接收结果
window.AndroidApp.requestPermission("camera", "cbPerm");
window.cbPerm = function (r) { console.log(r.permission, r.granted); };
// 文件导入：选一个 json 读回内容
window.AndroidApp.openFilePicker("cbFile");
window.cbFile = function (f) {
  if (f.dataBase64) {
    const text = decodeURIComponent(escape(atob(f.dataBase64))); // 或 Blob 方式
    console.log("文件内容:", text);
  }
};
// 成绩页截图：把当前统计页面变成图片
window.AndroidApp.screenshot("cbShot");
window.cbShot = function (res) {
  const img = document.createElement("img");
  img.src = "data:image/png;base64," + res.dataBase64;
  document.body.appendChild(img);   // 展示给用户，或进一步处理
};
// 一键粘贴：从剪贴板读题号/答案
const pasted = window.AndroidApp.readClipboard();
```

### v8.1 新增能力（版本探测 + 系统交互 + 注入 + 缓存 + 全屏/方向/亮度/TTS/文件/选择器/对话框/系统信息/应用 + 状态栏适配）

**① 版本探测（壳版本号已编译进 dex，无需依赖资源/manifest）**
```js
window.AndroidApp.getShellVersion();   // "v8"
window.AndroidApp.getBridgeApi();      // 4（桥 API 版本，能力探测用）
JSON.parse(window.AndroidApp.getVersion());  // {shellVersion:"v8", bridgeApi:4, versionName:"8.0", versionCode:8}
```

**② 剪贴板监听（前台可见时有效）**
```js
window.AndroidApp.startClipboardWatch("cbClip");
window.cbClip = function (r) { console.log("剪贴板变了:", r.text); };
// 不需要时：window.AndroidApp.stopClipboardWatch();
```

**③ 通知栏**
```js
// 33+ 需先授权：window.AndroidApp.requestPermission("notification", "cbPerm");
window.AndroidApp.showNotification("背题提醒", "今天的目标完成 80%！");
window.AndroidApp.cancelNotification(0);      // 取消某条
window.AndroidApp.cancelAllNotifications();   // 清空
```

**④ 前台服务（常驻通知栏，适合后台任务/计时）**
```js
window.AndroidApp.startForeground("后台学习中", "正在记录学习时长…");
// 结束：window.AndroidApp.stopForeground();
```

**⑤ 深链（外部用浏览器/其他应用打开壳的 http/https 链接）**
```js
const link = window.AndroidApp.getDeepLink();   // 最近一次外部 VIEW 的完整 URL（无则 ""）
// 热更新：页面监听 deeplink 事件（应用运行中被再次打开时触发）
window.addEventListener("deeplink", function (e) { console.log("深链:", e.detail); });
```

**⑥ 权限状态面板（原生对话框列出常用权限状态 + 一键跳系统设置）**
```js
window.AndroidApp.openPermissionPanel();
```

**⑦ JS 注入通道（页面加载完成后自动执行 / 即时注入）**
```js
window.AndroidApp.addScriptInjector("document.body.dataset.shell='v8'"); // 每次 onPageFinished 自动注入
window.AndroidApp.injectNow("console.log('injected now')");             // 立即执行
window.AndroidApp.clearScriptInjectors();
```

**⑧ 离线缓存（本地优先 / 仅离线 / 预缓存单 URL）**
```js
window.AndroidApp.setCacheMode(1);   // 0=默认(网络优先) 1=本地优先 2=仅离线
window.AndroidApp.getCacheMode();    // 读当前缓存模式
window.AndroidApp.precacheUrl("https://example.com/lesson1.json", "cbCache");
window.cbCache = function (r) { console.log("预缓存:", r.url, r.ok); };
window.AndroidApp.clearCache();      // 清空离线缓存
```

**⑨ 渲染进程崩溃保护**：页面渲染进程崩溃不会杀掉整个应用，壳自动回到错误重试页（API 26+ 的 `onRenderProcessGone`），无需 HTML 处理。

> **⚠️ 回调参数铁律（真机实测教训）**：凡签名里带 `callbackName` 的桥方法（`request` / `requestPermission` / `openFilePicker` / `screenshot` / `startClipboardWatch` / `precacheUrl`），**回调名参数必填**，必须原样传一个字符串。WebView 桥对参数**数量**严格匹配，缺参或错数不会报"参数错误"，而是直接抛 **`Method not found`**。反面示例（自测页曾犯，运行日志报 Method not found）：
>
> ```js
> // ❌ 错误：screenshot 少传回调名 → Error invoking screenshot: Method not found
> window.AndroidApp.screenshot();
> // ❌ 错误：requestPermission 只传 1 个参数（缺回调名）→ Method not found
> window.AndroidApp.requestPermission("camera");
> // ✅ 正确
> window.AndroidApp.screenshot("cbShot");
> window.AndroidApp.requestPermission("camera", "cbPerm");
> window.AndroidApp.startClipboardWatch("cbClip");
> ```
>
> 建议先传参、后定义回调函数（或先定义再传名均可，回调按名查找），并始终在调用前加 `typeof window.AndroidApp !== "undefined"` 守卫。

---

## 五、媒体播放【建议】

- 壳已开 `mediaPlaybackRequiresUserGesture=false`，**音频/视频可自动播放**
- 建议仍提供用户手势触发（首次播放时静音策略：`video.muted = true; video.play()` 兼容性最好）
- 音频格式用 mp3/aac；视频用 mp4(H.264)/webm；字体用 woff2

---

## 六、交互与系统按键【建议】

- **返回键**：壳内优先历史回退；无历史时双击退出。页面自身**不要**在 body 上监听返回（无效），
  需要拦截时用 `history.pushState` 增加壳内历史栈
- 全屏/沉浸：**走壳桥** `AndroidApp.enterFullscreen(true/false)`（实测壳无 `onShowCustomView`，`requestFullscreen()` 无效）；失焦后壳自动重新应用沉浸状态。页面初始化可 `isFullscreen()` 对齐状态；壳在切换时会向页面派发 `window.__shellFullscreen` + `shellfullscreenchange` 事件
- 顶部固定栏注意刘海安全区（见第二节）

---

## 七、性能【建议】

- 首屏：入口 HTML 尽量 < 50KB，关键 CSS/JS 内联或就近；图片懒加载
- 图片：使用 `loading="lazy"`；大图压缩（png→webp/avif）
- JS：避免引入重型框架的完整包（可 tree-shaking）；本地模式无 CDN 回退风险更低
- 总包体建议 < 10MB（APK 体积 ≈ 壳 1.5MB + 内容压缩后）

---

## 八、安全【必须/禁止】

- 【禁止】页面内 `eval`/`new Function` 执行不可信远程字符串（内容会被审计）
- 【禁止】把 `AndroidApp.request` 的 URL 拼上用户裸输入直接请求 —— 需自行校验协议（仅 http/https）
- 【建议】远程页面模式（url 模式）下不要依赖本地桥的文件类能力（shareFile 仅本地模式可用）
- 【提示】壳对本地模式做了路径穿越防护；请勿在 HTML 中尝试访问目录外文件

---

## 九、可直接复用的入口模板

> **能力自测台（推荐先跑一遍）**：`apk_shell/samples/engine_demo/index.html` 已升级为 **壳能力自测台**（chatkit 风格）——自动枚举全部桥方法、权限状态灯、v8.1 新能力一键实测（全屏/方向/亮度/TTS/壳内文件/选择器/对话框/系统信息/应用 + 通知/前台服务/剪贴板监听/深链/注入/缓存/截图）、实时调试日志流。生成完 HTML 应用后，把它作为"能力冒烟页"参考，或直接复制其 UI 风格（顶栏状态胶囊 / 版本信息条 / 能力列表 / 权限 chip / 分组实测按钮 / 日志控制台）到自己的应用首页。

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover, user-scalable=no">
<meta name="theme-color" content="#4338ca">
<title>我的应用</title>
<style>
  html, body { margin:0; height:100%; background:#f5f7ff; }
  .app { min-height:100vh; padding: 16px; padding-top: calc(16px + env(safe-area-inset-top)); box-sizing:border-box; }
  .btn { display:inline-block; padding:10px 24px; background:#4338ca; color:#fff; border-radius:8px; border:none; font-size:15px; }
</style>
</head>
<body>
<div class="app">
  <h1>你好，壳</h1>
  <button class="btn" onclick="window.AndroidApp && window.AndroidApp.shareText('来自壳 APK 的分享')">分享</button>
</div>
<script>
  // 桥可用性检查
  if (window.AndroidApp) {
    var info = JSON.parse(window.AndroidApp.getDeviceInfo() || "{}");
    console.log("屏幕:", info.screenWidth + "x" + info.screenHeight, "模式:", info.launchMode);
  }
</script>
</body>
</html>
```

---

## 十、导出前自检清单（agent 生成 HTML 后逐项核对）

- [ ] 入口为 `index.html`，资源全部相对路径
- [ ] viewport 含 `viewport-fit=cover`；固定定位处理了安全区
- [ ] 远程 API 用 `window.AndroidApp.request()` 或已确认 CORS 可用
- [ ] 需要用户交互的原生能力（相机/录音）已引导（壳会在首次请求时弹系统授权）
- [ ] 首屏无阻塞，总包体合理
- [ ] 无明文密钥、无 `file://` 路径、无 Service Worker
