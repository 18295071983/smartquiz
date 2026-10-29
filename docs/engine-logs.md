# 推理引擎日志清单（2026-10-30 定稿）

> 目的：以后改引擎日志先看这张表，不用再翻 350 条 `LOGI` 逐个确认。
> 维护原则：用户可见（引擎 Tab / 落盘）只保留 **W/E + 少量一次性 INFO**；新增日志先定级再写。

## 1. 数据流与上限

```
native LOGx(...) ──► engineLogAppend
                      ├─ 内存环：最近 800 行（实时 drain 给日志中心）
                      └─ 落盘：files/oilquiz/logs/engine_logs.txt
                              首行 #ENGINE_LOG_V2；>2MB 整份截断重开
日志中心「引擎」Tab：读文件尾部 2000 行 + 打开期间每 1.2s drain 内存环
「清空」：截断落盘文件（native 以 append 打开，截断后继续从末尾写）
```

- 一条日志一行：`\n` / `\r` 在写入前压成空格（避免整段 template 把文件切成无时间戳碎片）。
- 落盘带 `MM-dd HH:mm:ss` 时间戳。
- logcat 仍有完整输出（tag：`LlamaJNI` / `AgentKvCache`）。

## 2. 采集层过滤（不进引擎 Tab，也不落盘）

| 规则 | 说明 |
|---|---|
| `DEBUG` 级 | 一律不进 |
| `llama.cpp` 库 INFO/DEBUG | 建图/张量/create_tensor/eval 细节全部丢弃（只保留库的 W/E） |
| `[PERF]` / `[MEM]` | 计时与内存探针 |
| 逐 token | `Prompt token` / `Generated token` / `Tokenized prompt` / `Starting tokenization` / `Processing prompt as batches` |
| 指针与横幅 | `Model pointer` / `Context pointer` / `Vocab pointer` / `=== ... ===` |

## 3. INFO 清单（35 条语句，均为"每加载/每轮 ≤ 1 行"）

### 3.1 设备与后端（每次加载一轮）

| 消息模板 | 用途 |
|---|---|
| `OpenCL Platform: %s (%s)` | OpenCL 平台/厂商 |
| `GPU Device: %s (%s) - %s` | GPU 型号/厂商/驱动 |
| `Detected Qualcomm Adreno GPU!` | 命中 Adreno 检测 |
| `Detected Adreno 750 / 730 / 740 …` | 具体 GPU 型号 |
| `Detected Vulkan version: %s` | Vulkan API 版本 |
| `Vulkan physical device: %s, API version: …` | Vulkan 物理设备 |
| `Vulkan GPU detected, auto-setting GPU layers to %d` | Vulkan 自动分层 |
| `ggml GPU detected: free=%zuMB, total=%zuMB` | 显存容量 |
| `Using previously detected GPU memory from OpenCL: %zu MB` | 复用显存探测结果 |
| `GPU layers from Java/Default: %d` | 配置的 GPU 层数 |
| `GPU available: %s, Vulkan GPU detected: %s, GPU layers: %d` | 最终 GPU/CPU 分工 |
| `Backend choice set to: %s` | auto/opencl/vulkan 选择结果 |
| `Backend switch: selected device %zu (backend=%s, name=%s)` | 指定设备 |
| `KV cache quantized to Q8_0 / Q4_0 …` | KV 量化档位 |
| `Memory pool budget … limits / OK …` | 内存池对 n_ctx 的约束 |
| `GPU mode working correctly with %d layers` | GPU 推理自检通过 |

### 3.2 模型能力与模板（每次加载/每轮）

| 消息模板 | 用途 |
|---|---|
| `Chat template loaded: %zu chars, preview=%.60s` | 用的是哪套 chat template（只给长度+预览，不再打全文） |
| `refreshThinkingTags: start='%s', %zu end tag(s); tool-call tags %zu open/%zu close` | 思考/工具语法能力（“模型权限”） |
| `ModelMeta: nCtxTrain=…, nEmbd=…, nLayer=…, nHead=…, nParams=…, name=…` | 模型结构元数据 |
| `applyChatTemplateForMessages: template=… bytes, thinking=… (requested/supports), startTag=…` | 模板应用与 thinking 支持判定 |
| `chatJson: tool_choice=%s, enable_thinking=%d, max_tokens=%d, temp=…, topP=…, topK=…` | 本轮请求能力/参数摘要 |
| `chatJson: parsed %zu tools` | 本轮注入的工具数 |
| `chatJson: enable_thinking=%d, template supports=%d, final=%d` | 思考开关最终判定 |
| `chatJson: template applied, prompt length: %zu, format: %s` | 实际格式化结果 |

### 3.3 生成状态与 KV（每轮 ≤ 5 行）

| 消息模板 | 用途 |
|---|---|
| `[GenSM] %s: %s -> %s` | 阶段迁移（PREPROCESS/THINKING/GENERATING/COMPLETE/ERROR） |
| `[GenSM] stop cause: %s (%s)` | 本轮结束原因 + 发生位置 |
| `KV cache reuse: %d%% (reused=%d, new=%d)` | 本轮 KV 前缀复用率 |
| `PARTIAL truncate failed, full eval %zu tokens` | 部分回滚失败退回全量（性能事件） |
| `chatJson: complete, content=%zu chars, tool_calls=%zu` | 每轮完成摘要 |
| `chatJson: complete (degraded), content=%zu chars` | 降级完成摘要 |

## 4. 不产出（LOGV 空宏 / 宏内丢弃，共 173 个调用点）

| 分类 | 代表内容 |
|---|---|
| 性能与内存 | `[PERF] Tokenize/Encode/Sampler/Context shift`、`[MEM]`、warmup 细节 |
| token 明细 | Prompt/Generated token、Tokenized prompt、batch 处理 |
| 模板全文 | 整段 chat template、PROMPT_HEAD/TAIL |
| 加载/后端细节 | 模型文件头 hex、GGUF 校验、后端探测逐项、Vulkan/OpenCL 设备枚举细节、上下文创建/重建、资源释放 |
| 请求细节 | chatJson 的 meta/thinking START/END/partial/fast-path/reasoning 字符数、grammar/采样器参数 |
| 库内部 | llama.cpp INFO/DEBUG（tensor/建图/eval） |
| AgentKvCache 诊断 | `FULL EVAL reason`、周期性 `KV-STATS`（降为 debug） |
| 可选符号 | `Could not find ggml_backend_init in ggml-opencl`（降为 debug，后端由标准注册链接管，非故障） |

## 5. W / E

- **W（84 条语句）**：只在可继续但需要注意时输出，例如 PARTIAL 回滚失败、OpenCL/Vulkan 加载回退、内存池压缩 n_ctx、ggml-opencl 缺失（现为 debug）、KV 量化回退。
- **E（195 条语句）**：失败路径，例如模型加载失败、上下文创建失败、decode/eval 失败、OOM、JNI attach 失败、chatJson 解析/流错误。
- 两者都不做过滤；正常推理时引擎 Tab 不应出现 W/E，出现即代表有事件。

## 6. 新日志判级规则

1. **INFO**：每次加载/每轮最多一条摘要，必须能回答"用什么设备/哪套模板/什么能力/当前阶段/本轮 KV 复用"。
2. **W**：能继续运行但用户应知道（回退、压缩、降级）。
3. **E**：失败。
4. 以下禁止进 INFO：每 token、整段 dump、库内部细节、纯调试参数、指针/横幅。
5. 多行日志必须单行化（采集层已强制）。

## 7. 查看方式

- 引擎 Tab：只看筛选后的 W/E + INFO 摘要；打开时实时、重进读文件历史。
- logcat：`adb logcat -s LlamaJNI:V AgentKvCache:V` 看完整调试流水。
