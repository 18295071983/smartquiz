# 空 catch 台账生成器（配套 docs/empty-catch-audit-2026-11-06.md）
# 用法：python docs/scripts/empty_catch_audit.py          # 汇总 + 分档样本
#       python docs/scripts/empty_catch_audit.py --json    # 全量 JSON（可 diff 回归）
# 只读扫描，不改代码。判据与局限见文档第 1、5 节。
import os, re, sys, json, collections

SEP = chr(92)
NL = chr(10)
QUO = chr(39)
ROOTS = ['src/main/java', 'src/test/java']
METHOD_RE = re.compile(r'(public|private|protected|static|final|synchronized)[^=;{]*?\s(\w+)\s*\(')
LOG_TOKENS = ('Log.', 'AILogger', 'AppLogger', 'System.out', 'System.err', 'println')
WRITE_HINT = re.compile(
    '(save|write|insert|update|delete|exec|download|install|persist|commit|flush|mkdir|copy|move'
    '|extract|append|remove|restore|import|export|create|rename|upload|clear|purge)', re.I)
UI_HINT = re.compile(
    '(onDraw|measure|layout|release|destroy|close|hide|show|format|dp|sp|toString|hashCode|equals'
    '|interrupt|scroll|animate|click|touch|visibility)', re.I)


def code_mask(text):
    """逐字符标记是否属于代码（排除注释与字符串/字符字面量）。跨行状态机，中途不重置。"""
    mask = [True] * len(text)
    i, n, state = 0, len(text), 'code'
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ''
        if state == 'code':
            if c == '/' and nxt == '/':
                mask[i] = mask[i + 1] = False
                state, i = 'line', i + 2
                continue
            if c == '/' and nxt == '*':
                mask[i] = mask[i + 1] = False
                state, i = 'block', i + 2
                continue
            if c == '"':
                mask[i] = False
                state, i = 'str', i + 1
                continue
            if c == QUO:
                mask[i] = False
                state, i = 'chr', i + 1
                continue
            i += 1
            continue
        mask[i] = False
        if state == 'line':
            if c == NL:
                state = 'code'
            i += 1
            continue
        if state == 'block':
            if c == '*' and nxt == '/':
                state = 'code'
                i += 2
                continue
            i += 1
            continue
        if c == SEP:
            i += 2
            continue
        if (state == 'str' and c == '"') or (state == 'chr' and c == QUO):
            state = 'code'
        i += 1
    return mask


def strip_comments(text):
    out = list(text)
    mask = code_mask(text)
    for i, ok in enumerate(mask):
        if not ok:
            out[i] = ' '
    return ''.join(out)


def method_at(lines, idx):
    for k in range(idx, max(-1, idx - 250), -1):
        if not re.match(r'^\s*(public|private|protected)\b', lines[k]):
            continue
        m = re.search(r'\s(\w+)\s*\(', lines[k])
        if m:
            return m.group(1)
    return ''


def classify(body):
    bare = strip_comments(body).strip()
    if bare == '':
        return 'empty'
    if all(any(t in stmt for t in LOG_TOKENS) for stmt in
           [s.strip() for s in bare.split(NL) if s.strip() not in ('', ';')]):
        return 'log_only'
    if re.fullmatch(r'[\w.\[\]]+\s*=\s*[^;]{1,60}', bare.replace(NL, ' ').rstrip(';')):
        return 'trivial_assign'
    return 'recovery'


def audit():
    rows = []
    for root in ROOTS:
        if not os.path.isdir(root):
            continue
        for d, _, fs in os.walk(root):
            for f in fs:
                if not f.endswith('.java'):
                    continue
                p = os.path.join(d, f).replace(SEP, '/')
                text = open(p, encoding='utf-8', errors='replace').read()
                lines = text.split(NL)
                mask = code_mask(text)
                for m in re.finditer(r'catch\s*\(', text):
                    s = m.start()
                    if not mask[s]:
                        continue
                    depth, k = 0, m.end() - 1
                    while k < len(text):
                        if mask[k]:
                            if text[k] == '(':
                                depth += 1
                            elif text[k] == ')':
                                depth -= 1
                                if depth == 0:
                                    break
                        k += 1
                    header = text[m.end() - 1:k + 1]
                    b = k + 1
                    while b < len(text) and not (mask[b] and text[b] in '{;'):
                        b += 1
                    if b >= len(text) or text[b] != '{':
                        continue
                    depth, j = 0, b
                    while j < len(text):
                        if mask[j]:
                            if text[j] == '{':
                                depth += 1
                            elif text[j] == '}':
                                depth -= 1
                                if depth == 0:
                                    break
                        j += 1
                    body = text[b + 1:j]
                    line = text[:s].count(NL) + 1
                    if 'Throwable' in header:
                        ctype = 'Throwable'
                    elif re.search(r'\bError\b', header):
                        ctype = 'Error'
                    elif re.search(r'\bException\b', header):
                        ctype = 'Exception'
                    else:
                        ctype = 'specific'
                    vm = re.search(r'(\w+)\s*$', header.rstrip(' )'))
                    rows.append({
                        'file': p,
                        'line': line,
                        'kind': classify(body),
                        'comment_only': bool(body.strip()) and strip_comments(body).strip() == '',
                        'caught': ctype,
                        'var': vm.group(1) if vm else '',
                        'method': method_at(lines, line - 1),
                        'src': 'main' if p.startswith('src/main') else 'test',
                    })
    return rows


rows = audit()
if '--json' in sys.argv:
    print(json.dumps(rows, ensure_ascii=False))
    sys.exit(0)

main = [r for r in rows if r['src'] == 'main']
empty = [r for r in main if r['kind'] == 'empty' and not r['comment_only']]
commented = [r for r in main if r['kind'] == 'empty' and r['comment_only']]
print('src/main 里 catch 总数: %d | 真·空体: %d | 只有注释的空体: %d | 只打日志: %d | 只有赋值: %d | 有恢复动作: %d'
      % (len(main), len(empty), len(commented),
         len([r for r in main if r['kind'] == 'log_only']),
         len([r for r in main if r['kind'] == 'trivial_assign']),
         len([r for r in main if r['kind'] == 'recovery'])))
print()
high = [r for r in empty if WRITE_HINT.search(r['method']) or WRITE_HINT.search(r['file'])]
low = [r for r in empty if r not in high and UI_HINT.search(r['method'])]
mid = [r for r in empty if r not in high and r not in low]
print('分档（启发式，需人工确认）: 高危=%d（写/持久化/工具执行相关） 中危=%d 低危=%d（UI/测量/回收/格式化相关）'
      % (len(high), len(mid), len(low)))
print('捕获类型分布:', dict(collections.Counter(r['caught'] for r in empty)))
print('变量名 top:', collections.Counter(r['var'] for r in empty).most_common(6))
print()
by_mod = collections.Counter('/'.join(r['file'].split('/')[5:7]) for r in empty)
print('空体按模块前 12:')
for k, v in by_mod.most_common(12):
    print('   %4d  %s' % (v, k))
print()
by_file = collections.Counter(r['file'] for r in empty)
print('空体最多的 20 个文件:')
for k, v in by_file.most_common(20):
    print('   %3d  %s' % (v, k))
print()
print('高危样本（前 30，file:line 方法 捕获类型）:')
for r in high[:30]:
    print('   %s:%d  %s()  catch %s' % (r['file'], r['line'], r['method'], r['caught']))
