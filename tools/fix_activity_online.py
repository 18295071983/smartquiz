# -*- coding: utf-8 -*-
# AIChatActivity.java: 在线思考顶部单行显示（observe onlineThinkingStream）
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
'''    private int lastThinkLineCount = 0;   // 已动画的思考行数：换新行才触发滑入，同段增长仅实时更新
''',
'''    private int lastThinkLineCount = 0;   // 已动画的思考行数：换新行才触发滑入，同段增长仅实时更新
    private final StringBuilder onlineThinkBuffer = new StringBuilder();  // 在线思考累积（顶部单行）
''',
'field')

# 2) observe（加在 inferenceProgress observe 后，observeViewModel 结尾前）
rep(
'''        // 推理进度心跳 → 更新进度条/计时
        chatViewModel.getInferenceProgress().observe(this, progress -> {
            if (progress == null) return;
            updateInferenceProgressUI(progress);
        });
    }''',
'''        // 推理进度心跳 → 更新进度条/计时
        chatViewModel.getInferenceProgress().observe(this, progress -> {
            if (progress == null) return;
            updateInferenceProgressUI(progress);
        });

        // 在线思考实时流 → 顶部单行显示（增量累积 + 段落切换动画；null 表示思考结束隐藏）
        chatViewModel.getOnlineThinkingStream().observe(this, s -> {
            try {
                if (s == null) {
                    onlineThinkBuffer.setLength(0);
                    lastThinkShown = null;
                    lastThinkLineCount = 0;
                    if (tvGenPhase != null) tvGenPhase.setVisibility(View.GONE);
                    return;
                }
                onlineThinkBuffer.append(s);
                String full = onlineThinkBuffer.toString();
                String disp = "💭 " + buildThinkingSegment(full);
                if (!disp.equals(lastThinkShown) && tvGenPhase != null) {
                    tvGenPhase.setVisibility(View.VISIBLE);
                    tvGenPhase.setText(disp);
                    lastThinkShown = disp;
                    int lc = countThinkingLines(full);
                    if (lc != lastThinkLineCount) {
                        lastThinkLineCount = lc;
                        startThinkingRollerAnim();
                    }
                }
            } catch (Exception ignored) {}
        });
    }''',
'observe online thinking')

# 3) refreshNativeStateUI useOnline 分支：tvGenPhase 交给 onlineThinkingStream 控制
rep(
'''            boolean useOnline = inferenceRouter != null && inferenceRouter.isUsingOnlineModel();
            if (useOnline) {
                if (tvGenPhase != null) tvGenPhase.setVisibility(View.GONE);
                if (tvKvStats != null) tvKvStats.setVisibility(View.GONE);
                return;
            }''',
'''            boolean useOnline = inferenceRouter != null && inferenceRouter.isUsingOnlineModel();
            if (useOnline) {
                if (tvKvStats != null) tvKvStats.setVisibility(View.GONE);
                // tvGenPhase 在线由 onlineThinkingStream observe 实时驱动（思考时显示/结束后隐藏），
                // 此处不强制 GONE，避免 800ms 轮询与实时 observe 互相覆盖闪烁；在线无 native 状态机/速度
                return;
            }''',
'online branch')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("ACTIVITY ONLINE OK")
