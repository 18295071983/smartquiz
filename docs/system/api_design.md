# 智能题库应用API设计文档

## 1. 概述

### 1.1 API设计目标

- 提供统一、规范的接口设计
- 确保API的可扩展性和可维护性
- 支持客户端与服务器之间的高效通信
- 提供清晰的错误处理机制
- 确保API的安全性

### 1.2 API设计原则

- **RESTful设计**：遵循REST架构风格
- **一致性**：统一的命名规范和参数格式
- **安全性**：实现适当的认证和授权机制
- **可扩展性**：支持未来功能的扩展
- **性能优化**：优化API响应时间和资源使用

### 1.3 API版本控制

#### 1.3.1 版本控制策略

- **URL路径版本控制**：使用`/api/v1/`、`/api/v2/`等路径前缀
- **向后兼容**：新版本API应保持对旧版本的兼容
- **版本废弃**：废弃的API应提供至少6个月的过渡期
- **版本通知**：在响应头中添加API版本信息

#### 1.3.2 版本升级流程

1. **需求分析**：分析新功能和变更需求
2. **设计新API**：设计新版本API，保持向后兼容
3. **实现新API**：实现新版本API
4. **测试**：测试新API的功能和兼容性
5. **部署**：部署新版本API
6. **废弃通知**：通知用户旧版本API将被废弃
7. **旧版本下线**：在过渡期后下线旧版本API

#### 1.3.3 版本管理最佳实践

- **语义化版本**：遵循Major.Minor.Patch版本号规则
- **版本文档**：为每个版本提供详细的文档
- **变更日志**：记录每个版本的变更内容
- **兼容性测试**：确保不同版本之间的兼容性

### 1.4 API调用示例

#### 1.4.1 基于Retrofit的调用示例

```java
// 定义API接口
public interface ApiService {
    @GET("/api/v1/questions")
    Call<QuestionListResponse> getQuestions(
            @Query("category") String category,
            @Query("type") String type,
            @Query("page") int page,
            @Query("limit") int limit
    );

    @POST("/api/v1/questions")
    Call<QuestionResponse> addQuestion(@Body QuestionRequest question);

    @POST("/api/v1/users/login")
    Call<LoginResponse> login(@Body LoginRequest loginRequest);
}

// 创建Retrofit实例
Retrofit retrofit = new Retrofit.Builder()
        .baseUrl("https://api.example.com")
        .addConverterFactory(GsonConverterFactory.create())
        .build();

ApiService apiService = retrofit.create(ApiService.class);

// 调用API
Call<QuestionListResponse> call = apiService.getQuestions("数学", "选择题", 1, 20);
call.enqueue(new Callback<QuestionListResponse>() {
    @Override
    public void onResponse(Call<QuestionListResponse> call, Response<QuestionListResponse> response) {
        if (response.isSuccessful()) {
            QuestionListResponse data = response.body();
            // 处理数据
        } else {
            // 处理错误
        }
    }

    @Override
    public void onFailure(Call<QuestionListResponse> call, Throwable t) {
        // 处理网络错误
    }
});
```

#### 1.4.2 基于OkHttp的调用示例

```java
// 创建OkHttpClient
OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build();

// 构建请求
Request request = new Request.Builder()
        .url("https://api.example.com/api/v1/questions?category=数学&type=选择题&page=1&limit=20")
        .get()
        .addHeader("Authorization", "Bearer your_token")
        .build();

// 发送请求
client.newCall(request).enqueue(new Callback() {
    @Override
    public void onResponse(Call call, Response response) throws IOException {
        if (response.isSuccessful()) {
            String responseBody = response.body().string();
            // 解析JSON
        } else {
            // 处理错误
        }
    }

    @Override
    public void onFailure(Call call, IOException e) {
        // 处理网络错误
    }
});
```

#### 1.4.3 基于Kotlin协程的调用示例

```kotlin
// 定义API接口
interface ApiService {
    @GET("/api/v1/questions")
    suspend fun getQuestions(
        @Query("category") category: String,
        @Query("type") type: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int
    ): QuestionListResponse

    @POST("/api/v1/questions")
    suspend fun addQuestion(@Body question: QuestionRequest): QuestionResponse
}

// 创建Retrofit实例
val retrofit = Retrofit.Builder()
    .baseUrl("https://api.example.com")
    .addConverterFactory(GsonConverterFactory.create())
    .build()

val apiService = retrofit.create(ApiService::class.java)

// 调用API
CoroutineScope(Dispatchers.IO).launch {
    try {
        val response = apiService.getQuestions("数学", "选择题", 1, 20)
        // 处理数据
    } catch (e: Exception) {
        // 处理错误
    }
}
```

## 2. 认证与授权

### 2.1 认证方式

- **JWT认证**：使用JSON Web Token进行身份验证
- **Bearer Token**：在请求头中携带token
- **刷新令牌**：支持token刷新机制

### 2.2 授权机制

- **基于角色的访问控制**：不同角色有不同的权限
- **资源级权限**：对特定资源的访问控制
- **权限检查**：在API层面进行权限验证

### 2.3 安全措施

- **HTTPS**：所有API请求使用HTTPS
- **Rate Limiting**：防止API滥用
- **输入验证**：验证所有用户输入
- **防止SQL注入**：使用参数化查询

## 3. API接口设计

### 3.1 用户相关接口

| 接口路径 | 方法 | 模块 | 类型 | 功能描述 | 请求体 (JSON) | 成功响应 (200 OK) |
|---------|------|------|------|----------|--------------|-------------------|
| `/api/v1/users` | `POST` | 用户模块 | `Router` | 用户注册 | `{"username": "...", "email": "...", "password": "..."}` | `{"id": 1, "username": "...", "email": "..."}` |
| `/api/v1/users/login` | `POST` | 用户模块 | `Router` | 用户登录 | `{"email": "...", "password": "..."}` | `{"token": "...", "user": {"id": 1, "username": "..."}}` |
| `/api/v1/users/{id}` | `GET` | 用户模块 | `Router` | 获取用户信息 | N/A | `{"id": 1, "username": "...", "email": "..."}` |
| `/api/v1/users/{id}` | `PUT` | 用户模块 | `Router` | 更新用户信息 | `{"username": "...", "email": "..."}` | `{"id": 1, "username": "...", "email": "..."}` |
| `/api/v1/users/{id}` | `DELETE` | 用户模块 | `Router` | 删除用户 | N/A | `{"status": "success"}` |
| `/api/v1/users/me` | `GET` | 用户模块 | `Router` | 获取当前用户信息 | N/A | `{"id": 1, "username": "...", "email": "..."}` |
| `/api/v1/users/refresh` | `POST` | 用户模块 | `Router` | 刷新令牌 | `{"refreshToken": "..."}` | `{"token": "..."}` |

### 3.2 题目相关接口

| 接口路径 | 方法 | 模块 | 类型 | 功能描述 | 请求体 (JSON) | 成功响应 (200 OK) |
|---------|------|------|------|----------|--------------|-------------------|
| `/api/v1/questions` | `GET` | 题目模块 | `Router` | 获取题目列表 | N/A (查询参数: category, type, page, limit) | `{"total": 100, "page": 1, "limit": 20, "data": [...]}` |
| `/api/v1/questions` | `POST` | 题目模块 | `Router` | 添加题目 | `{"questionText": "...", "optionA": "...", "optionB": "...", "optionC": "...", "optionD": "...", "correctAnswer": "...", "category": "...", "difficulty": 1, "questionType": "..."}` | `{"id": 1, "questionText": "...", ...}` |
| `/api/v1/questions/{id}` | `GET` | 题目模块 | `Router` | 获取题目详情 | N/A | `{"id": 1, "questionText": "...", ...}` |
| `/api/v1/questions/{id}` | `PUT` | 题目模块 | `Router` | 更新题目 | `{"questionText": "...", "optionA": "...", ...}` | `{"id": 1, "questionText": "...", ...}` |
| `/api/v1/questions/{id}` | `DELETE` | 题目模块 | `Router` | 删除题目 | N/A | `{"status": "success"}` |
| `/api/v1/questions/batch` | `POST` | 题目模块 | `Router` | 批量添加题目 | `{"questions": [...]}` | `{"success": 10, "failed": 0}` |
| `/api/v1/questions/categories` | `GET` | 题目模块 | `Router` | 获取题目分类 | N/A | `["数学", "语文", "英语", ...]` |
| `/api/v1/questions/types` | `GET` | 题目模块 | `Router` | 获取题目类型 | N/A | `["选择题", "判断题", "填空题", ...]` |

### 3.3 成绩相关接口

| 接口路径 | 方法 | 模块 | 类型 | 功能描述 | 请求体 (JSON) | 成功响应 (200 OK) |
|---------|------|------|------|----------|--------------|-------------------|
| `/api/v1/scores` | `POST` | 答题模块 | `Router` | 提交成绩 | `{"correctCount": 8, "totalQuestions": 10, "score": 80, "category": "...", "difficulty": "..."}` | `{"id": 1, "score": 80, ...}` |
| `/api/v1/scores` | `GET` | 答题模块 | `Router` | 获取成绩列表 | N/A (查询参数: userId, category, page, limit) | `{"total": 50, "page": 1, "limit": 20, "data": [...]}` |
| `/api/v1/scores/{id}` | `GET` | 答题模块 | `Router` | 获取成绩详情 | N/A | `{"id": 1, "score": 80, ...}` |
| `/api/v1/scores/user/{userId}` | `GET` | 答题模块 | `Router` | 获取用户成绩 | N/A (查询参数: page, limit) | `{"total": 20, "page": 1, "limit": 10, "data": [...]}` |
| `/api/v1/scores/statistics/{userId}` | `GET` | 答题模块 | `Router` | 获取用户成绩统计 | N/A | `{"averageScore": 85, "totalTests": 20, "highestScore": 100, ...}` |

### 3.4 学习计划相关接口

| 接口路径 | 方法 | 模块 | 类型 | 功能描述 | 请求体 (JSON) | 成功响应 (200 OK) |
|---------|------|------|------|----------|--------------|-------------------|
| `/api/v1/study-plans` | `POST` | 学习计划模块 | `Router` | 创建学习计划 | `{"planName": "...", "targetQuestions": 100, "startDate": "2024-01-01", "endDate": "2024-01-31"}` | `{"id": 1, "planName": "...", ...}` |
| `/api/v1/study-plans` | `GET` | 学习计划模块 | `Router` | 获取学习计划列表 | N/A (查询参数: userId, status, page, limit) | `{"total": 10, "page": 1, "limit": 10, "data": [...]}` |
| `/api/v1/study-plans/{id}` | `GET` | 学习计划模块 | `Router` | 获取学习计划详情 | N/A | `{"id": 1, "planName": "...", ...}` |
| `/api/v1/study-plans/{id}` | `PUT` | 学习计划模块 | `Router` | 更新学习计划 | `{"planName": "...", "targetQuestions": 150, ...}` | `{"id": 1, "planName": "...", ...}` |
| `/api/v1/study-plans/{id}` | `DELETE` | 学习计划模块 | `Router` | 删除学习计划 | N/A | `{"status": "success"}` |
| `/api/v1/study-plans/{id}/progress` | `PUT` | 学习计划模块 | `Router` | 更新学习进度 | `{"completedQuestions": 50}` | `{"id": 1, "completedQuestions": 50, ...}` |

### 3.5 错题相关接口

| 接口路径 | 方法 | 模块 | 类型 | 功能描述 | 请求体 (JSON) | 成功响应 (200 OK) |
|---------|------|------|------|----------|--------------|-------------------|
| `/api/v1/wrong-questions` | `POST` | 错题模块 | `Router` | 添加错题 | `{"questionId": 1, "userAnswer": "A"}` | `{"id": 1, "questionId": 1, ...}` |
| `/api/v1/wrong-questions` | `GET` | 错题模块 | `Router` | 获取错题列表 | N/A (查询参数: userId, page, limit) | `{"total": 20, "page": 1, "limit": 10, "data": [...]}` |
| `/api/v1/wrong-questions/{id}` | `DELETE` | 错题模块 | `Router` | 删除错题 | N/A | `{"status": "success"}` |
| `/api/v1/wrong-questions/user/{userId}` | `GET` | 错题模块 | `Router` | 获取用户错题 | N/A (查询参数: page, limit) | `{"total": 15, "page": 1, "limit": 10, "data": [...]}` |
| `/api/v1/wrong-questions/export/{userId}` | `GET` | 错题模块 | `Router` | 导出错题 | N/A (查询参数: format) | 文件下载 |

### 3.6 笔记相关接口

| 接口路径 | 方法 | 模块 | 类型 | 功能描述 | 请求体 (JSON) | 成功响应 (200 OK) |
|---------|------|------|------|----------|--------------|-------------------|
| `/api/v1/notes` | `POST` | 笔记模块 | `Router` | 创建笔记 | `{"title": "...", "content": "...", "relatedQuestionId": 1}` | `{"id": 1, "title": "...", ...}` |
| `/api/v1/notes` | `GET` | 笔记模块 | `Router` | 获取笔记列表 | N/A (查询参数: userId, page, limit) | `{"total": 30, "page": 1, "limit": 10, "data": [...]}` |
| `/api/v1/notes/{id}` | `GET` | 笔记模块 | `Router` | 获取笔记详情 | N/A | `{"id": 1, "title": "...", ...}` |
| `/api/v1/notes/{id}` | `PUT` | 笔记模块 | `Router` | 更新笔记 | `{"title": "...", "content": "..."}` | `{"id": 1, "title": "...", ...}` |
| `/api/v1/notes/{id}` | `DELETE` | 笔记模块 | `Router` | 删除笔记 | N/A | `{"status": "success"}` |
| `/api/v1/notes/user/{userId}` | `GET` | 笔记模块 | `Router` | 获取用户笔记 | N/A (查询参数: page, limit) | `{"total": 25, "page": 1, "limit": 10, "data": [...]}` |

### 3.7 AI相关接口

| 接口路径 | 方法 | 模块 | 类型 | 功能描述 | 请求体 (JSON) | 成功响应 (200 OK) |
|---------|------|------|------|----------|--------------|-------------------|
| `/api/v1/ai/chat` | `POST` | AI模块 | `Router` | AI聊天 | `{"message": "..."}` | `{"response": "..."}` |
| `/api/v1/ai/analysis/{userId}` | `GET` | AI模块 | `Router` | 学习分析 | N/A | `{"strengths": [...], "weaknesses": [...], "suggestions": [...]}` |
| `/api/v1/ai/questions` | `POST` | AI模块 | `Router` | 生成题目 | `{"topic": "...", "count": 5, "difficulty": "medium"}` | `{"questions": [...]}` |
| `/api/v1/ai/feedback` | `POST` | AI模块 | `Router` | 提交反馈 | `{"questionId": 1, "feedback": "..."}` | `{"status": "success"}` |

### 3.8 导出相关接口

| 接口路径 | 方法 | 模块 | 类型 | 功能描述 | 请求体 (JSON) | 成功响应 (200 OK) |
|---------|------|------|------|----------|--------------|-------------------|
| `/api/v1/export/questions` | `POST` | 导出模块 | `Router` | 导出题目 | `{"format": "excel", "category": "...", "count": 100}` | 文件下载 |
| `/api/v1/export/scores` | `POST` | 导出模块 | `Router` | 导出成绩 | `{"format": "pdf", "userId": 1, "startDate": "2024-01-01", "endDate": "2024-01-31"}` | 文件下载 |
| `/api/v1/export/study-plans` | `POST` | 导出模块 | `Router` | 导出学习计划 | `{"format": "csv", "userId": 1}` | 文件下载 |

### 3.9 系统相关接口

| 接口路径 | 方法 | 模块 | 类型 | 功能描述 | 请求体 (JSON) | 成功响应 (200 OK) |
|---------|------|------|------|----------|--------------|-------------------|
| `/api/v1/system/version` | `GET` | 系统模块 | `Router` | 获取系统版本 | N/A | `{"version": "1.0.0", "build": 100}` |
| `/api/v1/system/status` | `GET` | 系统模块 | `Router` | 获取系统状态 | N/A | `{"status": "ok", "uptime": "100h"}` |
| `/api/v1/system/config` | `GET` | 系统模块 | `Router` | 获取系统配置 | N/A | `{"features": [...], "limits": {...}}` |
| `/api/v1/system/logs` | `GET` | 系统模块 | `Router` | 获取系统日志 | N/A (查询参数: level, days) | `{"logs": [...]}` |

## 4. 请求与响应格式

### 4.1 请求格式

- **Content-Type**: `application/json`
- **Authorization**: `Bearer {token}` (需要认证的接口)
- **查询参数**: 用于过滤、分页等
- **路径参数**: 用于指定资源ID

### 4.2 响应格式

#### 4.2.1 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    // 响应数据
  }
}
```

#### 4.2.2 分页响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 100,
    "page": 1,
    "limit": 20,
    "data": [
      // 数据列表
    ]
  }
}
```

#### 4.2.3 错误响应

```json
{
  "code": 400,
  "message": "错误信息",
  "errors": [
    {
      "field": "email",
      "message": "邮箱格式不正确"
    }
  ]
}
```

## 5. 错误处理

### 5.1 错误码定义

| 错误码 | 描述 | HTTP状态码 |
|--------|------|------------|
| 200 | 成功 | 200 OK |
| 400 | 请求参数错误 | 400 Bad Request |
| 401 | 未授权 | 401 Unauthorized |
| 403 | 禁止访问 | 403 Forbidden |
| 404 | 资源不存在 | 404 Not Found |
| 405 | 方法不允许 | 405 Method Not Allowed |
| 429 | 请求过于频繁 | 429 Too Many Requests |
| 500 | 服务器内部错误 | 500 Internal Server Error |
| 503 | 服务不可用 | 503 Service Unavailable |

### 5.2 错误处理流程

1. 客户端发送请求
2. 服务器验证请求参数
3. 服务器处理业务逻辑
4. 发生错误时，返回错误响应
5. 客户端根据错误码和错误信息进行处理

### 5.3 常见错误处理

- **参数验证错误**：返回400错误，包含具体的字段错误信息
- **认证错误**：返回401错误，提示用户重新登录
- **授权错误**：返回403错误，提示用户没有权限
- **资源不存在**：返回404错误，提示资源不存在
- **服务器错误**：返回500错误，提示服务器内部错误

## 6. 性能优化

### 6.1 API性能优化

- **缓存策略**：对频繁访问的数据进行缓存
- **分页机制**：使用分页减少数据传输量
- **批量操作**：支持批量添加、更新操作
- **异步处理**：对耗时操作使用异步处理
- **压缩传输**：使用gzip压缩数据传输

### 6.2 数据库优化

- **索引**：为常用查询字段添加索引
- **查询优化**：优化SQL查询语句
- **连接池**：使用数据库连接池
- **事务管理**：合理使用事务

### 6.3 服务器优化

- **负载均衡**：使用负载均衡分发请求
- **水平扩展**：支持服务器水平扩展
- **监控**：实时监控API性能
- **自动扩缩容**：根据负载自动调整资源

## 7. 部署与维护

### 7.1 部署策略

- **容器化**：使用Docker容器部署
- **CI/CD**：实现持续集成和持续部署
- **环境隔离**：开发、测试、生产环境隔离
- **配置管理**：使用配置中心管理配置

### 7.2 监控与告警

- **API监控**：监控API响应时间和错误率
- **服务器监控**：监控服务器资源使用情况
- **数据库监控**：监控数据库性能和状态
- **告警机制**：设置阈值触发告警

### 7.3 日志管理

- **结构化日志**：使用JSON格式记录日志
- **日志级别**：根据重要性设置不同的日志级别
- **日志存储**：使用ELK或类似系统存储和分析日志
- **日志清理**：定期清理过期日志

## 8. 安全考虑

### 8.1 认证与授权

- **JWT安全**：使用安全的密钥和算法
- **Token过期**：设置合理的token过期时间
- **刷新机制**：实现安全的token刷新机制
- **权限控制**：细粒度的权限控制

### 8.2 数据安全

- **数据加密**：敏感数据加密存储
- **传输加密**：使用HTTPS加密传输
- **输入验证**：验证所有用户输入
- **防止SQL注入**：使用参数化查询

### 8.3 防护措施

- **CSRF防护**：实现CSRF令牌
- **XSS防护**：防止跨站脚本攻击
- **Rate Limiting**：防止API滥用
- **IP封禁**：封禁恶意IP

## 9. 文档与测试

### 9.1 API文档

- **Swagger/OpenAPI**：使用Swagger生成API文档
- **示例代码**：提供各语言的示例代码
- **使用指南**：详细的API使用指南
- **版本历史**：记录API版本变更

### 9.2 API测试

- **单元测试**：测试API的各个功能点
- **集成测试**：测试API与其他服务的集成
- **性能测试**：测试API的性能和响应时间
- **安全测试**：测试API的安全性

### 9.3 测试工具

- **Postman**：手动测试API
- **JMeter**：性能测试
- **Newman**：自动化测试
- **CI/CD集成**：在CI/CD流程中集成API测试

## 10. 总结

本API设计文档详细描述了智能题库应用的API接口设计，包括认证授权、接口设计、请求响应格式、错误处理、性能优化、部署维护和安全考虑等方面。

通过遵循本文档的设计规范，可以确保API的一致性、安全性和可扩展性。同时，本文档也为前端开发和后端开发提供了明确的接口规范，便于团队协作和代码维护。

随着应用的发展和功能的扩展，API设计也需要不断调整和优化，以适应新的需求和技术趋势。