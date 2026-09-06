# -*- coding: utf-8 -*-
# AIChatActivity.java: PREPROCESS 分支显示完整 prompt 大小
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

rep(
'''                                    org.json.JSONObject po = new org.json.JSONObject(pp);
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
                                    }''',
'''                                    org.json.JSONObject po = new org.json.JSONObject(pp);
                                    int done = po.optInt("done", 0);
                                    int total = po.optInt("total", 0);
                                    int pct = po.optInt("pct", 0);
                                    int prompt = po.optInt("prompt", 0);
                                    if (total > 0) {
                                        float ps = LlamaHelper.getPhaseSpeed();
                                        if (ps > 0) {
                                            if (prompt > 0) {
                                                tvGenPhase.setText(String.format("⏳ PREPROCESS · %d tok · %d%% · %.1f t/s", prompt, pct, ps));
                                            } else {
                                                tvGenPhase.setText(String.format("⏳ PREPROCESS · %d%% · %.1f t/s", pct, ps));
                                            }
                                        } else {
                                            if (prompt > 0) {
                                                tvGenPhase.setText(String.format("⏳ PREPROCESS · %d tok · %d%%", prompt, pct));
                                            } else {
                                                tvGenPhase.setText(String.format("⏳ PREPROCESS · %d%%", pct));
                                            }
                                        }
                                    } else {
                                        tvGenPhase.setText("⏳ PREPROCESS");
                                    }''',
'ui prompt size')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("UI PROMPT OK")
