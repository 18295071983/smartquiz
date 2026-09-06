# -*- coding: utf-8 -*-
"""修复 AgentSoftwareLayer.onIterationEnd：不再把正文摘要当思考更新推给思考区，
改为走 onStepUpdate 状态栏提示（思考区只接收 onThinkingUpdate 的真实 reasoning 事件）。"""
import io

p = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\agent\software\AgentSoftwareLayer.java"
s = io.open(p, encoding="utf-8").read()

old = '                    callback.onThinkingUpdate("第 " + iteration + " 轮: " + truncate(clean, 80));'
assert s.count(old) == 1, "anchor not unique: %d" % s.count(old)

new = ('                    // 第 N 轮结束仅作状态栏进度提示（onStepUpdate），不再把正文摘要当思考更新：\n'
       '                    // 思考区只接收 onThinkingUpdate 的真实 reasoning 事件，避免正文污染思考气泡\n'
       '                    callback.onStepUpdate("第 " + iteration + " 轮", truncate(clean, 40));')

s = s.replace(old, new)
io.open(p, "w", encoding="utf-8").write(s)
print("OK patched AgentSoftwareLayer")
