# OilQuiz 综合迭代文档

**生成时间**: 2026-08-09
**文档性质**: 汇总 `/sdcard/oilquiz/` 目录下全部文件（导航、迭代文档、2 个 Skill、3 个 Python 工具、3 个数据文件、11 天 AI 日志）后的统一迭代蓝图
**应用名称**: oilquiz（煤化工气化岗位理论考试题库）
**技术栈**: Android + Room/SQLite + Chaquopy (Python 3.10.15) + llama.cpp 端侧推理

---

## 一、项目现状概览

| 项目 | 现状 |
|------|------|
| 题库规模 | 38 题（全部来自「全员综合复审题库-气化.xlsx」），目标 200+ 题 |
| 数据库 | `/data/data/com.oilquiz.app/databases/smartquiz_database`，11 张表，由 Room 管理 |
| 数据量 | 除 question 外各表均为 0 条（功能表未启用） |
| Python 环境 | Chaquopy 已集成，openpyxl/xlrd/pandas/numpy 可用，pdfplumber/python-docx 缺失 |
| Skill 资产 | question_batch_import（v2）、quiz_file_analyzer 两个技能已沉淀为 md 备份 |
| 待导入素材 | 煤制气/安全题库约 1014 题 + 每日一题素材（xlsx） |

### 📁 手机端文件清单（/sdcard/oilquiz/）

| 文件 | 用途 | 状态 |
|------|------|------|
| OilQuiz_导航.md | 项目唯一导航入口 | ✅ 已修正为 oilquiz/ 实际路径，并收录本文档索引 |
| OilQuiz_应用问题分析与迭代建议.md | 详细迭代文档（问题+评审+崩溃分析） | ✅ 本文档的汇总来源 |
| question_batch_import_skill.md | 批量导入 Skill v2（省 token 版） | ✅ 含实测路径与踩坑记录 |
| quiz_file_analyzer_skill.md | 题库文件通用分析 Skill | ✅ 支持 10 种格式 |
| import_tool.py | 直连 SQLite 导入工具 | 🔴 致命 bug，一执行即失败 |
| batch_import.py / batch_import_v2.py | 分析预览工具，不实际写库 | 🟡 仅预处理 |
| import_all.sql（727KB） | 待导入题库 SQL | 📦 待导入 |
| import_data.json（491KB） | 待导入题库 JSON | 📦 待导入 |
| questions_import.sql（91KB） | 气化题库 SQL | 📦 部分已导入 |
| ai_logs/（11 个日志） | 应用运行日志（07-05~08-09） | 仅启动级日志，无错误记录 |

---

## 二、已知问题汇总（按严重度）

### 🔴 P0 级（阻断性）

#### 1. NOT NULL 无默认值 → 批量导入全军覆没
`question` 表有 **14 个 NOT NULL 且无 DEFAULT** 的字段：

```
favorite, createdAt, updatedAt, points, timeLimit,
usageCount, correctCount, incorrectCount, lastUsedAt,
status, isPublic, parentId, sortOrder
```

- 实测 `import_tool.py` 一执行即报 `NOT NULL constraint failed: question.favorite`
- 之前 1014 题导入失败的根本原因
- **修复**：走 Room Migration（Entity 加 `@ColumnInfo(defaultValue=...)` + 版本号+1 + Migration 重建表）

#### 2. DatabaseTool 线程池 Terminated 后不重建
- `get_question_count` / `get_question_statistics` 报 `RejectedExecutionException`（ThreadPoolExecutor Terminated）
- 同一时刻 `execute_sql` 走同步路径却正常 → 工具内部双执行路径，时灵时不灵
- 重启 App 可临时恢复，根治需代码修复（单例可复用线程池，或 isShutdown 检查重建）

#### 3. 表结构修改必须走 Room Migration（开发规范）
- 库中存在 `room_master_table`（identity_hash=`7fed00220c2d5f314a8e6671fdcf2408`）
- SQL 层直接 DROP/重建表会导致下次启动抛 `Room cannot verify the data integrity` 崩溃
- 所有 DDL 变更：改 Entity 注解 → 版本+1 → Migration 类 → Room 自动迁移

#### 4. SAF 权限崩溃：文件导入 SecurityException
- 2026-08-09 02:56:37 实测崩溃：直接访问小米 `FileExplorerDocumentsProvider` 被系统拒绝
- **修复**：改用 `ACTION_OPEN_DOCUMENT`/`ACTION_GET_CONTENT`；禁止硬编码第三方 Provider URI；长期访问用 `takePersistableUriPermission`；跨组件传 URI 加 `FLAG_GRANT_READ_URI_PERMISSION`；兜底 try-catch SecurityException

### 🟡 P1 级（结构性）

| # | 问题 | 说明 |
|---|------|------|
| 5 | question 表 44 列严重违反范式 | optionA~L 12 列固定选项；统计字段（usageCount 等）混入主表导致答题高频 UPDATE 锁竞争 |
| 6 | 索引不足 | 现有 6 个索引全在 question 表；score_history/wrong_question/favorite_question/chat_history/note/study_plan 全部零索引 |
| 7 | 无外键约束 | 删题后 wrong_question 等产生孤儿记录；且未开 `PRAGMA foreign_keys=ON` |
| 8 | DatabaseTool 接口臃肿 | 20+ action 混杂：execute_sql 与 execute_query 重复；add_questions 限 10 条无法批量 |
| 9 | 答案格式混乱 | 填空题多答案用分号分隔存 correctAnswer，与括号对应关系不明，自动判分困难 |
| 10 | user 表设计缺陷 | password 明文、无 UNIQUE(username/email) |
| 11 | difficulty 类型不一致 | score_history 为 TEXT，question 为 INTEGER |

### 🟢 P2 级（数据质量与体验）

| # | 问题 | 说明 |
|---|------|------|
| 12 | 重复题目 | 「粉尘发生爆炸应具备的条件」「烧嘴冷却水罐压力控制」各出现 2 次 |
| 13 | 时间戳异常 | 所有题目 createdAt/updatedAt 均为固定值 1786233600000 |
| 14 | 分类单一、题型单一 | 全部「气化」分类、无选项数据（optionA-D 全空），选择题模式不可用 |
| 15 | 功能表全未启用 | user/score_history/wrong_question/favorite_question/note/study_plan/chat_history 均无对应功能 |
| 16 | 无 WAL 模式、无 updatedAt 触发器 | 并发读写可能阻塞，时间戳需应用层手动维护 |

---

## 三、导入链路现状评估

### 三条可用通道（按实测结论排序）

| 通道 | 实测结果 | 说明 |
|------|---------|------|
| ❌ Python 直连 `/data/data/*.db` | Permission denied | 沙箱无权限，Skill 中已标注"别再试" |
| ❌ Python 访问 `Android/data/` | Permission denied | 同上 |
| ✅ `database execute_sql` | 每批 20-30 条稳定 | 但受线程池问题影响时灵时不灵 |
| ✅ python_execute 读写 `/storage/emulated/0/` 公开目录 | 可用 | openpyxl 可用，缺则 pip install |

### 现有 3 个导入工具的缺陷

1. **import_tool.py**：INSERT 只填 11 列，缺 14 个 NOT NULL 字段 → 必失败
2. **batch_import.py / v2**：只做分析预览，不实际入库；正则猜字段位置极脆弱
3. 共性：**无端到端链路**（分析→校验→入库→验证，缺"入库"一步）；绕过 Room 直写 SQLite 在 App 运行中有锁冲突风险

### 最小修复（让工具立即可用）

```python
import time
now_ms = int(time.time() * 1000)
DEFAULTS = {
    "favorite": 0, "createdAt": now_ms, "updatedAt": now_ms,
    "points": 0, "timeLimit": 0, "usageCount": 0,
    "correctCount": 0, "incorrectCount": 0, "lastUsedAt": 0,
    "status": 1, "isPublic": 1, "parentId": 0, "sortOrder": 0
}
```

---

## 四、迭代路线图

### 🔴 P0 — 立即修复（阻断解除）

| # | 事项 | 类型 | 验收标准 |
|---|------|------|---------|
| 1 | 修复 DatabaseTool 线程池 Terminated 不重建 | App 代码 | 所有 action 连续调用不报 RejectedExecutionException |
| 2 | Room Migration 给 14 个 NOT NULL 字段加 DEFAULT | App 代码 | 外部 INSERT 只填业务字段不再报 constraint failed |
| 3 | 导入 1014 道煤制气/安全题库，扩充至 200+ 题 | 数据 | 题库总量 ≥ 200 题且无 NOT NULL 报错 |
| 4 | 修复 SAF 文件导入 SecurityException 崩溃 | App 代码 | 从小米文件管理器选文件导入不再崩溃 |
| 5 | 补索引：score_history/wrong_question/favorite_question 的 userId | Room Migration | 索引创建成功 |

### 🟡 P1 — 短期优化（1-2 个迭代）

| # | 事项 |
|---|------|
| 6 | 选择题模式：补齐 optionA-D 数据、完善选择题答题流程 |
| 7 | 题库管理界面：增删改查 + Excel 批量导入入口 |
| 8 | 统计字段拆表：新建 question_stats（questionId + 统计字段） |
| 9 | parentId/sortOrder 改可空；wrong_question 补外键级联删除 |
| 10 | 填空题答案改 JSON 数组存储，明确括号对应关系 |
| 11 | 去重清理 + 修正固定时间戳 |
| 12 | 精简 DatabaseTool 接口：保留 execute_sql + add_questions（上限提至 100）+ list_tables |

### 🟢 P2 — 中期目标（用户体验）

| # | 事项 |
|---|------|
| 13 | 错题本：记录答错题目 + 复习入口 |
| 14 | 成绩统计：正确率、用时、历史成绩可视化 |
| 15 | 用户系统：注册/登录（加 UNIQUE + 密码哈希）、个人档案 |
| 16 | 搜索筛选：按分类/难度/题型过滤 |
| 17 | 开启 WAL + foreign_keys，增加 updatedAt 触发器 |

### ⚪ P3 — 长期规划

| # | 事项 |
|---|------|
| 18 | 学习计划：每日目标、进度跟踪、提醒激励 |
| 19 | AI 辅助：智能出题策略、个性化学习路径、解析生成 |
| 20 | 补 pdfplumber/python-docx，扩展 PDF/Word 试卷导入 |
| 21 | 选项结构改 JSON 或独立 options 子表（optionA~L 的彻底替代） |

---

## 五、关键技术约束（务必遵守）

1. **Room 管控**：数据库由 Room 管理，任何表结构变更必须走 Entity 注解 + Migration，严禁 SQL 层直接 DDL，否则 identity_hash 校验失败启动崩溃
2. **沙箱权限**：Python（Chaquopy）无法访问 `/data/data` 与 `Android/data`，导入必须经 database 工具通道或原生导入工具
3. **file_generator 路径重定向**：传 `/storage/emulated/0/OilQuiz/x.md` 实际写到 `Android/data/com.oilquiz.app/files/Download/x.md`；写真公开目录须用 python_execute 的 `open()`
4. **App 运行时写库风险**：绕过 Room 直写 SQLite 可能触发锁冲突，批量导入前建议先关闭 App 或走应用内通道
5. **SAF 规则**：访问 DocumentsProvider 必须先经 ACTION_OPEN_DOCUMENT 授权，禁止硬编码第三方 content:// URI

---

## 六、Skill 经验沉淀（省 token 铁律）

来自 question_batch_import_skill.md 的固化标准动作：

1. **数据零过话**：>20 条的批量数据（题目/SQL/JSON）禁止以文本形式出现在对话或工具参数中
2. **一次调用原则**：第 1 次 python_execute 完成全部环境探测（源文件/openpyxl/表结构/直连能力），输出能力清单
3. **失败换通道不缩批次**：SQL 过长失败时改走原生工具/Python 闭环，而不是把批次从 30 缩到 10
4. **只报数字**：导入完成只输出总数/分类/题型分布，不复述题目内容
5. **优先专用工具**：接任务先扫工具列表，question_batch_import 原生工具就是为"数据不过对话"设计的

### 实测执行路径清单（本机环境）

- ✅ python_execute 读写 `/storage/emulated/0/` 公开目录
- ✅ openpyxl 读 xlsx（首次 `import openpyxl` 试探，缺则 pip install）
- ✅ database execute_sql 每批 20-30 条 INSERT 稳定
- ❌ Python 直连应用私有库 / 访问 Android/data（直接走兜底，勿重试）

---

## 七、下次开工检查清单

- [x] 先修线程池（否则统计类 action 持续不可用）—— 2026-08-09 已修复
- [x] Room Migration 加默认值（当前题库少，迁移成本最低的最佳时机）—— 2026-08-09 已完成 v24
- [x] 修 SAF 崩溃（否则 App 内文件导入功能不可用）—— 2026-08-09 已修复
- [ ] 再执行 1014 题导入（import_all.sql / import_data.json 已在手机就位）
- [ ] 导入后：去重检查、分类统计、抽题验证渲染
- [x] 更新导航文件中的过时路径（Download/ → oilquiz/，2026-08-09 已完成）

---

## 八、版本记录

### v2026-08-09（P0 阻断解除）

**数据库版本**: 23 → 24（Migration 23→24 已实现，编译通过 BUILD SUCCESSFUL）

| # | 事项 | 修复方式 | 涉及文件 |
|---|------|---------|----------|
| 1 | 线程池 Terminated 不重建 | DatabaseManager/DatabaseFieldManager 改单例可重建线程池（getExecutor() 检测 isShutdown/isTerminated 自动重建）；移除 ImportActivity.onDestroy 中的 shutdown() 调用 | DatabaseManager.java、DatabaseFieldManager.java、ImportActivity.java |
| 2 | 14 个 NOT NULL 字段无 DEFAULT | Question Entity 加 @ColumnInfo(defaultValue)：difficulty=1、favorite=0、createdAt=0、updatedAt=0、points=0、timeLimit=0、usageCount=0、correctCount=0、incorrectCount=0、lastUsedAt=0、status=0、isPublic=1、parentId=0、sortOrder=0；Migration 23→24 重建 question 表带 DEFAULT | Question.java、AppDatabase.java |
| 4 | SAF SecurityException 崩溃 | ACTION_OPEN_DOCUMENT 启动失败降级 ACTION_GET_CONTENT；选中后 takePersistableUriPermission（try-catch）；getFileNameFromUri 查询兜底；ModelFileSelector 修正非法 EXTRA_INITIAL_URI（裸路径 → buildDocumentUri） | ImportActivity.java、ModelFileSelector.java |
| 5 | 三张表补 userId 索引 | Entity 加 @Index("userId") + Migration 创建 index_score_history_userId / index_wrong_question_userId / index_favorite_question_userId | ScoreHistory.java、WrongQuestion.java、FavoriteQuestion.java、AppDatabase.java |

**与文档原方案的偏差说明**：`status` 默认值采用 **0（正常）** 而非原 DEFAULTS 字典中的 1——因 App 全部查询带 `WHERE status = 0`，默认 1 会导致外部导入题目不可见。

**附带收益**：v2 离线导入管线（ImportCsvIngestor）此前只填映射列 + difficulty/createdAt/updatedAt，在旧 schema 下同样会遭遇 NOT NULL 失败；v24 后缺失列自动落 DEFAULT，导入链路同步解锁。

**下一步**：真机验证 Migration 23→24 平滑升级（38 题存量保留）→ 执行 1014 题导入（推荐走 App 内 v2 管线：文件放 /storage/emulated/0/OilQuiz/source/）→ 去重与分类统计。

**补充优化（v2 管线动态字段注入）**：Python 预处理层不再硬编码业务字段——标准 CSV 列（含 optionA~L/answerText 等全量候选）、AI 可填充字段、选项字母映射均由 Java 根据 PRAGMA table_info 真实表结构动态生成 spec_json 下发（parse_file/apply_fills 新参数）；Python 内置常量仅作为参数解析失败时的兜底默认值。表结构后续变更时无需同步修改 Python 代码。已通过：Gradle 编译 + 5 个端到端用例（动态 spec/空参兜底/非法 JSON 兜底/回写动态字段/回写兜底）。

**补充优化（v2026-08-09-2，Python→Java 返回管道容错 + 一键导入 UI）**：

| 层 | 改动 | 容错机制 |
|---|---|---|
| ImportPythonBridge | callWithRetry 统一调用包装 | 瞬时异常自动重试 1 次（重试前重载模块）；失败永不返回 null，一律归一化 {"success":false,"error":...}；parseResult 兼容 JSON 字符串与 Python dict 两种返回形态；异常信息取末行（Python 堆栈末行才是真实原因） |
| ImportMain | extractError 统一错误提取 | 采样结果检查 error 字段 + 表头非空校验；解析分片清单逐个校验磁盘存在性（丢弃脏路径）；入库断点分片丢失时清除断点并提示重导；回写失败记录错误但不阻断入库；批量模式单文件失败不中断整批，结束必发 all-done 信号 |
| Python | 6 条异常路径实测 | 文件不存在/类型不支持/非法 mapping JSON 等全部返回归一化错误 JSON，与 Java 契约对齐 |
| UI | 导入引导页新增「📁 一键导入 source 目录」卡片 | ImportActivity 新增 EXTRA_SOURCE_DIR_MODE 批量模式：实时进度+统计卡片累计，单文件错误非致命，结束后弹窗汇总（文件数/新增/重复/失败/出错数）；取消按钮支持中途终止 v2 管线 |

已通过：Gradle 编译 + Python 侧 6 条异常路径归一化实测。

---

*本文档由 AI 助手汇总 /sdcard/oilquiz/ 全部文件生成。后续迭代请在此文档上追加版本记录，保持唯一迭代入口。*
