#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
端到端验证：应用名补丁 + 图标替换（复刻 ApkPacker 的新 AppMeta 能力）
  1. 读取模板 assets/apk_shell_meta.json（icon_entry + label_placeholder + max_label_units）
  2. resources.arsc 原位补丁：占位 → 自定义应用名（UTF-8 池：[utf16_len][utf8_len][data]，≤占位长度）
  3. 替换 icon_entry 为自定义 PNG
  4. 重打包（resources.arsc 保持 STORED + 重算 CRC）→ apksigner 签名 → aapt 校验
用法: python patch_verify.py <template.apk> <new_label> [icon.png]
"""
import json
import io
import os
import struct
import subprocess
import sys
import zipfile
import zlib

AAPT = r"D:\Android\Sdk\build-tools\34.0.0\aapt.exe"
APKSIGNER = r"D:\Android\Sdk\build-tools\34.0.0\apksigner.bat"
KEYSTORE = r"D:\qzq\smartquiz\src\main\assets\apk_shell\export.keystore"
OUT = r"C:\Users\xiaocong\AppData\Local\Temp\apk_verify_v2\patched_verify.apk"


def make_test_png(path, size=192, rgb=(67, 56, 202)):
    """无依赖生成纯色 PNG（IHDR+IDAT+IEND），用于图标替换验证"""
    import zlib as zl
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zl.crc32(c) & 0xFFFFFFFF)
    raw = b""
    row = b"\x00" + bytes(rgb) * size
    for _ in range(size):
        raw += row
    ihdr = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zl.compress(raw)) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)
    return path


def patch_label(arsc: bytes, placeholder: str, new_label: str) -> bytes:
    """UTF-8 字符串池原位补丁：返回新 arsc。占位唯一（gen_meta 已校验）。"""
    pat = placeholder.encode("utf-8")
    hits = [i for i in range(len(arsc)) if arsc.startswith(pat, i)]
    if len(hits) != 1:
        raise RuntimeError("占位命中 %d 处" % len(hits))
    i = hits[0]
    old_u16 = arsc[i - 2]
    new_bytes = new_label.encode("utf-8")
    u16 = len(new_label.encode("utf-16-le")) // 2
    if u16 > old_u16:
        raise RuntimeError("应用名 %d 单元超过占位容量 %d" % (u16, old_u16))
    if len(new_bytes) > len(pat):
        raise RuntimeError("应用名 UTF-8 超占位长度")
    # [utf16_len][utf8_len][data...]（长度 <0x80 用单字节）
    if u16 >= 0x80 or len(new_bytes) >= 0x80:
        raise RuntimeError("长度超单字节编码范围")
    data = bytearray(arsc)
    data[i - 2] = u16
    data[i - 1] = len(new_bytes)
    data[i:i + len(pat)] = new_bytes + b"\x00" * (len(pat) - len(new_bytes))
    return bytes(data)


def rebuild(apk_in: str, arsc: bytes, icon_bytes: bytes | None, icon_entry: str) -> bytes:
    entries = []
    with zipfile.ZipFile(apk_in) as zin:
        for info in zin.infolist():
            name = info.filename
            if name.startswith("META-INF/") and (
                name.endswith(".RSA") or name.endswith(".SF")
                or name.endswith(".MF") or "KEY0" in name
            ):
                continue
            data = zin.read(name) if not info.is_dir() else None
            entries.append((name, data))
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zout:
        for name, data in entries:
            if name == "resources.arsc":
                data = arsc
            elif icon_bytes is not None and name == icon_entry:
                data = icon_bytes
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


def main():
    apk = sys.argv[1]
    label = sys.argv[2]
    icon_png = sys.argv[3] if len(sys.argv) > 3 else make_test_png(
        os.path.join(os.path.dirname(OUT), "test_icon.png"))
    with zipfile.ZipFile(apk) as z:
        meta = json.loads(z.read("assets/apk_shell_meta.json"))
        arsc = z.read("resources.arsc")
    print("meta:", meta)
    new_arsc = patch_label(arsc, meta["label_placeholder"], label)
    icon_bytes = open(icon_png, "rb").read()
    unsigned = rebuild(apk, new_arsc, icon_bytes, meta["icon_entry"])
    with open(OUT.replace("patched_verify.apk", "patched_unsigned.apk"), "wb") as f:
        f.write(unsigned)
    subprocess.run([APKSIGNER, "sign", "--ks", KEYSTORE, "--ks-pass", "pass:password",
                    "--ks-key-alias", "smartquiz", "--out", OUT,
                    OUT.replace("patched_verify.apk", "patched_unsigned.apk")],
                   check=True, capture_output=True)
    subprocess.run([APKSIGNER, "verify", OUT], check=True, capture_output=True)
    badging = subprocess.run([AAPT, "dump", "badging", OUT], capture_output=True,
                             text=True, encoding="utf-8", errors="replace").stdout
    for line in badging.splitlines():
        if line.startswith(("package:", "sdkVersion", "targetSdkVersion",
                            "application-label:", "application-icon-480:")):
            print(line)


if __name__ == "__main__":
    main()
