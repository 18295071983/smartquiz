# DeepSeek `deepseek-recipe` 请求序列化 vs 本仓在线请求构造（2026-10-23）

只读对比，未做真机验证。目的：看 DeepSeek 官方开源的"请求 → token"实现，能否指出本仓在线链路的行为差异。

## 对比范围与证据来源

对方（deepseek-ai/deepseek-recipe，MIT，Rust，2026-09-10 建仓）：

- `deepseek-recipe-encoding/src/v4/dsv41.rs`（17617 B，已读全文）
- `deepseek-recipe-encoding/src/v4/mod.rs`（24117 B，抓取失败一次，**未读**）
- `deepseek-recipe-encoding/src/v4/dsv4.rs`（15102 B，**未读**）
- `deepseek-recipe-core/src/special_tokens.rs`（9822 B，已读）
- crate 结构：`core / encoding / image / macros / python / recipe` + `server-rs`(Axum) / `server-py`(FastAPI)

本仓：

- `ai/service/ChatProtocol.java`（协议族枚举，4 族）
- `ai/service/ResponsesPayloadBuilder.java`（Responses 协议载荷）
- `ai/service/OnlineInferenceService.java`（4605 行，OPENAI_COMPAT 主路径）
- `ai/agent/online/OnlineAgentEngine.java`（Agent 多轮：872-893 工具结果写入、1437-1501 发送前规范化）

**层级差异先声明**：recipe 是"把请求编成 token 序列喂给模型"，本仓在线路径是"把请求发给服务商 API"，服务商自己 tokenize。所以 DSML 标签、BOS/EOS、`<｜User｜>` 这类 token 结构对本仓**不可搬运**，本档只比与层级无关的**请求构造语义**。

## 逐项对比

| # | 规则 | recipe（DeepSeek 官方口径） | 本仓现状 | 判定 |
|---|---|---|---|---|
| 1 | 历史 assistant 的 reasoning 是否回传内容 | `drop_thinking = !renders_tool_prompt` —— 本轮不渲染工具提示时，早期轮次的**思考内容**不进 prompt；但字段位仍渲染成**空思考块** | `buildOutgoingMessagesArray:1480-1486`：本轮开思考 → 所有 assistant **全文回传** reasoning_content；缺字段补 `""` | **实质差异**，见"可行动项 A" |
| 2 | 缺 reasoning 内容时的处理 | 渲染空思考块（`absent reasoning content renders as an empty reasoning block`） | 补 `reasoning_content: ""` | **同构**，本仓做法正确 |
| 3 | 多条工具结果的顺序 | `sort_tool_results_by_call_order`：按前置 assistant `tool_calls` 的 id 顺序**重排**；id 匹配不到任何 call 的排最前（`unwrap_or(0)`） | 工具结果在 `for tc : toolCalls` 循环内**串行 append**（`OnlineAgentEngine:872-893`），写入顺序恒等于调用顺序 | **本仓已天然满足**；recipe 的重排是给并行/乱序回填兜底的，本仓暂无该场景 |
| 4 | 悬空 tool 消息 | 不丢弃，连续 User/Tool 用 `\n\n` 合并进同一段 | 跳过无前置 `tool_calls` 的 tool 消息（1488-1496），另有 1001 段截断保护 | 本仓**更严**（防 OpenAI/GLM 400），无缺口 |
| 5 | "必须调用工具"怎么实现 | `tool_choice == Required` → 在 prompt 末尾**直接预填 `｜DSML｜ calls>` 开块**，让模型只能往里填 | `AgentLoopEngine:558-561` 实测 `tool_choice=required` 让 Qwen3-4B 退化（FcTest：tool_calls=0 + 乱码），决策恒 `auto` | **本仓踩过的坑，recipe 给了正解**，见"可行动项 B" |
| 6 | 工具提示与 response_format 的落位 | 追加到**首条 system** 之后；首条不是 system 就**插一条空 system** | Responses 路径走顶层 `instructions`（`ResponsesPayloadBuilder:73-75`，注释理由与 recipe 一致："稳定的请求前缀"） | **同构**，本仓设计得到官方实现印证 |
| 7 | JSON Schema 约束 | 文本化注入：`## Response Format: ... You MUST strictly adhere to the following schema` | 在线走服务商 `response_format` 字段；本地走 grammar（`native-lib.cpp:1357` 起，经 `common_sampler_init`，思考段内抑制） | 都对，各自层级正确 |
| 8 | reasoning_effort 的表达 | 编成**自然语言分数**注入首条前：Low=50 / None·High·Xhigh=75 / Max=100，仅 thinking_mode 生效 | 按服务商声明的 `thinking.param` 形态分发参数（`OnlineInferenceService:2383-2387` 起） | 参数分发是本仓正解；**该文本档仅对 DeepSeek 系有效**（训练时对齐），不可当通用手段 |
| 9 | DSML 文本的性质 | `｜DSML｜invoke name=` / `｜DSML｜parameter name= string=` 是 V4.1 **正式工具调用协议** | `OnlineAgentEngine:1469-1477` 把含 `<｜｜` 的历史 assistant 判为"**伪调用**残留、无信息量"并在请求层过滤；根除手段是每轮恒带工具（611、2040） | 过滤决策**没错**，但注释里"无信息量"**不准确**，见"需你拍板 C" |
| 10 | 中途换工具集 | 非首条 system 可带 `tools`，渲染成 `### Updated Tool Schemas` | 未发现对应机制（本仓每轮恒带同一套工具） | 本仓不需要；记录以备将来做动态工具子集 |

## 可行动项

**A. 非工具轮的思考内容可以置空而非全文回传（省 prompt）**
本仓"本轮开思考 → 历史所有 assistant 全文带 reasoning_content"。recipe 的口径是：**字段必须存在（满足 DeepSeek 400 硬性要求），但非工具轮的内容不进 prompt**。落点很精确——`buildOutgoingMessagesArray:1480-1483` 的分支从"保留全文"改成"带工具轮保留、其余轮保留字段但内容置空"。收益是多轮 Agent 思考链长时省掉重复的思考全文；风险是模型丢失跨轮推理连贯性，**这条没有实测依据，需真机 A/B**，且要按服务商位（`requiresReasoningInContext`）分档，不能全量。

**B. 小模型"不肯调工具"时，改用生成前缀预填而不是 `tool_choice=required`**
本仓已经用 FcTest 实证 required 会让 4B 退化，于是退回 auto。recipe 展示了官方另一条路：**在 generation prompt 之后直接把工具调用的开块写进 prompt**，模型只能往合法结构里填。本仓本地链路已经把 grammar 挂进采样链首（`native-lib.cpp:1347-1355` GRAMMAR-ATTACH），grammar 约束 + 前缀预填本就是同一套机制的两个部件，`common_chat_params` 的 `add_generation_prompt` / grammar `TOOL_CALLS` 类型标记（1363 行注释里已经踩过）是能接的位置。这条是本次对比里**最值得试**的：它针对的正是"required 退化"这个已复现的问题，而不是新需求。

**C. 需你拍板：模型吐 DSML 要不要解析成真实调用**
本仓把 `<｜｜` 当乱码过滤。recipe 证明它是 DeepSeek 的正式调用格式（含 `string="true|false"` 区分字符串与 JSON 值、多参数 `\n` 分隔、`<tool_result>` 包裹返回值）。若线上接的是 DeepSeek 系且出现过 DSML 文本输出，那可能是**调用意图被当成噪声丢了**。现状"每轮恒带工具"已把根因堵住，所以这是取舍而非 bug：要不要加一条 DSML → tool_calls 的解析回退。

## 未覆盖 / 未验证

- `mod.rs`、`dsv4.rs` 未读（一次抓取失败），其中可能还有请求校验/拒绝规则；本档结论只基于 V4.1 编码器。
- 本仓侧只核了 `OnlineAgentEngine` 与 Responses 构造路径；`OnlineInferenceService` 的 OPENAI_COMPAT 消息组装（4605 行）未逐行核。
- 全程静态阅读，无真机、无抓包。A、B 两条若要动手，先按仓内惯例做可回退开关 + 单点验证。
