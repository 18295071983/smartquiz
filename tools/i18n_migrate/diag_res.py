# -*- coding: utf-8 -*-
"""诊断 strings_migrated.xml 的问题：重复 key / 含 % 的文案 / 特殊字符"""
import re

paths = {
    "zh": r"D:\qzq\smartquiz\src\main\res\values\strings_migrated.xml",
    "en": r"D:\qzq\smartquiz\src\main\res\values-en\strings_migrated.xml",
    "tw": r"D:\qzq\smartquiz\src\main\res\values-zh-rTW\strings_migrated.xml",
}

for tag, p in paths.items():
    xml = open(p, encoding="utf-8").read()
    keys = re.findall(r'<string name="([^"]+)">', xml)
    dup = [k for k in set(keys) if keys.count(k) > 1]
    print(f"[{tag}] key 总数: {len(keys)}, 重复: {dup if dup else '无'}")
    items = re.findall(r'<string name="(h_[0-9a-f]+)">([^<]*)</string>', xml)
    pct = [(k, v) for k, v in items if "%" in v]
    print(f"  含 % 的条目: {len(pct)}")
    for k, v in pct[:20]:
        print(f"    {k} = {v!r}")
    # 检查非法 XML 字符
    bad = re.findall(r"<string name=\"(h_[0-9a-f]+)\">([^<]*[\x00-\x08\x0b\x0c\x0e-\x1f][^<]*)</string>", xml)
    if bad:
        print(f"  含控制字符: {len(bad)}")
        for k, v in bad[:10]:
            print(f"    {k} = {v!r}")
