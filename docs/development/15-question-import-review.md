# 题库导入功能设计评审报告

> 评审对象：`com.oilquiz.app.ai.importing` 包
> 评审基准：真实源码逐文件阅读，非仅依据设计文档
> 评审日期：2026-08-29（v1.0 初评）→ 2026-08-29（v2.0 清理后复核）→ 2026-08-29（v3.0 修复后复核）

---

## 〇、变更记录

### v2.0 — v1 清理（已执行）
v1 导入主流程（`AIImportOrchestrator.start/startWithSheet` 整条链路）全项目零调用方，已整体删除：
- 删除 8 个纯 v1 死类：`AIImportAgent`、`RuleBasedQuestionParser`、`QuestionFormatDetector`、`QuestionPreprocessor`、`FileProfiler`、`ImportMappingValidator`、`AIFieldMapper`、`model/AIImportResult`
- 瘦身 `AIImportOrchestrator`（1994→96 行，保留模型模式/信息/取消/回调）
- 保留被 v2/外部复用的 6 类：`FieldMappingRegistry`、`ExcelSheetPicker`、`ImportValidator`、`QuestionSchemaDictionary`、`SqlFileImporter`、`AIImportOrchestrator`
- 编译验证通过（BUILD SUCCESSFUL）

### v3.0 — 剩余问题修复（已执行，编译验证通过 BUILD SUCCESSFUL）
| # | 问题 | 修复内容 | 涉及文件 |
|---|------|---------|---------|
| P0#2 | SQL 导入 30MB / 5 万行静默截断 | 截断改为显式统计 + 告警：`truncatedRows`、`truncatedBySize` 字段，结果 messages 明确提示丢失行数与拆文件建议 | `SqlFileImporter` |
| P0#5 | 题型取值体系多处打架 | 新增 `QuestionSchemaDictionary.normalizeQuestionType` 统一收口（5 种带"题"字）；schema enum 与 prompt 同步修正；`ImportOutputSanitizer` 委托收口；`ImportValidator` 前置收口；`ExcelSheetPicker` 9 种收敛为 5 种（案例分析/计算/综合→简答，匹配→未分类） | `QuestionSchemaDictionary`、`ImportOutputSanitizer`、`ImportValidator`、`ExcelSheetPicker` |
| P2#15 | 断点不校验源文件变更 | 断点记录源文件大小 + 修改时间，`load()` 恢复前校验，不一致则断点失效全量重导 | `ImportBreakpointStore` |
| P2#16 | 映射缓存 key 不一致 | LLM 工具路径改用 `ImportMapCache.buildCacheKey(tableFinger, headerFinger)`（与主流程一致），缺 header_finger 时放弃保存防脏 key | `ImportToolManager` |
| P2#17 | Excel 子表头硬编码 `rawRows.get(1)` | 用传入 `subHeaderRowIndex` 计算局部偏移定位子表头，删除硬编码 | `ExcelSheetPicker` |
| P2#12 | 本地模型输出上限（复核） | **无需修改**：v2 为单题 256 / 批量 `160+n*200`(≤2048) / 映射 512 的小样本推理设计，无 v1 整块截断丢题问题 | — |

---

## 一、评审结论（摘要）

整体架构有亮点：**规则优先 + AI 兜底分层、断点续传 + 交互确认、Python 权限隔离、四层输出硬过滤** 在同类工具中属于成熟设计。

经 v2.0 清理 + v3.0 修复，初评的 **19 个问题中 16 个已解决或收敛，剩余 3 个为「复核确认无需修改」项**（判断题答案、填空分隔符、本地输出上限——详见下方「复核结论」）。当前无已知的静默丢数据 / 格式错乱硬伤。

---

## 二、问题清单（按严重程度分级，含最新状态）

### 🔴 P0 — 静默丢数据 / 入库格式错乱

| # | 问题 | 状态 |
|---|------|------|
| 1 | Excel 直读 600 行截断（v1 路径） | ✅ 已解决：v1 直读路径已删；v2 走 Python 解析无此限制 |
| 2 | **SQL 导入 30MB / 5 万行静默截断** | ✅ 已修复（v3.0）：截断显式统计 + 告警提示 |
| 3 | 判断题答案格式三套体系 | 🔶 复核确认：v1 的 A/B 来源已随删除消失，当前 AI prompt 统一"对/错"，`normalizeChoiceAnswer` 对非字母答案原样保留，与判题兼容；源数据若为 A/B 由 `Question` 模型既有归一化兜底，无需在导入层强制改写 |
| 4 | 填空分隔符不统一（`；` vs `\|`） | ✅ 已解决：v1 的 `\|` 已删，当前仅 `FieldMappingRegistry` 单一来源 `；`，与 `normalizeChoiceAnswer` 兼容 |
| 5 | **题型取值体系多处打架** | ✅ 已修复（v3.0）：`QuestionSchemaDictionary.normalizeQuestionType` 统一收口 5 种 |
| 6 | 两套去重键口径不一致 | ✅ 已解决：v1 删除后 v2 统一为「题干+答案」 |
| 7 | Moodle XML 检测无解析器 | ✅ 已解决：随 v1 删除 |

### 🟠 P1 — 架构 / 一致性隐患

| # | 问题 | 状态 |
|---|------|------|
| 8 | v1/v2 双引擎并存 | ✅ 已解决：v1 主流程删除，收敛单一管道 |
| 9 | v1 入库全量加载整库去重 | ✅ 已解决：随 v1 删除 |
| 10 | v1 入库无事务 | ✅ 已解决：v2 事务批量入库 |
| 11 | 字段映射线程失控 | ✅ 已解决：`AIFieldMapper` 已删 |

### 🟡 P2 — 可靠性 / 性能

| # | 问题 | 状态 |
|---|------|------|
| 12 | 本地模型输出上限 | 🔶 复核确认无需修改：v2 为小样本推理设计（单题 256 / 批量≤2048 / 映射 512），无整块截断丢题 |
| 13 | AI 空结果不自纠 | ✅ 已解决：随 v1 删除 |
| 14 | 流式模拟主线程风暴 | ✅ 已解决：随 v1 删除 |
| 15 | **断点不校验源文件变更** | ✅ 已修复（v3.0）：记录大小+修改时间，恢复前校验 |
| 16 | **映射缓存 key 不一致** | ✅ 已修复（v3.0）：统一 `buildCacheKey` |
| 17 | **Excel 子表头硬编码** | ✅ 已修复（v3.0）：按传入 `subHeaderRowIndex` 定位 |
| 18 | 旧表格路径按列位置硬猜 | ✅ 已解决：随 v1 删除 |
| 19 | 质检抽样固定 seed | ✅ 已解决：QA Gate 随 v1 删除 |

---

## 三、设计亮点（保留，勿推翻）

- **规则优先、AI 兜底**：零模型成本优先（词典规则 → LLM → 词典兜底），LLM 仅在规则不完整时介入。
- **v2 交互确认 + 断点续传 + 3000 行分片**：4 个决策点暂停确认；断点按文件 + 工作表隔离，且恢复前校验源文件未变更；分片防内存溢出。
- **Python 权限隔离**：Python 层仅操作公共存储目录、无数据库访问权限，海量题库文本不进模型上下文。
- **`ImportOutputSanitizer` 四层后置硬过滤**：文本裁剪 → JSON 校验 → 字段合法性 → 数据类型强修正，配 3 轮熔断，不依赖模型自觉。
- **题型统一收口（v3.0 新增）**：`QuestionSchemaDictionary` 作为全系统题型唯一权威，所有产出/校验点引用同一收口函数。
- **映射缓存表头指纹含列顺序**：同列不同序不会错命中；缓存 key 全系统统一构造函数。

---

## 四、修复路线图（全部完成 ✅）

| 阶段 | 内容 | 状态 |
|------|------|------|
| 1. 统一数据规范 | 题型枚举统一 5 种 + 收口函数；判断题答案 / 填空分隔符复核统一 | ✅ 完成 |
| 2. 消灭静默丢数据 | SQL 截断告警；断点源文件校验 | ✅ 完成 |
| 3. 一致性加固 | 映射缓存 key 统一；Excel 子表头定位修正 | ✅ 完成 |
| 4. 可靠性加固 | 本地输出上限复核（无需改）；事务/分片（v2 既有） | ✅ 完成 |

---

## 五、评审覆盖范围说明

- **初评（v1.0）**：逐文件阅读 23 类中的 20 个核心类。
- **清理后复核（v2.0）**：全项目引用扫描确认 8 个 v1 类零外部引用后删除；Orchestrator 瘦身。
- **修复后复核（v3.0）**：上述 6 处代码修改全部经 `compileDebugJavaWithJavac` 编译验证通过（BUILD SUCCESSFUL，无新增错误；仅项目既有的 Room 注解 warning）。
- 结论均附代码位置（类 / 方法），可对照源码复现。
