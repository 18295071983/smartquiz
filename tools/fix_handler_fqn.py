# -*- coding: utf-8 -*-
# 修复 AIChatActivity 编译错误：Handler/Looper 全限定名（该类无 import 风格）
import io, sys
path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ui\activity\AIChatActivity.java"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

old = "    private final Handler statePollHandler = new Handler(Looper.getMainLooper());"
new = "    private final android.os.Handler statePollHandler = new android.os.Handler(android.os.Looper.getMainLooper());"
c = src.count(old)
if c != 1:
    print("[FAIL] count=%d" % c); sys.exit(1)
src = src.replace(old, new, 1)
with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("HANDLER FQN FIX OK")
