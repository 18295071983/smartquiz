#include <jni.h>
#include <string>
#include <sstream>
#include <memory>
#include <android/log.h>
#include <chrono>
#include <thread>
#include <vector>
#include <mutex>
#include <atomic>
#include <sys/resource.h>
#include <unistd.h>
#include <stdarg.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <sys/system_properties.h>
#include "llama.h"
#include "ggml-backend.h"
// gguf.h：官方 GGUF 读取 API（gguf_init_from_file / gguf_find_key / gguf_get_val_* 等）。
// 已在 libllama-jni.so 中导出，用于 nativeReadGgufMeta（替代 Java 侧手写解析器）。
#include "gguf.h"
#include <cctype>
#include "chat.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "agent_kv_cache.h"
#include <nlohmann/json.hpp>
#include <vulkan/vulkan.h>

// 组3.15：致命信号兜底——接住 llama 内部 abort 不自杀，返回错误码
#include <signal.h>
#include <setjmp.h>

static thread_local sigjmp_buf fatal_jmp_buf;
static thread_local bool has_jmp_set = false;

static void fatal_signal_handler(int sig) {
    if (has_jmp_set) {
        siglongjmp(fatal_jmp_buf, sig);
    }
    // 如果没有设置 jmp，恢复默认处理
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_fatal_signal_handlers() {
    struct sigaction sa;
    sa.sa_handler = fatal_signal_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGBUS, &sa, nullptr);
    sigaction(SIGILL, &sa, nullptr);
}

// ========== 组3.15 新增：推理安全执行辅助 ==========
// 统一的 sigsetjmp 包裹宏，用于所有推理 JNI 方法（nativeGenerateStream / Bytes / FromMessages / runPureInference）
// 用法：SAFE_RUN_INFERENCE(jni_env, callback_object, on_error_method, inference_body)
//   - 自动设置 sigsetjmp / has_jmp_set / fatal_jmp_buf
//   - 信号触发时回调 onError("Native crash: signal X") 并返回
//   - 正常路径保持 has_jmp_set=false，不影响信号处理器行为
//   - EnsureLocalCapacity 预留 256 局部引用槽位，防止信号跳转后局部引用泄漏
#define SAFE_RUN_INFERENCE(env, callback, onErrorMethod, inference_block) \
    do { \
        (env)->EnsureLocalCapacity(256); \
        has_jmp_set = true; \
        int _sig = sigsetjmp(fatal_jmp_buf, 1); \
        if (_sig != 0) { \
            has_jmp_set = false; \
            LOGE("Fatal signal %d during inference", _sig); \
            /* 崩溃恢复：siglongjmp 跳过了 generateStream 内的 RAII guard，\
               isGenerating 会永久卡在 true，必须在此强制复位，否则后续所有推理都被拒绝 */ \
            if (s_helperContext != nullptr && s_helperContext->isCurrentlyGenerating()) { \
                s_helperContext->forceResetGeneration(); \
                LOGI("isGenerating flag reset after crash signal %d", _sig); \
            } \
            if ((onErrorMethod) != nullptr && (callback) != nullptr) { \
                std::string _errMsg = "Native inference crashed (signal " + std::to_string(_sig) + ")"; \
                jstring _errStr = (env)->NewStringUTF(_errMsg.c_str()); \
                if (_errStr != nullptr) { \
                    (env)->CallVoidMethod((callback), (onErrorMethod), _errStr); \
                    (env)->DeleteLocalRef(_errStr); \
                } \
            } \
            return; \
        } \
        inference_block; \
        has_jmp_set = false; \
    } while (0)

#define CL_TARGET_OPENCL_VERSION 300
typedef int cl_int;
typedef unsigned int cl_uint;
typedef unsigned long cl_ulong;
typedef void* cl_platform_id;
typedef void* cl_device_id;
typedef unsigned long cl_device_type;
typedef int cl_platform_info;
typedef int cl_device_info;
typedef int cl_bool;

#define CL_SUCCESS 0
#define CL_DEVICE_TYPE_GPU (1 << 2)
#define CL_PLATFORM_VENDOR 0x0901
#define CL_PLATFORM_NAME 0x0902
#define CL_PLATFORM_VERSION 0x0903
#define CL_DEVICE_NAME 0x102B
#define CL_DEVICE_VENDOR 0x102C
#define CL_DEVICE_TYPE 0x1000
#define CL_DEVICE_VERSION 0x1000
#define CL_DEVICE_GLOBAL_MEM_SIZE 0x101F
#define CL_DEVICE_MAX_MEM_ALLOC_SIZE 0x1010
#define CL_DEVICE_MAX_COMPUTE_UNITS 0x1002
#define CL_DEVICE_MAX_CLOCK_FREQUENCY 0x100C
#define CL_DEVICE_EXTENSIONS 0x1030
#define CL_DEVICE_NOT_FOUND -1
#define CL_DEVICE_NATIVE_VECTOR_WIDTH_FLOAT 0x1040
#define CL_DEVICE_NATIVE_VECTOR_WIDTH_DOUBLE 0x1041
#define CL_DEVICE_NATIVE_VECTOR_WIDTH_HALF 0x104A
#define CL_DEVICE_HALF_FP_CONFIG 0x1033
#define CL_DEVICE_SINGLE_FP_CONFIG 0x101B
#define CL_DEVICE_DOUBLE_FP_CONFIG 0x1032
#define CL_FP_FMA (1 << 2)
#define CL_FP_SOFT_FLOAT (1 << 3)
#define CL_FP_CORRECTLY_ROUNDED_DIVIDE_SQRT (1 << 4)

#define LOG_TAG "LlamaJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ========== llama.cpp 日志回调 ==========
// 将 llama.cpp 内部日志转发到 Android logcat，便于诊断模型加载失败原因
static void llama_log_callback_impl(enum ggml_log_level level, const char * text, void * user_data) {
    (void)user_data;
    if (!text) return;
    // 过滤掉空行和重复消息
    if (text[0] == '\0') return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:  LOGE("[llama] %s", text); break;
        case GGML_LOG_LEVEL_WARN:   LOGW("[llama] %s", text); break;
        case GGML_LOG_LEVEL_INFO:   LOGI("[llama] %s", text); break;
        case GGML_LOG_LEVEL_DEBUG:  LOGD("[llama] %s", text); break;
        default: LOGI("[llama] %s", text); break;
    }
}

// ========== UTF-8 安全工具 ==========

/**
 * 验证 UTF-8 序列是否完整
 * 返回完整的 UTF-8 字符串，移除末尾不完整的字节
 */
static std::string sanitizeUtf8(const std::string& input) {
    if (input.empty()) return input;

    std::string result;
    result.reserve(input.size());

    size_t i = 0;
    while (i < input.size()) {
        unsigned char c = static_cast<unsigned char>(input[i]);

        // ASCII 字符 (0xxxxxxx)
        if (c < 0x80) {
            result += input[i];
            i++;
            continue;
        }

        // 2 字节序列 (110xxxxx 10xxxxxx)
        if ((c & 0xE0) == 0xC0) {
            if (i + 1 >= input.size()) {
                LOGW("Incomplete 2-byte UTF-8 sequence at end, dropping");
                break;
            }
            unsigned char c2 = static_cast<unsigned char>(input[i + 1]);
            if ((c2 & 0xC0) != 0x80) {
                LOGW("Invalid 2-byte UTF-8 continuation byte, dropping");
                i++;
                continue;
            }
            result += input[i];
            result += input[i + 1];
            i += 2;
            continue;
        }

        // 3 字节序列 (1110xxxx 10xxxxxx 10xxxxxx)
        if ((c & 0xF0) == 0xE0) {
            if (i + 2 >= input.size()) {
                LOGW("Incomplete 3-byte UTF-8 sequence at end, dropping");
                break;
            }
            unsigned char c2 = static_cast<unsigned char>(input[i + 1]);
            unsigned char c3 = static_cast<unsigned char>(input[i + 2]);
            if ((c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80) {
                LOGW("Invalid 3-byte UTF-8 continuation bytes, dropping");
                i++;
                continue;
            }
            result += input[i];
            result += input[i + 1];
            result += input[i + 2];
            i += 3;
            continue;
        }

        // 4 字节序列 (11110xxx 10xxxxxx 10xxxxxx 10xxxxxx)
        if ((c & 0xF8) == 0xF0) {
            if (i + 3 >= input.size()) {
                LOGW("Incomplete 4-byte UTF-8 sequence at end, dropping");
                break;
            }
            unsigned char c2 = static_cast<unsigned char>(input[i + 1]);
            unsigned char c3 = static_cast<unsigned char>(input[i + 2]);
            unsigned char c4 = static_cast<unsigned char>(input[i + 3]);
            if ((c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80 || (c4 & 0xC0) != 0x80) {
                LOGW("Invalid 4-byte UTF-8 continuation bytes, dropping");
                i++;
                continue;
            }
            result += input[i];
            result += input[i + 1];
            result += input[i + 2];
            result += input[i + 3];
            i += 4;
            continue;
        }

        // 无效字节，跳过
        LOGW("Invalid UTF-8 byte: 0x%02x, skipping", c);
        i++;
    }

    return result;
}

/**
 * 剥离思考标签及其内容（标签源参数化，不硬编码）。
 * startTag / endTags 来自 chat template（mThinkStartTag / mThinkEndTags），由调用方传入；
 * 换用 GPT-OSS / MiniMax / Llama3 等非 <think> 模板时无需改动本函数。
 * 支持未闭合（无结束标记）的思考块：剥掉 startTag 到结尾的全部内容。
 * 模板未提供标签（startTag 为空）时原样返回——不猜测、不硬编码回退。
 */
static std::string stripThinkTags(const std::string& input,
                                  const std::string& startTag,
                                  const std::vector<std::string>& endTags) {
    if (input.empty()) return input;
    if (startTag.empty() || endTags.empty()) return input;
    std::string out;
    out.reserve(input.size());
    size_t pos = 0;
    const size_t len = input.size();
    while (pos < len) {
        // 查找思考开始标记（单一，来自模板）
        size_t start = input.find(startTag, pos);
        if (start == std::string::npos) {
            out.append(input, pos, len - pos);
            break;
        }
        // 开始标记之前的内容保留
        out.append(input, pos, start - pos);
        // 找最早出现的任一结束标记（模板可定义多个，如 Qwen3 的 </think> + <tool_call>）
        size_t close = std::string::npos;
        size_t closeLen = 0;
        for (const auto& tag : endTags) {
            size_t p = input.find(tag, start + startTag.size());
            if (p != std::string::npos && (close == std::string::npos || p < close)) {
                close = p;
                closeLen = tag.size();
            }
        }
        if (close == std::string::npos) {
            // 未闭合：剥掉开始标记到结尾的全部内容（视为思考残留）
            break;
        }
        pos = close + closeLen;
    }
    // 清理可能残留的 <|im_start|>assistant 等模板标记
    std::string cleaned = out;
    auto replaceAll = [&cleaned](const std::string& from, const std::string& to) {
        size_t p = 0;
        while ((p = cleaned.find(from, p)) != std::string::npos) {
            cleaned.replace(p, from.size(), to);
            p += to.size();
        }
    };
    replaceAll("<|im_start|>", "");
    replaceAll("<|im_end|>", "");
    replaceAll("<|im_start|>assistant", "");
    // 清理 tool_call 标签（完整块 + 残留碎片）：
    // 模型偶尔输出 tool_call 后未闭合/直接结束，标签会残留在正文里被 TTS 朗读
    // （实测 "<toolcall" / "</toolcall" 泄漏）。整块剥除 + 碎片标签剔除。
    replaceAll("<tool_call>", "");
    replaceAll("</tool_call>", "");
    replaceAll("<tool_call", "");
    replaceAll("</tool_call", "");
    replaceAll("<toolcall", "");
    replaceAll("</toolcall", "");
    // 工具调用残留的 JSON 头（如 {"name":"location","arguments": 后无闭合）无法可靠剥离，
    // 但标签剥掉后剩余散件由 Java cleanResponse 兜底。
    return cleaned;
}

/**
 * 分离 UTF-8 字符串为完整部分和不完整尾部
 * 用于流式传输时缓存不完整的多字节字符
 */
static std::string splitUtf8Complete(const std::string& input, std::string& completeOut) {
    if (input.empty()) {
        completeOut.clear();
        return "";
    }
    
    size_t len = input.size();
    // 从末尾往前找最后一个 UTF-8 序列的起始位置
    size_t i = len;
    while (i > 0 && (static_cast<unsigned char>(input[i - 1]) & 0xC0) == 0x80) {
        i--; // 跳过 continuation bytes (10xxxxxx)
    }
    
    if (i == 0) {
        // 全是 continuation bytes，都是不完整的
        completeOut.clear();
        return input;
    }
    
    i--; // 现在指向起始字节
    unsigned char c = static_cast<unsigned char>(input[i]);
    size_t expectedLen;
    
    if (c < 0x80) {
        expectedLen = 1;       // ASCII
    } else if ((c & 0xE0) == 0xC0) {
        expectedLen = 2;       // 2-byte sequence
    } else if ((c & 0xF0) == 0xE0) {
        expectedLen = 3;       // 3-byte sequence (中文)
    } else if ((c & 0xF8) == 0xF0) {
        expectedLen = 4;       // 4-byte sequence
    } else {
        // 无效起始字节
        completeOut = input;
        return "";
    }
    
    size_t actualLen = len - i;
    if (actualLen >= expectedLen) {
        // 最后一个字符是完整的
        completeOut = input;
        return "";
    } else {
        // 最后一个字符不完整，分离出来
        completeOut = input.substr(0, i);
        return input.substr(i);
    }
}

/**
 * 安全的 NewStringUTF 包装器
 * 不再强制清洗 UTF-8：模型输出的 token 按顺序拼接本身就是有效 UTF-8
 * 不完整字节已在回调层通过 splitUtf8Complete 缓冲，到达这里时已是完整字符
 */
static jstring safeNewStringUTF(JNIEnv* env, const char* str) {
    if (str == nullptr) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(str);
}

/**
 * 安全的 NewStringUTF 包装器（std::string 版本）
 */
static jstring safeNewStringUTF(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

static volatile bool s_openclLoaded = false;
static volatile bool s_gpuTested = false;
static volatile bool s_gpuWorking = false;
static void* s_oclHandle = nullptr;
static size_t s_detectedGpuMemory = 0;
static size_t s_detectedMaxMemAllocSize = 0; // GPU 单次最大分配大小

// Native层回调引用追踪
static jobject g_activeCallback = nullptr;
static std::mutex g_activeCallbackMutex;

static std::string s_detectedVulkanVersion = "Unknown";
static bool s_vulkanVersionDetected = false;

typedef VkResult (*PFN_vkEnumerateInstanceVersion)(uint32_t*);
typedef VkResult (*PFN_vkCreateInstance)(const VkInstanceCreateInfo*, const VkAllocationCallbacks*, VkInstance*);
typedef void (*PFN_vkDestroyInstance)(VkInstance, const VkAllocationCallbacks*);
typedef VkResult (*PFN_vkEnumeratePhysicalDevices)(VkInstance, uint32_t*, VkPhysicalDevice*);
typedef void (*PFN_vkGetPhysicalDeviceProperties)(VkPhysicalDevice, VkPhysicalDeviceProperties*);

/**
 * 读取系统属性布尔开关（用于 Vulkan 特性门控的真机回归测试）。
 * 属性置 "1"/"true"（不区分大小写）→ true；其他/缺失 → 默认值。
 */
static bool getPropEnabled(const char* name, bool def) {
    char buf[16] = {0};
    if (__system_property_get(name, buf) > 0) {
        // 手动小写比较（避免依赖 <strings.h> 的 strcasecmp）
        if (strcmp(buf, "1") == 0) return true;
        char lower[16] = {0};
        size_t n = strlen(buf);
        if (n >= sizeof(lower)) n = sizeof(lower) - 1;
        for (size_t i = 0; i < n; i++) {
            char c = buf[i];
            lower[i] = (c >= 'A' && c <= 'Z') ? (char)(c + ('a' - 'A')) : c;
        }
        return strcmp(lower, "true") == 0;
    }
    return def;
}

static std::string detectVulkanVersionViaAPI() {
    if (s_vulkanVersionDetected) {
        return s_detectedVulkanVersion;
    }
    
    const char* vulkanPaths[] = {
        "libvulkan.so",
        "/system/lib64/libvulkan.so",
        "/system/lib/libvulkan.so",
        "/vendor/lib64/libvulkan.so",
        "/vendor/lib/libvulkan.so"
    };
    
    void* vulkanLib = nullptr;
    for (const char* path : vulkanPaths) {
        vulkanLib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        if (vulkanLib) {
            LOGI("Loaded Vulkan library from: %s", path);
            break;
        }
    }
    
    if (!vulkanLib) {
        LOGW("Could not load Vulkan library");
        s_vulkanVersionDetected = true;
        s_detectedVulkanVersion = "Unknown";
        return s_detectedVulkanVersion;
    }
    
    PFN_vkEnumerateInstanceVersion vkEnumerateInstanceVersion = 
        (PFN_vkEnumerateInstanceVersion)dlsym(vulkanLib, "vkEnumerateInstanceVersion");
    PFN_vkCreateInstance vkCreateInstance = 
        (PFN_vkCreateInstance)dlsym(vulkanLib, "vkCreateInstance");
    PFN_vkDestroyInstance vkDestroyInstance = 
        (PFN_vkDestroyInstance)dlsym(vulkanLib, "vkDestroyInstance");
    PFN_vkEnumeratePhysicalDevices vkEnumeratePhysicalDevices = 
        (PFN_vkEnumeratePhysicalDevices)dlsym(vulkanLib, "vkEnumeratePhysicalDevices");
    PFN_vkGetPhysicalDeviceProperties vkGetPhysicalDeviceProperties = 
        (PFN_vkGetPhysicalDeviceProperties)dlsym(vulkanLib, "vkGetPhysicalDeviceProperties");
    
    std::string versionStr = "Unknown";
    uint32_t instanceVersion = 0;
    
    if (vkEnumerateInstanceVersion) {
        VkResult result = vkEnumerateInstanceVersion(&instanceVersion);
        if (result == VK_SUCCESS && instanceVersion != 0) {
            uint32_t major = VK_VERSION_MAJOR(instanceVersion);
            uint32_t minor = VK_VERSION_MINOR(instanceVersion);
            uint32_t patch = VK_VERSION_PATCH(instanceVersion);
            versionStr = std::to_string(major) + "." + std::to_string(minor);
            LOGI("Vulkan instance version via vkEnumerateInstanceVersion: %d.%d.%d", major, minor, patch);
        }
    }
    
    if (vkCreateInstance && vkDestroyInstance && vkEnumeratePhysicalDevices && vkGetPhysicalDeviceProperties) {
        uint32_t targetVersion = instanceVersion ? instanceVersion : VK_API_VERSION_1_0;
        
        VkApplicationInfo appInfo{};
        appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        appInfo.pApplicationName = "AIAssistant";
        appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
        appInfo.pEngineName = "No Engine";
        appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
        appInfo.apiVersion = targetVersion;
        
        VkInstanceCreateInfo createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        createInfo.pApplicationInfo = &appInfo;
        
        VkInstance instance = VK_NULL_HANDLE;
        VkResult result = vkCreateInstance(&createInfo, nullptr, &instance);
        
        if (result == VK_SUCCESS) {
            uint32_t deviceCount = 0;
            result = vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
            
            if (result == VK_SUCCESS && deviceCount > 0) {
                std::vector<VkPhysicalDevice> devices(deviceCount);
                result = vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());
                
                if (result == VK_SUCCESS) {
                    for (const auto& dev : devices) {
                        VkPhysicalDeviceProperties props;
                        vkGetPhysicalDeviceProperties(dev, &props);
                        
                        uint32_t major = VK_VERSION_MAJOR(props.apiVersion);
                        uint32_t minor = VK_VERSION_MINOR(props.apiVersion);
                        uint32_t patch = VK_VERSION_PATCH(props.apiVersion);
                        
                        LOGI("Vulkan physical device: %s, API version: %d.%d.%d", 
                             props.deviceName, major, minor, patch);
                        
                        versionStr = std::to_string(major) + "." + std::to_string(minor);
                        break;
                    }
                }
            }
            
            vkDestroyInstance(instance, nullptr);
        }
    }
    
    dlclose(vulkanLib);
    
    s_vulkanVersionDetected = true;
    s_detectedVulkanVersion = versionStr;
    LOGI("Detected Vulkan version: %s", versionStr.c_str());
    
    return versionStr;
}

static std::string getNativeLibraryDir() {
    Dl_info info;
    if (dladdr((void*)&getNativeLibraryDir, &info)) {
        std::string path = info.dli_fname;
        size_t lastSlash = path.find_last_of('/');
        if (lastSlash != std::string::npos) {
            return path.substr(0, lastSlash);
        }
    }
    return "";
}

static void* s_ggmlOpenclHandle = nullptr;
static std::atomic<bool> s_backendInitialized(false);
static std::atomic<bool> s_llamaBackendInitialized(false);

static void setupGGMLBackendPath() {
    if (s_backendInitialized.exchange(true)) {
        LOGI("GGML backend already initialized, skipping");
        return;
    }
    
    std::string libDir = getNativeLibraryDir();
    if (!libDir.empty()) {
        LOGI("Setting GGML_BACKEND_PATH to: %s", libDir.c_str());
        setenv("GGML_BACKEND_PATH", libDir.c_str(), 1);

        // ===== OpenCL kernel 编译缓存 =====
        // llama.cpp ggml-opencl 在 Android 上 default_cache_dir() 依赖 TMPDIR，
        // TMPDIR 未设置时缓存被禁用 -> 每次启动都重新编译全部 FA kernels（实测 ~9s）。
        // 显式指定 App 私有可写目录：首次编译后缓存 <sha256>.clbin（key 含设备/驱动/
        // 编译参数，驱动或参数变化自动失效重建），后续启动直接 HIT 加载，显著缩短
        // GPU 初始化耗时。
        const char* kOpenclCacheDir = "/data/user/0/com.oilquiz.app/cache/llama-cl";
        setenv("GGML_OPENCL_KERNEL_CACHE_DIR", kOpenclCacheDir, 1);
        LOGI("OpenCL kernel cache dir set to: %s", kOpenclCacheDir);

        // ===== OpenCL flash attention 运行时开关 =====
        // 背景：llama.cpp OpenCL FA 的 cluster-parallel (c8) kernel 默认仅对 Adreno
        // X2E/X1E 启用；A8X(830/840) 的 work-group 上限 128 使 stock kernel(需 256/192)
        // 注册失败回退普通 attention（长序列 decode 慢）。llama.cpp 已提供 NSG2
        // 变体（128 WG，适配 Adreno），但 dispatch 需 GGML_OPENCL_FA_C8=1 才走。
        // OpenCL flash attention cluster-parallel (c8/NSG2) 开关。
        // 实测(2026-08-29, Adreno 840): 上下文 2400+ tokens 时普通 attention 生成
        // 0.98s/tok(FA 未触发); NSG2 kernel(128 WG)适配 A8X, dispatch 需
        // GGML_OPENCL_FA_C8=1 且 n_kv>=2048。当前 12K 上下文已达标, 启用可显著提速。
        // 风险: A8X 上该路径未经上游充分验证(上游曾因编译器问题禁用 FA),
        // 如出现乱码/崩溃, 将此值改回 false 并重启 app。
        bool faC8On = true;
        if (faC8On) {
            setenv("GGML_OPENCL_FA_C8", "1", 0);
            LOGI("OpenCL: GGML_OPENCL_FA_C8=1 -> flash attention cluster-parallel (NSG2) ENABLED");
        } else {
            LOGI("OpenCL: flash attention cluster-parallel disabled");
        }

        // ===== Vulkan 加速变体的逐项开关 =====
        // 背景：NDK 26.1 自带 glslc 2026.1，它**支持** bfloat16 / e4m3 / integer_dot / coopmat，
        // 因此 ggml-vulkan 的 CMake 特性测试会把这些变体全部编进 shader 集。而本项目此前实测：
        //   MSYS2 shaderc 2026.1 生成的 bfloat16/e4m3/dot 变体在 Adreno 驱动上输出乱码，
        //   当时只能整体放弃加速变体，退回"基础路径（正确但较慢）"。
        // 现在 ggml-vulkan 已提供**运行时**逐项禁用开关（ggml-vulkan.cpp 里
        // "VK_KHR_shader_bfloat16" ... && !getenv("GGML_VK_DISABLE_BFLOAT16") 之类的判定），
        // 所以不必再整体放弃：**保留 coopmat**（Gen6 Adreno 的矩阵核心，才是真正的加速来源），
        // 只关掉已知会出乱码的三个变体。这样 Vulkan 既能提速又保持正确。
        // 想验证/放开某一项：把对应 disable 删掉或设 0，真机验证输出是否正确后再保留。
        // 注：e2m1/e4m3 两项上游没有 disable 开关，只能靠编译期特性测试控制（glslc 支持就编进来）。
        setenv("GGML_VK_DISABLE_BFLOAT16", "1", 0);
        setenv("GGML_VK_DISABLE_INTEGER_DOT_PRODUCT", "1", 0);
        setenv("GGML_VK_DISABLE_DOT2", "1", 0);
        LOGI("Vulkan: coopmat 保留；已禁用 bfloat16 / integer_dot / dot2 变体（Adreno 实测乱码）");
        
        const char* openclBackends[] = {
            "libggml-opencl.so",
            "/vendor/lib64/libggml-opencl.so",
            "/system/lib64/libggml-opencl.so",
            "/data/data/com.oilquiz.app/lib/libggml-opencl.so",
            "/data/app/com.oilquiz.app/lib/arm64-v8a/libggml-opencl.so",
            "/data/app/com.oilquiz.app-1/lib/arm64-v8a/libggml-opencl.so",
            "/data/app/com.oilquiz.app-2/lib/arm64-v8a/libggml-opencl.so",
        };
        
        for (const char* path : openclBackends) {
            void* handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
            if (handle) {
                LOGI("Successfully loaded ggml-opencl backend from: %s", path);
                s_ggmlOpenclHandle = handle;
                
                typedef void (*ggml_backend_init_fn)();
                ggml_backend_init_fn init_fn = (ggml_backend_init_fn)dlsym(handle, "ggml_backend_init");
                if (init_fn) {
                    LOGI("Calling ggml_backend_init...");
                    auto startTime = std::chrono::steady_clock::now();
                    init_fn();
                    auto endTime = std::chrono::steady_clock::now();
                    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();
                    LOGI("ggml-backend_init completed in %lldms", elapsed);
                } else {
                    LOGW("Could not find ggml_backend_init in ggml-opencl");
                }
                break;
            } else {
                LOGD("Failed to load ggml-opencl from %s: %s", path, dlerror());
            }
        }
    } else {
        LOGW("Could not determine native library directory");
    }
}

static bool detectAdrenoGPU() {
    if (!s_openclLoaded || !s_oclHandle) {
        return false;
    }
    
    typedef cl_int (*clGetPlatformIDs_fn)(cl_uint, cl_platform_id*, cl_uint*);
    typedef cl_int (*clGetPlatformInfo_fn)(cl_platform_id, cl_platform_info, size_t, void*, size_t*);
    typedef cl_int (*clGetDeviceIDs_fn)(cl_platform_id, cl_device_type, cl_uint, cl_device_id*, cl_uint*);
    typedef cl_int (*clGetDeviceInfo_fn)(cl_device_id, cl_device_info, size_t, void*, size_t*);
    
    auto clGetPlatformIDs_ptr = (clGetPlatformIDs_fn)dlsym(s_oclHandle, "clGetPlatformIDs");
    auto clGetPlatformInfo_ptr = (clGetPlatformInfo_fn)dlsym(s_oclHandle, "clGetPlatformInfo");
    auto clGetDeviceIDs_ptr = (clGetDeviceIDs_fn)dlsym(s_oclHandle, "clGetDeviceIDs");
    auto clGetDeviceInfo_ptr = (clGetDeviceInfo_fn)dlsym(s_oclHandle, "clGetDeviceInfo");
    
    if (!clGetPlatformIDs_ptr || !clGetPlatformInfo_ptr || !clGetDeviceIDs_ptr || !clGetDeviceInfo_ptr) {
        LOGW("Could not resolve OpenCL functions");
        return false;
    }
    
    cl_uint numPlatforms = 0;
    if (clGetPlatformIDs_ptr(0, nullptr, &numPlatforms) != CL_SUCCESS || numPlatforms == 0) {
        LOGW("No OpenCL platforms found");
        return false;
    }
    
    std::vector<cl_platform_id> platforms(numPlatforms);
    if (clGetPlatformIDs_ptr(numPlatforms, platforms.data(), nullptr) != CL_SUCCESS) {
        LOGW("Failed to get OpenCL platforms");
        return false;
    }
    
    for (auto platform : platforms) {
        char platformVendor[256] = {0};
        char platformName[256] = {0};
        clGetPlatformInfo_ptr(platform, CL_PLATFORM_VENDOR, sizeof(platformVendor), platformVendor, nullptr);
        clGetPlatformInfo_ptr(platform, CL_PLATFORM_NAME, sizeof(platformName), platformName, nullptr);
        LOGI("OpenCL Platform: %s (%s)", platformName, platformVendor);
        
        cl_uint numDevices = 0;
        if (clGetDeviceIDs_ptr(platform, CL_DEVICE_TYPE_GPU, 0, nullptr, &numDevices) != CL_SUCCESS || numDevices == 0) {
            continue;
        }
        
        std::vector<cl_device_id> devices(numDevices);
        if (clGetDeviceIDs_ptr(platform, CL_DEVICE_TYPE_GPU, numDevices, devices.data(), nullptr) != CL_SUCCESS) {
            continue;
        }
        
        for (auto device : devices) {
            char deviceName[256] = {0};
            char deviceVendor[256] = {0};
            char deviceVersion[256] = {0};
            clGetDeviceInfo_ptr(device, CL_DEVICE_NAME, sizeof(deviceName), deviceName, nullptr);
            clGetDeviceInfo_ptr(device, CL_DEVICE_VENDOR, sizeof(deviceVendor), deviceVendor, nullptr);
            clGetDeviceInfo_ptr(device, CL_DEVICE_VERSION, sizeof(deviceVersion), deviceVersion, nullptr);
            
            LOGI("GPU Device: %s (%s) - %s", deviceName, deviceVendor, deviceVersion);
            
            std::string nameStr(deviceName);
            std::string vendorStr(deviceVendor);
            
            bool isAdreno = nameStr.find("Adreno") != std::string::npos || 
                           nameStr.find("adreno") != std::string::npos ||
                           vendorStr.find("Qualcomm") != std::string::npos ||
                           vendorStr.find("qualcomm") != std::string::npos;
            
            if (isAdreno) {
                LOGI("Detected Qualcomm Adreno GPU!");
                
                if (nameStr.find("750") != std::string::npos) {
                    LOGI("Detected Adreno 750 (Xiaomi 14 / Snapdragon 8 Gen 3)");
                } else if (nameStr.find("730") != std::string::npos) {
                    LOGI("Detected Adreno 730 (Snapdragon 8 Gen 2)");
                } else if (nameStr.find("740") != std::string::npos) {
                    LOGI("Detected Adreno 740");
                }
                
                return true;
            }
        }
    }
    return false;
}

static size_t getProcessRSS() {
    FILE* f = fopen("/proc/self/status", "r");
    if (!f) return 0;
    char line[256];
    size_t rss = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "VmRSS:", 6) == 0) {
            sscanf(line + 6, "%zu", &rss);
            rss *= 1024;
            break;
        }
    }
    fclose(f);
    return rss;
}

static size_t getProcessVM() {
    FILE* f = fopen("/proc/self/status", "r");
    if (!f) return 0;
    char line[256];
    size_t vm = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "VmSize:", 7) == 0) {
            sscanf(line + 7, "%zu", &vm);
            vm *= 1024;
            break;
        }
    }
    fclose(f);
    return vm;
}

#define LOG_MEM(step) LOGI("[MEM] %s: RSS=%.1fMB, VM=%.1fMB", step, getProcessRSS()/1048576.0, getProcessVM()/1048576.0)

// 定义Android日志常量
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6

#define LOG_TAG "LlamaJNI"

// 全局JavaVM指针
static JavaVM* g_jvm = nullptr;

// 获取JavaVM实例
static JavaVM* getJavaVM() {
    return g_jvm;
}

// JNI_OnLoad函数，在库加载时被调用
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;

    // ========== Vulkan 自适应 GPU 检测 ==========
    // 使用 NDK 标准存根链接，运行时通过 Android 系统属性检测 GPU 厂商
    // ggml-vulkan 通过 vkGetInstanceProcAddr 动态加载所有 Vulkan 函数，自适应任何设备
    // 已知问题：Adreno 840 的 VK_KHR_cooperative_matrix 驱动实现有 bug，导致矩阵乘法计算错误
    {
        // 检测 GPU 厂商：通过 Android 系统属性判断是 Adreno (Qualcomm) 还是 Mali (ARM/MediaTek)
        char platform[128] = {0};
        char egl_renderer[128] = {0};
        
        // ro.board.platform: 芯片平台名 (如 snapdragon, mt6989)
        __system_property_get("ro.board.platform", platform);
        // ro.hardware.egl: GPU 渲染器名 (如 adreno, mali)
        __system_property_get("ro.hardware.egl", egl_renderer);
        
        bool isAdreno = (strstr(egl_renderer, "adreno") != nullptr || strstr(egl_renderer, "Adreno") != nullptr);
        
        // 备用检测：通过芯片平台名判断
        if (!isAdreno) {
            if (strstr(platform, "snapdragon") != nullptr || 
                strstr(platform, "qcom") != nullptr ||
                strstr(platform, "kona") != nullptr ||      // SM8250
                strstr(platform, "lahaina") != nullptr ||    // SM8350
                strstr(platform, "taro") != nullptr ||       // SM8450/8475
                strstr(platform, "kalama") != nullptr ||     // SM8550/8650
                strstr(platform, "sun") != nullptr) {        // SM8650+
                isAdreno = true;
            }
        }
        
        if (isAdreno) {
            // Adreno GPU: 多个 Vulkan 特性驱动实现有 bug，默认全部禁用（乱码预防）。
            // 历史：D6 曾对 Adreno 840 豁免 bfloat16/dot 启用，真机输出乱码，回退全禁用。
            //
            // 运行时特性门控（用于真机回归测试，无需重新编译）：
            //   每个特性对应一个系统属性，置 1 表示"启用该特性"（即不设禁用 env）：
            //     setprop persist.ggml.vk.coopmat 1    # 矩阵乘法加速（已知 Adreno 结果错误，默认禁）
            //     setprop persist.ggml.vk.coopmat2 1
            //     setprop persist.ggml.vk.coopmat2dv 1 # coopmat2_decode_vector
            //     setprop persist.ggml.vk.fusion 1     # shader 融合（曾致计算异常，默认禁）
            //     setprop persist.ggml.vk.graphopt 1   # 图形优化（曾致乱码，默认禁）
            //     setprop persist.ggml.vk.bfloat16 1   # bfloat16 变体（D6 乱码，新驱动可复测）
            //     setprop persist.ggml.vk.dot 1        # integer dot product（D6 乱码，可复测）
            //     setprop persist.ggml.vk.dot2 1
            //   一键全部启用（仅测试，勿在生产使用）：
            //     setprop persist.ggml.vk.safe 0
            // 注意：bfloat16/e4m3/dot 的加速变体还依赖构建期的 glslc/shaderc——
            //   当前用 NDK glslc 2022.3 不生成这些变体，需换新版 shaderc 重新构建后才有效。
            //   重启 app 后属性生效（setenv 在 JNI_OnLoad 执行）。
            bool safeMode = getPropEnabled("persist.ggml.vk.safe", true);
            if (!safeMode) {
                // 测试用：全部启用（不设任何禁用 env）
                __android_log_print(ANDROID_LOG_WARN, "LlamaJNI",
                    "Vulkan: OVERRIDE persist.ggml.vk.safe=0 -> ALL features enabled (TEST ONLY, garbled/crash risk)");
            } else {
                if (!getPropEnabled("persist.ggml.vk.coopmat", false))  setenv("GGML_VK_DISABLE_COOPMAT", "1", 1);
                if (!getPropEnabled("persist.ggml.vk.coopmat2", false)) setenv("GGML_VK_DISABLE_COOPMAT2", "1", 1);
                if (!getPropEnabled("persist.ggml.vk.coopmat2dv", false)) setenv("GGML_VK_DISABLE_COOPMAT2_DECODE_VECTOR", "1", 1);
                if (!getPropEnabled("persist.ggml.vk.fusion", false))    setenv("GGML_VK_DISABLE_FUSION", "1", 1);
                if (!getPropEnabled("persist.ggml.vk.graphopt", false))  setenv("GGML_VK_DISABLE_GRAPH_OPTIMIZE", "1", 1);
                if (!getPropEnabled("persist.ggml.vk.bfloat16", false))  setenv("GGML_VK_DISABLE_BFLOAT16", "1", 1);
                if (!getPropEnabled("persist.ggml.vk.dot", false))       setenv("GGML_VK_DISABLE_INTEGER_DOT_PRODUCT", "1", 1);
                if (!getPropEnabled("persist.ggml.vk.dot2", false))      setenv("GGML_VK_DISABLE_DOT2", "1", 1);
                // 注：GGML_VK_DISABLE_F16 不设置——F16 是基础路径，保留
                __android_log_print(ANDROID_LOG_INFO, "LlamaJNI",
                    "Vulkan: Adreno GPU detected (platform=%s, egl=%s), default features disabled (overridable via persist.ggml.vk.*)",
                    platform, egl_renderer);
            }
        } else {
            // 非 Adreno GPU (Mali/其他): 不禁用任何特性
            __android_log_print(ANDROID_LOG_INFO, "LlamaJNI", 
                "Vulkan: Non-Adreno GPU detected (platform=%s, egl=%s), all features enabled", 
                platform, egl_renderer);
        }
    }
    JNIEnv* env;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    void * ocl = nullptr;
    const char* openclPaths[] = {
        "/vendor/lib64/libOpenCL.so",
        "/vendor/lib/libOpenCL.so",
        "/system/lib64/libOpenCL.so",
        "/system/lib/libOpenCL.so",
        "/vendor/lib64/libOpenCL-pixel.so",
        "/vendor/lib64/libOpenCL-car.so",
        "/vendor/lib64/libOpenCL_adreno.so",
        "/vendor/lib/libOpenCL_adreno.so",
        "/vendor/lib64/egl/libGLES_adreno.so",
        "/vendor/lib64/egl/libGLES_mali.so",
        "/vendor/lib64/libmali.so",
        "/vendor/lib64/libAdrenoOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib/libOpenCL.so",
        "/data/local/tmp/libOpenCL.so",
        "./libOpenCL.so",
        "libOpenCL.so"
    };
    
    for (const char* path : openclPaths) {
        ocl = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (ocl) {
            __android_log_print(ANDROID_LOG_INFO, "LlamaJNI", "libOpenCL.so loaded successfully from: %s", path);
            s_oclHandle = ocl;
            s_openclLoaded = true;
            break;
        } else {
            const char* error = dlerror();
            if (error) {
                __android_log_print(ANDROID_LOG_DEBUG, "LlamaJNI", "Failed to load %s: %s", path, error);
            }
        }
    }
    
    if (!ocl) {
        __android_log_print(ANDROID_LOG_WARN, "LlamaJNI", "libOpenCL.so not available on this device - GPU acceleration disabled");
        s_openclLoaded = false;
        s_oclHandle = nullptr;
    } else {
        __android_log_print(ANDROID_LOG_INFO, "LlamaJNI", "OpenCL library loaded, detecting GPU...");
        detectAdrenoGPU();
    }

    return JNI_VERSION_1_6;
}

// 日志回调函数
static void native_log(int level, const char* tag, const char* format, ...) {
    va_list args;
    va_start(args, format);
    
    // 首先使用__android_log_print输出到系统日志
    __android_log_vprint(level, tag, format, args);
    
    // 重新初始化args，因为上面已经使用过了
    va_end(args);
    va_start(args, format);
    
    // 然后通过JNI调用传递到Java层
    JNIEnv* env = nullptr;
    JavaVM* jvm = getJavaVM();
    if (jvm != nullptr) {
        // 尝试获取JNIEnv
        int result = jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        
        // 如果当前线程没有Attach到JVM，需要Attach
        if (result == JNI_EDETACHED) {
            if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                __android_log_print(ANDROID_LOG_ERROR, tag, "Failed to attach thread to JVM");
                va_end(args);
                return;
            }
            
            // 处理日志
            char buffer[1024];
            vsnprintf(buffer, sizeof(buffer), format, args);
            
            jclass cls = env->FindClass("com/oilquiz/app/ai/jni/LlamaHelper");
            if (cls != nullptr) {
                jmethodID method = env->GetStaticMethodID(cls, "onNativeLog", "(ILjava/lang/String;Ljava/lang/String;)V");
                if (method != nullptr) {
                    jstring jtag = env->NewStringUTF(tag);
                    jstring jmessage = env->NewStringUTF(buffer);
                    env->CallStaticVoidMethod(cls, method, level, jtag, jmessage);
                    env->DeleteLocalRef(jtag);
                    env->DeleteLocalRef(jmessage);
                } else {
                    __android_log_print(ANDROID_LOG_ERROR, tag, "Failed to find onNativeLog method");
                }
                env->DeleteLocalRef(cls);
            } else {
                __android_log_print(ANDROID_LOG_ERROR, tag, "Failed to find LlamaHelper class");
            }
            
            //  detach the thread
            jvm->DetachCurrentThread();
        } else if (result == JNI_OK) {
            // 已经Attach到JVM，直接处理
            char buffer[1024];
            vsnprintf(buffer, sizeof(buffer), format, args);
            
            jclass cls = env->FindClass("com/oilquiz/app/ai/jni/LlamaHelper");
            if (cls != nullptr) {
                jmethodID method = env->GetStaticMethodID(cls, "onNativeLog", "(ILjava/lang/String;Ljava/lang/String;)V");
                if (method != nullptr) {
                    jstring jtag = env->NewStringUTF(tag);
                    jstring jmessage = env->NewStringUTF(buffer);
                    env->CallStaticVoidMethod(cls, method, level, jtag, jmessage);
                    env->DeleteLocalRef(jtag);
                    env->DeleteLocalRef(jmessage);
                } else {
                    __android_log_print(ANDROID_LOG_ERROR, tag, "Failed to find onNativeLog method");
                }
                env->DeleteLocalRef(cls);
            } else {
                __android_log_print(ANDROID_LOG_ERROR, tag, "Failed to find LlamaHelper class");
            }
        } else {
            __android_log_print(ANDROID_LOG_ERROR, tag, "Failed to get JNIEnv: %d", result);
        }
    } else {
        __android_log_print(ANDROID_LOG_ERROR, tag, "JavaVM is null");
    }
    
    va_end(args);
}

// 使用std命名空间
using namespace std;

namespace llama_jni {

// 禁止模型输出 control/suppress token（防止把模板前缀 <|im_start|> 等当内容生成）。
// 优先用 GGUF 的 tokenizer.ggml.suppress_tokens，未定义则遍历 vocab 的 control token；
// EOG（正常结束标记）必须保留，否则生成永不结束。文件级函数，供多个推理类共用。
static void addControlTokenSuppression(llama_sampler * smpl, const llama_vocab * vocab,
                                       const std::vector<llama_token>& preserved = {}) {
    if (smpl == nullptr || vocab == nullptr) return;
    // §6.1 官方 preserved_tokens 语义：模板差分出的标记（<tool_call> 等）是工具调用的
    // 必要结构，不能参与控制抑制（否则被 -INFINITY 禁掉，模型永远输不出工具标记——
    // 潜在"光思考不执行工具"根因之一）
    auto isPreserved = [&preserved](llama_token t) {
        for (auto pt : preserved) if (pt == t) return true;
        return false;
    };
    std::vector<llama_logit_bias> biases;
    const llama_token * suppress = nullptr;
    int32_t n_suppress = 0;
    suppress = llama_vocab_get_suppress_tokens(vocab, &n_suppress);
    int32_t n_vocab = llama_vocab_n_tokens(vocab);
    if (suppress != nullptr && n_suppress > 0) {
        for (int32_t i = 0; i < n_suppress; i++) {
            if (!llama_vocab_is_eog(vocab, suppress[i]) && !isPreserved(suppress[i])) {
                biases.push_back({ suppress[i], -INFINITY });
            }
        }
    } else {
        for (int32_t t = 0; t < n_vocab; t++) {
            if (llama_vocab_is_control(vocab, t) && !llama_vocab_is_eog(vocab, t) && !isPreserved(t)) {
                biases.push_back({ t, -INFINITY });
            }
        }
    }
    // 额外兜底：按文本匹配常见 ChatML 控制标记（部分 GGUF 未把 im_start 标为 control）
    static const char* chatml_markers[] = {"<|im_start|>", "<|im_end|>", "<|endoftext|>", "<|tool_call|>", "<|/tool_call|>"};
    for (int32_t t = 0; t < n_vocab; t++) {
        const char* txt = llama_vocab_get_text(vocab, t);
        if (txt == nullptr) continue;
        bool is_marker = false;
        for (const char* m : chatml_markers) {
            if (strcmp(txt, m) == 0) { is_marker = true; break; }
        }
        if (is_marker && !llama_vocab_is_eog(vocab, t) && !isPreserved(t)) {
            // 去重（suppress/control 已禁的跳过）
            bool dup = false;
            for (auto& b : biases) if (b.token == t) { dup = true; break; }
            if (!dup) biases.push_back({ t, -INFINITY });
        }
    }
    LOGI("Control suppression: %d tokens banned (suppress_table=%d, vocab=%d)",
         (int)biases.size(), suppress != nullptr ? n_suppress : -1, n_vocab);
    if (!biases.empty()) {
        llama_sampler_chain_add(smpl, llama_sampler_init_logit_bias(
                n_vocab, (int32_t)biases.size(), biases.data()));
    }
}

static int s_defaultGpuLayers = -1;
static int s_defaultThreadCount = 4;

// 多模态视觉上下文（mtmd）——需在 InferenceContext 之前声明，其多模态方法引用该全局变量
static mtmd_context* s_mtmdCtx = nullptr;
static std::mutex s_mtmdMutex;
static int s_defaultMemoryPoolSize = 1024;
static int s_defaultBatchSize = 512;
static std::string s_defaultChatTemplate = "";

class InferenceContext {
private:
    llama_model *model;
    llama_context *ctx;
    const llama_vocab *vocab;
    int contextSize;
    int threadCount;
    int gpuLayers;
    int memoryPoolSize;
    int batchSize;
    int kvCacheType;   // KV cache 量化：0=Q8_0(省一半内存) 1=F16(默认,精度更高)
    std::string modelPath;
    std::atomic<bool> shouldStop;
    std::atomic<bool> isGenerating;
    std::string lastError;
    int totalTokenCount;
    std::chrono::steady_clock::time_point inferenceStartTime;
    int currentTokenCount;
    std::chrono::steady_clock::time_point decodeStartTime;  // 纯 decode 速度：正文生成计时起点
    int decodeTokenCount;                                   // 纯 decode 速度：正文生成 token 数
    std::chrono::steady_clock::time_point thinkingStartTime; // 思考段速度：计时起点
    int thinkingTokenCount;                                 // 思考段速度：思考 token 数
    std::chrono::steady_clock::time_point prefillStartTime;  // PREPROCESS 阶段：prefill 计时起点
    int prefillDoneTokens;                                   // PREPROCESS 阶段：已处理 prompt token 数
    int prefillTotalTokens;                                  // PREPROCESS 阶段：本轮 eval token 总数
    int promptTotalTokens;                                   // 本轮完整 prompt token 数（含历史，用于统计显示）
    std::string thinkingBuffer_;                            // 实时思考内容监控：当前推理思考段累积
    std::string modelType;
    std::string chatTemplate;
    
    // ===== KV cache 增量解码状态（P0-2 / §5.6）=====
    // generateStreamIncremental 专用：Agent 增量缓存独立封装于 agent_kv_cache.h，
    // 记账/判定/失效逻辑全部收敛在该类，避免散落成员导致"记账与实际KV脱节"类 bug。
    AgentKvCache kvCache;

    // ===== 思考标签（来自 chat template，统一标签源，不硬编码）=====
    // 由 generateWithTools / chatJson 在 common_chat_templates_apply 后设置；
    // 空时表示模板未提供思考标签，调用方回退旧行为（不剥离思考段）。
    std::string mThinkStartTag;
    std::vector<std::string> mThinkEndTags;
    std::string backendChoice = "auto";  // GPU 后端：auto=全部 / opencl / vulkan（设置项控制，加载时按 devices 过滤）

    // ===== 生成流程状态机（native）=====
    // 统一管理生成阶段与停止原因，替代散落的布尔/字符串状态，
    // 保证 IDLE -> PREPROCESS -> THINKING/GENERATING -> COMPLETE/ERROR 迁移显式可观测。
    enum class GenPhase {
        IDLE,       // 空闲
        PREPROCESS, // prompt 构建/tokenize/eval
        THINKING,   // 思考段生成中
        GENERATING, // 正文生成中
        COMPLETE,   // 正常完成（EOS/stop_word/ctx_full/timeout/max_tokens/user_stop）
        ERROR       // 出错
    };
    enum class StopCause {
        NONE, EOS, STOP_WORD, CTX_FULL, TIMEOUT, TOKEN_LIMIT, USER_STOP, MAX_TOKENS, ERROR
    };
    GenPhase phase = GenPhase::IDLE;
    StopCause stopCause = StopCause::NONE;

    static const char* phaseName(GenPhase p) {
        switch (p) {
            case GenPhase::IDLE: return "IDLE";
            case GenPhase::PREPROCESS: return "PREPROCESS";
            case GenPhase::THINKING: return "THINKING";
            case GenPhase::GENERATING: return "GENERATING";
            case GenPhase::COMPLETE: return "COMPLETE";
            case GenPhase::ERROR: return "ERROR";
        }
        return "?";
    }
    static const char* stopName(StopCause c) {
        switch (c) {
            case StopCause::NONE: return "NONE";
            case StopCause::EOS: return "EOS";
            case StopCause::STOP_WORD: return "STOP_WORD";
            case StopCause::CTX_FULL: return "CTX_FULL";
            case StopCause::TIMEOUT: return "TIMEOUT";
            case StopCause::TOKEN_LIMIT: return "TOKEN_LIMIT";
            case StopCause::USER_STOP: return "USER_STOP";
            case StopCause::MAX_TOKENS: return "MAX_TOKENS";
            case StopCause::ERROR: return "ERROR";
        }
        return "?";
    }
    void setPhase(GenPhase p, const char* where) {
        if (phase != p) {
            LOGI("[GenSM] %s: %s -> %s", where, phaseName(phase), phaseName(p));
            phase = p;
        }
    }
    void setStop(StopCause c, const char* where) {
        stopCause = c;
        LOGI("[GenSM] stop cause: %s (%s)", stopName(c), where);
    }

public:
    // KV 增量缓存引用（供 JNI 监控查询；AgentKvCache 为 private 成员，须经此访问）
    AgentKvCache& getKvCacheRef() { return kvCache; }

    // 状态机阶段/停止原因（供 JNI 查询；phase/phaseName 为 private 成员，须经此访问）
    const char* genPhaseName() const { return phaseName(phase); }
    const char* genStopName() const { return stopName(stopCause); }
    bool genRunning() const {
        return phase == GenPhase::THINKING || phase == GenPhase::GENERATING || phase == GenPhase::PREPROCESS;
    }

    // 实时思考内容监控：思考段累积/读取（供 JNI 与 UI 轮询查看模型思考过程）
    void clearThinkingContent() { thinkingBuffer_.clear(); }
    void appendThinkingContent(const std::string& s) { thinkingBuffer_ += s; }
    const std::string& getThinkingContent() const { return thinkingBuffer_; }

    // 函数前向声明
    std::string applyChatTemplateForMessages(const std::vector<std::pair<std::string, std::string>>& messages, bool addAssistantStart);

    /**
     * 从模型内置 chat template 提取思考标签，缓存到 mThinkStartTag / mThinkEndTags。
     * 用一条最小 user 消息 apply 一次模板即可拿到模板声明的 thinking_*_tag。
     *
     * 作用：chatSend / generateStream 这类路径不经过 common_chat_templates_apply，
     * 此前拿不到模板标签，Java 侧只能硬编码 <think>。模型加载后主动调用一次，
     * 即可让 nativeGetThinkingTags 对所有推理路径都返回正确标签。
     *
     * 失败时保持原缓存不变，不影响推理（调用方按"无标签"处理）。
     */
    bool refreshThinkingTags() {
        if (model == nullptr) {
            LOGW("refreshThinkingTags: model not loaded");
            return false;
        }
        auto tmpl = common_chat_templates_init(model, "");
        if (!tmpl) {
            LOGW("refreshThinkingTags: failed to init chat templates");
            return false;
        }
        common_chat_templates_inputs inputs;
        inputs.use_jinja = true;
        inputs.add_generation_prompt = false;
        common_chat_msg probe;
        probe.role = "user";
        probe.content = "";
        inputs.messages.push_back(probe);
        try {
            common_chat_params params = common_chat_templates_apply(tmpl.get(), inputs);
            mThinkStartTag = params.thinking_start_tag;
            mThinkEndTags = params.thinking_end_tags;
            LOGI("refreshThinkingTags: start='%s', %zu end tag(s)",
                 mThinkStartTag.c_str(), mThinkEndTags.size());
            return true;
        } catch (const std::exception& e) {
            LOGW("refreshThinkingTags: template apply failed: %s", e.what());
            return false;
        }
    }

    /** 当前缓存的模板思考标签（未提取到时为空，表示"该模型无思考段"） */
    std::string getThinkStartTag() const { return mThinkStartTag; }
    std::vector<std::string> getThinkEndTags() const { return mThinkEndTags; }

    InferenceContext() : model(nullptr), ctx(nullptr), vocab(nullptr), 
                         contextSize(0), threadCount(0), gpuLayers(0), 
                         memoryPoolSize(0), batchSize(32), kvCacheType(1), shouldStop(false),
                         isGenerating(false),
                         lastError(""), totalTokenCount(0), currentTokenCount(0),
                         decodeTokenCount(0), thinkingTokenCount(0),
                         prefillDoneTokens(0), prefillTotalTokens(0), promptTotalTokens(0),
                         modelType("unknown"), chatTemplate(""), mThinkStartTag("") {
        LOGI("InferenceContext created");
        
        setupGGMLBackendPath();
        
        if (!s_llamaBackendInitialized.exchange(true)) {
            auto startTime = std::chrono::steady_clock::now();
            llama_backend_init();
            llama_log_set(llama_log_callback_impl, nullptr);
            auto endTime = std::chrono::steady_clock::now();
            auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();
            LOGI("Llama backend initialized in %lldms", elapsed);
            
            startTime = std::chrono::steady_clock::now();
            ggml_backend_load_all();
            endTime = std::chrono::steady_clock::now();
            elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();
            LOGI("Loaded all available backends in %lldms", elapsed);
            
            size_t devCount = ggml_backend_dev_count();
            LOGI("ggml backend detected %zu devices after load_all", devCount);
            for (size_t i = 0; i < devCount; i++) {
                ggml_backend_dev_t dev = ggml_backend_dev_get(i);
                if (dev) {
                    const char* devName = ggml_backend_dev_name(dev);
                    enum ggml_backend_dev_type devType = ggml_backend_dev_type(dev);
                    const char* devDesc = ggml_backend_dev_description(dev);
                    LOGI("ggml Device %zu: name=%s, type=%d, desc=%s", i, 
                         devName ? devName : "null", (int)devType, devDesc ? devDesc : "null");
                }
            }
        } else {
            LOGI("Llama backend already initialized, skipping");
        }
    }
    
    ~InferenceContext() {
        release();
    }
    
    bool loadModel(const std::string& modelPath, int contextSize, int nThreads) {
        if (isGenerating.exchange(true)) {
            LOGE("loadModel: generation in progress, cannot load model");
            return false;
        }
        struct GeneratingGuard {
            std::atomic<bool>& flag;
            GeneratingGuard(std::atomic<bool>& f) : flag(f) {}
            ~GeneratingGuard() { flag = false; }
        } guard(isGenerating);
        
        // 先读取全局设置
        this->gpuLayers = s_defaultGpuLayers;
        this->threadCount = nThreads;
        this->memoryPoolSize = s_defaultMemoryPoolSize;
        this->batchSize = s_defaultBatchSize;
        
        // ========== 资源限制保护 ==========
        // 限制线程数：Android 设备建议不超过 4 线程，避免内存竞争和 OOM
        const int MAX_THREADS = 4;
        const int MIN_THREADS = 1;
        if (this->threadCount > MAX_THREADS) {
            LOGW("Thread count %d exceeds max %d, clamping", this->threadCount, MAX_THREADS);
            this->threadCount = MAX_THREADS;
        }
        if (this->threadCount < MIN_THREADS) {
            LOGW("Thread count %d below min %d, clamping", this->threadCount, MIN_THREADS);
            this->threadCount = MIN_THREADS;
        }
        
        // GPU 层数上限：仅作合理性护栏，显存是否够由 Java 侧 ResourceConfig 判断
        // （它按"每层大小 x 层数 + KV 预留 <= 可用显存"决定是否全量卸载）。
        // 原值 36 来自"Qwen3-4B 共 36 层"这个当时的参考模型，但 36 是**重复层数**，
        // 而 n_gpu_layers 语义含输出层（见 src/llama-model.cpp: n_repeating = n_gpu; n_repeating--），
        // 于是 4B 想要全量需要 36+1=37，被这个上限卡回 36 -> 仍然留 1 层在 CPU，
        // 每个 token 都要付一次跨端同步。故放宽到 64：对当前会跑的手机端模型足够，
        // 且不会掩盖显存不足（那种情况 Java 侧根本不会给出全量）。
        const int MAX_GPU_LAYERS = 64;
        if (this->gpuLayers > MAX_GPU_LAYERS) {
            LOGW("GPU layers %d exceeds max %d, clamping", this->gpuLayers, MAX_GPU_LAYERS);
            this->gpuLayers = MAX_GPU_LAYERS;
        }
        
        // 限制上下文大小：确保不超过安全上限，预留推理空间。
        // 上限放宽到 65536（Qwen3-4B nCtxTrain=40960 可容纳），
        // 实际仍受内存池预算（KV 缓存）与模型 n_ctx_train 钳制，不会真正越界。
        const int MAX_CONTEXT_SIZE = 65536;
        const int MIN_CONTEXT_SIZE = 2048;
        const int INFERENCE_RESERVE = 512;
        if (contextSize > MAX_CONTEXT_SIZE) {
            LOGW("Context size %d exceeds max %d, clamping", contextSize, MAX_CONTEXT_SIZE);
            contextSize = MAX_CONTEXT_SIZE;
        }
        if (contextSize < MIN_CONTEXT_SIZE) {
            LOGW("Context size %d below min %d, clamping", contextSize, MIN_CONTEXT_SIZE);
            contextSize = MIN_CONTEXT_SIZE;
        }
        
        // 限制批处理大小
        // 批处理上限：1024（此前 256 导致 prompt decode 分 6 批变慢、且单批易超限；
        // 1024 覆盖常见 prompt（1400 tokens 内 2 批），GPU 模式 batch 大吞吐高）
        const int MAX_BATCH_SIZE = 1024;
        const int MIN_BATCH_SIZE = 32;
        if (this->batchSize > MAX_BATCH_SIZE) {
            LOGW("Batch size %d exceeds max %d, clamping", this->batchSize, MAX_BATCH_SIZE);
            this->batchSize = MAX_BATCH_SIZE;
        }
        if (this->batchSize < MIN_BATCH_SIZE) {
            LOGW("Batch size %d below min %d, clamping", this->batchSize, MIN_BATCH_SIZE);
            this->batchSize = MIN_BATCH_SIZE;
        }
        
        LOGI("=== LOAD MODEL START ===");
        LOG_MEM("loadModel_start");
        LOGI("Loading model: %s", modelPath.c_str());
        LOGI("Context size: %d, Threads: %d, GPU layers: %d, Batch size: %d", 
             contextSize, this->threadCount, this->gpuLayers, this->batchSize);
        
        FILE* fileCheck = fopen(modelPath.c_str(), "rb");
        if (fileCheck == nullptr) {
            LOGE("Model file not found: %s", modelPath.c_str());
            return false;
        }
        // 诊断：读取文件头和大小，验证 GGUF 格式
        fseek(fileCheck, 0, SEEK_END);
        long fileSize = ftell(fileCheck);
        fseek(fileCheck, 0, SEEK_SET);
        unsigned char header[16] = {0};
        size_t bytesRead = fread(header, 1, sizeof(header), fileCheck);
        fclose(fileCheck);
        LOGI("Model file exists, size=%ld bytes (%.1fMB), header read=%zu bytes",
             fileSize, fileSize / 1024.0 / 1024.0, bytesRead);
        LOGI("Model file header (hex): %02x %02x %02x %02x | %02x %02x %02x %02x | %02x %02x %02x %02x | %02x %02x %02x %02x",
             header[0], header[1], header[2], header[3],
             header[4], header[5], header[6], header[7],
             header[8], header[9], header[10], header[11],
             header[12], header[13], header[14], header[15]);
        // GGUF magic: "GGUF" = 0x47 0x47 0x55 0x46
        if (bytesRead >= 4 && header[0] == 0x47 && header[1] == 0x47 && header[2] == 0x55 && header[3] == 0x46) {
            uint32_t version = *(uint32_t*)&header[4];
            LOGI("Valid GGUF format detected, version=%u", version);
        } else {
            LOGE("WARNING: File does NOT start with GGUF magic! Expected 47 47 55 46, got %02x %02x %02x %02x",
                 header[0], header[1], header[2], header[3]);
        }
        
        this->modelPath = modelPath;
        this->contextSize = contextSize;
        if (this->threadCount <= 0) {
            this->threadCount = 2; // 默认 2 线程，更安全
        }
        if (this->batchSize <= 0) {
            this->batchSize = 128; // 默认 128，更安全
        }
        
        llama_model_params model_params = llama_model_default_params();

        int autoGpuLayers = -1;
        bool hasGPU = false;
        size_t gpuTotalMemory = 0;

        LOGI("Checking available devices for GPU acceleration...");
        
        if (s_detectedGpuMemory > 0) {
            LOGI("Using previously detected GPU memory from OpenCL: %zu MB", s_detectedGpuMemory / 1024 / 1024);
            gpuTotalMemory = s_detectedGpuMemory;
        }
        
        size_t devCount = ggml_backend_dev_count();
        LOGI("ggml backend detected %zu devices after ggml_backend_load_all()", devCount);
        
        for (size_t i = 0; i < devCount; i++) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            if (dev) {
                const char* devName = ggml_backend_dev_name(dev);
                enum ggml_backend_dev_type devType = ggml_backend_dev_type(dev);
                const char* devDesc = ggml_backend_dev_description(dev);
                
                const char* typeStr = "Unknown";
                switch (devType) {
                    case GGML_BACKEND_DEVICE_TYPE_GPU: typeStr = "GPU"; break;
                    case GGML_BACKEND_DEVICE_TYPE_IGPU: typeStr = "iGPU"; break;
                    case GGML_BACKEND_DEVICE_TYPE_CPU: typeStr = "CPU"; break;
                    case GGML_BACKEND_DEVICE_TYPE_ACCEL: typeStr = "Accel"; break;
                    default: typeStr = "Other"; break;
                }
                
                LOGI("ggml Device %zu: name=%s, type=%s, desc=%s", i, 
                     devName ? devName : "null", typeStr, devDesc ? devDesc : "null");
                
                if (devType == GGML_BACKEND_DEVICE_TYPE_GPU || devType == GGML_BACKEND_DEVICE_TYPE_IGPU) {
                    hasGPU = true;
                    size_t freeMem = 0, totalMem = 0;
                    ggml_backend_dev_memory(dev, &freeMem, &totalMem);
                    LOGI("ggml GPU detected: free=%zuMB, total=%zuMB",
                         freeMem/1024/1024, totalMem/1024/1024);
                    if (totalMem > 0) {
                        gpuTotalMemory = std::max(gpuTotalMemory, totalMem);
                    } else if (s_detectedGpuMemory > 0) {
                        LOGI("ggml returned 0 memory, using OpenCL detected memory: %zu MB", s_detectedGpuMemory / 1024 / 1024);
                    }
                }
            }
        }
        
        if (!hasGPU) {
            LOGI("No GPU devices found by ggml backend, checking native OpenCL...");
        }

        int initialGpuLayers = this->gpuLayers;
        bool gpuModeRequested = true;
        
        LOGI("GPU layers from Java/Default: %d", this->gpuLayers);
        LOGI("OpenCL loaded: %s, ggml GPU detected: %s", 
             s_openclLoaded ? "true" : "false", hasGPU ? "true" : "false");
        
        // ========== GPU 加速 ==========
        // GPU 后端：OpenCL（GGML_OPENCL=ON，Adreno 专用驱动 libOpenCL_adreno.so +
        // Adreno 优化 kernel；llama-adreno 实测 Adreno 830 663 t/s prefill）。
        // 日志文案沿用 "Vulkan GPU detected" 为历史遗留，实际为 ggml 后端检测
        // （OpenCL/Vulkan 任一可用即 hasGPU=true），不影响后端选择。
        bool gpuAvailable = hasGPU;
        if (gpuAvailable && this->gpuLayers < 0) {
            // 仅当 Java 端传入负数（"未指定/自动"哨兵）时才自动使用安全上限。
            // 注意：Java 端总是传入计算后的具体值（≥0），其中 0 = 明确要求 CPU
            // （如 gpu_layers_manual=0 手动覆盖），此前 <=0 的判断会把显式 CPU
            // 强改成 GPU，在 Vulkan shader 与驱动不兼容的设备（如 Adreno 750）
            // 上直接崩溃（createComputePipeline: ErrorUnknown）。
            this->gpuLayers = MAX_GPU_LAYERS;
            LOGI("Vulkan GPU detected, auto-setting GPU layers to %d (mixed GPU+CPU inference)", this->gpuLayers);
        }
        // 最终安全限幅：确保 GPU 层数不超过 MAX_GPU_LAYERS
        if (this->gpuLayers > MAX_GPU_LAYERS) {
            LOGW("GPU layers %d exceeds max %d, clamping (keeping %d layers for CPU)", 
                 this->gpuLayers, MAX_GPU_LAYERS, MAX_GPU_LAYERS);
            this->gpuLayers = MAX_GPU_LAYERS;
        }
        LOGI("GPU available: %s, Vulkan GPU detected: %s, GPU layers: %d (mixed inference: remaining layers on CPU)",
             gpuAvailable ? "true" : "false", hasGPU ? "true" : "false", this->gpuLayers);
        
        // 配置多 GPU 分片参数（手机通常只有 1 个 GPU，但保留扩展性）
        // split_mode=LAYER: 按层分配给不同 GPU，适合异构设备
        model_params.split_mode = LLAMA_SPLIT_MODE_LAYER;
        model_params.main_gpu = 0;  // 主 GPU 设备索引

        // ===== GPU 后端开关（OpenCL / Vulkan / auto）=====
        // 设置项控制：auto = 使用所有可用 GPU 设备（旧行为）。
        // 指定 opencl/vulkan 时，只将目标后端的 GPU device 放入 devices 数组，
        // 强制 llama offload 到该后端（另一后端不参与权重放置）。
        std::vector<ggml_backend_dev_t> selectedDevices;
        if (backendChoice == "opencl" || backendChoice == "vulkan") {
            for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
                ggml_backend_dev_t dev = ggml_backend_dev_get(i);
                if (!dev) continue;
                enum ggml_backend_dev_type dType = ggml_backend_dev_type(dev);
                if (dType != GGML_BACKEND_DEVICE_TYPE_GPU && dType != GGML_BACKEND_DEVICE_TYPE_IGPU) continue;
                ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
                const char* regName = reg ? ggml_backend_reg_name(reg) : "";
                bool isTarget = (backendChoice == "vulkan")
                    ? (strstr(regName, "Vulkan") != nullptr)
                    : (strstr(regName, "OpenCL") != nullptr);
                if (isTarget) {
                    selectedDevices.push_back(dev);
                    LOGI("Backend switch: selected device %zu (backend=%s, name=%s)",
                         i, regName, ggml_backend_dev_name(dev) ? ggml_backend_dev_name(dev) : "null");
                }
            }
            if (!selectedDevices.empty()) {
                selectedDevices.push_back(nullptr); // NULL-terminated
                model_params.devices = selectedDevices.data();
                LOGI("Backend switch: offload restricted to %s (%zu device(s))", backendChoice.c_str(), selectedDevices.size() - 1);
            } else {
                LOGW("Backend switch: no %s device found, using all available devices", backendChoice.c_str());
            }
        } else {
            LOGI("Backend switch: auto mode, using all available GPU devices");
        }
        
        if (this->gpuLayers > 0) {
            model_params.n_gpu_layers = this->gpuLayers;
            LOGI("Loading model with %d GPU layers (mixed GPU+CPU inference)", this->gpuLayers);
        } else {
            model_params.n_gpu_layers = 0;
            LOGI("Loading model with CPU only");
        }
        LOG_MEM("before_llama_model_load");
        
        auto startTime = std::chrono::steady_clock::now();
        try {
            model = llama_model_load_from_file(modelPath.c_str(), model_params);
        } catch (const std::exception& e) {
            LOGE("llama_model_load_from_file threw: %s", e.what());
            model = nullptr;
        } catch (...) {
            LOGE("llama_model_load_from_file threw unknown exception");
            model = nullptr;
        }
        auto endTime = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(endTime - startTime).count();
        
        if (model == nullptr) {
            if (gpuModeRequested && !s_gpuTested) {
                LOGW("GPU mode failed during model loading, falling back to CPU mode...");
                s_gpuWorking = false;
                s_gpuTested = true;
                this->gpuLayers = 0;
                model_params.n_gpu_layers = 0;
                LOGI("Retrying with CPU only...");
                
                startTime = std::chrono::steady_clock::now();
                try {
                    model = llama_model_load_from_file(modelPath.c_str(), model_params);
                } catch (const std::exception& e) {
                    LOGE("llama_model_load_from_file (CPU retry) threw: %s", e.what());
                    model = nullptr;
                } catch (...) {
                    model = nullptr;
                }
                endTime = std::chrono::steady_clock::now();
                elapsed = std::chrono::duration_cast<std::chrono::seconds>(endTime - startTime).count();
                
                if (model == nullptr) {
                    LOGE("Failed to load model even with CPU mode");
                    return false;
                }
                s_gpuWorking = false;
                LOGI("Model loaded successfully with CPU fallback in %llds", elapsed);
            } else {
                LOGE("Failed to load model");
                return false;
            }
        } else {
            LOGI("Model loaded successfully in %llds", elapsed);
            if (this->gpuLayers > 0) {
                s_gpuTested = true;
                s_gpuWorking = true;
                LOGI("GPU mode working correctly with %d layers", this->gpuLayers);
            } else {
                s_gpuWorking = false;
                LOGI("CPU mode working");
            }
        }
        
        LOG_MEM("after_llama_model_load");

        // 组3.12：读取模型元数据（model 已加载成功，ctx 尚未创建）
        if (model != nullptr) {
            meta.valid = true;
            meta.nCtxTrain = llama_model_n_ctx_train(model);
            meta.nEmbd = llama_model_n_embd(model);
            meta.nLayer = llama_model_n_layer(model);
            meta.nHead = llama_model_n_head(model);
            // llama_model_n_params 返回 uint64_t，cast 为 long long
            meta.nParams = (long long)llama_model_n_params(model);
            // 模型名称从路径提取
            std::string pathStr(modelPath);
            size_t lastSlash = pathStr.find_last_of('/');
            meta.modelName = (lastSlash != std::string::npos) ? pathStr.substr(lastSlash + 1) : pathStr;
            LOGI("ModelMeta: nCtxTrain=%d, nEmbd=%d, nLayer=%d, nHead=%d, nParams=%lld, name=%s",
                 meta.nCtxTrain, meta.nEmbd, meta.nLayer, meta.nHead,
                 meta.nParams, meta.modelName.c_str());
        }

        vocab = llama_model_get_vocab(model);
        if (vocab == nullptr) {
            LOGE("Failed to get vocab");
            llama_model_free(model);
            model = nullptr;
            return false;
        }
        
        int vocabSize = llama_vocab_n_tokens(vocab);
        LOGI("Vocabulary loaded with %d tokens", vocabSize);

        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = contextSize;

        // 确保线程数在安全范围内
        if (threadCount <= 0) {
            threadCount = 2; // 默认 2 线程，更安全
        }
        if (threadCount > MAX_THREADS) {
            threadCount = MAX_THREADS;
        }
        ctx_params.n_threads = threadCount;

        // 批处理线程数：使用更多线程加速 prefill
        // GPU 模式下，batch 处理可以用更多线程
        int batchThreadCount = (this->gpuLayers > 0) ?
            std::min(threadCount + 2, (int)std::thread::hardware_concurrency()) : threadCount;
        ctx_params.n_threads_batch = batchThreadCount;

        // 批处理大小：GPU 模式下使用更大的 batch
        // 256→512（12K 上下文重试）：实测 1523 tokens 增量 prefill 用 256 batch=6 批，
        // 每批 OpenCL 调度开销大 → 31s prefill（49 tok/s）；512 减半批数。
        // 之前 8K 上下文 512 曾触发 OOM（模型 2.4GB 常驻 + KV buffer），现 12K 上下文
        // 内存池 2048MB、KV 峰值 1080MB 有余量；如仍 OOM 回退 256。
        int n_batch_actual = batchSize;
        if (this->gpuLayers > 0 && n_batch_actual < 512) {
            n_batch_actual = 512;
            LOGI("GPU mode: increasing batch size to %d for prefill throughput (capped 512 to limit peak memory)", n_batch_actual);
        }
        if (n_batch_actual > MAX_BATCH_SIZE) {
            n_batch_actual = MAX_BATCH_SIZE;
        }
        if (n_batch_actual < MIN_BATCH_SIZE) {
            n_batch_actual = MIN_BATCH_SIZE;
        }
        ctx_params.n_batch = n_batch_actual;
        ctx_params.n_ubatch = n_batch_actual;

        // Flash Attention：原强制 ENABLED，但 Vulkan 后端 flash attention kernel 在部分 Adreno
        // 设备上性能差（推理 0.4 t/s 异常慢），改为 AUTO（llama.cpp 自行判断是否启用，
        // 不支持的 GPU 自动回退标准 attention——成熟 Vulkan kernel 更快）
        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        LOGI("Flash Attention set to AUTO (fallback-safe)");

        // kv_unified：统一 seq 管理（living-kv 前提：多 seq 时 seq_cp 需要 kv_unified，否则 abort）。
        // 同时为 KV 记忆引擎（独立 seq 1，长文档 KV 持久化）提供多 seq 能力。
        ctx_params.kv_unified = true;
        LOGI("KV cache unified seq management enabled (kv_unified=true)");

        // KV cache 量化：Q8_0 减半 KV 内存（8B@6144ctx：约0.9GB→0.45GB），精度损失极小
        // 默认 F16（kvCacheType=1）；Java 侧大模型/低内存时设为 0（Q8_0）或 2（Q4_0，极低内存场景）
        // Qwen3 系列为 hybrid-attention 模型，Q4_0 KV 近无损（llama.cpp 官方实证 BLEU 1.000 @4x 压缩）
        if (this->kvCacheType == 0) {
            ctx_params.type_k = GGML_TYPE_Q8_0;
            ctx_params.type_v = GGML_TYPE_Q8_0;
            LOGI("KV cache quantized to Q8_0 (memory ~50%% saved)");
        } else if (this->kvCacheType == 2) {
            ctx_params.type_k = GGML_TYPE_Q4_0;
            ctx_params.type_v = GGML_TYPE_Q4_0;
            LOGI("KV cache quantized to Q4_0 (memory ~75%% saved, hybrid model near-lossless)");
        } else {
            ctx_params.type_k = GGML_TYPE_F16;
            ctx_params.type_v = GGML_TYPE_F16;
        }

        // memoryPoolSize 作为 KV cache 内存预算（真正利用该配置）：
        // 按模型参数估算每 token KV 字节，超预算时钳制 n_ctx，防止 KV 撑爆可用内存。
        // KV/Token = 2(K+V) × n_layer × n_head_kv × head_dim × 元素字节
        if (this->memoryPoolSize > 0 && model != nullptr) {
            int nLayer = llama_model_n_layer(model);
            int nHeadKv = llama_model_n_head_kv(model);
            int nEmbd = llama_model_n_embd(model);
            int nHead = llama_model_n_head(model);
            int headDim = (nHead > 0 && nEmbd > 0) ? (nEmbd / nHead) : 128;
            int kvElemBytes = (this->kvCacheType == 0) ? 1 : 2; // Q8_0=1字节, F16=2字节
            long kvPerTokenBytes = 2L * nLayer * nHeadKv * headDim * kvElemBytes;
            long kvBudgetBytes = (long) this->memoryPoolSize * 1024 * 1024;
            if (kvPerTokenBytes > 0 && kvBudgetBytes > 0) {
                int maxCtxByBudget = (int) (kvBudgetBytes / kvPerTokenBytes);
                if (contextSize > maxCtxByBudget) {
                    LOGI("Memory pool budget (%dMB) limits n_ctx: %d -> %d (KV=%lld B/token)",
                         this->memoryPoolSize, contextSize, maxCtxByBudget, kvPerTokenBytes);
                    contextSize = maxCtxByBudget;
                    ctx_params.n_ctx = contextSize;
                } else {
                    LOGI("Memory pool budget (%dMB) OK for n_ctx=%d (KV=%lld B/token, peak=%lldMB)",
                         this->memoryPoolSize, contextSize, kvPerTokenBytes,
                         (kvPerTokenBytes * contextSize) / (1024 * 1024));
                }
            }
        }

        LOGI("Creating context with n_ctx=%d, n_threads=%d, n_threads_batch=%d, n_batch=%d, n_ubatch=%d, n_gpu_layers=%d",
             contextSize, threadCount, batchThreadCount, n_batch_actual, n_batch_actual, this->gpuLayers);
        
        LOGI("Creating context with n_ctx=%d, n_threads=%d, n_batch=%d, n_ubatch=%d, n_gpu_layers=%d",
             contextSize, threadCount, n_batch_actual, n_batch_actual, this->gpuLayers);
        LOG_MEM("before_llama_init_from_model");
        
        startTime = std::chrono::steady_clock::now();
        // 捕获 ggml backend（如 Vulkan shader 编译）抛出的 C++ 异常：
        // Adreno 上 Q8_0 KV shader pipeline 创建失败会抛 vk::SystemError，
        // 不捕获则 libc++abi terminate 直接崩进程（用户见"上下文初始化失败"）。
        try {
            ctx = llama_init_from_model(model, ctx_params);
        } catch (const std::exception& e) {
            LOGE("llama_init_from_model threw: %s (kvCacheType=%d, gpuLayers=%d)", e.what(), this->kvCacheType, this->gpuLayers);
            ctx = nullptr;
            // Q8_0/Q4_0 KV 在部分 GPU（Adreno Vulkan）shader 不兼容：按 "Q4_0→Q8_0→F16" 逐级回退
            if (this->kvCacheType == 2) {
                LOGI("KV Q4_0 shader failed, retrying with Q8_0 KV cache...");
                this->kvCacheType = 0;
                ctx_params.type_k = GGML_TYPE_Q8_0;
                ctx_params.type_v = GGML_TYPE_Q8_0;
                try {
                    ctx = llama_init_from_model(model, ctx_params);
                } catch (const std::exception& e2) {
                    LOGE("llama_init_from_model Q8_0 retry threw: %s", e2.what());
                    ctx = nullptr;
                } catch (...) {
                    LOGE("llama_init_from_model Q8_0 retry threw unknown exception");
                    ctx = nullptr;
                }
                if (ctx == nullptr) {
                    LOGI("KV Q8_0 retry failed, falling back to F16 KV cache...");
                    this->kvCacheType = 1;
                    ctx_params.type_k = GGML_TYPE_F16;
                    ctx_params.type_v = GGML_TYPE_F16;
                    try {
                        ctx = llama_init_from_model(model, ctx_params);
                    } catch (const std::exception& e3) {
                        LOGE("llama_init_from_model F16 retry threw: %s", e3.what());
                        ctx = nullptr;
                    } catch (...) {
                        LOGE("llama_init_from_model F16 retry threw unknown exception");
                        ctx = nullptr;
                    }
                }
            } else if (this->kvCacheType == 0) {
                LOGI("KV Q8_0 shader failed, retrying with F16 KV cache...");
                this->kvCacheType = 1;
                ctx_params.type_k = GGML_TYPE_F16;
                ctx_params.type_v = GGML_TYPE_F16;
                try {
                    ctx = llama_init_from_model(model, ctx_params);
                } catch (const std::exception& e2) {
                    LOGE("llama_init_from_model F16 retry threw: %s", e2.what());
                    ctx = nullptr;
                } catch (...) {
                    LOGE("llama_init_from_model F16 retry threw unknown exception");
                    ctx = nullptr;
                }
            }
        } catch (...) {
            LOGE("llama_init_from_model threw unknown exception");
            ctx = nullptr;
        }
        endTime = std::chrono::steady_clock::now();
        elapsed = std::chrono::duration_cast<std::chrono::seconds>(endTime - startTime).count();

        // GPU 预热：Vulkan/OpenCL shader pipeline 在首次计算图执行时才惰性编译。
        // 驱动不兼容（如 Adreno 750 上 createComputePipeline: ErrorUnknown）会在
        // 首次推理时抛未捕获 vk::SystemError 直接 SIGABRT 崩进程。
        // 这里用 1 个 token 解码预热，强制编译 shader；异常被接住后置 ctx=nullptr，
        // 走下方已有的 GPU->CPU 回退链路自动降级。
        // 2026-08 增强：OpenCL kernel 按 batch shape 惰性编译，1-token 只编译了
        // 1-token 路径；真实 prefill 是 256-token batch（n_batch=256），首次执行时
        // 仍要现场编译 → 首轮全量 eval 实测慢到 14s/1193 tokens。
        // 故追加一个 256-token 的 warmup decode（与真实 batch 同 shape），
        // 把 prefill 用 kernel 全部预编译，首轮即可满速。
        if (ctx != nullptr && this->gpuLayers > 0) {
            try {
                const char warmText[] = " ";
                int nt = -llama_tokenize(vocab, warmText, 1, nullptr, 0, false, false);
                if (nt > 0) {
                    std::vector<llama_token> warmTokens(nt);
                    int got = llama_tokenize(vocab, warmText, 1, warmTokens.data(), (int)warmTokens.size(), false, false);
                    if (got == nt && !warmTokens.empty()) {
                        llama_batch wb = llama_batch_init(1, 0, 1);
                        wb.n_tokens = 1;
                        wb.token[0] = warmTokens[0];
                        wb.pos[0] = 0;
                        wb.n_seq_id[0] = 1;
                        wb.seq_id[0][0] = 0;
                        int drc = llama_decode(ctx, wb);
                        llama_batch_free(wb);
                        llama_memory_clear(llama_get_memory(ctx), true);
                        if (drc != 0) {
                            LOGW("GPU warmup decode failed (rc=%d), falling back to CPU", drc);
                            llama_free(ctx);
                            ctx = nullptr;
                        } else {
                            LOGI("GPU warmup decode OK (shader pipelines compiled)");
                            // 追加 batch-shape 预热：256 tokens 与真实 prefill 同 shape，
                            // 预编译 batch kernel，消除首轮全量 eval 的现场编译延迟
                            if (ctx != nullptr) {
                                int warmupBatch = 256;
                                // 用重复 token 构造 256-token 序列（不依赖词表内容）
                                std::vector<llama_token> batchTokens(warmupBatch, warmTokens[0]);
                                llama_batch wb2 = llama_batch_init(warmupBatch, 0, 1);
                                for (int i = 0; i < warmupBatch; i++) {
                                    wb2.token[i] = batchTokens[i];
                                    wb2.pos[i] = i;
                                    wb2.n_seq_id[i] = 1;
                                    wb2.seq_id[i][0] = 0;
                                }
                                wb2.n_tokens = warmupBatch;
                                auto wstart = std::chrono::steady_clock::now();
                                int drc2 = llama_decode(ctx, wb2);
                                llama_batch_free(wb2);
                                llama_memory_clear(llama_get_memory(ctx), true);
                                auto wend = std::chrono::steady_clock::now();
                                double ws = std::chrono::duration<double>(wend - wstart).count();
                                if (drc2 != 0) {
                                    LOGW("GPU warmup batch decode failed (rc=%d), batch kernels may compile lazily", drc2);
                                } else {
                                    LOGI("GPU warmup batch decode OK: %d tokens in %.2fs (batch kernels precompiled)", warmupBatch, ws);
                                }
                            }
                        }
                    }
                }
            } catch (const std::exception& e) {
                LOGE("GPU warmup threw: %s, falling back to CPU", e.what());
                llama_free(ctx);
                ctx = nullptr;
            } catch (...) {
                LOGE("GPU warmup threw unknown exception, falling back to CPU");
                llama_free(ctx);
                ctx = nullptr;
            }
        }
        
        if (ctx == nullptr) {
            if (gpuModeRequested && s_gpuWorking) {
                LOGW("GPU mode failed during context creation, falling back to CPU mode...");
                s_gpuWorking = false;
                
                llama_free(ctx);
                ctx = nullptr;
                llama_model_free(model);
                model = nullptr;
                vocab = nullptr;
                
                LOGI("Releasing GPU resources and reloading with CPU only...");
                this->gpuLayers = 0;
                model_params.n_gpu_layers = 0;
                
                startTime = std::chrono::steady_clock::now();
                try {
                    model = llama_model_load_from_file(modelPath.c_str(), model_params);
                } catch (const std::exception& e) {
                    LOGE("llama_model_load_from_file (CPU reload) threw: %s", e.what());
                    model = nullptr;
                } catch (...) {
                    model = nullptr;
                }
                endTime = std::chrono::steady_clock::now();
                elapsed = std::chrono::duration_cast<std::chrono::seconds>(endTime - startTime).count();
                
                if (model == nullptr) {
                    LOGE("Failed to reload model with CPU mode");
                    return false;
                }
                
                LOGI("Model reloaded with CPU mode in %llds", elapsed);
                
                vocab = llama_model_get_vocab(model);
                if (vocab == nullptr) {
                    LOGE("Failed to get vocab after CPU reload");
                    llama_model_free(model);
                    model = nullptr;
                    return false;
                }
                
                LOGI("Creating context with CPU mode...");
                startTime = std::chrono::steady_clock::now();
                try {
                    ctx = llama_init_from_model(model, ctx_params);
                } catch (const std::exception& e) {
                    LOGE("llama_init_from_model (CPU mode) threw: %s", e.what());
                    ctx = nullptr;
                } catch (...) {
                    LOGE("llama_init_from_model (CPU mode) threw unknown exception");
                    ctx = nullptr;
                }
                endTime = std::chrono::steady_clock::now();
                elapsed = std::chrono::duration_cast<std::chrono::seconds>(endTime - startTime).count();
                
                if (ctx == nullptr) {
                    LOGE("Failed to create context even with CPU mode");
                    llama_model_free(model);
                    model = nullptr;
                    vocab = nullptr;
                    return false;
                }
                
                LOGI("Context created with CPU fallback in %llds", elapsed);
            } else {
                LOGE("Failed to create context");
                llama_model_free(model);
                model = nullptr;
                vocab = nullptr;
                return false;
            }
        }
        
        LOG_MEM("after_llama_init_from_model");
        
        detectModelType();
        getChatTemplate();
        
        LOGI("Context created successfully in %llds", elapsed);
        LOG_MEM("loadModel_complete");
        LOGI("=== LOAD MODEL COMPLETE ===");
        return true;
    }
    
    bool reinitializeContext() {
        LOGI("Reinitializing context...");
        
        // 保存当前参数
        std::string savedModelPath = modelPath;
        int savedContextSize = contextSize;
        int savedThreadCount = threadCount;
        int savedGpuLayers = gpuLayers;
        int savedBatchSize = batchSize;
        
        // 释放当前资源
        if (ctx) {
            llama_free(ctx);
            ctx = nullptr;
        }
        
        // 重新初始化上下文
        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = savedContextSize;
        ctx_params.n_threads = savedThreadCount;
        ctx_params.n_threads_batch = savedThreadCount;
        
        int n_batch_actual = savedBatchSize;
        if (n_batch_actual > savedContextSize) {
            n_batch_actual = savedContextSize;
        }
        ctx_params.n_batch = n_batch_actual;
        
        LOGI("Re-creating context with n_ctx=%d, n_threads=%d, n_batch=%d", 
             savedContextSize, savedThreadCount, n_batch_actual);
        
        auto startTime = std::chrono::steady_clock::now();
        ctx = llama_init_from_model(model, ctx_params);
        auto endTime = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(endTime - startTime).count();
        
        if (ctx == nullptr) {
            LOGE("Failed to re-create context");
            return false;
        }
        
        LOGI("Context reinitialized successfully in %llds", elapsed);
        return true;
    }
    
    bool generate(const std::string& prompt, int maxTokens, float temperature, float topP, int topK, std::string& output) {
        if (isGenerating.exchange(true)) {
            LOGE("generate: already generating, rejecting concurrent call");
            return false;
        }
        struct GeneratingGuard {
            std::atomic<bool>& flag;
            GeneratingGuard(std::atomic<bool>& f) : flag(f) {}
            ~GeneratingGuard() { flag = false; }
        } guard(isGenerating);
        
        shouldStop = false;
        inferenceStartTime = std::chrono::steady_clock::now();
        currentTokenCount = 0;
        
        LOGI("=== GENERATE START ===");
        LOGI("Prompt length: %zu, maxTokens: %d, temp=%f, topP=%f, topK=%d", prompt.size(), maxTokens, temperature, topP, topK);
        LOG_MEM("generate_start");
        
        if (!isValid()) {
            LOGE("Invalid context - model=%p, ctx=%p, vocab=%p", 
                 (void*)model, (void*)ctx, (void*)vocab);
            return false;
        }
        
        // 创建 sampler chain
        auto sparams = llama_sampler_chain_default_params();
        struct llama_sampler * smpl = llama_sampler_chain_init(sparams);
        if (temperature <= 0) {
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        } else {
            addControlTokenSuppression(smpl, vocab);
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK > 0 ? topK : 40));
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0 ? topP : 0.9f, 1));
            llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.3f, 0.0f, 0.0f));
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }
        
        LOGI("Context is valid, starting generation");
        
        // 直接使用原始prompt（Java层已经格式化了）
        const std::string& promptToUse = prompt;
        LOGI("Using prompt directly (Java layer already formatted)");
        
        // Tokenize input - 和 simple.cpp 一样！
        LOGI("Starting tokenization...");
        int n_prompt = -llama_tokenize(vocab, promptToUse.c_str(), promptToUse.size(), NULL, 0, true, true);
        
        if (n_prompt < 0) {
            LOGE("Tokenization failed: invalid prompt");
            return false;
        }
        
        std::vector<llama_token> prompt_tokens(n_prompt);
        if (llama_tokenize(vocab, promptToUse.c_str(), promptToUse.size(), prompt_tokens.data(), prompt_tokens.size(), true, true) < 0) {
            LOGE("Tokenization failed");
            return false;
        }
        
        LOGI("Tokenization complete, %zu tokens", prompt_tokens.size());
        
        // 只打印前20个token，避免大量日志导致内存问题
        size_t max_log_tokens = std::min(prompt_tokens.size(), (size_t)20);
        for (size_t i = 0; i < max_log_tokens; i++) {
            auto id = prompt_tokens[i];
            char buf[128];
            int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
            if (n >= 0) {
                std::string s(buf, n);
                LOGI("Prompt token %zu: %s", i, s.c_str());
            }
        }
        if (prompt_tokens.size() > 20) {
            LOGI("... (%zu more tokens not logged)", prompt_tokens.size() - 20);
        }
        
        llama_memory_t mem = llama_get_memory(ctx);
        if (mem != nullptr) {
            llama_memory_clear(mem, true);
            LOGI("KV cache cleared before new generation");
        } else {
            LOGE("llama_get_memory returned null in generate");
        }
        
        int n_ctx = llama_n_ctx(ctx);
        LOGI("Context size: n_ctx=%d, prompt_tokens=%zu, maxTokens=%d", n_ctx, prompt_tokens.size(), maxTokens);
        // 修复：maxTokens 是生成停止上限，不计入 context 预算（生成循环有 n_ctx-4 guard 优雅停止）。
        // 只校验 prompt 本体是否放得下，预留固定生成余量。
        const int GENERATION_RESERVE = 512;
        if ((int)prompt_tokens.size() > n_ctx - GENERATION_RESERVE) {
            LOGE("Prompt too long: %zu tokens >= %d", prompt_tokens.size(), n_ctx - GENERATION_RESERVE);
            setLastError("Prompt too long for context window");
            return false;
        }

        // 分块解码 prompt（batch ≤ n_batch，防 llama_decode assert 崩溃）
        const int nBatch = llama_n_batch(ctx);
        const int bSize = nBatch > 0 ? nBatch : 256;
        LOGI("Processing prompt as batches, size=%zu, n_batch=%d", prompt_tokens.size(), bSize);

        int ret = 0;
        for (size_t offset = 0; offset < prompt_tokens.size(); offset += bSize) {
            size_t nTokens = std::min((size_t)bSize, prompt_tokens.size() - offset);
            llama_batch prompt_batch = llama_batch_get_one(prompt_tokens.data() + offset, (int)nTokens);
            ret = llama_decode(ctx, prompt_batch);
            if (ret != 0) {
                LOGE("llama_decode failed for prompt batch (offset=%zu, n=%zu) code: %d", offset, nTokens, ret);
                break;
            }
        }
        LOGI("llama_decode for prompt completed successfully");
        
        LOGI("Prompt evaluated successfully");
        
        int n_decode = 0;
        output = "";
        
        LOGI("Starting generation loop, maxTokens=%d", maxTokens);
        
        int n_remain = maxTokens;
        // 当前 KV cache 位置：prompt 已全部写入，后续每生成一个 token 位置 +1
        int n_past = (int)prompt_tokens.size();
        
        while (n_remain > 0 && !shouldStop) {
            if (shouldStop) {
                LOGI("Stop requested");
                break;
            }
            // KV cache 满则停止，防止 llama_decode 位置越界触发 ggml_abort 崩溃
            if (n_past >= n_ctx - 4) {
                LOGI("Context full, stopping generation (n_past=%d, n_ctx=%d)", n_past, n_ctx);
                break;
            }
            
            llama_token new_token_id = llama_sampler_sample(smpl, ctx, -1);
            llama_sampler_accept(smpl, new_token_id);
            
            if (llama_vocab_is_eog(vocab, new_token_id)) {
                LOGI("EOS token encountered");
                break;
            }
            
            if (new_token_id == 151643 || new_token_id == 151644 || new_token_id == 151645 ||
                new_token_id == 128000 || new_token_id == 128001 || new_token_id == 128008 || new_token_id == 128009) {
                LOGI("Common EOS token ID detected: %d, stopping generation", new_token_id);
                break;
            }
            
            char buf[128];
            int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
            if (n >= 0) {
                std::string s(buf, n);
                
                if (s.find("<|im_end|") != std::string::npos ||
                    s.find("</s>") != std::string::npos ||
                    s.find("<|endoftext|") != std::string::npos ||
                    s.find("<end_of_solution|") != std::string::npos ||
                    s.find("<|im_sep|") != std::string::npos ||
                    s.find("assistant:") != std::string::npos) {
                    LOGI("Stop word detected in token: '%s', stopping generation", s.c_str());
                    break;
                }
                
                output += s;
                if (n_decode % 10 == 0) {
                    LOGI("Generated token %d: %s", n_decode, s.c_str());
                }
            }
            
            llama_batch batch = llama_batch_get_one(&new_token_id, 1);
            int ret = llama_decode(ctx, batch);
            if (ret != 0) {
                LOGE("llama_decode failed for generation with code: %d", ret);
                break;
            }
            n_past++;
            
            n_remain--;
            n_decode++;
            currentTokenCount++;
        }
        
        llama_sampler_free(smpl);
        
        LOGI("Generation completed, output length: %zu, decoded %d tokens", output.length(), n_decode);
        return !output.empty();
    }
    
    void releaseContext() {
        if (ctx) {
            LOGI("Releasing context only (keeping model)");
            llama_free(ctx);
            ctx = nullptr;
        }
    }

    /**
     * 检查是否有足够的上下文空间用于单次推理
     *
     * @param promptTokens 输入 token 数
     * @param maxOutputTokens 预期输出 token 数
     * @return true 如果有足够的空间，false 如果需要清理上下文
     */
    bool hasEnoughContextSpace(int promptTokens, int maxOutputTokens) {
        if (ctx == nullptr) {
            return false;
        }

        int requiredTokens = promptTokens + maxOutputTokens;
        int availableTokens = contextSize - currentTokenCount;

        LOGI("Context check: used=%d, required=%d, available=%d, contextSize=%d",
             currentTokenCount, requiredTokens, availableTokens, contextSize);

        if (requiredTokens > availableTokens) {
            LOGW("Not enough context space: need %d tokens, only %d available",
                 requiredTokens, availableTokens);
            return false;
        }

        return true;
    }

    /**
     * 清理 KV cache 以释放上下文空间
     * 保留最近的对话历史
     */
    void clearContextForInference() {
        if (ctx == nullptr) {
            return;
        }

        LOGI("Clearing context, used tokens before: %d", currentTokenCount);

        // 清理 KV cache
        // 必须使用软清理(false)：硬清理(true)会释放 Vulkan/OpenCL 后端的 KV GPU 缓冲区，
        // 下一次 llama_decode 访问已释放的缓冲区会触发 signal 6 崩溃
        // （导入引擎每次推理前调用此接口，曾因此导致本地推理必崩；对话路径的软清理一直正常）
        llama_memory_t mem = llama_get_memory(ctx);
        if (mem != nullptr) {
            llama_memory_clear(mem, false);
        }

        // 同步增量记账：KV 已被本路径清空，防止下次 chatJson 误判 seq_pos_mismatch
        kvCache.invalidate();

        // 重置 token 计数
        currentTokenCount = 0;

        LOGI("Context cleared (soft), freed tokens");
    }

    void release() {
        shouldStop = true;
        while (isGenerating) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        
        LOGI("Releasing resources");
        
        // R3-4：释放前失效增量缓存记账
        kvCache.invalidate();
        
        releaseContext();
        
        if (model) {
            llama_model_free(model);
            model = nullptr;
        }
        
        vocab = nullptr;
        contextSize = 0;
        threadCount = 0;
        gpuLayers = 0;
        memoryPoolSize = 0;
        batchSize = 32;
        
        LOGI("Model resources released (llama backend kept alive for next load)");
    }
    
    bool isValid() const {
        return model != nullptr && ctx != nullptr && vocab != nullptr;
    }

    bool ensureContext() {
        if (ctx != nullptr) return true;
        if (model == nullptr) return false;

        LOGI("Creating context on demand: n_ctx=%d, n_threads=%d, n_batch=%d, gpu_layers=%d",
             contextSize, threadCount, batchSize, gpuLayers);
        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = contextSize;
        if (threadCount <= 0) threadCount = 4;
        ctx_params.n_threads = threadCount;
        ctx_params.n_threads_batch = threadCount;
        int n_batch_actual = batchSize > 0 ? batchSize : 512;
        ctx_params.n_batch = n_batch_actual;
        ctx_params.n_ubatch = n_batch_actual;

        ctx = llama_init_from_model(model, ctx_params);
        if (ctx == nullptr) {
            LOGE("Failed to create context on demand");
            return false;
        }
        LOGI("Context created on demand successfully with n_batch=%d", n_batch_actual);
        return true;
    }
    
    void stopGeneration() {
        shouldStop = true;
        LOGI("Stop generation requested");
    }
    
    void clearHistory() {
        if (ctx != nullptr) {
            llama_memory_t mem = llama_get_memory(ctx);
            if (mem != nullptr) {
                llama_memory_clear(mem, true);
                LOGI("History cleared (KV cache reset)");
            }
        }
        // R3-4：清 KV 后失效增量缓存记账（seq_pos_max 校验为兜底，此处显式失效）
        kvCache.invalidate();
    }
    
    std::string getModelInfo() {
        std::string info;
        info = "Model Path: " + modelPath + "\n";
        info += "Model Type: " + modelType + "\n";
        info += "Context Size: " + std::to_string(contextSize) + "\n";
        info += "Thread Count: " + std::to_string(threadCount) + "\n";
        info += "GPU Layers: " + std::to_string(gpuLayers) + "\n";
        info += "Batch Size: " + std::to_string(batchSize) + "\n";
        if (model != nullptr) {
            int n_tokens = llama_vocab_n_tokens(vocab);
            info += "Vocabulary Size: " + std::to_string(n_tokens) + "\n";
        }
        info += "Chat Template: " + chatTemplate.substr(0, 100) + (chatTemplate.size() > 100 ? "..." : "") + "\n";
        return info;
    }
    
    float getInferenceSpeed() {
        if (currentTokenCount == 0) {
            return 0.0f;
        }
        auto endTime = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - inferenceStartTime).count();
        if (elapsed == 0) {
            return 0.0f;
        }
        return (currentTokenCount * 1000.0f) / elapsed;
    }

    // 纯 decode 速度：正文生成阶段 token / 耗时（思考段不计，think_end 后计时）
    float getDecodeSpeed() {
        if (decodeTokenCount == 0) return 0.0f;
        auto endTime = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - decodeStartTime).count();
        if (elapsed == 0) return 0.0f;
        return (decodeTokenCount * 1000.0f) / elapsed;
    }

    // 当前阶段速度（tokens/s）：按状态机阶段返回 THINKING 思考速度 / GENERATING 解码速度
    float getPhaseSpeed() {
        switch (phase) {
            case GenPhase::THINKING: {
                if (thinkingTokenCount == 0) return 0.0f;
                auto endTime = std::chrono::steady_clock::now();
                auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - thinkingStartTime).count();
                if (elapsed == 0) return 0.0f;
                return (thinkingTokenCount * 1000.0f) / elapsed;
            }
            case GenPhase::GENERATING:
                return getDecodeSpeed();
            case GenPhase::PREPROCESS: {
                // prefill 吞吐：已处理 prompt token / prefill 耗时
                if (prefillDoneTokens == 0) return 0.0f;
                auto endTime = std::chrono::steady_clock::now();
                auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - prefillStartTime).count();
                if (elapsed == 0) return 0.0f;
                return (prefillDoneTokens * 1000.0f) / elapsed;
            }
            default:
                return 0.0f;
        }
    }

    // PREPROCESS 进度 JSON：{done, total, pct}
    std::string getPrefillProgress() {
        char buf[160];
        int pct = prefillTotalTokens > 0
            ? (int)((prefillDoneTokens * 100L) / prefillTotalTokens) : 0;
        if (pct > 100) pct = 100;
        snprintf(buf, sizeof(buf), "{\"done\":%d,\"total\":%d,\"pct\":%d,\"prompt\":%d}",
                 prefillDoneTokens, prefillTotalTokens, pct, promptTotalTokens);
        return std::string(buf);
    }
    
    int getTokenCount() {
        return totalTokenCount;
    }
    
    void addTokenCount(int count) {
        totalTokenCount += count;
    }
    
    std::string getLastError() {
        return lastError;
    }
    
    void setLastError(const std::string& error) {
        lastError = error;
        LOGE("Error set: %s", error.c_str());
    }
    
    const llama_vocab* getVocab() {
        return vocab;
    }

    llama_model* getModel() {
        return model;
    }
    
    bool hasError() {
        return !lastError.empty();
    }
    
    void clearError() {
        lastError.clear();
    }
    
    void optimizeForPerformance() {
        LOGI("Optimizing for performance");
        if (threadCount < 4) {
            threadCount = 4;
        }
        if (batchSize < 32) {
            batchSize = 32;
        }
    }
    
    // 流式生成回调接口
    using TokenCallback = std::function<void(const std::string& token, bool isDone, const std::string& error)>;
    // 统一 JSON 回调接口（spec §5.1）
    using JsonCallback = std::function<void(const std::string& json)>;

    /**
     * 在累积的思考缓冲中查找思考结束标记（跨 token 安全）。
     * 单 token 检测会漏掉被拆分到多个 token 的结束标记（如  被拆成 "</" + "think>"），
     * 导致思考永不结束、全部内容滞留思考布局、主回复为空。
     * @return >=0 标记起始位置并回填标记长度；-1 未发现
     */
    static int findThinkingEndMarker(const std::string& buf, int& markerLen) {
        static const char* markers[] = {"\x3c/think\x3e", "\xe2\x9d\xb4", "\xe2\x9d\xb5"};
        int found = -1;
        markerLen = 0;
        for (const char* m : markers) {
            size_t mlen = strlen(m);
            size_t pos = buf.rfind(m);
            if (pos != std::string::npos && (found < 0 || (int)pos < found)) {
                found = (int)pos;
                markerLen = (int)mlen;
            }
        }
        return found;
    }

    /**
     * 缓冲末尾与某个结束标记的前缀匹配的字节数（需暂扣等待后续 token 拼接）。
     * 暂扣只会发生在字符边界（ASCII 前缀或多字节字符的起始字节），保证已冲刷部分始终是合法 UTF-8。
     */
    static size_t partialMarkerTailLen(const std::string& buf) {
        static const char* markers[] = {"\x3c/think\x3e", "\xe2\x9d\xb4", "\xe2\x9d\xb5"};
        size_t hold = 0;
        for (const char* m : markers) {
            std::string marker(m);
            for (size_t len = 1; len < marker.size() && len <= buf.size(); len++) {
                if (buf.compare(buf.size() - len, len, marker, 0, len) == 0) {
                    if (len > hold) hold = len;
                }
            }
        }
        return hold;
    }

    /**
     * 思考段剥离（统一标签源：mThinkStartTag / mThinkEndTags，来自 chat template，不硬编码）。
     * 输入一段已 decode 的文本 chunk，返回剥离思考段（含标签）后的正文；
     * 思考内容丢弃（reasoning 由生成完成后的 common_chat_parse 提取）。
     * inThinking 为跨 chunk 思考状态（调用方维护，初始 false）。
     * 模板未提供标签（mThinkStartTag 为空）时原样返回，不影响非思考模型。
     */
    std::string stripThinkingChunk(const std::string& chunk, bool& inThinking) {
        if (chunk.empty()) return "";
        if (mThinkStartTag.empty() || mThinkEndTags.empty()) return chunk;
        std::string out;
        out.reserve(chunk.size());
        size_t pos = 0;
        while (pos < chunk.size()) {
            if (!inThinking) {
                size_t open = chunk.find(mThinkStartTag, pos);
                if (open == std::string::npos) {
                    out.append(chunk, pos, std::string::npos);
                    break;
                }
                out.append(chunk, pos, open - pos);
                inThinking = true;
                pos = open + mThinkStartTag.size();
            } else {
                // 找最早出现的任一结束标签（模板可能定义多个，如 Qwen3 的 <|thinking_end|> + <|im_end|>）
                size_t close = std::string::npos;
                size_t closeLen = 0;
                for (const auto& tag : mThinkEndTags) {
                    size_t p = chunk.find(tag, pos);
                    if (p != std::string::npos && (close == std::string::npos || p < close)) {
                        close = p;
                        closeLen = tag.size();
                    }
                }
                if (close == std::string::npos) {
                    break;   // 思考未在本 chunk 结束：丢弃全部（思考内容不发给 UI）
                }
                inThinking = false;
                pos = close + closeLen;
            }
        }
        return out;
    }
    
    bool generateStream(const std::string& prompt, int maxTokens, float temperature, float topP, int topK, bool enableThinking, TokenCallback callback) {
        if (isGenerating.exchange(true)) {
            LOGE("generateStream: already generating, rejecting concurrent call");
            callback("", true, "Generation already in progress");
            return false;
        }
        struct GeneratingGuard {
            std::atomic<bool>& flag;
            GeneratingGuard(std::atomic<bool>& f) : flag(f) {}
            ~GeneratingGuard() { flag = false; }
        } guard(isGenerating);
        
        inferenceStartTime = std::chrono::steady_clock::now();
        currentTokenCount = 0;
        
        LOGI("=== STREAM GENERATE START ===");
        LOGI("Prompt length: %zu, maxTokens: %d, temp=%f, topP=%f, topK=%d, thinking=%d", prompt.size(), maxTokens, temperature, topP, topK, enableThinking);
        LOG_MEM("generateStream_start");
        
        if (!isValid()) {
            std::string error = "Model not initialized";
            setLastError(error);
            callback("", true, error);
            return false;
        }
        
        shouldStop = false;
        
        // 创建 sampler chain
        auto sparams = llama_sampler_chain_default_params();
        struct llama_sampler * smpl = llama_sampler_chain_init(sparams);
        if (temperature <= 0) {
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        } else {
            addControlTokenSuppression(smpl, vocab);
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK > 0 ? topK : 40));
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0 ? topP : 0.9f, 1));
            llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.3f, 0.0f, 0.0f));
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }
        
        try {
        std::string promptToUse = prompt;
        if (enableThinking) {
            promptToUse += "<think>\n";
            LOGI("generateStream: enableThinking=true, appended <think>\\n");
        }
        LOGI("Using prompt directly (Java layer already formatted)");
        
        // Tokenize the prompt
        LOGI("Starting tokenization...");
        std::vector<llama_token> tokens_list;
        int n_tokens = -llama_tokenize(vocab, promptToUse.c_str(), promptToUse.size(), NULL, 0, true, true);
        LOGI("Token count estimate: %d", n_tokens);
        if (n_tokens < 0) {
            std::string error = "Failed to tokenize prompt";
            setLastError(error);
            callback("", true, error);
            return false;
        }
        tokens_list.resize(n_tokens);
        if (llama_tokenize(vocab, promptToUse.c_str(), promptToUse.size(), tokens_list.data(), tokens_list.size(), true, true) < 0) {
            std::string error = "Failed to tokenize prompt";
            setLastError(error);
            callback("", true, error);
            return false;
        }
        
        if (tokens_list.empty()) {
            std::string error = "Failed to tokenize prompt";
            setLastError(error);
            callback("", true, error);
            return false;
        }
        
        LOGI("Tokenized prompt to %zu tokens", tokens_list.size());
        
        llama_memory_t mem = llama_get_memory(ctx);
        if (mem != nullptr) {
            // 使用软清除（false）：重置 KV 使用量但保留已分配的 GPU 缓冲区。
            // 硬清除（true）在 OpenCL 后端会释放缓冲，后续 llama_decode 触发 signal 6 崩溃
            llama_memory_clear(mem, false);
            LOGI("KV cache soft-cleared before new generation");
        } else {
            LOGE("llama_get_memory returned null, attempting seq_rm reset");
        }
        
        int n_ctx = llama_n_ctx(ctx);
        LOGI("Context size: n_ctx=%d, prompt_tokens=%zu, maxTokens=%d", n_ctx, tokens_list.size(), maxTokens);
        // 修复：maxTokens 是生成停止上限，不计入 context 预算（生成循环有 n_ctx-4 guard 优雅停止）。
        // 只校验 prompt 本体是否放得下，预留固定生成余量。
        const int GENERATION_RESERVE = 512;
        if ((int)tokens_list.size() > n_ctx - GENERATION_RESERVE) {
            std::string error = "Prompt too long: " + std::to_string(tokens_list.size()) + " tokens >= "
                + std::to_string(n_ctx - GENERATION_RESERVE);
            LOGE("%s", error.c_str());
            setLastError(error);
            callback("", true, error);
            return false;
        }
        
        // 检查模型状态
        LOGI("=== 检查模型状态 ===");
        LOGI("Model pointer: %p", (void*)model);
        LOGI("Context pointer: %p", (void*)ctx);
        LOGI("Vocab pointer: %p", (void*)vocab);
        if (!isValid()) {
            std::string error = "Model, context, or vocab is null after tokenization";
            setLastError(error);
            callback("", true, error);
            return false;
        }
        
        // 分块解码 prompt（batch ≤ n_batch，否则 llama_decode 对单 batch 超 n_batch 会 assert 崩溃）
        const int n_batch = llama_n_batch(ctx);
        if (n_batch <= 0) {
            LOGI("llama_n_batch unavailable, using 256");
        }
        const int batchSize = n_batch > 0 ? n_batch : 256;
        LOGI("Processing prompt as batches, size=%zu, n_batch=%d", tokens_list.size(), batchSize);
        LOG_MEM("before_prompt_decode");

        int ret = 0;
        for (size_t offset = 0; offset < tokens_list.size(); offset += batchSize) {
            size_t nTokens = std::min((size_t)batchSize, tokens_list.size() - offset);
            llama_batch prompt_batch = llama_batch_get_one(tokens_list.data() + offset, (int)nTokens);
            ret = llama_decode(ctx, prompt_batch);
            if (ret != 0) {
                LOGE("llama_decode failed for prompt batch (offset=%zu, n=%zu) code: %d", offset, nTokens, ret);
                break;
            }
        }
        
        LOG_MEM("after_prompt_decode");
        
        if (ret != 0) {
            LOGE("llama_decode failed for prompt batch with code: %d", ret);
            std::string error = "llama_decode failed for prompt";
            setLastError(error);
            callback("", true, error);
            return false;
        }
        
        LOGI("Prompt evaluated successfully");
        
        int n_remain = maxTokens;
        int n_decode = 0;
        // 当前 KV cache 位置：prompt 已全部写入，后续每生成一个 token 位置 +1
        int n_past = (int)tokens_list.size();
        const int TIMEOUT_SECONDS = 120;
        // 思考 token 上限：防止非思考模型（如 qwen2.5-instruct）被强行引导后
        // 永不输出 </think>，耗尽全部 maxTokens 导致主回复为空
        const int THINKING_TOKEN_LIMIT = std::max(96, maxTokens / 2);
        std::string fullText;
        std::string thinkingText;
        std::string thinkingPending; // 思考待冲刷缓冲：暂扣可能是结束标记前缀的尾部
        bool inThinking = enableThinking;
        bool thinkingEnded = !enableThinking;
        int thinkingTokens = 0;
        // 生成终止时思考未结束的原因诊断（便于日志定位）
        std::string stopReason = "normal";
        
        auto start = std::chrono::steady_clock::now();
        
        while (n_remain > 0 && !shouldStop) {
            // KV cache 满则停止，防止 llama_decode 位置越界触发 ggml_abort 崩溃
            if (n_past >= n_ctx - 4) {
                LOGI("Context full, stopping generation (n_past=%d, n_ctx=%d)", n_past, n_ctx);
                stopReason = "ctx_full";
                break;
            }
            // Check for timeout
            auto currentTime = std::chrono::steady_clock::now();
            auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(currentTime - start).count();
            if (elapsed > TIMEOUT_SECONDS) {
                LOGI("TIMEOUT: Generation exceeded %d seconds", TIMEOUT_SECONDS);
                stopReason = "timeout";
                break;
            }
            
            // 使用 llama_sampler API 采样
            llama_token new_token_id = llama_sampler_sample(smpl, ctx, -1);
            llama_sampler_accept(smpl, new_token_id);
            
            // Check for EOS (end of sequence) token
            if (llama_vocab_is_eog(vocab, new_token_id)) {
                LOGI("EOS token detected, stopping generation");
                stopReason = "eos";
                break;
            }
            
            if (new_token_id == 151643 || new_token_id == 151644 || new_token_id == 151645 ||
                new_token_id == 128000 || new_token_id == 128001 || new_token_id == 128008 || new_token_id == 128009) {
                LOGI("Common EOS token ID detected: %d, stopping generation", new_token_id);
                stopReason = "eos";
                break;
            }
            
            char token_str[256] = {0};
            int n = llama_token_to_piece(vocab, new_token_id, token_str, sizeof(token_str), 0, true);
            if (n < 0) {
                break;
            }
            std::string token(token_str, n);
            
            if (token.find("<|im_end|>") != std::string::npos ||
                token.find("</s>") != std::string::npos ||
                token.find("<|im_sep|>") != std::string::npos) {
                LOGI("Stop word detected in token, stopping generation");
                stopReason = "stop_word";
                break;
            }
            
            if (inThinking && !thinkingEnded) {
                thinkingTokens++;
                thinkingPending += token;
                int markerLen = 0;
                int markerPos = findThinkingEndMarker(thinkingPending, markerLen);
                if (markerPos >= 0) {
                    // 发现结束标记（跨 token 拆分也能命中）：冲刷标记前的思考内容
                    if (markerPos > 0) {
                        callback(thinkingPending.substr(0, markerPos), false, "");
                    }
                    thinkingEnded = true;
                    callback("[THINK_END]", false, "");
                    // 标记之后的文本属于正文
                    std::string rest = thinkingPending.substr(markerPos + markerLen);
                    thinkingPending.clear();
                    if (!rest.empty()) {
                        fullText += rest;
                        callback(rest, false, "");
                    }
                } else if (thinkingTokens >= THINKING_TOKEN_LIMIT) {
                    // 思考 token 达到上限：强制结束思考，保留剩余额度给主回复，
                    // 避免思考无限延续导致主回复为空
                    if (!thinkingPending.empty()) {
                        thinkingText += thinkingPending;
                        callback(thinkingPending, false, "");
                        thinkingPending.clear();
                    }
                    thinkingEnded = true;
                    LOGI("Thinking token limit reached (%d), forcing THINK_END", thinkingTokens);
                    callback("[THINK_END]", false, "");
                } else {
                    // 冲刷安全前缀：暂扣末尾可能是标记前缀的字节，等待后续 token 拼接
                    size_t hold = partialMarkerTailLen(thinkingPending);
                    size_t flushLen = thinkingPending.size() - hold;
                    if (flushLen > 0) {
                        std::string chunk = thinkingPending.substr(0, flushLen);
                        thinkingText += chunk;
                        callback(chunk, false, "");
                        thinkingPending.erase(0, flushLen);
                    }
                }
            } else {
                fullText += token;
                callback(token, false, "");
            }
            
            llama_batch batch = llama_batch_get_one(&new_token_id, 1);
            ret = llama_decode(ctx, batch);
            n_past++;
            
            if (ret != 0) {
                LOGE("llama_decode failed with code: %d", ret);
                break;
            }
            
            n_remain--;
            n_decode++;
            currentTokenCount++;
            

        }
        
        llama_sampler_free(smpl);
        
        // 生成终止时思考仍未结束：冲刷残留思考内容并补发 [THINK_END]，保证 UI 思考布局正常闭合
        if (!thinkingEnded) {
            if (n_remain <= 0) stopReason = "max_tokens";
            if (shouldStop) stopReason = "user_stop";
            LOGW("Thinking NOT ended by model (reason=%s, thinkingTokens=%d, mainTokens=%d), "
                 "sending fallback [THINK_END]", stopReason.c_str(), thinkingTokens, n_decode - thinkingTokens);
            if (!thinkingPending.empty()) {
                callback(thinkingPending, false, "");
                thinkingPending.clear();
            }
            callback("[THINK_END]", false, "");
            thinkingEnded = true;
        }
        
        // Call callback with completion and full text
        callback(fullText, true, "");
        
        auto end = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(end - start).count();
        LOGI("=== STREAM GENERATE END ===");
        LOGI("Generated %d tokens in %lld s", n_decode, elapsed);
        
        return true;
        } catch (const std::exception& e) {
            LOGE("Exception in generateStream: %s", e.what());
            llama_sampler_free(smpl);
            std::string error = std::string("Generation exception: ") + e.what();
            setLastError(error);
            callback("", true, error);
            return false;
        } catch (...) {
            LOGE("Unknown exception in generateStream");
            llama_sampler_free(smpl);
            std::string error = "Unknown generation error";
            setLastError(error);
            callback("", true, error);
            return false;
        }
    }

    /**
     * KV cache 增量生成（P0-2 / spec §5.6）
     * 与 generateStream 签名一致，区别：
     * 1. 若新 prompt 是上一轮 prompt 的严格超集（token 级前缀匹配命中）且 KV 位置一致，
     *    只 eval 增量 token，不调用 llama_memory_clear，位置由 llama_decode 自动续接
     *    （llama_batch_get_one pos=nullptr → seq_pos_max+1，llama-batch.cpp:90-118）；
     * 2. 前缀未命中 / 首次调用 / KV 被外部路径改动（seq_pos_max 校验失败，R3-4）→ 全量重 eval。
     * 由 chatJson 调用（enableThinking 恒传 false，思考交给模板）。
     */

    bool generateStreamIncremental(const std::string& prompt, int maxTokens, float temperature, float topP, int topK, bool enableThinking, TokenCallback callback,
                                   const common_chat_params* chatParams = nullptr) {
        setPhase(GenPhase::PREPROCESS, "incr:entry");
        prefillStartTime = std::chrono::steady_clock::now();  // PREPROCESS：prefill 计时起点
        prefillDoneTokens = 0;
        prefillTotalTokens = 0;
        promptTotalTokens = 0;
        if (isGenerating.exchange(true)) {
            LOGE("generateStreamIncremental: already generating, rejecting concurrent call");
            callback("", true, "Generation already in progress");
            return false;
        }
        struct GeneratingGuard {
            std::atomic<bool>& flag;
            GeneratingGuard(std::atomic<bool>& f) : flag(f) {}
            ~GeneratingGuard() { flag = false; }
        } guard(isGenerating);

        inferenceStartTime = std::chrono::steady_clock::now();
        currentTokenCount = 0;

        LOGI("=== STREAM GENERATE INCREMENTAL START ===");
        LOGI("Prompt length: %zu, maxTokens: %d, temp=%f, topP=%f, topK=%d, thinking=%d", prompt.size(), maxTokens, temperature, topP, topK, enableThinking);
        LOG_MEM("generateStreamIncremental_start");

        if (!isValid()) {
            std::string error = "Model not initialized";
            setLastError(error);
            callback("", true, error);
            return false;
        }

        shouldStop = false;   // R4-3：每次进入复位，防上一次取消导致本次立即终止

        // 首 token 总延迟计时：函数入口（tokenize 前）→ 首个正文/思考 token 回调
        auto entryTime = std::chrono::steady_clock::now();

        // 创建 sampler chain（与 generateStream 一致）
        auto sparams = llama_sampler_chain_default_params();
        struct llama_sampler * smpl = llama_sampler_chain_init(sparams);
        // §6.1 官方 preserved_tokens：模板差分出的标记（<tool_call>、thinking 标记等）转 token id，
        // 参与 control suppression 时跳过，防止工具调用结构被采样器禁掉
        std::vector<llama_token> preservedIds;
        if (chatParams != nullptr) {
            for (const auto& s : chatParams->preserved_tokens) {
                if (s.empty()) continue;
                int n = -llama_tokenize(vocab, s.c_str(), s.size(), NULL, 0, true, false);
                if (n <= 0) continue;
                std::vector<llama_token> ids(n);
                if (llama_tokenize(vocab, s.c_str(), s.size(), ids.data(), ids.size(), true, false) < 0) continue;
                for (auto id : ids) {
                    if (std::find(preservedIds.begin(), preservedIds.end(), id) == preservedIds.end()) {
                        preservedIds.push_back(id);
                    }
                }
            }
            LOGI("grammar: preserved tokens -> %zu ids", preservedIds.size());
        }
        if (temperature <= 0) {
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        } else {
            addControlTokenSuppression(smpl, vocab, preservedIds);
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK > 0 ? topK : 40));
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0 ? topP : 0.9f, 1));
            // §6.3 官方 min_p 采样（llama.cpp 默认 0.05）：过滤低于 max_prob*min_p 的低概率
            // token，小模型输出更干净、减少乱码与低质量续写（对工具调用 JSON 生成尤其有用）
            llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
            llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.3f, 0.0f, 0.0f));
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }
        // §6.1 官方 grammar 挂载已移除：autoparser 对 Qwen3-VL 模板的工具格式推断
        // （XML 参数格式）与模型实际输出（JSON-in-tags）不一致，lazy 触发后遇 '{' 崩溃
        // （Unexpected empty grammar stack）。保留官方 preserved_tokens 与 additional_stops，
        // 工具参数完整性由流式 common_chat_parse 的 JSON 补全兜底。

        try {
        std::string promptToUse = prompt;
        if (enableThinking) {
            promptToUse += "<think>\n";
            LOGI("generateStreamIncremental: enableThinking=true, appended <think>\\n");
        }

        // Tokenize
        std::vector<llama_token> tokens_list;
        int n_tokens = -llama_tokenize(vocab, promptToUse.c_str(), promptToUse.size(), NULL, 0, true, true);
        if (n_tokens < 0) {
            std::string error = "Failed to tokenize prompt";
            setLastError(error);
            callback("", true, error);
            return false;
        }
        tokens_list.resize(n_tokens);
        if (llama_tokenize(vocab, promptToUse.c_str(), promptToUse.size(), tokens_list.data(), tokens_list.size(), true, true) < 0) {
            std::string error = "Failed to tokenize prompt";
            setLastError(error);
            callback("", true, error);
            return false;
        }
        if (tokens_list.empty()) {
            std::string error = "Failed to tokenize prompt";
            setLastError(error);
            callback("", true, error);
            return false;
        }

        llama_memory_t mem = llama_get_memory(ctx);

        // ===== KV 增量判定（§5.6）=====
        // 三级策略封装于 AgentKvCache（agent_kv_cache.cpp，Agent 专用独立文件）：
        //   1. 完全超集 → 只 eval 增量（最省）
        //   2. 部分前缀匹配（tools JSON 增长/历史变化）→ seq_rm 截断复用前缀
        //   3. 前缀为空 / KV 外部改动 → 全量重 eval
        kvCache.plan(tokens_list, mem);

        // 全量路径：软清除 KV（首次调用 / 前缀失配 / 外部改动）
        if (kvCache.isFull() && mem != nullptr) {
            llama_memory_clear(mem, false);
        }

        // PARTIAL 路径：截断失配点之后的 KV；失败回退全量
        bool useIncremental = !kvCache.isFull();
        if (kvCache.isPartial()) {
            if (kvCache.truncate(mem)) {
                // 截断成功，增量 eval
            } else {
                useIncremental = false;
                llama_memory_clear(mem, false);
                AGENT_KV_LOGI("PARTIAL truncate failed, full eval %zu tokens", tokens_list.size());
            }
        }

        std::vector<llama_token> evalTokens;
        if (useIncremental) {
            evalTokens.assign(tokens_list.begin() + kvCache.matchedLen(), tokens_list.end());
        } else {
            evalTokens = tokens_list;
        }

        int n_ctx = llama_n_ctx(ctx);
        kvCache.setContextSize(n_ctx);   // KV 监控：记录 n_ctx 供上下文占用率计算
        promptTotalTokens = (int)tokens_list.size();   // 完整 prompt 大小（含历史），供状态条统计显示
        LOGI("Context size: n_ctx=%d, prompt_tokens=%zu, maxTokens=%d", n_ctx, tokens_list.size(), maxTokens);
        // 修复：maxTokens 是生成停止上限，不计入 context 预算（生成循环有 n_ctx-4 guard 优雅停止）。
        // 只校验 prompt 本体是否放得下，预留固定生成余量。
        const int GENERATION_RESERVE = 512;
        if ((int)tokens_list.size() > n_ctx - GENERATION_RESERVE) {
            std::string error = "Prompt too long: " + std::to_string(tokens_list.size()) + " tokens >= "
                + std::to_string(n_ctx - GENERATION_RESERVE);
            LOGE("%s", error.c_str());
            setLastError(error);
            callback("", true, error);
            return false;
        }

        // 分块解码 evalTokens（batch ≤ n_batch，pos 自动续接）
        const int n_batch = llama_n_batch(ctx);
        const int batchSize = n_batch > 0 ? n_batch : 256;
        LOGI("Processing eval batch, size=%zu, n_batch=%d", evalTokens.size(), batchSize);
        LOG_MEM("before_prompt_decode");

        int ret = 0;
        prefillTotalTokens = (int)evalTokens.size();
        for (size_t offset = 0; offset < evalTokens.size(); offset += batchSize) {
            size_t nTokens = std::min((size_t)batchSize, evalTokens.size() - offset);
            prefillDoneTokens += (int)nTokens;   // PREPROCESS：逐块累积已处理 prompt token
            llama_batch prompt_batch = llama_batch_get_one(evalTokens.data() + offset, (int)nTokens);
            ret = llama_decode(ctx, prompt_batch);
            if (ret != 0) {
                LOGE("llama_decode failed for eval batch (offset=%zu, n=%zu) code: %d", offset, nTokens, ret);
                break;
            }
        }
        if (ret != 0) {
            LOGE("llama_decode failed for eval with code: %d", ret);
            std::string error = "llama_decode failed for prompt";
            setLastError(error);
            callback("", true, error);
            return false;
        }

        // 记账统一在生成结束后由 kvCache.record() 完成（prompt + 输出），
        // 中途失败时缓存保持上一轮状态，由下轮 seq_pos_max 校验兜底为全量。

        // ===== 生成循环（与 generateStream 一致）=====
        setPhase(enableThinking ? GenPhase::THINKING : GenPhase::GENERATING, "incr:gen_loop");
        if (enableThinking) {
            thinkingStartTime = std::chrono::steady_clock::now();  // 思考段速度计时起点
            thinkingTokenCount = 0;
        }
        int n_remain = maxTokens;
        int n_decode = 0;
        int n_past = (int)tokens_list.size();
        std::vector<llama_token> generatedTokens;   // 记录生成输出 token，供 KV 增量记账
        // 生成超时：长回答/深度思考时给足时间，不掐断输出（Agent 总时长另有兜底）
        const int TIMEOUT_SECONDS = 400;
        const int THINKING_TOKEN_LIMIT = std::max(96, maxTokens / 2);
        std::string fullText;
        std::string thinkingText;
        std::string thinkingPending;
        bool inThinking = enableThinking;
        bool thinkingEnded = !enableThinking;
        int thinkingTokens = 0;
        std::string stopReason = "normal";
        std::string stopBuf;   // §6.2 additional_stops 跨 token 尾部匹配缓冲

        auto start = std::chrono::steady_clock::now();
        decodeStartTime = std::chrono::steady_clock::now();  // decode 速度计时起点
        decodeTokenCount = 0;
        // 首次 token 计时（诊断首轮 prefill 预热效果）：首个正文 token 回调时记录
        bool firstTokenLogged = false;
        auto firstTokenTime = start;
        bool firstTokenWasThinking = false;

        while (n_remain > 0 && !shouldStop) {
            if (n_past >= n_ctx - 4) {
                LOGI("Context full, stopping generation (n_past=%d, n_ctx=%d)", n_past, n_ctx);
                setStop(StopCause::CTX_FULL, "incr:ctx_full");
                setPhase(GenPhase::COMPLETE, "incr:ctx_full");
                stopReason = "ctx_full";
                break;
            }
            auto currentTime = std::chrono::steady_clock::now();
            auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(currentTime - start).count();
            if (elapsed > TIMEOUT_SECONDS) {
                LOGI("TIMEOUT: Generation exceeded %d seconds", TIMEOUT_SECONDS);
                setStop(StopCause::TIMEOUT, "incr:timeout");
                setPhase(GenPhase::COMPLETE, "incr:timeout");
                stopReason = "timeout";
                break;
            }

            llama_token new_token_id = llama_sampler_sample(smpl, ctx, -1);
            llama_sampler_accept(smpl, new_token_id);

            if (llama_vocab_is_eog(vocab, new_token_id)) {
                LOGI("EOS token detected, stopping generation");
                setStop(StopCause::EOS, "incr:eog");
                setPhase(GenPhase::COMPLETE, "incr:eog");
                stopReason = "eos";
                break;
            }
            if (new_token_id == 151643 || new_token_id == 151644 || new_token_id == 151645 ||
                new_token_id == 128000 || new_token_id == 128001 || new_token_id == 128008 || new_token_id == 128009) {
                LOGI("Common EOS token ID detected: %d, stopping generation", new_token_id);
                setStop(StopCause::EOS, "incr:eos_id");
                setPhase(GenPhase::COMPLETE, "incr:eos_id");
                stopReason = "eos";
                break;
            }

            char token_str[256] = {0};
            int n = llama_token_to_piece(vocab, new_token_id, token_str, sizeof(token_str), 0, true);
            if (n < 0) break;
            std::string token(token_str, n);

            if (token.find("<|im_end|>") != std::string::npos ||
                token.find("</s>") != std::string::npos ||
                token.find("<|im_sep|>") != std::string::npos) {
                LOGI("Stop word detected in token, stopping generation");
                setStop(StopCause::STOP_WORD, "incr:stop_word");
                setPhase(GenPhase::COMPLETE, "incr:stop_word");
                stopReason = "stop_word";
                break;
            }

            if (inThinking && !thinkingEnded) {
                thinkingTokens++;
                thinkingTokenCount++;  // 分阶段速度：思考段 token 计数
                thinkingPending += token;
                if (!firstTokenLogged) {
                    firstTokenLogged = true;
                    firstTokenTime = std::chrono::steady_clock::now();
                    firstTokenWasThinking = true;
                }
                int markerLen = 0;
                int markerPos = findThinkingEndMarker(thinkingPending, markerLen);
                if (markerPos >= 0) {
                    if (markerPos > 0) {
                        callback(thinkingPending.substr(0, markerPos), false, "");
                    }
                    thinkingEnded = true;
                    setPhase(GenPhase::GENERATING, "incr:think_end_marker");
                    decodeStartTime = std::chrono::steady_clock::now();
                    decodeTokenCount = 0;
                    callback("[THINK_END]", false, "");
                    std::string rest = thinkingPending.substr(markerPos + markerLen);
                    thinkingPending.clear();
                    if (!rest.empty()) {
                        fullText += rest;
                        callback(rest, false, "");
                    }
                } else if (thinkingTokens >= THINKING_TOKEN_LIMIT) {
                    if (!thinkingPending.empty()) {
                        thinkingText += thinkingPending;
                        callback(thinkingPending, false, "");
                        thinkingPending.clear();
                    }
                    thinkingEnded = true;
                    setPhase(GenPhase::GENERATING, "incr:think_limit");
                    decodeStartTime = std::chrono::steady_clock::now();
                    decodeTokenCount = 0;
                    setStop(StopCause::TOKEN_LIMIT, "incr:think_limit");
                    LOGI("Thinking token limit reached (%d), forcing THINK_END", thinkingTokens);
                    callback("[THINK_END]", false, "");
                } else {
                    size_t hold = partialMarkerTailLen(thinkingPending);
                    size_t flushLen = thinkingPending.size() - hold;
                    if (flushLen > 0) {
                        std::string chunk = thinkingPending.substr(0, flushLen);
                        thinkingText += chunk;
                        callback(chunk, false, "");
                        thinkingPending.erase(0, flushLen);
                    }
                }
            } else {
                if (!firstTokenLogged) {
                    firstTokenLogged = true;
                    firstTokenTime = std::chrono::steady_clock::now();
                    firstTokenWasThinking = false;
                }
                fullText += token;
                callback(token, false, "");
            }

            // §6.2 官方 additional_stops：跨 token 尾部缓冲匹配（支持停止词被拆分成多个 token），
            // 命中即停止，并把已累积输出中的停止词尾部裁掉（官方 server 同款语义）
            {
                stopBuf += token;
                if (stopBuf.size() > 128) stopBuf.erase(0, stopBuf.size() - 128);
                if (chatParams != nullptr) {
                    size_t swMatchLen = 0;
                    for (const auto& sw : chatParams->additional_stops) {
                        if (sw.empty() || stopBuf.size() < sw.size()) continue;
                        size_t pos = stopBuf.size() - sw.size();
                        if (stopBuf.compare(pos, sw.size(), sw) == 0) {
                            swMatchLen = sw.size();
                            break;
                        }
                    }
                    if (swMatchLen > 0) {
                        const std::string swText = stopBuf.substr(stopBuf.size() - swMatchLen);
                        if (!thinkingEnded && thinkingPending.size() >= swMatchLen
                                && thinkingPending.compare(thinkingPending.size() - swMatchLen, swMatchLen, swText) == 0) {
                            thinkingPending.erase(thinkingPending.size() - swMatchLen);
                        } else if (fullText.size() >= swMatchLen
                                && fullText.compare(fullText.size() - swMatchLen, swMatchLen, swText) == 0) {
                            fullText.erase(fullText.size() - swMatchLen);
                        }
                        LOGI("Stop word matched (cross-token): '%s' len=%zu, stopping generation", swText.c_str(), swMatchLen);
                        setStop(StopCause::STOP_WORD, "incr:add_stop");
                        setPhase(GenPhase::COMPLETE, "incr:add_stop");
                        stopReason = "stop_word";
                        break;
                    }
                }
            }

            llama_batch batch = llama_batch_get_one(&new_token_id, 1);
            ret = llama_decode(ctx, batch);
            n_past++;

            if (ret != 0) {
                LOGE("llama_decode failed with code: %d", ret);
                setStop(StopCause::ERROR, "incr:decode_fail");
                setPhase(GenPhase::ERROR, "incr:decode_fail");
                break;
            }
            generatedTokens.push_back(new_token_id);   // KV 增量记账：记录已 decode 进 KV 的输出 token
            n_remain--;
            n_decode++;
            currentTokenCount++;
            if (!inThinking) decodeTokenCount++;  // 纯 decode 速度：仅正文生成阶段计数
        }

        llama_sampler_free(smpl);

        // 首次 token 时间日志（诊断首轮 prefill 预热效果）
        if (firstTokenLogged) {
            auto ftElapsed = std::chrono::duration<double>(firstTokenTime - start).count();
            auto totalElapsed = std::chrono::duration<double>(firstTokenTime - entryTime).count();
            LOGI("[PERF] First token in %.3fs (from gen loop start; thinking=%d); total entry->first=%.3fs",
                 ftElapsed, (int)firstTokenWasThinking, totalElapsed);
        } else {
            LOGI("[PERF] No token produced (stop=%s)", stopReason.c_str());
        }

        if (!thinkingEnded) {
            if (n_remain <= 0) stopReason = "max_tokens";
            if (shouldStop) stopReason = "user_stop";
            LOGW("Thinking NOT ended by model (reason=%s, thinkingTokens=%d, mainTokens=%d), "
                 "sending fallback [THINK_END]", stopReason.c_str(), thinkingTokens, n_decode - thinkingTokens);
            if (!thinkingPending.empty()) {
                callback(thinkingPending, false, "");
                thinkingPending.clear();
            }
            callback("[THINK_END]", false, "");
            thinkingEnded = true;
            setPhase(GenPhase::GENERATING, "incr:think_fallback_end");
        decodeStartTime = std::chrono::steady_clock::now();
        decodeTokenCount = 0;
        }

        callback(fullText, true, "");

        // 生成结束后并入输出 token 到 KV 记账（AgentKvCache.record）：
        // 下一轮新 prompt 通常是"本轮 prompt + 输出 + 追加消息"的超集，只有记账包含输出 token，
        // 前缀匹配（matchedLen == cachedNPast）与 seq_pos_max 校验（== cachedNPast-1）才能命中增量。
        kvCache.record(tokens_list, generatedTokens);

        auto end = std::chrono::steady_clock::now();
        auto elapsedTotal = std::chrono::duration_cast<std::chrono::seconds>(end - start).count();
        LOGI("=== STREAM GENERATE INCREMENTAL END ===");
        LOGI("Generated %d tokens in %lld s (stop=%s, incremental=%d, phase=%s)", n_decode, elapsedTotal, stopReason.c_str(), (int)kvCache.isIncremental(), phaseName(phase));
        setPhase(GenPhase::COMPLETE, "incr:end");

        return true;
        } catch (const std::exception& e) {
            LOGE("Exception in generateStreamIncremental: %s", e.what());
            setPhase(GenPhase::ERROR, "incr:exception");
            setStop(StopCause::ERROR, "incr:exception");
            llama_sampler_free(smpl);
            std::string error = std::string("Generation exception: ") + e.what();
            setLastError(error);
            callback("", true, error);
            return false;
        } catch (...) {
            LOGE("Unknown exception in generateStreamIncremental");
            llama_sampler_free(smpl);
            std::string error = "Unknown generation error";
            setLastError(error);
            callback("", true, error);
            return false;
        }
    }

    // 单次生成路径：接收消息列表，用 llama_chat_apply_template 自动适配模型格式
    // 不污染多轮对话状态（chatMessages / prev_formatted_len）
    bool generateStreamFromMessages(const std::vector<std::pair<std::string, std::string>>& messages,
                                     int maxTokens, float temperature, float topP, int topK,
                                     bool enableThinking, TokenCallback callback) {
        LOGI("=== STREAM GENERATE FROM MESSAGES START ===");
        LOGI("Messages count: %zu, maxTokens: %d, thinking=%d", messages.size(), maxTokens, (int)enableThinking);

        // 用 chat template 格式化消息（addAssistantStart=true，末尾加 assistant 开始标记）
        std::string prompt = applyChatTemplateForMessages(messages, true);
        if (prompt.empty()) {
            std::string error = "Failed to apply chat template for messages";
            setLastError(error);
            callback("", true, error);
            return false;
        }
        LOGI("Formatted prompt length: %zu", prompt.size());

        // 思考链：在 assistant 开始标记后加 <think>
        if (enableThinking) {
            prompt += "<think>\n";
            LOGI("generateStreamFromMessages: enableThinking=true, appended <think>\\n");
        }

        // 复用 generateStream 的核心生成逻辑
        return generateStream(prompt, maxTokens, temperature, topP, topK, enableThinking, callback);
    }

    /**
     * 原生 Function Calling 生成
     * 使用 common_chat_templates_apply 传入 tools，由 llama.cpp 底层自动：
     * 1. 根据模型模板格式化 tools（Qwen Hermes/Llama3/Mistral 等）
     * 2. 生成符合模型原生的 tool_call 格式
     * 3. 解析模型输出的 tool_calls
     *
     * @param messages 对话消息列表
     * @param toolsJson OpenAI 格式的 tools JSON 数组
     * @param maxTokens 最大生成 token 数
     * @param temperature 温度
     * @param topP top_p
     * @param topK top_k
     * @param enableThinking 是否启用思考模式
     * @param callback token 回调
     * @return 生成是否成功
     */
    // common_chat_parse 解析结果结构体
    struct ChatParseResult {
        std::vector<common_chat_tool_call> tool_calls;
        std::string reasoning_content;
    };

    bool generateWithTools(const std::vector<std::pair<std::string, std::string>>& messages,
                           const std::string& toolsJson,
                           int maxTokens, float temperature, float topP, int topK,
                           bool enableThinking, TokenCallback callback,
                           ChatParseResult* outParseResult = nullptr) {
        LOGI("=== GENERATE WITH TOOLS START ===");
        LOGI("Messages: %zu, toolsJson len: %zu, maxTokens: %d, thinking=%d",
             messages.size(), toolsJson.size(), maxTokens, (int)enableThinking);

        if (!model) {
            std::string error = "Model not loaded";
            setLastError(error);
            callback("", true, error);
            return false;
        }

        // 初始化 chat templates（使用模型内置模板）
        auto chat_templates = common_chat_templates_init(model, "");
        if (!chat_templates) {
            LOGW("Failed to init chat templates, falling back to basic generateStreamFromMessages");
            return generateStreamFromMessages(messages, maxTokens, temperature, topP, topK, enableThinking, callback);
        }

        // 构建 common_chat_msg 列表
        std::vector<common_chat_msg> chat_msgs;
        for (auto& m : messages) {
            common_chat_msg msg;
            msg.role = m.first;
            msg.content = m.second;
            chat_msgs.push_back(msg);
        }

        // 解析 tools JSON
        std::vector<common_chat_tool> tools;
        if (!toolsJson.empty()) {
            try {
                auto tools_json = common_json::parse(toolsJson);
                tools = common_chat_tools_parse_oaicompat(tools_json);
                LOGI("Parsed %zu tools from JSON", tools.size());
            } catch (const std::exception& e) {
                LOGW("Failed to parse tools JSON: %s, continuing without tools", e.what());
            }
        }

        // 构建模板输入
        common_chat_templates_inputs inputs;
        inputs.messages = chat_msgs;
        inputs.tools = tools;
        // 工具场景强制 REQUIRED：generateWithTools 仅 Agent 阶段1（已判定涉及工具）使用，
        // 强制模型输出 tool_call（llama.cpp 对 Qwen3 支持 REQUIRED），
        // 避免模型"空输出/直接回答不调工具"导致工具调用失败
        inputs.tool_choice = tools.empty() ? COMMON_CHAT_TOOL_CHOICE_NONE : COMMON_CHAT_TOOL_CHOICE_REQUIRED;
        inputs.parallel_tool_calls = true;
        inputs.add_generation_prompt = true;
        inputs.use_jinja = true;
        // 仅当模板支持时才启用 thinking，否则模板引擎内部会 abort（signal 6）
        bool supports_thinking = common_chat_templates_support_enable_thinking(chat_templates.get());
        inputs.enable_thinking = enableThinking && supports_thinking;
        LOGI("enable_thinking=%d, template supports=%d, final=%d",
             (int)enableThinking, (int)supports_thinking, (int)inputs.enable_thinking);

        // 应用模板
        common_chat_params chat_params;
        try {
            chat_params = common_chat_templates_apply(chat_templates.get(), inputs);
            LOGI("Chat template applied, prompt length: %zu, format: %s",
                 chat_params.prompt.size(), common_chat_format_name(chat_params.format));
            // 提取模板思考标签（统一标签源），供流式思考段剥离
            mThinkStartTag = chat_params.thinking_start_tag;
            mThinkEndTags = chat_params.thinking_end_tags;
            LOGI("Thinking tags from template: start='%s', %zu end tag(s)",
                 mThinkStartTag.c_str(), mThinkEndTags.size());
        } catch (const std::exception& e) {
            LOGW("Failed to apply chat template with tools: %s, falling back", e.what());
            return generateStreamFromMessages(messages, maxTokens, temperature, topP, topK, enableThinking, callback);
        }

        if (chat_params.prompt.empty()) {
            std::string error = "Chat template produced empty prompt";
            setLastError(error);
            callback("", true, error);
            return false;
        }

        // 不再传 enableThinking=true 给 generateStream。
        // 模型想输出什么就输出什么：如果模型自己决定思考，chat template 会处理；
        // 如果模型直接回答，generateStream 正常输出 token。
        // 生成完成后用 common_chat_parse 解析模型输出，提取 tool_calls 和 reasoning_content。
        // 解析结果通过结构体传给 JNI 层，JNI 层直接调用 Java 的 onToolCalls/onReasoning 回调，
        // 与在线 Agent 使用相同的 ToolCallInfo 格式，工具调用互通。

        // 用包装回调收集 fullText，拦截 onComplete 以便在 common_chat_parse 解析后再发送。
        // 同时按模板思考标签剥离思考段：思考内容不流式发给 UI（reasoning 由 common_chat_parse 提取）。
        std::string collectedText;
        bool thinkActive = false;   // 跨 chunk 思考状态（模板标签式，非 generateStream 思考分支）
        auto wrappedCallback = [this, &callback, &collectedText, &thinkActive](const std::string& text, bool isComplete, const std::string& error) {
            if (!isComplete && !error.empty()) {
                callback(text, isComplete, error);
                return;
            }
            if (isComplete && !text.empty()) {
                collectedText = text;
                // 不立即触发 onComplete，等 common_chat_parse 解析完后再触发
                return;
            }
            if (!isComplete) {
                std::string stripped = stripThinkingChunk(text, thinkActive);
                if (!stripped.empty()) {
                    callback(stripped, false, "");
                }
            }
        };

        bool genOk = generateStream(chat_params.prompt, maxTokens, temperature, topP, topK, false, wrappedCallback);

        // 生成完成后，用 common_chat_parse 解析模型输出
        // 解析结果存到 outParseResult，由 JNI 层直接调用 Java 的 onToolCalls/onReasoning 回调
        // 与在线 Agent 使用相同的 ToolCallInfo 格式，工具调用互通，无需中间层
        if (genOk && !collectedText.empty()) {
            try {
                common_chat_parser_params parser_params(chat_params);
                parser_params.parse_tool_calls = true;
                common_chat_msg parsed = common_chat_parse(collectedText, false, parser_params);

                if (outParseResult != nullptr) {
                    outParseResult->tool_calls = std::move(parsed.tool_calls);
                    outParseResult->reasoning_content = std::move(parsed.reasoning_content);
                    if (!outParseResult->tool_calls.empty()) {
                        LOGI("Parsed %zu tool calls via common_chat_parse", outParseResult->tool_calls.size());
                    }
                    if (!outParseResult->reasoning_content.empty()) {
                        LOGI("Parsed reasoning content (%zu chars) via common_chat_parse", outParseResult->reasoning_content.size());
                    }
                }
            } catch (const std::exception& e) {
                LOGW("common_chat_parse failed: %s", e.what());
            }
        }

        // 触发 onComplete
        callback(collectedText, true, "");

        return genOk;
    }

    /**
     * 统一 JSON 协议入口（spec §4/§5.1）
     * 请求：{"action":"chat","messages":[...],"tools":[...],"tool_choice":"auto|required|none",
     *        "enable_thinking":bool,"max_tokens":int,"temperature":float,"top_p":float,"top_k":int}
     * 回调事件：token / tool_call / reasoning / complete / error（§4.2）
     * 内部：common_chat_templates_apply 格式化 → generateStreamIncremental（KV 增量）→
     *       common_chat_parse 解析 → 统一 JSON 回调
     */
    bool chatJson(const std::string& requestJson, JsonCallback jsonCallback) {
        // step 0（R3-3 修正，D5）：并发守卫由 generateStreamIncremental 内部持有——
        // 若此处先 exchange(isGenerating) 再调用 generateStreamIncremental（同一标志），会自锁
        // （"Generation already in progress"）。并发安全实际由：
        //   1) Java 侧 LlamaHelper.inferenceLock 写锁串行化所有推理调用（chatJson/generateStream/chatSend 同锁）
        //   2) generateStreamIncremental 自身 isGenerating guard 兜底
        // 此处仅复位 shouldStop（R4-3），防上一次取消导致本次立即终止。
        shouldStop = false;

        // 完成事件单发守卫：error 与 complete 互斥且每轮只向 Java 发一次（R5-2/A5 加固）
        std::atomic<bool> eventSent{false};
        auto sendError = [&jsonCallback, &eventSent](const std::string& msg) {
            if (eventSent.exchange(true)) {
                LOGW("chatJson: duplicate error event suppressed: %s", msg.c_str());
                return;
            }
            nlohmann::ordered_json j = {{"type", "error"}, {"message", msg}};
            if (jsonCallback) jsonCallback(j.dump());
        };

        // step 1（R8-3）：ordered_json 解析（与 common_chat_*_parse_oaicompat 参数类型一致）
        common_json req;
        try {
            req = common_json::parse(requestJson);
        } catch (const std::exception& e) {
            LOGE("chatJson: JSON parse failed: %s", e.what());
            sendError("JSON parse failed");
            return false;
        }

        // step 2：messages
        std::vector<common_chat_msg> messages;
        try {
            if (!req.contains("messages") || !req["messages"].is_array() || req["messages"].empty()) {
                sendError("messages is empty or missing");
                return false;
            }
            messages = common_chat_msgs_parse_oaicompat(req["messages"]);
        } catch (const std::exception& e) {
            LOGW("chatJson: messages parse failed: %s", e.what());
            sendError(std::string("messages parse failed: ") + e.what());
            return false;
        }
        LOGI("chatJson: parsed %zu messages", messages.size());

        // step 3：tools（可选）
        std::vector<common_chat_tool> tools;
        if (req.contains("tools") && req["tools"].is_array()) {
            try {
                tools = common_chat_tools_parse_oaicompat(req["tools"]);
                LOGI("chatJson: parsed %zu tools", tools.size());
            } catch (const std::exception& e) {
                LOGW("chatJson: tools parse failed: %s, continuing without tools", e.what());
            }
        }

        // step 4：参数
        std::string toolChoiceStr = req.value("tool_choice", std::string("auto"));
        common_chat_tool_choice toolChoice = COMMON_CHAT_TOOL_CHOICE_AUTO;
        try {
            toolChoice = common_chat_tool_choice_parse_oaicompat(toolChoiceStr);
        } catch (const std::exception& e) {
            LOGW("chatJson: invalid tool_choice '%s', using auto", toolChoiceStr.c_str());
        }
        // R10-1：enable_thinking 缺失时默认 false（R8-1 后与产品决策一致，Agent 模式不思考）
        bool enableThinking = req.value("enable_thinking", false);
        int maxTokens = req.value("max_tokens", 500);
        float temperature = req.value("temperature", 0.6f);
        float topP = req.value("top_p", 0.9f);
        int topK = req.value("top_k", 40);
        LOGI("chatJson: tool_choice=%s, enable_thinking=%d, max_tokens=%d, temp=%f, topP=%f, topK=%d",
             toolChoiceStr.c_str(), (int)enableThinking, maxTokens, temperature, topP, topK);

        // step 5：构建模板输入
        auto chat_templates = common_chat_templates_init(model, "");
        if (!chat_templates) {
            LOGW("chatJson: Failed to init chat templates");
            sendError("Failed to init chat templates");
            return false;
        }
        common_chat_templates_inputs inputs;
        inputs.messages = messages;
        inputs.tools = tools;
        inputs.tool_choice = toolChoice;
        inputs.parallel_tool_calls = true;
        inputs.add_generation_prompt = true;
        inputs.use_jinja = true;
        bool supports_thinking = common_chat_templates_support_enable_thinking(chat_templates.get());
        inputs.enable_thinking = enableThinking && supports_thinking;
        LOGI("chatJson: enable_thinking=%d, template supports=%d, final=%d",
             (int)enableThinking, (int)supports_thinking, (int)inputs.enable_thinking);

        // step 6：应用模板
        common_chat_params chat_params;
        try {
            chat_params = common_chat_templates_apply(chat_templates.get(), inputs);
            LOGI("chatJson: template applied, prompt length: %zu, format: %s",
                 chat_params.prompt.size(), common_chat_format_name(chat_params.format));
            // 提取模板思考标签（统一标签源），供流式思考段剥离
            mThinkStartTag = chat_params.thinking_start_tag;
            mThinkEndTags = chat_params.thinking_end_tags;
            LOGI("chatJson: thinking tags from template: start='%s', %zu end tag(s)",
                 mThinkStartTag.c_str(), mThinkEndTags.size());
            // 调试：打印模板 prompt 开头与末尾，排查"模型输出模板前缀/双前缀"问题
            std::string p0 = chat_params.prompt.substr(0, 250);
            std::string p1 = chat_params.prompt.size() > 250
                    ? chat_params.prompt.substr(chat_params.prompt.size() - 300) : "";
            LOGI("chatJson: PROMPT_HEAD>>%s<<", p0.c_str());
            if (!p1.empty()) LOGI("chatJson: PROMPT_TAIL>>%s<<", p1.c_str());
        } catch (const std::exception& e) {
            // R3-5：fallback 后失效 KV 增量缓存（generateStreamFromMessages 内部会清 KV）
            LOGW("chatJson: template apply failed (%s), falling back to flat messages", e.what());
            kvCache.invalidate();

            // 扁平化降级：结构化消息 → pair<role,content>（tool_calls/tool_call_id 拼进 content）
            std::vector<std::pair<std::string, std::string>> flat;
            for (auto& m : messages) {
                std::string content = m.content;
                if (!m.tool_calls.empty()) {
                    nlohmann::ordered_json arr = nlohmann::ordered_json::array();
                    for (auto& tc : m.tool_calls) {
                        arr.push_back({{"id", tc.id}, {"name", tc.name}, {"arguments", tc.arguments}});
                    }
                    if (!content.empty()) content += "\n";
                    content += "<tool_calls>" + arr.dump() + "</tool_calls>";
                }
                if (!m.tool_call_id.empty()) {
                    if (!content.empty()) content += "\n";
                    content += "[tool_call_id: " + m.tool_call_id + "]";
                }
                flat.push_back({m.role, content});
            }
            bool fbOk = generateStreamFromMessages(flat, maxTokens, temperature, topP, topK, false,
                [&jsonCallback, &eventSent](const std::string& text, bool isDone, const std::string& error) {
                    if (!isDone) {
                        if (error.empty() && !text.empty()) {
                            nlohmann::ordered_json j = {{"type", "token"}, {"content", text}, {"is_tool_call", false}};
                            jsonCallback(j.dump());
                        }
                    } else if (!error.empty()) {
                        if (!eventSent.exchange(true)) {
                            nlohmann::ordered_json j = {{"type", "error"}, {"message", error}};
                            jsonCallback(j.dump());
                        } else {
                            LOGW("chatJson: fallback duplicate error suppressed");
                        }
                    } else {
                        if (!eventSent.exchange(true)) {
                            nlohmann::ordered_json j = {{"type", "complete"}, {"content", text}};
                            jsonCallback(j.dump());
                        } else {
                            LOGW("chatJson: fallback duplicate complete suppressed");
                        }
                    }
                });
            if (!fbOk && !shouldStop) {
                sendError(getLastError().empty() ? "Generation failed (fallback)" : getLastError());
            }
            return fbOk;
        }

        if (chat_params.prompt.empty()) {
            sendError("Chat template produced empty prompt");
            return false;
        }

        // ===== step 7：生成阶段（阶段化处理器 + 状态机驱动）=====
        clearThinkingContent();             // 实时思考内容监控：新推理清空上一轮
        // 状态机 GenPhase 是本阶段的单一事实源：
        //   THINKING   → ThinkingStage：思考段识别/剥离/累积（思考内容监控）
        //   GENERATING → GeneratingStage：tool_call 增量检测 + 发 token
        // generateStreamIncremental 按 enableThinking=false 会在 gen_loop 置 GENERATING，
        // 本层每个 token 回调后由 ThinkingStage 校正为实际阶段，保证 UI/监控对齐。
        std::string collectedText;          // 完整输出（原始字节，供 parse）
        std::string utf8Buffer;             // R9-1：token 级 UTF-8 完整性缓冲
        std::string genError;               // 生成失败信息（R7-1）
        // §5.2 第一阶段：required → 所有 token 标记 is_tool_call=true；auto/none → false
        bool isInToolCall = (toolChoice == COMMON_CHAT_TOOL_CHOICE_REQUIRED);
        // 思考态识别：模板 enable_thinking=true 时模型会输出  thinking... response，
        // 生成循环 thinking=0（思考交给模板），故流式阶段需自行识别思考段——
        // 思考 token 标记 is_thinking=true（UI 折叠显示、不朗读），标签本身剥离
        // 关键：Qwen 模板把思考起始标签预置到 assistant 前缀（prompt 以 start tag 结尾），
        // 模型直接续写思考内容而不会再次输出起始标签——此时流式须初始处于思考态，
        // 否则思考内容被当作正文下发（泄漏到 UI/TTS）。
        bool promptTailInThinking = false;
        {
            std::string tail = chat_params.prompt;
            size_t lastNs = tail.find_last_not_of(" \t\r\n");
            if (lastNs != std::string::npos) tail = tail.substr(0, lastNs + 1);
            promptTailInThinking = !mThinkStartTag.empty()
                && tail.size() >= mThinkStartTag.size()
                && tail.compare(tail.size() - mThinkStartTag.size(), mThinkStartTag.size(), mThinkStartTag) == 0;
        }
        bool isInThinking = promptTailInThinking;
        if (isInThinking) {
            setPhase(GenPhase::THINKING, "chatJson:init_think");
            LOGI("chatJson: initial thinking state (prompt tail ends with start tag)");
        }
        std::string thinkingAccum;   // 用于检测标签边界（跨 token 的标签片段）
        // §5.2 第二阶段（阶段 4 优化）：auto/none 模式用 is_partial 增量解析检测 tool_call 起始，
        // 检测到后锁定 is_tool_call=true，减少 UI 短暂闪烁（已发出的前几个 token 无法撤回）
        const int PARTIAL_PARSE_INTERVAL = 4;   // 每收 N 个 token 检测一次（16→4：缩短泄漏窗口）
        int partialParseCounter = 0;

        // §5.3 官方流式 tool_call 增量发布：复用 common_chat_parse（PEG + pending_tool_call）
        // 的 partial 解析结果，diff 已发布集合，只发布"arguments 已闭合"且未发布过的 tool_call。
        // Java 侧 AgentLoopEngine 流式收集（协议 tool_call 事件），complete 后再兜底漏网的。
        std::vector<std::string> publishedToolCalls;
        auto toolCallKey = [](const common_chat_tool_call& tc) -> std::string {
            return tc.id.empty() ? ("nm:" + tc.name + "|" + tc.arguments) : ("id:" + tc.id);
        };
        auto jsonArgsClosed = [](const std::string& args) -> bool {
            if (args.empty() || args.front() != '{') return false;
            int depth = 0; bool inStr = false; bool esc = false;
            for (char c : args) {
                if (esc) { esc = false; continue; }
                if (c == '\\' && inStr) { esc = true; continue; }
                if (c == '"') { inStr = !inStr; continue; }
                if (!inStr) {
                    if (c == '{') depth++;
                    else if (c == '}') { depth--; if (depth == 0) return true; }
                }
            }
            return false;   // 未闭合（官方 json_brace_depth 同款逻辑，此处只判是否到闭合点）
        };
        auto publishToolCalls = [&](const std::vector<common_chat_tool_call>& tcs) {
            for (const auto& tc : tcs) {
                const std::string key = toolCallKey(tc);
                if (std::find(publishedToolCalls.begin(), publishedToolCalls.end(), key) != publishedToolCalls.end()) continue;
                if (!jsonArgsClosed(tc.arguments)) continue;   // 流式只发闭合完整版，避免 Java 执行半截参数
                publishedToolCalls.push_back(key);
                nlohmann::ordered_json j = {{"type", "tool_call"}, {"id", tc.id},
                                            {"name", tc.name}, {"arguments", tc.arguments}};
                jsonCallback(j.dump());
                LOGI("chatJson: stream tool_call id=%s name=%s args=%zu chars",
                     tc.id.c_str(), tc.name.c_str(), tc.arguments.size());
            }
        };

        // ---- 阶段A：ThinkingStage —— 思考段流式识别（THINKING 阶段）----
        // 用模板标签（mThinkStartTag/mThinkEndTags）识别完整前缀中的思考段：
        // 进入/退出思考态，标签不发给 UI（标签种类随模型模板变化，不硬编码）；
        // 思考内容累积到 thinkingBuffer_（实时思考内容监控），状态机校正为实际阶段。
        auto thinkingStage = [&](const std::string& completePart, std::string& filtered) -> void {
            filtered.reserve(completePart.size());
            const bool tagsAvailable = !mThinkStartTag.empty() && !mThinkEndTags.empty();
            if (!tagsAvailable) {
                // 无模板标签：不做思考段识别，整段作为正文
                filtered.append(completePart);
                return;
            }
            const std::string& thinkOpen = mThinkStartTag;
            const std::vector<std::string>& endTags = mThinkEndTags;
            size_t pos = 0;
            while (pos < completePart.size()) {
                if (!isInThinking) {
                    size_t open = completePart.find(thinkOpen, pos);
                    if (open == std::string::npos) {
                        filtered.append(completePart, pos, std::string::npos);
                        pos = completePart.size();
                    } else {
                        filtered.append(completePart, pos, open - pos);
                        isInThinking = true;
                        setPhase(GenPhase::THINKING, "chatJson:think_start");
                        LOGI("chatJson: thinking START detected");
                        pos = open + thinkOpen.size();
                    }
                } else {
                    // 思考中：确保状态机 THINKING。
                    // gen_loop 按 enableThinking=0 曾置 GENERATING，覆盖 init_think 的 THINKING，
                    // 若不纠正，UI 的 THINKING 分支（实时思考内容预览）永远不触发。
                    setPhase(GenPhase::THINKING, "chatJson:think_monitor");
                    size_t close = std::string::npos;
                    size_t closeLen = 0;
                    for (const auto& et : endTags) {
                        size_t pp = completePart.find(et, pos);
                        if (pp != std::string::npos && (close == std::string::npos || pp < close)) {
                            close = pp;
                            closeLen = et.size();
                        }
                    }
                    if (close == std::string::npos) {
                        // 思考内容不发流式 token（折叠显示由 Java 端收集），但实时累积供监控；
                        // 同时实时下发 thinking 增量事件 → Java 思考区（msg.thinkingContent）实时滚动
                        size_t sLen = thinkingBuffer_.size();
                        thinkingBuffer_.append(completePart, pos, std::string::npos);
                        if (thinkingBuffer_.size() > sLen) {
                            nlohmann::ordered_json tj = {{"type", "thinking"},
                                                         {"content", thinkingBuffer_.substr(sLen)}};
                            jsonCallback(tj.dump());
                        }
                        pos = completePart.size();
                    } else {
                        size_t sLen = thinkingBuffer_.size();
                        thinkingBuffer_.append(completePart, pos, close - pos);  // 实时思考内容监控
                        if (thinkingBuffer_.size() > sLen) {
                            nlohmann::ordered_json tj = {{"type", "thinking"},
                                                         {"content", thinkingBuffer_.substr(sLen)}};
                            jsonCallback(tj.dump());
                        }
                        isInThinking = false;
                        setPhase(GenPhase::GENERATING, "chatJson:think_end");
                        LOGI("chatJson: thinking END detected");
                        pos = close + closeLen;
                    }
                }
            }
        };

        // ---- 阶段B：GeneratingStage —— 正文 + 工具调用（GENERATING 阶段）----
        // 增量检测 tool_call 起始并锁定 is_tool_call 标记；剥离后的正文发流式 token。
        auto generatingStage = [&](const std::string& filtered) -> void {
            if (filtered.empty()) return;
            // 增量检测：仅 auto/none 且尚未进入 tool_call 时启用
            if (!isInToolCall && toolChoice != COMMON_CHAT_TOOL_CHOICE_REQUIRED) {
                // 快速路径：collectedText 出现 tool_call 标签特征立即锁定，
                // 不等 PARTIAL_PARSE_INTERVAL——避免 `<tool_call>` 前几个 token
                // 以 is_tool_call=false 泄漏到 UI/TTS（实测 TTS 朗读 "<toolcall"）
                if (collectedText.find("<tool_call>") != std::string::npos
                        || collectedText.find("<tool_call") != std::string::npos
                        || collectedText.find("<toolcall") != std::string::npos
                        || collectedText.find("tool_call") != std::string::npos) {
                    isInToolCall = true;
                    LOGI("chatJson: fast-path detected tool_call output");
                } else if (++partialParseCounter >= PARTIAL_PARSE_INTERVAL) {
                    partialParseCounter = 0;
                    try {
                        common_chat_parser_params pp(chat_params);
                        pp.parse_tool_calls = true;
                        common_chat_msg partial = common_chat_parse(collectedText, true, pp);
                        if (!partial.tool_calls.empty()) {
                            isInToolCall = true;   // 检测到 tool_call 输出，锁定后续标记
                            LOGI("chatJson: is_partial detected tool_call output");
                            publishToolCalls(partial.tool_calls);   // §5.3 流式增量发布（diff + 闭合判断）
                        }
                    } catch (const std::exception& e) {
                        LOGW("chatJson: partial parse failed (ignored): %s", e.what());
                    }
                }
            }
            // 思考内容本身不发流式 token（Java 端经 complete 的 reasoning 字段获取），
            // 只发剥离标签后的正文；工具调用中标记 is_tool_call=true（UI 不朗读）
            nlohmann::ordered_json j = {{"type", "token"}, {"content", filtered},
                                        {"is_tool_call", isInToolCall}};
            jsonCallback(j.dump());
        };

        // ---- tokenCallback：收集 → UTF-8 完整 → 阶段路由 ----
        auto tokenCallback = [&](const std::string& text, bool isComplete, const std::string& error) {
            if (!isComplete) {
                if (!error.empty()) {
                    genError = error;   // R7-1：生成中错误
                    return;
                }
                collectedText += text;
                // R9-1：UTF-8 完整性——只发完整前缀，不完整尾部留在 buffer
                std::string combined = utf8Buffer + text;
                std::string completePart;
                utf8Buffer = splitUtf8Complete(combined, completePart);
                if (completePart.empty()) return;
                // 阶段路由：ThinkingStage（思考剥离+状态机）→ GeneratingStage（tool_call+发 token）
                std::string filtered;
                thinkingStage(completePart, filtered);
                generatingStage(filtered);
            } else {
                if (!error.empty()) genError = error;   // R7-1：生成失败（isComplete+error）
            }
        };


        // ===== step 6.5：meta 事件（首个 token 前下发）=====
        // 把 chat template 推导出的思考标签交给 Java，Java 侧据此识别/剥离思考段，
        // 不再硬编码 <think>/</think>。模板未提供标签时下发空串 + 空数组，
        // Java 侧据此判定"该模型无思考段"，跳过剥离。
        {
            nlohmann::ordered_json meta;
            meta["type"] = "meta";
            meta["thinking_start_tag"] = mThinkStartTag;
            meta["thinking_end_tags"] = mThinkEndTags;
            jsonCallback(meta.dump());
            LOGI("chatJson: meta event sent, thinking_start_tag='%s', %zu end tag(s)",
                 mThinkStartTag.c_str(), mThinkEndTags.size());
        }

        bool genOk = false;
        try {
            // §6 官方 chat_params 全量接入：grammar(lazy)/preserved_tokens/additional_stops
            LOGI("chatJson: params util -> grammar=%s(%zuB) lazy=%d triggers=%zu preserved=%zu stops=%zu",
                 chat_params.grammar.empty() ? "none" : "set", chat_params.grammar.size(),
                 (int)chat_params.grammar_lazy, chat_params.grammar_triggers.size(),
                 chat_params.preserved_tokens.size(), chat_params.additional_stops.size());
            genOk = generateStreamIncremental(chat_params.prompt, maxTokens, temperature, topP, topK, false, tokenCallback, &chat_params);
        } catch (const std::exception& e) {
            // GPU shader 编译失败等异常：转错误事件而非崩进程（libc++abi terminate）
            LOGE("chatJson generation threw: %s", e.what());
            sendError(std::string("Generation failed: ") + e.what());
            return false;
        } catch (...) {
            LOGE("chatJson generation threw unknown exception");
            sendError("Generation failed: unknown error");
            return false;
        }

        // step 9（R3-2）：取消——shouldStop 置位时发 cancelled，不再 parse
        if (shouldStop) {
            LOGI("chatJson: generation cancelled by stop request");
            nlohmann::ordered_json j = {{"type", "error"}, {"message", "cancelled"}};
            jsonCallback(j.dump());
            return false;
        }

        // R7-1：生成失败 → error 事件（修复现状 wrappedCallback 吞 error 缺陷）
        if (!genOk || !genError.empty()) {
            std::string msg = !genError.empty() ? genError
                             : (getLastError().empty() ? "Generation failed" : getLastError());
            LOGW("chatJson: generation failed: %s", msg.c_str());
            sendError(msg);
            return false;
        }

        // ===== step 8：解析阶段（R5-2：parse 失败降级为 complete(collectedText)，不发 error）=====
        common_chat_msg parsed;
        bool parseOk = false;
        try {
            common_chat_parser_params parser_params(chat_params);
            parser_params.parse_tool_calls = true;
            parsed = common_chat_parse(collectedText, false, parser_params);
            parseOk = true;
        } catch (const std::exception& e) {
            LOGW("chatJson: common_chat_parse failed (%s), degrading to complete(collectedText)", e.what());
        }

        // step 8/10 互斥：complete 事件全轮只发一次（R5-2/A5）
        // step 8 增强：common_chat_parse 对 Qwen3 空格分隔 thinking 标签解析不出
        // reasoning_content（思考段混入正文）。用模板标签手动提取思考段，保证：
        // 思考 → reasoning 事件（UI 折叠显示），正文 → complete（干净）。
        std::string manualThinking;
        std::string manualContent;
        const bool tagsOk = !mThinkStartTag.empty() && !mThinkEndTags.empty();
        if (tagsOk) {
            size_t open = collectedText.find(mThinkStartTag);
            if (open != std::string::npos) {
                size_t cs = open + mThinkStartTag.size();
                size_t close = std::string::npos;
                size_t closeLen = 0;
                for (const auto& et : mThinkEndTags) {
                    size_t pp = collectedText.find(et, cs);
                    if (pp != std::string::npos && (close == std::string::npos || pp < close)) {
                        close = pp; closeLen = et.size();
                    }
                }
                if (close != std::string::npos) {
                    manualThinking = collectedText.substr(cs, close - cs);
                    manualContent = collectedText.substr(0, open) + collectedText.substr(close + closeLen);
                } else {
                    manualThinking = collectedText.substr(cs);
                    manualContent = collectedText.substr(0, open);
                }
            }
        }

        if (parseOk) {
            for (auto& tc : parsed.tool_calls) {
                // §5.3 去重：流式已发布（含闭合判定的同 key）不重发，仅兜底漏网的
                const std::string key = toolCallKey(tc);
                if (std::find(publishedToolCalls.begin(), publishedToolCalls.end(), key) != publishedToolCalls.end()) {
                    continue;
                }
                publishedToolCalls.push_back(key);
                nlohmann::ordered_json j = {
                    {"type", "tool_call"},
                    {"id", tc.id},
                    {"name", tc.name},
                    {"arguments", tc.arguments}
                };
                jsonCallback(j.dump());
                LOGI("chatJson: tool_call (final) id=%s name=%s", tc.id.c_str(), tc.name.c_str());
            }
            // 思考内容：common_chat_parse 解析出 reasoning_content 则用之，否则用模板标签
            // 手动提取的思考段（Qwen3 空格分隔 thinking 标签 parse 常解析不出）
            const std::string reasoningText = !parsed.reasoning_content.empty() ? parsed.reasoning_content : manualThinking;
            if (!reasoningText.empty()) {
                // 无 think 标签保护：仅当模型实际输出并闭合了 think 标记时才广播 reasoning。
                // 否则（模型未输出 think 标签 / 未闭合，内容被 common_chat_parse 划入 reasoning）
                // 不广播 reasoning，避免"无思考时把相同信息重新广播"；内容走下方 complete 兜底。
                // 用模板标签判定，不再硬编码 </think>/</thought>：GPT-OSS / MiniMax /
                // Llama3 等模板的结束标记各不相同，硬编码会让 reasoning 被误抑制、
                // 思考内容泄漏进正文。模板未提供标签时保持"不广播"行为。
                bool hasThinkEnd = false;
                for (const auto& tag : mThinkEndTags) {
                    if (!tag.empty() && collectedText.find(tag) != std::string::npos) {
                        hasThinkEnd = true;
                        break;
                    }
                }
                if (hasThinkEnd) {
                    nlohmann::ordered_json j = {{"type", "reasoning"}, {"content", reasoningText}};
                    jsonCallback(j.dump());
                    LOGI("chatJson: reasoning (%zu chars)", reasoningText.size());
                } else {
                    LOGW("chatJson: reasoning suppressed (no think end marker), content merged to body");
                }
            }
            // 完成事件单发：error 已发则不再发 complete
            if (!eventSent.exchange(true)) {
                // 正文兜底：模型未输出 think 标签时 common_chat_parse 可能把内容划入 reasoning
                // 导致 parsed.content 为空——此时用 collectedText 剥掉 think 标签作为正文，
                // 保证内容只作为正文广播一次（不丢失、不重复）
                // 思考已手动提取时正文用剥离后的内容，避免思考混入正文
                std::string finalContent = !manualContent.empty() ? manualContent : parsed.content;
                if (finalContent.empty() && !collectedText.empty()) {
                    finalContent = stripThinkTags(collectedText, mThinkStartTag, mThinkEndTags);
                }
                nlohmann::ordered_json j = {{"type", "complete"}, {"content", finalContent}};
                jsonCallback(j.dump());
                LOGI("chatJson: complete, content=%zu chars, tool_calls=%zu", finalContent.size(), parsed.tool_calls.size());
            } else {
                LOGW("chatJson: complete suppressed (error already sent)");
            }
        } else {
            // R5-2：parse 失败 → complete(collectedText)；空输出 → complete("")（A5 由构造保证只发一次）
            if (!eventSent.exchange(true)) {
                nlohmann::ordered_json j = {{"type", "complete"}, {"content", collectedText}};
                jsonCallback(j.dump());
                LOGI("chatJson: complete (degraded), content=%zu chars", collectedText.size());
            } else {
                LOGW("chatJson: degraded complete suppressed (error already sent)");
            }
        }

        return true;
    }
    
    // 并行批处理生成
    std::vector<std::string> generateBatch(const std::vector<std::string>& prompts, int maxTokens, float temperature, float topP, int topK) {
        std::vector<std::string> results(prompts.size());
        std::vector<std::thread> threads;
        
        LOGI("=== BATCH GENERATE START ===");
        LOGI("Processing %zu prompts in parallel", prompts.size());
        
        auto startTime = std::chrono::steady_clock::now();
        
        // 为每个prompt创建一个线程
        for (size_t i = 0; i < prompts.size(); i++) {
            threads.emplace_back([this, &prompts, i, maxTokens, temperature, topP, topK, &results]() {
                LOGI("Thread %zu processing prompt: %s", i, prompts[i].substr(0, 50).c_str());
                
                std::string output;
                if (generate(prompts[i], maxTokens, temperature, topP, topK, output)) {
                    results[i] = output;
                    LOGI("Thread %zu completed successfully", i);
                } else {
                    results[i] = "Error: Generation failed";
                    LOGI("Thread %zu failed", i);
                }
            });
        }
        
        // 等待所有线程完成
        for (size_t i = 0; i < threads.size(); i++) {
            if (threads[i].joinable()) {
                threads[i].join();
                LOGI("Thread %zu joined", i);
            }
        }
        
        auto endTime = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(endTime - startTime).count();
        
        LOGI("=== BATCH GENERATE END ===");
        LOGI("Processed %zu prompts in %lld s", prompts.size(), elapsed);
        
        return results;
    }
    
    int getGPULayers() const { return gpuLayers; }
    int getThreadCount() const { return threadCount; }
    int getMemoryPoolSize() const { return memoryPoolSize; }
    int getBatchSize() const { return batchSize; }
    int getContextSize() const { return contextSize; }
    const std::string& getModelPath() const { return modelPath; }
    llama_context* getLlamaContext() { return ctx; }

    // 崩溃恢复：siglongjmp 会跳过 generateStream 内的 RAII guard，
    // 导致 isGenerating 永久卡在 true，需要在 JNI 层检测并强制复位
    bool isCurrentlyGenerating() const { return isGenerating.load(); }
    void forceResetGeneration() { isGenerating.store(false); }

    void setGPULayers(int layers) { gpuLayers = layers; }

    void setBackendChoice(const std::string& choice) {
        if (choice == "opencl" || choice == "vulkan" || choice == "auto") {
            backendChoice = choice;
            LOGI("Backend choice set to: %s", choice.c_str());
        } else {
            LOGW("Invalid backend choice '%s', keeping '%s'", choice.c_str(), backendChoice.c_str());
        }
    }

    void setKvCacheType(int type) { kvCacheType = type; }
    void setThreadCount(int count) { threadCount = count; }
    void setMemoryPoolSize(int size) { memoryPoolSize = size; }
    void setBatchSize(int size) { batchSize = size; }
    
    // 检测模型类型
    void detectModelType() {
        if (model == nullptr) {
            LOGE("detectModelType: model is nullptr");
            return;
        }
        
        // 首先尝试从模型元数据中获取模型名称
        char buf[512];
        int len = llama_model_meta_val_str(model, "general.name", buf, sizeof(buf));
        if (len > 0) {
            modelType = buf;
            LOGI("Detected model type from metadata: %s", modelType.c_str());
            return;
        }
        
        // 如果元数据中没有，从模型路径推断
        std::string path = modelPath;
        std::transform(path.begin(), path.end(), path.begin(), ::tolower);
        
        if (path.find("llama") != std::string::npos) {
            modelType = "llama";
        } else if (path.find("mistral") != std::string::npos) {
            modelType = "mistral";
        } else if (path.find("gptj") != std::string::npos) {
            modelType = "gptj";
        } else if (path.find("gpt2") != std::string::npos) {
            modelType = "gpt2";
        } else if (path.find("bloom") != std::string::npos) {
            modelType = "bloom";
        } else if (path.find("falcon") != std::string::npos) {
            modelType = "falcon";
        } else {
            modelType = "unknown";
        }
        
        LOGI("Detected model type from path: %s", modelType.c_str());
    }
    
    // 获取聊天模板
    void getChatTemplate() {
        if (model == nullptr) {
            LOGE("getChatTemplate: model is nullptr");
            return;
        }
        
        const char* chat_template = llama_model_chat_template(model, nullptr);
        if (chat_template != nullptr) {
            chatTemplate = chat_template;
            LOGI("Got chat template from model: %s", chatTemplate.substr(0, 100).c_str());
        } else {
            chatTemplate = "(none, using ChatML fallback)";
            LOGI("No built-in chat template for %s, will use ChatML format", modelType.c_str());
        }
    }
    
    // 注意：prompt 格式化已统一由 llama_chat_apply_template 处理
    // 详见 applyChatTemplateForMessages() 和 NativeChatContext::formatMessages()

    // 组3.10：模型元数据 + 停止标志
    struct ModelMeta {
        bool valid = false;
        int nCtxTrain = 0;
        int nEmbd = 0;
        int nLayer = 0;
        int nHead = 0;
        long long nParams = 0;
        std::string modelName;
    };
    ModelMeta meta;
    std::atomic<bool> shouldStopAtom{false};

    // 组3.11：单次调用前后清洁工具——只清 ctx/KV/计数器/错误，不碰 model* 和 meta
    void resetRuntimeState() {
        // 不销毁 model，不碰 meta
        // 清理推理相关的运行时状态（使用 llama_get_memory + llama_memory_clear）
        if (ctx != nullptr) {
            llama_memory_t mem = llama_get_memory(ctx);
            if (mem != nullptr) {
                llama_memory_clear(mem, true);
                LOGI("resetRuntimeState: KV cache cleared");
            }
        }
        // 清计数器
        totalTokenCount = 0;
        currentTokenCount = 0;
        // 清错误
        lastError.clear();
        // 清停止标志
        shouldStopAtom.store(false);
    }

    // 组3.14：纯净推理壳——单次调用安全包装
    int runPureInference(const std::string& prompt, int /*contextSize*/, int maxTokens,
                         jobject /*callback*/, JNIEnv* /*env*/) {
        static std::mutex inferenceMutex;
        std::lock_guard<std::mutex> lock(inferenceMutex);

        // 入口清理
        resetRuntimeState();

        // prompt 非空检查
        if (prompt.empty()) {
            LOGE("runPureInference: empty prompt");
            lastError = "Empty prompt";
            return -1;
        }

        if (!isValid()) {
            LOGE("runPureInference: context not valid");
            lastError = "Context not valid";
            resetRuntimeState();
            return -2;
        }

        // 组3.15：信号兜底——sigsetjmp 包裹推理调用，接住 llama 内部 abort
        has_jmp_set = true;
        int sig = sigsetjmp(fatal_jmp_buf, 1);
        if (sig != 0) {
            // 信号被触发，返回错误码
            has_jmp_set = false;
            lastError = std::string("Fatal signal ") + std::to_string(sig) + " during inference";
            LOGE("runPureInference: %s", lastError.c_str());
            resetRuntimeState();
            return -97;
        }

        bool ok = false;
        try {
            // 按现有逻辑执行推理（复用已有的 generate 逻辑）
            std::string output;
            ok = generate(prompt, maxTokens, 0.8f, 0.95f, 40, output);
        } catch (const std::exception& e) {
            has_jmp_set = false;
            lastError = e.what();
            LOGE("runPureInference: exception: %s", e.what());
            resetRuntimeState();
            return -3;
        } catch (...) {
            has_jmp_set = false;
            lastError = "Unknown inference error";
            LOGE("runPureInference: unknown exception");
            resetRuntimeState();
            return -4;
        }

        has_jmp_set = false;

        // 出口清理
        resetRuntimeState();
        return ok ? 0 : -5;
    }

    // ========== 多模态视觉支持 ==========

    /**
     * 加载多模态投影文件（mmproj）
     * @param mmprojPath mmproj 文件路径
     * @return true 成功，false 失败
     */
    bool loadMultimodalProj(const std::string& mmprojPath) {
        if (model == nullptr) {
            LOGE("loadMultimodalProj: model not loaded");
            return false;
        }

        LOGI("Loading multimodal projection: %s", mmprojPath.c_str());

        // 检查文件是否存在
        FILE* f = fopen(mmprojPath.c_str(), "rb");
        if (f == nullptr) {
            LOGE("Multimodal projection file not found: %s", mmprojPath.c_str());
            return false;
        }
        fclose(f);

        // 设置 mtmd 参数
        mtmd_context_params mparams = mtmd_context_params_default();
        mparams.use_gpu = true;           // 使用 GPU 加速视觉编码
        mparams.print_timings = false;
        mparams.n_threads = threadCount > 0 ? threadCount : 4;
        mparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        mparams.warmup = false;           // 跳过预热以加快加载
        mparams.image_min_tokens = 256;   // 限制图像 token 数量
        mparams.image_max_tokens = 1024;

        // 初始化 mtmd 上下文
        s_mtmdCtx = mtmd_init_from_file(mmprojPath.c_str(), model, mparams);
        if (s_mtmdCtx == nullptr) {
            LOGE("Failed to initialize multimodal context from: %s", mmprojPath.c_str());
            return false;
        }

        LOGI("Multimodal projection loaded successfully");
        return true;
    }

    /**
     * 释放多模态上下文
     */
    void releaseMultimodal() {
        if (s_mtmdCtx != nullptr) {
            mtmd_free(s_mtmdCtx);
            s_mtmdCtx = nullptr;
            LOGI("Multimodal context released");
        }
    }

    /**
     * 检查多模态是否已加载
     */
    bool isMultimodalLoaded() const {
        return s_mtmdCtx != nullptr;
    }

    /**
     * 带图像的生成长文本（流式）- 支持历史上下文
     * @param history 历史消息列表 (role, content)
     * @param userText 当前用户文本
     * @param imagePath 图像文件路径
     * @param maxTokens 最大生成 token 数
     * @param temperature 温度
     * @param topP top-p 采样
     * @param topK top-k 采样
     * @param enableThinking 是否启用思考
     * @param callback 流式回调
     */
    bool generateStreamWithImage(
        const std::vector<std::pair<std::string, std::string>>& history,
        const std::string& userText,
        const std::string& imagePath,
        int maxTokens, float temperature, float topP, int topK,
        bool enableThinking, TokenCallback callback) {
        if (!isMultimodalLoaded()) {
            LOGE("generateStreamWithImage: multimodal not loaded");
            callback("", true, "Multimodal not loaded");
            return false;
        }

        LOGI("=== STREAM GENERATE WITH IMAGE START ===");
        LOGI("History count: %zu, Text length: %zu, Image: %s, maxTokens: %d",
             history.size(), userText.size(), imagePath.c_str(), maxTokens);

        // 1. 构建当前用户消息（带图像标记）
        const char* marker = mtmd_default_marker();
        std::string currentUserMsg = std::string(marker) + "\n" + userText;

        // 2. 添加当前消息到历史
        std::vector<std::pair<std::string, std::string>> allMessages = history;
        allMessages.push_back({"user", currentUserMsg});

        // 3. 格式化完整消息（含历史）
        std::string fullPrompt = applyChatTemplateForMessages(allMessages, true);
        if (fullPrompt.empty()) {
            LOGE("Failed to apply chat template");
            callback("", true, "Failed to format prompt");
            return false;
        }
        LOGI("Full prompt (history + image) length: %zu", fullPrompt.size());

        // 4. 多轮历史不再单独预评估：fullPrompt 已包含历史 + 当前图像消息，
        //    统一由 mtmd_tokenize 一次分词、mtmd_helper_eval_chunks 从 0 位置单趟评估，
        //    避免"历史先写入 KV cache 再以 n_past=0 评估全 prompt"导致的历史被覆盖/错位 bug。
        //    （与文本路径一致：每轮全量重评估，不做跨轮 KV 复用）

        // 5. 加载图像
        mtmd_helper_init_opt initOpt = {};
        mtmd_helper_bitmap_wrapper bitmapWrapper = mtmd_helper_bitmap_init_from_file(s_mtmdCtx, imagePath.c_str(), false, initOpt);
        if (bitmapWrapper.bitmap == nullptr) {
            LOGE("Failed to load image: %s", imagePath.c_str());
            callback("", true, "Failed to load image");
            return false;
        }

        // 6. 构建输入
        mtmd_input_text inputText;
        inputText.text = fullPrompt.c_str();
        inputText.text_len = fullPrompt.size();
        inputText.add_special = true;
        inputText.parse_special = true;

        const mtmd_bitmap* bitmaps[1] = { bitmapWrapper.bitmap };

        // 7. 分词（图像 + 当前消息）
        mtmd_input_chunks* chunks = mtmd_input_chunks_init();
        int32_t tokenizeRes = mtmd_tokenize(s_mtmdCtx, chunks, &inputText, bitmaps, 1);
        if (tokenizeRes != 0) {
            LOGE("mtmd_tokenize failed: %d", tokenizeRes);
            mtmd_input_chunks_free(chunks);
            mtmd_bitmap_free(bitmapWrapper.bitmap);
            callback("", true, "Failed to tokenize");
            return false;
        }

        // 8. 评估图像 + 完整 prompt（含历史）token —— 统一从 0 位置单趟评估
        llama_pos n_past = 0;
        
        // 检查上下文容量
        size_t totalTokens = mtmd_helper_get_n_tokens(chunks);
        LOGI("Image+prompt tokens: %zu, n_past: %d, total: %zu", totalTokens, (int)n_past, totalTokens + n_past);
        
        int n_ctx = llama_n_ctx(ctx);
        if ((int)(totalTokens + n_past) > n_ctx - maxTokens) {
            LOGW("Tokens exceed context, clearing KV cache");
            clearContextForInference();
            n_past = 0;
        }

        llama_pos new_n_past = 0;
        int32_t evalRes = mtmd_helper_eval_chunks(s_mtmdCtx, ctx, chunks, n_past, 0, 256, true, &new_n_past);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmapWrapper.bitmap);

        if (evalRes != 0) {
            LOGE("mtmd_helper_eval_chunks failed: %d", evalRes);
            callback("", true, "Failed to evaluate");
            return false;
        }

        LOGI("Image and prompt evaluated, starting generation from pos %d", (int)new_n_past);

        // 采样和生成循环（复用现有逻辑）
        // 这里需要实现采样循环，类似 generateStream 的后半部分
        // 为简化，先调用一个辅助方法
        return generateFromEvaluatedContext(maxTokens, temperature, topP, topK, enableThinking, callback);
    }

    /**
     * 从已评估的上下文生成（辅助方法）
     */
    bool generateFromEvaluatedContext(int maxTokens, float temperature, float topP, int topK,
                                       bool enableThinking, TokenCallback callback) {
        // 创建采样器
        auto sparams = llama_sampler_chain_default_params();
        struct llama_sampler* smpl = llama_sampler_chain_init(sparams);
        if (temperature <= 0) {
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        } else {
            addControlTokenSuppression(smpl, vocab);
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK > 0 ? topK : 40));
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0 ? topP : 0.9f, 1));
            llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.3f, 0.0f, 0.0f));
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }

        int n_remain = maxTokens;
        std::string fullText;
        const int TIMEOUT_SECONDS = 120;
        auto start = std::chrono::steady_clock::now();

        while (n_remain > 0 && !shouldStop) {
            auto currentTime = std::chrono::steady_clock::now();
            auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(currentTime - start).count();
            if (elapsed > TIMEOUT_SECONDS) {
                LOGI("TIMEOUT: Generation exceeded %d seconds", TIMEOUT_SECONDS);
                break;
            }

            llama_token new_token_id = llama_sampler_sample(smpl, ctx, -1);
            llama_sampler_accept(smpl, new_token_id);

            if (llama_vocab_is_eog(vocab, new_token_id)) {
                LOGI("EOS token detected");
                break;
            }

            char token_str[256] = {0};
            int n = llama_token_to_piece(vocab, new_token_id, token_str, sizeof(token_str), 0, true);
            if (n < 0) break;

            std::string token(token_str, n);
            fullText += token;
            callback(token, false, "");

            llama_batch batch = llama_batch_get_one(&new_token_id, 1);
            int ret = llama_decode(ctx, batch);
            if (ret != 0) {
                LOGE("llama_decode failed: %d", ret);
                break;
            }

            n_remain--;
        }

        llama_sampler_free(smpl);
        callback(fullText, true, "");
        LOGI("=== STREAM GENERATE WITH IMAGE END ===");
        return true;
    }

};

// InferenceContext::applyChatTemplateForMessages 的类外定义
// 独立版本：对任意消息列表应用 chat template，不依赖成员 chatMessages
// 用于单次生成路径（generateStreamFromMessages），避免污染多轮对话状态
std::string InferenceContext::applyChatTemplateForMessages(const std::vector<std::pair<std::string, std::string>>& messages, bool addAssistantStart) {
    if (!model || messages.empty()) return "";

    const char* tmpl = llama_model_chat_template(model, nullptr);
    if (!tmpl) {
        LOGW("No chat template found in model, falling back to ChatML format");
        std::string result;
        for (auto& m : messages) {
            result += "<|im_start|>" + m.first + "\n" + m.second + "<|im_end|>\n";
        }
        if (addAssistantStart) result += "<|im_start|>assistant\n";
        return result;
    }

    std::vector<llama_chat_message> msgs;
    msgs.reserve(messages.size());
    for (auto& m : messages) {
        msgs.push_back({m.first.c_str(), m.second.c_str()});
    }

    int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), addAssistantStart, nullptr, 0);
    if (len < 0) {
        LOGE("applyChatTemplateForMessages failed (len=%d)", len);
        return "";
    }

    std::string result(len, '\0');
    int len2 = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), addAssistantStart, &result[0], result.size());
    if (len2 < 0 || len2 > (int)result.size()) {
        LOGE("applyChatTemplateForMessages second call failed (len2=%d, bufSize=%d)", len2, (int)result.size());
        return "";
    }
    result.resize(len2);
    return result;
}

} // namespace llama_jni

// 命名空间外的 JNI 函数需要引用 llama_jni 内的多模态全局变量
using llama_jni::s_mtmdCtx;
using llama_jni::s_mtmdMutex;

// UTF-8 安全截断：按字节截断时回退到完整字符边界，避免切断多字节字符
// （曾导致 getInfo 返回非法 UTF-8，NewStringUTF 触发 JNI abort 杀进程）
static std::string utf8SafeTruncate(const std::string& s, size_t maxBytes) {
    if (s.size() <= maxBytes) return s;
    size_t end = maxBytes;
    // 跳过续字节（10xxxxxx），回退到字符起始字节
    while (end > 0 && (static_cast<unsigned char>(s[end]) & 0xC0) == 0x80) {
        --end;
    }
    return s.substr(0, end);
}

// ============================================================
// NativeChatContext - Native层独立管理上下文、KV缓存、多轮对话
// ============================================================

class NativeChatContext {
private:
    std::mutex mtx;
    std::atomic<bool> destroyed{false};
    
    llama_model *model;
    llama_context *ctx;
    const llama_vocab *vocab;
    int n_ctx;
    int n_threads;
    std::atomic<bool> shouldStop;
    std::atomic<bool> isGenerating;
    bool ownsModel;
    bool ownsContext;

    std::vector<llama_token> system_tokens;
    llama_pos system_end_pos;

    std::string global_prompt;
    std::vector<llama_token> global_tokens;
    std::string normal_prompt;
    std::vector<llama_token> normal_tokens;

    // 消息文本列表（role, content），用于 llama_chat_apply_template
    std::vector<std::pair<std::string, std::string>> chatMessages;
    // 上次格式化的总字符长度，用于增量提取
    int prev_formatted_len = 0;
    /** 最近一次生成的思考模式（chatSend 设置；reencode/格式化时保持一致，保证 KV 增量对齐） */
    bool thinkingEnabled = false;

    struct Turn {
        std::string role;
        std::vector<llama_token> tokens;
        llama_pos start_pos;
        llama_pos end_pos;
    };
    std::vector<Turn> turns;
    llama_pos current_pos;
    int total_tokens_in_kv;

    // ===== KV 记忆引擎（独立 seq 1，长文档 KV 持久化，living-kv 方案）=====
    static const llama_seq_id KV_SEQ = 1;
    bool kvMemReady = false;      // KV 记忆 seq 1 是否已有内容
    llama_pos kvMemNextPos = 0;   // 下一个可写位置（restore 后 = 恢复的 pos_max + 1）

    std::vector<llama_token> tokenize(const std::string& text, bool addBos = true) {
        int n_tokens = -llama_tokenize(vocab, text.c_str(), text.size(), NULL, 0, addBos, true);
        if (n_tokens < 0) return {};
        std::vector<llama_token> tokens(n_tokens);
        if (llama_tokenize(vocab, text.c_str(), text.size(), tokens.data(), tokens.size(), addBos, true) < 0) {
            return {};
        }
        return tokens;
    }

    // 用 llama_chat_apply_template 格式化消息列表，自动适配模型内置的 chat template
    // addAssistantStart=true 时在末尾添加 assistant 角色开始标记
    std::string applyChatTemplate(bool addAssistantStart) {
        if (!model || chatMessages.empty()) return "";

        const char* tmpl = llama_model_chat_template(model, nullptr);
        if (!tmpl) {
            LOGW("No chat template found in model, falling back to ChatML format");
            std::string result;
            for (auto& m : chatMessages) {
                const std::string& role = m.first;
                if (role == "assistant_tool_call") {
                    result += "<|im_start|>assistant\n" + m.second + "<|im_end|>\n";
                } else {
                    result += "<|im_start|>" + role + "\n" + m.second + "<|im_end|>\n";
                }
            }
            if (addAssistantStart) result += "<|im_start|>assistant\n";
            return result;
        }

        // 构造 llama_chat_message 数组
        std::vector<llama_chat_message> msgs;
        msgs.reserve(chatMessages.size());
        for (auto& m : chatMessages) {
            msgs.push_back({m.first.c_str(), m.second.c_str()});
        }

        // 第一次调用获取所需长度
        int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), addAssistantStart, nullptr, 0);
        if (len < 0) {
            LOGE("applyChatTemplate failed (len=%d)", len);
            return "";
        }

        // 第二次调用获取内容
        std::string result(len, '\0');
        int len2 = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), addAssistantStart, &result[0], result.size());
        if (len2 < 0 || len2 > (int)result.size()) {
            LOGE("applyChatTemplate second call failed (len2=%d, bufSize=%d)", len2, (int)result.size());
            return "";
        }
        result.resize(len2);
        return result;
    }

    /**
     * 带 enable_thinking 的模板应用：与 generateStream/chatJson 一致，走 common_chat_templates，
     * 显式把 thinkingEnabled 传给模板（enable_thinking=false 可关闭 Qwen3 等 thinking 模型的默认思考）。
     * 失败时回退 applyChatTemplate（旧行为）。
     */
    std::string applyChatTemplateWithThinking(bool addAssistantStart) {
        if (!model || chatMessages.empty()) return "";
        auto chat_templates = common_chat_templates_init(model, "");
        if (!chat_templates) {
            LOGW("applyChatTemplateWithThinking: template init failed, fallback");
            return applyChatTemplate(addAssistantStart);
        }
        std::vector<common_chat_msg> chat_msgs;
        chat_msgs.reserve(chatMessages.size());
        for (auto& m : chatMessages) {
            common_chat_msg msg;
            msg.role = m.first;
            msg.content = m.second;
            chat_msgs.push_back(msg);
        }
        common_chat_templates_inputs inputs;
        inputs.messages = chat_msgs;
        inputs.add_generation_prompt = addAssistantStart;
        inputs.use_jinja = true;
        // 仅当模板支持时才启用 thinking，否则模板引擎内部会 abort（signal 6）
        bool supports_thinking = common_chat_templates_support_enable_thinking(chat_templates.get());
        inputs.enable_thinking = thinkingEnabled && supports_thinking;
        try {
            common_chat_params params = common_chat_templates_apply(chat_templates.get(), inputs);
            if (params.prompt.empty()) {
                LOGW("applyChatTemplateWithThinking: empty prompt, fallback");
                return applyChatTemplate(addAssistantStart);
            }
            return params.prompt;
        } catch (const std::exception& e) {
            LOGW("applyChatTemplateWithThinking failed: %s, fallback", e.what());
            return applyChatTemplate(addAssistantStart);
        }
    }

    // 合并 global+system+normal prompt 为一条 system 消息
    std::string mergeSystemPrompts(const std::string& globalPrompt, const std::string& systemPrompt, const std::string& normalPrompt) {
        std::string merged;
        if (!globalPrompt.empty()) merged += globalPrompt + "\n";
        if (!systemPrompt.empty()) merged += systemPrompt + "\n";
        if (!normalPrompt.empty()) merged += normalPrompt;
        return merged;
    }

    // 用模板格式化并编码所有消息（用于初始化/清空/裁剪后重建）
    bool reencodeFromMessages() {
        if (ctx) {
            llama_memory_t mem = llama_get_memory(ctx);
            if (mem) llama_memory_clear(mem, true);
        }
        current_pos = 0;
        total_tokens_in_kv = 0;
        turns.clear();

        if (chatMessages.empty()) {
            prev_formatted_len = 0;
            return true;
        }

        std::string formatted = applyChatTemplateWithThinking(false);
        if (formatted.empty()) {
            LOGE("reencodeFromMessages: applyChatTemplate returned empty");
            return false;
        }

        std::vector<llama_token> tokens = tokenize(formatted, true);
        if (tokens.empty()) {
            LOGE("reencodeFromMessages: tokenize returned empty");
            return false;
        }

        Turn t;
        t.role = "system";
        t.tokens = tokens;
        t.start_pos = current_pos;
        if (!encodeTokens(tokens)) {
            LOGE("reencodeFromMessages: encodeTokens failed");
            return false;
        }
        t.end_pos = current_pos;
        turns.push_back(t);
        prev_formatted_len = (int)formatted.size();
        return true;
    }

    bool encodeTokens(const std::vector<llama_token>& tokens) {
        if (tokens.empty()) return true;
        if (shouldStop) return false;
        if (ctx == nullptr) {
            LOGE("encodeTokens: ctx is null");
            return false;
        }
        int n_ctx_avail = llama_n_ctx(ctx);
        if (total_tokens_in_kv + (int)tokens.size() > n_ctx_avail) {
            LOGE("Not enough context space: have %d, need %d", n_ctx_avail - total_tokens_in_kv, (int)tokens.size());
            return false;
        }
        std::vector<llama_token> tokens_copy(tokens);
        // 分块解码（batch ≤ n_batch，防 llama_decode assert 崩溃）
        const int nBatch = llama_n_batch(ctx);
        const int bSize = nBatch > 0 ? nBatch : 256;
        for (size_t offset = 0; offset < tokens_copy.size(); offset += bSize) {
            size_t nTokens = std::min((size_t)bSize, tokens_copy.size() - offset);
            llama_batch batch = llama_batch_get_one(tokens_copy.data() + offset, (int)nTokens);
            int ret = llama_decode(ctx, batch);
            if (ret != 0) {
                LOGE("llama_decode failed: %d (offset=%zu)", ret, offset);
                return false;
            }
        }
        total_tokens_in_kv += tokens.size();
        current_pos += tokens.size();
        LOGI("Encoded batch: %zu tokens, total kv_tokens=%d", tokens.size(), total_tokens_in_kv);
        return true;
    }

public:
    // ===================== KV 记忆引擎（独立 seq 1，长文档 KV 持久化，living-kv 方案）=====================
    llama_context* getKvMemCtx() { return ctx; }
    const llama_vocab* getKvMemVocab() { return vocab; }
    bool isKvMemReady() const { return kvMemReady; }

    /** 预加载长文本到 KV 记忆 seq 1：tokenize + decode，KV 写入 seq 1（独立于主 chat seq 0） */
    bool kvMemPreload(const std::string& text) {
        std::lock_guard<std::mutex> lock(mtx);
        if (!ctx || !vocab) { LOGE("kvMemPreload: ctx/vocab null"); return false; }
        std::vector<llama_token> tokens;
        int n = -llama_tokenize(vocab, text.c_str(), text.size(), NULL, 0, true, true);
        if (n <= 0) { LOGE("kvMemPreload: tokenize failed"); return false; }
        tokens.resize(n);
        if (llama_tokenize(vocab, text.c_str(), text.size(), tokens.data(), tokens.size(), true, true) < 0) {
            LOGE("kvMemPreload: tokenize(2) failed"); return false;
        }
        const int nBatch = llama_n_batch(ctx);
        const int bSize = nBatch > 0 ? nBatch : 256;
        llama_pos pos = kvMemNextPos;
        for (size_t off = 0; off < tokens.size(); off += (size_t)bSize) {
            size_t cnt = std::min((size_t)bSize, tokens.size() - off);
            llama_batch b = llama_batch_init((int)cnt, 0, 1);
            for (size_t i = 0; i < cnt; i++) {
                b.token[i] = tokens[off + i];
                b.pos[i] = pos + (llama_pos)i;
                b.seq_id[i][0] = KV_SEQ;
                b.n_seq_id[i] = 1;
            }
            int rc = llama_decode(ctx, b);
            llama_batch_free(b);
            if (rc != 0) { LOGE("kvMemPreload: decode failed rc=%d off=%zu", rc, off); return false; }
            pos += (llama_pos)cnt;
        }
        kvMemNextPos = pos;
        kvMemReady = true;
        LOGI("KV mem preloaded: %zu tokens -> seq1, nextPos=%d", tokens.size(), (int)kvMemNextPos);
        return true;
    }

    /** 保存 KV 记忆 seq 1 完整 state 到 out（供落盘） */
    bool kvMemGetState(std::vector<uint8_t>& out) {
        std::lock_guard<std::mutex> lock(mtx);
        if (!ctx) { LOGE("kvMemGetState: ctx null"); return false; }
        size_t size = llama_state_seq_get_size(ctx, KV_SEQ);
        if (size == 0) { LOGW("kvMemGetState: empty seq state"); return false; }
        out.resize(size);
        size_t n = llama_state_seq_get_data(ctx, out.data(), size, KV_SEQ);
        LOGI("KV mem save: %zu bytes (seq1)", n);
        return n == size && n > 0;
    }

    /** 从磁盘数据恢复 KV 记忆 seq 1；restore 前先 decode anchor 建立 cell（living-kv gotcha） */
    bool kvMemRestore(const uint8_t* data, size_t size) {
        std::lock_guard<std::mutex> lock(mtx);
        if (!ctx || !vocab) { LOGE("kvMemRestore: ctx/vocab null"); return false; }
        // living-kv gotcha：restore 需要附近已有 cell，fresh cache + far position 会 rc=-1。
        // 先 decode 一个 anchor token 到 seq 1 pos0 建立 cell。
        {
            llama_batch anchor = llama_batch_init(1, 0, 1);
            int bos = llama_vocab_bos(vocab);
            anchor.token[0] = bos >= 0 ? bos : 151644;
            anchor.pos[0] = 0;
            anchor.seq_id[0][0] = KV_SEQ;
            anchor.n_seq_id[0] = 1;
            int rc = llama_decode(ctx, anchor);
            llama_batch_free(anchor);
            if (rc != 0) LOGW("kvMemRestore: anchor decode rc=%d (continue)", rc);
        }
        size_t n = llama_state_seq_set_data(ctx, data, size, KV_SEQ);
        if (n == 0) { LOGE("kvMemRestore: set_data failed"); return false; }
        llama_memory_t mem = llama_get_memory(ctx);
        llama_pos pmax = mem ? llama_memory_seq_pos_max(mem, KV_SEQ) : 0;
        kvMemNextPos = pmax + 1;
        kvMemReady = true;
        LOGI("KV mem restored: %zu bytes, posMax=%d, nextPos=%d", n, (int)pmax, (int)kvMemNextPos);
        return true;
    }

    /** 在 KV 记忆 seq 1 上提问题并生成回答（基于已 restore/preload 的 KV，不重放历史） */
    bool kvMemAsk(const std::string& question, int maxTokens, float temperature, std::string& out) {
        std::lock_guard<std::mutex> lock(mtx);
        if (!ctx || !vocab || !kvMemReady) { LOGE("kvMemAsk: not ready (kvMemReady=%d)", kvMemReady); return false; }
        std::vector<llama_token> qtokens;
        int n = -llama_tokenize(vocab, question.c_str(), question.size(), NULL, 0, false, true);
        if (n <= 0) { LOGE("kvMemAsk: question tokenize failed"); return false; }
        qtokens.resize(n);
        if (llama_tokenize(vocab, question.c_str(), question.size(), qtokens.data(), qtokens.size(), false, true) < 0) return false;
        const int nBatch = llama_n_batch(ctx);
        const int bSize = nBatch > 0 ? nBatch : 256;
        llama_pos pos = kvMemNextPos;
        for (size_t off = 0; off < qtokens.size(); off += (size_t)bSize) {
            size_t cnt = std::min((size_t)bSize, qtokens.size() - off);
            llama_batch b = llama_batch_init((int)cnt, 0, 1);
            for (size_t i = 0; i < cnt; i++) {
                b.token[i] = qtokens[off + i];
                b.pos[i] = pos + (llama_pos)i;
                b.seq_id[i][0] = KV_SEQ;
                b.n_seq_id[i] = 1;
            }
            int rc = llama_decode(ctx, b);
            llama_batch_free(b);
            if (rc != 0) { LOGE("kvMemAsk: question decode failed rc=%d", rc); return false; }
            pos += (llama_pos)cnt;
        }
        kvMemNextPos = pos;
        auto sparams = llama_sampler_chain_default_params();
        sparams.no_perf = true;
        llama_sampler* smpl = llama_sampler_chain_init(sparams);
        if (temperature <= 0.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        } else {
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }
        int n_ctx_max = llama_n_ctx(ctx);
        int n_decode = 0;
        out = "";
        while (n_decode < maxTokens && !shouldStop) {
            if (kvMemNextPos >= n_ctx_max - 4) { LOGW("kvMemAsk: context full"); break; }
            llama_token id = llama_sampler_sample(smpl, ctx, -1);
            llama_sampler_accept(smpl, id);
            if (llama_vocab_is_eog(vocab, id)) break;
            char buf[128];
            int k = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
            if (k < 0) break;
            std::string s(buf, k);
            if (s.find("<|im_end|") != std::string::npos || s.find("</s>") != std::string::npos ||
                s.find("<|endoftext|") != std::string::npos) break;
            out += s;
            llama_batch b1 = llama_batch_init(1, 0, 1);
            b1.token[0] = id;
            b1.pos[0] = kvMemNextPos;
            b1.seq_id[0][0] = KV_SEQ;
            b1.n_seq_id[0] = 1;
            int rc = llama_decode(ctx, b1);
            llama_batch_free(b1);
            if (rc != 0) { LOGE("kvMemAsk: decode failed rc=%d", rc); break; }
            kvMemNextPos++;
            n_decode++;
        }
        llama_sampler_free(smpl);
        LOGI("KV mem ask done: %d tokens, outLen=%zu", n_decode, out.size());
        return !out.empty();
    }

    NativeChatContext() : model(nullptr), ctx(nullptr), vocab(nullptr),
                          n_ctx(0), n_threads(4), shouldStop(false), isGenerating(false),
                          ownsModel(false), ownsContext(false),
                          system_end_pos(0), current_pos(0), total_tokens_in_kv(0) {}

    ~NativeChatContext() { destroy(); }

    bool initFromExistingContext(llama_model* existingModel, llama_context* existingCtx, const llama_vocab* existingVocab,
                                 int ctxSize, int nThreads,
                                 const std::string& globalPrompt, const std::string& systemPrompt, const std::string& normalPrompt) {
        auto totalStartTime = std::chrono::steady_clock::now();
        LOGI("=== NativeChatContext INIT (reusing existing context) ===");
        LOGI("  ctxSize=%d, nThreads=%d", ctxSize, nThreads);
        LOGI("  globalPromptLen=%zu, systemPromptLen=%zu, normalPromptLen=%zu",
             globalPrompt.length(), systemPrompt.length(), normalPrompt.length());

        this->model = existingModel;
        this->ctx = existingCtx;
        this->vocab = existingVocab;
        this->ownsModel = false;
        this->ownsContext = false;
        this->n_ctx = ctxSize;
        this->n_threads = nThreads;
        this->global_prompt = globalPrompt;
        this->normal_prompt = normalPrompt;

        if (!model || !ctx || !vocab) {
            LOGE("Existing model, context, or vocab is null: model=%p, ctx=%p, vocab=%p",
                 (void*)model, (void*)ctx, (void*)vocab);
            return false;
        }

        LOGI("Using existing context: n_ctx=%d", llama_n_ctx(ctx));
        LOG_MEM("chat_ctx_reusing");

        auto stepStartTime = std::chrono::steady_clock::now();
        llama_memory_t mem = llama_get_memory(ctx);
        if (mem != nullptr) {
            llama_memory_clear(mem, true);
            LOGI("KV cache cleared before chat context init");
        } else {
            LOGE("llama_get_memory returned null");
        }
        auto stepEndTime = std::chrono::steady_clock::now();
        auto stepElapsed = std::chrono::duration_cast<std::chrono::milliseconds>(stepEndTime - stepStartTime).count();
        LOGI("[PERF] Clear KV cache: %lldms", stepElapsed);

        current_pos = 0;
        total_tokens_in_kv = 0;

        // 用 llama_chat_apply_template 格式化 system prompt，自动适配模型
        std::string systemContent = mergeSystemPrompts(globalPrompt, systemPrompt, normalPrompt);
        chatMessages.clear();
        if (!systemContent.empty()) {
            chatMessages.push_back({"system", systemContent});
        }

        if (!chatMessages.empty()) {
            std::string formatted = applyChatTemplate(false);
            if (formatted.empty()) {
                LOGE("Failed to apply chat template for system prompt");
                return false;
            }
            LOGI("Chat template applied: %zu chars (systemContent len=%zu)", formatted.length(), systemContent.length());

            stepStartTime = std::chrono::steady_clock::now();
            global_tokens = tokenize(formatted, true);
            stepEndTime = std::chrono::steady_clock::now();
            stepElapsed = std::chrono::duration_cast<std::chrono::milliseconds>(stepEndTime - stepStartTime).count();
            LOGI("[PERF] Tokenize system prompt: %zu tokens in %lldms", global_tokens.size(), stepElapsed);
            if (global_tokens.empty()) {
                LOGE("Failed to tokenize system prompt - tokenize returned empty");
                return false;
            }
            Turn sysTurn;
            sysTurn.role = "system";
            sysTurn.tokens = global_tokens;
            sysTurn.start_pos = current_pos;
            stepStartTime = std::chrono::steady_clock::now();
            if (!encodeTokens(global_tokens)) {
                LOGE("Failed to encode system prompt");
                return false;
            }
            stepEndTime = std::chrono::steady_clock::now();
            stepElapsed = std::chrono::duration_cast<std::chrono::milliseconds>(stepEndTime - stepStartTime).count();
            LOGI("[PERF] Encode system prompt: %lldms", stepElapsed);
            sysTurn.end_pos = current_pos;
            system_end_pos = current_pos;
            turns.push_back(sysTurn);
            prev_formatted_len = (int)formatted.size();
            LOGI("System prompt encoded, kv_tokens=%d", total_tokens_in_kv);
        } else {
            LOGI("All system prompts are empty, skipping");
            prev_formatted_len = 0;
        }

        auto totalEndTime = std::chrono::steady_clock::now();
        auto totalElapsed = std::chrono::duration_cast<std::chrono::milliseconds>(totalEndTime - totalStartTime).count();
        LOGI("NativeChatContext initialized (reusing context): n_ctx=%d, global_tokens=%zu, system_tokens=%zu, normal_tokens=%zu, kv_tokens=%d, total=%lldms",
             n_ctx, global_tokens.size(), system_tokens.size(), normal_tokens.size(), total_tokens_in_kv, totalElapsed);
        LOG_MEM("chat_init_complete_reuse");
        return true;
    }

    void shiftContext() {
        // 找到第一条非 system 消息并删除
        int startIdx = -1;
        for (size_t i = 0; i < chatMessages.size(); i++) {
            if (chatMessages[i].first != "system") {
                startIdx = (int)i;
                break;
            }
        }
        if (startIdx < 0 || startIdx >= (int)chatMessages.size()) return;

        std::string removedRole = chatMessages[startIdx].first;
        chatMessages.erase(chatMessages.begin() + startIdx);

        // 重新格式化并编码所有消息
        if (!reencodeFromMessages()) {
            LOGE("shiftContext: reencodeFromMessages failed");
            return;
        }

        LOGI("Context shifted: removed oldest message (role=%s), re-encoded, kv_tokens=%d",
             removedRole.c_str(), total_tokens_in_kv);
    }

    bool initFromExisting(llama_model* existingModel, const llama_vocab* existingVocab, int ctxSize, int nThreads, const std::string& globalPrompt, const std::string& systemPrompt, const std::string& normalPrompt) {
        LOGI("=== NativeChatContext INIT (from existing model) ===");
        LOGI("  ctxSize=%d, nThreads=%d", ctxSize, nThreads);
        LOGI("  globalPromptLen=%zu, systemPromptLen=%zu, normalPromptLen=%zu",
             globalPrompt.length(), systemPrompt.length(), normalPrompt.length());

        this->model = existingModel;
        this->vocab = existingVocab;
        this->ownsModel = false;
        this->n_ctx = ctxSize;
        this->n_threads = nThreads;
        this->global_prompt = globalPrompt;
        this->normal_prompt = normalPrompt;

        if (!model || !vocab) {
            LOGE("Existing model or vocab is null: model=%p, vocab=%p", model, vocab);
            return false;
        }

        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = ctxSize;
        ctx_params.n_threads = nThreads;
        ctx_params.n_threads_batch = nThreads;
        int n_batch_actual = 512;
        // 使用默认 batch size，GPU 模式下会在外层处理
        ctx_params.n_batch = n_batch_actual;
        ctx_params.n_ubatch = n_batch_actual;

        LOGI("Calling llama_init_from_model with n_ctx=%d, n_batch=%d, n_ubatch=%d",
             ctx_params.n_ctx, ctx_params.n_batch, ctx_params.n_ubatch);
        ctx = llama_init_from_model(model, ctx_params);
        if (!ctx) {
            LOGE("Failed to create context from existing model - llama_init_from_model returned null");
            LOGE("This could be due to: insufficient memory, invalid model, or GPU driver issues");
            return false;
        }
        ownsContext = true;

        LOGI("Context created from existing model: n_ctx=%d", ctxSize);
        LOG_MEM("chat_ctx_created");

        // 用 llama_chat_apply_template 格式化 system prompt，自动适配模型
        std::string systemContent = mergeSystemPrompts(globalPrompt, systemPrompt, normalPrompt);
        chatMessages.clear();
        if (!systemContent.empty()) {
            chatMessages.push_back({"system", systemContent});
        }

        if (!chatMessages.empty()) {
            std::string formatted = applyChatTemplate(false);
            if (formatted.empty()) {
                LOGE("Failed to apply chat template for system prompt");
                return false;
            }
            LOGI("Chat template applied: %zu chars", formatted.length());
            global_tokens = tokenize(formatted, true);
            if (global_tokens.empty()) {
                LOGE("Failed to tokenize system prompt - tokenize returned empty");
                return false;
            }
            LOGI("System prompt tokenized: %zu tokens", global_tokens.size());
            Turn sysTurn;
            sysTurn.role = "system";
            sysTurn.tokens = global_tokens;
            sysTurn.start_pos = current_pos;
            LOGI("Encoding system prompt tokens...");
            if (!encodeTokens(global_tokens)) {
                LOGE("Failed to encode system prompt");
                return false;
            }
            sysTurn.end_pos = current_pos;
            system_end_pos = current_pos;
            turns.push_back(sysTurn);
            prev_formatted_len = (int)formatted.size();
            LOGI("System prompt encoded, kv_tokens=%d", total_tokens_in_kv);
        } else {
            LOGI("All system prompts are empty, skipping");
            prev_formatted_len = 0;
        }

        LOGI("NativeChatContext initialized: n_ctx=%d, global_tokens=%zu, system_tokens=%zu, normal_tokens=%zu, kv_tokens=%d",
             n_ctx, global_tokens.size(), system_tokens.size(), normal_tokens.size(), total_tokens_in_kv);
        LOG_MEM("chat_init_complete");
        return true;
    }

    using StreamCallback = std::function<void(const std::string& token, bool isDone, const std::string& error)>;

    bool chatSend(const std::string& userMessage, int maxTokens, float temperature, float topP, int topK, bool enableThinking, StreamCallback callback) {
        // 防止重复调用
        if (isGenerating.exchange(true)) {
            LOGE("chatSend: already generating, rejecting concurrent call");
            callback("", true, "已有正在进行的生成任务");
            return false;
        }
        
        shouldStop = false;
        
        // 使用try_to_lock防止死锁
        std::unique_lock<std::mutex> lock(mtx, std::try_to_lock);
        if (!lock.owns_lock()) {
            LOGE("chatSend: Failed to acquire lock, possible deadlock");
            isGenerating.store(false);
            callback("", true, "无法获取锁，请稍后重试");
            return false;
        }
        
        if (destroyed.load()) {
            LOGE("chatSend: context already destroyed");
            isGenerating.store(false);
            lock.unlock();
            callback("", true, "Context destroyed");
            return false;
        }
        
        auto totalStartTime = std::chrono::steady_clock::now();
        LOGI("=== CHAT SEND START === user_msg_len=%zu, maxTokens=%d, thinking=%d", userMessage.size(), maxTokens, enableThinking);

        // 记录思考模式：模板格式化统一用它（enable_thinking=false 可关闭 thinking 模型默认思考）
        thinkingEnabled = enableThinking;

        if (!isValid()) {
            LOGE("chatSend: context invalid - model=%p, ctx=%p, vocab=%p, n_ctx=%d",
                 (void*)model, (void*)ctx, (void*)vocab, n_ctx);
            isGenerating.store(false);
            lock.unlock();
            callback("", true, "Context not initialized");
            return false;
        }

        if (shouldStop) {
            isGenerating.store(false);
            lock.unlock();
            callback("", true, "Generation stopped before start");
            return false;
        }

        auto stepStartTime = std::chrono::steady_clock::now();
        // 用带 enable_thinking 的模板格式化（add_ass=true 在末尾加 assistant 开始标记；
        // thinking 引导由模板按 thinkingEnabled 处理，不再手动追加 <think>）
        chatMessages.push_back({"user", userMessage});
        std::string formatted = applyChatTemplateWithThinking(true);
        if (formatted.empty()) {
            LOGE("chatSend: applyChatTemplate returned empty");
            chatMessages.pop_back();
            isGenerating.store(false);
            lock.unlock();
            callback("", true, "Failed to apply chat template");
            return false;
        }
        // 取增量部分（prev_formatted_len 之后的内容）
        std::string prompt;
        if (prev_formatted_len > 0 && (int)formatted.size() >= prev_formatted_len) {
            prompt = formatted.substr(prev_formatted_len);
        } else {
            prompt = formatted;
        }
        std::vector<llama_token> user_tokens = tokenize(prompt, false);
        auto stepEndTime = std::chrono::steady_clock::now();
        auto stepElapsed = std::chrono::duration_cast<std::chrono::milliseconds>(stepEndTime - stepStartTime).count();
        LOGI("[PERF] Tokenize user message: %zu tokens in %lldms (promptLen=%zu, prevLen=%d, formattedLen=%zu)",
             user_tokens.size(), stepElapsed, prompt.size(), prev_formatted_len, formatted.size());

        if (user_tokens.empty()) {
            chatMessages.pop_back();
            isGenerating.store(false);
            lock.unlock();
            callback("", true, "Failed to tokenize user message");
            return false;
        }

        stepStartTime = std::chrono::steady_clock::now();
        while (total_tokens_in_kv + (int)user_tokens.size() + maxTokens > n_ctx - 4) {
            if (shouldStop) break;
            // 检查是否有可裁剪的非 system 消息
            bool hasPrunable = false;
            for (auto& m : chatMessages) {
                if (m.first != "system") { hasPrunable = true; break; }
            }
            if (!hasPrunable) break;
            shiftContext();
            // shiftContext 会重新编码所有消息并重置 prev_formatted_len
            // 重新格式化以获取新的增量
            formatted = applyChatTemplateWithThinking(true);
            if (prev_formatted_len > 0 && (int)formatted.size() >= prev_formatted_len) {
                prompt = formatted.substr(prev_formatted_len);
            } else {
                prompt = formatted;
            }
            user_tokens = tokenize(prompt, false);
            if (total_tokens_in_kv + (int)user_tokens.size() + maxTokens <= n_ctx - 4) break;
            if (total_tokens_in_kv + (int)user_tokens.size() >= n_ctx - 4) {
                bool stillHasPrunable = false;
                for (auto& m : chatMessages) {
                    if (m.first != "system") { stillHasPrunable = true; break; }
                }
                if (stillHasPrunable) shiftContext();
                else break;
            }
        }
        stepEndTime = std::chrono::steady_clock::now();
        stepElapsed = std::chrono::duration_cast<std::chrono::milliseconds>(stepEndTime - stepStartTime).count();
        LOGI("[PERF] Context shift check: %lldms", stepElapsed);

        if (total_tokens_in_kv + (int)user_tokens.size() >= n_ctx - 4) {
            chatMessages.pop_back();
            isGenerating.store(false);
            lock.unlock();
            callback("", true, "Context too long, cannot fit user message");
            return false;
        }

        Turn userTurn;
        userTurn.role = "user";
        userTurn.tokens = user_tokens;
        userTurn.start_pos = current_pos;

        stepStartTime = std::chrono::steady_clock::now();
        LOGI("[PERF] Starting encode user tokens: %zu tokens, kv_before=%d", user_tokens.size(), total_tokens_in_kv);
        if (!encodeTokens(user_tokens)) {
            stepEndTime = std::chrono::steady_clock::now();
            stepElapsed = std::chrono::duration_cast<std::chrono::milliseconds>(stepEndTime - stepStartTime).count();
            LOGI("[PERF] Encode user tokens FAILED in %lldms", stepElapsed);
            isGenerating.store(false);
            lock.unlock();
            if (shouldStop) {
                callback("", true, "Generation stopped");
            } else {
                callback("", true, "Failed to encode user message");
            }
            return false;
        }
        stepEndTime = std::chrono::steady_clock::now();
        stepElapsed = std::chrono::duration_cast<std::chrono::milliseconds>(stepEndTime - stepStartTime).count();
        LOGI("[PERF] Encode user tokens: %lldms, kv_after=%d", stepElapsed, total_tokens_in_kv);

        userTurn.end_pos = current_pos;
        turns.push_back(userTurn);

        stepStartTime = std::chrono::steady_clock::now();
        auto sparams = llama_sampler_chain_default_params();
        struct llama_sampler *smpl = llama_sampler_chain_init(sparams);
        if (temperature <= 0) {
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        } else {
            llama_jni::addControlTokenSuppression(smpl, vocab);
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK > 0 ? topK : 40));
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0 ? topP : 0.9f, 1));
            llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.3f, 0.0f, 0.0f));
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }
        stepEndTime = std::chrono::steady_clock::now();
        stepElapsed = std::chrono::duration_cast<std::chrono::milliseconds>(stepEndTime - stepStartTime).count();
        LOGI("[PERF] Sampler init: %lldms", stepElapsed);

        std::string fullResponse;
        fullResponse.reserve(maxTokens * 4);
        std::string thinkingText;
        std::string thinkingPending; // 思考待冲刷缓冲：暂扣可能是结束标记前缀的尾部
        bool inThinking = enableThinking;
        bool thinkingEnded = !enableThinking;
        int n_decode = 0;
        // 思考 token 上限：防止非思考模型（如 qwen2.5-instruct）被强行引导后
        // 永不输出 结束标记，耗尽全部 maxTokens 导致主回复为空
        const int THINKING_TOKEN_LIMIT = std::max(96, maxTokens / 2);
        int thinkingTokens = 0;
        // 生成终止时思考未结束的原因诊断（便于日志定位）
        std::string stopReason = "normal";

        const int TOOL_CALL_DETECT_THRESHOLD = 10;
        bool possibleToolCall = false;
        const std::string qwenToolBegin = std::string("\xe2\x96\x85") + "tool" + std::string("\xe2\x96\x81") + "call" + std::string("\xe2\x96\x81") + "begin" + std::string("\xe2\x96\x85");

        stepStartTime = std::chrono::steady_clock::now();
        LOGI("[PERF] Starting generation loop...");

        auto genStartTime = std::chrono::steady_clock::now();
        const long long TIMEOUT_MS = 600000;

        while (n_decode < maxTokens && !shouldStop) {
            auto currentTime = std::chrono::steady_clock::now();
            auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(currentTime - genStartTime).count();
            if (elapsedMs > TIMEOUT_MS) {
                LOGW("Generation timeout: %lldms > %lldms, stopping", elapsedMs, TIMEOUT_MS);
                stopReason = "timeout";
                break;
            }

            if (total_tokens_in_kv >= n_ctx - 4) {
                LOGI("Context full, stopping generation");
                stopReason = "ctx_full";
                break;
            }

            llama_token new_token_id = llama_sampler_sample(smpl, ctx, -1);
            llama_sampler_accept(smpl, new_token_id);

            if (llama_vocab_is_eog(vocab, new_token_id)) { stopReason = "eos"; break; }
            if (new_token_id == 151643 || new_token_id == 151644 || new_token_id == 151645 ||
                new_token_id == 128000 || new_token_id == 128001 || new_token_id == 128008 || new_token_id == 128009) { stopReason = "eos"; break; }

            char buf[128];
            int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
            if (n > 0) {
                std::string token_str(buf, n);
                if (token_str.find("<|im_end|") != std::string::npos ||
                    token_str.find("</s>") != std::string::npos ||
                    token_str.find("<|endoftext|") != std::string::npos) { stopReason = "stop_word"; break; }

                bool sendToBody = false;
                if (inThinking && !thinkingEnded) {
                    thinkingTokens++;
                    thinkingPending += token_str;
                    int markerLen = 0;
                    int markerPos = llama_jni::InferenceContext::findThinkingEndMarker(thinkingPending, markerLen);
                    if (markerPos >= 0) {
                        // 发现结束标记（跨 token 拆分也能命中）：冲刷标记前的思考内容
                        if (markerPos > 0) {
                            callback(thinkingPending.substr(0, markerPos), false, "");
                        }
                        thinkingEnded = true;
                        callback("[THINK_END]", false, "");
                        // 标记之后的文本属于正文，交由下方正文流程处理
                        token_str = thinkingPending.substr(markerPos + markerLen);
                        thinkingPending.clear();
                        sendToBody = !token_str.empty();
                    } else if (thinkingTokens >= THINKING_TOKEN_LIMIT) {
                        // 思考 token 达到上限：强制结束思考，保留剩余额度给主回复，
                        // 避免思考无限延续导致主回复为空
                        if (!thinkingPending.empty()) {
                            thinkingText += thinkingPending;
                            callback(thinkingPending, false, "");
                            thinkingPending.clear();
                        }
                        thinkingEnded = true;
                        LOGI("chatSend: thinking token limit reached (%d), forcing THINK_END", thinkingTokens);
                        callback("[THINK_END]", false, "");
                    } else {
                        // 冲刷安全前缀：暂扣末尾可能是标记前缀的字节，等待后续 token 拼接
                        size_t hold = llama_jni::InferenceContext::partialMarkerTailLen(thinkingPending);
                        size_t flushLen = thinkingPending.size() - hold;
                        if (flushLen > 0) {
                            std::string chunk = thinkingPending.substr(0, flushLen);
                            thinkingText += chunk;
                            callback(chunk, false, "");
                            thinkingPending.erase(0, flushLen);
                        }
                    }
                } else {
                    sendToBody = true;
                }

                if (sendToBody) {
                    fullResponse += token_str;
                    
                    if (!possibleToolCall && n_decode > TOOL_CALL_DETECT_THRESHOLD) {
                        if (token_str.find("tool") != std::string::npos || 
                            token_str.find("<|tool") != std::string::npos) {
                            possibleToolCall = true;
                        }
                    }
                    
                    if (possibleToolCall) {
                        bool isToolCall = false;
                        if (fullResponse.find("<|tool_call_begin|>") != std::string::npos) {
                            isToolCall = true;
                        }
                        if (!isToolCall && fullResponse.find(qwenToolBegin) != std::string::npos) {
                            isToolCall = true;
                        }
                        if (!isToolCall && fullResponse.find("tool_call_begin") != std::string::npos) {
                            isToolCall = true;
                        }
                        if (isToolCall) {
                            callback("[TOOL_CALL]", false, "");
                            break;
                        }
                    }
                    callback(token_str, false, "");
                }
            }

            std::vector<llama_token> gen_token = {new_token_id};
            llama_batch batch = llama_batch_get_one(gen_token.data(), 1);
            int ret = llama_decode(ctx, batch);
            if (ret != 0) {
                LOGE("llama_decode failed during generation: %d", ret);
                break;
            }
            total_tokens_in_kv++;
            current_pos++;
            n_decode++;

            if (shouldStop) break;
        }

        stepEndTime = std::chrono::steady_clock::now();
        stepElapsed = std::chrono::duration_cast<std::chrono::milliseconds>(stepEndTime - stepStartTime).count();
        LOGI("[PERF] Generation loop: %d tokens in %lldms (%.2f t/s)", n_decode, stepElapsed, n_decode > 0 ? (n_decode * 1000.0f / stepElapsed) : 0);

        llama_sampler_free(smpl);

        // 生成终止时思考仍未结束：冲刷残留思考内容并补发 [THINK_END]，保证 UI 思考布局正常闭合
        if (!thinkingEnded) {
            if (n_decode >= maxTokens) stopReason = "max_tokens";
            if (shouldStop) stopReason = "user_stop";
            LOGW("chatSend: thinking NOT ended by model (reason=%s, thinkingTokens=%d, mainTokens=%d), "
                 "sending fallback [THINK_END]", stopReason.c_str(), thinkingTokens, n_decode - thinkingTokens);
            if (!thinkingPending.empty()) {
                callback(thinkingPending, false, "");
                thinkingPending.clear();
            }
            callback("[THINK_END]", false, "");
            thinkingEnded = true;
        }

        bool isToolCallResponse = (fullResponse.find("<|tool_call_begin|>") != std::string::npos ||
            fullResponse.find("tool_call_begin") != std::string::npos ||
            (fullResponse.find("tool") != std::string::npos && fullResponse.find("call") != std::string::npos && fullResponse.find("begin") != std::string::npos));

        if (!fullResponse.empty()) {
            Turn assistantTurn;
            assistantTurn.role = isToolCallResponse ? "assistant_tool_call" : "assistant";
            assistantTurn.tokens = tokenize(fullResponse, false);
            assistantTurn.start_pos = current_pos - n_decode;
            assistantTurn.end_pos = current_pos;
            turns.push_back(assistantTurn);

            chatMessages.push_back({assistantTurn.role, fullResponse});
            std::string formattedAfter = applyChatTemplateWithThinking(false);
            prev_formatted_len = (int)formattedAfter.size();
        }

        auto totalEndTime = std::chrono::steady_clock::now();
        auto totalElapsed = std::chrono::duration_cast<std::chrono::milliseconds>(totalEndTime - totalStartTime).count();
        LOGI("=== CHAT SEND COMPLETE === total=%lldms, tokens=%d, kv_total=%d, turns=%zu, tool_call=%d",
             totalElapsed, n_decode, total_tokens_in_kv, turns.size(), isToolCallResponse ? 1 : 0);

        // 解锁后再调用回调，防止死锁
        isGenerating.store(false);
        lock.unlock();
        
        callback(fullResponse, true, isToolCallResponse ? "[TOOL_CALL]" : "");
        return !fullResponse.empty() || isToolCallResponse;
    }

    void addAssistantToolCall(const std::string& toolCallContent) {
        std::lock_guard<std::mutex> lock(mtx);
        if (destroyed) return;
        LOGI("addAssistantToolCall: len=%zu", toolCallContent.size());
        chatMessages.push_back({"assistant_tool_call", toolCallContent});
    }

    void addToolResult(const std::string& toolResultContent) {
        std::lock_guard<std::mutex> lock(mtx);
        if (destroyed) return;
        LOGI("addToolResult: len=%zu", toolResultContent.size());
        chatMessages.push_back({"tool", toolResultContent});
    }

    // 注入 assistant 消息（不生成）：供外部产物（如工具结果解读）进入主对话历史，
    // 使后续轮次追问可见。与 addAssistantToolCall 同机制：不更新 prev_formatted_len，
    // 下次 chatSend 的增量模板编码会自动将其编入 KV
    void addAssistantMessage(const std::string& content) {
        std::lock_guard<std::mutex> lock(mtx);
        if (destroyed) return;
        LOGI("addAssistantMessage: len=%zu", content.size());
        chatMessages.push_back({"assistant", content});
    }

    void stopGeneration() {
        shouldStop = true;
    }

    void clearChat() {
        // 只保留 system 消息
        std::vector<std::pair<std::string, std::string>> systemMsgs;
        for (auto& m : chatMessages) {
            if (m.first == "system") systemMsgs.push_back(m);
        }
        chatMessages = systemMsgs;

        // 重新格式化并编码
        if (!reencodeFromMessages()) {
            LOGE("clearChat: reencodeFromMessages failed");
        }

        LOGI("Chat cleared, system messages re-encoded, kv_tokens=%d", total_tokens_in_kv);
    }

    std::string getInfo() {
        std::string info = "n_ctx: " + std::to_string(n_ctx) + "\n";
        info += "KV tokens: " + std::to_string(total_tokens_in_kv) + "/" + std::to_string(n_ctx) + "\n";
        info += "Turns: " + std::to_string(turns.size()) + "\n";
        info += "Global prompt: " + (global_prompt.empty() ? "(none)" : utf8SafeTruncate(global_prompt, 50) + (global_prompt.size() > 50 ? "..." : "")) + "\n";
        info += "Global tokens: " + std::to_string(global_tokens.size()) + "\n";
        info += "System tokens: " + std::to_string(system_tokens.size()) + "\n";
        info += "Normal prompt: " + (normal_prompt.empty() ? "(none)" : utf8SafeTruncate(normal_prompt, 50) + (normal_prompt.size() > 50 ? "..." : "")) + "\n";
        info += "Normal tokens: " + std::to_string(normal_tokens.size()) + "\n";
        info += "Available: " + std::to_string(n_ctx - total_tokens_in_kv) + " tokens\n";
        return info;
    }

    void destroy() {
        if (destroyed.exchange(true)) {
            LOGI("NativeChatContext already destroyed");
            return;
        }
        
        LOGI("NativeChatContext destroy (ownsContext=%d)", ownsContext ? 1 : 0);
        
        std::lock_guard<std::mutex> lock(mtx);
        
        turns.clear();
        chatMessages.clear();
        prev_formatted_len = 0;
        system_tokens.clear();
        global_tokens.clear();
        normal_tokens.clear();
        
        if (ctx && ownsContext) { 
            llama_free(ctx); 
            ctx = nullptr; 
        }
        if (ownsModel && model) { 
            llama_model_free(model); 
            model = nullptr; 
        }
        vocab = nullptr;
        n_ctx = 0;
        current_pos = 0;
        total_tokens_in_kv = 0;
        ownsContext = false;
        ownsModel = false;
    }

    bool isValid() const { 
        return model != nullptr && ctx != nullptr && vocab != nullptr && n_ctx > 0; 
    }

    bool isCurrentlyGenerating() const { return isGenerating.load(); }

    std::vector<Turn>& getTurns() { return turns; }

    int getContextSize() const { return n_ctx; }

    int getContextUsedTokens() const { return total_tokens_in_kv; }

    int getContextRemainingTokens() const { return n_ctx > 0 ? n_ctx - total_tokens_in_kv : 0; }

    /**
     * 检查是否有足够的上下文空间用于单次推理
     */
    bool hasEnoughContextSpace(int promptTokens, int maxOutputTokens) {
        if (ctx == nullptr) {
            return false;
        }

        int requiredTokens = promptTokens + maxOutputTokens;
        int availableTokens = n_ctx - total_tokens_in_kv;

        LOGI("Context check: used=%d, required=%d, available=%d, n_ctx=%d",
             total_tokens_in_kv, requiredTokens, availableTokens, n_ctx);

        if (requiredTokens > availableTokens) {
            LOGW("Not enough context space: need %d tokens, only %d available",
                 requiredTokens, availableTokens);
            return false;
        }

        return true;
    }

    /**
     * 清理 KV cache 以释放上下文空间
     */
    void clearContextForInference() {
        if (ctx == nullptr) {
            return;
        }

        LOGI("Clearing context, used tokens before: %d", total_tokens_in_kv);

        // 清理 KV cache
        // 必须使用软清理(false)：硬清理(true)会释放 Vulkan/OpenCL 后端的 KV GPU 缓冲区，
        // 下一次 llama_decode 会因访问已释放缓冲区触发 signal 6 崩溃
        llama_memory_t mem = llama_get_memory(ctx);
        if (mem != nullptr) {
            llama_memory_clear(mem, false);
        }

        // 重置计数器
        total_tokens_in_kv = 0;
        current_pos = 0;
        turns.clear();
        chatMessages.clear();
        prev_formatted_len = 0;

        LOGI("Context cleared (soft), freed all tokens");
    }

    bool updatePrompts(const std::string& globalPrompt, const std::string& systemPrompt, const std::string& normalPrompt) {
        LOGI("updatePrompts called");

        global_prompt = globalPrompt;
        normal_prompt = normalPrompt;
        global_tokens.clear();
        system_tokens.clear();
        normal_tokens.clear();

        // 合并 system prompts 并更新消息列表
        std::string systemContent = mergeSystemPrompts(globalPrompt, systemPrompt, normalPrompt);
        // 只保留非 system 消息，然后重新添加 system 消息
        std::vector<std::pair<std::string, std::string>> nonSystemMsgs;
        for (auto& m : chatMessages) {
            if (m.first != "system") nonSystemMsgs.push_back(m);
        }
        chatMessages.clear();
        if (!systemContent.empty()) {
            chatMessages.push_back({"system", systemContent});
        }
        for (auto& m : nonSystemMsgs) {
            chatMessages.push_back(m);
        }

        // 重新格式化并编码所有消息
        if (!reencodeFromMessages()) {
            LOGE("updatePrompts: reencodeFromMessages failed");
            return false;
        }

        LOGI("Prompts updated and re-encoded, kv_tokens=%d", total_tokens_in_kv);
        return true;
    }
};

static llama_jni::InferenceContext* s_helperContext = nullptr;
// GPU 后端偏好（设置项）：setBackend 写入；initModel 创建 context 后应用（无论调用顺序）
static std::string s_backendChoiceOverride = "auto";
static std::mutex s_globalMutex;

static NativeChatContext* g_chatContext = nullptr;

// 清理Native层消息内容
static std::string sanitizeNativeMessage(const std::string& message) {
    std::string result;
    result.reserve(message.size());
    for (char c : message) {
        // 移除null字符和其他控制字符（保留换行符）
        if (c != '\0' && (c >= 32 || c == '\n' || c == '\t' || c == '\r')) {
            result.push_back(c);
        }
    }
    return result;
}

static bool isValidChatHandle(jlong handle) {
    if (handle == 0) {
        return false;
    }
    NativeChatContext* ctx = reinterpret_cast<NativeChatContext*>(handle);
    if (ctx == nullptr) {
        return false;
    }
    if (ctx == g_chatContext) {
        return true;
    }
    return ctx->isValid();
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeSetBackend(
    JNIEnv* env, jclass, jstring backend) {
    if (backend == nullptr) {
        LOGW("setBackend: null backend string, ignoring");
        return;
    }
    const char* cstr = env->GetStringUTFChars(backend, nullptr);
    if (cstr == nullptr) return;
    std::string choice(cstr);
    env->ReleaseStringUTFChars(backend, cstr);
    std::lock_guard<std::mutex> lock(s_globalMutex);
    s_backendChoiceOverride = choice;
    LOGI("setBackend: preference -> %s", choice.c_str());
    if (s_helperContext != nullptr) {
        s_helperContext->setBackendChoice(choice);
    }
}

JNIEXPORT jlong JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatCreate(
    JNIEnv* env, jclass, jstring modelPath, jint ctxSize, jint nThreads, jstring globalPrompt, jstring systemPrompt, jstring normalPrompt) {
    LOGI("nativeChatCreate called");

    if (s_helperContext == nullptr || !s_helperContext->isValid()) {
        LOGE("nativeChatCreate: s_helperContext not initialized, model must be loaded first");
        return 0;
    }

    if (g_chatContext != nullptr) {
        delete g_chatContext;
        g_chatContext = nullptr;
    }

    const char* globalStr = globalPrompt ? env->GetStringUTFChars(globalPrompt, nullptr) : nullptr;
    const char* sysStr = systemPrompt ? env->GetStringUTFChars(systemPrompt, nullptr) : nullptr;
    const char* normalStr = normalPrompt ? env->GetStringUTFChars(normalPrompt, nullptr) : nullptr;

    std::string globalContent(globalStr ? globalStr : "");
    std::string sysContent(sysStr ? sysStr : "");
    std::string normalContent(normalStr ? normalStr : "");

    if (globalStr) env->ReleaseStringUTFChars(globalPrompt, globalStr);
    if (sysStr) env->ReleaseStringUTFChars(systemPrompt, sysStr);
    if (normalStr) env->ReleaseStringUTFChars(normalPrompt, normalStr);

    int actualCtxSize = s_helperContext->getContextSize();
    if (ctxSize > 0 && ctxSize < actualCtxSize) {
        actualCtxSize = ctxSize;
    }

    g_chatContext = new NativeChatContext();
    LOGI("nativeChatCreate: reusing existing InferenceContext for chat");
    bool ok = g_chatContext->initFromExistingContext(
        s_helperContext->getModel(),
        s_helperContext->getLlamaContext(),
        s_helperContext->getVocab(),
        actualCtxSize,
        nThreads > 0 ? nThreads : s_helperContext->getThreadCount(),
        globalContent,
        sysContent,
        normalContent
    );

    if (!ok) {
        delete g_chatContext;
        g_chatContext = nullptr;
        LOGE("nativeChatCreate failed");
        return 0;
    }
    return reinterpret_cast<jlong>(g_chatContext);
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatSend(
    JNIEnv* env, jclass, jlong handle, jstring message, jint maxTokens,
    jfloat temperature, jfloat topP, jint topK, jboolean enableThinking, jobject callback) {
    LOGI("nativeChatSend called, handle=%lld", (long long)handle);

    if (callback == nullptr) {
        LOGE("nativeChatSend: callback is null");
        return;
    }

    if (message == nullptr) {
        LOGE("nativeChatSend: message is null");
        jclass cbClass = env->GetObjectClass(callback);
        if (cbClass != nullptr) {
            jmethodID onError = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
            if (onError != nullptr) {
                jstring errStr = env->NewStringUTF("消息内容为空");
                env->CallVoidMethod(callback, onError, errStr);
                env->DeleteLocalRef(errStr);
            }
            env->DeleteLocalRef(cbClass);
        }
        return;
    }

    if (!isValidChatHandle(handle)) {
        LOGE("nativeChatSend: invalid handle=%lld, g_chatContext=%p", (long long)handle, (void*)g_chatContext);
        jclass cbClass = env->GetObjectClass(callback);
        if (cbClass != nullptr) {
            jmethodID onError = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
            if (onError != nullptr) {
                jstring errStr = env->NewStringUTF("Chat上下文未初始化或已失效");
                env->CallVoidMethod(callback, onError, errStr);
                env->DeleteLocalRef(errStr);
            }
            env->DeleteLocalRef(cbClass);
        }
        return;
    }

    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (!chatCtx || !chatCtx->isValid()) {
        LOGE("nativeChatSend: chatCtx is null or invalid");
        jclass cbClass = env->GetObjectClass(callback);
        if (cbClass != nullptr) {
            jmethodID onError = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
            if (onError != nullptr) {
                jstring errStr = env->NewStringUTF("Chat上下文未初始化");
                env->CallVoidMethod(callback, onError, errStr);
                env->DeleteLocalRef(errStr);
            }
            env->DeleteLocalRef(cbClass);
        }
        return;
    }

    // 检查是否已有正在进行的生成
    if (chatCtx->isCurrentlyGenerating()) {
        LOGE("nativeChatSend: already generating");
        jclass cbClass = env->GetObjectClass(callback);
        if (cbClass != nullptr) {
            jmethodID onError = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
            if (onError != nullptr) {
                env->CallVoidMethod(callback, onError, env->NewStringUTF("已有正在进行的生成任务"));
            }
            env->DeleteLocalRef(cbClass);
        }
        return;
    }

    // 获取JavaVM以便线程切换
    JavaVM* javaVM = nullptr;
    env->GetJavaVM(&javaVM);

    const char* msgStr = env->GetStringUTFChars(message, nullptr);
    if (msgStr == nullptr) {
        LOGE("nativeChatSend: GetStringUTFChars failed");
        jclass cbClass = env->GetObjectClass(callback);
        if (cbClass != nullptr) {
            jmethodID onError = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
            if (onError != nullptr) {
                env->CallVoidMethod(callback, onError, env->NewStringUTF("消息内容解析失败"));
            }
            env->DeleteLocalRef(cbClass);
        }
        return;
    }
    std::string msgContent(msgStr);
    env->ReleaseStringUTFChars(message, msgStr);
    msgStr = nullptr;

    // 清理消息内容
    msgContent = sanitizeNativeMessage(msgContent);
    if (msgContent.empty()) {
        LOGE("nativeChatSend: message is empty after sanitization");
        jclass cbClass = env->GetObjectClass(callback);
        if (cbClass != nullptr) {
            jmethodID onError = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
            if (onError != nullptr) {
                env->CallVoidMethod(callback, onError, env->NewStringUTF("消息内容无效"));
            }
            env->DeleteLocalRef(cbClass);
        }
        return;
    }

    jobject globalCallback = env->NewGlobalRef(callback);
    if (globalCallback == nullptr) {
        LOGE("nativeChatSend: Failed to create global ref for callback");
        jclass cbClass = env->GetObjectClass(callback);
        if (cbClass != nullptr) {
            jmethodID onError = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
            if (onError != nullptr) {
                env->CallVoidMethod(callback, onError, env->NewStringUTF("内存分配失败"));
            }
            env->DeleteLocalRef(cbClass);
        }
        return;
    }

    // 保存全局回调引用
    {
        std::lock_guard<std::mutex> lock(g_activeCallbackMutex);
        if (g_activeCallback != nullptr) {
            env->DeleteGlobalRef(g_activeCallback);
        }
        g_activeCallback = globalCallback;
    }
    
    jclass cbClass = env->GetObjectClass(globalCallback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onComplete = env->GetMethodID(cbClass, "onComplete", "(Ljava/lang/String;)V");
    jmethodID onError = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
    jclass globalCbClass = (jclass)env->NewGlobalRef(cbClass);
    if (globalCbClass == nullptr) {
        LOGE("nativeChatSend: Failed to create global ref for class");
        env->DeleteGlobalRef(globalCallback);
        env->DeleteLocalRef(cbClass);
        {
            std::lock_guard<std::mutex> lock(g_activeCallbackMutex);
            g_activeCallback = nullptr;
        }
        return;
    }
    env->DeleteLocalRef(cbClass);

    // UTF-8 流式缓冲：缓存不完整的多字节字符，与下一个 token 拼接
    // doneSent：完成事件（onComplete/onError）每轮只向 Java 发一次，防重复回调
    auto doneSent = std::make_shared<std::atomic<bool>>(false);
    auto streamCallback = [javaVM, globalCallback, globalCbClass, onToken, onComplete, onError, doneSent,
                           utf8Buffer = std::make_shared<std::string>()](const std::string& token, bool isDone, const std::string& error) mutable {
        if (javaVM == nullptr) {
            LOGE("streamCallback: JVM is null");
            return;
        }
        JNIEnv* cbEnv = nullptr;
        bool didAttach = false;

        int result = javaVM->GetEnv((void**)&cbEnv, JNI_VERSION_1_6);
        if (result == JNI_EDETACHED) {
            if (javaVM->AttachCurrentThread(&cbEnv, nullptr) != JNI_OK) return;
            didAttach = true;
        } else if (result != JNI_OK) return;

        // 检查回调是否有效
        {
            std::lock_guard<std::mutex> lock(g_activeCallbackMutex);
            if (globalCallback == nullptr || g_activeCallback != globalCallback) {
                LOGW("streamCallback: callback is invalid or outdated");
                if (didAttach) javaVM->DetachCurrentThread();
                return;
            }
        }

        try {
            if (globalCallback == nullptr) return;
            if (isDone) {
                // 完成事件单发守卫：重复的 onComplete/onError 直接忽略
                if (doneSent->exchange(true)) {
                    LOGW("streamCallback: duplicate completion suppressed");
                    if (didAttach) javaVM->DetachCurrentThread();
                    return;
                }
                if (!error.empty()) {
                    if (onError != nullptr) {
                        jstring jErr = cbEnv->NewStringUTF(error.c_str());
                        cbEnv->CallVoidMethod(globalCallback, onError, jErr);
                        cbEnv->DeleteLocalRef(jErr);
                    }
                } else {
                    if (onComplete != nullptr) {
                        // 刷新 UTF-8 缓冲区中剩余的不完整字节
                        std::string finalToken = token;
                        if (!utf8Buffer->empty()) {
                            finalToken = *utf8Buffer + finalToken;
                            utf8Buffer->clear();
                        }
                        jstring jToken = safeNewStringUTF(cbEnv, finalToken);
                        cbEnv->CallVoidMethod(globalCallback, onComplete, jToken);
                        cbEnv->DeleteLocalRef(jToken);
                    }
                }
                // 清理全局引用
                {
                    std::lock_guard<std::mutex> lock(g_activeCallbackMutex);
                    if (g_activeCallback == globalCallback) {
                        g_activeCallback = nullptr;
                    }
                }
                if (globalCallback != nullptr) cbEnv->DeleteGlobalRef(globalCallback);
                if (globalCbClass != nullptr) cbEnv->DeleteGlobalRef(globalCbClass);
            } else {
                if (onToken != nullptr) {
                    // 将缓存的不完整字节与新 token 拼接
                    std::string combined = *utf8Buffer + token;
                    utf8Buffer->clear();
                    
                    // 分离完整 UTF-8 和不完整尾部
                    std::string completePart;
                    std::string incompleteTail = splitUtf8Complete(combined, completePart);
                    
                    // 缓存不完整尾部，等下一个 token
                    if (!incompleteTail.empty()) {
                        *utf8Buffer = incompleteTail;
                    }
                    
                    // 只发送完整部分
                    if (!completePart.empty()) {
                        jstring jToken = safeNewStringUTF(cbEnv, completePart);
                        cbEnv->CallVoidMethod(globalCallback, onToken, jToken);
                        cbEnv->DeleteLocalRef(jToken);
                    }
                }
            }
        } catch (...) {
            LOGE("streamCallback: Exception caught");
        }

        if (didAttach) javaVM->DetachCurrentThread();
    };

    try {
        chatCtx->chatSend(msgContent, maxTokens, temperature, topP, topK, enableThinking, streamCallback);
    } catch (const std::exception& e) {
        LOGE("nativeChatSend: exception in chatSend: %s", e.what());
        std::lock_guard<std::mutex> lock(g_activeCallbackMutex);
        if (g_activeCallback == globalCallback) {
            g_activeCallback = nullptr;
        }
        if (globalCallback != nullptr) {
            env->DeleteGlobalRef(globalCallback);
        }
        if (globalCbClass != nullptr) {
            env->DeleteGlobalRef(globalCbClass);
        }
        jclass cbClassErr = env->GetObjectClass(callback);
        if (cbClassErr != nullptr) {
            jmethodID onErrorMethod = env->GetMethodID(cbClassErr, "onError", "(Ljava/lang/String;)V");
            if (onErrorMethod != nullptr) {
                std::string errorStr = "Native层异常: " + std::string(e.what());
                jstring jErr = safeNewStringUTF(env, errorStr);
                env->CallVoidMethod(callback, onErrorMethod, jErr);
                env->DeleteLocalRef(jErr);
            }
            env->DeleteLocalRef(cbClassErr);
        }
    }
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatStop(
    JNIEnv* env, jclass, jlong handle) {
    if (!isValidChatHandle(handle)) return;
    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (chatCtx) chatCtx->stopGeneration();
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatClear(
    JNIEnv* env, jclass, jlong handle) {
    if (!isValidChatHandle(handle)) return;
    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (chatCtx) chatCtx->clearChat();
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatDestroy(
    JNIEnv* env, jclass, jlong handle) {
    LOGI("nativeChatDestroy called, handle=%lld", (long long)handle);
    
    if (!isValidChatHandle(handle)) {
        LOGE("nativeChatDestroy: invalid handle=%lld", (long long)handle);
        return;
    }
    
    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (chatCtx) {
        chatCtx->destroy();
        if (chatCtx == g_chatContext) {
            g_chatContext = nullptr;
        }
        delete chatCtx;
        LOGI("nativeChatDestroy: context deleted");
    }
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatGetInfo(
    JNIEnv* env, jclass, jlong handle) {
    if (!isValidChatHandle(handle)) {
        return env->NewStringUTF("No chat context");
    }
    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (chatCtx && chatCtx->isValid()) {
        return env->NewStringUTF(chatCtx->getInfo().c_str());
    }
    return env->NewStringUTF("No chat context");
}

JNIEXPORT jboolean JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeKvMemPreload(
    JNIEnv* env, jclass, jlong handle, jstring text) {
    if (!isValidChatHandle(handle)) {
        LOGE("nativeKvMemPreload: invalid handle");
        return JNI_FALSE;
    }
    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (!chatCtx || !chatCtx->isValid()) {
        LOGE("nativeKvMemPreload: invalid chat context");
        return JNI_FALSE;
    }
    const char* str = text ? env->GetStringUTFChars(text, nullptr) : nullptr;
    if (!str) { LOGE("nativeKvMemPreload: text null"); return JNI_FALSE; }
    std::string content(str);
    env->ReleaseStringUTFChars(text, str);
    bool ok = chatCtx->kvMemPreload(content);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeKvMemSave(
    JNIEnv* env, jclass, jlong handle) {
    if (!isValidChatHandle(handle)) {
        LOGE("nativeKvMemSave: invalid handle");
        return nullptr;
    }
    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (!chatCtx || !chatCtx->isValid()) {
        LOGE("nativeKvMemSave: invalid chat context");
        return nullptr;
    }
    std::vector<uint8_t> state;
    if (!chatCtx->kvMemGetState(state)) {
        LOGE("nativeKvMemSave: kvMemGetState failed");
        return nullptr;
    }
    jbyteArray arr = env->NewByteArray((jsize)state.size());
    if (!arr) { LOGE("nativeKvMemSave: NewByteArray failed"); return nullptr; }
    env->SetByteArrayRegion(arr, 0, (jsize)state.size(), reinterpret_cast<const jbyte*>(state.data()));
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeKvMemRestore(
    JNIEnv* env, jclass, jlong handle, jbyteArray data) {
    if (!isValidChatHandle(handle)) {
        LOGE("nativeKvMemRestore: invalid handle");
        return JNI_FALSE;
    }
    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (!chatCtx || !chatCtx->isValid()) {
        LOGE("nativeKvMemRestore: invalid chat context");
        return JNI_FALSE;
    }
    if (!data) { LOGE("nativeKvMemRestore: data null"); return JNI_FALSE; }
    jsize len = env->GetArrayLength(data);
    if (len <= 0) { LOGE("nativeKvMemRestore: empty data"); return JNI_FALSE; }
    std::vector<uint8_t> buf((size_t)len);
    env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    bool ok = chatCtx->kvMemRestore(buf.data(), buf.size());
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeKvMemAsk(
    JNIEnv* env, jclass, jlong handle, jstring question, jint maxTokens, jfloat temperature) {
    if (!isValidChatHandle(handle)) {
        return env->NewStringUTF("No chat context");
    }
    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (!chatCtx || !chatCtx->isValid()) {
        return env->NewStringUTF("No chat context");
    }
    const char* qstr = question ? env->GetStringUTFChars(question, nullptr) : nullptr;
    if (!qstr) return env->NewStringUTF("Empty question");
    std::string q(qstr);
    env->ReleaseStringUTFChars(question, qstr);
    std::string out;
    if (!chatCtx->kvMemAsk(q, maxTokens > 0 ? maxTokens : 128, temperature, out)) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatUpdatePrompts(
    JNIEnv* env, jclass, jlong handle, jstring globalPrompt, jstring systemPrompt, jstring normalPrompt) {
    LOGI("nativeChatUpdatePrompts called");

    if (!isValidChatHandle(handle)) {
        LOGE("nativeChatUpdatePrompts: invalid handle");
        return JNI_FALSE;
    }

    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (!chatCtx || !chatCtx->isValid()) {
        LOGE("nativeChatUpdatePrompts: invalid chat context");
        return JNI_FALSE;
    }

    const char* globalStr = globalPrompt ? env->GetStringUTFChars(globalPrompt, nullptr) : nullptr;
    const char* sysStr = systemPrompt ? env->GetStringUTFChars(systemPrompt, nullptr) : nullptr;
    const char* normalStr = normalPrompt ? env->GetStringUTFChars(normalPrompt, nullptr) : nullptr;

    std::string globalContent(globalStr ? globalStr : "");
    std::string sysContent(sysStr ? sysStr : "");
    std::string normalContent(normalStr ? normalStr : "");

    if (globalStr) env->ReleaseStringUTFChars(globalPrompt, globalStr);
    if (sysStr) env->ReleaseStringUTFChars(systemPrompt, sysStr);
    if (normalStr) env->ReleaseStringUTFChars(normalPrompt, normalStr);

    bool ok = chatCtx->updatePrompts(globalContent, sysContent, normalContent);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatAddAssistantToolCall(
    JNIEnv* env, jclass, jlong handle, jstring toolCallContent) {
    LOGI("nativeChatAddAssistantToolCall called");

    if (!isValidChatHandle(handle)) {
        LOGE("nativeChatAddAssistantToolCall: invalid handle");
        return;
    }

    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (!chatCtx || !chatCtx->isValid()) {
        LOGE("nativeChatAddAssistantToolCall: invalid chat context");
        return;
    }

    const char* content = toolCallContent ? env->GetStringUTFChars(toolCallContent, nullptr) : nullptr;
    std::string toolCallStr(content ? content : "");
    if (content) env->ReleaseStringUTFChars(toolCallContent, content);

    chatCtx->addAssistantToolCall(toolCallStr);
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatAddToolResult(
    JNIEnv* env, jclass, jlong handle, jstring toolResultContent) {
    LOGI("nativeChatAddToolResult called");

    if (!isValidChatHandle(handle)) {
        LOGE("nativeChatAddToolResult: invalid handle");
        return;
    }

    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (!chatCtx || !chatCtx->isValid()) {
        LOGE("nativeChatAddToolResult: invalid chat context");
        return;
    }

    const char* content = toolResultContent ? env->GetStringUTFChars(toolResultContent, nullptr) : nullptr;
    std::string toolResultStr(content ? content : "");
    if (content) env->ReleaseStringUTFChars(toolResultContent, content);

    chatCtx->addToolResult(toolResultStr);
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatAddAssistant(
    JNIEnv* env, jclass, jlong handle, jstring content) {
    LOGI("nativeChatAddAssistant called");

    if (!isValidChatHandle(handle)) {
        LOGE("nativeChatAddAssistant: invalid handle");
        return;
    }

    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (!chatCtx || !chatCtx->isValid()) {
        LOGE("nativeChatAddAssistant: invalid chat context");
        return;
    }

    const char* contentChars = content ? env->GetStringUTFChars(content, nullptr) : nullptr;
    std::string contentStr(contentChars ? contentChars : "");
    if (contentChars) env->ReleaseStringUTFChars(content, contentChars);

    chatCtx->addAssistantMessage(contentStr);
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeHandleMemoryPressure(
    JNIEnv* env, jclass, jint level) {
    LOGI("nativeHandleMemoryPressure called, level=%d", level);

    int freedTokens = 0;

    if (g_chatContext != nullptr && g_chatContext->isValid()) {
        if (level >= 20) {
            int turnsBefore = 0;
            for (auto& t : g_chatContext->getTurns()) {
                if (t.role != "global" && t.role != "system") turnsBefore++;
            }
            while (turnsBefore > 0) {
                g_chatContext->shiftContext();
                freedTokens += 1;
                turnsBefore--;
                if (level >= 40 && turnsBefore > 0) {
                    g_chatContext->shiftContext();
                    freedTokens += 1;
                    turnsBefore--;
                }
                if (level < 40) break;
            }
            LOGI("Memory pressure handled: pruned %d turns, level=%d", freedTokens, level);
        }
    }

    if (level >= 80 && s_helperContext != nullptr) {
        LOGI("Critical memory pressure detected, but preserving model to avoid reinitialization");
        // 不删除 helper context，只清理 chat context 中的历史轮次
        // 这样模型可以快速恢复而不是需要重新加载
    }

    return freedTokens;
}

// ============================================================
// End NativeChatContext
// ============================================================

JNIEXPORT jlong JNICALL
Java_com_oilquiz_app_ai_jni_LlamaBridge_nativeInit(
    JNIEnv* env,
    jobject /* this */,
    jstring modelPath,
    jint contextSize,
    jint nThreads) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        LOGE("Failed to get model path");
        return 0;
    }

    std::string modelPathStr(path);
    env->ReleaseStringUTFChars(modelPath, path);

    auto* context = new llama_jni::InferenceContext();
    if (!context->loadModel(modelPathStr, contextSize, nThreads)) {
        delete context;
        return 0;
    }

    return reinterpret_cast<jlong>(context);
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaBridge_nativeGenerate(
    JNIEnv* env,
    jobject /* this */,
    jlong contextHandle,
    jstring prompt,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jint topK) {

    auto* context = reinterpret_cast<llama_jni::InferenceContext*>(contextHandle);
    if (context == nullptr || !context->isValid()) {
        LOGE("Invalid context handle");
        return env->NewStringUTF("Error: Invalid context");
    }

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    if (promptStr == nullptr) {
        LOGE("Failed to get prompt string");
        return env->NewStringUTF("Error: Failed to read prompt");
    }

    std::string promptContent(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    std::string output;
    if (!context->generate(promptContent, maxTokens, temperature, topP, topK, output)) {
        return env->NewStringUTF("Error: Generation failed");
    }

    return env->NewStringUTF(output.c_str());
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaBridge_nativeRelease(
    JNIEnv* env,
    jobject /* this */,
    jlong contextHandle) {

    auto* context = reinterpret_cast<llama_jni::InferenceContext*>(contextHandle);
    if (context != nullptr) {
        delete context;
        LOGI("Context released");
    }
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaBridge_nativeGetVersion(
    JNIEnv* env,
    jobject /* this */) {
    return env->NewStringUTF("1.0.0");
}

// LlamaHelper JNI methods

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeInitModel(
    JNIEnv* env,
    jclass /* clazz */,
    jstring modelPath,
    jint nCtx,
    jint nThreads) {
    LOGI("LlamaHelper: Initializing model");
    
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        LOGE("LlamaHelper: Failed to get model path");
        return -1;
    }
    
    std::string modelPathStr(path);
    env->ReleaseStringUTFChars(modelPath, path);
    
    // Release existing chat context first (must release before model context)
    if (g_chatContext != nullptr) {
        delete g_chatContext;
        g_chatContext = nullptr;
        LOGI("Chat context released before loading new model");
    }
    
    // Release existing model context
    if (s_helperContext != nullptr) {
        delete s_helperContext;
        s_helperContext = nullptr;
        LOGI("Model context released before loading new model");
    }
    
    s_helperContext = new llama_jni::InferenceContext();
    s_helperContext->setBackendChoice(s_backendChoiceOverride);
    if (!s_helperContext->loadModel(modelPathStr, nCtx, nThreads)) {
        LOGE("LlamaHelper: Failed to load model");
        delete s_helperContext;
        s_helperContext = nullptr;
        return -1;
    }

    // 模型加载后立刻提取模板思考标签并缓存：chatSend / generateStream 等推理路径
    // 不经过 common_chat_templates_apply，靠这次预提取才能让 nativeGetThinkingTags
    // 对它们同样有效（否则 Java 侧只能硬编码 <think>）。
    s_helperContext->refreshThinkingTags();

    LOGI("LlamaHelper: Model initialized successfully");
    return 0;
}

// Forward decl: utf8StringToJstring defined later in this file.
// nativeGetThinkingTags uses it before its definition; C++ needs the declaration first.
static jstring utf8StringToJstring(JNIEnv* env, const std::string& utf8Str);

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetThinkingTags(JNIEnv* env, jclass /* clazz */) {
    // 返回 {"thinking_start_tag":"<think>","thinking_end_tags":["</think>","<tool_call>"]}
    // 模板未提供标签时返回空串 + 空数组，Java 侧据此判定"该模型无思考段"。
    std::string start;
    std::vector<std::string> ends;
    if (s_helperContext != nullptr) {
        start = s_helperContext->getThinkStartTag();
        ends = s_helperContext->getThinkEndTags();
    } else {
        LOGW("nativeGetThinkingTags: helper context not initialized");
    }
    nlohmann::ordered_json j;
    j["thinking_start_tag"] = start;
    j["thinking_end_tags"] = ends;
    return utf8StringToJstring(env, j.dump());
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetKvCacheStats(JNIEnv* env, jclass /* clazz */) {
    // 返回 KV 增量缓存状态与上下文占用监控：
    // {"strategy":"FULL","matched_len":0,"cached_npast":0,"ctx_size":12288,
    //  "ctx_usage_pct":0.0,"plans":3,"inc":0,"part":0,"full":3,"hit_rate_pct":0.0,
    //  "valid":false,"full_reason":"first_call_or_invalidated"}
    nlohmann::ordered_json j;
    if (s_helperContext != nullptr) {
        auto& kc = s_helperContext->getKvCacheRef();
        const char* strat = "FULL";
        if (kc.isIncremental()) strat = "INCREMENTAL";
        else if (kc.isPartial()) strat = "PARTIAL";
        j["strategy"] = strat;
        j["matched_len"] = kc.matchedLen();
        j["cached_npast"] = kc.cachedNPast();
        j["ctx_size"] = kc.contextSize();
        j["ctx_usage_pct"] = kc.ctxUsage() * 100.0;
        j["plans"] = kc.planCount();
        j["inc"] = kc.incCount();
        j["part"] = kc.partCount();
        j["full"] = kc.fullCount();
        j["hit_rate_pct"] = kc.hitRate() * 100.0;
        j["valid"] = kc.valid();
        j["full_reason"] = kc.fullEvalReason();
    } else {
        LOGW("nativeGetKvCacheStats: helper context not initialized");
        j["error"] = "not_initialized";
    }
    return utf8StringToJstring(env, j.dump());
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetThinkingContent(JNIEnv* env, jclass /* clazz */) {
    // 返回当前推理已累积的思考内容（实时监控模型思考过程，供 UI 轮询显示）
    if (s_helperContext != nullptr) {
        return utf8StringToJstring(env, s_helperContext->getThinkingContent());
    }
    return utf8StringToJstring(env, "");
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetGenPhase(JNIEnv* env, jclass /* clazz */) {
    // 返回 native 生成流程状态机的当前阶段（对话界面顶部状态条展示）：
    // {"phase":"THINKING","stop_cause":"EOS","running":true}
    // phase: IDLE/PREPROCESS/THINKING/GENERATING/COMPLETE/ERROR
    nlohmann::ordered_json j;
    if (s_helperContext != nullptr) {
        j["phase"] = s_helperContext->genPhaseName();
        j["stop_cause"] = s_helperContext->genStopName();
        j["running"] = s_helperContext->genRunning();
    } else {
        j["phase"] = "IDLE";
        j["stop_cause"] = "NONE";
        j["running"] = false;
    }
    return utf8StringToJstring(env, j.dump());
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGenerate(
    JNIEnv* env,
    jclass /* clazz */,
    jstring prompt,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jint topK) {
    LOGI("=== LlamaHelper_nativeGenerate START ===");
    LOGI("LlamaHelper: maxTokens=%d, temperature=%f, topP=%f, topK=%d", maxTokens, temperature, topP, topK);
    
    if (s_helperContext == nullptr) {
        LOGE("LlamaHelper: s_helperContext is nullptr");
        return env->NewStringUTF("Error: Model not initialized (context is null)");
    }
    
    if (!s_helperContext->isValid()) {
        LOGE("LlamaHelper: s_helperContext is not valid");
        return env->NewStringUTF("Error: Model not initialized (context invalid)");
    }

    if (!s_helperContext->ensureContext()) {
        LOGE("LlamaHelper: Failed to create context");
        return env->NewStringUTF("Error: Failed to create inference context");
    }
    
    LOGI("LlamaHelper: Context is valid");
    
    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    if (promptStr == nullptr) {
        LOGE("LlamaHelper: Failed to get prompt");
        return env->NewStringUTF("Error: Failed to get prompt");
    }
    
    LOGI("LlamaHelper: Prompt length: %zu", strlen(promptStr));
    
    std::string promptContent(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);
    
    LOGI("LlamaHelper: Calling generate method...");
    
    std::string output;
    if (!s_helperContext->generate(promptContent, maxTokens, temperature, topP, topK, output)) {
        LOGE("LlamaHelper: Generation failed");
        return env->NewStringUTF("Error: Generation failed");
    }
    
    LOGI("LlamaHelper: Generation succeeded, output length: %zu", output.length());
    return env->NewStringUTF(output.c_str());
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeStopGeneration(
    JNIEnv* env,
    jclass /* clazz */) {
    LOGI("LlamaHelper: Stopping generation");
    if (s_helperContext != nullptr && s_helperContext->isValid()) {
        s_helperContext->stopGeneration();
    }
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeRelease(
    JNIEnv* env,
    jclass /* clazz */) {
    LOGI("LlamaHelper: Releasing resources");
    // 先释放多模态上下文
    if (s_mtmdCtx != nullptr) {
        mtmd_free(s_mtmdCtx);
        s_mtmdCtx = nullptr;
        LOGI("Multimodal context released");
    }
    if (s_helperContext != nullptr) {
        delete s_helperContext;
        s_helperContext = nullptr;
        LOGI("LlamaHelper: Resources released successfully");
    }
}

// ========== 多模态 JNI 函数 ==========

JNIEXPORT jboolean JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeLoadMultimodal(
    JNIEnv* env,
    jclass /* clazz */,
    jstring mmprojPath) {
    if (s_helperContext == nullptr || !s_helperContext->isValid()) {
        LOGE("nativeLoadMultimodal: model not loaded");
        return JNI_FALSE;
    }
    const char* path = env->GetStringUTFChars(mmprojPath, nullptr);
    if (path == nullptr) {
        return JNI_FALSE;
    }
    std::string pathStr(path);
    env->ReleaseStringUTFChars(mmprojPath, path);

    std::lock_guard<std::mutex> lock(s_mtmdMutex);
    // 如果已加载，先释放
    if (s_mtmdCtx != nullptr) {
        mtmd_free(s_mtmdCtx);
        s_mtmdCtx = nullptr;
    }
    bool result = s_helperContext->loadMultimodalProj(pathStr);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeReleaseMultimodal(
    JNIEnv* env,
    jclass /* clazz */) {
    std::lock_guard<std::mutex> lock(s_mtmdMutex);
    if (s_mtmdCtx != nullptr) {
        mtmd_free(s_mtmdCtx);
        s_mtmdCtx = nullptr;
        LOGI("Multimodal context released via JNI");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeIsMultimodalLoaded(
    JNIEnv* env,
    jclass /* clazz */) {
    return s_mtmdCtx != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGenerateWithImage(
    JNIEnv* env,
    jclass /* clazz */,
    jobjectArray historyArray,  // 历史消息数组: [{"role":"user","content":"..."}, ...]
    jstring prompt,
    jstring imagePath,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jint topK,
    jboolean enableThinking,
    jobject callback) {
    if (s_helperContext == nullptr || !s_helperContext->isValid()) {
        LOGE("nativeGenerateWithImage: model not initialized");
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
        if (onErrorMethod != nullptr) {
            jstring errorStr = env->NewStringUTF("Model not initialized");
            env->CallVoidMethod(callback, onErrorMethod, errorStr);
            env->DeleteLocalRef(errorStr);
        }
        env->DeleteLocalRef(callbackClass);
        return;
    }

    if (s_mtmdCtx == nullptr) {
        LOGE("nativeGenerateWithImage: multimodal not loaded");
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
        if (onErrorMethod != nullptr) {
            jstring errorStr = env->NewStringUTF("Multimodal not loaded");
            env->CallVoidMethod(callback, onErrorMethod, errorStr);
            env->DeleteLocalRef(errorStr);
        }
        env->DeleteLocalRef(callbackClass);
        return;
    }

    // 解析历史消息数组
    std::vector<std::pair<std::string, std::string>> history;
    if (historyArray != nullptr) {
        jsize historyLen = env->GetArrayLength(historyArray);
        jclass msgClass = env->FindClass("com/oilquiz/app/ai/chat/ChatMessage");
        jfieldID typeField = env->GetFieldID(msgClass, "type", "Lcom/oilquiz/app/ai/chat/ChatMessage$MessageType;");
        jfieldID contentField = env->GetFieldID(msgClass, "content", "Ljava/lang/String;");
        
        // 获取 MessageType 枚举的 name 方法
        jclass msgTypeClass = env->FindClass("com/oilquiz/app/ai/chat/ChatMessage$MessageType");
        jmethodID nameMethod = env->GetMethodID(msgTypeClass, "name", "()Ljava/lang/String;");
        
        for (int i = 0; i < historyLen; i++) {
            jobject msgObj = env->GetObjectArrayElement(historyArray, i);
            jobject typeObj = env->GetObjectField(msgObj, typeField);
            jstring contentStr = (jstring)env->GetObjectField(msgObj, contentField);
            
            if (typeObj && contentStr) {
                jstring typeStr = (jstring)env->CallObjectMethod(typeObj, nameMethod);
                if (typeStr) {
                    const char* type = env->GetStringUTFChars(typeStr, nullptr);
                    const char* content = env->GetStringUTFChars(contentStr, nullptr);
                    if (type && content) {
                        // 转换为小写 role 字符串
                        std::string roleLower = std::string(type);
                        for (auto& c : roleLower) c = std::tolower(c);
                        // MessageType.AI 枚举名为 "ai"，chat template 需要标准角色名 "assistant"
                        if (roleLower == "ai") roleLower = "assistant";
                        history.push_back({roleLower, std::string(content)});
                    }
                    if (type) env->ReleaseStringUTFChars(typeStr, type);
                    if (content) env->ReleaseStringUTFChars(contentStr, content);
                }
            }
            env->DeleteLocalRef(typeObj);
            env->DeleteLocalRef(contentStr);
            env->DeleteLocalRef(msgObj);
        }
        env->DeleteLocalRef(msgTypeClass);
        env->DeleteLocalRef(msgClass);
    }
    
    LOGI("Received %zu history messages for multimodal generation", history.size());

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    const char* imagePathStr = env->GetStringUTFChars(imagePath, nullptr);
    if (promptStr == nullptr || imagePathStr == nullptr) {
        if (promptStr) env->ReleaseStringUTFChars(prompt, promptStr);
        if (imagePathStr) env->ReleaseStringUTFChars(imagePath, imagePathStr);
        return;
    }

    std::string promptContent(promptStr);
    std::string imagePathContent(imagePathStr);
    env->ReleaseStringUTFChars(prompt, promptStr);
    env->ReleaseStringUTFChars(imagePath, imagePathStr);

    // 创建全局引用，防止回调时对象被回收
    jobject globalCallback = env->NewGlobalRef(callback);

    // 提前缓存 jmethodID
    jclass callbackClass = env->GetObjectClass(globalCallback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "(Ljava/lang/String;)V");
    jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
    jclass globalCallbackClass = (jclass)env->NewGlobalRef(callbackClass);
    env->DeleteLocalRef(callbackClass);

    // 创建回调包装器
    auto tokenCallback = [globalCallback, globalCallbackClass, onTokenMethod, onCompleteMethod, onErrorMethod,
                          utf8Buffer = std::make_shared<std::string>()](const std::string& token, bool isDone, const std::string& error) mutable {
        JavaVM* jvm = getJavaVM();
        JNIEnv* env = nullptr;

        int result = jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        bool didAttach = false;
        if (result == JNI_EDETACHED) {
            if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                LOGE("Failed to attach thread to JVM");
                return;
            }
            didAttach = true;
        } else if (result != JNI_OK) {
            LOGE("Failed to get JNIEnv");
            return;
        }

        try {
            if (isDone) {
                if (!error.empty()) {
                    if (onErrorMethod != nullptr) {
                        jstring errorStr = safeNewStringUTF(env, error);
                        env->CallVoidMethod(globalCallback, onErrorMethod, errorStr);
                        env->DeleteLocalRef(errorStr);
                    }
                } else {
                    if (onCompleteMethod != nullptr) {
                        std::string finalToken = token;
                        if (!utf8Buffer->empty()) {
                            finalToken = *utf8Buffer + finalToken;
                            utf8Buffer->clear();
                        }
                        jstring resultStr = safeNewStringUTF(env, finalToken);
                        env->CallVoidMethod(globalCallback, onCompleteMethod, resultStr);
                        env->DeleteLocalRef(resultStr);
                    }
                }
            } else if (!token.empty()) {
                if (onTokenMethod != nullptr) {
                    std::string combined = *utf8Buffer + token;
                    utf8Buffer->clear();

                    std::string completePart;
                    std::string incompleteTail = splitUtf8Complete(combined, completePart);

                    if (!incompleteTail.empty()) {
                        *utf8Buffer = incompleteTail;
                    }

                    if (!completePart.empty()) {
                        jstring tokenStr = safeNewStringUTF(env, completePart);
                        env->CallVoidMethod(globalCallback, onTokenMethod, tokenStr);
                        env->DeleteLocalRef(tokenStr);
                    }
                }
            }
        } catch (...) {
            LOGE("Exception in JNI callback");
        }

        if (isDone) {
            env->DeleteGlobalRef(globalCallback);
            env->DeleteGlobalRef(globalCallbackClass);
        }

        if (didAttach) {
            jvm->DetachCurrentThread();
        }
    };

    // 执行多模态生成（传入历史消息）
    SAFE_RUN_INFERENCE(env, globalCallback, onErrorMethod,
        s_helperContext->generateStreamWithImage(history, promptContent, imagePathContent, maxTokens, temperature, topP, topK, enableThinking, tokenCallback)
    );
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetGPULayers(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext != nullptr && s_helperContext->isValid()) {
        int layers = s_helperContext->getGPULayers();
        return layers > 0 ? layers : 0;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetThreadCount(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext != nullptr && s_helperContext->isValid()) {
        return s_helperContext->getThreadCount();
    }
    // 如果 s_helperContext 不存在，返回全局变量
    return llama_jni::s_defaultThreadCount;
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetMemoryPoolSize(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext != nullptr && s_helperContext->isValid()) {
        return s_helperContext->getMemoryPoolSize();
    }
    // 如果 s_helperContext 不存在，返回全局变量
    return llama_jni::s_defaultMemoryPoolSize;
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetBatchSize(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext != nullptr && s_helperContext->isValid()) {
        return s_helperContext->getBatchSize();
    }
    // 如果 s_helperContext 不存在，返回全局变量
    return llama_jni::s_defaultBatchSize;
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeSetGPULayers(
    JNIEnv* env,
    jclass /* clazz */,
    jint gpuLayers) {
    LOGI("LlamaHelper: Setting GPU layers to %d", gpuLayers);
    // 保存到全局变量
    llama_jni::s_defaultGpuLayers = gpuLayers;
    // 如果s_helperContext存在，也更新它
    if (s_helperContext != nullptr) {
        s_helperContext->setGPULayers(gpuLayers);
    }
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetModelNctx(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext == nullptr || !s_helperContext->isValid()) {
        return 0;
    }
    // 返回实际 context 的 n_ctx（llama_n_ctx，包含 memoryPool 预算等钳制后的真实值），
    // 不能用 contextSize 字段（字段可能未被预算钳制，导致 token 预算高估）
    llama_context* ctx = s_helperContext->getLlamaContext();
    if (ctx == nullptr) {
        return 0;
    }
    return llama_n_ctx(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeAppendMessagesAndGenerate(
    JNIEnv* env,
    jclass /* clazz */,
    jobjectArray roles,
    jobjectArray contents,
    jint appendStart,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jint topK,
    jboolean enableThinking) {
    if (s_helperContext == nullptr || !s_helperContext->isValid()) {
        return env->NewStringUTF("");
    }
    try {
        const llama_vocab* vocab = s_helperContext->getVocab();
        llama_context* ctx = s_helperContext->getLlamaContext();
        if (vocab == nullptr || ctx == nullptr) return env->NewStringUTF("");

        jsize n = env->GetArrayLength(roles);
        if (appendStart < 0) appendStart = 0;
        if (appendStart >= n) return env->NewStringUTF("");

        // 1) 手动渲染新增消息（Qwen3 im_start 格式，与模板一致）→ tokenize → 分块 decode（不清 KV）
        const int bSize = 1024;
        for (int i = appendStart; i < n; i++) {
            jstring roleJ = (jstring)env->GetObjectArrayElement(roles, i);
            jstring contentJ = (jstring)env->GetObjectArrayElement(contents, i);
            if (roleJ == nullptr || contentJ == nullptr) continue;
            const char* role = env->GetStringUTFChars(roleJ, nullptr);
            const char* content = env->GetStringUTFChars(contentJ, nullptr);
            std::string msg;
            if (role != nullptr && strcmp(role, "tool") == 0) {
                msg = "<|im_start|>user\n<tool_response>\n" + std::string(content ? content : "") + "\n</tool_response>\n<|im_end|>\n";
            } else {
                msg = std::string("<|im_start|>") + (role ? role : "user") + "\n" + (content ? content : "") + "<|im_end|>\n";
            }
            env->ReleaseStringUTFChars(roleJ, role);
            env->ReleaseStringUTFChars(contentJ, content);
            env->DeleteLocalRef(roleJ);
            env->DeleteLocalRef(contentJ);

            int nt = -llama_tokenize(vocab, msg.c_str(), msg.size(), nullptr, 0, true, true);
            if (nt <= 0) continue;
            std::vector<llama_token> tokens(nt);
            if (llama_tokenize(vocab, msg.c_str(), msg.size(), tokens.data(), tokens.size(), true, true) < 0) continue;
            for (size_t off = 0; off < tokens.size(); off += bSize) {
                size_t nn = std::min((size_t)bSize, tokens.size() - off);
                llama_batch b = llama_batch_get_one(tokens.data() + off, (int)nn);
                int ret = llama_decode(ctx, b);
                if (ret != 0) {
                    LOGE("nativeAppendMessagesAndGenerate: decode failed: %d", ret);
                    return env->NewStringUTF("");
                }
            }
        }

        // 2) generation prompt（assistant 起始，与 Qwen3 模板一致；非思考加空 think）
        std::string genPrompt = "<|im_start|>assistant\n";
        if (!enableThinking) {
            genPrompt += "<think>\n\n</think>\n\n";
        }
        int nt = -llama_tokenize(vocab, genPrompt.c_str(), genPrompt.size(), nullptr, 0, true, true);
        if (nt > 0) {
            std::vector<llama_token> tokens(nt);
            if (llama_tokenize(vocab, genPrompt.c_str(), genPrompt.size(), tokens.data(), tokens.size(), true, true) >= 0) {
                for (size_t off = 0; off < tokens.size(); off += bSize) {
                    size_t nn = std::min((size_t)bSize, tokens.size() - off);
                    llama_batch b = llama_batch_get_one(tokens.data() + off, (int)nn);
                    if (llama_decode(ctx, b) != 0) {
                        LOGW("generation prompt decode failed");
                        break;
                    }
                }
            }
        }

        // 3) 从 KV 当前位置生成（复用 generateFromEvaluatedContext）
        std::string out;
        bool ok = s_helperContext->generateFromEvaluatedContext(maxTokens, temperature, topP, topK,
            (bool)enableThinking,
            [&out](const std::string& text, bool complete, const std::string& err) {
                if (!complete && err.empty()) out += text;
            });
        LOGI("Incremental generate: %zu chars, ok=%d", out.size(), (int)ok);
        return env->NewStringUTF(out.c_str());
    } catch (const std::exception& e) {
        LOGE("nativeAppendMessagesAndGenerate error: %s", e.what());
        return env->NewStringUTF("");
    }
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetModelArchitecture(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext == nullptr || !s_helperContext->isValid()) {
        return env->NewStringUTF("");
    }
    llama_model* model = s_helperContext->getModel();
    if (model == nullptr) {
        return env->NewStringUTF("");
    }
    char buf[128] = {0};
    int len = llama_model_meta_val_str(model, "general.architecture", buf, sizeof(buf));
    if (len < 0) {
        // 兼容旧 GGUF：从 general.name 或模型类型推断
        int nameLen = llama_model_meta_val_str(model, "general.name", buf, sizeof(buf));
        if (nameLen < 0) return env->NewStringUTF("");
    }
    LOGI("Model architecture: %s", buf);
    return env->NewStringUTF(buf);
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeSetThreadCount(
    JNIEnv* env,
    jclass /* clazz */,
    jint threadCount) {
    LOGI("LlamaHelper: Setting thread count to %d", threadCount);
    llama_jni::s_defaultThreadCount = threadCount;
    if (s_helperContext != nullptr) {
        s_helperContext->setThreadCount(threadCount);
    }
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeSetKvCacheType(
    JNIEnv* env,
    jclass /* clazz */,
    jint kvCacheType) {
    LOGI("LlamaHelper: Setting KV cache type to %d (0=Q8_0, 1=F16, 2=Q4_0)", kvCacheType);
    if (s_helperContext != nullptr) {
        s_helperContext->setKvCacheType(kvCacheType);
    }
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeSetMemoryPoolSize(
    JNIEnv* env,
    jclass /* clazz */,
    jint memoryPoolSize) {
    LOGI("LlamaHelper: Setting memory pool size to %d", memoryPoolSize);
    llama_jni::s_defaultMemoryPoolSize = memoryPoolSize;
    if (s_helperContext != nullptr) {
        s_helperContext->setMemoryPoolSize(memoryPoolSize);
    }
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeSetBatchSize(
    JNIEnv* env,
    jclass /* clazz */,
    jint batchSize) {
    LOGI("LlamaHelper: Setting batch size to %d", batchSize);
    llama_jni::s_defaultBatchSize = batchSize;
    if (s_helperContext != nullptr) {
        s_helperContext->setBatchSize(batchSize);
    }
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeClearHistory(
    JNIEnv* env,
    jclass /* clazz */) {
    LOGI("LlamaHelper: Clearing history");
    if (s_helperContext != nullptr) {
        s_helperContext->clearHistory();
    }
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetModelInfo(
    JNIEnv* env,
    jclass /* clazz */) {
    LOGI("LlamaHelper: Getting model info");
    if (s_helperContext != nullptr) {
        std::string info = s_helperContext->getModelInfo();
        return env->NewStringUTF(info.c_str());
    }
    return env->NewStringUTF("Model not initialized");
}

JNIEXPORT jfloat JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetPhaseSpeed(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext != nullptr && s_helperContext->isValid()) {
        return s_helperContext->getPhaseSpeed();
    }
    return 0.0f;
}

JNIEXPORT jfloat JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetInferenceSpeed(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext != nullptr && s_helperContext->isValid()) {
        return s_helperContext->getInferenceSpeed();
    }
    return 0.0f;
}

JNIEXPORT jfloat JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetDecodeSpeed(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext != nullptr && s_helperContext->isValid()) {
        return s_helperContext->getDecodeSpeed();
    }
    return 0.0f;
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetPrefillProgress(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext != nullptr && s_helperContext->isValid()) {
        std::string info = s_helperContext->getPrefillProgress();
        return env->NewStringUTF(info.c_str());
    }
    return env->NewStringUTF("{\"done\":0,\"total\":0,\"pct\":0,\"prompt\":0}");
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetTokenCount(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext != nullptr && s_helperContext->isValid()) {
        return s_helperContext->getTokenCount();
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeOptimizeForPerformance(
    JNIEnv* env,
    jclass /* clazz */) {
    LOGI("LlamaHelper: Optimizing for performance");
    if (s_helperContext != nullptr && s_helperContext->isValid()) {
        s_helperContext->optimizeForPerformance();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeIsModelInitialized(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext == nullptr) {
        LOGI("nativeIsModelInitialized: s_helperContext is nullptr, returning false");
        return JNI_FALSE;
    }
    bool isValid = s_helperContext->isValid();
    LOGI("nativeIsModelInitialized: isValid=%d", isValid);
    return isValid ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetLastError(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext != nullptr) {
        std::string error = s_helperContext->getLastError();
        return env->NewStringUTF(error.c_str());
    }
    return env->NewStringUTF("No error");
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGenerateStream(
    JNIEnv* env,
    jclass /* clazz */,
    jstring prompt,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jint topK,
    jboolean enableThinking,
    jobject callback) {
    LOGI("LlamaHelper: Stream generation called");
    LOGI("LlamaHelper: prompt=%s, maxTokens=%d, temp=%f, topP=%f, topK=%d, thinking=%d", 
         (prompt ? "valid" : "null"), maxTokens, temperature, topP, topK, (int)enableThinking);
    
    if (s_helperContext == nullptr || !s_helperContext->isValid()) {
        LOGE("LlamaHelper: s_helperContext is null or invalid");
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
        if (onErrorMethod != nullptr) {
            jstring errorStr = env->NewStringUTF("Model not initialized");
            env->CallVoidMethod(callback, onErrorMethod, errorStr);
            env->DeleteLocalRef(errorStr);
        }
        return;
    }

    if (!s_helperContext->ensureContext()) {
        LOGE("LlamaHelper: Failed to create context for stream");
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
        if (onErrorMethod != nullptr) {
            jstring errorStr = env->NewStringUTF("Failed to create inference context");
            env->CallVoidMethod(callback, onErrorMethod, errorStr);
            env->DeleteLocalRef(errorStr);
        }
        return;
    }
    
    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    if (promptStr == nullptr) {
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
        if (onErrorMethod != nullptr) {
            jstring errorStr = env->NewStringUTF("Invalid prompt");
            env->CallVoidMethod(callback, onErrorMethod, errorStr);
            env->DeleteLocalRef(errorStr);
        }
        return;
    }
    
    std::string promptContent(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);
    
    // 创建全局引用，防止回调时对象被回收
    jobject globalCallback = env->NewGlobalRef(callback);
    
    // 提前缓存 jmethodID，避免每个 token 都反射查找
    jclass callbackClass = env->GetObjectClass(globalCallback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "(Ljava/lang/String;)V");
    jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
    jclass globalCallbackClass = (jclass)env->NewGlobalRef(callbackClass);
    env->DeleteLocalRef(callbackClass);
    
    // Create a callback wrapper for JNI - 正确处理线程安全
    auto tokenCallback = [globalCallback, globalCallbackClass, onTokenMethod, onCompleteMethod, onErrorMethod,
                          utf8Buffer = std::make_shared<std::string>()](const std::string& token, bool isDone, const std::string& error) mutable {
        JavaVM* jvm = getJavaVM();
        JNIEnv* env = nullptr;
        
        // 尝试获取JNIEnv
        int result = jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        
        // 如果当前线程没有Attach到JVM，需要Attach
        bool didAttach = false;
        if (result == JNI_EDETACHED) {
            if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                LOGE("Failed to attach thread to JVM");
                return;
            }
            didAttach = true;
        } else if (result != JNI_OK) {
            LOGE("Failed to get JNIEnv");
            return;
        }
        
        try {
            if (isDone) {
                if (!error.empty()) {
                    if (onErrorMethod != nullptr) {
                        jstring errorStr = safeNewStringUTF(env, error);
                        env->CallVoidMethod(globalCallback, onErrorMethod, errorStr);
                        env->DeleteLocalRef(errorStr);
                    }
                } else {
                    if (onCompleteMethod != nullptr) {
                        // 刷新 UTF-8 缓冲区中剩余的不完整字节
                        std::string finalToken = token;
                        if (!utf8Buffer->empty()) {
                            finalToken = *utf8Buffer + finalToken;
                            utf8Buffer->clear();
                        }
                        jstring resultStr = safeNewStringUTF(env, finalToken);
                        env->CallVoidMethod(globalCallback, onCompleteMethod, resultStr);
                        env->DeleteLocalRef(resultStr);
                    }
                }
            } else if (!token.empty()) {
                if (onTokenMethod != nullptr) {
                    // 将缓存的不完整字节与新 token 拼接
                    std::string combined = *utf8Buffer + token;
                    utf8Buffer->clear();
                    
                    // 分离完整 UTF-8 和不完整尾部
                    std::string completePart;
                    std::string incompleteTail = splitUtf8Complete(combined, completePart);
                    
                    // 缓存不完整尾部，等下一个 token
                    if (!incompleteTail.empty()) {
                        *utf8Buffer = incompleteTail;
                    }
                    
                    // 只发送完整部分
                    if (!completePart.empty()) {
                        jstring tokenStr = safeNewStringUTF(env, completePart);
                        env->CallVoidMethod(globalCallback, onTokenMethod, tokenStr);
                        env->DeleteLocalRef(tokenStr);
                    }
                }
            }
        } catch (...) {
            LOGE("Exception in JNI callback");
        }
        
        // 如果是完成回调，释放全局引用
        if (isDone) {
            env->DeleteGlobalRef(globalCallback);
            env->DeleteGlobalRef(globalCallbackClass);
        }
        
        // 如果是我们Attach的，需要Detach
        if (didAttach) {
            jvm->DetachCurrentThread();
        }
    };
    
    // Start stream generation —— 信号安全包裹（SIGABRT/SIGSEGV/SIGBUS/SIGILL 兜底）
    SAFE_RUN_INFERENCE(env, globalCallback, onErrorMethod,
        s_helperContext->generateStream(promptContent, maxTokens, temperature, topP, topK, enableThinking, tokenCallback)
    );
}

// 辅助函数：将byte[]转换为标准UTF-8字符串（正确处理中文）
static std::string bytesToUtf8String(JNIEnv* env, jbyteArray byteArray) {
    if (byteArray == nullptr) {
        return "";
    }

    jsize length = env->GetArrayLength(byteArray);
    if (length == 0) {
        return "";
    }

    jbyte* bytes = env->GetByteArrayElements(byteArray, nullptr);
    if (bytes == nullptr) {
        return "";
    }

    // 直接构造std::string，Java层已经用UTF-8编码
    std::string result(reinterpret_cast<const char*>(bytes), length);

    env->ReleaseByteArrayElements(byteArray, bytes, JNI_ABORT);

    return result;
}

// 辅助函数：将标准UTF-8字符串转换为jstring（正确处理中文）
static jstring utf8StringToJstring(JNIEnv* env, const std::string& utf8Str) {
    if (utf8Str.empty()) {
        return env->NewStringUTF("");
    }

    // 使用NewString构造UTF-16字符串，避免Modified UTF-8问题
    // 先将UTF-8转为UTF-16
    std::vector<jchar> utf16Chars;
    utf16Chars.reserve(utf8Str.length()); // 预分配，通常UTF-16不会比UTF-8长

    size_t i = 0;
    while (i < utf8Str.length()) {
        unsigned char c = utf8Str[i];
        unsigned int codePoint = 0;

        if ((c & 0x80) == 0) {
            // 1-byte ASCII
            codePoint = c;
            i += 1;
        } else if ((c & 0xE0) == 0xC0) {
            // 2-byte sequence
            if (i + 1 < utf8Str.length()) {
                codePoint = ((c & 0x1F) << 6) | (utf8Str[i + 1] & 0x3F);
            }
            i += 2;
        } else if ((c & 0xF0) == 0xE0) {
            // 3-byte sequence (中文常用)
            if (i + 2 < utf8Str.length()) {
                codePoint = ((c & 0x0F) << 12) | ((utf8Str[i + 1] & 0x3F) << 6) | (utf8Str[i + 2] & 0x3F);
            }
            i += 3;
        } else if ((c & 0xF8) == 0xF0) {
            // 4-byte sequence
            if (i + 3 < utf8Str.length()) {
                codePoint = ((c & 0x07) << 18) | ((utf8Str[i + 1] & 0x3F) << 12) |
                           ((utf8Str[i + 2] & 0x3F) << 6) | (utf8Str[i + 3] & 0x3F);
            }
            i += 4;
        } else {
            // Invalid byte, skip
            i += 1;
            continue;
        }

        // 将code point转为UTF-16
        if (codePoint <= 0xFFFF) {
            utf16Chars.push_back(static_cast<jchar>(codePoint));
        } else {
            // Surrogate pair for code points > 0xFFFF
            codePoint -= 0x10000;
            utf16Chars.push_back(static_cast<jchar>(0xD800 + (codePoint >> 10)));
            utf16Chars.push_back(static_cast<jchar>(0xDC00 + (codePoint & 0x3FF)));
        }
    }

    return env->NewString(utf16Chars.data(), utf16Chars.size());
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGenerateStreamBytes(
    JNIEnv* env,
    jclass /* clazz */,
    jbyteArray promptUtf8,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jint topK,
    jboolean enableThinking,
    jobject callback) {
    LOGI("LlamaHelper: Stream generation with bytes called");
    LOGI("LlamaHelper: promptBytes=%p, maxTokens=%d, temp=%f, topP=%f, topK=%d",
         promptUtf8, maxTokens, temperature, topP, topK);

    if (s_helperContext == nullptr || !s_helperContext->isValid()) {
        LOGE("LlamaHelper: s_helperContext is null or invalid");
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
        if (onErrorMethod != nullptr) {
            jstring errorStr = env->NewStringUTF("Model not initialized");
            env->CallVoidMethod(callback, onErrorMethod, errorStr);
            env->DeleteLocalRef(errorStr);
        }
        return;
    }

    if (!s_helperContext->ensureContext()) {
        LOGE("LlamaHelper: Failed to create context for stream");
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
        if (onErrorMethod != nullptr) {
            jstring errorStr = env->NewStringUTF("Failed to create inference context");
            env->CallVoidMethod(callback, onErrorMethod, errorStr);
            env->DeleteLocalRef(errorStr);
        }
        return;
    }

    // 使用byte[]方式获取UTF-8字符串（正确处理中文）
    std::string promptContent = bytesToUtf8String(env, promptUtf8);
    if (promptContent.empty()) {
        LOGE("LlamaHelper: Failed to decode prompt bytes");
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
        if (onErrorMethod != nullptr) {
            jstring errorStr = env->NewStringUTF("Invalid prompt encoding");
            env->CallVoidMethod(callback, onErrorMethod, errorStr);
            env->DeleteLocalRef(errorStr);
        }
        return;
    }

    LOGI("LlamaHelper: Decoded prompt length=%zu", promptContent.length());

    // 创建全局引用，防止回调时对象被回收
    jobject globalCallback = env->NewGlobalRef(callback);

    // 提前缓存 jmethodID，避免每个 token 都反射查找
    jclass callbackClass = env->GetObjectClass(globalCallback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "(Ljava/lang/String;)V");
    jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
    jclass globalCallbackClass = (jclass)env->NewGlobalRef(callbackClass);
    env->DeleteLocalRef(callbackClass);

    // Create a callback wrapper for JNI - 正确处理线程安全和中文编码
    auto tokenCallback = [globalCallback, globalCallbackClass, onTokenMethod, onCompleteMethod, onErrorMethod,
                          utf8Buffer = std::make_shared<std::string>()](const std::string& token, bool isDone, const std::string& error) mutable {
        JavaVM* jvm = getJavaVM();
        JNIEnv* env = nullptr;

        // 尝试获取JNIEnv
        int result = jvm->GetEnv((void**)&env, JNI_VERSION_1_6);

        // 如果当前线程没有Attach到JVM，需要Attach
        bool didAttach = false;
        if (result == JNI_EDETACHED) {
            if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                LOGE("Failed to attach thread to JVM");
                return;
            }
            didAttach = true;
        } else if (result != JNI_OK) {
            LOGE("Failed to get JNIEnv");
            return;
        }

        try {
            if (isDone) {
                if (!error.empty()) {
                    if (onErrorMethod != nullptr) {
                        jstring errorStr = utf8StringToJstring(env, error);
                        env->CallVoidMethod(globalCallback, onErrorMethod, errorStr);
                        env->DeleteLocalRef(errorStr);
                    }
                } else {
                    if (onCompleteMethod != nullptr) {
                        // 刷新 UTF-8 缓冲区中剩余的不完整字节
                        std::string finalToken = token;
                        if (!utf8Buffer->empty()) {
                            finalToken = *utf8Buffer + finalToken;
                            utf8Buffer->clear();
                        }
                        jstring resultStr = utf8StringToJstring(env, finalToken);
                        env->CallVoidMethod(globalCallback, onCompleteMethod, resultStr);
                        env->DeleteLocalRef(resultStr);
                    }
                }
            } else if (!token.empty()) {
                if (onTokenMethod != nullptr) {
                    // 将缓存的不完整字节与新 token 拼接
                    std::string combined = *utf8Buffer + token;
                    utf8Buffer->clear();
                    
                    // 分离完整 UTF-8 和不完整尾部
                    std::string completePart;
                    std::string incompleteTail = splitUtf8Complete(combined, completePart);
                    
                    // 缓存不完整尾部
                    if (!incompleteTail.empty()) {
                        *utf8Buffer = incompleteTail;
                    }
                    
                    // 只发送完整部分
                    if (!completePart.empty()) {
                        jstring tokenStr = utf8StringToJstring(env, completePart);
                        env->CallVoidMethod(globalCallback, onTokenMethod, tokenStr);
                        env->DeleteLocalRef(tokenStr);
                    }
                }
            }
        } catch (...) {
            LOGE("Exception in JNI callback");
        }

        // 如果是完成回调，释放全局引用
        if (isDone) {
            env->DeleteGlobalRef(globalCallback);
            env->DeleteGlobalRef(globalCallbackClass);
        }

        // 如果是我们Attach的，需要Detach
        if (didAttach) {
            jvm->DetachCurrentThread();
        }
    };

    // Start stream generation —— 信号安全包裹（SIGABRT/SIGSEGV/SIGBUS/SIGILL 兜底）
    SAFE_RUN_INFERENCE(env, globalCallback, onErrorMethod,
        s_helperContext->generateStream(promptContent, maxTokens, temperature, topP, topK, enableThinking, tokenCallback)
    );
}

// 单次生成路径：接收消息列表（roles[] + contents[]），用 applyChatTemplate 自动适配模型格式
// 参数说明：
//   roles: 角色字符串数组（"system"/"user"/"assistant"）
//   contents: 对应的消息内容字节数组（UTF-8 编码，解决中文问题）
JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGenerateStreamFromMessages(
    JNIEnv* env,
    jclass /* clazz */,
    jobjectArray roles,
    jobjectArray contents,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jint topK,
    jboolean enableThinking,
    jobject callback) {
    LOGI("LlamaHelper: Stream generation from messages called");
    LOGI("LlamaHelper: maxTokens=%d, temp=%f, topP=%f, topK=%d, thinking=%d",
         maxTokens, temperature, topP, topK, (int)enableThinking);

    if (s_helperContext == nullptr || !s_helperContext->isValid()) {
        LOGE("LlamaHelper: s_helperContext is null or invalid");
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
        if (onErrorMethod != nullptr) {
            jstring errorStr = env->NewStringUTF("Model not initialized");
            env->CallVoidMethod(callback, onErrorMethod, errorStr);
            env->DeleteLocalRef(errorStr);
        }
        return;
    }

    if (!s_helperContext->ensureContext()) {
        LOGE("LlamaHelper: Failed to create context for stream");
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
        if (onErrorMethod != nullptr) {
            jstring errorStr = env->NewStringUTF("Failed to create inference context");
            env->CallVoidMethod(callback, onErrorMethod, errorStr);
            env->DeleteLocalRef(errorStr);
        }
        return;
    }

    jsize msgCount = env->GetArrayLength(roles);
    if (msgCount == 0 || env->GetArrayLength(contents) != msgCount) {
        LOGE("LlamaHelper: Invalid messages arrays, roles=%d, contents=%d",
             msgCount, env->GetArrayLength(contents));
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
        if (onErrorMethod != nullptr) {
            jstring errorStr = env->NewStringUTF("Invalid messages arrays");
            env->CallVoidMethod(callback, onErrorMethod, errorStr);
            env->DeleteLocalRef(errorStr);
        }
        return;
    }

    // 组装消息列表
    std::vector<std::pair<std::string, std::string>> messages;
    messages.reserve(msgCount);
    for (jsize i = 0; i < msgCount; i++) {
        jstring roleStr = (jstring)env->GetObjectArrayElement(roles, i);
        jbyteArray contentBytes = (jbyteArray)env->GetObjectArrayElement(contents, i);

        std::string role;
        if (roleStr != nullptr) {
            const char* roleChars = env->GetStringUTFChars(roleStr, nullptr);
            if (roleChars != nullptr) {
                role = roleChars;
                env->ReleaseStringUTFChars(roleStr, roleChars);
            }
            env->DeleteLocalRef(roleStr);
        }

        std::string content;
        if (contentBytes != nullptr) {
            content = bytesToUtf8String(env, contentBytes);
            env->DeleteLocalRef(contentBytes);
        }

        if (!role.empty() && !content.empty()) {
            messages.push_back({role, content});
        }
    }
    LOGI("LlamaHelper: Parsed %zu messages", messages.size());

    if (messages.empty()) {
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
        if (onErrorMethod != nullptr) {
            jstring errorStr = env->NewStringUTF("No valid messages");
            env->CallVoidMethod(callback, onErrorMethod, errorStr);
            env->DeleteLocalRef(errorStr);
        }
        return;
    }

    // 创建全局引用，防止回调时对象被回收
    jobject globalCallback = env->NewGlobalRef(callback);
    jclass callbackClass = env->GetObjectClass(globalCallback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "(Ljava/lang/String;)V");
    jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
    jclass globalCallbackClass = (jclass)env->NewGlobalRef(callbackClass);
    env->DeleteLocalRef(callbackClass);

    // 复用 nativeGenerateStreamBytes 的回调包装器（支持中文）
    auto tokenCallback = [globalCallback, globalCallbackClass, onTokenMethod, onCompleteMethod, onErrorMethod,
                          utf8Buffer = std::make_shared<std::string>()](const std::string& token, bool isDone, const std::string& error) mutable {
        JavaVM* jvm = getJavaVM();
        JNIEnv* env = nullptr;

        int result = jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        bool didAttach = false;
        if (result == JNI_EDETACHED) {
            if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                LOGE("Failed to attach thread to JVM");
                return;
            }
            didAttach = true;
        } else if (result != JNI_OK) {
            LOGE("Failed to get JNIEnv");
            return;
        }

        try {
            if (isDone) {
                if (!error.empty()) {
                    if (onErrorMethod != nullptr) {
                        jstring errorStr = utf8StringToJstring(env, error);
                        env->CallVoidMethod(globalCallback, onErrorMethod, errorStr);
                        env->DeleteLocalRef(errorStr);
                    }
                } else {
                    if (onCompleteMethod != nullptr) {
                        // 刷新 UTF-8 缓冲区中剩余的不完整字节
                        std::string finalToken = token;
                        if (!utf8Buffer->empty()) {
                            finalToken = *utf8Buffer + finalToken;
                            utf8Buffer->clear();
                        }
                        jstring resultStr = utf8StringToJstring(env, finalToken);
                        env->CallVoidMethod(globalCallback, onCompleteMethod, resultStr);
                        env->DeleteLocalRef(resultStr);
                    }
                }
            } else if (!token.empty()) {
                if (onTokenMethod != nullptr) {
                    // 将缓存的不完整字节与新 token 拼接
                    std::string combined = *utf8Buffer + token;
                    utf8Buffer->clear();
                    
                    // 分离完整 UTF-8 和不完整尾部
                    std::string completePart;
                    std::string incompleteTail = splitUtf8Complete(combined, completePart);
                    
                    // 缓存不完整尾部
                    if (!incompleteTail.empty()) {
                        *utf8Buffer = incompleteTail;
                    }
                    
                    // 只发送完整部分
                    if (!completePart.empty()) {
                        jstring tokenStr = utf8StringToJstring(env, completePart);
                        env->CallVoidMethod(globalCallback, onTokenMethod, tokenStr);
                        env->DeleteLocalRef(tokenStr);
                    }
                }
            }
        } catch (...) {
            LOGE("Exception in JNI callback");
        }

        if (isDone) {
            env->DeleteGlobalRef(globalCallback);
            env->DeleteGlobalRef(globalCallbackClass);
        }

        if (didAttach) {
            jvm->DetachCurrentThread();
        }
    };

    SAFE_RUN_INFERENCE(env, globalCallback, onErrorMethod,
        s_helperContext->generateStreamFromMessages(messages, maxTokens, temperature, topP, topK, enableThinking, tokenCallback)
    );
}

/**
 * 原生 Function Calling 生成 JNI 入口
 * 参数：
 *   roles/contents: 消息角色和内容数组
 *   toolsJson: OpenAI 格式的 tools JSON 数组字符串
 *   maxTokens/temperature/topP/topK/enableThinking: 生成参数
 *   callback: TokenCallback 回调
 */
JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGenerateWithTools(
    JNIEnv* env,
    jclass /* clazz */,
    jobjectArray roles,
    jobjectArray contents,
    jbyteArray toolsJsonBytes,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jint topK,
    jboolean enableThinking,
    jobject callback) {
    LOGI("LlamaHelper: Generate with tools called");

    if (s_helperContext == nullptr || !s_helperContext->isValid()) {
        LOGE("LlamaHelper: s_helperContext is null or invalid");
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
        if (onErrorMethod != nullptr) {
            jstring errorStr = env->NewStringUTF("Helper context not initialized");
            env->CallVoidMethod(callback, onErrorMethod, errorStr);
            env->DeleteLocalRef(errorStr);
        }
        return;
    }

    if (!s_helperContext->ensureContext()) {
        LOGE("LlamaHelper: Failed to create context for generate with tools");
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
        if (onErrorMethod != nullptr) {
            jstring errorStr = env->NewStringUTF("Failed to create inference context");
            env->CallVoidMethod(callback, onErrorMethod, errorStr);
            env->DeleteLocalRef(errorStr);
        }
        return;
    }

    // 解析消息数组
    jsize msgCount = env->GetArrayLength(roles);
    std::vector<std::pair<std::string, std::string>> messages;
    for (jsize i = 0; i < msgCount; i++) {
        auto roleStr = (jstring)env->GetObjectArrayElement(roles, i);
        jbyteArray contentBytes = (jbyteArray)env->GetObjectArrayElement(contents, i);

        std::string role;
        if (roleStr != nullptr) {
            const char* roleChars = env->GetStringUTFChars(roleStr, nullptr);
            if (roleChars != nullptr) {
                role = roleChars;
                env->ReleaseStringUTFChars(roleStr, roleChars);
            }
            env->DeleteLocalRef(roleStr);
        }

        std::string content;
        if (contentBytes != nullptr) {
            content = bytesToUtf8String(env, contentBytes);
            env->DeleteLocalRef(contentBytes);
        }

        if (!role.empty() && !content.empty()) {
            messages.push_back({role, content});
        }
    }
    LOGI("LlamaHelper: Parsed %zu messages", messages.size());

    // 解析 tools JSON
    std::string toolsJson;
    if (toolsJsonBytes != nullptr) {
        toolsJson = bytesToUtf8String(env, toolsJsonBytes);
    }
    LOGI("LlamaHelper: toolsJson length: %zu", toolsJson.size());

    // 创建全局引用
    jobject globalCallback = env->NewGlobalRef(callback);
    jclass callbackClass = env->GetObjectClass(globalCallback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "(Ljava/lang/String;)V");
    jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
    // onToolCalls 和 onReasoning 是 default 方法，需要用接口类查找
    jclass toolCallInfoClass = env->FindClass("com/oilquiz/app/ai/service/OnlineInferenceService$ToolCallInfo");
    jclass listClass = env->FindClass("java/util/ArrayList");
    jclass globalCallbackClass = (jclass)env->NewGlobalRef(callbackClass);
    env->DeleteLocalRef(callbackClass);

    // 回调包装器
    auto tokenCallback = [globalCallback, globalCallbackClass, onTokenMethod, onCompleteMethod, onErrorMethod,
                          utf8Buffer = std::make_shared<std::string>()](const std::string& token, bool isDone, const std::string& error) mutable {
        JavaVM* jvm = getJavaVM();
        JNIEnv* env = nullptr;

        int result = jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        bool didAttach = false;
        if (result == JNI_EDETACHED) {
            if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                didAttach = true;
            } else {
                return;
            }
        } else if (result != JNI_OK) {
            return;
        }

        try {
            if (isDone) {
                if (!error.empty()) {
                    if (onErrorMethod != nullptr) {
                        jstring errorStr = utf8StringToJstring(env, error);
                        env->CallVoidMethod(globalCallback, onErrorMethod, errorStr);
                        env->DeleteLocalRef(errorStr);
                    }
                } else {
                    if (onCompleteMethod != nullptr) {
                        // 刷新 UTF-8 缓冲区中剩余的不完整字节
                        std::string finalToken = token;
                        if (!utf8Buffer->empty()) {
                            finalToken = *utf8Buffer + finalToken;
                            utf8Buffer->clear();
                        }
                        jstring resultStr = utf8StringToJstring(env, finalToken);
                        env->CallVoidMethod(globalCallback, onCompleteMethod, resultStr);
                        env->DeleteLocalRef(resultStr);
                    }
                }
            } else if (!token.empty()) {
                if (onTokenMethod != nullptr) {
                    // 将缓存的不完整字节与新 token 拼接
                    std::string combined = *utf8Buffer + token;
                    utf8Buffer->clear();
                    
                    // 分离完整 UTF-8 和不完整尾部
                    std::string completePart;
                    std::string incompleteTail = splitUtf8Complete(combined, completePart);
                    
                    // 缓存不完整尾部
                    if (!incompleteTail.empty()) {
                        *utf8Buffer = incompleteTail;
                    }
                    
                    // 只发送完整部分
                    if (!completePart.empty()) {
                        jstring tokenStr = utf8StringToJstring(env, completePart);
                        env->CallVoidMethod(globalCallback, onTokenMethod, tokenStr);
                        env->DeleteLocalRef(tokenStr);
                    }
                }
            }
        } catch (...) {
            LOGE("Exception in JNI callback");
        }

        if (isDone) {
            env->DeleteGlobalRef(globalCallback);
            env->DeleteGlobalRef(globalCallbackClass);
        }

        if (didAttach) {
            jvm->DetachCurrentThread();
        }
    };

    // 调用 generateWithTools，获取 common_chat_parse 解析结果
    llama_jni::InferenceContext::ChatParseResult parseResult;
    SAFE_RUN_INFERENCE(env, globalCallback, onErrorMethod,
        s_helperContext->generateWithTools(messages, toolsJson, maxTokens, temperature, topP, topK, enableThinking, tokenCallback, &parseResult)
    );

    // 将 common_chat_parse 解析结果通过 Java 回调传递
    // onToolCalls: 创建 ToolCallInfo 列表并调用 onToolCalls 回调
    // onReasoning: 调用 onReasoning 回调
    // 这与在线 Agent 使用相同的 ToolCallInfo 格式，工具调用互通，无需中间层
    try {
        if (!parseResult.tool_calls.empty() && toolCallInfoClass != nullptr && listClass != nullptr) {
            // 创建 ArrayList<ToolCallInfo>
            jmethodID listConstructor = env->GetMethodID(listClass, "<init>", "()V");
            jmethodID listAdd = env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");
            jmethodID tcConstructor = env->GetMethodID(toolCallInfoClass, "<init>", "()V");

            if (listConstructor != nullptr && listAdd != nullptr && tcConstructor != nullptr) {
                jobject listObj = env->NewObject(listClass, listConstructor);

                // ToolCallInfo 的字段: id (String), name (String), arguments (String)
                jfieldID idField = env->GetFieldID(toolCallInfoClass, "id", "Ljava/lang/String;");
                jfieldID nameField = env->GetFieldID(toolCallInfoClass, "name", "Ljava/lang/String;");
                jfieldID argsField = env->GetFieldID(toolCallInfoClass, "arguments", "Ljava/lang/String;");

                if (idField != nullptr && nameField != nullptr && argsField != nullptr) {
                    for (const auto& tc : parseResult.tool_calls) {
                        jobject tcObj = env->NewObject(toolCallInfoClass, tcConstructor);
                        if (!tc.id.empty()) {
                            jstring idStr = utf8StringToJstring(env, tc.id);
                            env->SetObjectField(tcObj, idField, idStr);
                            env->DeleteLocalRef(idStr);
                        }
                        if (!tc.name.empty()) {
                            jstring nameStr = utf8StringToJstring(env, tc.name);
                            env->SetObjectField(tcObj, nameField, nameStr);
                            env->DeleteLocalRef(nameStr);
                        }
                        if (!tc.arguments.empty()) {
                            jstring argsStr = utf8StringToJstring(env, tc.arguments);
                            env->SetObjectField(tcObj, argsField, argsStr);
                            env->DeleteLocalRef(argsStr);
                        }
                        env->CallBooleanMethod(listObj, listAdd, tcObj);
                        env->DeleteLocalRef(tcObj);
                    }
                }

                // 调用 onToolCalls 回调
                // 使用接口类查找 default 方法
                jclass tokenCallbackClass = env->FindClass("com/oilquiz/app/ai/jni/LlamaHelper$TokenCallback");
                if (tokenCallbackClass != nullptr) {
                    jmethodID onToolCallsMethod = env->GetMethodID(tokenCallbackClass, "onToolCalls", "(Ljava/util/List;)V");
                    if (onToolCallsMethod != nullptr) {
                        env->CallVoidMethod(globalCallback, onToolCallsMethod, listObj);
                        LOGI("Called Java onToolCalls with %zu tool calls", parseResult.tool_calls.size());
                    }
                    env->DeleteLocalRef(tokenCallbackClass);
                }

                env->DeleteLocalRef(listObj);
            }
        }

        // onReasoning 回调
        if (!parseResult.reasoning_content.empty()) {
            jclass tokenCallbackClass = env->FindClass("com/oilquiz/app/ai/jni/LlamaHelper$TokenCallback");
            if (tokenCallbackClass != nullptr) {
                jmethodID onReasoningMethod = env->GetMethodID(tokenCallbackClass, "onReasoning", "(Ljava/lang/String;)V");
                if (onReasoningMethod != nullptr) {
                    jstring reasoningStr = utf8StringToJstring(env, parseResult.reasoning_content);
                    env->CallVoidMethod(globalCallback, onReasoningMethod, reasoningStr);
                    env->DeleteLocalRef(reasoningStr);
                    LOGI("Called Java onReasoning with %zu chars", parseResult.reasoning_content.size());
                }
                env->DeleteLocalRef(tokenCallbackClass);
            }
        }
    } catch (...) {
        LOGE("Exception in JNI onToolCalls/onReasoning callback");
    }

    // 清理局部引用
    if (toolCallInfoClass != nullptr) env->DeleteLocalRef(toolCallInfoClass);
    if (listClass != nullptr) env->DeleteLocalRef(listClass);
}

// ===== 统一 JSON 协议 JNI（spec §6.1）=====
JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatJson(
    JNIEnv* env,
    jclass /* clazz */,
    jbyteArray requestJsonBytes,
    jobject callback) {
    LOGI("LlamaHelper: nativeChatJson called");

    if (s_helperContext == nullptr || !s_helperContext->isValid()) {
        LOGE("LlamaHelper: s_helperContext is null or invalid");
        jclass cbClass = env->GetObjectClass(callback);
        jmethodID onJsonMethod = env->GetMethodID(cbClass, "onJson", "(Ljava/lang/String;)V");
        if (onJsonMethod != nullptr) {
            jstring err = env->NewStringUTF("{\"type\":\"error\",\"message\":\"Helper context not initialized\"}");
            env->CallVoidMethod(callback, onJsonMethod, err);
            env->DeleteLocalRef(err);
        }
        return;
    }

    if (!s_helperContext->ensureContext()) {
        LOGE("LlamaHelper: Failed to create context for nativeChatJson");
        jclass cbClass = env->GetObjectClass(callback);
        jmethodID onJsonMethod = env->GetMethodID(cbClass, "onJson", "(Ljava/lang/String;)V");
        if (onJsonMethod != nullptr) {
            jstring err = env->NewStringUTF("{\"type\":\"error\",\"message\":\"Failed to create inference context\"}");
            env->CallVoidMethod(callback, onJsonMethod, err);
            env->DeleteLocalRef(err);
        }
        return;
    }

    std::string requestJson = bytesToUtf8String(env, requestJsonBytes);
    LOGI("LlamaHelper: nativeChatJson request len: %zu", requestJson.size());

    // 全局引用
    jobject globalCallback = env->NewGlobalRef(callback);
    jclass callbackClass = env->GetObjectClass(globalCallback);
    // onJson / onError 用接口类查找（default 方法需接口类，与 onToolCalls/onReasoning 同模式）
    jclass jsonCallbackClass = env->FindClass("com/oilquiz/app/ai/jni/LlamaHelper$JsonCallback");
    jclass effectiveClass = jsonCallbackClass != nullptr ? jsonCallbackClass : callbackClass;
    jmethodID onJsonMethod = env->GetMethodID(effectiveClass, "onJson", "(Ljava/lang/String;)V");
    jmethodID onErrorMethod = env->GetMethodID(effectiveClass, "onError", "(Ljava/lang/String;)V");
    if (jsonCallbackClass != nullptr) env->DeleteLocalRef(jsonCallbackClass);
    env->DeleteLocalRef(callbackClass);

    // 包装 JsonCallback：推理线程 → Java onJson 回调（AttachCurrentThread 若需要）
    JavaVM* jvm = getJavaVM();
    auto jsonCallback = [jvm, globalCallback, onJsonMethod](const std::string& json) {
        JNIEnv* cbEnv = nullptr;
        bool didAttach = false;
        if (jvm->GetEnv(reinterpret_cast<void**>(&cbEnv), JNI_VERSION_1_6) != JNI_OK) {
            if (jvm->AttachCurrentThread(&cbEnv, nullptr) != JNI_OK) return;
            didAttach = true;
        }
        try {
            jstring js = utf8StringToJstring(cbEnv, json);
            cbEnv->CallVoidMethod(globalCallback, onJsonMethod, js);
            cbEnv->DeleteLocalRef(js);
        } catch (...) {
            LOGE("Exception in nativeChatJson onJson callback");
        }
        if (didAttach) jvm->DetachCurrentThread();
    };

    // SAFE_RUN_INFERENCE：崩溃恢复 + isGenerating 复位 + EnsureLocalCapacity
    // 崩溃路径走 JsonCallback.onError 默认方法（Java 侧转成 error JSON 事件）
    SAFE_RUN_INFERENCE(env, globalCallback, onErrorMethod,
        s_helperContext->chatJson(requestJson, jsonCallback)
    );

    env->DeleteGlobalRef(globalCallback);
}

JNIEXPORT jobjectArray JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGenerateBatch(
    JNIEnv* env,
    jclass /* clazz */,
    jobjectArray prompts,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jint topK) {
    LOGI("LlamaHelper: Batch generation called");
    
    if (s_helperContext == nullptr || !s_helperContext->isValid()) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), env->NewStringUTF(""));
    }

    if (!s_helperContext->ensureContext()) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), env->NewStringUTF(""));
    }
    
    jsize promptCount = env->GetArrayLength(prompts);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray results = env->NewObjectArray(promptCount, stringClass, env->NewStringUTF(""));
    
    // 收集所有prompt
    std::vector<std::string> promptList;
    for (jsize i = 0; i < promptCount; i++) {
        jstring prompt = (jstring)env->GetObjectArrayElement(prompts, i);
        const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
        
        if (promptStr != nullptr) {
            promptList.push_back(std::string(promptStr));
            env->ReleaseStringUTFChars(prompt, promptStr);
        }
        env->DeleteLocalRef(prompt);
    }
    
    // 使用并行批处理生成
    std::vector<std::string> batchResults = s_helperContext->generateBatch(promptList, maxTokens, temperature, topP, topK);
    
    // 将结果转换回Java数组
    for (jsize i = 0; i < batchResults.size() && i < promptCount; i++) {
        jstring resultStr = env->NewStringUTF(batchResults[i].c_str());
        env->SetObjectArrayElement(results, i, resultStr);
        env->DeleteLocalRef(resultStr);
    }
    
    return results;
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeCountTokens(
    JNIEnv* env,
    jclass /* clazz */,
    jstring text) {
    LOGI("LlamaHelper: Counting tokens");
    
    if (s_helperContext == nullptr || !s_helperContext->isValid()) {
        LOGI("LlamaHelper: s_helperContext is null or invalid, returning 0");
        return 0;
    }
    
    const char* textStr = env->GetStringUTFChars(text, nullptr);
    if (textStr == nullptr) {
        LOGI("LlamaHelper: Failed to get text string, returning 0");
        return 0;
    }
    
    std::string textContent(textStr);
    env->ReleaseStringUTFChars(text, textStr);
    
    // 使用 llama_tokenize 计算 token 数量
    const llama_vocab* vocab = s_helperContext->getVocab();
    if (vocab == nullptr) {
        LOGI("LlamaHelper: Failed to get vocab, returning 0");
        return 0;
    }
    
    int tokenCount = -llama_tokenize(vocab, textContent.c_str(), textContent.size(), NULL, 0, true, true);
    LOGI("LlamaHelper: Token count for text '%s' is %d", textContent.substr(0, 50).c_str(), tokenCount);
    
    return tokenCount > 0 ? tokenCount : 0;
}

JNIEXPORT jfloat JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetMemoryUsage(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext != nullptr && s_helperContext->isValid()) {
        FILE* fp = fopen("/proc/self/status", "r");
        if (fp) {
            char line[256];
            long vmRSS = 0;
            while (fgets(line, sizeof(line), fp)) {
                if (sscanf(line, "VmRSS: %ld kB", &vmRSS) == 1) {
                    break;
                }
            }
            fclose(fp);
            return (float)(vmRSS / 1024);
        }
    }
    return 0.0f;
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeOptimizeForMemory(
    JNIEnv* env,
    jclass /* clazz */) {
    LOGI("LlamaHelper: Optimizing for memory");
    llama_jni::s_defaultThreadCount = 2;
    llama_jni::s_defaultBatchSize = 128;
    llama_jni::s_defaultMemoryPoolSize = 512;
    if (s_helperContext != nullptr) {
        s_helperContext->setThreadCount(2);
        s_helperContext->setBatchSize(128);
        s_helperContext->setMemoryPoolSize(512);
    }
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetDeviceInfo(
    JNIEnv* env,
    jclass /* clazz */) {
    std::string info;
    
    info += "CPU Info:\n";
    FILE* fp = fopen("/proc/cpuinfo", "r");
    if (fp) {
        char line[256];
        int lineCount = 0;
        while (fgets(line, sizeof(line), fp) && lineCount < 10) {
            info += line;
            lineCount++;
        }
        fclose(fp);
    }
    
    info += "\nMemory Info:\n";
    fp = fopen("/proc/meminfo", "r");
    if (fp) {
        char line[256];
        int lineCount = 0;
        while (fgets(line, sizeof(line), fp) && lineCount < 5) {
            info += line;
            lineCount++;
        }
        fclose(fp);
    }
    
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetDeviceCount(
    JNIEnv* env,
    jclass /* clazz */) {
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetFreeDeviceMemory(
    JNIEnv* env,
    jclass /* clazz */) {
    FILE* fp = fopen("/proc/meminfo", "r");
    if (fp) {
        char line[256];
        long memFree = 0;
        while (fgets(line, sizeof(line), fp)) {
            if (sscanf(line, "MemAvailable: %ld kB", &memFree) == 1) {
                break;
            }
        }
        fclose(fp);
        return (jlong)(memFree * 1024);
    }
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetTotalDeviceMemory(
    JNIEnv* env,
    jclass /* clazz */) {
    FILE* fp = fopen("/proc/meminfo", "r");
    if (fp) {
        char line[256];
        long memTotal = 0;
        while (fgets(line, sizeof(line), fp)) {
            if (sscanf(line, "MemTotal: %ld kB", &memTotal) == 1) {
                break;
            }
        }
        fclose(fp);
        return (jlong)(memTotal * 1024);
    }
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetGpuMaxMemAllocSize(
    JNIEnv* env,
    jclass /* clazz */) {
    return (jlong)s_detectedMaxMemAllocSize;
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetContextSize(
    JNIEnv* env,
    jclass /* clazz */,
    jlong handle) {
    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (chatCtx && chatCtx->isValid()) {
        return chatCtx->getContextSize();
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetContextUsedTokens(
    JNIEnv* env,
    jclass /* clazz */,
    jlong handle) {
    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (chatCtx && chatCtx->isValid()) {
        return chatCtx->getContextUsedTokens();
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetContextRemainingTokens(
    JNIEnv* env,
    jclass /* clazz */,
    jlong handle) {
    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (chatCtx && chatCtx->isValid()) {
        return chatCtx->getContextRemainingTokens();
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeHasEnoughContextSpace(
    JNIEnv* env,
    jclass /* clazz */,
    jlong handle,
    jint promptTokens,
    jint maxOutputTokens) {
    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (chatCtx && chatCtx->isValid()) {
        return chatCtx->hasEnoughContextSpace(promptTokens, maxOutputTokens) ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeClearContextForInference(
    JNIEnv* env,
    jclass /* clazz */,
    jlong handle) {
    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (chatCtx && chatCtx->isValid()) {
        // 信号保护：推理崩溃（signal 6）后 GPU/KV 状态可能已损坏，
        // 此时清理 KV 会触发 SIGSEGV，若无 sigsetjmp 保护会直接杀死整个进程
        // （表现为导入结束后 App 瞬间退出）
        has_jmp_set = true;
        int sig = sigsetjmp(fatal_jmp_buf, 1);
        if (sig != 0) {
            has_jmp_set = false;
            LOGE("clearContextForInference: caught fatal signal %d, KV cleanup aborted", sig);
            if (s_helperContext != nullptr && s_helperContext->isCurrentlyGenerating()) {
                s_helperContext->forceResetGeneration();
            }
            return;
        }
        chatCtx->clearContextForInference();
        has_jmp_set = false;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeIsOpenCLLoaded(
    JNIEnv* /* env */,
    jclass /* clazz */) {
    return s_openclLoaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeSetOpenCLLoaded(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jboolean loaded) {
    s_openclLoaded = loaded ? JNI_TRUE : JNI_FALSE;
    __android_log_print(ANDROID_LOG_INFO, "LlamaJNI", "s_openclLoaded set to %d from Java", loaded);
}

JNIEXPORT jboolean JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeIsGPUWorking(
    JNIEnv* /* env */,
    jclass /* clazz */) {
    return s_gpuWorking ? JNI_TRUE : JNI_FALSE;
}

static std::string getOpenCLDeviceInfoFromAPI() {
    std::string info;
    
    if (!s_openclLoaded || !s_oclHandle) {
        info += "OpenCL library not loaded\n";
        return info;
    }
    
    typedef cl_int (*clGetPlatformIDs_fn)(cl_uint, cl_platform_id*, cl_uint*);
    typedef cl_int (*clGetPlatformInfo_fn)(cl_platform_id, cl_platform_info, size_t, void*, size_t*);
    typedef cl_int (*clGetDeviceIDs_fn)(cl_platform_id, cl_device_type, cl_uint, cl_device_id*, cl_uint*);
    typedef cl_int (*clGetDeviceInfo_fn)(cl_device_id, cl_device_info, size_t, void*, size_t*);
    
    auto clGetPlatformIDs_ptr = (clGetPlatformIDs_fn)dlsym(s_oclHandle, "clGetPlatformIDs");
    auto clGetPlatformInfo_ptr = (clGetPlatformInfo_fn)dlsym(s_oclHandle, "clGetPlatformInfo");
    auto clGetDeviceIDs_ptr = (clGetDeviceIDs_fn)dlsym(s_oclHandle, "clGetDeviceIDs");
    auto clGetDeviceInfo_ptr = (clGetDeviceInfo_fn)dlsym(s_oclHandle, "clGetDeviceInfo");
    
    if (!clGetPlatformIDs_ptr || !clGetPlatformInfo_ptr || !clGetDeviceIDs_ptr || !clGetDeviceInfo_ptr) {
        LOGW("Could not resolve OpenCL functions for device info");
        info += "Failed to resolve OpenCL functions\n";
        return info;
    }
    
    cl_uint numPlatforms = 0;
    if (clGetPlatformIDs_ptr(0, nullptr, &numPlatforms) != CL_SUCCESS || numPlatforms == 0) {
        info += "No OpenCL platforms found\n";
        return info;
    }
    
    info += "OpenCL Platforms: " + std::to_string(numPlatforms) + "\n";
    
    std::vector<cl_platform_id> platforms(numPlatforms);
    if (clGetPlatformIDs_ptr(numPlatforms, platforms.data(), nullptr) != CL_SUCCESS) {
        info += "Failed to get platforms\n";
        return info;
    }
    
    int gpuCount = 0;
    bool hasAdreno = false;
    
    for (auto platform : platforms) {
        char platformVendor[256] = {0};
        char platformName[256] = {0};
        char platformVersion[256] = {0};
        clGetPlatformInfo_ptr(platform, CL_PLATFORM_VENDOR, sizeof(platformVendor), platformVendor, nullptr);
        clGetPlatformInfo_ptr(platform, CL_PLATFORM_NAME, sizeof(platformName), platformName, nullptr);
        clGetPlatformInfo_ptr(platform, CL_PLATFORM_VERSION, sizeof(platformVersion), platformVersion, nullptr);
        
        info += "\nPlatform: " + std::string(platformName) + "\n";
        info += "  Vendor: " + std::string(platformVendor) + "\n";
        info += "  Version: " + std::string(platformVersion) + "\n";
        
        cl_uint numDevices = 0;
        if (clGetDeviceIDs_ptr(platform, CL_DEVICE_TYPE_GPU, 0, nullptr, &numDevices) != CL_SUCCESS || numDevices == 0) {
            info += "  No GPU devices found\n";
            continue;
        }
        
        gpuCount += numDevices;
        info += "  GPU Devices: " + std::to_string(numDevices) + "\n";
        
        std::vector<cl_device_id> devices(numDevices);
        if (clGetDeviceIDs_ptr(platform, CL_DEVICE_TYPE_GPU, numDevices, devices.data(), nullptr) != CL_SUCCESS) {
            continue;
        }
        
        for (auto device : devices) {
            char deviceName[256] = {0};
            char deviceVendor[256] = {0};
            char deviceVersion[256] = {0};
            cl_ulong globalMemSize = 0;
            cl_ulong maxMemAllocSize = 0;
            cl_uint maxComputeUnits = 0;
            cl_uint maxFreq = 0;
            
            clGetDeviceInfo_ptr(device, CL_DEVICE_NAME, sizeof(deviceName), deviceName, nullptr);
            clGetDeviceInfo_ptr(device, CL_DEVICE_VENDOR, sizeof(deviceVendor), deviceVendor, nullptr);
            clGetDeviceInfo_ptr(device, CL_DEVICE_VERSION, sizeof(deviceVersion), deviceVersion, nullptr);
            clGetDeviceInfo_ptr(device, CL_DEVICE_GLOBAL_MEM_SIZE, sizeof(globalMemSize), &globalMemSize, nullptr);
            clGetDeviceInfo_ptr(device, CL_DEVICE_MAX_MEM_ALLOC_SIZE, sizeof(maxMemAllocSize), &maxMemAllocSize, nullptr);
            clGetDeviceInfo_ptr(device, CL_DEVICE_MAX_COMPUTE_UNITS, sizeof(maxComputeUnits), &maxComputeUnits, nullptr);
            clGetDeviceInfo_ptr(device, CL_DEVICE_MAX_CLOCK_FREQUENCY, sizeof(maxFreq), &maxFreq, nullptr);
            
            size_t extSize = 0;
            clGetDeviceInfo_ptr(device, CL_DEVICE_EXTENSIONS, 0, nullptr, &extSize);
            std::vector<char> extensions(extSize, 0);
            if (extSize > 0) {
                clGetDeviceInfo_ptr(device, CL_DEVICE_EXTENSIONS, extSize, extensions.data(), nullptr);
            }
            std::string deviceExtensions(extensions.data());
            bool supportsFP16 = deviceExtensions.find("cl_khr_fp16") != std::string::npos;
            
            std::string devName = deviceName;
            std::string devVendor = deviceVendor;
            
            info += "\n    Device: " + devName + "\n";
            info += "      Vendor: " + devVendor + "\n";
            info += "      Version: " + std::string(deviceVersion) + "\n";
            info += "      Compute Units: " + std::to_string(maxComputeUnits) + "\n";
            info += "      Max Frequency: " + std::to_string(maxFreq) + " MHz\n";
            info += "      Global Memory: " + std::to_string(globalMemSize/1024/1024) + " MB\n";
            info += "      Max Alloc Size: " + std::to_string(maxMemAllocSize/1024/1024) + " MB\n";
            info += "      FP16 Support: " + std::string(supportsFP16 ? "Yes (cl_khr_fp16)" : "No") + "\n";
            
            if (devName.find("Adreno") != std::string::npos || 
                devVendor.find("QUALCOMM") != std::string::npos ||
                devVendor.find("Qualcomm") != std::string::npos) {
                hasAdreno = true;
                info += "      Type: Qualcomm Adreno GPU (Supported)\n";
            }
        }
    }
    
    info += "\nSummary: " + std::to_string(gpuCount) + " GPU device(s) detected";
    if (hasAdreno) {
        info += " (Adreno GPU detected)\n";
    } else {
        info += "\n";
    }
    
    return info;
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetOpenCLInfo(
    JNIEnv* env,
    jclass /* clazz */) {
    std::string info;
    
    if (!s_openclLoaded) {
        info = "OpenCL: Not loaded\n";
        info += "GPU Mode: CPU only (OpenCL not available)\n";
        info += "Note: OpenCL library not found on this device\n";
        info += "Tried paths:\n";
        info += "  /vendor/lib64/libOpenCL.so\n";
        info += "  /vendor/lib/libOpenCL.so\n";
        info += "  /system/lib64/libOpenCL.so\n";
        info += "  /system/lib/libOpenCL.so\n";
        info += "  /vendor/lib64/libOpenCL-pixel.so (Pixel)\n";
        info += "  /vendor/lib64/libOpenCL-car.so (Adreno)";
        return env->NewStringUTF(info.c_str());
    }
    
    info = "OpenCL: Loaded successfully\n";
    info += "Backend: OpenCL\n\n";
    
    if (s_gpuWorking) {
        info += "GPU Acceleration: ACTIVE (Hardware acceleration enabled)\n";
        info += "Status: Model loaded with GPU support\n\n";
    } else if (s_gpuTested) {
        info += "GPU Acceleration: FAILED (Fallback to CPU)\n";
        info += "Status: GPU mode tested but failed, using CPU instead\n\n";
    } else {
        info += "GPU Acceleration: NOT TESTED (Model not loaded yet)\n";
        info += "Status: Load a model to test GPU acceleration\n\n";
    }
    
    info += "=== GPU Device Detection ===\n";
    std::string devInfo = getOpenCLDeviceInfoFromAPI();
    if (!devInfo.empty()) {
        info += devInfo;
    } else {
        info += "Could not detect GPU devices\n";
    }
    
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeDetectGPUInfo(
    JNIEnv* env,
    jclass /* clazz */) {
    std::string result = "{}";
    
    if (s_openclLoaded && s_oclHandle) {
        typedef cl_int (*clGetPlatformIDs_fn)(cl_uint, cl_platform_id*, cl_uint*);
        typedef cl_int (*clGetPlatformInfo_fn)(cl_platform_id, cl_platform_info, size_t, void*, size_t*);
        typedef cl_int (*clGetDeviceIDs_fn)(cl_platform_id, cl_device_type, cl_uint, cl_device_id*, cl_uint*);
        typedef cl_int (*clGetDeviceInfo_fn)(cl_device_id, cl_device_info, size_t, void*, size_t*);
        
        auto clGetPlatformIDs_ptr = (clGetPlatformIDs_fn)dlsym(s_oclHandle, "clGetPlatformIDs");
        auto clGetPlatformInfo_ptr = (clGetPlatformInfo_fn)dlsym(s_oclHandle, "clGetPlatformInfo");
        auto clGetDeviceIDs_ptr = (clGetDeviceIDs_fn)dlsym(s_oclHandle, "clGetDeviceIDs");
        auto clGetDeviceInfo_ptr = (clGetDeviceInfo_fn)dlsym(s_oclHandle, "clGetDeviceInfo");
        
        if (clGetPlatformIDs_ptr && clGetPlatformInfo_ptr && clGetDeviceIDs_ptr && clGetDeviceInfo_ptr) {
            cl_uint numPlatforms = 0;
            if (clGetPlatformIDs_ptr(0, nullptr, &numPlatforms) == CL_SUCCESS && numPlatforms > 0) {
                std::vector<cl_platform_id> platforms(numPlatforms);
                if (clGetPlatformIDs_ptr(numPlatforms, platforms.data(), nullptr) == CL_SUCCESS) {
                    for (auto platform : platforms) {
                        cl_uint numDevices = 0;
                        if (clGetDeviceIDs_ptr(platform, CL_DEVICE_TYPE_GPU, 0, nullptr, &numDevices) == CL_SUCCESS && numDevices > 0) {
                            std::vector<cl_device_id> devices(numDevices);
                            if (clGetDeviceIDs_ptr(platform, CL_DEVICE_TYPE_GPU, numDevices, devices.data(), nullptr) == CL_SUCCESS) {
                                for (auto device : devices) {
                                    char deviceName[256] = {0};
                                    char deviceVendor[256] = {0};
                                    char deviceVersion[256] = {0};
                                    cl_ulong globalMemSize = 0;
                                    cl_ulong maxMemAllocSize = 0;
                                    cl_uint maxComputeUnits = 0;
                                    cl_uint maxFreq = 0;
                                    
                                    clGetDeviceInfo_ptr(device, CL_DEVICE_NAME, sizeof(deviceName), deviceName, nullptr);
                                    clGetDeviceInfo_ptr(device, CL_DEVICE_VENDOR, sizeof(deviceVendor), deviceVendor, nullptr);
                                    clGetDeviceInfo_ptr(device, CL_DEVICE_VERSION, sizeof(deviceVersion), deviceVersion, nullptr);
                                    clGetDeviceInfo_ptr(device, CL_DEVICE_GLOBAL_MEM_SIZE, sizeof(globalMemSize), &globalMemSize, nullptr);
                                    clGetDeviceInfo_ptr(device, CL_DEVICE_MAX_MEM_ALLOC_SIZE, sizeof(maxMemAllocSize), &maxMemAllocSize, nullptr);
                                    clGetDeviceInfo_ptr(device, CL_DEVICE_MAX_COMPUTE_UNITS, sizeof(maxComputeUnits), &maxComputeUnits, nullptr);
                                    clGetDeviceInfo_ptr(device, CL_DEVICE_MAX_CLOCK_FREQUENCY, sizeof(maxFreq), &maxFreq, nullptr);
                                    
                                    size_t extSize = 0;
                                    clGetDeviceInfo_ptr(device, CL_DEVICE_EXTENSIONS, 0, nullptr, &extSize);
                                    std::vector<char> extensions(extSize, 0);
                                    if (extSize > 0) {
                                        clGetDeviceInfo_ptr(device, CL_DEVICE_EXTENSIONS, extSize, extensions.data(), nullptr);
                                    }
                                    std::string deviceExtensions(extensions.data());
                                    
                                    bool supportsFP16 = deviceExtensions.find("cl_khr_fp16") != std::string::npos;
                                    bool supportsFP64 = deviceExtensions.find("cl_khr_fp64") != std::string::npos;
                                    bool supportsBF16 = deviceExtensions.find("cl_khr_fp16") != std::string::npos;
                                    bool supportsATIFMA = deviceExtensions.find("cl_arm_fp16") != std::string::npos;
                                    bool supportsSUBGLOBAL_INT8 = deviceExtensions.find("cl_khr_quantized_float16") != std::string::npos;
                                    
                                    cl_uint halfFPConfig = 0;
                                    cl_uint singleFPConfig = 0;
                                    cl_uint doubleFPConfig = 0;
                                    cl_uint halfVectorWidth = 0;
                                    cl_uint floatVectorWidth = 0;
                                    cl_uint doubleVectorWidth = 0;
                                    
                                    clGetDeviceInfo_ptr(device, CL_DEVICE_HALF_FP_CONFIG, sizeof(halfFPConfig), &halfFPConfig, nullptr);
                                    clGetDeviceInfo_ptr(device, CL_DEVICE_SINGLE_FP_CONFIG, sizeof(singleFPConfig), &singleFPConfig, nullptr);
                                    clGetDeviceInfo_ptr(device, CL_DEVICE_DOUBLE_FP_CONFIG, sizeof(doubleFPConfig), &doubleFPConfig, nullptr);
                                    clGetDeviceInfo_ptr(device, CL_DEVICE_NATIVE_VECTOR_WIDTH_HALF, sizeof(halfVectorWidth), &halfVectorWidth, nullptr);
                                    clGetDeviceInfo_ptr(device, CL_DEVICE_NATIVE_VECTOR_WIDTH_FLOAT, sizeof(floatVectorWidth), &floatVectorWidth, nullptr);
                                    clGetDeviceInfo_ptr(device, CL_DEVICE_NATIVE_VECTOR_WIDTH_DOUBLE, sizeof(doubleVectorWidth), &doubleVectorWidth, nullptr);
                                    
                                    std::string fpTypes;
                                    std::vector<std::string> supportedTypes;
                                    
                                    bool hasFP32 = true;
                                    bool hasFP16 = supportsFP16 || (halfFPConfig != 0 || halfVectorWidth > 0);
                                    bool hasFP64 = supportsFP64 || (doubleFPConfig != 0 || doubleVectorWidth > 0);
                                    bool hasBF16 = supportsBF16;
                                    
                                    if (hasFP32) {
                                        supportedTypes.push_back("FP32");
                                    }
                                    if (hasFP16) {
                                        supportedTypes.push_back("FP16");
                                    }
                                    if (hasFP64) {
                                        supportedTypes.push_back("FP64");
                                    }
                                    if (hasBF16) {
                                        supportedTypes.push_back("BF16");
                                    }
                                    
                                    if (!supportedTypes.empty()) {
                                        for (size_t i = 0; i < supportedTypes.size(); i++) {
                                            fpTypes += supportedTypes[i];
                                            if (i < supportedTypes.size() - 1) {
                                                fpTypes += ", ";
                                            }
                                        }
                                    } else {
                                        fpTypes = "FP32";
                                    }
                                    
                                    std::string name = deviceName;
                                    std::string vendor = deviceVendor;
                                    std::string version = deviceVersion;
                                    
                                    std::string openclVersion = version;
                                    std::string vulkanVersion = "Unknown";
                                    
                                    std::string apiVulkanVersion = detectVulkanVersionViaAPI();
                                    if (!apiVulkanVersion.empty() && apiVulkanVersion != "Unknown") {
                                        vulkanVersion = apiVulkanVersion;
                                    } else {
                                        FILE* f1 = popen("getprop ro.hardware.vulkan.version", "r");
                                        if (f1) {
                                            char buf[64] = {0};
                                            if (fgets(buf, sizeof(buf), f1) != nullptr) {
                                                vulkanVersion = buf;
                                                vulkanVersion.erase(vulkanVersion.find_last_not_of(" \n\r\t") + 1);
                                            }
                                            pclose(f1);
                                        }
                                        
                                        if (vulkanVersion.empty() || vulkanVersion == "0" || vulkanVersion == "Unknown") {
                                            if (name.find("Adreno 750") != std::string::npos) {
                                                vulkanVersion = "1.3";
                                            } else if (name.find("Adreno 7") != std::string::npos) {
                                                vulkanVersion = "1.3";
                                            } else if (name.find("Adreno 6") != std::string::npos) {
                                                vulkanVersion = "1.2";
                                            } else if (name.find("Adreno 5") != std::string::npos) {
                                                vulkanVersion = "1.1";
                                            } else {
                                                vulkanVersion = "1.1";
                                            }
                                        }
                                    }
                                    
                                    if (maxFreq == 1) {
                                        maxFreq = 600;
                                    }
                                    
                                    if (globalMemSize > 0) {
                                        s_detectedGpuMemory = std::max(s_detectedGpuMemory, globalMemSize);
                                        LOGI("Stored detected GPU memory: %zu bytes (%zu MB)", s_detectedGpuMemory, s_detectedGpuMemory / 1024 / 1024);
                                    }

                                    if (maxMemAllocSize > 0) {
                                        s_detectedMaxMemAllocSize = std::max(s_detectedMaxMemAllocSize, (size_t)maxMemAllocSize);
                                        LOGI("Stored detected max mem alloc size: %zu bytes (%zu MB)", s_detectedMaxMemAllocSize, s_detectedMaxMemAllocSize / 1024 / 1024);
                                    }
                                    
                                    std::ostringstream oss;
                                    oss << "{\"name\":\"" << name << "\","
                                        << "\"vendor\":\"" << vendor << "\","
                                        << "\"openclVersion\":\"" << openclVersion << "\","
                                        << "\"vulkanVersion\":\"" << vulkanVersion << "\","
                                        << "\"globalMemoryMB\":" << (globalMemSize / 1024 / 1024) << ","
                                        << "\"maxMemory\":" << (globalMemSize / 1024 / 1024) << ","
                                        << "\"maxComputeUnits\":" << maxComputeUnits << ","
                                        << "\"maxFrequencyMHz\":" << maxFreq << ","
                                        << "\"supportsFP16\":" << (hasFP16 ? "true" : "false") << ","
                                        << "\"supportsFP32\":" << (hasFP32 ? "true" : "false") << ","
                                        << "\"supportsFP64\":" << (hasFP64 ? "true" : "false") << ","
                                        << "\"supportsBF16\":" << (hasBF16 ? "true" : "false") << ","
                                        << "\"supportedFloatingPointTypes\":\"" << fpTypes << "\","
                                        << "\"halfVectorWidth\":" << halfVectorWidth << ","
                                        << "\"floatVectorWidth\":" << floatVectorWidth << ","
                                        << "\"doubleVectorWidth\":" << doubleVectorWidth << ","
                                        << "\"isAdreno\":" << (name.find("Adreno") != std::string::npos) << "}";
                                    result = oss.str();
                                    return env->NewStringUTF(result.c_str());
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeCleanupCallback(
    JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_activeCallbackMutex);
    if (g_activeCallback != nullptr) {
        env->DeleteGlobalRef(g_activeCallback);
        g_activeCallback = nullptr;
        LOGI("nativeCleanupCallback: cleaned up global callback ref");
    }
}

// 组3.16：模型元数据 + 纯净推理 JNI 导出
// 注意：使用 s_helperContext 全局指针获取 InferenceContext 实例
// nativeGetLastError 已存在（见上方），不重复添加

extern "C" JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetMetaNCtxTrain(JNIEnv* /*env*/, jclass) {
    if (s_helperContext == nullptr) return 0;
    return s_helperContext->meta.nCtxTrain;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetMetaNEmbd(JNIEnv* /*env*/, jclass) {
    if (s_helperContext == nullptr) return 0;
    return s_helperContext->meta.nEmbd;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetMetaNLayer(JNIEnv* /*env*/, jclass) {
    if (s_helperContext == nullptr) return 0;
    return s_helperContext->meta.nLayer;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetMetaNHead(JNIEnv* /*env*/, jclass) {
    if (s_helperContext == nullptr) return 0;
    return s_helperContext->meta.nHead;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetMetaNParams(JNIEnv* /*env*/, jclass) {
    if (s_helperContext == nullptr) return 0;
    return (jlong)s_helperContext->meta.nParams;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetMetaModelName(JNIEnv* env, jclass) {
    if (s_helperContext == nullptr) return env->NewStringUTF("");
    return safeNewStringUTF(env, s_helperContext->meta.modelName);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetMetaValid(JNIEnv* /*env*/, jclass) {
    if (s_helperContext == nullptr) return JNI_FALSE;
    return s_helperContext->meta.valid ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeInstallSignalHandlers(JNIEnv* /*env*/, jclass) {
    install_fatal_signal_handlers();
    LOGI("Fatal signal handlers installed (SIGABRT/SIGSEGV/SIGBUS/SIGILL)");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeRunInferenceOnce(
    JNIEnv* env, jclass /*clazz*/,
    jstring prompt, jint contextSize, jint maxTokens, jobject callback) {
    if (s_helperContext == nullptr || !s_helperContext->isValid()) {
        LOGE("nativeRunInferenceOnce: s_helperContext not initialized");
        return -1;
    }
    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    if (promptStr == nullptr) {
        LOGE("nativeRunInferenceOnce: failed to get prompt");
        return -1;
    }
    std::string promptContent(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    int result = s_helperContext->runPureInference(
        promptContent, (int)contextSize, (int)maxTokens, callback, env);
    return (jint)result;
}

// ============================================================================
// GGUF 元数据读取（架构信息卡片 + NPU 上下文预算规划用）
// ============================================================================
//
// 背景（2026-10-07）：这些字段原先由 Java 侧**手写解析器**（GgufMeta.java）从 GGUF
// 头部读。手写解析器出过"读取位置漂移 → EOFException → read() 返回 null"的问题，
// 而 read() 的 catch 又静默吞异常，最终表现为 NPU 的 KV 估算拿不到模型规格、
// 上下文规划走 4096 兜底、Agent 每轮工具结果被裁掉。
//
// 这里改用 **ggml/llama.cpp 自带、且已导出在 libllama-jni.so 里的官方 GGUF 读取 API**
// （gguf_init_from_file 等，见 ggml/include/gguf.h），不再自己维护解析逻辑：
//   · no_alloc=true 且 ctx=nullptr —— 只读头部元数据，**不加载/不分配权重数据**；
//   · 格式兼容性与 GGUF 规范同步（跟着库一起升级）。
// 一次 JNI 调用返回全部所需字段，避免多次打开文件。
//
// 返回值（jlongArray；未找到的键为 -1，调用方据此区分"缺失"与"值为 0"）：
//   [0]  api 版本（1）        [1]  blockCount         [2]  headCount
//   [3]  headCountKv          [4]  embeddingLength    [5]  contextLength
//   [6]  attentionKeyLength   [7]  fullAttentionInterval
//   [8]  hasLinearAttention（0/1）  [9]  parameterCount   [10] tensorCount
// 返回 null 表示文件不存在 / 非 GGUF / 打开失败。
JNIEXPORT jlongArray JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeReadGgufMeta(
    JNIEnv* env, jclass /* clazz */, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    if (cpath == nullptr) {
        return nullptr;
    }
    std::string filePath(cpath);
    env->ReleaseStringUTFChars(path, cpath);

    gguf_init_params params;
    params.no_alloc = true;      // 只读元数据，不分配张量数据
    params.ctx      = nullptr;   // 不需要 ggml_context

    gguf_context* ctx = gguf_init_from_file(filePath.c_str(), params);
    if (ctx == nullptr) {
        LOGW("nativeReadGgufMeta: 无法读取 %s（非 GGUF 或文件不可读）", filePath.c_str());
        return nullptr;
    }

    // GGUF 规范：元数据键一律小写。为兼容不规范的导出器，精确查不到时再试小写。
    auto findKey = [&](const char* key) -> int64_t {
        int64_t id = gguf_find_key(ctx, key);
        if (id >= 0) {
            return id;
        }
        std::string lower(key);
        for (size_t i = 0; i < lower.size(); i++) {
            lower[i] = (char) ::tolower((unsigned char) lower[i]);
        }
        return gguf_find_key(ctx, lower.c_str());
    };

    auto readInt = [&](const char* key) -> jlong {
        int64_t id = findKey(key);
        if (id < 0) {
            return -1;
        }
        switch (gguf_get_kv_type(ctx, id)) {
            case GGUF_TYPE_UINT8:  return (jlong) gguf_get_val_u8(ctx, id);
            case GGUF_TYPE_INT8:   return (jlong) gguf_get_val_i8(ctx, id);
            case GGUF_TYPE_UINT16: return (jlong) gguf_get_val_u16(ctx, id);
            case GGUF_TYPE_INT16:  return (jlong) gguf_get_val_i16(ctx, id);
            case GGUF_TYPE_UINT32: return (jlong) gguf_get_val_u32(ctx, id);
            case GGUF_TYPE_INT32:  return (jlong) gguf_get_val_i32(ctx, id);
            case GGUF_TYPE_UINT64: return (jlong) gguf_get_val_u64(ctx, id);
            case GGUF_TYPE_INT64:  return (jlong) gguf_get_val_i64(ctx, id);
            case GGUF_TYPE_FLOAT32:return (jlong) gguf_get_val_f32(ctx, id);
            case GGUF_TYPE_FLOAT64:return (jlong) gguf_get_val_f64(ctx, id);
            default:               return -1;
        }
    };

    // 架构名：用于把 "<arch>.xxx" 补全（llama.cpp 的键名模板就是 %s.xxx）
    std::string arch;
    {
        int64_t id = findKey("general.architecture");
        if (id >= 0 && gguf_get_kv_type(ctx, id) == GGUF_TYPE_STRING) {
            const char* s = gguf_get_val_str(ctx, id);
            if (s != nullptr) {
                arch = s;
            }
        }
    }

    auto suffixed = [&](const char* suffix, jlong fallback) -> jlong {
        if (!arch.empty()) {
            std::string key = arch + suffix;
            jlong v = readInt(key.c_str());
            if (v >= 0) {
                return v;
            }
        }
        return fallback;
    };

    jlong out[11];
    for (int i = 0; i < 11; i++) {
        out[i] = -1;
    }
    out[0] = 1;
    // 张量类型直方图（HTP 兼容性提示用）：追加在 11 个固定字段之后，成对 [type, count]
    int histTypes[64];
    int histCounts[64];
    int nHist = 0;

    out[1] = suffixed(".block_count", readInt("block_count"));
    out[2] = suffixed(".attention.head_count", readInt("attention.head_count"));
    out[3] = suffixed(".attention.head_count_kv", readInt("attention.head_count_kv"));
    out[4] = suffixed(".embedding_length", -1);
    out[5] = suffixed(".context_length", -1);
    out[6] = suffixed(".attention.key_length", -1);
    out[7] = suffixed(".full_attention_interval", -1);

    // 线性注意力 / SSM 层：沿用原 Java 侧判据（键名含 .ssm. 或 .linear_）
    {
        bool hasLinear = false;
        const int64_t nkv = gguf_get_n_kv(ctx);
        for (int64_t i = 0; i < nkv; i++) {
            const char* k = gguf_get_key(ctx, i);
            if (k == nullptr) {
                continue;
            }
            std::string key(k);
            if (key.find(".ssm.") != std::string::npos
                    || key.find(".linear_") != std::string::npos) {
                hasLinear = true;
                break;
            }
        }
        out[8] = hasLinear ? 1 : 0;
    }

    // 参数量：优先元数据 general.parameter_count，缺失则用张量元素数累加。
    // 同时统计张量类型直方图（HTP 兼容性提示用；ggml type id 取值 0..47）。
    {
        int64_t paramId = findKey("general.parameter_count");
        const int64_t nt = gguf_get_n_tensors(ctx);
        out[10] = nt;
        if (paramId >= 0) {
            out[9] = readInt("general.parameter_count");
        }
        int typeHist[64];
        for (int i = 0; i < 64; i++) {
            typeHist[i] = 0;
        }
        long double total = 0;
        for (int64_t i = 0; i < nt; i++) {
            if (paramId < 0) {
                const int64_t* ne = gguf_get_tensor_ne(ctx, i);
                if (ne != nullptr) {
                    long double elems = 1;
                    for (int d = 0; d < 4; d++) {
                        if (ne[d] > 0) {
                            elems *= (long double) ne[d];
                        }
                    }
                    total += elems;
                }
            }
            int t = (int) gguf_get_tensor_type(ctx, i);
            if (t >= 0 && t < 64) {
                typeHist[t]++;
            }
        }
        if (paramId < 0) {
            out[9] = (jlong) total;
        }
        nHist = 0;
        for (int t = 0; t < 64; t++) {
            if (typeHist[t] > 0) {
                histTypes[nHist] = t;
                histCounts[nHist] = typeHist[t];
                nHist++;
            }
        }
    }

    gguf_free(ctx);

    const jsize totalLen = (jsize) (11 + nHist * 2);
    jlongArray arr = env->NewLongArray(totalLen);
    if (arr == nullptr) {
        return nullptr;
    }
    jlong* buf = new jlong[totalLen];
    for (int i = 0; i < 11; i++) {
        buf[i] = out[i];
    }
    for (int i = 0; i < nHist; i++) {
        buf[11 + i * 2]     = histTypes[i];
        buf[11 + i * 2 + 1] = histCounts[i];
    }
    env->SetLongArrayRegion(arr, 0, totalLen, buf);
    delete[] buf;

    LOGI("nativeReadGgufMeta: %s -> block=%lld head=%lld headKv=%lld emb=%lld ctx=%lld keyLen=%lld fai=%lld linear=%lld params=%lld tensors=%lld types=%d",
         filePath.c_str(),
         (long long) out[1], (long long) out[2], (long long) out[3], (long long) out[4],
         (long long) out[5], (long long) out[6], (long long) out[7], (long long) out[8],
         (long long) out[9], (long long) out[10], nHist);
    return arr;
}

}
