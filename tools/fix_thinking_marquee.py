# -*- coding: utf-8 -*-
# AIChatActivity.java: 思考内容 marquee 滚动显示 + 节流更新
# 1) 加字段 lastThinkShown/lastThinkUiUpdate（tvGenPhase 附近）
# 2) THINKING 分支：显示最新 200 字思考 + 2.5s 节流 setText（避免每 800ms 重置滚动）+ setSelected(true) 激活 marquee
# 3) 非 THINKING 分支：setSelected(false) 停止滚动
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

# 1) 字段
rep(
'''    private TextView tvGenPhase;
    private TextView tvKvStats;''',
'''    private TextView tvGenPhase;
    private TextView tvKvStats;
    private String lastThinkShown;          // 思考内容 marquee 上次显示（内容未变化不重置滚动）
    private long lastThinkUiUpdate;         // 思考内容 UI 上次节流更新时间戳''',
'fields')

# 2) THINKING 分支改 marquee
rep(
'''                        } else if ("THINKING".equals(phase)) {
                            // 思考段实时显示模型思考内容预览（native 实时累积，截断防刷屏）
                            String think = LlamaHelper.getThinkingContent();
                            if (think != null && !think.isEmpty()) {
                                String preview = think.replace('\\n', ' ').replace('\\r', ' ').trim();
                                if (preview.length() > 60) preview = preview.substring(0, 60) + "…";
                                tvGenPhase.setText("⏳ 思考中 " + preview);
                            } else {
                                tvGenPhase.setText("⏳ THINKING");
                            }
                        } else {
                            tvGenPhase.setText("⏳ " + phase);
                        }
                        tvGenPhase.setVisibility(View.VISIBLE);
                        show = true;''',
'''                        } else if ("THINKING".equals(phase)) {
                            // 思考段实时显示模型思考内容：marquee 滚动 + 2.5s 节流更新
                            // （native 每 token 累积，若每 800ms setText 会一直重置滚动位置，
                            //   节流让滚动能真正跑起来，内容阶段性跳到最新累积）
                            String think = LlamaHelper.getThinkingContent();
                            if (think != null && !think.isEmpty()) {
                                String preview = think.replace('\\n', ' ').replace('\\r', ' ').trim();
                                // 取最新 200 字滚动展示（思考尾部=最新进展）
                                if (preview.length() > 200) preview = preview.substring(preview.length() - 200);
                                long now = System.currentTimeMillis();
                                String disp = "⏳ 思考中 " + preview;
                                if (lastThinkShown == null || !disp.equals(lastThinkShown)
                                        || (now - lastThinkUiUpdate >= 2500)) {
                                    tvGenPhase.setText(disp);
                                    lastThinkShown = disp;
                                    lastThinkUiUpdate = now;
                                }
                            } else if (lastThinkShown == null) {
                                tvGenPhase.setText("⏳ THINKING");
                                lastThinkShown = "⏳ THINKING";
                            }
                            tvGenPhase.setSelected(true);   // 激活 marquee 滚动
                        } else {
                            tvGenPhase.setText("⏳ " + phase);
                            tvGenPhase.setSelected(false);  // 非思考阶段停止滚动
                        }
                        tvGenPhase.setVisibility(View.VISIBLE);
                        show = true;''',
'thinking marquee')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("THINKING MARQUEE OK")
