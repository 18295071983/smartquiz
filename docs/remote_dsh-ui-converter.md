# 手机端 DSH 远程连接：事件 → UI 转换器重构

> 2026-11-12 · 范围：`com.oilquiz.app.ai.remote`（新增）+ `AIChatActivity` + `DshHistoryImporter`
> 结论：旧转换器不是"写得糙"，是**分层不对**——把传输、领域模型、卡片构造、UI 副作用、
> 断点游标压在一个 Activity 方法里，而且写的那份模型**根本不是渲染真值**。

---

## 1. 旧设计的问题（逐条可指认）

| # | 问题 | 证据 / 后果 |
|---|---|---|
| 1 | **单方法上帝对象**：`applyDshEvent` 约 380 行 if-else，塞在 1.4 万行的 `AIChatActivity` 里 | 改一种事件要动 Activity；没有任何单测（转换规则只能真机看） |
| 2 | **写错了真值源**：`rebuildDshParts()` 维护 `msg.assistantTurn.parts`，但渲染层每次绑定都用 `AssistantTurn.fromMessageForParts(msg)` 从 `roundSnapshots + components + content` **现建** parts | 那份 parts 是死数据。多步回合在手机上永远是"一大段思考 + 一大段正文 + 末尾一堆卡"，与电脑端的分步交错不一致 |
| 3 | **同一套规则写两遍**：实时 `applyDshEvent` 一份，历史导入 `DshHistoryImporter.buildMessages` 另一份 | 导入的会话**没有工具结果卡、待办卡、工作流卡**；工具状态还被硬编码成 `success` |
| 4 | **隐式全局状态**：`upsertDshComponent()` 忽略入参 `live`，直接改字段 `dshLiveMessage`；组件 id 用 Activity 的 `currentTurnId` 发号 | 补拉复用气泡/多气泡并存时，卡片会写到"另一条消息"上；id 与消息对不上 |
| 5 | **游标先推进、渲染后执行**：方法开头就把 `dshLastSeq` 推到当前事件，UI 异常被 `catch (Throwable ignored)` 吞掉；补拉路径还在后台线程先 `setLastSeq` 再 post 渲染 | 渲染失败/进程被杀 → 这条事件**永久丢失**（下次 `since` 已经越过它） |
| 6 | **静默丢弃未知事件**：只认约 25 种类型，其余无日志、无计数 | 电脑端新增事件时，手机端表现为"什么都没发生"，排查只能靠抓包 |
| 7 | **卡片构造散落在转换分支里**：`jput/jadd` + 每个事件一段 props 拼接 | 卡片文案/字段无法复用、无法测；`todo` 反复写要靠"按 key 删了再加"手工兜 |

还有一个直接 bug：`DshTool.call` 字段从未赋值，`rebuildDshParts()` 里 `if (tool.call == null) continue;`
让工具 part 永远进不了 parts —— 即使有人去读那份 parts，也看不到工具。

---

## 2. 新分层

```
        ┌────────────────────────── UI（AIChatActivity） ─────────────────────────┐
        │  applyDshEvent(raw)  ≈ 40 行：归约 → 副作用 → 投影 → 推游标              │
        └───────────────┬──────────────────────────────────────────────┬─────────┘
                        │ DshEvent（信封解析一次）                       │
        ┌───────────────▼────────────────┐              ┌──────────────▼─────────┐
        │ DshTimeline（纯 JVM 归约器）    │              │ DshProjection（投影）   │
        │  turn/step → Step(text/think/  │  ──────────► │  roundSnapshots（顺序） │
        │  tools)；Card(key/type/props)  │              │  components（卡片）     │
        │  Update（改了什么 + 副作用）    │              │  content/thinking       │
        └───────────────┬────────────────┘              └────────────────────────┘
                        │ DshCards（事件 → 卡片 props，纯函数）
                        ▼
                未识别事件计数（unknownTypes）
```

新增 4 个类，全部在 `com.oilquiz.app.ai.remote`，**不依赖 Android**：

| 类 | 职责 | 不做什么 |
|---|---|---|
| `DshEvent` | 信封解析（type/seq/id/ts/data/turn/step），保留 `raw` 供 approval 顶层字段 | 不解释事件语义 |
| `DshTimeline` | 唯一归约器：位置归组、工具 call/result 配对、seq 去重、卡片 upsert、审批/反问副作用、进度文案 | 不碰 View/消息 |
| `DshCards` | 事件 → 卡（`todo_card`/`file_list`/`steps`/`alert`/`markdown_doc`），含"同 key 覆盖"与按 turn 聚合 | 不决定卡片顺序 |
| `DshProjection` | 时间线 → `roundSnapshots + components + content/thinking`，组件 id 由调用方注入 | 不维护第四份 parts |

三条消费路径共用同一套规则：

- **实时同步**：`subscribeEvents` → `applyDshEvent` → 投影当前气泡；
- **断点补拉**：同一条时间线实例增量归约（`seq` 去重），整批落屏后才持久化游标；
- **历史导入**：`DshHistoryImporter` 一次性回放事件 → 同一 `DshTimeline`/`DshProjection`。

---

## 3. 渲染契约：为什么写 `roundSnapshots` 而不是 parts

App 的 parts 渲染器（`ChatAdapter.bindWithParts`）在每次绑定时：

```
fromMessageForParts(msg)
  → fromMessage(msg)            // 读 roundSnapshots（或 contentRoundBounds）切出每轮
  → fillToolPartsFromComponents // 按 toolCallId 回填工具名/参数/结果
  → appendDataParts             // components 里非 tool_call 的组件 → data part
  → groupTools                  // 连续 tool_call 折成「已运行 N 个工具」
```

也就是说**顺序真值是 `roundSnapshots`**，卡片真值是 `components`。把 DSH 的每一步投影成一条
snapshot（`content=该步正文`、`thinking=该步思考`、`toolCallIds=该步工具`），就自动得到
"思考 → 正文 → 工具卡 → 下一步思考…"的交错顺序，和电脑端一致。

同时投影会 `msg.assistantTurn = null`：该字段存在时 `bindMessageContent` 会调
`applyToMessage()` 反向覆盖 `content/bounds`，等于再开一个写者。

> 数据卡（待办/文件/工作流/告警）仍由 `appendDataParts` 追加在 parts 末尾。
> 这是 App 现有的 parts 能力边界，不是本次引入的；要交错必须改 parts 构建器，另开一轮。

---

## 3b. 同批修的：`RemoteCardView`（服务端 view → 原生卡片的转换器）

「远程连接 → 自检」与聊天页 `REMOTE_CARD` 用的都是 `RemoteCardView`。它不是崩溃型缺陷，
而是"看起来能用、信息丢了"：

| 问题 | 后果 | 修法 |
|---|---|---|
| 底色/描边写死 `0x0A000000` / `0x1A000000` | 暗色主题下卡片整张消失（4% 黑贴在深色底上等于透明） | 读主题 `colorSurfaceVariant` / `colorOutline`，暗色有兜底色 |
| 无 title 时直接显示 `kind` 原文 | 用户看到 "checks" / "timeline" 这种英文内部词 | `kind → 中文` 兜底（自检/连接/会话/待处理/最近消息） |
| 忽略 `checks[].hint` | 自检失败只看到现象、看不到"怎么办" | detail + 「建议：hint」两行 |
| 忽略卡片级 `detail` / `error` | 会话读取失败时是一张只有标题的空卡 | 原因按 error 色渲染 |
| 时间线消息硬截 3 行 | 手机上永远只看到半句话 | 点击展开/收起（3 行 ↔ 全文） |
| 每次轮询 `removeAllViews` | 展开态被清、滚动位置跳动 | 内容签名未变直接跳过重建 |
| `appendBreath` 每次追加 | 轮询几次后一屏"呼吸"卡 | 复用同一张，只更新文字 |
| `approval_id` 被丢弃 | 待处理卡不可交互 | 挂到行 tag，暴露 `RemoteCardView.approvalIdOf(view)` 给页面接 |

---

## 3c. 接线前先补的基础设施（2026-11-12 第二轮）

上一轮留了两条"妥协"：数据卡只能堆末尾、审批只能弹窗。根因不在转换器，在平台层缺两样东西；
这轮按"先基建、再接线"补齐：

| 基础设施 | 缺什么 | 补法 | 谁受益 |
|---|---|---|---|
| **卡片定位** | `AssistantTurn.appendDataParts` 只有 `add(末尾)`，卡片没有"我在第几步"的信息 | `ComponentData.round`（Gson 持久化；0=旧行为）+ `insertPartsByRound()`：按 part id 的 `-R{n}` 轮次键插到该轮正文/工具之后 | DSH 待办/文件/工作流/告警卡；后续任何带轮次的数据卡 |
| **审批进时间线** | `ApprovalPartRenderer` 早就写好了，但**没有生产者**：没人往 parts 里放 approval part，`ChatPartsHost` 的回调在适配器里是空实现 | `ChatMessage.approvals`（持久化）+ `AssistantTurn.appendApprovalParts()` + `ChatAdapter.ApprovalDecisionListener` + 页面回传；`bindWithParts` 放行 `TYPE_APPROVAL` | DSH 审批卡（允许/拒绝就地生效，失败回滚）；本机引擎审批可复用同一通道 |
| **降级可观测** | parts 接管失败时静默回落 legacy，只有"看起来变旧了" | `bindWithParts` 在 type 未接管/构建失败时打 `ChatAdapter.parts` 日志（type/id），不再静默 | 所有 parts 渲染问题 |
| **能力协商** | `hello()` 是 GET、没人调；服务端不知道客户端能渲染什么；客户端不知道服务端新增了什么 | 客户端 `RemoteCaps`（14 项）随 `/v2/hello`、`/v2/view`、`/events` 上送；插件 `caps.js` 纯函数按 caps 裁剪卡片/审批事件；`/v2/view` 回显「能力协商」卡（客户端 N 项 / 未识别 / 缺少），legacy 客户端不裁 | 双端升级节奏不同时不再"静默丢事件/白传流量" |

契约约束：`RemoteCaps.ALL` 与插件 `caps.js` 的 `SERVER_CAPS` **必须同名**，
`RemoteCapsTest` 会读 `caps.js` 做双向比对（缺一边就红）；插件侧另有 `caps_selftest.js`（node 7 条）。

---

## 4. 事件映射（手机端认这些）

| 事件 | 产出 | 备注 |
|---|---|---|
| `turn/start` | 阶段=思考中 | |
| `step/start` / `step/end` | 阶段文案「第 N 步 · 思考中 / 完成」 | |
| `assistant/message` | 该步 `text` + `reasoning`（覆盖式，取累计值） | reasoning/thinking 都算思考 |
| `tool/call` | 该步一条工具（卡片 status=running） + 「正在运行 X」 | `callId` 空则忽略 |
| `tool/result` | 回填工具 result/error（卡片 success/failed） | 先到先丢，不落幽灵卡 |
| `turn/end` | 本轮收尾：投影 + 落盘 + 推游标 | |
| `approval` / `approval_gone` | UI 动作（弹/收审批框） | 不产生消息 |
| `question` / `question_gone` | UI 动作（只读提示/收） | |
| `todo/write` | `todo_card`（key=`todo-{turn}`，覆盖） | |
| `deliverables/presented` | `file_list` | |
| `job/registered` | `alert`，按 turn 聚合成一张 | 计数在 `DshCards` 内，seq 去重防重放翻倍 |
| `command/run` | `alert` | |
| `tool-workflow/*` | `steps`（run 状态在 `DshCards` 内） | |
| `subagent/catalog` / `llm/retry` / `assistant/attempt` / `compaction/*` | `alert` / `markdown_doc` | |
| 其它 | 无 UI，`unknownTypes[type]++` | 可观测"新事件被静默丢掉" |

---

## 5. 游标与幂等

- `DshTimeline.lastSeq` 只在事件成功归约后前进；`Update.seq` 回传 Activity。
- Activity 在**投影落屏之后**才 `RemoteDshTool.setLastSeq(...)`（补拉整批在同一个
  `runOnUiThread` 里做完再存），归约/投影抛异常时游标不动，下次 `since` 还能补回。
- `seq > 0 && seq <= lastSeq` 直接忽略：服务端重连会从 `since` 重放，否则
  `job/registered` 这类计数事件会被重复累加。
- 投影是"整体重建 + 稳定 id"，重复投影同一时间线结果一致，不会重复插卡/换 id。

---

## 6. 与旧数据的兼容

- 旧消息没有 `roundSnapshots`：`fromMessage` 回落到 `content` 单段 + `thinkingContent`，
  与重构前的观感一致，不迁移、不改落盘格式。
- 新消息同时写 `content/thinkingContent`（搜索、复制、旧渲染路径仍可用）。
- 导入消息的组件 id 用 `dsh-I{n}`（一次性导入不需要参与 `ChatIdDispatcher` 的回合序号），
  实时同步的组件 id 仍走 `ChatIdDispatcher`（`Tn-Fn` / `Tn-Cn`），同一 key 复用。

---

## 7. 验证

```powershell
# 纯转换层 + 导入一致性
.\gradlew.bat --offline testDebugUnitTest --tests "com.oilquiz.app.ai.remote.*" --tests "com.oilquiz.app.ai.tool.DshHistoryImporterTest"
# 全量单测
.\gradlew.bat --offline testDebugUnitTest
```

覆盖点（`DshTimelineTest` / `DshProjectionTest` / `DshHistoryImporterTest`）：

1. reasoning 与正文分流；阶段文案；
2. 无 turn/step 的事件沿用上一步（不再重置到 turn 1）；
3. 工具 call/result 合成一张卡；result 先到被丢弃；失败标记；
4. 待办同 turn 覆盖不堆积；后台任务按 turn 聚合；
5. 同 seq 重放不重复计数；seq 只增不减；
6. 未识别事件计数；
7. 审批事件产出 UI 动作而非消息；
8. 投影后 `AssistantTurn.fromMessageForParts` 的 part 顺序为
   `reasoning → text → tool_group → 下一步…`；
9. 投影幂等（重复投影内容/条数/组件 id 不变）；
10. 导入一条会话与实时形状一致，且不产生空气泡；
11. 卡片渲染（`RemoteCardViewTest`，Robolectric）：kind 中文兜底、hint/detail 可见、
    同内容不重建、呼吸卡不叠加、时间线可展开/收起；
12. 卡片定位（`AssistantTurnPositionTest`）：`round` 卡插到该轮之后、`round=0` 仍追加末尾、
    纯工具步的卡跟在工具组后面；
13. 审批 part：pending/approved 状态、按轮次排序、无轮次追加末尾；
14. 能力协商契约（`RemoteCapsTest`）：客户端 14 项与 `caps.js` 双向一致、hello body 形状；
15. 插件 `caps.js` 纯函数（`caps_selftest.js`，node 7 条）：legacy 不裁、按 kind 裁卡、
    审批事件按 parts.approval 过滤、未知能力不静默。

---

## 8. 仍待做（与本次边界）

| # | 项 | 说明 |
|---|---|---|
| 1 | ~~数据卡交错进 parts~~ | ✅ 已做：`ComponentData.round` + `insertPartsByRound`（粒度=模型步骤） |
| 2 | ~~审批卡进时间线~~ | ✅ 已做：`ChatMessage.approvals` → approval part → 就地决策 → 回传电脑端 |
| 3 | 文案由服务端派生 | 「第 N 步 · 正在运行 X」仍在客户端拼，与 `/v2/view` 的服务端文案未合并 |
| 4 | 反向命令的 caps 门禁 | `cmd.ui` / `cmd.shell` 只做了声明，`/exec` 与 `cmd_enqueue` 尚未按设备 caps 逐条强制 |
| 5 | 真机回归 | 需在手机上跑一轮含多工具/待办/工作流/审批的同步回合：看交错顺序、审批卡按钮、失败回滚 |
| 6 | 插件真机端 | `caps.js` 已进 assets，但**运行中的电脑端插件需重新加载**后才会按 caps 裁剪（`node --check` 与 `caps_selftest.js` 已过） |
