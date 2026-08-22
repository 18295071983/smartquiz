# -*- coding: utf-8 -*-
"""
离线题库批量导入 —— Python 文件预处理层。

分层权限隔离：本模块仅负责 SQL/Excel/JSON/CSV 题库源文件解析与标准化 CSV 中转，
仅操作公共存储目录（/storage/emulated/0/OilQuiz/），无数据库访问权限。
海量题库文本不进入模型上下文。

对外接口（供 Java ImportPythonBridge 调用）：
- sample_file(path, max_rows)          采样：表头 + 前 N 行
- parse_file(path, mapping_json, out_dir, resume_row, chunk_rows, breakpoint_path, spec_json)
                                         全量解析 + 分片 CSV + 断点进度
- apply_fills(chunk_path, fills_json)  回写 AI 填充结果至 CSV 分片

动态字段注入：标准列/填充字段/选项字段均由 Java 侧根据 question 表实际结构
（PRAGMA table_info）通过 spec_json 参数下发，本文件常量仅作为解析失败时的兜底默认值。
"""

import csv
import itertools
import json
import os
import re
import sqlite3
import time

# 标准化 CSV 固定列（仅兜底默认值：正常由 Java 侧 spec_json 动态下发）
STD_COLUMNS = ["questionText", "optionA", "optionB", "optionC", "optionD",
               "correctAnswer", "category", "difficulty", "explanation",
               "questionType", "source"]

# 可补充字段（仅兜底默认值：缺失时收集交给 AI 填充）
FILL_FIELDS = ("category", "difficulty", "explanation")

# 选项字段（仅兜底默认值：收集 missing 时附带）
OPTION_FIELDS = {"A": "optionA", "B": "optionB", "C": "optionC", "D": "optionD"}

# missing 列表上限，防止超大题库撑爆返回 JSON
MAX_MISSING = 500

# 表头行定位关键词（命中越多越可能是真表头行，兼容标题行/说明行在前）
_HEADER_KEYWORDS = {
    "题干", "题目", "问题", "题目内容", "答案", "正确答案", "选项", "可选项",
    "题型", "难度", "分数", "解析", "答案解析", "说明", "序号", "分类",
    "类别", "知识点", "关键字", "questiontext", "question", "answer",
    "correctanswer", "options", "difficulty", "type", "score",
}

# 候选分隔符（自动检测选用）：按优先级排列，检测时取拆分段数最多且无空段的
_OPT_DELIMITERS = ["；", ";", "|", "｜", "、", "，", ",", "\t", "/", "／", "~", "～", "　", " "]


def _extract_doc_hint(raw_rows, header_start_row):
    """从表头之前的原始行中提取题库说明/模板说明文本。
    返回拼接后的说明文本；无说明时返回 None。
    说明块特征（表头前置的说明/模板说明/封面文字）：
    - 含强说明词（说明/模板/填写/必填/示例/注意/请勿/格式/规范/要求/录入/每题/校验/标题/题号）；
    - 或含多条编号规则（"1.…2.…" 多段）；
    表头之后的数据行一律不参与（避免题干误判）。
    """
    if not raw_rows:
        return None
    lines = []
    # 只看表头之前的行（header_start_row 之前）；无法定位时看前 3 行
    limit = header_start_row if header_start_row is not None and header_start_row > 0 else 3
    # 表头行本身不算说明（即使含"说明"列名），排除
    excluded = header_start_row if header_start_row is not None and header_start_row >= 0 else -1
    for i, row in enumerate(raw_rows[:limit]):
        if i == excluded:
            continue
        if not row:
            continue
        cells = [str(c) for c in row if c is not None and str(c).strip() != ""]
        if not cells:
            continue
        joined = " ".join(cells)
        first = cells[0].strip()
        # 排除纯题号行（首格数字且内容短）
        if first.isdigit() and len(joined) < 20:
            continue
        # 排除表头型行：列数多（≥5）且基本都是短词（列名特征），如"关键字 题型 难度 分数…"
        if len(cells) >= 5 and all(len(c) < 12 for c in cells):
            continue
        strong = ("说明", "模板", "填写", "必填", "示例", "注意", "请勿",
                  "格式", "规范", "要求", "录入", "删除", "每题", "校验",
                  "标题", "题号", "系统")
        hits = sum(1 for kw in strong if kw in joined)
        # 多条编号规则（1.…2.…3.…）也算说明
        numbered = len(re.findall(r"[1-9][0-9]{0,1}[.、)．]", joined))
        if hits >= 1 and numbered >= 2:
            lines.append(joined)
        elif hits >= 2 and len(joined) >= 10:
            lines.append(joined)
        elif hits >= 1 and len(joined) >= 40 and not first.isdigit():
            lines.append(joined)
        # 宽松提取：无任何关键词的简单说明（如"宝丰能源安全题库"、"共180题，请核对"等）。
        # 表头之前、非表头型、非题号行的非空短行，视为简单说明一并提取——说明对映射推理
        # 有帮助（如题型范围/来源信息），宁可多提取也不漏。
        elif len(joined) >= 6 and len(joined) <= 200 and not first.isdigit():
            lines.append(joined)
    if not lines:
        return None
    return "\n".join(lines)[:2000]

def _split_options(raw, preferred=None):
    """自动拆分聚合选项列：检测分隔符 + 字母前缀模式，返回拆分后的选项列表（去前缀、去空）。

    检测算法（非硬编码单一分隔符）：
    0. 说明驱动：preferred 为题库说明指定的分隔符（如"多个备选答案用竖线'/'分隔"→ "/"），
       且存在于文本时优先使用（说明是文件自己的格式约定，比自动检测更可信）
    1. 字母前缀模式优先："A. xxx B. xxx" / "A、xxx" / "A) xxx" → 按字母分界拆分
    2. 分隔符自动检测：遍历候选分隔符，按"拆分段数最多 + 无空段 + 各段长度合理"评分，
       选最优者（如内容用分号则分号拆出的段数最合理，不会误用出现次数多的逗号）
    3. 换行拆分
    4. 整段视为单个选项
    """
    if not raw:
        return []
    text = str(raw).strip()
    if not text:
        return []

    # 0. 说明指定的分隔符优先（动态：不同文件说明不同，分隔符随之不同）
    if preferred:
        pd = str(preferred).strip()
        if pd and pd in text:
            raw_parts = [p.strip() for p in text.split(pd)]
            non_empty = [p for p in raw_parts if p]
            if len(non_empty) >= 2:
                # 用说明分隔符拆出的多段选项，直接使用（跳过自动检测）
                parts = non_empty
            else:
                parts = None  # 说明分隔符在文本中不产生多段 → 回退自动检测
            if parts is not None:
                return _strip_option_prefixes(parts)

    # 1. 字母前缀模式（A./A、/A)/A．/A: /A： 后跟内容，且至少 2 组）
    letter_parts = re.split(
        r"\s*(?=[A-Za-z][.、)．:：])\s*", text)
    cleaned = [p.strip() for p in letter_parts if p.strip()]
    if len(cleaned) >= 2:
        parts = cleaned
    else:
        # 2. 分隔符自动检测：对每个候选分隔符尝试拆分，评分选择最优。
        #    评分=段数*2 - 空段*10 - 短段轻罚 - 空格类降权。
        #    段数权重最高（数值选项 "2；3；4；5"、单字选项 "是；否" 也能正确拆开），
        #    空段重罚（连续分隔符/误拆），空格类分隔符额外降权（防误拆含空格长句）。
        best_parts = None
        best_score = 0
        for d in _OPT_DELIMITERS:
            if d not in text:
                continue
            raw_parts = [p.strip() for p in text.split(d)]
            # 空段惩罚：真实分隔符不会产生空段（除非连续分隔符）
            non_empty = [p for p in raw_parts if p]
            if len(non_empty) < 2:
                continue
            empty_count = len(raw_parts) - len(non_empty)
            avg_len = sum(len(p) for p in non_empty) / len(non_empty)
            score = len(non_empty) * 2 - empty_count * 10
            if avg_len < 2:
                score -= 1
            if d.isspace():
                score -= 2
            if score > best_score:
                best_score = score
                best_parts = non_empty
        if best_parts is not None:
            parts = best_parts
        else:
            # 3. 换行拆分
            parts = [p.strip() for p in text.splitlines() if p.strip()]
        # 若分隔拆分只有 1 段但内容明显多段（如换行），兜底换行
        if len(parts) <= 1 and ("\n" in text or "\r" in text):
            parts = [p.strip() for p in text.splitlines() if p.strip()]

    return _strip_option_prefixes(parts)


def _strip_option_prefixes(parts):
    r"""剥离每段前缀：仅当字母/序号后有分隔符跟随（A. / A、/ 1. / 一、）。
    无分隔符跟随的字母/数字（"2"、"TRUE"）一律保留；数字分支用 (?!\d)
    防止把小数 "10.5" 的 "10." 误当序号前缀剥成 "5"。"""
    result = []
    for p in parts:
        p2 = re.sub(
            r"^\s*(?:[A-Za-z一二三四五六七八九十]{1,3}[.、)．:：]|[1-9][0-9]{0,2}[.、)．:：](?!\d))\s*",
            "", p).strip()
        if not p2:
            continue
        # 段尾残留分隔符剥除（字母前缀路径下 "A.甲；" 的分号残留）
        p2 = re.sub(r"[；;|｜、，,\t/／~～　 ]+$", "", p2).strip()
        if p2:
            result.append(p2)
    return result


def _detect_header(rows):
    """在前 12 行内定位真实表头行，兼容标题行/说明行置顶与选项字母双子行模板。

    返回 (header_list, data_start_row, header_hits)；
    header_hits 为命中表头关键词的单元格数，0/1 视为表头可疑（交由 LLM 识别）；
    无法识别时兜底第 0 行（hits=0）。
    """
    n = len(rows)
    limit = min(n, 12)
    best_i, best_hits = 0, 0
    for i in range(limit):
        hits = 0
        for c in rows[i]:
            if c and str(c).strip().lower() in _HEADER_KEYWORDS:
                hits += 1
        if hits > best_hits:
            best_i, best_hits = i, hits
    if best_hits == 0:
        return (rows[0] if n else []), (1 if n else 0), 0
    header = list(rows[best_i])
    data_start = best_i + 1
    # 双子行模板：表头下一行是连续选项字母 A/B/C... 或 填空位 空1/空2/空3... → 合并。
    # 若不合并，子表头行（A/B/C 或 空1/空2）会被当成数据行，导致选项列错位/多出垃圾题。
    if data_start < n:
        nxt = rows[data_start]
        letters = []
        for c in nxt:
            s = str(c).strip().upper() if c is not None else ""
            if s == "":
                letters.append("")
            elif len(s) == 1 and "A" <= s <= "L":
                letters.append(s)
            elif re.match(r"^空\d+$", s):
                letters.append(s)
            else:
                letters = []
                break
        if len([x for x in letters if x]) >= 3:
            for j in range(min(len(header), len(letters))):
                if not letters[j]:
                    continue
                if re.match(r"^空\d+$", letters[j]):
                    # 填空位：第一个填空位保留主表头原列名（"填空项"），
                    # 后续加序号（"填空项2/填空项3"）——保留原列名保证
                    # optionsCombined/correctAnswer 等按"填空项"映射仍能命中。
                    prefix = str(header[j]).strip() if j < len(header) and header[j] else "填空项"
                    header[j] = prefix if letters[j] == "空1" else prefix + letters[j][1:]
                else:
                    prefix = str(header[j]).strip() if j < len(header) and header[j] else "选项"
                    header[j] = prefix + letters[j]
            data_start += 1
    return header, data_start, best_hits


def _iter_xls(path):
    """旧版 .xls（xlrd）：遍历全部工作表，逐表定位表头后合并行"""
    try:
        import xlrd
    except ImportError:
        raise RuntimeError("xlrd 不可用，无法解析 xls")
    wb = xlrd.open_workbook(path)
    headers = []
    rows = []
    for sh in wb.sheets():
        all_rows = [[_norm_cell(sh.cell_value(r, c)) for c in range(sh.ncols)]
                    for r in range(sh.nrows)]
        all_rows = [r for r in all_rows if any(x != "" for x in r)]
        if not all_rows:
            continue
        h, start, _hits = _detect_header(all_rows)
        if not h:
            continue
        if not headers:
            headers = h
        rows.extend(all_rows[start:])
    return headers, rows


def _resolve_spec(spec_json):
    """解析 Java 下发的动态字段规格；解析失败/缺失时用内置默认值兜底。

    spec_json 格式：{"std_columns":[...], "fill_fields":[...], "option_fields":{"A":"optionA",...}}
    """
    std = STD_COLUMNS
    fill = list(FILL_FIELDS)
    opts = dict(OPTION_FIELDS)
    if spec_json:
        try:
            spec = json.loads(spec_json)
            sc = spec.get("std_columns")
            if isinstance(sc, list) and "questionText" in sc:
                std = [str(c) for c in sc]
            ff = spec.get("fill_fields")
            if isinstance(ff, list) and ff:
                fill = [str(f) for f in ff]
            of = spec.get("option_fields")
            if isinstance(of, dict) and of:
                opts = {str(k): str(v) for k, v in of.items()}
        except Exception:
            pass  # 解析失败 → 全部用兜底默认值，流程不中断
    return std, fill, opts


def _kind_of(path):
    ext = os.path.splitext(path)[1].lower()
    if ext == ".xlsx":
        return "xlsx"
    if ext in (".csv", ".txt"):
        return "csv"
    if ext in (".md", ".markdown"):
        return "md"
    if ext == ".json":
        return "json"
    if ext == ".sql":
        return "sql"
    if ext in (".db", ".sqlite", ".sqlite3", ".db3"):
        return "db"
    if ext == ".xls":
        return "xls"
    return "unknown"


def _read_text(path):
    for enc in ("utf-8", "gbk", "latin-1"):
        try:
            with open(path, "r", encoding=enc) as f:
                return f.read()
        except (UnicodeDecodeError, UnicodeError):
            continue
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        return f.read()


def _norm_cell(v):
    if v is None:
        return ""
    s = str(v).strip()
    # 单元格内换行归一为空格：避免 CSV 出现跨行引号字段，
    # 下游按行读取的组件（Java ingest/扫描）不会把一行切碎
    if "\n" in s or "\r" in s:
        s = re.sub(r"[\r\n]+", " ", s).strip()
    return s


def _trim_trailing_empties(cols):
    """去掉列表尾部全空元素：Excel 常因格式残留把 max_column 撑到 255 列，
    导致表头列表尾部挂着一长串空字符串。裁剪后表头仅保留真实列，
    避免 LLM 提示词噪音、列数一致性误判。数据行仍按位置取值，不受影响。"""
    n = len(cols)
    while n > 0 and (cols[n - 1] or "") == "":
        n -= 1
    return cols[:n]


# ==================== 各格式行迭代器 ====================

def _iter_xlsx(path, sheet_index=None, header_row=None):
    """流式读取 xlsx：两遍读（read_only 模式开销小）。

    指定 sheet_index 时只读该 sheet（用户已在 UI 选择工作表）；
    否则自动扫全部 sheet：第一遍定位首个有效 sheet 的表头，第二遍逐 sheet 产出，
    跨 sheet 表头（列数+列名）不一致的 sheet 跳过，避免按首个表头错位合并。

    header_row：LLM 识别出的真实表头行号（0-based，仅 sheet_index 指定时生效）：
      - None：自动检测表头（默认）
      - >=0：直接以该行作为表头，数据从下一行开始（不再扫描）
      - -1：无表头文件，首行即数据，用占位列名 "列1/列2/..."
    返回 (headers, 数据行生成器)。
    """
    try:
        import openpyxl
    except ImportError:
        raise RuntimeError("openpyxl 不可用，无法解析 xlsx")

    def _sheet_rows(ws):
        it = ws.iter_rows(values_only=True)
        head_rows = []
        for r in it:
            head_rows.append([_norm_cell(c) for c in r])
            if len(head_rows) >= 12:
                break
        head_rows = [r for r in head_rows if any(x != "" for x in r)]
        return head_rows, it

    # 指定 sheet + 指定表头行（LLM 识别结果）：不扫描，直接按行号取表头
    if sheet_index is not None and header_row is not None:
        wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
        try:
            sheets = wb.worksheets
            if sheet_index < 0 or sheet_index >= len(sheets):
                return [], iter(())
            ws = sheets[sheet_index]
            need = (header_row + 1) if header_row >= 0 else 1
            head_rows = []
            it = ws.iter_rows(values_only=True)
            for r in it:
                head_rows.append([_norm_cell(c) for c in r])
                if len(head_rows) >= need:
                    break
        finally:
            wb.close()

        if header_row == -1:
            # 无表头：占位列名，首行即数据
            first = next((r for r in head_rows if any(x != "" for x in r)), [])
            h = ["列%d" % (i + 1) for i in range(len(first))]
            start = 0
        else:
            if header_row >= len(head_rows) or not head_rows:
                return [], iter(())
            h = list(head_rows[header_row])
            start = header_row + 1

        def gen_specified():
            wb2 = openpyxl.load_workbook(path, read_only=True, data_only=True)
            try:
                ws = wb2.worksheets[sheet_index]
                it = ws.iter_rows(values_only=True)
                head_rows = []
                need = (header_row + 1) if header_row >= 0 else 1
                for r in it:
                    head_rows.append([_norm_cell(c) for c in r])
                    if len(head_rows) >= need:
                        break
                begin = 0 if header_row == -1 else (header_row + 1)
                for r in head_rows[begin:]:
                    if any(x != "" for x in r):
                        yield r
                for r in it:
                    row = [_norm_cell(c) for c in r]
                    if any(x != "" for x in row):
                        yield row
            finally:
                wb2.close()

        return _trim_trailing_empties(h), gen_specified()

    # 指定 sheet：直接读该 sheet 的表头
    if sheet_index is not None:
        wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
        try:
            sheets = wb.worksheets
            if sheet_index < 0 or sheet_index >= len(sheets):
                return [], iter(())
            head_rows, _ = _sheet_rows(sheets[sheet_index])
            if not head_rows:
                return [], iter(())
            h, start, _hits = _detect_header(head_rows)
            if not h:
                return [], iter(())
        finally:
            wb.close()

        def gen_single():
            wb2 = openpyxl.load_workbook(path, read_only=True, data_only=True)
            try:
                ws = wb2.worksheets[sheet_index]
                it = ws.iter_rows(values_only=True)
                head_rows = []
                for r in it:
                    head_rows.append([_norm_cell(c) for c in r])
                    if len(head_rows) >= 12:
                        break
                head_rows = [r for r in head_rows if any(x != "" for x in r)]
                _h, start, _hits = _detect_header(head_rows) if head_rows else (None, 0, 0)
                for r in head_rows[start:]:
                    if any(x != "" for x in r):
                        yield r
                for r in it:
                    row = [_norm_cell(c) for c in r]
                    if any(x != "" for x in row):
                        yield row
            finally:
                wb2.close()

        return _trim_trailing_empties(h), gen_single()

    # 未指定 sheet：自动扫全部（原有逻辑）
    # 第一遍：定位首个有效 sheet 的表头
    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
    main_header = None
    try:
        for ws in wb.worksheets:
            head_rows, _ = _sheet_rows(ws)
            if not head_rows:
                continue
            h, _start, _hits = _detect_header(head_rows)
            if h:
                main_header = h
                break
    finally:
        wb.close()

    if not main_header:
        return [], iter(())

    def gen():
        wb2 = openpyxl.load_workbook(path, read_only=True, data_only=True)
        try:
            for ws in wb2.worksheets:
                it = ws.iter_rows(values_only=True)
                head_rows = []
                for r in it:
                    head_rows.append([_norm_cell(c) for c in r])
                    if len(head_rows) >= 12:
                        break
                head_rows = [r for r in head_rows if any(x != "" for x in r)]
                if not head_rows:
                    continue
                h, start, _hits = _detect_header(head_rows)
                if not h:
                    continue
                # 表头一致性校验（列数与列名），不一致的 sheet 跳过。
                # 比较前去掉尾部空列：避免某 sheet 因 Excel 格式残留多出的
                # 空列（如 255 列）被误判为不一致而整表跳过。
                h_trim = _trim_trailing_empties(h)
                main_trim = _trim_trailing_empties(main_header)
                if len(h_trim) != len(main_trim) or any((a or "") != (b or "")
                                                        for a, b in zip(h_trim, main_trim)):
                    continue
                for r in head_rows[start:]:
                    if any(x != "" for x in r):
                        yield r
                for r in it:
                    row = [_norm_cell(c) for c in r]
                    if any(x != "" for x in row):
                        yield row
        finally:
            wb2.close()

    return _trim_trailing_empties(main_header), gen()


def _raw_head_rows(path, sheet_index=None, max_rows=12):
    """读取 Excel 前 max_rows 行原始内容（含空行，行号与工作表一致），
    供表头可疑判断与 LLM 表头识别使用。sheet_index=None 时取首个非空 sheet。"""
    try:
        import openpyxl
    except ImportError:
        return []
    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
    try:
        sheets = wb.worksheets
        if not sheets:
            return []
        target = None
        if sheet_index is not None and 0 <= sheet_index < len(sheets):
            target = sheets[sheet_index]
        else:
            # 自动：取首个非空 sheet
            for ws in sheets:
                for r in ws.iter_rows(values_only=True):
                    if any(c is not None and str(c).strip() != "" for c in r):
                        target = ws
                        break
                if target is not None:
                    break
            if target is None and sheets:
                target = sheets[0]
        if target is None:
            return []
        out = []
        for r in target.iter_rows(values_only=True):
            out.append([_norm_cell(c) for c in r])
            if len(out) >= max_rows:
                break
        return out
    finally:
        wb.close()


def _open_text(path):
    """打开文本文件（自动探测编码），返回文件对象。UTF-8 BOM 自动剥离。"""
    with open(path, "rb") as f:
        head = f.read(4096)
    if head.startswith(b"\xef\xbb\xbf"):
        return open(path, "r", encoding="utf-8-sig", errors="replace")
    for enc in ("utf-8", "gbk", "latin-1"):
        try:
            head.decode(enc)
            return open(path, "r", encoding=enc, errors="replace")
        except (UnicodeDecodeError, UnicodeError):
            continue
    return open(path, "r", encoding="utf-8", errors="replace")


def _iter_csv(path):
    """流式读取 CSV：读前 50 行嗅探分隔符并定位表头，其余行逐行产出。
    返回 (headers, 数据行生成器)；生成器耗尽或异常时自动关闭文件。"""
    f = _open_text(path)
    try:
        head_lines = []
        while len(head_lines) < 50:
            line = f.readline()
            if line == "":
                break
            head_lines.append(line)
        sample = "".join(head_lines)
        try:
            dialect = csv.Sniffer().sniff(sample, delimiters=",;\t|")
        except csv.Error:
            dialect = csv.excel

        reader = csv.reader(itertools.chain(head_lines, iter(f.readline, "")), dialect)

        def norm_rows():
            try:
                for row in reader:
                    yield [_norm_cell(c) for c in row]
            finally:
                f.close()

        all_rows = norm_rows()
        # 分隔符嗅探失败时按竖线/制表符兜底（第一行单列且含分隔符）
        first = next(all_rows, None)
        if first is not None and len(first) == 1 and ("|" in first[0] or "\t" in first[0]):
            sep = "|" if "|" in first[0] else "\t"

            def split_rows():
                try:
                    yield first[0].split(sep)
                    for row in all_rows:
                        if row and row[0]:
                            yield row[0].split(sep)
                finally:
                    f.close()
            all_rows = split_rows()
        else:
            def prepend_first():
                try:
                    yield first
                    for row in all_rows:
                        yield row
                finally:
                    f.close()
            all_rows = prepend_first()

        header = next(all_rows, None)
        if header is None:
            f.close()
            return [], iter(())
        return header, all_rows
    except Exception:
        f.close()
        raise


def _iter_md(path):
    """流式读取 Markdown 表格文本（AI 导入导出的 .md 工作表）：
    按 | 分隔符切分单元格，跳过 Markdown 分隔行（|---|---|）与空行。
    返回 (headers, 数据行生成器)。"""
    f = _open_text(path)
    try:
        lines = []
        for line in f:
            s = line.strip()
            if not s:
                continue
            # Markdown 表格分隔行：|---|:---:|---| 等
            if re.match(r"^\s*\|?[\s:\-]+\|[\s:\-|]+\|?\s*$", s) and "-" in s:
                continue
            lines.append(s)

        if not lines:
            f.close()
            return [], iter(())

        def parse_cells(line):
            parts = line.strip().strip("|").split("|")
            return [_norm_cell(c) for c in parts]

        header = parse_cells(lines[0])

        def gen():
            try:
                for line in lines[1:]:
                    cells = parse_cells(line)
                    if any(c for c in cells):
                        yield cells
            finally:
                f.close()

        return header, gen()
    except Exception:
        f.close()
        raise


def _iter_json(path):
    text = _read_text(path)
    data = json.loads(text)
    if isinstance(data, dict):
        for key in ("questions", "data", "rows", "list", "items"):
            if key in data and isinstance(data[key], list):
                data = data[key]
                break
    if not isinstance(data, list) or not data:
        return [], iter(())
    headers = []
    for item in data[:20]:
        if isinstance(item, dict):
            for k in item.keys():
                if k not in headers:
                    headers.append(str(k))
    if not headers:
        return [], iter(())

    def gen():
        for item in data:
            if isinstance(item, dict):
                yield [_norm_cell(item.get(h, "")) for h in headers]

    return headers, gen()


_SQL_INSERT_RE = re.compile(
    r"INSERT\s+INTO\s+[`\"]?(\w+)[`\"]?\s*\(([^)]*)\)\s*VALUES\s*",
    re.IGNORECASE)


def _split_sql_values(segment):
    """解析 VALUES 后的一组或多组元组，返回元组列表"""
    tuples = []
    i = 0
    n = len(segment)
    while i < n:
        if segment[i] == '(':
            i += 1
            cells = []
            cur = []
            in_str = False
            quote = "'"
            while i < n:
                ch = segment[i]
                if in_str:
                    if ch == quote:
                        if i + 1 < n and segment[i + 1] == quote:
                            cur.append(quote)
                            i += 2
                            continue
                        in_str = False
                    else:
                        cur.append(ch)
                else:
                    if ch in ("'", '"'):
                        in_str = True
                        quote = ch
                    elif ch == ',':
                        cells.append("".join(cur).strip())
                        cur = []
                    elif ch == ')':
                        cells.append("".join(cur).strip())
                        break
                    else:
                        cur.append(ch)
                i += 1
            tuples.append([_norm_cell(c) if c.upper() != "NULL" else "" for c in cells])
        i += 1
    return tuples


_SQL_MAX_ROWS = 50000  # SQL 脚本数据行数上限，防超大脚本全量物化


def _iter_sql(path):
    text = _read_text(path)
    headers = []
    collected = []

    def gen():
        for m in _SQL_INSERT_RE.finditer(text):
            cols = [c.strip().strip('`"[]') for c in m.group(2).split(",")]
            end = text.find(";", m.end())
            segment = text[m.end():end if end > 0 else len(text)]
            tuples = _split_sql_values(segment)
            if not headers:
                headers.extend(cols)
            if cols == headers:
                for row in tuples:
                    if len(collected) >= _SQL_MAX_ROWS:
                        return
                    collected.append(row)
                    yield row

    return headers, gen()


def _pick_db_table(db_path):
    """挑选含题干+答案类列且行数最多的表；数据行流式产出（游标逐行，防大表全量物化）。
    返回 (conn, headers, 数据行生成器)；调用方负责关闭 conn。"""
    conn = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True)
    try:
        tables = [r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'")]
        best = None
        best_score = -1
        for t in tables:
            try:
                cols = [r[1] for r in conn.execute('PRAGMA table_info("%s")' % t)]
                cnt = conn.execute('SELECT COUNT(*) FROM "%s"' % t).fetchone()[0]
            except sqlite3.Error:
                continue
            low = ",".join(c.lower() for c in cols)
            score = 0
            if re.search(r"question|题干|题目|stem", low):
                score += 2
            if re.search(r"answer|答案", low):
                score += 2
            score += min(cnt, 100000) / 100000.0
            if score > best_score and ("question" in low or "answer" in low):
                best_score = score
                best = (t, cols)
        if not best:
            return conn, None, iter(())
        t, cols = best
        cur = conn.execute('SELECT * FROM "%s"' % t)

        def gen():
            for r in cur:
                yield [_norm_cell(c) for c in r]

        return conn, cols, gen()
    except Exception:
        try:
            conn.close()
        except Exception:
            pass
        raise


# ==================== 对外接口 ====================

def sample_file(path, max_rows=15, sheet_index=None):
    """采样：表头 + 前 max_rows 行（单元格截断由 Java 侧二次处理）。
    sheet_index：Excel 用户选定工作表索引（None=自动扫全部）。

    返回补充字段：
    - header_suspicious：表头命中关键词 ≤1，自动检测不可信（交由 Java LLM 识别）
    - header_hits：表头命中关键词数
    - raw_rows：仅 suspicious 时返回，工作表前 12 行原始内容（含空行，行号与工作表一致），
      供 LLM 判断真实表头行号与列含义。
    """
    try:
        max_rows = int(max_rows) if max_rows else 15
        kind = _kind_of(path)
        suspicious = False
        hits = 0
        raw = None
        if kind == "xlsx":
            headers, rows = _iter_xlsx(path, sheet_index)
            sampled = list(itertools.islice(rows, max_rows))
            # 行同样裁剪尾部空列（Excel 格式残留 255 列）：预览与 LLM 提示词不携带噪音
            sampled = [_trim_trailing_empties(r) for r in sampled]
            # 表头可信度检测：读取原始前 12 行重新判定
            raw = _raw_head_rows(path, sheet_index)
            _h, _start, hits = _detect_header(raw) if raw else (None, 0, 0)
            suspicious = hits <= 1
        elif kind == "csv":
            headers, rows = _iter_csv(path)
            sampled = list(itertools.islice(rows, max_rows))
            # CSV 原始前 12 行（含表头前可能的说明行）：简单按行拆单元格
            try:
                raw = []
                with _open_text(path) as f:
                    for _ in range(12):
                        line = f.readline()
                        if not line:
                            break
                        raw.append([c.strip() for c in line.rstrip("\r\n").split(",")])
                _h, _start, hits = _detect_header(raw) if raw else (None, 0, 0)
            except Exception:
                raw = None
                hits = 0
        elif kind == "md":
            headers, rows = _iter_md(path)
            sampled = list(itertools.islice(rows, max_rows))
        elif kind == "json":
            headers, rows = _iter_json(path)
            sampled = list(itertools.islice(rows, max_rows))
        elif kind == "sql":
            headers, rows = _iter_sql(path)
            sampled = list(itertools.islice(rows, max_rows))
        elif kind == "db":
            conn, headers, rows = _pick_db_table(path)
            try:
                if headers is None:
                    return json.dumps({"error": "未找到合适的题库表"}, ensure_ascii=False)
                sampled = list(itertools.islice(rows, max_rows))
            finally:
                try:
                    conn.close()
                except Exception:
                    pass
        elif kind == "xls":
            headers, rows = _iter_xls(path)
            sampled = list(itertools.islice(rows, max_rows))
        else:
            return json.dumps({"error": "不支持的文件类型: %s" % kind},
                              ensure_ascii=False)
        result = {
            "source_kind": kind,
            "headers": headers,
            "rows": sampled,
            "header_suspicious": suspicious,
            "header_hits": hits,
        }
        # 题库说明/模板说明提取（有则用，无则跳过）：供 Java 注入映射/表头识别提示词。
        # 说明通常在表头之前，基于 raw_rows（前 12 行）检测；raw 为 None 时回退 sampled。
        try:
            header_start = -1
            if raw:
                _h2, header_start, _hits2 = _detect_header(raw)
            doc = _extract_doc_hint(raw if raw else sampled, header_start)
            if doc:
                result["doc_hint"] = doc
        except Exception:
            pass
        if raw:
            result["raw_rows"] = raw
        return json.dumps(result, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"error": str(e)}, ensure_ascii=False)


def _update_breakpoint(bp_path, processed):
    if not bp_path:
        return
    try:
        state = {}
        if os.path.exists(bp_path):
            try:
                with open(bp_path, "r", encoding="utf-8") as f:
                    state = json.load(f)
            except Exception:
                state = {}
        state["parseRowIndex"] = int(processed)
        state["updatedAt"] = int(time.time() * 1000)
        tmp = bp_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(state, f, ensure_ascii=False)
        os.replace(tmp, bp_path)
    except Exception:
        pass


def _map_row(headers, row, mapping, source_name, std_columns, option_fields, col_index=None,
             preferred_delimiter=None):
    """按映射规则把源行转换为标准字段字典。
    col_index：表头→列索引映射，调用方构建一次传入（避免每行重复构建）。
    preferred_delimiter：题库说明指定的选项分隔符（如"用竖线|分隔"→"|"），优先用于拆分。"""
    out = {c: "" for c in std_columns}
    if col_index is None:
        col_index = {h.strip(): i for i, h in enumerate(headers)} if headers else {}
    for std, src in mapping.items():
        if not src:
            continue
        # 虚拟字段：合并选项列（分号/竖线/顿号分隔）拆分到 optionA~L
        if std == "optionsCombined":
            if src in col_index and col_index[src] < len(row):
                raw = str(row[col_index[src]])
                parts = _split_options(raw, preferred_delimiter)
                letters = list(option_fields.keys()) or list("ABCDEFGHIJKL")
                for k, letter in enumerate(letters):
                    if k >= len(parts):
                        break
                    col = option_fields.get(letter, "option" + letter)
                    if col in out and not out[col]:
                        out[col] = parts[k]
            continue
        if std not in out:
            continue
        if src in col_index:
            idx = col_index[src]
            if idx < len(row):
                out[std] = _norm_cell(row[idx])
    if "source" in out and not out.get("source"):
        out["source"] = source_name
    return out


def _ctx_summary(rec):
    """相邻题目上下文摘要：供缺失字段的 LLM 推断参考（题型/难度/分类/题干，简短但足够支撑一致性推断）。"""
    if not rec:
        return None
    parts = []
    qt = rec.get("questionType", "")
    if qt:
        parts.append("题型=" + qt[:10])
    df = rec.get("difficulty", "")
    if df:
        parts.append("难度=" + str(df)[:6])
    cat = rec.get("category", "")
    if cat:
        parts.append("分类=" + cat[:12])
    q = rec.get("questionText", "")
    if q:
        parts.append("题干=" + q[:60])
    return "; ".join(parts) if parts else None


def parse_file(path, mapping_json, out_dir, resume_row=0, chunk_rows=3000,
               breakpoint_path=None, spec_json=None, sheet_index=None,
               header_row=None, default_question_type=None, option_delimiter=None):
    """全量解析：分片写标准化 CSV，实时写断点，收集缺失字段题目。
    sheet_index：Excel 用户选定工作表索引（None=自动扫全部）。
    header_row：LLM 识别的真实表头行号（0-based；-1=无表头；None=自动检测）。
    default_question_type：工作表名推断的标准题型（如"单选题"），源表无题型列时
    填充到 questionType，避免按题型分 sheet 的题库导入后题型为空。
    option_delimiter：题库说明指定的选项分隔符（如"用竖线|分隔"→"|"），
    优先用于拆分聚合选项列；None 时自动检测。

    spec_json：Java 侧根据 question 表实际结构动态下发的字段规格
    （std_columns/fill_fields/option_fields），为空时用内置默认值兜底。
    """
    conn = None
    try:
        std_columns, fill_fields, option_fields = _resolve_spec(spec_json)
        mapping = json.loads(mapping_json) if mapping_json else {}
        resume_row = int(resume_row or 0)
        chunk_rows = int(chunk_rows or 3000)
        os.makedirs(out_dir, exist_ok=True)

        kind = _kind_of(path)
        if kind == "xlsx":
            headers, rows = _iter_xlsx(path, sheet_index, header_row)
        elif kind in ("csv",):
            headers, rows = _iter_csv(path)
        elif kind == "md":
            headers, rows = _iter_md(path)
        elif kind == "json":
            headers, rows = _iter_json(path)
        elif kind == "sql":
            headers, rows = _iter_sql(path)
        elif kind == "db":
            conn, headers, rows = _pick_db_table(path)
            if headers is None:
                return json.dumps({"success": False,
                                   "error": "未找到合适的题库表"}, ensure_ascii=False)
        elif kind == "xls":
            headers, rows = _iter_xls(path)
        else:
            return json.dumps({"success": False,
                               "error": "不支持的文件类型: %s" % kind},
                              ensure_ascii=False)

        source_name = os.path.basename(path)
        # 表头→列索引映射只构建一次（表头全文件固定，避免每行重复构建）
        col_index = {h.strip(): i for i, h in enumerate(headers)} if headers else {}
        # 流式解析：rows 为生成器，无法预知总数；total 由处理行数代替（Java 侧不消费 total_rows）
        total = 0

        # 已有分片（续导时保留）+ 新分片计数器
        existing = sorted(f for f in os.listdir(out_dir)
                          if f.startswith("import_part_") and f.endswith(".csv"))
        part_index = len(existing) + 1
        chunks = [os.path.join(out_dir, f) for f in existing]

        missing = []
        skipped_log = []     # 跳过诊断（题干为空的行号+原因，最多 20 条）
        skipped_total = 0    # 已废弃（保留字段，实际不再累计）；重复/题干空各自单独计数
        duplicate_total = 0  # 文件内重复题数（写入分片，入库层统一去重）
        empty_question_total = 0  # 题干为空的题数（写入分片，入库判失败；与重复区分）
        pending_missing = None  # 待补 ctx_next 的缺失条目
        written = 0          # 本次新写行数
        processed = resume_row
        cur_file = None
        cur_writer = None
        cur_rows_in_file = 0
        seen = set()
        # 重复/近似重复明细（供 UI 展示"哪道题重复、为何重复"）：
        # dup_map: 判重键 → {question, answer, rows:[数据题序号...]}
        # stem_map: 题干键 → {key, question, rows:[...]}（同题干不同答案，均保留）
        dup_map = {}
        stem_map = {}

        # ===== 阶段0：缓存全部行（生成器只能消费一次，阶段1统计 + 主循环写盘都要用）=====
        # 内存保护：>50000 行用流式缓存（仍可两遍消费，list 内存约每行数百字节，50k 行可控）；
        # 超过 200k 行降级为"全部字段参与缺失收集"（放弃整列缺失判定，避免撑爆内存）。
        all_rows = list(rows)
        if len(all_rows) > 200000:
            # 超大文件：不缓存，直接把所有 fill_fields 视为"部分缺失"（回退旧行为）
            all_rows = None
            active_fill_fields = list(fill_fields)
            print("[import_preprocessor] 超大文件(>200k行)，跳过整列缺失判定", flush=True)
        else:
            active_fill_fields = None  # 阶段1计算

        # ===== 阶段1：统计各 fill_field 的非空行数（判断"整列缺失" vs "个别行缺失"）=====
        # 整列缺失（源表没有该列数据/整列空白）不是"题目缺字段"，不参与缺失收集与告警；
        # 只有"部分行有值、个别行缺失"的字段才收集（可被 AI 填充）。
        col_nonempty = {f: 0 for f in fill_fields}
        col_total = 0
        if all_rows is not None:
            for idx, row in enumerate(all_rows):
                if idx < resume_row:
                    continue
                rec_probe = _map_row(headers, row, mapping, source_name, std_columns,
                                     option_fields, col_index, option_delimiter)
                q = re.sub(r"\s+", "", rec_probe.get("questionText", ""))
                if not q:
                    continue  # 题干为空行不参与列统计
                col_total += 1
                for f in fill_fields:
                    v = rec_probe.get(f)
                    if v is not None and str(v).strip() != "":
                        col_nonempty[f] = col_nonempty.get(f, 0) + 1
            # 可参与缺失收集的字段 = 非空行数 > 0 且 < 总行数（部分缺失；整列缺失排除）
            active_fill_fields = [f for f in fill_fields
                                  if col_nonempty.get(f, 0) > 0
                                  and col_nonempty.get(f, 0) < col_total]
            if not active_fill_fields:
                active_fill_fields = []  # 全部整列缺失 → 无缺失收集
            # 记录诊断
            for f in fill_fields:
                if col_nonempty.get(f, 0) == 0:
                    print("[import_preprocessor] 列整列缺失不参与填充: %s" % f, flush=True)
        else:
            active_fill_fields = active_fill_fields or list(fill_fields)

        def open_new_part():
            nonlocal part_index, cur_file, cur_writer, cur_rows_in_file
            if cur_writer:
                cur_writer[0].close()
            name = "import_part_%04d.csv" % part_index
            part_index += 1
            cur_file = os.path.join(out_dir, name)
            fh = open(cur_file, "w", encoding="utf-8", newline="")
            w = csv.writer(fh)
            w.writerow(std_columns)
            cur_writer = (fh, w)
            cur_rows_in_file = 0
            chunks.append(cur_file)

        bp_counter = 0
        # 断点语义：记录"已写入分片的最后源行号"（last_written），而非"已处理行号"。
        # 恢复时 resume_row=last_written，已写行绝不重写，杜绝检查点粒度重叠导致的 CSV 重复。
        last_written = resume_row
        prev_rec = None   # 上一题完整记录（供缺失字段上下文推断）
        # 超大文件（all_rows=None）：重新打开迭代器消费；否则用缓存
        _iter_source = all_rows if all_rows is not None else rows
        for idx, row in enumerate(_iter_source):
            if idx < resume_row:
                continue
            processed = idx + 1

            rec = _map_row(headers, row, mapping, source_name, std_columns,
                           option_fields, col_index, option_delimiter)
            # 题型兜底：源表无题型列时，用工作表名推断的题型填充（按题型分 sheet 的模板）
            if default_question_type and not rec.get("questionType"):
                rec["questionType"] = default_question_type
            # 判重键 = 题干 + 答案（仅题干判重会误杀"题干同但答案/选项不同"的题目，
            # 如多选题的不同版本）；题干本身不截断（_norm_cell 只去空白归一换行）。
            q_key = re.sub(r"\s+", "", rec.get("questionText", ""))
            a_key = re.sub(r"\s+", "", rec.get("correctAnswer", ""))
            key = q_key + "\u0001" + a_key
            # 题干为空：不丢弃，写入分片（题干留空），由入库层判为"必填缺失"计入失败，
            # 并在预览中展示为"缺题干"——避免题目从总数中静默消失。
            # 题干为空的行不参与去重（空 key 全相同会误伤不同题）。
            if not q_key:
                if cur_writer is None or cur_rows_in_file >= chunk_rows:
                    open_new_part()
                cur_writer[1].writerow([rec.get(c, "") for c in std_columns])
                last_written = processed
                row_in_chunk = cur_rows_in_file
                cur_rows_in_file += 1
                written += 1
                empty_question_total += 1
                # 题干为空的行不累计 skipped_total（不视为"跳过"，由入库层判失败并计入统计）
                # 诊断：记录跳过原因（题干列为空），仅首 20 条避免刷屏
                if len(skipped_log) < 20:
                    skipped_log.append(
                        "row=%d 题干为空（questionText映射列=%s, 表头=%s）" % (
                            idx + 1, mapping.get("questionText", "?"), headers))
                prev_rec = rec
                bp_counter += 1
                if bp_counter % 500 == 0:
                    _update_breakpoint(breakpoint_path, last_written)
                continue
            # 文件内重复：不丢弃，写入分片并单独计数（预览可见），
            # 由入库层用完整题干统一去重（库内已有才判重复）——
            # 避免"用户看到的题被判重后从预览消失"。
            if key in seen:
                duplicate_total += 1
                d = dup_map.get(key)
                if d is not None:
                    d["rows"].append(idx + 1)  # 重复出现行号（数据题序号，1-based）
            else:
                seen.add(key)
                dup_map[key] = {
                    "question": rec.get("questionText", "")[:120],
                    "answer": rec.get("correctAnswer", "")[:120],
                    "rows": [idx + 1],  # 首次出现行号
                }
            # 同题干不同答案（近似重复）：均保留，仅提示供用户核对
            if q_key:
                if q_key in stem_map:
                    prev = stem_map[q_key]
                    if prev["key"] != key:
                        prev["rows"].append(idx + 1)
                else:
                    stem_map[q_key] = {
                        "key": key,
                        "question": rec.get("questionText", "")[:120],
                        "rows": [idx + 1],
                    }

            # 当前题已写入：若上一题缺失字段，则当前题就是它的"下一题"上下文，补 ctx_next 并收尾
            if pending_missing is not None:
                pending_missing["ctx_next"] = _ctx_summary(rec)
                missing.append(pending_missing)
                pending_missing = None

            if cur_writer is None or cur_rows_in_file >= chunk_rows:
                open_new_part()

            cur_writer[1].writerow([rec.get(c, "") for c in std_columns])
            last_written = processed
            row_in_chunk = cur_rows_in_file
            cur_rows_in_file += 1
            written += 1

            # 收集缺失可补充字段的题目（填充字段由 Java 动态指定，且仅"部分行缺失"的字段参与）：
            # 当前题缺失 → 挂起 pending，等下一题补 ctx_next（若下一题存在）
            if len(missing) < MAX_MISSING and active_fill_fields:
                need = any(not rec.get(f) for f in active_fill_fields)
                if need:
                    pending_missing = {
                        "chunk": cur_file,
                        "row": row_in_chunk,
                        "questionText": rec.get("questionText", "")[:120],
                        "options": {
                            k: rec.get(v, "")[:40] for k, v in option_fields.items()
                        },
                        "has": {f: bool(rec.get(f)) for f in active_fill_fields},
                        "ctx_prev": _ctx_summary(prev_rec),
                        "ctx_next": None,
                    }

            prev_rec = rec

            # 实时写断点（每 500 行一次，值为已写行号；降低 IO）
            bp_counter += 1
            if bp_counter % 500 == 0:
                _update_breakpoint(breakpoint_path, last_written)

        # 文件末尾：最后一条 pending 无下一题
        if pending_missing is not None:
            missing.append(pending_missing)
            pending_missing = None

        if cur_writer:
            cur_writer[0].close()

        _update_breakpoint(breakpoint_path, last_written)

        # 诊断：题干为空跳过的行（辅助定位"缺失字段题目被跳过"问题）
        if skipped_log:
            print("[import_preprocessor] 跳过诊断(题干为空): %d 条, 示例: %s" % (
                len(skipped_log), " | ".join(skipped_log[:5])), flush=True)

        return json.dumps({
            "success": True,
            "chunks": chunks,
            "total_rows": processed,
            "processed_rows": processed,
            "written_rows": written,
            "missing": missing,
            "skipped_count": skipped_total,
            "duplicate_count": duplicate_total,
            "empty_question_count": empty_question_total,
            "duplicates": [d for d in dup_map.values() if len(d["rows"]) > 1][:50],
            "stem_variants": [{"question": v["question"], "rows": v["rows"]}
                              for v in stem_map.values() if len(v["rows"]) > 1][:50],
        }, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"success": False, "error": str(e)}, ensure_ascii=False)
    finally:
        if conn:
            try:
                conn.close()
            except Exception:
                pass


def apply_fills(chunk_path, fills_json, fill_fields_json=None):
    """把 AI 填充结果按行索引回写到 CSV 分片。

    fill_fields_json：Java 动态下发的可填充字段列表 JSON（如 ["category","difficulty"]），
    为空时用内置默认值兜底。
    """
    try:
        fill_fields = list(FILL_FIELDS)
        if fill_fields_json:
            try:
                ff = json.loads(fill_fields_json)
                if isinstance(ff, list) and ff:
                    fill_fields = [str(f) for f in ff]
            except Exception:
                pass
        fills = json.loads(fills_json) if fills_json else {}
        with open(chunk_path, "r", encoding="utf-8", newline="") as f:
            reader = csv.reader(f)
            all_rows = list(reader)
        if not all_rows:
            return json.dumps({"updated": 0}, ensure_ascii=False)
        header = all_rows[0]
        col_idx = {c: i for i, c in enumerate(header)}
        updated = 0
        for row_key, fill in fills.items():
            try:
                r = int(row_key)
            except (ValueError, TypeError):
                continue
            line = r + 1  # 表头偏移
            if line < 0 or line >= len(all_rows) or not isinstance(fill, dict):
                continue
            row = all_rows[line]
            for field in fill_fields:
                if field in fill and field in col_idx:
                    # 不覆盖源文件已有内容：仅当单元格为空时才写入填充值
                    old = row[col_idx[field]].strip()
                    if old:
                        continue
                    v = fill[field]
                    if isinstance(v, bool):
                        continue
                    row[col_idx[field]] = str(v)
            updated += 1
        # 原子写回：先写临时文件再改名，避免中途崩溃损坏分片 CSV
        tmp_path = chunk_path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8", newline="") as f:
            csv.writer(f).writerows(all_rows)
        os.replace(tmp_path, chunk_path)
        return json.dumps({"updated": updated}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({"error": str(e)}, ensure_ascii=False)
