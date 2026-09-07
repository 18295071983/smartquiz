# -*- coding: utf-8 -*-
"""
prepare_translations.py
读取 tools/i18n_migrate/translations_{zh,en,tw}.tsv（key<TAB>value，值处于 XML 实体层），
按 values/strings_migrated.xml 的 key 顺序与 formatted 标志，写回三个
strings_migrated.xml：values / values-en / values-zh-rTW。
并做 XML 合法性防护：裸 & < > 自动实体化、裸 ' 转义为 \\'（跳过已有 \\'）。
"""
import io
import os
import re
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(BASE, '..', '..', 'src', 'main', 'res'))

HEADER = '<!-- 硬编码迁移自动生成，key 为 h_ + md5(原文) 前缀 -->\n'


def read_tsv(path):
    out = {}
    with io.open(path, encoding='utf-8') as f:
        for line in f.read().splitlines():
            if not line or '\t' not in line:
                print('[warn] skip line: %r' % line)
                continue
            k, v = line.split('\t', 1)
            out[k] = v
    return out


def parse_zh_xml(path):
    """返回 (header, [(key, formatted)], formatted_set)"""
    with io.open(path, encoding='utf-8') as f:
        data = f.read()
    header = ''
    m = re.search(r'<!--.*?-->', data, re.S)
    if m:
        header = m.group(0) + '\n'
    entries = []
    formatted_set = set()
    for mm in re.finditer(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', data, re.S):
        k = mm.group(1)
        fmt = 'formatted="false"' in mm.group(2)
        entries.append((k, fmt))
        if fmt:
            formatted_set.add(k)
    return header, entries, formatted_set


def escape_value(v, audit):
    """值处于 XML 实体层；做防护性转义，并返回 (转义后, 是否改动)。"""
    changed = False
    # 1) 裸 &（不是合法实体开头）-> &amp;
    out = []
    i = 0
    n = len(v)
    while i < n:
        ch = v[i]
        if ch == '&':
            m = re.match(r'&(amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);', v[i:])
            if m:
                out.append(v[i:i + len(m.group(0))])
                i += len(m.group(0))
            else:
                out.append('&amp;')
                changed = True
                i += 1
        else:
            out.append(ch)
            i += 1
    v = ''.join(out)
    # 2) 裸 < > -> 实体（跳过已实体化的）
    for a, b in (('<', '&lt;'), ('>', '&gt;')):
        # 只处理不在实体序列中的裸符号
        v = re.sub(r'(?<!&)' + re.escape(a), b, v)
    # 3) 裸单引号 -> \'（Android 资源规则；跳过已转义的 \'）
    v = re.sub(r"(?<!\\)'", "\\'", v)
    return v, changed


def build(zh_map, en_map, tw_map, header, entries, formatted_set):
    rows = []
    for k, fmt in entries:
        rows.append((k, fmt))
    zh_formatted = formatted_set

    def fmt_attr(k):
        return ' formatted="false"' if k in zh_formatted else ''

    texts = {}
    for k, _ in rows:
        texts[k] = escape_value(zh_map[k], None)[0]
    audit = {'escaped': []}
    parts_zh = []
    parts_en = []
    parts_tw = []
    for k, _ in rows:
        if k not in zh_map:
            raise SystemExit('zh missing key: %s' % k)
        zv, zc = escape_value(zh_map[k], None)
        ev, ec = escape_value(en_map.get(k, zh_map[k]), None)
        tv, tc = escape_value(tw_map.get(k, zh_map[k]), None)
        if zc or ec or tc:
            audit['escaped'].append(k)
        parts_zh.append('    <string name="%s"%s>%s</string>' % (k, fmt_attr(k), zv))
        parts_en.append('    <string name="%s"%s>%s</string>' % (k, fmt_attr(k), ev))
        parts_tw.append('    <string name="%s"%s>%s</string>' % (k, fmt_attr(k), tv))
    header_xml = '<resources>\n' + HEADER
    for name, parts in (('values', parts_zh), ('values-en', parts_en), ('values-zh-rTW', parts_tw)):
        out_path = os.path.join(RES, name, 'strings_migrated.xml')
        content = header_xml + '\n'.join(parts) + '\n</resources>\n'
        with io.open(out_path, 'w', encoding='utf-8', newline='\n') as f:
            f.write(content)
        print('[ok] wrote %s (%d strings)' % (out_path, len(parts)))
    print('[audit] escaped keys: %d -> %s' % (len(audit['escaped']), audit['escaped'][:20]))


def main():
    zh = read_tsv(os.path.join(BASE, 'translations_zh.tsv'))
    en = read_tsv(os.path.join(BASE, 'translations_en.tsv'))
    tw = read_tsv(os.path.join(BASE, 'translations_tw.tsv'))
    zh_xml = os.path.join(RES, 'values', 'strings_migrated.xml')
    header, entries, fmt_set = parse_zh_xml(zh_xml)
    print('[info] zh xml entries: %d, formatted: %d' % (len(entries), len(fmt_set)))
    print('[info] tsv: zh=%d en=%d tw=%d' % (len(zh), len(en), len(tw)))
    miss_en = set(zh) - set(en)
    miss_tw = set(zh) - set(tw)
    if miss_en:
        print('[warn] en missing %d keys: %s' % (len(miss_en), sorted(miss_en)[:10]))
    if miss_tw:
        print('[warn] tw missing %d keys: %s' % (len(miss_tw), sorted(miss_tw)[:10]))
    build(zh, en, tw, header, entries, fmt_set)


if __name__ == '__main__':
    main()
