# 答题宝 AI 模型用量统计与费用计算系统 - 开发文档

## 一、项目概述

### 1.1 系统定位

答题宝是一款集成本地 LLM 推理与 Agent 智能代理的 Android 学习平台。本系统为核心应用增加一套 **AI 模型用量统计与费用计算系统**，实现：

- **配置即代码**：不修改代码，通过配置文件管理一切，实现热更新能力
- **全链路可观测性**：从 App → 路由层 → 服务商 API → 解析器 → 数据库，端到端追踪
- **企业级可扩展性**：新增服务商只需 JSON 配置，零代码接入

### 1.2 核心目标

| 目标 | 说明 |
|------|------|
| 价格数据远程配置 | Gitee 托管 JSON，应用启动自动同步，无需发版 |
| 服务商动态注册 | 配置即服务商，API 响应结构配置化 |
| 用量实时统计 | 输入/输出/缓存 Token 实时统计，本地费用计算 |
| 可视化统计界面 | 汇总卡片、趋势图、模型分布、历史记录 |
| 高可用降级 | Gitee 不可用时使用本地缓存，核心功能不受影响 |

---

## 二、系统架构

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                        Android App                          │
├─────────────────────────────────────────────────────────────┤
│  UI 层                                                        │
│  ├── ChatBottomBar (用量实时展示)                              │
│  ├── StatisticsPage (统计页面)                                 │
│  └── ModelSelector (动态模型选择器)                            │
├─────────────────────────────────────────────────────────────┤
│  业务逻辑层                                                     │
│  ├── UsageCalculator (费用计算器)                              │
│  ├── TraceManager (全链路追踪管理器)                            │
│  ├── ConfigSyncManager (配置同步管理器)                         │
│  └── ParserRouter (解析器路由器)                               │
├─────────────────────────────────────────────────────────────┤
│  数据层                                                        │
│  ├── ConfigRepository (配置仓储)                               │
│  ├── UsageRepository (用量日志仓储)                             │
│  └── Room Database                                           │
├─────────────────────────────────────────────────────────────┤
│  配置源                                                        │
│  ├── Gitee Remote (远程配置)                                   │
│  ├── Local Cache (本地缓存)                                    │
│  └── Assets Default (内置默认配置)                              │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 核心模块职责

| 模块 | 职责 | 实现方式 |
|------|------|----------|
| **ConfigSyncManager** | 配置同步与版本管理 | 启动时拉取 + WorkManager 定时同步 |
| **ParserRouter** | 解析器路由与动态注册 | 根据 modelId 匹配 providerId → 选择解析器 |
| **UsageCalculator** | Token 用量统计与费用计算 | 本地查询单价 → (tokens/1,000,000) × price_per_1m |
| **TraceManager** | 全链路追踪与监控 | 生成 traceId，贯穿全链路记录 Span |
| **ConfigRepository** | 配置数据持久化 | Room 数据库读写 |
| **UsageRepository** | 用量日志持久化 | Room 数据库读写 |

---

## 三、数据模型设计

### 3.1 数据库表结构

#### 3.1.1 api_provider_config (服务商配置表)

```kotlin
@Entity(tableName = "api_provider_config")
data class ApiProviderConfig(
    @PrimaryKey val providerId: String,          // 服务商唯一标识
    val displayName: String,                      // 显示名称
    val apiBase: String,                          // API 基础地址
    val authType: String,                         // 认证类型: Bearer/ApiKey/None
    val authHeaderName: String,                   // 认证 Header 名称
    val isActive: Boolean = true,                 // 是否启用
    val configVersion: Int = 1,                   // 配置版本
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```

#### 3.1.2 api_price_config (模型价格配置表)

```kotlin
@Entity(
    tableName = "api_price_config",
    uniqueConstraints = UniqueConstraint(value = ["modelId", "priceType", "contextLength"])
)
data class ApiPriceConfig(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val modelId: String,                          // 模型标识
    val providerId: String,                       // 关联服务商
    val priceType: String,                        // 价格类型: input/output/cache_hit/cache_create
    val pricePer1M: Double,                       // 每百万 Token 价格 (CNY)
    val currency: String = "CNY",                // 货币单位
    val minContext: Int = 0,                      // 最小上下文长度
    val maxContext: Int = 128000,                 // 最大上下文长度
    val displayName: String,                      // 模型显示名称
    val contextWindow: Int = 128000,              // 上下文窗口大小
    val isActive: Boolean = true,
    val syncTime: Long = System.currentTimeMillis()
)
```

#### 3.1.3 api_usage_log (用量日志表)

```kotlin
@Entity(
    tableName = "api_usage_log",
    indices = [Index(value = ["userId", "callTime"])]
)
data class ApiUsageLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val traceId: String,                          // 全链路追踪 ID
    val sessionId: String,                        // 会话 ID
    val userId: String,                           // 用户 ID
    val modelId: String,                          // 模型标识
    val providerId: String,                       // 服务商标识
    val inputTokens: Int = 0,                     // 输入 Token 数
    val outputTokens: Int = 0,                    // 输出 Token 数
    val cacheHitTokens: Int = 0,                  // 缓存命中 Token 数
    val cacheCreateTokens: Int = 0,               // 缓存创建 Token 数
    val totalTokens: Int = 0,                     // 总 Token 数
    val inputCost: Double = 0.0,                  // 输入费用
    val outputCost: Double = 0.0,                 // 输出费用
    val cacheCost: Double = 0.0,                  // 缓存费用
    val totalCost: Double = 0.0,                  // 总费用
    val cacheSavedCost: Double = 0.0,             // 缓存节省费用
    val status: String = "success",               // success/error/timeout
    val errorMessage: String? = null,             // 错误信息
    val duration: Long = 0,                       // 调用耗时 (ms)
    val callTime: Long = System.currentTimeMillis(), // 调用时间
    val parentId: String? = null                  // Agent 链式调用的父 Span
)
```

#### 3.1.4 api_response_schema (响应格式配置表)

```kotlin
@Entity(
    tableName = "api_response_schema",
    uniqueConstraints = UniqueConstraint(value = ["providerId", "modelId"])
)
data class ApiResponseSchema(
    @PrimaryKey val schemaId: String,             // 配置唯一标识
    val providerId: String,                       // 关联服务商
    val modelId: String,                          // 关联模型 (空表示通用)
    val rootPath: String,                         // usage 根路径: "usage"
    val inputTokenPath: String,                   // 输入 Token 路径: "prompt_tokens"
    val outputTokenPath: String,                  // 输出 Token 路径: "completion_tokens"
    val cacheHitTokenPath: String? = null,        // 缓存命中路径: "cache_hits" (可选)
    val cacheCreateTokenPath: String? = null,     // 缓存创建路径: "cache_creations" (可选)
    val createdAt: Long = System.currentTimeMillis()
)
```

#### 3.1.5 sync_log (同步日志表)

```kotlin
@Entity(tableName = "sync_log")
data class SyncLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val syncType: String,                         // startup/scheduled/manual
    val remoteVersion: Int,                       // 远程版本号
    val localVersion: Int,                        // 本地版本号
    val syncStatus: String,                       // success/failed/unchanged
    val errorMsg: String? = null,
    val syncTime: Long = System.currentTimeMillis()
)
```

### 3.2 全链路追踪模型

```kotlin
// 全链路追踪数据模型
data class AITrace(
    val traceId: String,                          // 全局唯一 ID (UUID)
    val sessionId: String,                        // 会话 ID
    val userId: String,                           // 用户 ID
    val parentSpanId: String? = null,             // 父级 Span (用于 Agent 链式调用)
    val spans: List<AISpan> = emptyList()         // 各阶段耗时
)

data class AISpan(
    val spanId: String,                           // 阶段 ID
    val name: String,                             // 阶段名称: "route.resolve", "api.call", "usage.parse"
    val startTime: Long,
    val endTime: Long,
    val duration: Long,
    val attributes: Map<String, String> = emptyMap(), // 附加属性: model, provider, status
    val error: String? = null                     // 错误信息
)
```

---

## 四、配置文件设计

### 4.1 配置文件结构

配置文件托管在 Gitee 仓库，JSON 格式，示例：

```json
{
  "configVersion": 2,
  "lastUpdated": "2026-08-11T00:00:00Z",
  "providers": [
    {
      "providerId": "deepseek",
      "displayName": "DeepSeek",
      "apiBase": "https://api.deepseek.com",
      "authType": "Bearer",
      "authHeaderName": "Authorization"
    },
    {
      "providerId": "qwen",
      "displayName": "通义千问",
      "apiBase": "https://dashscope.aliyuncs.com",
      "authType": "Bearer",
      "authHeaderName": "Authorization"
    },
    {
      "providerId": "local",
      "displayName": "本地模型",
      "apiBase": "http://127.0.0.1:8000",
      "authType": "None",
      "authHeaderName": ""
    }
  ],
  "responseSchemas": [
    {
      "schemaId": "deepseek-chat",
      "providerId": "deepseek",
      "modelId": "deepseek-chat",
      "rootPath": "usage",
      "inputTokenPath": "prompt_tokens",
      "outputTokenPath": "completion_tokens",
      "cacheHitTokenPath": "cache_hits",
      "cacheCreateTokenPath": "cache_creations"
    },
    {
      "schemaId": "deepseek-reasoner",
      "providerId": "deepseek",
      "modelId": "deepseek-reasoner",
      "rootPath": "usage",
      "inputTokenPath": "prompt_tokens",
      "outputTokenPath": "completion_tokens",
      "cacheHitTokenPath": "cache_hits",
      "cacheCreateTokenPath": "cache_creations"
    },
    {
      "schemaId": "qwen-plus",
      "providerId": "qwen",
      "modelId": "qwen-plus",
      "rootPath": "usage",
      "inputTokenPath": "input_tokens",
      "outputTokenPath": "output_tokens",
      "cacheHitTokenPath": null,
      "cacheCreateTokenPath": null
    }
  ],
  "models": [
    {
      "modelId": "deepseek-chat",
      "providerId": "deepseek",
      "displayName": "DeepSeek Chat",
      "contextWindow": 64000
    },
    {
      "modelId": "deepseek-reasoner",
      "providerId": "deepseek",
      "displayName": "DeepSeek Reasoner",
      "contextWindow": 64000
    },
    {
      "modelId": "qwen-plus",
      "providerId": "qwen",
      "displayName": "通义千问 Plus",
      "contextWindow": 128000
    },
    {
      "modelId": "local-llama-3",
      "providerId": "local",
      "displayName": "本地 Llama 3",
      "contextWindow": 8192
    }
  ],
  "prices": [
    {
      "modelId": "deepseek-chat",
      "providerId": "deepseek",
      "priceType": "input",
      "pricePer1M": 0.14,
      "currency": "CNY",
      "minContext": 1,
      "maxContext": 64000
    },
    {
      "modelId": "deepseek-chat",
      "providerId": "deepseek",
      "priceType": "output",
      "pricePer1M": 0.28,
      "currency": "CNY",
      "minContext": 1,
      "maxContext": 64000
    },
    {
      "modelId": "deepseek-chat",
      "providerId": "deepseek",
      "priceType": "cache_hit",
      "pricePer1M": 0.02,
      "currency": "CNY",
      "minContext": 1,
      "maxContext": 64000
    },
    {
      "modelId": "deepseek-reasoner",
      "providerId": "deepseek",
      "priceType": "input",
      "pricePer1M": 0.14,
      "currency": "CNY",
      "minContext": 1,
      "maxContext": 64000
    },
    {
      "modelId": "deepseek-reasoner",
      "providerId": "deepseek",
      "priceType": "output",
      "pricePer1M": 0.28,
      "currency": "CNY",
      "minContext": 1,
      "maxContext": 64000
    },
    {
      "modelId": "deepseek-reasoner",
      "providerId": "deepseek",
      "priceType": "cache_hit",
      "pricePer1M": 0.02,
      "currency": "CNY",
      "minContext": 1,
      "maxContext": 64000
    },
    {
      "modelId": "qwen-plus",
      "providerId": "qwen",
      "priceType": "input",
      "pricePer1M": 0.04,
      "currency": "CNY",
      "minContext": 1,
      "maxContext": 128000
    },
    {
      "modelId": "qwen-plus",
      "providerId": "qwen",
      "priceType": "output",
      "pricePer1M": 0.04,
      "currency": "CNY",
      "minContext": 1,
      "maxContext": 128000
    }
  ]
}
```

### 4.2 配置字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| configVersion | Int | 是 | 配置版本号，变更时递增 |
| lastUpdated | String | 是 | 最后更新时间 (ISO 8601) |
| providers | Array | 是 | 服务商列表 |
| providers[].providerId | String | 是 | 服务商唯一标识 |
| providers[].displayName | String | 是 | 显示名称 |
| providers[].apiBase | String | 是 | API 基础地址 |
| providers[].authType | String | 是 | 认证类型: Bearer/ApiKey/None |
| providers[].authHeaderName | String | 是 | 认证 Header 名称 |
| responseSchemas | Array | 是 | 响应格式配置 |
| responseSchemas[].schemaId | String | 是 | 配置唯一标识 |
| responseSchemas[].providerId | String | 是 | 关联服务商 |
| responseSchemas[].modelId | String | 否 | 关联模型 (空表示通用) |
| responseSchemas[].rootPath | String | 是 | usage 根路径 |
| responseSchemas[].inputTokenPath | String | 是 | 输入 Token 路径 |
| responseSchemas[].outputTokenPath | String | 是 | 输出 Token 路径 |
| responseSchemas[].cacheHitTokenPath | String? | 否 | 缓存命中路径 |
| responseSchemas[].cacheCreateTokenPath | String? | 否 | 缓存创建路径 |
| models | Array | 是 | 模型列表 |
| prices | Array | 是 | 价格配置列表 |

---

## 五、核心流程设计

### 5.1 配置同步流程

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  应用启动/定时 │────▶│  拉取远程配置 │────▶│  版本号比对   │
│  (WorkManager)│     │  (Gitee)     │     │  (Version)   │
└──────────────┘     └──────────────┘     └──────┬───────┘
                                                 │
                              ┌──────────────────┼──────────────────┐
                              │                  │                  │
                          ▼ 有更新           ▼ 无更新          ▼ 拉取失败
                       ┌──────────┐      ┌──────────┐      ┌──────────┐
                       │  解析 JSON │      │ 使用缓存  │      │ 使用内置  │
                       │  写入数据库│      │ 不更新   │      │ 默认配置  │
                       └──────────┘      └──────────┘      └──────────┘
```

#### 同步策略

| 时机 | 方式 | 说明 |
|------|------|------|
| 应用启动 | 同步拉取 | 阻塞式，确保配置可用后再启动业务 |
| 每 6 小时 | WorkManager 定时 | 后台拉取，不阻塞主线程 |
| 手动触发 | 用户操作 | 服务商管理界面手动同步 |

#### 降级策略

```
优先级 1: Gitee 远程配置 (最新)
优先级 2: 本地缓存配置 (上次成功同步)
优先级 3: Assets 内置默认配置 (兜底)
```

### 5.2 API 调用与用量统计流程

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  用户发送消息 │────▶│  生成 TraceID │────▶│  路由决策    │
│              │     │  (UUID)       │     │  model→provider│
└──────────────┘     └──────────────┘     └──────┬───────┘
                                                 │
                              ┌──────────────────┼──────────────────┐
                              ▼                  ▼                  ▼
                       ┌──────────┐      ┌──────────┐      ┌──────────┐
                       │  调用 API │      │  解析用量 │      │  计算费用 │
                       │ (Span)    │      │ (Span)    │      │ (Span)    │
                       └──────────┘      └──────────┘      └──────────┘
                              │                  │                  │
                              └──────────────────┼──────────────────┘
                                                 ▼
                                         ┌──────────────┐
                                         │  写入用量日志 │
                                         │  + 全链路追踪 │
                                         └──────────────┘
```

#### 各阶段 Span 记录

| 阶段 | Span Name | 属性 |
|------|-----------|------|
| 路由决策 | `route.resolve` | model, provider, duration |
| API 调用 | `api.call` | model, provider, status, duration |
| 用量解析 | `usage.parse` | provider, status, duration |
| 费用计算 | `cost.calculate` | model, currency, totalCost |

### 5.3 费用计算流程

```kotlin
// 费用计算逻辑
fun calculateCost(usage: ApiUsageLog): ApiUsageLog {
    // 1. 根据 modelId + priceType 查询单价
    val inputPrice = priceRepository.findBy(modelId, "input")
    val outputPrice = priceRepository.findBy(modelId, "output")
    val cacheHitPrice = priceRepository.findBy(modelId, "cache_hit")
    
    // 2. 计算各项费用
    val inputCost = (usage.inputTokens / 1_000_000.0) * inputPrice.pricePer1M
    val outputCost = (usage.outputTokens / 1_000_000.0) * outputPrice.pricePer1M
    val cacheCost = (usage.cacheHitTokens / 1_000_000.0) * cacheHitPrice.pricePer1M
    
    // 3. 计算缓存节省费用 (未命中缓存时应支付的费用 - 实际支付费用)
    val originalInputCost = (usage.cacheHitTokens / 1_000_000.0) * inputPrice.pricePer1M
    val cacheSavedCost = originalInputCost - cacheCost
    
    return usage.copy(
        inputCost = inputCost,
        outputCost = outputCost,
        cacheCost = cacheCost,
        totalCost = inputCost + outputCost + cacheCost,
        cacheSavedCost = cacheSavedCost
    )
}
```

### 5.4 动态解析器路由流程

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│ API 响应 JSON │────▶│ 解析器路由器  │────▶│ 匹配 schema  │
│              │     │ (ParserRouter)│     │ 配置         │
└──────────────┘     └──────────────┘     └──────┬───────┘
                                                 │
                              ┌──────────────────┼──────────────────┐
                              ▼                  ▼                  ▼
                       ┌──────────┐      ┌──────────┐      ┌──────────┐
                       │  按 root │      │  按路径   │      │  返回   │
                       │  Path   │────▶│ 提取 Token │────▶│ 用量对象  │
                       │  定位   │      │  数值      │      │         │
                       └──────────┘      └──────────┘      └──────────┘
```

#### 解析器实现要点

```kotlin
class ConfigurableUsageParser(
    private val schemaRepository: SchemaRepository
) {
    fun parse(response: JsonObject, modelId: String, providerId: String): UsageData {
        // 1. 查找响应 schema
        val schema = schemaRepository.findBy(modelId, providerId)
            ?: schemaRepository.findByGeneric(providerId)
        
        // 2. 按配置路径提取 Token 数值
        val rootPath = schema.rootPath
        val inputPath = schema.inputTokenPath
        val outputPath = schema.outputTokenPath
        
        val inputTokens = jsonExtractor.getLong(response, rootPath, inputPath)
        val outputTokens = jsonExtractor.getLong(response, rootPath, outputPath)
        val cacheHitTokens = schema.cacheHitTokenPath?.let {
            jsonExtractor.getLong(response, rootPath, it) ?: 0L
        } ?: 0L
        val cacheCreateTokens = schema.cacheCreateTokenPath?.let {
            jsonExtractor.getLong(response, rootPath, it) ?: 0L
        } ?: 0L
        
        return UsageData(
            inputTokens = inputTokens.toInt(),
            outputTokens = outputTokens.toInt(),
            cacheHitTokens = cacheHitTokens.toInt(),
            cacheCreateTokens = cacheCreateTokens.toInt()
        )
    }
}
```

---

## 六、界面设计

### 6.1 聊天界面底部用量展示

```
┌─────────────────────────────────────────────┐
│  ┌─────────────────────────────────────┐    │
│  │ 💡 Token: 输入 1,200 | 输出 800     │    │
│  │    缓存: 500 | 费用: ¥0.0005        │    │
│  └─────────────────────────────────────┘    │
└─────────────────────────────────────────────┘
```

**设计要点：**
- 显示在每条 AI 回复消息底部
- 默认收起，点击可展开详细统计
- 实时显示，费用精确到小数点后 6 位
- 缓存命中时显示节省费用标签

### 6.2 统计页面

```
┌─────────────────────────────────────────────┐
│  📊 用量统计                                │
├─────────────────────────────────────────────┤
│  ┌────────┐ ┌────────┐ ┌────────┐ ┌───────┐ │
│  │ 总Token│ │ 总费用 │ │ 消息数 │ │缓存节省│ │
│  │ 1.2M  │ │ ¥0.85  │ │  42    │ │ ¥0.12 │ │
│  └────────┘ └────────┘ └────────┘ └───────┘ │
├─────────────────────────────────────────────┤
│  📈 最近 7 天用量趋势                        │
│  │          ╭─╮                              │
│  │    ╭────╯ ╰─╮                            │
│  │  ─╯       ╰─────╮                        │
│  │  └──────────────┘                        │
│  │  周一  周二  周三  周四  周五              │
├─────────────────────────────────────────────┤
│  🧩 模型分布                                 │
│  ┌─────────────────────────────┐            │
│  │ ● DeepSeek Chat    65%     │            │
│  │   ████████████████          │            │
│  │ ● 通义千问 Plus  30%        │            │
│  │   ████████                    │            │
│  │ ● 本地模型         5%       │            │
│  │   ███                          │            │
│  └─────────────────────────────┘            │
├─────────────────────────────────────────────┤
│  📋 调用历史记录                             │
│  ┌─────────────────────────────────────┐    │
│  │ 🕐 今天 14:30                        │    │
│  │    DeepSeek Chat | 输入 500 输出 200 │    │
│  │    费用: ¥0.0003  状态: ✓            │    │
│  ├─────────────────────────────────────┤    │
│  │ 🕐 今天 14:25                        │    │
│  │    通义千问 Plus | 输入 1200 输出 800│    │
│  │    费用: ¥0.0001  状态: ✓            │    │
│  └─────────────────────────────────────┘    │
│            [加载更多...]                      │
└─────────────────────────────────────────────┘
```

### 6.3 模型选择器

按服务商分组动态渲染，配置更新后自动刷新。

```
┌─────────────────────────────────────────────┐
│  选择模型                                     │
├─────────────────────────────────────────────┤
│  🔵 DeepSeek                                │
│  ┌─────────────────────────────────────┐    │
│  │ ● DeepSeek Chat          ✓ 已选     │    │
│  │ ○ DeepSeek Reasoner                 │    │
│  └─────────────────────────────────────┘    │
├─────────────────────────────────────────────┤
│  🟢 通义千问                                │
│  ┌─────────────────────────────────────┐    │
│  │ ○ 通义千问 Plus                     │    │
│  │ ○ 通义千问 Max                      │    │
│  └─────────────────────────────────────┘    │
├─────────────────────────────────────────────┤
│  ⚪ 本地模型                                │
│  ┌─────────────────────────────────────┐    │
│  │ ○ 本地 Llama 3                      │    │
│  └─────────────────────────────────────┘    │
└─────────────────────────────────────────────┘
```

### 6.4 服务商管理

```
┌─────────────────────────────────────────────┐
│  ⚙️ 服务商管理                         ↻ 同步│
├─────────────────────────────────────────────┤
│  当前配置版本: v2 (2026-08-11)               │
│  上次同步: 今天 10:30                        │
├─────────────────────────────────────────────┤
│  🔵 DeepSeek                           ✓     │
│  API: https://api.deepseek.com               │
│  认证: Bearer Token                          │
│  模型数: 2                                   │
├─────────────────────────────────────────────┤
│  🟢 通义千问                          ✓     │
│  API: https://dashscope.aliyuncs.com         │
│  认证: Bearer Token                          │
│  模型数: 2                                   │
├─────────────────────────────────────────────┤
│  ⚪ 本地模型                          ✓     │
│  API: http://127.0.0.1:8000                  │
│  认证: 无需认证                               │
│  模型数: 1                                   │
├─────────────────────────────────────────────┤
│  [ 手动触发同步 ]                             │
└─────────────────────────────────────────────┘
```

---

## 七、核心类设计

### 7.1 ConfigSyncManager

```kotlin
class ConfigSyncManager @Inject constructor(
    private val configRepository: ConfigRepository,
    private val remoteDataSource: RemoteDataSource,
    private val localDataSource: LocalDataSource,
    private val assetDataSource: AssetDataSource
) {
    /**
     * 同步配置到本地数据库
     * @return 同步结果
     */
    suspend fun syncConfig(): SyncResult {
        try {
            // 1. 尝试从 Gitee 拉取
            val remoteConfig = remoteDataSource.fetchConfig()
            
            // 2. 获取本地版本
            val localVersion = configRepository.getConfigVersion()
            
            // 3. 版本比对
            if (remoteConfig.configVersion <= localVersion) {
                return SyncResult.NoChange
            }
            
            // 4. 解析并写入数据库 (事务)
            configRepository.upsertConfig(remoteConfig)
            
            // 5. 记录同步日志
            syncLogRepository.log(
                SyncLog(
                    syncType = "startup",
                    remoteVersion = remoteConfig.configVersion,
                    localVersion = localVersion,
                    syncStatus = "success"
                )
            )
            
            return SyncResult.Success(remoteConfig.configVersion)
            
        } catch (e: Exception) {
            // 降级：使用本地缓存
            return try {
                localDataSource.loadCachedConfig()?.let { cached ->
                    SyncResult.FallbackToCache(cached.configVersion)
                } ?: SyncResult.FallbackToAssets
            } catch (cacheError: Exception) {
                SyncResult.Error(cacheError)
            }
        }
    }
    
    /**
     * 检查并同步 (用于 WorkManager 定时任务)
     */
    fun scheduledSync() {
        // 类似逻辑，syncType = "scheduled"
    }
}
```

### 7.2 ParserRouter

```kotlin
class ParserRouter @Inject constructor(
    private val schemaRepository: SchemaRepository,
    private val jsonExtractor: JsonPathExtractor
) {
    /**
     * 根据模型和服务商选择解析器
     */
    fun selectParser(modelId: String, providerId: String): UsageParser {
        val schema = schemaRepository.findBy(modelId, providerId)
            ?: schemaRepository.findByGeneric(providerId)
            ?: throw IllegalArgumentException("No schema found for model: $modelId")
        
        return ConfigurableUsageParser(schema, jsonExtractor)
    }
}

interface UsageParser {
    fun parse(response: JsonObject): UsageData
}

class ConfigurableUsageParser(
    private val schema: ApiResponseSchema,
    private val jsonExtractor: JsonPathExtractor
) : UsageParser {
    override fun parse(response: JsonObject): UsageData {
        val root = jsonExtractor.getObject(response, schema.rootPath)
        
        return UsageData(
            inputTokens = jsonExtractor.getLong(root, schema.inputTokenPath).toInt(),
            outputTokens = jsonExtractor.getLong(root, schema.outputTokenPath).toInt(),
            cacheHitTokens = schema.cacheHitTokenPath?.let {
                jsonExtractor.getLong(root, it, missingAsZero = true)
            }?.toInt() ?: 0,
            cacheCreateTokens = schema.cacheCreateTokenPath?.let {
                jsonExtractor.getLong(root, it, missingAsZero = true)
            }?.toInt() ?: 0
        )
    }
}
```

### 7.3 UsageCalculator

```kotlin
class UsageCalculator @Inject constructor(
    private val priceRepository: PriceRepository
) {
    /**
     * 计算用量费用
     */
    fun calculate(usage: UsageData, modelId: String): CalculatedUsage {
        val inputPrice = priceRepository.findBy(modelId, "input")
            ?: throw IllegalArgumentException("No price for model: $modelId")
        val outputPrice = priceRepository.findBy(modelId, "output")
        val cacheHitPrice = priceRepository.findBy(modelId, "cache_hit")
        
        // 计算费用
        val inputCost = (usage.inputTokens.toDouble() / 1_000_000) * inputPrice.pricePer1M
        val outputCost = (usage.outputTokens.toDouble() / 1_000_000) * outputPrice.pricePer1M
        val cacheCost = (usage.cacheHitTokens.toDouble() / 1_000_000) * cacheHitPrice.pricePer1M
        
        // 计算缓存节省
        val originalCacheCost = (usage.cacheHitTokens.toDouble() / 1_000_000) * inputPrice.pricePer1M
        val cacheSavedCost = originalCacheCost - cacheCost
        
        return CalculatedUsage(
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cacheHitTokens = usage.cacheHitTokens,
            cacheCreateTokens = usage.cacheCreateTokens,
            totalTokens = usage.totalTokens,
            inputCost = inputCost,
            outputCost = outputCost,
            cacheCost = cacheCost,
            totalCost = inputCost + outputCost + cacheCost,
            cacheSavedCost = cacheSavedCost,
            currency = inputPrice.currency
        )
    }
}
```

### 7.4 TraceManager

```kotlin
class TraceManager @Inject constructor(
    private val usageRepository: UsageRepository
) {
    /**
     * 生成追踪 ID
     */
    fun generateTraceId(): String = UUID.randomUUID().toString()
    
    /**
     * 记录 Span
     */
    fun recordSpan(
        traceId: String,
        spanName: String,
        attributes: Map<String, String> = emptyMap(),
        duration: Long = 0,
        error: String? = null
    ) {
        val span = AISpan(
            spanId = UUID.randomUUID().toString(),
            name = spanName,
            startTime = System.currentTimeMillis() - duration,
            endTime = System.currentTimeMillis(),
            duration = duration,
            attributes = attributes,
            error = error
        )
        
        // 追加到 Trace (使用 MutableMap 在内存中累积)
        currentTraces.getOrPut(traceId) { mutableListOf() }.add(span)
    }
    
    /**
     * 完成 Trace 并持久化
     */
    fun completeTrace(traceId: String, sessionId: String, userId: String) {
        val trace = currentTraces.remove(traceId) ?: return
        
        val usageLog = ApiUsageLog(
            traceId = traceId,
            sessionId = sessionId,
            userId = userId,
            spans = trace,
            callTime = System.currentTimeMillis()
        )
        
        usageRepository.insertUsageLog(usageLog)
    }
}
```

### 7.5 StatisticsRepository

```kotlin
class StatisticsRepository @Inject constructor(
    private val usageDao: UsageDao,
    private val priceDao: PriceDao
) {
    /**
     * 获取汇总数据
     */
    suspend fun getSummary(userId: String): UsageSummary {
        val totalTokens = usageDao.sumTotalTokens(userId)
        val totalCost = usageDao.sumTotalCost(userId)
        val messageCount = usageDao.countMessages(userId)
        val cacheSavedCost = usageDao.sumCacheSavedCost(userId)
        
        return UsageSummary(
            totalTokens = totalTokens ?: 0,
            totalCost = totalCost ?: 0.0,
            messageCount = messageCount ?: 0,
            cacheSavedCost = cacheSavedCost ?: 0.0
        )
    }
    
    /**
     * 获取最近 7 天趋势
     */
    suspend fun getWeeklyTrend(userId: String): List<DailyUsage> {
        val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000
        
        return usageDao.getDailyUsage(userId, sevenDaysAgo)
    }
    
    /**
     * 获取模型分布
     */
    suspend fun getModelDistribution(userId: String): List<ModelUsageStats> {
        return usageDao.getUsageByModel(userId)
    }
    
    /**
     * 分页获取历史记录
     */
    suspend fun getUsageHistory(
        userId: String,
        page: Int,
        pageSize: Int = 20
    ): PagedResult<ApiUsageLog> {
        val offset = page * pageSize
        
        val items = usageDao.getUsageHistory(userId, offset, pageSize)
        val total = usageDao.countUsageHistory(userId)
        
        return PagedResult(items = items, total = total)
    }
}
```

---

## 八、Room DAO 设计

```kotlin
@Dao
interface UsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsageLog(log: ApiUsageLog)
    
    @Query("SELECT SUM(totalTokens) FROM api_usage_log WHERE userId = :userId")
    suspend fun sumTotalTokens(userId: String): Int?
    
    @Query("SELECT SUM(totalCost) FROM api_usage_log WHERE userId = :userId")
    suspend fun sumTotalCost(userId: String): Double?
    
    @Query("SELECT SUM(cacheSavedCost) FROM api_usage_log WHERE userId = :userId")
    suspend fun sumCacheSavedCost(userId: String): Double?
    
    @Query("SELECT COUNT(*) FROM api_usage_log WHERE userId = :userId")
    suspend fun countMessages(userId: String): Int?
    
    @Query("""
        SELECT 
            DATE(callTime) as day,
            SUM(totalTokens) as totalTokens,
            SUM(totalCost) as totalCost
        FROM api_usage_log
        WHERE userId = :userId AND callTime >= :since
        GROUP BY DATE(callTime)
        ORDER BY day ASC
    """)
    suspend fun getDailyUsage(userId: String, since: Long): List<DailyUsage>
    
    @Query("""
        SELECT modelId, 
               SUM(totalTokens) as totalTokens,
               SUM(totalCost) as totalCost
        FROM api_usage_log
        WHERE userId = :userId
        GROUP BY modelId
        ORDER BY totalCost DESC
    """)
    suspend fun getUsageByModel(userId: String): List<ModelUsageStats>
    
    @Query("SELECT * FROM api_usage_log WHERE userId = :userId ORDER BY callTime DESC LIMIT :limit OFFSET :offset")
    suspend fun getUsageHistory(userId: String, offset: Int, limit: Int): List<ApiUsageLog>
    
    @Query("SELECT COUNT(*) FROM api_usage_log WHERE userId = :userId")
    suspend fun countUsageHistory(userId: String): Int
}

@Dao
interface PriceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrice(config: ApiPriceConfig)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllPrices(prices: List<ApiPriceConfig>)
    
    @Query("SELECT * FROM api_price_config WHERE modelId = :modelId AND priceType = :priceType AND isActive = 1 LIMIT 1")
    suspend fun findBy(modelId: String, priceType: String): ApiPriceConfig?
    
    @Query("SELECT MAX(configVersion) FROM sync_log WHERE syncStatus = 'success'")
    suspend fun getLastConfigVersion(): Int?
    
    @Query("UPDATE api_price_config SET isActive = 0")
    suspend fun clearAllPrices()
}

@Dao
interface SchemaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchema(schema: ApiResponseSchema)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllSchemas(schemas: List<ApiResponseSchema>)
    
    @Query("SELECT * FROM api_response_schema WHERE modelId = :modelId AND providerId = :providerId LIMIT 1")
    suspend fun findBy(modelId: String, providerId: String): ApiResponseSchema?
    
    @Query("SELECT * FROM api_response_schema WHERE modelId = '' AND providerId = :providerId LIMIT 1")
    suspend fun findByGeneric(providerId: String): ApiResponseSchema?
}
```

---

## 九、集成点设计

### 9.1 与现有 AI 聊天集成

在现有的 AgentLoopEngine 或 AI 调用流程中，增加用量统计拦截：

```kotlin
// 在 API 调用后拦截
class UsageInterceptingAPIWrapper(
    private val parserRouter: ParserRouter,
    private val usageCalculator: UsageCalculator,
    private val traceManager: TraceManager,
    private val usageRepository: UsageRepository
) {
    suspend fun callWithUsageTracking(
        traceId: String,
        sessionId: String,
        userId: String,
        modelId: String,
        providerId: String,
        apiCall: suspend () -> ApiResponse
    ): ApiResponse {
        val startTime = System.currentTimeMillis()
        
        try {
            // 1. 记录 API 调用 Span
            traceManager.recordSpan(traceId, "api.call", mapOf(
                "model" to modelId,
                "provider" to providerId
            ))
            
            // 2. 调用 API
            val response = apiCall()
            
            // 3. 解析用量
            val parser = parserRouter.selectParser(modelId, providerId)
            val usage = parser.parse(response.body)
            
            traceManager.recordSpan(traceId, "usage.parse", mapOf(
                "provider" to providerId,
                "status" to "success"
            ))
            
            // 4. 计算费用
            val calculated = usageCalculator.calculate(usage, modelId)
            
            traceManager.recordSpan(traceId, "cost.calculate", mapOf(
                "model" to modelId,
                "currency" to calculated.currency,
                "totalCost" to calculated.totalCost.toString()
            ))
            
            // 5. 写入数据库
            usageRepository.insertUsageLog(
                ApiUsageLog(
                    traceId = traceId,
                    sessionId = sessionId,
                    userId = userId,
                    modelId = modelId,
                    providerId = providerId,
                    inputTokens = calculated.inputTokens,
                    outputTokens = calculated.outputTokens,
                    cacheHitTokens = calculated.cacheHitTokens,
                    cacheCreateTokens = calculated.cacheCreateTokens,
                    totalTokens = calculated.totalTokens,
                    inputCost = calculated.inputCost,
                    outputCost = calculated.outputCost,
                    cacheCost = calculated.cacheCost,
                    totalCost = calculated.totalCost,
                    cacheSavedCost = calculated.cacheSavedCost,
                    duration = System.currentTimeMillis() - startTime
                )
            )
            
            return response
            
        } catch (e: Exception) {
            traceManager.recordSpan(traceId, "api.call", mapOf(
                "status" to "error",
                "error" to e.message
            ), error = e.message)
            
            throw e
        }
    }
}
```

### 9.2 聊天界面实时展示

```kotlin
// ChatViewModel
class ChatViewModel @Inject constructor(
    private val statisticsRepository: StatisticsRepository
) : ViewModel() {
    private val _currentUsage = MutableStateFlow<CalculatedUsage?>(null)
    val currentUsage: StateFlow<CalculatedUsage?> = _currentUsage.asStateFlow()
    
    fun onMessageCompleted(usage: CalculatedUsage) {
        _currentUsage.value = usage
    }
    
    fun clearCurrentUsage() {
        _currentUsage.value = null
    }
}

// ChatBottomBar (Jetpack Compose)
@Composable
fun ChatUsageBar(usage: CalculatedUsage?) {
    if (usage == null) return
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("Token: 输入 ${usage.inputTokens} | 输出 ${usage.outputTokens}")
            Text("缓存: ${usage.cacheHitTokens} | 费用: ¥${String.format("%.6f", usage.totalCost)}")
            
            if (usage.cacheSavedCost > 0) {
                Text("💰 缓存节省: ¥${String.format("%.6f", usage.cacheSavedCost)}", 
                     color = Color.Green)
            }
        }
    }
}
```

---

## 十、WorkManager 定时同步

```kotlin
class ConfigSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    @Inject lateinit var configSyncManager: ConfigSyncManager
    
    override suspend fun doWork(): Result {
        return try {
            val result = configSyncManager.scheduledSync()
            
            when (result) {
                is SyncResult.Success -> Result.success()
                is SyncResult.NoChange -> Result.success()
                is SyncResult.FallbackToCache -> Result.success()
                is SyncResult.FallbackToAssets -> Result.success()
                is SyncResult.Error -> Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

// 配置定时任务
fun scheduleConfigSync() {
    val request = PeriodicWorkRequestBuilder<ConfigSyncWorker>(
        6, TimeUnit.HOURS
    )
    .setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    )
    .setBackoffCriteria(
        BackoffPolicy.EXPONENTIAL,
        15, TimeUnit.MINUTES
    )
    .build()
    
    WorkManager.getInstance(applicationContext)
        .enqueueUniquePeriodicWork(
            "config_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
}
```

---

## 十一、异常处理与降级

### 11.1 配置同步降级

```
第 1 层: Gitee 远程配置 (成功)
第 2 层: Gitee 失败 → 本地缓存 (成功)
第 3 层: 本地缓存失败 → Assets 内置默认 (兜底)
```

### 11.2 费用计算降级

```kotlin
// 如果价格不存在，使用默认值
fun getOrDefaultPrice(modelId: String, priceType: String): Double {
    return priceRepository.findBy(modelId, priceType)?.pricePer1M 
        ?: DEFAULT_PRICE
}

companion object {
    private const val DEFAULT_PRICE = 0.0  // 本地模型免费
}
```

### 11.3 解析器降级

```kotlin
// 如果配置中未定义缓存字段，默认为 0
val cacheHitTokens = schema.cacheHitTokenPath?.let {
    jsonExtractor.getLong(root, it)
} ?: 0L

// 如果 JSON 路径不存在，返回默认值
jsonExtractor.getLong(json, path, missingAsZero = true)
```

---

## 十二、安全与合规

### 12.1 API Key 管理

- API Key 存储在 `SharedPreferences` 或 `AndroidKeyStore`，不写入配置文件
- 配置文件仅包含配置元数据，不包含敏感信息
- 认证 Header 通过配置中的 `authHeaderName` 动态确定

### 12.2 数据隐私

- 用量数据本地存储，不上传云端
- 支持用户清除本地用量历史
- 全链路追踪 ID 不关联用户身份

### 12.3 配置完整性校验

```kotlin
fun validateConfig(config: RemoteConfig): ValidationResult {
    // 1. 必填字段校验
    if (config.configVersion <= 0) return ValidationResult.Invalid("Invalid version")
    if (config.providers.isEmpty()) return ValidationResult.Invalid("No providers")
    if (config.prices.isEmpty()) return ValidationResult.Invalid("No prices")
    
    // 2. 关联关系校验
    for (price in config.prices) {
        if (!config.providers.any { it.providerId == price.providerId }) {
            return ValidationResult.Invalid("Unknown provider: ${price.providerId}")
        }
        if (!config.models.any { it.modelId == price.modelId }) {
            return ValidationResult.Invalid("Unknown model: ${price.modelId}")
        }
    }
    
    // 3. 价格合理性校验
    for (price in config.prices) {
        if (price.pricePer1M < 0) {
            return ValidationResult.Invalid("Negative price: ${price.pricePer1M}")
        }
    }
    
    return ValidationResult.Valid
}
```

---

## 十三、性能优化

### 13.1 数据库优化

```kotlin
// 批量插入配置 (避免多次 I/O)
@Transaction
suspend fun upsertConfig(config: RemoteConfig) {
    // 清除旧数据
    priceDao.clearAllPrices()
    schemaDao.upsertAllSchemas(emptyList())
    providerDao.clearAllProviders()
    
    // 批量插入
    priceDao.upsertAllPrices(config.prices)
    schemaDao.upsertAllSchemas(config.responseSchemas)
    providerDao.upsertAllProviders(config.providers)
    
    // 记录同步日志
    syncLogDao.insert(SyncLog(...))
}
```

### 13.2 内存管理

- 全链路追踪使用 `ConcurrentHashMap` 存储，避免内存泄漏
- 统计查询使用 `Flow` 响应式数据流，自动管理生命周期
- 图表数据在后台线程预计算，避免主线程阻塞

### 13.3 网络优化

- Gitee 请求使用 `OkHttp` 缓存策略，设置 304 Not Modified
- 使用 `Gzip` 压缩传输
- 配置大小控制在 100KB 以内

---

## 十四、测试策略

### 14.1 单元测试

```kotlin
class UsageCalculatorTest {
    @Test
    fun testCalculateCost_DeepSeek_CacheHit() {
        // 模拟: 输入 1000, 输出 500, 缓存命中 200
        val usage = UsageData(
            inputTokens = 1000,
            outputTokens = 500,
            cacheHitTokens = 200,
            cacheCreateTokens = 0
        )
        
        val result = calculator.calculate(usage, "deepseek-chat")
        
        assertEquals(0.00014, result.inputCost)    // (1000/1M) * 0.14
        assertEquals(0.00014, result.outputCost)   // (500/1M) * 0.28
        assertEquals(0.000004, result.cacheCost)   // (200/1M) * 0.02
        assertTrue(result.totalCost > 0)
        assertTrue(result.cacheSavedCost > 0)       // 缓存节省
    }
}
```

### 14.2 集成测试

- 配置同步流程端到端测试
- 解析器路由正确性测试
- 费用计算精度测试

### 14.3 UI 测试

- 聊天界面用量展示正确性
- 统计页面数据刷新
- 模型选择器动态渲染

---

## 十五、实施计划

### 阶段一：基础架构 (1-2 周)

- [ ] Room 数据库表创建
- [ ] 配置数据模型定义
- [ ] ConfigSyncManager 实现
- [ ] Gitee 远程配置同步
- [ ] 降级策略实现

### 阶段二：核心功能 (1-2 周)

- [ ] ParserRouter 实现
- [ ] UsageCalculator 实现
- [ ] TraceManager 实现
- [ ] API 调用集成
- [ ] 聊天界面用量展示

### 阶段三：统计界面 (1 周)

- [ ] StatisticsRepository 实现
- [ ] 汇总卡片 UI
- [ ] 趋势图实现
- [ ] 模型分布 UI
- [ ] 历史记录列表

### 阶段四：高级功能 (1 周)

- [ ] WorkManager 定时同步
- [ ] 模型选择器动态渲染
- [ ] 服务商管理界面
- [ ] 异常处理与边界测试

### 阶段五：测试与优化 (1 周)

- [ ] 单元测试覆盖
- [ ] 集成测试
- [ ] 性能优化
- [ ] 用户体验优化

---

## 十六、验收标准

- [x] 启动后自动从 Gitee 拉取最新配置，版本无变化时不更新
- [x] Gitee 不可用时使用本地缓存，核心功能不受影响
- [x] 每次 AI 对话后正确显示 Token 用量和费用
- [x] 统计页面汇总数据、趋势图、模型分布、历史记录完整展示
- [x] 新增服务商只需修改 JSON，应用自动识别并支持解析
- [x] 缓存命中 Token 单独统计，并显示节省费用

---

## 附录 A: 依赖配置

```gradle
dependencies {
    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    
    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON
    implementation("com.squareup.moshi:moshi:1.15.1")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.1")
    
    // Coroutine
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Compose (UI)
    implementation("androidx.compose.ui:ui:1.6.0")
    implementation("androidx.compose.material3:material3:1.2.0")
}
```

## 附录 B: 配置文件仓库结构

```
gitee.com/yourname/smartquiz-config/
├── config/
│   ├── v1/
│   │   └── ai-usage-config.json
│   └── latest/
│       └── ai-usage-config.json  (符号链接或硬链接到最新版本)
├── CHANGELOG.md  (配置变更记录)
└── README.md
```

## 附录 C: 关键常量

```kotlin
object ConfigConstants {
    const val GITEE_REPO_URL = "https://gitee.com/yourname/smartquiz-config/raw/latest/ai-usage-config.json"
    const val CACHE_EXPIRY_MS = 6 * 60 * 60 * 1000  // 6 小时
    const val SYNC_WORK_INTERVAL_HOURS = 6L
    const val DEFAULT_PRICE = 0.0
    const val TOKENS_PER_MILLION = 1_000_000
    const val MAX_CONFIG_SIZE_KB = 100
}
```
