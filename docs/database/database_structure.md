# 数据库结构设计

> 版本: v20 | 更新日期: 2026-07-18 | ORM: Room 2.5.2

## 一、数据库概览

| 属性 | 值 |
|------|-----|
| **数据库名称** | `smartquiz_database` |
| **ORM 框架** | Room 2.5.2 |
| **当前版本** | **v20** |
| **导出支持** | 可选 JSON 备份 |
| **加密** | SecurityCrypto（密钥加密存储） |

## 二、核心表结构

### 2.1 question（题库）

| 字段 | 类型 | 说明 | 版本 |
|------|------|------|------|
| `id` | INTEGER PK AUTO | 主键 | v1 |
| `questionText` | TEXT | 题目内容 | v1 |
| `optionA` | TEXT | 选项 A | v1 |
| `optionB` | TEXT | 选项 B | v1 |
| `optionC` | TEXT | 选项 C | v1 |
| `optionD` | TEXT | 选项 D | v1 |
| `correctAnswer` | TEXT | 正确答案 | v1 |
| `category` | TEXT | 分类 | v1 |
| `difficulty` | INTEGER | 难度等级（1~5） | v1 |
| `explanation` | TEXT | 题目解析 | v1 |
| `relatedQuestion` | TEXT | 关联题目 | v1 |
| `questionType` | TEXT | 题目类型（单选/多选/判断/填空） | v1 |
| `favorite` | INTEGER | 是否收藏（0/1） | v1 |
| `subCategory` | TEXT | 子分类 | **v20 新增** |
| `points` | INTEGER | 分值 | **v20 新增** |
| `timeLimit` | INTEGER | 时间限制（秒） | **v20 新增** |
| `hint` | TEXT | 提示 | **v20 新增** |
| `analysis` | TEXT | 题目解析（详细） | **v20 新增** |
| `knowledgePoint` | TEXT | 知识点 | **v20 新增** |
| `tags` | TEXT | 标签（逗号分隔） | **v20 新增** |
| `source` | TEXT | 来源（导入/手动/生成） | **v20 新增** |
| `usageCount` | INTEGER | 使用次数 | **v20 新增** |
| `correctCount` | INTEGER | 正确次数 | **v20 新增** |
| `incorrectCount` | INTEGER | 错误次数 | **v20 新增** |
| `lastUsedAt` | INTEGER | 最后使用时间戳 | **v20 新增** |
| `status` | INTEGER | 状态（0-正常，1-禁用，2-待审核） | **v20 新增** |
| `isPublic` | INTEGER | 是否公开（0-私有，1-公开） | **v20 新增** |
| `author` | TEXT | 作者 | **v20 新增** |
| `comment` | TEXT | 备注 | **v20 新增** |
| `extraOptions` | TEXT | 扩展选项（JSON格式） | **v20 新增** |
| `createdAt` | INTEGER | 创建时间 | **v20 新增** |
| `updatedAt` | INTEGER | 更新时间 | **v20 新增** |

**索引**:
- `idx_question_category`
- `idx_question_difficulty`
- `idx_question_favorite`
- `idx_question_category_difficulty`
- `idx_questions_created` (v20)
- `idx_questions_updated` (v20)
- `idx_questions_status` (v20)
- `idx_questions_points` (v20)

### 2.2 note（笔记）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `title` | TEXT | 笔记标题 |
| `content` | TEXT | 笔记内容 |
| `createTime` | INTEGER | 创建时间 |
| `updateTime` | INTEGER | 更新时间 |
| `userId` | INTEGER | 用户 ID |
| `relatedQuestionId` | INTEGER | 关联题目 ID（可选） |

### 2.3 wrong_question（错题本）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `questionId` | LONG FK | 关联的题目 ID |
| `userId` | LONG | 用户 ID |
| `wrongCount` | INTEGER | 错误次数 |
| `lastWrongTime` | LONG | 最后错误时间 |
| `userAnswer` | TEXT | 用户答案 |

> **注意**: 代码中还有 `@Ignore` 注解的临时字段（questionText, correctAnswer, category, questionType, explanation, optionA, optionB, optionC, optionD），这些字段用于 UI 展示，不存储在数据库中。

### 2.4 score_history（成绩记录）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `correctCount` | INTEGER | 正确数 |
| `totalQuestions` | INTEGER | 总题数 |
| `score` | INTEGER | 得分 |
| `startTime` | LONG | 开始时间戳 |
| `endTime` | LONG | 结束时间戳 |
| `category` | TEXT | 题目分类 |
| `difficulty` | TEXT | 难度 |
| `quizType` | TEXT | 答题模式 |
| `userId` | LONG | 用户 ID |

### 2.5 study_plan（学习计划）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `planName` | TEXT | 计划名称 |
| `targetQuestions` | INTEGER | 目标题目数 |
| `completedQuestions` | INTEGER | 已完成题目数 |
| `startDate` | LONG | 开始日期时间戳 |
| `endDate` | LONG | 结束日期时间戳 |
| `userId` | LONG | 用户 ID |
| `status` | TEXT | 状态 |

### 2.6 user（用户信息）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `username` | TEXT | 用户名 |
| `email` | TEXT | 邮箱 |
| `phone` | TEXT | 手机号 |
| `password` | TEXT | 密码（加密存储） |
| `avatar` | TEXT | 头像路径 |
| `isLoggedIn` | INTEGER | 是否登录（0/1） |

### 2.7 chat_history（AI 对话历史）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `userId` | LONG | 用户 ID |
| `message` | TEXT | 消息内容 |
| `sender` | TEXT | 发送者（user/assistant） |
| `timestamp` | LONG | 时间戳 |

### 2.8 template（导出模板）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `name` | TEXT | 模板名称 |
| `description` | TEXT | 模板描述 |
| `filePath` | TEXT | 模板文件路径 |
| `createdAt` | LONG | 创建时间 |
| `updatedAt` | LONG | 更新时间 |
| `enabled` | BOOLEAN | 是否启用 |

### 2.9 favorite_question（收藏题目）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `questionId` | LONG FK | 关联题目 ID |
| `userId` | LONG | 用户 ID |
| `favoriteTime` | LONG | 收藏时间 |

### 2.10 log_entry（日志记录）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `userId` | LONG | 用户 ID（可选） |
| `action` | TEXT | 操作类型 |
| `detail` | TEXT | 操作详情 |
| `timestamp` | LONG | 时间戳 |
| `level` | TEXT | 日志级别 |

## 三、数据库迁移历史

| 版本 | 变更内容 |
|------|----------|
| v1 ~ v17 | 初始版本与迭代优化 |
| v17 → v18 | 添加 AI 增强字段（ai_enhanced, ai_tags） |
| v18 → v19 | 新增 `ocr_history`、`question_images`、`ai_usage_log` 三张表；添加索引 |
| **v19 → v20** | `question` 表重大扩展：新增 18 个字段（createdAt, updatedAt, source, tags, points, timeLimit, hint, analysis, knowledgePoint, subCategory, usageCount, correctCount, incorrectCount, lastUsedAt, status, isPublic, author, comment, extraOptions）；新增 4 个索引 |

## 四、DAO 接口

| DAO | 对应表 | 主要操作 |
|-----|--------|---------|
| `QuestionDao` | question | CRUD, 批量导入, 搜索, 统计 |
| `NoteDao` | note | CRUD |
| `WrongQuestionDao` | wrong_question | CRUD, 统计 |
| `ScoreDao` | score_history | CRUD, 统计分析 |
| `StudyPlanDao` | study_plan | CRUD |
| `UserDao` | user | CRUD |
| `ChatHistoryDao` | chat_history | CRUD |
| `TemplateDao` | template | CRUD |
| `FavoriteQuestionDao` | favorite_question | CRUD |
| `LogEntryDao` | log_entry | CRUD |

## 五、数据关系

```
user ──────────────────────────────────┐
    │ 1:N                                │
    ├── score_history ──── question      │
    ├── study_plan                       │
    ├── note                             │
    ├── wrong_question ─── question      │
    ├── chat_history                     │
    ├── favorite_question ─── question   │
    └── log_entry                        │
                                        │
question ──────────────────────────────┐
    │ 1:N                               │
    ├── wrong_question                  │
    ├── favorite_question               │
    ├── note                            │
    └── score_history                   │
```

## 六、备份与恢复

- **备份机制**: `BackupManager` + `BackupWorker` (WorkManager)
- **备份格式**: JSON 文件（`smartquiz_backup_*.json`）
- **备份内容**: 可选全部表或仅题库
- **恢复**: `BackupManager.restore()`
- **定时备份**: 通过 WorkManager 后台定期执行

---

## 相关文档

- [系统架构设计](../system/system_architecture.md)
- [技术栈详情](../development/tech_stack.md)
- [模块功能设计](../development/module_function_design.md)