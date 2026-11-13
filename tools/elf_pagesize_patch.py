"""把 .so 的 LOAD 段 p_align 从 4 KB 改到 16 KB（二进制补丁，不重编）。

前提：对每个 PT_LOAD 段，`p_vaddr % 0x4000 == p_offset % 0x4000` 必须成立，
否则改成 16 KB 对齐后内核/链接器映射会错位，**不能打补丁**。

用法：
    python tools/elf_pagesize_patch.py --check  <so...>
    python tools/elf_pagesize_patch.py --apply  <so...>   # 原地修改，先自行备份
"""
import os
import struct
import sys

PT_LOAD = 1
A4 = 0x1000
A16 = 0x4000


def load_headers(data):
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != 2:
        return None
    le = data[5] == 1
    e = "<" if le else ">"
    (phoff,) = struct.unpack(e + "Q", data[32:40])
    (phentsize,) = struct.unpack(e + "H", data[54:56])
    (phnum,) = struct.unpack(e + "H", data[56:58])
    out = []
    for i in range(min(phnum, 64)):
        base = phoff + i * phentsize
        if base + 56 > len(data):
            break
        p_type = struct.unpack(e + "I", data[base:base + 4])[0]
        (p_offset,) = struct.unpack(e + "Q", data[base + 8:base + 16])
        (p_vaddr,) = struct.unpack(e + "Q", data[base + 16:base + 24])
        (p_align,) = struct.unpack(e + "Q", data[base + 48:base + 56])
        out.append({"i": i, "type": p_type, "off": p_offset, "vaddr": p_vaddr,
                    "align": p_align, "align_pos": base + 48})
    return e, out


def analyse(path):
    data = open(path, "rb").read()
    parsed = load_headers(data)
    if parsed is None:
        return None
    e, phs = parsed
    loads = [p for p in phs if p["type"] == PT_LOAD]
    bad = [p for p in loads if (p["vaddr"] - p["off"]) % A16 != 0]
    already = loads and all(p["align"] >= A16 for p in loads)
    return {"loads": loads, "bad": bad, "already": already, "endian": e}


def main(argv):
    mode = "--check"
    files = []
    for a in argv[1:]:
        if a in ("--check", "--apply"):
            mode = a
        else:
            files.append(a)
    if not files:
        print("用法: python tools/elf_pagesize_patch.py --check|--apply <so...>")
        return 2
    unsafe = 0
    for f in files:
        r = analyse(f)
        if r is None:
            print(f"跳过（非 ELF64）: {f}")
            continue
        name = os.path.basename(f)
        if r["already"]:
            print(f"已是 16 KB: {name}")
            continue
        if r["bad"]:
            unsafe += 1
            print(f"不能打补丁（段地址与偏移在 16 KB 下不同余）: {name}")
            for p in r["bad"]:
                print(f"    LOAD#{p['i']} off={hex(p['off'])} vaddr={hex(p['vaddr'])}")
            continue
        if mode == "--check":
            print(f"可打补丁: {name}（{len(r['loads'])} 个 LOAD 段，均 16 KB 同余）")
            continue
        data = bytearray(open(f, "rb").read())
        for p in r["loads"]:
            struct.pack_into(r["endian"] + "Q", data, p["align_pos"], A16)
        with open(f, "wb") as fp:
            fp.write(data)
        print(f"已打补丁: {name}")
    return 1 if unsafe else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
