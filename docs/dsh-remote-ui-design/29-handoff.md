# 29 · 交接文档（2026-11-14 会话结束；A3-接 已落地）

> 下一轮从这里开始，不要重复已验通的结论，也不要重踩已记录的坑。
> **A3-接**（锚点入账 + 按锚点建会话 + 空壳不落盘）已于 2026-11-14 17:20 落地并真机验到，
> 细节见 `28-anchor-ledger.md` 第 7 节；下一步从 A4 开始。

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
| 锚点**入账**（hello / SSE ready·snapshot） | `RemoteDshTool.saveAnchorFromFrame` → `RemoteAnchorStore` | 真机 logcat `[anchor] 入账 local=94a27de2e65bac63`；`shared_prefs/remote_anchors.xml` 有 `current` + `by_remote.*` |
| 建本地会话**按锚点查或建** | `AIChatActivity.sessionIdForSave()`（4 个保存点） | 真机 logcat `[anchor] 保存挂到锚点会话 94a27…`；清历史后新对话落在 `conversations/94a27….json` |
| 空会话不落盘 | `ChatHistoryManager.saveConversationSession` | 单测 2 条（`emptySessionIsNotPersisted` 等） |

---

## 3. 下一轮要做的（按顺序）

### A3-接 ✅ 已完成（2026-11-14）

三处都落了，细节与验证证据见 `28-anchor-ledger.md` 第 7 节：

1. `hello`（以及 SSE 的 `ready`/`snapshot`）里的 `anchor` → `RemoteAnchorStore.save()`；
2. 建本地会话按锚点**查或建**（`AIChatActivity.sessionIdForSave()`，4 个保存点）；
3. `saveConversationSession` 空 `messages` 直接 return。

**仍需人工复核的唯一一条**：干净状态下"进出对话页 5 次 → `files/conversations` 仍只有 1 个文件"。
自动化跑过一轮（`am force-stop` → 冷启动 → 点「AI聊天」×5），**文件数没变**、
`[anchor] 入账` 每次都是同一个 `local_session_id`；但那几轮页面是空的（没有消息 → 不触发保存），
所以严格版"带消息进出 5 次"还是手点一次最稳。
注意别用 adb 连续点按代替：那会叠出第二个 `AIChatActivity` 实例，两个实例各存一条，
反而多出文件（本次已踩过）。

### A4：清历史 → 上报 `anchor:null` → 服务端重签

- 手机清历史时调 `RemoteAnchorStore.clear()` + `RemoteDshTool.resetSyncState()`；
- 上报走 `/ws/ctl` 的 `anchor` 帧（插件侧接收还没写）。
- 顺带：`DshHistoryImporter` 的 `import_sid_<电脑会话>` 映射应改成认锚点，否则同一条电脑会话
  会有"导入副本"和"锚点会话"两条本地文件。

### 事件帧带 anchor

插件 `sub.send` / `sse({type:'event'…})` 处附 `anchor`（按 device + session 取账本）。
手机收到后按锚点找会话。（握手帧与 SSE `ready`/`snapshot` 已带，事件帧还没。）

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
| 插件顶层就调 `loadAnchors()` | 同上一条的变体，但更阴：`ANCHOR_FILE()` 依赖 `PLUGIN_DIR`，而它在文件更后面才定义 → `ReferenceError` 被 `catch` 吞掉 → **账本每次重载都从空开始，下一次 `saveAnchors()` 覆盖磁盘**。表现：插件重载一次，`local_session_id` 换一个，手机上就多一条历史。读取已挪到 `PLUGIN_DIR` 之后 |
| 只给 WS `hello` 带 anchor | 隧道掐长连接时 App 走 **SSE**，这条路上拿不到锚点 → 又变成随机新建。SSE 的 `ready`/`snapshot` 已补 `anchor`（按 `Authorization` 设备令牌认设备） |
| "新建对话"保存旧对话用 `currentSessionId` | 清历史/页面重进后它是 null → 又随机开一条。已统一走 `sessionIdForSave()`；且必须在 `startNewRemoteConversation()` **之前**取 id（那步会把锚点换成新会话的） |
| 用 adb 自动点按验"进出 5 次" | 会叠出第二个 `AIChatActivity` 实例（`ChatNavigation` 只管应用内入口），两个实例各存各的 → 反而多出文件。这条验收只能手点 |

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
| `28-anchor-ledger.md` | 锚点对账（动态二维键、回收站/恢复/永久删除）；**第 7 节是 A3-接落地记录** |
| 本文件 | 交接 |

---

## 6. 已知未解决

1. **进出对话页多出本地历史**：A3-接已完成（见第 3 节、`28-anchor-ledger.md` 第 7 节），
   剩"手点 5 次仍 1 个文件"这一条人工复核；
2. **清历史后对账**：已加"失败清游标重试"，但"清锚点 + 上报 anchor:null 让服务端重签"没做（A4）；
3. **C6 自更新端到端未验**（792MB 下载）；
4. 甲板 chips（多板）只做了数据模型（U1），渲染仍是旧整行样式；
5. `resetSyncState` 只在 `syncSessionIdentity` 失败时触发，正常路径不重置；
6. `DshHistoryImporter` 的 `import_sid_` 映射与锚点是两本账，同一条电脑会话会出现两条本地文件（A4 时一并统一）。
