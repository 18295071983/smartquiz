# -*- coding: utf-8 -*-
# AIChatActivity.java: 本地模式 TPS 按状态机阶段切换（THINKING 思考速度 / GENERATING 解码速度）
import io, sys
path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ui\activity\AIChatActivity.java"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

def rep(old, new, label, expect=1):
    global src
    c = src.count(old)
    if c != expect:
        print("[FAIL] %s: count=%d" % (label, c)); sys.exit(1)
    src = src.replace(old, new, expect)
    print("[OK] %s" % label)

# 本地模式：displayTps 优先取当前阶段速度（getPhaseSpeed），并带阶段标签
rep(
'''            } else {
                // 本地模式：优先使用 native 层
                float nativeTps = LlamaHelper.getInferenceSpeed();
                int nativeTokens = LlamaHelper.getTokenCount();
                displayTps = nativeTps > 0 ? nativeTps : tokensPerSecond;
                displayTokens = nativeTokens > 0 ? nativeTokens : totalTokens;
                sourceTag = "⚡"; // 本地 native
            }''',
'''            } else {
                // 本地模式：优先使用 native 层；TPS 按状态机阶段切换（思考速度/解码速度）
                float phaseSpeed = LlamaHelper.getPhaseSpeed();
                float nativeTps = LlamaHelper.getInferenceSpeed();
                int nativeTokens = LlamaHelper.getTokenCount();
                displayTps = phaseSpeed > 0 ? phaseSpeed : (nativeTps > 0 ? nativeTps : tokensPerSecond);
                displayTokens = nativeTokens > 0 ? nativeTokens : totalTokens;
                sourceTag = "⚡"; // 本地 native
                // 阶段标签：THINKING 思考段用 🧠，GENERATING 正文用 ⚡
                try {
                    String pj = LlamaHelper.getGenPhase();
                    if (pj != null && !pj.isEmpty()) {
                        String ph = new org.json.JSONObject(pj).optString("phase", "");
                        if ("THINKING".equals(ph)) sourceTag = "🧠";
                        else if ("GENERATING".equals(ph)) sourceTag = "⚡";
                    }
                } catch (Throwable ignored) {}
            }''',
'phase-switched TPS')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("PHASE TPS UI OK")
