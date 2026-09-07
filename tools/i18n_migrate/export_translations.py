# -*- coding: utf-8 -*-
"""导出待翻译清单 translations_zh.tsv（key \t zh），供翻译后回写"""
import re

XML = r"D:\qzq\smartquiz\src\main\res\values\strings_migrated.xml"
OUT = r"D:\qzq\smartquiz\tools\i18n_migrate\translations_zh.tsv"

xml = open(XML, encoding="utf-8").read()
items = re.findall(r'<string name="(h_[0-9a-f]+)"[^>]*>([^<]*)</string>', xml)
with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    for k, v in items:
        f.write(f"{k}\t{v}\n")
print(f"导出 {len(items)} 条 -> {OUT}")
