# 24 · 实施总纲：先 UI，后后端（已批准方向）

> 用户口径：「好写到方案里面，先做 UI，之后做后端。」
> 本文件是 21 / 22 / 23 三份方案的实施顺序与验收总表；细节看各分册，这里只定**做什么、按什么顺序、怎么算做完**。

---

## 0. 一句话目标

把"状态"从消息流搬到甲板（chip 多板），把"历史"在消息流里做成按事件分型的专用卡；
手机侧先把 UI 做完（不依赖协议改动），再回头补插件侧的端点与动作白名单。

---

## 1. 总原则

| 原则 | 含义 |
|---|---|
| **UI 先做** | 所有手机侧改动不依赖新端点、新协议；用现有 `/ws`（或 `/ws/events`+`/ws/ui`）就能跑起来 |
| **后端后做** | 端点细分（`/ws/draft`、`/ws/status`）、invoke 效果白名单、`/ws/file` 打开链路，排在 UI 之后 |
| **开关可回退** | 每步都有开关或旧样式保留，真机不对能一键退回 |
| **不新增 part 类型** | `DshTimeline`/part 体系不动，卡片仍走 `ComponentRegistry` |
| **默认折叠** | 任何新卡片默认收起，用户点开才铺开——避免"卡越做越重" |

---

## 2. UI 阶段（先做）

### U1 · 甲板数据模型（对应 22 号 D1）

- `DockSlotBus.Slot` 增加 `kind`（toggle/action/display/list）、`board`（alert/task/asset/state）、`pinned`、`count`；
- `DeckRowModel` 增加 `chipText`（≤12 字）与 `count`；
- `RemoteStatusSlots` 按新字段发布现有槽位（权限/任务/待处理/跟随）；
- **渲染仍用现有整行样式**（行为零变化）。

**验收**：单测覆盖分组、排序、固定优先；真机与改动前像素级一致。

### U2 · 多板 chip 渲染（对应 22 号 D2）

- 新增 `DeckChipBoard`：按 board 分组，每行一条 `HorizontalScrollView` + `Chip`（28dp，复用 `com.google.android.material.chip.Chip`）；
- 行内 ≤3 个 chip，超出末尾 `+N`；`alert` 行 warn 态用橙色；
- 点 chip → 现有的甲板动作分发；`+N` → `RemoteDeckListActivity`（已实现）。

**验收**：真机三行 chip（alert/task/state），点 `+N` 进列表；旧的整行样式可通过开关切回。

### U3 · toggle 与固定/动态（对应 22 号 D3）

- `toggle` 类接上：自动允许、跟随会话（写回配置，重启保留）；
- 固定槽位常驻（空值显示"—"）、动态槽位出现/消失；
- `display` 槽位点开进全屏列表看详情。

**验收**：点 chip 切换立刻生效且重启保留；关掉自动允许后 chip 消失。

### U4 · 专用卡骨架 + 6 类（对应 23 号 C1）

- `CardSpec` / `CardRouter` / `CardShell`；
- 先把 `dsh_notice` 里 6 类迁成专用卡：`sys_card`、`retry_card`、`team_card`、`compact_card`、`feedback_card`、`approval_card`；
- 未知事件继续走 `generic_notice`（现 `dsh_notice`）。

**验收**：单测：事件 → 卡片 type 一一对应；真机视觉可区分（黄/橙/灰/折叠）。

### U5 · 命令卡与文件卡（对应 23 号 C2/C3 的 UI 部分）

- `cmd_card`：等宽命令、退出码、耗时、输出折叠 N 行；
- `file_card`：文件列表（+N/−M）、产物行；
- 文件点击**先只做 UI**（点了提示"待接后端"），真正打开留给 B3。

**验收**：真机命令跑完卡上有退出码/耗时；文件卡能展开列表。

---

## 3. 后端阶段（UI 之后）

### B1 · 端点细分（对应 21 号 P1）

- 新增 `/ws/draft`（草稿洪水）、`/ws/status`（状态类推送）；
- `/status.ws_channels` 显示各端点在线数；
- 手机侧按需连接（draft 仅前台生成时）。

**验收**：Node 客户端四连接各自 `hello.features` 正确；越界请求回 `frame_not_allowed`。

### B2 · 动作白名单（对应 21 号 P2、22 号 D4）

- 点按 → `/ws/ui` 发 `{type:'invoke', action, value}` → 插件返回效果；
- 效果白名单 9 种：`insert/command/open/link/toast/copy/refresh/patch/open_file`；
- **≤4 个效果、点按后 3 秒内有效**；未知效果忽略并记日志；
- 状态类事件停止出卡（22 号第 5 节迁移表生效）。

**验收**：真机点 chip/卡按钮 → 插件收到 invoke；越权效果被拒。

### B3 · 文件打开链路（对应 21 号 P3）

- `RemoteFileClient`：`/ws/file` 拉流 → 缓存 → sha256 校验；
- `open_file` 效果 → `FileRenderActivity`（`EXTRA_FILE_PATH`）；
- 上限 32MB（与插件一致），超限拒绝并提示。

**验收**：真机点文件卡 → 文件在手机里渲染出来（md/pdf/图片各一例）。

---

## 4. 依赖关系

```
U1 ──▶ U2 ──▶ U3
 │
 └──▶ U4 ──▶ U5 ──┐
                  ├──▶ B2（动作白名单，需要 U2/U4 的点击点）
B1（端点）────────┘
                  └──▶ B3（文件打开，需要 B1 + U5）
```

- U1 必须先做（其余都依赖新字段）；
- B1 与 U1–U5 可以并行（不同设备/不同文件），但**发布顺序**仍按"UI 先"；
- B2 要求 U2/U4 已有可点的 chip/按钮，否则没东西可点。

---

## 5. 每阶段的验收基线（都要真机过一遍）

1. 改动前后**截图对比**（同一会话、同一滚动位置）；
2. 不出现新的 `FATAL`/`ANR`（logcat 抓 5 分钟）；
3. 断网/断连走一遍：甲板清空、重连后状态回填；
4. 旧数据（老 `dsh_notice` 卡）仍能正常渲染。

---

## 6. 明确不做

- 不做双向 RPC（只保留 `pull/ack/file_get/cmd_ack/invoke`）；
- 不做 v2 输入/表单双向绑定；
- 不新增 part 类型、不改 `DshTimeline` 归约；
- 不改 Qoder 那种"任务卡首页"产品形态。

---

## 7. 分册索引

| 分册 | 内容 |
|---|---|
| [21-ws-endpoints-and-ui-infra.md](21-ws-endpoints-and-ui-infra.md) | WS 端点矩阵、动作模型、文件链路、dsh-Go/Qoder 调研 |
| [22-deck-kinds-and-chips.md](22-deck-kinds-and-chips.md) | 甲板四类槽位、固定/动态、状态类事件迁移表 |
| [23-specialized-cards-and-routing.md](23-specialized-cards-and-routing.md) | 专用卡片分型、`CardSpec`/`CardRouter` 分流架构 |
| 本文件 | 实施顺序（先 UI 后后端）与验收总表 |
