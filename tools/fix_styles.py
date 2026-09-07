# -*- coding: utf-8 -*-
"""styles.xml 组件样式的主题色引用 → ?attr（正则版，避免误伤 primary_alpha_20 等派生色名）。
跳过 <item name="colorXxx">@color/... 定义行（主题属性的默认值定义）。
"""
import re

FILES = [
    r"D:\qzq\smartquiz\src\main\res\values\styles.xml",
    r"D:\qzq\smartquiz\src\main\res\values-night\styles.xml",
]

# 精确色名替换（长名优先）
REPLACEMENTS = [
    ("@color/purple_500", "?attr/colorPrimary"),
    ("@color/purple_700", "?attr/colorPrimaryVariant"),
    ("@color/purple_200", "?attr/colorPrimaryContainer"),
    ("@color/primary_container", "?attr/colorPrimaryContainer"),
    ("@color/on_primary_container", "?attr/colorOnPrimaryContainer"),
    ("@color/on_primary", "?attr/colorOnPrimary"),
    ("@color/primary_dark", "?attr/colorPrimaryVariant"),
    ("@color/primary_light", "?attr/colorPrimaryContainer"),
    ("@color/secondary_container", "?attr/colorSecondaryContainer"),
    ("@color/on_secondary_container", "?attr/colorOnSecondaryContainer"),
    ("@color/on_secondary", "?attr/colorOnSecondary"),
]

# 短名正则：其后不能紧跟字母或下划线（防止命中 primary_container / primary_dark / primary_light / primary_alpha_20）
PATTERNS = [
    (re.compile(r"@color/primary(?![a-z_])"), "?attr/colorPrimary"),
    (re.compile(r"@color/secondary(?![a-z_])"), "?attr/colorSecondary"),
]

# 定义行：name="colorXxx" 或 name="android:colorXxx"
DEF_RE = re.compile(r'^\s*<item name="(?:android:)?color[A-Za-z]+">')

for path in FILES:
    with open(path, "r", encoding="utf-8") as f:
        lines = f.readlines()
    out = []
    changed = 0
    for line in lines:
        if DEF_RE.match(line):
            out.append(line)
            continue
        new = line
        for old, rep in REPLACEMENTS:
            new = new.replace(old, rep)
        for pat, rep in PATTERNS:
            new = pat.sub(rep, new)
        if new != line:
            changed += 1
        out.append(new)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.writelines(out)
    print("OK %s changed_lines=%d" % (path, changed))
