# -*- coding: utf-8 -*-
"""给 strings_migrated.xml 中含 % 的 <string> 添加 formatted="false"，跳过 aapt 格式校验"""
import re

paths = [
    r"D:\qzq\smartquiz\src\main\res\values\strings_migrated.xml",
    r"D:\qzq\smartquiz\src\main\res\values-en\strings_migrated.xml",
    r"D:\qzq\smartquiz\src\main\res\values-zh-rTW\strings_migrated.xml",
]

pat = re.compile(r'(<string name="(h_[0-9a-f]+)")(>([^<]*)</string>)')


def fix(path):
    xml = open(path, encoding="utf-8").read()
    count = 0

    def repl(m):
        nonlocal count
        name, value = m.group(2), m.group(4)
        if "%" not in value:
            return m.group(0)
        if 'formatted="false"' in m.group(1):
            return m.group(0)
        count += 1
        return f'{m.group(1)} formatted="false"{m.group(3)}'

    xml = pat.sub(repl, xml)
    open(path, "w", encoding="utf-8", newline="\n").write(xml)
    print(f"{path.split(chr(92))[-2]}: 添加 formatted 属性 {count} 处")


for p in paths:
    fix(p)
