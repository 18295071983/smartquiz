# -*- coding: utf-8 -*-
# AIService.java + native-lib.cpp：小模型 GPU 全量 KV 改 F16 + GPU batch 1024
import io, sys

# ===== AIService.java =====
p1 = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\service\AIService.java"
s1 = io.open(p1, "r", encoding="utf-8").read()
def rep1(old, new, label, expect=1):
    global s1
    c = s1.count(old)
    if c != expect:
        print("[FAIL1] %s: count=%d" % (label, c)); sys.exit(1)
    s1 = s1.replace(old, new, expect)
    print("[OK1] %s" % label)

rep1(
'''            } else if (gpuLayers >= 24) {
                // 小模型 GPU 全量（>=24 层）时 KV 用 Q8_0：省 50% 内存支撑全量 offload，
                // Qwen3 hybrid-attention 上 Q8_0 近无损（官方 BLEU 1.000 @2x 压缩），
                // 消除 8 层 CPU 瓶颈的同时控制内存，避免 LMK。
                kvCacheType = 0; // Q8_0
                AILogger.i(TAG, "Small model with full GPU offload (layers=" + gpuLayers + "): enabling Q8_0 KV cache (KV memory ~50% saved)");
            }''',
'''            } else if (gpuLayers >= 24) {
                // 小模型 GPU 全量（>=24 层）：保持 F16 KV（默认，快）。
                // 2B 级模型 F16 KV 仅 ~350MB，Adreno 840 空闲 4.4GB 充足；Q8_0 虽省 ~50%
                // 但 prefill/decode 的 KV 量化反量化开销显著拖慢吞吐（实测 prefill ~110t/s）。
                // 大模型内存保护已由上方 modelSizeMB>1800 -> Q4_0 分支负责。
                // kvCacheType 保持 1（F16），不再强制 Q8_0。
            }''',
'kv f16 for small model')

io.open(p1, "w", encoding="utf-8", newline="\n").write(s1)

# ===== native-lib.cpp =====
p2 = r"D:\qzq\smartquiz\src\main\cpp\native-lib.cpp"
s2 = io.open(p2, "r", encoding="utf-8").read()
def rep2(old, new, label, expect=1):
    global s2
    c = s2.count(old)
    if c != expect:
        print("[FAIL2] %s: count=%d" % (label, c)); sys.exit(1)
    s2 = s2.replace(old, new, expect)
    print("[OK2] %s" % label)

rep2(
'''        int n_batch_actual = batchSize;
        if (this->gpuLayers > 0 && n_batch_actual < 512) {
            n_batch_actual = 512;
            LOGI("GPU mode: increasing batch size to %d for better throughput", n_batch_actual);
        }''',
'''        int n_batch_actual = batchSize;
        if (this->gpuLayers > 0 && n_batch_actual < 1024) {
            n_batch_actual = 1024;
            LOGI("GPU mode: increasing batch size to %d for better prefill throughput", n_batch_actual);
        }''',
'batch 1024')

io.open(p2, "w", encoding="utf-8", newline="\n").write(s2)
print("KV-F16 + BATCH-1024 OK")
