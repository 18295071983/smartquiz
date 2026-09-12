#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成并嵌入壳模板元数据 assets/apk_shell_meta.json：
  {
    "icon_entry": "res/RJ.png",                        # 启动图标在 APK zip 中的条目（aapt badging 提取）
    "label_placeholder": "SmartQuizExportAppName",     # resources.arsc 中可原位补丁的应用名占位
    "max_label_units": 22,                             # 占位 UTF-16 单元数（自定义应用名上限）
    "package_placeholder": "com.cjhtmldemo.xxxxxxxxxxx" # AndroidManifest.xml(AXML) 中可等长替换的包名占位
  }
同时校验占位串在 resources.arsc 中唯一且可寻址（补丁前提），校验包名占位存在于 AXML 字符串池。

用法: python tools/gen_meta.py <unsigned.apk> [--placeholder <串>] [--max-units N] [--package <串>]
"""
import json
import os
import re
import subprocess
import sys
import zipfile

AAPT = r"D:\Android\Sdk\build-tools\34.0.0\aapt.exe"
DEFAULT_PLACEHOLDER = "SmartQuizExportAppName"
DEFAULT_MAX_UNITS = 22
DEFAULT_PACKAGE = None  # 未显式指定时从 build.gradle 的 applicationId 自动读取


def gradle_application_id():
    """从 build.gradle 读取 applicationId（与模板真实包名占位保持单点一致）。"""
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    with open(os.path.join(root, "build.gradle"), encoding="utf-8") as f:
        txt = f.read()
    m = re.search(r'applicationId\s+"([a-zA-Z0-9_.]+)"', txt)
    if not m:
        raise RuntimeError("build.gradle 中未找到 applicationId，请检查或使用 --package 显式指定")
    return m.group(1)


def gradle_versions():
    """从 build.gradle 读取 versionName/versionCode（包内自证用）。"""
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    with open(os.path.join(root, "build.gradle"), encoding="utf-8") as f:
        txt = f.read()
    vn = re.search(r'versionName\s+"([^"]+)"', txt)
    vc = re.search(r'versionCode\s+(\d+)', txt)
    return (vn.group(1) if vn else "0"), (int(vc.group(1)) if vc else 0)


def shell_constants():
    """从 MainActivity.java 提取 SHELL_VERSION / BRIDGE_API（单一权威来源，进 dex 的常量）。"""
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    java = os.path.join(root, "src", "main", "java", "com", "cjhtmldemo", "apk", "MainActivity.java")
    with open(java, encoding="utf-8") as f:
        txt = f.read()
    sv = re.search(r'SHELL_VERSION\s*=\s*"([^"]+)"', txt)
    ba = re.search(r'BRIDGE_API\s*=\s*(\d+)', txt)
    return (sv.group(1) if sv else "unknown"), (int(ba.group(1)) if ba else 0)


def badging(apk):
    out = subprocess.run([AAPT, "dump", "badging", apk], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    icon = None
    for line in out.splitlines():
        if line.startswith("application-icon-480:"):
            icon = line.split(":", 1)[1].strip().strip("'")
            break
        if line.startswith("application-icon-") and icon is None:
            icon = line.split(":", 1)[1].strip().strip("'")
    return icon


def find_placeholder(apk, placeholder):
    """返回占位串 UTF-8 字节在 resources.arsc 中的位置；要求唯一。"""
    with zipfile.ZipFile(apk) as z:
        arsc = z.read("resources.arsc")
    pat = placeholder.encode("utf-8")
    hits = [i for i in range(len(arsc)) if arsc.startswith(pat, i)]
    if len(hits) != 1:
        raise RuntimeError("占位串命中 %d 处（期望 1）：%s" % (len(hits), placeholder))
    # 校验长度头：前两字节 = [utf16_len][utf8_len]
    u16 = arsc[hits[0] - 2]
    u8 = arsc[hits[0] - 1]
    print("placeholder @ %d, len header [utf16=%d][utf8=%d]" % (hits[0], u16, u8))
    if u8 != len(pat) or u16 > len(placeholder):
        raise RuntimeError("长度头异常，无法原位补丁")
    return hits[0]


def find_package_placeholder(apk, placeholder):
    """校验包名占位出现在 AndroidManifest.xml(AXML) 中。
    AXML 字符串池可为 UTF-8 或 UTF-16 编码，双编码搜索任一命中即通过；
    ApkPacker 的 patchPackage 会对两种编码都做等长替换。"""
    with zipfile.ZipFile(apk) as z:
        axml = z.read("AndroidManifest.xml")
    pat8 = placeholder.encode("utf-8")
    pat16 = placeholder.encode("utf-16-le")
    hits8 = [i for i in range(len(axml)) if axml.startswith(pat8, i)]
    hits16 = [i for i in range(len(axml)) if axml.startswith(pat16, i)]
    if not hits8 and not hits16:
        raise RuntimeError("包名占位未出现在 AndroidManifest.xml（UTF-8/UTF-16 均未命中）: %s" % placeholder)
    print("package placeholder hit(s): utf8=%d utf16=%d" % (len(hits8), len(hits16)))
    return hits8 or hits16


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else None
    if not apk or not os.path.isfile(apk):
        print("用法: python gen_meta.py <unsigned.apk> [--placeholder 串] [--max-units N] [--package 串]")
        sys.exit(1)
    placeholder = DEFAULT_PLACEHOLDER
    max_units = DEFAULT_MAX_UNITS
    package_ph = DEFAULT_PACKAGE
    if "--placeholder" in sys.argv:
        placeholder = sys.argv[sys.argv.index("--placeholder") + 1]
    if "--max-units" in sys.argv:
        max_units = int(sys.argv[sys.argv.index("--max-units") + 1])
    if "--package" in sys.argv:
        package_ph = sys.argv[sys.argv.index("--package") + 1]
    if package_ph is None:
        package_ph = gradle_application_id()
        print("package placeholder from build.gradle: %s" % package_ph)

    icon = badging(apk)
    if not icon:
        raise RuntimeError("未从 badging 解析到启动图标")
    find_placeholder(apk, placeholder)
    find_package_placeholder(apk, package_ph)

    shell_version, bridge_api = shell_constants()
    version_name, version_code = gradle_versions()
    meta = {
        "shell_version": shell_version,          # 壳版本（= MainActivity.SHELL_VERSION，进 dex）
        "bridge_api": bridge_api,                # 桥 API 版本（= MainActivity.BRIDGE_API，进 dex）
        "version_name": version_name,            # 构建 versionName（build.gradle）
        "version_code": version_code,            # 构建 versionCode（build.gradle）
        "icon_entry": icon,
        "label_placeholder": placeholder,
        "max_label_units": max_units,
        "package_placeholder": package_ph,
        "note": "由 tools/gen_meta.py 生成；重建壳模板后需重新运行",
    }
    # 更新 APK 内 assets/apk_shell_meta.json（无则新增）
    meta_bytes = json.dumps(meta, ensure_ascii=False, indent=1).encode("utf-8")
    tmp = apk + ".tmp"
    had_meta = False
    with zipfile.ZipFile(apk) as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            data = zin.read(info.filename) if not info.is_dir() else None
            if info.filename == "assets/apk_shell_meta.json":
                data = meta_bytes
                had_meta = True
            if data is None:
                zout.writestr(info, "")
            else:
                zout.writestr(info, data)
        if not had_meta:
            zout.writestr("assets/apk_shell_meta.json", meta_bytes)
    os.replace(tmp, apk)
    print("meta written:", json.dumps(meta, ensure_ascii=False))
    print("apk size:", os.path.getsize(apk))


if __name__ == "__main__":
    main()
