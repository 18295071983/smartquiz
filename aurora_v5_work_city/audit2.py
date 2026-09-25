# -*- coding: utf-8 -*-
"""静态审查：确认关键 id 是否有 addEventListener 绑定 + 桥调用合规"""
import io, os

WEBROOT = r'D:\qzq\smartquiz\aurora_v5_web'
js_all = ''
for root, dirs, files in os.walk(WEBROOT):
    if '_backup' in root or '_shots' in root:
        continue
    for f in files:
        if f.endswith('.js'):
            js_all += io.open(os.path.join(root, f), encoding='utf-8').read() + '\n'

# 关键按钮：检查是否有 addEventListener 或 .onclick 绑定（在当前文件内）
targets = ['btnOrient', 'btnImmerse', 'btnKeep', 'btnTheme', 'btnAddAlarm', 'btnAddCity',
           'btnSetClose', 'swRun', 'swLap', 'swReset', 'cdRun', 'cdReset', 'pmRun', 'pmReset',
           'btnShot', 'btnInfo', 'btnReset', 'sheetClose', 'roSnooze', 'roStop', 'oWx']
print("=== 事件绑定检查 ===")
for t in targets:
    # 查找形如 $('#id').addEventListener 或 getElementById('id').addEventListener 或 onclick
    hit = ('%s').join(['', ''])  # noop
    pat1 = "$('#%s').addEventListener" % t
    pat2 = 'getElementById("%s").addEventListener' % t
    pat3 = 'getElementById(\'%s\').addEventListener' % t
    pat4 = 'getElementById("%s").onclick' % t
    pat5 = "getElementById('%s').onclick" % t
    pat6 = "$$('#%s'" % t  # 批量
    ok = (pat1 in js_all) or (pat2 in js_all) or (pat3 in js_all) or (pat4 in js_all) or (pat5 in js_all) or (pat6 in js_all)
    print('  %-14s %s' % (t, 'BOUND' if ok else '!! 未直接绑定（可能事件代理）'))

# 检查 weather.js 桥调用合规性
wx = io.open(os.path.join(WEBROOT, 'js', 'weather.js'), encoding='utf-8').read()
print("\n=== weather.js 桥调用检查 ===")
import re
calls = re.findall(r'B\(\)\.\w+', wx)
print('  桥调用:', sorted(set(calls)))
# 检查回调名铁律：request 必须传回调名
if 'B().request(' in wx or 'b.request(' in wx:
    print('  request 调用: 存在（需确认带回调名参数）')
for m in re.finditer(r'\.request\(([^)]*)\)', wx):
    args = m.group(1)
    print('    request(', args[:100], ')')
