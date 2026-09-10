#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
知识库预处理脚本：Markdown/TXT → knowledge_import.json（答题宝 App 知识库导入格式）。

用途：把用户自己的学习资料/笔记/文档批量切块，生成 App 可导入的 JSON。
配合 App 内 knowledge_base 工具的 import_json / import_file 动作导入知识库。

输出 JSON 结构：
{
  "version": 1,
  "generated_at": "2026-09-10T12:00:00",
  "chunks": [
    {"title": "标题路径", "category": "general", "keywords": "关键词",
     "content": "正文", "source": "相对路径/文件名.md"}
  ]
}

用法示例：
  # 处理单个文件
  python knowledge_preprocess.py --input notes/化学.md --output kb.json --category 化学
  # 递归处理整个目录（.md/.markdown/.txt）
  python knowledge_preprocess.py --input docs/ --output kb.json --category guide
  # 多个输入源合并
  python knowledge_preprocess.py --input docs/a.md --input notes/ --output kb.json

说明：
  - 按 Markdown 标题层级（#~######）切块，标题路径用 " - " 连接作为块标题；
  - 块超过 --chunk-size 字符时按空行自动再拆分（默认 800，检索友好）；
  - keywords 自动从标题提取连续中文词与英文词（可后续在 JSON 中手动增强）；
  - 仅依赖 Python 标准库，无第三方依赖。
"""

import argparse
import json
import os
import re
import sys
from datetime import datetime

# 支持的文档扩展名
SUPPORTED_EXTS = {".md", ".markdown", ".txt"}
# Markdown 标题：行首 1~6 个 # + 空格
HEADING_RE = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
# 中英文关键词提取
CJK_WORD_RE = re.compile(r"[\u4e00-\u9fff]{2,}")
EN_WORD_RE = re.compile(r"[a-zA-Z][a-zA-Z0-9]{1,}")


def read_text(path):
    """按 UTF-8 读取文本，兼容带 BOM 与常见编码（UTF-8 失败时尝试 GBK/UTF-16）。"""
    raw = open(path, "rb").read()
    for enc in ("utf-8-sig", "utf-8", "gbk", "utf-16"):
        try:
            return raw.decode(enc)
        except (UnicodeDecodeError, ValueError):
            continue
    raise ValueError("无法识别文件编码: %s" % path)


def extract_keywords(title, text):
    """从标题/正文提取关键词：标题中的连续中文词 + 英文词，正文高频词不再统计（保持轻量）。"""
    words = []
    for m in CJK_WORD_RE.finditer(title or ""):
        words.append(m.group(0))
    for m in EN_WORD_RE.finditer(title or ""):
        words.append(m.group(0).lower())
    # 去重保序
    seen = set()
    result = []
    for w in words:
        if w not in seen:
            seen.add(w)
            result.append(w)
    return ",".join(result)


def split_long_text(text, chunk_size):
    """超长块按空行拆分，返回片段列表（每段不超过 chunk_size 字符）。"""
    text = text.strip()
    if len(text) <= chunk_size:
        return [text] if text else []
    segments = []
    for para in re.split(r"\n\s*\n", text):
        para = para.strip()
        if not para:
            continue
        # 段落仍超长：按句号/换行硬切
        while len(para) > chunk_size:
            cut = para.rfind("。", 0, chunk_size)
            if cut < chunk_size // 3:
                cut = para.rfind("\n", 0, chunk_size)
            if cut < chunk_size // 3:
                cut = chunk_size
            segments.append(para[:cut].strip())
            para = para[cut:].strip()
        if para:
            segments.append(para)
    return segments


def parse_document(text, source, category, chunk_size):
    """
    解析单篇文档为知识块列表。
    策略：按标题层级维护标题栈，标题路径作为块标题；正文累积到当前块，
    标题出现时 flush 上一块；块超长时按空行拆分。
    """
    chunks = []
    stack = []          # [(level, heading_text), ...]
    buffer_lines = []   # 当前块正文行

    def flush():
        nonlocal buffer_lines
        if not stack and not buffer_lines:
            return
        title = " - ".join(h for _, h in stack) if stack else "(未分组)"
        body = "\n".join(buffer_lines).strip()
        buffer_lines = []
        if not body:
            return
        segments = split_long_text(body, chunk_size)
        for seg in segments:
            chunks.append({
                "title": title,
                "category": category,
                "keywords": extract_keywords(title, seg),
                "content": seg,
                "source": source,
            })

    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            buffer_lines.append("")
            continue
        m = HEADING_RE.match(line)
        if m:
            level = len(m.group(1))
            heading = m.group(2).strip()
            if not heading:
                continue
            flush()
            # 弹出同级或更深的标题
            while stack and stack[-1][0] >= level:
                stack.pop()
            stack.append((level, heading))
        else:
            buffer_lines.append(raw_line.strip())

    flush()
    return chunks


def collect_files(paths):
    """收集输入路径中的文档文件列表。"""
    files = []
    for p in paths:
        if os.path.isfile(p):
            ext = os.path.splitext(p)[1].lower()
            if ext in SUPPORTED_EXTS:
                files.append(p)
            else:
                print("[跳过] 不支持的文件类型: %s" % p, file=sys.stderr)
        elif os.path.isdir(p):
            for root, _, names in os.walk(p):
                for name in sorted(names):
                    if os.path.splitext(name)[1].lower() in SUPPORTED_EXTS:
                        files.append(os.path.join(root, name))
        else:
            print("[警告] 路径不存在: %s" % p, file=sys.stderr)
    # 去重保序
    seen = set()
    result = []
    for f in files:
        absf = os.path.abspath(f)
        if absf not in seen:
            seen.add(absf)
            result.append(absf)
    return result


def main():
    parser = argparse.ArgumentParser(description="知识库预处理：Markdown/TXT → knowledge_import.json")
    parser.add_argument("--input", "-i", action="append", required=True,
                        help="输入文件或目录（可多次指定，目录递归扫描 .md/.markdown/.txt）")
    parser.add_argument("--output", "-o", default="knowledge_import.json",
                        help="输出 JSON 路径（默认 knowledge_import.json）")
    parser.add_argument("--category", "-c", default="general",
                        help="知识分类（默认 general），如 化学/数学/guide/faq")
    parser.add_argument("--chunk-size", type=int, default=800,
                        help="单块最大字符数（默认 800），超长按空行拆分")
    args = parser.parse_args()

    if args.chunk_size < 100:
        print("[错误] --chunk-size 过小（至少 100）", file=sys.stderr)
        sys.exit(1)

    files = collect_files(args.input)
    if not files:
        print("[错误] 未找到可处理的文档（支持 .md/.markdown/.txt）", file=sys.stderr)
        sys.exit(1)

    all_chunks = []
    failed = 0
    for f in files:
        # source 用相对当前工作目录的路径，便于溯源
        try:
            rel = os.path.relpath(f)
        except ValueError:
            rel = f
        try:
            text = read_text(f)
            chunks = parse_document(text, rel, args.category, args.chunk_size)
            all_chunks.extend(chunks)
            print("[OK] %s -> %d 块" % (rel, len(chunks)))
        except Exception as e:
            failed += 1
            print("[失败] %s: %s" % (rel, e), file=sys.stderr)

    if failed:
        print("[警告] %d 个文件处理失败，已跳过" % failed, file=sys.stderr)

    output = {
        "version": 1,
        "generated_at": datetime.now().strftime("%Y-%m-%dT%H:%M:%S"),
        "chunks": all_chunks,
    }

    out_path = os.path.abspath(args.output)
    with open(out_path, "w", encoding="utf-8") as fp:
        json.dump(output, fp, ensure_ascii=False, indent=2)

    print("")
    print("完成：共 %d 块，写入 %s" % (len(all_chunks), out_path))
    print("导入方式：在答题宝中让 Agent 调用 knowledge_base 工具的 import_file 动作，")
    print("file_path 填 %s；或将文件放到 App 可访问目录后调用 import_file。" % out_path)


if __name__ == "__main__":
    main()
