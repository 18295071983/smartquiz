#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 Termux 的常用命令行工具打进 App（jniLibs）——可重复运行的通用打包脚本。

为什么这么做（都是实测结论，别改坏）：
1) targetSdk>=29 的 App **不能 execve App 数据目录里的文件**（Permission denied, exit 126），
   只有随 APK 打包、被系统解压到 nativeLibraryDir 的文件才能执行。
2) 但 **dlopen 数据目录里的 .so 是允许的**（已用 System.load 实测）。
   → 所以：**可执行文件**必须以 lib*.so 形式放 jniLibs；**依赖库**可以打成 tar.gz，
     运行时解包到 files/ 下用 LD_LIBRARY_PATH 加载，不必把 libcrypto.so.3 这类带版本号的
     SONAME 逐个改名（改名还要同步改 verneed，很容易漏）。
3) Termux 的包是 bionic 构建，在 App 域不会像 musl 静态二进制那样被 seccomp 以 SIGSYS 杀掉。

用法：
    python tools/tests/bundle_termux_bins.py            # 打包下方 TOOLS 列表
    python tools/tests/bundle_termux_bins.py --with-ffmpeg   # 额外打包 ffmpeg（约 +90MB 依赖）

产出（都在 src/main/jniLibs/arm64-v8a/）：
    libtoolkit_libs.so      依赖库 tar.gz（内部保留真实文件名，如 libcrypto.so.3）
    libtoolkit_manifest.so  JSON 清单（工具名 -> 对应的 lib*.so），运行时按它建软链接
    lib<name>_bin.so        各可执行文件（RUNPATH 已补成 $ORIGIN）
"""

import io
import json
import os
import re
import subprocess
import sys
import tarfile
import urllib.request

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JNI = os.path.join(REPO, "src", "main", "jniLibs", "arm64-v8a")
CACHE = os.path.join(REPO, ".workbuddy", "tmp", "termux_bins")
BASE = "https://packages.termux.dev/apt/termux-main/"
NDK_RE = os.path.join(
    os.environ.get("ANDROID_HOME", r"D:\Android\Sdk"),
    "ndk",
)
SYSTEM_LIBS = {
    "libc.so", "libdl.so", "libm.so", "liblog.so", "libandroid.so",
    "libnativehelper.so", "ld-android.so", "libstdc++.so",
}

# (工具名, 包名, 包内路径)   —— 加工具只改这里
TOOLS = [
    ("jq", "jq", "bin/jq"),
    ("tree", "tree", "bin/tree"),
    ("zip", "zip", "bin/zip"),
    ("unzip", "unzip", "bin/unzip"),
    ("file", "file", "bin/file"),
    ("zstd", "zstd", "bin/zstd"),
    ("ncdu", "ncdu", "bin/ncdu"),
    ("htop", "htop", "bin/htop"),
    ("ps", "procps", "bin/ps"),
    ("free", "procps", "bin/free"),
    ("tmux", "tmux", "bin/tmux"),
    ("nano", "nano", "bin/nano"),
    ("sqlite3", "sqlite", "bin/sqlite3"),
    ("rg", "ripgrep", "bin/rg"),
    ("curl", "curl", "bin/curl"),
    ("aria2c", "aria2", "bin/aria2c"),
    ("gawk", "gawk", "bin/gawk"),
]
FFMPEG_TOOLS = [
    ("ffmpeg", "ffmpeg", "bin/ffmpeg"),
    ("ffprobe", "ffmpeg", "bin/ffprobe"),
]


def log(msg):
    print(msg, flush=True)


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "pip/26.2.1"})
    return urllib.request.urlopen(req, timeout=600).read()


def index():
    txt = fetch(BASE + "dists/stable/main/binary-aarch64/Packages").decode("utf-8", "ignore")
    lines = txt.replace("\r", "").split("\n")
    pkgs = {}
    i = 0
    while i < len(lines):
        if lines[i].startswith("Package: "):
            name = lines[i][9:].strip()
            blk, j = [], i + 1
            while j < len(lines) and not lines[j].startswith("Package: "):
                blk.append(lines[j])
                j += 1
            dep = next((x[9:].strip() for x in blk if x.startswith("Depends: ")), "")
            fn = next((x[10:].strip() for x in blk if x.startswith("Filename: ")), "")
            sz = next((x[6:].strip() for x in blk if x.startswith("Size: ")), "0")
            pkgs[name] = (dep, fn, int(sz or 0))
            i = j
        else:
            i += 1
    return pkgs


def extract_deb(data, outdir):
    os.makedirs(outdir, exist_ok=True)
    pos, payload = 8, None
    while pos + 60 <= len(data):
        nm = data[pos:pos + 16].decode("ascii", "ignore").strip().rstrip("/")
        sz = int(data[pos + 48:pos + 58].decode("ascii", "ignore").strip())
        if nm.startswith("data.tar"):
            payload = data[pos + 60:pos + 60 + sz]
            break
        pos += 60 + sz
        if pos % 2:
            pos += 1
    tf = tarfile.open(fileobj=io.BytesIO(payload))
    try:
        tf.extractall(outdir, filter="fully_trusted")
    except TypeError:
        tf.extractall(outdir)


def needed(readelf, path):
    out = subprocess.run([readelf, "-d", path], capture_output=True, text=True, errors="ignore").stdout
    return [l.split("[")[1].rstrip("]") for l in out.splitlines() if "NEEDED" in l]


def find_readelf():
    ndk_root = os.environ.get("ANDROID_NDK_HOME") or ""
    cands = []
    if ndk_root:
        cands.append(ndk_root)
    sdk = os.environ.get("ANDROID_HOME", r"D:\Android\Sdk")
    ndk_dir = os.path.join(sdk, "ndk")
    if os.path.isdir(ndk_dir):
        for d in sorted(os.listdir(ndk_dir)):
            cands.append(os.path.join(ndk_dir, d))
    for c in cands:
        p = os.path.join(c, "toolchains", "llvm", "prebuilt", "windows-x86_64", "bin", "llvm-readelf.exe")
        if os.path.exists(p):
            return p
    raise SystemExit("找不到 llvm-readelf.exe（设 ANDROID_NDK_HOME）")


def ensure_pkg(name, pkgs):
    deb = os.path.join(CACHE, name + ".deb")
    out = os.path.join(CACHE, name)
    if not os.path.exists(deb):
        fn = pkgs[name][1]
        log("   下载 %-16s %s" % (name, fn))
        open(deb, "wb").write(fetch(BASE + fn))
    if not os.path.isdir(out):
        extract_deb(open(deb, "rb").read(), out)
    return os.path.join(out, "data", "data", "com.termux", "files", "usr")


def main():
    tools = list(TOOLS)
    if "--with-ffmpeg" in sys.argv:
        tools += FFMPEG_TOOLS
    readelf = find_readelf()
    os.makedirs(CACHE, exist_ok=True)
    pkgs = index()
    log("索引包数: %d，待打包工具: %d" % (len(pkgs), len(tools)))

    # 1) 先把所需包的【包级依赖闭包】算出来（只下载直接列出的包会漏掉 oniguruma/ncurses/openssl 这些）
    roots = {}
    queue = [pkg for _, pkg, _ in tools if pkg in pkgs]
    visited = set()
    while queue:
        pkg = queue.pop()
        if pkg in visited or pkg not in pkgs:
            continue
        visited.add(pkg)
        roots[pkg] = ensure_pkg(pkg, pkgs)
        for d in re.split(r"[,\s]+", pkgs[pkg][0]):
            d = re.split(r"[<>=]", d.strip())[0].strip()
            if d in pkgs and d not in visited:
                queue.append(d)
    log("  相关包 %d 个" % len(roots))

    # 2) 库名 -> 文件（含软链接名）
    byname = {}
    for pkg, root in roots.items():
        for sub in ("lib", "libexec"):
            d = os.path.join(root, sub)
            if not os.path.isdir(d):
                continue
            for f in os.listdir(d):
                fp = os.path.join(d, f)
                if os.path.isfile(fp) and ".so" in f and f not in byname:
                    byname[f] = fp

    # 3) 每个工具做 NEEDED 传递闭包
    binaries, need = {}, {}
    queue = []
    for name, pkg, rel in tools:
        p = os.path.join(roots.get(pkg, ""), rel.replace("/", os.sep))
        if not os.path.isfile(p):
            log("   !! 找不到可执行文件 %s（%s）" % (name, rel))
            continue
        binaries[name] = p
        for n in needed(readelf, p):
            if n not in SYSTEM_LIBS and n not in need:
                need[n] = None
                queue.append(n)
    while queue:
        n = queue.pop()
        fp = byname.get(n)
        if not fp:
            continue
        need[n] = fp
        for m in needed(readelf, fp):
            if m not in SYSTEM_LIBS and m not in need:
                need[m] = None
                queue.append(m)
    missing = [n for n, v in need.items() if v is None]
    if missing:
        log("   !! 缺失库（可能是可选依赖，运行时可能报警）: %s" % missing)
    libs = {n: v for n, v in need.items() if v}
    log("  依赖库 %d 个，%.1f MB" % (len(libs), sum(os.path.getsize(v) for v in libs.values()) / 1048576))

    # 4) 库打包（保留真实文件名）
    os.makedirs(JNI, exist_ok=True)
    libs_tar = os.path.join(JNI, "libtoolkit_libs.so")
    with tarfile.open(libs_tar, "w:gz") as tf:
        for n, fp in sorted(libs.items()):
            tf.add(fp, arcname=n)
    log("  写出 %s  %.1f MB" % (os.path.basename(libs_tar), os.path.getsize(libs_tar) / 1048576))

    # 5) 可执行文件 -> lib<name>_bin.so（RUNPATH 补 $ORIGIN）
    old_runpath = b"/data/data/com.termux/files/usr/lib"
    manifest = {"libs_bundle": "libtoolkit_libs.so", "extract_dir": "toolkit_lib", "tools": []}
    for name, p in sorted(binaries.items()):
        safe = re.sub(r"[^A-Za-z0-9]", "_", name)
        dst = os.path.join(JNI, "lib%s_bin.so" % safe)
        d = open(p, "rb").read()
        if old_runpath in d:
            d = d.replace(old_runpath, b"$ORIGIN" + b"\x00" * (len(old_runpath) - 7))
        open(dst, "wb").write(d)
        manifest["tools"].append({"name": name, "lib": os.path.basename(dst), "pkg": name})
        log("  %-10s -> %-24s %8d bytes" % (name, os.path.basename(dst), len(d)))

    man = os.path.join(JNI, "libtoolkit_manifest.so")
    open(man, "w", encoding="utf-8").write(json.dumps(manifest, ensure_ascii=False, indent=1))
    log("  写出清单 %s（%d 个工具）" % (os.path.basename(man), len(manifest["tools"])))


if __name__ == "__main__":
    main()
