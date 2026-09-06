# -*- coding: utf-8 -*-
# AIChatActivity.java: 修复 TranslateAnimation 8 参构造器参数
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
'''            android.view.animation.Animation a = new android.view.animation.TranslateAnimation(
                    android.view.animation.Animation.RELATIVE_TO_SELF, -1.0f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                    0, 0);''',
'''            android.view.animation.Animation a = new android.view.animation.TranslateAnimation(
                    android.view.animation.Animation.RELATIVE_TO_SELF, -1.0f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0f);''',
'fix anim args')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("ANIM ARGS OK")
