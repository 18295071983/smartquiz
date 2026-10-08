package com.oilquiz.app.ai.model;

import java.io.File;

/**
 * GGUF 头部元数据（架构信息卡片 + NPU 上下文预算规划用）。
 *
 * <p>数据来源是 **llama.cpp / ggml 自带的官方 GGUF 读取 API**
 * （{@code gguf_init_from_file} 等，经 JNI {@code nativeReadGgufMeta} 调用），
 * 不再由 Java 侧手写解析器读取。</p>
 *
 * <p><b>为什么改为官方实现</b>：Java 手写解析器先后出过"读取位置漂移 → EOFException →
 * {@link #read(File)} 返回 null"，而 catch 又静默吞异常。后果是 NPU 的 KV 估算拿不到模型规格
 * （{@code kvBytesPerToken} 返回 0）→ 上下文规划走 4096 兜底 → Agent 每轮工具结果被裁掉，
 * 表现为"工具调用了但结果像是没回传"。改用官方 API 后，格式兼容性跟着库一起升级，
 * 也不再需要自己维护解析逻辑。</p>
 *
 * <p>原来读这些字段是为了：状态页在**未加载模型**时也能显示参数量/层数/注意力头
 * （NPU 模式下本地服务是空的，否则一直显示"未加载"）。native 侧已有的
 * {@code llama_model_meta_val_str} 需要**已加载的 llama_model**，NPU 规划上下文时模型尚未加载，
 * 所以用不依赖加载的 {@code gguf_init_from_file}(no_alloc) 这条路。</p>
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
    /** 张量总数（官方 API 顺带给出） */
    public long tensorCount = 0;

    /** native 侧字段索引，与 nativeReadGgufMeta 的返回顺序一一对应 */
    private static final int IDX_BLOCK_COUNT = 1;
    private static final int IDX_HEAD_COUNT = 2;
    private static final int IDX_HEAD_COUNT_KV = 3;
    private static final int IDX_EMBEDDING_LENGTH = 4;
    private static final int IDX_CONTEXT_LENGTH = 5;
    private static final int IDX_KEY_LENGTH = 6;
    private static final int IDX_FULL_ATTENTION_INTERVAL = 7;
    private static final int IDX_HAS_LINEAR_ATTENTION = 8;
    private static final int IDX_PARAMETER_COUNT = 9;
    private static final int IDX_TENSOR_COUNT = 10;

    /**
     * 读取 GGUF 元数据。
     *
     * <p>结果按"路径 + 大小 + 修改时间"缓存：该解析会读入 GGUF 的整个元数据区
     * （tokenizer 十万级词表，实测约 0.5 秒），而调用方中 {@code planForCurrentModel}
     * 在**每次请求**都会问一次，重复解析纯属浪费。</p>
     *
     * @return 解析失败（文件不存在/非 GGUF/native 不可用）返回 null，调用方照旧显示占位
     */
    public static GgufMeta read(File f) {
        if (f == null || !f.isFile()) {
            return null;
        }
        String path = f.getAbsolutePath();
        long size = f.length();
        long mtime = f.lastModified();

        CacheEntry hit = cacheGet(path, size, mtime);
        if (hit != null) {
            return hit.meta;   // meta 为 null 表示"上次也解析失败"，同样命中缓存避免反复尝试
        }

        GgufMeta m = loadFromNative(path);
        cachePut(path, size, mtime, m);
        return m;
    }

    /**
     * 与 {@link #read(File)} 同逻辑但不走缓存，并且**不吞异常**。
     *
     * <p>供诊断用（异常在 {@link #read(File)} 里被吞掉时无法定位）。</p>
     */
    public static GgufMeta readOrThrow(File f) throws Exception {
        if (f == null || !f.isFile()) {
            return null;
        }
        GgufMeta m = loadFromNative(f.getAbsolutePath());
        if (m == null) {
            throw new java.io.IOException("nativeReadGgufMeta 返回空（非 GGUF 或 native 不可用）");
        }
        return m;
    }

    /**
     * 调用 native 侧读取（实现在 LlamaHelper.nativeReadGgufMeta —— 与其余 native 方法
     * 放在同一个类，因为库是在那里 System.loadLibrary 加载的）。
     */
    private static long[] callNative(String path) {
        return com.oilquiz.app.ai.jni.LlamaHelper.nativeReadGgufMeta(path);
    }

    private static GgufMeta loadFromNative(String path) {
        long[] v;
        try {
            v = callNative(path);
        } catch (Throwable t) {
            // GGUF-PARSE-DIAG：解析失败必须可见（原先静默返回 null，查了很久）
            android.util.Log.w("GgufMeta", "nativeReadGgufMeta 调用失败: " + path, t);
            return null;
        }
        if (v == null || v.length < 11) {
            android.util.Log.w("GgufMeta", "nativeReadGgufMeta 返回无效结果: " + path);
            return null;
        }
        GgufMeta m = new GgufMeta();
        // native 侧用 -1 表示"该键不存在"，与"值为 0"区分开
        m.blockCount = nonNegative(v[IDX_BLOCK_COUNT]);
        m.headCount = nonNegative(v[IDX_HEAD_COUNT]);
        m.headCountKv = nonNegative(v[IDX_HEAD_COUNT_KV]);
        m.embeddingLength = nonNegative(v[IDX_EMBEDDING_LENGTH]);
        m.contextLength = nonNegative(v[IDX_CONTEXT_LENGTH]);
        m.headLength = nonNegative(v[IDX_KEY_LENGTH]);
        m.fullAttentionInterval = nonNegative(v[IDX_FULL_ATTENTION_INTERVAL]);
        m.hasLinearAttention = v[IDX_HAS_LINEAR_ATTENTION] == 1;
        m.parameterCount = nonNegative(v[IDX_PARAMETER_COUNT]);
        m.tensorCount = nonNegative(v[IDX_TENSOR_COUNT]);
        // 11 个固定字段之后是张量类型直方图，成对 [ggmlType, count]（HTP 兼容性提示用）
        for (int i = 11; i + 1 < v.length; i += 2) {
            int type = (int) v[i];
            int count = (int) v[i + 1];
            if (count > 0) {
                m.tensorTypeCounts.merge(type, count, Integer::sum);
            }
        }
        return m;
    }

    private static long nonNegative(long v) {
        return v > 0 ? v : 0;
    }

    // ---------------- 结果缓存 ----------------

    private static final class CacheEntry {
        final long size;
        final long mtime;
        final GgufMeta meta;

        CacheEntry(long size, long mtime, GgufMeta meta) {
            this.size = size;
            this.mtime = mtime;
            this.meta = meta;
        }
    }

    private static final int CACHE_MAX = 8;
    private static final java.util.LinkedHashMap<String, CacheEntry> CACHE =
            new java.util.LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, CacheEntry> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    private static synchronized CacheEntry cacheGet(String path, long size, long mtime) {
        CacheEntry e = CACHE.get(path);
        if (e != null && e.size == size && e.mtime == mtime) {
            return e;
        }
        return null;
    }

    private static synchronized void cachePut(String path, long size, long mtime, GgufMeta meta) {
        CACHE.put(path, new CacheEntry(size, mtime, meta));
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

    /**
     * KV-REAL-LAYERS(2026-10-09)：**真正会存 KV cache 的层数**。
     *
     * <p>背景（实测）：混合注意力模型并非每层都存 KV。Qwen3.5-2B 的 GGUF 里
     * {@code qwen35.full_attention_interval = 4}，即每 4 层只有 1 层是全注意力层，
     * 其余 3 层是 SSM/GDN 线性层（{@code qwen35.ssm.*}，固定尺寸状态、不随 token 增长）。
     * 佐证：llama.cpp 日志对 Qwen3.5-2B 打印
     * {@code llama_kv_cache: size = 96.00 MiB (8192 cells, 6 layers)} —— 24 层里只有 6 层有 KV。</p>
     *
     * <p>为什么必须折算：按"全部层数"估算 KV 会**高估 4 倍**（Qwen3.5-2B：49,152 → 真实 12,288 B/token），
     * 后果是上下文预算被过早收紧、内存池过早钳制 n_ctx。
     * 而 MiniCPM5-2B（纯 LLaMA 架构，无该键）42 层全部存 KV，按全层数算才正确。</p>
     *
     * <p>对齐 llama.cpp 的判定：{@code llama_hparams::has_kv(il)} 用
     * {@code n_layer_kv_from_start}（非负时只算前 N 层）。此处按 interval 折算层数，
     * 与日志实测的 6 层一致（{@code ceil(24/4) = 6}）。</p>
     *
     * @return 存 KV 的层数；无法判断时返回 blockCount（保守：按全部层数算）
     */
    public long kvLayerCount() {
        if (blockCount <= 0) {
            return 0;
        }
        if (fullAttentionInterval > 1) {
            // 每 fullAttentionInterval 层一个全注意力层（向上取整，避免少算）
            long n = (blockCount + fullAttentionInterval - 1) / fullAttentionInterval;
            return Math.max(1, Math.min(blockCount, n));
        }
        return blockCount;
    }

    /**
     * KV-REAL-LAYERS：每 token 的 KV cache 字节数（F16，每元素 2 字节）。
     *
     * <p>{@code KV/token = 2(K+V) × kvLayerCount × n_head_kv × head_dim × 2}</p>
     *
     * @return 字节数；信息不足时返回 0
     */
    public long kvBytesPerToken() {
        long layers = kvLayerCount();
        if (layers <= 0 || headCountKv <= 0 || headLength <= 0) {
            return 0;
        }
        return 2L * layers * headCountKv * headLength * 2L;
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
        int npuFriendly = 0;
        int cpuOnly = 0;
        for (java.util.Map.Entry<Integer, Integer> e : tensorTypeCounts.entrySet()) {
            if (isHtpFriendly(e.getKey())) {
                npuFriendly += e.getValue();
            } else {
                cpuOnly += e.getValue();
            }
        }
        if (cpuOnly == 0) {
            return "量化类型对 Hexagon NPU 友好（可全量走 HTP）";
        }
        if (npuFriendly == 0) {
            return "量化类型不是 NPU 友好型，可能整体退到 CPU 推理";
        }
        return "部分张量为 NPU 不友好类型（" + cpuOnly + " 个），可能部分退到 CPU";
    }

    /** ggml type id 是否在 HTP 支持范围内（Q4_0=2 / Q4_1=3 / Q8_0=8 / IQ4_NL=20 / MXFP4=39 / F16=1 / F32=0） */
    private static boolean isHtpFriendly(int type) {
        switch (type) {
            case 0:   // F32
            case 1:   // F16
            case 2:   // Q4_0
            case 3:   // Q4_1
            case 8:   // Q8_0
            case 20:  // IQ4_NL
            case 39:  // MXFP4
                return true;
            default:
                return false;
        }
    }
}
