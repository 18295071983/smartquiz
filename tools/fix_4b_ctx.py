# -*- coding: utf-8 -*-
import io, sys
p = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\service\AIService.java"
s = io.open(p, encoding="utf-8").read()
old = '''            // 收紧到 6144：Q4_0 KV @6144 ≈ 216MB；若 Q4_0 shader 回退 F16 ≈ 576MB，
            // 权重 2.4GB 常驻时 6K 是内存安全的平衡点（实测 8K 会触发 LMK thrashing）。
            contextSize = Math.min(contextSize, 6144);
            AILogger.i(TAG, "4B-level model (" + modelSizeMB + "MB): context capped to " + contextSize);'''
new = '''            // 保持 8192（Agent 兼容底线）：Agent 第 1 轮 prompt 实测 ~6974 tokens（2B），
            // 6144 减生成预留后仅 5632 可用会 Prompt too long。Q4_0 KV @8192 ≈ 288MB
            // 可控；若 Q4_0 shader 回退 F16（≈768MB）再单独降档/换 Q8_0，不以牺牲 Agent 为代价。
            contextSize = Math.min(contextSize, 8192);
            AILogger.i(TAG, "4B-level model (" + modelSizeMB + "MB): context capped to " + contextSize);'''
c = s.count(old)
if c != 1:
    print("FAIL count=%d" % c); sys.exit(1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s.replace(old, new))
print("4B ctx back to 8192 OK")
