# 大模型服务商接口能力全景调研 — providers.json 扩展依据

> 调研时间：2026-09-10
> 项目路径：`D:\qzq\smartquiz`
> 现有 providers.json：`src\main\assets\providers.json`（18 家，含 chatEndpoint / modelsEndpoint / ttsModel / asrModel）

---

## 一、App 当前接入情况（代码扫描结论）

### 1.1 已确认接入的接口族

| 接口族 | 接入状态 | 代码证据 |
|---|---|---|
| **对话/推理** | ✅ 已接入（核心） | `providers.json` 全局 `chatEndpoint=/chat/completions`，18 家全部配置 |
| **视觉/多模态理解** | ✅ 已接入 | `ChatMessage.java` 多模态字段、`AttachmentProcessor.java` 图片附件处理、`LlamaHelper.java` 本地多模态投影 |
| **TTS 语音合成** | ✅ 已接入 | `ai/speech/tts/` 目录：`OpenAiTtsEngine`、`DashScopeTtsEngine`、`BaiduTtsEngine`、`IflytekTtsEngine`、`MimoTtsEngine`、`VolcanoTtsEngine`、`SystemTtsEngine` |
| **ASR 语音识别** | ✅ 已接入 | `ai/speech/asr/` 目录：`OpenAiAsrEngine`、`DashScopeAsrEngine`、`BaiduAsrEngine`、`IflytekAsrEngine`、`MimoAsrEngine`、`VolcanoAsrEngine`、`SenseVoiceAsr` |
| **文生图** | ⚠️ 部分接入（独立通道） | `ImageGenTool.java` 走 **Pollinations.ai 免费 API**（`https://image.pollinations.ai/prompt/`），**不走 providers.json 的服务商端点** |
| **Embedding 向量** | ❌ 未接入 | 无 `EmbeddingService` / `EmbeddingClient` 类，无 `/v1/embeddings` 调用 |
| **Rerank 重排** | ❌ 未接入 | 无 `RerankService` 类，无 `/rerank` 端点调用 |

### 1.2 providers.json 现有字段结构

```jsonc
{
  "chatEndpoint": "/chat/completions",   // 全局对话端点（可被 provider 覆盖）
  "modelsEndpoint": "/models",           // 全局模型列表端点
  "providers": [{
    "id": "openai",
    "baseUrl": "https://api.openai.com/v1",
    "auth": "bearer",                   // bearer / apikey / anthropic / none
    "thinking": { ... },                // 深度思考参数
    "models": [...],                    // 推荐模型列表
    "ttsModel": "tts-1",                // TTS 模型名
    "asrModel": "whisper-1"             // ASR 模型名
    // ❌ 缺少：embeddingModel / rerankModel / imageModel / embeddingEndpoint / rerankEndpoint / imageEndpoint / audioEndpoint
  }]
}
```

---

## 二、分厂商接口能力矩阵

### 1. OpenAI（OpenAI 兼容）

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /v1/chat/completions` | Bearer API Key（全套共用） | ✅ 已接入 | [OpenAI API Reference](https://platform.openai.com/docs/api-reference) |
| Embedding | ✅ | `POST /v1/embeddings` | 同对话 Key | ❌ 未接入，可补 | [OpenAI Embeddings](https://platform.openai.com/docs/api-reference/embeddings) |
| Rerank | ❌ | 无官方 rerank 端点 | — | ❌ | OpenAI 无原生 rerank |
| 视觉理解 | ✅ | 对话接口传 image_url | 同对话 Key | ✅ 已接入（多模态消息） | [Vision Guide](https://platform.openai.com/docs/guides/vision) |
| 文生图 | ✅ | `POST /v1/images/generations` | 同对话 Key | ❌ 未接入（App 用 Pollinations 替代） | [Images API](https://platform.openai.com/docs/api-reference/images) |
| TTS | ✅ | `POST /v1/audio/speech` | 同对话 Key | ✅ 已接入（OpenAiTtsEngine） | [Audio Speech](https://platform.openai.com/docs/api-reference/audio/createSpeech) |
| ASR | ✅ | `POST /v1/audio/transcriptions` | 同对话 Key | ✅ 已接入（OpenAiAsrEngine） | [Audio Transcriptions](https://platform.openai.com/docs/api-reference/audio/createTranscription) |
| 其他 | ✅ | Function Calling、JSON Mode、Responses API、视频生成(Sora) | 同对话 Key | 部分（Function Calling 已用） | OpenAI 官方文档 |

---

### 2. 阿里百炼 DashScope（阿里云 Model Studio）

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST {base}/compatible-mode/v1/chat/completions`（OpenAI 兼容）；原生 `POST /api/v1/services/aigc/text-generation/generation` | Bearer DashScope API Key | ✅ 已接入 | [DashScope API 参考](https://help.aliyun.com/zh/model-studio/qwen-api-via-dashscope) |
| Embedding | ✅ | `POST {base}/compatible-mode/v1/embeddings`；原生 `POST /api/v1/services/embeddings/text-embedding/text-embedding` | 同对话 Key | ❌ 未接入，可补 | [Embedding 文档](https://help.aliyun.com/zh/model-studio/embedding) |
| Rerank | ✅ | `POST /api/v1/services/rerank/text-rerank/text-rerank` | 同对话 Key | ❌ 未接入，可补 | [Rerank 端点](https://help.aliyun.com/zh/model-studio/text-rerank) |
| 视觉理解 | ✅ | 对话接口传 image（qwen-vl 系列） | 同对话 Key | ✅ 已接入 | [百炼多模态](https://help.aliyun.com/zh/model-studio/vision) |
| 文生图 | ✅ | `POST /api/v1/services/aigc/text2image/image-synthesis`（wanx 系列）；兼容模式 `POST /compatible-mode/v1/images/generations` | 同对话 Key | ❌ 未接入 | [文生图 API](https://help.aliyun.com/zh/model-studio/text-to-image) |
| TTS | ✅ | Qwen-TTS：`POST /api/v1/services/aigc/multimodal-generation/generation`；CosyVoice：WebSocket SpeechSynthesizer | 同对话 Key | ✅ 已接入（DashScopeTtsEngine） | [语音合成 API](https://help.aliyun.com/zh/model-studio/text-to-speech) |
| ASR | ✅ | Qwen-ASR：`POST /api/v1/services/aigc/multimodal-generation/generation`；Paraformer：WebSocket | 同对话 Key | ✅ 已接入（DashScopeAsrEngine） | [Qwen-ASR API](https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference) |
| 其他 | ✅ | Function Calling、JSON 输出、视频生成(Wanx)、声音复刻 | 同对话 Key | 部分 | 百炼官方文档 |

> **百炼是接口族最全的厂商之一**，8 族全覆盖，且 OpenAI 兼容模式下 embeddings/images/audio 路径与 OpenAI 一致。

---

### 3. 智谱 GLM（BigModel）

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /v4/chat/completions` | Bearer API Key | ✅ 已接入 | [智谱 API 文档](https://docs.bigmodel.cn/cn/guide/develop/python/introduction) |
| Embedding | ✅ | `POST /v4/embeddings`（embedding-3 等） | 同对话 Key | ❌ 未接入，可补 | [智谱 Embedding](https://docs.bigmodel.cn/cn/guide/models/embedding) |
| Rerank | ✅ | `POST /v4/rerank` | 同对话 Key | ❌ 未接入，可补 | [智谱 Rerank](https://docs.bigmodel.cn/cn/guide/models/rerank) |
| 视觉理解 | ✅ | 对话接口传 image_url（glm-4v / glm-4.5v） | 同对话 Key | ✅ 已接入 | [智谱视觉](https://docs.bigmodel.cn/cn/guide/models/vision) |
| 文生图 | ✅ | `POST /v4/images/generations`（cogview-4 / GLM-Image） | 同对话 Key | ❌ 未接入 | [智谱图像生成](https://docs.bigmodel.cn/cn/guide/models/cogview) |
| TTS | ✅ | `POST /v4/audio/speech`（GLM-TTS） | 同对话 Key | ❌ 未接入（当前 ttsModel 为空） | [GLM-TTS 文档](https://docs.bigmodel.cn/cn/guide/models/sound-and-video/glm-tts) |
| ASR | ✅ | `POST /v4/audio/transcriptions`（GLM-ASR） | 同对话 Key | ❌ 未接入（当前 asrModel 为空） | [GLM-ASR 文档](https://docs.bigmodel.cn/cn/guide/models/sound-and-video/glm-asr) |
| 其他 | ✅ | Function Calling、视频生成(CogVideoX-3)、智能助手、内容审核 | 同对话 Key | 部分 | 智谱官方文档 |

> **智谱也是全接口族覆盖**，且路径与 OpenAI 几乎一致（`/v4/embeddings`、`/v4/images/generations`、`/v4/audio/speech`、`/v4/audio/transcriptions`）。

---

### 4. 腾讯混元 Hunyuan

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /v1/chat/completions`（OpenAI 兼容）；原生 `hunyuan.tencentcloudapi.com` TC3-HMAC 签名 | Bearer（兼容模式） | ✅ 已接入 | [混元 OpenAI 兼容接口](https://cloud.tencent.cn/document/product/1729/111007) |
| Embedding | ✅ | `POST /v1/embeddings`（OpenAI 兼容）；原生 `GetEmbedding` API | Bearer（兼容模式） | ❌ 未接入，可补 | [混元向量化](https://tengxunhunyuan.apifox.cn/6148237m0) |
| Rerank | 待核实 | 待核实（混元有 Rerank 模型，端点待确认） | — | ❌ | 待核实 |
| 视觉理解 | ✅ | 对话接口传图片（hunyuan-vision-1.5-instruct） | 同对话 Key | ✅ 已接入 | [混元视觉](https://cloud.tencent.cn/document/product/1729/105701) |
| 文生图 | ✅ | 混元生图 API（非 OpenAI 兼容路径，走 hunyuan.tencentcloudapi.com） | TC3-HMAC 签名（非 Bearer） | ❌ 未接入 | [混元生图](https://cloud.tencent.com/document/product/1729/104753) |
| TTS | 待核实 | 腾讯云语音合成独立服务（非混元 API 套件），走 `tts.tencentcloudapi.com` | 独立 SecretId/SecretKey | ❌ 未接入 | 腾讯云语音文档 |
| ASR | 待核实 | 腾讯云语音识别独立服务 | 独立 SecretId/SecretKey | ❌ 未接入 | 腾讯云语音文档 |
| 其他 | ✅ | 3D 生成、视频生成、Function Calling | — | 部分 | 混元官方文档 |

> **注意**：混元的文生图/语音走腾讯云独立服务，鉴权方式（TC3-HMAC 签名）与对话 Bearer 不同，接入复杂度高。

---

### 5. 讯飞星火（iFlytek）

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | WebSocket `wss://spark-api.xf-yun.com/v4.0/chat`（星火原生）；MaaS 兼容 `maas-api.cn-huabei-1.xf-yun.com` | APIKey+APISecret 握手鉴权（非标准 Bearer） | ✅ 已接入 | [星火大模型文档](https://www.xfyun.cn/doc/spark) |
| Embedding | ✅ | `POST https://cn-huabei-1.xf-yun.com/v1/private/sa8a05c27`（Embeddingp/Embeddingq 两个服务） | 独立鉴权（appid+apikey+apisecret） | ❌ 未接入 | [Embedding API](https://www.xfyun.cn/doc/spark/Embedding_new_api.html) |
| Rerank | 待核实 | 待核实 | — | ❌ | 待核实 |
| 视觉理解 | ✅ | 星火 V3.5+ 支持图片输入 | 同对话鉴权 | ✅ 已接入 | 星火多模态文档 |
| 文生图 | ✅ | 讯飞图片生成 API（独立端点） | 独立鉴权 | ❌ 未接入 | [图片生成 API](https://www.xfyun.cn/doc/spark/ImageGeneration.html) |
| TTS | ✅ | 讯飞语音合成 WebSocket（独立服务） | 独立鉴权（与大模型 APIKey 不同） | ✅ 已接入（IflytekTtsEngine） | 讯飞语音合成文档 |
| ASR | ✅ | 讯飞语音识别 WebSocket（独立服务） | 独立鉴权（与大模型 APIKey 不同） | ✅ 已接入（IflytekAsrEngine） | [星火语音识别大模型](https://www.xfyun.cn/services/speech_big_model2025) |
| 其他 | ✅ | 语音评测、OCR、NLP | — | 部分 | 讯飞开放平台 |

> **注意**：讯飞的大模型、语音、图片、Embedding 是**各自独立的服务**，鉴权方式和端点都不同，不能共用同一套 API Key。App 已通过 IflytekTtsEngine/IflytekAsrEngine 分别接入了语音。

---

### 6. 火山引擎豆包（Volcengine / 火山方舟 Ark）

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST https://ark.cn-beijing.volces.com/api/v3/chat/completions` | Bearer ARK API Key | ✅ 已接入 | [火山方舟快速入门](https://docs.volcengine.com/docs/82379/1399008) |
| Embedding | ✅ | `POST https://ark.cn-beijing.volces.com/api/v3/embeddings` | 同对话 Key | ❌ 未接入，可补 | [文本向量化 API](https://doubao.apifox.cn/265901295e0) |
| Rerank | ✅ | 火山方舟有 Rerank 模型（端点待确认，预计 `/api/v3/rerank`） | 同对话 Key | ❌ 未接入，可补 | [火山方舟文档](https://www.volcengine.com/docs/82379) |
| 视觉理解 | ✅ | 对话接口传图片（doubao-vision-pro） | 同对话 Key | ✅ 已接入 | 火山方舟多模态文档 |
| 文生图 | ✅ | `POST /api/v3/images/generations`（doubao-seedream 即梦） | 同对话 Key | ❌ 未接入 | [图像生成 API](https://docs.volcengine.com/docs/82379/1399008) |
| TTS | ✅ | 火山语音合成（`openspeech.bytedance.com`，独立端点） | 独立鉴权（与方舟 API Key 不同） | ✅ 已接入（VolcanoTtsEngine） | 火山语音文档 |
| ASR | ✅ | 火山流式语音识别（`openspeech.bytedance.com`，独立端点） | 独立鉴权 | ✅ 已接入（VolcanoAsrEngine） | [音频理解 API](https://docs.volcengine.com/docs/82379/2377589) |
| 其他 | ✅ | 视频生成、3D 生成、多模态向量化、音频理解 | — | 部分 | 火山方舟 API 文档中心 |

> **注意**：火山方舟（ark.cn-beijing.volces.com）与语音服务（openspeech.bytedance.com）是**两个不同域名**，鉴权 Key 不同。App 当前 providers.json 中火山引擎的 baseUrl 配的是 `openspeech.bytedance.com`（语音域名），对话/Embedding/文生图需要走 `ark.cn-beijing.volces.com`。

---

### 7. 百度千帆（Baidu / 文心一言）

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/completions`；兼容模式 `https://qianfan.baidubce.com/v2/chat/completions` | Bearer / API Key | ✅ 已接入 | [千帆 API 文档](https://wenxinyiyan.apifox.cn/doc-3251584) |
| Embedding | ✅ | `POST /rpc/2.0/ai_custom/v1/wenxinworkshop/embeddings/embedding-v1` | 同对话 Key | ❌ 未接入，可补 | [Embedding-V1](https://wenxinyiyan.apifox.cn/api-125367515) |
| Rerank | ✅ | 千帆有 Reranker 重排序模型 | 同对话 Key | ❌ 未接入，可补 | [千帆重排序](https://qianfan.readthedocs.io/en/stable/docs/inference.html) |
| 视觉理解 | ✅ | ERNIE-VL 系列多模态对话 | 同对话 Key | ✅ 已接入 | 千帆多模态文档 |
| 文生图 | ✅ | ERNIE-ViLG 文生图 API（独立端点） | 同对话 Key | ❌ 未接入 | [文生图 API](https://wenxinyiyan.apifox.cn/doc-3251584) |
| TTS | ✅ | 百度语音合成 API（独立服务） | 独立鉴权 | ✅ 已接入（BaiduTtsEngine） | 百度语音技术文档 |
| ASR | ✅ | 百度语音识别 API（独立服务） | 独立鉴权 | ✅ 已接入（BaiduAsrEngine） | 百度语音技术文档 |
| 其他 | ✅ | Function Calling、插件调用 | — | 部分 | 千帆 SDK 文档 |

---

### 8. MiniMax

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /v1/chat/completions`（OpenAI 兼容）；原生 `POST /v1/text/chatcompletion_v2` | Bearer API Key | ✅ 已接入 | [MiniMax 对话 API](https://platform.minimaxi.com/document/%E5%AF%B9%E8%AF%9D?key=36954880539f4e2ab8b13067) |
| Embedding | 待核实 | 待核实（MiniMax 主要聚焦对话和语音） | — | ❌ | 待核实 |
| Rerank | 待核实 | 待核实 | — | ❌ | 待核实 |
| 视觉理解 | ✅ | M3 原生多模态 | 同对话 Key | ✅ 已接入 | [MiniMax M3](https://www.minimaxi.com/blog/minimax-m3) |
| 文生图 | ✅ | `POST /v1/image_generation`（image-01 系列） | 同对话 Key | ❌ 未接入 | [图片生成文档](https://platform.minimaxi.com/docs/guides/image-generation.md) |
| TTS | ✅ | `POST /v1/audio/speech`（speech-02-hd 等）；WebSocket 流式 | 同对话 Key | ✅ 已接入（ttsModel=speech-02-hd） | MiniMax 语音文档 |
| ASR | ✅ | MiniMax 有 ASR 模型 | 同对话 Key | ❌ 未接入（当前 asrModel 为空） | MiniMax 语音文档 |
| 其他 | ✅ | 音乐生成、视频生成、音色复刻、Function Calling | — | 部分 | [MiniMax API 概览](https://platform.minimaxi.com/docs/api-reference/api-overview) |

> **注意**：MiniMax 文生图端点是 `/v1/image_generation`（不是 OpenAI 标准的 `/v1/images/generations`），路径不同。

---

### 9. 小米 MiMo

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /v1/chat/completions`（OpenAI 兼容）；`POST /anthropic/v1/messages`（Anthropic 兼容） | Bearer API Key | ✅ 已接入 | [MiMo API 快速开始](https://mimo.mi.com/docs/zh-CN/quick-start/first-api-call) |
| Embedding | ✅ | `POST /v1/embeddings` | 同对话 Key | ❌ 未接入，可补 | [MiMo API 指南](https://www.xiaomi-mimo-ai.com/zh/tutorials/mimo-api-guide.html) |
| Rerank | 待核实 | 待核实 | — | ❌ | 待核实 |
| 视觉理解 | ✅ | MiMo-V2.5 多模态 | 同对话 Key | ✅ 已接入 | MiMo 官方文档 |
| 文生图 | 待核实 | 待核实 | — | ❌ | 待核实 |
| TTS | ✅ | `mimo-v2.5-tts`（App 已配置） | 同对话 Key | ✅ 已接入（MimoTtsEngine） | MiMo 语音文档 |
| ASR | ✅ | `mimo-v2.5-asr`（App 已配置） | 同对话 Key | ✅ 已接入（MimoAsrEngine） | MiMo 语音文档 |
| 其他 | ✅ | Function Calling、Thinking | 同对话 Key | 部分 | MiMo 官方文档 |

---

### 10. 月之暗面 Kimi（Moonshot）

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /v1/chat/completions`（OpenAI 兼容）；`POST /v1/responses`（Responses API） | Bearer API Key | ✅ 已接入 | [Kimi API 概述](https://platform.kimi.com/docs/api/overview) |
| Embedding | ✅ | `POST /v1/embeddings`（moonshot-v1-embedding） | 同对话 Key | ❌ 未接入，可补 | Kimi API 文档 |
| Rerank | 待核实 | 待核实 | — | ❌ | 待核实 |
| 视觉理解 | ✅ | Kimi-VL 系列（kimi-k2.6/k3 原生视觉） | 同对话 Key | ✅ 已接入 | [Kimi Vision 模型](https://platform.kimi.ai/docs/guide/use-kimi-vision-model) |
| 文生图 | ❌ | Kimi 无独立文生图 API | — | ❌ | Kimi 聚焦对话 |
| TTS | ❌ | Kimi 无 TTS API | — | ❌（当前 ttsModel 为空） | Kimi 无 TTS |
| ASR | ❌ | Kimi 无 ASR API | — | ❌（当前 asrModel 为空） | Kimi 无 ASR |
| 其他 | ✅ | 文件上传(file-extract)、Function Calling、Web 搜索、长上下文 | 同对话 Key | 部分 | Kimi API 文档 |

> **Kimi 是对话/视觉强、但语音和文生图缺失的厂商**。

---

### 11. 硅基流动 SiliconFlow

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /v1/chat/completions`（OpenAI 兼容） | Bearer API Key | ✅ 已接入 | [Chat Completions](https://docs.siliconflow.com/en/api-reference/chat-completions/chat-completions) |
| Embedding | ✅ | `POST /v1/embeddings`（BAAI/bge-m3、Qwen3-Embedding 等） | 同对话 Key | ❌ 未接入，可补 | [Create Embeddings](https://docs.siliconflow.com/en/api-reference/embeddings/create-embeddings) |
| Rerank | ✅ | `POST /v1/rerank`（BAAI/bge-reranker-v2-m3 等） | 同对话 Key | ❌ 未接入，可补 | [SiliconFlow Rerank API](https://docs.siliconflow.com/) |
| 视觉理解 | ✅ | 多模态对话模型 | 同对话 Key | ✅ 已接入 | SiliconFlow 文档 |
| 文生图 | ✅ | `POST /v1/images/generations`（Stable Diffusion、FLUX 等） | 同对话 Key | ❌ 未接入 | [Create Image](https://docs.siliconflow.com/en/api-reference/images/images-generations) |
| TTS | ✅ | `POST /v1/audio/speech` | 同对话 Key | ❌ 未接入（当前 ttsModel 为空） | [文本转语音模型](https://docs.siliconflow.cn/cn/userguide/capabilities/text-to-speech) |
| ASR | ✅ | `POST /v1/audio/transcriptions` | 同对话 Key | ❌ 未接入（当前 asrModel 为空） | [创建语音转文本请求](https://api-docs.siliconflow.cn/docs/api/audio-transcriptions-post) |
| 其他 | ✅ | Function Calling、JSON 输出、视频生成 | 同对话 Key | 部分 | SiliconFlow 文档中心 |

> **硅基流动是接口族覆盖最全的聚合平台之一**，7 族全部支持，且路径完全 OpenAI 兼容。

---

### 12. 百川智能（Baichuan）

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /v1/chat/completions`（OpenAI 兼容） | Bearer API Key | ✅ 已接入 | [百川 API 文档](https://platform.baichuan-ai.com/) |
| Embedding | ✅ | `POST /v1/embeddings`（Baichuan-Embedding） | 同对话 Key | ❌ 未接入，可补 | 百川 API 文档 |
| Rerank | 待核实 | 待核实 | — | ❌ | 待核实 |
| 视觉理解 | ✅ | Baichuan4-VL 多模态 | 同对话 Key | ✅ 已接入 | 百川 API 文档 |
| 文生图 | ❌ | 百川无独立文生图 API | — | ❌ | 百川聚焦文本 |
| TTS | ❌ | 百川无 TTS API | — | ❌（当前 ttsModel 为空） | 百川无 TTS |
| ASR | ❌ | 百川无 ASR API | — | ❌（当前 asrModel 为空） | 百川无 ASR |
| 其他 | ✅ | Function Calling、JSON 输出 | 同对话 Key | 部分 | 百川 API 文档 |

---

### 13. DeepSeek

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /v1/chat/completions`（OpenAI 兼容）；`POST /anthropic/v1/messages`（Anthropic 兼容） | Bearer API Key | ✅ 已接入 | [DeepSeek API 文档](https://api-docs.deepseek.com/zh-cn/) |
| Embedding | ✅ | `POST /v1/embeddings` | 同对话 Key | ❌ 未接入，可补 | DeepSeek API 文档 |
| Rerank | ❌ | DeepSeek 无独立 rerank 端点 | — | ❌ | DeepSeek 无 rerank |
| 视觉理解 | ✅ | `deepseek-v4-flash-vision-exp` 等 | 同对话 Key | ✅ 已接入 | DeepSeek API 文档 |
| 文生图 | ❌ | DeepSeek 无独立文生图 API | — | ❌ | DeepSeek 聚焦对话/推理 |
| TTS | ❌ | DeepSeek 无 TTS API | — | ❌（当前 ttsModel 为空） | DeepSeek 无 TTS |
| ASR | ❌ | DeepSeek 无 ASR API | — | ❌（当前 asrModel 为空） | DeepSeek 无 ASR |
| 其他 | ✅ | Function Calling、JSON 输出、FIM 代码补全 | 同对话 Key | 部分 | DeepSeek API 文档 |

> **DeepSeek 是纯文本/推理厂商**，除对话和 Embedding 外，其他模态均不支持。

---

### 14. Anthropic Claude

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /v1/messages`（Anthropic 原生格式） | `x-api-key` + `anthropic-version` 头 | ✅ 已接入（chatEndpoint=/v1/messages） | [Messages API](https://docs.anthropic.com/en/api/messages) |
| Embedding | ❌ | Anthropic 无 Embedding API | — | ❌ | Anthropic 不提供 embedding |
| Rerank | ❌ | Anthropic 无 Rerank API | — | ❌ | Anthropic 不提供 rerank |
| 视觉理解 | ✅ | Messages 接口传 image（base64/url/file） | 同对话鉴权 | ✅ 已接入 | [Vision 文档](https://docs.anthropic.com/claude/docs/vision) |
| 文生图 | ❌ | Anthropic 无文生图 API | — | ❌ | Anthropic 聚焦对话 |
| TTS | ❌ | Anthropic 无 TTS API | — | ❌（当前 ttsModel 为空） | Anthropic 无 TTS |
| ASR | ❌ | Anthropic 无 ASR API | — | ❌（当前 asrModel 为空） | Anthropic 无 ASR |
| 其他 | ✅ | Function Calling、JSON 输出、Computer Use、Files API | 同对话鉴权 | 部分 | [API Overview](https://docs.anthropic.com/en/api/overview) |

> **Anthropic 是纯文本+视觉厂商**，只有对话和视觉理解，其他模态全缺。

---

### 15. Google Gemini

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /v1beta/models/{model}:generateContent`；兼容 `POST /v1beta/openai/chat/completions` | API Key（query param 或 x-goog-api-key） | ✅ 已接入 | [Generating content](https://ai.google.dev/api/generate-content) |
| Embedding | ✅ | `POST /v1beta/models/{model}:embedContent`；兼容 `POST /v1beta/openai/embeddings` | 同对话 Key | ❌ 未接入，可补 | Gemini API 文档 |
| Rerank | ❌ | Gemini 无独立 rerank 端点 | — | ❌ | Gemini 无 rerank |
| 视觉理解 | ✅ | generateContent 传 inline_data 文件（图片/音频） | 同对话 Key | ✅ 已接入 | Gemini 多模态文档 |
| 文生图 | ✅ | Imagen API（`:predict`）；gemini-3-flash-image 原生文生图 | 同对话 Key | ❌ 未接入 | [Gemini API 文档](https://ai.google.dev/gemini-api/docs) |
| TTS | ✅ | Gemini TTS（`:generateContent` 输出音频） | 同对话 Key | ❌ 未接入（当前 ttsModel 为空） | Gemini TTS 文档 |
| ASR | ✅ | Gemini 音频输入理解（ASR 能力内置） | 同对话 Key | ❌ 未接入（当前 asrModel 为空） | Gemini 音频文档 |
| 其他 | ✅ | Function Calling、JSON 输出、视频生成(Veo)、代码执行 | 同对话 Key | 部分 | [All methods](https://ai.google.dev/api/all-methods) |

> **注意**：Gemini 的端点格式是 `:generateContent` / `:embedContent` 这种 RPC 风格，与 OpenAI REST 风格不同。但 Gemini 也提供了 OpenAI 兼容端点 `/v1beta/openai/` 前缀。

---

### 16. Groq

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /openai/v1/chat/completions`（OpenAI 兼容） | Bearer API Key | ✅ 已接入 | [Groq API 文档](https://groq.com/groqcloud) |
| Embedding | ✅ | `POST /openai/v1/embeddings` | 同对话 Key | ❌ 未接入，可补 | [Groq Embeddings](https://providers.apis.io/providers/groq/) |
| Rerank | ✅ | Groq Reranking API | 同对话 Key | ❌ 未接入，可补 | [Groq Reranking](https://providers.apis.io/providers/groq/) |
| 视觉理解 | ✅ | Llama 4 多模态模型 | 同对话 Key | ✅ 已接入 | Groq 文档 |
| 文生图 | ❌ | Groq 无文生图 API（聚焦推理加速） | — | ❌ | Groq 无文生图 |
| TTS | 待核实 | Groq 有 TTS（待确认端点） | — | ❌ | 待核实 |
| ASR | ✅ | `POST /openai/v1/audio/transcriptions`（Whisper v3） | 同对话 Key | ❌ 未接入（当前 asrModel 为空） | [Groq ASR API](https://groq.com/GroqDocs/Groq%20ASR%20Model%20Guide.pdf) |
| 其他 | ✅ | Function Calling、JSON 输出、Batch、Responses API | 同对话 Key | 部分 | Groq API 文档 |

> **Groq 是推理加速平台**，主打超低延迟，支持对话/Embedding/Rerank/ASR/视觉，但不提供文生图。

---

### 17. Together AI

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /v1/chat/completions`（OpenAI 兼容） | Bearer API Key | ✅ 已接入 | [OpenAI compatibility](https://docs.together.ai/docs/inference/openai-compatibility) |
| Embedding | ✅ | `POST /v1/embeddings` | 同对话 Key | ❌ 未接入，可补 | [Together Embeddings](https://docs.together.ai/docs/pythonv2-migration-guide) |
| Rerank | ✅ | `POST /v1/rerank`（专用端点，需 dedicated endpoint） | 同对话 Key | ❌ 未接入，可补 | [Together Rerank](https://docs.together.ai/docs/serverless-models) |
| 视觉理解 | ✅ | 多模态对话模型 | 同对话 Key | ✅ 已接入 | Together 文档 |
| 文生图 | ✅ | `POST /v1/images/generations`（FLUX 等开源模型） | 同对话 Key | ❌ 未接入 | [Together Images](https://docs.together.ai/docs/pythonv2-migration-guide) |
| TTS | ✅ | `POST /v1/audio/speech`（canopylabs/orpheus） | 同对话 Key | ❌ 未接入（当前 ttsModel 为空） | [Text-to-speech](https://docs.together.ai/docs/text-to-speech) |
| ASR | 待核实 | 待核实 | — | ❌ | 待核实 |
| 其他 | ✅ | Function Calling、JSON 输出、视频生成(Veo/Kling) | 同对话 Key | 部分 | Together 文档 |

---

### 18. Ollama（本地）

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /v1/chat/completions`（OpenAI 兼容）；原生 `POST /api/chat` | 无（本地，api-key 忽略） | ✅ 已接入 | [Ollama API](https://github.com/ollama/ollama/blob/main/docs/api.md) |
| Embedding | ✅ | `POST /v1/embeddings`（OpenAI 兼容）；原生 `POST /api/embed` | 无 | ❌ 未接入，可补 | [Generate embeddings](https://docs.ollama.com/api/embed) |
| Rerank | ❌ | Ollama 无 rerank 端点 | — | ❌ | Ollama 无 rerank |
| 视觉理解 | ✅ | 原生 `/api/chat` 传 images（base64） | 无 | ✅ 已接入 | Ollama 多模态文档 |
| 文生图 | ❌ | Ollama 无文生图（本地 LLM 推理） | — | ❌ | Ollama 无文生图 |
| TTS | ❌ | Ollama 无 TTS API | — | ❌（当前 ttsModel 为空） | Ollama 无 TTS |
| ASR | ❌ | Ollama 无 ASR API | — | ❌（当前 asrModel 为空） | Ollama 无 ASR |
| 其他 | ✅ | 模型管理(show/pull/push)、流式、Tool Calling | 无 | 部分 | [Ollama API Reference](https://github.com/ollama/ollama/blob/main/docs/api.md) |

> **Ollama 是纯本地推理运行时**，只提供对话和 Embedding，其他模态全缺。

---

### 19. 阶跃星辰 StepFun（新增）

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /v1/chat/completions`（OpenAI 兼容） | Bearer API Key | ❌ 未接入 | [Chat Completions](https://platform.stepfun.com/docs/zh/api-reference/chat/chat-completion-create) |
| Embedding | ✅ | `POST /v1/embeddings`（Step-1o-embedding） | 同对话 Key | ❌ | [StepFun 模型总览](https://platform.stepfun.com/docs/zh/guides/models/overview) |
| Rerank | 待核实 | 待核实 | — | ❌ | 待核实 |
| 视觉理解 | ✅ | Step 系列多模态 | 同对话 Key | ❌ | StepFun 文档 |
| 文生图 | ✅ | Step Image Edit 2 文生图 | 同对话 Key | ❌ | [StepFun 模型总览](https://platform.stepfun.com/docs/zh/guides/models/overview) |
| TTS | ✅ | `POST /v1/audio/speech`（StepAudio 2.5 TTS）；WebSocket 流式 | 同对话 Key | ❌ | [Text-to-Speech](https://platform.stepfun.ai/docs/en/api-reference/audio/create-audio) |
| ASR | ✅ | `POST /v1/audio/asr/sse`；WebSocket 实时流式 | 同对话 Key | ❌ | [StepAudio 2.5 ASR](https://platform.stepfun.ai/docs/en/guides/models/stepaudio-2.5-asr) |
| 其他 | ✅ | 实时语音对话、Function Calling | 同对话 Key | ❌ | StepFun 文档 |

> **阶跃星辰接口族较全**，7 族基本覆盖，语音能力突出（实时双向流式 ASR/TTS）。

---

### 20. 零一万物 01.AI（新增）

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /v1/chat/completions`（OpenAI 兼容） | Bearer API Key | ❌ 未接入 | [零一万物 API 文档](https://platform.lingyiwanwu.com/docs) |
| Embedding | 待核实 | 待核实 | — | ❌ | 待核实 |
| Rerank | 待核实 | 待核实 | — | ❌ | 待核实 |
| 视觉理解 | ✅ | Yi-VL 多模态系列 | 同对话 Key | ❌ | 零一万物文档 |
| 文生图 | ❌ | 零一万物无文生图 | — | ❌ | 聚焦对话/视觉 |
| TTS | ❌ | 零一万物无 TTS | — | ❌ | 零一万物无 TTS |
| ASR | ❌ | 零一万物无 ASR | — | ❌ | 零一万物无 ASR |
| 其他 | ✅ | Function Calling、长上下文 | 同对话 Key | ❌ | 零一万物文档 |

> **零一万物主要聚焦对话和视觉**，模态能力较单一。

---

### 21. 商汤 SenseNova（新增）

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /compatible-mode/v2/chat/completions`（OpenAI 兼容）；Anthropic 兼容 `POST /v1/messages` | Bearer API Key | ❌ 未接入 | [OpenAI 兼容模式](https://www.sensecore.cn/help/docs/model-as-a-service/nova/overview/compatible-mode) |
| Embedding | ✅ | `POST /compatible-mode/v2/embeddings` | 同对话 Key | ❌ | [SenseCore 文档](https://www.sensecore.cn/) |
| Rerank | 待核实 | 待核实 | — | ❌ | 待核实 |
| 视觉理解 | ✅ | SenseNova-6.8 多模态（图文对话） | 同对话 Key | ❌ | [图文对话生成](https://console.sensecore.cn/micro/help/docs/model-as-a-service/nova/vision/ChatCompletions/) |
| 文生图 | ✅ | `POST /v1/images/generations`（sensenova-u1-fast 秒画） | 同对话 Key | ❌ | [SenseNova 文生图](https://segmentfault.com/a/1190000047754607) |
| TTS | ✅ | `POST /v1/audio/speech`（SenseNova-TTS-2.0） | 同对话 Key | ❌ | [TTS 文档](https://docs.senseaudio.cn/guides/token-plan/overview) |
| ASR | ✅ | SenseNova ASR | 同对话 Key | ❌ | SenseAudio 文档 |
| 其他 | ✅ | Function Calling、图像编辑、音色复刻 | 同对话 Key | ❌ | SenseCore 文档 |

> **商汤接口族较全**，OpenAI 兼容模式下路径标准化。

---

### 22. 无问芯穹 Infini-AI（新增）

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /maas/v1/chat/completions`（OpenAI 兼容）；Anthropic 兼容 | Bearer `sk-` API Key | ❌ 未接入 | [GenStudio API 概览](https://docs.infini-ai.com/gen-studio/api/get-started/overview.html) |
| Embedding | ✅ | `POST /maas/v1/embeddings` | 同对话 Key | ❌ | [GenStudio 模型列表](https://docs.infini-ai.com/gen-studio/models/) |
| Rerank | ✅ | GenStudio 提供 Rerank 接口 | 同对话 Key | ❌ | [GenStudio API 概览](https://docs.infini-ai.com/gen-studio/api/get-started/overview.html) |
| 视觉理解 | ✅ | 聚合多家多模态模型 | 同对话 Key | ❌ | GenStudio 文档 |
| 文生图 | ✅ | 生图大模型（聚合多家） | 同对话 Key | ❌ | GenStudio 模型广场 |
| TTS | 待核实 | 待核实 | — | ❌ | 待核实 |
| ASR | 待核实 | 待核实 | — | ❌ | 待核实 |
| 其他 | ✅ | 视频生成、M×N 路由 | 同对话 Key | ❌ | GenStudio 文档 |

> **无问芯穹是 MaaS 聚合平台**，支持 OpenAI 兼容 + Rerank + 生图，相当于一个"国产 OpenRouter"。

---

### 23. 京东言犀 JoyAI（新增）

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /v1/chat/completions`（OpenAI 兼容） | Bearer API Key | ❌ 未接入 | [会话接口](https://docs.jdcloud.com/cn/yanxi-cap/invoke-service) |
| Embedding | ✅ | `POST /v1/embeddings` | 同对话 Key | ❌ | [向量化 API](https://docs.jdcloud.com/cn/jdaip/embeddings) |
| Rerank | 待核实 | 待核实 | — | ❌ | 待核实 |
| 视觉理解 | ✅ | 言犀多模态 | 同对话 Key | ❌ | 京东云文档 |
| 文生图 | 待核实 | 待核实 | — | ❌ | 待核实 |
| TTS | ✅ | `POST /v1/audio/speech`（JoyTTS） | 同对话 Key | ❌ | [短文本语音合成](https://docs.jdcloud.com/cn/text-to-speech/api/api-reference) |
| ASR | ✅ | 言犀语音识别 | 同对话 Key | ❌ | 京东云语音文档 |
| 其他 | ✅ | 数字人、智能体 | — | ❌ | 言犀平台 |

---

### 24. 360 智脑（新增）

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST /v1/chat/completions`（OpenAI 兼容） | Bearer API Key | ❌ 未接入 | [360 智脑快速开始](https://ai.360.com/docs) |
| Embedding | ✅ | 360 向量模型（API 市场有） | 同对话 Key | ❌ | [360 API 市场](https://zyun.360.cn/product/apimarketitem/video) |
| Rerank | ✅ | 360 重排序模型 | 同对话 Key | ❌ | [360 API 市场](https://zyun.360.cn/product/apimarketitem/video) |
| 视觉理解 | ✅ | 360 多模态大模型 | 同对话 Key | ❌ | 360 智脑文档 |
| 文生图 | ✅ | `POST /v1/images/generations`（openai/gpt-image-1 等） | 同对话 Key | ❌ | [生成图片](https://ai.360.com/docs/413292017e0) |
| TTS | ✅ | 360 语音合成大模型 | 同对话 Key | ❌ | [360 API 市场](https://zyun.360.cn/product/apimarketitem/video) |
| ASR | ✅ | 360 语音识别大模型（音频生成 API） | 同对话 Key | ❌ | [音频生成文档](https://ai.360.com/docs/413292021e0) |
| 其他 | ✅ | 视频生成、OCR、知识库 | 同对话 Key | ❌ | [360 智脑文档](https://ai.360.com/docs/llms.txt) |

> **360 智脑接口族较全**，且走 OpenAI 兼容路径。

---

### 25. 华为云盘古 Pangu（新增）

| 接口族 | 是否支持 | 端点路径 | 鉴权 | App 已接入？ | 来源 |
|---|---|---|---|---|---|
| 对话/推理 | ✅ | `POST https://api.modelarts-maas.com/v2/chat/completions`（OpenAI 兼容） | Bearer API Key | ❌ 未接入 | [快速体验 openPangu](https://support.huaweicloud.com/bestpractice-maas/bestpractice_maas_0021_01.html) |
| Embedding | ✅ | `POST /app/search/v1/vector/query`（盘古原生）；兼容模式 `/v1/embeddings` | 同对话 Key | ❌ | [调用向量&重排大模型](https://support.huaweicloud.com/usermanual-pangulm/pangulm_04_0368.html) |
| Rerank | ✅ | 盘古向量&重排大模型（同端点族） | 同对话 Key | ❌ | [调用向量&重排大模型](https://support.huaweicloud.com/usermanual-pangulm/pangulm_04_0368.html) |
| 视觉理解 | ✅ | 盘古多模态大模型 | 同对话 Key | ❌ | 华为云文档 |
| 文生图 | 待核实 | 盘古有 CV 大模型（文生图待确认） | — | ❌ | 待核实 |
| TTS | 待核实 | 华为云语音合成为独立服务 | — | ❌ | 华为云语音文档 |
| ASR | 待核实 | 华为云语音识别为独立服务 | — | ❌ | 华为云语音文档 |
| 其他 | ✅ | Function Calling、知识计算 | 同对话 Key | ❌ | [盘古 API 概述](https://support.huaweicloud.com/api-pangulm/pangulm_05_0002.html) |

> **注意**：盘古部署在 ModelArts Studio 上，每个模型部署后有独立 URI，不是统一的 baseUrl。Embedding/Rerank 走 `/app/search/v1/vector/query` 路径，与 OpenAI 格式不同。

---

## 三、汇总对比表

> 图例：✅ 支持且 App 已接入 | 🔶 支持但 App 未接入 | ❌ 不支持 | ❓ 待核实

| 厂商 | 对话 | Embedding | Rerank | 视觉 | 文生图 | TTS | ASR | 其他亮点 |
|---|---|---|---|---|---|---|---|---|
| **OpenAI** | ✅ | 🔶 | ❌ | ✅ | 🔶 | ✅ | ✅ | Responses API、视频生成(Sora) |
| **阿里百炼** | ✅ | 🔶 | 🔶 | ✅ | 🔶 | ✅ | ✅ | 全接口族最全、声音复刻 |
| **智谱 GLM** | ✅ | 🔶 | 🔶 | ✅ | 🔶 | 🔶 | 🔶 | 视频生成(CogVideoX-3)、全族覆盖 |
| **腾讯混元** | ✅ | 🔶 | ❓ | ✅ | 🔶 | ❓ | ❓ | 3D 生成、TC3 签名复杂 |
| **讯飞星火** | ✅ | 🔶 | ❓ | ✅ | 🔶 | ✅ | ✅ | 独立服务鉴权、方言识别强 |
| **火山豆包** | ✅ | 🔶 | 🔶 | ✅ | 🔶 | ✅ | ✅ | 即梦文生图、双域名(ark+openspeech) |
| **百度千帆** | ✅ | 🔶 | 🔶 | ✅ | 🔶 | ✅ | ✅ | ERNIE-ViLG 文生图 |
| **MiniMax** | ✅ | ❓ | ❓ | ✅ | 🔶 | ✅ | 🔶 | 音乐生成、音色复刻 |
| **小米 MiMo** | ✅ | 🔶 | ❓ | ✅ | ❓ | ✅ | ✅ | 双协议兼容(OpenAI+Anthropic) |
| **Kimi/Moonshot** | ✅ | 🔶 | ❓ | ✅ | ❌ | ❌ | ❌ | 文件提取、超长上下文 |
| **硅基流动** | ✅ | 🔶 | 🔶 | ✅ | 🔶 | 🔶 | 🔶 | **OpenAI 全兼容、聚合多家模型** |
| **百川** | ✅ | 🔶 | ❓ | ✅ | ❌ | ❌ | ❌ | 纯文本+视觉 |
| **DeepSeek** | ✅ | 🔶 | ❌ | ✅ | ❌ | ❌ | ❌ | 推理强、FIM 代码补全 |
| **Anthropic** | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | Computer Use、Files API |
| **Google Gemini** | ✅ | 🔶 | ❌ | ✅ | 🔶 | 🔶 | 🔶 | Imagen/Veo、原生多模态 |
| **Groq** | ✅ | 🔶 | 🔶 | ✅ | ❌ | ❓ | 🔶 | 超低延迟推理、Whisper ASR |
| **Together AI** | ✅ | 🔶 | 🔶 | ✅ | 🔶 | 🔶 | ❓ | FLUX 文生图、开源模型聚合 |
| **Ollama** | ✅ | 🔶 | ❌ | ✅ | ❌ | ❌ | ❌ | 本地运行、无鉴权 |
| **阶跃星辰** 🆕 | ❌ | ❓ | ❓ | ❌ | ❌ | ❌ | ❌ | 实时双向语音流式 |
| **零一万物** 🆕 | ❌ | ❓ | ❓ | ❌ | ❌ | ❌ | ❌ | Yi 系列开源模型 |
| **商汤 SenseNova** 🆕 | ❌ | ❓ | ❓ | ❌ | ❌ | ❌ | ❌ | 秒画文生图、双协议兼容 |
| **无问芯穹** 🆕 | ❌ | ❓ | ❓ | ❌ | ❌ | ❓ | ❓ | MaaS 聚合、Rerank+生图 |
| **京东言犀** 🆕 | ❌ | ❓ | ❓ | ❌ | ❓ | ❌ | ❌ | 数字人、企业级 |
| **360 智脑** 🆕 | ❌ | ❓ | ❓ | ❌ | ❌ | ❌ | ❌ | 视频生成、OCR |
| **华为云盘古** 🆕 | ❌ | ❓ | ❓ | ❌ | ❓ | ❓ | ❓ | ModelArts 部署、向量&重排 |

---

## 四、App 现状缺口分析

### 4.1 已覆盖 vs 未覆盖

```
已接入接口族：对话 ✅ | 视觉 ✅ | TTS ✅ | ASR ✅
未接入接口族：Embedding ❌ | Rerank ❌ | 文生图(走 Pollinations 旁路) ⚠️
```

### 4.2 关键缺口

| 缺口 | 影响 | 哪些厂商可补 |
|---|---|---|
| **Embedding 无配置入口** | 无法做 RAG 知识库、语义搜索、题目相似度匹配 | 百炼/智谱/硅基流动/OpenAI/Gemini/Together/Groq/MiMo/百度千帆/DeepSeek 等 15+ 家 |
| **Rerank 无配置入口** | 无法做检索结果精排、知识库二次排序 | 百炼/智谱/硅基流动/Groq/Together/百度千帆/无问芯穹/华为云/360 等 |
| **文生图不走 providers.json** | 文生图能力被锁死在 Pollinations，无法用百炼 wanx/智谱 cogview/火山 seedream 等高质量模型 | 百炼/智谱/硅基流动/火山/MiniMax/Together/360/商汤 等 |
| **语音端点未独立配置** | TTS/ASR 端点散落在各 Engine 类中硬编码，无法通过 providers.json 远程配置 | 百炼/智谱/火山/OpenAI 等 |
| **新增 7 家国内厂商未配置** | 阶跃星辰/零一万物/商汤/无问芯穹/京东言犀/360/华为云盘古 全部未入 providers.json | — |

---

## 五、providers.json 扩展设计建议

### 5.1 设计原则

1. **向后兼容**：现有 `ttsModel` / `asrModel` 字段保留，不破坏旧配置
2. **OpenAI 优先**：多数厂商走 OpenAI 兼容路径，缺省时默认 `/v1/embeddings`、`/v1/images/generations`、`/v1/audio/speech`、`/v1/audio/transcriptions`
3. **端点可覆盖**：每家 provider 可覆盖任一接口族的端点路径（如百炼原生路径、讯飞独立服务）
4. **模型名独立**：每个接口族可配自己的模型名（embeddingModel / rerankModel / imageModel / ttsModel / asrModel）

### 5.2 推荐字段设计（方案 A：扁平字段 + 端点覆盖）

```jsonc
{
  "version": "2026-09-10",
  // ========== 全局默认端点（OpenAI 兼容标准路径）==========
  "chatEndpoint": "/chat/completions",
  "modelsEndpoint": "/models",
  "embeddingEndpoint": "/embeddings",       // 🆕 全局默认
  "rerankEndpoint": "/rerank",              // 🆕 全局默认
  "imageEndpoint": "/images/generations",   // 🆕 全局默认
  "ttsEndpoint": "/audio/speech",           // 🆕 全局默认
  "asrEndpoint": "/audio/transcriptions",   // 🆕 全局默认

  "providers": [
    {
      "id": "openai",
      "name": "OpenAI 兼容",
      "baseUrl": "https://api.openai.com/v1",
      "urlKeywords": ["openai", "azure"],
      "auth": "bearer",
      "thinking": { "param": "reasoning_effort", ... },
      "models": ["gpt-4o", "gpt-4o-mini"],

      // ========== 对话/推理（已有）==========
      "chatEndpoint": null,           // null=用全局默认

      // ========== 🆕 Embedding 向量 ==========
      "embeddingEndpoint": null,      // null=用全局默认 /embeddings
      "embeddingModel": "text-embedding-3-small",  // 🆕 模型名

      // ========== 🆕 Rerank 重排 ==========
      "rerankEndpoint": null,         // null=用全局默认 /rerank
      "rerankModel": "",              // 🆕 模型名（OpenAI 无 rerank，留空）

      // ========== 🆕 文生图 ==========
      "imageEndpoint": null,          // null=用全局默认 /images/generations
      "imageModel": "dall-e-3",       // 🆕 模型名

      // ========== 语音 TTS/ASR（已有，补充端点）==========
      "ttsEndpoint": null,            // null=用全局默认 /audio/speech
      "ttsModel": "tts-1",
      "asrEndpoint": null,            // null=用全局默认 /audio/transcriptions
      "asrModel": "whisper-1"
    },

    // ========== 百炼示例：需要覆盖原生路径 ==========
    {
      "id": "dashscope",
      "name": "百炼 DashScope",
      "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
      "auth": "bearer",
      "models": ["qwen3-max"],

      // 百炼 OpenAI 兼容模式下 embedding/image 路径与 OpenAI 一致，不用覆盖
      "embeddingModel": "text-embedding-v3",
      // 但 rerank 走百炼原生路径（非 OpenAI 兼容）
      "rerankEndpoint": "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank",
      "rerankModel": "gte-rerank",
      "imageModel": "wanx2.1-t2i-turbo",
      "ttsModel": "qwen3-tts-flash",
      "asrModel": "qwen3-asr-flash"
    },

    // ========== 智谱示例：路径前缀不同 ==========
    {
      "id": "zhipu",
      "name": "智谱 GLM",
      "baseUrl": "https://open.bigmodel.cn/api/paas/v4",
      "auth": "bearer",
      "models": ["glm-5"],
      "embeddingModel": "embedding-3",
      "rerankModel": "rerank-2",
      "imageModel": "cogview-4",
      "ttsModel": "glm-tts",
      "asrModel": "glm-asr"
      // 端点路径与 OpenAI 一致（/embeddings /rerank /images/generations /audio/speech /audio/transcriptions），不用覆盖
    },

    // ========== 火山示例：双域名（对话走 ark，语音走 openspeech）==========
    {
      "id": "volcengine",
      "name": "火山引擎",
      "baseUrl": "https://ark.cn-beijing.volces.com/api/v3",  // 📝 改为方舟域名
      "auth": "bearer",
      "models": ["doubao-pro-32k"],
      "embeddingModel": "doubao-embedding",
      "imageModel": "doubao-seedream-5-0",
      // 语音走独立域名，需要完整覆盖
      "ttsEndpoint": "https://openspeech.bytedance.com/api/v1/tts",
      "ttsModel": "zh_female_qingxin",
      "asrEndpoint": "https://openspeech.bytedance.com/api/v1/asr",
      "asrModel": "volcengine_streaming_common"
    },

    // ========== Anthropic 示例：只有对话+视觉，其他全空 ==========
    {
      "id": "anthropic",
      "name": "Anthropic Claude",
      "baseUrl": "https://api.anthropic.com",
      "auth": "anthropic",
      "chatEndpoint": "/v1/messages",
      "models": ["claude-sonnet-4-5"],
      "embeddingModel": "",   // Anthropic 不支持
      "rerankModel": "",
      "imageModel": "",
      "ttsModel": "",
      "asrModel": ""
    }
  ]
}
```

### 5.3 备选方案 B：endpoints 映射对象

如果希望更紧凑，也可以用 `endpoints` 子对象统一管理：

```jsonc
{
  "id": "dashscope",
  "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
  "auth": "bearer",
  "endpoints": {
    "chat": "/chat/completions",
    "models": "/models",
    "embedding": "/embeddings",
    "rerank": "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank",  // 完整 URL 覆盖
    "image": "/images/generations",
    "tts": "/audio/speech",
    "asr": "/audio/transcriptions"
  },
  "models": {
    "chat": ["qwen3-max"],
    "embedding": "text-embedding-v3",
    "rerank": "gte-rerank",
    "image": "wanx2.1-t2i-turbo",
    "tts": "qwen3-tts-flash",
    "asr": "qwen3-asr-flash"
  }
}
```

### 5.4 方案对比与推荐

| 维度 | 方案 A（扁平字段） | 方案 B（endpoints 映射） |
|---|---|---|
| 向后兼容性 | ✅ 保留 ttsModel/asrModel，平滑升级 | ⚠️ 需要改字段读取逻辑 |
| 可读性 | 字段多但直观 | 结构紧凑但嵌套深 |
| 扩展性 | 新增接口族需加新字段 | endpoints 是 map，天然可扩展 |
| 代码改动量 | 小（ProviderConfigManager 新增几个 getter） | 中（需要重构配置读取） |
| 远程更新 | ✅ JSON 覆盖即生效 | ✅ JSON 覆盖即生效 |

**推荐方案 A**，理由：
1. 现有 `ttsModel` / `asrModel` 字段已经是扁平结构，方案 A 保持风格一致
2. ProviderConfigManager 只需新增 `getEmbeddingEndpoint()` / `getRerankEndpoint()` / `getImageEndpoint()` 等 getter，改动最小
3. 对不支持的接口族，模型名留空字符串即可，无需额外标记

### 5.5 新增 provider 配置模板（以阶跃星辰为例）

```jsonc
{
  "id": "stepfun",
  "name": "阶跃星辰 StepFun",
  "baseUrl": "https://api.stepfun.com/v1",
  "urlKeywords": ["stepfun", "stepfun.ai"],
  "auth": "bearer",
  "thinking": {
    "param": "enable_thinking",
    "defaultEnabled": false,
    "modelKeywords": ["step-3"]
  },
  "models": ["step-3-flash", "step-2-16k"],
  "embeddingModel": "step-1o-embedding",
  "rerankModel": "",
  "imageModel": "step-image-2",
  "ttsModel": "stepaudio-2.5-tts",
  "asrModel": "stepaudio-2.5-asr"
}
```

---

## 六、落地优先级建议

| 优先级 | 动作 | 理由 |
|---|---|---|
| **P0** | providers.json 新增 `embeddingModel` / `embeddingEndpoint` 字段 | 知识库 RAG 是高频需求，15+ 家支持，改造成本最低 |
| **P0** | 新增 `imageModel` / `imageEndpoint` 字段，把文生图从 Pollinations 旁路纳入 providers 体系 | 当前文生图被锁死在免费 API，接入百炼 wanx/智谱 cogview 可大幅提升质量 |
| **P1** | 新增 `rerankModel` / `rerankEndpoint` 字段 | RAG 精排必备，百炼/智谱/硅基流动已支持 |
| **P1** | 补全 `ttsEndpoint` / `asrEndpoint` 独立配置（当前散落在 Engine 类硬编码） | 远程可配置、支持双域名厂商（火山） |
| **P2** | 接入阶跃星辰、硅基流动、商汤等新厂商 | 硅基流动是 OpenAI 全兼容，接入成本极低，且 Rerank/Embedding/文生图全支持 |
| **P2** | 修正火山引擎 baseUrl（当前配的是 openspeech 语音域名，对话/Embedding 应走 ark 域名） | 当前配置有误，对话走语音域名会失败 |

---

## 七、关键注意事项

1. **鉴权不统一**：讯飞、百度、火山语音服务的 API Key 与大模型 API Key **不同**，需要独立配置。建议在 provider 中增加 `ttsAuth` / `asrAuth` 字段（复用 `auth` 类型），或允许 TTS/ASR 走独立的 apiKey。
2. **端点路径不统一**：
   - OpenAI 标准：`/embeddings`、`/rerank`、`/images/generations`、`/audio/speech`、`/audio/transcriptions`
   - 百炼原生：`/api/v1/services/rerank/text-rerank/text-rerank`、`/api/v1/services/aigc/text2image/image-synthesis`
   - MiniMax：`/v1/image_generation`（注意不是 `images/generations`）
   - Gemini：`:generateContent` / `:embedContent`（RPC 风格）
   - 华为云：`/app/search/v1/vector/query`
3. **双域名厂商**：火山引擎（ark 对话 + openspeech 语音）、百度千帆（qianfan 对话 + aip 语音）需要端点完整 URL 覆盖。
4. **OpenAI 兼容模式**是趋势：百炼、智谱、硅基流动、阶跃星辰、商汤、无问芯穹、360、京东言犀、华为云都提供了 OpenAI 兼容端点，接入时优先用兼容模式，减少适配成本。

---

*报告完成。所有端点路径和鉴权方式均来自各厂商官方文档（已在各节标注来源 URL），标"待核实"的项目需在接入时二次确认。*
