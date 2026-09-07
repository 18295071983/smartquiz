# -*- coding: utf-8 -*-
# AIChatActivity.java: 追加 buildThinkingSegment + startThinkingRollerAnim 方法
import io, sys
path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ui\activity\AIChatActivity.java"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

METHODS = '''    /**
     * 单行滚动切换：把思考内容按行切段，轮播/跟随最新段。
     * 内容增长（新段出现）→ 跳到最新段（实时显示最新进展）；
     * 内容停顿 → 轮播各段（滚动切换效果）。
     */
    private String buildThinkingSegment(String think) {
        java.util.List<String> segs = new java.util.ArrayList<>();
        for (String line : think.split("\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) segs.add(t);
        }
        if (segs.isEmpty()) {
            segs.add(think);
        }
        int oldSize = thinkingSegments.size();
        thinkingSegments = segs;
        if (segs.size() > oldSize) {
            // 内容增长：显示最新段（最新进展）
            thinkingSegmentIndex = segs.size() - 1;
        } else {
            // 内容停顿：轮播到下一段（滚动切换）
            thinkingSegmentIndex = (thinkingSegmentIndex + 1) % segs.size();
        }
        String seg = segs.get(thinkingSegmentIndex);
        // 单行截断：保留最新尾部（最新思考）
        if (seg.length() > 34) seg = seg.substring(seg.length() - 34);
        return seg;
    }

    /** 单行段落切换动画：新内容从下方滚入（无跑马灯定时重置问题） */
    private void startThinkingRollerAnim() {
        try {
            if (tvGenPhase == null) return;
            android.view.animation.Animation a = new android.view.animation.TranslateAnimation(
                    0, 0, 14f, 0);
            a.setDuration(220);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator());
            tvGenPhase.startAnimation(a);
        } catch (Exception ignored) {}
    }

    private void refreshNativeStateUI() {'''

anchor = "    private void refreshNativeStateUI() {"
c = src.count(anchor)
if c != 1:
    print("[FAIL] anchor count=%d" % c); sys.exit(1)
src = src.replace(anchor, METHODS, 1)
with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("ROLLER METHODS OK")
