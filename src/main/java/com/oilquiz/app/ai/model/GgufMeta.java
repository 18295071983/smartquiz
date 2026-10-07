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
    /** 张量 ggml 类型直方图：type id -> 张量个数（用于判断 HTP 兼容性）*/
    public final java.util.Map<Integer, Integer> tensorTypeCounts = new java.util.LinkedHashMap<>();
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
            Reader r = new Reader();
            byte[] magic = new byte[4];
            raf.readFully(magic);
            if (magic[0] != 'G' || magic[1] != 'G' || magic[2] != 'U' || magic[3] != 'F') {
                return null;
            }
            r.readInt(raf);                        // version
            long tensorCount = r.readLong(raf);
            long kvCount = r.readLong(raf);

            GgufMeta m = new GgufMeta();
            for (long i = 0; i < kvCount; i++) {
                String key = r.readString(raf);
                int type = r.readInt(raf);
                Object val = r.readValue(raf, type);
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
            // 无论 parameter_count 是否缺失都遍历一次张量表：既补参数量，也统计类型（HTP 兼容性提示）
        if (tensorCount > 0 && tensorCount < 100000) {
                long total = 0;
                for (long i = 0; i < tensorCount; i++) {
                    r.readString(raf);             // tensor name
                    int nDims = r.readInt(raf);
                    long elems = 1;
                    for (int d = 0; d < nDims && d < 8; d++) {
                        long dim = r.readLong(raf);
                        if (dim > 0 && elems < (1L << 40)) {
                            elems *= dim;
                        }
                    }
                    int ggmlType = r.readInt(raf); // ggml type
                    m.tensorTypeCounts.merge(ggmlType, 1, Integer::sum);
                    r.readLong(raf);               // offset
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

    // ---------------- 底层读取（小端，带预读缓冲） ----------------
    //
    // GGUF-BUFFERED（2026-10-07 实测定位）：原实现用**未缓冲**的 RandomAccessFile 逐字节读：
    // readInt = 4 次 raf.read()、readLong = 8 次 —— 每次都是一次 syscall。而 GGUF 的
    // tokenizer.ggml.tokens / merges 是**十万级字符串数组**，readValue 的 ARRAY 分支又逐个元素
    // 递归解析，于是单次 read() 要打上百万次 syscall。实测该解析在 NPU 路径上**每次发消息都会
    // 重跑一遍**（NpuEngineRouter.chatJson → NpuLlmChat.planForCurrentModel → planNCtx →
    // GgufMeta.read），表现为"模型已就绪后仍有一段十几秒的无日志空等"。
    // 修法：64KB 预读缓冲 + 定长数组整体跳过。解析状态放在 Reader 实例里（read() 是静态方法）。
    private static final int BUF_SIZE = 1 << 16;

    private static final class Reader {
        private final byte[] ioBuf = new byte[BUF_SIZE];
        private int ioLen = 0;   // 缓冲区有效字节数
        private int ioPos = 0;   // 已消费位置

        private void fill(RandomAccessFile raf) throws Exception {
            ioPos = 0;
            int off = 0;
            while (off < ioBuf.length) {
                int r = raf.read(ioBuf, off, ioBuf.length - off);
                if (r < 0) break;
                off += r;
            }
            ioLen = off;
        }

        private int byteAt(RandomAccessFile raf, long index) throws Exception {
            // index 只在 ioPos..ioPos+4 内被调用（readInt），最多预读一次
            if (index >= ioLen) {
                fill(raf);
                if (index >= ioLen) {
                    throw new java.io.EOFException();
                }
            }
            return ioBuf[(int) index] & 0xFF;
        }

        /** 跳过 n 字节（n 可能很大，分块推进） */
        private void skip(RandomAccessFile raf, long n) throws Exception {
            long left = n;
            while (left > 0) {
                if (ioPos >= ioLen) {
                    fill(raf);
                    if (ioLen == 0) throw new java.io.EOFException();
                }
                long step = Math.min(left, ioLen - (long) ioPos);
                ioPos += (int) step;
                left -= step;
            }
        }

        /** 把 n 字节完整读入 dst 的 off 处（可能跨多个缓冲块） */
        private void readFullyBuffered(RandomAccessFile raf, byte[] dst, int off, int n) throws Exception {
            int written = 0;
            while (written < n) {
                if (ioPos >= ioLen) {
                    fill(raf);
                    if (ioLen == 0) throw new java.io.EOFException();
                }
                int step = Math.min(n - written, ioLen - ioPos);
                System.arraycopy(ioBuf, ioPos, dst, off + written, step);
                ioPos += step;
                written += step;
            }
        }

        /** 跳过一个数组：定长类型可直接前移；字符串数组只按长度前缀前移 */
        private void skipArray(RandomAccessFile raf, int itemType, long n) throws Exception {
            int sz = fixedTypeSize(itemType);
            if (sz > 0) {
                skip(raf, n * sz);
                return;
            }
            for (long i = 0; i < n; i++) {
                if (itemType == 8) {              // STRING：长度前缀 + 内容
                    long len = readLong(raf);
                    if (len < 0 || len > (16L << 20)) throw new java.io.EOFException("bad string len " + len);
                    skip(raf, len);
                } else {
                    readValue(raf, itemType);     // 嵌套数组等罕见情况，回退递归
                }
            }
        }

        private int readInt(RandomAccessFile raf) throws Exception {
            int b0 = byteAt(raf, ioPos);
            int b1 = byteAt(raf, ioPos + 1L);
            int b2 = byteAt(raf, ioPos + 2L);
            int b3 = byteAt(raf, ioPos + 3L);
            ioPos += 4;
            return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
        }

        private long readLong(RandomAccessFile raf) throws Exception {
            long lo = readInt(raf) & 0xFFFFFFFFL;
            long hi = readInt(raf) & 0xFFFFFFFFL;
            return lo | (hi << 32);
        }

        private String readString(RandomAccessFile raf) throws Exception {
            long len = readLong(raf);
            if (len < 0 || len > (16L << 20)) {
                throw new java.io.EOFException("bad string len " + len);
            }
            byte[] buf = new byte[(int) len];
            readFullyBuffered(raf, buf, 0, buf.length);
            return new String(buf, StandardCharsets.UTF_8);
        }

        /** 按 GGUF 值类型读一个值；数组只做跳过（返回元素个数），避免为展示付代价 */
        private Object readValue(RandomAccessFile raf, int type) throws Exception {
            switch (type) {
                case 0:                                     // UINT8
                    return (long) byteAt(raf, ioPos++);
                case 1:                                     // INT8
                    return (long) (byte) byteAt(raf, ioPos++);
                case 2: {                                   // UINT16
                    int b0 = byteAt(raf, ioPos);
                    int b1 = byteAt(raf, ioPos + 1L);
                    ioPos += 2;
                    return (long) (b0 | (b1 << 8));
                }
                case 3: {                                   // INT16
                    int b0 = byteAt(raf, ioPos);
                    int b1 = byteAt(raf, ioPos + 1L);
                    ioPos += 2;
                    return (long) (short) (b0 | (b1 << 8));
                }
                case 4:                                     // UINT32
                    return readInt(raf) & 0xFFFFFFFFL;
                case 5:                                     // INT32
                    return (long) readInt(raf);
                case 6:                                     // FLOAT32
                    return (double) Float.intBitsToFloat(readInt(raf));
                case 7:                                     // BOOL
                    return (long) byteAt(raf, ioPos++);
                case 8:                                     // STRING
                    return readString(raf);
                case 9: {                                   // ARRAY：跳过
                    int itemType = readInt(raf);
                    long n = readLong(raf);
                    if (n < 0 || n > (1L << 28)) {
                        throw new java.io.EOFException("bad array len " + n);
                    }
                    // GGUF-ARRAY-SKIP：原先逐个元素递归解析（tokenizer 十万级数组 → 上百万次
                    // syscall）。这里整体跳过：定长类型直接前移，字符串数组只按长度前缀前移。
                    skipArray(raf, itemType, n);
                    return n;
                }
                case 10:                                    // UINT64
                    return readLong(raf);
                case 11:                                    // INT64
                    return readLong(raf);
                case 12:                                    // FLOAT64
                    return Double.longBitsToDouble(readLong(raf));
                default:
                    throw new java.io.EOFException("unknown gguf type " + type);
            }
        }
    }

    /** 定长 GGUF 值类型的字节数；变长（STRING=8 / ARRAY=9）返回 0 */
    private static int fixedTypeSize(int t) {
        switch (t) {
            case 0: case 1: case 7: return 1;      // U8 / I8 / BOOL
            case 2: case 3: return 2;              // U16 / I16
            case 4: case 5: case 6: return 4;      // U32 / I32 / F32
            case 10: case 11: case 12: return 8;   // U64 / I64 / F64
            default: return 0;
        }
    }

    /**
     * HTP（Hexagon NPU）兼容性说明。
     * 佐证：GenieX/HTP 只对部分量化类型提供 NPU 算子（Q4_0 / Q4_1 / Q8_0 / IQ4_NL / MXFP4 / F16 / F32），
     * K-quant（Q4_K/Q5_K/Q6_K…）会退到 CPU → 表现为"能跑但明显变慢"。这里据张量直方图给出提示。
     */
    public String htpCompatNote() {
        if (tensorTypeCounts.isEmpty()) {
            return "";
        }
        final java.util.Set<Integer> htpOk = new java.util.HashSet<>(java.util.Arrays.asList(
                0,   // F32
                1,   // F16
                2,   // Q4_0
                3,   // Q4_1
                8,   // Q8_0
                20,  // IQ4_NL
                39   // MXFP4
        ));
        final java.util.Map<Integer, String> names = new java.util.HashMap<>();
        names.put(0, "F32"); names.put(1, "F16"); names.put(2, "Q4_0"); names.put(3, "Q4_1");
        names.put(6, "Q5_0"); names.put(7, "Q5_1"); names.put(8, "Q8_0");
        names.put(10, "Q2_K"); names.put(11, "Q3_K"); names.put(12, "Q4_K"); names.put(13, "Q5_K");
        names.put(14, "Q6_K"); names.put(16, "IQ2_XXS"); names.put(17, "IQ2_XS"); names.put(18, "IQ3_XXS");
        names.put(19, "IQ1_S"); names.put(20, "IQ4_NL"); names.put(21, "IQ3_S"); names.put(22, "IQ2_S");
        names.put(23, "IQ4_XS"); names.put(30, "BF16"); names.put(39, "MXFP4");
        int bad = 0;
        int badTensors = 0;
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Integer, Integer> e : tensorTypeCounts.entrySet()) {
            if (!htpOk.contains(e.getKey())) {
                bad++;
                badTensors += e.getValue();
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(names.containsKey(e.getKey()) ? names.get(e.getKey()) : ("type" + e.getKey()))
                        .append("×").append(e.getValue());
            }
        }
        if (bad == 0) {
            return "HTP 兼容：全部张量均命中 NPU 算子（Q4_0/Q8_0 等）";
        }
        return "⚠️ HTP 部分退 CPU：" + sb + "（共 " + badTensors + " 个张量，速度会下降；建议改用 Q4_0）";
    }
}
