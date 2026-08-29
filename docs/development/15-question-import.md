# 题库文件导入功能设计

> 版本: 3.0 | 更新日期: 2026-08-29 | 分支: feature/agent-local
> 本文件描述题库文件导入功能：文件识别、格式解析、字段映射、校验、结构化入库。全部依据真实源码（`com.oilquiz.app.ai.importing`，当前 15 类）。
>
> **v2.0 变更**：v1 导入主流程无调用方，整体删除（8 类），`AIImportOrchestrator` 瘦身为模型配置壳（1994→96 行），导入统一由 v2 引擎承担。
> **v3.0 变更**：完成评审剩余问题修复——题型取值全系统统一收口（5 种带"题"字）、SQL 导入截断告警、断点源文件变更校验、映射缓存 key 统一、Excel 子表头定位修正。

## 一、功能概览

导入功能支持从 **Excel / CSV / 文本 / SQL(SQLite)** 文件解析题目，经「文件采样 → 字段映射 → 全量解析 → 质量预览 → 缺失填充 → 事务入库」流程，将题目写入 `question` 表。

导入入口：
- UI 主入口：`AIImportActivity`（`activity_ai_import.xml`）— 全部走 v2 引擎
- 老入口：`ImportActivity`（`activity_import_new.xml` / `activity_import.xml`）— Excel/CSV/TXT 走 v2，`.sql/.db` 走 `SqlFileImporter`
- 编排：`v2.ImportMain`（Java 调度层）↔ `ImportPythonBridge`（Chaquopy Python 预处理层）↔ `ImportCsvIngestor`（事务入库层）
- 推理：`v2.ImportLlmEngine`（本地模型专用，字段映射 + 缺失字段填充）

## 二、模块结构（当前实际代码）

```
com.oilquiz.app.ai.importing
├── AIImportOrchestrator      模型配置载体（瘦身版，原 v1 编排引擎仅保留模型模式/信息）
├── FieldMappingRegistry      字段映射注册表（别名 → 标准字段，v2/模板导入/Excel 工具共用）
├── ExcelSheetPicker          工作表选择 + 题型推断（v2 复用 inferQuestionTypeFromName）
├── ImportValidator           结果校验 + parseStructuredOutput（外部修复引擎/在线服务共用）
├── QuestionSchemaDictionary  字段 schema 字典 + 题型标准收口（全系统题型唯一权威）
├── SqlFileImporter           SQL / SQLite 导入（老 UI ImportActivity 的 .sql/.db 专用通道）
└── v2/                       CSV/Excel/文本 主导入引擎（9 类）
    ├── ImportMain            导入主流程/调度/交互确认/批量/多工作表
    ├── ImportCsvIngestor     CSV 分片事务批量入库 + 断点进度
    ├── ImportLlmEngine       本地推理引擎（字段映射/表头识别/缺失填充）
    ├── ImportPythonBridge    Python 预处理桥接（采样/解析/回写填充）
    ├── ImportMapCache        字段映射缓存（表结构指纹 + 表头指纹）
    ├── ImportBreakpointStore 断点续传存储（含源文件变更校验）
    ├── ImportOutputSanitizer 模型输出四层硬过滤
    ├── ImportToolManager     导入专用工具集（3 个工具 + 熔断）
    └── ImportDirs            会话/断点/报告目录管理
```

> **已删除的 v1 类（8 个，均为纯 v1 主流程死代码）**：`AIImportAgent`、`RuleBasedQuestionParser`、`QuestionFormatDetector`、`QuestionPreprocessor`、`FileProfiler`、`ImportMappingValidator`、`AIFieldMapper`、`model.AIImportResult`。

## 三、导入流程（v2 主流程）

```
选择文件（Excel/CSV/文本/SQL）
    ↓ 步骤0  初始化引擎（ImportLlmEngine + Python 桥接 + 读 question 表结构 PRAGMA）
    ↓ 步骤1  断点判断（校验源文件未变更）+ 映射缓存匹配（tableFinger + headerFinger）
    ↓ 步骤1.5 表头可疑时 LLM 识别真实表头行（仅 Excel + 表头关键词命中≤1 时）
    ↓ 步骤2  字段映射：缓存命中 > 本地词典规则（零 LLM）> LLM 映射（说明驱动）> 词典兜底
    ↓         ↳ 交互点1：字段映射确认（用户可修改映射）
    ↓ 步骤3  Python 全量解析：按映射清洗源文件 → 分片 CSV（3000 行/片）+ 实时断点
    ↓         ↳ 质量统计：总行/跳过/重复/题干空/缺字段（missing 数组）
    ↓ 步骤3.5 ↳ 交互点2：数据预览 + 错误处理（仅导入完整题 or 全部导入）
    ↓ 步骤4  缺失字段填充：规则优先（题型特征判定）→ LLM 辅助（10 题/批）
    ↓         ↳ 交互点3：填充确认（可关闭填充留空入库）
    ↓         ↳ 交互点4：最终入库确认
    ↓ 步骤5  Java 事务批量入库（ImportCsvIngestor，60 行/批 + 断点实时保存）
    ↓
导入结果（ImportSummary：新增/重复/失败/耗时 + 缺字段报告 issues）
```

关键设计：
- **分层单向数据流**：Java 调度层 → 专用 LLM 引擎 → Python 预处理层 → SQLite 持久层；Python 仅操作公共目录、无数据库权限。
- **海量题库文本不进模型上下文**：Python 侧完成解析与 CSV 中转，LLM 只做字段映射 / 缺失填充等小样本推理。
- **断点续传 + 源文件变更校验**：按文件 + 工作表隔离；断点记录源文件大小与修改时间，恢复前校验，文件被改动则断点失效全量重导（防错位）；解析与入库两级断点，分片缺失时显式报错。
- **4 个交互决策点**：字段映射确认 / 数据预览 / 填充确认 / 最终确认，可随时取消（取消自动清理断点）。

## 四、题型标准口径（v3.0 统一收口）

**入库 `questionType` 全系统统一为 5 种带"题"字标准值**（与 `Question` 模型 TYPE_* 常量一致）：

| 标准题型 | 常量 | 归一化来源示例 |
|---------|------|---------------|
| 单选题 | `TYPE_SINGLE` | 单选/single/sc/选择 |
| 多选题 | `TYPE_MULTIPLE` | 多选/multiple/mc |
| 判断题 | `TYPE_TRUE_FALSE` | 判断/对错/是非/judge/tf |
| 填空题 | `TYPE_FILL` | 填空/完形/fill/blank |
| 简答题 | `TYPE_SHORT_ANSWER` | 简答/问答/主观/案例分析/计算/综合/short/saq |

- **权威收口函数**：`QuestionSchemaDictionary.normalizeQuestionType(raw)` → 任意写法映射到标准 5 种之一；无法识别返回 null（视为"未分类"）。
- 扩展题型归并：**案例分析/论述/计算/综合 → 简答题**；**匹配 → 未分类**（无标准对应，由上层忽略或补填）。
- 各产出/校验点统一引用收口函数：`ImportOutputSanitizer.normalizeQuestionType`（委托）、`ImportValidator.normalizeType`（前置收口）、`ExcelSheetPicker.inferQuestionTypeFromName`（9 种收敛为 5 种）。
- AI 抽取 schema enum 与 prompt 取值同步改为 5 种，杜绝 prompt/schema 冲突。

> 判断题答案：统一按"对/错"写入（`normalizeChoiceAnswer` 对非字母答案原样保留，与判题 `checkAnswer` 兼容）；填空多空答案统一以全角分号 `；` 分隔（`FieldMappingRegistry` 单一来源）。

## 五、字段映射

分层策略（尽量少调 LLM，优先确定性规则）：

| 优先级 | 路径 | 说明 |
|--------|------|------|
| 1 | 映射缓存 | key = 表结构指纹 + 表头指纹（含列顺序，列变序不命中）；命中后校验源列仍存在，并用词典补全残缺映射 |
| 2 | 本地词典规则 | `FieldMappingRegistry.buildMappingFromHeaders`，题干+答案+选项列齐即采用（零 LLM） |
| 3 | LLM 映射 | 结合题库说明/模板说明（docHint）识别特殊列名，输出经 `ImportOutputSanitizer` 四层硬过滤 |
| 4 | 词典兜底 | LLM 失效时回退，缺题干/答案列则报错 |

特殊处理：
- **聚合选项列**（`可选项/选项/备选答案` 单列多选项）：映射为虚拟字段 `optionsCombined`，Python 按分隔符自动拆分为 `optionA~L`。
- **无表头文件**：LLM 识别真实表头行号（0-based，-1=无表头用占位列名）。
- **映射缓存持久化** `map_cache.json`（上限 64 条）；缓存 key 统一由 `ImportMapCache.buildCacheKey(tableFinger, headerFinger)` 生成——主流程与 LLM 工具路径共用同一构造函数，缺表头指纹时放弃保存防脏 key。

## 六、解析与入库（ImportCsvIngestor）

- CSV 分片 `parseCsvLine`（RFC4180）+ `readLogicalLine`（逻辑行跨行字段）。
- 入库必填仅题干；题型/难度/分类/解析缺失进入 `missing` 数组，由规则 + LLM 补填或留空入库（入库默认值兜底）。
- 事务批量入库（60 行/批），库内去重键 = 题干+答案；文件内重复仅保留 1 题，同题干不同答案全部保留并提示人工核对。
- 断点保存入库偏移（`onOffsetChange`），中断后可跳过解析直接续导入库。

## 七、SQL / SQLite 导入（SqlFileImporter）

- **SQLite 数据库文件**（.db/.sqlite/.sqlite3/SQLite 魔数）：只读打开，自动挑选最像题库的表（按列名映射，要求题干+答案），逐行提取。
- **SQL 脚本文件**（.sql）：解析 `INSERT INTO 表 (列...) VALUES (...)`，无列清单语句跳过并提示。
- **截断保护不再静默**：单表 5 万行上限截断的行数计入 `truncatedRows` 并告警提示；SQL 脚本 30MB 超限标记 `truncatedBySize` 并提示拆分文件，避免静默丢数据。
- 答案规范化：`Question.normalizeChoiceAnswer`（"A;B"→"AB" 等）。

## 八、校验与结果

- `ImportValidator` — 通用题目校验 + `parseStructuredOutput`（JSON 修复），亦被 `QuestionRepairEngine` / `OnlineInferenceService` 复用。
- `ImportOutputSanitizer` — 模型输出四层硬过滤（文本裁剪 → JSON 语法 → 字段合法性 → 数据类型强修正：difficulty 强制 1/2/3、题型收口 5 种）。
- 缺字段报告：扫描库内缺分类/答案的题目导出 CSV（上限 5000 条），提示用户补列后重导（重复题自动回填不重复入库）。

## 九、相关 Repository/DAO

导入题目通过 `ImportCsvIngestor` 事务批量写入 `question` 表（`AppDatabase` + `PRAGMA table_info` 动态适配表结构，详见 [数据库设计](10-database-design.md)）。

## 相关文档

- [数据库设计](10-database-design.md)
- [数据层设计](06-data-layer.md)
- [模块清单](08-module-inventory.md)
- [导入功能设计评审](15-question-import-review.md)
