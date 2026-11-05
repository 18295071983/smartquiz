#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""资源侧孤儿扫描：`src/main/res` 里没人再引用的 layout / drawable / anim / color / menu / xml / font。

为什么要单独一把尺子：Java 侧那套判据（整词检索类名）在资源上完全不成立。资源的引用形态有三种，
少看一种就会把活资源判成孤儿：
1. 代码里 `R.layout.foo` / `R.drawable.foo`；
2. 别的资源或 Manifest 里 `@layout/foo` / `@drawable/foo` / `@style/Foo`（含 `<include>`、
   `tools:layout`、`android:icon`、`android:theme`、`android:backupRule` 等）；
3. **ViewBinding 生成的类名**——本工程 `buildFeatures.viewBinding true`，布局 foo_bar.xml 会被
   生成成 `FooBarBinding` 并在代码里按类名引用，**整条链上不出现 `R.layout.foo_bar`**。
   漏掉这一种就会把在用布局判成孤儿，是最容易翻车的一处。

还有两种"按名字动态取"的情况，机器判不死，一律标"需人工"而不是删：
- `getIdentifier(name, type, pkg)`：名字在字符串里；
- 启动器图标/自适应图标、`xml/` 下的系统配置（network_security_config、file_paths、backup_rules）、
  `values` 里被主题继承的样式。

用法：python docs/scripts/res_orphan_scan.py [--json]
"""
import io
import json
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(REPO, 'src', 'main', 'res')
# 文件型资源目录（values*/ 与 drawable-*dpi 这类限定名另算）
FILE_TYPES = {'layout': ('layout',), 'drawable': ('drawable', 'mipmap'),
              'anim': ('anim',), 'animator': ('animator',), 'xml': ('xml',),
              'menu': ('menu',), 'font': ('font',), 'color': ('color',)}
EXT = ('.xml', '.png', '.jpg', '.jpeg', '.webp', '.gif', '.ttf', '.otf', '.svg')
# 这些一律不报：系统按约定名字找、或改了会伤启动/装机
KEEP = re.compile(r'^(ic_launcher|launcher|backup_rules|network_security_config|file_paths|'
                  r'paths|searchable|preferences|adaptive|fullscreen|dialog|notification)'
                  r'|^abc_|^btn_|^ic_ab_|^common_', re.I)
DYNAMIC = re.compile(r'getIdentifier\s*\(')


def text_files():
    """把所有可能出现引用的文本一次读进来（Java/Kotlin/XML/gradle/pro/py/properties）。"""
    exts = ('.java', '.kt', '.xml', '.gradle', '.pro', '.py', '.properties', '.json', '.html', '.js')
    blob = []
    for root in ('src', 'build.gradle', 'app', 'proguard-rules.pro'):
        base = os.path.join(REPO, root) if not os.path.isfile(os.path.join(REPO, root)) else os.path.join(REPO, root)
        if os.path.isfile(base):
            blob.append(base)
            continue
        for dirpath, dirs, files in os.walk(base):
            if 'build' in dirs:
                dirs.remove('build')
            for f in files:
                if f.endswith(exts):
                    blob.append(os.path.join(dirpath, f))
    out = []
    for p in blob:
        try:
            with io.open(p, encoding='utf-8', errors='replace') as fh:
                out.append((p, fh.read()))
        except OSError:
            continue
    return out


def camel(name):
    return ''.join(w.capitalize() for w in re.split(r'[_\-.]+', name) if w)


def string_prefixes(files):
    """代码里所有以 _ 结尾的字符串字面量：它们是拼资源名的最常见形状。"""
    lits = set()
    for _, src in files:
        for m in re.finditer(r'\"([A-Za-z0-9_]{4,}_)\"', src):
            lits.add(m.group(1))
    return lits


STRING_PREFIXES = set()


def main():
    global STRING_PREFIXES
    files = text_files()
    STRING_PREFIXES = string_prefixes(files)
    joined = '\n'.join(s for _, s in files)
    dynamic_types = set(m.group(1) for m in re.finditer(
        r'getIdentifier\s*\(\s*"[^"]*"\s*,\s*"(\w+)"', joined))
    orphans = []
    for dirname, types in FILE_TYPES.items():
        for t in types:
            for sub in sorted(os.listdir(RES)) if os.path.isdir(RES) else []:
                if not (sub == t or sub.startswith(t + '-')):
                    continue
                d = os.path.join(RES, sub)
                for f in sorted(os.listdir(d)):
                    if not f.endswith(EXT):
                        continue
                    name = f.rsplit('.', 1)[0]
                    if KEEP.match(name):
                        continue
                    if '@' + t + '/' + name in joined:          # XML / Manifest 引用
                        continue
                    if 'R.' + t + '.' + name in joined:          # 代码引用
                        continue
                    if t == 'layout' and (camel(name) + 'Binding') in joined:   # ViewBinding
                        continue
                    if name.lower() in ('splash', 'loading'):    # 常见启动/占位图，另判
                        continue
                    # 动态取名字的类型（本仓库 drawable 有三处 getIdentifier，含天气图标映射
                    # QWeatherIconMapper 按拼出来的名字取图）：只靠整词检索判孤儿一定不准，
                    # 一律降级为"需人工"，并且额外看它的名字是不是某个字符串前缀拼出来的。
                    reason = None
                    if t in dynamic_types:
                        reason = '该类型有 getIdentifier 动态取用，整词检索判不了孤儿——需人工'
                    else:
                        for lit in STRING_PREFIXES:
                            if len(lit) >= 5 and lit.endswith('_') and name.startswith(lit):
                                reason = '名字像运行时拼出来的（代码里有字符串前缀 %r）——需人工' % lit
                                break
                    orphans.append({'type': t, 'name': name, 'file': os.path.relpath(
                        os.path.join(d, f), REPO).replace(os.sep, '/'),
                        'note': reason or 'res/代码/Manifest/ViewBinding 四处都不见引用'})
    print('代码里用于拼名的字符串前缀 %d 个（抽样：%s）' % (
        len(STRING_PREFIXES), ', '.join(sorted(STRING_PREFIXES)[:6]) or '无'))
    if '--json' in sys.argv:
        print(json.dumps(orphans, ensure_ascii=False, indent=1))
    else:
        print('资源孤儿候选 %d 个（判据：@type/name、R.type.name、<Camel>Binding、Manifest，四类全空）' % len(orphans))
        bytype = {}
        for o in orphans:
            bytype.setdefault(o['type'], []).append(o)
        for t in sorted(bytype):
            print('\n  %s（%d 个）' % (t, len(bytype[t])))
            for o in bytype[t]:
                print('    %-40s %s' % (o['name'], o['file']))
        if dynamic_types:
            print('\n注意：这些类型存在 getIdentifier 动态取用 %s，上面的候选要再人工确认一轮' % sorted(dynamic_types))


if __name__ == '__main__':
    main()
