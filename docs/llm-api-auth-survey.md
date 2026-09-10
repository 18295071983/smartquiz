# 大模型 API 鉴权方式深度调研报告

> 用途：扩展 OilQuiz Android 项目鉴权系统
> 调研日期：2026-09-10
> 现有代码基线：`APIKeyManager.getAuthHeader(APIConfig)` / `getTestUrl(APIConfig)` / `ModelListFetcher.testConnection(apiUrl, apiKey)` / `ModelListFetcher.fetchModels(apiUrl, apiKey)`

---

## 0. 现有代码诊断（先讲清楚现状）

### 0.1 APIKeyManager.getAuthHeader 当前实现

```java
private String getAuthHeader(APIConfig config) {
    switch (serviceType) {
        case OPENAI:
        case CUSTOM:    return "Bearer " + apiKey;
        case ANTHROPIC: return "x-api-key: " + apiKey;   // ← BUG
        case BING_SEARCH: return null;
        default: return null;
    }
}
```

调用方硬编码：
```java
String authHeader = getAuthHeader(config);
if (authHeader != null) connection.setRequestProperty("Authorization", authHeader);
```

**关键问题：**
- 方法签名只返回一个 String（header 的 **值**），header 名永远写死成 `"Authorization"`。
- `ANTHROPIC` 分支返回 `"x-api-key: sk-ant-xxx"`，被塞进 `Authorization` 头 → 实际发出的是 `Authorization: x-api-key: sk-ant-xxx`，**这是错的**（Anthropic 不读这个）。正确做法应该是 header 名 `x-api-key`，值 `sk-ant-xxx`。
- 这个方法**无法**表达：非 Authorization 头（x-api-key / api-key / x-goog-api-key）、URL query 拼接、双 Key、预签名、无鉴权等场景。
- 注意：你问题里写的是 `getAuthHeader(apiUrl, apiKey)`，实际代码是 `getAuthHeader(APIConfig config)`，下面按实际签名讨论。

### 0.2 ModelListFetcher.testConnection / fetchModels 当前实现

```java
if (apiUrl.toLowerCase().contains("anthropic")) {
    connection.setRequestProperty("x-api-key", apiKey);
    connection.setRequestProperty("anthropic-version", "2023-06-01");
} else {
    connection.setRequestProperty("Authorization", "Bearer " + apiKey);
}
```

按 URL 字符串 contains 猜厂商，只能区分 anthropic / google / openai-or-azure 三类，其余全部走 Bearer。Google 模型列表是硬编码的，不发请求。

### 0.3 APIConfig.ServiceType 现有枚举

```
openai, anthropic, google, hefeng_weather, bing_search, google_maps, custom
```

没有任何 auth 字段，鉴权方式完全靠 serviceType + URL 猜。

---

## 1. Bearer Token（`Authorization: Bearer sk-xxx`）

### ① 标识
`auth = bearer`

### ② 在用厂商（最主流，OpenAI 事实标准）
- **OpenAI**（api.openai.com）
- **DeepSeek**（api.deepseek.com）["https://api-docs.deepseek.com/zh-cn/api/deepseek-api/"]
- **Moonshot / Kimi**（api.moonshot.cn / api.moonshot.ai）["https://platform.kimi.com/docs/api/overview"]
- **智谱 BigModel / GLM**（open.bigmodel.cn，支持 Bearer 直接传 API Key，也支持 JWT）["https://docs.bigmodel.cn/cn/guide/start/quick-start","https://zhipu-ef7018ed.mintlify.app/cn/guide/develop/http/introduction"]
- **阿里百炼 / DashScope / 通义千问**（dashscope.aliyuncs.com，兼容模式 base 是 `{workspaceid}.cn-beijing.maas.aliyuncs.com/compatible-mode`）["https://help.aliyun.com/zh/model-studio/first-api-call-to-qwen?disableWebsiteRedirect=true","https://www.alibabacloud.com/help/zh/model-studio/compatibility-of-openai-with-dashscope"]
- **火山方舟 Ark / 豆包**（ark.cn-beijing.volces.com/api/v3）["https://www.volcengine.com/docs/82379/1298459?lang=zh"]
- **腾讯混元（OpenAI 兼容接口）**（api.hunyuan.cloud.tencent.com/v1）["https://cloud.tencent.com.cn/document/product/1729/111007"]
- **MiniMax（OpenAI 兼容模式）**（api.minimax.cn/v1）["https://platform.minimaxi.com/docs/guides/text-generation"]
- **SiliconFlow 硅基流动**（api.siliconflow.cn/v1）["https://docs.siliconflow.cn/cn/userguide/capabilities/text-generation"]
- Mistral、Groq、OpenRouter、Together AI、Novita、Lepton 等所有 OpenAI 兼容聚合平台
- **Anthropic 新版 OAuth**（`Authorization: Bearer <token>`，由 Workload Identity Federation 颁发，短期 token；传统静态 Key 仍走 x-api-key）["https://platform.claude.com/docs/en/manage-claude/authentication"]

### ③ Header 模板
```http
Authorization: Bearer <API_KEY>
Content-Type: application/json
```

### ④ 预处理
无。直接拼。

### ⑤ 现有代码能否覆盖
**能覆盖。** `OPENAI` / `CUSTOM` 分支就是这个。绝大部分国产厂商（DeepSeek/Kimi/智谱/百炼/方舟/SiliconFlow）走 OpenAI 兼容端点时都落 `CUSTOM`，天然覆盖。

### ⑥ 官方来源
- OpenAI：https://platform.openai.com/docs/api-reference/authentication
- DeepSeek：https://api-docs.deepseek.com/zh-cn/api/deepseek-api/
- 智谱：https://docs.bigmodel.cn/cn/guide/start/quick-start
- 百炼：https://help.aliyun.com/zh/model-studio/first-api-call-to-qwen
- 方舟：https://www.volcengine.com/docs/82379/1298459?lang=zh
- Kimi：https://platform.kimi.com/docs/api/overview

---

## 2. x-api-key header（`x-api-key: xxx`）

### ① 标识
`auth = x-api-key`

### ② 在用厂商
- **Anthropic Claude 原生 API**（api.anthropic.com）—— 典型代表，必须同时带 `x-api-key` 和 `anthropic-version: 2023-06-01` 两个头。["https://console.anthropic.com/docs/en/api","https://docs.anthropic.com/en/api/messages"]
- **MiniMax（Anthropic 兼容模式）**（api.minimax.cn/anthropic）—— 走 Anthropic 协议时用 `x-api-key`，不需要 anthropic-version。["https://github.com/hkai-ai/LLM_OFFICIAL_DOCUMENTATION/blob/main/minimax/README.md"]

### ③ Header 模板
```http
x-api-key: <API_KEY>
anthropic-version: 2023-06-01     ← 仅 Anthropic 必须
Content-Type: application/json
```

### ④ 预处理
无。Anthropic 的 `anthropic-version` 是固定值，可硬编码。

### ⑤ 现有代码能否覆盖
**不能干净覆盖。** `APIKeyManager.getAuthHeader` 的 ANTHROPIC 分支写法是错的（见 §0.1）。`ModelListFetcher` 里倒是写对了（`setRequestProperty("x-api-key", apiKey)` + version）。需要统一成"header 名 + header 值"二元组。

### ⑥ 官方来源
- https://docs.anthropic.com/en/api/messages
- https://console.anthropic.com/docs/en/api

---

## 3. Query 参数鉴权（`?key=xxx` 或 `?access_token=xxx`）

### ① 标识
`auth = query-key`（参数名可配，常见 `key` / `access_token`）

### ② 在用厂商
- **Google Gemini**（generativelanguage.googleapis.com）—— 两种方式二选一：
  - Header：`x-goog-api-key: $GEMINI_API_KEY`（官方推荐生产环境用这个）
  - Query：`?key=$GEMINI_API_KEY`["https://ai.google.dev/gemini-api/docs/api-key?hl=zh-cn","https://github.com/hkai-ai/LLM_OFFICIAL_DOCUMENTATION/blob/main/google-gemini/README.md"]
- **百度千帆 ERNIE 原生接口** —— `?access_token=<从OAuth换得的token>`（见 §7）
- **和风天气 QWeather**（项目已用）—— `?key=<key>`["https://openweathermap... 项目代码中 getTestUrl 已用"]
- Google Maps / YouTube Data API / 多数 Google 系 API 传统用法

### ③ URL 模板
```
https://generativelanguage.googleapis.com/v1beta/models?key=<API_KEY>
https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/ernie-speed-8k?access_token=<TOKEN>
```

### ④ 预处理
Google 不需要；百度需要先 OAuth 换 token（见 §7）。

### ⑤ 现有代码能否覆盖
**部分覆盖。** `getTestUrl` 里 GOOGLE 和 HEFENG 分支已经手拼 `?key=`。但：
- `ModelListFetcher.fetchGoogleModels` 是硬编码模型列表，根本不发请求（Gemini 的 `?key=` 拉 `/v1beta/models` 实际可用）。
- 没有通用的"把 key 拼到 query 里"的分支，百度 `access_token` 完全没覆盖。

### ⑥ 官方来源
- Google：https://ai.google.dev/gemini-api/docs/api-key?hl=zh-cn
- 百度 access_token：https://wenxinyiyan.apifox.cn/api-125213017

---

## 4. `api-key` header（小写中划线，无 x- 前缀）

### ① 标识
`auth = apikey-header`

### ② 在用厂商
- **Azure OpenAI / Microsoft Foundry**（`*.openai.azure.com`）—— 明确要求用 `api-key` 头，**不要**用 `Authorization`。["https://learn.microsoft.com/zh-cn/azure/ai-services/openai/reference","https://learn.microsoft.com/en-us/training/modules/secure-azure-openai-authentication-authorization/3-methods-of-authentication-supported-by-azure-openai"]
- Azure 也支持 Entra ID（即 OAuth Bearer token）作为替代，但 REST 直调默认就是 `api-key` 头。

### ③ Header 模板
```http
api-key: <AZURE_OPENAI_API_KEY>
Content-Type: application/json
```

注意：Azure 的 URL 结构也不一样，路径里要带 `deployment` 和 `api-version`：
```
POST https://{resource}.openai.azure.com/openai/deployments/{deployment-id}/chat/completions?api-version=2024-10-21
```

### ④ 预处理
无。但 URL 拼装规则与 OpenAI 不同（要带 deployment 名和 api-version）。

### ⑤ 现有代码能否覆盖
**不能。** `ModelListFetcher.fetchModels` 里把 URL contains "azure" 归到 `fetchOpenAIModels`，用的是 `Authorization: Bearer`，Azure 会直接 401。需要单独分支走 `api-key` 头。

### ⑥ 与 x-api-key 的区别
| 项 | `x-api-key` | `api-key` |
|---|---|---|
| 前缀 | 带 `x-`（HTTP 自定义头惯例） | 无 `x-`（Azure 历史命名） |
| 代表厂商 | Anthropic | Azure OpenAI |
| 是否带版本头 | 必须带 `anthropic-version` | 不带，版本走 URL query `api-version` |
| HTTP 语义 | 非标准扩展头 | Azure 自命名头 |

### ⑦ 官方来源
- https://learn.microsoft.com/zh-cn/azure/ai-services/openai/reference
- https://learn.microsoft.com/en-us/training/modules/secure-azure-openai-authentication-authorization/3-methods-of-authentication-supported-by-azure-openai

---

## 5. HMAC-SHA256 签名（讯飞星火）—— 重点

### ① 标识
`auth = hmac-xfyun`

### ② 在用厂商
- **讯飞星火大模型**（spark-api.xf-yun.com / aichat.xf-yun.com 等）["https://www.xfyun.cn/doc/spark/http_url_authentication.html"]
- 讯飞的 OCR、数字人、语音等所有 WebAPI 都用同一套签名规则。

### ③ 需要的三个值
| 字段 | 说明 |
|---|---|
| **APPID** | 应用 ID（控制台创建应用后获得） |
| **APIKey** | 用于放进 authorization_origin 里，告诉服务端是哪个 key |
| **APISecret** | 真正的 HMAC 密钥（signing key） |

### ④ 签名原文与计算流程（每次请求都要算）

**Step 1**：生成当前时间 `date`，RFC1123 / GMT 格式：
```
Fri, 05 May 2023 10:43:39 GMT
```
服务端校验时钟偏移，**±300 秒内**，超出拒签。

**Step 2**：拼签名原文 `tmp`（注意是三行，行间 `\n`）：
```
host: spark-api.xf-yun.com
date: Fri, 05 May 2023 10:43:39 GMT
POST /v1.1/chat HTTP/1.1
```
即：`"host: " + host + "\n" + "date: " + date + "\n" + method + " " + path + " HTTP/1.1"`

**Step 3**：用 **APISecret** 做 HMAC-SHA256：
```
tmp_sha = HMAC-SHA256(key=APISecret, msg=tmp)   // 二进制摘要
```

**Step 4**：对 tmp_sha 做 Base64 → `signature`：
```
SFd0y1XlQwsvdK2a0Xyo3wKmfsUovlavQOFPYiWanfw=
```

**Step 5**：拼 authorization_origin（注意单引号/双引号，官方示例混用，按官方模板）：
```
api_key="<APIKey>", algorithm="hmac-sha256", headers="host date request-line", signature="<signature>"
```

**Step 6**：对 authorization_origin 再做一次 Base64 → 最终 `authorization` 值。

### ⑤ 最终请求怎么带

**WebSocket 场景**（星火对话最常用）：把三个参数 URL encode 拼到 query string：
```
wss://spark-api.xf-yun.com/v1.1/chat?authorization=<base64>&date=<rfc1123>&host=spark-api.xf-yun.com
```

**HTTP REST 场景**（如数字人视频、OCR）：放在请求头：
```http
Host: spark-api.xf-yun.com
Date: Fri, 05 May 2023 10:43:39 GMT
Authorization: <base64(authorization_origin)>
```
注意：**不是** `Authorization: Bearer ...`，而是直接把 base64 串作为 Authorization 值（OAuth1.0a 风格）。

### ⑥ 什么时候算
**每次请求都要重新算。** 因为 `date` 有 300 秒时效。不能像 token 那样缓存。APPID 一般放在请求体 `header.app_id` 字段里，不参与签名。

### ⑦ 预处理需求
- 需要 APPID + APIKey + APISecret **三个字段**（现有 APIConfig 只有一个 apiKey 字段，不够）。
- 需要在 Android 端实现 HMAC-SHA256（`javax.crypto.Mac` + `HmacSHA256`）和 Base64（`android.util.Base64`）。
- 需要根据请求 URL 解析出 host 和 path。

### ⑧ 官方来源
- 鉴权总文档：https://www.xfyun.cn/doc/spark/http_url_authentication.html
- 数字人 WebAPI（HTTP 头鉴权示例）：https://www.xfyun.cn/doc/spark/videoGenerate.html

---

## 6. HMAC / 云厂商标准签名（百度 BCE 签名、腾讯 TC3、AWS SigV4）

这类签名逻辑都很重，一般不推荐在 Android 端裸写 SDK 级签名。这里列清楚，让项目知道边界。

### 6.1 百度 BCE AK/SK 签名（千帆"训练/发布模型"等高级接口）
- 凭证：Access Key（AK）+ Secret Key（SK），在百度智能云控制台"安全认证"页拿。
- 算法：百度 BCE 规范，`Authorization: bce-auth-v1/{ak}/{timestamp}/{expiration}/{signedHeaders}/{signature}`，用 SK 对 `method + path + query + headers + sha256(body)` 做 HMAC-SHA256。
- 适用场景：千帆 SDK 的 AK/SK 模式（训练、模型发布）；普通 ERNIE 推理**不推荐**走这个，走 §7 的 access_token 更简单。["https://qianfan.readthedocs.io/en/latest/README.html","https://blog.csdn.net/lfdfhl/article/details/141871223"]

### 6.2 腾讯云 TC3-HMAC-SHA256（混元原生 API）
- 凭证：SecretId + SecretKey。
- Authorization 头格式：
  ```
  TC3-HMAC-SHA256 Credential=AKIDxxxx/2026-09-10/hunyuan/tc3_request, SignedHeaders=content-type;host, Signature=xxxx
  ```
- 注意：混元现在有 OpenAI 兼容接口（`api.hunyuan.cloud.tencent.com/v1`），直接 Bearer 就行，**不需要走 TC3 签名**。TC3 只在老的 `hunyuan.cloud.tencent.com/hyllm/v1` 原生接口用。["https://tengxunhunyuan.apifox.cn/6148067m0","https://cloud.tencent.com.cn/document/product/1729/111007"]

### 6.3 AWS SigV4（Amazon Bedrock）
- 凭证：AWS Access Key ID + Secret Access Key（+ 可选 session token）。
- 算法：AWS Signature V4，要算 canonical request、credential scope、string to sign、signing key 四步。
- Bedrock 现在也支持 **Bearer Token**（bedrock-mantle 端点）和 **Bedrock API Key**，可以绕过 SigV4。["https://docs.aws.amazon.com/zh_cn/bedrock/latest/userguide/inference-messages-api.html","https://docs.anthropic.com/en/docs/build-with-claude/claude-on-amazon-bedrock"]

### 对项目的建议
**这三类在 Android 端不建议自己实现签名**，复杂度高、易错。项目里如果要支持，优先走各家提供的"简化版"：
- 百度 → 用 access_token（§7）
- 腾讯混元 → 用 OpenAI 兼容端点（§1 Bearer）
- AWS Bedrock → 用 Bearer token 或 Bedrock API Key

---

## 7. Access Token 模式（先换 token，再调模型）

### 7.1 百度千帆 ERNIE —— OAuth client_credentials

### ① 标识
`auth = access-token-baidu`

### ② 流程
1. 用户填 **API Key（client_id）** 和 **Secret Key（client_secret）** 两个值。
2. App 调换 token 接口：
   ```
   GET https://aip.baidubce.com/oauth/2.0/token
       ?grant_type=client_credentials
       &client_id=<APIKey>
       &client_secret=<SecretKey>
   ```
3. 返回 JSON：
   ```json
   {
     "access_token": "24.xxxxx...",
     "expires_in": 2592000,
     "scope": "public ..."
   }
   ```
   `expires_in` = 2592000 秒 = **30 天**，可以缓存复用。
4. 调模型时把 token 拼到 URL query：
   ```
   POST https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/ernie-speed-8k?access_token=<TOKEN>
   ```
   （**不是**放在 Authorization 头里！）

### ③ 预处理
必须先换 token；token 要缓存（带过期时间），过期前刷新。

### ④ 双 Key 需求
**是。** 需要同时填 APIKey 和 SecretKey。现有 APIConfig 只有一个 `apiKey` 字段。

### ⑤ 官方来源
- 调用流程：https://wenxinyiyan.apifox.cn/doc-3251584
- 换 token 接口：https://wenxinyiyan.apifox.cn/api-125213017

### 7.2 火山引擎方舟 —— Bearer API Key（不需要换 token）

**澄清一个常见误解：** 方舟（Ark）调豆包模型**不需要**先换 token。直接：
```
Authorization: Bearer $ARK_API_KEY
```
API Key 在方舟控制台"API Key 管理"页直接创建，长期有效。["https://www.volcengine.com/docs/82379/1298459?lang=zh","https://www.volcengine.com/docs/82379/1541594?lang=zh"]

只有当你要用火山引擎**云服务全局的 AK/SK**（而不是方舟专用 API Key）时，才需要调 `GetApiKey` 接口换一个临时方舟 API Key —— 这是企业 IAM 场景，普通用户用不到。["https://www.volcengine.com/docs/82379/1544136?lang=zh"]

所以对项目来说，方舟 = **bearer** 枚举，不用单独做 access-token-volc。

### 7.3 其他需要先换 token 的
- **Azure Entra ID**（`az account get-access-token`）：企业级，移动端不适用，略。
- **智谱 JWT**：智谱 API Key 格式是 `{id}.{secret}`，可以直接 Bearer 传；也可以拆出来用 secret 自签 JWT（HS256），有效期自己定。项目用 Bearer 直传即可，不用做 JWT。["https://zhipu-ef7018ed.mintlify.app/cn/guide/develop/http/introduction"]

---

## 8. 双 Key 模式（APIKey + APISecret 分开填）

### ① 标识
`auth = dual-key`

### ② 哪些厂商需要同时填两个 Key
| 厂商 | 字段1 | 字段2 | 用途 |
|---|---|---|---|
| **讯飞星火** | APIKey | APISecret | HMAC 签名（§5）。另外还有 APPID（第三个值，放请求体） |
| **百度千帆 ERNIE** | API Key (AK) | Secret Key (SK) | OAuth 换 access_token（§7.1） |
| **智谱（可选）** | APIKey 里的 id 段 | APIKey 里的 secret 段 | 自签 JWT；但直传 Bearer 更简单 |
| **百度智能云 BCE** | Access Key | Secret Key | BCE 签名（§6.1） |

### ③ 对数据模型的要求
现有 `APIConfig` 只有一个 `apiKey` 字段。要支持双 Key，需要新增：
- `apiKey2`（或更语义化的 `apiSecret` / `apiKeySecondary`）
- 讯飞还需要 `appId`（第三个）
- 建议直接用现有的 `additionalParams: Map<String,String>` 存，不用改表结构。

---

## 9. 无鉴权（本地服务）

### ① 标识
`auth = none`

### ② 在用厂商
- **Ollama**（localhost:11434）—— 本地默认无鉴权，谁能访问 11434 端口就能用。["https://github.com/ollama/ollama/blob/main/docs/api/authentication.mdx"]
- **LM Studio / llama.cpp / vLLM / Xinference** 等本地推理服务，默认也不鉴权。
- OpenAI 兼容客户端（如 OpenAI SDK）要求填一个 apiKey 字符串才能初始化，本地服务随便填 `"ollama"` / `"sk-noauth"` 即可，服务端不校验。

### ③ 请求模板
```http
GET http://localhost:11434/api/tags
（不带任何鉴权头）
```
OpenAI 兼容端点：
```
http://localhost:11434/v1/models
Authorization: Bearer ollama     ← 随便填，Ollama 不校验
```

### ④ 预处理
无。

### ⑤ 现有代码能否覆盖
**部分。** 如果 apiKey 填了任意字符串，走 `CUSTOM` → Bearer，Ollama 能通。但如果用户想明确表示"无鉴权"（apiKey 留空），现有代码 `fetchModels` 会直接抛 "API Key 不能为空"。需要加一个 `none` 分支允许空 key。

### ⑥ 官方来源
- https://github.com/ollama/ollama/blob/main/docs/api/authentication.mdx

---

## 10. 特殊鉴权汇总

| 厂商 | 鉴权方式 | 简述 |
|---|---|---|
| Azure OpenAI | `api-key` 头 | §4 |
| AWS Bedrock | SigV4 / Bearer / API Key | §6.3 |
| 腾讯混元原生 | TC3-HMAC-SHA256 | §6.2（建议改用 OpenAI 兼容端点） |
| 百度 BCE | bce-auth-v1 HMAC | §6.1（建议改用 access_token） |
| Anthropic OAuth | `Authorization: Bearer <短期token>` | 工作负载身份联合，移动端用不到 |
| QWeather JWT | RS256 JWT | 项目已实现（`saveQWeatherJwtCredentials`），参考即可 |

---

## 11. 建议的 auth 枚举扩展列表

在 `APIConfig` 里新增一个字段（建议叫 `authType`），枚举值如下：

| 枚举值 | 含义 | header / URL 模板 | 代表厂商 |
|---|---|---|---|
| `bearer` | `Authorization: Bearer <key>` | `Authorization: Bearer {apiKey}` | OpenAI, DeepSeek, Kimi, 智谱, 百炼, 方舟, 混元兼容, MiniMax兼容, SiliconFlow |
| `x-api-key` | 自定义头 x-api-key | `x-api-key: {apiKey}` + `anthropic-version: 2023-06-01` | Anthropic, MiniMax Anthropic兼容 |
| `query-key` | URL query 拼接 | `{baseUrl}?key={apiKey}`（参数名可配，默认 `key`） | Google Gemini, 和风天气 |
| `apikey-header` | Azure 风格 api-key 头 | `api-key: {apiKey}`（版本走 URL query `api-version`） | Azure OpenAI |
| `hmac-xfyun` | 讯飞 HMAC-SHA256 | 每次请求动态算 `Authorization: <base64>`，需要 apiKey2(APISecret) + appId | 讯飞星火 |
| `oauth-baidu` | 百度 OAuth client_credentials | 先 GET `/oauth/2.0/token` 换 access_token，再 `?access_token={token}` 调模型 | 百度千帆 ERNIE |
| `none` | 无鉴权 | 不发任何鉴权头；apiKey 可留空或随便填 | Ollama, LM Studio, 本地 vLLM |

> 你原始设想里的 `hmac-baidu` / `access-token-volc` / `dual-key` 我建议合并掉：
> - `hmac-baidu`（BCE 签名）复杂度高，移动端不推荐，用 `oauth-baidu` 替代。
> - `access-token-volc` 方舟不需要换 token，直接 `bearer` 即可。
> - `dual-key` 不是独立鉴权方式，而是"数据需要两个字段"，由 `hmac-xfyun` 和 `oauth-baidu` 内部各自需要 apiKey2 即可，不必单独枚举。

---

## 12. Java 侧改动点建议（不写代码，只列分支）

### 12.1 APIConfig.java
- 新增字段 `String authType`（默认 `"bearer"`，兼容旧配置）。
- 新增字段 `String apiKey2`（存 APISecret / SecretKey 等第二个密钥；也可以直接塞 `additionalParams`，但单独字段更清晰）。
- 新增字段 `String appId`（讯飞用）。
- `ServiceType` 不动，authType 与 serviceType 正交：serviceType 描述"是哪个厂商"，authType 描述"怎么签"。

### 12.2 APIKeyManager 改造
**核心重构：把 `getAuthHeader` 从"返回单个 String"改成"返回一组 header"。**

新增方法签名（建议）：
```java
Map<String, String> buildAuthHeaders(APIConfig config, String requestUrl, String method)
```
- 入参要带 requestUrl 和 method，因为讯飞签名需要 host + path + method。
- 返回 `Map<headerName, headerValue>`，调用方逐个 `setRequestProperty`。

`testAPIConnection` 里的调用从：
```java
String authHeader = getAuthHeader(config);
if (authHeader != null) connection.setRequestProperty("Authorization", authHeader);
```
改成：
```java
Map<String,String> headers = buildAuthHeaders(config, testUrl, "GET");
for (Map.Entry<String,String> e : headers.entrySet())
    connection.setRequestProperty(e.getKey(), e.getValue());
```

按 authType 分支：
- `bearer` → `{Authorization: "Bearer " + apiKey}`
- `x-api-key` → `{x-api-key: apiKey, anthropic-version: "2023-06-01"}`
- `apikey-header` → `{api-key: apiKey}`
- `query-key` → 不往 header 里加，改 `getTestUrl` 拼 `?key=apiKey`
- `hmac-xfyun` → 新建 `XfyunSigner.sign(config, url, method)`，返回 `{Host, Date, Authorization}` 三个头（HTTP 场景）；WebSocket 场景改拼 URL query
- `oauth-baidu` → 新建 `BaiduTokenProvider.getAccessToken(apiKey, apiKey2)`（带内存缓存 + 过期时间），`getTestUrl` 拼 `?access_token=...`
- `none` → 返回空 Map

### 12.3 getTestUrl(APIConfig) 改造
- 增加 authType 分支：
  - `query-key`：返回 `baseUrl + "/models?key=" + apiKey`
  - `oauth-baidu`：先换 token，再返回 `baseUrl + "/...?access_token=" + token`（注意 ERNIE 原生没有 `/models` 端点，测试要改成调一个最便宜的 chat 接口或直接测 token 接口）
  - `hmac-xfyun`：测试 URL 不能简单拼 `/models`，要按讯飞实际接口路径拼（如 `/v1.1/chat`），且是 POST 不是 GET
  - `none`：直接 `baseUrl + "/models"`
- 现有 GOOGLE 分支硬编码 `?key=`，改走 `query-key` 分支统一处理。

### 12.4 ModelListFetcher.testConnection(apiUrl, apiKey) 改造
当前签名只传了 apiUrl + apiKey，**信息不够**做讯飞/百度签名。建议改成传 `APIConfig` 对象（或至少传 authType + apiKey2 + appId）：
```java
public CompletableFuture<Boolean> testConnection(APIConfig config)
```
内部按 authType 分支：
- `bearer` → 现有逻辑
- `x-api-key` → 现有 anthropic 分支逻辑
- `apikey-header` → `setRequestProperty("api-key", apiKey)`
- `query-key` → URL 拼 `?key=`
- `none` → 不加鉴权头
- `hmac-xfyun` / `oauth-baidu` → 这两类**没有标准 /models 端点**，建议测试方式改成：讯飞测签名生成是否成功（不发网络请求），百度测换 token 接口。

### 12.5 ModelListFetcher.fetchModels 改造
- 当前按 URL contains 猜厂商，改成按 authType 分支。
- `query-key`（Google）：真正发请求 `GET /v1beta/models?key=xxx`，替换掉现在硬编码的模型列表。
- `none`：允许 apiKey 为空（当前代码会抛异常）。
- `apikey-header`（Azure）：Azure 的 `/models` 路径不一样（要带 deployment），如果拿不到就 fallback 到硬编码列表或返回空。
- `hmac-xfyun` / `oauth-baidu`：这两类没有标准 OpenAI `/models` 端点，建议返回内置模型列表（讯飞 spark-4.0 / 文心 ernie-speed 等）。

### 12.6 新增工具类
- `XfyunSigner.java`：封装 §5 的 HMAC-SHA256 + Base64 流程。
- `BaiduAccessTokenCache.java`：内存缓存 access_token，key = APIKey+SecretKey 组合，value = (token, expireTime)。
- `AuthHeadersBuilder.java`：统一入口，按 authType 分发。

### 12.7 兼容旧配置
- 旧配置没有 authType 字段，反序列化时默认 `"bearer"`。
- 但旧 ANTHROPIC 配置在 `getAuthHeader` 里返回的是错的格式（§0.1），需要在迁移时把 serviceType=anthropic 的旧记录自动标记 `authType=x-api-key`。
- 旧 GOOGLE 配置自动标记 `authType=query-key`。

---

## 13. 优先级建议

| 优先级 | 鉴权方式 | 理由 |
|---|---|---|
| P0 | `bearer` 现有 | 已覆盖，保持 |
| P0 | 修 `x-api-key` bug | 现在 Anthropic 鉴权在 APIKeyManager 里是坏的 |
| P1 | `apikey-header` | Azure 用户量不小，加一个分支很便宜 |
| P1 | `none` | Ollama 本地用户刚需 |
| P1 | `query-key` 通用化 | Google 已经在用，抽出来 |
| P2 | `oauth-baidu` | 百度 ERNIE 国内用户多，但要做 token 缓存 |
| P2 | `hmac-xfyun` | 讯飞国内用户多，但签名实现量较大 |
| P3 | BCE / TC3 / SigV4 | 复杂度高，移动端不建议，用各家兼容端点替代 |
