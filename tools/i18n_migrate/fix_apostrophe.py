# -*- coding: utf-8 -*-
"""修复 strings_migrated.xml：
1) &apos; -> \\' （Android string 资源单引号转义规则）
2) 检查含反斜杠的条目（\\ 后跟非 n/t/u 等合法转义时需处理）
"""
import re

paths = [
    r"D:\qzq\smartquiz\src\main\res\values\strings_migrated.xml",
    r"D:\qzq\smartquiz\src\main\res\values-en\strings_migrated.xml",
    r"D:\qzq\smartquiz\src\main\res\values-zh-rTW\strings_migrated.xml",
]

for p in paths:
    xml = open(p, encoding="utf-8").read()
    n1 = xml.count("&apos;")
    xml = xml.replace("&apos;", "\\'")
    open(p, "w", encoding="utf-8", newline="\n").write(xml)
    print(f"{p.split(chr(92))[-2]}: 替换单引号转义 {n1} 处")

    # 检查反斜杠后跟非法转义字符的情况（\ 后跟非 n t u 反斜杠 数字）
    bad = re.findall(r'<string name="(h_[0-9a-f]+)">([^<]*)</string>', open(p, encoding="utf-8").read())
    suspicious = []
    for k, v in bad:
        for i, ch in enumerate(v):
            if ch == "\\":
                nxt = v[i + 1] if i + 1 < len(v) else ""
                if nxt not in ("n", "t", "\\", "u", "'", '"'):
                    suspicious.append((k, v[:40]))
                    break
    if suspicious:
        print(f"  可疑反斜杠 {len(suspicious)} 处:")
        for k, v in suspicious[:10]:
            print(f"    {k} = {v!r}")
    else:
        print("  无可疑反斜杠")
