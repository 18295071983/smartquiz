# 答题宝知识库（Knowledge Base）

> 版本：v4（2026-10-28）　模块：`com.oilquiz.app.ai.knowledge`
> 历史设计（v1–v3 的取舍与踩坑日志）见 [`_archive/projects/docs/knowledge_base.md`](../_archive/projects/docs/knowledge_base.md)，
> 本文是**现行**口径，包含 v4 的检索升级与维护流程。

## 一、能力

- 全文检索：中文 bigram、英文按词，标题/分类/关键词加权 + BM25 近似打分
- 渐进披露：`list` 看索引（不含正文）→ `get` 取单条 → `search` 模糊找
- 精确入口：`search(id=…)` / `search(exact_title=…)` / `search(tag=…)`（别名、同义词、标签）
- 维护：`add` / `add_batch` / `update` / `delete` / `clear` / `stats`
- 导入：`import_json` / `import_file` / `import_document`（Word/Excel/TXT/MD/CSV/PDF/HTML/图片 OCR/音频 ASR）
- 完全离线，不依赖任何外部 API（`semantic=true` 才消费在线 embedding/rerank）

## 二、架构

```
Agent（本地/在线）
   └─ KnowledgeBaseTool（@Tool 注解 + 动作分发）
        └─ KnowledgeBaseManager（单例、线程安全）
             └─ knowledge_base.db（独立库，不碰主库）
                  ├─ kb_chunks  正文 + search_text（bigram token 流）
                  └─ kb_meta    格式版本标记（search_text_version）
```

- 独立数据库：`databases/knowledge_base.db`，与 `smartquiz_database` 零耦合。
- 表结构：`kb_chunks(id,title,category,keywords,content,source,search_text,created_at,updated_at)`。
- 版本：`DB_VERSION = 4`；`user_version` 管表结构，`kb_meta.search_text_version` 管**检索格式**。

## 三、动作表（工具层）

| action | 必填 | 说明 |
|---|---|---|
| `list` | — | 轻量索引（id/标题/分类/关键词/来源/更新时间，**不含正文**），默认 20、上限 100；支持 `category`、`tag` |
| `get` | `id` | 取单条全文；缺 id 直接报错，不会退化成返回全部 |
| `search` | `query` 或 `id`/`exact_title` | 模糊检索；传 `id`/`exact_title` 走精确取；`tag` 走 keywords 整词过滤；`category`、`top_k`、`semantic` |
| `add` | `title`、`content` | 单条；`keywords` 建议放别名/同义词/英文名（逗号分隔） |
| `add_batch` | `items` | 批量，元素字段同上 |
| `update` | `id` 或 `find_title` | 只改传入字段，id/created_at 不变；`find_title` 必须唯一命中 |
| `import_json` / `import_file` | `json` / `file_path` | 批量导入 JSON（`{"chunks":[…]}` 或裸数组） |
| `import_document` | `file_path` | 直接解析文档/图片/音频并切块入库 |
| `delete` | `id` 或 `title` | 删除 |
| `clear` | — | 清空全部 |
| `stats` | — | 总量、分类分布、最近更新时间 |

## 四、检索实现（v4）

| 版本 | 方案 | 结果 |
|---|---|---|
| v1 | FTS5 + unicode61 + bm25 | 部分设备没编译 FTS5 → `no such module: fts5`，不可用 |
| v2 | FTS4 + 中文逐字空格化 + snippet | `AND` 依赖 `SQLITE_ENABLE_FTS3_PARENTHESIS`，未开时**恒 0 命中** |
| v3 | 普通表 + `search_text` 原文 LIKE（中文逐字） | 零依赖，但**逐字 AND、无序**，假阳性高 |
| **v4** | **bigram token 流 + 空格边界匹配 + BM25 近似** | 顺序敏感；仍是全表 LIKE（个人库量级够用） |

v4 细节：

- 入库 `buildSearchText`：中文连续段切相邻两字（`光合作用` → `光合 合作 作用`），英文/数字按词，
  四段文本（标题/分类/关键词/正文）拼接成空格分隔的 token 流。
- 查询 `tokenizeQuery` 同口径；SQL 用 `(' ' || search_text || ' ') LIKE '% token %'` 做**整 token 边界**匹配，
  单字 token 退回包含匹配。
- 打分 `scoreOf`：`1+log(tf)` 压长尾 + 字段权重（标题/分类/关键词 3、正文 1）+ 正文长度归一。

## 五、维护

### 5.1 清空并重建知识库（推荐流程）

工具定义（`category=tool_defs`）是**版本门控**入库的：`prefs.tool_defs_version` 已记录版本时，
`ensureToolDefsInKnowledgeBase()` 直接 return。**只清库不抬版本，工具定义不会回来**。

```text
1. knowledge_base(action=clear)                 # 或删 databases/knowledge_base.db*（效果相同）
2. 把 OnlineToolManager.TOOL_DEFS_VERSION 抬一版（如 20261026-v6 → v7），装机
3. 打开聊天触发 Agent 初始化 → 自动先删旧 tool_defs 再全量重灌
4. 核对：SELECT count(*) FROM kb_chunks; 应等于启用工具数；category 全为 tool_defs
```

注意：

- 清空动作**只影响知识库**，与"清空对话历史"是两个入口，别点错。
- 清空前确认没有想保留的自建条目（`category` 非 `tool_defs` 的都是手写/沉淀内容，
  删除后没有源文件就找不回来）。
- 执行设备级清理前先确认"回收站"不是空的（真实事故：清库过程中误清空会话，回收站也空）。

### 5.2 索引格式迁移

- 表结构变更走 `SQLiteOpenHelper.onUpgrade`（`ALTER TABLE` 等，幂等 try/catch）。
- **检索格式**（`search_text` 口径）变更走开库后的 `ensureSearchTextV4()`：
  读 `kb_meta.search_text_version`，低于当前值就整表重算并在**独立事务**里写回标记。
  - 不要放在 `onUpgrade`：逐行 UPDATE 会被 SQLiteOpenHelper 的升级事务吞掉
    （实测版本抬了、`search_text` 一字未变）。
  - 标记存库内 `kb_meta`，不要存 SharedPreferences（两者生命周期不同，换机/清数据会错位）。
- 迁移失败只记日志：旧格式数据仍可按单字命中，不会把库弄坏。

### 5.3 没有独立 `reindex` 动作

需要重建索引时，用 5.1 的"清空 + 抬版本"，或临时改 `ensureSearchTextV4` 的格式版本号触发重算。
真要长期用，再加 `knowledge_base(action=reindex)`（讨论过，暂缓）。

## 六、文件清单

| 文件 | 说明 |
|---|---|
| `src/main/java/com/oilquiz/app/ai/knowledge/KnowledgeBaseManager.java` | 存储 + 检索（bigram/打分/迁移）+ 文档导入 + `lastError` |
| `src/main/java/com/oilquiz/app/ai/knowledge/KnowledgeBaseTool.java` | 工具入口：动作分发、参数校验、错误语义 |
| `src/main/java/com/oilquiz/app/ai/tool/AIToolManager.java` | 注册 + 在线 function calling 的显式 ToolDefinition |
| `src/main/java/com/oilquiz/app/ai/agent/online/OnlineToolManager.java` | `ensureToolDefsInKnowledgeBase()`（版本门控）与 `TOOL_DEFS_VERSION` |
| `src/main/java/com/oilquiz/app/ai/agent/online/OnlinePromptBuilder.java` | 【知识库】使用指引（list→get、tag 别名、update 不删重加） |
| `src/test/java/com/oilquiz/app/ai/knowledge/*` | 单测：更新/索引精确取/tag/v3→v4 迁移 |
