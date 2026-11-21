"""对账：源码树里的 .so 与 APK 里的 .so 为什么数量/结论"前后对不上"。

输出三张表：
1. 两边都有：按 ELF 对齐判定
2. 只在 APK：来自 AAR 依赖（TFLite/ORT/MLKit/PDFium/RTMP/Chaquopy…），源码树里没有
3. 只在源码树：被 abiFilters / 未打包 / 非 ELF 过滤掉的

用法：python tools/check_native_consistency.py [apk]
"""
import os
import shutil
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_elf_16k import PAGE_16K, iter_so, read_elf  # noqa: E402

ROOTS = ["src/main/jniLibs/arm64-v8a", "src/main/cpp", "viewer/src/main/jniLibs/arm64-v8a"]


def source_set():
    """源码树 arm64 的 ELF：返回 {basename: (rel, min_align)}，非 ELF 单独返回。"""
    elf, other = {}, []
    for p in iter_so(ROOTS):
        info = read_elf(p)
        rel = os.path.relpath(p, os.getcwd())
        if info is None:
            other.append(rel)
        else:
            elf[os.path.basename(p)] = (rel, info[0], info[3])
    return elf, other


def apk_set(apk):
    tmp = ".tmp-elf-audit"
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp, exist_ok=True)
    elf, other = {}, []
    with zipfile.ZipFile(apk) as z:
        for name in [n for n in z.namelist() if n.startswith("lib/arm64-v8a/") and n.endswith(".so")]:
            p = os.path.join(tmp, os.path.basename(name))
            with open(p, "wb") as f:
                f.write(z.read(name))
            info = read_elf(p)
            if info is None:
                other.append(name)
            else:
                elf[os.path.basename(name)] = (name, info[0], info[3])
    shutil.rmtree(tmp, ignore_errors=True)
    return elf, other


def main(apk):
    src, src_other = source_set()
    pkg, pkg_other = apk_set(apk)

    def verdict(align):
        return "OK" if align >= PAGE_16K else "BAD(4K)"

    both = sorted(set(src) & set(pkg))
    only_pkg = sorted(set(pkg) - set(src))
    only_src = sorted(set(src) - set(pkg))

    print(f"源码树 arm64 ELF={len(src)}（另有非 ELF {len(src_other)}）")
    print(f"APK    arm64 ELF={len(pkg)}（另有非 ELF {len(pkg_other)}）")
    print(f"\n[1] 两边都有 {len(both)}：")
    for n in both:
        print(f"    {n:52s} src={verdict(src[n][2]):7s} apk={verdict(pkg[n][2])}")
    print(f"\n[2] 只在 APK（AAR 依赖带进来的）{len(only_pkg)}：")
    for n in only_pkg:
        print(f"    {n:52s} apk={verdict(pkg[n][2])}")
    print(f"\n[3] 只在源码树（被过滤/未打包/Hexagon DSP/非 ELF）{len(only_src)}：")
    for n in only_src:
        print(f"    {n:52s} src={verdict(src[n][2])}")
    if src_other:
        print(f"\n[4] 源码树里非 ELF 的占位文件 {len(src_other)}：")
        for rel in src_other[:10]:
            print("    " + rel)
    bad_src = sum(1 for n in both if src[n][2] < PAGE_16K) + sum(1 for n in only_src if src[n][2] < PAGE_16K)
    bad_pkg = sum(1 for n in both if pkg[n][2] < PAGE_16K) + sum(1 for n in only_pkg if pkg[n][2] < PAGE_16K)
    print(f"\n汇总：源码树 BAD={bad_src}  APK BAD={bad_pkg}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "build/outputs/apk/debug/答题宝-debug-2.0.apk"))
