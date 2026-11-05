# 私有成员死代码扫描器（配套 docs/dead-code-triage-2026-11-06.md）
# 用法：python docs/scripts/deadcode_scan.py         # 只打印计划（dry-run）
#       python docs/scripts/deadcode_scan.py --apply  # 按计划在仓库内删除
# 判据与盲区说明见文档第 1、8 节；--apply 前务必先看 dry-run 输出。
import os, re, sys, json

ROOT = 'src/main/java'
BS = chr(92)
NL = chr(10)

def code_mask(text):
    mask = [True] * len(text)
    i, n, state = 0, len(text), 'code'
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ''
        if state == 'code':
            if c == '/' and nxt == '/': state, mask[i], mask[i+1] = 'line', False, False; i += 2; continue
            if c == '/' and nxt == '*': state, mask[i], mask[i+1] = 'block', False, False; i += 2; continue
            if c == '"' or c == chr(39):
                state = 'str' if c == '"' else 'chr'; mask[i] = False; i += 1; continue
            i += 1; continue
        mask[i] = False
        if state == 'line':
            if c == NL: state = 'code'
            i += 1; continue
        if state == 'block':
            if c == '*' and nxt == '/': mask[i+1] = False; state = 'code'; i += 2; continue
            i += 1; continue
        if c == BS:
            if i + 1 < n: mask[i+1] = False
            i += 2; continue
        if (state == 'str' and c == '"') or (state == 'chr' and c == chr(39)):
            state = 'code'
        i += 1
    return mask

def line_starts(text):
    starts = [0]
    for k, ch in enumerate(text):
        if ch == NL: starts.append(k + 1)
    return starts

def line_of(starts, pos):
    lo, hi = 0, len(starts) - 1
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if starts[mid] <= pos: lo = mid
        else: hi = mid - 1
    return lo

def head_up(lines, decl_line):
    """把方法/字段上方的注解与紧邻 javadoc 一起纳入删除区间"""
    u = decl_line - 1
    seen = False
    while u >= 0 and lines[u].strip().startswith('@'):
        u -= 1; seen = True
    if u >= 0 and lines[u].strip() == '*/':
        top = u
        while top >= 0 and not lines[top].strip().startswith('/**'):
            top -= 1
        if top >= 0:
            return top
    return decl_line - 1 if seen else decl_line

def method_end(mask, starts, text, decl_line):
    p = starts[decl_line]
    depth, k, close = 0, p, -1
    while k < len(mask):
        if mask[k]:
            c = text[k]
            if c == '(': depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0: close = k; break
        k += 1
    if close < 0: return None
    b = close
    while not (mask[b] and text[b] == '{'): b += 1
    depth, j = 0, b
    while j < len(mask):
        if mask[j]:
            c = text[j]
            if c == '{': depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0: break
        j += 1
    return line_of(starts, j)

def const_end(mask, starts, text, decl_line, lines):
    """字段语句：从声明行起，直到代码层出现收尾分号（深度 0）"""
    pos = starts[decl_line]
    end_line = decl_line
    k = pos
    par = bra = cur = 0
    while k < len(text):
        if mask[k]:
            c = text[k]
            if c in '([': par += 1
            elif c in ')]': par -= 1
            elif c in '{' : cur += 1
            elif c == '}': cur -= 1
            elif c == ';' and par == 0 and cur == 0:
                end_line = line_of(starts, k)
                return end_line
        k += 1
    return None

meth_re = re.compile(r'^\s*(private|private static)\s+(?:final\s+)?[\w<>\[\],.\s]+?\s(\w+)\s*\([^{]*\)\s*\{')
const_re = re.compile(r'^\s*private\s+static\s+final\s+[\w<>\[\],.]+\s+(\w+)\s*=')
SAFE_CONST = re.compile(r'^[A-Z][A-Z_0-9]*$')
SKIP_ANN = ('@JavascriptInterface', '@Override', '@Subscribe', '@Nullable', '@NonNull')
SKIP_FIELD_ANN = ('@SerializedName', '@ColumnInfo', '@Expose', '@Persisted', '@PrimaryKey', 'androidx.room')

def build_plan():
    plan = []
    for dirpath, _, files in os.walk(ROOT):
        for fn in sorted(files):
            if not fn.endswith('.java'): continue
            path = os.path.join(dirpath, fn)
            text = open(path, encoding='utf-8').read()
            lines = text.split(NL)
            starts = line_starts(text)
            mask = code_mask(text)
            spans = []
            for i, l in enumerate(lines):
                m = meth_re.match(l)
                if m:
                    name = m.group(2)
                    if name == fn[:-5]: continue
                    near = NL.join(lines[max(0, i - 3):i])
                    if any(a in near for a in SKIP_ANN): continue
                    if len(re.findall(r'(?<![\w$])' + name + r'\b', text)) > 1: continue
                    e = method_end(mask, starts, text, i)
                    if e is None:
                        print('!! 方法无法定界', path, i + 1, name); continue
                    spans.append([head_up(lines, i), e, name, 'method'])
                    continue
                c = const_re.match(l)
                if c:
                    name = c.group(1)
                    if not SAFE_CONST.match(name): continue
                    near = NL.join(lines[max(0, i - 5):i])
                    if any(a in near for a in SKIP_FIELD_ANN): continue
                    if len(re.findall(r'(?<![\w$])' + name + r'(?![\w$])', text)) > 1: continue
                    e = const_end(mask, starts, text, i, lines)
                    if e is None:
                        print('!! 常量无法定界', path, i + 1, name); continue
                    spans.append([head_up(lines, i), e, name, 'const'])
            if not spans: continue
            spans.sort()
            bad = False
            for a in range(1, len(spans)):
                if spans[a][0] <= spans[a - 1][1]:
                    print('!! 区间重叠，整文件跳过', path); bad = True; break
            if bad: continue
            for sp in spans:
                if sp[1] - sp[0] > 300:
                    print('!! 区间超 300 行，整文件跳过', path, sp[2]); bad = True; break
                nx = sp[1] + 1
                if nx < len(lines) and lines[nx].strip() == '':
                    sp[1] = nx
            if bad: continue
            plan.append([path, spans])
    return plan

plan = build_plan()
if '--apply' in sys.argv:
    for path, spans in plan:
        lines = open(path, encoding='utf-8').read().split(NL)
        for s, e, name, kind in sorted(spans, key=lambda x: -x[0]):
            del lines[s:e + 1]
        open(path, 'w', encoding='utf-8', newline=NL).write(NL.join(lines))
    print('已应用')
else:
    meth = sum(1 for _, sps in plan for s, e, n, k in sps if k == 'method')
    const = sum(1 for _, sps in plan for s, e, n, k in sps if k == 'const')
    tot = sum(e - s + 1 for _, sps in plan for s, e, n, k in sps)
    print('files', len(plan), 'methods', meth, 'consts', const, 'lines', tot)
    for path, spans in plan:
        for s, e, name, kind in spans:
            print(f'{kind:6} {e - s + 1:4} {path}:{s + 1} {name}')
