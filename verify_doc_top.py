# -*- coding: utf-8 -*-
"""验证：说明行置顶（与题目同表）时，表头检测/说明提取/解析是否正确"""
import sys, io, json, os
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
sys.path.insert(0, r'D:\qzq\smartquiz\src\main\python')
import importlib.util
spec = importlib.util.spec_from_file_location("ipp", r'D:\qzq\smartquiz\src\main\python\import_preprocessor.py')
ipp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ipp)

p = r'D:\qzq\smartquiz\test_files\review_doc_top.xlsx'

# 1. 表头检测（自动）
raw = ipp._raw_head_rows(p, 2)
print('=== _raw_head_rows(前12行原始) ===')
for i, r in enumerate(raw[:6]):
    print('  row%d: %s' % (i, json.dumps(r[:8], ensure_ascii=False)[:150]))
h, start, hits = ipp._detect_header(raw)
print('表头:', json.dumps(h[:13], ensure_ascii=False))
print('data_start:', start, 'hits:', hits)

# 2. 说明提取
doc = ipp._extract_doc_hint(raw, start)
print('=== doc_hint ===')
print(repr(doc[:300]) if doc else 'None')

# 3. sample_file（模拟 app 调用）
obj = json.loads(ipp.sample_file(p, 15, 2))
print('=== sample_file(sheet=2) ===')
print('headers 列数:', len(obj.get('headers', [])))
print('headers:', json.dumps(obj.get('headers', [])[:13], ensure_ascii=False))
print('header_suspicious:', obj.get('header_suspicious'), 'hits:', obj.get('header_hits'))
print('doc_hint:', repr((obj.get('doc_hint') or '')[:200]))
print('rows 前2:', json.dumps(obj.get('rows', [])[:2], ensure_ascii=False)[:260])

# 4. parse_file（真实 spec）
mapping = {'questionText': '题目内容', 'optionsCombined': '可选项', 'correctAnswer': '答案',
           'questionType': '题型', 'difficulty': '难度', 'category': '关键字'}
specj = {'std_columns': ['questionText','optionA','optionB','optionC','optionD','optionE','optionF','optionG','optionH','optionI','optionJ','optionK','optionL','correctAnswer','answerText','category','difficulty','explanation','questionType','source'],
         'fill_fields': ['category','difficulty','explanation'],
         'option_fields': {c: 'option'+c for c in 'ABCDEFGHIJKL'}}
out = r'D:\qzq\smartquiz\test_files\parse_doc_top'
os.makedirs(out, exist_ok=True)
for f in os.listdir(out):
    os.remove(os.path.join(out, f))
res = json.loads(ipp.parse_file(p, json.dumps(mapping, ensure_ascii=False), out,
                                spec_json=json.dumps(specj, ensure_ascii=False), sheet_index=2))
print('=== parse_file(sheet=2) ===')
for k in ['total_rows', 'written_rows', 'duplicate_count', 'empty_question_count']:
    print(' ', k, ':', res.get(k))
# 说明行是否被当成数据（检查第一行数据是否应为原第1题）
first = open(os.path.join(out, 'import_part_0001.csv'), encoding='utf-8').readline()
print('第一数据行:', first[:120])
