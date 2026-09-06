# -*- coding: utf-8 -*-
# AIChatActivity.java: 思考内容刷新动画 上滚 -> 从左往右滑入（刮刀式）
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
'''    /** 单行段落切换动画：新内容从下方滚入（无跑马灯定时重置问题） */
    private void startThinkingRollerAnim() {
        try {
            if (tvGenPhase == null) return;
            android.view.animation.Animation a = new android.view.animation.TranslateAnimation(
                    0, 0, 14f, 0);
            a.setDuration(220);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator());
            tvGenPhase.startAnimation(a);
        } catch (Exception ignored) {}
    }''',
'''    /** 单行段落切换动画：新内容从左往右滑入（刮刀式刷新，非跑马灯/打字机） */
    private void startThinkingRollerAnim() {
        try {
            if (tvGenPhase == null) return;
            android.view.animation.Animation a = new android.view.animation.TranslateAnimation(
                    android.view.animation.Animation.RELATIVE_TO_SELF, -1.0f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                    0, 0);
            a.setDuration(260);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator());
            tvGenPhase.startAnimation(a);
        } catch (Exception ignored) {}
    }''',
'wipe left-to-right')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("WIPE OK")
