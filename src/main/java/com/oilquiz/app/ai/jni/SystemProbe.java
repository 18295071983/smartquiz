package com.oilquiz.app.ai.jni;

import com.oilquiz.app.util.AILogger;

/**
 * SYS-PROBE(2026-11-08)：系统 / 引擎实时事实的 Java 入口。
 *
 * <p>数据全部来自 native 侧的 {@code sys_probe.cpp}（纯函数，无 JNI）：
 * <ul>
 *   <li>系统内存：{@code /proc/meminfo}（MemTotal / <b>MemAvailable</b> / MemFree /
 *       Cached+SReclaimable / Swap），内核口径，不是 ActivityManager 的估算；</li>
 *   <li>本进程：{@code /proc/self/status}（VmRSS/VmSize/VmSwap/Threads）、
 *       {@code /proc/self/statm}（页数 × {@code sysconf(_SC_PAGESIZE)}，16 KB 页内核上
 *       不会差 4 倍）、{@code /proc/self/fd} 计数、{@code mallinfo2}；</li>
 *   <li>内存压力：{@code /proc/pressure/memory}（PSI）；老内核没有该文件时
 *       {@link #pressureAvailable()} 返回 false，<b>不是</b>压力为 0；</li>
 *   <li>引擎：{@code llama_model_size()}（张量实际字节，不是文件长度）、
 *       {@code llama_n_ctx()}（真窗口）、{@code llama_memory_seq_pos_max()+1}
 *       （真 KV 占用）、按真实 KV 层数折算的 B/token；</li>
 *   <li>后端设备：{@code ggml_backend_dev_memory()} 的逐设备 free/total，
 *       替代 Java 侧"GPU 总显存 × 60%"。</li>
 * </ul>
 *
 * <p>返回值一律是 {@code long[]} 批量（索引常量见下），不在 native 侧做
 * JSON/对象序列化——这些读数会被加载决策反复取。</p>
 *
 * <p>native 库不可用（纯 JVM 单测、加载失败）时，所有读数返回 0/false，
 * 调用方据此回退到原有路径，不抛异常。</p>
 */
public final class SystemProbe {

    private static final String TAG = "SystemProbe";
    private static final long MB = 1024L * 1024L;

    // ===== nativeProbeMemory() 索引 =====
    public static final int MEM_VALID = 0;
    public static final int MEM_TOTAL_BYTES = 1;
    public static final int MEM_AVAILABLE_BYTES = 2;
    public static final int MEM_FREE_BYTES = 3;
    public static final int MEM_CACHED_BYTES = 4;
    public static final int MEM_SWAP_TOTAL_BYTES = 5;
    public static final int MEM_SWAP_FREE_BYTES = 6;
    public static final int MEM_PAGE_SIZE_BYTES = 7;
    private static final int MEM_LEN = 8;

    // ===== nativeProbeSelf() 索引 =====
    public static final int SELF_VALID = 0;
    public static final int SELF_RSS_BYTES = 1;
    public static final int SELF_VM_SIZE_BYTES = 2;
    public static final int SELF_VM_SWAP_BYTES = 3;
    public static final int SELF_THREADS = 4;
    public static final int SELF_FD_COUNT = 5;
    public static final int SELF_STATM_RSS_BYTES = 6;
    private static final int SELF_LEN = 7;

    // ===== nativeProbePressure() 索引（值 ×100 存整数）=====
    public static final int PRESSURE_VALID = 0;
    public static final int PRESSURE_SOME_AVG10 = 1;
    public static final int PRESSURE_SOME_AVG60 = 2;
    public static final int PRESSURE_SOME_AVG300 = 3;
    public static final int PRESSURE_FULL_AVG10 = 4;
    public static final int PRESSURE_FULL_AVG60 = 5;
    public static final int PRESSURE_FULL_AVG300 = 6;
    private static final int PRESSURE_LEN = 7;

    // ===== nativeProbeMalloc() 索引 =====
    public static final int MALLOC_VALID = 0;
    public static final int MALLOC_ARENA = 1;
    public static final int MALLOC_ORD_BLKS = 2;
    public static final int MALLOC_HBLKHD = 3;
    public static final int MALLOC_UORD_BLKS = 4;
    public static final int MALLOC_FORD_BLKS = 5;
    public static final int MALLOC_KEEP_COST = 6;
    private static final int MALLOC_LEN = 7;

    // ===== nativeProbeEngine() 索引 =====
    public static final int ENGINE_VALID = 0;
    public static final int ENGINE_HAS_MODEL = 1;
    public static final int ENGINE_HAS_CTX = 2;
    public static final int ENGINE_MODEL_SIZE_BYTES = 3;
    public static final int ENGINE_MODEL_PARAMS = 4;
    public static final int ENGINE_STATE_SIZE_BYTES = 5;
    public static final int ENGINE_N_CTX = 6;
    public static final int ENGINE_N_CTX_SEQ = 7;
    public static final int ENGINE_N_BATCH = 8;
    public static final int ENGINE_N_UBATCH = 9;
    public static final int ENGINE_KV_POS_MIN = 10;
    public static final int ENGINE_KV_POS_MAX = 11;
    public static final int ENGINE_KV_TOKENS = 12;
    public static final int ENGINE_KV_LAYERS = 13;
    public static final int ENGINE_BLOCK_COUNT = 14;
    public static final int ENGINE_HEAD_COUNT_KV = 15;
    public static final int ENGINE_HEAD_DIM = 16;
    public static final int ENGINE_KV_BYTES_PER_TOKEN = 17;
    private static final int ENGINE_LEN = 18;

    private static final boolean LIB_LOADED;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("llama-jni");
            ok = true;
        } catch (Throwable t) {
            AILogger.w(TAG, "llama-jni 未加载，SystemProbe 读数全部回退为 0: " + t);
        }
        LIB_LOADED = ok;
    }

    private SystemProbe() {
    }

    public static boolean isAvailable() {
        return LIB_LOADED;
    }

    // ===== 原始批量读数（失败时返回全 0 数组，绝不返回 null）=====

    public static long[] memory() {
        if (!LIB_LOADED) {
            return new long[MEM_LEN];
        }
        try {
            long[] a = nativeProbeMemory();
            return a != null ? a : new long[MEM_LEN];
        } catch (Throwable t) {
            AILogger.w(TAG, "probeMemory failed: " + t);
            return new long[MEM_LEN];
        }
    }

    public static long[] self() {
        if (!LIB_LOADED) {
            return new long[SELF_LEN];
        }
        try {
            long[] a = nativeProbeSelf();
            return a != null ? a : new long[SELF_LEN];
        } catch (Throwable t) {
            AILogger.w(TAG, "probeSelf failed: " + t);
            return new long[SELF_LEN];
        }
    }

    public static long[] pressure() {
        if (!LIB_LOADED) {
            return new long[PRESSURE_LEN];
        }
        try {
            long[] a = nativeProbePressure();
            return a != null ? a : new long[PRESSURE_LEN];
        } catch (Throwable t) {
            AILogger.w(TAG, "probePressure failed: " + t);
            return new long[PRESSURE_LEN];
        }
    }

    public static long[] mallocInfo() {
        if (!LIB_LOADED) {
            return new long[MALLOC_LEN];
        }
        try {
            long[] a = nativeProbeMalloc();
            return a != null ? a : new long[MALLOC_LEN];
        } catch (Throwable t) {
            AILogger.w(TAG, "probeMalloc failed: " + t);
            return new long[MALLOC_LEN];
        }
    }

    public static long[] engine() {
        if (!LIB_LOADED) {
            return new long[ENGINE_LEN];
        }
        try {
            long[] a = nativeProbeEngine();
            return a != null ? a : new long[ENGINE_LEN];
        } catch (Throwable t) {
            AILogger.w(TAG, "probeEngine failed: " + t);
            return new long[ENGINE_LEN];
        }
    }

    public static long[] devices() {
        if (!LIB_LOADED) {
            return new long[]{0L};
        }
        try {
            long[] a = nativeProbeDevices();
            return a != null ? a : new long[]{0L};
        } catch (Throwable t) {
            AILogger.w(TAG, "probeDevices failed: " + t);
            return new long[]{0L};
        }
    }

    public static String[] deviceNames() {
        if (!LIB_LOADED) {
            return new String[0];
        }
        try {
            String[] a = nativeProbeDeviceNames();
            return a != null ? a : new String[0];
        } catch (Throwable t) {
            AILogger.w(TAG, "probeDeviceNames failed: " + t);
            return new String[0];
        }
    }

    // ===== 语义化取值（调用方最常用）=====

    /** 内核口径的可用内存（MemAvailable）；读不到返回 0，调用方回退旧路径。 */
    public static long memAvailableBytes() {
        long[] a = memory();
        return a[MEM_VALID] != 0 ? a[MEM_AVAILABLE_BYTES] : 0;
    }

    public static long memAvailableMB() {
        return memAvailableBytes() / MB;
    }

    public static long memTotalMB() {
        long[] a = memory();
        return a[MEM_VALID] != 0 ? a[MEM_TOTAL_BYTES] / MB : 0;
    }

    public static long processRssBytes() {
        long[] a = self();
        return a[SELF_VALID] != 0 ? a[SELF_RSS_BYTES] : 0;
    }

    public static long processRssMB() {
        return processRssBytes() / MB;
    }

    /** true 表示内核提供 PSI；false 时 pressure 读数没有意义（不是 0）。 */
    public static boolean pressureAvailable() {
        return pressure()[PRESSURE_VALID] != 0;
    }

    /** 当前活体模型的权重字节数（llama_model_size）；未加载返回 0。 */
    public static long loadedModelSizeBytes() {
        long[] a = engine();
        return a[ENGINE_VALID] != 0 ? a[ENGINE_MODEL_SIZE_BYTES] : 0;
    }

    /** 真实 n_ctx（llama_n_ctx）；未加载返回 0。 */
    public static int engineContextSize() {
        long[] a = engine();
        return a[ENGINE_VALID] != 0 ? (int) a[ENGINE_N_CTX] : 0;
    }

    /** 真实 KV 占用 token（seq 0 的 pos_max+1）；空/未加载返回 0。 */
    public static int engineKvTokens() {
        long[] a = engine();
        return a[ENGINE_VALID] != 0 ? (int) a[ENGINE_KV_TOKENS] : 0;
    }

    public static long engineKvBytesPerToken() {
        long[] a = engine();
        return a[ENGINE_VALID] != 0 ? a[ENGINE_KV_BYTES_PER_TOKEN] : 0;
    }

    /** seq 0 尾部截断，保留前 keepTokens 个位置。用于"混合注意力只能尾部截断"。 */
    public static boolean trimKvTail(int keepTokens) {
        if (!LIB_LOADED || keepTokens < 0) {
            return false;
        }
        try {
            return nativeKvTrimTail(keepTokens);
        } catch (Throwable t) {
            AILogger.w(TAG, "kvTrimTail failed: " + t);
            return false;
        }
    }

    /** 软清空 seq 0 的 KV（不释放 GPU 缓冲，避免 signal 6）。 */
    public static boolean clearKv() {
        if (!LIB_LOADED) {
            return false;
        }
        try {
            return nativeKvClear();
        } catch (Throwable t) {
            AILogger.w(TAG, "kvClear failed: " + t);
            return false;
        }
    }

    /** 一行式摘要，供状态页/日志用；不参与决策。 */
    public static String describeEngine() {
        long[] a = engine();
        if (a[ENGINE_VALID] == 0) {
            return "engine: none";
        }
        return "engine: weights=" + (a[ENGINE_MODEL_SIZE_BYTES] / MB) + "MB"
                + " params=" + a[ENGINE_MODEL_PARAMS]
                + " nCtx=" + a[ENGINE_N_CTX]
                + " kv=" + a[ENGINE_KV_TOKENS] + "/" + a[ENGINE_N_CTX]
                + " kvPos=[" + a[ENGINE_KV_POS_MIN] + "," + a[ENGINE_KV_POS_MAX] + "]"
                + " kvLayers=" + a[ENGINE_KV_LAYERS] + "/" + a[ENGINE_BLOCK_COUNT]
                + " kvBytes/token=" + a[ENGINE_KV_BYTES_PER_TOKEN]
                + " state=" + (a[ENGINE_STATE_SIZE_BYTES] / MB) + "MB";
    }

    public static String describeMemory() {
        long[] m = memory();
        long[] s = self();
        if (m[MEM_VALID] == 0) {
            return "mem: unavailable";
        }
        return "mem: total=" + (m[MEM_TOTAL_BYTES] / MB) + "MB"
                + " avail=" + (m[MEM_AVAILABLE_BYTES] / MB) + "MB"
                + " free=" + (m[MEM_FREE_BYTES] / MB) + "MB"
                + " cache=" + (m[MEM_CACHED_BYTES] / MB) + "MB"
                + " swap=" + (m[MEM_SWAP_FREE_BYTES] / MB) + "/" + (m[MEM_SWAP_TOTAL_BYTES] / MB) + "MB"
                + " pageSize=" + m[MEM_PAGE_SIZE_BYTES]
                + " | self rss=" + (s[SELF_RSS_BYTES] / MB) + "MB"
                + " fds=" + s[SELF_FD_COUNT] + " threads=" + s[SELF_THREADS];
    }

    // ===== JNI（name-based，与 LlamaHelper 一致；库均为 llama-jni）=====

    private static native long[] nativeProbeMemory();

    private static native long[] nativeProbeSelf();

    private static native long[] nativeProbePressure();

    private static native long[] nativeProbeMalloc();

    private static native long[] nativeProbeEngine();

    private static native long[] nativeProbeDevices();

    private static native String[] nativeProbeDeviceNames();

    private static native boolean nativeKvTrimTail(int keepTokens);

    private static native boolean nativeKvClear();
}
