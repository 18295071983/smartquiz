# apk_shell —— SmartQuiz 设备端 APK 导出壳（v6 专业版）

把 PC 端 html2apk 的现代 WebView 壳适配为安卓项目内置壳模板的**源码工程**。
构建产物替换 `src/main/assets/apk_shell/base.apk`，增强原有 ApkPacker 打包底座。

> **HTML 设计规则见 `apk_shell/HTML_DESIGN_RULES.md`**（agent 生成 HTML 的规范，最大化壳能力）。

## 能力升级（相对旧壳）
| 项 | 旧壳 | v2 | v3 | v4 | v5 | **v6 专业版** |
|---|---|---|---|---|---|---|
| minSdk/targetSdk | 21/31 | 21/34 | 21/34 | 26/34 | 26/34 | **26/34**（不兼容 8.0 以下旧机） |
| WebView 配置 | 基础 | +媒体/混合内容 | +上传/权限/下载/错误页 | 同左 | 同左 | 同左 |
| 加载模式 | 仅本地 | 本地+远程 URL | 同左 | 同左 | 同左 | 同左 |
| **JS 原生桥** | 无 | 无 | toast/分享/request() 等 | +权限桥+电池/存储/常亮/SAF | + screenshot/readClipboard | 同左 |
| **内置前端库** | 无 | 无 | 无 | 6 库 | + ECharts/KaTeX/Marked/Lodash（31 文件） | 同左；**url 模式自动剔除 libs（省 ~2MB）** |
| **自定义应用名/图标** | 固定"背题" | 固定 | 固定 | agent 可指定 | + emoji 图标 + app.json | **应用名按 UTF-8 字节 ≤22（中文约 7 字）计，超限报错带字节数；图标 <192×192 拒绝、JPEG 自动转 PNG** |
| **独立包名（可共存）** | 固定 | 固定 | 固定 | 固定 | 固定 | **每包派生 com.cjhtmldemo.p<slug>（AXML 等长替换，UTF-8/16 双编码），多包可同时安装互不覆盖** |
| HTML 来源参数 | — | — | — | 四选一 | 四选一 | **互斥强校验（多传报错列出冲突参数）** |
| arsc 对齐 | — | — | — | — | 依赖 zipalign 后处理 | **resources.arsc 置 ZIP 首位，天然 4 字节对齐（Android 11+ 安装校验根治）** |
| 本地服务 | HttpWebServerService | HtmlHttpServer(防护/候选端口) | 同左 | 同左 | 同左 | 同左 |

## 加载契约（与 ApkPacker 对称）
- `assets/manifest.json`：
  - `{"main":"index.html","targver":1}` → 本地模式：解密 `assets/dt.jet`（AES-128-CBC，key=`MyHtmlEditorKey1`，IV 在文件头 16 字节）→ ZIP 解压到 `filesDir/html` → 注入内置库到 `htmlDir/libs/` → 本地 HTTP 服务 → `http://localhost:{port}/{main}`
  - 追加 `"url":"https://..."` → 远程模式：直接加载该地址（不读取 dt.jet 内容）
- 本地模式支持相对路径资源、fetch/XHR、ES 模块（经 HTTP 服务加载）。

## 自定义应用名/图标/包名（资源补丁）
- 模板 `strings.xml` 的应用名是**占位串**（`SmartQuizExportAppName`，22 单元），ApkPacker 导出时在
  resources.arsc 字符串池中**原位改写**为 agent 指定名（**UTF-8 字节 ≤22，中文约 7 字**，默认"背题"）；无需 aapt2。
  超限报错文案带"当前字符数/字节数/上限"。
- 图标：APK 内 `res/*.png` 中的启动图标条目（记录于 `assets/apk_shell_meta.json`）被替换为自定义 PNG。
  **PNG 按 IHDR 校验尺寸（<192×192 拒绝，192-511 警告）；JPEG 自动转 PNG；其他格式报错**。
- 导出工具三种图标来源：`icon_path`（PNG/JPEG）> `icon_emoji`+`icon_bg`（壳端 Canvas 生成 512×512 渐变圆角+emoji）> `html_dir/icon.png` / `app.json` 的 icon。
- **独立包名**：模板 `applicationId` 为占位 `com.cjhtmldemo.xxxxxxxxxxxxx`（28 字符，13 个 x），
  ApkPacker 导出时按 app_name+apk_name 派生 slug（`p`+12 位 sha1hex，与 x 段等长），在 AXML 字符串池做
  **UTF-8/UTF-16 双编码全文件等长替换**——package 属性、FileProvider authority、动态权限等派生串随前缀一并替换。
  同一参数重复导出 → 同包名（升级覆盖）；不同参数 → 新包名（**可共存安装**）。
- 元数据由 `tools/gen_meta.py` 生成（**重建模板后必须重跑**，否则 ApkPacker 无法定位图标/占位；
  包名占位从 build.gradle 的 applicationId 自动读取，保持单点一致）。

## 依赖
- `androidx.core:core:1.13.0`（FileProvider，分享壳内文件用）。首次构建需联网补齐传递依赖
  （已按 2026-09 拉入本机 `D:/Gradle/Home` 缓存，之后可 `--offline`）。
- 产物体积 ~1.7MB（含 androidx.core、基线 profile、6 个内置前端库；相比旧壳 49KB 是"专业能力"的代价）。

## 构建
```powershell
# 环境：JAVA_HOME=D:\jdk-21，AGP 8.4.0 缓存于 D:/Gradle/Home（gradle.properties 已配置）
.\gradlew.bat assembleRelease          # 首次联网补齐 androidx 依赖
.\gradlew.bat assembleRelease --offline  # 之后离线秒级
# 产物：build\outputs\apk\release\apk_shell-release-unsigned.apk
#   （无需签名：ApkPacker 导出时用 export.keystore 重新签名；本模板未带 META-INF 签名）

# 生成/刷新模板元数据（每次重建模板后必做）：
python tools\gen_meta.py build\outputs\apk\release\apk_shell-release-unsigned.apk
```

## 替换进主项目
```powershell
Copy-Item "build\outputs\apk\release\apk_shell-release-unsigned.apk" "..\src\main\assets\apk_shell\base.apk" -Force
```
替换后重跑主应用编译（`gradlew compileDebugJavaWithJavac --offline`）。
旧壳备份：`test_files\apk_export_verify\base_legacy.apk`（v1）、`base_v2.apk`（v2）、`base_v3.apk`（v3）。

## 重新生成占位 dt.jet
```powershell
python tools\gen_dt_jet.py                # 默认生成 src/main/assets/dt.jet（占位 index.html）
python tools\gen_dt_jet.py <html目录> <输出路径>
```

## 机制验证
- `test_files\apk_export_verify\verify_v2_new_shell.py`：复刻 ApkPacker 链路（注入+回读+URL 模式）
- `test_files\apk_export_verify\patch_verify.py <template.apk> <应用名> [icon.png]`：**应用名补丁+图标替换**端到端验证
  （aapt 实测：`application-label:'我的背题助手'` 成功）
- 签名用 `D:\Android\Sdk\build-tools\34.0.0\apksigner.bat`（密钥 `src\main\assets\apk_shell\export.keystore`，
  alias=smartquiz，口令 password）
- **共存验证（v6）**：两个不同 app_name 导出 → aapt 各自 package 不同、label 正确 → `zipalign -c -p 4` 通过
  （arsc 置首位天然对齐）→ 真机先装 A 再装 B，`pm list packages` 两包共存、均可启动（PID 存活）
- 演示产物
  - `demo_v4_libs_bridge.apk`（内置 Vue + 原生桥，label=背题）
  - `custom_label_icon_demo.apk`（自定义名+图标）
  - `demo_v5_engines.apk`（**引擎演示：ECharts 成绩图表 + KaTeX 公式 + Marked Markdown + 截图桥**，label=背题助手，📚 emoji 图标，2.46MB）
  - `coexist_a.apk` / `coexist_b.apk`（v6 共存演示：独立包名同时安装）
