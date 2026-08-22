# 本地推理引擎统一 JSON 协议改造方案

> 版本：v1.12（实施决策版）  
> 日期：2026-08-22  
> 状态：完全体方案（十轮审查 + 收敛终检 + 实施拍板；v1.0 审查结论见 `review.md`，修订与决策记录见 §12.2）

---

## 一、背景与现状

### 1.1 项目现状

SmartQuiz 本地 Agent 已具备完整的推理基础设施（**注意：该链路当前处于禁用/休眠状态，见下方"1.1.1 激活状态"**）：

| 层级 | 组件 | 能力 |
|---|---|---|
| C++ | `native-lib.cpp` | `generateWithTools` 已走通 `common_chat_templates_apply` → 生成 → `common_chat_parse` 全链路 |
| JNI | `nativeGenerateWithTools` | onToken / onComplete / onToolCalls / onReasoning 四回调已通 |
| Java | `LlamaHelper` | JNI 封装，`generateWithTools(roles, contents, toolsJson, ...)` |
| Java | `AgentLoopEngine` | ReAct 单循环、智能工具选择、预算管理、循环保护 |
| Java | `AgentSoftwareLayer` | 单线程 executor、回调桥接 |
| Java | `AgentChatHandler` | UI 回调桥接 |
| UI | `AIChatActivity` / `ChatAdapter` | 流式渲染、工具调用气泡、思考显示 |

#### 1.1.1 激活状态（R3-1，方案级前提）

第三轮审查反向追踪 `AgentLoopEngine` 全部调用方，确认**当前没有任何活跃代码路径会执行本地 Agent**：

| 上游 | 状态 | 证据 |
|---|---|---|
| `AgentChatHandler.softwareLayer` | 死代码：构造 + setCallback 后无 processMessage 调用 | AgentChatHandler.java:48/67/68 |
| `AgentExecutionEngine` | 从未被实例化（全仓库仅本文件内引用） | AgentExecutionEngine.java |
| `AgentRouter.execute` | 仅在线引擎（"本地模型已被上层降级普通对话"） | AgentRouter.java:71-81 |
| `AIChatActivity.forceRunLocalAgent` | 只显示"🚫 本地 Agent 已禁用" | AIChatActivity.java:4357-4359 |

**含义**：本方案改造的对象（AgentLoopEngine）目前是休眠代码。方案隐含目标 = **为复活本地 Agent 做准备**。实施前需确认复活路径（§3.1.1），阶段 3 联调必须先复活一个入口。

### 1.2 核心问题

| # | 问题 | 影响 | 位置 |
|---|---|---|---|
| P0-1 | `tool_choice` 硬编码 `REQUIRED` | 每轮都强制调工具，模型无法正常收尾给回答 | `native-lib.cpp:2293` |
| P0-2 | KV cache 每次软清除 | 每轮 Agent 循环重新 eval 完整历史，11 tokens/s 下单工具调用需 3-5 分钟 | `native-lib.cpp:1953-1961` |
| P0-3 | tool_call JSON 逐 token 发 onToken | UI 显示裸 JSON 片段，体验割裂 | `generateWithTools` wrappedCallback |
| P0-4 | 最终回答假流式 | `streamDirectAnswer` 按 4 字符切片模拟，不是真正 LLM 生成 | `AgentLoopEngine.java:768` |
| P1-1 | 回调碎片化 | onComplete / onToolCalls / onReasoning 分三次发，**实测顺序固定为 onComplete → onToolCalls → onReasoning，与 `LlamaHelper.java:1386` 的 javadoc（"onToolCalls 在 onComplete 之前"）矛盾**，Java 需 latch + holder 缓冲防竞态 | JNI 层（`native-lib.cpp:5882/5977/5994`） |
| P1-2 | JNI 样板代码多 | 手动 new ArrayList / SetObjectField，60+ 行 | `native-lib.cpp:5933-5985` |
| P1-3 | 工具执行走 onlineToolManager | 本地 Agent 应该用本地 toolManager，返回类型需适配 | `AgentLoopEngine.java:309` |
| P1-4 | 消息结构扁平 | C++ 只收 `vector<pair<role,content>>`，不支持 assistant.tool_calls / tool.tool_call_id | `generateWithTools` 入参 |
| P1-5 | 生成失败错误被吞（R7-2） | `wrappedCallback`（`native-lib.cpp:2330-2343`）对 `generateStream` 的失败回调 `callback("", true, error)` 三个分支全不命中，error 丢失，Java 收到空 onComplete，用户只见泛化兜底 | `generateWithTools` wrappedCallback |

> **已确认非问题**：采样器（`native-lib.cpp:1908-1915`）已正确实现 temperature/topP/topK 链，非 greedy 硬编码。`llama-bridge.cpp`（391 行，独立静态 model/ctx，无 `common_chat` 依赖）虽在 `CMakeLists.txt:191` 参与编译，但行为上不影响主路径。

### 1.3 已有能力确认（查证结果）

llama.cpp 版本的 `common_chat_msg`（`common/chat.h:81`）完整支持：

```cpp
struct common_chat_msg {
    std::string role;
    std::string content;
    std::vector<common_chat_msg_content_part> content_parts;
    std::vector<common_chat_tool_call> tool_calls;   // ✅ 原生支持
    std::string reasoning_content;
    std::string tool_name;
    std::string tool_call_id;                        // ✅ 原生支持
};
```

现成解析函数（无需手动写，已逐行核实存在，其中 4 个已在 `generateWithTools` 生产路径使用并通过编译）：
- `common_chat_msgs_parse_oaicompat(json)` — OpenAI 格式 messages 直接转（`common/chat.cpp:372-471`，**完整支持 assistant.tool_calls / tool.tool_call_id / reasoning_content**）
- `common_chat_tools_parse_oaicompat(json)` — tools 解析（`common/chat.cpp:532+`）
- `common_chat_tool_choice_parse_oaicompat(str)` — "auto"/"required"/"none" 解析（`common/chat.cpp:344-355`，非法值 throw）
- `common_chat_parse(text, is_partial, params)` — 支持 `is_partial=true` 增量解析（`common/chat.cpp:3427-3431`，内部转 PEG 解析）
- `common_chat_msg_diff::compute_diffs()` — 两次解析结果做 diff（`common/chat.h:137`）

> **已核实**：`common_chat_templates_inputs`（`common/chat.h:250-262`）含 `tool_choice`（默认 AUTO）/ `parallel_tool_calls` / `add_generation_prompt` / `use_jinja` / `enable_thinking` 全部字段；`common_chat_parser_params` 含 `parse_tool_calls`（`common/chat.h:288`，`native-lib.cpp:2353` 已用）。

---

## 二、设计目标与原则

### 2.1 设计目标

1. **统一 JSON 协议**：Java ↔ C++ 全部用 JSON 交互，两边各自用 JSON 解释器解析
2. **模板全托管**：Java 只传结构化 messages + tools，C++ 用 `common_chat_templates_apply` 自动格式化
3. **tool_call 完整发送**：不逐 token 发 tool_call JSON，收集完整后统一发
4. **流式文本正常发**：非 tool_call 的自然语言回答照常流式输出
5. **UI 零改动**：新协议在 AgentLoopEngine 内部消化，转成现有 LoopCallback 出去
6. **最小侵入**：复用现有 generateWithTools 核心逻辑，旧接口全保留可回退

### 2.2 设计原则

- **旧接口不删**：`generateWithTools` / `nativeGenerateWithTools` 保留，新接口跑稳后再下线
- **分层清晰**：C++ 负责推理+解析，Java 负责循环调度+工具执行，UI 负责渲染
- **可观测**：每轮推理输出完整日志（messages 数、tools 数、tool_choice、生成 token 数、解析结果）
- **容错优先**：JSON 解析失败、tool_call 格式错误、模型空输出都有降级路径

---

## 三、整体架构

### 3.1 改造后调用链

```
AIChatActivity (UI, 零改动)
  ↑ AgentChatCallback
AgentChatHandler (桥接, 微调：复活入口 + 开关，R3-1)
  ↑ AgentCallback
AgentSoftwareLayer (桥接, 微调：cancel 桥接 stopGeneration，R3-2)
  ↑ LoopCallback
AgentLoopEngine (核心改造)
  ├─ 构建 messages JSON
  ├─ LlamaHelper.chatJson(json, JsonCallback)  ← 新接口
  │    └─ nativeChatJson → C++ chatJson
  │         ├─ common_chat_msgs_parse_oaicompat
  │         ├─ common_chat_templates_apply (模型内置模板)
  │         ├─ generateStreamIncremental (KV 增量, 采样器已正确无需改)
  │         ├─ common_chat_parse (结束后解析)
  │         └─ 统一 onJson 回调
  ├─ 解析 onJson: token/tool_call/reasoning/complete/error
  ├─ 有 tool_call → 执行本地工具 → 结果追加 messages → 循环
  └─ 无 tool_call → complete.content 为最终回答
```

> **R3-1 说明（当前链路休眠）**：如上调用链描述的是**改造后 + 复活后**的目标状态。现状 `AgentChatHandler.startAgentLoop` 只路由在线引擎（AgentRouter 仅 OnlineAgentEngine），本地 Agent 链无活跃入口（§1.1.1）。改造前需接通复活入口（§3.1.1）。

#### 3.1.1 复活入口（R3-1）

| 候选 | 改动量 | 说明 |
|---|---|---|
| **a) 聊天气泡桥接（推荐）** | 小 | `AgentChatHandler.startAgentLoop` 在本地模型时调 `softwareLayer.processMessage(message)`；桥接回调已就绪（AgentChatHandler.java:68-134），`AIChatActivity.AgentCallbackImpl` 已能消费 onToken/onThinkingToken/onToolCall/onComplete |
| b) 执行面板（AgentExecutionEngine） | 中 | 面板事件链路（ExecutionEvent/AgentExecutionView）设计完备但引擎从未实例化，需在 UI 侧新增接线 |
| c) 仅作基础设施 | 无 | 不复活，协议备好；阶段 3 无 UI 验证对象 |

**推荐 a)**：改动最小、复用现有桥接与 UI 消费端，与"UI 零改动"目标最接近。复活受配置开关 `aiConfig.localAgentEnabled`（新增，默认 `false`）控制——**默认行为不变**，仅联调/上线时开启。产品决策点：是否复活、何时复活。

### 3.2 数据流

```
Java (JSON 请求)                          C++ (推理引擎)
     │                                          │
     │── messages:[{role,content,tool_calls}] ─→│
     │── tools:[{type,function}]              ─→│
     │── tool_choice:"auto|required|none"     ─→│
     │── max_tokens,temperature,top_p,top_k   ─→│
     │                                          │
     │                                          │── common_chat_templates_apply
     │                                          │── generateStreamIncremental (流式, KV 增量)
     │◄── {"type":"token","content":"好","is_tool_call":false}
     │◄── {"type":"token","content":"的","is_tool_call":false}
     │   ... (tool_call 模式的 token 也照发，is_tool_call=true，由 Java 侧忽略不渲染)
     │                                          │── 生成结束
     │                                          │── common_chat_parse
     │◄── {"type":"tool_call","id":"call_1","name":"ai_weather","arguments":"{\"city\":\"银川\"}"}
     │◄── {"type":"reasoning","content":"..."}  (如有)
     │◄── {"type":"complete","content":"..."}
     │
     │── 执行工具 ai_weather
     │── 构建 messages (追加 assistant.tool_calls + tool.result)
     │── chatJson (tool_choice=auto) ──────────→│
     │   ... 循环直到 complete 无 tool_call
```

---

## 四、JSON 协议定义

### 4.1 请求（Java → C++）

```json
{
  "action": "chat",
  "messages": [
    {"role": "system", "content": "你是智能助手"},
    {"role": "user", "content": "银川天气怎么样"},
    {
      "role": "assistant",
      "content": "",
      "tool_calls": [
        {"id": "call_1", "type": "function", "function": {"name": "ai_weather", "arguments": "{\"city\":\"银川\"}"}}
      ]
    },
    {"role": "tool", "tool_call_id": "call_1", "content": "晴，25°C"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "ai_weather",
        "description": "查询天气",
        "parameters": {
          "type": "object",
          "properties": {"city": {"type": "string", "description": "城市名"}},
          "required": ["city"]
        }
      }
    }
  ],
  "tool_choice": "auto",
  "enable_thinking": false,
  "max_tokens": 500,
  "temperature": 0.6,
  "top_p": 0.9,
  "top_k": 40
}
```

**字段说明：**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `action` | string | 是 | 固定 `"chat"`，预留扩展 |
| `messages` | array | 是 | OpenAI 标准格式，assistant 可带 `tool_calls`，tool 带 `tool_call_id` |
| `tools` | array | 否 | OpenAI 标准 tools 数组，为空时纯聊天 |
| `tool_choice` | string | 否 | `"auto"`(默认) / `"required"` / `"none"` |
| `enable_thinking` | bool | 否 | **由调用方传入**；Agent 模式默认 `false`（R8-1，与产品决策一致：AIChatActivity.java:4547-4550 明确 AGENT 模式不启用 thinking，Qwen3-4B thinking+FC 会忘记调工具），深度思考模式可开；C++ 侧 `inputs.enable_thinking = supports_thinking && enableThinking` |
| `max_tokens` | int | 否 | 默认 500 |
| `temperature` | float | 否 | 默认 0.6，≤0 时用 greedy |
| `top_p` | float | 否 | 默认 0.9 |
| `top_k` | int | 否 | 默认 40 |

> 注：`parallel_tool_calls` 暂不进 schema，C++ 侧固定 `true`（F8）。

### 4.2 回调（C++ → Java，统一走 `onJson`）

#### 流式文本 token
```json
{"type": "token", "content": "好的，", "is_tool_call": false}
```
- `is_tool_call=false`：自然语言文本，UI 正常渲染
- `is_tool_call=true`：tool_call 的 JSON 片段，Java 层吞掉不渲染

#### 完整 tool_call（收集完一次性发）
```json
{"type": "tool_call", "id": "call_1", "name": "ai_weather", "arguments": "{\"city\":\"银川\"}"}
```
- 生成结束后 `common_chat_parse` 解析出的完整 tool_call
- 多个 tool_call 时发多条

#### 思考内容
```json
{"type": "reasoning", "content": "用户问的是银川天气，需要调用天气工具"}
```
- 仅 `enable_thinking=true` 时产生（R9-2：Agent 模式默认 false 后基本不出现，深度思考模式可开）

#### 本轮生成结束
```json
{"type": "complete", "content": "银川今天晴，25°C"}
```
- `content`：模型本轮输出的正文（去掉 tool_call / reasoning 后的纯文本）

#### 错误
```json
{"type": "error", "message": "模型未加载"}
```

### 4.3 回调顺序保证

```
onJson(token) × N  →  onJson(tool_call) × M  →  onJson(reasoning)?  →  onJson(complete)
```
- `token` 在生成过程中流式发送
- `tool_call` / `reasoning` / `complete` 在生成结束后按顺序发送
- `error` 可在任何时刻发送，发送后本轮终止

> **注意**：与旧协议对比——旧协议实测顺序为 onComplete → onToolCalls → onReasoning（`native-lib.cpp:5882/5977/5994`，onComplete 由 `generateWithTools` 末尾 2372 行触发，先于后两者），且与 `LlamaHelper.java:1386` javadoc 矛盾；新协议单 onJson 通道彻底消除此问题。

---

## 五、C++ 层改造

### 5.1 新增 `chatJson` 函数

**位置**：`native-lib.cpp`，InferenceContext 类内新增方法

**签名**：
```cpp
using JsonCallback = std::function<void(const std::string& json)>;

bool chatJson(const std::string& requestJson, JsonCallback jsonCallback);
```

**流程**：

```
0. 并发守卫（R3-3）：isGenerating.exchange(true) + GeneratingGuard RAII
   ├─ 已在生成 → onJson({"type":"error","message":"Generation already in progress"}) → return false
   └─ 与 generateStream 互斥（复用 1878-1887 同一机制）
   │
0'. shouldStop 复位（R4-3）：每次进入 chatJson 前 shouldStop = false
    （同 generateStream 1903 行；否则上一次取消（R3-2）后本次调用立即终止）
   │
1. nlohmann::ordered_json::parse(requestJson)   (R8-3：与 common_chat_*_parse_oaicompat 参数类型一致，现状 2278 行同款)
   ├─ 失败 → onJson({"type":"error","message":"JSON parse failed"}) → return false
   │
2. 解析 messages
   ├─ req["messages"] → common_chat_msgs_parse_oaicompat()
   └─ 为空 → error
   │
3. 解析 tools (可选)
   └─ req["tools"] → common_chat_tools_parse_oaicompat()
   │
4. 解析参数
   ├─ tool_choice → common_chat_tool_choice_parse_oaicompat()
   ├─ enable_thinking → req["enable_thinking"]（缺失时默认 false，R10-1：R8-1 后与产品决策一致）
   ├─ max_tokens / temperature / top_p / top_k
   └─ 默认值兜底
   │
5. 构建 common_chat_templates_inputs
   ├─ inputs.messages = messages
   ├─ inputs.tools = tools
   ├─ inputs.tool_choice = parsed
   ├─ inputs.parallel_tool_calls = true
   ├─ inputs.add_generation_prompt = true
   ├─ inputs.use_jinja = true
   └─ inputs.enable_thinking = supports_thinking && enableThinking
   │
6. common_chat_templates_apply → chat_params
   ├─ 失败 → fallback generateStreamFromMessages，并 kvCacheValid = false（R3-5）
   └─ 成功 → 继续
   │
7. 生成阶段（核心，调用 generateStreamIncremental，enableThinking 恒传 false 交给模板）
   ├─ 维护 collectedText (完整输出，累积原始字节)
   ├─ 维护 utf8Buffer（R9-1：token 级 UTF-8 完整性缓冲）
   ├─ 维护 isInToolCall (是否进入 tool_call 输出)
   ├─ 每个 token:
   │   ├─ collectedText += token（原始字节，供 common_chat_parse 用）
   │   ├─ 拼入 utf8Buffer → splitUtf8Complete 分离完整前缀与不完整尾部
   │   │   （复用 native-lib.cpp:240 机制；nlohmann dump 遇非法 UTF-8 会抛 type_error.316）
   │   ├─ 检测 tool_call 模式 (见 5.2)
   │   └─ 完整前缀非空 → onJson({"type":"token","content":完整前缀,"is_tool_call":isInToolCall})
   │   （不完整尾部留在 buffer，等下一 token 拼接；生成结束残留丢弃，同现状 JNI 行为）
   ├─ 失败处理（R7-1）：
   │   ├─ 回调收到 isComplete=true 且 error 非空（或函数返回 false）→
   │   │   发 onJson({"type":"error","message":lastError}) → return false
   │   │   （修复现状 wrappedCallback 吞 error 缺陷，native-lib.cpp:2330-2343）
   │   └─ 注意与 step 9 取消互斥：shouldStop 引起的终止走 cancelled，不走此分支
   └─ EOS / max_tokens / shouldStop → 停止
   │
8. 解析阶段（R5-2：parse 失败降级，与现状 2366-2372 行为一致）
   ├─ common_chat_parse(collectedText, false, parser_params)
   ├─ 失败且 collectedText 非空 → 不中断：发 complete(content=collectedText)
   │        （无 tool_call/reasoning 事件，Java 侧当纯文本回答处理，走 f 分支/F4 降级）
   ├─ 失败且 collectedText 为空 → 并入步骤 10（只发一次 complete("")）
   ├─ 遍历 parsed.tool_calls → onJson({"type":"tool_call",...})
   ├─ parsed.reasoning_content 非空 → onJson({"type":"reasoning",...})
   └─ onJson({"type":"complete","content":parsed.content})
   │
9. 取消（R3-2）：shouldStop 被 nativeStopGeneration 置位时
   ├─ 生成循环退出后不再 parse
   └─ onJson({"type":"error","message":"cancelled"}) → return false
   │
10. 空输出兜底：collectedText 为空（含 parse 失败且空）时发 complete(content="")
    （与步骤 8 互斥，complete 事件全轮只发一次；Java 侧走现有
     "空回复 break → 统一退出路径 → buildSimpleFallback"逻辑，不发 error）
```

> **F5 说明（思考 token 流式行为）**：步骤 7 中思考区文本（`<think>` 区）同样以 `is_tool_call=false` 的 token 事件发出（现状 generateWithTools 即如此，非回归）；`complete.content` 来自 `parsed.content`（纯净无思考标签），优于现状 cleanResponse 正则清理。

### 5.2 tool_call 检测机制

**第一阶段（简化版，推荐）：**

| tool_choice | 检测策略 | is_tool_call 标记 |
|---|---|---|
| `required` | 整轮一定是 tool_call | 所有 token `true` |
| `auto` / `none` | 先正常发（`false`），生成结束后 parse 确认 | 生成中 `false` |

**理由**：
- `required` 模式用于首轮（强制调工具），所有输出都是 tool_call JSON，直接标记不渲染
- `auto` 模式用于后续轮（工具结果回灌后该给回答了），模型大概率输出自然语言，正常流式渲染；如果偶尔输出 tool_call，UI 会短暂显示 JSON 片段然后跳到工具调用气泡，影响可接受
- **不做"清除已渲染文本"**：UI 层零改动，没有清除当前气泡的接口，接受 auto 模式下偶尔的短暂闪烁
- 不需要 C++ 层做复杂的实时 JSON 模式检测

**第二阶段（优化，可选）：**
利用 `common_chat_parse(text, true, params)` 的 `is_partial=true` 做增量解析，每收 N 个 token 解析一次，检测到 tool_call 起始后立即将后续 token 标记为 `is_tool_call=true`，减少闪烁。已发出的前几个 token 无法撤回，但能缩短闪烁时长。

### 5.3 采样器（已确认正确，无需修改）

`native-lib.cpp:1908-1915` 已正确实现采样器链：
```cpp
if (temperature <= 0) {
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
} else {
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK > 0 ? topK : 40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0 ? topP : 0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
}
```
`chatJson` 调用 `generateStreamIncremental` 时传入正确参数即可，采样器自动生效（F6 修正）。

### 5.4 tool_choice 可配置

当前硬编码：
```cpp
inputs.tool_choice = tools.empty() ? COMMON_CHAT_TOOL_CHOICE_NONE : COMMON_CHAT_TOOL_CHOICE_REQUIRED;
```

改为从请求 JSON 解析：
```cpp
std::string tc = request.value("tool_choice", "auto");
inputs.tool_choice = common_chat_tool_choice_parse_oaicompat(tc);
```

### 5.5 JSON 输出构造

```cpp
// token
nlohmann::json tok = {
    {"type", "token"},
    {"content", token},
    {"is_tool_call", isInToolCall}
};
jsonCallback(tok.dump());

// tool_call
for (auto& tc : parsed.tool_calls) {
    nlohmann::json j = {
        {"type", "tool_call"},
        {"id", tc.id},
        {"name", tc.name},
        {"arguments", tc.arguments}
    };
    jsonCallback(j.dump());
}

// reasoning
if (!parsed.reasoning_content.empty()) {
    nlohmann::json j = {
        {"type", "reasoning"},
        {"content", parsed.reasoning_content}
    };
    jsonCallback(j.dump());
}

// complete
nlohmann::json j = {
    {"type", "complete"},
    {"content", parsed.content}
};
jsonCallback(j.dump());
```

### 5.6 KV cache 增量解码（P0-2，性能关键）

**问题确认**：`native-lib.cpp:1953-1961` 每次 `generateStream` 调用前执行 `llama_memory_clear(mem, false)` 软清除 KV cache，导致每轮 Agent 循环重新 eval 完整历史。在 11 tokens/s 的手机上，单工具调用需 3-5 分钟。

**改造方案**：新增 `generateStreamIncremental`，维护已 eval token 缓存，前缀匹配复用 KV cache。

#### 成员变量
```cpp
std::vector<llama_token> cachedTokens;  // 上一轮已 eval 的完整 token
int cachedNPast = 0;                    // 已 eval 位置
bool kvCacheValid = false;              // KV cache 是否有效（未被清除）
```

#### 流程
```
1. tokenize 新 prompt → newTokens
2. 前缀匹配 newTokens vs cachedTokens → matchedLen
3. if (kvCacheValid && matchedLen == cachedTokens.size() && matchedLen > 0
        && llama_memory_seq_pos_max(mem, 0) == cachedNPast - 1):   (R3-4 一致性校验)
     // 增量模式：新 prompt 是旧 prompt 的严格超集（全部旧 token 仍在开头）
     // 且 KV 实际位置与缓存记账一致（未被任何并行路径清除/移动）
     // 只 eval newTokens[matchedLen:]，位置由 llama_decode 自动续接
     evalTokens = newTokens[matchedLen:]
     不调用 llama_memory_clear
   else:
     // 历史变了（裁剪/修改）或首次调用，或 KV 被外部路径改动 → 全量重 eval
     llama_memory_clear(mem, false)
     evalTokens = newTokens
4. 分块 llama_decode evalTokens（沿用 llama_batch_get_one，见下方"位置机制"）
5. 更新 cachedTokens = newTokens
   cachedNPast = newTokens.size()
   kvCacheValid = true
6. 生成循环从 newTokens.size() 位置开始（n_past = newTokens.size()）
```

> **位置机制（已核实，无需手动设 pos）**：`llama_batch_get_one` 返回的 batch 其 `pos=nullptr`（`llama.h:935-937`），llama_decode 在 pos 为空时按 `memory->seq_pos_max(s)+1` 自动续接位置（`src/llama-batch.cpp:90-118`）。因此增量模式下只解码 delta token，位置自动接在缓存前缀之后。**阶段 0 第 4 项确认结果：`llama_batch.pos` 可手动设置，但本项目不需要**；手动 pos 仅用于"部分前缀复用 + `llama_memory_seq_rm` 删尾部"的场景。
>
> **必须先例（强烈建议参考）**：同文件 `NativeChatContext.chatSend` 路径（`native-lib.cpp:2965-3900`）已实现"文本增量 + KV 跨轮保留"：`prev_formatted_len` 取 `formatted.substr(prev_formatted_len)` 只 eval 增量（3391-3397）、`total_tokens_in_kv`/`current_pos`/`turns` 记账（3085-3114 encodeTokens 用 llama_batch_get_one 分块解码且跨轮不调 llama_memory_clear）、`shiftContext()` 处理上下文满。`generateStreamIncremental` 的实现可直接参照该成熟写法（位置记账、分块、溢出处理），不必从零设计。

#### Agent 循环中的命中情况

| 轮次 | 完整 prompt tokens | 增量 eval | 说明 |
|---|---|---|---|
| 首轮 | ~1000 | 1000（全量） | 冷启动，KV cache 空 |
| 第二轮 | ~1200 | ~200 | 追加 assistant tool_call + tool result |
| 第三轮 | ~1400 | ~200 | 继续追加 |

Agent 循环中历史只追加不修改，**每轮都命中增量模式**（前提：模板渲染逐 token 一致，见下）。

> **命中率风险（重要）**：增量命中要求新 prompt 是旧 prompt 的**逐 token 前缀超集**，这依赖 chat 模板确定性渲染。新协议把 assistant.tool_calls 以**结构化字段**传入后，模板按自身规则渲染 tool_call（如 Qwen 的 `<tool_call>...</tool_call>`），**未必逐字节等于上一轮模型原始生成的文本**；不一致时前缀匹配失败、回退全量 eval（正确性不受影响，性能收益在工具轮次消失）。
>
> **决策（已拍板，2026-08-22，实施 v1.12）**：第一阶段按 §7.2.1/A2 执行——assistant 消息 content 用纯净 `complete.content`（**禁用 raw 输出**，防思考/JSON 双份渲染与泄漏）；阶段 3 实测 KV 命中率。若命中率确实低（第二轮即回退全量 eval），再评估折中方案：a) assistant content 填 raw 但 Java 侧不渲染（只渲染 tool_call 气泡）；b) `llama_memory_seq_rm`（llama.h:735）删尾部 + 手动设 pos 做部分前缀复用。**该决策不阻塞开工。**
>
> **F7 正面确认**：`tool_choice` 不参与 prompt 文本渲染（`common/chat.cpp:3229` 只写入 `params.tool_choice`，进入 grammar/parser 规则），因此首轮 required → 二轮 auto 的切换**不会**因 tool_choice 本身破坏前缀命中；前缀失配只取决于消息历史与模板渲染。

#### 失效条件（触发全量 eval）
- 历史被裁剪（`trimHistoryToFit` 删除了旧消息）
- 切换了对话/用户
- 调用了 `clearHistory` / `release`
- KV cache 溢出（n_past >= n_ctx）
- **任何并行路径清 KV（R3-4）**：`llama_memory_clear` 本文件有 7+ 处调用点（1527/1687/1758/2519/3048/3155/3835），`NativeChatContext.chatSend`/`clearHistory` 与 `InferenceContext` 共享同一 llama_context（`initFromExistingContext` 复用）——依赖步骤 3 的 `llama_memory_seq_pos_max(mem,0) == cachedNPast-1` 一致性校验兜底，无需逐点联动
- fallback 到 `generateStreamFromMessages` 后（R3-5）

#### 接口设计

`chatJson` 内部调用 `generateStreamIncremental(prompt, ...)` 而非 `generateStream`。`generateStreamIncremental` 签名与 `generateStream` 一致，内部管理 KV cache 状态。

旧 `generateStream` 保留不动（非 Agent 场景继续用，每次清 KV cache 保证独立性）。

#### 预期性能提升（11 tokens/s，**量级参考**）

> 注：上表假设 eval 速度 ≈ 生成速度（11 tokens/s）；实测中 prompt eval 通常快于生成，真实数字以阶段 3 基准为准。

| 阶段 | 无增量 | 有增量 |
|---|---|---|
| 首轮 eval | ~1000 tokens / ~90s | ~1000 tokens / ~90s（相同） |
| 第二轮 eval | ~1200 tokens / ~109s | ~200 tokens / ~18s |
| 单工具总耗时 | ~3.5 min | ~2.5 min |
| 多工具（3轮） | ~6 min | ~3 min |

---

## 六、JNI 层改造

### 6.1 新增 `nativeChatJson`

**位置**：`native-lib.cpp`，新增 JNI 导出函数

**签名**：
```cpp
JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatJson(
    JNIEnv* env,
    jclass clazz,
    jbyteArray requestJsonBytes,
    jobject callback);
```

**流程**：
```
0. 整段推理调用包进 SAFE_RUN_INFERENCE 宏（native-lib.cpp:59）：
   - sigsetjmp 崩溃恢复 + 信号回调 onError
   - isGenerating 崩溃后强制复位（forceResetGeneration）
   - EnsureLocalCapacity(256) 防局部引用泄漏
1. requestJsonBytes → std::string (UTF-8)
2. 创建全局引用 callback
3. 查找 callback 的 onJson 方法: onJson(String)V
4. 包装 JsonCallback:
   jsonCallback = [globalCallback, onJsonMethod](const std::string& json) {
       // attach thread if needed
       // jstring = utf8StringToJstring(json)
       // env->CallVoidMethod(globalCallback, onJsonMethod, jstring)
   }
5. s_helperContext->chatJson(requestJson, jsonCallback)
6. 完成后释放全局引用
```

**删除**：旧 `nativeGenerateWithTools` 中 60+ 行手动拼 ArrayList / SetObjectField 的代码（旧函数本身保留，但内部可简化为调用 chatJson）。

> **R5-1 确认**：项目 JNI 采用静态导出命名（`JNI_OnLoad` 只设 g_jvm + GPU 检测，无 RegisterNatives），新增 `nativeChatJson` 按现有命名规范即可，无需注册表改动。

> **附带收益**：旧 JNI 硬编码 `OnlineInferenceService$ToolCallInfo` 类（`native-lib.cpp:5842`，`com/oilquiz/app/ai/service/OnlineInferenceService$ToolCallInfo`）——新 onJson 协议顺带消除该耦合（Java 侧只需解析 JSON 字符串）。旧接口保留时该耦合仍存在，勿删。

### 6.2 线程安全

- JNI 回调在推理线程执行，需 `AttachCurrentThread` / `DetachCurrentThread`
- 复用现有 `getJavaVM()` 机制
- callback 全局引用在完成后释放，防止内存泄漏
- **R4-4 确认**：`InferenceContext` 与 `NativeChatContext` 各有独立 `isGenerating` guard，native 层互不感知；并发安全由 Java 侧 `LlamaHelper.inferenceLock` 写锁统一串行化（generateWithTools/chatSend/chatJson 同锁），SAFE_RUN_INFERENCE 崩溃恢复可复位各自标志。无需额外处理。

---

## 七、Java 层改造

### 7.1 `LlamaHelper.java`

#### 新增 `JsonCallback` 接口
```java
public interface JsonCallback {
    void onJson(String json);
}
```

#### 新增 `chatJson` 方法
```java
public static void chatJson(String requestJson, JsonCallback callback) {
    // 推理锁
    // nativeChatJson(requestJson.getBytes(UTF_8), callback)
}

private static native void nativeChatJson(byte[] requestJson, JsonCallback callback);
```

#### 旧接口保留
- `generateWithTools(...)` — 保留，标记 `@Deprecated`
- `generate(...)` / `generateStream(...)` — 保留，非 Agent 场景继续用

### 7.2 `AgentLoopEngine.java`（核心改造）

#### 7.2.1 循环流程重构

```
run(userMessage, enableThinking):   (R8-1：enableThinking 由调用方传入，Agent 模式默认 false)
  1. 智能工具选择 (保留现有逻辑)
  2. 构建初始 messages: [system, user]
  3. 循环 (iteration 1..max):
     a. trimHistoryToFit (保留)
     b. 构建请求 JSON:
        - messages = 当前历史
        - tools = selectedTools
        - tool_choice = (iteration==1 && tools 非空) ? "required" : "auto"   (F2：tools 空时不用 required)
        - enable_thinking = enableThinking   (R8-1：贯穿传递，不再硬编码 true)
        - max_tokens = iteration==1 ? 500 : 1000
     c. chatJson(requestJson, jsonCallback)
     d. jsonCallback 内部（加"已结束"幂等标志，error/complete 后忽略迟到事件，F10）:
        - type=token, is_tool_call=false → callback.onToken(token)
        - type=token, is_tool_call=true → 忽略
        - type=tool_call → 收集到 toolCalls 列表
        - type=reasoning → callback.onThinkingUpdate(reasoning)
        - type=complete → 本轮结束，break
        - type=error → callback.onError(msg), return
        （R5-3：每 token 事件用 org.json 解析即可；性能敏感时可先子串判断
          "type":"token" 再决定是否完整解析，4B 本地模型下开销可忽略）
     e. 有 toolCalls:
        - 先为每个 ToolCall 补齐 id（tc.id == null → "call_" + 自增计数器），
          assistant 消息与 tool 消息共用同一 id（R4-1）
        - callback.onToolCall(name, args)
        - 执行工具 (toolManager.executeTool, 非 onlineToolManager)
        - callback.onToolResult(name, success, result)
        - 追加 assistant(tool_calls) 到 messages：content 用 complete.content（纯净正文，可为空；A2）
          **不用 raw 输出**——raw 含思考+tool_call JSON，会与结构化 tool_calls 双份渲染/思考泄漏，
          也违背 F5 的 content 纯净目标；KV 命中率问题（F7）单独由阶段 3 实测模板渲染格式解决
        - 追加 tool(result) 到 messages：new ChatMessage("tool", resultStr, tc.id, true)
          （tool 消息必须带与 assistant tool_calls 一致的 tool_call_id，R4-1）
        - continue 下一轮
     f. 无 toolCalls:
        - iteration==1 && complete.content 为空 → 走 generateFallbackPrompt 重试（保留现状降级，F4）
        - 否则 complete.content 即最终回答
        - callback.onComplete(complete.content)
        - return
```

#### 7.2.2 关键变更点

| 变更 | 旧 | 新 |
|---|---|---|
| 推理接口 | `generateWithTools(roles, contents, toolsJson, ...)` | `chatJson(requestJson, JsonCallback)` |
| 同步机制 | 单 `CountDownLatch` 等 onComplete/onError，结果靠 4 个回调（onToken/onComplete/onToolCalls/onReasoning）写 holder 缓冲；顺序与 javadoc 矛盾（onComplete 先于 onToolCalls/onReasoning） | 单 `onJson` 回调 + `CountDownLatch` 等本轮结束（从 4 回调简化为 1 回调，消除顺序契约问题） |
| tool_choice | C++ 硬编码 required | 首轮且 tools 非空 → required，其余 auto（F2：tools 空时不用 required） |
| 工具执行 | `onlineToolManager.executeTool(id, name, argsJson)` 返回 `OnlineToolResult` | `toolManager.executeTool(name, paramsMap)` 返回 `AIToolResult`，需做返回类型适配 |
| 最终回答 | `streamDirectAnswer` 按 4 字符切片模拟 | auto 模式下 token 流式输出到 UI，`complete.content` 用于统计/记录 |
| messages 格式 | `List<ChatMessage>` (role+content) | JSON 数组，支持 assistant.tool_calls / tool.tool_call_id |
| 采样参数 | 无改动（`native-lib.cpp:1908-1915` 采样器链已正确实现，传参即生效） | 同左，无需修改 |
| KV cache | 每次 `llama_memory_clear` 全量 eval | `generateStreamIncremental` 前缀匹配增量 eval（参考 `NativeChatContext.chatSend` 成熟实现） |

#### 7.2.2.1 工具执行返回类型适配

当前代码用 `onlineToolManager`，切到本地 `toolManager` 需适配：

```java
// 旧：在线工具
OnlineToolResult result = onlineToolManager.executeTool(tc.id, toolName, argsStr);
// OnlineToolResult 字段: success / result / error

// 新：本地工具
Map<String, Object> params;
try {
    params = jsonToMap(new JSONObject(argsStr));  // 现有 jsonToMap 签名是 (JSONObject)，F3 修正
} catch (Exception e) {
    params = new HashMap<>();
}
AIToolResult result = toolManager.executeTool(toolName, params);
// AIToolResult 方法: isSuccess() / getResult()(Object) / getErrorMessage()

// 适配为统一格式
boolean success = result.isSuccess();
String resultText = success ? String.valueOf(result.getResult()) : result.getErrorMessage();
```

`AgentLoopEngine` 里已有 `jsonToMap()` 和 `executeToolSafely()` 辅助方法但未被使用（主循环 309 行直接走 onlineToolManager），直接接上即可。

> **R4-2 序列化提示**：`getResult()` 返回 Object（可能是 ComponentData/Map/List/String）；`String.valueOf()` 对 Map/List 得到 Java toString 而非 JSON。回灌 messages 按文本截断（MAX_TOOL_RESULT_LENGTH）与现状一致；若工具返回结构化数据且希望保留格式，可改 `new JSONObject(result.getResult())` 序列化（可选，非本方案范围）。

#### 7.2.3 messages JSON 构建

```java
private String buildRequestJson(List<ChatMessage> history, String toolsJson, 
                                 String toolChoice, int maxTokens, boolean enableThinking) {
    JSONObject req = new JSONObject();
    req.put("action", "chat");
    
    JSONArray msgs = new JSONArray();
    for (ChatMessage m : history) {
        JSONObject msg = new JSONObject();
        msg.put("role", m.role);
        msg.put("content", m.content != null ? m.content : "");
        if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
            JSONArray tcs = new JSONArray();
            for (ToolCall tc : m.toolCalls) {
                JSONObject call = new JSONObject();
                // A3：id 必须来自执行工具前的补齐（§7.2.1 step e，R4-1），这里不再临时生成，
                // 否则 assistant/tool 消息 id 不一致；防御：null 时跳过该条（记日志）
                if (tc.id == null) { continue; }
                call.put("id", tc.id);
                call.put("type", "function");
                JSONObject fn = new JSONObject();
                fn.put("name", tc.toolName);
                fn.put("arguments", tc.args.toString());
                call.put("function", fn);
                tcs.put(call);
            }
            msg.put("tool_calls", tcs);
        }
        if (m.toolCallId != null) {
            msg.put("tool_call_id", m.toolCallId);
        }
        msgs.put(msg);
    }
    req.put("messages", msgs);
    
    if (toolsJson != null && !toolsJson.isEmpty()) {
        req.put("tools", new JSONArray(toolsJson));
    }
    req.put("tool_choice", toolChoice);
    req.put("enable_thinking", enableThinking);   // R8-1：调用方传入（Agent 模式默认 false）
    req.put("max_tokens", maxTokens);
    req.put("temperature", 0.6f);
    req.put("top_p", 0.9f);
    req.put("top_k", 40);
    
    return req.toString();
}
```

#### 7.2.4 ChatMessage 扩展

当前内部类只有 role + content，需扩展：
```java
private static class ChatMessage {
    final String role;
    final String content;
    final List<ToolCall> toolCalls;      // assistant 消息的工具调用
    final String toolCallId;             // tool 消息对应的调用 ID
    
    ChatMessage(String role, String content) { ... }
    ChatMessage(String role, String content, List<ToolCall> toolCalls) { ... }
    ChatMessage(String role, String content, String toolCallId, boolean isToolResult) { ... }
}
```

> **R4-1**：`toolCallId` 必须与对应 assistant `toolCalls` 的 id 一致（llama.cpp 渲染依赖，`common/chat.cpp:2073-2089`）；`isToolResult` 参数实际冗余（role="tool" 已隐含），保留仅为可读性。现状 `AgentLoopEngine.java:315` 的 tool 消息无 id，正是 P1-4 的表现，新协议必须修复。

#### 7.2.5 保留不动的逻辑

- 智能工具选择（关键词路由 + schema 预算裁剪）
- 循环保护（去重 / 轮次上限 / 超时 / 总时长预算）
- 历史裁剪（trimHistoryToFit）
- prompt 预算计算
- 提示词泄漏检测
- 降级路径（native 不可用时用 prompt 模式；**首轮 required 无 tool_call 输出时用 generateFallbackPrompt 重试，F4**）

### 7.3 `AgentSoftwareLayer.java`（微调）

- `AgentCallback` 接口不变
- `loopEngine.setCallback` 桥接不变
- 确认 `onComplete(AgentResponse)` 能拿到正确的 finalAnswer
- **processMessage 签名**：`processMessage(String userMessage, boolean enableThinking)`（R8-1 贯穿传递，内部传给 `loopEngine.run(userMessage, enableThinking)`）
- **cancel 桥接（R3-2）**：`cancel()` 内调用 `LlamaHelper.stopGeneration()`（native shouldStop 置位，打断阻塞中的 chatJson），再置 `isProcessing=false`

### 7.4 `AgentChatHandler.java`（微调，R3-1）

- `softwareLayer` 回调桥接不变（AgentChatHandler.java:68-134 已就绪）
- `onToolCallStart` / `onToolCallComplete` / `onToken` / `onComplete` 语义不变
- **复活入口**：`startAgentLoop` 在本地模型且 `aiConfig.localAgentEnabled=true` 时调用 `softwareLayer.processMessage(message, enableThinking)`（当前只路由在线引擎，AgentRouter.java:71-81；**enableThinking 贯穿传递**，R8-1）
- 唯一行为变化：tool_call 不再逐字符显示，直接跳到工具调用状态

### 7.5 `forceRunLocalAgent` 复活分支（R8-2）

- `ChatMessage.Action.forceLocalAgent` → `AIChatActivity.forceRunLocalAgent`（4357-4359）当前只显示"🚫 本地 Agent 已禁用"；
- 复活后：`localAgentEnabled=true` 时改为真正走本地 Agent（复用 startAgentLoop 本地分支）；禁用时保持现状提示即可。

---

## 八、UI 层适配策略（零改动）

> **R3-1 UI 对象说明**：本地 Agent 复活后（§3.1.1 推荐路径 a），UI 消费端是 `AIChatActivity.AgentCallbackImpl`（聊天气泡桥接，AIChatActivity.java:5568+，已能消费 onToken/onThinkingToken/onToolCall/onComplete）。若走执行面板路径 b，则消费端为 `ExecutionEvent`/`AgentExecutionView`（AgentExecutionEngine 事件链路）。两类消费端都不需要改：AgentLoopEngine 的输出语义（onToken/onToolCall/onThinkingUpdate/onComplete）保持不变，新协议在引擎内部消化。

### 8.1 现有 UI 回调接口

`AgentChatHandler.AgentChatCallback` 保持不变：
- `onToken(String token)` — 流式文本
- `onToolCallStart(String toolCallId, String toolName, String args)` — 工具调用开始
- `onToolCallComplete(String toolCallId, String toolName, OnlineToolResult result)` — 工具完成
- `onThinkingToken(String token)` — 思考
- `onComplete(String fullText)` — 完成
- `onError(String error)` — 错误
- `onInferenceProgress(int tokenCount, float tokensPerSecond)` — 进度

### 8.2 行为变化说明

| 场景 | 旧行为 | 新行为 |
|---|---|---|
| 模型输出 tool_call | JSON 逐字符显示在气泡里 | 气泡显示"生成中..."，完成后直接跳工具调用气泡 |
| 最终回答 | streamDirectAnswer 模拟打字机 | 真正的 LLM 生成文本（如有流式 token）或完整文本 |
| 思考过程 | onThinkingToken 逐段 | onReasoning 一次性（C++ parse 后统一发；**仅 enable_thinking=true 时出现，R9-2**） |

> **F5 说明（思考的流式行为）**：生成期间 `<think>` 区文本仍以 `is_tool_call=false` 的 token 流式进入主气泡（现状即如此，非回归；`is_tool_call` 只负责吞 tool_call JSON，不处理思考文本）；**完成时 `complete.content` 来自 `parsed.content`（纯净、无思考标签）**，优于现状 cleanResponse 正则清理。可选优化：Java 流式剥 `<think>` 区，或第二阶段用 is_partial 增量解析抑制。

### 8.3 不需要改 UI 的原因

- `onToken` 只收 `is_tool_call=false` 的文本，UI 渲染逻辑不变
- `onToolCallStart` 在完整 tool_call 到达时触发，UI 显示工具气泡逻辑不变
- `onComplete` 收到最终回答文本，UI 更新气泡逻辑不变

---

## 九、实施步骤

### 阶段 0：前置确认（0.5 天）
- [x] 采样器已确认正确（`native-lib.cpp:1908-1915`，temperature/topP/topK 链已实现）
- [x] KV cache 每次软清除已确认（`native-lib.cpp:1953-1961`，`llama_memory_clear(mem, false)`）
- [x] `common_chat_msgs_parse_oaicompat` 处理 tool_calls / tool_call_id — **静态已确认**（`common/chat.cpp:372-471` 完整支持），实施时跑一次冒烟即可
- [ ] 确认 `common_chat_parse` 返回的 `content` 字段是否纯净（无 tool_call JSON / reasoning 标签残留）— **唯一需运行时实测项**（现生产代码从不读 parsed.content，新协议首次依赖；PEG 语法按格式生成 content，`chat.cpp:3427-3509`）
  - **操作化（R4-5）**：在现有 `generateWithTools` 路径（`native-lib.cpp:2352-2354`）临时加日志，对比 `parsed.content` 与 `collectedText`（工具轮与回答轮各一次），确认 content 无 tool_call JSON / 思考标签残留
- [x] `nlohmann::json` 已包含（`native-lib.cpp:22`，且 2278 行已在用 `nlohmann::ordered_json`）
- [x] `llama_batch.pos` — **可手动设置，但无需**：`pos=nullptr` 时 llama_decode 自动从 `memory->seq_pos_max(s)+1` 续接（`src/llama-batch.cpp:90-118`），增量 eval 直接适用

### 阶段 1：C++ 层（1.5 天）
- [ ] 新增 `InferenceContext::generateStreamIncremental(prompt, ...)` — KV cache 前缀匹配增量解码（**参考 `NativeChatContext.chatSend` 成熟实现**：`native-lib.cpp:2965-3900` 的 prev_formatted_len / total_tokens_in_kv / current_pos / shiftContext）
  - [ ] 维护 `cachedTokens` / `cachedNPast` / `kvCacheValid` 成员变量
  - [ ] 前缀匹配逻辑，命中则只 eval 新增 token
  - [ ] 未命中则 `llama_memory_clear` + 全量 eval
  - [ ] 位置续接依赖 `llama_batch_get_one` 的自动 pos（`pos=nullptr` 自动从 `seq_pos_max+1` 续接），**无需手动设置 `llama_batch.pos`**
- [ ] 新增 `InferenceContext::chatJson(requestJson, jsonCallback)`
  - [ ] JSON 解析 → messages（`common_chat_msgs_parse_oaicompat`）/ tools / 参数
  - [ ] tool_choice 可配置（`common_chat_tool_choice_parse_oaicompat`）
  - [ ] `common_chat_templates_apply` 格式化
  - [ ] 调用 `generateStreamIncremental`（非 `generateStream`）
  - [ ] 生成循环 + collectedText 收集 + isInToolCall 标记
  - [ ] **token UTF-8 完整性缓冲（R9-1）**：splitUtf8Complete + utf8Buffer，token 事件只发完整前缀，collectedText 累积原始字节
  - [ ] `common_chat_parse` 解析 + 统一 JSON 回调输出（token/tool_call/reasoning/complete/error）
  - [ ] 全程 try-catch：JSON 解析 / 模板应用失败发 `{"type":"error"}`；**parse 失败降级为 complete(collectedText)（R5-2/A1，不发 error）**；空输出发 complete("")（A5，complete 全轮只发一次）
  - [ ] 并发守卫 `isGenerating` + GeneratingGuard（R3-3）
  - [ ] 取消：shouldStop → 发 `{"type":"error","message":"cancelled"}`（R3-2）
  - [ ] 生成失败（回调 isComplete+error 或 genOk=false）→ 发 `{"type":"error","message":lastError}`（R7-1，与取消互斥）
  - [ ] fallback generateStreamFromMessages 后 `kvCacheValid=false`（R3-5）
- [ ] 新增 JNI `nativeChatJson(byte[] requestJson, JsonCallback callback)`，整段包 `SAFE_RUN_INFERENCE` 宏（崩溃恢复 + isGenerating 复位 + EnsureLocalCapacity）
- [ ] `generateStreamIncremental` 增量命中前置 `llama_memory_seq_pos_max(mem,0)==cachedNPast-1` 一致性校验（R3-4）
- [ ] 编译验证（arm64-v8a）

### 阶段 2：Java 层（1.5 天，含复活入口）
- [ ] `AIConfig` 新增 `useJsonProtocol` 开关字段（SharedPreferences 持久化，默认 true）— 回退开关前置
- [ ] `AIConfig` 新增 `localAgentEnabled` 开关字段（默认 false）— 本地 Agent 复活入口开关（R3-1）
- [ ] `LlamaHelper` 新增 `JsonCallback` 接口 + `chatJson` 方法 + `nativeChatJson` 声明（持同一把推理写锁 `inferenceLock`，模式同 `generateWithTools`）
- [ ] `AgentLoopEngine` 重构：
  - [ ] ChatMessage 扩展（toolCalls / toolCallId）
  - [ ] `buildRequestJson` 方法（OpenAI 格式 messages JSON，含 `enable_thinking` 参数化，R8-1）
  - [ ] `chatJson` 调用 + `onJson` 状态机解析（**含"已结束"幂等标志**，error/complete 后忽略迟到事件，F10）
  - [ ] `CountDownLatch` 从 4 回调简化为单回调等待
  - [ ] 工具执行切到 `toolManager` + 返回类型适配（AIToolResult：`isSuccess()` / `getResult()`(Object) / `getErrorMessage()`；`jsonToMap(new JSONObject(argsStr))`，F3）
  - [ ] 首轮 tool_choice=required（tools 非空时），后续 auto（F2）
  - [ ] 首轮 required 无 tool_call 输出 → generateFallbackPrompt 重试（F4）
  - [ ] **tool_call_id 补齐与匹配（R4-1）**：执行工具前为每个 ToolCall 补齐 id（null → 自增 "call_N"），assistant 消息与 tool 消息共用同一 id
  - [ ] 最终回答：auto 模式 token 流式输出，complete.content 用于统计
  - [ ] MAX_TOOL_ROUNDS **维持 4**（D3：待阶段 3 实测后决定是否降 2；多步任务可用性与循环保护权衡）
- [ ] **复活入口（R3-1/R8-1）**：`AgentChatHandler.startAgentLoop` 在本地模型且 `localAgentEnabled=true` 时调 `softwareLayer.processMessage(message, enableThinking)`（路径 a，推荐；enableThinking 贯穿传递）
- [ ] **forceRunLocalAgent 复活分支（R8-2）**：`localAgentEnabled=true` 时路由到本地 Agent，否则保持禁用提示
- [ ] `AgentSoftwareLayer` 验证回调桥接 + **cancel 桥接 `LlamaHelper.stopGeneration()`**（R3-2）
- [ ] 编译验证

### 阶段 3：联调测试（1 天，需先复活入口，R3-6）
- [ ] 简单聊天（无工具）验证流式输出
- [ ] 单工具调用验证（天气）— 确认无裸 JSON、工具执行正常、最终回答正确
- [ ] **`common_chat_parse` content 纯净性实测**（required 模式输出 tool_call 后，complete.content 无 JSON 残留）
- [ ] 多工具串行验证（定位→天气）— 确认 KV cache 增量命中、第二轮速度提升、**实测前缀命中率**（结构化 tool_calls 回灌后若命中率低，改用 raw 文本回灌）
- [ ] 工具失败降级验证
- [ ] 长对话历史裁剪验证 — 确认裁剪后 KV cache 失效并全量重 eval
- [ ] **并行清 KV 后增量一致性验证（R3-4）**：chatSend/clearHistory 清上下文后再跑 chatJson，确认 seq_pos_max 校验触发全量重 eval、无乱码
- [ ] **取消验证（R3-2）**：生成中点取消，确认 native 生成被 shouldStop 打断、UI 正常收尾
- [ ] 内存泄漏检查（多次循环后 native 内存）
- [ ] UI 显示验证（无裸 JSON、思考显示正常、工具气泡正常）
- [ ] 性能基准：首轮 eval 耗时 / 增量 eval 耗时 / 单工具总耗时（校准 11 tokens/s 假设）

### 阶段 4：优化（可选，0.5 天）
- [x] 增量解析 tool_call（`common_chat_parse` is_partial=true，减少 auto 模式闪烁）— **已实施（D4）**：chatJson step 7 每收 16 个 token 做一次 partial parse，检测到 tool_call 后锁定 is_tool_call=true（仅 auto/none 模式启用）
- [ ] 旧 `generateWithTools` 接口下线评估
- [ ] `llama-bridge.cpp` 旧路径清理评估

---

## 十、风险与回退

### 10.1 风险

| 风险 | 概率 | 影响 | 应对 |
|---|---|---|---|
| `common_chat_msgs_parse_oaicompat` 不支持 tool 消息的 tool_call_id | 低（已静态确认支持，`chat.cpp:372-471`） | 工具结果回灌格式错误 | 实施时冒烟验证；不支持则手动构建 common_chat_msg |
| `common_chat_parse` 返回的 content 含 tool_call JSON 残留 | 中（**唯一阻塞性运行时验证项**） | 最终回答带垃圾文本 | 阶段 0 用目标模型实测；必要时手动清理 content |
| 4B 模型 auto 模式下仍反复调工具不收尾 | 中 | 循环靠保护机制强制结束 | 循环保护已就绪；MAX_TOOL_ROUNDS=4（D3，待阶段 3 实测决定是否降 2）；必要时 system prompt 加强约束 |
| KV cache 增量解码前缀匹配失败 | 中 | 回退全量 eval，性能下降 | 历史裁剪时主动失效 KV cache；日志记录命中/失效；**补充：结构化 tool_calls 经模板渲染可能 ≠ 模型原始生成流（§5.6 命中率风险），阶段 3 实测，必要时回灌 raw 文本** |
| `llama_batch.pos` 手动设置导致 KV 位置错乱 | 低（已消除：采用 pos=nullptr 自动续接，无需手动设置） | 生成乱码或崩溃 | 严格测试增量 eval；保留全量 eval 回退路径 |
| **并行路径清 KV 导致增量位置错乱（R3-4）** | 中 | 增量模式在错误 KV 位置续写，生成乱码 | 增量命中前置 `llama_memory_seq_pos_max(mem,0)==cachedNPast-1` 一致性校验，不一致全量重 eval |
| **取消不生效（R3-2）** | 中 | 用户点取消后生成继续跑完，UI 卡"处理中" | 取消链路补 `LlamaHelper.stopGeneration()` → shouldStop → chatJson 发 cancelled |
| JNI 回调线程安全问题 | 低 | 崩溃 | 复用现有 AttachCurrentThread 机制 + `SAFE_RUN_INFERENCE` 崩溃恢复 |
| nlohmann::json 解析异常 | 低 | 推理失败 | 全链路 try-catch，失败发 error 回调 |
| auto 模式 UI 短暂闪烁 | 低 | 体验小瑕疵 | 接受；第二阶段用增量解析优化 |

### 10.2 回退方案

- 旧接口 `generateWithTools` / `nativeGenerateWithTools` **全部保留**
- `AgentLoopEngine` 保留旧路径作为 fallback：`chatJson` 抛 `UnsatisfiedLinkError` 时自动切回 `generateWithToolsSync`
- 通过配置开关 `aiConfig.useJsonProtocol` 控制新旧路径，默认新接口，出问题可一键切回
  - **注意**：`AIConfig` 目前无此字段，需在阶段 2 新增（SharedPreferences 持久化，默认 true）

---

## 十一、测试计划

### 11.1 单元测试

| 测试项 | 验证点 |
|---|---|
| `chatJson` 请求解析 | 正确解析 messages/tools/tool_choice/参数 |
| `chatJson` 空 messages | 返回 error |
| `chatJson` 无效 JSON | 返回 error |
| tool_choice=required | 模型输出 tool_call，is_tool_call=true |
| tool_choice=auto 纯聊天 | 模型输出文本，is_tool_call=false |
| 采样器 temperature>0 | 非 greedy 采样生效 |
| 采样器 temperature=0 | greedy 采样 |
| onJson 状态机（R5-4） | token/tool_call/reasoning/complete/error/cancelled 各事件处理 + 幂等忽略 |
| parse 失败降级（R5-4/R5-2） | parse 异常 → complete(collectedText)，无 tool_call 时按文本回答 |
| 空输出兜底（R5-4） | complete(content="") → Java 空回复 break 路径 |

> 注：KV 增量命中/失效（§5.6）与 tool_call_id 匹配（R4-1）在 Android 上不便做 C++ 单测，由 §11.2 集成测试覆盖。

### 11.2 集成测试

| 测试项 | 验证点 |
|---|---|
| 单轮聊天 | 流式 token + complete |
| 单工具调用 | tool_call JSON 不显示 + 工具执行 + 结果回灌 + 最终回答 |
| 多工具串行 | 多轮 tool_call + 结果引用 |
| **tool_call_id 匹配（R4-1）** | assistant.tool_calls 与 tool 消息的 id 一致，多工具场景模板渲染正确（chat.cpp:2073-2089 排序依赖） |
| 工具执行失败 | 错误结果回灌 + 模型降级回答 |
| 无工具场景 | 直接 complete，无 tool_call |
| 长上下文 | 历史裁剪生效，不 OOM |

### 11.3 UI 测试

| 测试项 | 验证点 |
|---|---|
| 流式文本 | 正常逐字显示 |
| 工具调用 | 无裸 JSON，直接显示工具气泡 |
| 思考过程 | 正常显示 |
| 最终回答 | 正常显示，无打字机模拟感 |

---

## 十二、附录

### 12.1 关键文件清单

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `src/main/cpp/native-lib.cpp` | 修改 | 新增 chatJson + generateStreamIncremental（含 seq_pos_max 一致性校验，R3-4）+ nativeChatJson（采样器已正确，无需改） |
| `src/main/java/.../jni/LlamaHelper.java` | 修改 | 新增 JsonCallback + chatJson + nativeChatJson 声明 |
| `src/main/java/.../agent/software/engine/AgentLoopEngine.java` | 修改 | 核心重构，适配 JSON 协议 |
| `src/main/java/.../refactor/AIConfig.java` | 修改 | 新增 `useJsonProtocol` 开关（回退用）+ `localAgentEnabled` 开关（复活入口用，R3-1），SharedPreferences 持久化 |
| `src/main/java/.../agent/software/AgentSoftwareLayer.java` | 微调 | 回调验证 + cancel 桥接 `LlamaHelper.stopGeneration()`（R3-2） |
| `src/main/java/.../chat/AgentChatHandler.java` | 微调 | 复活入口：本地模型且 `localAgentEnabled` 时调 `softwareLayer.processMessage`（R3-1） |
| `src/main/java/.../ui/activity/AIChatActivity.java` | 零改动 | 无需修改（消费端 AgentCallbackImpl 已就绪） |
| `src/main/java/.../chat/ChatAdapter.java` | 零改动 | 无需修改 |
| `src/main/java/.../agent/AgentExecutionEngine.java` | 可选 | 复活路径 b 时接线（默认不选） |

### 12.2 审查修订记录（v1.0 → v1.11）

基于 `review.md` 的逐条代码核对，v1.1 并入以下修正：

1. **P1-1 修正**：回调顺序实测为 onComplete → onToolCalls → onReasoning（`native-lib.cpp:5882/5977/5994`），非"顺序不确定"，且与 `LlamaHelper.java:1386` javadoc 矛盾（§1.2、§4.3、§7.2.2）
2. **AIToolResult API 修正**：`getError()` → `getErrorMessage()`；`getResult()` 返回 Object 需 `String.valueOf`（§7.2.2.1）
3. **`AIConfig.useJsonProtocol` 不存在**：阶段 2 新增该字段（§9、§10.2、§12.1）
4. **llama_batch.pos 无需手动设置**：pos=nullptr 自动续接（`llama-batch.cpp:90-118`），阶段 0 该项勾除（§5.6、§9）
5. **新增 KV 增量实现参考**：`NativeChatContext.chatSend`（`native-lib.cpp:2965-3900`）已有文本增量 + KV 跨轮保留先例（§5.6）
6. **新增 KV 命中率风险**：结构化 tool_calls 模板渲染可能 ≠ 原始生成流，前缀命中率需实测（§5.6、§10.1、§9 阶段3）
7. **`nativeChatJson` 接入 `SAFE_RUN_INFERENCE`** 崩溃恢复宏（§6.1）
8. **阶段 0 四项确认结果**：3 项静态确认（msgs_parse/nlohmann/pos），1 项需运行时实测（content 纯净性）
9. **llama-bridge.cpp 表述修正**：参与编译（CMakeLists.txt:191）但无 common_chat 依赖，行为不影响主路径（§1.2）
10. **性能表标注为量级参考**：eval 速度 ≠ 生成速度，阶段 3 校准（§5.6）

**v1.2 修订（第二轮审查 F1-F10，依据 review.md 第二轮）**

| # | 修正 | 落点 |
|---|---|---|
| F1 | 请求 schema 增补 `enable_thinking`，C++/Java 两侧取值来源明确（默认值后经 R8-1/R10-1 改为调用方传入，Agent 模式 false） | §4.1、§5.1、§7.2.1、§7.2.3、§9 阶段2 |
| F2 | 首轮 tool_choice=required 增加"tools 非空"条件（空 tools 不 required） | §7.2.1、§7.2.2、§9 阶段2 |
| F3 | 工具适配示例改 `jsonToMap(new JSONObject(argsStr))` + try-catch | §7.2.2.1、§9 阶段2 |
| F4 | 保留"首轮 required 无 tool_call → generateFallbackPrompt 重试"降级 | §7.2.1、§7.2.5、§9 阶段2 |
| F5 | 明确思考 token 流式期间仍进主气泡（现状非回归），complete.content 纯净落定 | §5.1、§8.2 |
| F6 | §5.3 调用对象改 `generateStreamIncremental` | §5.3 |
| F7 | 正面确认 tool_choice 不参与 prompt 渲染，required→auto 不破坏 KV 前缀命中 | §5.6 |
| F8 | `parallel_tool_calls` 暂不进 schema，C++ 固定 true | §4.1 注 |
| F9 | max_tokens 首轮 500 足够输出 tool_call（现状 1500） | §7.2.1 注（无需改） |
| F10 | onJson 状态机加"已结束"幂等标志，error/complete 后忽略迟到事件 | §7.2.1、§9 阶段2 |

**v1.3 修订（第三轮审查 R3-1~R3-6，依据 review.md 第三轮）**

| # | 修正 | 落点 |
|---|---|---|
| R3-1 | 方案级前提：本地 Agent 链当前禁用/休眠（死代码），明确复活路径（推荐 a：聊天气泡桥接 + `aiConfig.localAgentEnabled` 开关，默认 false） | §1.1.1、§3.1/§3.1.1、§7.4、§8、§9 阶段2、§12.1 |
| R3-2 | 取消链路断裂：cancel 不停止 native 生成；补 UI → `LlamaHelper.stopGeneration()` → shouldStop → chatJson 发 cancelled | §5.1、§7.3、§9 阶段1/3、§10.1 |
| R3-3 | chatJson 补 `isGenerating` 并发守卫（复用 generateStream 1878-1887 机制） | §5.1、§9 阶段1 |
| R3-4 | KV 增量与并行清 KV 路径无联动 → 增量命中前置 `llama_memory_seq_pos_max(mem,0)==cachedNPast-1` 一致性校验 | §5.6、§9 阶段1/3、§10.1 |
| R3-5 | fallback 到 generateStreamFromMessages 后 `kvCacheValid=false` | §5.1、§5.6、§9 阶段1 |
| R3-6 | 阶段 3 联调前置：先复活入口（承接 R3-1） | §9 阶段3 |

**v1.4 修订（第四轮审查 R4-1~R4-5，依据 review.md 第四轮）**

| # | 修正 | 落点 |
|---|---|---|
| R4-1 | tool 消息必须带与 assistant tool_calls 一致的 tool_call_id（llama.cpp 渲染依赖 `chat.cpp:2073-2089`）；Java 侧执行工具前先补齐 ToolCall id | §7.2.1、§7.2.4、§9 阶段2、§11.2 |
| R4-2 | 工具结果序列化提示：`getResult()` 返回 Object，Map/List 的 toString 非 JSON | §7.2.2.1 |
| R4-3 | `generateStreamIncremental`/chatJson 每次进入复位 `shouldStop=false`（防取消后下次调用立即终止） | §5.1 step 0' |
| R4-4 | 双 isGenerating guard 确认：并发安全由 Java 推理写锁串行化，无需额外处理 | §6.2 |
| R4-5 | 阶段 0 content 纯净性实测操作化（临时日志对比 parsed.content vs collectedText） | §9 阶段0 |

**v1.5 修订（第五轮审查 R5-1~R5-5，依据 review.md 第五轮）**

| # | 修正 | 落点 |
|---|---|---|
| R5-1 | JNI 静态导出确认：`nativeChatJson` 无需 RegisterNatives 注册 | §6.1 |
| R5-2 | parse 失败降级路径修正：发 `complete(content=collectedText)` 而非 error（与现状 native-lib.cpp:2366-2372 一致，避免丢输出）；空输出发 complete(content="") 由 Java 兜底 | §5.1 step 8/10 |
| R5-3 | onJson 每 token JSON 解析开销提示（可选子串预判） | §7.2.1 step d |
| R5-4 | 单元测试补项：onJson 状态机 / parse 降级 / 空输出兜底（KV 与 tool_call_id 由集成测试覆盖） | §11.1 |
| R5-5 | 全文结构终检确认（围栏/编号/交叉引用/行号引用一致） | — |

**v1.6 修订（全文终检 A1-A6）**

| # | 修正 | 落点 |
|---|---|---|
| A1 | 阶段 1 checklist 的"parse 失败发 error"残留 → 与 §5.1 step 8（R5-2）对齐：parse 失败降级 complete(collectedText)，不发 error | §9 阶段1 |
| A2 | step e 的 assistant 消息 content 明确用 complete.content（纯净正文，可为空），**禁用 raw 输出**（避免思考/JSON 双份渲染与泄漏） | §7.2.1 |
| A3 | buildRequestJson 示例的 id 兜底生成删除：id 必须来自执行工具前的补齐（R4-1），null 时跳过该条（防御），不再 nanoTime 临时生成 | §7.2.3 |
| A4 | §3.2 数据流图 `generateStream` → `generateStreamIncremental`（与 §3.1/§5.3 一致） | §3.2 |
| A5 | step 8/10 空输出与 parse 失败的互斥说明：complete 事件全轮只发一次 | §5.1 step 8/10 |
| A6 | §12.2 标题改为"审查修订记录（v1.0 → v1.6）" | §12.2 |

**v1.7 修订（第七轮审查 R7-1/R7-2，依据 review.md 第七轮）**

| # | 修正 | 落点 |
|---|---|---|
| R7-1 | chatJson 生成阶段失败错误传播：回调 isComplete+error 或 genOk=false → 发 `{"type":"error","message":lastError}`（修复现状 wrappedCallback 吞 error 缺陷，与 step 9 取消互斥） | §5.1 step 7、§9 阶段1 |
| R7-2 | §1.2 问题表补 P1-5：生成失败错误被吞（`native-lib.cpp:2330-2343`） | §1.2 |

**v1.8 修订（第八轮审查 R8-1~R8-3，依据 review.md 第八轮）**

| # | 修正 | 落点 |
|---|---|---|
| R8-1 | `enable_thinking` 默认值冲突修正：由调用方贯穿传递（startAgentLoop → processMessage → run → buildRequestJson），Agent 模式默认 false（产品决策 AIChatActivity:4547-4550，Qwen3-4B thinking+FC 不稳定）；顺带修复休眠引擎 179 行硬编码 | §4.1、§7.2.1、§7.2.3、§7.3、§7.4、§9 阶段2 |
| R8-2 | 复活完整性：`forceRunLocalAgent` 在 `localAgentEnabled=true` 时路由到本地 Agent | §7.5、§9 阶段2 |
| R8-3 | §5.1 step 1 改 `nlohmann::ordered_json::parse`（与 parse_oaicompat 参数类型一致） | §5.1 |

**v1.9 修订（第九轮审查 R9-1/R9-2，依据 review.md 第九轮）**

| # | 修正 | 落点 |
|---|---|---|
| R9-1 | token 事件 UTF-8 完整性：chatJson step 7 加 splitUtf8Complete + utf8Buffer 缓冲（token 事件发完整前缀，collectedText 累积原始字节；防 nlohmann dump 遇非法 UTF-8 抛 type_error.316） | §5.1 step 7、§9 阶段1 |
| R9-2 | reasoning 事件注明仅 `enable_thinking=true` 时产生（R8-1 联动） | §4.2、§8.2 |

**v1.10 修订（第十轮审查 R10-1，依据 review.md 第十轮）**

| # | 修正 | 落点 |
|---|---|---|
| R10-1 | §5.1 step 4 的 enable_thinking 默认值残留"默认 true，F1" → 改为"缺失时默认 false（R8-1 后与产品决策一致）"；F1 修订记录行加注 | §5.1 step 4、§12.2 F1 行 |

**v1.11 修订（第十一轮收敛终检，依据 review.md 第十一轮）**

| # | 修正 | 落点 |
|---|---|---|
| R11-1 | §12.2 标题更新为"审查修订记录（v1.0 → v1.11）"；全文收敛扫描无实质性问题（围栏 52 成对、无残留矛盾/待办标记），声明**完全体方案** | §12.2、头部 |

**v1.12 实施决策（2026-08-22）**

| # | 决策 | 落点 |
|---|---|---|
| D1 | §5.6 命中率风险与 §7.2.1/A2 纯净 content 的冲突拍板：**第一阶段按 A2 执行**（纯净 content，禁用 raw）；阶段 3 实测命中率，低则评估折中方案（raw content + Java 侧不渲染 / `llama_memory_seq_rm` + 手动 pos）。不阻塞开工 | §5.6、§7.2.1 |
| D2 | 实施完成（阶段 1-2）：C++ `generateStreamIncremental`/`chatJson`/`nativeChatJson` + Java 层全部落地；Java 编译 BUILD SUCCESSFUL；C++ 待 NDK 构建机验证；阶段 3 待真机 | — |
| D3 | `MAX_TOOL_ROUNDS` **维持 4**：多步任务可用性（三工具串行需 3 轮）+ 去重/迭代上限/总时长预算已构成多重防线 + KV 增量后多轮成本下降，降 2 的必要性降低；**待阶段 3 实测**（auto 反复调工具观察项）后决定是否降 2 | §9 阶段2、§10.1 |
| D4 | 阶段 4 优化实施：is_partial 增量解析（`common_chat_parse(collectedText, true, pp)` 每 16 token 一次，检测到 tool_call 后锁定 is_tool_call=true，仅 auto/none 模式启用，required 恒定 true 无需检测）；+ AI 设置页新增 `useJsonProtocol`/`localAgentEnabled` 两个开关（AIServiceStatusActivity） | §5.2、§9 阶段4 |

### 12.3 旧接口保留清单

以下接口保留不动，非 Agent 场景继续使用：
- `LlamaHelper.generate(prompt, ...)` — 简单文本生成
- `LlamaHelper.generate(messages, ...)` — 消息列表生成
- `LlamaHelper.generateStream(...)` — 流式生成
- `LlamaHelper.generateWithTools(...)` — 旧 FC 接口（标记 @Deprecated）
- `LlamaHelper.generateStreamFromMessages(...)` — 消息列表流式

### 12.4 术语表

| 术语 | 说明 |
|---|---|
| FC | Function Calling，函数调用 |
| tool_call | 模型输出的一次工具调用，包含 id/name/arguments |
| tool_choice | 工具选择策略：auto(自动)/required(必须调)/none(不调) |
| common_chat_templates_apply | llama.cpp 的聊天模板应用函数，用模型内置 Jinja 模板格式化 messages+tools |
| common_chat_parse | llama.cpp 的输出解析函数，从模型输出提取 tool_calls/reasoning |
| KV cache | 键值缓存，增量解码时复用之前的计算结果 |
