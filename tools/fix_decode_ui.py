# -*- coding: utf-8 -*-
# AIChatActivity.java: 状态机栏 GENERATING 时显示纯 decode 速度
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
'''                    if (running) {
                        tvGenPhase.setText("⏳ " + phase);
                        tvGenPhase.setVisibility(View.VISIBLE);
                        show = true;
                    }''',
'''                    if (running) {
                        // 正文生成阶段附上纯 decode 速度（思考段不计，native getDecodeSpeed）
                        if ("GENERATING".equals(phase)) {
                            float ds = LlamaHelper.getDecodeSpeed();
                            if (ds > 0) {
                                tvGenPhase.setText(String.format("⏳ %s · %.1f t/s", phase, ds));
                            } else {
                                tvGenPhase.setText("⏳ " + phase);
                            }
                        } else {
                            tvGenPhase.setText("⏳ " + phase);
                        }
                        tvGenPhase.setVisibility(View.VISIBLE);
                        show = true;
                    }''',
'show decode speed in phase bar')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("DECODE UI OK")
