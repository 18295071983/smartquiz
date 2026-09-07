# -*- coding: utf-8 -*-
"""把布局/drawable 中主题色静态引用替换为 M2 主题属性引用（?attr/...）。
仅处理 res/layout* 与 res/drawable*；values/、styles.xml、colors.xml 不在此脚本范围。
"""
import os, re

RES = r"D:\qzq\smartquiz\src\main\res"
DIRS = ["layout", "layout-land", "drawable", "drawable-night"]

# 长名优先，避免 primary 先于 primary_container 被替换
REPLACEMENTS = [
    ("@color/primary_container", "?attr/colorPrimaryContainer"),
    ("@color/on_primary_container", "?attr/colorOnPrimaryContainer"),
    ("@color/on_primary", "?attr/colorOnPrimary"),
    ("@color/primary_dark", "?attr/colorPrimaryVariant"),
    ("@color/secondary_container", "?attr/colorSecondaryContainer"),
    ("@color/on_secondary_container", "?attr/colorOnSecondaryContainer"),
    ("@color/on_secondary", "?attr/colorOnSecondary"),
]

# 短名用正则：其后不能紧跟字母或下划线（避免命中 primary_container / primary_dark / primary_light / primary_alpha_20 等）
PATTERNS = [
    (re.compile(r"@color/primary(?![a-z_])"), "?attr/colorPrimary"),
    (re.compile(r"@color/secondary(?![a-z_])"), "?attr/colorSecondary"),
]

total_files = 0
total_repl = 0
for d in DIRS:
    root = os.path.join(RES, d)
    if not os.path.isdir(root):
        continue
    for fn in os.listdir(root):
        if not fn.endswith(".xml"):
            continue
        path = os.path.join(root, fn)
        with open(path, "r", encoding="utf-8") as f:
            content = f.read()
        orig = content
        for old, new in REPLACEMENTS:
            content = content.replace(old, new)
        for pat, new in PATTERNS:
            content = pat.sub(new, content)
        if content != orig:
            n = sum(orig.count(a) for a, _ in REPLACEMENTS) + sum(len(p.findall(orig)) for p, _ in PATTERNS)
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(content)
            total_files += 1
            total_repl += n
            print("OK %-24s +%d" % (d + "/" + fn, n))

print("files=%d replacements=%d" % (total_files, total_repl))
