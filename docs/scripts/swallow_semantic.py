#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""按"吞掉的那件事有没有后果"给空 catch 分档——常驻判据，取代关键词分档。

为什么另开一个脚本而不是往 empty_catch_audit.py 里塞关键词：
`empty_catch_audit.py` 的高危/中危/低危是按**方法名与文件名的动词**猜的，两次实测证明它会漏、
也会误报——v17→v25 那批 32 处全在 `migrate()` 里，一条都没进高危档，可后果是整库升级失败；
反过来按"catch 之后 30 行里有没有 return true"猜"假成功"，捞出的 10 处人工读完**全是假阳性**
（旁证：那些位置本来就有理由注释，或删除失败最坏只是多降级一次）。

所以这一档不问"名字像不像危险"，只问那句话：**这件事失败之后，会不会有人以为它成功了**——
用户、模型、还是下一次启动读盘的代码。判据是吞掉的语句本身含副作用动词或落盘出口。

用法：
    python docs/scripts/swallow_semantic.py            # 分档计数 + S1/S2 明细
    python docs/scripts/swallow_semantic.py --s1-only  # 只列 S1（落盘/持久化 + 删改名），给脚本喂用

S1/S2 是要逐处读的队列（几十处量级）；S3/S4 绝大多数是探测、展示、关流收尾，读完确认合理就留在原地。
"""
import io
import json
import os
import re
import subprocess
import sys

# 只认"会改东西"的动词。之前把 load*/start*/bind* 也算副作用，于是
# WeatherBannerView:439 那种"读 prefs 后备一个默认值"被误投进 S2——分档要当队列用，
# 但队列里混进读操作就没人愿意再看了，所以判据收紧：动词必须落在写/删/改名/发送上。
SIDE = re.compile(
    r'\.(delete|deleteRecursively|renameTo|mkdirs?|commit|apply|flush)\s*\('
    r'|(save|insert|update|delete|remove|write|append|copy|move|restore|import|export|'
    r'create|rename|upload|clear|purge|exec|execute|send|post|enqueue|register|install|'
    r'download|persist)\w*\s*\(', re.I)

# 真正的"落盘出口"，而不是"沾了 SharedPreferences 这个词"
PERSIST = re.compile(
    r'\.edit\(\)|\.(putBoolean|putString|putInt|putLong|putFloat)\(|\.commit\(\)|'
    r'execSQL|\.insert\(|\.update\(|Dao\.(insert|update|delete|add)|'
    r'FileWriter|FileOutputStream|printWriter|openOutputStream|BufferedOutputStream|'
    r'getWritableDatabase', re.I)
# 已经有可见反馈（打了日志/审计）的站点不重复报
REPORTED = re.compile(r'AILogger\.|AppLogger|Log\.[wdie]\(|System\.(out|err)')


def audit_rows():
    """复用关键词扫描器的解析结果，避免两套解析器漂移。"""
    out = subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__),
                                                       'empty_catch_audit.py'), '--json'],
                         capture_output=True, text=True, encoding='utf-8', errors='replace').stdout
    return json.loads(out[out.find('['):])


def try_body(lines, catch_index):
    """回看找出与这条 catch 配对的 try，返回它吞掉的语句正文。"""
    for start in range(catch_index, max(-1, catch_index - 60), -1):
        if 'try' in lines[start]:
            span = '\n'.join(lines[start:catch_index + 1])
            if span.count('{') - span.count('}') in (0, 1):
                return '\n'.join(lines[start + 1:catch_index]) or span
    return '\n'.join(lines[max(0, catch_index - 3):catch_index])


def main():
    s1_only = '--s1-only' in sys.argv
    buckets = {'S1 落盘/持久化+删改名': [], 'S2 落盘/持久化': [],
               'S3 其它有副作用': [], 'S4 看不出副作用': []}
    for r in audit_rows():
        if r['src'] != 'main' or r['kind'] != 'empty':
            continue
        path = r['file'].replace(os.sep, '/')
        try:
            lines = io.open(path, encoding='utf-8', errors='replace').read().split('\n')
        except OSError:
            continue
        ci = r['line'] - 1
        body = try_body(lines, ci)
        # 同一条 catch 体里已经有日志/审计的，说明这处已经处理过，不再排队
        tail = '\n'.join(lines[ci:ci + 4])
        if REPORTED.search(tail) or REPORTED.search(body.split('\n')[-1] if body else ''):
            continue
        side, pers = bool(SIDE.search(body)), bool(PERSIST.search(body))
        key = ('S1 落盘/持久化+删改名' if side and pers else 'S2 落盘/持久化' if pers
               else 'S3 其它有副作用' if side else 'S4 看不出副作用')
        buckets[key].append((path, r['line'], r['method'], r['var'], body.strip().replace('\n', ' ')[:96]))

    total = sum(len(v) for v in buckets.values())
    if not s1_only:
        print('待读队列（空体 src/main，已排除有反馈的站点）：共 %d 处' % total)
        for k in ('S1 落盘/持久化+删改名', 'S2 落盘/持久化', 'S3 其它有副作用', 'S4 看不出副作用'):
            print('  %-24s %d' % (k, len(buckets[k])))
        print('')
        print('判据：S1/S2 逐处读；S3 抽查；S4 默认保留。分档是队列不是结论。')
    for k in ('S1 落盘/持久化+删改名', 'S2 落盘/持久化'):
        print('\n=== %s（%d 处）===' % (k, len(buckets[k])))
        for path, line, method, var, snippet in buckets[k]:
            short = path.split('com/oilquiz/app/')[-1]
            print('   %s:%d %s var=%s' % (short, line, method, var))
            if not s1_only:
                print('        %s' % snippet)


if __name__ == '__main__':
    main()
