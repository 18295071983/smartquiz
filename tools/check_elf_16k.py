"""检查 .so 的 16 KB 页对齐（Android 15/16 要求 LOAD 段 p_align >= 0x4000）。

用法：python tools/check_elf_16k.py [目录或 .so ...]
输出每个库的：架构、ELF 类型、LOAD 段 p_align 集合、是否符合 16 KB、风险提示。
退出码：0 全部兼容；1 存在 4 KB 对齐（不兼容 16 KB 设备）。
"""
import os
import struct
import sys

PT_LOAD = 1
PAGE_4K = 0x1000
PAGE_16K = 0x4000

MACHINES = {0x28: "arm", 0xB7: "aarch64", 0x3E: "x86-64", 0x03: "x86"}


def read_elf(path):
    """返回 (arch, is64, load_aligns, min_align) 或 None（非 ELF/不可读）。"""
    try:
        with open(path, "rb") as f:
            ident = f.read(16)
            if len(ident) < 16 or ident[:4] != b"\x7fELF":
                return None
            is64 = ident[4] == 2
            endian = "<" if ident[5] == 1 else ">"
            f.seek(0)   # 上一行读掉了 16 字节 ident，必须回到文件头再读 e_* 字段
            if is64:
                header = f.read(64)
                if len(header) < 64:
                    return None
                (e_machine,) = struct.unpack(endian + "H", header[18:20])
                (e_phoff,) = struct.unpack(endian + "Q", header[32:40])
                (e_phentsize,) = struct.unpack(endian + "H", header[54:56])
                (e_phnum,) = struct.unpack(endian + "H", header[56:58])
            else:
                header = f.read(52)
                if len(header) < 52:
                    return None
                (e_machine,) = struct.unpack(endian + "H", header[18:20])
                (e_phoff,) = struct.unpack(endian + "I", header[28:32])
                (e_phentsize,) = struct.unpack(endian + "H", header[42:44])
                (e_phnum,) = struct.unpack(endian + "H", header[44:46])
            aligns = []
            for i in range(min(e_phnum, 64)):
                f.seek(e_phoff + i * e_phentsize)
                raw = f.read(e_phentsize)
                if len(raw) < e_phentsize:
                    break
                if is64:
                    (p_type,) = struct.unpack(endian + "I", raw[0:4])
                    (p_align,) = struct.unpack(endian + "Q", raw[48:56])
                else:
                    (p_type,) = struct.unpack(endian + "I", raw[0:4])
                    (p_align,) = struct.unpack(endian + "I", raw[28:32])
                if p_type == PT_LOAD and p_align:
                    aligns.append(p_align)
            if not aligns:
                return None
            return MACHINES.get(e_machine, hex(e_machine)), is64, aligns, min(aligns)
    except OSError:
        return None


def iter_so(paths):
    for p in paths:
        if os.path.isdir(p):
            for root, _dirs, files in os.walk(p):
                if f"{os.sep}build{os.sep}" in root + os.sep:
                    continue
                for name in files:
                    if name.endswith(".so"):
                        yield os.path.join(root, name)
        elif p.endswith(".so"):
            yield p


def main(argv):
    roots = argv[1:] or ["src/main/jniLibs", "src/main/cpp", "viewer/src/main/jniLibs"]
    bad, ok, skipped = [], [], []
    for so in sorted(set(iter_so(roots))):
        rel = os.path.relpath(so, os.getcwd())
        try:
            info = read_elf(so)
        except Exception as exc:  # 畸形/截断的 ELF：记一笔，不让整轮扫描中断
            skipped.append(f"{rel}（解析失败: {exc}）")
            continue
        if info is None:
            skipped.append(rel)
            continue
        arch, is64, aligns, minimum = info
        line = f"{rel}  arch={arch}  bits={'64' if is64 else '32'}  LOAD_align={sorted(hex(a) for a in set(aligns))}"
        if minimum >= PAGE_16K:
            ok.append(line)
        else:
            bad.append(line + ("   <-- 4KB 对齐，16KB 设备会加载失败" if minimum <= PAGE_4K else "   <-- 对齐不足 16KB"))
    print("=== 16 KB 兼容 ===")
    for line in ok:
        print("  OK   " + line)
    if bad:
        print("=== 不兼容 ===")
        for line in bad:
            print("  BAD  " + line)
    if skipped:
        print(f"=== 跳过 {len(skipped)} 个（非 ELF 或不可读）===")
        for rel in skipped[:10]:
            print("  --   " + rel)
    print(f"\n合计 OK={len(ok)}  BAD={len(bad)}  SKIP={len(skipped)}")
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
