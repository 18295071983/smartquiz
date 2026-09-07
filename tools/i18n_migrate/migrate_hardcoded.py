# -*- coding: utf-8 -*-
"""
硬编码中文 → strings.xml 自动迁移工具（批量字面量替换）

原理：
- 输入 migratable_report.json 中 kind != html 的 ui 记录
- 对每条记录在源文件对应行做精确字面量替换: "中文" -> <expr>.getString(R.string.<key>)
- 表达式按文件上下文启发式决定（Activity/Fragment->getString, View->getContext().getString,
  Adapter->context 字段, Application/Service->getString, 其余跳过待人工）
- key = h_ + md5(zh)[:8]，相同文案复用同一 key
- 新增三个独立资源文件（不动现有 strings.xml）:
    res/values/strings_migrated.xml (zh)
    res/values-en/strings_migrated.xml (en 占位=原文, 待翻译)
    res/values-zh-rTW/strings_migrated.xml (tw 占位=原文, 待翻译)

用法:
  python migrate_hardcoded.py                # 全量迁移（跳过 html / 无上下文）
  python migrate_hardcoded.py --file xxx.java   # 只迁移指定文件（试点）
  python migrate_hardcoded.py --dry-run         # 只出计划不落盘
"""
import os
import re
import sys
import json
import hashlib
from collections import Counter, OrderedDict

BASE = r"D:\qzq\smartquiz"
ROOT = os.path.join(BASE, "src", "main")
REPORT = r"D:\qzq\smartquiz\tools\i18n_migrate\migratable_report.json"
OUT_REPORT = r"D:\qzq\smartquiz\tools\i18n_migrate\migration_report.json"

APP_PKG = "com.oilquiz.app"

MAIN_CLASS = re.compile(
    r"^\s*(?:public\s+|abstract\s+|final\s+|private\s+|protected\s+)*"
    r"(?:class|interface)\s+\w+\s+extends\s+([^\s{<]+)",
    re.MULTILINE,
)

CTX_ACTIVITY = re.compile(r"\w*(Activity|AppCompatActivity|Fragment)$")
CTX_VIEW = re.compile(r"\w*(View|ViewGroup|ViewHolder)$")
CTX_ADAPTER = re.compile(r"\w*Adapter$")
CTX_APP = re.compile(r"(Application|Service|ContentProvider)$")
CTX_FIELD = re.compile(r"(?:private|public|protected)?\s*Context\s+(\w+)\s*[;=]")
IMPORT_R = re.compile(r"import\s+(?:%s\.)?R\s*;" % re.escape(APP_PKG))


def file_ctx_expr(fp):
    """按主类声明判定返回 (表达式, 是否可迁移)"""
    with open(fp, "r", encoding="utf-8", errors="ignore") as f:
        head = f.read(80000)
    m = MAIN_CLASS.search(head)
    parent = m.group(1) if m else ""
    if CTX_ADAPTER.search(parent):
        m2 = CTX_FIELD.search(head)
        if m2:
            return m2.group(1) + ".getString", True
        m3 = re.search(r"\([^)]*\bContext\s+(\w+)\)", head)
        if m3:
            return m3.group(1) + ".getString", True
        return None, False
    if CTX_VIEW.search(parent):
        return "getContext().getString", True
    if CTX_ACTIVITY.search(parent):
        return "getString", True
    if CTX_APP.search(parent):
        return "getString", True
    return None, False


def has_r_import(fp, lines):
    for ln in lines[:80]:
        if IMPORT_R.search(ln) or "import %s.R;" % APP_PKG in ln:
            return True
        if "import %s.R" % APP_PKG in ln:
            return True
    # 同包引用（package 与 APP_PKG 相同则无需 import）
    for ln in lines[:10]:
        if ln.startswith("package %s" % APP_PKG):
            return True
    return False


def gen_key(text):
    return "h_" + hashlib.md5(text.encode("utf-8")).hexdigest()[:8]


def esc_xml(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
             .replace('"', "&quot;").replace("'", "&apos;"))


def main():
    args = sys.argv[1:]
    only_file = None
    dry = False
    if "--file" in args:
        i = args.index("--file")
        only_file = args[i + 1]
    if "--dry-run" in args:
        dry = True

    rep = json.load(open(REPORT, encoding="utf-8"))
    records = [r for r in rep["records"] if r["kind"] != "html"]
    if only_file:
        records = [r for r in records if os.path.basename(r["file"]) == only_file]

    # 加载源文件行缓存
    line_cache = {}
    def get_lines(fp):
        if fp not in line_cache:
            with open(fp, "r", encoding="utf-8", errors="ignore") as f:
                line_cache[fp] = f.readlines()
        return line_cache[fp]

    # 文件级上下文表达式
    ctx_expr = {}
    # 文案 -> key
    key_map = OrderedDict()
    # 按文件聚合待替换（file -> list of (line, text, key, expr))
    plan = {}
    skipped = []
    replace_count = 0

    for rec in records:
        fp = os.path.join(ROOT, rec["file"].replace("/", os.sep))
        if not os.path.exists(fp):
            skipped.append((rec["file"], rec["line"], "file_missing"))
            continue
        if fp not in ctx_expr:
            expr, ok = file_ctx_expr(fp)
            ctx_expr[fp] = (expr, ok)
        expr, ok = ctx_expr[fp]
        if not ok:
            skipped.append((rec["file"], rec["line"], rec["text"][:30], "no_ctx"))
            continue
        key = gen_key(rec["text"])
        key_map.setdefault(key, rec["text"])
        plan.setdefault(fp, []).append((rec["line"], rec["text"], key, expr))
        replace_count += 1

    if dry:
        print(f"[dry-run] 计划替换 {replace_count} 处，涉及 {len(plan)} 个文件，"
              f"新 key {len(key_map)} 个")
        by_file = Counter()
        for fp, items in plan.items():
            by_file[os.path.basename(fp)] += len(items)
        for f, n in by_file.most_common(20):
            print(f"  {n:5d}  {f}")
        skip_no_ctx = [s for s in skipped if len(s) > 3 and s[3] == "no_ctx"]
        print(f"跳过(无上下文): {len(skip_no_ctx)} 处")
        print("  Top 文件:", end=" ")
        skip_by_file = Counter(os.path.basename(s[0]) for s in skip_no_ctx)
        print(", ".join(f"{f}({n})" for f, n in skip_by_file.most_common(10)))
        return

    # 执行替换
    replaced = 0
    failed = []
    file_changes = {}
    for fp, items in plan.items():
        lines = get_lines(fp)
        changed = 0
        for (ln, text, key, expr) in items:
            idx = ln - 1
            if not (0 <= idx < len(lines)):
                failed.append((os.path.basename(fp), ln, "line_oob"))
                continue
            literal = '"' + text + '"'
            repl = expr + "(R.string." + key + ")"
            if literal not in lines[idx]:
                failed.append((os.path.basename(fp), ln, "literal_not_found: " + text[:20]))
                continue
            lines[idx] = lines[idx].replace(literal, repl, 1)
            changed += 1
            replaced += 1
        if changed:
            # 确保 import R
            if not has_r_import(fp, lines):
                # 找第一个 import 行插入
                ins = 0
                for i, l in enumerate(lines[:80]):
                    if l.startswith("import "):
                        ins = i
                        break
                lines.insert(ins, "import %s.R;\n" % APP_PKG)
            with open(fp, "w", encoding="utf-8", newline="") as f:
                f.writelines(lines)
            file_changes[fp] = changed

    # 生成资源文件
    xml_zh = []
    xml_en = []
    xml_tw = []
    for key, text in key_map.items():
        e = esc_xml(text)
        xml_zh.append(f'    <string name="{key}">{e}</string>')
        # en/tw 占位为原文，待后续翻译
        xml_en.append(f'    <string name="{key}">{e}</string>')
        xml_tw.append(f'    <string name="{key}">{e}</string>')

    def write_res(rel_dir, lines):
        path = os.path.join(ROOT, rel_dir, "strings_migrated.xml")
        body = "\n".join(lines)
        content = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<resources>\n'
            '    <!-- 硬编码迁移自动生成，key 为 h_ + md5(原文) 前缀 -->\n'
            + body + "\n"
            '</resources>\n'
        )
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(content)

    write_res(os.path.join("res", "values"), xml_zh)
    write_res(os.path.join("res", "values-en"), xml_en)
    write_res(os.path.join("res", "values-zh-rTW"), xml_tw)

    report = {
        "planned": replace_count,
        "replaced": replaced,
        "failed": len(failed),
        "files_changed": len(file_changes),
        "new_keys": len(key_map),
        "skipped_no_ctx": len([s for s in skipped if s[2] == "no_ctx"]),
        "failed_samples": failed[:30],
        "skipped_samples": [s for s in skipped if s[2] == "no_ctx"][:20],
        "per_file": {os.path.basename(k): v for k, v in file_changes.items()},
    }
    with open(OUT_REPORT, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)

    print("=" * 60)
    print(f"计划替换: {replace_count} | 实际替换: {replaced} | 失败: {len(failed)}")
    print(f"改动文件: {len(file_changes)} | 新 key: {len(key_map)}")
    print(f"跳过(无上下文): {report['skipped_no_ctx']}")
    if failed:
        print("-" * 60)
        print("失败样例:")
        for s in failed[:10]:
            print("  ", s)
    print("=" * 60)


if __name__ == "__main__":
    main()
