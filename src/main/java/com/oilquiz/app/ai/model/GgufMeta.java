package com.oilquiz.app.ai.model;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * 极简 GGUF 头部解析：只取"模型架构信息"卡片需要的元数据。
 *
 * <p>为什么需要：状态页原先只在**本地 llama.cpp 模型已加载**时才显示参数量/层数/注意力头，
 * NPU 引擎模式下本地服务是空的 → 一直显示"未加载"。这里直接从磁盘上的 .gguf 文件读，
 * 于是 NPU / llama.cpp 两种模式都能显示真实架构信息。
 *
 * <p>格式（GGUF v2/v3）：magic "GGUF" + u32 version + u64 tensorCount + u64 kvCount
 * + kvCount × (string key + u32 type + value)；其后是 tensorCount × (string name + u32 nDims
 * + u64 dims[] + u32 type + u64 offset)。全部小端。
 */
public final class GgufMeta {

    public String architecture = "";
    public long blockCount = 0;
    public long headCount = 0;
    public long headCountKv = 0;
    public long embeddingLength = 0;
    public long contextLength = 0;
    public long headLength = 0;          // attention.key_length（KV 头维度）
    public boolean hasLinearAttention = false;   // 混合结构（含 SSM/线性注意力层）
    public long fullAttentionInterval = 0;       // 每 N 层一个全注意力层
    public long parameterCount = 0;

    private GgufMeta() {
    }

    /** 解析失败（文件不存在/非 GGUF/截断）返回 null，调用方照旧显示占位 */
    public static GgufMeta read(File f) {
        if (f == null || !f.isFile()) {
            return null;
        }
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] magic = new byte[4];
            raf.readFully(magic);
            if (magic[0] != 'G' || magic[1] != 'G' || magic[2] != 'U' || magic[3] != 'F') {
                return null;
            }
            readInt(raf);                          // version
            long tensorCount = readLong(raf);
            long kvCount = readLong(raf);

            GgufMeta m = new GgufMeta();
            for (long i = 0; i < kvCount; i++) {
                String key = readString(raf);
                int type = readInt(raf);
                Object val = readValue(raf, type);
                if (key == null) {
                    continue;
                }
                if ("general.architecture".equals(key) && val instanceof String) {
                    m.architecture = (String) val;
                } else if ("general.parameter_count".equals(key) && val instanceof Number) {
                    m.parameterCount = ((Number) val).longValue();
                } else if (key.endsWith(".block_count") && val instanceof Number) {
                    m.blockCount = ((Number) val).longValue();
                } else if (key.endsWith(".attention.head_count") && val instanceof Number) {
                    m.headCount = ((Number) val).longValue();
                } else if (key.endsWith(".attention.head_count_kv") && val instanceof Number) {
                    m.headCountKv = ((Number) val).longValue();
                } else if (key.endsWith(".embedding_length") && val instanceof Number) {
                    m.embeddingLength = ((Number) val).longValue();
                } else if (key.endsWith(".context_length") && val instanceof Number) {
                    m.contextLength = ((Number) val).longValue();
                } else if (key.endsWith(".attention.key_length") && val instanceof Number) {
                    m.headLength = ((Number) val).longValue();
                } else if (key.endsWith(".full_attention_interval") && val instanceof Number) {
                    m.fullAttentionInterval = ((Number) val).longValue();
                } else if (key.contains(".ssm.") || key.contains(".linear_")) {
                    m.hasLinearAttention = true;
                }
            }

            // 元数据里没有参数量时，用张量表累加（元素个数即参数量）
            if (m.parameterCount <= 0 && tensorCount > 0 && tensorCount < 100000) {
                long total = 0;
                for (long i = 0; i < tensorCount; i++) {
                    readString(raf);               // tensor name
                    int nDims = readInt(raf);
                    long elems = 1;
                    for (int d = 0; d < nDims && d < 8; d++) {
                        long dim = readLong(raf);
                        if (dim > 0 && elems < (1L << 40)) {
                            elems *= dim;
                        }
                    }
                    readInt(raf);                  // ggml type
                    readLong(raf);                 // offset
                    total += elems;
                }
                m.parameterCount = total;
            }
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 参数量展示：4.02B / 1.71B / 512M */
    public String parameterText() {
        if (parameterCount <= 0) {
            return "—";
        }
        double b = parameterCount / 1e9;
        if (b >= 1) {
            return String.format(java.util.Locale.US, "%.2f B", b);
        }
        return String.format(java.util.Locale.US, "%.0f M", parameterCount / 1e6);
    }

    /** 注意力头展示：32 / 8 (KV) */
    public String headText() {
        if (headCount <= 0) {
            return "—";
        }
        if (headCountKv > 0 && headCountKv != headCount) {
            return headCount + " / " + headCountKv + " (KV)";
        }
        return String.valueOf(headCount);
    }

    // ---------------- 底层读取（小端） ----------------

    private static int readInt(RandomAccessFile raf) throws Exception {
        int b0 = raf.read(), b1 = raf.read(), b2 = raf.read(), b3 = raf.read();
        if ((b0 | b1 | b2 | b3) < 0) {
            throw new java.io.EOFException();
        }
        return (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24);
    }

    private static long readLong(RandomAccessFile raf) throws Exception {
        long lo = readInt(raf) & 0xFFFFFFFFL;
        long hi = readInt(raf) & 0xFFFFFFFFL;
        return lo | (hi << 32);
    }

    private static String readString(RandomAccessFile raf) throws Exception {
        long len = readLong(raf);
        if (len < 0 || len > (16L << 20)) {
            throw new java.io.EOFException("bad string len " + len);
        }
        byte[] buf = new byte[(int) len];
        raf.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    /** 按 GGUF 值类型读一个值；数组只做跳过（返回元素个数），避免为展示付代价 */
    private static Object readValue(RandomAccessFile raf, int type) throws Exception {
        switch (type) {
            case 0:                                     // UINT8
                return (long) (raf.read() & 0xFF);
            case 1:                                     // INT8
                return (long) raf.read();
            case 2: {                                   // UINT16
                int b0 = raf.read(), b1 = raf.read();
                return (long) ((b0 & 0xFF) | ((b1 & 0xFF) << 8));
            }
            case 3: {                                   // INT16
                int b0 = raf.read(), b1 = raf.read();
                return (long) (short) ((b0 & 0xFF) | ((b1 & 0xFF) << 8));
            }
            case 4:                                     // UINT32
                return readInt(raf) & 0xFFFFFFFFL;
            case 5:                                     // INT32
                return (long) readInt(raf);
            case 6:                                     // FLOAT32
                return (double) Float.intBitsToFloat(readInt(raf));
            case 7:                                     // BOOL
                return (long) raf.read();
            case 8:                                     // STRING
                return readString(raf);
            case 9: {                                   // ARRAY：跳过
                int itemType = readInt(raf);
                long n = readLong(raf);
                if (n < 0 || n > (1L << 28)) {
                    throw new java.io.EOFException("bad array len " + n);
                }
                for (long i = 0; i < n; i++) {
                    readValue(raf, itemType);
                }
                return n;
            }
            case 10:                                    // UINT64
                return readLong(raf);
            case 11:                                    // INT64
                return readLong(raf);
            case 12: {                                  // FLOAT64
                return Double.longBitsToDouble(readLong(raf));
            }
            default:
                throw new java.io.EOFException("unknown gguf type " + type);
        }
    }
}
