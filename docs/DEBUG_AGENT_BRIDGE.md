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
| `[SUMMARY] <摘要>` | 外部提供的摘要点（默认回显形式，不回显完整指令） |
| `[PROMPT] <指令文本>` | 完整提示词本体（仅 `expose_prompt=true` 时，用户气泡渲染） |
| `[PROMPT] 已接收（未提供摘要，不回显完整指令，长度 N）` | 无摘要且未允许回显时的占位 |
| `[REJECT] <原因> HH:mm:ss` | 校验拒绝回执 |
| `[RETRY] 网络错误，3 秒后自动重连（第 N 次） HH:mm:ss` | 零输出+网络类错误触发自动重连 |
| `[RESULT] done/partial/error/timeout/init_error result=<文件> HH:mm:ss` | 任务终态回执 |

### 通道定位：正式文本通道（提示词零接触）

本调试通道的协议定位是**正式文本通道**：

- **输入**：`prompt` = 任务的**正式文本**（任务内容/用户问题），不是提示词
- **输出**：模型生成的**正式回答**（完整记录/展示）
- **提示词/系统指令**：由外部系统自行管理，**不注入本通道**——我方对提示词**零接触**（不接收、不处理、不记录），防蒸馏天然成立
- 正式文本本身不敏感 → 外部可传 `expose_prompt=true` 让调试台完整回显输入

### 防蒸馏兜底（违规保护）

若外部仍把提示词性质内容塞进 `prompt`（违反协议），通道默认**不回显、不落盘**：

- **prompt_summary 是外部自愿提供的「可见说明」**：可写指令用途、注入原因、为何要这么做等，我方原样展示——外部看到显示内容即明白，不会误会我方隐瞒
- 外部不传摘要 → 我方显示**协议说明占位**（"已接收 N 字符（正式文本通道：提示词由外部管理不注入；防蒸馏不回显，可传 prompt_summary 提供可见说明）"）
- 仅当外部显式传 `expose_prompt=true` 时才完整回显（本地自用调试）
- 完整 prompt 只在引擎内部执行，不写入任何外部可见面（调试台 UI / result 文件 / status.json / logcat）

**同一口径同时服务内部**：内部人员/组件看到「未回显」占位或说明日志时，不会被误解为功能故障——
显示层、结果文件、status.json、logcat 四处的文案统一声明这是**正式文本通道协议 + 防蒸馏兜底**，并给出两条解除路径：
- 需要可见说明 → 传 `prompt_summary`
- 正式文本/本地自用需要完整回显 → 传 `expose_prompt=true`

**内部消费语义（防止回传错误）**：status.json 新增 `prompt_type` 字段，内部读取方**必须按类型判断**，不得把摘要/占位当完整输入回传：

```json
{"status":"done","result":"result_xxx.txt","prompt_type":"full|summary|placeholder","prompt":"...","time":"..."}
```

| prompt_type | 含义 | 内部使用规则 |
|---|---|---|
| `full` | 完整输入正式文本（expose_prompt=true） | 可安全回传/复用 |
| `summary` | 外部提供的可见说明（prompt_summary） | 仅展示，**不可**当完整输入回传 |
| `placeholder` | 协议占位说明 | 仅提示，**不可**当完整输入回传 |

结果文件头部前缀同样三分：`PROMPT:`（full）/ `PROMPT_SUMMARY:`（summary）/ `PROMPT_PLACEHOLDER:`（placeholder），供文件消费方识别。

```bash
# 正式文本注入示例（不敏感，可完整回显）
adb shell am broadcast -a com.oilquiz.app.DEBUG.AGENT_EXEC -n com.oilquiz.app/.infra.AgentDebugBridge \
  --es token 7b2f328d67c2d416 --es prompt '<正式任务文本>' --ez expose_prompt true

# 防蒸馏兜底示例：外部注入提示词性质内容时，摘要写明"为何要这么做"
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
