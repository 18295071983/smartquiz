# DSH 远程 UI · 交叉审查结论（v2.1 修订依据）

- 日期：2026-11-13
- 方法：5 个子代理并行审查三份设计文档，角度为 事实 / 接口 / 约束 / 计划 / 规范；结论均附 file:line
- 处置：**本文优先于** `00-overall.md`、`10-modules.md`、`90-tests-acceptance.md` 与 `../dsh-remote-ui-architecture-v2-2026-11-13.md` 中被本文更正的部分

---

## 1. 总体结论

| 维度 | 结论 |
|---|---|
| 事实准确性 | **中**：核心机制与代码吻合，但有 9 处引用错误 + 1 处"声称已改其实没改" |
| 接口可实现性 | **低**：三个新接口全部需要调整；`ActionSink` 在现有代码上连触发条件都不成立 |
| 约束完整性 | **不足**：7 条约束里 5 条不够，另有 4 条遗漏；"唯一入口"在现有代码里本就不成立 |
| 计划可行性 | **偏低**：每阶段工期低估约 2 倍；缺一条会丢数据的 B11 |
| 与规范一致性 | **有 13 处冲突/重复**，其中 3 处需要改文档结构 |

一句话：**方向对（接入既有设施），切片错（先建控制器、先做甲板）、数字错（行数/图元/页大小）、漏项（B11）。**

---

## 2. 已修代码（审查直接促成）

| # | 问题 | 处置 |
|---|---|---|
| F1 | **分页 50 从未生效**：提交 `63ad2225` 说明写了 100→50，但该文件在 ID 回滚时被 `git checkout` 连带还原，常量仍是 100 | 已恢复为 50，提交 `3fca3917`，注释说明防再犯 |

---

## 3. 新增缺陷（按严重度）

| # | 缺陷 | 证据 | 影响 | 归属 |
|---|---|---|---|---|
| **B11** | `deepCopyForSave` 与 `ChatMessage.clone()` **都漏拷 `approvals` / `questions`** | `ChatHistoryManager.java:292-341`、`ChatMessage.java:1713-1739` | 重启后 pending 审批直接消失 → "审批可重试"是假验收 | **P0** |
| **B12** | `/v2/view` 远程卡是死路：不可交互、`lastRemoteCardSig` 只在进程内、重启/重放重复插卡、保存后变空条目 | `AIChatActivity.java:13107-13113,13151-13153,13795`；`RemoteCardView` 无 actions | 重复且不可用的第二条渲染路径 | **P0（建议整条删除，B7/B9 随之消失）** |
| **B13** | DSH 位置索引与归档冲突：`maybeArchiveAfterSave` 会从头部删、`safeNotifyItemChanged` 只查边界 | `AIChatActivity.java:8622-8627,14090-14097` | 流式刷新可能刷错行 | P2 |

---

## 4. 缺陷归属修正

| # | 原归属 | 改为 | 理由 |
|---|---|---|---|
| B1 `DockSlotBus.clear()` | P0 | **P1** | 仅被测试调用；且 DSH `turn/end` **不走 `endGeneration()`**（`applyDshEvent:13496-13513` 只标 COMPLETED），在那里补 clear 对 DSH 无效，还会误删仍有效的跨回合 job/goal |
| B2 组件 id 跨回合撞车 | P1 | **P2（低风险）** | 每次新建镜像气泡即 `clear`，key 自带 `tool:<callId>` → **不可复现**，影响被高估 |
| B3 props 指纹不排序 | P1 | **P2** | 属动态卡自身设计前置，非线上缺陷 |
| B4 回传前缀嗅探 | P1 | **P2** | DSH 卡片当前根本不产 `actions` → 不是线上缺陷，是动态卡的前置 |
| B5 `pushView` 绕过 caps | P4 | **P1（半天）** | 单点修复 |
| B6 DSH 气泡 id≠subId | P1 | **P2/P3** | 不是丢数据（UUID 已稳定，`ensureRecoveredIds` 只补空 id）；是命名约定 |
| B7 远程卡审批点击 | P1 | **随 B12 一起消失** | 整条路径删除 |
| B9 `remoteCardView` 未补拷 | P1 | **随 B12 一起消失**（若保留则 P0） | 同上 |
| B10 会话文件无 fsync/`.bak` | 可选 | **不是可选，但 2 天内不碰** | `delete→rename` 有整文件丢失窗口（`ChatHistoryManager.java:658-663`） |

---

## 5. 计划重排（替代 00-overall §9 与 v2 §9）

| 阶段 | 内容 | 工期 | 依赖 |
|---|---|---|---|
| **P0** | **B11**（补拷 approvals/questions/remoteCardView + clone + 单测）→ **B12**（删 `/v2/view` 远程卡整条路径） | 2-3 天 | — |
| **P1** | **B5**（`pushView` 补 caps）+ **B8**（caps 声明与构造点矩阵对齐）+ B1（DSH ended / 切会话 / 断连时清 transient slot） | 2-3 天 | — |
| **P2** | 动态卡：本地 fixture 验证 `layout` 渲染 → B3/B2/B4（含 source 注入）→ B13（索引改按 message 查找） | 2-3 天 | P1 的 B5 |
| **P3** | B6（id 命名）+ B10（原子写加固）+ 配置表（仅给甲板 maxRows 提供值） | 2-3 天 | — |
| **P4** | 插件动态卡（走 `/events` 事件，**不走 `/v2/view`**）+ 拆模块（ESM `import`） | 2-3 天 | P2 |

**总计 10-14 天**（原估 6-9 天被低估约 2 倍）。

**更早验证的顺序**：P2 用本地 fixture 先证明 `layout` 能渲染（`ComponentRegistry.java:107` 已注册 `LayoutCardView`），再 P4 做服务端卡，最后 P3。

---

## 6. 两天最小一刀（建议先做这个）

> 不建 `StatusDeckController`、不删布局 id。

1. 在现有 `renderDockSlots` 里把 **D3 压到 1 条**；
2. 在 `renderAgentProgress` 里**收起 msg/meta 两行**（保留标题+进度条）；
3. 两处显隐各补一次 `onViewportChanged(改前 pinned)`；
4. DSH `ended` / 切会话 / 断连时**清 transient slot**（不走 `endGeneration`）；
5. 补 `approvals` / `questions` / `remoteCardView` **三行深拷贝** + 一条单测。

预期：聊天区立即多出约 2 行高度、审批不再因重启丢失；改动集中、可单独 commit（`fix(chat): ...`），能 `git revert`。

---

## 7. 验收改法（替代 90-tests 的 A1/A2/A9）

| 原验收 | 问题 | 改为 |
|---|---|---|
| A1 "聊天区损失 <5%" | 与"3 行"数学冲突（3×11sp+padding≈57dp > 5%×640dp=32dp）；且未区分本机/DSH 基线 | 固定设备脚本记录 `status_deck.getHeight()` 与 `messageList.getHeight()`：断言 deck ≤72dp 且视口减少 ≤12%；D1 对 DSH 不适用（DSH 不写 `AgentProgressBus`），需单列 |
| A2 插槽无残留 | 无触发手段、无日志 | `StatusDeckControllerTest(FakeHost)` 断言显隐各回调一次；注入 3 slot → DSH `turn/end` → 断言 `DockSlotBus.list()` 为空 |
| A3 贴底不裁切 | 靠截图 | pinned 时断言 `onViewportChanged(true)` 恰好一次、`lastChild.bottom≈height-padding`、`!canScrollVertically(1)` |
| A9 caps 一致 | 全 caps 客户端下 `filterViewCards` 是恒等映射 → **假阳性** | 用只声明 `events.stream` 的 caps 请求两条通道，断言都等于 `filterViewCards(cards, reduced)`；再加 caps 矩阵（每个 cap 必须有构造点+渲染器） |

---

## 8. 事实更正表（文档引用错误）

| 文档位置 | 错误 | 正确 |
|---|---|---|
| v2 §15.3 | 分页 100→50 已落地 | **实际未落地**（已修，见 F1）|
| v2 §1.1 / §5.2 / 10-modules M2 | `ComponentActions.collectValues` | 该类**无此方法**（只有 `collectInputs(String)`）；整树取值在 `NativeLayoutRenderer.collectValues(Map)` |
| v2 §4.1 / §13 | `dock_slot_host` 403-410、`agent_progress_slot` 415-425 | **397-404 / 408-471** |
| v2 §14.4 | B9 补拷点在 `ChatHistoryManager:324-335` | **301-323** |
| v2 §15.1 | 14,4xx / 6,387 / ~3,100 | **14,588 / 5,584 / 4,694**（前三是 ROADMAP 旧值）|
| v2 §1.1 | ~70 图元 | **64**（容器 11 个正确）|
| v2 §1.1 | ≤200KB | **200,000 字符** |
| v2 §1.3 | `ProductCards.mount` 唯一挂载口 | 还有 `mountAll` |
| v1 稿 | 67 个卡片组件 | **38 个 ChatComponent 实现 / 70 个注册实例** |

---

## 9. 接口修订（替代 10-modules 的接口签名）

### M1 StatusDeckController
- **不要 `refresh()` 无主**：`statusPhraseTicker` 每 1.2s 直调 `refreshProgressSlotMeta`，须改为调 `refresh()`；触发集合写死：slot 变化 / 进度变化 / queue 变化 / ticker
- **D1 数据源**：DSH → `live.inferenceProgress` / `setDshPhaseText`；本机 → `AgentProgressBus`（DSH 从不写它）
- **D2 数据源**：服务端 `/status.text_lines`（不本地拼文案）
- **回调改签名**：`onDeckHeightChanged(wasVisible, nowVisible)`；Host 内**先取 pinned** 再改可见性，post 后调 `onViewportChanged`
- **注册时机**：不要放 `:824`（早于 `stickToBottom` 创建与 `setAdapter`，回放会 NPE）；放 `onResume` 或把回放 post 到下一帧
- **清理**：`clear()` 不转调 `AgentProgressBus.clear()`（其 `AILogger` 静态 Handler 会让 JVM 测试崩），由 Host 注入

### M2 DshUiCatalog
- 拆 `load(Context)` + `parse(String)`（否则只能 Robolectric）
- 版本闸函数在 `ai/usage` 是包私有 → **抽公共 util**，不要复制
- `isCacheTrusted` 在"无法比较"时返回 true，与"覆盖档必须严格新于随包"相反 → 非法/缺版本判拒绝
- `versionOf` 取第一个 `"version"` → 嵌套 catalog 会读错，改为顶层键
- 补 `removeListener`；`forwards` 改三态（allow / deny / unregistered）
- `maxRows` 与甲板上限取 **min**，权威归甲板

### M3 ActionSink（前置最多）
- **`Source` 必须补到数据层**：`ComponentData.source` + 投影置值 + `deepCopyForSave`/`clone` + `focusClone` + 6 个渲染调用点
- **保持现行为**：发送失败要**回落本机回显**（现状如此），不要严格分派
- `sendComponentAction` 是**同步 HTTP（30s）**，必须移出 UI 线程并给失败回调
- `Source`/`Port` 放 `ai/chat/component` 或 `ai/remote`，实现注入（避免 `ai/chat/component→ai/tool` 新依赖边）
- **前提**：`DshProjection` 现在根本没注入 `actions`/`component_id` → M4 的验收前提不成立，先补

---

## 10. 约束修订（补充第 8–11 条）

在 00-overall §7 七条之外追加：

8. **输入区任何高度变化**都要走同一入口（`thinking_indicator`、`attachment_list`、chip 行、`queue_bar`、键盘 insets 都在改高度）。
9. **`approvals`/`questions`/`remoteCardView` 必须进 `deepCopyForSave` + `clone()`**（B11；比"新增字段同步"更具体）。
10. **`ComponentData.id` 必须非空**（空则落盘省略、恢复换号、`data#<idx>` 错位）；props 禁放二进制/大整数/大字符串（`byte[]`→`"[B@…"`、>2^53 失真、无大小上限）。
11. **`ActionSink` 先于动态卡落地**（现有路由靠 `dsh-` 嗅探，与 `T{n}` 前缀互斥，过渡期两条都要留）。

---

## 11. 规范冲突处置（择要）

| 冲突 | 处置 |
|---|---|
| 手机端仍在本地拼状态文案 | D1/D2 改读 `/status.text_lines`，Controller 只管档位/截断/显隐 |
| `/v2/view` 与事件投影两个视图源 | 明确边界：会话时间线=事件投影；`/v2/view` 只保留连接/自检静态卡 |
| `dsh_ui_catalog.json` 与 caps 双声明 | 配置表只做映射，**一律受 caps 门禁**；`ui/slot` 不在 `EVENT_CAP` 里，新 cap 名客户端未声明会被判 false → 加门会切断现网 slot，需先加 cap 声明 |
| `ActionSink` 放 `ai/tool` | 改放 `ai/chat/component` 或 `ai/remote` |
| `index.js` 拆分归属 | 不是 ROADMAP §2.1（那张表只有 Java 文件），改引架构评审 §5；且 `index.js` 是 ESM，拆文件用 `import` |
| 文档重复 | v2 降级为"调查与决策附录"，删与三份重复的章节；v1 标 superseded |
| 提交前缀 | 指南是 7 个前缀（含 `chore:`/`style:`）+ 功能分支；scope 写法视为补充 |

---

## 12. 未采信 / 存疑

| 项 | 理由 |
|---|---|
| "130dp ≈ 23% 损失" | 无测量产物，只有布局推算；改为按实际 `getHeight()` 测 |
| "56 个未提交文件" | 当时快照，现已提交 |
| 在线/本地 agent 的 `.round`/`AtomicFileText` 细节 | 未逐条核对，保留但不作为设计依据 |

---

## 13. 回滚现实

- **P0 必须单独 commit**：要动 `activity_ai_chat.xml:397-471`、`AIChatActivity`(176-178/8243/12946-12977/14383)，与 P1 共用 Activity 时 `revert` 会冲突
- P1 不是"单文件回退"：B2/B3 在 `DshProjection`/`ChatAdapter`，B4 涉及 `ActionSink`+`ComponentActions`+`ChatMessage`+Activity，B5 在插件，至少 6 个文件
- 插件回滚要**先留副本 + 重启 DSH**；仓库无版本化插件目录，新增 `ui_cards.js`/`ui_catalog.js` 要列进回滚清单
- P3 配置表：删覆盖档是唯一干净回滚

---

## 14. DSH 事件产出与插件能力（三路调查，2026-11-13）

方法：① 读 DSH 源码枚举权威类型；② 真实抓 `/events` 两段各 28s；③ 通读插件能力。
DSH 版本 `@deepseek-ai/dsh` **0.2.2-rc.2**。

### 14.1 权威规模：63 条 session 事件，插件目录覆盖 21 条（33%）

- `dsh-session/src/known-event-types.ts:22-86` 的 `KNOWN_SESSION_EVENT_TYPES` = **63 条**
- 信封 `SessionEvent` = `{type, seq, time, data, ignorable?, surfaceOp?, sourceEventSeqs?}` —— **有 seq+time，无 id**
- `EVENT_CATALOG` 命中 **21/63**；**缺 36 条可实际产出**（另 6 条无写入者）；**多 12 条**不是 session 事件

### 14.2 必须分三层（否则对账永远对不上）

| 层 | 类型 | 来源 |
|---|---|---|
| **session 事件（63）** | `system/message`、`developer/message`、`compaction/summary`、`tool/ptc-dispatch`、`team/*`、`tool-workflow/*`、`deliverables/presented`、`approval/asked`/`approval/decided` … | DSH 会话日志，带 seq |
| **宿主事件（Cordis）** | `agent/assistant-stream`（token 流真名）、`approval/request`、`user-questions/request`、`goal/activation-changed`、`schedule/changed` | `ctx.on`，非 session 事件 |
| **插件合成帧** | `stream/delta`、`stream/end`、`subagent/activity`、`approval_gone`、`ui/slot` | 插件自造（DSH 全库 0 命中） |

**实测信封层**（手机真正解析的）：`stream/delta` 802 条、`ui/slot` 35、`subagent/activity` 21、`event` 21、`view` 12、`pulse` 4、`stream/end` 3、`ready`/`state`/`end` 各 2。
其中 `ui/slot`/`view`/`pulse`/`state`/`ready`/`end` 与包装层 `event` **都不在目录里**。

### 14.3 新发现（B14–B16）

| # | 缺陷 | 证据 | 影响 | 归属 |
|---|---|---|---|---|
| **B14** | 事件覆盖仅 21/63（33%），且目录把宿主事件/合成帧混在一起 | 源码 63 条 vs `index.js:1567` 33 条 | 系统消息、压缩详情、产物、工作区变更、PTC、团队/工作流手机全看不到 | **P1** |
| **B15** | **根会话收不到工具/任务事件**：`tool/call`/`tool/result`/`job/*` 只出现在做事的子会话，手机绑定的根会话只收到合成的 `subagent/activity` | 抓包 A 窗口（根）0 条 tool/job；B 窗口（子）3/3/2 条 | 手机端工具卡/job 卡的来源其实是合成帧，不是 durable 事件 → 恢复后不可重建 | **P1** |
| **B16** | **回执缺口 1345 条**：`acked_seq=4485` vs `last_seq=5830`，而 `since=-2` 不重放历史 | 抓包 `state` 帧 | 手机冷启动会缺一大段；只能靠 `/v2/stream` 补拉 | P1 |

### 14.4 目录重做的硬要求（替代 v2 §8 的 B8）

1. 按三层分类重建 `EVENT_CATALOG`：`session` / `host` / `synth` 三个分区，各自标注 `forward`/`slot`。
2. 补 36 条可产出类型；把 12 条非 session 事件迁到 host/synth 分区。
3. **加 `plugin:` 前缀放行规则**（`ignorable:true`，走同一事件流但不在 63 条内）。
4. `noteEventType` 对 `session-log-` 前缀**静默跳过、不记未登记**（`index.js:1615`）→ 改为按前缀族登记，否则永远发现不了新日志类型。
5. 对账口径分开：**信封 `type`** 与 **内层 `event.type`** 两张表，否则 `subagent/activity`/`stream/*` 会被误判为"未出现"。

### 14.5 插件能力盘点（与设计相关的结论）

| 结论 | 事实 |
|---|---|
| 手机端能力 | v2 全套（对话/SSE/审批问答/日程计划/子代理/卡片/对账/设备管理/可选 exec） |
| 改不了电脑侧配置 | `/config`(allowExec)、`/pair`、`/pair.json`、`/balance` **仅本机回环** |
| **反向通道没有生产者** | `CMD_ALLOW` 5 工具已实现队列/poll/ack，但插件**零 LLM 工具注册**（无 `ctx.tool`），面板 `client.js` 也无入口 → 只能人工 curl |
| **没有服务端推配置** | 可推的只有 `state`/`view`/`ui/slot`/`pulse`/`approval`/`__force_resync`；能力表只能客户端拉 `/v2/hello` |
| caps 从未校验 | `cmd.ui`/`cmd.shell` 只声明无实现；`/cmd*` 只校验 token |
| 两条视图通道不一致 | `/v2/view` 走 `filterViewCards`；SSE `view` 不裁剪、无 caps 卡、无 timeline |
| 两份插件副本 | SHA256 完全一致（9 个文件），改完必须同步 `src/main/assets/remote_dsh/plugin/` |

### 14.6 对设计文档的修正（覆盖 v2 §1.10 / §8）

- M3 配置下发：**改为客户端拉取**（`/v2/hello` 版本 + 目录端点）；服务端推是可选增强，当前无通道。
- M5 动态卡：必须走 `/events` 事件；**不能等 agent 主动推**（插件无工具注册）。
- M1 的 D1：DSH 生成态来自 `live.inferenceProgress`；B15 说明根会话连工具事件都收不到，D1/D2 不应假设有 durable 事件。
- 事件目录工作量按"补 36 条 + 三层分类"重估：**P1 内 2-3 天**。

---

## 15. 开源资料对照（2026-11-13 补）

DSH 已开源：仓库 [`deepseek-ai/deepseek-harness`](https://github.com/deepseek-ai/deepseek-harness)（developer preview），文档站 [`deepseek-harness.github.io`](https://deepseek-harness.github.io/deepseek-harness/)。本机 asar 版本 `0.2.2-rc.2`。

### 15.1 `tool/result.meta` 是官方的"卡片载荷"（直接改变 M2）

> "`meta` 是 opaque 的（产出工具拥有其形状，并在 `presentResult` 里读回），但 MUST JSON-serializable：`Session.append` 用 `isJsonValue` 运行时校验，非可序列化 `meta` 在源头被拒；durable 日志在 replay 时**复现同一张卡**。"

来源：[会话子系统文档](https://deepseek-harness.github.io/deepseek-harness/reference/subsystems/session)

**结论**：动态卡不该自造 part 类型、也不该只靠客户端投影——**应优先用工具结果的 `meta`**：
- 它天生随事件落盘、天生 replay 一致；
- 非 JSON 值在源头被拒，不会出现我们发现的 `byte[]→"[B@…"` 静默损坏；
- 我们的 `DshProjection` 现在完全没读 `meta`。

### 15.2 仓外插件**不能**发自定义会话事件（证实 B14 的处置方向）

> "out-of-repo plugin cannot emit a custom session event type without making every session it touches unloadable after a restart. 持久化读路径拒绝未知事件类型，除非信封带 `ignorable: true`；但 `Session.append` 没提供设置该标记的入口，且 known-event 集合由仓库自身的 `SessionEventMap` 合并构建。"

来源：[Discussion #5474](https://github.com/deepseek-ai/deepseek-harness/discussions/5474)

**结论**：
- 插件合成的 `stream/delta`/`subagent/activity`/`ui/slot` **只能当传输帧**，不能伪装成 session 事件——与我们现状一致，但文档里要写明这条边界；
- 想新增**持久化**事件类型做不到；动态卡只能走 `meta`（§15.1）或 `components`；
- `plugin:*` 前缀事件形如"同一条事件流但 `ignorable:true`"，监听方必须放行（B14 的处置已包含）。

### 15.3 Slots 子系统：我们手机端插槽的**参考实现**

DSH 自己的 Web Client 用一套类型化 Slot 系统组合 UI，关键机制值得照搬（[Slots 文档](https://deepseek-harness.github.io/deepseek-harness/reference/subsystems/slots)）：

| 机制 | DSH 做法 | 我们的现状 |
|---|---|---|
| 基数 | `single` / `list` / `keyed` / `chain` / `hoc` 五类 | `DockSlotBus` 只有"按 id 覆盖 + 优先级排序"，等价于 `list` 的简化版 |
| 作用域 | `root` / `session-maybe` / `session` | 无作用域概念，`ui/slot` 还是**全局广播** |
| 优先级 | `single/list/keyed/hoc` 是遮蔽优先级；`chain` 是选举顺序 | 只有排序，无"遮蔽" |
| 声明前置 | 向**未声明**的 slot 注册会在插件激活时**失败**；重复声明他人 child 也失败 | 任意 id 都能注册，无校验 → B8 类漂移的温床 |
| 生命周期 | 跟随 Cordis effect：销毁 entry 即移除贡献并递归折叠子 slot | 监听器不注销（review 指出的泄漏点） |
| 组合位置 | 会话页有 `conversation.composer.dock`、`conversation.input.*`、`conversation.chat.node`（keyed 分发） | 我们的甲板位置≈`composer.dock`；part 分发≈`chat.node` |

**对 M1 的修正**：甲板应按 `single`（一个 dock）+ 内部 `list`（D3 行）建模，**注册前必须声明 slot key**（未声明直接报错，从源头阻止漂移）；并补 `scope=session`，避免 `ui/slot` 全局广播串会话。

**对 M2 的修正**：`conversation.chat.node` 的 keyed 分发正是"kind → 组件"的官方形态——我们的 `ComponentRegistry` 已等价，不必再造。

### 15.4 新增/调整的工作项

| # | 项 | 依据 | 归属 |
|---|---|---|---|
| **B17** | `DshProjection` 未读 `tool/result.meta`，动态卡没有走官方载荷 | §15.1 | P2 |
| **B18** | `ui/slot` 是全局广播、无 session 作用域，且注册无声明校验 | §15.3 | P1 |
| 调整 | M1 按 `single + list` 建模、加声明校验与注销 | §15.3 | P0/P1 |
| 调整 | M2 优先用 `meta`，`components` 仅作兜底 | §15.1 | P2 |
| 约束 | 插件合成帧不得伪装成 session 事件；`plugin:` 前缀必须放行 | §15.2 | P1 |

---

## 16. 修复进度（2026-11-13 晚）

| # | 状态 | 证据 / 说明 |
|---|---|---|
| **B5** | ✅ 已修 | SSE `pushView` 补 `normalizeCaps + filterViewCards`，与 GET `/v2/view` 同口径 |
| **B8** | ✅ 已修 | `buildView` 补齐 `schedule/subagents/plan` 三张卡；自检断言"存在 + 被 caps 裁剪"（21 passed）|
| **B9** | ✅ 已修 | `deepCopyForSave` 补拷 `remoteCardView`；`ChatHistoryManagerDeepCopyTest` 2 条 |
| **B11** | ✅ 已修 | 同补拷 `approvals/questions`；重启后 pending 审批不再消失 |
| **B14** | ✅ 已修 | 目录重建为 84 条三层 + 前缀族；渲染侧补 9 类卡（system/developer/feedback/team×5），13 类内部事件明确忽略（不再污染 unknown 计数）；`DshCardsB14Test` 5 条 |
| **B18** | ✅ 已修 | 插件侧 goal/compaction 按会话定向发送、`ui.slot` 支持 `session_id`；手机侧丢弃不属于当前会话的插槽（不再串台）|
| **B1** | ✅ 已修 | 断连时 `DockSlotBus.clear()`；甲板显隐补 `onViewportChanged`；D1 可见时 D3 只留 1 条 |
| **B12** | ✅ 已修 | `/v2/view` 不再往对话流插卡；`refreshRemoteCards`/`addRemoteCardMessage`/`remoteCardsLane` 及布局空容器已删；旧 REMOTE_CARD 消息仍按 legacy 渲染 |
| **B13** | ✅ 已修 | `safeNotifyItemChanged` 改为按消息对象重定位：归档后不再刷错行 |
| **B15** | 🟡 部分 | 合成 activity 现在带结果正文（`工具完成：…`）；**durable 卡片仍需专用 lane**，未做 |
| **B16** | ✅ 已修 | `state` 帧检测落后 >500 → 重建时间线（服务端超阈值只发 snapshot，不再静默缺段）；落后 100 的整数倍记一条日志 |
| **B17** | ✅ 已修 | `DshTimeline.Tool.meta` + `toolComponent` 投影进 props；`DshProjectionMetaTest` 2 条 |
| **B2** | ✅ 复核无需改 | `dshComponentId` 已用 `applySubId(…, 气泡 turnId)`，id 前缀与消息 turnId 一致 |
| **B3** | ✅ 已修 | 指纹改用规范化 JSON（对象键排序、数组保序）；`AssistantTurnCanonicalJsonTest` 4 条 |
| **B6** | ✅ 已修 | 镜像气泡 id 直接用 `T{n}-A{k}`（id == subId，与 turnId 同源） |
| **B10** | ✅ 已修 | 临时文件 fsync → `.bak` → 同目录 rename 原子覆盖；`ChatHistoryManagerAtomicSaveTest` 3 条 |

### 16.1 已在线验证的能力（真机）

- 事件目录：`/v2/events/catalog` → 84 条、`by_layer={session:63,host:5,synth:16}`、`unregistered=0`
- UI 目录：`/v2/ui/catalog` → `version=2026-11-13-v1`、10 个槽位、25 项 caps
- 工具：`phone_tool: true`（`phone_ui` 注册成功）
- 反向通道：`toast` / `notification` 往返 `done`，通知栏中文原文（`dumpsys notification` 取证）
- 插槽推送：`POST /v2/action {action:'ui.slot'}` 推 → 真机出现「插槽自检」行 → 空帧移除成功
- 甲板：D1 不可见时显示 2 条（`后台任务` + `子代理`），推理中只留 1 条（代码已改，待场景验证）

### 16.2 重启后已验证（2026-11-13 16:04）

- GET /v2/view?caps=status,schedule,subagents,plan → 卡片 status,schedule,subagents,plan,caps；只声明 status 时裁到 status,caps（B8 生效）
- POST /v2/action {action:'ui.slot', slot_action:'jobs'} → 返回 slot.action='jobs'，真机出现「重启后自检 · B8/槽位已生效」且排在「后台任务」之上（优先级 15）（排序 + 上限 2 条生效）
- phone_tool: true（重启后工具仍在）

### 16.3 仍未验证

- B15 的 	ext 字段：需要有子代理在跑的窗口才能看到真实 activity；当前进程 childParent.size=0，合成帧不会产生

- `slot_action`（插槽点击出口）与 B8 三卡：代码已提交，**运行实例待下次重启生效**
- B1 的"推理中只留 1 条"需要 D1 出现才能验证（DSH 路径不写 `AgentProgressBus`，需本机 agent 触发）
