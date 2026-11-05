#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""整类零引用候选的复核实跑：把"误匹配"从候选里剔出去，剩下的才是要决策的。

为什么要这个脚本而不是信 docs/dead-code-triage-2026-11-06.md 第 7 节那行手抄名单：
名单是 2026-11-06 上午生成的，之后删过 AILogger2 / ToolExecutionOrchestrator / AILogger 的
可视化那半，也接回过 linux_shell——名单里已经混进"已经不存在的类"；同时它还留着几条
按名字检索永远命中不了调用方的假阳性（Hilt `@Module`、注解驱动装配）。第 8 节写的四类盲区
（同名撞车 / 点号后视 / 漏 `::` / 注解驱动）里，"注解驱动"和"同名撞车"这两类只有重新按规则
搜一遍才能排掉，靠人工记忆排必然漏。

口径（每条都打印依据，不接受"看起来是死的"）：
- 检索范围 = src/main/java、src/main/kotlin、src/main/res、AndroidManifest.xml、
  src/main/assets（源码类：.java/.kt/.xml/.gradle/proguard/py），二进制资产不算引用；
- 自己的声明文件不算引用；
- 带下列注解或装配特征的，判"入口不是按名字调用"，从候选里剔除：
  @Module/@InstallIn/@AndroidEntryPoint/@HiltViewModel/@Singleton/@Keep/@JavascriptInterface、
  Room @Database/@TypeConverters、被 proguard keep 规则点名、在 Manifest 里出现；
- 只被 src/test / src/androidTest 引用的，单独归到"生产死·测试活"，不混进候选；
- 其它仍零引用的才算真候选，并附行数。

用法：python docs/scripts/dead_class_verify.py [名单文件路径]
      不给参数就直接读第 7 节那一行规模清单。
"""
import io
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REPO_SLASH = REPO.replace('\\', '/')
DOC = os.path.join(REPO, 'docs', 'dead-code-triage-2026-11-06.md')
# C/C++ 也要算进去：JNI 桥那批类（TypeConverter / GgmlProbe / AgentToolCall 这类）只在 native 侧
# 被调用，只看 Java 就会把它们误判成零引用。这是第 8 节四类盲区之外的第五类。
TEXT_EXT = ('.java', '.kt', '.xml', '.gradle', '.pro', '.py', '.properties', '.json',
            '.c', '.cpp', '.cc', '.h', '.hpp', '.td', '.cmake', '.txt')
ENTRY_ANNOT = re.compile(
    r'@(Module|InstallIn|AndroidEntryPoint|HiltViewModel|Singleton|Keep|JavascriptInterface'
    r'|Database|TypeConverters|Dao)\b')


def names_from_doc():
    """读第 7 节"规模按行数降序"那一行的 `Name` 列表。"""
    with io.open(DOC, encoding='utf-8', errors='replace') as fh:
        text = fh.read()
    m = re.search(r'规模按行数降序：(.+)', text)
    return re.findall(r'`([A-Za-z_][\w]*)`', m.group(1)) if m else []


def locate(name):
    """按"public class|interface|enum|abstract class Name"定位声明文件。"""
    r = subprocess.run(['grep', '-rl', '--include=*.java',
                        re.escape('class ' + name) , re.escape('interface ' + name),
                        re.escape('enum ' + name), os.path.join('src', 'main', 'java')],
                       cwd=REPO, capture_output=True, text=True, encoding='utf-8', errors='replace')
    files = [f for f in r.stdout.splitlines() if f]
    for f in files:
        p = os.path.join(REPO, f.replace('\\', '/').replace(os.sep, '/').replace('/', os.sep))
        try:
            with io.open(p, encoding='utf-8', errors='replace') as fh:
                src = fh.read()
        except OSError:
            continue
        if re.search(r'\b(class|interface|enum)\s+' + re.escape(name) + r'\b', src):
            return f.replace('\\', '/'), src
    return None, None


def refs(name, own):
    """整词检索引用，范围限定在文本型源文件；返回 (源码命中, 测试命中)。"""
    roots = [os.path.join(REPO, 'src', 'main'), os.path.join(REPO, 'src', 'debug'),
             os.path.join(REPO, 'build.gradle'), os.path.join(REPO, 'proguard-rules.pro')]
    hits = []
    for root in roots:
        if not os.path.exists(root):
            continue
        r = subprocess.run(['grep', '-rn', '--binary-files=without-match', '-w', name]
                           + ['--include=*' + e for e in TEXT_EXT] + [root],
                           capture_output=True, text=True, encoding='utf-8', errors='replace')
        for line in r.stdout.splitlines():
            # Windows 绝对路径里就有冒号（D:/a/File.java:12:），不能 split(':',1) 取路径；
            # REPO 带反斜杠，比较前先统一成斜杠。
            m2 = re.match(r'^([A-Za-z]:/[^:]*?\.(?:java|kt|xml|gradle|pro|py|properties|json)):',
                          line.replace('\\', '/'))
            if not m2:
                continue
            rel = m2.group(1)[len(REPO_SLASH) + 1:]
            if rel == own or rel.split('/')[-1] == name + '.java':
                continue          # 自己的声明文件不算
            hits.append(rel)
    tr = subprocess.run(['grep', '-rl', '-w', name] + ['--include=*.java']
                        + [os.path.join(REPO, 'src', 'test'), os.path.join(REPO, 'src', 'androidTest')],
                        capture_output=True, text=True, encoding='utf-8', errors='replace')
    test = [x for x in tr.stdout.splitlines() if x]
    return sorted(set(hits)), sorted(set(test))


def main():
    names = names_from_doc()
    if len(sys.argv) > 1:
        names = [n.strip() for n in io.open(sys.argv[1], encoding='utf-8').read().split() if n.strip()]
    gone, false_pos, test_only, real, not_found = [], [], [], [], []
    for name in names:
        own, src = locate(name)
        if src is None:
            not_found.append(name)          # 名单里已经不存在的类
            continue
        if ENTRY_ANNOT.search(src):
            false_pos.append((name, own, '带入口注解（按名检索永远命中不了调用方）'))
            continue
        main_hits, test_hits = refs(name, own)
        if main_hits:
            false_pos.append((name, own, '有源码引用：' + '; '.join(main_hits[:2])))
            continue
        if test_hits:
            test_only.append((name, own, len(src.splitlines()), test_hits[0].split('/')[-1]))
            continue
        real.append((name, own, len(src.splitlines())))
    out = sys.stdout.buffer

    def w(text):
        out.write(text.encode('utf-8'))

    w('名单 %d 条 → 已不在仓库 %d ｜ 误匹配剔除 %d ｜ 生产死·测试活 %d ｜ 真候选 %d（约 %d 行）\n'
      % (len(names), len(not_found), len(false_pos), len(test_only), len(real),
         sum(x[2] for x in real)))
    if not_found:
        w('\n=== 名单里已不存在的（该从文档删掉）===\n   ' + ', '.join(not_found) + '\n')
    if false_pos:
        w('\n=== 误匹配（有入口注解或真有引用）===\n')
        for n, f, why in false_pos:
            w('   %-28s %-34s %s\n' % (n, f.split('/')[-1], why))
    if test_only:
        w('\n=== 生产死·测试活（删类要连测试一起处置）===\n')
        for n, f, l, t in test_only:
            w('   %-28s %5d 行   测试：%s\n' % (n, l, t))
    if real:
        w('\n=== 真候选（零引用，按行数降序）===\n')
        for n, f, l in sorted(real, key=lambda x: -x[2]):
            w('   %5d  %-28s %s\n' % (l, n, f))


if __name__ == '__main__':
    main()
