# -*- coding: utf-8 -*-
"""
smartquiz 硬编码中文扫描分类工具
扫描 src/main/java + src/main/kotlin 中双引号内包含中文的字符串字面量，
按所在行的语义线索分为: ui(界面文案) / log(日志) / other(待定)。
输出: report.json（明细）+ 控制台统计（汇总）。
"""
import os
import re
import json
from collections import Counter

ROOT = r"D:\qzq\smartquiz\src\main"
DIRS = ["java", "kotlin"]
OUT_DIR = os.path.join(r"D:\qzq\smartquiz\tools", "i18n_migrate")

# 双引号字符串字面量（含中文），跳过行内注释结尾等粗糙情况，够用即可
STR_PAT = re.compile(r'"((?:[^"\\]|\\.)*[\u4e00-\u9fa5]+(?:[^"\\]|\\.)*)"')

UI_KEYWORDS = [
    "setText", "Toast", "makeText", "setTitle", "setMessage", "setHint",
    "setPositiveButton", "setNegativeButton", "setNeutralButton",
    "setContentDescription", "setError", "setPlaceholderText", "Snackbar",
    "snackbar", "notifyDataSetChanged", "setAdapter", "setItemChecked",
    "setChecked", "setSelected", "setLabel", "addTab", "setDescription",
    "setSummary", "setDialogMessage", "showDialog", "showToast", "toast",
    "tv.setText", "title", "hint", "message", "dialog", "AlertDialog",
    "MaterialAlertDialog", "setDisplayedChild", "setNavigationItemSelectedListener",
    "setTextColor", "spinner", "Spinner", "ArrayAdapter", "list_item",
    "simple_list_item", "TextUtils.isEmpty", "String.format", "append(",
    "setTextSize", "input.setText", "et.setText", "edt.setText", "btn.setText",
    "tv_", "btn_", "et_", "textView", "TextView", "Button", "EditText",
]

LOG_KEYWORDS = [
    "Log.", "android.util.Log", "LogUtils", "LogUtil", "L.d(", "L.i(", "L.e(",
    "L.w(", "L.v(", "Logger", "System.out", "System.err", "printStackTrace",
    "println(", "println", "LogManager", "log(", "logger",
]

DATA_KEYWORDS = [
    "http", "https", ".db", ".json", ".txt", ".md", ".wav", ".mp3", ".xml",
    ".zip", ".apk", ".jar", ".so", ".py", ".onnx", ".gguf", ".bin",
    "SELECT ", "INSERT ", "CREATE ", "UPDATE ", "WHERE ", "FROM ", "JOIN ",
    "DDL", "sql", "SQL", "JsonObject", "JSONObject", "Gson", "JSONArray",
    "model_config", "api_key", "apiKey", "token", "Bearer", "charset",
    "UTF-8", "utf-8", "application/json", "text/plain", "image/png",
]


def classify(line: str) -> str:
    if any(k in line for k in LOG_KEYWORDS):
        return "log"
    if any(k in line for k in UI_KEYWORDS):
        return "ui"
    if any(k in line for k in DATA_KEYWORDS):
        return "data"
    return "other"


def main():
    records = []
    for d in DIRS:
        base = os.path.join(ROOT, d)
        if not os.path.isdir(base):
            continue
        for root, _, files in os.walk(base):
            for fn in files:
                if not (fn.endswith(".java") or fn.endswith(".kt")):
                    continue
                fp = os.path.join(root, fn)
                try:
                    with open(fp, "r", encoding="utf-8", errors="ignore") as f:
                        lines = f.readlines()
                except Exception:
                    continue
                rel = os.path.relpath(fp, ROOT).replace("\\", "/")
                for i, line in enumerate(lines):
                    for m in STR_PAT.finditer(line):
                        s = m.group(1)
                        if len(s.strip()) < 2:
                            continue
                        # 跳过纯模板/占位符类
                        if s.strip() in ("%s", "%d", "%1$s", "%1$d", "{}"):
                            continue
                        records.append({
                            "file": rel,
                            "line": i + 1,
                            "text": s,
                            "cat": classify(line),
                        })

    total = len(records)
    cat_counter = Counter(r["cat"] for r in records)
    uniq_texts = Counter(r["text"] for r in records)

    per_file = Counter(r["file"] for r in records)
    ui_records = [r for r in records if r["cat"] == "ui"]

    os.makedirs(OUT_DIR, exist_ok=True)
    report = {
        "total": total,
        "unique_texts": len(uniq_texts),
        "by_category": dict(cat_counter),
        "unique_by_category": {
            c: len(set(r["text"] for r in records if r["cat"] == c))
            for c in ("ui", "log", "data", "other")
        },
        "top_files": per_file.most_common(30),
        "ui_records": ui_records,
    }
    with open(os.path.join(OUT_DIR, "report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)

    print("=" * 60)
    print(f"总命中（双引号中文片段，含重复）: {total}")
    print(f"去重后唯一文案数: {len(uniq_texts)}")
    print("-" * 60)
    for c in ("ui", "log", "data", "other"):
        print(f"  {c:6s}: 出现 {cat_counter.get(c, 0):6d} 次 | 唯一 {report['unique_by_category'][c]:6d}")
    print("-" * 60)
    print("按文件命中 Top 15:")
    for fn, cnt in per_file.most_common(15):
        print(f"  {cnt:5d}  {fn}")
    print("=" * 60)


if __name__ == "__main__":
    main()
