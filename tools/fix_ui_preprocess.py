# -*- coding: utf-8 -*-
# AIChatActivity.java: PREPROCESS 分支显示 prefill 进度 + 吞吐
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

# 在 THINKING 分支前插入 PREPROCESS 分支
rep(
'''                        } else if ("THINKING".equals(phase)) {
                            // 顶部单行由 thinking 事件驱动（120ms 节流，无 800ms 轮询限制）：
                            // 这里只负责首次进入思考时初始化显示，内容段落切换/动画交给 thinkingRefreshRunnable
                            if (lastThinkShown == null) {
                                tvGenPhase.setText("💭 ");
                                lastThinkShown = "💭 ";
                            }
                        } else {''',
'''                        } else if ("PREPROCESS".equals(phase)) {
                            // prefill 阶段：显示进度 + 吞吐（native 分块 decode 逐块统计）
                            String pp = LlamaHelper.getPrefillProgress();
                            if (pp != null && !pp.isEmpty()) {
                                try {
                                    org.json.JSONObject po = new org.json.JSONObject(pp);
                                    int done = po.optInt("done", 0);
                                    int total = po.optInt("total", 0);
                                    int pct = po.optInt("pct", 0);
                                    if (total > 0) {
                                        float ps = LlamaHelper.getPhaseSpeed();
                                        if (ps > 0) {
                                            tvGenPhase.setText(String.format("⏳ PREPROCESS · %d%% · %.1f t/s", pct, ps));
                                        } else {
                                            tvGenPhase.setText(String.format("⏳ PREPROCESS · %d%%", pct));
                                        }
                                    } else {
                                        tvGenPhase.setText("⏳ PREPROCESS");
                                    }
                                } catch (Exception ignored) {
                                    tvGenPhase.setText("⏳ PREPROCESS");
                                }
                            } else {
                                tvGenPhase.setText("⏳ PREPROCESS");
                            }
                            lastThinkShown = null;   // 非思考阶段重置，下次思考重新开始
                        } else if ("THINKING".equals(phase)) {
                            // 顶部单行由 thinking 事件驱动（120ms 节流，无 800ms 轮询限制）：
                            // 这里只负责首次进入思考时初始化显示，内容段落切换/动画交给 thinkingRefreshRunnable
                            if (lastThinkShown == null) {
                                tvGenPhase.setText("💭 ");
                                lastThinkShown = "💭 ";
                            }
                        } else {''',
'preprocess ui branch')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("UI PREPROCESS OK")
