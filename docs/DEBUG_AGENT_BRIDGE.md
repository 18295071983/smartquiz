# Agent 调试控制台 & 外部注入桥 — 协议文档

> 主应用（com.oilquiz.app）内置的 Agent 调试工具链：
> 外部注入 → 自动跳转调试台 → 双流实时观测 → 状态回执。
> 调试台全部走 chatkit 全局组件（ChatAdapter / TokenStatsBar / InferenceStateManager / ToolResultInterpreter）。

---

## 1. 外部注入通道（AgentDebugBridge）

类：`com.oilquiz.app.infra.AgentDebugBridge`
持久化：SharedPreferences `agent_bridge`（开关 + token）

### 广播

| 动作 | 参数 | 说明 |
|---|---|---|
| `com.oilquiz.app.DEBUG.AGENT_ENABLE` | `token`, `enable`(bool) | 开关外部注入通道（**也必须带 token**） |
| `com.oilquiz.app.DEBUG.AGENT_EXEC` | `token`, `prompt`, `session`(可选), `max_tokens`(可选), `thinking`(可选) | 执行一次 Agent 任务 |
| | `prompt_summary`(可选) | **防蒸馏摘要点**：外部主动提供的指令摘要，我方只显示/记录摘要，不回显完整 prompt |
| | `expose_prompt`(可选, bool) | 显式允许完整回显 prompt（调试自用；默认不回显） |

- Android 8+ 必须显式指定组件：`-n com.oilquiz.app/.infra.AgentDebugBridge`
- Token 在调试台页面显示/复制（默认 `7b2f328d67c2d416`，首次随机生成后持久化）
- 并发锁：同一时间只允许一个任务；120s 超时仅释放锁（不强制中断引擎）
- 校验拒绝（token 不匹配 / 通道未开 / prompt 为空 / 已有任务）会发 `[REJECT]` 到原始指令流

### 实测命令（PC 端）

```bash
adb shell am broadcast -a com.oilquiz.app.DEBUG.AGENT_ENABLE -n com.oilquiz.app/.infra.AgentDebugBridge \
  --es token 7b2f328d67c2d416 --ez enable true

adb shell am broadcast -a com.oilquiz.app.DEBUG.AGENT_EXEC -n com.oilquiz.app/.infra.AgentDebugBridge \
  --es token 7b2f328d67c2d416 --es prompt '你好' --ez thinking true
```

---

## 2. 双流结构（AgentDebugActivity）

调试台两个独立 RecyclerView + ChatAdapter（chatkit 渲染）：

| 流 | 显示内容 | 事件来源 |
|---|---|---|
| **对话流**（上方） | 执行全过程：用户指令、助手 token 流式正文、思考区、工具调用卡片、工具结果、步骤、完成/错误 | `onEvent`（引擎执行事件） |
| **原始指令流**（下方，注入时自动展开） | **只显示外部传入的数据**：注入元数据、提示词本体、校验拒绝、重连、结果回执 | `onRawEvent`（外部输入/回执） |

### 原始指令流协议行（onRawEvent）

| 行 | 含义 |
|---|---|
| `[ENABLE] 外部注入通道 -> ON/OFF HH:mm:ss` | 通道开关回执 |
| `[INJECT] action=AGENT_EXEC token=OK session=.. max_tokens=.. thinking=.. HH:mm:ss` | 注入请求元数据 |
| `[PROMPT] <指令文本>` | 完整正式文本（默认回显，用户气泡渲染；防蒸馏模式下外部传 prompt_summary 才切摘要） |
| `[SUMMARY] <摘要>` | 外部主动声明的敏感摘要（prompt_type=summary） |
| `[REJECT] <原因> HH:mm:ss` | 校验拒绝回执 |
| `[RETRY] 网络错误，3 秒后自动重连（第 N 次） HH:mm:ss` | 零输出+网络类错误触发自动重连 |
| `[RESULT] done/partial/error/timeout/init_error result=<文件> HH:mm:ss` | 任务终态回执 |
| `[ANSWER] <模型最终回答单行摘要 ≤300 字符>` | 最终回答直达（AI 消息渲染，Markdown 生效） |

### 事件缓冲回放（修复"注入开头内容丢失"）

广播 `startActivity` 是异步的，调试台 `onResume` 注册 listener 前注入事件会丢。桥内三通道缓冲（event/raw/state，上限 200/200/50），`setListener` 时按 states→events→raw 顺序回放——注入即见全貌。

### 对话流多轮回答分段（回答树）

所有 TOKEN 原追加到同一个 AI 消息，多轮输出被合并。现**工具调用边界封口**：

```
用户消息 → AI 消息 1（第一轮输出）→ 工具卡片（▶/✔）→ AI 消息 2（第二轮输出）→ …
```

`▶ 工具调用` 事件触发 `s.ai = null`（+清思考），下一轮 TOKEN 到达 `ensureAi` 自动新开 AI 消息——每轮回答独立显示，思考随轮次归属。

### 通道定位：正式文本通道（默认完整可见）

本调试通道的协议定位是**正式文本通道**：

- **输入**：`prompt` = 任务的**正式文本**（任务内容/用户问题），**默认在调试台/结果文件/status.json 完整可见**
- **输出**：模型生成的**正式回答**（完整记录/展示）
- **提示词/系统指令**：由外部系统自行管理，**不注入本通道**——我方对提示词**零接触**（不接收、不处理、不记录）
- 正式文本本身不敏感 → 注入即显示，外部无需额外参数

### 防蒸馏模式（外部主动声明）

外部若仍注入**提示词性质内容**（违反"不注入提示词"的协议），**传 `prompt_summary` 即声明敏感**，我方切到防蒸馏模式：

- **prompt_summary = 外部自愿提供的「可见说明」**：可写指令用途、注入原因等，我方只显示该摘要，不回显完整内容
- 传了 `prompt_summary` 但需要强制完整回显 → 叠加 `expose_prompt=true`
- 完整 prompt 只在引擎内部执行，不写入任何外部可见面（调试台 UI / result 文件 / status.json / logcat）

**内部消费语义（防止回传错误）**：status.json 的 `prompt_type` 字段让内部读取方**按类型判断**，不得把摘要当完整输入回传；`result_text` 直接携带模型最终回答（≤2000 字符，超长截断），外部无需读文件即可看到结果：

```json
{"status":"done","result":"result_xxx.txt","prompt_type":"full|summary","prompt":"...","result_text":"模型最终回答...","time":"..."}
```

| 字段 | 含义 | 内部使用规则 |
|---|---|---|
| `prompt_type` | `full`（完整正式文本）/ `summary`（外部声明敏感内容摘要） | `summary` 仅展示，**不可**当完整输入回传 |
| `result_text` | 模型最终回答 / 部分输出尾部 / 错误信息（≤2000 字符截断） | 可直接展示/回传 |
| `result` | 完整过程结果文件（result_<ts>.txt，含思考/步骤/工具/最终回答） | 审计用 |

结果文件头部前缀同样区分：`PROMPT:`（full）/ `PROMPT_SUMMARY:`（summary），供文件消费方识别。

```bash
# 正式文本注入（默认完整可见，无需额外参数）
adb shell am broadcast -a com.oilquiz.app.DEBUG.AGENT_EXEC -n com.oilquiz.app/.infra.AgentDebugBridge \
  --es token 7b2f328d67c2d416 --es prompt '<正式任务文本>'

# 防蒸馏模式：外部注入提示词性质内容时传 prompt_summary 声明敏感
adb shell am broadcast -a com.oilquiz.app.DEBUG.AGENT_EXEC -n com.oilquiz.app/.infra.AgentDebugBridge \
  --es token 7b2f328d67c2d416 --es prompt '<提示词性质内容>' \
  --es prompt_summary '系统级安全指令（含内部规则）。为避免提示词蒸馏，我方不暴露完整内容，仅提供此说明供调试台展示'
```

对话流事件行（onEvent）：`PROMPT:` `TOKEN:` `THINK:` `[STEP]` `▶ 工具调用:` `✔ 工具完成:` 等。

---

## 3. 回调机制（外部调用方可观测的四通道）

1. **UI 实时**：注入自动跳转调试台，双流 + 状态胶囊 + TokenStatsBar 实时刷新
2. **结果文件**：`/sdcard/Android/data/com.oilquiz.app/files/agent_bridge/result_<ts>.txt`（完整过程 + 最终答案）
3. **状态回执**：同目录 `status.json` —— 外部轮询标准接口：

```json
{"status":"running|done|partial|error|timeout","result":"result_20260913_051818.txt","prompt":"...","time":"2026-09-13 05:18:20"}
```

```bash
adb shell cat /sdcard/Android/data/com.oilquiz.app/files/agent_bridge/status.json
```

4. **logcat**：`adb logcat -s AgentDebugBridge`（execute#N / retry / done / error / partial / timeout）

---

## 4. 错误处理三层判定（不粗暴判网络问题）

引擎每轮流式推理，桥层按「是否已输出 token」分流：

| 场景 | 判定 | 处理 |
|---|---|---|
| 已输出思考/正文 token 后断连 | **部分完成**（连接是通的，仅流式中断） | 保留全部已生成内容，追加 `=== PARTIAL ===`，status 写 `partial`，**不重试** |
| 零输出 + 网络类错误（connection/abort/socket/timeout/网络…） | 真·网络问题 | **自动重连**：复用同一引擎实例（保留 messageHistory/thinkingChain 断点续传），3s 后重试，最多 2 次 |
| 其他错误 / 重试耗尽 | 错误 | status 写 `error` |

- 引擎侧超时保护：`readTimeout=120s` + idle 活动刷新（每个 token 到达刷新计时），**长思考/慢模型不会因超时被误判**
- 重连/部分完成均通过 `[RETRY]`/`[RESULT] partial` 回执到原始指令流，外部调用方可见

---

## 5. 调试台接入方式

- **入口**：AI 服务页「打开调试控制台」按钮；外部注入自动跳转
- **本地运行**：调试台底部输入框直接运行（AgentSession 标准接入，同一套事件/UI/统计）
- **原始流自动展开**：外部数据到达时面板自动展开

---

## 6. 本地 Agent 标准接入（AgentSession）

```java
AgentSession session = AgentSession.create(context);
session.setCallback(new AgentCallback() { /* onToken / onThinkingToken / onToolCallStart /
    onToolCallComplete / onStepUpdate / onComplete / onError */ });
session.start(prompt, 4096, true);   // maxTokens, enableThinking
session.stop(); session.shutdown();  // onDestroy 必须 shutdown
```

---

## 7. 测试清单（真机实证记录）

| 场景 | 结果 |
|---|---|
| 注入 → 自动跳转调试台 → 双流显示 | ✅ 实测通过 |
| status.json running→done / error 回写 | ✅ 实测通过 |
| 原始指令流隔离（无 TOKEN/STEP 混入） | ✅ UI dump 实证 |
| `[INJECT]`/`[PROMPT]`/`[RESULT]` 渲染 | ✅ 实测通过 |
| 防蒸馏：prompt_summary 只显示摘要、完整指令不入 status/result 文件 | ✅ 实测通过（status 显示摘要、结果文件仅 PROMPT_SUMMARY） |
| 正常任务一次成功（execute#1 → done） | ✅ 实测通过 |
| partial / retry 分支 | 代码级就位（网络状态不可控，未触发实测；分支逻辑经源码核验：引擎 abort 后 finishGeneration 复位 isGenerating） |
