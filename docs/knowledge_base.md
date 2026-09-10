# 答题宝知识库（Knowledge Base）使用文档

> 版本：v1.0　模块：`com.oilquiz.app.ai.knowledge`　引入时间：2026-09

## 一、功能概述

答题宝内置了**应用级知识库**，供本地 Agent 与在线 Agent 调用。知识库内容**完全由用户维护**（不内置任何种子内容），适合存放应用专属知识、学习资料、笔记、FAQ、操作说明等，让 AI 回答更准确。

**核心能力：**
- 全文检索（中英文混检，bm25 相关性排序）
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
│   └── kb_chunks_fts  FTS5 全文索引（bm25 排序）          │
└──────────────────────────────────────────────────────┘
```

- **独立数据库**：知识库存放在独立的 `knowledge_base.db`，不修改主数据库 `smartquiz_database`，零迁移风险。
- **注册方式**：`AIToolManager.registerToolFactories()` 中注册为 `knowledge_base`（懒加载、自动卸载，与其余 50+ 工具一致）。
- **在线 Agent 可见**：`@Tool` 注解由 `ToolSchemaExtractor` 自动提取，自动同步到 `OnlineToolRegistry`，在线 Agent 可直接以 function calling 调用。
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

## 五、中文检索说明

SQLite FTS5 的 `unicode61` 分词器会把连续中文串（含相邻英文数字）合并成一个长 token，导致中文关键词无法命中。本实现通过**入库时对中文逐字空格化**（存于 FTS `search_text` 列）解决：每个汉字成为独立 token；检索时把中文连续段拆字并用 AND 组合，实现"包含全部关键字"的中文检索；英文/数字按词 + 前缀匹配。经本地 SQLite 3.53 实测：`原子`、`化学键`、`AI 工具`、`qweather` 等中英混合查询均可正确命中。

检索串由白名单 token 生成并参数化绑定，FTS5 特殊字符（`" : ( ) *` 等）一律剥离，无注入面。

## 六、验证方式

- **编译**：`gradlew compileDebugJavaWithJavac` 通过（项目既有 8 个警告与本次改动无关）。
- **SQL 链路**：本地 SQLite 实测建表、插入、中文/英文检索、分类过滤、注入防护、删除、统计、FTS 与主表 JOIN 一致性，全部通过。

## 七、文件清单

| 文件 | 说明 |
|------|------|
| `src/main/java/com/oilquiz/app/ai/knowledge/KnowledgeBaseManager.java` | 知识库引擎：独立 SQLite+FTS5 存储与检索、增删查统计、文档直接导入（`importDocument`）、安全查询构造 |
| `src/main/java/com/oilquiz/app/ai/knowledge/KnowledgeBaseTool.java` | Agent 工具入口：`@Tool` 注解 + 9 个动作分发（含直接导入文档 `import_document`） |
| `tools/knowledge_preprocess.py` | 预处理脚本：Markdown/TXT → 知识 JSON（仅标准库） |
| `src/main/java/com/oilquiz/app/ai/tool/AIToolManager.java` | 已注册 `knowledge_base`（1 行 + 1 import） |
