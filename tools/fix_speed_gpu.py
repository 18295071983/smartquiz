# -*- coding: utf-8 -*-
# 推理速度优化：AIService.java
# 1) GPU 层数钳制：非 bigModel 从 20 放宽到 28（Qwen3VL-2B 全量 offload，消除 8 层 CPU 瓶颈）
# 2) KV cache：小模型 GPU 全量（>=24 层）时用 Q8_0（省 50% 内存支撑全量，hybrid 近无损）
import io, sys

path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\service\AIService.java"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

def replace_once(old, new, label):
    global src
    c = src.count(old)
    if c != 1:
        print("%s: count=%d ABORT" % (label, c))
        sys.exit(1)
    src = src.replace(old, new, 1)
    print("%s: OK" % label)

# 1) GPU 层数钳制 20 -> 28（非 bigModel 允许全量）
replace_once(
'''                } else if (availMemMB < 2500) {
                    gpuLayers = Math.min(gpuLayers, bigModel ? 30 : 20);
                    AILogger.i(TAG, "Moderate available memory (" + availMemMB + "MB), limiting GPU layers to " + gpuLayers);
                }''',
'''                } else if (availMemMB < 2500) {
                    // 非 bigModel 允许 28 层全量（Qwen3VL-2B 28 层全 offload，消除 CPU-GPU 交替瓶颈）
                    gpuLayers = Math.min(gpuLayers, bigModel ? 30 : 28);
                    AILogger.i(TAG, "Moderate available memory (" + availMemMB + "MB), limiting GPU layers to " + gpuLayers);
                }''',
'gpu layers clamp 20->28')

# 2) KV cache：小模型 GPU 全量时用 Q8_0
replace_once(
'''            int kvCacheType = 1; // F16 默认
            if (modelSizeMB > 1800) {
                // 4B 级模型：无条件启用 Q4_0（KV -75%）。Qwen3 hybrid-attention 模型上 Q4_0 KV 近无损
                // （llama.cpp 官方实证 BLEU 1.000 @4x 压缩）；不能用"加载时可用内存"判断——加载那一刻
                // 内存可能显得充足，但模型加载后必然推高系统内存（实测 91-93%），Q8_0 都降不下来。
                // native 有 Q4_0→Q8_0→F16 逐级回退兜底，shader 不兼容自动降级，不会崩。
                kvCacheType = 2; // Q4_0：4B 无条件，KV 内存 -75%
                AILogger.i(TAG, "4B model: enabling Q4_0 KV cache (KV memory ~75% saved, modelSize=" + modelSizeMB + "MB)");
            }
            LlamaHelper.setKvCacheType(kvCacheType);''',
'''            int kvCacheType = 1; // F16 默认
            if (modelSizeMB > 1800) {
                // 4B 级模型：无条件启用 Q4_0（KV -75%）。Qwen3 hybrid-attention 模型上 Q4_0 KV 近无损
                // （llama.cpp 官方实证 BLEU 1.000 @4x 压缩）；不能用"加载时可用内存"判断——加载那一刻
                // 内存可能显得充足，但模型加载后必然推高系统内存（实测 91-93%），Q8_0 都降不下来。
                // native 有 Q4_0→Q8_0→F16 逐级回退兜底，shader 不兼容自动降级，不会崩。
                kvCacheType = 2; // Q4_0：4B 无条件，KV 内存 -75%
                AILogger.i(TAG, "4B model: enabling Q4_0 KV cache (KV memory ~75% saved, modelSize=" + modelSizeMB + "MB)");
            } else if (gpuLayers >= 24) {
                // 小模型 GPU 全量（>=24 层）时 KV 用 Q8_0：省 50% 内存支撑全量 offload，
                // Qwen3 hybrid-attention 上 Q8_0 近无损（官方 BLEU 1.000 @2x 压缩），
                // 消除 8 层 CPU 瓶颈的同时控制内存，避免 LMK。
                kvCacheType = 0; // Q8_0
                AILogger.i(TAG, "Small model with full GPU offload (layers=" + gpuLayers + "): enabling Q8_0 KV cache (KV memory ~50% saved)");
            }
            LlamaHelper.setKvCacheType(kvCacheType);''',
'kv cache Q8_0 for full offload')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("ALL PATCHES OK")
