# AI 用量配置说明

> 配置文件路径: `assets/ai_usage_config_default.json`  
> 远程地址: `https://gitee.com/xiaocongcong495863994/smartquiz/raw/main/ai-usage-config.json`

---

## 一、全局配置

| 字段 | 类型 | 说明 | 示例值 |
|------|------|------|--------|
| `configVersion` | int | 配置版本号，每次修改后递增，应用根据版本号决定是否更新 | `1` |
| `lastUpdated` | string | 最后更新时间 | `"2026-08-11T00:00:00Z"` |
| `currency` | string | 默认币种 | `"CNY"` |
| `exchangeRate` | double | 汇率（USD → CNY） | `7.2` |

### remoteConfig — 远程连接配置

| 字段 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| `giteeUrl` | string | Gitee 配置文件 Raw 地址 | `https://gitee.com/xiaocongcong495863994/smartquiz/raw/main/ai-usage-config.json` |
| `connectTimeoutMs` | int | 连接超时（毫秒） | `5000` |
| `readTimeoutMs` | int | 读取超时（毫秒） | `10000` |
| `autoSync` | boolean | 启动时是否自动同步 | `true` |
| `syncIntervalHours` | int | 定时同步间隔（小时） | `6` |

---

## 二、服务商配置 (providers)

每个服务商一条记录，新增服务商只需在数组中追加。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `providerId` | string | ✅ | 服务商唯一 ID，用于匹配模型和 Schema |
| `displayName` | string | ✅ | 显示名称 |
| `apiBase` | string | ✅ | API 基础地址 |
| `authType` | string | ✅ | 认证类型：`Bearer` / `APIKey` / `None` |
| `authHeaderName` | string | ✅ | 认证 Header 名称 |
| `usageFormat` | string | ✅ | 用量格式：`openai` / `anthropic` |

### 示例

```json
{
  "providerId": "openai",
  "displayName": "OpenAI / DeepSeek",
  "apiBase": "https://api.deepseek.com",
  "authType": "Bearer",
  "authHeaderName": "Authorization",
  "usageFormat": "openai"
}
```

---

## 三、响应格式配置 (responseSchemas)

定义从 API 响应中提取 Token 数的字段路径。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `schemaId` | string | ✅ | Schema 唯一 ID |
| `providerId` | string | ✅ | 关联的服务商 ID，`""` 表示通用 |
| `modelId` | string | ❌ | 关联的模型 ID，`""` 表示通用 |
| `rootPath` | string | ✅ | usage 数据所在的 JSON 路径 |
| `inputTokenPath` | string | ✅ | 输入 Token 字段名 |
| `outputTokenPath` | string | ✅ | 输出 Token 字段名 |
| `cacheHitTokenPath` | string | ❌ | 缓存命中 Token 字段名（可为 null） |
| `cacheCreateTokenPath` | string | ❌ | 缓存创建 Token 字段名（可为 null） |

### 不同服务商的 Token 字段

| 服务商 | 输入字段 | 输出字段 |
|--------|---------|---------|
| OpenAI / DeepSeek | `prompt_tokens` | `completion_tokens` |
| Anthropic | `input_tokens` | `output_tokens` |

---

## 四、模型配置 (models)

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `modelId` | string | ✅ | 模型唯一 ID，API 调用时传入 |
| `providerId` | string | ✅ | 关联的服务商 ID |
| `displayName` | string | ✅ | 显示名称 |
| `contextWindow` | int | ✅ | 上下文窗口大小（Token 数） |

### 示例

```json
{
  "modelId": "deepseek-chat",
  "providerId": "openai",
  "displayName": "DeepSeek Chat",
  "contextWindow": 64000
}
```

---

## 五、价格配置 (prices)

**每条价格记录对应一个 `modelId` + `priceType` 组合**，一个模型至少需要 input 和 output 两条记录。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `modelId` | string | ✅ | 模型 ID，必须与 models 中一致 |
| `providerId` | string | ✅ | 服务商 ID |
| `priceType` | string | ✅ | `input`（输入）/ `output`（输出）/ `cache_hit`（缓存命中） |
| `pricePer1M` | double | ✅ | **每百万 Token 的价格（元）** |
| `currency` | string | ✅ | 币种 |
| `minContext` | int | ✅ | 最小上下文（Token） |
| `maxContext` | int | ✅ | 最大上下文（Token） |

### 费用计算公式

```
费用 = (Token数 / 1,000,000) × pricePer1M
```

**示例：输入 1000 Token，价格 3.0**
```
(1000 / 1,000,000) × 3.0 = 0.003 元
```

### 示例

```json
// 输入价格
{
  "modelId": "deepseek-chat",
  "providerId": "openai",
  "priceType": "input",
  "pricePer1M": 3.0,
  "currency": "CNY",
  "minContext": 1,
  "maxContext": 64000
}

// 输出价格
{
  "modelId": "deepseek-chat",
  "providerId": "openai",
  "priceType": "output",
  "pricePer1M": 6.0,
  "currency": "CNY",
  "minContext": 1,
  "maxContext": 64000
}
```

### 缓存计费（可选）

如果服务商支持缓存计费（如 DeepSeek），可添加 `cache_hit` 类型：

```json
{
  "modelId": "deepseek-chat",
  "providerId": "openai",
  "priceType": "cache_hit",
  "pricePer1M": 0.02,
  "currency": "CNY",
  "minContext": 1,
  "maxContext": 64000
}
```

缓存命中费用的计算会单独使用 `cache_hit` 的价格，而不是 `input` 的价格。

---

## 六、请求格式配置 (requestSchemas)

定义如何构造 API 请求，通常不需要修改。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `schemaId` | string | ✅ | Schema 唯一 ID |
| `providerId` | string | ✅ | 服务商 ID |
| `modelId` | string | ❌ | 模型 ID，`""` 表示通用 |
| `messagesPath` | string | ✅ | 消息数组路径（如 `messages`） |
| `messageRolePath` | string | ✅ | 消息 role 字段路径（如 `role`） |
| `messageContentPath` | string | ✅ | 消息内容路径（如 `content`） |
| `systemMessageRole` | string | ✅ | 系统消息的 role 值 |
| `userMessageRole` | string | ✅ | 用户消息的 role 值 |
| `assistantMessageRole` | string | ✅ | 助手消息的 role 值 |
| `temperaturePath` | string | ✅ | temperature 字段路径 |
| `topPPath` | string | ❌ | top_p 字段路径 |
| `maxTokensPath` | string | ❌ | max_tokens 字段路径 |
| `streamPath` | string | ❌ | stream 字段路径 |
| `supportsStream` | boolean | ❌ | 是否支持流式输出 |

---

## 七、操作流程

### 新增模型

1. 在 `models` 数组中添加模型记录
2. 在 `prices` 数组中添加 `input` 和 `output` 两条价格记录
3. 将 `configVersion` 递增（如 `1 → 2`）
4. 推送至 Gitee

### 修改价格

1. 找到对应 `modelId` + `priceType` 的记录
2. 修改 `pricePer1M` 字段
3. 将 `configVersion` 递增
4. 推送至 Gitee

### 新增服务商

1. 在 `providers` 数组中添加服务商
2. 在 `responseSchemas` 中添加对应的 Token 字段路径
3. 在 `models` 中添加该服务商的模型
4. 在 `prices` 中添加对应价格
5. 在 `requestSchemas` 中添加请求格式
6. 将 `configVersion` 递增
7. 推送至 Gitee

---

## 八、注意事项

1. **configVersion 必须递增**：应用只在 `remoteVersion > localVersion` 时更新
2. **价格单位是每百万 Token**：不要写小数点
3. **modelId 必须唯一**：每个模型 ID 全局唯一
4. **priceType 必须与服务商匹配**：确保 `input`/`output`/`cache_hit` 与 API 返回一致
5. **配置文件大小不超过 50KB**：超过会被拒绝
