# -*- coding: utf-8 -*-
# AIChatActivity.java: 刮刀式刷新连续化（完整版，replace_all 处理重置锚点）
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

def rep_all(old, new, label, expect_min=1):
    global src
    c = src.count(old)
    if c < expect_min:
        print("[FAIL] %s: count=%d" % (label, c)); sys.exit(1)
    src = src.replace(old, new)
    print("[OK] %s x%d" % (label, c))

# 1) 字段
rep(
'''    private String lastThinkShown;
''',
'''    private String lastThinkShown;
    private int lastThinkLineCount = 0;   // 已动画的思考行数：换新行才触发滑入，同段增长仅实时更新
''',
'field')

# 2) runnable
rep(
'''                // 顶部单行：thinking 事件驱动（120ms 节流），段落切换 + 上滚动画，无 800ms 轮询限制
                if (isInThinking && tvGenPhase != null && tvGenPhase.getVisibility() == View.VISIBLE
                        && msg.thinkingContent != null && !msg.thinkingContent.isEmpty()) {
                    String disp = "💭 " + buildThinkingSegment(msg.thinkingContent);
                    if (!disp.equals(lastThinkShown)) {
                        tvGenPhase.setText(disp);
                        lastThinkShown = disp;
                        startThinkingRollerAnim();
                    }
                }''',
'''                // 顶部单行：thinking 事件驱动（120ms 节流），无 800ms 轮询限制。
                // 连续性策略：同段内容增长只实时更新文本（不重启动画，自然连续）；
                // 出现新行（换段）才刮刀式滑入动画，避免频繁重启造成的断续抖动。
                if (isInThinking && tvGenPhase != null && tvGenPhase.getVisibility() == View.VISIBLE
                        && msg.thinkingContent != null && !msg.thinkingContent.isEmpty()) {
                    String disp = "💭 " + buildThinkingSegment(msg.thinkingContent);
                    if (!disp.equals(lastThinkShown)) {
                        tvGenPhase.setText(disp);
                        lastThinkShown = disp;
                        int lineCount = countThinkingLines(msg.thinkingContent);
                        if (lineCount != lastThinkLineCount) {
                            lastThinkLineCount = lineCount;
                            startThinkingRollerAnim();
                        }
                    }
                }''',
'runnable')

# 3) 重置锚点：所有 "lastThinkShown = null;   // 非思考阶段重置，下次思考重新开始"
rep_all(
'''lastThinkShown = null;   // 非思考阶段重置，下次思考重新开始''',
'''lastThinkShown = null;   // 非思考阶段重置，下次思考重新开始
                            lastThinkLineCount = 0;''',
'reset anchors')

# 4) 动画柔和化 + countThinkingLines
rep(
'''    /** 单行段落切换动画：新内容从左往右滑入（刮刀式刷新，非跑马灯/打字机） */
    private void startThinkingRollerAnim() {
        try {
            if (tvGenPhase == null) return;
            android.view.animation.Animation a = new android.view.animation.TranslateAnimation(
                    android.view.animation.Animation.RELATIVE_TO_SELF, -1.0f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0f);
            a.setDuration(260);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator());
            tvGenPhase.startAnimation(a);
        } catch (Exception ignored) {}
    }''',
'''    /** 思考文本按 \\n 计行数（用于判断是否出现新段） */
    private int countThinkingLines(String content) {
        if (content == null) return 0;
        int n = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\\n') n++;
        }
        return n;
    }

    /** 单行段落切换动画：新内容从左往右刮刀式滑入 + 柔和淡入（非跑马灯/打字机） */
    private void startThinkingRollerAnim() {
        try {
            if (tvGenPhase == null) return;
            android.view.animation.AnimationSet set = new android.view.animation.AnimationSet(true);
            android.view.animation.TranslateAnimation ta = new android.view.animation.TranslateAnimation(
                    android.view.animation.Animation.RELATIVE_TO_SELF, -0.6f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0f);
            ta.setDuration(320);
            ta.setInterpolator(new android.view.animation.DecelerateInterpolator());
            android.view.animation.AlphaAnimation aa = new android.view.animation.AlphaAnimation(0.3f, 1f);
            aa.setDuration(320);
            set.addAnimation(ta);
            set.addAnimation(aa);
            tvGenPhase.startAnimation(set);
        } catch (Exception ignored) {}
    }''',
'anim')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("CONTINUOUS WIPE OK")
