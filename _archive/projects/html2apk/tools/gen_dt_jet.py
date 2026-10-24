#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成壳模板占位 assets/dt.jet（与 ApkPacker 相同格式）：
    index.html → ZIP → AES-128-CBC 加密（key="MyHtmlEditorKey1"，IV 随机16字节放文件头）
用法: python tools/gen_dt_jet.py [html_dir] [out_dt_jet]
"""
import io
import os
import sys
import zipfile

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives import padding

KEY = b"MyHtmlEditorKey1"

DEFAULT_HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>背题</title>
<style>
  body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
       font-family:sans-serif;background:#f5f7ff;color:#333}
  .box{text-align:center;padding:40px}
  h1{font-size:22px;color:#4338ca}
  p{color:#888;font-size:14px}
</style>
</head>
<body>
  <div class="box">
    <h1>SmartQuiz 导出应用</h1>
    <p>内容打包成功，正在加载…</p>
  </div>
</body>
</html>
"""


def aes_encrypt(plain: bytes) -> bytes:
    iv = os.urandom(16)
    padder = padding.PKCS7(128).padder()
    data = padder.update(plain) + padder.finalize()
    cipher = Cipher(algorithms.AES(KEY), modes.CBC(iv))
    enc = cipher.encryptor()
    return iv + enc.update(data) + enc.finalize()


def zip_dir(html_dir: str) -> bytes:
    # 排除开发/测试文件：备份、截图、缓存、版本库、编辑器临时文件（不进客户 APK）
    EXCLUDE_DIRS = {"_backup", "_shots", "__pycache__", ".git", ".svn", "node_modules"}
    EXCLUDE_EXTS = {".tmp", ".bak", ".log"}
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, files in os.walk(html_dir):
            dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]
            for fn in files:
                if os.path.splitext(fn)[1].lower() in EXCLUDE_EXTS:
                    continue
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, html_dir).replace(os.sep, "/")
                zf.write(full, rel)
    return buf.getvalue()


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    html_dir = sys.argv[1] if len(sys.argv) > 1 else None
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(here, "..", "src", "main", "assets", "dt.jet")

    if html_dir and os.path.isdir(html_dir):
        zip_bytes = zip_dir(html_dir)
        print("zip entries:", [i.filename for i in zipfile.ZipFile(io.BytesIO(zip_bytes)).infolist()])
    else:
        # 默认占位：单个 index.html
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
            zf.writestr("index.html", DEFAULT_HTML)
        zip_bytes = buf.getvalue()
        print("placeholder zip entries: ['index.html']")

    jet = aes_encrypt(zip_bytes)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "wb") as f:
        f.write(jet)
    print("dt.jet written:", os.path.abspath(out), "size:", len(jet))


if __name__ == "__main__":
    main()
