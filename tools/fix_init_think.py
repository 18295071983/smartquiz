# -*- coding: utf-8 -*-
# 补丁：chatJson 初始思考态（prompt 尾部以 start tag 结尾）时也标记 THINKING
import io, sys
path = r"D:\qzq\smartquiz\src\main\cpp\native-lib.cpp"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()
old = '''        bool isInThinking = promptTailInThinking;
        if (isInThinking) LOGI("chatJson: initial thinking state (prompt tail ends with start tag)");'''
new = '''        bool isInThinking = promptTailInThinking;
        if (isInThinking) {
            setPhase(GenPhase::THINKING, "chatJson:init_think");
            LOGI("chatJson: initial thinking state (prompt tail ends with start tag)");
        }'''
c = src.count(old)
if c != 1:
    print("FAIL: count=%d" % c); sys.exit(1)
src = src.replace(old, new, 1)
with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("OK: chatJson init_think THINKING marker added")
