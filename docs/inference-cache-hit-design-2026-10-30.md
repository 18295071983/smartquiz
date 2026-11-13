# 推理引擎缓存命中设计 v1.2（2026-11-13）

> 版本 1.2 | 取代同日 v1.1（2026-10-30）
> v1.2 的性质是**修复回填**：v1.1 的隐患台账里 **10 条全部关闭**（5.1–5.10），
> 另有一条子结论过时（§4.3 的"扩展预算实际 2"）。
> 本次核对方式：**只读源码 + 真跑构建与单测**（`compileDebugUnitTestJavaWithJavac`、`externalNativeBuildDebug`、`testDebugUnitTest` 全量 1926 用例）。

---

## 0. 一页结论（当前状态）

三层里只剩两层在跑：

- **层1（本地 KV 增量）**：`INCREMENTAL` 真省；`PARTIAL` 在混合架构模型上截断必失败、回退全量。**指标已改成说真话**——`hitRate()` 的分子只算真正复用成功的部分，计划态另给一个键。
- **层2（应用层 Prompt→Response 缓存）**：**已整体删除**。它读侧只覆盖"本地普通对话"，而路由统一后那条路只剩"Agent 引擎未就绪时降级"一个入口 → 缓存几乎只写不读，且会把在线/Agent 答案搬给本地普通对话。缓存类与 5 处调用点全部撤除。
- **层3（在线前缀缓存）**：优化项仍成立；本日新增**历史裁剪滞回**（超 110% 才裁、裁到 80%），把"预算边缘每轮打掉前缀"改成"偶发一次"。

剩余真正的问题只有一类：**本地链路缺少可信的单轮观测**（KV-STATS 仍是 DEBUG 级、逐轮 prompt/eval/耗时没有归口），这直接决定后续多阶段/反思能不能量化。

---

## 1. 两层全景

```
┌──────────────────────────────────────────────────────────────┐
│ 层3  在线 API 服务商前缀缓存命中（OnlineAgentEngine）          │
│      省输入 token 账单；命中数已接线到 UI（TokenStatsBar）     │
├──────────────────────────────────────────────────────────────┤
│ 层1  本地 KV 增量缓存（AgentKvCache，native C++）              │
│      INCREMENTAL 真省；PARTIAL 名义命中、实际回退全量          │
└──────────────────────────────────────────────────────────────┘
     层2（CacheManager）已删除，见 §3 的历史记录。
```

---

## 2. 层1：本地 KV 增量缓存（AgentKvCache）

### 2.1 仍然成立的基础事实

| 主张 | 证据 |
|---|---|
| 三策略 `INCREMENTAL/PARTIAL/FULL` | `src/main/cpp/agent_kv_cache.h` 策略枚举 |
| `INCREMENTAL` 条件＝严格超集 + 位置一致 | `agent_kv_cache.cpp` 的 `plan()` |
| 记账含生成输出 token | `record()` 把 prompt+output 并入 `cachedTokens_` |
| 外部清 KV 必须 `invalidate()` | `agent_kv_cache.cpp` + native-lib 四处调用 |
| `ctxUsage()=cachedNPast/n_ctx`；`STATS_INTERVAL=10` | 头文件 |

### 2.2 `PARTIAL` 在本机模型上不产生收益（仍成立）

`truncate()` 在 `llama_memory_seq_rm` 返回 false 时回传失败，调用方随即
`useIncremental=false` + `llama_memory_clear` + **全量 prefill**。
根因是 `n_rs_seq=0`（attention+recurrent 混合架构的 recurrent state 无法任意位置截断），
曾试 `n_rs_seq=256` 换取 PARTIAL 成立，代价是内存按 `(1+n_rs_seq)` 放大、被 lowmemorykiller 连杀，故回退。
**本日未改这条**（改它要动 native 内存参数，属另一轮）。

### 2.3 命中率口径 —— ✅ 已修（2026-11-13）

v1.1 指出的问题：`hitRate()` 用 `plan()` 的**策略判定**作分子，不看 `truncate()` 结果，
于是"计划 PARTIAL 但最终全量"被计为命中，设备页可以显示接近 100%。

现状：

- 新增 `partApplied_`，只在 `truncate()` 里 `llama_memory_seq_rm` **成功**时自增；
- `hitRate()` 分子 = `incCount_ + partApplied_`（实际复用）；
- 原口径保留为 `plannedHitRate()`，差值即"打算 PARTIAL 但回退全量"的量；
- JNI 快照新增 `part_applied` 与 `hit_rate_planned_pct`，**`hit_rate_pct` 语义变了但键名没变**，
  三个消费方（`DeviceInfoActivity`、`PerformanceDashboardFragment`、`LlamaSignalAdapter`）无需改动。

### 2.4 聚合统计可见性 —— ✅ 已修（2026-11-13）

原状：`dumpStats()` 走 `AGENT_KV_LOGD_KV`（只进 logcat DEBUG），而 `engineLogAppend` 另有
`if (level == 'D') return;`，因此 App 内"引擎日志"页与落盘日志里永远看不到摘要。

现状：摘要行改走 `AGENT_KV_LOGI`，且**首轮也打一次**（`planCount_ == 1`）——只跑几次 plan 的短会话
否则一条摘要都没有，而"第一次就没命中"恰恰最有诊断价值；之后每 10 次 plan 再打一行。

`PARTIAL seq_rm 失败` 那一批保持 DEBUG 不动：那是设计内的性能回退，不该在用户视图里冒充故障。

### 2.5 单轮账 ROUND-STATS —— ✅ 新增（2026-11-13）

KV-STATS 解决的是"聚合命中率"，不解决"这一轮花了多少"。补上逐轮账：

```
[I][InferenceGateway] ROUND-STATS route=AgentLoopEngine prompt=4096 gen=100
                      prefill_ms=500 decode_ms=2000 tps=50.0 kv=PARTIAL matched=512
```

- **不增加推理开销**：prefill/decode 的分界就是"第一条流式事件"，只用两个时间戳分开，
  不需要采样线程，也不依赖 UI 是否可见。
- **token 数取自 native 记账**（`prompt_tokens` / `generated_tokens`），不是数回调事件——
  工具轮的 arguments 增量不算正文。
- **两条本地路径都接了**：`InferenceGateway.chatJson/chatSend`（bridge 普通对话、AIService）
  与 `AgentLoopEngine.generateWithToolsSync`（本地 Agent 直连 native，不经 gateway）。
- 纯算术部分在 `LocalRoundMetrics`（可单测），落点共用 `RoundLog`。
- 单测：`LocalRoundMetricsTest`（5 例，钉 prefill/decode 拆分、无事件、tps 除零、空摘要）。

---

## 3. 层2：应用层答案缓存 —— 已删除（2026-11-13）

### 3.1 历史结论（保留，供追溯）

v1.1 的核心判断是：层2 "只有一个类接线，且它的读与写分属不同引擎路径"。
读写点当时分别在 `AIChatActivity.processChatMessageNormal`（读）与 `completeGeneration`（写，三引擎共用），
于是在线/Agent 的回答会被缓存，之后被"本地 + 普通对话"直接返回，既不推理也不发请求。

### 3.2 为什么最终是删除而不是加闸门

v1.1 建议的最小改动是"写入侧加引擎闸门"。本次先按这个格门做了（`roundCacheable` 标记），
随后核对发现 **`ROUTE-UNIFIED(2026-11-01)` 之后 `processChatMessage` 恒走 Agent**，
`processChatMessageNormal` 只剩"`AgentChatHandler` 未就绪时降级"一条入口 ——
也就是说读侧本身已近乎不可达，缓存退化成"只写不读 + prefs 无界增长"。

因此改为整体删除，一并关闭的隐患见 §5：5.1 / 5.3 / 5.4 / 5.5 / 5.6 / 5.8。

### 3.3 删除清单

- `src/main/java/com/oilquiz/app/ai/refactor/CacheManager.java`（整类）
- `AIChatActivity`：读缓存、写缓存、两处"重生成失效"、字段、初始化，以及为它加的 `roundCacheable`
- `AIConfig`：`cacheEnabled` 字段 / pref 读写 / getter / setter
- `invalidateEngineCache` 的 4 个调用点：`LlamaHelper`（NPU 降级、Vulkan 降级）、
  `AIServiceStatusActivity`（切 GPU 后端）、`UserModelParamsManager`（改 GPU 层数）
- `ResponseCacheManager`：本次核对全仓已无该类，无需再动

残留：旧设备上可能还留着 `cache_enabled` 键与 `ai_cache` prefs 文件，无人读取，无害。

---

## 4. 层3：在线 API 前缀缓存

### 4.1 仍成立的优化项

系统提示词版本标记（`PROMPT_VERSION`，改提示词必须同步递增）、
会话内工具集稳定、核心工具集固定顺序、长期记忆/知识库/当前时刻移出前缀区（D 块尾部注入）、
切换模型按「会话 × 模型」隔离历史文件、`/status` 能直读服务商缓存统计并已接线 UI。

> 注：v1.1 的行号表已整体漂移（本日又改了两处），后续引用请按**方法名/字符串锚**，不要按行号。

### 4.2 前缀稳定域比"tools 只增不改"要小（仍成立）

`getStableToolsJson` 每次 execute 开头会 `cachedToolNames.clear()` 再装入核心集，
真正"只增不改"的窗口只有单次 execute 内的多轮工具循环。
跨用户消息时仍有三处会让 tools 段变化：高频预载（≤2）、`document_parser` 条件注入、附件点名的工具。
核心集那段顺序与字节稳定，失配只发生在追加段。

### 4.3 计数注释已互相矛盾（仍成立，且更明显）

`CORE_TOOLS` 实际是 **8 个**（`ui_component`、`workspace`、`network_search`、`time_date`、
`tool_registry`、`knowledge_base`、`douyin_downloader`、`mcp_toolbox`），
但同一文件里还留着 `核心 6`（3 处）、`精简 12→8`、`核心 5 个（v3）` 等历史注释。

另外 v1.1 说的"扩展预算实际 2 而非 3"**已过时**：`MAX_SESSION_TOOLS=9` 的条数上限已被
`SessionToolBudget.trimToFit` 的**体积口径**取代，`trimSessionTools` 现在按 schema 字节数裁剪。

### 4.4 `isCasualChat` 死支路 —— ✅ 已删（2026-11-13）

该方法恒 `return false`，三处依赖它的分叉（记忆注入、知识库检索、`toolsJson = null`）**从未执行**。
现已删除方法与全部分叉，直写成实况：每轮都注入记忆、都检索知识库、恒带工具。

### 4.5 历史裁剪滞回（本日新增）

在线侧 `trimMessageHistory` 原先按"超过预算 100% 就裁、裁到 50%"，
长对话会在预算边缘反复触发 —— 每裁一次，system 之后的历史整体平移，服务商前缀缓存从第一个被移除的
token 起失效。现改为滞回：

- **超 110% 才裁**（`HISTORY_TRIM_HIGH_WATER_PCT`），
- **一次裁到 80%**（`HISTORY_TRIM_LOW_WATER_PCT`），
- 落在 `[预算, 110%)` 区间时打一行 `History in hysteresis band, not trimming` 说明"故意没裁"。

安全性：预算本身只占模型窗口 60%，110% 仍只到 66%。对应单测
`OnlineAgentEngineHistoryTrimWatermarkTest`（7 例，钉边界与 60 万预算下的溢出）。

**本地侧 `trimHistoryToFit` 也已改成双水位（2026-11-13 补）**，但不能照搬 110%：

- 本地预算 = `门 − OUTPUT_RESERVE_TOKENS(4000)`，大窗口下 `预算 × 110%` 会**越过**
  native 的 `prompt >= safeRef − 512` 拒收线（例如 64K 窗口）；
- 所以高水位 = `预算 + (门 − 预算)/4`，并硬夹在 `门 − 512` 以下；门未知时退回预算本身；
- 裁剪目标 = 预算的 **80%**（不低于 256），裁完要再长回高水位才触发下一次；
- 连预算都没到时走快速放行，不去读 native 门（否则 `getEffectiveContextSize()` 的日志会每轮刷屏）。

对应单测 `LocalHistoryTrimWatermarkTest`（6 例：8K/64K/极小余量/门未知/目标下限/目标必须低于高水位）。

---

## 5. 隐患台账（v1.2 状态）

| # | 隐患 | 状态 |
|---|---|---|
| 5.1 | 层2 读写跨引擎：在线/Agent 答案被普通对话直接返回 | ✅ **关闭**（缓存已删） |
| 5.2 | `hitRate()` 把计划态 PARTIAL 计为命中 | ✅ **关闭**（分子改为实际复用） |
| 5.3 | `prompt.hashCode()` 作 key、取出无 equals 校验 | ✅ 关闭（随类删除） |
| 5.4 | prefs 侧无淘汰、`clearAllCache()` 零调用 | ✅ 关闭（随类删除；旧文件可能残留） |
| 5.5 | 层2 无用户开关（`setCacheEnabled` 零调用） | ✅ 关闭（没有缓存可关） |
| 5.6 | `ResponseCacheManager` 死类被文档当现成能力引用 | ✅ 关闭（类已不存在） |
| 5.7 | KV-STATS 摘要为 DEBUG，引擎日志/落盘看不到 | ✅ **关闭**（改 INFO，且首轮打印） |
| 5.8 | `CacheManager` 非线程安全（accessOrder 的 `get()` 改结构） | ✅ 关闭（随类删除） |
| 5.9 | 核心集计数注释多处矛盾 | ✅ **关闭**（改为"以 `CORE_TOOLS` 为准"，不再复述数量） |
| 5.10 | 本地链路缺可信单轮观测（逐轮 prompt/eval/耗时） | ✅ **关闭**（ROUND-STATS，见 §2.5） |

---

## 6. 剩余动作（按价值排序）

1. **（可选）层3 四态分类**：补"本地内存命中""降级上游"两态；网关命中态已有 UI 消费方。

> 已完成（2026-11-13）：KV-STATS 升 INFO + 首轮打印（§2.4）；本地单轮账 ROUND-STATS（§2.5）；
> 核心集计数注释改为以 `CORE_TOOLS` 为准（5.9）；**本地历史裁剪双水位**（§4.5）。

---

## 7. 验证状态台账（v1.2）

**已跑**：`compileDebugUnitTestJavaWithJavac`（通过）、`externalNativeBuildDebug`（通过，22s 增量）、
`testDebugUnitTest` 全量 **1926 个用例**。

**结果**：5 个失败，均为**本次改动前既有**，已用"暂存改动、在 HEAD 干净树重跑"对照确认：

| 用例 | 断言 |
|---|---|
| `ComponentTypesTest.catalogCoversEveryCanonicalType` | 组件目录超过 29 条（提示词体积守卫在报警） |
| `AssistantTurnTest.toolGroupsDoNotMergeAcrossModelRounds` | 期望每轮一张工具组卡 |
| `DshTimelineTest.toolResultWithoutCall_isDroppedInsteadOfPhantomCard` | 无调用却发出 cardsChanged |
| `DshTimelineTest.replayingSameSeq_doesNotDoubleCount` | 重放同一 seq 被算两次 |
| `DshTimelineTest.jobRegistered_aggregatesPerTurn` | 按轮聚合数不符 |

**未验**：真机 `hit_rate_pct` 的实际数值（尤其改成实际态后是否如预期跌到接近 0）；
DeepSeek 侧真实命中率与 `tools` 段在其缓存键中的位置；本地滞回在长对话上的体感收益。
（KV-STATS 的 INFO 改动只做了编译验证，真机日志是否如期出现在"引擎日志"页待装机确认。）

**复现抓手**：

- 层1 命中率真假：`DeviceInfoActivity` 读 `hit_rate_pct`，同时看**App 内引擎日志**的
  `[I][AgentKvCache] KV-STATS: … hit=… planned=…`（2026-11-13 起 INFO 级、首轮即打印；
  之前只进 logcat DEBUG）；两者差距 = PARTIAL 回退量。
- 层3 滞回：长对话里看 `Cache probe` 的 `sha256` 是否连续一致；若每轮都变，说明前缀仍在抖。
- 层2：现在应完全没有"（来自缓存）"提示，也没有 `ai_cache` 新写入。

---

## 8. 核对方法附记

1. **行号锚点必漂**：`OnlineAgentEngine.java` 与 `AIChatActivity.java` 都在万行级且高频改动，
   结论级主张按"字符串 + 方法边界"重钉，不要按行号复述。
2. **同名近似残留会污染检索**：`native-lib.cpp~RF*.TMP` 这类编辑器残留被 `.gitignore` 挡住但仍在盘上，
   对 `AgentKvCache` / `CacheManager` 做 grep 时命中的行号可能来自它。
3. **"计划态 ≠ 实际态"是这类监控的通用陷阱**：凡是拿"打算怎么做"当"做到了"的指标，都会在失败回退时虚高。
   新增指标时先问一句"这个数在失败路径上会变吗"。

---

## 9. 变更记录：2026-11-13 落实清单

| 改动 | 证据 |
|---|---|
| KV 命中率分子改为 `inc + partApplied`，新增 `plannedHitRate()` | `agent_kv_cache.h/.cpp`、`native-lib.cpp` 的 `nativeGetKvCacheStats` |
| 在线历史裁剪改滞回（110% 触发 / 裁到 80%） | `OnlineAgentEngine.trimMessageHistory` + `OnlineAgentEngineHistoryTrimWatermarkTest` |
| KV-STATS 升到 INFO，且首轮也打一次 | `agent_kv_cache.cpp` 的 `dumpStats()` / `plan()` |
| 本地单轮账 ROUND-STATS（prompt/gen/prefill/decode/tps） | `LocalRoundMetrics` / `RoundLog` / `InferenceGateway` / `AgentLoopEngine.generateWithToolsSync` |
| 本地历史裁剪改双水位（门感知，不照搬 110%） | `AgentLoopEngine.trimHistoryToFit/historyHighWater` + `LocalHistoryTrimWatermarkTest` |
| 删除 `isCasualChat` 及其三处分叉 | `OnlineAgentEngine.execute` |
| 删除 `CacheManager` 与 5 处调用点 | `AIChatActivity` / `AIConfig` / `LlamaHelper` / `AIServiceStatusActivity` / `UserModelParamsManager` |
| 删死代码：`ModelExecutionBridge` 的第二套 agent 循环、`AgentExecutionEngine`、`BridgeTokenCallback` | bridge 1641 → 1055 行；`ChatCommand` 去掉 `SEND_MESSAGE_AGENT` |
| 提示词装配器清理：删 `PromptContext` / `complete`；`tool_discovery` 提到共享层 | `src/main/java/com/oilquiz/app/ai/prompt/`、`OnlinePromptBuilder` |
