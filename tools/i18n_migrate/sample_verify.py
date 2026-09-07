# -*- coding: utf-8 -*-
import io, re, os
res = r'D:\qzq\smartquiz\src\main\res'
for name in ('values', 'values-en', 'values-zh-rTW'):
    p = os.path.join(res, name, 'strings_migrated.xml')
    with io.open(p, encoding='utf-8') as f:
        data = f.read()
    vals = dict(re.findall(r'<string name="([^"]+)"[^>]*>(.*?)</string>', data, re.S))
    print('==', name, len(vals))
    for k in ('h_7d2ff42c', 'h_664b37da', 'h_9e0ee86a', 'h_970f00ea', 'h_1533a43c',
              'h_3e238a20', 'h_fc1d52a4', 'h_a330b6ec', 'h_38cf16f2'):
        print('   %s => %s' % (k, vals.get(k, '<MISSING>')[:70]))
# key 一致性
sets = {}
for name in ('values', 'values-en', 'values-zh-rTW'):
    p = os.path.join(res, name, 'strings_migrated.xml')
    with io.open(p, encoding='utf-8') as f:
        data = f.read()
    sets[name] = set(re.findall(r'<string name="([^"]+)"', data))
base = sets['values']
print('en 缺:', len(base - sets['values-en']), 'tw 缺:', len(base - sets['values-zh-rTW']))
