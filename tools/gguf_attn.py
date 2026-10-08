"""GGUF 注意力架构探针：读设备上模型的元数据，判断它有多少层真正存 KV cache。

用途
----
混合注意力模型（如 Qwen3.5 的 SSM/GDN 结构）并非每层都存 KV：循环层只维护固定尺寸
状态，KV cache 只建在非循环层上。按全部层数估算 KV 会高估数倍，进而误判内存预算。
本脚本给出判断所需的关键键，与 llama.cpp 的判定同源：

    KV 层数 = block_count - 循环层数
    循环层数 = attention.recurrent_layers 中非 0 的个数；
               无该键时用 full_attention_interval（llama.cpp 默认 4），
               即 (i+1) % interval != 0 的层为循环层

实测对照：Qwen3.5-2B 24 层里只有 6 层存 KV（144 MiB @12288 ctx），
          MiniCPM5-2B 42 层全部存 KV（504 MiB @12288 ctx），相差 3.5 倍。

用法
----
    python tools/gguf_attn.py                      # 默认探设备上的 Qwen3.5-2B 与 MiniCPM5
    python tools/gguf_attn.py files/ai_models/X.gguf

实现：gguf 元数据经 `adb exec-out run-as <pkg> cat` 导出到本地临时文件后解析
（避免二进制走 shell 管道被破坏）；只读头部、不加载权重。
依赖：adb 可执行文件路径见下方 ADB；模型位于应用私有目录，故需 run-as。
"""
import os
import struct
import subprocess
import sys
import tempfile

ADB = r"D:\Android\Sdk\platform-tools\adb.exe"
PKG = "com.oilquiz.app"

(U8, I8, U16, I16, U32, I32, F32, BOOL, STRING, ARRAY, U64, I64, F64) = range(13)
SCALAR_SIZE = {U8: 1, I8: 1, U16: 2, I16: 2, U32: 4, I32: 4, F32: 4,
               BOOL: 1, U64: 8, I64: 8, F64: 8}
SCALAR_FMT = {U8: "<B", I8: "<b", U16: "<H", I16: "<h", U32: "<I", I32: "<i",
              F32: "<f", BOOL: "<?", U64: "<Q", I64: "<q", F64: "<d"}

INTEREST = ("block_count", "head_count", "key_length", "value_length",
            "embedding_length", "context_length", "sliding_window",
            "full_attention_interval", "recurrent", "linear",
            "architecture", "name", "rope.", "attention.layer_norm",
            "attention.q_lora", "attention.kv_lora", "head_count_kv",
            "ssm", "conv", "state_size", "gdn", "delta", "hybrid")


def export(remote, local):
    with open(local, "wb") as out:
        p = subprocess.Popen([ADB, "exec-out", "run-as", PKG, "cat", remote],
                             stdout=out, stderr=subprocess.DEVNULL)
        p.wait()
    return os.path.getsize(local)


class R:
    def __init__(self, f):
        self.f = f

    def read(self, n):
        b = self.f.read(n)
        if len(b) != n:
            raise EOFError("short read %d/%d" % (len(b), n))
        return b


def read_string(r):
    ln = struct.unpack("<Q", r.read(8))[0]
    if ln > (1 << 24):
        raise ValueError("string too long %d" % ln)
    return r.read(ln).decode("utf-8", "replace")


def skip(r, t):
    if t == STRING:
        read_string(r)
    elif t == ARRAY:
        et = struct.unpack("<I", r.read(4))[0]
        n = struct.unpack("<Q", r.read(8))[0]
        if et == STRING:
            for _ in range(n):
                read_string(r)
        else:
            sz = SCALAR_SIZE.get(et)
            if sz is None:
                raise ValueError("bad elem %d" % et)
            r.read(sz * n)
    else:
        fmt = SCALAR_FMT.get(t)
        if fmt is None:
            raise ValueError("bad type %d" % t)
        r.read(struct.calcsize(fmt))


def read_scalar(r, t):
    if t == STRING:
        return read_string(r)
    fmt = SCALAR_FMT.get(t)
    return struct.unpack(fmt, r.read(struct.calcsize(fmt)))[0] if fmt else None


def probe(remote):
    name = remote.split("/")[-1]
    print("== %s ==" % name)
    with tempfile.NamedTemporaryFile(delete=False, suffix=".gguf") as tf:
        local = tf.name
    try:
        size = export(remote, local)
        print("  导出字节数: %d" % size)
        with open(local, "rb") as f:
            r = R(f)
            if r.read(4) != b"GGUF":
                print("  非 GGUF")
                return
            ver = struct.unpack("<I", r.read(4))[0]
            n_tensors = struct.unpack("<Q", r.read(8))[0]
            n_kv = struct.unpack("<Q", r.read(8))[0]
            print("  GGUF v%d, tensors=%d, kv=%d" % (ver, n_tensors, n_kv))
            arch = ""
            rows = []
            for _ in range(n_kv):
                k = read_string(r)
                t = struct.unpack("<I", r.read(4))[0]
                if any(x in k for x in INTEREST) and "tokens" not in k and "merges" not in k:
                    if t == ARRAY:
                        et = struct.unpack("<I", r.read(4))[0]
                        n = struct.unpack("<Q", r.read(8))[0]
                        rows.append((k, "<array n=%d>" % n))
                        if et == STRING:
                            for _ in range(n):
                                read_string(r)
                        else:
                            sz = SCALAR_SIZE.get(et)
                            if sz is None:
                                raise ValueError("bad elem %d" % et)
                            r.read(sz * n)
                    else:
                        v = read_scalar(r, t)
                        rows.append((k, v))
                        if k.endswith("architecture"):
                            arch = v
                else:
                    skip(r, t)
            print("  architecture = %s" % arch)
            for k, v in rows:
                if k.endswith("architecture"):
                    continue
                print("    %-50s = %s" % (k, v))
    except Exception as e:
        print("  失败: %s" % e)
    finally:
        try:
            os.unlink(local)
        except Exception:
            pass
    print()


if __name__ == "__main__":
    targets = sys.argv[1:] or [
        "files/ai_models/Qwen3.5-2B-Q4_0.gguf",
        "files/ai_models/MiniCPM5-2B-Q4_K_M.gguf",
    ]
    for t in targets:
        probe(t)
