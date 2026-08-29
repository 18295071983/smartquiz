# 题库文件导入功能设计

> 版本: 1.0 | 更新日期: 2026-08-29 | 分支: feature/agent-local
> 本文件描述题库文件导入功能：文件识别、格式解析（规则/AI 双路径）、字段映射、结构化入库。全部依据真实源码（`com.oilquiz.app.ai.importing`，23 类）。

## 一、功能概览

导入功能支持从 **Excel/CSV/文本(纯文本/SQL)** 文件解析题目，经「格式识别 → 内容解析 → 字段映射 → 校验 → 入库」流程，将题目写入 `question` 表。

导入入口：
- UI 入口：`activity_ai_import.xml` / `activity_import_new.xml` / `activity_import.xml`
- 编排：`AIImportOrchestrator`
- 路径：**规则解析**（正则/状态机）与 **AI 解析**（LLM 抽取）双路径

## 二、模块结构

```
com.oilquiz.app.ai.importing
├── AIImportOrchestrator      导入编排（入口/调度/进度）
├── AIImportAgent             导入解析 Agent（规则 + AI，双模式）
├── FileProfiler              文件识别（类型/编码/结构）
├── QuestionFormatDetector    格式检测（选择题/文本/SQL 等）
├── RuleBasedQuestionParser   规则解析器（正则+状态机）
├── QuestionPreprocessor      题目预处理
├── QuestionSchemaDictionary  字段 schema 字典（题干/选项/答案等）
├── AIFieldMapper             AI 字段映射（header→canonical）
├── FieldMappingRegistry      字段映射注册表
├── ImportValidator           结果校验
├── ImportMappingValidator    映射校验
├── ExcelSheetPicker          工作表选择
├── SqlFileImporter           SQL 导入
├── model.AIImportResult      导入结果模型
└── v2/                       CSV 导入引擎
    ├── ImportMain / ImportCsvIngestor / ImportDirs / ImportToolManager
    ├── ImportLlmEngine / ImportMapCache / ImportBreakpointStore
    └── ImportOutputSanitizer / ImportPythonBridge
```

## 三、导入流程

```
选择文件（Excel/CSV/文本/SQL）
    ↓
FileProfiler 识别文件类型/编码/结构
    ↓
QuestionFormatDetector 检测格式
    ↓  判断 canUseRuleParser
├─ 规则解析（canUseRuleParser=true）
│   └─ RuleBasedQuestionParser（正则+状态机）→ List<Question>
└─ AI 解析（canUseRuleParser=false 或规则失败）
    └─ AIImportAgent.extractFromChunk(chunk, ...)（LLM 抽取）→ List<Question>
    ↓
AIFieldMapper 字段映射（header→canonical，AI 智能映射）
    ↓
ImportValidator / ImportMappingValidator 校验
    ↓
结构化 Question 入库（question 表）
    ↓
导入结果（AIImportResult：成功/失败/统计）
```

## 四、格式识别（QuestionFormatDetector）

**类**: `QuestionFormatDetector`

```java
public enum DetectedFormat { ... }   // 检测到的格式
public static DetectionResult detect(String text);
public static DetectionResult detect(String text, String fileName);
public static boolean canUseRuleParser(DetectionResult result);
```

- 对样本打分（`scoreNumberedChoice` 等），判断能否用规则解析（确定性、更快）。
- 不能可靠规则解析时走 AI 解析。

## 五、规则解析（RuleBasedQuestionParser）

**类**: `RuleBasedQuestionParser`

用**正则 + 状态机**从文本提取结构化题目。

### 5.1 支持格式

| 格式 | 正则模式 | 说明 |
|------|---------|------|
| 编号选择题 | `QUESTION_NUM` | `1. 题干 A. B. C. D.` |
| 选项行 | `OPTION_LINE` / `OPTION_PAREN` | `A. xx` 或 `(A) xx` |
| 答案行 | `ANSWER_LINE` | `答案：A` |
| 解析行 | `EXPLANATION_LINE` | `解析：...` |
| Aiken 格式 | `AIKEN_ANSWER` | `ANSWER: A`（Aiken 标准） |

### 5.2 状态机

`ParseState` 枚举驱动逐行解析，识别题目边界、选项、答案、解析。

## 六、AI 解析（AIImportAgent）

**类**: `AIImportAgent`

当规则解析不可用时，用 LLM 抽取题目。

| 方法 | 说明 |
|------|------|
| `extractFromChunk(chunk, chunkIndex, totalChunks, ...)` | 从文本块抽取题目 |
| `setModelMode(mode)` | 设置模型模式 |
| `getRuntimeChunkBudget()` | 运行时块预算 |
| `cancel()` | 取消 |

支持流式 token 输出（`onTokenStream`/`onThinking`）与分块完成回调（`onChunkComplete`）。

### 6.1 AgentState

`AIImportAgent.AgentState` 枚举跟踪解析状态。

## 七、字段映射（AIFieldMapper）

**类**: `AIFieldMapper`

将文件列头映射到题库标准字段（canonical），支持 AI 智能映射。

```java
void buildSmartMappingAsync(List<String> headers, MappingCallback callback);
static void cacheMapping(String header, String canonical);  // 映射缓存
```

### 7.1 字段字典（QuestionSchemaDictionary）

标准字段：
- `questionText` — 题干原文
- `optionA/B/C/D` — 选项
- `correctAnswer` — 正确答案
- `questionType` — 题型（单选/多选/判断/填空/简答）
- 等

### 7.2 映射注册表

`FieldMappingRegistry` — header 别名 → 标准字段的映射注册。

## 八、CSV 导入引擎（v2）

**类**: `v2.ImportCsvIngestor` / `ImportMain`

适用于大规模 CSV 导入：

| 类 | 职责 |
|-----|------|
| `ImportCsvIngestor` | CSV 逐行摄入 `ingest(chunkFiles, startOffset, ...)` |
| `ImportMain` | 导入主流程 |
| `ImportDirs` | 导入目录管理 |
| `ImportToolManager` | 导入工具管理 |
| `ImportMapCache` | 映射缓存 |
| `ImportBreakpointStore` | 断点续传 |
| `ImportLlmEngine` | LLM 引擎（CSV 语义识别） |
| `ImportOutputSanitizer` | 输出清洗 |
| `ImportPythonBridge` | Python 桥接（chardet 等） |

CSV 行解析：`parseCsvLine(line)`（RFC4180）、`readLogicalLine(reader)`（逻辑行）。

## 九、校验与结果

- `ImportValidator` — 校验结构化题目是否合法。
- `ImportMappingValidator` — 校验字段映射是否完整。
- `AIImportResult` — 导入结果（成功数/失败数/统计）。

## 十、支持的文件类型

| 类型 | 处理类 |
|------|--------|
| Excel (.xlsx) | `ExcelSheetPicker`（工作表选择）+ 规则/AI 解析 |
| CSV (.csv) | `v2.ImportCsvIngestor` |
| 纯文本 (.txt) | `QuestionFormatDetector` + 规则/AI 解析 |
| SQL (.sql) | `SqlFileImporter` |

## 十一、相关 Repository/DAO

导入题目通过 `QuestionRepository` + `QuestionDao.insertAll(...)` 批量写入 `question` 表（详见 [数据库设计](10-database-design.md)）。

## 相关文档

- [数据库设计](10-database-design.md)
- [数据层设计](06-data-layer.md)
- [模块清单](08-module-inventory.md)
