# -*- coding: utf-8 -*-
"""分析宝丰题库：sheet 结构、说明行内容、表头、数据行特征"""
import sys, io, json, openpyxl
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

p = r'D:\qzq\smartquiz\test_files\baofeng.xlsx'
wb = openpyxl.load_workbook(p, read_only=True, data_only=True)
print('=== sheets ===')
for ws in wb.worksheets:
    cnt = 0
    rows = []
    for i, row in enumerate(ws.iter_rows(values_only=True), start=1):
        cells = [('' if c is None else str(c).strip()) for c in row]
        if any(c != '' for c in cells):
            cnt += 1
            rows.append((i, cells))
    print('%s: 非空行 %d' % (ws.title, cnt))
    print('  前5非空行:')
    for i, r in rows[:5]:
        print('    row%d: %s' % (i, json.dumps(r[:8], ensure_ascii=False)[:180]))
    print('  末2非空行:')
    for i, r in rows[-2:]:
        print('    row%d: %s' % (i, json.dumps(r[:8], ensure_ascii=False)[:150]))
wb.close()
