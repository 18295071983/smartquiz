# -*- coding: utf-8 -*-
# 4B 模型防御性收紧：ctx 6144 / batch 512 / memoryPool 1200
import io, sys

def patch(path, old, new, label, must=1):
    s = io.open(path, "r", encoding="utf-8").read()
    c = s.count(old)
    if c != must:
        print("[FAIL] %s: count=%d" % (label, c)); sys.exit(1)
    s = s.replace(old, new)
    io.open(path, "w", encoding="utf-8", newline="\n").write(s)
    print("[OK] %s" % label)

# 1) AIService: 4B context cap 8192 -> 6144
patch(r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\service\AIService.java",
'''        } else if (modelSizeMB >= 1800) {
            // 4B 级（Qwen3-VL-4B Q4≈2381MB）也降档：KV 是 2B 的 ~1.6 倍（nLayer 28→36、nEmbd 2048→2560），
            // 同 context 下内存压力显著更高，且 Agent 场景 8K 足够（历史有压缩）。
            contextSize = Math.min(contextSize, 8192);
            AILogger.i(TAG, "4B-level model (" + modelSizeMB + "MB): context capped to " + contextSize);''',
'''        } else if (modelSizeMB >= 1800) {
            // 4B 级（Qwen3-VL-4B Q4≈2381MB）也降档：KV 是 2B 的 ~1.6 倍（nLayer 28→36、nEmbd 2048→2560），
            // 同 context 下内存压力显著更高，且 Agent 场景 6K 足够（历史有压缩）。
            // 收紧到 6144：Q4_0 KV @6144 ≈ 216MB；若 Q4_0 shader 回退 F16 ≈ 576MB，
            // 权重 2.4GB 常驻时 6K 是内存安全的平衡点（实测 8K 会触发 LMK thrashing）。
            contextSize = Math.min(contextSize, 6144);
            AILogger.i(TAG, "4B-level model (" + modelSizeMB + "MB): context capped to " + contextSize);''',
'4B ctx cap 6144')

# 2) AIService: 4B batch/memoryPool 钳制（在 batchSize/memoryPoolSize 定义后）
patch(r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\service\AIService.java",
'''            int threadCount = resourceConfig.getOptimalThreadCount();
            int batchSize = resourceConfig.getOptimalBatchSize(LlamaHelper.getBatchSize());
            int memoryPoolSize = resourceConfig.getOptimalMemoryPoolSize(LlamaHelper.getMemoryPoolSize());

            AILogger.i(TAG, "Initializing model with optimized parameters: " +''',
'''            int threadCount = resourceConfig.getOptimalThreadCount();
            int batchSize = resourceConfig.getOptimalBatchSize(LlamaHelper.getBatchSize());
            int memoryPoolSize = resourceConfig.getOptimalMemoryPoolSize(LlamaHelper.getMemoryPoolSize());

            // 4B 级模型（>1800MB）统一收紧：batch 减半降 prefill 峰值计算内存，
            // 内存池收紧避免 native 按预算放行过大 KV（权重 2.4GB 常驻，需给系统留余量）。
            if (modelSizeMB > 1800) {
                batchSize = Math.min(batchSize, 512);
                memoryPoolSize = Math.min(memoryPoolSize, 1200);
                AILogger.i(TAG, "4B model: batch capped to " + batchSize + ", memory pool capped to " + memoryPoolSize + "MB");
            }

            AILogger.i(TAG, "Initializing model with optimized parameters: " +''',
'4B batch/mempool cap')

print("4B TIGHTEN OK")
