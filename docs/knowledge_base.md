# 答题宝知识库（Knowledge Base）

> 版本：v6（2026-11-10）　模块：`com.oilquiz.app.ai.knowledge`
> 历史设计（v1–v3 的取舍与踩坑日志）见 [`_archive/projects/docs/knowledge_base.md`](../_archive/projects/docs/knowledge_base.md)，
> 本文是**现行**口径，包含 v5 的检索升级、v6 的本地向量召回与维护流程。

## 一、能力

- 全文检索：中文 bigram、英文按词，标题/分类/关键词加权 + BM25 近似打分
- **语义扩召回（v6）**：写入侧把每块向量化（float32 存库），查询侧只算 1 次 query 向量，
  关键词 + 向量两路 RRF 融合——能捞回"关键词一个字都不命中、意思却一样"的问法
- 渐进披露：`list` 看索引（不含正文）→ `get` 取单条 → `search` 模糊找
- 精确入口：`search(id=…)` / `search(exact_title=…)` / `search(tag=…)`（别名、同义词、标签）
- 维护：`add` / `add_batch` / `update` / `delete` / `clear` / `stats` / `embed`
- 导入：`import_json` / `import_file` / `import_document`（Word/Excel/TXT/MD/CSV/PDF/HTML/图片 OCR/音频 ASR）
- 关键词检索完全离线；向量召回只在配了 embedding 模型时启用（写入侧按块调用一次，之后查询侧 1 次）

## 二、架构

```
Agent（本地/在线）
   └─ KnowledgeBaseTool（@Tool 注解 + 动作分发）
        └─ KnowledgeBaseManager（单例、线程安全、坏库自愈）
             └─ knowledge_base.db（独立库，不碰主库）
                  ├─ kb_documents  文档层（一篇一行：doc_hash / chunk_count）
                  ├─ kb_chunks     正文 + search_text（bigram token 流）+ doc_id/chunk_seq + embedding（BLOB）
                  └─ kb_meta       格式版本标记（search_text_version / documents_version / embedding_model）
```

- 独立数据库：`databases/knowledge_base.db`，与 `smartquiz_database` 零耦合。
- 表结构：
  - `kb_chunks(id,title,category,keywords,content,source,search_text,doc_id,chunk_seq,embedding,created_at,updated_at)`
  - `kb_documents(doc_id,title,category,source,doc_hash,chunk_count,created_at,updated_at)`
- 索引：`category`、`title`、`source`、`updated_at`、`doc_id`（chunks 上）、`source`（documents 上）。
- 版本：`DB_VERSION = 6`；`user_version` 管表结构，`kb_meta.search_text_version` 管**检索格式**，
  `kb_meta.documents_version` 管**文档层回填**，`kb_meta.embedding_model` 管**向量模型**（换了就整批重建）。

## 三、动作表（工具层）

| action | 必填 | 说明 |
|---|---|---|
| `list` | — | 轻量索引（id/标题/分类/关键词/来源/更新时间/doc_id/chunk_seq，**不含正文**），默认 20、上限 100；支持 `category`、`tag`、`offset`（翻页，返回带 `next_offset`） |
| `get` | `id` | 取单条全文；缺 id 直接报错，不会退化成返回全部 |
| `search` | `query` 或 `id`/`exact_title` | 模糊检索；传 `id`/`exact_title` 走精确取；`tag` 走 keywords 整词过滤；`category`、`top_k`、`semantic`；**不传 category 默认排除 `tool_defs`** |
| `add` | `title`、`content` | 单条；`keywords` 建议放别名/同义词/英文名（逗号分隔） |
| `add_batch` | `items` | 批量，元素字段同上 |
| `update` | `id` 或 `find_title` | 只改传入字段，id/created_at 不变；`find_title` 必须唯一命中 |
| `import_json` / `import_file` | `json` / `file_path` | 批量导入 JSON（`{"chunks":[…]}` 或裸数组） |
| `import_document` | `file_path` | 直接解析文档/图片/音频并切块入库 |
| `delete` | `id` 或 `title` 或 `source` | 删除；`source` 删掉该来源下**全部块**（文档改版整体替换用）；`title` 命中多块时会一起删 |
| `export` | — | 导出 JSON 到文件（结构与 `import_json` 一致，可 `import_file` 导回）；`file_path` 可选，默认落 app 私有目录 |
| `clear` | — | 清空全部（**含** `category=tool_defs`；tool_defs 下次 Agent 初始化会自动重灌） |
| `stats` | — | 总量、文档数、已向量化块数 `embedded`、当前向量模型 `embedding_model`、分类分布、最近更新时间、库内真实 `db_version`（`PRAGMA user_version`） |
| `embed` | — | 给现有块补本地向量（老库首次接入 / 换 embedding 模型后用）：已有向量的块跳过，按块消耗在线额度；没配模型返回 0 并说明原因 |

### 3.1 tool_defs 不走 Java 侧过滤（v5）

不传 `category` 的检索在 **SQL WHERE 里**就排除 `category='tool_defs'`
（`OnlineToolManager` 每轮召回显式传 `category=tool_defs`，不受影响）。

v4 时这层过滤在 `KnowledgeBaseTool.doSearch` 的 Java 侧、且发生在 `top_k` 截断**之后**——
模型召 8 条有 5 条是工具用法，用户资料被挤掉；被过滤掉的名额也不会补。现在命中集一开始就干净。

## 四、检索实现（v5 关键词 / v6 向量）

| 版本 | 方案 | 结果 |
|---|---|---|
| v1 | FTS5 + unicode61 + bm25 | 部分设备没编译 FTS5 → `no such module: fts5`，不可用 |
| v2 | FTS4 + 中文逐字空格化 + snippet | `AND` 依赖 `SQLITE_ENABLE_FTS3_PARENTHESIS`，未开时**恒 0 命中** |
| v3 | 普通表 + `search_text` 原文 LIKE（中文逐字） | 零依赖，但**逐字 AND、无序**，假阳性高 |
| v4 | bigram token 流 + 空格边界匹配 + BM25 近似 | 顺序敏感；但 **token 全 AND**，整句查询基本恒 0 命中 |
| v5 | 索引格式同 v4 + OR 召回 + 覆盖率门槛排序 | 整句可召回；仍是全表 LIKE（个人库量级够用） |
| **v6** | **v5 + 本地向量（写入侧算、查询侧 1 次）+ RRF 融合** | 关键词 + 语义双路；语义扩召回补上"同义/口语化召不回" |

v5 细节（2026-11-10，修"整句查询恒 0 命中"）：

- **入库 `buildSearchText` 未动**：中文连续段切相邻两字（`光合作用` → `光合 合作 作用`），英文/数字按词，
  四段文本（标题/分类/关键词/正文）拼接成空格分隔的 token 流。
  索引格式没变，所以**不用抬 `search_text_version`、不用重建索引**。
- **查询 `tokenizeQuery` 多了"分段"**：先按标点/空白/**虚词**把整句切成语义段，再在段内切 bigram。
  `光气泄漏怎么处理` → `光气 气泄 泄漏 处理`，不再产生 `漏怎`/`么处` 这种跨词、任何文档里都不存在的 token。
  双字以上虚词（怎么/什么/如何/可以/需要…）才当切分点；单字虚词（的/了/是/和/在…）只在单独成词时剔除
  （拿它们当切分点会把"在线/和平/有机"切坏）。
- **召回改 OR**：命中任意 token 即进候选。SQL 仍是 `(' ' || search_text || ' ') LIKE '% token %'` 的
  **整 token 边界**匹配，单字 token 退回包含匹配。
- **不再先截断再打分**：去掉了 v4 的 `ORDER BY updated_at DESC LIMIT max(limit*10,50)`（批量导入时间戳全同，
  实际取的是最早那批 50 条）。现在扫全部命中行（上限 `MAX_SCAN_ROWS = 5000`），
  第一遍只算分/覆盖率，第二遍只为入选的 limit 条回表取正文。
- **排序 `(覆盖率 → scoreRow → updated_at)`**：覆盖率 = 命中 token 数 / 总 token 数，
  低于 `MIN_COVERAGE = 0.34` 直接不返回（挡掉 OR 带进来的"只命中一个泛词"的行）；
  `scoreRow` 仍是 `1+log(tf)` + 字段权重（3/1）+ 正文长度归一。
- 结果多回一个 `coverage` 字段，排查"为什么这条排前面 / 为什么没命中"时直接看它。
- **成本实测**（`KnowledgeBaseScaleProbeTest`，4000 块 / 2.1 MB）：`1token=20ms / 3tokens=21ms / 6tokens=20ms`。
  OR 链会短路（第一个条件命中就不再评估后续），所以耗时跟 token 数几乎无关，
  实际是"命中行数 × 行宽 × 一次 LIKE"。**真正的硬上限不是速度而是 `MAX_SCAN_ROWS = 5000`**：
  单次查询命中超过 5000 行时，后面的候选不参与排序（会打 warn）。库总量不到 5000 时无影响。

> `semantic=true` 在 **v6 之前**只是对已截断的 top_k 做余弦重排（召不回关键词没命中的块）；
> v6 起若库内已有向量，`semantic=true` 走的是**两路召回 + RRF 融合**（见 4.1），是真扩召回。
> 还没回填向量时自动退回旧重排，行为与 v5 一致。

### 4.1 本地向量召回（v6，2026-11-10）

解决的是 v5 剩下的最大短板：**关键词召回不到，就永远出不来**（同义、改写、口语化问法）。

口径：

- **写入侧算向量**：`addChunk` / `addBatch` / `storeChunks` 成功后提交后台任务
  （单线程 `kb-embed-backfill`），每批 24 块补齐 `embedding IS NULL` 的块，最多 200 批（4800 块）。
  不在写入事务里算：几百块 × 一次在线调用会把导入从秒级拖成分钟级。
- **存 float32 BLOB**（小端）：1 万块 × 768 维 ≈ 30 MB。存 JSON 文本会贵一个数量级。
- **查询侧只算 1 次**：query 向量化一次，其余全是本地点积；
  候选是 `embedding IS NOT NULL` 的块（按 category 过滤、默认排除 tool_defs）。
- **余弦门槛 `SEMANTIC_MIN_COS = 0.30`**：低于它视为无关，不拿向量往结果里灌噪声。
- **融合用 RRF**：`score = Σ 1/(60 + rank)`。两路分数量纲不同（覆盖率 0~1、余弦 -1~1），
  按排名倒数相加不用调参；同一块两路都靠前会自然上浮，向量独有命中也能进榜。
- **失败一律降级，且失败就熔断（不给主 agent 制造故障）**：
  - 没配模型 / 没回填 / 调用失败 → 向量路返回空数组，关键词结果原样保留，**永不因为向量路失败而丢结果**；
  - 连续失败 2 次 → **静默停用 10 分钟**（`SEMANTIC_COOLDOWN_MS`），期间检索完全等同纯关键词，
    不报错、也不再为一次注定失败的调用白等超时；成功一次即自动恢复；
  - 单次 embedding 超时 **4s**（原 20s）：这条路径在工具检索的同步链上，等 20s 等于把"服务挂了"转嫁成"主 agent 卡 20 秒"；
  - 回填线程同样受熔断约束，熔断后不再打接口，"静默"到底。
- **换模型自动作废**：`kb_meta.embedding_model` 记录 `apiUrl|模型名`，一变就把 `embedding` 整列置 NULL 重建；
  维度对不上的旧向量在余弦里返回 -1，排到最后，不会被当"很像"。

测试用"概念字典"假向量（`KnowledgeBaseSemanticSearchTest`）：查询"有毒气体跑冒滴漏了咋整"
与库里的"光气泄漏应急处置"在 bigram 上零重叠，但概念同维 → 余弦 1，可稳定断言扩召回。

谁在用（默认开在哪一层）：

- **embedding 端点不绑定对话模型**（v6 起）：优先用当前对话模型那家（同一个 key 最省事）；
  它没声明 `embeddingModel` 就在已启用的端点里挑一个声明了的。
  所以"DeepSeek 聊天 + 百炼/硅基流动向量"能共存——不用为了向量换掉主力模型。
  注意 DeepSeek 官方**没有** `/embeddings`（providers.json 里已按事实标 `enabled:false`），
  只配 DeepSeek 时走本地向量模型（下了才启用）或纯关键词，不会报错、也不会白花调用。
- **工具 `search` 默认用**（`semantic` 不传即 true）：模型主动查知识时才多 1 次 embedding，性价比最高。
  没向量/没配模型自动退回关键词，不需要模型知道"有没有向量"。
- **每轮自动注入仍不走向量**（`OnlineAgentEngine.buildKnowledgeContext` 传 `semantic=false`）：
  它跑在每轮请求构建的同步路径上，加一次网络调用会直接抬高首字延迟。要开就在那条路径上另配开关+超时降级。
- `query` 改写（把口语问题先压成关键词组）与 int8 量化、ANN 索引都**没做**：当前量级不需要，做了反而是复杂度。

向量从哪来（v6.1，本地优先）：

- **本地 ONNX 模型优先**：`bge-base-zh-v1.5` int8（768 维，约 98MB）放
  `filesDir/ai_models/embedding/bge-base-zh-v1.5/`（`model.onnx` + `vocab.txt`），
  走 ONNX Runtime 1.18（包里本来就有——RapidOCR / SenseVoice 在用），
  分词是自己写的 BERT WordPiece（21128 词表，中文逐字，无 JNI）。不打包进 APK，安装包体积不变。
- **取用顺序**：注入实现（测试）> 本地（文件在才算）> 在线（providers.json 声明了 `embeddingModel`）。
  本地缺失就直接跳到在线，连加载都不尝试；在线也没有就纯关键词。
- **不自动联网**：`embed` 在"既没本地也没在线"时触发一次**后台**下载（hf-mirror 优先、HF 官方兜底），
  立即返回 `download=started`；下载与推理都在后台线程，不阻塞工具调用。
  **有在线端点时默认先用在线**（省流量）；要离线自足就传 `local=true`——不管在线可不可用，
  先把本地模型拉下来，下完再调一次 `embed`，`currentEmbedder()` 本地优先，会自动改用它重算全部向量。
- **`stats` 能看状态**：`embedding_source` = `local` / `online` / `downloading` / `none`；
  `local_embedding` = `missing` / `downloading` / `ready` / `failed`；`local_embedding_dir` 是落盘路径；
  `embedding_pending` = `total - embedded`，导入后后台回填还在跑时的"还差多少"，不是缺数据。
- **本地与在线的向量不通用**：`kb_meta.embedding_model` 记的是 `local|模型名` 或 `apiUrl|模型名`，
  换来源即整列作废重建——不同模型的向量空间不同，混着算余弦只会得出噪声。
- **不走 llama.cpp**：`LlamaHelper` 只有 `nEmbd` 元数据，没有 embedding 输出通道；
  真要本地 GGUF 嵌入得改 native 层，收益不如 ONNX 这条路。

### 4.2 文档层（v5，2026-11-10）

为什么加这一层：v4 只有 `kb_chunks` 一张平表，"一篇文档"只能靠 `source` 字符串约定，
于是三件事都别扭——去重要每次把全篇正文捞出来现算 MD5；检索说不出"哪篇第几块"；`export` 丢结构。

口径：

- **一篇文档 = `kb_documents` 一行**：`doc_id` 主键、`doc_hash`（全篇 MD5）、`chunk_count`、`source`。
- **块挂 `doc_id` + `chunk_seq`**（从 1 开始）；`doc_id=0` 表示手工 `add` 的单条知识（不属于任何文档）。
- **去重 O(1)**：`storeChunks` 先 `findDocument(source)` 读 `doc_hash` 比对，不再扫正文；
  文档层没回填/回填失败时回退到旧的"按 source 现算"路径（`inspectSource`）。
- **删除联动**：
  - `delete(source=…)` 按文档身份删整篇 + 删文档记录（`source` 兜底旧数据）；
  - `delete(id=…)` / `delete(title=…)` 删块后调 `refreshDocument(docId)` 重算哈希与块数（块数归 0 则删文档记录）；
  - `delete(category=…)` 连该分类的文档记录一起删；`clear` 清两表。
- **改块也重算**：`updateChunk` 改了正文/来源后 `refreshDocument`；改了 `source` 还会把块重新挂到新文档。
- **溯源**：`search`/`list`/`get` 结果都带 `doc_id` + `chunk_seq`，可以说"这是《手册》第 3 块"。
- **导出保结构**：`export` 额外写 `chunk_seq`；`import_json`/`import_file` 按 `source` 分组成篇写入
  （否则导回来只剩散块，文档身份丢失）。恢复备份走 `dedup=false`，语义是**覆盖**同 source 的旧块。
- **切块**（2026-11-10）：按 Markdown 标题分组，单块上限 800 字（超长按空行 → 句号硬切）；
  不足 150 字的非首块并进前一块（合并后超 1000 字则保持独立）——避免"标题下先一句结论、
  再挂三条证据"被切成两块，检索命中前者只拿到没有下文的半句话。

迁移：结构变更在 `onUpgrade`（`ALTER TABLE` 加两列 + `createTables` 建新表/索引）；
**数据回填**在开库后的 `ensureDocumentsV5()`——按 `source` 分组、**一次只装一篇**聚合，
写 `doc_id`/`chunk_seq` 后落 `kb_meta.documents_version=5`。
不在 `onUpgrade` 里做的原因同 v4（升级事务会吞逐行 UPDATE），也不一次性把全表读进内存（那是 OOM 写法）。

## 五、维护

### 5.1 清空并重建知识库（推荐流程）

工具定义（`category=tool_defs`）是**版本门控**入库的：`prefs.tool_defs_version` 已记录版本时，
`ensureToolDefsInKnowledgeBase()` 直接 return。**只清库不抬版本，工具定义不会回来**。

```text
1. knowledge_base(action=clear)                  # 全清；或删 databases/knowledge_base.db*（效果相同）
2. [仅工具定义内容变了才需要] 把 OnlineToolManager.TOOL_DEFS_VERSION 抬一版（如 20261026-v6 → v7），装机
3. 打开聊天触发 Agent 初始化 → 自动删旧 tool_defs 再全量重灌（版本标记对得上但库是空的也会重灌）
4. 核对：SELECT count(*) FROM kb_chunks; 应等于启用工具数；category 全为 tool_defs
```

注意：

- `clear` 是**全清**（2026-11-10 定稿）：自建内容和 `tool_defs` 一起删。曾经加过"默认保留 tool_defs"
  的保护，后来撤掉了——它的唯一正当性是"清掉回不来"，而现在入灌逻辑会校验
  "版本标记对得上 **且** 库里真有 tool_defs 行"（`countByCategory`），清空后下次 Agent 初始化自动重灌，
  不需要抬版本。既然能自愈，语义就该是最直白的"清空"，不必让用户/模型判断要不要多传 flag。
- 因此**清空后不用再抬 `TOOL_DEFS_VERSION`**；抬版本只在**工具定义内容本身变了**（工具增删、描述改动）时才需要。
- 清空动作**只影响知识库**，与"清空对话历史"是两个入口，别点错。
- 清空前确认没有想保留的自建条目（`category` 非 `tool_defs` 的都是手写/沉淀内容，
  删除后没有源文件就找不回来；要保底就先 `export` 备份）。
- 执行设备级清理前先确认"回收站"不是空的（真实事故：清库过程中误清空会话，回收站也空）。

### 5.2 索引格式迁移

- 表结构变更走 `SQLiteOpenHelper.onUpgrade`（`ALTER TABLE` 等，幂等 try/catch）。
- **检索格式**（`search_text` 口径）变更走开库后的 `ensureSearchTextV4()`：
  读 `kb_meta.search_text_version`，低于当前值就整表重算并在**独立事务**里写回标记。
  - 不要放在 `onUpgrade`：逐行 UPDATE 会被 SQLiteOpenHelper 的升级事务吞掉
    （实测版本抬了、`search_text` 一字未变）。
  - 标记存库内 `kb_meta`，不要存 SharedPreferences（两者生命周期不同，换机/清数据会错位）。
- **文档层回填**（v4→v5）同理走开库后的 `ensureDocumentsV5()`，标记 `kb_meta.documents_version`。
- 迁移失败只记日志：旧格式数据仍可按单字命中，不会把库弄坏。

### 5.3 没有独立 `reindex` 动作

需要重建索引时，用 5.1 的"清空 + 抬版本"，或临时改 `ensureSearchTextV4` 的格式版本号触发重算。
真要长期用，再加 `knowledge_base(action=reindex)`（讨论过，暂缓）。

### 5.4 导入去重、整篇替换、备份（2026-11-10，v5 改为走文档层）

- **去重**：`import_document` 入库前按 `source` 读 `kb_documents.doc_hash`（**O(1)**，不再现算全篇）：
  - 哈希一致 → `{"success":true,"added":0,"skipped":true}`，不再产生第二份；
  - 哈希不同 → `{"success":false,"conflict":true}`，**不静默覆盖**，提示先 `delete(source=…)`；
  - 文档层缺失（未回填/回填失败）→ 回退到旧的"按 source 现算 MD5"，行为不变。
- **整篇替换**：`knowledge_base(action=delete, source=<导入时的绝对路径>)` 再 `import_document`。
- **备份**：`knowledge_base(action=export[, file_path][, category])` 流式写 JSON，
  结构与 `import_json` 一致，`import_file` 可直接导回。不传 `file_path` 落到
  `getExternalFilesDir()/knowledge_export_<时间戳>.json`。
  - 导出带 `chunk_seq`，导回时按 `source` 重新聚成篇——**备份能保住"哪篇第几块"**。
  - `import_file`/`import_json` 走 `dedup=false`（覆盖同 source），不会把备份导成"冲突"。

### 5.5 数据库损坏自愈（v5）

`openWithRecovery()` 在拿连接时捕获 `SQLiteException`：

- 只认**明确的损坏信号**：`SQLiteDatabaseCorruptException`，或消息含
  `malformed` / `not a database` / `sqlite_corrupt`。普通 SQL 错误照旧抛出
  （把普通错误当损坏去重建库 = 用丢数据掩盖 bug）。
- 命中损坏时：关 helper → 把 `knowledge_base.db` / `-wal` / `-shm` **改名留档**
  为 `*.corrupted-<yyyyMMdd_HHmmss>`（**不删**）→ 建空库 → 再开一次；仍失败抛
  `IllegalStateException`，`lastError` 写明原文件位置。
- 坏库恢复后工具定义会自动重灌（`ensureToolDefsInKnowledgeBase` 校验"版本对得上且库里真有行"）；
  自建内容从最近的 `export` 备份 `import_file` 导回。

已知未覆盖：单测里把库文件写成垃圾字节后，Robolectric 的 SQLite 会把它当新库打开，
**没有真正走到"改名留档 → 重建"分支**（只证明了坏文件之后库仍可用）。
所以另加了一条反射用例直接钉 `isCorruption` 的判定边界（真损坏信号 4 种 vs 普通 SQL 错误 3 种）。
恢复分支本身靠代码审查 + 真机验证（`knowledge_base.db.corrupted-*` 文件是否真出现）。

## 六、何时该换 FTS5（自带 SQLite）

> 现状：v5 用系统 SQLite + bigram + LIKE，**故意不引入**自带 SQLite。
> 这不是能力不够，是代价/收益不划算。判据如下，避免以后凭感觉反复讨论。

### 6.1 满足任意两条才值得换

0. **先看实测**（2026-11-10，`KnowledgeBaseScaleProbeTest`，4000 块 / 库 2.1 MB）：

   ```
   insert=1270ms | 1token=20ms | 3tokens=21ms | 6tokens=20ms
   ```

   OR 链短路，token 数几乎不影响耗时；4000 块 20 ms（Robolectric 口径，真机约 50–150 ms）。
   按线性外推 1 万块 ≈ 50 ms 级、3 万块 ≈ 150 ms 级。**"给这个量级换引擎"是负收益。**
   要判断自己的库到什么量级，改 `KnowledgeBaseScaleProbeTest.ROWS` 跑一次即可。

1. 知识库稳定超过 **1 万条**，或单条正文很长，检索开始有可感知延迟
   （判据用实测，不用感觉：改探针的 `ROWS`，或看 `SLOW_SEARCH_MS` 的 warn 是否频繁出现）；
2. 抱怨的是**排序**（"最相关的没排前面"）而不是"搜不到/搜错"。
   ⚠️ v4 曾把"整句提问恒 0 命中"归为排序问题，其实是**查询语义**问题（token 全 AND + 跨词 bigram），
   已于 v5 修掉。换 FTS5 也不解决它：`MATCH 'a b'` 默认同样是隐式 AND，要 OR 必须显式写 `OR`。
3. 需要 FTS 独有能力：短语查询 `"光合作用"`、列权重、前缀查询、`MATCH` 语法；
4. 能接受 **APK +3 MB**（native 库）与"两套 SQLite 并存"的维护成本。

只满足 1 条、或只是想"用上更正统的组件"，不建议动。

### 6.2 真要换，走 A 不走 B

| 方案 | 做法 | 代价 |
|---|---|---|
| **A（推荐）** | `KnowledgeBaseManager` 把"开库"抽一层：生产用 `org.sqlite.database.sqlite.*`，单测用系统 `android.database.*`（走 LIKE 兜底） | ~80 行抽象，单测保持秒级 |
| B | 知识库单测全迁 `androidTest`（真机跑） | 每次回归连设备、分钟级 |

### 6.3 已实测的事实（2026-10-28，25113PN0EC）

用的是 SQLite 官方 Android Bindings 的 Maven 再发布 `mil.nga:sqlite-android:3450200`：

```
sqlite_version = 3.45.2
compile_option: ENABLE_FTS5
bigram MATCH hits = [光合作用(bm25)]     ← 只命中该命中的
trigram 3+ chars = 1 / 2 chars = 0      ← trigram 对 2 字中文词无效
```

- 结论：**中文继续用 bigram 词流 + `unicode61`，不要用 `trigram`**（中文高频查询是 2 字）。
- 坑：这版 bindings 移除了 `SQLiteDatabase.loadLibs(Context)`，用前必须 `System.loadLibrary("sqliteX")`，
  否则 `UnsatisfiedLinkError: nativeOpen`。
- 探针保留在 `src/androidTest/java/com/oilquiz/app/ai/knowledge/BundledSqliteFts5DeviceTest.java`，
  当"以后要不要上"的 canary：真要切换前先跑它确认库与 FTS5 仍在。

## 七、文件清单

| 文件 | 说明 |
|---|---|
| `src/main/java/com/oilquiz/app/ai/knowledge/KnowledgeBaseManager.java` | 存储 + 检索（bigram/打分/迁移）+ 文档导入 + `lastError` |
| `src/main/java/com/oilquiz/app/ai/knowledge/KnowledgeBaseTool.java` | 工具入口：动作分发、参数校验、错误语义 |
| `src/main/java/com/oilquiz/app/ai/tool/AIToolManager.java` | 注册 + 在线 function calling 的显式 ToolDefinition |
| `src/main/java/com/oilquiz/app/ai/agent/online/OnlineToolManager.java` | `ensureToolDefsInKnowledgeBase()`（版本门控）与 `TOOL_DEFS_VERSION` |
| `src/main/java/com/oilquiz/app/ai/agent/online/OnlinePromptBuilder.java` | 【知识库】使用指引（list→get、tag 别名、update 不删重加） |
| `src/test/java/com/oilquiz/app/ai/knowledge/*` | 单测：更新/索引精确取/tag/老库迁移/文档层与资产安全/坏库自愈/向量召回（30+1+3+8+7+1=50 条） |
| `src/test/java/com/oilquiz/app/ai/knowledge/KnowledgeBaseSemanticSearchTest.java` | v6 向量召回测试：概念字典假向量，不联网；覆盖回填、扩召回、失败降级、换维度、分类过滤、去重 |
| `src/test/java/com/oilquiz/app/ai/knowledge/KnowledgeBaseScaleProbeTest.java` | 规模探针：插入 N 块并打印检索耗时，判断"要不要换检索引擎"用它取数（改 `ROWS` 即可） |
| `src/main/java/com/oilquiz/app/ai/embedding/BertWordPieceTokenizer.java` | 纯 Java BERT WordPiece 分词（本地向量的前置件，不引 NLP 库、不走 JNI） |
| `src/main/java/com/oilquiz/app/ai/embedding/LocalEmbeddingModel.java` | 本地 ONNX 向量模型：加载 / 推理 / 后台下载 / 失败封存（bge-base-zh-v1.5 int8，768 维） |
| `src/test/java/com/oilquiz/app/ai/embedding/*` | 单测：分词确定性 + 模型缺失/损坏时静默不启用（7+4=11 条） |
| `src/androidTest/java/com/oilquiz/app/ai/knowledge/BundledSqliteFts5DeviceTest.java` | 真机探针：自带 SQLite 的 FTS5/bm25/trigram 是否可用（切换前的 canary） |
