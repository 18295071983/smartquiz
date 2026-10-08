# llama.cpp grammar 的原理性用法与本项目不适用分析

> ## ⚠️ 结论已作废（2026-10-09 更新）
>
> **本文档下方的原始结论「应保持 `kAttachGrammar=false`」已被推翻，请勿据此实现。**
>
> 当时判断"grammar 无法挂载"的原因是**误判**：真正的问题不是 grammar 本身不适用，而是
> **我们自己的采样链绕过了上游 `common_sampler` 封装**。上游"思考段内抑制 lazy grammar"
> 的修复（PR #20970，2026-03-27 已合入本 checkout 的 `common/sampling.cpp`）
> **只存在于 `common_sampler` 的 accept/apply 里**；自己拼 `llama_sampler` 链就绕过了这层保护，
> 于是模板 grammar 在生成提示词的 `<think></think>` 空块内被提前触发/完成，栈变空而崩溃
> （`Unexpected empty grammar stack` / `GGML_ABORT`）。
>
> **正确做法（已实施并双模型真机验证）**：改用 `common_sampler_init()` 构造采样器，
> 并**成对**传入 grammar 与 reasoning-budget 参数：
> - `sp.grammar = common_grammar(COMMON_GRAMMAR_TYPE_TOOL_CALLS, chat_params->grammar)`
>   —— 必须标 `TOOL_CALLS` 类型，否则 `common_grammar_needs_prefill()` 为假、生成提示词不会预填充进 grammar；
> - `sp.reasoning_budget_start/end` = 模板思考标签的 token 序列，`reasoning_budget_tokens = -1`
>   —— **这是触发抑制的开关**，上游仅在两采样器同时存在时才在思考段内停止喂 token 给 grammar。
>
> 实测结果：Qwen3.5-2B 与 MiniCPM5-2B 均正常，工具调用由 native 解析成功，
> 生成量从「163 token / 35 秒（畸形标签）」降到「24-26 token / 1 秒」。
> `kAttachGrammar` 开关已从代码中彻底移除。
>
> 下方原文保留作排查过程记录，其中"grammar 原理"章节仍然有效，**仅结论部分作废**。

> 归档日期：2026-10-08
> ~~结论：**本项目当前生成路径不具备挂载模板 grammar 的前提条件，应保持 `kAttachGrammar=false`。**~~
> 本文档基于 llama.cpp `2c36e81`（2026-10-01）源码与官方设计文档实证，非推测。

---

## 一、结论摘要

| 项 | 结论 |
|---|---|
| grammar 设计上怎么用 | 生成提示词（`generation_prompt`）必须是 grammar 能匹配的**起点**；非 lazy 时还必须预填充（`grammar_prefill`） |
| 本项目能不能这么用 | **不能**。本项目的生成提示词以 `<think>\n\n</think>\n\n` 结尾，而 grammar 的 root 以 `<tool_call>` 开头，两边起点不一致 |
| 强行挂载的后果 | 触发上游 3 处崩溃点（throw / abort / assert），Agent 引擎每轮失败 |
| 当前处置 | `native-lib.cpp` 中 `kAttachGrammar = false`，保留 `preserved_tokens` 与流式 `common_chat_parse` 兜底 |

---

## 二、grammar 的设计意图（官方依据）

### 2.1 官方文档明确的两条硬性要求

`docs/autoparser.md`：

> The generation prompt is prepended to model output before PEG parsing via `wrap_for_generation_prompt()`.
> The full string is also fed to the grammar sampler via `llama_sampler_accept`
> (stored in `common_params_sampling::grammar_prefill`), advancing the grammar past tokens
> already in the prompt.

> **`grammar_prefill`** (`common_params_sampling`): The generation prompt string tokenized and
> accepted by the grammar sampler at init time.

即两个配套动作缺一不可：

1. grammar 匹配的对象是**模型新生成的文本**，起点必须对应生成提示词结束的位置；
2. 非 lazy 模式下，必须把生成提示词 token 化后**主动 accept 进 grammar sampler**，把语法状态推进到"模型即将输出"的位置。

### 2.2 root 允许匹配空是**设计意图**，不是缺陷

`docs/development/parsing.md` 给出的官方范例：

```cpp
return p.sequence({
    p.content(p.until("<tool_call>")),
    p.optional(tool_call),      // 工具调用是可选的
    p.end()
});
```

模型可以只回正文、不调工具，所以 root 必须允许空匹配。**问题不在"可空"，而在"可空的 root 与生成提示词起点不一致时，grammar 会在模型开口前就完成"。**

### 2.3 权威调用序列（`common/sampling.cpp`）

```cpp
// 1) 构造 grammar sampler
if (params.grammar_lazy) {
    grmr = llama_sampler_init_grammar_lazy_patterns(vocab, grammar_str.c_str(), "root",
            trigger_patterns_c.data(), trigger_patterns_c.size(),
            trigger_tokens.data(), trigger_tokens.size());
} else {
    grmr = llama_sampler_init_grammar(vocab, grammar_str.c_str(), "root");
}

// 2) 非 lazy：把 generation_prompt 喂进 grammar，推进到模型输出起点
if (grmr && !params.grammar_lazy && common_grammar_needs_prefill(params.grammar)) {
    for (const auto & token : prefill_tokens) {      // prefill_tokens 来自 params.generation_prompt
        llama_sampler_accept(grmr, token);
    }
}
```

**注意第 2 步只在 `!grammar_lazy` 时执行** —— lazy 模式靠触发器定位起点，因此不需要预填充。这是两种模式互相替代的关系，不能只取其一。

### 2.4 触发器必须按类型转换（易错点）

`common/sampling.cpp:222-256` 对 `common_grammar_trigger` 的处理：

| 类型 | 正确处理 |
|---|---|
| `WORD` | `regex_escape(value)` 后作为 pattern |
| `PATTERN` | 原样作为 pattern |
| `PATTERN_FULL` | 加 `^...$` 锚定后作为 pattern |
| `TOKEN` | 进 `trigger_tokens` 数组，**不是** pattern |

把 WORD 直接当 pattern 传入语义不等价。

---

## 三、本项目的实际不匹配（实测证据）

### 3.1 生成提示词与 grammar 起点不一致

真机日志（`chatJson: PROMPT_TAIL>>` 之后）显示本项目生成的提示词末尾为：

```
<|im_start|>assistant
<think>

</think>

```

而同一轮从模板取到的 grammar 是（`native-lib.cpp` dump）：

```
grammar[30] root           ::= tool-call-root
grammar[38] tool-call      ::= "<tool_call>\n" (tool-ai-weather | ...)
grammar[39] tool-call-root ::= (tool-call tool-call*)?
```

**root 要求文本以 `<tool_call>` 开头，而提示词在 `<think>` 之后结束。** 两者起点不一致，grammar 在模型开口前即匹配空分支而"完成"。

### 3.2 为什么预填充也救不了

即使按文档补上 `grammar_prefill`，喂进去的是生成提示词本身（`<|im_start|>assistant\n<think>\n\n</think>\n\n`），仍需与 root 的 `<tool_call>` 起点对齐 —— 依然不匹配。**这是模板设计（空思考块前置）与 grammar 起点假设之间的结构性冲突，不是缺一步调用能补的。**

对照官方示例：其生成提示词在 `assistant` 后结束，随后才由 `p.content(p.until("<tool_call>"))` 接管正文 —— 两边天然一致。

### 3.3 强行挂载触发的三处上游崩溃点

grammar 因起点不匹配而"完成"后 `grammar.stacks` 变空，随后依次触发（实测逐个暴露）：

| # | 位置 | 行为 | 现象 |
|---|---|---|---|
| 1 | `src/llama-grammar.cpp` `llama_grammar_accept_token` 末尾 | `throw std::runtime_error("Unexpected empty grammar stack after accepting piece: ...")` | `chatJson` 收到 error，Agent `genResult null at iteration 1, breaking` |
| 2 | 同文件 `llama_grammar_accept_impl` 的 EOG 分支 | `GGML_ABORT("fatal error")` | `Fatal signal 6 during inference` |
| 3 | 同文件 `llama_grammar_reject_candidates` 开头 | `GGML_ASSERT(!stacks.empty()); // REVIEW` | 采样时 SIGABRT |

第 3 处上游自带 `// REVIEW` 注释，说明作者对该断言本身存疑。

> 注：曾对上述三处分别打过补丁，确实能逐个消除崩溃，但那只是**在下游掩盖"起点不一致"这一根因**，且侵入了 vendored 代码。已全部撤销（`git status` 干净）。

### 3.4 历史印证

`native-lib.cpp` 中原有一段说明 grammar 被移除的注释：

> §6.1 官方 grammar 挂载已移除：autoparser 对 Qwen3-VL 模板的工具格式推断（XML 参数格式）
> 与模型实际输出（JSON-in-tags）不一致，lazy 触发后遇 `'{'` 崩溃（Unexpected empty grammar stack）。

本次分析把"为什么移除"从"某个模型的特例"推进到了**通用原因：生成提示词起点与 grammar 起点不一致**。Qwen3-VL 只是最早暴露该问题的一个模板。

---

## 四、当前采用方案（不启用 grammar）

保留能力、放弃约束：

| 机制 | 作用 | 位置 |
|---|---|---|
| `preserved_tokens` → `preservedIds` | 豁免控制抑制，保证 `<function`/`<param`/`</function>`/`</param>` 等**不被 -INFINITY 禁掉** | `native-lib.cpp` `generateStreamIncremental` |
| 流式 `common_chat_parse(collectedText, true, pp)` | 边生成边解析出结构化 `tool_call` 事件 | `native-lib.cpp` `generatingStage` |
| 模板标签下发（`tool_call_open_tags` / `tool_call_close_tags`） | 让 Java 侧按**当前模型真实标签**做流式吞除，不硬编码 | meta 事件 → `ToolCallTagConfig` |
| 生成完成后的 `common_chat_parse` | 最终解析（实测可容忍畸形输出，三次工具调用均成功） | `native-lib.cpp` |

**已实测有效的旁证**：即使模型输出畸形的 `<function name="location"<param name=...`（缺 `>`），最终解析仍成功产出 `tool call: name=location` / `ai_weather` / `memory`，Agent 工具链正常。

---

## 五、若将来要重启 grammar 的前置条件

必须**同时**满足以下三条，缺一不可：

1. **生成提示词与 grammar 起点对齐**：模板在 `enable_thinking=false` 时不得在生成提示词尾部留下 `<think></think>` 空块，或 grammar 的 root 需能吸收该前缀（例如把 root 改为 `think_block? tool_call`）。
2. **补上 `grammar_prefill`**（仅非 lazy 模式）：把生成提示词 token 化后 `llama_sampler_accept` 进 grammar sampler。`common_chat_params` 未暴露 grammar 类型字段（`common_grammar_needs_prefill()` 依赖 `common_grammar::type`），需自行判定或扩展结构。
3. **触发器按类型正确转换**（WORD → `regex_escape`，TOKEN → `trigger_tokens`）。

验证方式：先在 PC 侧用同一份 grammar + 同一段生成提示词跑 `llama_grammar_init_impl` + 逐 token `llama_grammar_accept_token`，确认不出现空栈，再上真机。

**不满足第 1 条就挂载，必然复现本文 3.3 的崩溃。**

---

## 六、相关源码索引

| 主题 | 位置 |
|---|---|
| grammar 挂载与预填充（权威实现） | `common/sampling.cpp:222-308` |
| 触发器类型转换 | `common/sampling.cpp:222-256` |
| 预填充判定 | `common/common.h:218-223`（`common_grammar_needs_prefill`）|
| root 规则生成（lazy / 非 lazy 两种） | `common/peg-parser.cpp:1796-1814` |
| MiniCPM5 的 grammar 与触发器 | `common/parsers/minicpm5.cpp:118-127` |
| 空栈相关的三处崩溃点 | `src/llama-grammar.cpp`（`accept_token` 末尾 / `accept_impl` EOG 分支 / `reject_candidates` 开头）|
| 设计文档 | `docs/autoparser.md`、`docs/development/parsing.md` |
| 本项目挂载开关 | `native-lib.cpp` `kAttachGrammar`（当前 `false`）|
