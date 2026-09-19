# Mock v8 预演 · 装机核对单（v5 · 59 桥）

**包**：`apk_export/aurora_clock_v5_mocktest.apk`（待导出）
应用名「极光时钟预演 v5」　壳版本 **v8**（bridge_api 4，59 桥）
与正式页 `aurora_clock_v5.html` 口径一致；mock 面板预演 v8 壳全量桥。

**入口**：右下角「MOCK 壳」面板。按钮：`切 v7/v8`（mkbtn）、`清空`（mkclr）、`真 Fullscreen`（mkfs）、`当前桥`（mkapi）。
状态行：`模式 · 全屏中/窗口 · 桥 N`。

---

## 一、离线静态预检（已通过，无需复做）

| 检查项 | 结果 |
|---|---|
| mock 是否覆盖页面调用的全部桥（v5 新增 TTS/亮度/对话框/系统信息） | ✅ 全覆盖（BASE 40 + V8NAMES 19 = 59），无缺 |
| 页面新增调用（speakText / setBrightness / showDialog / getSystemInfo）是否判空 | ✅ 均有 `hasBridgeFn(...)` / `b.xxx` 判空 + `try/catch`，v7 下不抛错 |
| 「切 v7」是否真摘掉 19 个 v8 桥 | ✅ `install()` 每次重建对象，v7=40 桥 |
| 新 v8 方法是否有日志 | ✅ enterFullscreen / setOrientation / speakText / saveFile / showDialog 等均 `rec(...)` |
| mock BASE(40) + V8NAMES(19) 与壳 @JavascriptInterface(59) | ✅ 一一对应，无冲突 |

---

## 二、步骤与预期（★=关键）

| # | 操作 | 预期现象 |
|---|---|---|
| 1 | 打开 App（默认 v8） | 状态行 `v8 · 窗口 · 桥 59`；日志首行「已注入 mock 壳 v8 —— 59 个桥（v8 全量 59：全屏/方向/亮度/TTS/文件/选择器/对话框/系统/应用）」 |
| 2 | 点「当前桥」 | 日志列出 59 个桥名，含 enterFullscreen / setOrientation / speakText / saveFile / showDialog / getSystemInfo 等 |
| 3 ★ | 点时钟页 ⛶（沉浸键） | 日志 `enterFullscreen(true) → 模拟系统栏隐藏/恢复`；页面顶部出现「[MOCK] 真全屏生效中」条；状态行变 `全屏中` |
| 4 | 再点 ⛶ | 日志 `enterFullscreen(false)`；顶部条消失 |
| 5 ★ | 点「切 v7」 | 状态行 `v7 · 窗口 · 桥 40`；日志「已注入 mock 壳 v7 —— 40 个桥（v7 无全屏桥）」 |
| 6 ★ | v7 下点 ⛶ | **不出现** enterFullscreen 日志（页面靠 CSS 回落进沉浸）；**不报错** |
| 7 | 切回 v8；点「真 Fullscreen」 | 探测 WebView：成功→「requestFullscreen() 成功 → 壳支持标准 Fullscreen API」；否则→「被拒 / 本 WebView 无 requestFullscreen API」 |
| 8 | 转屏（横竖切换） | v8 下日志出现 `setOrientation('landscape'\|'portrait')`；横屏时钟面板/网格**不溢出** |
| 9 | 设置页「声音」块开「语音报时（TTS）」 | v8 下行可见；开启后整点日志出现 `TTS 朗读: 现在是X点整`（mock）；v7 下该行隐藏 |
| 10 | 设置页「显示」块开「同步系统亮度」并拖亮度 | v8 下行可见；拖动日志出现 `setBrightness(N)`；v7 下该行隐藏 |
| 11 | 设置页「恢复默认设置」 | v8 下走原生 `showDialog('恢复默认设置', …)`（mock 用 confirm 模拟）；v7 下走页内 sheet |
| 12 | 设置页「设备与版本信息」 | v8：`壳版本 v8-mock (API 4)` + 新增行「语言 / 系统时区 / Android版本 / 状态栏 / 原生桥 59 个」 |

---

## 三、判定

- **3 / 5 / 6 / 9 / 10 / 11 全对** ⇒ 桥接入、回落、隐藏逻辑正确 ⇒ v8 壳 59 桥上线后页面侧行为一致。
- 第 6 若反而出现 `enterFullscreen` 日志或报错 ⇒ 注入块没真摘桥 / 页面没判空。
- 第 7 决定 v8 壳里"全屏"走哪条路：**若 WebView 支持标准 `requestFullscreen`**，则壳可不依赖桥；实测壳无 `onShowCustomView`，仍走壳桥为主。
- 第 9/10 验证 v5 新增桥的**条件显示**（桥不可用自动隐藏行，不破坏 v7 体验）。

## 四、备注

- 日志最多保留 60 条，旧的自动滚出。
- 面板「清空」只清日志，不改模式。
- 本页仅测试用；正式页 `aurora_clock_v5.html` **不含**该注入块。
- v5 正式页与 mock 页均已同步：`files/aurora_clock_v5.html`、`files/aurora_clock_v5_mocktest.html`。
