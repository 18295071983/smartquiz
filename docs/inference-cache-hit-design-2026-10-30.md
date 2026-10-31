# 推理引擎缓存命中设计 v1.1（2026-10-30）

> 版本 1.1 | 取代同日 v1.0 | 依据：真实源码逐条重钉，代码 tip `b5e5ed6f`（分支 `chore/chat-dead-code-cleanup`）
> 核对方式：只读源码 + 调用图；**未跑构建、未跑测试、未真机验证**（真机项单列在第 7 节）

---

## 0. 一页结论

v1.0 的三层骨架方向是对的，但有三处**结论级**错误，和一批行号漂移。修正后的现状描述是：

- **层1（本地 KV 增量）记账正确，收益未兑现**：`PARTIAL` 在当前部署的混合架构模型上截断必失败、回退全量 prefill，而 `hitRate()` 把 `PARTIAL` 计入命中 → 对外显示的命中率虚高。
- **层2（应用层 Prompt→Response）实际只有一个类接线**，且它的读与写分属不同引擎路径：**在线/Agent 的回答会被缓存，稍后由本地普通对话直接返回，既不发 HTTP 也不推理**。这比 v1.0 强调的 `hashCode` 碰撞更容易触发，且不需要碰撞。
- **层3（在线前缀缓存）优化项确实存在**，但"会话内工具集只增不改"只在**单次 execute 内**成立；跨用户消息是每轮 `clear()` 重建，前缀稳定域比 v1.0 描述的小。另外"核心 6"实为 **7**。

规模判定：**文档改 6 处事实；代码只需在写入侧加一道引擎闸门，几行。不挡开发，不需要重写。**

---

## 1. 三层全景（修正版）

```
┌──────────────────────────────────────────────────────────────┐
│ 层3  在线 API 服务商前缀缓存命中（OnlineAgentEngine）          │
│      省输入 token 账单；命中数已接线到 UI（TokenStatsBar）     │
├──────────────────────────────────────────────────────────────┤
│ 层2  应用层 Prompt→Response（CacheManager）                    │
│      ⚠ 只有 CacheManager 接线，且读侧仅覆盖"本地非 Agent"路径  │
│      ⚠ ResponseCacheManager 是零调用死类，不构成本层           │
├──────────────────────────────────────────────────────────────┤
│ 层1  本地 KV 增量缓存（AgentKvCache，native C++）              │
│      INCREMENTAL 真省；PARTIAL 名义命中、实际回退全量          │
└──────────────────────────────────────────────────────────────┘
```

三层的优化目标不变（省本地 decode / 免网络 / 省输入 token 账单），但"三层互不干扰"这句要收窄：**层2 的缓存条目由三个引擎共同写入，却只被其中一个引擎读取**，因此它会在引擎之间搬运答案（见 5.1）。

---

## 2. 层1：本地 KV 增量缓存（AgentKvCache）

### 2.1 与 v1.0 一致、已复核成立的部分

| v1.0 主张 | 证据 | 判定 |
|---|---|---|
| 文件 `src/main/cpp/agent_kv_cache.h` / `.cpp` | 两文件存在（mtime 10-30 02:19） | 成立 |
| 三策略 `INCREMENTAL/PARTIAL/FULL` | 头文件 53–57 | 成立 |
| `INCREMENTAL` 条件＝严格超集 + 位置一致 | cpp 21–23（`matchedLen==cachedNPast && seqMax==cachedNPast-1`） | 成立 |
| 记账含生成输出 token | cpp 87–98（`record` 把 prompt+output 并入 `cachedTokens_`） | 成立 |
| 外部清 KV 必须 `invalidate()` | cpp 100–107；native-lib 2899/2916/2977/4392 四处调用 | 成立 |
| `hitRate()=(inc+part)/plan`、`ctxUsage()=cachedNPast/n_ctx` | 头 118–124，`STATS_INTERVAL=10`（头 141） | 成立 |
| 日志串 `HIT: matched …` / `bookkeeping updated: …` | cpp 36 / 96 | 成立 |

### 2.2 修正一：PARTIAL 在实机模型上不产生收益

`truncate()` 在 `llama_memory_seq_rm` 返回 false 时回传失败，调用方 [native-lib.cpp](/D:/qzq/smartquiz/src/main/cpp/native-lib.cpp):3720–3729 随即 `useIncremental=false` + `llama_memory_clear` + **全量 prefill**。

而仓库里有一条明确的取舍记录（native-lib.cpp:2326–2334）：`n_rs_seq` 保持默认 0，因为 attention+recurrent 混合架构的 recurrent state 无法任意位置截断，**默认 0 时 `llama_memory_recurrent::seq_rm` 对部分回滚必然返回 false**；曾试设 256 换取 PARTIAL 成立，代价是 recurrent state 张量按 `(1+n_rs_seq)` 线性放大、本机内存枯竭并被 lowmemorykiller 连杀进程，故回退。第 81–83 行的注释另记有"2026-09-23 真机实测：每次工具轮后 PARTIAL 均在此失败，20s/轮"。

结论：层1 当前的真实收益只来自 `INCREMENTAL`；`PARTIAL` 是一条**名义路径**。（该实测结论出自源码注释，本次**未复现**，见第 7 节复现抓手。）

### 2.3 修正二：命中率指标口径掩盖了 2.2

`hitRate()` 用 `plan()` 的**策略判定**作分子，不看 `truncate()` 的结果，因此"计划走 PARTIAL 但最终全量重 prefill"被计为命中。这个数已经出到 UI：

- JNI 快照 `nativeGetKvCacheStats` → `hit_rate_pct`（native-lib.cpp:7648–7670）
- 消费方：`LlamaSignalAdapter.java`:86、`PerformanceDashboardFragment.java`:230、`DeviceInfoActivity.java`:447（"命中率: x%"）

所以 `DeviceInfoActivity` 上的 KV 命中率对这套模型可以接近 100%，而每轮仍在做全量 prefill。**v1.0 的"✅ 层1 记账原则正确"应降级为"记账正确、收益未兑现、观测口径掩盖"**，并在指标名上区分 planned / applied。

### 2.4 修正三：聚合统计在引擎日志里看不到

`dumpStats()` 用的是 `AGENT_KV_LOGD_KV`（头 32），该宏**只发 logcat DEBUG、不进引擎日志环与落盘**；`engineLogAppend` 另有硬过滤 `if (level == 'D') return;`（native-lib.cpp:252）。

后果：`KV-STATS: plans=… hit=…` 这类摘要在 App 内"引擎日志 Tab"不可见，能看到的只有单条 `HIT` / `PARTIAL` / `seq_rm failed`（INFO/WARN/ERROR 级）。另外落盘行格式是 `[I][AgentKvCache] HIT: matched …`（native-lib.cpp:250），**不是** v1.0 写的 `AgentKvCache | HIT: …`。

---

## 3. 层2：应用层 Prompt→Response 缓存

### 3.1 CacheManager（唯一实际接线的）

[CacheManager.java](/D:/qzq/smartquiz/src/main/java/com/oilquiz/app/ai/refactor/CacheManager.java) 结构描述与 v1.0 一致：key=`String.valueOf(prompt.hashCode())`（27/45/61）、内存 LRU 容量 100（13）、TTL 12h（14）、内存 + SharedPreferences 双落地（48）。

**作用域才是它的主要问题。** 读与写的调用点：

| 侧 | 位置 | 所属方法 | 覆盖的引擎路径 |
|---|---|---|---|
| 读 | `AIChatActivity.java`:5184–5187 | `processChatMessageNormal`（5129） | 仅"本地模型且智能助手关"与 NPU 快聊（5101–5105、5117） |
| 写 | 同上 :7193–7196 | `completeGeneration`（6946） | 三个引擎都写：在线 bridge 回调 6361、本地 native 6741、Agent 回调 7544 |
| 失效 | 同上 :9282、:9986 | 重新生成 / 清空 | — |

路由在 5108–5114：**在线模型、或本地 Agent 开启 → 走 `processChatMessageWithAgent`，不查缓存**。于是形成单向搬运——在线（或 Agent）回答一次，12 小时内把同一句原文在"本地 + 助手关"下再问，`addAIMessage(cached)` 直接返回那条答案，只补一行"(来自缓存)"，**本地模型既没推理也没发请求**。这条不需要任何哈希碰撞就能触发。

另有两项次级问题：

- **SharedPreferences 无淘汰**：`cacheResponse`（48）每条整段落 `ai_cache` prefs；内存 LRU 驱逐不会带走 prefs 条目，过期只在"读命中该 key"时顺手删那一条；`clearAllCache()`（51）**零调用** → prefs 无上限增长。
- **用户关不掉**：`cache_enabled` 默认 true（AIConfig.java:103/105），`setCacheEnabled()`（185）**零调用**，没有任何 UI 入口。
- 线程安全：`LRUCache` 是 `accessOrder=true` 的 `LinkedHashMap`（71–73），`get()` 本身会改结构；读在 UI 线程、写在生成回调，无同步。`hits/misses`（68–69）是从未自增的死字段，`AILogger` import（5）未使用。

### 3.2 ResponseCacheManager（死类，不构成"层2 的第二种形态"）

[ResponseCacheManager.java](/D:/qzq/smartquiz/src/main/java/com/oilquiz/app/ai/agent/ResponseCacheManager.java) 在全 `src/` 内**除自身外零引用**（`getInstance` 无任何调用方），因此其 24h TTL（20）、按会话分桶 LRU（69–71、83–96）、`getHitRate()`（98–101）都不参与运行。它声明了 `prefs`（42）却只在 `clearAll()` 里动过一次（80），**从不写入**，所以"持久化"也不成立。

需要注意连带影响：[local-agent-multi-phase-design-2026-10-25.md](/D:/qzq/smartquiz/docs/local-agent-multi-phase-design-2026-10-25.md):29 与 :45 把它当作"现成可用的答案级缓存"引用。后续实现若照此接线，会接上一个从没跑过、且 key 同样是 `hashCode()`（115/120）的对象。建议**删除**，不要接线。

---

## 4. 层3：在线 API 前缀缓存命中优化

### 4.1 行号对照表（v1.0 引用 → 当前实际）

| v1.0 主张 | v1.0 引用 | 实际位置 | 判定 |
|---|---|---|---|
| 系统提示词版本标记 | 75 行附近 | `OnlineAgentEngine.java`:98–105（`PROMPT_VERSION = "20261027-v10"`）；落盘打副本 2845–2852；restore 门控 2907–2913 | 成立，行号漂 |
| 会话内工具集「只增不改」 | 156–163 | 193–200（注释 + `cachedToolNames`/`cachedToolsJson`/`toolsCacheInitialized`） | 表述需收窄，见 4.2 |
| 核心 6 工具稳定前缀 + 高频预载(≤2) + 本轮调用即注册 | 585 | 741–761 组装、2041–2088 `getStableToolsJson` | **核心是 7 个**（204–207） |
| 长期记忆移出前缀区（D 块尾部注入） | 149 / 524 / 1484 | 186–189 字段、688–694 构建、1826–1830 注入 | 成立，行号漂 |
| 知识库 D2 块 | 1493 | 190–191 字段、701–703 构建、1832–1836 注入（提示词正文见 1954） | 成立，行号漂 |
| 切换模型字节级还原 | 78 行注释 | 101 与 2907–2909；另见 143–146（历史按"会话 × 模型"双维度隔离） | 成立，但机制是 **pv 门控的 restoreHistory**，不是"切换动作" |
| 直读 API 缓存统计 | 183–184 / 1297–1328 | 226–228 字段、257 getter、404 每轮清零、1595–1632 读取与 `cachePct = cachedTokens*100/promptTokens`（1627） | 成立，行号漂；且**已接线 UI** |

字段来源已核：`usage.prompt_cache_hit_tokens`（DeepSeek/智谱顶层）与 OpenAI `cached_tokens`，归一在 OnlineInferenceService.java:3237–3254。**v1.0 第六节建议的"四态分类"里，网关命中这一态其实已有消费方**：`TokenStatsBar.java`:136–140 用 `getLastCacheHitTokens()/getLastPromptTokens()` 直接算百分比显示。缺的是"本地内存命中"与"降级上游"两态。

### 4.2 修正：前缀稳定域比 v1.0 描述的小

`getStableToolsJson` **每次 execute（每条用户消息）开头就 `cachedToolNames.clear()`**（2051），再装入核心集。真正"只增不改"的窗口只有单次 execute 内的多轮工具循环（`liveToolsJson` 每 execute 重置，761；模型 `tool_registry(get)` 后调用即注册）。跨用户消息时，tools 段仍会因下列三处而变：

1. 高频预载 ≤2（2056，来自历史共现统计，会随使用行为漂移）；
2. `document_parser` 条件注入（2070–2075，只在识别到文档解析意图的那轮出现）；
3. 附件提示词点名的工具（746–747 `AttachmentToolHints.mentionedTools`，带附件的那轮会加进 `coreTools`）。

由于用的是 `LinkedHashSet` 且核心集先入，**核心 7 那一段的顺序与字节仍稳定**，失配只发生在其后的追加段——这与 v1.0"tools 前缀字节稳定"的说法方向一致但口径过大。附带一个尚未知的边界：服务商把 `tools` 段放在缓存键的什么位置（改动它是否连带作废其后整段历史前缀），属外部实现，本次**未取证实**。

### 4.3 注释里的计数已经互相矛盾（与"清单会漂"同源）

同一个 `CORE_TOOLS` 常量，注释给了至少四种口径：204–207 实际 **7 个**；214 "核心 6 + 最多 3 个扩展"（`MAX_SESSION_TOOLS = 9`，215）；2093 "扩展工具最多 6 个"；735–740 还留着"精简 12→8"和"常驻核心 5 个（v3）"的历史层。

推导后果：`trimSessionTools`（2095–2116）拿 `核心 + 扩展` 一起对 9 这条上限做 LRU，核心实为 7 时**扩展预算实际是 2 而非注释说的 3**，2069 的注释也为此专门给 `document_parser` 打 `toolLastUsed` 时间戳以免被紧接着的淘汰掉。提示词侧你 10-25 已改成动态取 `coreTools()`（209–213），注释层没跟上。

### 4.4 一处死支路（不算风险，但别被它误导）

`isCasualChat()`（2344–2346）**恒 `return false`**，因此 755–757 的 `toolsJson = null`（"闲聊轮省 token"）永不会执行，配套助手 `containsAnyCasual`（2348）也随之失效。752 行注释"闲聊模式已废弃"与此一致——它只是还留在代码里。

---

## 5. 隐患台账（按可触发性排序）

| # | 隐患 | 触发条件 | 后果 | 证据 | 严重度 |
|---|---|---|---|---|---|
| 5.1 | 层2 读写跨引擎：在线/Agent 答案被本地普通对话直接返回 | 同一句原文 12h 内先在线/Agent 答过，再在"本地 + 助手关"问 | 不发 HTTP 不推理，返回别家模型答案；token 统计与实际行为不符 | AIChatActivity 5108–5114 / 5184–5187 / 7193–7196 / 6361 / 6741 / 7544 | **高** |
| 5.2 | `hitRate()` 把计划态 PARTIAL 计为命中 | 混合架构 + `n_rs_seq=0` | DeviceInfo/性能面板显示的高命中率对应全量 prefill，掩盖 20s/轮 | agent_kv_cache.h:118–120；native-lib 3720–3729、2326–2334；DeviceInfoActivity:447 | **高**（指标失真） |
| 5.3 | `prompt.hashCode()` 作 key，取出无 equals 二次校验 | 碰撞 | 返回错内容。概率低，但 5.1 已让它不是主要矛盾 | CacheManager 27/45/61 | 中 |
| 5.4 | prefs 侧无淘汰，`clearAllCache()` 零调用 | 长期使用 | `ai_cache` 无上限增长，整段长回答落盘 | CacheManager 48/51 | 中 |
| 5.5 | 层2 无用户开关（`setCacheEnabled` 零调用），默认开 | — | 用户无法关闭，只事后看到"(来自缓存)" | AIConfig 103/105/185 | 中 |
| 5.6 | `ResponseCacheManager` 死类却被设计文档当现成能力引用 | 后续接线 | 接上从未经运行检验的对象 | 全 src 零引用；local-agent-multi-phase-design:29/45 | 中 |
| 5.7 | KV-STATS 摘要为 DEBUG，引擎日志环/落盘/引擎 Tab 看不到 | 每次 plan%10==0 | 命中率排障只剩 logcat | agent_kv_cache.h:32、cpp:66–69；native-lib:252 | 低 |
| 5.8 | `CacheManager` 非线程安全（`accessOrder=true` 的 `get()` 改结构） | 写在后台回调 | 理论上的结构破坏/可见性问题 | CacheManager 28/71–73 | 低 |
| 5.9 | 核心集计数注释多处互相矛盾，扩展预算实际 2 非 3 | 读注释做判断时 | 误导维护者 | OnlineAgentEngine 204–207/214/2093/735–740 | 低 |

---

## 6. 取向与最小改动（单一方案，不给菜单）

**文档层**：按本文改写 v1.0——层2 从"两个实现"改成"一个接线 + 一个死类"，风险排序改为 作用域不一致 > prefs 无界 > hashCode 碰撞。

**代码层，就动一处**：在 7193 的写入侧加引擎闸门——仅当本轮确实走了"本地非 Agent"路径才 `cacheResponse`，读侧不动。这一处同时压掉 5.1 的主要危害和 5.5 的可见性问题，几行规模。

随后按序跟进（都不是本轮必须）：

1. **层2 建议改成纯内存**：跨进程重启还返回 12h 前的旧答案本身没有价值，纯内存可一并解决 5.4；key 换 SHA-256 并保留"取出后 `prompt.equals` 校验"作为 5.3 的兜底。
2. **`ResponseCacheManager` 直接删**，同时把 local-agent 设计文档 29/45 两处引用改掉，别接线。
3. **让指标说真话**：`partCount` 拆 planned/applied，或 `hitRate()` 只在真正增量 eval 时计数；这需要动 native，重编约 30–50s，建议单独一轮做。
4. `isCasualChat`/`containsAnyCasual` 与 `CacheManager.hits/misses`、未用 import 属死残骸，可按既有死码台账一并处置。
5. 层3 若要补"四态分类"，只补"本地内存命中"与"降级上游"两态——网关命中态已有 UI 消费方。

---

## 7. 验证状态台账

**已核（源码/调用图可复算）**：层1 三策略与记账逻辑；`hitRate`/`ctxUsage` 口径；PARTIAL 回退全量的代码路径；`n_rs_seq=0` 的决策记录；层2 读写作用域与三个写入引擎；`clearAllCache`/`setCacheEnabled`/`ResponseCacheManager` 零调用；层3 全部 7 个优化项的实际位置；`CORE_TOOLS` 实为 7；`isCasualChat` 恒 false；缓存 token 的字段来源与 UI 消费链。

**未核**：真机 `hit_rate_pct` 的实际数值（以及 PARTIAL 是否仍在当前所用模型上失败——本文只引用了源码里 2026-09-23 的实测记录，未复现）；DeepSeek 侧真实命中率与 `tools` 段在其缓存键中的位置；层2 跨引擎命中在你实际使用习惯下的发生频率；未跑构建、未跑测试、未装机。

**自助复现抓手**：

- 层1 命中率真假：`DeviceInfoActivity` 读 `hit_rate_pct`，同时看引擎日志有无 `[E][AgentKvCache] PARTIAL seq_rm failed …` / `[I][AgentKvCache] PARTIAL truncate failed, full eval …`——两者同现即为指标虚高。
- 层2 跨引擎：在线模型答一句 → 关掉智能助手、切本地模型 → 重发同一句原文，看是否秒回并附"(来自缓存)"。
- `adb` 侧可直接读 `ai_cache` / `agent_response_cache` 两个 SharedPreferences 文件是否存在及其体积（后者预期不存在，可反证 3.2）。

---

## 8. 核对方法附记

两个坑值得写进流程：

1. **行号锚点会漂**：`OnlineAgentEngine.java`（181 KB）今日 01:49 才动过，v1.0 的引用整体偏 30–50 行。结论级主张要按"字符串 + 方法边界"重钉，不要按行号复述。
2. **同名近似残留会污染检索**：`src/main/cpp/native-lib.cpp~RF18113ede.TMP` 是编辑器残留，被 `.gitignore:291`（`*.tmp`）挡住、未入 git，但它内含一整套旧 `kvCache` 调用点。对 `AgentKvCache` 做 grep 时命中的行号有一半来自它——本次所有 `native-lib.cpp` 引用均已确认落在真文件上。
