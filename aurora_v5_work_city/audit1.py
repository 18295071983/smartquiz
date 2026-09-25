# -*- coding: utf-8 -*-
"""静态审查：检查 index.html 中所有 button/select/input 是否有 JS 绑定"""
import io, re, os, glob

WEBROOT = r'D:\qzq\smartquiz\aurora_v5_web'
html = io.open(os.path.join(WEBROOT, 'index.html'), encoding='utf-8').read()

# 收集所有带 id 的可交互元素
ids = re.findall(r'<(button|select|input|a)\b[^>]*\bid="([^"]+)"', html)
print("=== 交互元素清单（%d 个）===" % len(ids))
for tag, i in ids:
    print('%-7s #%s' % (tag, i))

# 收集所有 JS 文件内容
js_all = ''
for root, dirs, files in os.walk(WEBROOT):
    if '_backup' in root or '_shots' in root or 'node_modules' in root:
        continue
    for f in files:
        if f.endswith('.js'):
            js_all += io.open(os.path.join(root, f), encoding='utf-8').read() + '\n'

print("\n=== 未在 JS 中被引用的 id（可能僵尸）===")
unbound = []
for tag, i in ids:
    # 在 JS 中查找 '$("#id")' 或 "getElementById('id')" 或 addEventListener
    if ("'%s'" % i) not in js_all and ('"%s"' % i) not in js_all and ('#' + i) not in js_all:
        unbound.append((tag, i))
if unbound:
    for tag, i in unbound:
        print('  %-7s #%s  <- 未找到引用' % (tag, i))
else:
    print('  全部有引用')

# 额外：检查 data-act / data-pg 等点击代理是否覆盖
print("\n=== 检查 button 是否有 onclick 属性（内联）===")
inline = re.findall(r'<button[^>]*\sonclick=', html)
print('  内联 onclick:', len(inline), '个')
