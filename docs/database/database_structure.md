# 智能题库应用数据库设计文档

## 1. 数据库概览

**数据库名称**：smartquiz_database
**数据库版本**：18
**使用技术**：Room Persistence Library

**核心功能**：
- 存储用户信息
- 管理题库数据
- 记录答题成绩
- 跟踪学习进度
- 管理错题集和收藏题目

## 2. 表结构设计

### 2.1 用户表 (user)

| 字段名 | 数据类型 | 约束 | 描述 |
|-------|---------|------|------|
| id | INTEGER | PRIMARY KEY, AUTOINCREMENT | 用户ID |
| username | TEXT | NOT NULL | 用户名 |
| email | TEXT | NOT NULL, UNIQUE | 邮箱 |
| phone | TEXT | | 手机号 |
| password | TEXT | NOT NULL | 密码 |
| avatar | TEXT | | 头像路径 |
| isLoggedIn | INTEGER | DEFAULT 0 | 是否登录状态 |

### 2.2 题目表 (question)

| 字段名 | 数据类型 | 约束 | 描述 |
|-------|---------|------|------|
| id | INTEGER | PRIMARY KEY, AUTOINCREMENT | 题目ID |
| questionText | TEXT | NOT NULL | 题目文本 |
| optionA | TEXT | | 选项A |
| optionB | TEXT | | 选项B |
| optionC | TEXT | | 选项C |
| optionD | TEXT | | 选项D |
| correctAnswer | TEXT | NOT NULL | 正确答案 |
| category | TEXT | | 题目分类 |
| difficulty | INTEGER | DEFAULT 1 | 难度等级(1-5) |
| explanation | TEXT | | 答案解析 |
| relatedQuestion | TEXT | | 相关题目 |
| questionType | TEXT | DEFAULT "选择题" | 题目类型 |

### 2.3 成绩历史表 (score_history)

| 字段名 | 数据类型 | 约束 | 描述 |
|-------|---------|------|------|
| id | INTEGER | PRIMARY KEY, AUTOINCREMENT | 成绩ID |
| correctCount | INTEGER | NOT NULL | 正确题数 |
| totalQuestions | INTEGER | NOT NULL | 总题数 |
| score | INTEGER | NOT NULL | 得分 |
| startTime | INTEGER | NOT NULL | 开始时间戳 |
| endTime | INTEGER | NOT NULL | 结束时间戳 |
| category | TEXT | | 题目分类 |
| difficulty | TEXT | | 难度级别 |
| userId | INTEGER | NOT NULL | 用户ID |

### 2.4 学习计划表 (study_plan)

| 字段名 | 数据类型 | 约束 | 描述 |
|-------|---------|------|------|
| id | INTEGER | PRIMARY KEY, AUTOINCREMENT | 计划ID |
| planName | TEXT | NOT NULL | 计划名称 |
| targetQuestions | INTEGER | NOT NULL | 目标题数 |
| completedQuestions | INTEGER | DEFAULT 0 | 已完成题数 |
| startDate | INTEGER | NOT NULL | 开始日期 |
| endDate | INTEGER | NOT NULL | 结束日期 |
| userId | INTEGER | NOT NULL | 用户ID |
| status | TEXT | DEFAULT "进行中" | 计划状态 |

### 2.5 错题表 (wrong_question)

| 字段名 | 数据类型 | 约束 | 描述 |
|-------|---------|------|------|
| id | INTEGER | PRIMARY KEY, AUTOINCREMENT | 错题ID |
| questionId | INTEGER | NOT NULL | 题目ID |
| userId | INTEGER | NOT NULL | 用户ID |
| wrongCount | INTEGER | DEFAULT 1 | 错误次数 |
| lastWrongTime | INTEGER | NOT NULL | 最后错误时间 |
| userAnswer | TEXT | | 用户答案 |

### 2.6 收藏题目表 (favorite_question)

| 字段名 | 数据类型 | 约束 | 描述 |
|-------|---------|------|------|
| id | INTEGER | PRIMARY KEY, AUTOINCREMENT | 收藏ID |
| questionId | INTEGER | NOT NULL | 题目ID |
| userId | INTEGER | NOT NULL | 用户ID |
| favoriteTime | INTEGER | NOT NULL | 收藏时间 |

### 2.7 笔记表 (note)

| 字段名 | 数据类型 | 约束 | 描述 |
|-------|---------|------|------|
| id | INTEGER | PRIMARY KEY, AUTOINCREMENT | 笔记ID |
| title | TEXT | NOT NULL | 笔记标题 |
| content | TEXT | NOT NULL | 笔记内容 |
| createTime | INTEGER | NOT NULL | 创建时间 |
| updateTime | INTEGER | NOT NULL | 更新时间 |
| userId | INTEGER | NOT NULL | 用户ID |
| relatedQuestionId | INTEGER | | 相关题目ID |

### 2.8 聊天历史表 (chat_history)

| 字段名 | 数据类型 | 约束 | 描述 |
|-------|---------|------|------|
| id | INTEGER | PRIMARY KEY, AUTOINCREMENT | 聊天记录ID |
| userId | INTEGER | NOT NULL | 用户ID |
| message | TEXT | NOT NULL | 消息内容 |
| sender | TEXT | NOT NULL | 发送者(user/ai) |
| timestamp | INTEGER | NOT NULL | 消息时间 |

### 2.9 日志表 (log_entry)

| 字段名 | 数据类型 | 约束 | 描述 |
|-------|---------|------|------|
| id | INTEGER | PRIMARY KEY, AUTOINCREMENT | 日志ID |
| userId | INTEGER | | 用户ID |
| action | TEXT | NOT NULL | 操作类型 |
| detail | TEXT | | 操作详情 |
| timestamp | INTEGER | NOT NULL | 操作时间 |
| level | TEXT | DEFAULT "info" | 日志级别 |

## 3. 表关系

### 3.1 关系图

```
用户表 (user)
  |
  +--- 1:N --- 成绩历史表 (score_history)
  |
  +--- 1:N --- 学习计划表 (study_plan)
  |
  +--- 1:N --- 错题表 (wrong_question)
  |
  +--- 1:N --- 收藏题目表 (favorite_question)
  |
  +--- 1:N --- 笔记表 (note)
  |
  +--- 1:N --- 聊天历史表 (chat_history)
  |
  +--- 1:N --- 日志表 (log_entry)

题目表 (question)
  |
  +--- 1:N --- 错题表 (wrong_question)
  |
  +--- 1:N --- 收藏题目表 (favorite_question)
  |
  +--- 1:N --- 笔记表 (note)
```

### 3.2 外键关系

| 子表 | 外键字段 | 父表 | 父表主键 | 说明 |
|------|---------|------|---------|------|
| score_history | userId | user | id | 用户成绩记录 |
| study_plan | userId | user | id | 用户学习计划 |
| wrong_question | userId | user | id | 用户错题记录 |
| wrong_question | questionId | question | id | 错题对应的题目 |
| favorite_question | userId | user | id | 用户收藏记录 |
| favorite_question | questionId | question | id | 收藏的题目 |
| note | userId | user | id | 用户笔记 |
| note | relatedQuestionId | question | id | 笔记关联的题目 |
| chat_history | userId | user | id | 用户聊天记录 |
| log_entry | userId | user | id | 用户操作日志 |

## 4. 数据库操作流程

### 4.1 初始化流程

1. **应用启动**：调用AppDatabase.getDatabase(context)获取数据库实例
2. **数据库创建**：首次启动时自动创建所有表结构
3. **版本迁移**：数据库版本变更时，使用fallbackToDestructiveMigration()策略

### 4.2 题目管理流程

1. **添加题目**：
   - 调用QuestionDao.insert()或insertAll()方法
   - 支持单题添加和批量导入

2. **查询题目**：
   - 按ID查询：getQuestionById()
   - 按分类查询：getQuestionsByCategory()
   - 按难度查询：getQuestionsByDifficulty()
   - 按类型查询：getQuestionsByType()
   - 搜索题目：searchQuestions()
   - 分页查询：getQuestionsByPage()

3. **更新题目**：
   - 调用QuestionDao.update()方法

4. **删除题目**：
   - 调用QuestionDao.deleteQuestion()或deleteAllQuestions()方法

### 4.3 成绩管理流程

1. **记录成绩**：
   - 调用ScoreDao.insert()方法

2. **查询成绩**：
   - 按用户查询：getScoresByUserId()
   - 按分类查询：getScoresByCategory()
   - 按难度查询：getScoresByDifficulty()
   - 获取平均分：getAverageScoreByUserId()
   - 获取最近成绩：getRecentScores()

3. **删除成绩**：
   - 调用ScoreDao.deleteScore()或deleteScoresByUserId()方法

### 4.4 用户管理流程

1. **注册用户**：
   - 调用UserDao.insert()方法

2. **用户登录**：
   - 调用UserDao.login()或getByUsernameAndPassword()方法
   - 登录成功后更新isLoggedIn字段为1

3. **获取用户信息**：
   - 调用UserDao.getById()或getByUsername()方法
   - 获取当前登录用户：getLoggedInUser()

4. **更新用户信息**：
   - 调用UserDao.update()方法

5. **删除用户**：
   - 调用UserDao.deleteUser()方法

### 4.5 学习计划流程

1. **创建计划**：
   - 调用StudyPlanDao.insert()方法

2. **更新计划**：
   - 调用StudyPlanDao.update()方法
   - 更新已完成题数和状态

3. **查询计划**：
   - 按用户查询：getStudyPlansByUserId()
   - 按状态查询：getStudyPlansByStatus()

4. **删除计划**：
   - 调用StudyPlanDao.deleteStudyPlan()方法

### 4.6 错题管理流程

1. **添加错题**：
   - 调用WrongQuestionDao.insert()方法

2. **更新错题**：
   - 调用WrongQuestionDao.update()方法
   - 更新错误次数和最后错误时间

3. **查询错题**：
   - 按用户查询：getWrongQuestionsByUserId()
   - 按题目查询：getWrongQuestionsByQuestionId()

4. **删除错题**：
   - 调用WrongQuestionDao.deleteWrongQuestion()方法

## 5. 数据库优化策略

### 5.1 索引优化

| 表名 | 索引字段 | 索引类型 | 用途 |
|------|---------|----------|------|
| user | username | UNIQUE | 加速用户登录和查询 |
| user | email | UNIQUE | 加速用户登录和查询 |
| question | category | NORMAL | 加速题目分类查询 |
| question | questionType | NORMAL | 加速题目类型筛选 |
| question | difficulty | NORMAL | 加速难度筛选 |
| question | category, questionType | COMPOSITE | 加速组合查询 |
| score_history | userId | NORMAL | 加速用户成绩查询 |
| score_history | endTime | NORMAL | 加速时间排序 |
| score_history | userId, endTime | COMPOSITE | 加速用户成绩时间排序 |
| wrong_question | userId | NORMAL | 加速用户错题查询 |
| wrong_question | questionId | NORMAL | 加速题目错题查询 |
| wrong_question | userId, questionId | UNIQUE | 确保用户错题唯一性 |
| favorite_question | userId | NORMAL | 加速用户收藏查询 |
| favorite_question | questionId | NORMAL | 加速题目收藏查询 |
| favorite_question | userId, questionId | UNIQUE | 确保用户收藏唯一性 |
| note | userId | NORMAL | 加速用户笔记查询 |
| note | relatedQuestionId | NORMAL | 加速题目相关笔记查询 |
| chat_history | userId | NORMAL | 加速用户聊天记录查询 |
| chat_history | timestamp | NORMAL | 加速聊天记录时间排序 |

### 5.2 查询优化

1. **分页查询**：使用LIMIT和OFFSET实现分页，减少内存消耗
2. **按需查询**：只查询必要字段，避免SELECT *
3. **批量操作**：使用批量插入和更新，减少数据库操作次数
4. **缓存机制**：对频繁访问的数据进行内存缓存
5. **预处理语句**：使用Room的@Query注解，生成预编译SQL语句
6. **避免N+1查询**：使用Room的@Relation或自定义查询减少查询次数
7. **索引覆盖**：设计索引覆盖常用查询字段，减少回表操作

### 5.3 事务管理

1. **批量操作**：使用事务确保数据一致性
2. **错误处理**：事务中出现错误时回滚，保证数据完整性
3. **性能优化**：减少事务开启和提交的次数
4. **事务隔离**：合理设置事务隔离级别
5. **批量事务**：将多个相关操作合并到一个事务中

### 5.4 数据库迁移

1. **迁移策略**：
   - 开发环境：使用fallbackToDestructiveMigration()
   - 生产环境：编写具体的迁移脚本

2. **迁移步骤**：
   - 增加版本号
   - 编写Migration类
   - 实现migrate方法
   - 测试迁移过程

3. **迁移示例**：
   ```java
   static final Migration MIGRATION_1_2 = new Migration(1, 2) {
       @Override
       public void migrate(SupportSQLiteDatabase database) {
           // 添加新表
           database.execSQL("CREATE TABLE IF NOT EXISTS `new_table` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT)");
           // 修改现有表
           database.execSQL("ALTER TABLE `old_table` ADD COLUMN `new_column` TEXT");
       }
   };
   ```

### 5.5 数据库备份与恢复

1. **备份策略**：
   - 定期备份：使用WorkManager定期执行备份
   - 手动备份：提供用户手动备份功能
   - 自动备份：在重要操作后自动备份

2. **备份实现**：
   - 使用RoomDatabase的exportSchema功能
   - 复制数据库文件到安全位置
   - 加密备份文件保护数据安全

3. **恢复策略**：
   - 从备份文件恢复
   - 验证恢复数据的完整性
   - 提供恢复确认机制

4. **备份文件格式**：
   - SQLite数据库文件
   - JSON格式
   - 加密压缩文件

### 5.6 性能监控

1. **监控指标**：
   - 查询执行时间
   - 数据库大小
   - 索引使用情况
   - 事务执行次数

2. **监控工具**：
   - Android Studio Profiler
   - SQLite自带的EXPLAIN语句
   - 自定义监控日志

3. **性能优化建议**：
   - 定期分析慢查询
   - 优化索引使用
   - 清理冗余数据
   - 调整数据库参数

## 6. 数据库安全

### 6.1 数据保护

1. **密码加密**：用户密码使用加密存储
2. **敏感数据**：敏感信息（如用户信息）加密处理
3. **权限控制**：限制数据库访问权限

### 6.2 备份与恢复

1. **定期备份**：实现数据库定期备份功能
2. **恢复机制**：提供数据库恢复功能
3. **导出功能**：支持数据导出为JSON、Excel等格式

## 7. 数据库版本管理

### 7.1 版本号规则

- **主版本**：数据库结构重大变更
- **次版本**：添加表或字段
- **修订版本**：修改索引或约束

### 7.2 迁移策略

- **开发环境**：使用fallbackToDestructiveMigration()策略
- **生产环境**：编写具体的迁移脚本

## 8. 总结

本数据库设计文档详细描述了智能题库应用的数据库结构、表关系和操作流程。通过合理的表结构设计和优化策略，确保了应用的数据存储效率和安全性。同时，清晰的操作流程为应用开发提供了明确的指导，有助于提高开发效率和代码质量。

数据库设计充分考虑了应用的功能需求，包括用户管理、题目管理、成绩记录、学习计划、错题管理等核心功能。通过建立合理的表关系和索引，保证了数据查询的效率和数据的一致性。

在实际开发过程中，应根据应用的具体需求和性能要求，对数据库设计进行适当的调整和优化。