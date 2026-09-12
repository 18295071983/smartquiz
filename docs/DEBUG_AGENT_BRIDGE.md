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
| `[PROMPT] <指令文本>` | 外部传入的提示词本体（用户气泡渲染） |
| `[REJECT] <原因> HH:mm:ss` | 校验拒绝回执 |
| `[RETRY] 网络错误，3 秒后自动重连（第 N 次） HH:mm:ss` | 零输出+网络类错误触发自动重连 |
| `[RESULT] done/partial/error/timeout/init_error result=<文件> HH:mm:ss` | 任务终态回执 |

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
| 正常任务一次成功（execute#1 → done） | ✅ 实测通过 |
| partial / retry 分支 | 代码级就位（网络状态不可控，未触发实测；分支逻辑经源码核验：引擎 abort 后 finishGeneration 复位 isGenerating） |
