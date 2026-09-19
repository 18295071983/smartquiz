# html2apk —— HTML 一键打包 APK 工具

把任意 HTML/CSS/JS 目录打包成**可直接安装的签名 APK**。壳为 **63 桥 WebView 壳**（与 `apk_shell` 同源）：除基础渲染外，页面内可直接调用 `window.AndroidApp.*` 原生能力（TTS 语音播报、方向锁定、屏幕亮度、权限管理、SAF 文件读写、截图、剪贴板、网络代理等）。Agent 生成 HTML 后，用一条命令即可导出 APK。

## 特性

- **任意 HTML 入包**：本地 `index.html`（含全部子目录资源）打包为 `dt.jet`（ZIP→AES-128-CBC，与主应用 ApkPacker 同一格式），壳内解密后由本地 HTTP 服务加载
- **63 桥原生能力**：`window.AndroidApp` 提供 TTS（speakText/ttsState/stopSpeak）、方向（setOrientation）、亮度（setBrightness）、权限管理（requestPermission/checkPermission/openAppSettings）、SAF 文件（openFilePicker/saveFile/readFile/listFiles）、截图（screenshot）、剪贴板（readClipboard）、状态栏（setStatusBarStyle/getStatusBarHeight）、设备信息、电池/存储、通知/前台服务、无 CORS 网络代理（request）、全屏、脚本注入、缓存控制等 63 个接口
- **远程 URL 模式**：也可直接加载一个 http/https 地址（manifest.json 的 url 字段），不打包本地 HTML
- **中文应用名**：应用显示名、包名、版本号全部可配（UTF-8 安全）
- **自动签名**：首次运行自动生成 release keystore，后续复用；也支持传入已有 keystore
- **增量构建快**：复用本机 Gradle 缓存，第二次构建秒级完成
- **自动校验**：构建后用 `aapt dump badging` 校验 APK 元信息
- **自定义图标**：`-Icon` 传一张 512×512 PNG 即可

## 环境要求（本机已满足）

| 组件 | 位置 |
|---|---|
| JDK 21 | `D:\jdk-21`（`JAVA_HOME`） |
| Android SDK | `D:\Android\Sdk`（`local.properties` 自动读取） |
| Gradle 8.13 + AGP 8.4.0 | 缓存于 `D:\Gradle\Home`，首次构建联网补齐缺失依赖 |

## 快速开始

```powershell
# 本地 HTML -> APK
.\export_apk.ps1 -HtmlDir .\samples\demo -AppName "我的应用" -Package com.demo.myapp

# 指定图标与版本
.\export_apk.ps1 -HtmlDir .\samples\demo -AppName "Demo" -Icon .\icon.png `
    -Package com.demo.myapp -VersionName 2.0.0 -VersionCode 2

# 远程 URL 模式（不打包本地 HTML）
.\export_apk.ps1 -RemoteUrl "https://example.com" -AppName "WebApp" -Package com.web.app
```

输出：`out\<应用名(ASCII化)>-<版本>.apk`，安装：`adb install -r out\HTML-1.0.0.apk`

## 参数一览

| 参数 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `-HtmlDir` | 与 RemoteUrl 二选一 | — | 含 `index.html` 的目录 |
| `-RemoteUrl` | 与 HtmlDir 二选一 | — | 加载的远程 URL（http/https） |
| `-AppName` | 否 | `HTML App` | 应用显示名（支持中文） |
| `-Package` | 否 | `com.html2apk.wrapper` | 应用包名（applicationId） |
| `-VersionName` | 否 | `1.0.0` | 版本名 |
| `-VersionCode` | 否 | `1` | 版本号（整数） |
| `-Icon` | 否 | 内置默认图标 | 自定义图标 PNG（512×512 推荐） |
| `-OutDir` | 否 | `<工具目录>\out` | APK 输出目录 |
| `-Keystore` | 否 | 自动生成 | 复用已有 keystore |
| `-KeystorePass/-KeyAlias/-KeyPass` | 否 | `html2apk123`/`html2apk`/`html2apk123` | 签名参数 |
| `-DebugBuild` | 否 | 否 | 构建 debug 包（不签名） |
| `-SkipBuild` | 否 | 否 | 只同步资源不构建（调试用） |

## 工作原理

```
Agent 生成 HTML 目录
        │
        ▼
export_apk.ps1
 ├─ 校验 index.html / RemoteUrl
 ├─ HTML 目录 → ZIP → AES-128-CBC → assets/dt.jet（与 ApkPacker 同格式）
 ├─ 写入 assets/manifest.json（main 入口 + 可选 url 远程）
 ├─ (可选) 覆盖 app_icon.png
 ├─ 生成/复用 release keystore
 ├─ 写入 build_config.properties（UTF-8）
 ├─ gradlew assembleRelease（离线优先，缺依赖自动联网）
 └─ aapt 校验 + 复制到 out/
        │
        ▼
签名 APK（63 桥 WebView 壳 + 你的 HTML）
```

壳运行时规则（`MainActivity.java`，与 apk_shell 同源）：
1. 读取 `assets/manifest.json`：取 `main` 入口与可选 `url`（远程加载）
2. 有 `url` → 直接加载远程地址（需联网）；否则解密 `assets/dt.jet` → 解出 HTML 目录 → 本地 HTTP 服务加载
3. 已启用：JavaScript、DOM Storage、本地文件访问、媒体自动播放、返回键回退
4. `window.AndroidApp` 注入 63 个原生桥接口（TTS/方向/亮度/权限/SAF/截图/剪贴板/网络代理等）

## 目录结构

```
html2apk/
├── export_apk.ps1            # 导出工具（主入口）
├── build.gradle / settings.gradle / gradle.properties / local.properties
├── gradlew.bat / gradle/wrapper/   # Gradle 包装器（复用本机缓存）
├── keystore/                 # 自动生成的签名密钥
├── app/
│   ├── build.gradle          # 壳工程配置（参数经 build_config.properties 注入）
│   └── src/main/
│       ├── AndroidManifest.xml          # 权限 + FileProvider + 前台服务
│       ├── java/com/html2apk/wrapper/
│       │   ├── MainActivity.java        # 63 桥壳（TTS/方向/亮度/权限/SAF/截图/剪贴板…）
│       │   ├── HtmlHttpServer.java      # 本地 HTTP 服务（加载解密后的 HTML 目录）
│       │   └── ForegroundBridgeService.java
│       ├── res/drawable-nodpi/app_icon.png
│       └── assets/           # dt.jet + manifest.json（脚本自动生成，勿手工编辑）
├── tools/gen_dt_jet.py       # HTML 目录 → ZIP → AES(dt.jet)
├── samples/demo/             # 示例 HTML 应用
└── out/                      # APK 输出目录
```

## 注意事项

- **首次构建需联网**：离线缓存缺 `transform-api`、`javapoet` 等小依赖，脚本会自动转联网下载一次，之后均走缓存
- **APK 文件名为 ASCII**：应用显示名可中文，但输出文件名会 ASCII 化（aapt/adb 对非 ASCII 路径兼容性差）
- **图标持久化**：传过 `-Icon` 后图标会保留，下次不传则沿用；想还原默认图标需手动恢复 `res/drawable-nodpi/app_icon.png`
- **minSdk 26 / targetSdk 35**：覆盖 Android 8.0+ 设备（与主应用 apk_shell 一致）
- **签名密钥请妥善保管**：`keystore/html2apk-release.keystore`（默认口令 `html2apk123`），后续升级必须用同一密钥
