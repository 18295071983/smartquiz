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
    /** GPU 层数上限（Android 设备建议） */
    private static final int MAX_GPU_LAYERS = 30;
    /** GPU 层数下限 */
    private static final int MIN_GPU_LAYERS = 0;
    /** 单层模型权重估算大小（MB）- 7B模型约 400MB/层 */
    private static final long ESTIMATED_LAYER_SIZE_MB = 400;

    // ========== 上下文大小 ==========
    /** 上下文大小下限（保证单次推理） */
    private static final int MIN_CONTEXT_SIZE = 2048;
    /** 上下文大小上限 */
    private static final int MAX_CONTEXT_SIZE = 8192;
    /** 默认上下文大小 */
    private static final int DEFAULT_CONTEXT_SIZE = 4096;
    /** 推理预留 token 数（输入 + 输出） */
    private static final int INFERENCE_RESERVE_TOKENS = 512;

    // ========== 内存限制 ==========
    /** 内存池大小上限（MB） */
    private static final int MAX_MEMORY_POOL_MB = 2048;
    /** 内存池大小下限（MB） */
    private static final int MIN_MEMORY_POOL_MB = 256;
    /** 系统内存保留比例（30%） */
    private static final float SYSTEM_MEM_RESERVE_RATIO = 0.3f;

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
            // 大内存设备（>6GB）：3-4 线程
            threads = Math.min(MAX_THREADS, Math.max(3, (int) (cpuCores * THREAD_CORE_RATIO)));
            AILogger.i(TAG, "High memory device (>6GB), using " + threads + " threads");
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
        if (modelSizeMB < 4000) return 32;      // 1.5GB-4GB: ~7B
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

        // 估算模型参数
        int totalLayers = estimateTotalLayers(modelSizeMB);
        double quantFactor = getQuantizationFactor(modelPath);
        long layerSizeMB = estimateLayerSizeMB(modelSizeMB, totalLayers);

        // 考虑量化后的实际每层大小
        long actualLayerSizeMB = (long) (layerSizeMB * quantFactor);

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

        // 考虑单次分配限制
        if (maxMemAllocSizeMB > 0 && usableGpuMemoryMB > maxMemAllocSizeMB) {
            usableGpuMemoryMB = maxMemAllocSizeMB;
            AILogger.i(TAG, "  Limited by maxMemAllocSize to " + maxMemAllocSizeMB + "MB");
        }

        int gpuLayers;

        if (gpuMemoryMB <= 0) {
            // GPU 显存未知，根据系统内存估算
            if (totalMemoryMB >= 8192) {
                gpuLayers = 30;
            } else if (totalMemoryMB >= 6144) {
                gpuLayers = 25;
            } else if (totalMemoryMB >= 4096) {
                gpuLayers = 20;
            } else if (totalMemoryMB >= 3072) {
                gpuLayers = 15;
            } else {
                gpuLayers = 10;
            }
            AILogger.i(TAG, "GPU memory unknown, estimated " + gpuLayers + " layers based on system memory (" + totalMemoryMB + "MB)");
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

        // 小模型（<3B）优化：减少 GPU 层数，避免 GPU 带宽瓶颈
        // 3B 模型建议使用 20-25 层，而不是全部层数
        if (totalLayers <= 26 && finalLayers > 25) {
            int suggestedLayers = (int)(totalLayers * 0.8); // 使用 80% 的层数
            AILogger.i(TAG, "Small model optimization: reducing GPU layers from " + finalLayers +
                    " to " + suggestedLayers + " (80% of " + totalLayers + " layers)");
            finalLayers = suggestedLayers;
        }

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

        // 根据可用内存调整上限
        if (availableMemoryMB < 1024) {
            // 内存极低：2048-4096
            maxAllowed = 4096;
            AILogger.i(TAG, "Very low available memory (<1GB), max context=4096");
        } else if (availableMemoryMB < 2048) {
            // 内存较低：2048-6144
            maxAllowed = 6144;
            AILogger.i(TAG, "Low available memory (<2GB), max context=6144");
        } else if (availableMemoryMB < 4096) {
            // 内存一般：2048-8192
            maxAllowed = 8192;
        } else {
            // 内存充足：使用默认上限
            maxAllowed = MAX_CONTEXT_SIZE;
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
