package com.oilquiz.app.ai.optimization;

import android.app.ActivityManager;
import android.content.Context;

import com.oilquiz.app.util.AILogger;

/**
 * ResourceConfig - 资源配置管理器
 * 
 * 解决以下问题：
 * 1. 线程数过多导致OOM和内存不连续
 * 2. GPU层数过高导致内存溢出
 * 3. 上下文大小计算不当导致崩溃
 * 
 * 基于 llama.cpp 官方建议和 Android 设备特性：
 * - 线程数：建议为 CPU 核心数的 50-75%，不超过 4 线程
 * - GPU 层：Android 设备建议不超过 30 层
 * - 上下文：需预留足够空间给单次推理
 */
public class ResourceConfig {
    private static final String TAG = "ResourceConfig";

    // ========== 线程数限制 ==========
    /** 最大线程数上限（Android 设备建议） */
    private static final int MAX_THREADS = 4;
    /** 最小线程数下限 */
    private static final int MIN_THREADS = 1;
    /** 线程数占 CPU 核心数的比例（50%） */
    private static final float THREAD_CORE_RATIO = 0.5f;

    // ========== GPU 层数限制 ==========
    /** GPU 层数上限（Qwen3-4B 共 36 层，全量上 GPU 避免混合推理的 CPU 瓶颈） */
    private static final int MAX_GPU_LAYERS = 36;
    /** GPU 层数下限 */
    private static final int MIN_GPU_LAYERS = 0;
    /** 单层模型权重估算大小（MB）- 7B模型约 400MB/层 */
    private static final long ESTIMATED_LAYER_SIZE_MB = 400;

    // ========== 上下文大小 ==========
    /** 上下文大小下限（保证单次推理） */
    private static final int MIN_CONTEXT_SIZE = 2048;
    /** 上下文大小上限（手机端封顶 16K：Qwen3-4B KV≈90KB/token，32K 纯 KV≈2.9GB，
     *  叠加权重后 6~8GB 设备加载即被杀进程；16K KV≈1.4GB 才是设备可承受的量级） */
    private static final int MAX_CONTEXT_SIZE = 16384;
    /** 默认上下文大小 */
    private static final int DEFAULT_CONTEXT_SIZE = 4096;
    /** 推理预留 token 数（输入 + 输出） */
    private static final int INFERENCE_RESERVE_TOKENS = 512;

    // ========== 内存限制 ==========
    /** 内存池大小上限（MB）（16K 上下文封顶后 KV≈1.4GB，2GB 池子足够并留余量；
     *  4GB 池会让 native 在低内存设备上按此预算分配 KV，反而推高峰值内存） */
    private static final int MAX_MEMORY_POOL_MB = 2048;
    /** 内存池大小下限（MB） */
    private static final int MIN_MEMORY_POOL_MB = 256;
    /** 系统内存保留比例（45%：内存池最多占可用内存 45%，仍留 55% 给系统；用户明确要求大上下文时放宽） */
    private static final float SYSTEM_MEM_RESERVE_RATIO = 0.45f;

    // ========== 批处理大小 ==========
    /** 批处理大小上限 */
    private static final int MAX_BATCH_SIZE = 256;
    /** 批处理大小下限 */
    private static final int MIN_BATCH_SIZE = 32;

    private final Context context;
    private final int cpuCores;
    private final long totalMemoryMB;
    private final long availableMemoryMB;

    public ResourceConfig(Context context) {
        this.context = context.getApplicationContext();
        this.cpuCores = Runtime.getRuntime().availableProcessors();
        
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(memInfo);
            this.totalMemoryMB = memInfo.totalMem / (1024 * 1024);
            this.availableMemoryMB = memInfo.availMem / (1024 * 1024);
        } else {
            this.totalMemoryMB = 4096; // 默认假设 4GB
            this.availableMemoryMB = 2048; // 默认假设 2GB 可用
        }

        AILogger.i(TAG, "Device info: CPU cores=" + cpuCores + 
                ", Total memory=" + totalMemoryMB + "MB" +
                ", Available memory=" + availableMemoryMB + "MB");
    }

    // ========== 线程数计算 ==========

    /**
     * 计算最优线程数
     * 
     * 基于 llama.cpp 官方建议：
     * - Android 设备建议线程数不超过 CPU 核心数的 50%
     * - 最大不超过 4 线程（避免内存竞争和 OOM）
     * - 小内存设备（<4GB）建议 2 线程
     * 
     * @return 推荐线程数
     */
    public int getOptimalThreadCount() {
        int threads;
        
        if (totalMemoryMB < 3072) {
            // 小内存设备（<3GB）：2 线程
            threads = 2;
            AILogger.i(TAG, "Low memory device (<3GB), using 2 threads");
        } else if (totalMemoryMB < 6144) {
            // 中等内存设备（3-6GB）：2-3 线程
            threads = Math.min(3, Math.max(2, (int) (cpuCores * THREAD_CORE_RATIO)));
            AILogger.i(TAG, "Medium memory device (3-6GB), using " + threads + " threads");
        } else {
            // 大内存设备（>6GB）：固定 3 线程（2 Prime + 1 Perf），留 1 核给系统/UI，
            // 避免 4 线程把大核全占导致发热降频、整机卡顿；prefill 由 n_threads_batch(+2) 承担。
            threads = Math.min(3, Math.max(3, (int) (cpuCores * THREAD_CORE_RATIO)));
            AILogger.i(TAG, "High memory device (>6GB), using " + threads + " threads (leave 1 core for system)");
        }

        // 确保在有效范围内
        threads = Math.max(MIN_THREADS, Math.min(MAX_THREADS, threads));
        
        AILogger.i(TAG, "Optimal thread count: " + threads + " (CPU cores=" + cpuCores + ")");
        return threads;
    }

    // ========== GPU 层数计算 ==========

    /**
     * 根据模型文件大小估算每层大小（MB）
     *
     * @param modelSizeMB 模型文件大小（MB）
     * @param totalLayers 模型总层数（估算）
     * @return 每层大小（MB）
     */
    public static long estimateLayerSizeMB(long modelSizeMB, int totalLayers) {
        if (totalLayers <= 0 || modelSizeMB <= 0) return ESTIMATED_LAYER_SIZE_MB;
        return modelSizeMB / totalLayers;
    }

    /**
     * 根据模型文件大小估算模型总层数
     *
     * @param modelSizeMB 模型文件大小（MB）
     * @return 估算的总层数
     */
    public static int estimateTotalLayers(long modelSizeMB) {
        // 常见模型架构的层数估算
        // 1B: ~22层, 3B: ~26层, 7B: ~32层, 13B: ~40层, 30B: ~60层, 70B: ~80层
        if (modelSizeMB < 500) return 22;       // <500MB: ~1B
        if (modelSizeMB < 1500) return 26;      // 500MB-1.5GB: ~3B
        if (modelSizeMB < 4000) return 40;      // 1.5GB-4GB: 3B-7B（Qwen3-4B 实为 36 层，估算放宽）
        if (modelSizeMB < 8000) return 40;      // 4GB-8GB: ~13B
        if (modelSizeMB < 18000) return 60;     // 8GB-18GB: ~30B
        return 80;                              // >18GB: ~70B
    }

    /**
     * 根据量化类型估算模型大小缩减因子
     *
     * @param modelPath 模型路径（从文件名推断量化类型）
     * @return 缩减因子（1.0 表示 FP16，0.5 表示 Q8，0.25 表示 Q4）
     */
    public static double getQuantizationFactor(String modelPath) {
        if (modelPath == null) return 1.0;
        String lower = modelPath.toLowerCase();

        // Q4 量化（最常见）
        if (lower.contains("q4_k_m") || lower.contains("q4_0") || lower.contains("q4_1") ||
            lower.contains("q4_k_s") || lower.contains("iq4_xxs") || lower.contains("iq4_xs")) {
            return 0.25; // 约 4.5 bits/weight
        }
        // Q5 量化
        if (lower.contains("q5_k_m") || lower.contains("q5_0") || lower.contains("q5_1") ||
            lower.contains("q5_k_s")) {
            return 0.31; // 约 5.5 bits/weight
        }
        // Q8 量化
        if (lower.contains("q8_0") || lower.contains("q8_k")) {
            return 0.5; // 约 8.5 bits/weight
        }
        // Q2 量化
        if (lower.contains("q2_k") || lower.contains("iq2_xxs") || lower.contains("iq2_xs")) {
            return 0.15; // 约 2.5 bits/weight
        }
        // Q3 量化
        if (lower.contains("q3_k") || lower.contains("iq3_xxs") || lower.contains("iq3_xs")) {
            return 0.2; // 约 3.5 bits/weight
        }

        return 1.0; // 默认 FP16
    }

    /**
     * 计算最优 GPU 层数
     *
     * Android 设备限制：
     * - 最大 30 层（避免 GPU 内存溢出）
     * - 需要考虑 KV 缓存占用的 GPU 内存
     * - 需要考虑单次分配大小限制（maxMemAllocSize）
     *
     * @param hasGpuSupport 是否支持 GPU 加速
     * @param gpuMemoryMB GPU 显存大小（MB），0 表示未知
     * @param maxMemAllocSizeMB GPU 单次最大分配大小（MB），0 表示未知
     * @param contextSize 上下文大小（用于估算 KV 缓存）
     * @param modelSizeMB 模型文件大小（MB），0 表示未知
     * @param modelPath 模型路径（用于推断量化类型）
     * @return 推荐 GPU 层数
     */
    public int getOptimalGpuLayers(boolean hasGpuSupport, long gpuMemoryMB, long maxMemAllocSizeMB,
                                   int contextSize, long modelSizeMB, String modelPath) {
        AILogger.i(TAG, "========== GPU LAYERS CALCULATION ==========");
        AILogger.i(TAG, "Input params:");
        AILogger.i(TAG, "  hasGpuSupport=" + hasGpuSupport);
        AILogger.i(TAG, "  gpuMemoryMB=" + gpuMemoryMB + "MB");
        AILogger.i(TAG, "  maxMemAllocSizeMB=" + maxMemAllocSizeMB + "MB");
        AILogger.i(TAG, "  contextSize=" + contextSize);
        AILogger.i(TAG, "  modelSizeMB=" + modelSizeMB + "MB");
        AILogger.i(TAG, "  modelPath=" + modelPath);

        if (!hasGpuSupport) {
            AILogger.i(TAG, "GPU not supported, using CPU only (layers=0)");
            return 0;
        }

        // 估算模型参数：优先用实际加载模型的层数（Qwen3-4B=36），未加载时按大小估算
        int totalLayers = 0;
        try {
            com.oilquiz.app.ai.jni.LlamaHelper.ModelMeta meta =
                    com.oilquiz.app.ai.jni.LlamaHelper.getModelMeta();
            if (meta != null && meta.nLayer > 0) {
                totalLayers = meta.nLayer;
                AILogger.i(TAG, "Using real model layers: " + totalLayers);
            }
        } catch (Throwable ignored) {
        }
        if (totalLayers <= 0) {
            totalLayers = estimateTotalLayers(modelSizeMB);
        }
        double quantFactor = getQuantizationFactor(modelPath);
        long layerSizeMB = estimateLayerSizeMB(modelSizeMB, totalLayers);

        // 每层实际大小：直接取 文件大小/层数，不再乘量化因子。
        // 关键：modelSizeMB 是 modelFile.length() 即量化后的真实文件大小（如 Q4 的 4.4GB），
        // 每层大小 = 文件/层数 已是"实际值"。若再乘 getQuantizationFactor()（Q4=0.25），
        // 会把 Q4 文件按 FP16 摊薄 4 倍 → 层数高估 4 倍 → 误判"4.4GB 模型可全量塞进
        // 4GB 显存"，实际 VRAM 溢出导致 GPU 驱动崩溃（Vulkan/OpenCL SIGABRT 高危）。
        long actualLayerSizeMB = layerSizeMB;

        AILogger.i(TAG, "Model estimation:");
        AILogger.i(TAG, "  totalLayers=" + totalLayers);
        AILogger.i(TAG, "  quantFactor=" + quantFactor);
        AILogger.i(TAG, "  layerSizeMB=" + layerSizeMB + "MB");
        AILogger.i(TAG, "  actualLayerSizeMB=" + actualLayerSizeMB + "MB");

        // 计算可用于模型权重的 GPU 内存
        // 策略：GPU 显存 × 60% 用于模型权重，40% 预留给 KV 缓存和系统开销
        // 这比精确计算 KV 缓存更实用，因为实际 KV 缓存取决于模型架构
        long usableGpuMemoryMB = gpuMemoryMB > 0 ? (long) (gpuMemoryMB * 0.6) : 0;

        AILogger.i(TAG, "Memory calculation:");
        AILogger.i(TAG, "  usableGpuMemoryMB=" + usableGpuMemoryMB + "MB (60% of total)");

        // 不再用 maxMemAllocSize 钳制可用预算：llama.cpp ggml-alloc 按
        // CL_DEVICE_MAX_MEM_ALLOC_SIZE（buffer_type get_max_size）自动把大 tensor
        // 拆成多个 OpenCL buffer，单次分配上限不构成总卸载量的硬约束。
        // 之前用 maxAlloc 硬卡导致 4B 模型只能卸载 23/36 层，剩余 13 层在 CPU
        // 上成为每 token 瓶颈（实测 CPU 374%、8-11 tok/s）。
        // 安全兜底仍保留：AIService 加载时按系统可用内存降级（10/15/20 层），
        // 模型加载失败自动切 CPU。手机 GPU 为共享内存架构，全量 offload 不增加
        // 内存总量（权重本就常驻），反而释放 CPU 给 UI。

        int gpuLayers;

        if (gpuMemoryMB <= 0) {
            // GPU 显存未知：保守估算。不能按系统总内存拍高层数——系统内存≠GPU显存，
            // 大内存+小显存设备按内存估 30 层会让 GPU 驱动过载（Vulkan/OpenCL 崩溃高危）。
            // 档位整体下调：8GB+→28、6-8GB→16、4-6GB→12、<4GB→8。
            if (totalMemoryMB >= 8192) {
                gpuLayers = 28;
            } else if (totalMemoryMB >= 6144) {
                gpuLayers = 16;
            } else if (totalMemoryMB >= 4096) {
                gpuLayers = 12;
            } else {
                gpuLayers = 8;
            }
            AILogger.i(TAG, "GPU memory unknown, conservative estimate " + gpuLayers + " layers based on system memory (" + totalMemoryMB + "MB)");
        } else {
            // 根据可用 GPU 内存计算
            if (usableGpuMemoryMB <= 0 || actualLayerSizeMB <= 0) {
                gpuLayers = 0;
                AILogger.w(TAG, "Not enough GPU memory for layers! usableGpuMemoryMB=" + usableGpuMemoryMB + ", actualLayerSizeMB=" + actualLayerSizeMB);
            } else {
                gpuLayers = (int) (usableGpuMemoryMB / actualLayerSizeMB);
                AILogger.i(TAG, "Calculated layers: " + gpuLayers + " = " + usableGpuMemoryMB + " / " + actualLayerSizeMB);
            }
        }

        // 确保不超过上限和模型总层数
        int finalLayers = Math.max(MIN_GPU_LAYERS, Math.min(Math.min(MAX_GPU_LAYERS, totalLayers), gpuLayers));

        // 小模型全量 offload：Adreno 8xx 等 SoC GPU 带宽充足，全量 GPU 消除 CPU-GPU 交替
        // 瓶颈（每 token 跨端同步是 decode 慢的主因）。显存支撑能力已由 usableGpuMemoryMB
        // 在上文钳制（Calculated layers → clamp totalLayers），不再额外砍 80% 层数。
        // 注：旧逻辑对小模型按 80% 层数削减（如 26→20），在本设备上导致 6 层 CPU 拖慢 decode，
        // 故移除。

        AILogger.i(TAG, "========== GPU LAYERS RESULT ==========");
        AILogger.i(TAG, "  Final layers: " + finalLayers + "/" + totalLayers);
        AILogger.i(TAG, "  (gpuMem=" + gpuMemoryMB + "MB, maxAlloc=" + maxMemAllocSizeMB + "MB, model=" + modelSizeMB + "MB)");
        AILogger.i(TAG, "========================================");

        return finalLayers;
    }

    /**
     * 兼容旧接口的计算最优 GPU 层数
     *
     * @deprecated 使用 {@link #getOptimalGpuLayers(boolean, long, long, int, long, String)} 代替
     */
    @Deprecated
    public int getOptimalGpuLayers(boolean hasGpuSupport, long gpuMemoryMB, long maxMemAllocSizeMB, int contextSize) {
        return getOptimalGpuLayers(hasGpuSupport, gpuMemoryMB, maxMemAllocSizeMB, contextSize, 0, null);
    }

    /**
     * 兼容旧接口的计算最优 GPU 层数
     *
     * @deprecated 使用 {@link #getOptimalGpuLayers(boolean, long, long, int, long, String)} 代替
     */
    @Deprecated
    public int getOptimalGpuLayers(boolean hasGpuSupport, long gpuMemoryMB) {
        return getOptimalGpuLayers(hasGpuSupport, gpuMemoryMB, 0, 4096, 0, null);
    }

    // ========== 上下文大小计算 ==========

    /**
     * 计算最优上下文大小
     * 
     * 考虑因素：
     * 1. 可用内存（上下文占用大量内存）
     * 2. 单次推理需求（输入 + 输出 tokens）
     * 3. 批处理大小（影响内存占用）
     * 
     * @param requestedContextSize 请求的上下文大小
     * @param batchSize 批处理大小
     * @return 安全的上下文大小
     */
    public int getOptimalContextSize(int requestedContextSize, int batchSize) {
        // 基础计算：确保能容纳单次推理
        int minRequired = MIN_CONTEXT_SIZE;
        int maxAllowed = MAX_CONTEXT_SIZE;

        // 按设备总内存定档（用 totalMemory 而非瞬时 availableMemory：可用内存随后台
        // 应用大幅波动，会导致同一设备上下文跳变；且 KV 缓存是常驻内存，必须按总内存规划）。
        // KV 成本参考（Qwen3-4B ≈ 90KB/token）：4K≈0.4GB、8K≈0.7GB、12K≈1.1GB、16K≈1.4GB。
        if (totalMemoryMB < 4096) {
            // 极小内存（<4GB）：4K
            maxAllowed = 4096;
            AILogger.i(TAG, "Very small device (<4GB total), max context=4096");
        } else if (totalMemoryMB < 6144) {
            // 小内存（4-6GB）：8K
            maxAllowed = 8192;
            AILogger.i(TAG, "Small device (4-6GB total), max context=8192");
        } else if (totalMemoryMB < 8192) {
            // 中内存（6-8GB）：12K
            maxAllowed = 12288;
            AILogger.i(TAG, "Mid device (6-8GB total), max context=12288");
        } else {
            // 大内存（≥8GB）：16K 封顶（不再放行 32K）
            maxAllowed = MAX_CONTEXT_SIZE;
            AILogger.i(TAG, "Large device (≥8GB total), max context=" + MAX_CONTEXT_SIZE);
        }

        // 确保上下文大小满足单次推理需求
        int effectiveSize = requestedContextSize;
        
        // 检查是否超过上限
        if (effectiveSize > maxAllowed) {
            AILogger.w(TAG, "Requested context " + effectiveSize + " exceeds max " + maxAllowed + ", adjusting");
            effectiveSize = maxAllowed;
        }
        
        // 检查是否低于下限
        if (effectiveSize < minRequired) {
            AILogger.w(TAG, "Requested context " + effectiveSize + " below min " + minRequired + ", adjusting");
            effectiveSize = minRequired;
        }

        // 确保预留足够的推理空间
        int contextWithReserve = effectiveSize + INFERENCE_RESERVE_TOKENS;
        if (contextWithReserve > maxAllowed) {
            // 预留空间后超出上限，减少上下文大小
            effectiveSize = Math.max(minRequired, maxAllowed - INFERENCE_RESERVE_TOKENS);
            AILogger.i(TAG, "Adjusted context to " + effectiveSize + " to reserve " + INFERENCE_RESERVE_TOKENS + " tokens for inference");
        }

        AILogger.i(TAG, "Optimal context size: " + effectiveSize + 
                " (requested=" + requestedContextSize + 
                ", min=" + minRequired + 
                ", max=" + maxAllowed + ")");
        return effectiveSize;
    }

    // ========== 内存池大小计算 ==========

    /**
     * 计算最优内存池大小
     * 
     * @param requestedSize 请求的内存池大小（MB）
     * @return 安全的内存池大小（MB）
     */
    public int getOptimalMemoryPoolSize(int requestedSize) {
        // 根据可用内存计算上限
        long maxSafePool = (long) (availableMemoryMB * SYSTEM_MEM_RESERVE_RATIO);
        maxSafePool = Math.min(maxSafePool, MAX_MEMORY_POOL_MB);
        maxSafePool = Math.max(maxSafePool, MIN_MEMORY_POOL_MB);

        int safeSize = (int) Math.min(requestedSize, maxSafePool);
        safeSize = Math.max(safeSize, MIN_MEMORY_POOL_MB);

        if (safeSize < requestedSize) {
            AILogger.w(TAG, "Memory pool reduced from " + requestedSize + "MB to " + safeSize + "MB for safety");
        }

        AILogger.i(TAG, "Optimal memory pool size: " + safeSize + "MB");
        return safeSize;
    }

    // ========== 批处理大小计算 ==========

    /**
     * 计算最优批处理大小
     * 
     * @param requestedSize 请求的批处理大小
     * @return 安全的批处理大小
     */
    public int getOptimalBatchSize(int requestedSize) {
        int safeSize = Math.max(MIN_BATCH_SIZE, Math.min(MAX_BATCH_SIZE, requestedSize));
        
        // 内存不足时减小批处理大小
        if (availableMemoryMB < 1024) {
            safeSize = Math.min(safeSize, 64);
            AILogger.i(TAG, "Low memory, reduced batch size to " + safeSize);
        }

        AILogger.i(TAG, "Optimal batch size: " + safeSize);
        return safeSize;
    }

    // ========== 完整配置计算 ==========

    /**
     * 计算完整的资源配置
     *
     * @param hasGpuSupport 是否支持 GPU
     * @param gpuMemoryMB GPU 显存大小
     * @param maxMemAllocSizeMB GPU 单次最大分配大小
     * @param requestedContextSize 请求的上下文大小
     * @param modelSizeMB 模型文件大小（MB），0 表示未知
     * @param modelPath 模型路径（用于推断量化类型）
     * @return 优化后的资源配置
     */
    public OptimalConfig calculateOptimalConfig(boolean hasGpuSupport, long gpuMemoryMB, long maxMemAllocSizeMB,
                                               int requestedContextSize, long modelSizeMB, String modelPath) {
        OptimalConfig config = new OptimalConfig();

        config.threadCount = getOptimalThreadCount();
        config.batchSize = getOptimalBatchSize(128);
        config.contextSize = getOptimalContextSize(requestedContextSize, config.batchSize);
        config.gpuLayers = getOptimalGpuLayers(hasGpuSupport, gpuMemoryMB, maxMemAllocSizeMB,
                config.contextSize, modelSizeMB, modelPath);
        config.memoryPoolMB = getOptimalMemoryPoolSize(512);

        AILogger.i(TAG, "=== Optimal Configuration ===");
        AILogger.i(TAG, "Threads: " + config.threadCount);
        AILogger.i(TAG, "GPU Layers: " + config.gpuLayers);
        AILogger.i(TAG, "Context Size: " + config.contextSize);
        AILogger.i(TAG, "Batch Size: " + config.batchSize);
        AILogger.i(TAG, "Memory Pool: " + config.memoryPoolMB + "MB");

        return config;
    }

    /**
     * 兼容旧接口的计算完整资源配置
     *
     * @deprecated 使用 {@link #calculateOptimalConfig(boolean, long, long, int, long, String)} 代替
     */
    @Deprecated
    public OptimalConfig calculateOptimalConfig(boolean hasGpuSupport, long gpuMemoryMB, long maxMemAllocSizeMB, int requestedContextSize) {
        return calculateOptimalConfig(hasGpuSupport, gpuMemoryMB, maxMemAllocSizeMB, requestedContextSize, 0, null);
    }

    /**
     * 最优配置数据类
     */
    public static class OptimalConfig {
        public int threadCount = 2;
        public int gpuLayers = 0;
        public int contextSize = 4096;
        public int batchSize = 128;
        public int memoryPoolMB = 512;

        @Override
        public String toString() {
            return "OptimalConfig{" +
                    "threads=" + threadCount +
                    ", gpuLayers=" + gpuLayers +
                    ", context=" + contextSize +
                    ", batch=" + batchSize +
                    ", memPool=" + memoryPoolMB + "MB" +
                    '}';
        }
    }
}
