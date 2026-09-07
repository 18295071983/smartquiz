# 数据库设计

> 版本: 1.0 | 更新日期: 2026-08-29 | 分支: feature/agent-local
> 本文件是数据库的详细设计，所有表结构/字段/关系均依据真实源码（`com.oilquiz.app.database` / `com.oilquiz.app.model`）。

## 一、概述

主数据库使用 **Room**，数据库名 `smartquiz_database`，版本 **24**。

**类**: `com.oilquiz.app.database.AppDatabase`

```java
@Database(
    entities = { User, Question, ScoreHistory, StudyPlan, Template,
                 WrongQuestion, FavoriteQuestion, Note, ChatHistory, LogEntry },
    version = 24,
    exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    public static final int DATABASE_VERSION = 24;
    public abstract UserDao userDao();
    public abstract QuestionDao questionDao();
    public abstract ScoreDao scoreDao();
    public abstract StudyPlanDao studyPlanDao();
    public abstract TemplateDao templateDao();
    public abstract WrongQuestionDao wrongQuestionDao();
    public abstract FavoriteQuestionDao favoriteQuestionDao();
    public abstract NoteDao noteDao();
    public abstract ChatHistoryDao chatHistoryDao();
    public abstract LogEntryDao logEntryDao();
}
```

## 二、核心表：Question（题目）

**表名**: `question` | **类**: `com.oilquiz.app.model.Question`

题库核心表，字段最多，支撑全部答题功能。

### 2.1 字段

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| id | long | autoGenerate | 主键 |
| questionText | String | — | 题干 |
| optionA~D | String | — | 选项 A-D |
| optionE~L | String | — | 选项 E-L（v22 独立列） |
| correctAnswer | String | — | 正确答案（选项字母，如 "A"/"AB"） |
| answerText | String | — | 标准答案文本（填空/简答，与 correctAnswer 分离） |
| category | String | — | 分类 |
| subCategory | String | — | 子分类 |
| difficulty | int | 1 | 难度（1 简单 2 中等 3 困难） |
| questionType | String | — | 题型 |
| explanation | String | — | 解析 |
| analysis | String | — | 详细解析 |
| relatedQuestion | String | — | 相关题目 |
| hint | String | — | 提示 |
| knowledgePoint | String | — | 知识点 |
| source | String | — | 来源 |
| tags | String | — | 标签（逗号分隔） |
| createdAt / updatedAt | long | 0 | 创建/更新时间 |
| favorite | boolean | 0 | 是否收藏 |
| points | int | 0 | 分值 |
| timeLimit | int | 0 | 答题时限（秒） |
| usageCount / correctCount / incorrectCount | int | 0 | 使用/正确/错误统计 |
| lastUsedAt | long | 0 | 最后使用时间 |
| status | int | 0 | 状态（0 正常 1 禁用 2 待审核） |
| isPublic | int | 1 | 是否公开 |
| author / comment | String | — | 作者/备注 |
| imageUri / audioUri | String | — | 配图/音频路径 |
| parentId | long | 0 | 母题 ID（子题关联） |
| sortOrder | int | 0 | 排序权重 |

### 2.2 索引

| 索引 | 字段 |
|------|------|
| `index_question_category` | category |
| `index_question_difficulty` | difficulty |
| `index_question_favorite` | favorite |
| `index_question_questionType` | questionType |
| `index_question_category_difficulty` | category, difficulty |
| `index_question_questionType_status` | questionType, status |

### 2.3 题型常量

`单选题` / `多选题` / `判断题` / `填空题` / `简答题`

### 2.4 QuestionDao 关键方法

```java
long insert(Question question);
void insertAll(List<Question> questions);
void update(Question question);
List<Question> searchQuestions(String keyword);
List<Question> getQuestionsByPage(int limit, int offset);
List<Question> getQuestionsByCategory(String category);
List<Question> getQuestionsByDifficulty(int difficulty);
```

## 三、其余表

### 3.1 User（用户）

**表名**: `user`

`id`（PK autoGenerate）/ `username` / `password` / `email` 等。

### 3.2 ScoreHistory（得分历史）

**表名**: `score_history`

`id` / `correctCount` / `wrongCount` / `score` / `createdAt` / `userId`

索引：`index_score_history_userId(userId)`

### 3.3 StudyPlan（学习计划）

**表名**: `study_plan`

`id` / `planName` / `questionCount` / `progress` / `createdAt` 等。

### 3.4 Template（模板）

**表名**: `template`

`id` / `name` / `type` / `content` / `fieldMapping` 等（用于答题卡模板）。

### 3.5 WrongQuestion（错题）

**表名**: `wrong_question`

`id` / `questionId` / `wrongCount` / `lastWrongAt` / `userId`

索引：`index_wrong_question_userId(userId)`

### 3.6 FavoriteQuestion（收藏）

**表名**: `favorite_question`

`id` / `questionId` / `createdAt` / `userId`

索引：`index_favorite_question_userId(userId)`

### 3.7 Note（笔记）

**表名**: `note`

`id` / `title` / `content` / `createdAt` / `updatedAt`

### 3.8 ChatHistory（聊天历史）

**表名**: `chat_history`

`id` / `userId` / `conversationId` / `role` / `content` / `timestamp` / `isCompressed` 等。

> 注意：AI 对话的会话级历史存储在 `ai.db`（`ChatDatabaseHelper`/`ChatRepository`）中，主库 `chat_history` 是另一套（按 userId）。详细见 `ai.util.ChatHistoryManager`。

### 3.9 LogEntry（日志）

**表名**: `log_entry`

`id` / `userId` / `level` / `tag` / `message` / `timestamp`

## 四、DAO 一览

| DAO | 对应表 | 职责 |
|-----|--------|------|
| `UserDao` | user | 用户 CRUD |
| `QuestionDao` | question | 题目 CRUD/搜索/分页 |
| `ScoreDao` | score_history | 得分历史 |
| `StudyPlanDao` | study_plan | 学习计划 |
| `TemplateDao` | template | 模板 |
| `WrongQuestionDao` | wrong_question | 错题 |
| `FavoriteQuestionDao` | favorite_question | 收藏 |
| `NoteDao` | note | 笔记 |
| `ChatHistoryDao` | chat_history | 聊天历史 |
| `LogEntryDao` | log_entry | 日志 |

## 五、数据库管理（辅助类）

| 类 | 职责 |
|-----|------|
| `DatabaseManager` | 数据库管理 |
| `DatabaseUpgradeManager` | 升级管理 |
| `DatabaseVersionChecker` | 版本检测 |
| `DatabaseFieldManager` | 字段管理 |

## 六、迁移（版本演进）

数据库从 v1 演进到 v24，关键迁移（`AppDatabase.MIGRATIONS`）：

| 版本 | 变更 |
|------|------|
| v17→v18 | question 加 `ai_enhanced`/`ai_tags` |
| v18→v19 | 新增 `ocr_history`/`question_images`/`ai_usage_log` 表 + 索引 |
| v19→v20 | question 加 createdAt/updatedAt/source/tags/points/timeLimit/hint/analysis/knowledgePoint 等 ~15 字段 |
| v20→v21 | question 加 answerText/imageUri/audioUri/parentId/sortOrder |
| v21→v22 | optionE~L 独立列（替代 extraOptions JSON），迁移存量数据 |
| v22→v23 | questionType 索引 |
| v23→v24 | question 表重建（14 个 NOT NULL 字段补 DEFAULT）；补 userId 索引 |

> **v24 修复点**：重建 question 表，为 `difficulty`/`favorite`/`createdAt` 等 14 个 NOT NULL 字段补 `DEFAULT`，使外部批量 INSERT 只填业务字段即可成功，避免 `NOT NULL constraint failed`。

## 七、其他数据库

- **聊天数据库**（`ai.db`）：`ChatDatabaseHelper` + `ChatRepository`，独立于主库，存 AI 对话会话/消息。
- **用量数据库**（`ai.usage.db`）：`UsageDatabase`，记录模型调用计费（详见 `ai.usage`）。

## 相关文档

- [数据层设计](06-data-layer.md)
- [项目架构总览](01-project-overview.md)
- [模块清单](08-module-inventory.md)
