# -*- coding: utf-8 -*-
"""解析 compile_err.txt，分类编译错误，输出修复建议"""
import re
from collections import Counter, defaultdict

err = open(r"D:\qzq\smartquiz\tools\i18n_migrate\compile_err.txt", encoding="utf-8", errors="ignore").read()

# 错误行: 文件:行: 错误: 消息
pat = re.compile(r"^(D:\\qzq\\smartquiz\\.*?\.(?:java|kt)):(\d+): 错误: (.*)$", re.MULTILINE)
errors = pat.findall(err)

print(f"总错误: {len(errors)}")
by_msg = Counter(m.split("  ")[0][:50] for _, _, m in errors)
print("\n=== 错误消息类型 Top 15 ===")
for m, n in by_msg.most_common(15):
    print(f"  {n:4d}  {m}")

by_file = Counter(f for f, _, _ in errors)
print("\n=== 按文件错误数 Top 15 ===")
for f, n in by_file.most_common(15):
    print(f"  {n:4d}  {f.replace('D:\\\\qzq\\\\smartquiz\\\\', '')}")

# 详细样例（每种类型取 2 个）
print("\n=== 错误样例 ===")
seen = set()
for f, ln, m in errors:
    key = m.split("  ")[0][:40]
    if key in seen:
        continue
    seen.add(key)
    print(f"  {f.split(chr(92))[-1]}:{ln}  {m}")
    if len(seen) >= 12:
        break
