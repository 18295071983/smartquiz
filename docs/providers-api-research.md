# 国内大模型服务商 API 配置调研报告

> 调研日期：2026-09-10
> 用途：新增到 Android 项目 providers.json
> 原则：以官方文档可查证为准，查不到的标 "待核实"



***

## 1. 阶跃星辰 StepFun



| 项目             | 内容                                                                                                                                                               |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 厂商名称           | 阶跃星辰 StepFun                                                                                                                                                     |
| id             | `stepfun`                                                                                                                                                        |
| baseUrl        | `https://api.stepfun.com/v1`                                                                                                                                     |
| chatEndpoint   | `/chat/completions`（OpenAI 兼容）                                                                                                                                   |
| modelsEndpoint | `/models`                                                                                                                                                        |
| auth           | `bearer`，Header 格式：`Authorization: Bearer $STEP_API_KEY`                                                                                                         |
| thinking 参数    | `reasoning_effort`，可选值 `low`/`medium`/`high`（step-3.5-flash-2603 仅支持 low/high）                                                                                   |
| thinking 默认开启  | 推理模型默认开启思考，`reasoning_effort` 用于控制深度档位                                                                                                                           |
| 支持思考的模型关键词     | `step-3.7-flash`、`step-3.5-flash`、`step-3.5-flash-2603`、`step-router-v1`                                                                                         |
| models         | step-3.7-flash、step-3.5-flash、step-3.5-flash-2603、stepaudio-2.5-chat、step-1o-audio、step-audio-2、step-audio-2-mini、step-audio-r1.5、step-router-v1                 |
| urlKeywords    | `stepfun`、`step.ai`、`stepfun.com`                                                                                                                                |
| 官方来源           | [https://platform.stepfun.com/docs/zh/api-reference/chat/chat-completion-create](https://platform.stepfun.com/docs/zh/api-reference/chat/chat-completion-create) |

**JSON 配置条目：**



```
{

&#x20; "id": "stepfun",

&#x20; "name": "阶跃星辰 StepFun",

&#x20; "baseUrl": "https://api.stepfun.com/v1",

&#x20; "urlKeywords": \["stepfun", "step.ai", "stepfun.com"],

&#x20; "auth": "bearer",

&#x20; "thinking": {

&#x20;   "param": "reasoning\_effort",

&#x20;   "defaultEnabled": true,

&#x20;   "modelKeywords": \["step-3.7", "step-3.5-flash", "step-router", "step-audio-r"],

&#x20;   "instruction": "你处于深度推理模式。请先进行充分的内部推理（reasoning），再输出最终答案。\n推理阶段：拆解问题→多角度分析→逐步验证逻辑链条。\n最终回答：结论先行，简洁明确，只保留关键论据，不要输出思考过程。"

&#x20; },

&#x20; "models": \["step-3.7-flash", "step-3.5-flash", "step-3.5-flash-2603", "stepaudio-2.5-chat"],

&#x20; "ttsModel": "",

&#x20; "asrModel": ""

}
```



***

## 2. 零一万物 Yi / 01.AI



| 项目             | 内容                                                                                                         |
| -------------- | ---------------------------------------------------------------------------------------------------------- |
| 厂商名称           | 零一万物 Yi / 01.AI                                                                                            |
| id             | `yi`                                                                                                       |
| baseUrl        | `https://api.lingyiwanwu.com/v1`                                                                           |
| chatEndpoint   | `/chat/completions`（OpenAI 完全兼容）                                                                           |
| modelsEndpoint | `/models`                                                                                                  |
| auth           | `bearer`，Header 格式：`Authorization: Bearer YOUR_API_KEY`                                                    |
| thinking 参数    | 待核实（官方文档未明确标注独立思考开关参数）                                                                                     |
| thinking 默认开启  | 待核实                                                                                                        |
| 支持思考的模型关键词     | 待核实                                                                                                        |
| models         | yi-lightning、yi-large、yi-medium、yi-medium-200k、yi-spark、yi-large-rag、yi-large-fc、yi-large-turbo            |
| urlKeywords    | `lingyiwanwu`、`01.ai`、`yi`、`yi.lighting`                                                                   |
| 官方来源           | [https://platform.lingyiwanwu.com/docs/api-reference](https://platform.lingyiwanwu.com/docs/api-reference) |

**JSON 配置条目：**



```
{

&#x20; "id": "yi",

&#x20; "name": "零一万物 Yi",

&#x20; "baseUrl": "https://api.lingyiwanwu.com/v1",

&#x20; "urlKeywords": \["lingyiwanwu", "01.ai", "yi.lighting"],

&#x20; "auth": "bearer",

&#x20; "thinking": {

&#x20;   "param": "",

&#x20;   "defaultEnabled": false,

&#x20;   "modelKeywords": \[]

&#x20; },

&#x20; "models": \["yi-lightning", "yi-large", "yi-medium", "yi-medium-200k", "yi-spark", "yi-large-turbo"],

&#x20; "ttsModel": "",

&#x20; "asrModel": ""

}
```



***

## 3. 商汤日日新 SenseNova



| 项目             | 内容                                                                                                                                                                                                   |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 厂商名称           | 商汤日日新 SenseNova                                                                                                                                                                                      |
| id             | `sensenova`                                                                                                                                                                                          |
| baseUrl        | `https://token.sensenova.cn/v1`（Token Plan / OpenAI 兼容端点）                                                                                                                                            |
| chatEndpoint   | `/chat/completions`（OpenAI 兼容）                                                                                                                                                                       |
| modelsEndpoint | `/models`（待核实）                                                                                                                                                                                       |
| auth           | `bearer`，Header 格式：`Authorization: Bearer YOUR_API_KEY`                                                                                                                                              |
| thinking 参数    | `reasoning_effort`（DeepSeek 等推理模型在平台上使用）；原生深度推理接口使用 `enabled` 布尔参数                                                                                                                                   |
| thinking 默认开启  | 待核实（DeepSeek V4 默认 high）                                                                                                                                                                             |
| 支持思考的模型关键词     | `sensenova`、`deepseek`、`glm`（平台聚合的推理模型）                                                                                                                                                              |
| models         | sensenova-6.7-flash-lite、sensenova-6.8-flash-lite、sensenova-u1-fast、sensenova-u1.5-lite、SenseNova-V6-Reasoner、deepseek-v4-pro、deepseek-v4-flash、glm-5.2、kimi-k2.6                                    |
| urlKeywords    | `sensenova`、`sensetime`、`sensecore`、`sensenova.cn`                                                                                                                                                   |
| 官方来源           | [https://platform.sensenova.cn/docs](https://platform.sensenova.cn/docs) ；[https://www.sensecore.cn/help/docs/model-as-a-service/nova/](https://www.sensecore.cn/help/docs/model-as-a-service/nova/) |

> 注：商汤有两套接口：
> **Token Plan（推荐新用户）**
>
> ：
>
> `https://token.sensenova.cn/v1`
>
> ，OpenAI 兼容，Bearer 鉴权
> **原生大装置接口**
>
> ：
>
> `https://api.sensenova.cn/v1/llm/chat-completions`
>
> ，Access Key（AK/SK）签名鉴权，接口路径非标准 OpenAI

**JSON 配置条目：**



```
{

&#x20; "id": "sensenova",

&#x20; "name": "商汤日日新 SenseNova",

&#x20; "baseUrl": "https://token.sensenova.cn/v1",

&#x20; "urlKeywords": \["sensenova", "sensetime", "sensecore"],

&#x20; "auth": "bearer",

&#x20; "thinking": {

&#x20;   "param": "reasoning\_effort",

&#x20;   "defaultEnabled": true,

&#x20;   "modelKeywords": \["deepseek", "glm", "kimi", "reasoner", "thinking"],

&#x20;   "instruction": "你处于深度思考模式。对于复杂问题，请先进行系统性的分析推理，再给出最终答案。\n思考阶段：拆解问题→多角度分析→逐步推理验证逻辑链条。\n最终回答：结论先行，简洁明确，只保留关键论据。"

&#x20; },

&#x20; "models": \["sensenova-6.7-flash-lite", "sensenova-6.8-flash-lite", "deepseek-v4-pro", "deepseek-v4-flash", "glm-5.2", "kimi-k2.6"],

&#x20; "ttsModel": "",

&#x20; "asrModel": ""

}
```



***

## 4. 无问芯穹 Infini-AI



| 项目             | 内容                                                                                                                                                 |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| 厂商名称           | 无问芯穹 Infini-AI / 无问科技                                                                                                                              |
| id             | `infini`                                                                                                                                           |
| baseUrl        | `https://cloud.infini-ai.com/maas/v1`                                                                                                              |
| chatEndpoint   | `/chat/completions`（OpenAI 兼容）                                                                                                                     |
| modelsEndpoint | `/models`                                                                                                                                          |
| auth           | `bearer`，Header 格式：`Authorization: Bearer $GENSTUDIO_API_KEY`                                                                                      |
| thinking 参数    | 待核实（平台透传各模型原生参数，推理模型返回 reasoning\_content）                                                                                                         |
| thinking 默认开启  | 待核实                                                                                                                                                |
| 支持思考的模型关键词     | `deepseek-r1`、`deepseek-v3`、`deepseek-v4`、`kimi`、`qwen`                                                                                            |
| models         | deepseek-v4-flash、deepseek-v4-pro、kimi-k2.6、deepseek-r1（已下线建议替换）                                                                                   |
| urlKeywords    | `infini-ai`、`infinigence`、`infini-ai.com`、`cloud.infini-ai.com`                                                                                    |
| 官方来源           | [https://docs.infini-ai.com/gen-studio/api/text-generation/tutorial.html](https://docs.infini-ai.com/gen-studio/api/text-generation/tutorial.html) |

**JSON 配置条目：**



```
{

&#x20; "id": "infini",

&#x20; "name": "无问芯穹 Infini-AI",

&#x20; "baseUrl": "https://cloud.infini-ai.com/maas/v1",

&#x20; "urlKeywords": \["infini-ai", "infinigence", "infini-ai.com"],

&#x20; "auth": "bearer",

&#x20; "thinking": {

&#x20;   "param": "enable\_thinking",

&#x20;   "defaultEnabled": false,

&#x20;   "modelKeywords": \["deepseek-r1", "deepseek-v4", "kimi-k2", "qwen3"]

&#x20; },

&#x20; "models": \["deepseek-v4-flash", "deepseek-v4-pro", "kimi-k2.6"],

&#x20; "ttsModel": "",

&#x20; "asrModel": ""

}
```



***

## 5. 京东言犀 JoyAI / Yanxi



| 项目             | 内容                                                                                                                                                                                   |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 厂商名称           | 京东言犀 JoyAI                                                                                                                                                                           |
| id             | `yanxi`                                                                                                                                                                              |
| baseUrl        | `https://open-omniforce.jdl.com/api/v1`                                                                                                                                              |
| chatEndpoint   | `/chat/completions`（OpenAI 兼容）                                                                                                                                                       |
| modelsEndpoint | `/models`（待核实）                                                                                                                                                                       |
| auth           | `bearer`，支持两种 Header：`Authorization: Bearer 你的密钥` 或 `api-key: 你的密钥`                                                                                                                  |
| thinking 参数    | 待核实（JoyAI 1.0 官方文档提及 "思考 / 非思考模式"，但具体参数名未在公开文档中明确）                                                                                                                                   |
| thinking 默认开启  | 待核实                                                                                                                                                                                  |
| 支持思考的模型关键词     | `JoyAI`、`言犀`、`750B`、`1.3T`                                                                                                                                                           |
| models         | JoyAI-1.0、JoyAI-LLM-Flash、JoyAI-LLM-1.3T、chatrhino-81b-pro、言犀 - 750B                                                                                                                 |
| urlKeywords    | `yanxi`、`jdl.com`、`jdcloud`、`joyai`、`joyagent`                                                                                                                                       |
| 官方来源           | [https://jdai.jd.com/yanxi/build/book](https://jdai.jd.com/yanxi/build/book) ；[https://docs.jdcloud.com/cn/jdaip/FunctionCalling](https://docs.jdcloud.com/cn/jdaip/FunctionCalling) |

> 注：京东有两套平台：
> **JoyAI 开放平台**
>
> （
>
> [open-omniforce.jdl.com](https://open-omniforce.jdl.com)
>
> ）：自研言犀 / JoyAI 模型，OpenAI 兼容
> **JoyBuilder 模型开发平台 2.0**
>
> （
>
> [modelservice.jdcloud.com](https://modelservice.jdcloud.com)
>
> ）：聚合多厂商模型（DeepSeek、Qwen、GLM 等），OpenAI 兼容

**JSON 配置条目：**



```
{

&#x20; "id": "yanxi",

&#x20; "name": "京东言犀 JoyAI",

&#x20; "baseUrl": "https://open-omniforce.jdl.com/api/v1",

&#x20; "urlKeywords": \["yanxi", "jdl.com", "jdcloud", "joyai", "joyagent"],

&#x20; "auth": "bearer",

&#x20; "thinking": {

&#x20;   "param": "",

&#x20;   "defaultEnabled": false,

&#x20;   "modelKeywords": \["JoyAI", "言犀", "750B", "1.3T"]

&#x20; },

&#x20; "models": \["JoyAI-1.0", "JoyAI-LLM-Flash", "JoyAI-LLM-1.3T", "chatrhino-81b-pro"],

&#x20; "ttsModel": "",

&#x20; "asrModel": ""

}
```



***

## 6. 360 智脑



| 项目             | 内容                                                                                                                                                     |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 厂商名称           | 360 智脑                                                                                                                                                 |
| id             | `zhinao`                                                                                                                                               |
| baseUrl        | `https://api.360.cn/v1`                                                                                                                                |
| chatEndpoint   | `/chat/completions`（OpenAI 兼容）                                                                                                                         |
| modelsEndpoint | `/models`（待核实）                                                                                                                                         |
| auth           | `bearer`，Header 格式：`Authorization: Bearer your-api-key-value`                                                                                          |
| thinking 参数    | 待核实                                                                                                                                                    |
| thinking 默认开启  | 待核实                                                                                                                                                    |
| 支持思考的模型关键词     | `360gpt2-o1`、`o1`                                                                                                                                      |
| models         | 360GPT2-Pro、360GPT-Turbo、360GPT-Turbo-32K-Agent、360 智脑 - 7B-360K、360-deepseek-r1                                                                       |
| urlKeywords    | `360`、`360cn`、`zhinao`、`ai.360.com`、`api.360.cn`                                                                                                       |
| 官方来源           | [https://ai.360.com/docs/quick-start](https://ai.360.com/docs/quick-start) ；[https://ai.360.com/docs/413292007e0](https://ai.360.com/docs/413292007e0) |

**JSON 配置条目：**



```
{

&#x20; "id": "zhinao",

&#x20; "name": "360智脑",

&#x20; "baseUrl": "https://api.360.cn/v1",

&#x20; "urlKeywords": \["360", "360cn", "zhinao", "ai.360.com"],

&#x20; "auth": "bearer",

&#x20; "thinking": {

&#x20;   "param": "",

&#x20;   "defaultEnabled": false,

&#x20;   "modelKeywords": \["360gpt2-o1", "o1", "deepseek-r1"]

&#x20; },

&#x20; "models": \["360GPT2-Pro", "360GPT-Turbo", "360GPT-Turbo-32K-Agent", "360-deepseek-r1"],

&#x20; "ttsModel": "",

&#x20; "asrModel": ""

}
```



***

## 7. 华为云 ModelArts / 盘古大模型



| 项目             | 内容                                                                                                                                                                                                                                     |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 厂商名称           | 华为云 ModelArts / 盘古大模型                                                                                                                                                                                                                  |
| id             | `huawei`                                                                                                                                                                                                                               |
| baseUrl        | `https://api.modelarts-maas.com/openai`                                                                                                                                                                                                |
| chatEndpoint   | `/v1/chat/completions`（OpenAI 兼容）                                                                                                                                                                                                      |
| modelsEndpoint | `/v1/models`（待核实）                                                                                                                                                                                                                      |
| auth           | `bearer`，Header 格式：`Authorization: Bearer 该服务所在Region的ApiKey`                                                                                                                                                                          |
| thinking 参数    | 待核实（盘古 N2/N4 系列支持快慢思考融合，具体参数名待官方文档确认）                                                                                                                                                                                                  |
| thinking 默认开启  | 待核实                                                                                                                                                                                                                                    |
| 支持思考的模型关键词     | `pangu`、`reasoner`、`deepseek`、`kimi`                                                                                                                                                                                                   |
| models         | openPangu-2.0-Flash、openPangu-2.0-Pro、DeepSeek-V4-Pro、Kimi-K2.6、Pangu-NLP-N1、Pangu-NLP-N2、Pangu-NLP-N4                                                                                                                                 |
| urlKeywords    | `huaweicloud`、`modelarts`、`pangu`、`modelarts-maas`                                                                                                                                                                                     |
| 官方来源           | [https://support.huaweicloud.com/qs-maas/qs-maas-0001.html](https://support.huaweicloud.com/qs-maas/qs-maas-0001.html) ；[https://support.huaweicloud.com/model-call-modelarts/](https://support.huaweicloud.com/model-call-modelarts/) |

> 注：华为云 MaaS 平台聚合了盘古自研 + 三方模型（DeepSeek、Kimi、Qwen 等），统一通过 OpenAI 兼容接口调用。区域不同 baseUrl 不同（如 
>
> `api.modelarts-maas.com`
>
>  或 
>
> `api-ap-southeast-1.modelarts-maas.com`
>
> ）。

**JSON 配置条目：**



```
{

&#x20; "id": "huawei",

&#x20; "name": "华为云 ModelArts 盘古",

&#x20; "baseUrl": "https://api.modelarts-maas.com/openai",

&#x20; "urlKeywords": \["huaweicloud", "modelarts", "pangu", "modelarts-maas"],

&#x20; "auth": "bearer",

&#x20; "thinking": {

&#x20;   "param": "",

&#x20;   "defaultEnabled": false,

&#x20;   "modelKeywords": \["pangu", "reasoner", "deepseek", "kimi", "n2", "n4"]

&#x20; },

&#x20; "models": \["openPangu-2.0-Flash", "openPangu-2.0-Pro", "DeepSeek-V4-Pro", "Kimi-K2.6"],

&#x20; "ttsModel": "",

&#x20; "asrModel": ""

}
```



***

## 8. 百度千帆



| 项目             | 内容                                                                                                                                                                                                                                                                 |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 厂商名称           | 百度千帆 ModelBuilder                                                                                                                                                                                                                                                  |
| id             | `qianfan`                                                                                                                                                                                                                                                          |
| baseUrl        | `https://qianfan.baidubce.com/v2`                                                                                                                                                                                                                                  |
| chatEndpoint   | `/chat/completions`（OpenAI 兼容）                                                                                                                                                                                                                                     |
| modelsEndpoint | `/models`（待核实）                                                                                                                                                                                                                                                     |
| auth           | `bearer`，Header 格式：`Authorization: Bearer bce-v3/ALTAK-xxx/xxx`                                                                                                                                                                                                    |
| thinking 参数    | `thinking_budget`（DeepSeek-V3.2-Think / DeepSeek-V3.1-Think 模型用于控制思维链长度）；ERNIE 系列思考参数待核实                                                                                                                                                                           |
| thinking 默认开启  | 待核实                                                                                                                                                                                                                                                                |
| 支持思考的模型关键词     | `deepseek-r1`、`deepseek-v3`、`deepseek-v4`、`ernie-4.5-thinking`、`thinking`                                                                                                                                                                                          |
| models         | ernie-5.1、ernie-4.5-turbo、ernie-speed-8k、deepseek-r1、deepseek-v3.1、deepseek-v3.2、deepseek-v4-flash、deepseek-v4-pro、glm-5.2、kimi-k2.6                                                                                                                               |
| urlKeywords    | `qianfan`、`baidubce`、`wenxin`、`qianfan.cloud.baidu.com`                                                                                                                                                                                                            |
| 官方来源           | [https://qianfan.cloud.baidu.com](https://qianfan.cloud.baidu.com) ；[https://bce-cdn.bj.bcebos.com/p3m/pdf/ai-cloud-share/online/WENXINWORKSHOP/WENXINWORKSHOP.pdf](https://bce-cdn.bj.bcebos.com/p3m/pdf/ai-cloud-share/online/WENXINWORKSHOP/WENXINWORKSHOP.pdf) |

> 注：此为千帆 ModelBuilder V2 版 OpenAI 兼容接口（区别于 
>
> [aip.baidubce.com](https://aip.baidubce.com)
>
>  的旧版语音 / 文心接口）。API Key 格式为 
>
> `bce-v3/ALTAK-xxx/xxx`
>
> 。

**JSON 配置条目：**



```
{

&#x20; "id": "qianfan",

&#x20; "name": "百度千帆 ModelBuilder",

&#x20; "baseUrl": "https://qianfan.baidubce.com/v2",

&#x20; "urlKeywords": \["qianfan", "baidubce", "wenxin"],

&#x20; "auth": "bearer",

&#x20; "thinking": {

&#x20;   "param": "thinking\_budget",

&#x20;   "defaultEnabled": false,

&#x20;   "modelKeywords": \["deepseek-r1", "deepseek-v3", "deepseek-v4", "ernie-4.5-thinking", "thinking"],

&#x20;   "instruction": "你处于深度思考模式。对于复杂问题，请先进行系统性的分析推理，再给出最终答案。\n思考阶段：拆解问题→多角度分析→逐步推理验证逻辑链条。\n最终回答：结论先行，简洁明确，只保留关键论据。"

&#x20; },

&#x20; "models": \["ernie-5.1", "ernie-4.5-turbo", "deepseek-r1", "deepseek-v3.2", "deepseek-v4-flash", "deepseek-v4-pro", "glm-5.2", "kimi-k2.6"],

&#x20; "ttsModel": "",

&#x20; "asrModel": ""

}
```



***

## 9. 腾讯混元独立端点（补充完善）

> 现有 providers.json 已有 hunyuan 条目，以下为基于官方文档的补充完善。



| 项目             | 内容                                                                                                                                                                                                                                 |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 厂商名称           | 腾讯混元大模型                                                                                                                                                                                                                            |
| id             | `hunyuan`                                                                                                                                                                                                                          |
| baseUrl        | `https://api.hunyuan.cloud.tencent.com/v1`                                                                                                                                                                                         |
| chatEndpoint   | `/chat/completions`（OpenAI 兼容）                                                                                                                                                                                                     |
| modelsEndpoint | `/models`（待核实）                                                                                                                                                                                                                     |
| auth           | `bearer`，Header 格式：`Authorization: Bearer your-api-key`                                                                                                                                                                            |
| thinking 参数    | `enable_thinking`（OpenAI 兼容接口）；原生 API 为 `EnableThinking`（Boolean，默认开启）                                                                                                                                                             |
| thinking 默认开启  | 未传值时默认开启思维链推理能力（仅对 hunyuan-a13b 等模型生效）                                                                                                                                                                                             |
| 支持思考的模型关键词     | `hunyuan-t1`、`hunyuan-2.0-thinking`、`hunyuan-turbos`、`a13b`                                                                                                                                                                        |
| models         | hunyuan-turbos-latest、hunyuan-turbo、hunyuan-pro、hunyuan-2.0-thinking-20251109、hunyuan-2.0-instruct-20251111、hunyuan-T1-latest、hunyuan-a13b、hunyuan-vision-1.5-instruct                                                             |
| urlKeywords    | `hunyuan`、`tencent`、`hunyuan.cloud.tencent.com`                                                                                                                                                                                    |
| 官方来源           | [https://cloud.tencent.com/document/product/1729/105701](https://cloud.tencent.com/document/product/1729/105701) ；[https://cloud.tencent.com/document/product/1729/116755](https://cloud.tencent.com/document/product/1729/116755) |

**建议更新后的 JSON 配置条目：**



```
{

&#x20; "id": "hunyuan",

&#x20; "name": "腾讯混元",

&#x20; "baseUrl": "https://api.hunyuan.cloud.tencent.com/v1",

&#x20; "urlKeywords": \["hunyuan", "tencent"],

&#x20; "auth": "bearer",

&#x20; "thinking": {

&#x20;   "param": "enable\_thinking",

&#x20;   "defaultEnabled": true,

&#x20;   "modelKeywords": \["hunyuan-t1", "hunyuan-2.0-thinking", "hunyuan-turbos", "hunyuan-a13b", "hunyuan-pro"],

&#x20;   "instruction": "你处于深度思考模式。对于复杂问题，请先进行系统性的分析推理，再给出最终答案。\n思考阶段：拆解问题→多角度分析→逐步推理验证逻辑链条。\n最终回答：结论先行，简洁明确，只保留关键论据。"

&#x20; },

&#x20; "models": \["hunyuan-turbos-latest", "hunyuan-turbo", "hunyuan-pro", "hunyuan-2.0-thinking-20251109", "hunyuan-T1-latest"],

&#x20; "ttsModel": "",

&#x20; "asrModel": ""

}
```



***

## 10. 阿里百炼专属空间（Workspace 专属域名模式）



| 项目             | 内容                                                                                                                                                                                                                                                                 |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 厂商名称           | 阿里百炼专属空间                                                                                                                                                                                                                                                           |
| id             | `bailian-workspace`                                                                                                                                                                                                                                                |
| baseUrl        | `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`                                                                                                                                                                                            |
| chatEndpoint   | `/chat/completions`（OpenAI 兼容）                                                                                                                                                                                                                                     |
| modelsEndpoint | `/models`（待核实）                                                                                                                                                                                                                                                     |
| auth           | `bearer`，Header 格式：`Authorization: Bearer $DASHSCOPE_API_KEY`                                                                                                                                                                                                      |
| thinking 参数    | `enable_thinking`（与 dashscope 公共域名一致）                                                                                                                                                                                                                              |
| thinking 默认开启  | false                                                                                                                                                                                                                                                              |
| 支持思考的模型关键词     | `qwen3`、`qwen3.5`、`qwen3-thinking`                                                                                                                                                                                                                                 |
| models         | qwen3.8-max、qwen3.5-plus、qwen3.5-flash、qwen3-max、qwen3-plus、qwen3-flash                                                                                                                                                                                            |
| urlKeywords    | `maas.aliyuncs.com`、`workspace`、`bailian`                                                                                                                                                                                                                          |
| 官方来源           | [https://help.aliyun.com/zh/model-studio/base-url](https://help.aliyun.com/zh/model-studio/base-url) ；[https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions) |

> 注：百炼专属空间域名格式为 
>
> `[workspace-id].[region].maas.aliyuncs.com`
>
> ，推荐用于生产环境，提供更高吞吐、更低时延与业务空间级流量隔离。国内北京区域为 
>
> `cn-beijing`
>
> ，新加坡为 
>
> `ap-southeast-1`
>
> 。

**JSON 配置条目：**



```
{

&#x20; "id": "bailian-workspace",

&#x20; "name": "阿里百炼专属空间",

&#x20; "baseUrl": "https://{workspace-id}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",

&#x20; "urlKeywords": \["maas.aliyuncs.com", "bailian-workspace"],

&#x20; "auth": "bearer",

&#x20; "thinking": {

&#x20;   "param": "enable\_thinking",

&#x20;   "defaultEnabled": false,

&#x20;   "modelKeywords": \["qwen3", "qwen3.5", "qwen3-thinking"],

&#x20;   "instruction": "你处于深度思考模式。对于复杂问题，请先进行系统性的分析推理，再给出最终答案。\n思考阶段：拆解问题→多角度分析→逐步推理验证逻辑链条。\n最终回答：结论先行，简洁明确，只保留关键论据。"

&#x20; },

&#x20; "models": \["qwen3.8-max", "qwen3.5-plus", "qwen3.5-flash", "qwen3-max", "qwen3-plus", "qwen3-flash"],

&#x20; "ttsModel": "qwen3-tts-flash",

&#x20; "asrModel": "qwen3-asr-flash"

}
```



***

## 11. 其他可查证的国内聚合 / 中转平台

### 11.1 302.AI 聚合平台



| 项目             | 内容                                                                                             |
| -------------- | ---------------------------------------------------------------------------------------------- |
| 厂商名称           | 302.AI                                                                                         |
| id             | `302ai`                                                                                        |
| baseUrl        | `https://api.302.ai/v1`                                                                        |
| chatEndpoint   | `/chat/completions`（OpenAI 兼容）                                                                 |
| modelsEndpoint | `/models`（待核实）                                                                                 |
| auth           | `bearer`，Header 格式：`Authorization: Bearer YOUR_API_KEY`                                        |
| thinking 参数    | 透传各模型原生参数                                                                                      |
| thinking 默认开启  | 待核实                                                                                            |
| 支持思考的模型关键词     | 透传各模型关键词                                                                                       |
| models         | 聚合多家（GPT、Claude、DeepSeek、Kimi、通义千问、GLM 等）                                                      |
| urlKeywords    | `302.ai`、`302ai`                                                                               |
| 官方来源           | [https://doc.302.ai](https://doc.302.ai) ；[https://302ai.apifox.cn/](https://302ai.apifox.cn/) |

**JSON 配置条目：**



```
{

&#x20; "id": "302ai",

&#x20; "name": "302.AI 聚合平台",

&#x20; "baseUrl": "https://api.302.ai/v1",

&#x20; "urlKeywords": \["302.ai", "302ai"],

&#x20; "auth": "bearer",

&#x20; "thinking": {

&#x20;   "param": "enable\_thinking",

&#x20;   "defaultEnabled": false,

&#x20;   "modelKeywords": \[]

&#x20; },

&#x20; "models": \[],

&#x20; "ttsModel": "",

&#x20; "asrModel": ""

}
```



***

## 汇总表



| 厂商              | baseUrl                                                                  | auth 方式 | thinking 参数        | 状态   | 官方来源                                                                                       |
| --------------- | ------------------------------------------------------------------------ | ------- | ------------------ | ---- | ------------------------------------------------------------------------------------------ |
| 阶跃星辰 StepFun    | `https://api.stepfun.com/v1`                                             | bearer  | `reasoning_effort` | 已核实  | [platform.stepfun.com/docs](https://platform.stepfun.com/docs)                             |
| 零一万物 Yi         | `https://api.lingyiwanwu.com/v1`                                         | bearer  | 待核实                | 部分核实 | [platform.lingyiwanwu.com/docs](https://platform.lingyiwanwu.com/docs)                     |
| 商汤日日新 SenseNova | `https://token.sensenova.cn/v1`                                          | bearer  | `reasoning_effort` | 已核实  | [platform.sensenova.cn/docs](https://platform.sensenova.cn/docs)                           |
| 无问芯穹 Infini-AI  | `https://cloud.infini-ai.com/maas/v1`                                    | bearer  | 待核实                | 部分核实 | [docs.infini-ai.com](https://docs.infini-ai.com)                                           |
| 京东言犀 JoyAI      | `https://open-omniforce.jdl.com/api/v1`                                  | bearer  | 待核实                | 部分核实 | [jdai.jd.com/yanxi](https://jdai.jd.com/yanxi)                                             |
| 360 智脑          | `https://api.360.cn/v1`                                                  | bearer  | 待核实                | 部分核实 | [ai.360.com/docs](https://ai.360.com/docs)                                                 |
| 华为云 ModelArts   | `https://api.modelarts-maas.com/openai`                                  | bearer  | 待核实                | 部分核实 | [support.huaweicloud.com](https://support.huaweicloud.com)                                 |
| 百度千帆            | `https://qianfan.baidubce.com/v2`                                        | bearer  | `thinking_budget`  | 已核实  | [qianfan.cloud.baidu.com](https://qianfan.cloud.baidu.com)                                 |
| 腾讯混元            | `https://api.hunyuan.cloud.tencent.com/v1`                               | bearer  | `enable_thinking`  | 已核实  | [cloud.tencent.com/document/product/1729](https://cloud.tencent.com/document/product/1729) |
| 阿里百炼专属空间        | `https://{workspace-id}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1` | bearer  | `enable_thinking`  | 已核实  | [help.aliyun.com/zh/model-studio](https://help.aliyun.com/zh/model-studio)                 |
| 302.AI 聚合       | `https://api.302.ai/v1`                                                  | bearer  | 透传各模型              | 已核实  | doc.302.ai                                                                                 |



***

## 备注



1. **鉴权方式**：本次调研的所有厂商 OpenAI 兼容端点均使用 `bearer` 鉴权（`Authorization: Bearer YOUR_API_KEY`），与现有 providers.json 中绝大多数厂商一致。

2. **思考参数差异**：

* StepFun、SenseNova：使用 `reasoning_effort`（low/medium/high 档位控制）

* 百度千帆：使用 `thinking_budget`（控制思维链 token 长度）

* 腾讯混元、百炼：使用 `enable_thinking`（布尔开关）

* 其余厂商待核实

1. **商汤双端点**：Token Plan 端点（[token.sensenova.cn/v1](https://token.sensenova.cn/v1)）为推荐新用户使用的 OpenAI 兼容端点；原生大装置端点（[api.sensenova.cn/v1/llm/](https://api.sensenova.cn/v1/llm/)）使用 AK/SK 签名鉴权，非标准 OpenAI 路径。

2. **华为云区域差异**：ModelArts MaaS 的 baseUrl 随区域变化，国内一般为 `api.modelarts-maas.com`，海外为 `api-ap-southeast-1.modelarts-maas.com` 等。

3. **待核实项**：零一万物、无问芯穹、京东言犀、360 智脑、华为云的思考参数名及 models 端点路径需在实际控制台或更详细官方文档中确认。