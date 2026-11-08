#pragma once

// ============================================================================
// SYS-PROBE(2026-11-08)：系统 / 引擎实时事实的**唯一**采集层（纯函数，无 JNI）。
//
// 为什么要有这一层：内存、KV 占用、GPU 后端显存这些量，Java 侧过去只能拿
//   · ActivityManager.MemoryInfo.availMem（系统口径的瞬时快照，含"可回收"估算）
//   · /proc/self/maps 或模型文件长度（把文件大小当权重常驻）
//   · "GPU 总显存 × 60%"（对共享内存的手机 GPU 没有物理意义）
// 去近似。近似值一旦进入"要不要降上下文 / 砍 GPU 层"的决策，就会出现本机实测的
// 那种自相矛盾：11GB 设备、KV 只要 144MB，窗口却被压到 floor 4096。
//
// 本层只回答事实，不做策略：
//   · /proc 读数一律按 sysconf(_SC_PAGESIZE) 换算。Android 15 的 16 KB 页内核上
//     硬编码 4096 会让 statm 等"页数"读数差 4 倍；本文件没有 4096 页假设。
//   · /proc/pressure/memory 在未开 CONFIG_PSI 的老内核上**不存在**——那时返回
//     valid=false，而不是报一组假 0（假 0 会被上层读成"系统毫无压力"）。
//   · 引擎侧直接问 llama.cpp / ggml：权重字节数用 llama_model_size()（张量实际
//     字节，不是文件长度），KV 占用用 llama_memory_seq_pos_max()+1（真实位置，
//     不是 Java 侧 total_tokens_in_kv 记账），后端显存用 ggml_backend_dev_memory()。
//
// 并发约定：本层不持锁，调用方负责保证 ctx/model 生命周期。与既有
// nativeGetContextSize（native-lib.cpp）同一约定：在 native 侧把句柄
// reinterpret 后判 isValid() 再读；装载由 Java 侧 modelInitLock 串行化。
// ============================================================================

#include <cstdint>
#include <string>
#include <vector>

#include "llama.h"

namespace sysprobe {

// sysconf(_SC_PAGESIZE)：16 KB 页内核上为 16384。所有"页数"换算只准用它。
uint64_t pageSizeBytes();

// ---------------------------------------------------------------------------
// 系统内存（/proc/meminfo）
// ---------------------------------------------------------------------------
struct MemoryInfo {
    bool     valid = false;
    uint64_t memTotalBytes = 0;
    uint64_t memAvailableBytes = 0;   // 内核 MemAvailable；老内核缺失时为 0
    uint64_t memFreeBytes = 0;
    uint64_t cachedBytes = 0;         // Cached + SReclaimable
    uint64_t swapTotalBytes = 0;
    uint64_t swapFreeBytes = 0;
    uint64_t pageSizeBytes = 0;       // sysconf(_SC_PAGESIZE)，16 KB 内核上为 16384
};

MemoryInfo readMemoryInfo();

// ---------------------------------------------------------------------------
// 本进程（/proc/self/status + /proc/self/statm + /proc/self/fd）
// ---------------------------------------------------------------------------
struct SelfInfo {
    bool     valid = false;
    uint64_t vmRssBytes = 0;          // 进程常驻（mmap 已触碰的权重页也在内）
    uint64_t vmSizeBytes = 0;
    uint64_t vmSwapBytes = 0;
    uint64_t threads = 0;
    uint64_t fdCount = 0;
    // statm 的 resident 是**页数**，这里按 pageSize 换算；与 VmRSS 互为交叉校验
    // （两者口径差一点，差 4 倍就说明页大小取错了）。
    uint64_t statmResidentBytes = 0;
    uint64_t pageSizeBytes = 0;
};

SelfInfo readSelfInfo();

// ---------------------------------------------------------------------------
// 内存 PSI（/proc/pressure/memory）
// ---------------------------------------------------------------------------
struct PressureInfo {
    // false = 内核没有 PSI（文件不存在/不可读）；**不是**"压力为 0"。
    bool  valid = false;
    bool  somePresent = false;
    bool  fullPresent = false;
    float someAvg10 = 0, someAvg60 = 0, someAvg300 = 0;
    float fullAvg10 = 0, fullAvg60 = 0, fullAvg300 = 0;
};

PressureInfo readPressure();

// ---------------------------------------------------------------------------
// bionic malloc 统计（mallinfo2；NDK 26+ 的 malloc.h 有，malloc_trim 没有）
// ---------------------------------------------------------------------------
struct MallocInfo {
    bool     valid = false;
    uint64_t arena = 0;
    uint64_t ordblks = 0;
    uint64_t hblkhd = 0;
    uint64_t uordblks = 0;
    uint64_t fordblks = 0;
    uint64_t keepcost = 0;
};

MallocInfo readMallocInfo();

// ---------------------------------------------------------------------------
// ggml 后端设备（CPU / OpenCL / Vulkan / HTP …）
// ---------------------------------------------------------------------------
struct DeviceInfo {
    int          type = 0;            // ggml_backend_dev_type
    bool         memoryKnown = false; // false = 后端不报显存（CPU 常如此）
    uint64_t     freeBytes = 0;
    uint64_t     totalBytes = 0;
    std::string  name;
    std::string  description;
};

std::vector<DeviceInfo> readDevices();

// ---------------------------------------------------------------------------
// 活体引擎快照
// ---------------------------------------------------------------------------
struct EngineInfo {
    bool     valid = false;           // model 或 ctx 任一存在
    bool     hasModel = false;
    bool     hasCtx = false;

    // 权重：llama_model_size() 是张量字节数（量化后的实际占用），不是文件长度。
    uint64_t modelSizeBytes = 0;
    uint64_t modelParams = 0;

    // 会话状态大小（logits/embedding/memory 的总字节）
    uint64_t stateSizeBytes = 0;

    // 真实窗口与批次（就是 llama.cpp 内部值，不是 Java 档位）
    uint32_t nCtx = 0;
    uint32_t nCtxSeq = 0;
    uint32_t nBatch = 0;
    uint32_t nUbatch = 0;
    // llama_n_rs_seq(ctx)：循环层回滚快照数。0 = 不支持部分回滚（seq_rm 只能整段删）。
    // 混合模型（GDN/SSM）上这是 PARTIAL KV 复用失败的直接原因，必须实测而不是猜。
    uint32_t nRsSeq = 0;

    // seq 0 的真实 KV 位置（-1 = 空）。kvTokens = pos_max + 1。
    int32_t  kvPosMin = -1;
    int32_t  kvPosMax = -1;
    int32_t  kvTokens = 0;

    // KV 代价（F16 口径，与 GgufMeta.kvBytesPerToken 同公式）：
    //   2(K+V) × kvLayers × headKv × headDim × 2 字节
    // kvLayers 优先用调用方探测出的真实存 KV 层数（混合注意力只算全注意力层）。
    int32_t  kvLayers = 0;
    int32_t  blockCount = 0;
    int32_t  headCountKv = 0;
    int32_t  headDim = 0;
    uint64_t kvBytesPerToken = 0;
};

// @param kvLayersHint countKvLayers() 的结果；<=0 时回退为全部层数
EngineInfo probeEngine(const llama_model* model,
                       const llama_context* ctx,
                       int kvLayersHint);

// 尾部截断：删除 seq 0 中位置 >= keepTokens 的 KV。
// 这是"混合注意力只能尾部截断"场景的底层原语（整段重排/中间挖洞会被
// llama_memory_seq_rm 拒绝）。
bool kvTrimTail(llama_context* ctx, int keepTokens);

// 清空 seq 0 的 KV。data=false：只清元数据，不释放 GPU/OpenCL 的 KV 缓冲。
// 项目实测 data=true 会让后端释放缓冲区，下一次 decode 访问已释放缓冲触发
// signal 6（见 native-lib.cpp clearContextForInference 的注释），这里沿用软清。
bool kvClear(llama_context* ctx);

} // namespace sysprobe
