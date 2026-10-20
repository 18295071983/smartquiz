# 抖音下载器 UIFID 修复 + 和风天气引导链路修复（2026-10-21）

> 本文记录 2026-10-21 凌晨两轮修复的结论与验证方式，供后续排查同类似问题时直接对照。
> 涉及模块：`douyin_downloader`（内置工具）、`ai_weather`（和风天气）、AI 对话快捷工具面板。

---

## 一、抖音下载器：官方内核 403 `Uifid Not Found`

### 症状

- `douyin_downloader(source=official)` 返回 `NEED_COOKIE`；`source=auto` 回退第三方源后报「源限流」，真实原因被掩盖。
- 直接请求 `aweme/v1/web/aweme/detail` 得到：

  ```
  HTTP 403  Blocked by ArgusSecurityPlugin Uifid Not Found
  ```

### 实测证据（真机 25113PN0EC / Android 17）

| 检查项 | 结果 |
|---|---|
| 详情接口 | 403，body 46 字节，`Uifid Not Found` |
| `files/dy_webview_cookie.txt` | 330 B，只有 `ttwid` / `MONITOR_WEB_ID` / `x-web-secsdk-uid`，**无 UIFID** |
| WebView `CookieManager.getCookie("https://www.douyin.com")` | 700 字符，**无 UIFID** |
| 签名链耗时 | `a_bogus` 0.01s、`websign` 0.00s → 签名不是瓶颈 |
| 网络 | ping 29ms，排除网络问题 |

### 根因

官方 detail 接口要求服务端下发的 `UIFID`（100+ 位 hex，只能由真实浏览器 secsdk/webmssdk 握手后 Set-Cookie）。
原抓取 WebView 用的是**移动端 UA**，抖音返回「下载APP」引导页或验证码中间页，握手根本不执行；
且抓取时机是固定 `sleep 4s`、结果只写 SharedPreferences，没有落盘校验 —— 抓不到也不会报错。

### 修复（`DouyinDownloaderTool.java`）

1. 抓取 WebView 改用**专用桌面 UA**（Windows Chrome 143），不动全局 `WebViewDefaults.CHROME_USER_AGENT`（那是全 App 网页 UA，改桌面会破坏登录页/移动站点布局）。
2. 补 `CookieManager.setAcceptThirdPartyCookies(wv, true)` —— 抖音 secsdk 在子域/iframe 里握手，不放开第三方 Cookie 会把下发值直接丢掉；抓到后 `flush()`。
3. 固定 `sleep 4s` 改为**每秒轮询** `CookieManager`，最长 14s；`onPageFinished` 后开始轮询，另加 6s 兜底（个别机型不回调），总等待 22s。
4. 抓到合格 UIFID（`[0-9a-fA-F]{100,}`）后，**合并**写入 `files/dy_webview_cookie.txt`（先备份 `.bak`，保留 m 域的 `UIFID_TEMP`）。
5. 两条通道都失败时把 `NEED_COOKIE` 单独带出，提示「在应用内登录抖音一次」，不再被第三方源的「源限流」掩盖。

### 验证结果

- cookie 文件 2067 B，`UIFID` 384 位；
- 官方内核第 1 次即成功，接口 200、73 KB 正文；
- 四件套（视频 / BGM / 封面 / 文案）落盘，视频 2.80 MB、1080p、无水印。

---

## 二、和风天气：快捷「查天气」报参数冲突

### 契约变更

`ai_weather` 更新后要求 **`city` 与 `lat/lon` 二选一**，同时传入会直接返回「参数冲突」。
但快捷查天气走的是本地引导链路（不经大模型），有四处仍在无条件补位置参数：

| 位置 | 旧行为 | 后果 |
|---|---|---|
| `ToolPreChecker` | 对 `ai_weather` 无条件要求 city + lat + lon | 选城市后预检又补 GPS → 冲突 |
| 引导「自动定位」回调 | city、lat、lon 一起写入执行参数 | 冲突 |
| `injectAutoContext` | 城市名与坐标一起注入 | 回退改选后冲突 |
| `CompositeGuideFlow`（出行准备第 2 步） | 同时引用 `$prev.city` / `$prev.lat` / `$prev.lon` | 冲突 |

### 修复

1. `ToolPreChecker`：有城市名就不补坐标；没城市且坐标不成对才补 `lat`+`lon`（不补 city）。
   单测输出：`city-only → []`、`无位置 → [lat, lon]`、`坐标 → []`。
2. 引导自动定位回调：只写 `lat`/`lon`，城市名仅用于提示条展示。
3. `injectAutoContext`：位置参数互斥（有城市不注入坐标，有坐标不注入城市）。
4. `ToolExecutionOrchestrator.autoFillMissingParams` / `AIChatActivity.autoFillMissingParams`：增加 `toolName` 入参，天气工具走同一套规则。
5. 确认页摘要改为可读文案：「查询：实时天气 / 城市：银川」「位置：银川（自动定位 106.23, 38.49）」。

### 验证结果

| 路径 | 结果 |
|---|---|
| 查天气 → 现在天气 → 银川 → 执行 | 已完成 · 496ms，真实数据：晴、-0.8℃、体感 -3.8℃、湿度 76%、风 2 级、气压 1034.7hPa |
| 查天气 → 现在天气 → 自动定位 → 执行 | 已完成 · 119ms，确认页无重复位置 |

---

## 三、快捷工具面板：聚合工具包是死入口

- 布局里的 `chip_calc`（文案「🧰 聚合工具包」）只有 XML 声明，`QuickToolChipModule.bindStaticToolChips()` 拿到后从未绑定点击事件。
- 修复：新增 `onToolPackList` 回调 + `AIChatActivity.showToolPackPicker()`，弹出全部聚合方案（出行准备 4 步 / 学习查询 1 步 / 网页研究 2 步），点选进入对应引导。
- 顺带修掉 `CompositeGuideFlow` 出行准备第 2 步的 city+坐标冲突。

---

## 四、会话数据防护

- 工具中心的「🗑️ 清空对话」chip 原来**没有二次确认**，一点就 `clearAllConversationSessions()` 删除全部 `conversations/*.json`。
  现改为与历史抽屉一致的确认框（`AIChatActivity.confirmClearChat()`）。
- `ChatHistoryManager.clearAllConversationSessions()` 改为**软删除**：移动到 `files/conversations_trash/`（带清空时间戳，保留最近 40 份），不再直接 `delete()`。

---

## 五、改动文件

| 文件 | 说明 |
|---|---|
| `ai/tool/DouyinDownloaderTool.java` | 桌面 UA、第三方 Cookie、轮询抓取、UIFID 落盘、错误透出 |
| `agent/ToolPreChecker.java` | 天气位置参数按契约二选一 |
| `agent/ToolExecutionOrchestrator.java` | 错误恢复补参按工具区分 |
| `agent/CompositeGuideFlow.java` | 出行准备第 2 步只传坐标 |
| `ui/activity/AIChatActivity.java` | 引导参数互斥、确认页摘要、聚合工具包选择器、清空确认 |
| `ai/chat/component/QuickToolChipModule.java` | 聚合工具包 chip 绑定 |
| `ai/util/ChatHistoryManager.java` | 会话软删除 + 回收目录清理 |
