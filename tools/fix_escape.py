# -*- coding: utf-8 -*-
# 重写 sendMessage 入口日志行：避免内嵌双引号转义，只打印输入长度
import io

path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ui\activity\AIChatActivity.java"
with io.open(path, "r", encoding="utf-8") as f:
    lines = f.readlines()

# 目标 0-indexed 行 3311（即源文件第 3312 行）
idx = 3311
print("BEFORE:", lines[idx].rstrip())

new_line = ('                + ", inputLen=" + (inputMessage != null ? inputMessage.getText().length() : 0));\n')
lines[idx] = new_line

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.writelines(lines)
print("AFTER :", new_line.rstrip())
print("OK")
