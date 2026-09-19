#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
机制验证 v2：复刻 ApkPacker 新底座（新壳 base.apk）的完整链路
  1. 解密 base.apk 现有 dt.jet，确认格式
  2. 多文件 HTML 目录打包 → AES 加密 → 注入（本地模式，manifest 仅 main）
  3. URL 模式：manifest.json 带 url → 注入（远程加载模式）
  4. 反向回读校验 dt.jet / manifest.json 完全一致
输出 unsigned APK，交由 apksigner 签名后可安装验证。
"""
import io
import os
import zipfile
import zlib

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives import padding

BASE_APK = r"D:\qzq\smartquiz\src\main\assets\apk_shell\base.apk"
KEY = b"MyHtmlEditorKey1"
OUT_DIR = r"C:\Users\xiaocong\AppData\Local\Temp\apk_verify_v2"
OUT_LOCAL = os.path.join(OUT_DIR, "unsigned_local.apk")
OUT_URL = os.path.join(OUT_DIR, "unsigned_url.apk")


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
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, _, files in os.walk(html_dir):
            for fn in files:
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, html_dir).replace(os.sep, "/")
                zf.write(full, rel)
    return buf.getvalue()


def rebuild_apk(template: bytes, new_jet: bytes, manifest: bytes) -> bytes:
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


def build_html_dir(out_dir: str) -> str:
    html_dir = os.path.join(out_dir, "web")
    os.makedirs(os.path.join(html_dir, "css"), exist_ok=True)
    os.makedirs(os.path.join(html_dir, "js"), exist_ok=True)
    with open(os.path.join(html_dir, "index.html"), "w", encoding="utf-8") as f:
        f.write('<!DOCTYPE html><html><head><meta charset="utf-8">'
                '<link rel="stylesheet" href="css/style.css">'
                '<title>新壳验证</title></head><body>'
                '<h1>新壳多文件打包验证</h1>'
                '<script src="js/app.js"></script></body></html>')
    with open(os.path.join(html_dir, "css", "style.css"), "w", encoding="utf-8") as f:
        f.write("body{background:#f0f4ff;font-family:sans-serif}h1{color:#4338ca}")
    with open(os.path.join(html_dir, "js", "app.js"), "w", encoding="utf-8") as f:
        f.write('document.title="app.js loaded";')
    return html_dir


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    template = open(BASE_APK, "rb").read()
    with zipfile.ZipFile(io.BytesIO(template)) as z:
        jet0 = z.read("assets/dt.jet")
        manifest0 = z.read("assets/manifest.json")
    print("template dt.jet size:", len(jet0))
    print("template manifest.json:", manifest0.decode("utf-8"))
    plain0 = aes_decrypt(jet0)
    with zipfile.ZipFile(io.BytesIO(plain0)) as z:
        print("template dt.jet entries:", z.namelist())

    # ---- 本地模式：多文件 HTML ----
    html_dir = build_html_dir(OUT_DIR)
    zip_bytes = zip_dir_to_bytes(html_dir)
    print("\n[local] zip entries:", [i.filename for i in zipfile.ZipFile(io.BytesIO(zip_bytes)).infolist()])
    new_jet = aes_encrypt(zip_bytes)
    unsigned = rebuild_apk(template, new_jet, manifest0)
    open(OUT_LOCAL, "wb").write(unsigned)
    # 回读校验
    with zipfile.ZipFile(io.BytesIO(unsigned)) as z:
        back_jet = z.read("assets/dt.jet")
        back_manifest = z.read("assets/manifest.json")
    assert aes_decrypt(back_jet) == zip_bytes
    assert back_manifest == manifest0
    print("[local] VERIFY OK: dt.jet 与 manifest.json 回读一致 ->", OUT_LOCAL)

    # ---- URL 模式：manifest 带 url ----
    url_manifest = b'{"main":"index.html","targver":1,"url":"https://example.com/app"}'
    url_zip = zip_dir_to_bytes(html_dir)  # 占位内容（壳在 url 模式下不读取）
    url_unsigned = rebuild_apk(template, aes_encrypt(url_zip), url_manifest)
    open(OUT_URL, "wb").write(url_unsigned)
    with zipfile.ZipFile(io.BytesIO(url_unsigned)) as z:
        back_manifest = z.read("assets/manifest.json")
    assert back_manifest == url_manifest
    print("[url] VERIFY OK: manifest.json 含 url 字段回读一致 ->", OUT_URL)


if __name__ == "__main__":
    main()
