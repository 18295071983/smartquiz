# -*- coding: utf-8 -*-
"""抽样查看 report.json 中 ui 类构成"""
import json
from collections import Counter

r = json.load(open(r"D:\qzq\smartquiz\tools\i18n_migrate\report.json", encoding="utf-8"))
ui = r["ui_records"]

print("=== ui 类文案抽样（前40条唯一，文件:行 文本）===")
seen = set()
for rec in ui:
    t = rec["text"]
    if t in seen:
        continue
    seen.add(t)
    print(f"{rec['file']}:{rec['line']}  {t}")
    if len(seen) >= 40:
        break

print()
print("=== ui 类按文件分布 Top 12 ===")
c = Counter(x["file"] for x in ui)
for f, n in c.most_common(12):
    print(f"  {n:5d}  {f}")
