# SmartQuiz AgentLoop × HermesAgentLoop 编排能力差异清单

> 对照对象：
> - **Hermes 侧**：`ref\HermesApp\hermes-android\src\main\java\com\xiaomo\hermes\hermes\AgentLoop.kt`（Hermes Agent Loop 的 Kotlin 1:1 翻译，标准 OpenAI tools 协议）
> - **SmartQuiz 侧**：`src\main\java\com\oilquiz\app\ai\agent\software\engine\AgentLoopEngine.java`（本地引擎，Qwen 原生 `<tool_call>` 协议 + KV 前缀稳定性优化）
> 日期：2026-10-11
>
> ⚠ **本地侧工具集已于 2026-10-11 收敛为 4 个**（time_date / location / ai_weather /
> network_search），且"未注入 schema 的工具不可调用"。本节以下内容描述的是**编排骨架**
> （循环、并行、上下文、steering），在工具集问题上**已不代表现状** ——
> 排查工具/天气/定位类问题前请先读第六节「本地侧工具集现状与 schema 定义路径」。

## 一、结论摘要

1. **你的 AgentLoopEngine 已经实现了 Hermes 编排能力的绝大部分**，且在三处更强：KV 前缀稳定性优化（Hermes 没有）、循环保护（去重/轮次/总时长，Hermes 只有 maxTurns）、声明化工具卡片 UI。
2. **真正缺的有 5 项，其中 2 项值得补**（P1）：steer 注入机制、空回复语义化 fallback。
3. 两者循环**骨架同构**（决策→工具→回填→再决策），协议差异（OpenAI tools vs Qwen `<tool_call>`）只是语法层，不需要统一。

## 二、循环骨架对照（run() 主流程）

| 阶段 | HermesAgentLoop（AgentLoop.kt） | AgentLoopEngine.java（你的） |
| --- | --- | --- |
| 入口 | `run(messages)` 就地修改 OpenAI 格式消息列表 | `run(userMessage, enableThinking, priorHistory)` 构造本地 history |
| 每轮 | `for (turn in 0 until maxTurns)`，`server.chatCompletion(messages, tools)` | `for (iteration in 1..getAgentMaxIterations())`，`buildRequestJson → generateWithChatJsonSync` |
| 解析工具调用 | `choices[0].message.tool_calls`（标准 OpenAI 结构） | native 层 PEG parser 下发 `tool_call` 事件（Qwen 模板），Java 侧不猜格式 |
| 参数校验 | `validToolNames` 白名单 + JSON 解析失败→错误结果回填 | 无显式白名单；native 解析 + `toolManager.resolveToolNameFuzzy` 模糊归一 |
| 工具分发 | `ToolDispatcher.dispatch`，多工具 `coroutineScope + async` 并行 | `executeToolSafely`，Thread 并行 + 按原顺序回填（`tool_call_id` 配对） |
| 结果回填 | `role:"tool"` + `tool_call_id` 追加消息列表 | `ChatMessage("tool", ...)` + id 配对；重复调用注入 `call_hint_*` 合成 id |
| 循环终止 | 无 `tool_calls` → 返回最终文本；`maxTurns`（默认 30） | 无 `tool_calls` → cleanResponse 即答案；`MAX_TOOL_ROUNDS` + `TOTAL_TIME_BUDGET_MS` + 去重保护 |
| 统一退出 | `AgentResult(messages, turnsUsed, finishedNaturally, toolErrors, reasoningPerTurn, pendingSteer)` | 统一退出路径：`synthesizeAbortedToolResults` → `generateFinalAnswer`（tool_choice=none 总结）→ `lastMeaningfulResponse` 兜底 |

## 三、能力矩阵

### A. 你已有且不输 Hermes（甚至更强）

| 能力 | 说明 |
| --- | --- |
| 并行工具执行 + 按序回填 | 两者一致；你有工具级超时 + 总轮超时 |
| 上下文预算/裁剪/压缩 | `computePromptBudget` / `trimHistoryToFit` / `buildCompactionSummary` ≈ Hermes `ContextCompressor` / `TrajectoryCompressor` |
| 循环保护 | 执行去重 + 连续重复强制收尾 + 工具轮次上限 + 总时长预算（Hermes 只有 maxTurns） |
| **KV 前缀稳定性** | `sessionMessages` 逐字节回放 → KV INCREMENTAL（本地推理性能关键；Hermes 无此项） |
| 工具动态注入 | 按模型实际调用增长工具集（Hermes 是固定 `toolSchemas` 全量传入） |
| 提示词泄漏检测 | `isPromptLeakage`（Hermes 无显式实现） |
| 思考提取 | `extractThought` + reasoning 回调（Hermes `extractReasoning` 同构） |
| 流式 UI | `onToken` / `onToolCall` / `onToolPresent` 声明化卡片（Hermes 是 `AgentEvent` 简单事件流） |

### B. Hermes 有、你缺（可借鉴项）

| # | 能力 | Hermes 实现 | 你的现状 | 建议优先级 |
| --- | --- | --- | --- | --- |
| B1 | **steer 注入机制** | `steer()` + `_preApiCallSteerDrain` / `_applyPendingSteerToToolResults`（运行中注入用户指引到下一轮 tool 结果，不打断循环、保持角色交替） | 无；生成中无法改指令 | **P1 值得做** |
| B2 | **空回复语义化 fallback** | 区分"纯思考无正文"（reasoning≠null）vs"上下文过大/工具结果超长"（turn>0 且无 reasoning），给不同提示文案 | `cleanResponse.isEmpty()` → break 走统一退出，不区分原因 | **P1 值得做** |
| B3 | **结构化工具错误审计** | `ToolError(turn, toolName, arguments, error, toolResult)` 收集未知工具/无效 JSON/执行异常/exit_code<0 | 靠 AILogger 日志，无结构化 per-turn 错误列表 | P2 看情况 |
| B4 | 无效 JSON 参数回填 | 解析失败→错误 JSON 回填，引导模型自纠正 | 依赖 native 解析，无此回填 | P2 看情况 |
| B5 | 子 Agent 嵌套 | `delegate_task` / `spawn_agent`（子 HermesAgentLoop 递归） | 无（单助手场景） | P3 暂不做 |
| B6 | 统一事件协议 | `AgentEvent`（Thinking/Delta/ToolCallStart/End/Final/Error） | 已有 callback 体系近似覆盖 | P3 暂不做 |

### C. 协议层差异（不必改）

| 维度 | Hermes | 你 |
| --- | --- | --- |
| 协议 | 标准 OpenAI tools（OpenAI/VLLM/SGLang/OpenRouter/本地 ManagedServer 通用） | 本地 = Qwen 原生 `<tool_call>`（llama.cpp 模板）；在线 = OpenAI 兼容 |
| 说明 | 两者编排骨架同构，协议只是"语法层"；你的双引擎各走各的协议是正确的，无需统一 | |

## 四、建议落地清单

### P1-1：steer 注入机制（B1）

**目的**：流式生成/工具循环运行中，用户补充指令（如"别用网络搜索，直接用题库"）能作用于下一轮，而不打断当前工具批次、不破坏 user/assistant/tool 角色交替。

**对照 Hermes 的实现要点**：
- `@Volatile _pendingSteer` + synchronized 锁（可跨线程调用，不阻塞主循环）
- 消费点 1（pre-API drain）：下次 `chatCompletion` 前，扫描最后一个 `role:"tool"` 消息追加 `"\n\nUser guidance: {text}"`
- 消费点 2（post-batch drain）：每个工具结果追加后 / 整批工具结果追加后，立即注入
- 注入失败（无 tool 消息可挂）→ 重新 stash，等下一个消费点

**在 AgentLoopEngine 的落点**：
- 加字段 `private volatile String pendingUserGuidance = null;` + `public void steer(String text)`（拼接）
- 每轮工具结果回填（`history.add(new ChatMessage("tool", ...))`）后追加标记；若本轮无工具调用则保留到下一轮
- 注意：你的历史是 `ChatMessage` 而非 Map，注入 = 修改最后一条 tool 消息的 content 末尾追加 `\n\n用户补充：...`，或新增一条 system 提示消息（需验证 Qwen 模板角色交替约束）

### P1-2：空回复语义化 fallback（B2）

**目的**：模型返回空内容时给用户可操作的提示，而不是静默收尾。

**对照 Hermes**：
- `finalText.isBlank() && (turn > 0 || reasoning != null)` 时给两条不同文案：
  - `reasoning != null` → "[AI 已完成思考但未输出可见回复，请换个说法重试]"
  - 否则 → "[返回空响应，通常是上下文过大（如工具结果超长），请更具体地重试]"

**在 AgentLoopEngine 的落点**：`cleanResponse.isEmpty()` break 处，按 `reasoning != null` 与 `iteration > 1` 分支给不同提示文案，并经 `callback.onComplete` 交付。

### P2-3：结构化工具错误记录（B3）

在 `AgentLoopEngine` 增加 `List<ToolError>`（turn/toolName/arguments/error/toolResult），在工具失败分支（`isToolSuccess == false`）与执行异常处填充；`AgentResponse` 或 callback 暴露，供 UI 展示与排查。

### P2-4：无效 JSON 参数回填（B4）

若某天本地模型出现"输出 JSON 但解析失败"（native 未识别），可在 Java 侧加一道兜底：识别 `response` 中疑似 tool_call 片段解析失败 → 回填一条 `[系统] 参数 JSON 无效，请重试` tool 消息，让模型自纠。当前 native PEG 解析已足够，此项仅作保险。

### P3：暂不做

- B5 子 Agent 嵌套：单助手场景无收益
- B6 统一事件协议：现有 callback 已覆盖同等能力

## 五、参考源码位置

**Hermes 侧**（`D:\qzq\smartquiz\ref\HermesApp\`）：
- `hermes-android\src\main\java\com\xiaomo\hermes\hermes\AgentLoop.kt` — 主循环（steer：L209-378；run：L459-890）
- `hermes-android\src\main\java\com\xiaomo\hermes\hermes\agent\ContextEngine.kt` / `ContextCompressor.kt` — 上下文管理
- `hermes-android\src\main\java\com\xiaomo\hermes\hermes\tools\SkillsHub.kt` / `SkillsTool.kt` — 技能体系
- `app\src\main\java\com\ai\assistance\operit\hermes\HermesAdapter.kt` / `OperitToolDispatcher.kt` — 壳侧桥接

**你的侧**：
- `src\main\java\com\oilquiz\app\ai\agent\software\engine\AgentLoopEngine.java` — 主循环（run：L278-854；compaction：L1804；buildSystemPrompt：L1832 / buildFcSystemPrompt：L1855）

---

## 六、本地侧工具集现状与 schema 定义路径（2026-10-11 更新）

> 本节记录本文档五节之后**本地侧发生的两项结构性变化**，供后续排查时先读这里。
> 与上方"能力矩阵"的差异：上方描述的是编排骨架（循环/并行/上下文/steering），
> 本节描述的是**工具集与 schema 的供给方式**。

### 6.1 本地 Agent 的工具集已收敛为 4 个

`AgentLoopEngine.DEFAULT_CORE_TOOLS`：

```
收窄前：time_date, location, ai_weather, network_search, smart_research, memory,
        tool_registry, file_reader, workspace
收窄后：time_date, location, ai_weather, network_search      ← 本地只做这四个
```

`tool_registry`（原 list/search/get 发现通道）、`smart_research`、`memory`、`file_reader`、
`workspace` 已移出。

### 6.2 ⚠ 关键约束：未注入 schema 的工具**不可调用**（与 Hermes 侧的本质差异）

Hermes 侧走标准 OpenAI tools 协议，模型可以自由点名任何工具名；**本地侧不行**。
native 用模板推导的 PEG 语法解析模型输出，而该语法对工具名是**逐字枚举**的：

```cpp
// llama.cpp/common/chat-peg-parser.cpp: common_chat_peg_builder::build_json_tools_flat_keys
for (const auto & tool_def : tools) {          // 只遍历本次请求传入的 tools
    std::string name = function.at("name");
    auto tool_name_ = ... atomic(literal("\"") + tool_name(literal(name)) + literal("\""));
    auto tool_args_ = ... tool_args(schema(json(), "tool-" + name + "-schema", params));
}
```

推论（三条都已被证伪过，别再试）：

- ❌ **"猜名调用"救不回来**。`AIToolManager.resolveToolNameFuzzy` 只归一化**已在 tools 数组里
  的名字**（如别名 `ui_control` → `ui_component`），无法让未注入的工具变得可调用。
- ❌ **在 system prompt 里列"能力清单"没用**。模型看得到名字，但 generate 不出那个工具名 ——
  解析失败后整段 tool_call 退化成正文。
- ❌ **现有"动态注入"补不回来**。`AgentLoopEngine` 的注入逻辑要求工具**先被成功调用**才加入
  `activeTools`（下轮才有 schema），而没 schema 就调不动 → 死锁。该分支现为**休眠路径**
  （`!contains` 恒不成立），保留仅为将来恢复发现通道时复用。

**结论**：4 个工具就是本地 Agent 的能力上限。将来若要恢复 4 个之外的能力，唯一出路是新增
**引擎侧注入通道**（意图匹配注入，或发现工具返回工具名后由 Java 加入 `activeTools`），
改 prompt 或让模型猜名字均无效。→ 与本文档上方 B1/B2 的"可借鉴项"性质不同，这是**前置约束**。

### 6.3 四个工具的 schema 来自两条不同路径（排查时最容易踩的坑）

| 工具 | 定义来源 | 参数质量 |
| --- | --- | --- |
| `ai_weather` | `AIToolManager.getToolDefinition` 的 **switch 定义** | ✅ 类型正确、有 enum、有默认值 |
| `network_search` | 同上 | ✅ 类型正确（integer/boolean/array） |
| `time_date` | **无 switch case** → `createToolDefinitionFromAITool` **注解路径** | ⚠️ 参数一律 `string`、`required=false` |
| `location` | 同上，注解路径 | ⚠️ 同上 |

注解路径的行为（`AIToolManager:1940-1948`）：`getParameterDescriptions()` 返回
`Map<String,String>`，被一律注册为 `string` + 非必填；**枚举值只能靠
`PARAM_ENUM_OVERRIDES` 手工补**，否则 action 退化成无约束字符串（"2B 只敢用第一个值"）。

已补齐：`PARAM_ENUM_OVERRIDES` 现在含 `time_date`（7 个 action）与 `location`（3 个 action），
**枚举值与各自 `execute()` 的 switch 分支逐字一致** —— 改 `execute()` 分支时必须同步改这张表，
否则模型会调出"未知操作"。

**已知残留限制**：注解路径的参数类型/必填/默认值全部丢失（`time_date` 的 `timestamp` 实际是
integer、`date_to_timestamp` 的 `date` 实际必填，schema 里看不出来），目前只靠文本描述兜底。
根治需给 `AITool` 增结构化参数接口（参考 `StructuredParamTool`）。

### 6.4 system prompt 的当前形态

`buildFcSystemPrompt()` 现有 **4 行规则**（工作/方式/工具选择/边界），已不再是
2026-10-07 版的"无规则段最小版"——`RULES-REMOVED(2026-10-07)` 的前提（工具集 9 个、
靠模型自主发现、schema 足以引导）在 4 工具限定下不成立：

- schema 只能描述"单个工具怎么用"，**无法表达跨工具选用规则**；
- 也无法表达"只准用这四个"和"能力边界外的请求怎么办"。

rules 里两条必须保留的硬约束：① 只准这四个（否则模型凭训练记忆点名 → 整轮退化）；
② 能力边界外如实说明（否则模型拿 `network_search` 去凑生成文件/读文件的需求）。

**天气位置参数的写法**（易写错，与代码强耦合）：`executeToolSafely` 只在模型**完全没给**
`city`/`lat`/`lon` 时才调 `fillWeatherLocation` 自动补手机定位经纬度；给了 `city` 就按城市名查。
所以"问用户所在城市"的正确写法是**不带任何位置参数**，而不是先调一次 `location`
（多烧一轮推理，拿到的经纬度还得自己填回去）。

⚠ **2026-10-11 修正（NO-CITY-SUBSTITUTE）**：上文原有"查不到会丢弃该 city 改走位置兜底重试一次"，
该行为**已移除**。原因是它在用户明确问"镇江天气"而城市查不到时，会**静默返回设备所在城市**的天气
—— 属"错误数据冒充正确数据"，比查询失败更糟，且与 system prompt 的
"如实告知查不到，不要用别的城市顶上"直接冲突。现在：

- 用户**未指定城市** → 不带参数 → `fillWeatherLocation` 补设备经纬度（正确：问的就是用户所在地）
- 用户**已指定城市** → 解析失败就如实报错，**不再替换为设备所在城市**

### 6.5 核对手段（真机排查第一步）

`AgentLoopEngine` 启动时会打印实际下发的 system prompt 与 tools schema（截断）：

```
adb logcat -s AgentLoopEngine | grep -E "system prompt dump|tools schema dump|Core tools|effective temperature"
```

局部行为异常时**先看这四行**，确认 prompt / schema / 工具集 / 温度是否符合预期，
再判断是模型问题还是定义问题。

---

## 七、城市解析链现状（CITY-RESOLVE，2026-10-11 统一后）

> 天气类问题（查不到城市 / 403 / 返回了错误城市）**先读本节**。
> 与第六节同属"排查前先读这里"的知识。

### 7.1 唯一数据源：CSV，不是硬编码表

城市数据**只有一个来源**：`src/main/assets/China-City-List-latest.csv`
（**3578 个城市**，含 `Location_ID` / 中英文名 / adm1 / adm2 / **经纬度** / AD_code）。

**`DEFAULT_CITY_IDS` 已整表删除**（约 365 行硬编码）。删除依据：

1. 它有**错编码**，会返回错误城市的数据（按 CSV 主键反查确认）：
   `镇江 → 101191101`（该码实为**常州**）、`襄阳 → 101200501`、`湛江 → 101310201`（实为**三亚**）、
   `茂名 → 101310301`（实为**三沙**）
2. 它自称兜底"CSV 可能缺失的港澳台"，但实测 CSV **已含**香港 `101320101`、澳门 `101330101`
   与台湾地区 363 条 —— 前提不成立
3. 它的唯一价值（CSV 异步加载期间同步可用）被 7.2 的竞态修复抵消

**回退方式**：若发现某城市确实缺失，**补进 CSV**，不要恢复硬编码表。

### 7.2 CSV 加载是确定性等待（曾是真 bug）

`QWeatherCityManager` 的 CSV 由后台线程加载，而原实现的查表方法
（`getCityById` / `getCityByName` / `getHotCities`）**直接读 map、不检查也不等待**，
`searchCities` / `findCityNamesInMessage` 则静默返回空。后果：加载完成前的天气查询查不到城市，
沿 `resolveHefengLocationParam` 回落 geo API（**该端点无 JWT 权限时 403**）——
这就是"**天气详情页正常、Agent 却 403**"的根因（详情页通常在 App 跑起来后才被打开）。

**现状**：`CountDownLatch loadLatch` + `ensureLoaded()`（上限 3000ms，实测远快于此），
所有查表方法读取前等待；加载线程无论成败都在 `finally` 中 `countDown()`；
`allCities` 的写入与快照读取统一 `synchronized`。

### 7.3 两条解析入口共用一段前缀（消除漂移）

| 方法 | 返回 | 共享前缀 | 各自回退 |
| --- | --- | --- | --- |
| `resolveHefengLocationParam` | 编码 或 `"lon,lat"` | `resolveCityEntry(city)` | 设备 Geocoder → geo API(取 locationId) |
| `getHefengLatLon` | `{lat,lon}` | 同上 | 设备 Geocoder → geo API(取坐标) |

`resolveCityEntry(city)` 是**唯一**做 CSV 查表的地方：

```
0. 纯数字 → getCityById（主键反查编码/坐标）
1. 精确匹配 → 2. 去行政后缀（市/县/区/旗/盟/州/地区/自治州）→ 3. 关键词搜索
```

> **为什么必须共用**：这两个函数原本各维护一份相同解析链，于是出现"只修一处"的 bug ——
> 数字编码特判只加在了 `resolveHefengLocationParam`（当时名为 `getHefengLocationId`），
> `getHefengLatLon` 拿**编码**去查**名字索引**
> 必然 miss，导致 `air_quality`/`alerts`/`indices`/`minutely` 这四个
> **URL 路径必须坐标**的 action 直接失败（`current`/`forecast` 用 `location` 参数，
> 和风原生接受编码，所以**默认查询正常、只有空气质量/预警/生活指数会炸**，不易发现）。

### 7.4 两条路径的语义区分（改一处要同时改 prompt）

| 场景 | 行为 | 为什么 |
| --- | --- | --- |
| 用户**未指定城市**（"今天天气"） | 不带位置参数 → `fillWeatherLocation` 补**设备**经纬度 | 问的就是用户所在地，正确 |
| 用户**已指定城市**（"镇江天气"） | 传 `city`，失败则**如实报错** | 不可用设备定位顶替 → 会返回错误城市的数据 |

该规则与 system prompt 的 **ACTION-LOCATION** 段、注入的 `【本次任务】` 文案**三处必须一致**
（历史上已两次出现两处打架：一次是 prompt 说"不要带参数"而注入说"用 location 的 lat/lon"，
一次是 prompt 说"不要用别的城市顶上"而引擎重试仍在替换）。**改天气位置逻辑时三处一起看。**

### 7.5 核对手段

```
adb logcat -s QWeatherCityManager | grep -E "Loaded .* cities|not loaded within"
adb logcat -s AIWeatherManager     | grep -E "Local city lookup hit|City code lookup hit|System Geocoder hit|GeoAPI failed"
adb logcat -s AgentLoopEngine      | grep -E "ai_weather"
```

- 看到 `Loaded 3578 cities from CSV` → 表加载正常
- `City code lookup hit: 101280101 -> 广州` → 编码入参正确落到了坐标（COORD-CODE-FIX 生效标志）
- 频繁出现 `GeoAPI failed` → 本地链未命中而回落 geo API（该端点 JWT 403），
  应查 CSV 是否覆盖该城市，**而不是**去恢复硬编码表

