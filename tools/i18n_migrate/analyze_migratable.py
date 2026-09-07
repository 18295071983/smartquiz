# -*- coding: utf-8 -*-
"""
迁移可行性分析：
对 report.json 中 ui 类记录，逐条判定替换难度：
- simple  : 独立字符串字面量，可脚本直接替换为 getString(R.string.x)
- format  : 含 %s/%d/{} 等占位符，替换需带参数
- concat  : 字符串拼接场景（行内有 + 号），替换需处理拼接
- html    : 含 HTML 标签/模板
- ctx_no  : 所在文件无 Activity/View/Adapter 上下文线索（需注入 Context）
输出 migratable_report.json + 控制台统计。
"""
import json
import os
import re
from collections import Counter

REPORT = r"D:\qzq\smartquiz\tools\i18n_migrate\report.json"
OUT = r"D:\qzq\smartquiz\tools\i18n_migrate\migratable_report.json"
ROOT = r"D:\qzq\smartquiz\src\main"

# 所在文件是否有可直接 getString 的上下文
CTX_PAT = re.compile(
    r"extends\s+\w*(Activity|Fragment|Adapter|View|Dialog|BottomSheet)"
    r"|AppCompatActivity|RecyclerView\.ViewHolder|BaseAdapter|Context"
)

def file_has_ctx(fp):
    try:
        with open(fp, "r", encoding="utf-8", errors="ignore") as f:
            head = f.read(60000)
    except Exception:
        return False
    return bool(CTX_PAT.search(head))


def classify_text(line: str, text: str, file_ctx: bool) -> str:
    if "<" in text and ">" in text and ("<title>" in text or "<br" in text or "<b>" in text or "<p>" in text or "</" in text):
        return "html"
    # 行内该字符串是否为独立字面量：看该串在行中的左右邻字符
    idx = line.find('"' + text + '"')
    if idx < 0:
        return "concat"  # 保守：找不到精确位置就当复杂
    before = line[:idx].rstrip()
    after = line[idx + len(text) + 2:].lstrip()
    has_plus = "+" in before or "+" in after
    has_format = "%" in text or "{0}" in text or "{1}" in text or "{}" in text or "$s" in text or "$d" in text
    if has_plus:
        return "concat"
    if has_format:
        return "format"
    if not file_ctx:
        return "ctx_no"
    return "simple"


def main():
    r = json.load(open(REPORT, encoding="utf-8"))
    ui = r["ui_records"]

    # 文件上下文缓存
    ctx_cache = {}

    for rec in ui:
        fp = os.path.join(ROOT, rec["file"].replace("/", os.sep))
        if fp not in ctx_cache:
            ctx_cache[fp] = file_has_ctx(fp)
        rec["ctx"] = ctx_cache[fp]
        rec["kind"] = classify_text(rec.get("_line", ""), rec["text"], ctx_cache[fp])

    # 重新读行内容做判定（classify_text 需要行文本）
    # 上面 classify 时 line 参数为空，这里重新遍历文件载入行
    line_cache = {}
    for rec in ui:
        fp = os.path.join(ROOT, rec["file"].replace("/", os.sep))
        if fp not in line_cache:
            try:
                with open(fp, "r", encoding="utf-8", errors="ignore") as f:
                    line_cache[fp] = f.readlines()
            except Exception:
                line_cache[fp] = []
        ln = rec["line"] - 1
        line = line_cache[fp][ln] if 0 <= ln < len(line_cache[fp]) else ""
        rec["_line"] = line
        rec["kind"] = classify_text(line, rec["text"], rec["ctx"])

    kinds = Counter(x["kind"] for x in ui)
    uniq_kinds = Counter()
    seen = set()
    for x in ui:
        key = (x["kind"], x["text"])
        if key not in seen:
            seen.add(key)
            uniq_kinds[x["kind"]] += 1

    out = {
        "ui_total": len(ui),
        "ui_unique": r["unique_by_category"]["ui"],
        "by_kind": dict(kinds),
        "unique_by_kind": dict(uniq_kinds),
        "records": ui,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)

    print("=" * 60)
    print(f"ui 类总出现: {len(ui)} | 唯一文案: {r['unique_by_category']['ui']}")
    print("-" * 60)
    for k in ("simple", "format", "concat", "html", "ctx_no"):
        print(f"  {k:8s}: 出现 {kinds.get(k, 0):6d} | 唯一 {uniq_kinds.get(k, 0):6d}")
    print("-" * 60)
    print("simple 即可安全自动替换的比例: "
          f"{kinds.get('simple', 0) / max(len(ui), 1) * 100:.1f}% "
          f"({kinds.get('simple', 0)}/{len(ui)})")
    print("=" * 60)


if __name__ == "__main__":
    main()
