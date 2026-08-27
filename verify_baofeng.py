# -*- coding: utf-8 -*-
"""宝丰题库：sample_file + parse_file 完整验证（单选/多选/判断/填空 sheet）"""
import sys, io, json, os
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
sys.path.insert(0, r'D:\qzq\smartquiz\src\main\python')
import importlib.util
spec = importlib.util.spec_from_file_location("ipp", r'D:\qzq\smartquiz\src\main\python\import_preprocessor.py')
ipp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ipp)

p = r'D:\qzq\smartquiz\test_files\baofeng.xlsx'

# 各 sheet 索引
import openpyxl
wb = openpyxl.load_workbook(p, read_only=True)
names = wb.sheetnames
wb.close()
print('sheets:', names)

specj = {'std_columns': ['questionText','optionA','optionB','optionC','optionD','optionE','optionF','optionG','optionH','optionI','optionJ','optionK','optionL','correctAnswer','answerText','category','difficulty','explanation','questionType','source','knowledgePoint'],
         'fill_fields': ['category','difficulty','explanation','knowledgePoint'],
         'option_fields': {c: 'option'+c for c in 'ABCDEFGHIJKL'}}

mappings = {
    '单选题': {'questionText': '题目', 'optionsCombined': '选项', 'correctAnswer': '正确答案',
              'difficulty': '难度', 'category': '知识点', 'explanation': '答案解析'},
    '多选题': {'questionText': '题目', 'optionsCombined': '选项', 'correctAnswer': '正确答案'},
    '判断题': {'questionText': '题目', 'correctAnswer': '正确答案(必填)', 'difficulty': '难度',
              'category': '知识点', 'explanation': '答案解析'},
    '填空题': {'questionText': '题目', 'optionsCombined': '填空项', 'correctAnswer': '填空项'},
}

for sheet_name, mapping in mappings.items():
    idx = names.index(sheet_name)
    obj = json.loads(ipp.sample_file(p, 15, idx))
    print('\n===== %s (sheet idx %d) =====' % (sheet_name, idx))
    print('headers:', json.dumps(obj.get('headers', [])[:14], ensure_ascii=False))
    print('suspicious:', obj.get('header_suspicious'), 'hits:', obj.get('header_hits'))
    dh = obj.get('doc_hint') or ''
    print('doc_hint 前120:', dh[:120] if dh else 'None')
    rows = obj.get('rows', [])
    if rows:
        print('rows[0]:', json.dumps(rows[0][:9], ensure_ascii=False)[:220])
    # parse
    out = r'D:\qzq\smartquiz\test_files\parse_bf_' + sheet_name
    os.makedirs(out, exist_ok=True)
    for f in os.listdir(out):
        os.remove(os.path.join(out, f))
    res = json.loads(ipp.parse_file(p, json.dumps(mapping, ensure_ascii=False), out,
                                    spec_json=json.dumps(specj, ensure_ascii=False), sheet_index=idx))
    print('parse: total=%s written=%s dup=%s empty=%s missing=%s' % (
        res.get('total_rows'), res.get('written_rows'), res.get('duplicate_count'),
        res.get('empty_question_count'), len(res.get('missing', []))))
    for f in sorted(os.listdir(out)):
        if f.startswith('import_part_'):
            lines = open(os.path.join(out, f), encoding='utf-8').read().splitlines()
            print('  CSV %d 行(含表头), 表头前8: %s' % (len(lines), lines[0].split(',')[:8]))
            print('  第1题:', lines[1][:200])
            break
