# 29 · 交接文档（2026-11-14 会话结束）

> 下一轮从这里开始，不要重复已验通的结论，也不要重踩已记录的坑。

---

## 1. 环境事实

| 项 | 值 |
|---|---|
| 仓库 | `D:\qzq\smartquiz`（Android，包名 `com.oilquiz.app`） |
| 插件 | `D:\qzq\smartquiz\tools\dsh-plugin-smartquiz-remote\index.js`（ESM，跑在 DSH 宿主进程内） |
| DSH 插件档案 | `C:\Users\xiaocong\.workbuddy\dsh-home\profiles\desktop`（`@local/smartquiz-remote` 是 pnpm link） |
| 构建 | `.\gradlew.bat --offline assembleDebug`；APK 在 `build\outputs\apk\debug\答题宝-debug-2.2.apk`（约 792MB，内嵌模型） |
| 单测 | `.\gradlew.bat --offline testDebugUnitTest --tests "…"` |
| 装机 | `adb install -r <apk>`（USB 序列号 279b6c51；无线 192.168.1.10:42063 经常掉） |
| 电脑 IP | 局域网 `192.168.1.2`；Tailscale `100.113.188.52`；插件 LAN 端口 **8218** |
| 插件重载 | `plugin_manager set_plugin enabled:false` → `enabled:true`。**注意**：有时 enable 返回 `changed:false` 但其实没重载，必须验 `/status` 或新路由 |
| 版本号 | `build.gradle` 的 `versionCode`（当前 4 / 2.2） |

**令牌**：主令牌 32 位（`.token` 文件）；设备令牌 32 位（`devices.json`，`/pair/claim` 签发）。`authOk` 两种都认，`deviceByToken` 只认设备令牌。

---

## 2. 已验通的能力（可直接用，别再改）

| 能力 | 位置 | 证据 |
|---|---|---|
| 多端点 WS：`/ws/events`、`/ws/ui`、`/ws/alert`、`/ws/file`、`/ws/ctl` | 插件 `handleWsConn` | 手机 `ws_channels={events:1, ui:1}` |
| 通用视图渲染器（10 组件 + 预算） | `ai/remote/view/ViewRenderer.java` | 真机渲染插件推的 JSON |
| 效果执行器（9 种、≤4 个、3 秒窗口） | `ai/remote/view/EffectExecutor.java` | 单测 6 条 + 真机 toast |
| `invoke` 往返 | 插件 `/v2/invoke` + `RemoteViewActivity` | 真机点按钮弹 pong |
| 视图定义下发 + 缓存 + 按名字打开 | 插件 `VIEW_DEFS` + `RemoteViewDefs` | 真机打开 demo 视图 |
| 甲板槽位由插件推（`ui/slot`） | 插件 → `RemoteSlotBridge` | 真机甲板出现插件槽位 |
| 连接管理独立页（切换/删除/保存） | `ai/tool/RemoteProfilesActivity.java` | 真机列出并显示「当前」 |
| 6 位配对码输入 | `RemoteDshTool.claimPairingCode` | 代码就位 |
| 锚点账本 + `create`（手机新建→电脑新建） | 插件 `ANCHORS`、`/v2/anchor` | curl 实测 `create` 返回一对 id 并入账 |

---

## 3. 下一轮要做的（按顺序）

### A3-接（最要紧，直接决定"多出历史"）

1. `RemoteDshTool` 的 WS `hello` 分支里，把 `o.optJSONObject("anchor")` 交给
   `RemoteAnchorStore.save(app, anchor)`；
2. 建本地会话的地方改成**按锚点查或建**：
   已经有 `local_session_id` → 复用；没有才 `new`；
3. `ChatHistoryManager.saveConversationSession`：**`messages` 为空直接 return**（不写文件）。

验收：进出对话页 5 次，`files/conversations` 仍只有 1 个文件。

### A4：清历史 → 上报 `anchor:null` → 服务端重签

- 手机清历史时调 `RemoteAnchorStore.clear()` + `RemoteDshTool.resetSyncState()`；
- 上报走 `/ws/ctl` 的 `anchor` 帧（插件侧接收还没写）。

### 事件帧带 anchor

插件 `sub.send` / `sse({type:'event'…})` 处附 `anchor`（按 device + session 取账本）。
手机收到后按锚点找会话。

### C6 自更新

代码就位（插件 `app_release` + `/app/latest.apk`，手机 `RemoteAppUpdate`），**端到端未验**——
792MB 下载没实测过。要验：`versionCode` 改成比手机高的值，build，插件重载，手机点插槽。

---

## 4. 已踩过的坑（务必避开）

| 坑 | 教训 |
|---|---|
| PowerShell 发 JSON / 中文给插件 | `Invoke-WebRequest -Body` 会把引号剥掉、中文变 `?`。**一律 `curl --data-binary @文件`（UTF-8 无 BOM）** |
| 用 PowerShell 读改含中文的 Java/XML | 默认按 GBK 解码再按 UTF-8 写回 → 文件损坏（manifest 直接解析失败）。**用 edit/write 工具，不用 PowerShell 改文本文件** |
| 动态往别的页面插 View | 三次都失败（chips 不显示、按钮重复、仍不显示）。**静态控件 + 独立 Activity** 一次就成（`RemoteDeckListActivity` / `RemoteProfilesActivity`） |
| 插件重载 `changed:false` | 看着成功其实没加载新代码。重载后**必须验**一个新增的路由或 `/status` 字段 |
| 模块顶层早引用 `PLUGIN_DIR` | `const X = join(PLUGIN_DIR,…)` 放在定义之前 → 插件加载失败。改成 `() => join(PLUGIN_DIR,…)` |
| `create` 走 session_id 校验 | 新建时没有 id，会被 400 挡掉。只校验 `device_id` |
| 只改布局没改页面 | 曾怀疑过，其实两边都改了；真正的问题是那段代码包在 `catch (Throwable ignored)` 里静默失败。**catch 要打日志** |

---

## 5. 设计文档索引（`docs/dsh-remote-ui-design/`）

| 文档 | 内容 |
|---|---|
| `20-chat-shell.md` | 聊天外壳 v1.1、M8 落地记录 |
| `21-ws-endpoints-and-ui-infra.md` | 端点矩阵、动作模型、文件链路、dsh-Go/Qoder 调研 |
| `22-deck-kinds-and-chips.md` | 甲板四类槽位（toggle/action/display/list）、状态类事件迁移 |
| `23-specialized-cards-and-routing.md` | 专用卡片分型、`CardSpec`/`CardRouter` |
| `24-plan-ui-first.md` | 实施总纲（先 UI 后后端） |
| `25-channel-lights-and-selfheal.md` | 通道健康灯、对码、熔断、dry-run |
| `26-client-infra-plugin-driven.md` | 客户端基础设施 C1–C6 |
| `27-plugin-update-guide.md` | **改插件不改 App 的指南**（推视图、接点按、返效果） |
| `28-anchor-ledger.md` | 锚点对账（动态二维键、回收站/恢复/永久删除） |
| 本文件 | 交接 |

---

## 6. 已知未解决

1. **进出对话页多出本地历史**（A3 未接完，见第 3 节）；
2. **清历史后对账**：已加"失败清游标重试"，但"上报 anchor:null 让服务端重签"没做；
3. **C6 自更新端到端未验**（792MB 下载）；
4. 甲板 chips（多板）只做了数据模型（U1），渲染仍是旧整行样式；
5. `resetSyncState` 只在 `syncSessionIdentity` 失败时触发，正常路径不重置。
