# 本地推理 JSON 协议改造方案 — 可行性审查报告

> 审查对象：`.trae/specs/local-inference-json-protocol/spec.md`（v1.0）
> 审查方式：逐条核对方案断言与真实代码（仅静态取证，未运行/未改码）
> 结论：**方案总体可行**，关键技术前提（llama.cpp API、回调链、增量解码可行性）全部成立；发现 2 处方案事实错误、3 处需补的运行时验证、若干需修正的细节。

---

## 一、总体结论

| 维度 | 判定 | 说明 |
|---|---|---|
| llama.cpp API 依赖 | ✅ 全部存在 | `common_chat_*` 系列 6 个 API 在当前 `src/main/cpp/llama.cpp` 子模块中真实存在且签名与方案一致，且 **generateWithTools 已在生产路径使用其中 4 个** |
| 结构化消息支持 | ✅ 成立 | `common_chat_msgs_parse_oaicompat` 完整支持 assistant.tool_calls / tool.tool_call_id / reasoning_content |
| KV 增量解码 | ✅ 可行且已有先例 | `llama_batch_get_one` 的 pos 自动续接（`llama-batch.cpp:90-118`）；且同文件 `NativeChatContext.chatSend` 已有"文本增量 + KV 跨轮保留"的成熟实现 |
| 回调协议简化 | ✅ 合理 | 现 JNI 确实是 onComplete/onToolCalls/onReasoning 三次独立回调（`native-lib.cpp:5882/5977/5994`），统一 onJson 能消除顺序问题 |
| UI 零改动 | ✅ 基本成立 | AgentChatHandler/ChatAdapter 只转发，`onToken` 收到的是净文本即可 |
| 方案事实错误 | ⚠️ 2 处 | `AIConfig.useJsonProtocol` 不存在；`AIToolResult.getError()` 实为 `getErrorMessage()` |
| 需运行时验证 | ⚠️ 3 处 | `common_chat_parse` 的 content 纯净性；KV 前缀命中率（模板渲染一致性）；增量性能收益实测 |

---

## 二、断言核对表（逐条）

### 2.1 问题清单（§1.2）

| 方案条目 | 方案位置 | 实际位置 | 判定 |
|---|---|---|---|
| P0-1 tool_choice 硬编码 REQUIRED | `native-lib.cpp:2293` | **2293 行，完全一致** | ✅ 精确 |
| P0-2 KV cache 每次软清除 | `native-lib.cpp:1953-1961` | 1953 `llama_get_memory` / 1957 `llama_memory_clear(mem, false)` / 1961 else | ✅ 一致（块范围正确） |
| P0-3 tool_call JSON 逐 token 发 onToken | generateWithTools wrappedCallback | `native-lib.cpp:2329-2343` wrappedCallback 把非 complete 的 token 全部转发 → JNI onToken（5904） | ✅ 成立 |
| P0-4 streamDirectAnswer 4 字符切片 | `AgentLoopEngine.java:768` | **768 行 `int chunkSize = 4`** | ✅ 精确 |
| P1-1 回调碎片化 3 次独立发 | JNI 层 | onComplete=5882（由 `generateWithTools` 末尾 2372 触发）、onToolCalls=5977、onReasoning=5994 | ✅ 成立（顺序见 3.4 修正） |
| P1-2 JNI 样板 60+ 行 | `native-lib.cpp:5933-5985` | 实际 5933-6003（ArrayList/SetObjectField 构造 + onToolCalls/onReasoning 分发），约 70 行 | ✅ 基本一致 |
| P1-3 工具执行走 onlineToolManager | `AgentLoopEngine.java:309` | **309 行 `onlineToolManager.executeTool(tc.id, toolName, argsStr)`** | ✅ 精确 |
| P1-4 消息结构扁平 | generateWithTools 入参 | `vector<pair<role,content>>`（native-lib.cpp:2242），Java 侧 ChatMessage 仅 role+content（AgentLoopEngine.java:1083） | ✅ 成立 |
| "已确认非问题"：llama-bridge.cpp 旧实现不影响主路径 | §1.2 注 | llama-bridge.cpp（391 行）是独立静态模型/ctx 的旧实现，无 common_chat 调用；但**它确实参与编译**（CMakeLists.txt:191） | ✅ 行为成立，表述需微调（见 4.4） |

### 2.2 已有能力确认（§1.3）

| 方案声称 | 实际 | 判定 |
|---|---|---|
| `common_chat_msg` 含 tool_calls/tool_call_id | `common/chat.h:81-129`，字段齐全（含 content_parts、reasoning_content、tool_name） | ✅ |
| `common_chat_msgs_parse_oaicompat(json)` | `common/chat.cpp:372-471`，支持 tool_calls（417-446）、tool_call_id（458-460）、reasoning_content（452-454）、content 字符串/数组 | ✅ |
| `common_chat_tools_parse_oaicompat(json)` | `common/chat.cpp:532+` | ✅ |
| `common_chat_tool_choice_parse_oaicompat(str)` | `common/chat.cpp:344-355`，"auto/none/required"，非法值 throw | ✅ |
| `common_chat_parse(text, is_partial, params)` | `common/chat.cpp:3427-3431`，转 PEG 解析 | ✅ |
| `common_chat_msg_diff::compute_diffs()` | `common/chat.h:137` | ✅ |
| `common_chat_templates_apply` | `common/chat.h:325`；native-lib.cpp:2306 已在用 | ✅ |
| `common_chat_templates_inputs` 字段 | `common/chat.h:250-262`：add_generation_prompt/use_jinja/tool_choice/parallel_tool_calls/enable_thinking 全有；**tool_choice 默认是 AUTO**（259），当前 REQUIRED 是 native-lib 显式设置 | ✅ |

### 2.3 阶段 0 四项待确认（§九）

| 待确认项 | 静态结论 |
|---|---|
| `common_chat_msgs_parse_oaicompat` 处理 tool_calls/tool_call_id | ✅ **代码层面已确认支持**（见 2.2）。可在实施时用真实模型跑一轮冒烟验证 |
| `common_chat_parse` 的 content 是否纯净 | ⚠️ **静态无法确认**。content 由 PEG 语法按格式生成，取决于模板格式（FUNC_HERMES/FUNC_QWEN 等）的语法定义；且**当前生产代码从未使用 parsed.content**（Agent 用的是 collectedText 原文，parse 只取 tool_calls/reasoning）→ 必须运行时实测（见 3.5） |
| nlohmann::json 已包含 | ✅ `native-lib.cpp:22` `#include <nlohmann/json.hpp>`；2278 行已在用 `nlohmann::ordered_json` |
| `llama_batch.pos` 可手动设置 | ✅ 可手动（`llama_batch_init` 分配 pos 数组，`llama_decode` 非空时直接使用，见 `llama-kv-cache.cpp:2253`、`server-context.cpp:186`）；**但增量模式不需要**——`pos==nullptr` 时 llama_decode 自动从 `memory->seq_pos_max(s)+1` 续接（`llama-batch.cpp:90-118`），与增量诉求天然吻合（见 3.2） |

### 2.4 采样器（§5.3）

`native-lib.cpp:1908-1915` 与方案引用**逐行一致**（temp<=0 → greedy；否则 top_k/top_p/temp/dist 链）。§7.2.2 变更表中"采样参数 传了但 C++ 没用（误判，实际已生效）"这一行自相矛盾——已核实**实际生效**，建议实施时把该行简化为"无需修改"。

---

## 三、关键技术验证点

### 3.1 llama.cpp API 依赖全部落地 ✅

方案新增 C++ 函数 `chatJson` 用到的所有 API 均在当前子模块存在，且其中 4 个（templates_init/apply、tools_parse、parse、tool_choice_parse）已在 `generateWithTools`（2242-2375）生产路径使用并通过编译——**不存在"引用了不存在的 API"风险**。

### 3.2 KV 增量解码：可行性确认 + 无需手动 pos ✅

- 生成循环当前用 `llama_batch_get_one`（pos=nullptr，seq=0）。llama_decode 在 pos 为空时自动按 `seq_pos_max(s)+1` 续接位置（`llama-batch.cpp:90-118`）。
- 因此 **chatJson 只要在增量命中时跳过 `llama_memory_clear`（1957）并只解码 delta token，位置自动接在缓存前缀之后**，`llama_batch.pos` 无需手动设置。阶段 0 第 4 项可勾掉并注明"无需手动 pos"。
- 方案 5.6 的命中条件 `matchedLen == cachedTokens.size()`（严格超集）是**正确且必要**的：自动续接只能用于"旧 token 全部仍在"的场景；部分前缀（如裁剪/换会话）若不清缓存直接续接会造成位置错乱，必须全量重 eval 或先用 `llama_memory_seq_rm`（llama.h:735）删尾部 cell。方案已把"裁剪/切换"列为失效条件 → 设计安全。

### 3.3 已有增量机制先例（方案未提，强烈建议参考）✅

`native-lib.cpp` 的 `NativeChatContext`（chatSend 路径，约 2965-3900）**已经在做文本级增量 + KV 跨轮保留**：
- `prev_formatted_len`：新格式化文本若以旧文本为前缀，只 eval `substr(prev_formatted_len)` 的增量（3391-3397）；
- `total_tokens_in_kv` / `current_pos` / `turns` 维护 KV 位置状态，跨轮**不调 llama_memory_clear**（encodeTokens 3085-3114 用 llama_batch_get_one 分块解码）；
- `shiftContext()` 做上下文满时的裁剪。

这证明：(a) 该 llama.cpp 构建下"KV 跨轮保留 + 增量 eval"路径可跑通；(b) 方案 5.6 的 `generateStreamIncremental` 可参考 chatSend 的成熟写法（尤其位置记账与上下文满处理），不必从零设计。注意 Agent 路径（generateWithTools→generateStream）与 chatSend 路径各自独立，互不影响。

### 3.4 回调顺序事实修正（P1-1）⚠️

实测顺序：**onToken × N → onComplete → onToolCalls → onReasoning**（onComplete 由 `generateWithTools` 末尾 2372 行 `callback(collectedText, true, "")` 触发，早于 JNI 在 5977/5994 行的 onToolCalls/onReasoning）。因此：

- 方案 P1-1 说"顺序不确定"**不准确**——顺序是固定的，只是**与 `LlamaHelper.java:1386` 的 TokenCallback javadoc（"onToolCalls 在 onComplete 之前调用"）相矛盾**；
- Java 侧当前靠"单线程同步执行 + holder 缓冲"才没踩坑（latch 在 onComplete 时 countDown，随后读 holders 时 onToolCalls/onReasoning 已在 native 调用内执行完）；
- 统一 onJson 协议恰好消除这类契约矛盾，**这是该方案最实在的价值点之一**。

### 3.5 content 纯净性：唯一必须实测的阻塞项 ⚠️

- `common_chat_parse` 转 PEG 解析（chat.cpp:3427-3509），content 由格式专属 mapper 从 AST 生成；tool_call 文本是否残留进 content 取决于模板对应格式（FUNC_HERMES / FUNC_QWEN / PEG 等）的语法；
- 关键事实：**当前生产代码从不读 parsed.content**（Agent 的回答文本来自 collectedText 原文，parse 仅取 tool_calls/reasoning），所以这是新协议第一次依赖 parsed.content——若 content 带 tool_call JSON 残留，会污染 complete 回调与历史回灌。**建议阶段 0 用目标模型（如 Qwen3）实跑一次 required 模式验证后再开工**，方案 10.1 风险表第 2 行已覆盖此风险，应对正确。

### 3.6 KV 前缀命中率风险（新增，方案未充分评估）⚠️

方案假设"Agent 循环中历史只追加不修改，每轮都命中增量模式"。但增量命中要求**逐 token 前缀完全一致**，而这取决于 chat 模板的确定性渲染：

- 新协议把 assistant.tool_calls 以**结构化字段**传入 → 模板按自身规则渲染 tool_call（如 Qwen 的 `<tool_call>...</tool_call>` 或 JSON 块），**不一定逐字节等于上一轮模型原始生成的文本**；
- 若不一致 → 前缀匹配失败 → 回退全量 eval，P0-2 的性能收益在工具轮次消失（正确性不受影响）。
- 缓解：回灌时同时保留原始生成文本（content 用 raw 输出，tool_calls 结构仅作辅助）；或在阶段 3 实测命中率，命中率低则接受回退。**建议把这条加入 10.1 风险表。**

---

## 四、发现的方案错误与需修正项

| # | 类型 | 内容 | 修正建议 |
|---|---|---|---|
| 4.1 | **事实错误** | §10.2 回退开关 `aiConfig.useJsonProtocol` **不存在**（AIConfig.java 无此字段，现有字段见 50-59 行） | 需在 AIConfig 新增字段 + SharedPreferences 存取，方案补上该改动项 |
| 4.2 | **事实错误** | §7.2.2.1 写 `AIToolResult.getError()`，实际是 `getErrorMessage()`（AIToolResult.java:97）；且 `getResult()` 返回 `Object`（:90），拼 resultText 需 `String.valueOf` | 修正方法名并加类型转换 |
| 4.3 | 表述错误 | P1-1"顺序不确定" → 实际固定 onComplete 先于 onToolCalls/onReasoning | 改为"顺序与接口文档矛盾" |
| 4.4 | 表述微调 | "llama-bridge.cpp 不影响主路径"行为正确，但该文件在 CMakeLists.txt:191 参与编译 | 结论保留，注明"虽参与编译但无 common_chat 依赖" |
| 4.5 | 内部不一致 | §3.2 数据流图写"tool_call 模式下 token 被吞"，但 §4.2/§5.1 是"每个 token 都发（is_tool_call 标记），Java 侧吞" | 统一为后者（C++ 全发 + Java 忽略） |
| 4.6 | 遗漏 | 新 `nativeChatJson` 应接入现有 `SAFE_RUN_INFERENCE` 宏（native-lib.cpp:59，sigsetjmp 崩溃恢复 + isGenerating 复位 + EnsureLocalCapacity(256)），否则崩溃后 isGenerating 卡死 | 方案 6.1 流程补第 0 步 |
| 4.7 | 遗漏 | 现 JNI 硬编码 `OnlineInferenceService$ToolCallInfo` 类（native-lib.cpp:5842）——onJson 协议顺带消除该耦合，值得在方案里写明（回退旧接口时仍需保留） | 已在 6.1 提及"旧函数保留"，补一句耦合说明即可 |
| 4.8 | 产品取舍 | MAX_TOOL_ROUNDS 4→2 会让多步任务（定位→天气→回答）更易被循环保护提前截断 | 确认是产品决策；建议保留循环保护其他项（去重/提示语）不动 |
| 4.9 | 性能假设 | §5.6 性能表假设 eval 速度 ≈ 生成速度（11 t/s），实测中 prompt eval 通常快于生成 | 数字仅作量级参考，阶段 3 用真实基准校准 |
| 4.10 | 小问题 | §7.1 `chatJson` 需与现有接口一样持推理写锁（`inferenceLock`，LlamaHelper.java:397-435 模式） | 方案已写"推理锁"，确认与 generateWithTools 同锁即可 |

---

## 五、风险修正（在方案 10.1 基础上）

| 风险 | 原判定 | 审查后判定 |
|---|---|---|
| `common_chat_msgs_parse_oaicompat` 不支持 tool 消息 | 中概率 | **降为低**：静态代码已确认支持（chat.cpp:458-460），仅需冒烟验证 |
| `common_chat_parse` content 含残留 | 中概率 | **保持中，且是唯一阻塞性运行时验证项**（见 3.5） |
| 4B 模型 auto 模式反复调工具 | 中概率 | 保持中；MAX_TOOL_ROUNDS=2 + 现有循环保护可兜底 |
| KV 前缀匹配失败 | 中概率 | **补充：结构化 tool_calls 渲染与原始生成流可能不一致**（3.6），命中率需实测；正确性不受影响 |
| `llama_batch.pos` 手动设置错乱 | 低概率 | **降为低且基本可消除**：采用 pos=nullptr 自动续接即可，无需手动 pos（3.2） |
| JNI 回调线程安全 | 低 | 保持低；复用现有 AttachCurrentThread 包装（native-lib.cpp:5850-5921 模式）即可 |
| nlohmann 解析异常 | 低 | 保持低；全链路 try-catch |
| auto 模式 UI 短暂闪烁 | 低 | 保持低；第二阶段增量解析可选 |

---

## 六、实施建议

1. **阶段 0 实际只需补 1 个运行时验证**：`common_chat_parse` 的 content 纯净性（用目标模型 required 模式跑一次）；其余 3 项静态已确认。
2. **C++ 侧（阶段 1）**：`generateStreamIncremental` 强烈建议对照 `NativeChatContext`（chatSend）的成熟写法（位置记账、分块解码、上下文满处理），不要从零写；`llama_batch.pos` 无需手动设置。
3. **JNI 侧**：`nativeChatJson` 直接套 `SAFE_RUN_INFERENCE` + 现有 UTF-8 拆分回调包装（5850-5921 可复用）。
4. **Java 侧（阶段 2）**：先补 AIConfig.useJsonProtocol 字段；AIToolResult 用 `getErrorMessage()`；`chatJson` 持同一把推理写锁；MAX_TOOL_ROUNDS 改 2 前确认产品意图。
5. **联调（阶段 3）**：把"KV 增量命中率"与"content 纯净性"列为必测项；性能基准用实测校准 11 t/s 假设。
6. 方案"旧接口保留 + UnsatisfiedLinkError 回退 + 配置开关"的三重回退设计成立，无需改动。

---

## 附：取证文件与行号速查

- `src/main/cpp/native-lib.cpp`：采样器 1908-1915；KV 清除 1953-1961；tool_choice 2293；wrappedCallback 2329-2343；parse 2352-2354；onComplete 触发 2372；JNI 回调 5850-5921；onToolCalls/onReasoning 5933-6003；SAFE_RUN_INFERENCE 59；NativeChatContext 增量 2965-3900（chatSend 3380+、encodeTokens 3085、增量截取 3391-3397）
- `src/main/cpp/llama.cpp/common/chat.h`：tool_call 28-36；msg 81-129；inputs 250-262；parse_oaicompat 348-355
- `src/main/cpp/llama.cpp/common/chat.cpp`：tool_choice 344-355；msgs_parse 372-471；tools_parse 532+；common_chat_parse 3427-3431
- `src/main/cpp/llama.cpp/src/llama-batch.cpp`：pos 自动续接 90-118
- `src/main/cpp/llama.cpp/include/llama.h`：llama_batch_get_one 935-937；llama_memory_clear 726；llama_memory_seq_rm 735
- `src/main/java/.../agent/software/engine/AgentLoopEngine.java`：MAX_TOOL_ROUNDS 50；generateWithToolsSync 358-461；onlineToolManager 309；streamDirectAnswer 768-775；trimHistoryToFit 610-635；ChatMessage 1083；executeToolSafely 967 / jsonToMap 1014（均存在但主路径未用）
- `src/main/java/.../jni/LlamaHelper.java`：TokenCallback 1380-1394（javadoc 顺序矛盾 1386）；推理锁 397+；generateWithTools 563-600
- `src/main/java/.../agent/software/AgentSoftwareLayer.java`：AgentCallback 43-52；桥接 63-124
- `src/main/java/.../chat/AgentChatHandler.java`：AgentChatCallback 27-41；softwareLayer 桥接 68-134；onComplete(AgentResponse)→finalAnswer 115-119
- `src/main/java/.../refactor/AIConfig.java`：无 useJsonProtocol（50-59 现有字段）
- `src/main/java/.../tool/AIToolResult.java`：isSuccess 83 / getResult 90 / getErrorMessage 97
- `src/main/cpp/llama-bridge.cpp`：独立旧实现（391 行，无 common_chat）；CMakeLists.txt:191 参与编译

---

# 第二轮审查（spec.md v1.1 修订后）

> 审查对象：v1.1 修订版（含 review.md 第一轮全部修正）
> 审查方式：对修订后文档做一致性复查 + 补充此前未深入的验证点（请求 schema 字段对齐、工具选择/思考/降级路径在伪代码中的完整性、UI 流式行为、tool_choice 与模板渲染的关系）
> 结论：v1.1 修订正确；新增 **3 处方案缺口（1 处必改、2 处建议改）**、若干表述修正；同时**正面确认 1 处此前存疑点不成立**

---

## 七、新增发现（F1-F10）

### F1【必改】请求 schema 缺 `enable_thinking` 字段（字段对齐断裂）

- §4.1 请求 JSON 与字段表（184-193）**无 `enable_thinking`**；
- 但 §5.1 C++ 流程第 5 步用 `inputs.enable_thinking = supports_thinking && enableThinking`（278 行）——`enableThinking` 在 chatJson 中无来源；
- 现状：`AgentLoopEngine.java:179` 恒传 thinking=true。
- **建议**：§4.1 增补 `enable_thinking`（bool，默认 true 保持现行为）；§5.1 第 5 步注明取自请求字段；§7.2.1 buildRequestJson 一并带出。

### F2【建议改】首轮 `tool_choice="required"` 无条件，tools 为空时未定义

- §7.2.1 伪代码 `tool_choice = iteration==1 ? "required" : "auto"`（544 行）——若 selectedTools 为空（纯聊天）仍发 required；
- 现状 C++ 是 `tools.empty() ? COMMON_CHAT_TOOL_CHOICE_NONE : REQUIRED`（native-lib.cpp:2293），空 tools 用 NONE；
- **建议**：改为"首轮且 tools 非空 → required；tools 空 → auto/none"。

### F3【必改】§7.2.2.1 示例代码 `jsonToMap(argsStr)` 编译不过

- 现有辅助方法签名是 `jsonToMap(JSONObject)`（AgentLoopEngine.java:1014），示例传的是 `String argsStr`；
- **建议**：改为 `jsonToMap(new JSONObject(argsStr))`（与现有 executeToolSafely 968 行用法一致），并包 try-catch 失败回 `{}`。

### F4【建议改】首轮 required 但模型未输出 tool_call 的降级路径未保留

- 现状 `AgentLoopEngine.java:224-240` 有"iteration==1 && genResult.toolCalls==null → prompt 模式重试"；
- §7.2.1 新伪代码未保留；§7.2.5 只写了 UnsatisfiedLinkError 降级；
- **建议**：明确保留（首轮 chatJson 返回无 tool_call 且 content 为空时，走 generateFallbackPrompt 重试），或显式声明删除。

### F5【表述修正】思考 token 在流式期间仍进主气泡，§8.2 描述不完整

- chatJson 把**所有** token（含 `<think>` 区文本）以 `is_tool_call=false` 发出（§5.1 step 7）；`is_tool_call` 只吞 tool_call JSON，不处理思考文本；
- UI 侧 `appendToken`（ChatAdapter.java:3006-3016）原始追加进主气泡，**流式期间思考文本（含标签）会显示在主气泡**——这是现状行为（generateWithTools 同样全量流式），非新协议回归；
- 改善点：新协议 `complete.content` 来自 `parsed.content`（纯净，无 think 标签），优于现状的 cleanResponse 正则清理；
- **建议**：§8.2 注明"流式期间思考文本仍临时显示在主气泡，complete 后以纯净 content 落定；可选优化：Java 流式时剥 `<think>` 区，或第二阶段用 is_partial 增量解析抑制"。

### F6【表述修正】§5.3 末尾仍写"chatJson 调用 generateStream"

- 331 行"chatJson 调用 `generateStream` 时传入正确参数即可"与 v1.1 的 `generateStreamIncremental` 调用链不一致（§3.1 图已改）；
- **建议**：改为 `generateStreamIncremental`。

### F7【正面确认】tool_choice 不影响渲染文本 → required→auto 切换不会导致 KV 前缀失配

- 已核实 `common_chat_templates_apply`：`params.tool_choice = inputs.tool_choice`（chat.cpp:3229），tool_choice 只进 **grammar/parser 规则**（min_calls=1 for REQUIRED，如 1063-1090、1665-1687），**不参与 prompt 文本渲染**；
- 且当前 `generateWithTools` 只取 `chat_params.prompt`（2345 行），grammar 未应用——REQUIRED 的约束仅体现在 parse 期望；
- **结论**：首轮(required)→二轮(auto)的 KV 前缀命中只取决于消息历史与模板渲染，**tool_choice 切换本身不破坏命中**（与 §5.6 已记录的命中率风险正交）。可补进 §5.6 命中率风险说明。

### F8【提示】`parallel_tool_calls` 不进请求 schema

- §5.1 step 5 硬编码 `inputs.parallel_tool_calls = true`；§4.1 无该字段。无配置需求，可接受；如需暴露再加。

### F9【提示】max_tokens 首轮 500 足够输出 tool_call

- 现状 generateWithToolsSync 传 1500（179 行）；新方案首轮 500/后续 1000（§7.2.1）。首轮只输出一个 tool_call JSON（一般 <200 token），500 足够；后续 1000 与现状一致。

### F10【提示】Java onJson 状态机需幂等保护

- error 可在任意时刻到达（§4.3），error 后不应再处理后续事件；UI 已有 completed CAS（AIChatActivity.java:5696），建议 AgentLoopEngine 的 onJson 状态机同样加"已结束"标志，防 error 后迟到事件。

---

## 八、第二轮结论

| # | 类型 | 处置建议 |
|---|---|---|
| F1 | 必改（schema 缺口） | v1.2 补 `enable_thinking` 字段 |
| F3 | 必改（示例代码编译不过） | v1.2 改 `jsonToMap(new JSONObject(argsStr))` |
| F2 / F4 | 建议改（边界行为） | v1.2 明确空 tools 与首轮空 tool_call 的处理 |
| F5 / F6 | 表述修正 | v1.2 同步 §8.2 / §5.3 措辞 |
| F7 | 正面确认 | 可并入 §5.6 说明（tool_choice 不影响前缀命中） |
| F8-F10 | 提示 | 可选并入 |

第一轮 10 项修正经复查全部正确落地；v1.1 无回归。

---

# 第三轮审查（spec.md v1.2 并入后，架构层）

> 审查对象：v1.2 修订版 + 调用链活性核查
> 审查方式：从"谁真正调用 AgentLoopEngine"出发反向追踪全链路（入口活性 + 取消/并发/缓存联动）
> 结论：v1.2 的 F1-F10 并入正确；发现 **1 个方案级前提问题（本地 Agent 链当前禁用/不可达）**、**1 个取消链路断裂**、**1 个 KV 缓存联动风险** 及若干补充点

---

## 九、第三轮发现（R3-1 ~ R3-6）

### R3-1【方案级前提，最重要】本地 Agent 链（AgentLoopEngine）当前禁用且不可达

反向追踪 `AgentLoopEngine` 的全部调用方：

| 上游 | 状态 | 证据 |
|---|---|---|
| `AgentSoftwareLayer`（processMessage → loopEngine.run） | 仅被两处构造 | AgentChatHandler:67（见下）、AgentExecutionEngine:150 |
| `AgentChatHandler.softwareLayer` | **死代码**：构造 + setCallback 后无任何 processMessage 调用 | AgentChatHandler.java:48/67/68；全仓库 `processMessage(` 仅 3 处（AgentExecutionEngine:155、ThinkingAssistantEngine:72 不同类、定义处） |
| `AgentExecutionEngine` | **从未被实例化** | 全仓库 `AgentExecutionEngine` 仅 5 处匹配，全在本文件/注释内 |
| `AgentRouter.execute` | 仅在线引擎 | AgentRouter.java:71-81 注释"本地模型已被上层降级普通对话，不进入本路由" |
| `AIChatActivity.forceRunLocalAgent` | 只显示"🚫 本地 Agent 已禁用" | AIChatActivity.java:4357-4359 |
| 欢迎引导 | "本地模型 — 普通对话：离线可用" | AIChatActivity.java:4374 |

**结论**：spec §1.1 现状表与 §3.1 调用链图（AIChatActivity → AgentChatHandler → AgentSoftwareLayer → AgentLoopEngine）描述的是**遗留/休眠链路**，当前没有任何活跃代码路径会执行 `generateWithTools`。方案改造的对象（AgentLoopEngine）目前是死代码。

**影响与建议**：
1. 方案需明确"复活路径"：改造完成后由谁调用？候选：a) 复活 `AgentExecutionEngine`（执行面板 UI）；b) 复活 `AgentChatHandler.softwareLayer`（聊天气泡）；c) 维持禁用，把 JSON 协议作为基础设施备好（仅当未来启用时生效）。
2. §3.1 调用链图与 §8 需按实际目标入口改写；§8 的"UI 零改动"对象应从 AgentChatHandler.AgentChatCallback 改为实际入口（AgentExecutionView/ExecutionEvent 或聊天气泡桥接）。
3. 阶段 3 联调**必须先复活一个入口**，否则 UI 验证项无法执行。
4. 顺带说明：P0/P1 问题描述的是休眠路径的性能/体验问题——是否仍值得投入，取决于"复活本地 Agent"的产品决策（本报告假定答案是肯定的，方案按复活准备）。

### R3-2【取消链路断裂】cancel 不停止 native 生成

- `AgentExecutionEngine.cancel(messageId)`（346-357）：只移除 activeExecutions、state.cancel()、发事件——**不调用 `LlamaHelper.stopGeneration()`，不通知 softwareLayer**；
- `AgentSoftwareLayer.cancel()`（224-226）：仅 `isProcessing.set(false)`，executor 线程继续阻塞在 native 调用；
- 只有 native 层 `shouldStop`（`nativeStopGeneration` 置位，generateStream 循环 2038 检查）能真正打断生成；
- **新 `chatJson` 继承同一问题**（同步阻塞，取消同样无效）。
- **建议**：方案补"取消"设计点——取消链路：UI cancel → AgentExecutionEngine.cancel → `LlamaHelper.stopGeneration()`（native shouldStop → generateStreamIncremental 退出）→ 回调侧由 activeExecutions/isProcessing 拦截；chatJson 在 shouldStop 时发 `{"type":"error","message":"cancelled"}`（或 complete-with-stop 标记）并结束。

### R3-3【chatJson 缺 isGenerating 并发守卫】

- `generateStream` 开头有 `isGenerating.exchange(true)` + GeneratingGuard RAII（1878-1887）防同上下文并发推理；§5.1 chatJson 流程未提。
- **建议**：chatJson 复用同一守卫（与 generateStream 互斥），SAFE_RUN_INFERENCE 崩溃恢复已能复位（第一阶段已补）。

### R3-4【KV 增量缓存与并行清 KV 路径无联动（正确性风险）】

- `llama_memory_clear` 在本文件多达 7+ 处调用点（1527/1687/1758/2519/3048/3155/3835），且 `NativeChatContext.chatSend`/`clearHistory` 与 `InferenceContext` 共享同一 llama_context（`initFromExistingContext` 复用）；
- 若任一并行路径清了 KV（如用户清空聊天上下文、chatSend shift、普通 generate），`generateStreamIncremental` 的 `kvCacheValid=true` 仍成立，**增量模式会在错误的 KV 位置上续写**（位置错乱 → 生成乱码，属 R3 新风险，v1.2 的失效条件未覆盖）；
- **建议（推荐做法）**：增量命中前置增加一致性校验——`kvCacheValid` 时校验 `llama_memory_seq_pos_max(0) == cachedNPast - 1`，不一致则全量重 eval 并复位缓存。这比枚举所有清 KV 调用点更稳健。

### R3-5【模板 fallback 后 KV 缓存失效】

- §5.1 step 6：`common_chat_templates_apply` 失败 → fallback `generateStreamFromMessages` → 内部 `generateStream` 会清 KV，但 `cachedTokens/kvCacheValid` 未复位 → 下次 chatJson 前缀匹配可能误判（配合 R3-4 的 seq_pos_max 校验可一并覆盖）。
- **建议**：fallback 路径结束后 `kvCacheValid = false`。

### R3-6【阶段 3 联调前置】需先复活入口（承接 R3-1）

- 阶段 3 的 UI 验证项（无裸 JSON/思考显示/工具气泡）依赖真实入口；建议阶段 2 之前先落地 R3-1 的入口复活（最小化：AgentExecutionEngine 或聊天气泡桥接任选其一）。

---

## 十、第三轮结论

| # | 类型 | 处置建议 |
|---|---|---|
| R3-1 | 方案级前提 | v1.3 明确本地 Agent 复活路径；§1.1/§3.1/§8 按实际入口改写 |
| R3-2 | 取消链路 | v1.3 补取消设计（UI → stopGeneration → shouldStop → chatJson error/终止） |
| R3-3 | 并发守卫 | v1.3 §5.1 补 isGenerating 守卫 |
| R3-4 | 正确性风险 | v1.3 §5.6 补 seq_pos_max 一致性校验（或全路径失效联动） |
| R3-5 | 边界 | v1.3 §5.1 fallback 后 kvCacheValid=false |
| R3-6 | 联调前置 | 阶段 2 前复活入口（承接 R3-1） |

v1.2 的 F1-F10 并入无回归；第三轮未发现协议本身（JSON schema / 回调 / KV 增量机制）的设计错误，问题集中在"入口活性、取消、缓存联动"三个工程层面。

---

# 第四轮审查（spec.md v1.3 并入后，协议细节）

> 审查对象：v1.3 修订版
> 审查方式：文档结构完整性检查（围栏/标题）+ 协议层细节核对（tool 消息 id 匹配、工具结果序列化、状态复位、实测操作化）
> 结论：v1.3 六项并入无回归，文档结构完整；新增 **1 处必改级协议细节（R4-1）**、3 处提示、2 处确认

---

## 十一、第四轮发现（R4-1 ~ R4-7）

### R4-1【必改级】tool 消息必须带匹配的 `tool_call_id`

- llama.cpp 渲染依赖 tool 消息的 `tool_call_id`：GLM4 系模板按 id 匹配 assistant tool_calls 与 tool 结果并**重排顺序**（`common/chat.cpp:2073-2089`，`call_order.find(m.value("tool_call_id",""))`），id 缺失/不匹配时排序退化为 0、多工具场景渲染错乱；OpenAI 兼容格式本身也要求 tool 消息必有 tool_call_id；
- §7.2.1 step e 只写"追加 assistant(tool_calls) + tool(result) 到 messages"，**未明确 tool 消息带 toolCallId**；
- 现状 `AgentLoopEngine.java:315` 的 tool 消息恰好无 id（扁平模式的 P1-4 表现之一），新协议必须修复；
- **建议**：step e 明确"tool 消息 = `new ChatMessage("tool", resultStr, tc.id, true)`（§7.2.4 构造器），id 与对应 assistant tool_calls 的 id 一致"；`buildRequestJson` 已支持 toolCallId 字段（§7.2.3 的 `m.toolCallId` 分支）。

### R4-2【提示】工具结果序列化：`getResult()` 是 Object

- `AIToolResult.getResult()` 返回 Object（可能 ComponentData/Map/List/String）；`String.valueOf()` 对 Map/List 得到 Java toString 而非 JSON；
- 回灌 messages 是文本截断（MAX_TOOL_RESULT_LENGTH）——与现状一致，组件信息本就未走本地 Agent 回灌，超出本方案范围；如需结构化结果用 `JSONObject` 序列化。提示即可。

### R4-3【提示】`generateStreamIncremental` 每次进入需复位 `shouldStop=false`

- 现状 `generateStream` 每次调用开头 `shouldStop = false`（1903 行）；`generateStreamIncremental` 必须同样处理，否则取消（R3-2 的 stopGeneration 置位）后**下一次调用会立即终止**。
- 建议：§5.1 step 0 或 generateStreamIncremental 实现内补复位。

### R4-4【提示】双 `isGenerating` 标志（InferenceContext 与 NativeChatContext 各自独立）

- 两个上下文各有自己的 isGenerating guard，native 层互不感知；Java 侧 `LlamaHelper.inferenceLock` 写锁已串行化全部推理调用（generateWithTools/chatSend/chatJson 同锁），并发风险已封口；SAFE_RUN_INFERENCE 崩溃恢复能复位各自标志。无需改动，确认即可。

### R4-5【改进】阶段 0 content 纯净性实测操作化

- 阶段 0 该项目前只写"需实测"；建议给出具体做法：在现有 `generateWithTools` 路径（native-lib.cpp:2352-2354）临时加日志，对比 `parsed.content` 与 `collectedText`（工具轮与回答轮各一次），确认 content 无 tool_call JSON / 思考标签残留。

### R4-6【确认】请求构建无隐藏问题

- `new JSONArray(toolsJson)` 正确（org.json 数组串构造器）；
- assistant tool_call 消息 content 空串可接受：`common_chat_msgs_parse_oaicompat` 接受（chat.cpp:391-415），`workaround::requires_non_null_content`（3268-3273）还会把 null 归一为空串。

### R4-7【确认】v1.3 文档完整性

- 代码围栏 52 个成对、EOF 平衡；标题结构无重复编号；§1.1.1/§3.1.1/§7.3/§7.4/§8/§9/§10.1/§12.1/§12.2 的 R3 引用相互一致。

---

## 十二、第四轮结论

| # | 类型 | 处置建议 |
|---|---|---|
| R4-1 | 必改（协议细节） | v1.4 明确 tool 消息带匹配 tool_call_id（§7.2.1/§7.2.4） |
| R4-2 / R4-3 / R4-4 | 提示 | 可并入 v1.4 备注（序列化提示、shouldStop 复位、双 guard 确认） |
| R4-5 | 改进 | 阶段 0 补实测操作步骤 |
| R4-6 / R4-7 | 确认 | 无需改动 |

三至四轮累计：协议设计无根本性问题；R4-1 是协议落地的最后一块拼图（tool 消息 id 匹配），补上即可进入实施。

---

# 第五轮审查（spec.md v1.4 并入后，实施前终检）

> 审查对象：v1.4 修订版
> 审查方式：JNI 注册机制核查、parse 失败降级路径对照、Java 侧解析开销评估、测试计划完整性、全文结构终检
> 结论：v1.4 并入无回归；新增 **1 处必改级降级路径修正（R5-2）**、1 处确认、2 处提示、1 处改进；四轮累计后方案达到实施就绪

---

## 十三、第五轮发现（R5-1 ~ R5-5）

### R5-1【确认】JNI 静态导出，新增 `nativeChatJson` 无需注册

- 项目 JNI 采用**静态导出命名**（`Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeXxx`），`JNI_OnLoad`（native-lib.cpp:640）只设 g_jvm + GPU 检测，**无 RegisterNatives**；
- 新增 `Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatJson`（§6.1 签名）按现有命名规范即可，无需注册表改动。

### R5-2【必改级】parse 失败降级路径与现状不一致（且更激进）

- 现状 `generateWithTools`：`common_chat_parse` 失败 → **仅 LOGW**（native-lib.cpp:2366-2368），随后**照常发 onComplete(collectedText)**（2372），Java 侧 toolCalls 为空 → 走纯文本回答/F4 降级；
- 新协议 §5.1 step 8 写"parse 失败发 `{"type":"error"}`"——**若直接 error，会丢失模型全部输出，整个 Agent 失败**，比现状激进；
- **建议**：step 8 改为——parse 失败时发 `complete(content=collectedText)`（无 tool_call/reasoning 事件），Java 侧按纯文本回答处理（与现状 2366-2372 行为一致）；仅在 collectedText 也为空时才发 error。此改动符合 §2.2"容错优先"原则。

### R5-3【提示】onJson 每 token JSON 解析开销（Java 侧）

- 每个 token 事件 Java 需解析 JSON 取 type/content/is_tool_call（现 onToken 是裸 String 零解析）；4B 本地模型 11 t/s 下开销可忽略；
- 可选优化：先子串判断 `"type":"token"` 再决定是否完整 `new JSONObject(...)`。实现提示，非必须。

### R5-4【改进】单元测试补项（§11.1）

- 补：onJson 状态机分支（token/tool_call/reasoning/complete/error/cancelled 各事件的处理与幂等忽略）；
- 补：parse 失败降级（R5-2）→ complete(collectedText)；
- KV 增量命中/失效（前缀匹配、seq_pos_max 校验）与 tool_call_id 匹配（R4-1）在 Android 上 C++ 单测不便，标注"由集成测试覆盖"即可。

### R5-5【终检确认】全文结构完整

- 代码围栏成对、EOF 平衡（52 个）；版本链 v1.1→v1.4 与 §12.2 修订记录逐条对应；交叉引用（§3.1.1/§5.6/§7.2.1/§9/§10.1/§12.1）一致；
- 引用的代码行号（native-lib.cpp / chat.cpp / AgentLoopEngine.java 等）与真实代码仍一致（源文件未变）。

---

## 十四、第五轮结论与实施就绪度

| # | 类型 | 处置建议 |
|---|---|---|
| R5-1 | 确认 | 无需改动 |
| R5-2 | 必改（降级路径） | v1.5 §5.1 step 8：parse 失败 → complete(collectedText)；仅空输出才 error |
| R5-3 / R5-4 | 提示/改进 | 可并入 v1.5（解析优化提示、单测补项） |
| R5-5 | 确认 | 无需改动 |

**实施就绪度评估**：
- 协议设计（schema / 回调 / KV 增量 / tool_call_id 匹配）：就绪；
- 工程前置（复活入口 R3-1 / 取消 R3-2 / KV 联动 R3-4 / parse 降级 R5-2）：已全部落入文档；
- 唯一运行时阻塞项仍是阶段 0 的 `common_chat_parse` content 纯净性实测（R4-5 已给操作化步骤）；
- 建议：并入 R5-2 后即可按 §9 阶段 0→4 开工。

---

# 第六轮（v1.5 并入后，全文终检 A1-A6）

> 审查方式：对 v1.5 全文通读，找内部矛盾与残留错误
> 结论：发现 3 处明显问题 + 3 处小问题，全部并入 v1.6（明细见 spec §12.2 的 v1.6 表）

## 十四.五、A1-A6 摘要

| # | 内容 |
|---|---|
| A1 | §9 阶段1 checklist 仍写"parse 失败发 error"，与 §5.1 step 8（R5-2）矛盾 → 对齐 |
| A2 | step e 的 assistant 消息 content 允许 raw 输出 → 与结构化 tool_calls 双份渲染/思考泄漏 → 明确用 complete.content |
| A3 | buildRequestJson 的 `System.nanoTime()` id 兜底与 R4-1 机制冲突 → 删除，id 来自前置补齐 |
| A4 | §3.2 数据流图残留 `generateStream` → `generateStreamIncremental` |
| A5 | step 8/10 空输出与 parse 失败可能重复发 complete → 互斥，全轮只发一次 |
| A6 | §12.2 标题版本号更新 |

---

# 第七轮审查（v1.6 终检后，错误传播与实现细节）

> 审查对象：v1.6 修订版 + 生成失败路径的实际行为
> 审查方式：错误传播链路核查（generateStream 失败 → wrappedCallback → Java 回调）、nativeStopGeneration 实现、KV 一致性校验 API 签名、跨 run 缓存安全
> 结论：v1.6 六项修正无回归；发现 **1 处必改级（R7-1，chatJson 生成失败错误传播未定义，且现状存在吞 error 缺陷）**、若干确认项

---

## 十五、第七轮发现（R7-1 ~ R7-7）

### R7-1【必改级】chatJson 生成阶段失败的错误传播未定义（现状 generateWithTools 会吞 error）

- 现状 `generateStream` 所有失败路径都调 `callback("", true, error)`（native-lib.cpp:1933/1941/1948/1971/2012/2180/2186，含模型未初始化/分词失败/decode 失败/异常）；
- `generateWithTools` 的 `wrappedCallback`（2329-2343）三个分支：`!isComplete && error非空` / `isComplete && text非空` / `!isComplete`——**对"isComplete=true 且 text 空"的失败回调全部不命中，error 被吞**；随后 2372 行发 `onComplete("")`，Java 拿到空完成而非错误；
- JNI 侧 `onError` 实际只在"上下文未初始化"等前置检查触发（5778-5797），生成失败从未走到 Java onError；
- **影响**：现状用户看到的是泛化兜底回答（buildSimpleFallback），真实错误丢失；若 chatJson 按 §5.1 现状照搬，同样吞错；
- **建议（v1.7）**：§5.1 step 7 明确生成阶段错误处理——generateStreamIncremental 回调收到 `isComplete && error非空`，或函数返回 false 时，发 `{"type":"error","message":lastError}`（cancelled 场景除外，走 step 9）；§9 阶段1 checklist 补"生成失败 → error 事件"项。同时把"现状 wrappedCallback 吞 error"记录为修复动机。

### R7-2【记录】现状缺陷盘点（新协议应一并修复的隐性项）

- 生成失败 error 被吞（R7-1）；
- tool 消息无 tool_call_id（P1-4/R4-1）；
- 回调顺序与 javadoc 矛盾（P1-1）；
- 最终回答假流式（P0-4）。
- 建议：§1.2 问题表可补一行"生成失败错误被吞（wrappedCallback 2330-2343）"。

### R7-3【确认】取消链路 native 侧成立

- `nativeStopGeneration`（4604-4611）→ `InferenceContext::stopGeneration()`（1749-1752）→ `shouldStop = true`；
- 与 R3-2（Java cancel → stopGeneration）、R4-3（每次进入 chatJson 复位 shouldStop=false）闭环一致。

### R7-4【确认】`llama_memory_seq_pos_max` API 存在且用法正确

- `llama.h:787` 声明 `llama_memory_seq_pos_max(llama_memory_t mem, llama_seq_id seq_id)`；
- 传 `seq_id=0` 与 `llama_batch_get_one`（seq 固定 0，llama.h:930-937）一致；R3-4 校验表达式成立。

### R7-5【确认】error 后不再发 complete

- §4.3/§5.1 step 9：error 后本轮终止、不补发 complete；与 Java 侧"已结束"幂等标志（F10）闭环。

### R7-6【确认】跨 run 的 KV 缓存安全

- 每次 `run()` 重建 history（[system, user_new]），与上一任务 prompt 前缀不同 → 前缀失配 → 全量重 eval；R3-4 的 seq_pos_max 校验作第二道保险。无泄漏风险。

### R7-7【改进】review.md 补第六轮记录

- spec §12.2 已有 v1.6 表（A1-A6），review.md 停在第五轮；补一节归档 A1-A6 即可（本报告即第七轮，A1-A6 明细见 spec §12.2）。

---

## 十六、第七轮结论

| # | 类型 | 处置建议 |
|---|---|---|
| R7-1 | 必改（错误传播） | v1.7 §5.1 step 7 + §9 阶段1：生成失败 → `{"type":"error"}` 事件；记录现状吞 error 缺陷 |
| R7-2 | 记录 | §1.2 问题表补"生成失败 error 被吞"一行 |
| R7-3 ~ R7-6 | 确认 | 无需改动 |
| R7-7 | 改进 | review.md 归档第六轮（可选） |

六轮累计：方案主干（协议/回调/KV 增量/复活/取消）已完备；R7-1 是错误处理闭环的最后一块——补上后，生成失败将正确到达 Java onError（比现状更优），方案即完全就绪。

---

# 第八轮审查（v1.7 后，产品决策对齐）

> 审查对象：v1.7 修订版 + 复活路径与产品决策的对齐
> 审查方式：从 AIChatActivity 的 Agent 调用链向上核对思考开关的产品语义、复活路径完整性、协议实现细节
> 结论：v1.7 无回归；发现 **1 处必改级（R8-1，enable_thinking 默认值与产品决策冲突）**、1 处建议改、1 处提示、1 处确认

---

## 十七、第八轮发现（R8-1 ~ R8-4）

### R8-1【必改级】`enable_thinking` 默认 true 与产品决策冲突（Agent 模式不思考）

- **产品决策**：`AIChatActivity.java:4547-4550` 明确注释——"AGENT 模式不启用 thinking——Qwen3-4B 在 thinking+FC 组合下思考完会'忘记'调用工具（直接回答'无法获取'而非输出 tool_call），非思考 FC 模式工具调用更稳定"；`enableThinking = isDeepThinkingEnabled()`（深度思考是独立聊天模式，Agent 模式下为 false）；
- **方案现状**：§4.1 字段表注记"默认 true（与现状一致，AgentLoopEngine 恒传 thinking=true）"、§7.2.1 step b `enable_thinking = true`、§7.2.3 `req.put("enable_thinking", true)`——依据的是休眠引擎 `AgentLoopEngine.java:179` 的硬编码 thinking=true；
- **冲突**：休眠引擎的硬编码与产品决策相悖（引擎写死 true，UI 决策是 false）；复活路径 a 若照搬方案，本地 Agent 会开思考，Qwen3-4B 工具调用稳定性下降；
- **建议（v1.8）**：`enable_thinking` 由调用方贯穿传递，默认值与产品决策一致——
  1. §4.1 字段说明改为"由调用方传入；Agent 模式默认 false（AIChatActivity:4547-4550 产品决策），深度思考模式可开"；
  2. §7.2.1 step b：`enable_thinking = <调用方传入（复活路径 a 传递 startAgentLoop 的 enableThinking 参数）>`；
  3. §7.2.3 示例：`req.put("enable_thinking", enableThinking)`（方法参数）；
  4. §7.4 复活入口：`startAgentLoop` 分支把 `enableThinking` 传进 `softwareLayer.processMessage(message, enableThinking)` → `AgentLoopEngine.run(userMessage, enableThinking)`；
  5. 顺带修复休眠引擎 179 行硬编码（run 签名带 enableThinking 参数）。

### R8-2【建议改】复活路径完整性：`forceRunLocalAgent` 未纳入

- `ChatMessage.Action.forceLocalAgent` → `AIChatActivity.forceRunLocalAgent`（4357-4359）当前只显示"🚫 本地 Agent 已禁用"；复活后（`localAgentEnabled=true`）应真正路由到本地 Agent（走 startAgentLoop 本地分支），否则"强制本地 Agent"入口仍不可用；禁用时保持现状提示即可；
- 引导文案（4374 "本地模型 — 普通对话"）复活后可选择性更新（产品文档，非本方案范围）。

### R8-3【提示】§5.1 step 1 明确用 `nlohmann::ordered_json::parse`

- `common_chat_msgs_parse_oaicompat`/`tools_parse_oaicompat` 参数类型是 `nlohmann::ordered_json`（chat.h:353/355，`using json = nlohmann::ordered_json`）；§5.1 step 1 写 `nlohmann::json::parse`——虽可经模板转换构造，但直接 `nlohmann::ordered_json::parse(requestJson)` 更干净（与现状 2278 行同款），建议明确。

### R8-4【确认】桥接链就绪，无缺口

- `AIChatActivity.AgentCallbackImpl` ← `AgentChatHandler.softwareLayer` 回调（68-134）← `AgentSoftwareLayer` ← `AgentLoopEngine`：回调语义逐层匹配，复活路径 a 无缺口（除 R8-1 的 enableThinking 传递外）。

---

## 十八、第八轮结论

| # | 类型 | 处置建议 |
|---|---|---|
| R8-1 | 必改（产品决策冲突） | v1.8：enable_thinking 贯穿传递，Agent 模式默认 false |
| R8-2 | 建议改 | v1.8：forceRunLocalAgent 复活分支 |
| R8-3 | 提示 | v1.8：step 1 改 ordered_json::parse |
| R8-4 | 确认 | 无需改动 |

---

# 第九轮审查（v1.8 后，编码边界）

> 审查对象：v1.8 修订版
> 审查方式：token 级编码边界核查（UTF-8 分片与 JSON 序列化）、enable_thinking 残留检查
> 结论：v1.8 无回归；发现 **1 处必改级（R9-1，token 事件 UTF-8 完整性缺失，会导致 nlohmann dump 抛异常）**、1 处提示、1 处确认

---

## 十九、第九轮发现（R9-1 ~ R9-3）

### R9-1【必改级】chatJson 的 token 事件缺 UTF-8 完整性处理（nlohmann dump 会抛异常）

- **机制**：`llama_token_to_piece` 输出的 token 可能含**不完整 UTF-8 字节**（多字节字符被拆到相邻 token）；现有 JNI 全部回调都用 `splitUtf8Complete`（`native-lib.cpp:240`）+ utf8Buffer 缓存不完整尾部（4183/4828/5289/5526/5718/5894 六处），保证 Java 侧只收完整字符；
- **问题**：§5.1 step 7 直接把 token 塞进 `nlohmann::json` 发 `{"type":"token","content":token,...}`——nlohmann 默认 strict 错误处理，**dump() 遇非法 UTF-8 字节抛 `type_error.316`** → 生成循环中断（恰好在多字节字符边界）；
- **建议（v1.9）**：step 7 增加 UTF-8 缓冲（复用 splitUtf8Complete 模式）——
  - `collectedText += token`（**累积原始字节**，保证 common_chat_parse 拿到完整输出）；
  - token 事件：拼入 utf8Buffer → splitUtf8Complete 出完整前缀 → 只发完整前缀的 token 事件，不完整尾部留 buffer；
  - 生成结束：残留不完整尾部丢弃（与现状 JNI 行为一致）；
  - reasoning/complete/tool_call 的 content 来自 parse 结果（完整文本），无需处理。

### R9-2【提示】reasoning 事件仅 `enable_thinking=true` 时出现（R8-1 联动）

- Agent 模式默认 enable_thinking=false（R8-1）后，思考区/reasoning 事件在 Agent 模式基本不出现；§8.2 思考行与 F5 说明仍成立（深度思考模式可开），建议 §4.2 reasoning 事件或 §8.2 加一句"仅 enable_thinking=true 时产生"。

### R9-3【确认】v1.8 无 thinking=true 残留

- grep 全文：仅剩 step 7"enableThinking 恒传 false 交给模板"（指 generateStreamIncremental 的参数，正确）；§4.1/§7.2.1/§7.2.3 的 enable_thinking 已全部参数化。

---

## 二十、第九轮结论

| # | 类型 | 处置建议 |
|---|---|---|
| R9-1 | 必改（编码边界） | v1.9：step 7 补 splitUtf8Complete UTF-8 缓冲 |
| R9-2 | 提示 | v1.9：§4.2/§8.2 加"仅 enable_thinking=true"注 |
| R9-3 | 确认 | 无需改动 |

---

# 第十轮（v1.9 后，残留扫描）

> 审查方式：全文矛盾/残留扫描（默认值、旧表述、版本链）
> 结论：仅 1 处残留（§5.1 step 4 的 enable_thinking 默认值"默认 true，F1"未随 R8-1 更新），并入 v1.10

## 二十.五、R10-1

| # | 内容 |
|---|---|
| R10-1 | §5.1 step 4：enable_thinking 缺失时默认值 "true，F1" → "false（R8-1 后与产品决策一致）"；§12.2 F1 修订记录行加注 |

---

# 第十一轮（收敛终检）

> 审查方式：全文矛盾/残留扫描（默认值、旧表述、TODO、围栏、版本链）+ 结构完整性
> 结论：**方案达到完全体（收敛）**——仅剩 1 处标题过时（已并入 v1.11），连续两轮无实质性问题

## 二十一、收敛终检结果

| 检查项 | 结果 |
|---|---|
| 代码围栏 | 52 个成对，EOF 平衡 ✅ |
| 残留矛盾扫描（默认 true / 旧表述 / 硬编码 / TODO / FIXME / 待补充） | 仅 §12.2 标题版本号过时（v1.6 → 实际 v1.10），已并入 v1.11 ✅ |
| `useJsonProtocol` 默认 true | 正确（回退开关默认新协议）✅ |
| enable_thinking 链路 | §4.1（调用方传入/false 默认）→ §5.1 step 4（默认 false）→ §7.2.1（参数化）→ §7.2.3（参数化）→ §7.3/§7.4（贯穿传递）→ §9 阶段2，全链一致 ✅ |
| "UI 零改动"声明 | 与 §3.1.1（复活在 AgentChatHandler 层）+ §12.1（AIChatActivity/ChatAdapter 零改动）一致 ✅ |
| 版本链 | v1.1 → v1.11，§12.2 逐条对应，无缺档 ✅ |
| 错误处理闭环 | 解析失败 error / 生成失败 error（R7-1）/ parse 失败 complete 降级（R5-2）/ 空输出 complete("")（A5）/ 取消 cancelled（R3-2），互斥完整 ✅ |
| 编码边界 | token UTF-8 缓冲（R9-1）、ordered_json（R8-3）✅ |

## 二十二、完全体结论

十轮实质审查（v1.1-v1.10）+ 收敛终检（v1.11），方案已达到完全体：

- **协议定义**（§4）：请求/回调/顺序，含 tool_call_id 匹配（R4-1）与 UTF-8 边界（R9-1）；
- **C++ 层**（§5）：chatJson 全流程、KV 增量 + seq_pos_max 校验（R3-4）、并发/取消/复位、错误传播闭环（R7-1）、parse 降级（R5-2）；
- **JNI 层**（§6）：SAFE_RUN_INFERENCE、静态导出确认、线程安全（R4-4）；
- **Java 层**（§7）：AgentLoopEngine 重构、enable_thinking 贯穿（R8-1）、工具适配（F3/R4-2）、复活入口（R3-1/R8-2）；
- **工程前置**：复活路径、取消链路、回退开关、双配置开关；
- **测试与实施**（§9/§11）：阶段 0-4 与单/集/UI 测试齐备，唯一运行时阻塞项为阶段 0 的 content 纯净性实测（R4-5 已操作化）。

**方案状态：完全体，可直接按 §9 阶段 0→4 实施。**
