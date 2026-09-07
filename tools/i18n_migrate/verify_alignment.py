# -*- coding: utf-8 -*-
"""三语 strings_migrated.xml 对齐校验：key 集合/顺序/formatted/非空/残留检测"""
import io, os, re, sys

RES = r'D:\qzq\smartquiz\src\main\res'
NAMES = ['values', 'values-en', 'values-zh-rTW']

def load(name):
    p = os.path.join(RES, name, 'strings_migrated.xml')
    with io.open(p, encoding='utf-8') as f:
        data = f.read()
    entries = []
    fmt_set = set()
    for m in re.finditer(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', data, re.S):
        k, attr, v = m.group(1), m.group(2), m.group(3)
        entries.append((k, v))
        if 'formatted="false"' in attr:
            fmt_set.add(k)
    return entries, fmt_set

data = {n: load(n) for n in NAMES}
keys = {n: [k for k, _ in data[n][0]] for n in NAMES}
vals = {n: {k: v for k, v in data[n][0]} for n in NAMES}
fmts = {n: data[n][1] for n in NAMES}

zh_keys = keys['values']
print('key 总数: values=%d en=%d tw=%d' % (len(keys['values']), len(keys['values-en']), len(keys['values-zh-rTW'])))
n = 'values-en'
print('--- key 集合差集 ---')
for a, b in [('values','values-en'), ('values','values-zh-rTW')]:
    la, lb = set(keys[a]), set(keys[b])
    print('%s 缺: %d, %s 缺: %d' % (b, len(la - lb), a, len(lb - la)))
print('--- key 顺序一致性(与 values 逐位比较) ---')
for n in ('values-en','values-zh-rTW'):
    same = all(k1 == k2 for k1, k2 in zip(zh_keys, keys[n]))
    print('%s 顺序一致: %s' % (n, same))
print('--- formatted=false 标志 ---')
for n in ('values','values-en','values-zh-rTW'):
    print('%s: %d 条, 与 values 差集 %d' % (n, len(fmts[n]), len(fmts['values'] ^ fmts[n])))
print('--- 空值检查 ---')
for n in ('values','values-en','values-zh-rTW'):
    empty = [k for k, v in vals[n].items() if not v]
    print('%s 空值: %d' % (n, len(empty)))
print('--- 残留检测 ---')
han_re = re.compile(r'[\u4e00-\u9fff]')
en_hits = [k for k, v in vals['values-en'].items() if han_re.search(v)]
# tw 简体残留：opencc 幂等性检测（若仍含简体，s2twp 再转会变化）
import opencc
cc = opencc.OpenCC('s2twp')
tw_hits = [k for k, v in vals['values-zh-rTW'].items() if cc.convert(v) != v]
print('en 含中文字符的条目: %d' % len(en_hits))
print('tw 非幂等(含简体/可再转换)条目: %d' % len(tw_hits))
print('en 中文残留 key 示例:', en_hits[:8])
print('tw 残留 key 示例:', tw_hits[:8])
