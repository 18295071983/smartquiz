"""检查 APK 内 .so 的打包方式与 16 KB 对齐。

关注两点：
1. compress_type：0=STORE（直接 mmap），8=DEFLATE（安装时解压）
2. STORE 时数据起始偏移是否 16 KB 对齐（Android 15 的 16 KB 设备要求）
"""
import struct
import sys
import zipfile

PAGE = 16384


def data_offset(f, info):
    f.seek(info.header_offset)
    local = f.read(30)
    if len(local) < 30 or local[:4] != b"PK\x03\x04":
        return None
    name_len, extra_len = struct.unpack("<HH", local[26:30])
    return info.header_offset + 30 + name_len + extra_len


def main(apk):
    with open(apk, "rb") as f, zipfile.ZipFile(apk) as z:
        libs = [i for i in z.infolist() if i.filename.startswith("lib/") and i.filename.endswith(".so")]
        print(f"APK: {apk}\n内嵌 .so: {len(libs)} 个\n")
        stored = delated = misaligned = 0
        for info in libs:
            mode = "STORE" if info.compress_type == 0 else "DEFLATE"
            off = data_offset(f, info)
            align = off % PAGE if off is not None else None
            if info.compress_type == 0:
                stored += 1
                if align != 0:
                    misaligned += 1
            else:
                delated += 1
            flag = ""
            if info.compress_type == 0 and align != 0:
                flag = f"   <-- STORE 但偏移未按 16KB 对齐 (off%16K={align})"
            print(f"  {info.filename:58s} {mode:8s} {info.file_size/1048576:7.2f}MB{flag}")
        print(f"\nSTORE={stored}  DEFLATE={delated}  STORE 未对齐={misaligned}")
        return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "build/outputs/apk/debug/答题宝-debug-2.0.apk"))
