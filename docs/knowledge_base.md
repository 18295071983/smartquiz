# 答题宝知识库（Knowledge Base）使用文档

> 版本：v1.0　模块：`com.oilquiz.app.ai.knowledge`　引入时间：2026-09

## 一、功能概述

答题宝内置了**应用级知识库**，供本地 Agent 与在线 Agent 调用。知识库内容**完全由用户维护**（不内置任何种子内容），适合存放应用专属知识、学习资料、笔记、FAQ、操作说明等，让 AI 回答更准确。

**核心能力：**
- 全文检索（中英文混检，标题/关键词命中加权 + 更新时间兜底的相关度排序）
- 运行时增删（单条 / 批量 / JSON 导入）
- 分类管理、统计查询
- 完全离线，不依赖任何外部 API

## 二、架构

```
┌──────────────────────────────────────────────────────┐
│                  本地 Agent / 在线 Agent                │
└─────────────────────────┬────────────────────────────┘
                          │ 工具调用
┌─────────────────────────▼────────────────────────────┐
│          KnowledgeBaseTool（AITool，@Tool 注解）        │
│        search / add / add_batch / import_json /       │
│        import_file / delete / clear / stats           │
└─────────────────────────┬────────────────────────────┘
┌─────────────────────────▼────────────────────────────┐
│        KnowledgeBaseManager（单例，线程安全）            │
│   独立数据库 knowledge_base.db（不触碰主数据库）          │
│   ├── kb_chunks      知识块主表（元数据 + 原文）          │
│   └── search_text    归一化检索列（包含式检索 + 打分）    │
└──────────────────────────────────────────────────────┘
```

- **独立数据库**：知识库存放在独立的 `knowledge_base.db`，不修改主数据库 `smartquiz_database`，零迁移风险。
- **注册方式**：`AIToolManager.registerToolFactories()` 中注册为 `knowledge_base`（懒加载、自动卸载，与其余 50+ 工具一致）。
- **在线 Agent 可见**：`AIToolManager.getToolDefinition("knowledge_base")` 提供**显式** function calling schema（正确的 `action` 枚举、`top_k`=integer、`semantic`=boolean、`items`=array），`OnlineToolRegistry` 据此生成 tools JSON；同时 `knowledge_base` 已加入在线 Agent 的**核心工具集**（`OnlineAgentEngine` coreTools），并按用户消息关键词/意图追加，保证每轮请求都能看到并调用它。
- **预留扩展**：`keywords` 字段与表结构已为将来向量语义检索（RAG）预留位置（新增 embedding 列即可，无需重构）。

## 三、Agent 调用方式

Agent 通过 `knowledge_base` 工具，参数 `action` 区分操作：

| action | 必填参数 | 说明 |
|--------|----------|------|
| `search` | `query` | 全文检索，支持 `category` 分类过滤、`top_k` 条数（默认 5，最大 20） |
| `add` | `title`、`content` | 添加单条知识，可选 `category`/`keywords`/`source` |
| `add_batch` | `items` | 批量添加，items 为对象数组 |
| `import_json` | `json` | 导入知识 JSON（支持 `{"chunks":[...]}` 或裸数组） |
| `import_file` | `file_path` | 从手机存储导入 JSON 文件（UTF-8，最大 5MB） |
| `import_document` | `file_path` | **直接导入文件**：Word(doc/docx)/Excel(xls/xlsx)/TXT/MD/CSV/PDF/HTML/图片（截图拍照走 OCR）/音频（mp3/wav/m4a/aac/amr/ogg/flac 等走语音识别 ASR 转写）；自动解析、按标题切块入库；可选 `category` 分类、`title` 指定标题（图片/音频建议传入）；单文件最大 10MB |
| `delete` | `id` 或 `title` | 按 id 或标题（精确）删除 |
| `clear` | - | 清空全部 |
| `stats` | - | 统计：总量、分类分布、最近更新时间 |

## 四、添加知识（用户侧操作）

### 方式一：直接让 Agent 导入文件（推荐，无需转换）

把 Word / Excel / TXT / MD / CSV / PDF / **图片（截图、拍照）** / **音频（录音、语音笔记）** 文件放到手机（下载目录等），直接对答题宝 AI 说：

> "把 /storage/emulated/0/Download/我的笔记.docx 导入知识库"

Agent 会调用 `import_document` 动作：自动解析 → 按标题切块 → 入库，返回导入条数。也可以指定分类和标题：

> "把 /storage/emulated/0/Download/化学讲义.xlsx 导入知识库，分类是 化学"

> "把 /storage/emulated/0/Download/IMG_0001.jpg 导入知识库，标题是 化学反应方程式截图，分类是 化学"

> "把 /storage/emulated/0/Download/课堂录音.mp3 导入知识库，标题是 物理课第二讲录音，分类是 物理"

- **图片**（.png/.jpg/.jpeg/.gif/.bmp/.webp）走 **OCR 识别**（在线视觉模型 → 本地 PP-OCRv6 → ML Kit 兜底），识别出的文字自动入库。
- **音频**（.mp3/.wav/.m4a/.aac/.amr/.ogg/.flac/.wma/.opus/.3gp）**直接调用项目内语音识别模型**（在线 qwen3-asr/whisper，无网/离线时用本地 SenseVoice）转写为文字后入库，不经过任何多余的音频提取/转码步骤；识别超时 120s。
- 图片/音频文件名通常无意义，建议通过 `title` 指定语义化标题。

### 方式二：对话中让 Agent 添加单条知识

直接对答题宝 AI 说：
> "把这条知识加入知识库：标题是 XX，内容是 XX，分类是 XX"

Agent 会调用 `knowledge_base` 的 `add` 动作完成。

### 方式三：批量预处理导入（可选，适用于需要精细控制关键词的场景）

1. 把资料（Markdown / TXT）整理好，在项目根目录运行预处理脚本：

```bash
# 处理单个文件
python tools/knowledge_preprocess.py --input 我的笔记/化学.md --output knowledge_import.json --category 化学

# 递归处理整个目录
python tools/knowledge_preprocess.py --input 我的资料/ --output knowledge_import.json --category 资料

# 多来源合并
python tools/knowledge_preprocess.py --input docs/a.md --input notes/ --output kb.json
```

2. 脚本按 Markdown 标题层级自动切块（标题路径作为块标题），超长块按空行拆分（`--chunk-size` 可调，默认 800 字符），自动提取中文/英文关键词。

3. 把生成的 JSON 传到手机（下载目录等），在对话中让 Agent 调用：
> "把文件 /storage/emulated/0/Download/knowledge_import.json 导入知识库"

Agent 会调用 `import_file` 动作完成导入，返回成功条数。

### 方式四：直接构造 JSON

每条知识结构：

```json
{
  "chunks": [
    {
      "title": "知识标题（建议含分类前缀，如 化学 - 原子结构）",
      "category": "general",
      "keywords": "化学,原子,结构",
      "content": "正文内容……",
      "source": "来源标识（可选，如文件名）"
    }
  ]
}
```

## 五、检索实现与中文说明（v3：不依赖 FTS）

**为什么不用 FTS**（历史踩坑，均有真机证据）：

| 版本 | 方案 | 结果 |
|------|------|------|
| v1 | FTS5 + `unicode61 remove_diacritics 2` + `bm25()` | 部分设备内置 SQLite 未编译 FTS5 → `no such module: fts5`，建表失败，整个知识库不可用 |
| v2 | 改 FTS4 + 中文逐字空格化 + `snippet()` | 建表能过，但 **FTS3/4 的布尔查询语法随编译开关变化**：未启用 `SQLITE_ENABLE_FTS3_PARENTHESIS` 时，`MATCH '"原" AND "子"'` 里的 `AND` 被当成普通词，检索**恒返回空**（本地 SQLite 实测复现）；FTS4 无 `bm25()`，也拿不到相关性排序 |
| **v3** | **普通表 + 归一化 `search_text` 列做包含式检索** | 零 SQLite 扩展依赖，任何机型/编译配置都可用 |

当前实现：
- 入库时把「标题 + 分类 + 关键词 + 正文」小写归一后写入 `search_text`（`buildSearchText`），标题/关键词不额外拆词。
- 查询分词（`tokenizeQuery`）：英文/数字按词、**中文连续段逐字拆分**，去重并限制 12 个 token，实现"包含全部关键字"的中文检索。
- 查询 SQL 由 token 生成 `search_text LIKE ?` 的 AND 组合，**全部参数化绑定**（token 来自正则白名单，`%`/`_` 不可能出现），无注入面；分类过滤、条数上限同样绑定。
- 相关度打分（`scoreOf`）：命中标题/分类/关键词 +3，仅命中正文 +1；同分按 `updated_at` 降序。
- `snippet` 由 Java 生成（命中位置 ±窗口 + `[命中词]` 标记），不再依赖 FTS 的 `snippet()`。
- 召回规模按个人知识库量级设计（单次最多扫描 `MAX_CANDIDATES=300` 条候选），全表 LIKE 的开销可忽略。

## 六、验证方式

- **编译**：`gradlew compileDebugJavaWithJavac` 通过。
- **SQL 链路**：用与真机同款 SQL 语义在本地 SQLite 验证：建表（含 v3 迁移补列 + 回填）、插入、中文逐字检索、英文子串检索、分类过滤、参数化防注入、删除、统计、相关度排序。

## 七、文件清单

| 文件 | 说明 |
|------|------|
| `src/main/java/com/oilquiz/app/ai/knowledge/KnowledgeBaseManager.java` | 知识库引擎：独立 SQLite 存储与检索（v3 无 FTS 依赖）、相关度打分、增删查统计、文档直接导入（`importDocument`）、`lastError` 错误上报 |
| `src/main/java/com/oilquiz/app/ai/knowledge/KnowledgeBaseTool.java` | Agent 工具入口：`@Tool` 注解 + 9 个动作分发（含直接导入文档 `import_document`）；检索失败与"没找到"分别如实上报 |
| `src/main/java/com/oilquiz/app/ai/tool/AIToolManager.java` | 注册 `knowledge_base` + **显式 ToolDefinition**（action 枚举/参数真实类型，供在线 function calling） |
| `src/main/java/com/oilquiz/app/ai/agent/online/OnlineAgentEngine.java` | `knowledge_base` 加入在线 Agent 核心工具集 |
| `src/main/java/com/oilquiz/app/ai/agent/online/OnlineToolManager.java` | 关键词/意图注入 `knowledge_base`；文件导入超时放宽到 180s |
| `src/main/java/com/oilquiz/app/ai/agent/online/OnlinePromptBuilder.java` | 【知识库】使用指引（先检索、如何入库、空结果/报错的正确表达） |
| `tools/knowledge_preprocess.py` | 预处理脚本：Markdown/TXT → 知识 JSON（仅标准库） |
