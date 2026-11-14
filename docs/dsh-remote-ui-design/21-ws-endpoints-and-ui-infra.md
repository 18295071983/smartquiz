# 21 · WS 端点矩阵与手机 UI 基础设施（方案 · 待审批）

> 状态：**方案稿，未实施**。写完给你审，批了再动手。
> 依据：本轮对插件 `index.js` 与手机端 `ai/tool`、`ai/chat/component`、`ai/chat/status` 的实际调查。

---

## 1. 调查结论（现状）

### 1.1 插件会推的帧（`STREAM_HUB` 广播）

| 类别 | 帧类型 | 频率 | 现有端点 |
|---|---|---|---|
| 会话事件 | `event`、`pulse`、`batch` | 高 | `/ws/events` |
| 告警 | `approval`、`approval_gone`、`question`、`question_gone` | 低但要命 | `/ws/alert` |
| 插件 UI | `cmd`、`ui/slot`、`ui_component`、`view`、`state` | 中 | `/ws/ui` |
| 子代理/日程 | `subagent/activity`、`schedule/changed` | 中 | `/ws/ui`（挤在一起） |
| 流式草稿 | `stream/delta`、`thinking`、`text` | **很高** | `/ws/ui`（挤在一起） |
| 文件 | `file_begin/chunk/end/error` | 突发大 | `/ws/file` |
| 控制 | `pull`→`batch`、`ack`、`ping`→`pong` | 低 | `/ws/ctl` |

问题：`stream/delta`、`thinking`、`text` 这类**高频草稿帧**现在和 `cmd`、`ui/slot` 抢同一条 `/ws/ui`，
一个 token 增量洪水就可能拖慢「插件推一条 slot / 手机收一条命令」。

### 1.2 手机端已有基础设施

| 设施 | 位置 | 能做 | 缺什么 |
|---|---|---|---|
| 甲板插槽 | `DockSlotBus.Slot{id,priority,title,detail,action,warn}` → `StatusDeckController` → `DeckRowModel` | 最多 3 行，单行一个 `action` 字符串，点击由 `onDockSlotClick` 解释 | 没有"列表"，没有逐行按钮，`action` 语义靠 if-else |
| 卡片按钮 | `ComponentActions`（`renderActions` / `execute`）+ `ComponentRegistry` 统一包一层 | `props.actions` 渲染按钮，支持 link/copy/open_app/am… | 只有前端动作，没有"回服务端取文件"这类**跨端动作** |
| 本地动作 | `ComponentActions.LocalAction`（本轮新增） | `permission`/`insight`/`session_pick` 由页面执行 | 没有 `open_file` 这类"先拉数据再打开"的动作 |
| 文件渲染 | `FileRenderActivity`（`EXTRA_FILE_PATH` / `EXTRA_FILE_URI`），`FileCardView.openFile()` 已是现成入口 | 按类型渲染 md/文本/表格/pdf，图片走应用内预览 | **没有"远程文件"路径**：file_card 的 `path` 是电脑上的绝对路径，手机本地不存在 → 直接打不开 |
| 反向命令 | `RemoteConnection.execCommand` / `executePushedCommand` | toast/dialog/ui_component… | 没有"打开文件"命令 |

### 1.3 关键结论

1. **端点还差两条**：`stream/delta`+`thinking`+`text`（草稿洪水）应独立；`subagent/activity`+`schedule/changed`+`ui/slot` 属于"状态类"，也可独立。
2. **UI 基础设施的真正缺口不是"渲染"，而是"动作模型"**：现在 `action` 是一个字符串 + 一堆 if-else；
   要做"列表每行可点、点了进文件渲染"，需要把动作变成**结构化对象**并由一个路由器分发。
3. **文件链路缺中间一层**：插件已有 `/ws/file` 流式推送，手机 `openLiveSocket` 也已经在拼块落盘，
   但没有"发起请求 → 回调 → 打开渲染器"的 API，也没有任何按钮会触发它。

---

## 2. 目标（这一版要做到）

1. 端点按事件语义拆干净：**任何一类流量都不能拖慢审批**。
2. 手机有一个**动作路由器** `RemoteActionRouter`：卡片按钮、甲板行、动态列表行、`cmd` 帧全部走它。
3. 能跑通一条端到端链路：
   **卡片按钮 / 动态列表行 → `open_file` 动作 → `/ws/file` 拉流 → 缓存 → `FileRenderActivity`**。
4. 动态列表组件（`remote_list`）：N 行，每行 label + detail + 一个动作。

---

## 3. 设计

### 3.1 端点矩阵（插件侧）

| 端点 | 推的帧 | 收的帧 | 理由 |
|---|---|---|---|
| `/ws/events` | `event`、`pulse` | `pull`、`ack`、`ping` | 主数据面 |
| `/ws/alert` | `approval*`、`question*` | `ping` | 用户正等着点，任何情况都不能慢 |
| `/ws/draft`（**新**） | `stream/delta`、`thinking`、`text` | `ping` | 草稿洪水，丢了也不影响正确性，可整体丢弃/降频 |
| `/ws/status`（**新**） | `ui/slot`、`view`、`state`、`subagent/activity`、`schedule/changed` | `ping` | 状态类，低频，可丢 |
| `/ws/ui` | `cmd`、`ui_component` | `cmd_ack`、`ping` | 命令/组件，必须到 |
| `/ws/file` | `file_*` | `file_get` | 大块数据 |
| `/ws/ctl` | `batch`、`pong` | `pull`、`ack`、`ping` | 控制面 |
| `/ws` | 全部 | 全部 | 旧客户端兼容 |

手机只开需要的：`events` + `alert` + `status` + `ui`（4 条，都是小包），
`draft` 按需（页面在前台且正在生成时），`file` 按需（用户点文件时）。

### 3.2 动作模型（手机侧核心）

```java
// ai/remote/action/UiAction.java
public final class UiAction {
    public final String kind;      // open_file | open_session | run_cmd | slot | insight | toast
    public final JSONObject payload; // kind 相关：open_file → {path, title}
}

// ai/remote/action/RemoteActionRouter.java
public interface ActionHandler { boolean handle(Context ctx, UiAction action); }
// 注册：open_file / open_session / run_cmd / slot / insight ...
```

来源统一：
- 卡片 `props.actions[i]` → `UiAction{kind=action, payload=action对象}`；
- 甲板 `Slot.action` → `UiAction{kind=slot.action, payload=Slot}`；
- 新组件 `remote_list` 的 `props.rows[i].action` → 同上；
- WS `cmd` 帧 → `run_cmd`。

### 3.3 动态列表组件 `remote_list`（手机渲染）

`ComponentData{type:"remote_list", props:{title, rows:[{label, detail, action:{kind,path}}]}}`

- 渲染：`LinearLayout` + 每行 `label`（主）+ `detail`（次，灰色）+ `›`；
- 点击：`RemoteActionRouter.dispatch(row.action)`；
- 来源：插件侧 `ui/slot` 改用 `remote_list`（把 3 行甲板上限放开成可滚动列表）。

### 3.4 文件链路（`open_file` 完整路径）

```
用户点按钮/列表行
  → RemoteActionRouter(kind=open_file, path=…)
  → RemoteFileClient.fetch(sid, path)           // 走 /ws/file 端点
       file_begin → 建缓冲（cacheDir/remote/）
       file_chunk → 追加（带 seq 校验，乱序就重来）
       file_end   → 校验 sha256 → 落盘 → 回调 File
  → FileRenderActivity.start(ctx, file)         // 复用现有 EXTRA_FILE_PATH 路径
```

失败/超时（15s）：`remote_list`/卡片上把按钮变灰 + Toast「电脑端没给这个文件」。

### 3.5 与现有基础设施的边界

- **不碰** `ChatMessage.components` 的类型系统：`remote_list` 只是新增一个 `ChatComponent` 实现（注册表里加一条）。
- **不碰** part 类型（设计稿硬约束）。
- `FileCardView` 保持不动；远程文件走新的 `RemoteFileClient`，两者共用 `FileRenderActivity`。

---

## 4. 分阶段与验收

| 阶段 | 内容 | 验收（必须可复现） |
|---|---|---|
| **P1** | 插件加 `/ws/draft`、`/ws/status` 端点 + 帧路由；`/status.ws_channels` 显示各端点 | Node 客户端开 4 条连接，各自的 `hello.features` 正确；在 `draft` 上发 `pull` → `frame_not_allowed` |
| **P2** | 手机 `RemoteActionRouter` + `UiAction`；卡片 `actions`、甲板 `Slot.action` 全部改走它（行为不变） | 现有「改权限」「看面板」「切换会话」三个动作仍工作；单测覆盖分发 |
| **P3** | `RemoteFileClient`（`/ws/file` 拉取 → 缓存 → sha256 校验）+ `open_file` 动作 → `FileRenderActivity` | 真机：点一个按钮，文件在手机上渲染出来（md/pdf/图片各一例） |
| **P4** | `remote_list` 组件 + 插件侧把某类 `ui/slot` 改推 `remote_list` | 真机：列表 5 行，点任一行打开对应文件 |

每阶段独立提交、独立可回滚（端点新增不删旧的，手机侧加开关）。

---

## 5. 风险

| 风险 | 处置 |
|---|---|
| 拆端点导致某类帧没人收（静默丢推送） | 插件侧 `/ws` 保持全功能；新端点只是**分流**，`hello.features` 明确声明；加一条 `/status` 自检：各端点最近一次收到帧的时间 |
| `RemoteActionRouter` 改动面大（卡片/甲板/命令三处） | P2 先做"纯转发"——旧 if-else 逻辑搬进 handler，行为零变化，再逐个加新动作 |
| 文件落到 cache 被系统清掉 | 落 `cacheDir/remote/<sha前8位>-<name>`，打开前校验存在；清掉了就重新拉 |
| 大文件把手机内存顶爆 | 客户端上限 32MB（与插件一致），超过直接拒绝并提示；分块写临时文件而不是全内存（P3 就按流式写盘做） |

---

## 6. 需要你拍板的三个问题

1. **动态列表放在哪？** 我建议：替换现在甲板的「最多 3 行」，改成**可滚动列表**（输入框上方，最多 5 行高度）。
   也可以做成消息流里的卡片。你选哪个？
2. **文件点击的入口先接谁？** 我建议先接 **`deliverables/presented` 卡片的按钮**（交付物本来就是给人点的），
   再接 `workspace/changes`，最后才是 `remote_list` 任意行。
3. **`/ws/draft` 要不要默认关？** 草稿只影响"边打字边看"，关了省流量；我建议**默认开、页面不在前台时自己断**。

---

## 7. 参考实现调查：dsh-Go 与 Qoder Mobile（2026-11-14）

### 7.1 dsh-Go 的原生插件 UI 协议（就是我们要的那套"基础设施"）

文档：`docs/plugins/README.md`、`components.md`、`actions.md`、`inputs.md`、`slots.md`、`design.md`。

**核心模型：插件不碰 App，只给"声明 + JSON 视图 + 动作"，App 用自己的组件画。**

| 层 | 做法 | 我们的现状 |
|---|---|---|
| 视图 | 组件树 `{type, …}`：v1 16 个（`stack/row/text/callout/steps/compare/metric/chart/progress/list/keyValue/table/actions/image/options/confirm/picker`），v2 再加 26 个（输入/结构/空状态/图集） | 我们有 `text/markdown/file_list/dsh_*` 等一批，**缺 `list`/`actions`(多按钮)/`options`/`confirm`/`emptyState`** |
| 点按 | 点 → `invoke(actionId, value)` → 插件 handler → 返回**效果** | 我们是"前端直接执行"（link/copy/am…），没有回服务端的 invoke |
| 效果白名单 | 8 种：`insert`（塞输入框不发送）/`command`/`open`（页面或面板）/`link`（只 https、弹确认）/`toast`/`copy`/`refresh`/`patch`，**一次 ≤4 个，点按后 3 秒内有效** | 无白名单、无时效 |
| 延后 | `pending` + `result`（≤60s，只允许 insert/copy/toast/patch，每插件并发 2 个） | 无 |
| 预算 | 视图 JSON 工具卡 8KiB / 面板 32KiB / 状态条 2KiB；组件数 40/120/8；层级 6/8/3 | **完全没有**（这是真风险：一个超大视图能把 UI 卡死） |
| 错误降级 | 子组件坏 → 该块灰占位「无法显示」；根坏 → 整块一行「插件界面有误」；空数组 → 「暂无数据」 | 部分有（canRender 兜底），无统一降级 |
| 永远不开放 | 审批工具、改权限、手机操控、装卸插件、切主机、读凭据、无用户操作的自动触发 | 我们的审批同样只走 App 自己的卡 |

**它的 `list` 组件**正是我们要的"动态列表"：
`items:[{title, subtitle?, trailing?, icon?, tone?, action?}]`，≤50 行，**有 action 的行带箭头、可点**。

**它的 `actions` 组件**是我们卡片的加强版：
`items:[{label, action, icon?, style?: primary|plain}]`，**≤4 个，至多一个 primary**，1–2 个并排、3–4 个竖排。

### 7.2 Qoder Mobile 的设计（阿里，远程控制 AI Agent）

- **首页不是聊天，是任务卡**：哪些在跑、哪个等你决策、哪个跑完；
- 点进去看**结构化执行**：当前计划、每步推理、工具调用、决策点，**可展开**；
- 过程**压成一行摘要**（读文件/改代码/跑测试），一眼扫完，点击展开；
- 通知直达任务；**锁屏可批准/拒绝**高风险操作；
- 手机端**不执行任何代码**（与 dsh-go 同一条安全边界）。

### 7.3 对我们的三条修正（比第 3 节更准）

1. **动作模型改成"invoke + 效果白名单"**，别用自由 `UiAction{kind,payload}`：
   点按 → `/ws/ui` 发 `{type:'invoke', action, value}` → 插件 handler 返回效果 → App 校验白名单后执行。
   我们的白名单在 dsh-go 8 种的基础上**加第 9 种 `open_file {path}`**（它有 `pickFile`，没有"打开电脑文件"）。
   这样"按钮能干什么"永远由 App 决定，插件越权不了 —— 比现在的 if-else 安全得多。
2. **先补 5 个组件**：`list`（动态列表）、`actions`（多按钮）、`options`（单选）、`confirm`、`emptyState`。
   其余（metric/chart/progress/table/keyValue/image）延后。
3. **加预算校验**：视图 ≤32KiB、组件 ≤120、层级 ≤8、list ≤50 行、actions ≤4 个。
   超限按 dsh-go 的办法降级（截断 / 灰占位），不整块崩。

### 7.4 不抄的部分

- **不做** v2 输入/表单（双向绑定、有限公式）——我们的表单在本地页面，不在这条链路上；
- **不做** dsh-go 的 `pending/result` 延后效果（我们的动作都是本地或一条 WS，3 秒足够）；
- **不抄** Qoder 的"任务卡首页"（那是产品形态改动，跟本次基础设施无关，但可以列为后续方向）。

---

## 8. 本次不做（明确排除）


- 不做双向 RPC（手机调插件任意方法）；只用现有 `pull/ack/file_get/cmd_ack` 四个动词。
- 不改 `DshTimeline`/part 体系。
- 不做断点续传（分块重传即可，文件 ≤32MB）。
