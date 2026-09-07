# 数据层设计

> 版本: 1.0 | 更新日期: 2026-08-29 | 分支: feature/agent-local
> 本文件描述数据层：Room 数据库、DAO、Repository、聊天历史存储。

## 一、数据层概览

```
com.oilquiz.app.database     Room 数据库 + DAO
com.oilquiz.app.repository   仓库层（业务数据访问）
com.oilquiz.app.model        数据模型
com.oilquiz.app.ai.db        聊天历史数据库（独立）
```

## 二、Room 数据库（`database`）

### 2.1 AppDatabase

**类**: `com.oilquiz.app.database.AppDatabase`

主数据库，数据库名 `smartquiz_database`，版本 **24**，聚合 10 个 DAO：

| DAO | 对应表 | 职责 |
|-----|--------|------|
| `UserDao` | user | 用户 |
| `QuestionDao` | question | 题目（题库核心） |
| `ScoreDao` | score_history | 得分 |
| `StudyPlanDao` | study_plan | 学习计划 |
| `TemplateDao` | template | 模板 |
| `WrongQuestionDao` | wrong_question | 错题 |
| `FavoriteQuestionDao` | favorite_question | 收藏题目 |
| `NoteDao` | note | 笔记 |
| `ChatHistoryDao` | chat_history | 聊天历史 |
| `LogEntryDao` | log_entry | 日志 |

辅助类：
- `DatabaseManager` — 数据库管理
- `DatabaseUpgradeManager` — 升级管理
- `DatabaseVersionChecker` — 版本检测
- `DatabaseFieldManager` — 字段管理

> **详细的表结构、字段、索引、迁移历史见**：[数据库设计](10-database-design.md)

## 三、Repository 层

仓库层封装业务数据访问，供 ViewModel/UI 层调用：

| Repository | 职责 |
|-----------|------|
| `QuestionRepository` | 题目 CRUD |
| `WrongQuestionRepository` | 错题 |
| `FavoriteQuestionRepository` | 收藏 |
| `ScoreRepository` | 得分 |
| `StatisticsRepository` | 统计 |
| `StudyPlanRepository` | 学习计划 |
| `TemplateRepository` | 模板 |
| `UserRepository` | 用户 |
| `NoteRepository` | 笔记 |
| `ThemeRepository` / `ThemeColorRepository` | 主题 |
| `ImportHistoryRepository` | 导入历史 |

## 四、聊天历史（`ai.db`）

聊天历史数据库独立于主数据库，用于 AI 对话。

| 类 | 职责 |
|-----|------|
| `ChatDatabaseHelper` | 聊天数据库 |
| `ChatRepository` | 聊天历史读写（conversation/message） |

相关数据访问（`ai.util`）：
- `ChatHistoryManager` — 对话历史管理器（`saveConversationSession`/`listConversationSessions`/`deleteConversationSession`）
- `ConversationSession` — 会话（id/title/messages/createdAt/updatedAt）

## 五、数据模型

关键模型类（`com.oilquiz.app.model` + `ai.chat.ChatMessage`）：
- `ChatMessage` — AI 对话消息（含 MessageType/MessageStatus/Attachment/ModelInfo/InferenceProgress 等）

自然语言模型在 `ai.chat.ChatMessage`（13 种 ViewType）与 `ai.model.*`（题目/模型等）。

## 六、WebView 支持（`webview`）

| 类 | 职责 |
|-----|------|
| `WebViewLoadManager` | 加载管理 |
| `RedirectWebViewClient` | 重定向客户端 |
| `FileRedirectManager` | 文件重定向 |
| `FileRedirectRule` | 重定向规则 |

## 相关文档

- [项目架构总览](01-project-overview.md)
- [模块清单](08-module-inventory.md)
- [开发规范](09-development-guide.md)
