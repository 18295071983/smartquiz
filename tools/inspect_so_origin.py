"""看 .so 是哪个 NDK/编译器编的（读 .comment 与 .note.android.ident）。

用于判断"这个库是我自己编的，还是从别的 APK 里扒的"：
- 有 `.note.android.ident`（Android 标识段）→ 一般是 NDK 编的 Android 库
- `.comment` 里能看到 clang 版本，可比对 NDK 版本（r26 之前默认 4 KB 对齐，r28 默认 16 KB）
"""
import os
import struct
import sys

SHT_NOTE = 7
SHT_STRTAB = 3


def sections(data):
    """返回 [(name, type, offset, size)]；解析失败返回 []。"""
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != 2:
        return []
    le = data[5] == 1
    e = "<" if le else ">"
    (e_shoff,) = struct.unpack(e + "Q", data[40:48])
    (e_shentsize,) = struct.unpack(e + "H", data[58:60])
    (e_shnum,) = struct.unpack(e + "H", data[60:62])
    (e_shstrndx,) = struct.unpack(e + "H", data[62:64])
    if not e_shoff or not e_shnum:
        return []
    raw = []
    for i in range(e_shnum):
        base = e_shoff + i * e_shentsize
        if base + 64 > len(data):
            break
        (name_off,) = struct.unpack(e + "I", data[base:base + 4])
        (sh_type,) = struct.unpack(e + "I", data[base + 4:base + 8])
        (sh_offset,) = struct.unpack(e + "Q", data[base + 24:base + 32])
        (sh_size,) = struct.unpack(e + "Q", data[base + 32:base + 40])
        raw.append((name_off, sh_type, sh_offset, sh_size))
    if e_shstrndx >= len(raw):
        return []
    str_off = raw[e_shstrndx][2]
    out = []
    for name_off, sh_type, sh_offset, sh_size in raw:
        end = data.find(b"\x00", str_off + name_off)
        name = data[str_off + name_off:end].decode("utf-8", "replace") if end > 0 else ""
        out.append((name, sh_type, sh_offset, sh_size))
    return out


def main(paths):
    for p in paths:
        if not os.path.isfile(p) or not p.endswith(".so"):
            continue
        data = open(p, "rb").read()
        print(f"=== {p}")
        secs = sections(data)
        if not secs:
            print("   （非 ELF64，跳过）")
            continue
        for name, sh_type, off, size in secs:
            if name in (".comment", ".note.android.ident"):
                chunk = data[off:off + size]
                text = " ".join(
                    s.decode("utf-8", "replace")
                    for s in chunk.split(b"\x00")
                    if len(s) > 3 and all(32 <= b < 127 or b in (9, 10, 13) for b in s)
                )
                print(f"   {name}: {text[:400]}")
        if not any(n in (".comment", ".note.android.ident") for n, *_ in secs):
            print("   无 .comment / .note.android.ident（可能被 strip 过）")


if __name__ == "__main__":
    main(sys.argv[1:])
