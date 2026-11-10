# 知识库子系统评审（2026-11-10）

评审对象：`com.oilquiz.app.ai.knowledge`（评审时口径 v4 / 2026-10-28；当日已改成 v5，见下方修订块）
涉及文件：

| 文件 | 角色 |
|---|---|
| `src/main/java/com/oilquiz/app/ai/knowledge/KnowledgeBaseManager.java` | 存储/检索/切块/迁移（1785 行） |
| `src/main/java/com/oilquiz/app/ai/knowledge/KnowledgeBaseTool.java` | 13 个 action 的工具入口 |
| `src/main/java/com/oilquiz/app/ai/tool/AIToolManager.java:2238` | 在线 function calling 的显式 schema |
| `src/main/java/com/oilquiz/app/ai/agent/online/OnlineAgentEngine.java:2089` | **每轮用户消息**自动注入【相关资料】 |
| `src/main/java/com/oilquiz/app/ai/agent/online/OnlineToolManager.java:791 / :870` | tool_defs 入知识库 + **每轮**按用户消息召回工具 |
| `src/main/java/com/oilquiz/app/ai/agent/online/OnlinePromptBuilder.java:488` | 【知识库】使用指引 |

---

## 一、先说结论

这套东西**不是 RAG**，是"离线全文检索 + 渐进披露 + 一个谓词式工具面"的个人知识库。
方向是对的，取舍也清楚（不引 FTS、不引向量、独立库、零外部 API），工程质量在个人项目里算上乘：
迁移有版本标记、0 命中与检索失败分开报、`update` 保 id、`tag` 走整词、tool_defs 有版本门控、
单测覆盖了精确取/歧义标题/迁移。

但**检索语义这一层有一个结构性问题**，而且它恰好卡在两条最贵的通道上（每轮自动注入、每轮工具发现），
导致"知识库明明有内容，模型却经常拿到 0 命中"。下面按严重度列。

---

## 二、P0：要动手的

> **修订（2026-11-10 当日，第三轮：基础设施补齐，DB_VERSION 4 → 5）**：这一轮把"知识库数据库够不够结实"
> 收敛成三件事，并全部做完：
> 1. **索引与过滤下推**：`kb_chunks` 补 `source` / `updated_at` / `doc_id` 三个索引；
>    `tool_defs` 过滤从工具层 Java 侧（发生在 `top_k` 截断**之后**）下沉到 `searchInternal` 的 SQL
>    `WHERE category <> 'tool_defs'`；`list` 支持 `offset`（返回 `next_offset`）；
>    `stats` 的 `db_version` 改读 `PRAGMA user_version`，并新增 `documents` 计数。
> 2. **坏库自愈**：`openWithRecovery()` 捕获 `SQLiteDatabaseCorruptException` / `malformed` / `not a database`，
>    把库文件**改名留档**（`*.corrupted-<时间戳>`，不删）后重建空库；普通 SQL 错误照旧抛出。
> 3. **文档层**：新增 `kb_documents` 表 + `kb_chunks.doc_id/chunk_seq`；去重从"每次捞全篇现算 MD5"
>    改成 O(1) 读 `doc_hash`；`search`/`list`/`get` 带溯源字段；`export` 保 `chunk_seq` 并在导入时按
>    `source` 重新聚成篇；`updateChunk`/`deleteById`/`deleteByTitle`/`deleteBySource`/`deleteByCategory`/`clear`
>    都维护文档记录（`refreshDocument` 重算哈希与块数）。
>
> 迁移：结构走 `onUpgrade`（`ALTER TABLE` 幂等补列），**数据回填**走开库后的 `ensureDocumentsV5()`
> （按 `source` 分组、一次只装一篇，不再复制 v4 全表进 List 的 OOM 写法），标记 `kb_meta.documents_version`。
> 单测 41 条（知识库 4 个类共 41 条；本轮新增 11 条：文档身份/块序、删块重算指纹、按标题删清文档、
> `offset` 翻页、tool_defs 下推、`stats` 版本、`clear` 清文档表、export→import 保结构、
> 无 source 条目不算文档、坏库判定边界、坏文件后可用性）。
> **仍未处理**：P1-6 切块质量、P2-9 的 `AILogger` 级埋点、P2-11 `lastError` 竞态、P2-13 路径白名单、
> `rebuildSearchTextV4` 的全表 `List` 写法（新代码没有复制，老方法未改）、VACUUM、`search` 回整段正文。

> **修订（2026-11-10 当日，第二轮）**：P0-3、P1-5、P2-10 已改完。
> · `addBatch` 改为事务提交成功才认账（`committed` 标志），回滚一律返回 0；
> · 新增 `delete(source=…)`（按来源删整篇）、`export`（流式导出 JSON，可 `import_file` 导回）、
>   导入去重（按 `source` 现算正文 MD5，同内容跳过、内容变了报冲突，**没有加列也没有升 DB_VERSION**）；
> · `clear` 恢复为**全清**（含 `tool_defs`），`include_tool_defs` 参数已删除——自愈逻辑让"保护"失去必要性；
> · `semantic` 的描述改成"只重排、不扩大召回";
> · 新增 `KnowledgeBaseToolAdminTest`（3 条）+ `KnowledgeBaseManagerTest` 4 条，全量 1821 条单测绿。
> 顺手补了 `assets/tool_guide_flow.json` 里 `export` 的中文词条（`ToolGuideFlowValidatorTest` 要求全覆盖）。
> 仍未处理：P1-6 切块质量、P1-7 溯源、P2-9 埋点以外的性能项、P2-11 lastError 竞态、P2-13 路径白名单。

> **修订（2026-11-10 当日，第一轮）**：P0-1、P0-2 已按下述方案改完并跑绿。
> 落地位置：`KnowledgeBaseManager`（`tokenizeQuery` 分段 + `searchInternal` OR 召回/覆盖率排序 + `scoreRow`），
> 口径写进 `docs/knowledge_base.md` 第四章 v5；新增 5 条单测：
> `queryTokensBreakAtStopwords`、`naturalLanguageQueryHitsRealDoc`、`extraScenarioWordsDoNotSinkRecall`、
> `coverageRanksRelevantAboveGeneric`、`oldCandidateIsNotDroppedByRecentRowBias`。
> `KnowledgeBaseManagerTest` 15 条 + 迁移/Tool 用例全绿。
> **索引格式没变，不需要重建索引、不需要抬 `search_text_version`。**
> P0-3 及其后各项仍未处理。

### P0-1　bigram 全 AND = 变相"整句逐字匹配"，自然语言查询几乎必然 0 命中

`searchInternal` 对每个 token 都拼 `AND`（`KnowledgeBaseManager.java:601-614`）；
`tokenizeQuery` 把中文连续段切成**相邻两字**（`:770-773`），所以 bigram **会跨词边界**。

举例，库里有一条"光气泄漏应急处理流程"：

- 库侧 `search_text`：`光气 气泄 泄漏 漏应 应急 急处 处理 理流 流程 …`
- 查询"光气泄漏怎么处理" → `光气 气泄 泄漏 漏怎 怎么 么处 处理`
- 差在 `漏怎`、`么处` 这两个跨词 bigram → **全 AND 直接 0 命中**

只有两种查询能稳定命中：单个词（"光合作用"）、或与文档逐字相同的短语。
而模型的输入是自然语言整句——这正是最典型的用法。

对比 v3（逐字 AND）：`{光,气,泄,漏,处,理}` 都在文档里 → 命中。
**v4 为了压假阳性，加了相邻性约束，顺手把长查询的召回打死了。** 文档第 54 行把它描述成
"顺序敏感、假阳性大幅下降"，漏了代价这一半。

实际受影响的两条通道（都是把**原始用户消息**当 query 传进去）：

1. `OnlineAgentEngine.buildKnowledgeContext()` → `kb.search(userMessage.trim(), null, 8, false)`
2. `OnlineToolManager.getToolNamesByKnowledgeSearch()` → `kb.search(userMessage, "tool_defs", topN, false)`

也就是：**每轮注入的【相关资料】和每轮的工具自动发现，基本处于静默失效状态**。
工具发现失效后还有 `tool_registry` 兜底，所以没人发现；资料注入失效后模型就"凭空作答"。

单测为什么没抓到：`KnowledgeBaseManagerTest` 里全是 `search("光合作用")` 这种**逐字出现在文档里**的短语。

> 换 FTS5 也救不了：FTS5 的 `MATCH 'a b'` 默认同样是隐式 AND（要用 OR 得显式写 OR）。
> 这是**查询语义**的问题，不是索引组件的问题。判据见 `docs/knowledge_base.md` 第六章，
> 那一章把病根归为"排序"，其实病根是"召回"。

**改法（最小可行）**：SQL 层改成 OR 召回、Java 层按覆盖度排序。

```java
// 1) WHERE 用 OR，保留空格边界整 token 匹配
for (String token : tokens) {
    where.append(" OR (' ' || search_text || ' ') LIKE ?");
}
// 2) Java 侧新增 coverage
int hit = 0;
for (String t : tokens) if (containsAny(title, cat, keywords, content, t)) hit++;
double coverage = (double) hit / tokens.size();
// 3) 排序键从 score 改成 (coverage, score)；并丢掉 coverage 过低的行
if (coverage < 0.34) continue;
```

配套两点：

- query token 里剔掉**停用 bigram**（`的/了/是/和/在/怎么/什么/如何/可以/需要/为什么`）；
- `MAX_QUERY_TOKENS = 12`（`:79`）下，中文一句话只够 13 个字，剩下的直接丢——OR 之后建议提到 24。

回归测试（现在会红，正好当修复的验收）：

```java
@Test
public void naturalLanguageQueryShouldHitLongDoc() {
    add("光气泄漏应急处理", "guide", "", "光气泄漏时应立即……处理流程分三步", "");
    assertEquals("整句提问也要能命中", 1, hits("光气泄漏怎么处理"));
}
```

### P0-2　候选先按 `updated_at` 截断，再打分——相关度排序只在前 50 条里生效

```java
int fetchLimit = hasQuery ? Math.min(MAX_CANDIDATES, Math.max(limit * 10, 50)) : limit;  // :630
... + " ORDER BY updated_at DESC LIMIT ?"                                                 // :635
```

`limit=5` → **只取 50 行**，然后才在 Java 侧算分排序。命中集一超过 50，剩下的根本进不了排序。

更糟的是批量导入：`addBatch` 给同一批所有行写**同一个** `now`（`:1045`），
`import_document` 的 `storeChunks` 也一样（`:1246`）。时间戳全相同 → `ORDER BY updated_at DESC`
实际退化成 rowid 顺序，取到的是**这批导入里最前面那 50 条**，跟"最新"无关。

**改法**：SQL 不截断（或 `LIMIT 2000`、`ORDER BY id`），打分后取 topK。
个人库量级全表扫描本来就能接受（文档自己也这么说），没必要在 SQL 层提前砍。

### P0-3　`addBatch` 事务回滚了，却照样报 `added=N`

`:1054-1078`：`added++` 在 try 里累加；任何一行抛异常 → 不调 `setTransactionSuccessful()`
→ 全部回滚，但 `return added` 仍返回回滚前的数字，工具层如实透出
`{"success":false,"added":37}`。用户/模型看到"失败但加了 37 条"，实际一条没进。

**改法**：`added` 只在 `setTransactionSuccessful()` 之后才赋值给返回值，或加 `committed` 标志。

**状态（✅ 已改）**：加了 `boolean committed`，`return committed ? added : 0`。
没有配单测——Robolectric 下没法稳定构造"中途某行抛异常"，靠代码结构保证（改成报告口径一致而非新增分支）。

---

## 三、P1：该排进计划

### P1-4　`semantic=true` 不是语义召回，只是"前 top_k 条内部重排"

`search()` 先 `searchInternal(..., limit)` 截断到 `limit`（默认 5），再 `applySemanticRerank`（`:403-408`），
函数内 `n = min(len, SEMANTIC_CANDIDATES=8)` → 实际只对 **≤5 条**做余弦重排。

- 关键词没命中的块，语义重排**永远召不回来**；
- 代价：query 1 次 + 候选 ≤8 次 embedding 调用，再加 rerank，换来的只是"5 条换个顺序"。

名字和文档都容易让人误判（`docs/knowledge_base.md` 第一节写"`semantic=true` 才消费在线 embedding/rerank"，
读起来像语义检索）。**改法**：要么改名为 `rerank`，要么把它做成"召回前置"——
先向量召回候选，再用关键词/BM25 合并（这才是 hybrid）。现有 `keywords` + 预留 embedding 列的设计支持后者。

### P1-5　重复导入没有去重、没有按来源删、没有导出

- `import_document` 再导同一个文件 → 整份再插一遍，库里两份内容，检索结果重复；
  表里也没有 `doc_hash`/`content_hash` 可用。
- 只有 `delete(id)` / `delete(title)`，**没有 `delete(source=…)`**。文档改版后想换掉旧版本，
  只能 `list` 里一条条删，或者 `delete(title)` 一把删（见 P2-10）。
- 有 `import_json`，**没有 `export_json`**。这个库是用户的唯一资产，
  文档第 82 行自己写"删除后没有源文件就找不回来"——却没有备份出口。

**改法**（都很便宜）：表加 `source_hash`（正文 sha1 前 16 位）+ `doc_title`；
`import_document` 同 `(source, source_hash)` 直接跳过并返回 `skipped=N`；
新增 `delete_by_source`、`export_json`（直接复用 `list` + `get` 拼 `{"chunks":[…]}`，能被 `import_json` 吃回去）。

**状态（✅ 已改，改法与上面不同）**：没有加列、没有升 `DB_VERSION`、没有回填。
去重靠"按 `source` 现查正文拼起来算 MD5"（`inspectSource` + `contentHash`），一篇几十块的开销可忽略；
同内容 → `{"success":true,"added":0,"skipped":true}`，内容不同 → `{"success":false,"conflict":true}`（**不静默覆盖**）。
`delete(source=…)`、`export`（`BufferedWriter` 逐块流式写，防大库 OOM）都已接进工具层与显式 `ToolDefinition`。

### P1-6　切块质量：非 Markdown 文档"全篇一个标题、无重叠、有碎片块、关键词没区分度"

`chunkText`（`:1300-1338`）只认 `^#{1,6} ` 的标题行。PDF/Word/Excel/纯 TXT 经
`FileContentExtractor` 出来没有 `#`，于是：

- 整篇累积成一个 buffer → 只有末尾 flush 一次 → 全部块 `title = docTitle`（`:1347`）；
- 超长才按 800 字硬切，**块之间没有 overlap**，答案跨边界就被切断；
- `splitLongText` 先按空行分段落，短段落各自成块（`<150` 字的碎块会大量出现，`search_text` 里信息密度很低）；
- `extractKeywords(title)` 用标题抽词（`:1349`）→ 同一文档的所有块 keywords **完全相同**，
  既不能区分块，`tag` 过滤也会一次命中整篇。

**改法**：非 MD 文档按 `。`/`\n\n` 切到 ~800 字、加 100 字 overlap；
`<150` 字块并入相邻块；keywords 改成从**块正文**抽前 6 个高频 4-gram，或干脆留空（别用标题）。

### P1-7　没有溯源字段，回答无法标出处

表里只有 `source = 文件绝对路径`（`createTables :194-205`），没有块序号、页码、字符区间。
模型回答"根据你的资料……"时给不出"哪份文件第几段"，用户无法核验。
`SearchResult` 已有的 `snippet` 也只是"内容截窗"，不是定位。

**改法**：加 `chunk_seq INTEGER`、`page INTEGER`（PDF 能填就填），`search` 结果返回
`source` + `chunk_seq`，渲染成 `文件名#3`。

**状态（✅ 已改，2026-11-10 第三轮 v5）**：`kb_chunks` 补 `doc_id` + `chunk_seq`，
`kb_documents` 提供篇级身份；`search`/`list`/`get` 都返回这两个字段，模型可以说"《手册》第 3 块"。
`page` 没做——`FileContentExtractor` 现在只回纯文本，拿不到页号，加个假页码不如不加。

### P1-8　排序信号其实只有"词频 + 长度"，因为 AND 之后覆盖度恒等于 1

`scoreOf`（`:706-731`）设计得像 BM25：`1+log(1+tf)` + 字段权重 3/1 + 长度归一。
但在 AND 语义下，**每一行都命中全部 token**，覆盖度没有区分度，排序实际只剩
"谁重复得多、谁更短"。改成 OR + coverage 之后，这套打分才会真正开始工作。

---

## 四、P2：小问题，但会在真实使用里咬人

### P2-9　全表 LIKE × token 数，且没有耗时观测

每个 token 一次 `(' ' || search_text || ' ') LIKE '% t %'`（`:607-609`），12 个 token 就是
12 次全表扫 + 12 次字符串拼接。`search_text` 是 bigram 流，**体积约正文的 3 倍**（N 字正文 ≈ N-1 个
bigram × 3 字符）。1 万块 × 800 字 ≈ 30 MB 的列要被扫 12 遍，秒级。

文档第六章的判据（1 万条 / 可感知延迟）是对的，但现在**没有埋点**，到阈值那天你只能靠感觉。
**改法**：`search` 里记 `tok_cnt`、`rows_scanned`、`elapsed_ms` 到 `AILogger`；超 500 ms 打 warn。
到阈值再按他们已有的 A 方案（抽象一层开库 + 自带 SQLite FTS5 + canary 测试）切。

**实测修正（2026-11-10 第三轮，`KnowledgeBaseScaleProbeTest`）**：上面"12 个 token = 12 次全表扫 / 秒级"
**是错的**。实测 4000 块（库 2.1 MB）：

```
rows=4000  insert=1270ms | 1token=20ms | 3tokens=21ms | 6tokens=20ms
```

- **OR 链会短路**：`(A OR B OR C …)` 里第一个条件命中就不再评估后面的，所以 token 数几乎不影响耗时
  （1/3/6 token 全是 20 ms），成本 ≈ 命中行数 × 行宽 × 1 次 LIKE。
- 4000 块 ≈ 20 ms（Robolectric，真机大概 50–150 ms 量级）。按此推算 1 万块 ≈ 50 ms 级，
  对"每轮注入一次"完全够用——**瓶颈不在 token 数，在命中行数与单块正文体积**。
- 仍未做的观测：`tok_cnt` / `rows_scanned` 进 `AILogger`（现在只有 500 ms 的 `Log.w`）。

### P2-10　`clear` 会连 tool_defs 一起清；`delete(title)` 对导入文档等于"删整篇"

- `clear()` 删全表（`:1640`），包括 `category=tool_defs`。文档 5.1 写了要抬
  `TOOL_DEFS_VERSION` 才能回灌——但工具层没有任何保护，模型一句"清空知识库"就踩雷。
  **改法**：`clear` 默认 `WHERE category <> 'tool_defs'`，加参数 `include_tool_defs=true` 才全清（同时清掉 prefs 里的版本标记）。
- `deleteByTitle` 是 `DELETE WHERE title=?`（`:1585`），而导入文档所有块同标题 → **一次删一整篇**。
  工具描述只写"按标题精确匹配"，模型大概率不知道这个后果。**改法**：命中多行时返回"将删除 N 条（整篇），需确认"，或直接要求改用 id。

**状态（✅ 已改，后按用户意见回退为全清）**：一度做成 `clear()` 默认 `WHERE category <> 'tool_defs'`、
`clear(true)` 全清、工具层加 `include_tool_defs`。后来**撤掉了这个保护**——它唯一的正当性是
"清掉之后回不来"，而自愈逻辑（见下条）已经解决了这点，所以 `clear()` 恢复为**全清**，
`include_tool_defs` 参数一并删除（少一个 flag 就少一种模型填错的可能）。「大不了重新建」是用户原话。
`delete(title)` 命中多块时在结果里回一条 `note` 说明"一并删除了该标题下 N 块（整篇）"，不阻断；
真正要整篇替换时用新加的 `delete(source=…)`。

**顺带发现并修掉的连带问题**：`tool_defs_version` 存在 SharedPreferences，数据在 db，两者会错位——
`clear(include_tool_defs=true)` 清掉 tool_defs 后标记还在（或删 db 文件重来但 prefs 没清），
`ensureToolDefsInKnowledgeBase()` 只信标记就直接 return，**工具用法检索从此永久空着**。
现在判据改为"版本对得上 **且** `countByCategory(tool_defs) > 0`"，不一致时自愈重灌一次
（`countByCategory` 查询失败返回 -1，按"已灌过"处理，避免反复重建）。
代价是每轮 Agent 初始化多一次 `count(*)`（几十到几千行、约 1ms 量级）。

### P2-11　`lastError` 是实例字段，不是调用级返回

`manager.search(...)` 之后靠 `manager.getLastError()` 取错误（`KnowledgeBaseTool` 多处）。
`lastError` 是 `volatile` 的实例字段，两个线程/两次调用交错时，A 的错误会被 B 覆盖，
模型可能把"检索失败"读成"没找到"（这正是当初加它要避免的情况）。
**改法**：让 `search/listMeta/getById` 把 `error` 塞进返回结构；`lastError` 只留兼容。

### P2-12　tool_defs 的过滤发生在截断之后

`doSearch`（`:288-298`）先拿 topK 结果，再在 Java 侧剔掉 `tool_defs`。
查询词同时命中工具定义时，前三四个位置被占掉，用户实际拿到的条数变少。
`buildKnowledgeContext` 同理（`OnlineAgentEngine :2107`，`injected >= 3` 才 break，但名额已被跳过项消耗）。
**改法**：过滤下推到 SQL（`AND category <> 'tool_defs'`）。

**状态（✅ 已改，2026-11-10 第三轮）**：下推到 `searchInternal` 的 `WHERE ... AND category <> 'tool_defs'`，
只有显式 `category=tool_defs` 才绕过（`OnlineToolManager` 每轮工具召回正是这么调的，不受影响）。
`KnowledgeBaseTool.doSearch` 里那段 Java 侧二次剔除已删除——留着它等于让过滤继续发生在截断之后。
`OnlineAgentEngine.buildKnowledgeContext`（`search(msg, null, 8, false)`）随之只注入用户资料，不再被工具定义挤掉。
单测 `searchExcludesToolDefsUnlessCategoryAsked` 钉住这个口径。

### P2-13　`import_file` / `import_document` 接受任意绝对路径

路径来自模型参数，没有任何前缀校验（`:1136-1153`、`KnowledgeBaseTool :461-492`）。
提示注入场景下可以"把 /sdcard 上某个文件导进知识库"，再 `get` 出来读走——一条不需要文件权限弹窗的外泄通道。
**改法**：限定到 app 私有导入目录（或 SAF URI），路径不在白名单直接拒。

### P2-14　`search` 返回完整正文，跟"渐进披露"自相矛盾

索引层精心做了 `list` 不带正文（这点很好），但 `search` 每条仍回 `content` 全文 + `snippet`
（`:647-654`），5 条 × 800 字 ≈ 4000 字进上下文，其中大半和 `snippet` 重复。
**改法**：`search` 默认只回 `snippet` + `content_len` + `id`，正文用 `get`；给 `full=true` 才回全文。

### P2-15　杂项

- `stats` 的 `db_version` 回的是 `DB_VERSION` 常量，不是库里的 `user_version`；也没回 `search_text_version`。
  → **已改（第三轮）**：改读 `PRAGMA user_version`，并补 `documents` 计数；`search_text_version` / `documents_version` 仍未回。
- `listMeta` 不回 `total`，模型看不到"一共多少条"，只能猜。
  → **部分改（第三轮）**：list 支持 `offset` 并在满页时回 `next_offset`，翻页不再靠猜；`total` 仍不回（要的话加一次 `count(*)`）。
- `category` 进了 `search_text` 也进打分：查"general"会命中所有默认分类行。
- `v4` 迁移 `rebuildSearchTextV4` 把全表拉进内存 `List<Object[]>`（`:170-179`），1 万块 × 3KB ≈ 30 MB 峰值，低内存机型有风险；建议分批（500 行一批）。
- 首次开库若在主线程触发迁移（`getReadableDb` 每次都走 `ensureSearchTextV4`），几千行逐行 UPDATE 会卡启动/ANR。

---

## 五、做得对、别在重构时弄丢的

1. **独立库** `knowledge_base.db`，跟主库零耦合——省了无数迁移风险。
2. **不引 FTS**：真机上踩过 FTS5 未编译、FTS4 布尔语法随编译开关漂移。这套判断是有实测支撑的（第六章、canary 测试都在）。
3. **渐进披露** `list`(不带正文) → `get(id)`，以及 `search` 支持 `id`/`exact_title` 精确取——这是省 token 的正解。
4. **`tag` 走整词**（`tagClause` 逗号包边），把"别名/同义词"从分词里救出来。
5. **`update` 保 id、只改传入字段**，明确禁止"删了重加"。
6. **0 命中 ≠ 失败**：三参 `AIToolResult(..., true)` 显式区分（`:315-319`），这是很细的一手。
7. **tool_defs 版本门控 + 先删后灌**，避免重复条目残渣。
8. **迁移格式版本存库内 `kb_meta`** 而不是 SharedPreferences，理由（生命周期错位）写得对。

---

## 六、建议的动手顺序

| 顺序 | 动作 | 改动量 | 收益 |
|---|---|---|---|
| 1 | P0-1 改 OR 召回 + 覆盖度排序 + 停用 bigram | `searchInternal` + `tokenizeQuery`，~60 行 | **把两条每轮通道救活**，体感提升最大（✅ 已改） |
| 2 | P0-2 去 SQL 侧提前截断 | ~5 行 | 相关度排序真正生效（✅ 已改） |
| 3 | P0-3 `addBatch` 回报修正 | ~5 行 | 不再骗人（✅ 已改） |
| 4 | P2-12 tool_defs 过滤下推 SQL | ~10 行 | 每轮少一次无效召回（✅ 已改） |
| 5 | P1-5 `export` / `delete(source)` / 导入去重 | ~120 行（**未加列、未升 DB_VERSION**） | 资产可备份、改版可替换（✅ 已改；第三轮去重改走 `doc_hash`，DB_VERSION 4→5） |
| 6 | P1-4 把 `semantic` 改名 `rerank` 并在描述里写明边界 | 文档 + 2 处文案 | 不再误导（文案已改，字段名未改） |
| 7 | P1-6 切块 overlap / 最短块合并 | `chunkText`，~40 行 | 长文档可读性 |
| 8 | P1-7 溯源 `chunk_seq` | 表 + 返回结构 | 回答能标出处（✅ 已改，`doc_id` + `chunk_seq`，顺带建了文档层） |
| 9 | P2-9 检索耗时埋点 | ~10 行 | 到 1 万条时不用凭感觉（✅ 已加 500ms 告警） |
| 10 | P2-10 `clear` 语义收敛（全清 + 自愈重灌）/ `delete(title)` 说明 | ~15 行 | 防误删（✅ 已改） |
| 11 | 第三轮：索引补齐 + 坏库自愈 + 文档层 | `KnowledgeBaseManager` ~＋400 行、`DB_VERSION 4→5` | 查询走索引、坏库不再永久废掉、去重 O(1)、能溯源（✅ 已改） |

前 4 项加起来不到 100 行，但决定了"这套知识库到底能不能用"。

---

## 七、一句话

工程细节扎实、取舍清楚，**唯一的结构性问题是"查询语义"**：
把"关键词集合匹配"实现成了"整句逐字匹配"，
而最依赖它的两条通道（每轮自动注入、工具自动发现）恰好都用自然语言整句当查询。
先把召回从 AND 改成 OR+覆盖度，这套系统才叫真的能用。

---

## 八、架构评估：该不该换数据库 / 检索引擎（2026-11-10 第四轮）

**触发**：用户说"这个数据库明显不行"，追问后确认**没有具体故障**，属于"看代码觉得架构该升级"。
所以先取数，不凭感觉动架构。

### 8.1 实测（`KnowledgeBaseScaleProbeTest`）

| 规模 | 库体积 | 批量插入 | 1 token 检索 | 3 tokens | 6 tokens |
|---|---|---|---|---|---|
| 4000 块 | 2.1 MB | 1270 ms（一次事务） | 20 ms | 21 ms | 20 ms |

- **token 数几乎不影响耗时**：`(A OR B OR C…)` 在 SQLite 里会**短路**，第一个条件命中就不评估后面。
  成本 ≈ 命中行数 × 行宽 × 一次 LIKE。按线性外推 1 万块 ≈ 50 ms 级（Robolectric 口径，真机约 50–150 ms）。
- 所以本评审 4.4 节 P2-9 原来写的"12 个 token = 12 次全表扫、秒级"**是错的**，已就地更正。
  这条教训本身很值钱：**架构结论必须先取数**，否则会照着一个凭感觉的量级去换引擎。

### 8.2 现状能力盘点

| 能做 | 弱 / 不能 |
|---|---|
| 全文召回（OR + 覆盖率门槛）、精确取（id/标题全等）、tag 整词、分类过滤 | 语义召回（同义/改写/跨语言）：`semantic=true` 只重排已召回的 top_k，召不回就是召不回 |
| 文档层（`doc_id`/`chunk_seq` 溯源）、O(1) 去重、整篇替换、导出/导回保结构 | 单次命中超过 `MAX_SCAN_ROWS=5000` 的行**静默不参与排序**（只打一条 warn） |
| 坏库自愈（留档重建）、事务、双层迁移标记（结构 `onUpgrade` / 数据 `kb_meta`） | 数据回填跑在**开库路径的主线程**上（一次事务、一次性完成），首次开大库会卡 |
| 0 命中与失败用三参 `AIToolResult` 显式区分 | 观测：只有 `SLOW_SEARCH_MS=500` 的 `Log.w`，没有 `AILogger`、没有 `rows_scanned` |
| 索引：category/title/source/updated_at/doc_id | 无 `VACUUM`，删完/清空后空间不回收 |

### 8.3 三个候选方案

| 方案 | 解决什么 | 收益 | 代价 | 判断 |
|---|---|---|---|---|
| **A. 现状 + 调度/容量/可观测升级** | 主线程迁移卡顿、5000 行截断、出问题不知道慢在哪 | 中 | **低**（~150 行，不动 schema、不抬版本） | ✅ 现在做 |
| **B. FTS5 + 自带 SQLite（`mil.nga:sqlite-android`）** | 真倒排、bm25、短语/前缀查询；省掉全表扫 | **低**（20 ms 不是瓶颈；且 `MATCH 'a b'` 默认也是隐式 AND，召回语义得自己写 OR） | 高：APK +3 MB、两套 SQLite 并存、单测要分双实现（A 方案抽象开库层）、重建索引 + 回填 | ❌ 暂不做 |
| **C. 向量检索（sqlite-vec / 本地 embedding）** | 同义、改写、口语化提问的召回 | **高**（如果"搜不到"是主诉） | 高：要 embedding 模型或在线服务（离线不成立）、每轮成本、索引与增量更新 | ⏸ 等"搜不到/搜不准"成为主诉再评估 |

结论：**现在换引擎，是用"架构正统感"换 3 MB 和双 SQLite 维护成本，收益接近 0。**
真正的架构债不在引擎，在"检索调度、迁移执行、可观测"这三处。

### 8.4 建议动作（按收益/成本排序，都不动 schema）

1. **检索埋点**：`tok_cnt` / `rows_scanned` / `elapsed_ms` 进 `AILogger`（现在只有 500 ms 的 `Log.w`，
   等于没有）。没有这个，下次"要不要换引擎"还得靠猜。
2. **`MAX_SCAN_ROWS` 自适应**：库总量 ≤ 5000 就不截断；超限时按"分数分两轮"（先扫最近 N 行排序取满，
   再扫剩余）而不是直接丢掉后面的候选。现在这条是**静默丢数据**，比慢更糟。
3. **迁移出主线程**：`ensureSearchTextV4` / `ensureDocumentsV5` 从开库路径挪到后台，
   500 行一批、可续跑（`kb_meta` 已有 `documents_version` 可做断点）。
4. **`rebuildSearchTextV4` 改分批**：老的全表进 `List<Object[]>` 写法还在（万块级 ~30 MB 峰值）。
5. **VACUUM**：`export` / `clear` 之后触发一次（可选，代价是一次全库重写）。
6. **保留探针**：`KnowledgeBaseScaleProbeTest` 当"要不要换"的取数工具，改 `ROWS` 跑一次就有答案。

### 8.5 如果坚持"搞专用检索库"：正确的落法与真实代价

先把一句话拆开："数据库专用数据库"里其实是两件事——

- **主库（存数据）**：`kb_chunks` + `kb_documents`。访问模式只有"按 id 取 / 按分类列 / 全文扫"三种，
  关系库不需要再专业化，换 Room 或换 PostgreSQL 式建模都不会有收益。
- **索引（找数据）**：这里"专用"是对的。专业做法就是**倒排索引**（Lucene / Tantivy / SQLite FTS5），
  而不是在一张宽表上 `LIKE`。所以正确动作不是换数据库，是**给知识库加一个专用检索索引，主库不动**。

**能拿到什么**（都是现在确实没有的能力）：

| 能力 | 现状（LIKE） | FTS5 倒排 |
|---|---|---|
| 短语/邻近（"光气 泄漏"必须相邻） | 做不到（OR 召回） | `"光气 泄漏"` 短语查询 |
| 前缀查询 | 做不到 | `前缀*` |
| 排序 | 自己拍的 `3.0/1.0 + 长度惩罚` | `bm25()` 标准相关度 |
| 容量上限 | 命中 > 5000 行静默丢弃 | 无此限制 |
| 增量维护 | 每次写入重算 `search_text`（bigram 流） | 触发器增量同步 |

**代价的真身**（不是"换个库"这么简单）：

1. **APK +3 MB**（`mil.nga:sqlite-android` 带 arm64/armeabi-v7a/x86_64 三套 native，
   需要 `abiFilters` 收窄，否则更多）。
2. **两套 SQLite**：系统库和自带库**不能共享连接**，现在直连 `SQLiteOpenHelper` 的地方全部要经过
   一层 `openDatabase()` 抽象；`getReadableDb()` / `getWritableDb()` 的分支要能按"是否需要 FTS5"选择实现。
3. **双写一致性**：FTS5 用 **external content table**（`content='kb_chunks', content_rowid='id'`）
   + insert/delete/update 三个触发器，`deleteById`/`deleteByTitle`/`updateChunk`/`clear` 全都要覆盖；
   漏一个就出现"表里有、搜不到"。
4. **备份/恢复**：`exportJson` 只导 `kb_chunks`（现在就是这样），索引必须在导入后**重建**，不能当数据源。
5. **测试双实现**：Robolectric **跑不了 FTS5**，单测必须保留 LIKE 实现兜底，否则 CI 全红。
   所以 `Searcher` 必须是接口（`searchLikes` / `searchFts`），不是"把 LIKE 删掉换成 FTS"。
6. **迁移**：首次建索引对 1 万块要约几秒，**不能在主线程**（见 8.4 第 3 条，同一个坑）。

**建议的落地顺序（影子索引，最坏只是多花 3 MB，不会搜不出东西）**：

1. **抽象 `KnowledgeSearcher`**：`search()` 接口 + `LikeSearcher`（现有 `searchInternal` 搬过去，行为不变）。
   这一步零风险、不动 schema、不抬版本、测试全绿。
2. **加 `FtsSearcher` 只写不读**：后台建 FTS5 索引（external content + 触发器），
   线上查询仍然走 LIKE；新增一个"双查询对比"诊断（把两套 top8 打 diff 到 `AILogger`）。
3. **对比通过再切读路径**：`FtsSearcher` 上线后保持 LIKE 兜底——FTS5 不可用 / 索引未建好 / 查询抛异常，
   自动回落 LIKE（设备差异 `no such module: fts5` 的坑已经有 `BundledSqliteFts5DeviceTest` 兜住）。

**不推荐的**：Chroma / Milvus / LanceDB 这类服务端或独立向量库——离线 APP 里要自带进程与外置模型，
与"个人知识库"的量级完全不匹配；真要做语义召回，优先考虑在线 embedding 重排（已有 `semantic` 通道）。

### 8.6 已落地：C 方案（本地向量，2026-11-10 / DB_VERSION 6）

用户确认目标只是"让 Agent 更好用"，并选了 C（写入侧算向量）。实现口径：

| 环节 | 做法 | 为什么 |
|---|---|---|
| 写入 | `addChunk`/`addBatch`/`storeChunks` 成功后提交**单线程后台任务**，每批 24 块补 `embedding IS NULL` | 不在写入事务里算：否则导入从秒级变分钟级；单线程避免打爆 embedding 限额 |
| 存储 | `kb_chunks.embedding` **float32 BLOB**（小端） | 1 万块 × 768 维 ≈ 30 MB；存 JSON 文本贵一个数量级 |
| 查询 | 只算 **1 次** query 向量，其余本地点积（候选按 category 过滤、默认排除 tool_defs） | 旧重排每轮要 10 次调用，这才是"能用得起"的形态 |
| 融合 | **RRF**（`Σ 1/(60+rank)`），关键词 + 向量两路 | 两路分数量纲不同，按排名融合不用调参；向量独有命中能进榜 |
| 门槛 | 余弦 < **0.30** 丢弃 | 不让向量把噪声灌进结果 |
| 换模型 | `kb_meta.embedding_model` 一变 → `embedding` 整列置 NULL 重建；维度不一致的旧向量余弦返回 -1 | 换模型后拿旧维度硬算会产生一堆假相似 |
| 失败 | 没配模型 / 没回填 / 调用失败 → 向量路返回空，关键词结果原样保留 | 新能力不能把老能力弄坏 |

工具层：`search` 的 `semantic=true` 改为"有向量就两路融合、没向量退回旧重排"；新增 `action=embed`
给老库/换模型后补向量（已有向量的块跳过）；`stats` 多回 `embedded` / `embedding_model`；
`tool_guide_flow.json` 的 action 词条补 `embed`（`ToolGuideFlowValidatorTest` 会盯这个）。

**仍未做**（下一步的可选项，不是缺陷）：查询改写（1 次 LLM 调用把口语问题压成关键词组）、
向量量化（int8 省 4 倍）、ANN 索引（万级以上才需要，现在是全量点积）、rerank 精排（已有代码，配了才走）。

### 8.7 补一层：本地向量模型（离线 ONNX，2026-11-10 / v6.1）

8.6 只解决了"便宜"，没解决"依赖服务商"——DeepSeek 没有 embedding，用户又可能一个带
`embeddingModel` 的端点都没配。用户提的"功能模型可以增加个本地的向量小模型"是对的，
但它**接不进现有的 `FEATURE_*` 机制**（那套是在线端点之间选模型），也不能走 llama.cpp
（`LlamaHelper` 只读 `nEmbd` 元数据，没有 embedding 输出通道）。实际落法：

| 环节 | 做法 | 为什么 |
|---|---|---|
| 运行时 | **ONNX Runtime 1.18**（包里已有：RapidOCR / SenseVoice 在用） | 只加模型文件，不加运行时、不改 native |
| 模型 | `bge-base-zh-v1.5` int8（768 维，约 98MB），`filesDir/ai_models/embedding/bge-base-zh-v1.5/` | 不打包 APK，安装包体积不变；**先用 bge-small 真机跑过一轮，模糊问法召回噪声明显（"路线规划"召出 douyin_downloader），换 base 补精度** |
| 分词 | 自写 `BertWordPieceTokenizer`（21128 词表、中文逐字、英文 wordpiece） | ONNX 只吃 token id；项目里没有现成的 BERT 分词，引第三方库不如自己写 100 行 |
| 池化 | CLS（`last_hidden_state[:,0,:]`）+ L2 归一 | bge 官方用法；mean pooling 对短句没必要 |
| 取用顺序 | 注入（测试）> 本地（文件在）> 在线（配置表声明了 embeddingModel） | 本地零成本、离线、不依赖 key；文件不在就跳过，连加载都不尝试 |
| 下载 | `embed` 在"既没本地也没在线"时，或显式传 `local=true` 时触发**后台**下载（hf-mirror 优先、HF 兜底），立即返回 | 近 100MB 同步下会把工具调用卡住，违背"不给主 agent 制造故障" |
| 状态 | `stats` 增 `embedding_source`（local/online/downloading/none）、`local_embedding`（missing/downloading/ready/failed）、`local_embedding_dir` | 用户选了"只在知识库工具里报状态"，不加 UI |
| 失败 | 加载失败封存（本次运行不再重试），交给已有熔断；换来源时 `kb_meta.embedding_model=local\|模型名` 整列作废 | 本地与在线向量不同空间，混算余弦只会得噪声 |

已验证：`hf-mirror.com/Xenova/bge-base-zh-v1.5/resolve/main/onnx/model_quantized.onnx`
返回 200、`Content-Length=102,868,746`；`vocab.txt` 同为 21128 行（与 small 共用同一份 bert-base-chinese 词表）。
真机（25113PN0EC）跑 small 版时：`LocalEmbedding` 下载完成 → `libonnxruntime4j_jni.so` 加载 ok →
`本地向量模型已加载（输入 [input_ids, attention_mask, token_type_ids]，输出 [last_hidden_state]）`，
59 条 tool_defs 全部重算成功、`embedding_source=local`，语义召回实测生效（关键词 0 分条目靠 cosine 0.56/0.60 进榜）。
HF 官方直连在开发机上 25s 超时，所以**镜像优先**不是可选优化，是必需。

未验证（留真机）：单条 300 字的推理耗时、首次加载耗时；JVM 单测加载不了 ORT native，
只钉了"缺失/损坏时静默不启用"这条路径（`LocalEmbeddingModelTest` 4 条 + 分词 7 条）。

