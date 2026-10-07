package com.oilquiz.app.ai.model;

import java.io.File;
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
        try {
            return readOrThrow(f);
        } catch (Throwable t) {
            // GGUF-PARSE-DIAG：原先这里静默返回 null，导致"元数据读不出来"这件事
            // 在上层只表现为一个 null，无法定位（实测 kvBytesPerToken 因此返回 0、
            // 上下文规划走兜底、Agent 窗口被压到 4096）。解析失败必须可见。
            android.util.Log.w("GgufMeta", "解析失败: " + t, t);
            return null;
        }
    }

    /**
     * 与 {@link #read(File)} 同逻辑，但**不吞异常**。
     *
     * <p>供测试/诊断定位解析失败的真实原因（read 内部 catch 后只返回 null，
     * 而 android.util.Log 在 JVM 单测里是空实现，异常会彻底消失）。</p>
     */
    public static GgufMeta readOrThrow(File f) throws Exception {
        if (f == null || !f.isFile()) {
            return null;
        }
        // GGUF-REFIMPL（2026-10-07）：改用**标准 InputStream + BufferedInputStream**，
        // 与项目内 llama.cpp 官方 Android 示例的实现一致
        // （src/main/cpp/llama.cpp/examples/llama.android/.../internal/gguf/GgufMetadataReaderImpl.kt）。
        // 先前手写 RandomAccessFile 预读缓冲，因"缓冲前移会重置 ioPos、而调用方仍用旧坐标"
        // 导致读取位置漂移、最终 EOFException，read() 返回 null → KV 估算为 0 →
        // 上下文规划走兜底 → Agent 窗口被压到 4096。标准流没有这种坐标问题。
        try (java.io.InputStream in = new java.io.BufferedInputStream(
                new java.io.FileInputStream(f), BUF_SIZE)) {
            return parse(in);
        }
    }

    /** 按 GGUF 规范解析（小端）。逻辑对照官方示例实现，只保留本类需要的字段。 */
    private static GgufMeta parse(java.io.InputStream in) throws Exception {
        byte[] magic = readFully(in, 4);
        if (magic[0] != 'G' || magic[1] != 'G' || magic[2] != 'U' || magic[3] != 'F') {
            return null;
        }
        readLEUInt32(in);              // version
        long tensorCount = readLELong(in);
        long kvCount = readLELong(in);

        GgufMeta m = new GgufMeta();
        java.util.ArrayDeque<String> trace = new java.util.ArrayDeque<>();
        for (long i = 0; i < kvCount; i++) {
            String key = readString(in);
            int type = readLEUInt32(in);
            Object val = parseValue(in, type);
            trace.addLast("kv[" + i + "] key=" + key + " type=" + type
                    + " val=" + (val == null ? "null" : val.toString()));
            while (trace.size() > 15) {
                trace.removeFirst();
            }
            // 实时更新：中途抛异常时也能看到"读到哪一对开始出问题"
            lastTrace = String.join("\n", trace);
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

        // 张量表：既补参数量（元数据可能缺 parameter_count），也统计类型（HTP 兼容性提示）
        if (tensorCount > 0 && tensorCount < 100000) {
            long total = 0;
            for (long i = 0; i < tensorCount; i++) {
                readString(in);                // tensor name
                int nDims = readLEUInt32(in);
                long elems = 1;
                for (int d = 0; d < nDims && d < 8; d++) {
                    long dim = readLELong(in);
                    if (dim > 0 && elems < (1L << 40)) {
                        elems *= dim;
                    }
                }
                int ggmlType = readLEUInt32(in);
                m.tensorTypeCounts.merge(ggmlType, 1, Integer::sum);
                readLELong(in);                // offset
                total += elems;
            }
            m.parameterCount = total;
        }
        return m;
    }

    /** 最近一次解析的 KV 轨迹（仅诊断用；read 失败时保留失败前的内容） */
    public static volatile String lastTrace = "";

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

    // ---------------- 底层读取（小端，标准流 + BufferedInputStream） ----------------
    //
    // GGUF-REFIMPL（2026-10-07）：读取与跳过逻辑**对照项目内 llama.cpp 官方 Android 示例**实现，
    //   src/main/cpp/llama.cpp/examples/llama.android/.../internal/gguf/GgufMetadataReaderImpl.kt
    // 性能靠 BufferedInputStream（64KB）解决；不再自己管理预读缓冲 —— 手写缓冲一旦搞错
    // "缓冲前移后 ioPos 的坐标系"就会读错位置（实测 EOFException → read 返回 null →
    // KV 估算为 0 → 上下文规划走兜底 → Agent 窗口被压到 4096）。
    private static final int BUF_SIZE = 1 << 16;

    /** 读满 n 字节，不足则抛 EOF（对应示例里的 readFully） */
    private static byte[] readFully(java.io.InputStream in, int n) throws Exception {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) {
                throw new java.io.EOFException("EOF while reading " + n + " bytes (got " + off + ")");
            }
            off += r;
        }
        return b;
    }

    /** 跳满 n 字节，不足则抛 EOF（对应示例里的 skipFully：skip() 返回 0 时回退为读取丢弃） */
    private static void skipFully(java.io.InputStream in, long n) throws Exception {
        long remaining = n;
        byte[] scratch = new byte[8192];
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else if (skipped == 0L) {
                int want = (int) Math.min(remaining, scratch.length);
                int read = in.read(scratch, 0, want);
                if (read < 0) {
                    throw new java.io.EOFException("EOF while skipping " + n + " bytes");
                }
                remaining -= read;
            } else {
                throw new java.io.EOFException("skip returned negative");
            }
        }
    }

    /** 小端 u32 */
    private static int readLEUInt32(java.io.InputStream in) throws Exception {
        byte[] b = readFully(in, 4);
        return (b[3] & 0xFF) << 24 | (b[2] & 0xFF) << 16 | (b[1] & 0xFF) << 8 | (b[0] & 0xFF);
    }

    /** 小端 u64（长度/计数，按 long 承载） */
    private static long readLELong(java.io.InputStream in) throws Exception {
        byte[] b = readFully(in, 8);
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[i] & 0xFFL);
        }
        return v;
    }

    /** GGUF 字符串：u64 长度 + UTF-8 字节 */
    private static String readString(java.io.InputStream in) throws Exception {
        long len = readLELong(in);
        if (len < 0 || len > (1 << 20)) {
            throw new java.io.EOFException("bad string len " + len);
        }
        if (len == 0) {
            return "";
        }
        return new String(readFully(in, (int) len), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 定长 GGUF 值类型的字节数；变长（STRING=8 / ARRAY=9）返回 0 */
    private static int fixedTypeSize(int t) {
        switch (t) {
            case 0: case 1: case 7: return 1;      // U8 / I8 / BOOL
            case 2: case 3: return 2;              // U16 / I16
            case 4: case 5: case 6: return 4;      // U32 / I32 / F32
            case 10: case 11: case 12: return 8;   // U64 / I64 / F64
            default: return 0;                     // STRING / ARRAY / 未知
        }
    }

    /**
     * 读一个值。数组按元素类型**递归跳过**（不解析内容），这是读大 tokenizer 数组的关键。
     *
     * <p>注意：字符串数组**不能**整体前移跳过 —— 每个元素自带变长长度前缀，
     * 只能逐个读长度、再跳过内容（与官方示例的 skipValue 一致）。</p>
     */
    private static Object parseValue(java.io.InputStream in, int type) throws Exception {
        switch (type) {
            case 0: return (long) (readFully(in, 1)[0] & 0xFF);
            case 1: return (long) readFully(in, 1)[0];
            case 2: { byte[] b = readFully(in, 2); return (long) ((b[1] & 0xFF) << 8 | (b[0] & 0xFF)); }
            case 3: { byte[] b = readFully(in, 2); return (long) (short) ((b[1] & 0xFF) << 8 | (b[0] & 0xFF)); }
            case 4: return (long) readLEUInt32(in) & 0xFFFFFFFFL;
            case 5: return (long) readLEUInt32(in);
            case 6: return (double) Float.intBitsToFloat(readLEUInt32(in));
            case 7: return readFully(in, 1)[0] != 0;
            case 8: return readString(in);
            case 9: {
                int elemType = readLEUInt32(in);
                long n = readLELong(in);
                if (n < 0 || n > (1L << 32)) {
                    throw new java.io.EOFException("bad array len " + n);
                }
                int esz = fixedTypeSize(elemType);
                if (esz > 0) {
                    // 定长元素：一次跳过整段（避免逐元素 syscall 级开销）
                    skipFully(in, n * esz);
                } else if (elemType == 8) {
                    // 字符串数组：逐个读长度前缀再跳过内容（变长，无法整体前移）
                    for (long i = 0; i < n; i++) {
                        long len = readLELong(in);
                        if (len < 0) {
                            throw new java.io.EOFException("bad array string len");
                        }
                        skipFully(in, len);
                    }
                } else {
                    // 嵌套数组等罕见情况：回退递归
                    for (long i = 0; i < n; i++) {
                        parseValue(in, elemType);
                    }
                }
                return n;   // 只回报元素个数，不保留内容
            }
            case 10: return readLELong(in);
            case 11: return readLELong(in);
            case 12: return Double.longBitsToDouble(readLELong(in));
            default:
                throw new java.io.EOFException("unknown gguf type " + type);
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
