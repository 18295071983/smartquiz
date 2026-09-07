# -*- coding: utf-8 -*-
"""
修复迁移后编译错误：
1) 错误行中 context.getString / 裸 getString -> SmartQuizApplication.getAppContext().getString
   （解决静态上下文、局部 context 不可用两类问题）
2) 补 import com.oilquiz.app.R（修正 has_r_import 对子包的误判）
3) 补 import com.oilquiz.app.SmartQuizApplication
输入: compile_err.txt 中 文件:行: 错误: 消息
"""
import os
import re

BASE = r"D:\qzq\smartquiz"
ERR = r"D:\qzq\smartquiz\tools\i18n_migrate\compile_err.txt"
APP_PKG = "com.oilquiz.app"

pat = re.compile(r"^(D:\\qzq\\smartquiz\\.*?\.(?:java|kt)):(\d+): 错误: (.*)$", re.MULTILINE)
errs = pat.findall(open(ERR, encoding="utf-8", errors="ignore").read())

# 需要修的 (file, line) 集合，按文件聚合
need_fix = {}
for f, ln, m in errs:
    # gradle 错误消息下一行才是具体代码；这里按行号收集，修复时再看行内容
    need_fix.setdefault(f, set()).add(int(ln))

# 修 R 包的文件（程序包R不存在）
r_missing = set()
for f, ln, m in errs:
    if "程序包R不存在" in m:
        r_missing.add(f)

print(f"需修 getString 错误行: {sum(len(v) for v in need_fix.values())} 处 / {len(need_fix)} 文件")
print(f"需修 R import: {len(r_missing)} 文件")

STATIC_IMPORT = "import %s.SmartQuizApplication;\n" % APP_PKG
R_IMPORT = "import %s.R;\n" % APP_PKG
PACKAGE_RE = re.compile(r"^package\s+([\w.]+)\s*;")


def ensure_import(lines, imp):
    """确保 import 存在；无 import 区时插到 package 之后"""
    for ln in lines:
        if ln.strip() == imp.strip():
            return
    ins = 0
    for i, l in enumerate(lines[:100]):
        if l.startswith("import "):
            ins = i
            break
    if ins == 0:
        # 无 import 行：插到 package 行之后
        for i, l in enumerate(lines[:10]):
            if l.startswith("package "):
                lines.insert(i + 1, "\n" + imp)
                return
    lines.insert(ins, imp)


for f in need_fix:
    with open(f, "r", encoding="utf-8", errors="ignore") as fh:
        lines = fh.readlines()
    changed = 0
    for ln in sorted(need_fix[f]):
        idx = ln - 1
        if not (0 <= idx < len(lines)):
            continue
        line = lines[idx]
        # 仅当行内确有迁移引入的 getString 调用才替换
        if "R.string.h_" not in line:
            continue
        # 任意变量名 .getString（context./ctx./xxx.）以及裸 getString 都改为全局上下文
        new = re.sub(r"\b\w+\.getString\(R\.string", "SmartQuizApplication.getAppContext().getString(R.string", line)
        new = re.sub(r"(?<![\w.])getString\(R\.string", "SmartQuizApplication.getAppContext().getString(R.string", new)
        if new != line:
            lines[idx] = new
            changed += 1
    if changed:
        # 确认该文件不在 com.oilquiz.app 包（无需 import）且没有 SmartQuizApplication import
        pkg_m = PACKAGE_RE.match(lines[0] if lines else "")
        need_imp = not (pkg_m and pkg_m.group(1) == APP_PKG)
        if need_imp:
            ensure_import(lines, STATIC_IMPORT)
        with open(f, "w", encoding="utf-8", newline="") as fh:
            fh.writelines(lines)
    print(f"  修复 {os.path.basename(f)}: {changed} 行")

for f in r_missing:
    with open(f, "r", encoding="utf-8", errors="ignore") as fh:
        lines = fh.readlines()
    pkg_m = PACKAGE_RE.match(lines[0] if lines else "")
    if not (pkg_m and pkg_m.group(1) == APP_PKG):
        ensure_import(lines, R_IMPORT)
    with open(f, "w", encoding="utf-8", newline="") as fh:
        fh.writelines(lines)
    print(f"  补 R import: {os.path.basename(f)}")
