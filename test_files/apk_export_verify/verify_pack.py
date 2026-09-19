#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
机制验证：复刻 ApkPacker.buildApkFromDir 的完整链路（设备端之外的同构验证）
  1. 解密 base.apk 现有 dt.jet，确认格式（ZIP + index.html）
  2. 用多文件 HTML 目录（index.html + css + js）打包 ZIP
  3. AES-128-CBC 加密 → dt.jet
  4. 注入 base.apk 副本（剔除旧签名，替换 assets/dt.jet，resources.arsc 保持 STORED）
  5. 输出未签名 APK，交由 apksigner 签名
"""
import io
import os
import shutil
import sys
import zipfile
import zlib

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives import padding

BASE_APK = r"D:\qzq\smartquiz\src\main\assets\apk_shell\base.apk"
KEY = b"MyHtmlEditorKey1"  # 与 ApkPacker.AES_KEY_STR 一致 (16字节)
OUT_APK = r"C:\Users\xiaocong\AppData\Local\Temp\apk_verify\unsigned.apk"

def aes_encrypt(plain: bytes) -> bytes:
    iv = os.urandom(16)
    padder = padding.PKCS7(128).padder()
    data = padder.update(plain) + padder.finalize()
    cipher = Cipher(algorithms.AES(KEY), modes.CBC(iv))
    enc = cipher.encryptor()
    return iv + enc.update(data) + enc.finalize()

def aes_decrypt(jet: bytes) -> bytes:
    iv, ct = jet[:16], jet[16:]
    cipher = Cipher(algorithms.AES(KEY), modes.CBC(iv))
    dec = cipher.decryptor()
    plain = dec.update(ct) + dec.finalize()
    unp = padding.PKCS7(128).unpadder()
    return unp.update(plain) + unp.finalize()

def zip_dir_to_bytes(html_dir: str) -> bytes:
    """递归打包目录（保留相对路径；与 ApkPacker.addDirToZip 行为一致）"""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, _, files in os.walk(html_dir):
            for fn in files:
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, html_dir).replace(os.sep, "/")
                zf.write(full, rel)
    return buf.getvalue()

def rebuild_apk(template: bytes, new_jet: bytes, manifest: bytes) -> bytes:
    """读模板 APK → 剔除签名 → 替换 dt.jet/manifest.json → 重写（resources.arsc STORED）"""
    entries = []
    with zipfile.ZipFile(io.BytesIO(template)) as zin:
        for info in zin.infolist():
            name = info.filename
            if name.startswith("META-INF/") and (
                name.endswith(".RSA") or name.endswith(".SF")
                or name.endswith(".MF") or "KEY0" in name
            ):
                continue
            data = zin.read(name) if not info.is_dir() else None
            entries.append((name, data))

    replaced = []
    for name, data in entries:
        if name == "assets/dt.jet":
            data = new_jet
        elif name == "assets/manifest.json":
            data = manifest
        replaced.append((name, data))

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zout:
        for name, data in replaced:
            if name == "resources.arsc" and data is not None:
                # 与 ApkPacker 一致：resources.arsc 保持 STORED + CRC（Android 11+ 签名要求）
                info = zipfile.ZipInfo(name)
                info.compress_type = zipfile.ZIP_STORED
                info.CRC = zlib.crc32(data) & 0xFFFFFFFF
                info.file_size = len(data)
                zout.writestr(info, data)
            elif data is None:
                zout.writestr(name, "")
            else:
                zout.writestr(name, data)
    return buf.getvalue()

def main():
    out_dir = os.path.dirname(OUT_APK)
    os.makedirs(out_dir, exist_ok=True)

    template = open(BASE_APK, "rb").read()

    # ---- 1. 验证现有 dt.jet 格式 ----
    with zipfile.ZipFile(io.BytesIO(template)) as z:
        jet = z.read("assets/dt.jet")
        manifest = z.read("assets/manifest.json")
    print("existing dt.jet size:", len(jet))
    print("existing manifest.json:", manifest.decode("utf-8"))
    plain = aes_decrypt(jet)
    assert plain[:2] == b"PK", "dt.jet 解密后不是 ZIP"
    with zipfile.ZipFile(io.BytesIO(plain)) as z:
        print("existing dt.jet entries:", z.namelist())

    # ---- 2. 多文件 HTML 目录 ----
    html_dir = os.path.join(out_dir, "web")
    os.makedirs(os.path.join(html_dir, "css"), exist_ok=True)
    os.makedirs(os.path.join(html_dir, "js"), exist_ok=True)
    with open(os.path.join(html_dir, "index.html"), "w", encoding="utf-8") as f:
        f.write('<!DOCTYPE html><html><head><meta charset="utf-8">'
                '<link rel="stylesheet" href="css/style.css">'
                '<title>验证</title></head><body>'
                '<h1>多文件 HTML 打包验证</h1>'
                '<script src="js/app.js"></script></body></html>')
    with open(os.path.join(html_dir, "css", "style.css"), "w", encoding="utf-8") as f:
        f.write("body{background:#f0f4ff;font-family:sans-serif}h1{color:#4338ca}")
    with open(os.path.join(html_dir, "js", "app.js"), "w", encoding="utf-8") as f:
        f.write('document.title="已加载 app.js 验证";')

    zip_bytes = zip_dir_to_bytes(html_dir)
    print("new web zip entries:", [i.filename for i in zipfile.ZipFile(io.BytesIO(zip_bytes)).infolist()])

    # ---- 3/4. 加密 + 注入 ----
    new_jet = aes_encrypt(zip_bytes)
    unsigned = rebuild_apk(template, new_jet, manifest)
    with open(OUT_APK, "wb") as f:
        f.write(unsigned)
    print("unsigned APK written:", OUT_APK, "size:", len(unsigned))

    # ---- 反向校验：从结果 APK 解出 dt.jet 并解密 ----
    with zipfile.ZipFile(io.BytesIO(unsigned)) as z:
        back_jet = z.read("assets/dt.jet")
        names = z.namelist()
    assert "assets/dt.jet" in names and "assets/manifest.json" in names
    assert not any(n.startswith("META-INF/") for n in names if n.endswith((".RSA", ".SF", ".MF")))
    back_plain = aes_decrypt(back_jet)
    assert back_plain == zip_bytes, "dt.jet 回读不一致"
    print("VERIFY OK: 注入后的 dt.jet 可解密且内容与多文件 HTML ZIP 完全一致")

if __name__ == "__main__":
    main()
