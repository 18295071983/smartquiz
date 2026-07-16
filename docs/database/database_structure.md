# 数据库结构设计

> 版本: v20 | 更新日期: 2026-05-16 | ORM: Room 2.5.2

## 一、数据库概览

| 属性 | 值 |
|------|-----|
| **数据库名称** | `smartquiz.db` |
| **ORM 框架** | Room 2.5.2 |
| **当前版本** | **v20** |
| **导出支持** | 可选 JSON 备份 |
| **加密** | SecurityCrypto（密钥加密存储） |

## 二、核心表结构

### 2.1 questions（题库）

| 字段 | 类型 | 说明 | 版本 |
|------|------|------|------|
| `id` | INTEGER PK AUTO | 主键 | v1 |
| `title` | TEXT | 题目标题 | v1 |
| `content` | TEXT | 题目内容 | v1 |
| `type` | TEXT | 题目类型（单选/多选/判断/填空） | v1 |
| `category` | TEXT | 分类 | v1 |
| `subCategory` | TEXT | 子分类 | **v20 新增** |
| `difficulty` | TEXT | 难度（容易/中等/困难） | v1 |
| `options` | TEXT (JSON) | 选项列表 | v1 |
| `answer` | TEXT | 正确答案 | v1 |
| `analysis` | TEXT | 题目解析 | **v20 新增** |
| `knowledgePoint` | TEXT | 知识点 | **v20 新增** |
| `tags` | TEXT | 标签（逗号分隔） | **v20 新增** |
| `points` | INTEGER | 分值 | **v20 新增** |
| `timeLimit` | INTEGER | 时间限制（秒） | **v20 新增** |
| `hint` | TEXT | 提示 | **v20 新增** |
| `source` | TEXT | 来源（导入/手动/生成） | **v20 新增** |
| `usageCount` | INTEGER | 使用次数 | **v20 新增** |
| `correctCount` | INTEGER | 正确次数 | **v20 新增** |
| `incorrectCount` | INTEGER | 错误次数 | **v20 新增** |
| `lastUsedAt` | INTEGER | 最后使用时间戳 | **v20 新增** |
| `status` | TEXT | 状态 | **v20 新增** |
| `isPublic` | INTEGER | 是否公开 | **v20 新增** |
| `author` | TEXT | 作者 | **v20 新增** |
| `comment` | TEXT | 备注 | **v20 新增** |
| `extraOptions` | TEXT | 扩展选项 | v1 |
| `createdAt` | INTEGER | 创建时间 | **v20 新增** |
| `updatedAt` | INTEGER | 更新时间 | **v20 新增** |

**索引** (v20):
- `idx_questions_type`
- `idx_questions_category`
- `idx_questions_difficulty`
- `idx_questions_created` **(v20 新增)**
- `idx_questions_updated` **(v20 新增)**
- `idx_questions_status` **(v20 新增)**
- `idx_questions_points` **(v20 新增)**

### 2.2 notes（笔记）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `title` | TEXT | 笔记标题 |
| `content` | TEXT | 笔记内容 |
| `category` | TEXT | 分类 |
| `createTime` | INTEGER | 创建时间 |
| `updateTime` | INTEGER | 更新时间 |

### 2.3 wrong_questions（错题本）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `questionId` | INTEGER FK | 关联的题目 ID |
| `wrongCount` | INTEGER | 错误次数 |
| `lastWrongTime` | INTEGER | 最后错误时间 |
| `corrected` | INTEGER | 是否已改正确（0/1） |
| `note` | TEXT | 错题笔记 |

### 2.4 scores（成绩记录）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `mode` | TEXT | 答题模式 |
| `totalQuestions` | INTEGER | 总题数 |
| `correctCount` | INTEGER | 正确数 |
| `score` | FLOAT | 得分 |
| `accuracy` | FLOAT | 正确率 |
| `usedTime` | INTEGER | 用时（秒） |
| `date` | INTEGER | 日期时间戳 |
| `category` | TEXT | 题目分类 |

### 2.5 study_plans（学习计划）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `title` | TEXT | 计划标题 |
| `category` | TEXT | 分类 |
| `dailyQuestions` | INTEGER | 每日题目数 |
| `isActive` | INTEGER | 是否激活 |
| `startDate` | INTEGER | 开始日期 |
| `endDate` | INTEGER | 结束日期 |

### 2.6 users（用户信息）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `name` | TEXT | 用户名 |
| `avatar` | TEXT | 头像路径 |
| `totalScore` | INTEGER | 总分 |
| `level` | TEXT | 等级 |
| `joinDate` | INTEGER | 注册日期 |

### 2.7 chat_history（AI 对话历史）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `role` | TEXT | 角色（user/assistant/system） |
| `content` | TEXT | 消息内容 |
| `timestamp` | INTEGER | 时间戳 |
| `sessionId` | TEXT | 会话 ID |
| `modelProvider` | TEXT | AI 模型提供商 |

### 2.8 templates（导出模板）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `name` | TEXT | 模板名称 |
| `type` | TEXT | 模板类型（excel/csv/json 等） |
| `content` | TEXT (JSON) | 模板内容定义 |
| `isDefault` | INTEGER | 是否默认模板 |
| `createTime` | INTEGER | 创建时间 |

### 2.9 ocr_history（OCR 识别历史）

**v19 新增表**

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `imagePath` | TEXT | 图片路径 |
| `recognizedText` | TEXT | 识别文字 |
| `language` | TEXT | 识别语言 |
| `createTime` | INTEGER | 创建时间 |

### 2.10 question_images（题目配图）

**v19 新增表**

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `questionId` | INTEGER FK | 关联题目 |
| `imagePath` | TEXT | 图片路径 |
| `imageType` | TEXT | 图片类型 |
| `order` | INTEGER | 排序 |

### 2.11 ai_usage_log（AI 使用日志）

**v19 新增表**

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK AUTO | 主键 |
| `provider` | TEXT | AI 提供商 |
| `action` | TEXT | 操作类型 |
| `promptTokens` | INTEGER | 输入 token 数 |
| `completionTokens` | INTEGER | 输出 token 数 |
| `timestamp` | INTEGER | 时间戳 |

## 三、数据库迁移历史

| 版本 | 变更内容 |
|------|----------|
| v1 ~ v17 | 初始版本与迭代优化 |
| v18 → v19 | 新增 `ocr_history`、`question_images`、`ai_usage_log` 三张表 |
| **v19 → v20** | `questions` 表重大扩展：新增 **18 个字段**（createdAt, updatedAt, source, tags, points, timeLimit, hint, analysis, knowledgePoint, subCategory, usageCount, correctCount, incorrectCount, lastUsedAt, status, isPublic, author, comment）；新增 **4 个索引** |

## 四、DAO 接口

| DAO | 对应表 | 主要操作 |
|-----|--------|---------|
| `QuestionDao` | questions | CRUD, 批量导入, 搜索, 统计 |
| `NoteDao` | notes | CRUD |
| `WrongQuestionDao` | wrong_questions | CRUD, 统计 |
| `ScoreDao` | scores | CRUD, 统计分析 |
| `StudyPlanDao` | study_plans | CRUD |
| `UserDao` | users | CRUD |
| `ChatHistoryDao` | chat_history | CRUD, 按会话查询 |
| `TemplateDao` | templates | CRUD |
| `OCRHistoryDao` | ocr_history | CRUD (v19) |
| `QuestionImageDao` | question_images | CRUD (v19) |
| `AIUsageLogDao` | ai_usage_log | CRUD (v19) |

## 五、数据关系

```
users ──────────────────────────────────┐
    │ 1:N                                │
    ├── scores ──── questions            │
    ├── study_plans                      │
    ├── notes                            │
    ├── wrong_questions ─── questions    │
    ├── chat_history                     │
    └── ai_usage_log                     │
                                        │
questions ──────────────────────────────┐
    │ 1:N                               │
    ├── wrong_questions                 │
    ├── question_images                 │
    └── scores                          │
```

## 六、备份与恢复

- **备份机制**: `BackupManager` + `AutoBackupManager` + `BackupWorker` (WorkManager)
- **备份格式**: JSON 文件（`smartquiz_backup_*.json`）
- **备份内容**: 可选全部表或仅题库
- **恢复**: `BackupManager.restore()`
- **定时备份**: 通过 WorkManager 后台定期执行

---

## 相关文档

- [系统架构设计](../system/system_architecture.md)
- [技术栈详情](../development/tech_stack.md)
- [模块功能设计](../development/module_function_design.md)