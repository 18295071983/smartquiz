// ============================================================================
// SYS-PROBE 实现（2026-11-08）。设计说明见 sys_probe.h 顶部。
// 本文件不包含 jni.h：JNI 只在 native-probe.cpp，便于在 host 侧单独编译/单测。
// ============================================================================

#include "sys_probe.h"

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <dirent.h>
#include <malloc.h>
#include <unistd.h>

#include "ggml-backend.h"

namespace sysprobe {
namespace {

// 读整个 /proc 文本文件。失败返回 false（调用方据此给 valid=false，而不是 0）。
bool readWholeFile(const char* path, std::string& out) {
    out.clear();
    FILE* f = fopen(path, "r");
    if (f == nullptr) {
        return false;
    }
    char buf[4096];
    size_t n = 0;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
        out.append(buf, n);
    }
    const bool ok = (ferror(f) == 0);
    fclose(f);
    return ok;
}

// "MemTotal:       11083012 kB" -> 取冒号后的第一个整数（kB）× 1024。
// 只用于 /proc/meminfo 与 /proc/self/status 的 Vm* 行。
uint64_t parseKbLine(const std::string& line) {
    const size_t colon = line.find(':');
    if (colon == std::string::npos) {
        return 0;
    }
    const char* p = line.c_str() + colon + 1;
    while (*p == ' ' || *p == '\t') {
        ++p;
    }
    return static_cast<uint64_t>(strtoull(p, nullptr, 10)) * 1024ull;
}

// 只取冒号后的第一个整数，不做 kB 换算（Threads: 12 这类无单位行）。
uint64_t parseRawLine(const std::string& line) {
    const size_t colon = line.find(':');
    if (colon == std::string::npos) {
        return 0;
    }
    const char* p = line.c_str() + colon + 1;
    while (*p == ' ' || *p == '\t') {
        ++p;
    }
    return static_cast<uint64_t>(strtoull(p, nullptr, 10));
}

// 键名必须精确匹配（避免 "Cached:" 命中 "SwapCached:"）。
bool lineKeyIs(const std::string& line, const char* key) {
    const size_t colon = line.find(':');
    if (colon == std::string::npos) {
        return false;
    }
    size_t end = colon;
    while (end > 0 && (line[end - 1] == ' ' || line[end - 1] == '\t')) {
        --end;
    }
    const size_t keyLen = std::strlen(key);
    return end == keyLen && line.compare(0, keyLen, key) == 0;
}

void forEachLine(const std::string& text, const std::function<void(const std::string&)>& fn) {
    size_t pos = 0;
    while (pos < text.size()) {
        size_t nl = text.find('\n', pos);
        if (nl == std::string::npos) {
            nl = text.size();
        }
        if (nl > pos) {
            fn(text.substr(pos, nl - pos));
        }
        pos = nl + 1;
    }
}

bool parsePressureLine(const std::string& line, float& avg10, float& avg60, float& avg300) {
    bool any = false;
    auto take = [&](const char* key, float& dst) {
        const char* p = std::strstr(line.c_str(), key);
        if (p != nullptr) {
            dst = std::strtof(p + std::strlen(key), nullptr);
            any = true;
        }
    };
    take("avg10=", avg10);
    take("avg60=", avg60);
    take("avg300=", avg300);
    return any;
}

} // namespace

uint64_t pageSizeBytes() {
    const long ps = sysconf(_SC_PAGESIZE);
    return ps > 0 ? static_cast<uint64_t>(ps) : 0;
}

MemoryInfo readMemoryInfo() {
    MemoryInfo info;
    info.pageSizeBytes = pageSizeBytes();

    std::string text;
    if (!readWholeFile("/proc/meminfo", text)) {
        return info;
    }
    forEachLine(text, [&](const std::string& line) {
        if (lineKeyIs(line, "MemTotal")) {
            info.memTotalBytes = parseKbLine(line);
        } else if (lineKeyIs(line, "MemAvailable")) {
            info.memAvailableBytes = parseKbLine(line);
        } else if (lineKeyIs(line, "MemFree")) {
            info.memFreeBytes = parseKbLine(line);
        } else if (lineKeyIs(line, "Cached")) {
            info.cachedBytes += parseKbLine(line);
        } else if (lineKeyIs(line, "SReclaimable")) {
            info.cachedBytes += parseKbLine(line);
        } else if (lineKeyIs(line, "SwapTotal")) {
            info.swapTotalBytes = parseKbLine(line);
        } else if (lineKeyIs(line, "SwapFree")) {
            info.swapFreeBytes = parseKbLine(line);
        }
    });
    info.valid = info.memTotalBytes > 0;
    return info;
}

SelfInfo readSelfInfo() {
    SelfInfo info;
    info.pageSizeBytes = pageSizeBytes();

    std::string status;
    if (readWholeFile("/proc/self/status", status)) {
        forEachLine(status, [&](const std::string& line) {
            if (lineKeyIs(line, "VmRSS")) {
                info.vmRssBytes = parseKbLine(line);
            } else if (lineKeyIs(line, "VmSize")) {
                info.vmSizeBytes = parseKbLine(line);
            } else if (lineKeyIs(line, "VmSwap")) {
                info.vmSwapBytes = parseKbLine(line);
            } else if (lineKeyIs(line, "Threads")) {
                info.threads = parseRawLine(line);
            }
        });
    }

    // statm：size resident shared text lib data dt（单位是**页**）。
    // 在 16 KB 页内核上，如果这里乘 4096，所有内存读数会差 4 倍——必须用 pageSize。
    std::string statm;
    if (readWholeFile("/proc/self/statm", statm)) {
        unsigned long long sizePages = 0;
        unsigned long long residentPages = 0;
        if (std::sscanf(statm.c_str(), "%llu %llu", &sizePages, &residentPages) == 2) {
            info.statmResidentBytes = residentPages * info.pageSizeBytes;
            if (info.vmRssBytes == 0) {
                info.vmRssBytes = info.statmResidentBytes;
            }
        }
    }

    DIR* dir = opendir("/proc/self/fd");
    if (dir != nullptr) {
        struct dirent* ent = nullptr;
        while ((ent = readdir(dir)) != nullptr) {
            if (std::strcmp(ent->d_name, ".") == 0 || std::strcmp(ent->d_name, "..") == 0) {
                continue;
            }
            ++info.fdCount;
        }
        closedir(dir);
    }

    info.valid = info.vmRssBytes > 0 || info.vmSizeBytes > 0;
    return info;
}

PressureInfo readPressure() {
    PressureInfo info;
    std::string text;
    if (!readWholeFile("/proc/pressure/memory", text)) {
        // 老内核无 PSI：明确 valid=false，绝不报假 0。
        return info;
    }
    forEachLine(text, [&](const std::string& line) {
        if (line.rfind("some ", 0) == 0) {
            info.somePresent = parsePressureLine(line, info.someAvg10, info.someAvg60, info.someAvg300);
        } else if (line.rfind("full ", 0) == 0) {
            info.fullPresent = parsePressureLine(line, info.fullAvg10, info.fullAvg60, info.fullAvg300);
        }
    });
    info.valid = info.somePresent || info.fullPresent;
    return info;
}

MallocInfo readMallocInfo() {
    MallocInfo info;
#if defined(__ANDROID__)
    // bionic 的 struct mallinfo2 与 mallinfo 同体；NDK 26 起可用。
    // malloc_trim 不在 NDK 的 malloc.h 里，这里不碰。
    struct mallinfo2 mi = mallinfo2();
    info.valid = true;
    info.arena = mi.arena;
    info.ordblks = mi.ordblks;
    info.hblkhd = mi.hblkhd;
    info.uordblks = mi.uordblks;
    info.fordblks = mi.fordblks;
    info.keepcost = mi.keepcost;
#endif
    return info;
}

std::vector<DeviceInfo> readDevices() {
    std::vector<DeviceInfo> out;
    const size_t count = ggml_backend_dev_count();
    out.reserve(count);
    for (size_t i = 0; i < count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev == nullptr) {
            continue;
        }
        DeviceInfo info;
        info.type = static_cast<int>(ggml_backend_dev_type(dev));
        const char* name = ggml_backend_dev_name(dev);
        const char* desc = ggml_backend_dev_description(dev);
        info.name = name != nullptr ? name : "";
        info.description = desc != nullptr ? desc : "";

        size_t freeBytes = 0;
        size_t totalBytes = 0;
        ggml_backend_dev_memory(dev, &freeBytes, &totalBytes);
        info.freeBytes = freeBytes;
        info.totalBytes = totalBytes;
        info.memoryKnown = totalBytes > 0;
        out.push_back(std::move(info));
    }
    return out;
}

EngineInfo probeEngine(const llama_model* model,
                       const llama_context* ctx,
                       int kvLayersHint) {
    EngineInfo info;
    info.hasModel = model != nullptr;
    info.hasCtx = ctx != nullptr;

    if (model != nullptr) {
        info.modelSizeBytes = llama_model_size(model);
        info.modelParams = llama_model_n_params(model);
        info.blockCount = llama_model_n_layer(model);
        info.headCountKv = llama_model_n_head_kv(model);
        const int32_t nHead = llama_model_n_head(model);
        const int32_t nEmbd = llama_model_n_embd(model);
        if (nHead > 0 && nEmbd > 0) {
            info.headDim = nEmbd / nHead;
        }
    }

    if (ctx != nullptr) {
        info.stateSizeBytes = llama_state_get_size(const_cast<llama_context*>(ctx));
        info.nCtx = llama_n_ctx(ctx);
        info.nCtxSeq = llama_n_ctx_seq(ctx);
        info.nBatch = llama_n_batch(ctx);
        info.nUbatch = llama_n_ubatch(ctx);
        info.nRsSeq = llama_n_rs_seq(ctx);

        llama_memory_t mem = llama_get_memory(ctx);
        if (mem != nullptr) {
            info.kvPosMin = llama_memory_seq_pos_min(mem, 0);
            info.kvPosMax = llama_memory_seq_pos_max(mem, 0);
            if (info.kvPosMax >= 0) {
                info.kvTokens = info.kvPosMax + 1;
            }
        }
    }

    // KV-REAL-LAYERS：优先用调用方（native-lib 的 countKvLayers）探测出的
    // "真正存 KV 的层数"；失败(0)时退回全部层——与 InferenceContext 的约定一致。
    info.kvLayers = kvLayersHint > 0 ? kvLayersHint : info.blockCount;
    if (info.kvLayers > 0 && info.headCountKv > 0 && info.headDim > 0) {
        // 2(K+V) × layers × n_head_kv × head_dim × 2 字节(F16)
        info.kvBytesPerToken = 2ull
                * static_cast<uint64_t>(info.kvLayers)
                * static_cast<uint64_t>(info.headCountKv)
                * static_cast<uint64_t>(info.headDim)
                * 2ull;
    }

    info.valid = info.hasModel || info.hasCtx;
    return info;
}

bool kvTrimTail(llama_context* ctx, int keepTokens) {
    if (ctx == nullptr || keepTokens < 0) {
        return false;
    }
    llama_memory_t mem = llama_get_memory(ctx);
    if (mem == nullptr) {
        return false;
    }
    // 删除 seq 0 的 [keepTokens, inf)。p1<0 表示到末尾。
    return llama_memory_seq_rm(mem, 0, keepTokens, -1);
}

bool kvClear(llama_context* ctx) {
    if (ctx == nullptr) {
        return false;
    }
    llama_memory_t mem = llama_get_memory(ctx);
    if (mem == nullptr) {
        return false;
    }
    // data=false：只清元数据；data=true 会连数据缓冲一起清，项目实测会触发
    // Vulkan/OpenCL 已释放缓冲的访问（signal 6）。
    llama_memory_clear(mem, false);
    return true;
}

} // namespace sysprobe
