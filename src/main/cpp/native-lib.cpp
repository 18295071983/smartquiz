#include <jni.h>
#include <string>
#include <memory>
#include <android/log.h>
#include <chrono>
#include <thread>
#include <vector>
#include <mutex>
#include <atomic>
#include <sys/resource.h>
#include <unistd.h>
#include "llama.h"
#include "ggml-backend.h"

#ifdef GGML_USE_OPENCL
#include <dlfcn.h>
#include <CL/cl.h>
#endif

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
    JNIEnv* env;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

#ifdef GGML_USE_OPENCL
    void * ocl = nullptr;
    const char* openclPaths[] = {
        "libOpenCL.so",
        "/vendor/lib64/libOpenCL.so",
        "/vendor/lib/libOpenCL.so",
        "/system/lib64/libOpenCL.so",
        "/system/lib/libOpenCL.so"
    };
    for (const char* path : openclPaths) {
        ocl = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (ocl) {
            __android_log_print(ANDROID_LOG_INFO, "LlamaJNI", "libOpenCL.so loaded successfully from: %s", path);
            break;
        }
    }
    if (!ocl) {
        __android_log_print(ANDROID_LOG_WARN, "LlamaJNI", "libOpenCL.so not available on this device");
    }
#endif

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

// 日志宏
#define LOGD(...) native_log(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) native_log(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) native_log(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) native_log(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 使用std命名空间
using namespace std;

namespace llama_jni {

static int s_defaultGpuLayers = 1;
static int s_defaultThreadCount = 4;
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
    std::string modelPath;
    std::atomic<bool> shouldStop;
    std::atomic<bool> isGenerating;
    std::string lastError;
    int totalTokenCount;
    std::chrono::steady_clock::time_point inferenceStartTime;
    int currentTokenCount;
    std::string modelType;
    std::string chatTemplate;
    
public:
    InferenceContext() : model(nullptr), ctx(nullptr), vocab(nullptr), 
                         contextSize(0), threadCount(0), gpuLayers(0), 
                         memoryPoolSize(0), batchSize(32), shouldStop(false),
                         isGenerating(false),
                         lastError(""), totalTokenCount(0), currentTokenCount(0),
                         modelType("unknown"), chatTemplate("") {
        LOGI("InferenceContext created");
        
        llama_backend_init();
        LOGI("Llama backend initialized");
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
        fclose(fileCheck);
        LOGI("Model file exists");
        
        this->modelPath = modelPath;
        this->contextSize = contextSize;
        this->threadCount = 4;
        this->batchSize = 512;
        
        llama_model_params model_params = llama_model_default_params();

        int autoGpuLayers = -1;
        bool hasGPU = false;
        size_t gpuTotalMemory = 0;

#ifdef GGML_USE_OPENCL
        LOGI("OpenCL backend compiled in, testing OpenCL availability...");
        const char* openclPaths[] = {
            "libOpenCL.so",
            "/vendor/lib64/libOpenCL.so",
            "/vendor/lib/libOpenCL.so",
            "/system/lib64/libOpenCL.so",
            "/system/lib/libOpenCL.so"
        };
        void* ocl_handle = nullptr;
        for (const char* path : openclPaths) {
            ocl_handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
            if (ocl_handle) {
                LOGI("OpenCL: loaded from %s", path);
                break;
            }
        }
        if (ocl_handle) {
            typedef cl_int (*clGetPlatformIDs_fn)(cl_uint, cl_platform_id*, cl_uint*);
            typedef cl_int (*clGetPlatformInfo_fn)(cl_platform_id, cl_platform_info, size_t, void*, size_t*);
            typedef cl_int (*clGetDeviceIDs_fn)(cl_platform_id, cl_device_type, cl_uint, cl_device_id*, cl_uint*);
            typedef cl_int (*clGetDeviceInfo_fn)(cl_device_id, cl_device_info, size_t, void*, size_t*);

            auto clGetPlatformIDs = (clGetPlatformIDs_fn)dlsym(ocl_handle, "clGetPlatformIDs");
            auto clGetPlatformInfo = (clGetPlatformInfo_fn)dlsym(ocl_handle, "clGetPlatformInfo");
            auto clGetDeviceIDs = (clGetDeviceIDs_fn)dlsym(ocl_handle, "clGetDeviceIDs");
            auto clGetDeviceInfo = (clGetDeviceInfo_fn)dlsym(ocl_handle, "clGetDeviceInfo");

            if (clGetPlatformIDs && clGetPlatformInfo) {
                cl_uint nplat = 0;
                cl_int err = clGetPlatformIDs(0, nullptr, &nplat);
                LOGI("OpenCL: clGetPlatformIDs returned err=%d, nplatforms=%u", err, nplat);
                if (err == CL_SUCCESS && nplat > 0) {
                    cl_platform_id* platforms = new cl_platform_id[nplat];
                    err = clGetPlatformIDs(nplat, platforms, nullptr);
                    for (cl_uint i = 0; i < nplat && err == CL_SUCCESS; i++) {
                        char name[256] = {0};
                        char vendor[256] = {0};
                        char version[256] = {0};
                        clGetPlatformInfo(platforms[i], CL_PLATFORM_NAME, sizeof(name), name, nullptr);
                        clGetPlatformInfo(platforms[i], CL_PLATFORM_VENDOR, sizeof(vendor), vendor, nullptr);
                        clGetPlatformInfo(platforms[i], CL_PLATFORM_VERSION, sizeof(version), version, nullptr);
                        LOGI("OpenCL: Platform %u: name=%s, vendor=%s, version=%s", i, name, vendor, version);

                        if (clGetDeviceIDs && clGetDeviceInfo) {
                            cl_uint ndev = 0;
                            cl_int devErr = clGetDeviceIDs(platforms[i], CL_DEVICE_TYPE_GPU, 0, nullptr, &ndev);
                            if (devErr == CL_SUCCESS && ndev > 0) {
                                hasGPU = true;
                                cl_device_id* devices = new cl_device_id[ndev];
                                devErr = clGetDeviceIDs(platforms[i], CL_DEVICE_TYPE_GPU, ndev, devices, nullptr);
                                for (cl_uint j = 0; j < ndev; j++) {
                                    char devName[256] = {0};
                                    cl_ulong devMem = 0;
                                    cl_uint devUnits = 0;
                                    clGetDeviceInfo(devices[j], CL_DEVICE_NAME, sizeof(devName), devName, nullptr);
                                    clGetDeviceInfo(devices[j], CL_DEVICE_GLOBAL_MEM_SIZE, sizeof(devMem), &devMem, nullptr);
                                    clGetDeviceInfo(devices[j], CL_DEVICE_MAX_COMPUTE_UNITS, sizeof(devUnits), &devUnits, nullptr);
                                    LOGI("OpenCL: GPU Device %u: %s, Mem=%zuMB, CU=%u",
                                         j, devName, (size_t)(devMem / 1024 / 1024), devUnits);
                                    gpuTotalMemory = std::max(gpuTotalMemory, (size_t)devMem);
                                }
                                delete[] devices;
                            }
                        }
                    }
                    delete[] platforms;
                }
            } else {
                LOGW("OpenCL: dlsym failed for essential functions");
            }
        } else {
            LOGW("OpenCL: libOpenCL.so not available on this device");
        }
#endif

        size_t devCount = ggml_backend_dev_count();
        LOGI("ggml backend detected %zu devices", devCount);
        for (size_t i = 0; i < devCount; i++) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            if (dev) {
                const char* devName = ggml_backend_dev_name(dev);
                enum ggml_backend_dev_type devType = ggml_backend_dev_type(dev);
                LOGI("ggml Device %zu: name=%s, type=%d", i, devName ? devName : "null", (int)devType);
                if (devType == GGML_BACKEND_DEVICE_TYPE_GPU) {
                    hasGPU = true;
                    size_t freeMem = 0, totalMem = 0;
                    ggml_backend_dev_memory(dev, &freeMem, &totalMem);
                    LOGI("ggml GPU: %s, free=%zuMB, total=%zuMB",
                         devName ? devName : "unknown", freeMem/1024/1024, totalMem/1024/1024);
                    gpuTotalMemory = std::max(gpuTotalMemory, totalMem);
                    if (autoGpuLayers < 0) {
                        autoGpuLayers = 0;
                    }
                }
            }
        }

        if (hasGPU && this->gpuLayers <= 0) {
            if (gpuTotalMemory >= 4ULL * 1024 * 1024 * 1024) {
                this->gpuLayers = 99;
                LOGI("Auto-config: GPU with >=4GB detected, setting 99 layers (all layers)");
            } else if (gpuTotalMemory >= 2ULL * 1024 * 1024 * 1024) {
                this->gpuLayers = 32;
                LOGI("Auto-config: GPU with >=2GB detected, setting 32 layers");
            } else if (gpuTotalMemory >= 1ULL * 1024 * 1024 * 1024) {
                this->gpuLayers = 16;
                LOGI("Auto-config: GPU with >=1GB detected, setting 16 layers");
            } else {
                this->gpuLayers = 8;
                LOGI("Auto-config: GPU detected with limited memory, setting 8 layers");
            }
        }

        if (this->gpuLayers > 0) {
            model_params.n_gpu_layers = this->gpuLayers;
            LOGI("Loading model with %d GPU layers", this->gpuLayers);
        } else {
            model_params.n_gpu_layers = 0;
            LOGI("Loading model with CPU only");
        }
        LOG_MEM("before_llama_model_load");
        
        auto startTime = std::chrono::steady_clock::now();
        model = llama_model_load_from_file(modelPath.c_str(), model_params);
        auto endTime = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(endTime - startTime).count();
        
        if (model == nullptr) {
            LOGE("Failed to load model");
            return false;
        }
        
        LOGI("Model loaded successfully in %llds", elapsed);
        LOG_MEM("after_llama_model_load");
        
        vocab = llama_model_get_vocab(model);
        if (vocab == nullptr) {
            LOGE("Failed to get vocab");
            llama_model_free(model);
            model = nullptr;
            return false;
        }
        
        int vocabSize = llama_vocab_n_tokens(vocab);
        LOGI("Vocabulary loaded with %d tokens", vocabSize);
        
        detectModelType();
        getChatTemplate();
        
        LOGI("Model loaded successfully (context will be created on demand)");
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
        ctx_params.n_ubatch = 32;
        
        LOGI("Re-creating context with n_ctx=%d, n_threads=%d, n_batch=%d, n_ubatch=32", 
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
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK > 0 ? topK : 40));
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0 ? topP : 0.9f, 1));
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
        LOGI("Context size: n_ctx=%d, prompt_tokens=%zu", n_ctx, prompt_tokens.size());
        if ((int)prompt_tokens.size() > n_ctx) {
            LOGE("Prompt too long: %zu tokens > n_ctx %d", prompt_tokens.size(), n_ctx);
            setLastError("Prompt too long for context window");
            return false;
        }

        llama_batch prompt_batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());
        LOGI("Processing prompt as single batch, size=%d", prompt_batch.n_tokens);
        
        int ret = llama_decode(ctx, prompt_batch);
        if (ret != 0) {
            LOGE("llama_decode failed for prompt batch with code: %d", ret);
            return false;
        }
        LOGI("llama_decode for prompt completed successfully");
        
        LOGI("Prompt evaluated successfully");
        
        int n_decode = 0;
        output = "";
        
        LOGI("Starting generation loop, maxTokens=%d", maxTokens);
        
        int n_remain = maxTokens;
        
        while (n_remain > 0 && !shouldStop) {
            if (shouldStop) {
                LOGI("Stop requested");
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

    void release() {
        shouldStop = true;
        while (isGenerating) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        
        LOGI("Releasing resources");
        
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
        
        try {
            llama_backend_free();
            LOGI("Llama backend freed");
        } catch (...) {
            LOGE("Error freeing backend");
        }
    }
    
    bool isValid() const {
        return model != nullptr && vocab != nullptr;
    }

    bool ensureContext() {
        if (ctx != nullptr) return true;
        if (model == nullptr) return false;

        LOGI("Creating context on demand: n_ctx=%d, n_threads=%d", contextSize, threadCount);
        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = contextSize;
        if (threadCount <= 0) threadCount = 4;
        ctx_params.n_threads = threadCount;
        ctx_params.n_threads_batch = threadCount;
        ctx_params.n_batch = batchSize > 0 ? batchSize : 512;
        ctx_params.n_ubatch = 32;

        ctx = llama_init_from_model(model, ctx_params);
        if (ctx == nullptr) {
            LOGE("Failed to create context on demand");
            return false;
        }
        LOGI("Context created on demand successfully");
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
    
    bool generateStream(const std::string& prompt, int maxTokens, float temperature, float topP, int topK, TokenCallback callback) {
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
        LOGI("Prompt length: %zu, maxTokens: %d, temp=%f, topP=%f, topK=%d", prompt.size(), maxTokens, temperature, topP, topK);
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
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK > 0 ? topK : 40));
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0 ? topP : 0.9f, 1));
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }
        
        try {
        // 直接使用原始prompt（Java层已经格式化了）
        const std::string& promptToUse = prompt;
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
            llama_memory_clear(mem, true);
            LOGI("KV cache cleared before new generation");
        } else {
            LOGE("llama_get_memory returned null, attempting seq_rm reset");
        }
        
        int n_ctx = llama_n_ctx(ctx);
        LOGI("Context size: n_ctx=%d, prompt_tokens=%zu", n_ctx, tokens_list.size());
        if ((int)tokens_list.size() > n_ctx) {
            std::string error = "Prompt too long: " + std::to_string(tokens_list.size()) + " tokens > n_ctx " + std::to_string(n_ctx);
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
        
        llama_batch prompt_batch = llama_batch_get_one(tokens_list.data(), tokens_list.size());
        LOGI("Processing prompt as single batch, size=%d", prompt_batch.n_tokens);
        LOG_MEM("before_prompt_decode");
        
        int ret = llama_decode(ctx, prompt_batch);
        
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
        const int TIMEOUT_SECONDS = 120;
        std::string fullText;
        
        auto start = std::chrono::steady_clock::now();
        
        while (n_remain > 0 && !shouldStop) {
            // Check for timeout
            auto currentTime = std::chrono::steady_clock::now();
            auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(currentTime - start).count();
            if (elapsed > TIMEOUT_SECONDS) {
                LOGI("TIMEOUT: Generation exceeded %d seconds", TIMEOUT_SECONDS);
                break;
            }
            
            // 使用 llama_sampler API 采样
            llama_token new_token_id = llama_sampler_sample(smpl, ctx, -1);
            llama_sampler_accept(smpl, new_token_id);
            
            // Check for EOS (end of sequence) token
            if (llama_vocab_is_eog(vocab, new_token_id)) {
                LOGI("EOS token detected, stopping generation");
                break;
            }
            
            if (new_token_id == 151643 || new_token_id == 151644 || new_token_id == 151645 ||
                new_token_id == 128000 || new_token_id == 128001 || new_token_id == 128008 || new_token_id == 128009) {
                LOGI("Common EOS token ID detected: %d, stopping generation", new_token_id);
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
                break;
            }
            
            fullText += token;
            callback(token, false, "");
            
            llama_batch batch = llama_batch_get_one(&new_token_id, 1);
            ret = llama_decode(ctx, batch);
            
            if (ret != 0) {
                LOGE("llama_decode failed with code: %d", ret);
                break;
            }
            
            n_remain--;
            n_decode++;
            currentTokenCount++;
            

        }
        
        llama_sampler_free(smpl);
        
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
    
    void setGPULayers(int layers) { gpuLayers = layers; }
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
        
        // 从模型中获取聊天模板
        const char* chat_template = llama_model_chat_template(model, nullptr);
        if (chat_template != nullptr) {
            chatTemplate = chat_template;
            LOGI("Got chat template from model: %s", chatTemplate.substr(0, 100).c_str());
        } else {
            // 使用默认聊天模板
            if (modelType == "llama") {
                chatTemplate = "[INST] <<SYS>>\nYou are a helpful assistant\n<</SYS>>\n\n{{ user_message }} [/INST]\n";
            } else {
                chatTemplate = "User: {{ user_message }}\nAssistant: ";
            }
            LOGI("Using default chat template for %s: %s", modelType.c_str(), chatTemplate.c_str());
        }
    }
    
    // 根据模型类型格式化请求
    std::string formatPrompt(const std::string& prompt) {
        // 临时简化：直接返回原始prompt，避免任何可能的问题
        LOGI("formatPrompt: returning raw prompt (length=%zu)", prompt.size());
        return prompt;
    }
};

} // namespace llama_jni

// ============================================================
// NativeChatContext - Native层独立管理上下文、KV缓存、多轮对话
// ============================================================

class NativeChatContext {
private:
    llama_model *model;
    llama_context *ctx;
    const llama_vocab *vocab;
    int n_ctx;
    int n_threads;
    std::atomic<bool> shouldStop;
    bool ownsModel;

    std::vector<llama_token> system_tokens;
    llama_pos system_end_pos;

    std::string global_prompt;
    std::vector<llama_token> global_tokens;
    std::string normal_prompt;
    std::vector<llama_token> normal_tokens;

    struct Turn {
        std::string role;
        std::vector<llama_token> tokens;
        llama_pos start_pos;
        llama_pos end_pos;
    };
    std::vector<Turn> turns;
    llama_pos current_pos;
    int total_tokens_in_kv;

    std::vector<llama_token> tokenize(const std::string& text, bool addBos = true) {
        int n_tokens = -llama_tokenize(vocab, text.c_str(), text.size(), NULL, 0, addBos, true);
        if (n_tokens < 0) return {};
        std::vector<llama_token> tokens(n_tokens);
        if (llama_tokenize(vocab, text.c_str(), text.size(), tokens.data(), tokens.size(), addBos, true) < 0) {
            return {};
        }
        return tokens;
    }

    bool encodeTokens(const std::vector<llama_token>& tokens) {
        if (tokens.empty()) return true;
        if (shouldStop) return false;
        int n_ctx_avail = llama_n_ctx(ctx);
        if (total_tokens_in_kv + (int)tokens.size() > n_ctx_avail) {
            LOGE("Not enough context space: have %d, need %d", n_ctx_avail - total_tokens_in_kv, (int)tokens.size());
            return false;
        }
        std::vector<llama_token> tokens_copy(tokens);
        llama_batch batch = llama_batch_get_one(tokens_copy.data(), tokens_copy.size());
        int ret = llama_decode(ctx, batch);
        if (ret != 0) {
            LOGE("llama_decode failed: %d", ret);
            return false;
        }
        if (total_tokens_in_kv > 0) {
            float * logits = llama_get_logits(ctx);
            if (logits == nullptr) {
                LOGE("llama_get_logits returned null after decode - GPU backend may have failed");
                return false;
            }
        }
        total_tokens_in_kv += tokens.size();
        current_pos += tokens.size();
        return true;
    }

public:
    NativeChatContext() : model(nullptr), ctx(nullptr), vocab(nullptr),
                          n_ctx(0), n_threads(4), shouldStop(false),
                          ownsModel(false), system_end_pos(0), current_pos(0), total_tokens_in_kv(0) {}

    ~NativeChatContext() { destroy(); }

    void shiftContext() {
        int startIdx = -1;
        for (size_t i = 0; i < turns.size(); i++) {
            if (turns[i].role != "global" && turns[i].role != "system") {
                startIdx = (int)i;
                break;
            }
        }
        if (startIdx < 0 || startIdx >= (int)turns.size()) return;

        llama_memory_t mem = llama_get_memory(ctx);
        if (mem == nullptr) {
            LOGE("shiftContext: llama_get_memory returned null");
            return;
        }

        Turn& oldest = turns[startIdx];
        int n_discard = oldest.tokens.size();
        llama_pos discard_start = oldest.start_pos;
        llama_pos discard_end = oldest.end_pos;

        llama_memory_seq_rm(mem, 0, discard_start, discard_end);

        int shift_delta = -(discard_end - discard_start);
        if (discard_end < current_pos) {
            llama_memory_seq_add(mem, 0, discard_end, current_pos, shift_delta);
        }

        current_pos += shift_delta;
        total_tokens_in_kv += shift_delta;

        for (size_t i = startIdx + 1; i < turns.size(); i++) {
            turns[i].start_pos += shift_delta;
            turns[i].end_pos += shift_delta;
        }

        turns.erase(turns.begin() + startIdx);

        LOGI("Context shifted: discarded %d tokens (role=%s), new current_pos=%d, kv_tokens=%d",
             n_discard, oldest.role.c_str(), current_pos, total_tokens_in_kv);
    }

    bool initFromExisting(llama_model* existingModel, const llama_vocab* existingVocab, int ctxSize, int nThreads, const std::string& globalPrompt, const std::string& systemPrompt, const std::string& normalPrompt) {
        LOGI("=== NativeChatContext INIT (from existing model) ===");

        this->model = existingModel;
        this->vocab = existingVocab;
        this->ownsModel = false;
        this->n_ctx = ctxSize;
        this->n_threads = nThreads;
        this->global_prompt = globalPrompt;
        this->normal_prompt = normalPrompt;

        if (!model || !vocab) {
            LOGE("Existing model or vocab is null");
            return false;
        }

        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = ctxSize;
        ctx_params.n_threads = nThreads;
        ctx_params.n_threads_batch = nThreads;
        ctx_params.n_batch = 512;
        ctx_params.n_ubatch = 32;

        ctx = llama_init_from_model(model, ctx_params);
        if (!ctx) {
            LOGE("Failed to create context from existing model");
            return false;
        }

        LOGI("Context created from existing model: n_ctx=%d", ctxSize);

        if (!globalPrompt.empty()) {
            std::string formatted = "<|im_start|>global\n" + globalPrompt + "\n<|im_end|>\n";
            global_tokens = tokenize(formatted, true);
            if (global_tokens.empty()) {
                LOGE("Failed to tokenize global prompt");
                return false;
            }
            Turn globalTurn;
            globalTurn.role = "global";
            globalTurn.tokens = global_tokens;
            globalTurn.start_pos = current_pos;
            if (!encodeTokens(global_tokens)) {
                LOGE("Failed to encode global prompt");
                return false;
            }
            globalTurn.end_pos = current_pos;
            turns.push_back(globalTurn);
        }

        if (!systemPrompt.empty()) {
            std::string formatted = "<|im_start|>system\n" + systemPrompt + "\n<|im_end|>\n";
            system_tokens = tokenize(formatted, global_tokens.empty());
            if (system_tokens.empty()) {
                LOGE("Failed to tokenize system prompt");
                return false;
            }
            Turn sysTurn;
            sysTurn.role = "system";
            sysTurn.tokens = system_tokens;
            sysTurn.start_pos = current_pos;
            if (!encodeTokens(system_tokens)) {
                LOGE("Failed to encode system prompt");
                return false;
            }
            sysTurn.end_pos = current_pos;
            system_end_pos = current_pos;
            turns.push_back(sysTurn);
        }

        if (!normalPrompt.empty()) {
            std::string formatted = "<|im_start|>normal\n" + normalPrompt + "\n<|im_end|>\n";
            normal_tokens = tokenize(formatted, global_tokens.empty() && system_tokens.empty());
            if (normal_tokens.empty()) {
                LOGE("Failed to tokenize normal prompt");
                return false;
            }
            Turn normalTurn;
            normalTurn.role = "normal";
            normalTurn.tokens = normal_tokens;
            normalTurn.start_pos = current_pos;
            if (!encodeTokens(normal_tokens)) {
                LOGE("Failed to encode normal prompt");
                return false;
            }
            normalTurn.end_pos = current_pos;
            turns.push_back(normalTurn);
        }

        LOGI("NativeChatContext initialized: n_ctx=%d, global_tokens=%zu, system_tokens=%zu, normal_tokens=%zu, kv_tokens=%d",
             n_ctx, global_tokens.size(), system_tokens.size(), normal_tokens.size(), total_tokens_in_kv);
        LOG_MEM("chat_init_complete");
        return true;
    }

    using StreamCallback = std::function<void(const std::string& token, bool isDone, const std::string& error)>;

    void chatSend(const std::string& userMessage, int maxTokens, float temperature, float topP, int topK, bool enableThinking, StreamCallback callback) {
        shouldStop = false;
        LOGI("=== CHAT SEND === user_msg_len=%zu, maxTokens=%d, thinking=%d", userMessage.size(), maxTokens, enableThinking);

        if (!model || !ctx || !vocab) {
            callback("", true, "Context not initialized");
            return;
        }

        if (shouldStop) {
            callback("", true, "Generation stopped before start");
            return;
        }

        std::string formattedUser = "<|im_start|>user\n" + userMessage + "\n<|im_end|>\n<|im_start|>assistant\n";
        if (enableThinking) {
            formattedUser = "<|im_start|>user\n" + userMessage + "\n<|im_end|>\n<|im_start|>assistant\n<think>\n";
        }
        std::vector<llama_token> user_tokens = tokenize(formattedUser, false);
        if (user_tokens.empty()) {
            callback("", true, "Failed to tokenize user message");
            return;
        }

        while (total_tokens_in_kv + (int)user_tokens.size() + maxTokens > n_ctx - 4) {
            if (shouldStop) break;
            bool hasPrunable = false;
            for (auto& t : turns) {
                if (t.role != "global" && t.role != "system") { hasPrunable = true; break; }
            }
            if (!hasPrunable) break;
            shiftContext();
            if (total_tokens_in_kv + (int)user_tokens.size() + maxTokens <= n_ctx - 4) break;
            if (total_tokens_in_kv + (int)user_tokens.size() >= n_ctx - 4) {
                bool stillHasPrunable = false;
                for (auto& t : turns) {
                    if (t.role != "global" && t.role != "system") { stillHasPrunable = true; break; }
                }
                if (stillHasPrunable) shiftContext();
                else break;
            }
        }

        if (total_tokens_in_kv + (int)user_tokens.size() >= n_ctx - 4) {
            callback("", true, "Context too long, cannot fit user message");
            return;
        }

        Turn userTurn;
        userTurn.role = "user";
        userTurn.tokens = user_tokens;
        userTurn.start_pos = current_pos;
        if (!encodeTokens(user_tokens)) {
            if (shouldStop) {
                callback("", true, "Generation stopped");
            } else {
                callback("", true, "Failed to encode user message");
            }
            return;
        }
        userTurn.end_pos = current_pos;
        turns.push_back(userTurn);

        auto sparams = llama_sampler_chain_default_params();
        struct llama_sampler *smpl = llama_sampler_chain_init(sparams);
        if (temperature <= 0) {
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        } else {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK > 0 ? topK : 40));
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0 ? topP : 0.9f, 1));
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }

        std::string fullResponse;
        std::string thinkingText;
        bool inThinking = enableThinking;
        bool thinkingEnded = !enableThinking;
        int n_decode = 0;

        while (n_decode < maxTokens && !shouldStop) {
            if (total_tokens_in_kv >= n_ctx - 4) {
                LOGI("Context full, stopping generation");
                break;
            }

            llama_token new_token_id = llama_sampler_sample(smpl, ctx, -1);
            llama_sampler_accept(smpl, new_token_id);

            if (llama_vocab_is_eog(vocab, new_token_id)) break;
            if (new_token_id == 151643 || new_token_id == 151644 || new_token_id == 151645 ||
                new_token_id == 128000 || new_token_id == 128001 || new_token_id == 128008 || new_token_id == 128009) break;

            char buf[128];
            int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
            if (n > 0) {
                std::string token_str(buf, n);
                if (token_str.find("<|im_end|") != std::string::npos ||
                    token_str.find("</s>") != std::string::npos ||
                    token_str.find("<|endoftext|") != std::string::npos) break;

                if (inThinking && !thinkingEnded) {
                    thinkingText += token_str;
                    size_t endPos = thinkingText.find("\xe2\x9d\xb4");
                    if (endPos == std::string::npos) {
                        endPos = thinkingText.find("\xe2\x9d\xb5");
                    }
                    if (endPos == std::string::npos) {
                        endPos = thinkingText.find("\n\n");
                    }
                    if (endPos != std::string::npos) {
                        thinkingText = thinkingText.substr(0, endPos);
                        thinkingEnded = true;
                        callback("[THINK_END]", false, "");
                    } else {
                        callback(token_str, false, "");
                    }
                } else {
                    fullResponse += token_str;
                    bool isToolCall = false;
                    if (fullResponse.find("<|tool_call_begin|>") != std::string::npos) {
                        isToolCall = true;
                    }
                    std::string qwenToolBegin = std::string("\xe2\x96\x85") + "tool" + std::string("\xe2\x96\x81") + "call" + std::string("\xe2\x96\x81") + "begin" + std::string("\xe2\x96\x85");
                    if (!isToolCall && fullResponse.find(qwenToolBegin) != std::string::npos) {
                        isToolCall = true;
                    }
                    if (!isToolCall && fullResponse.find("tool_call_begin") != std::string::npos) {
                        isToolCall = true;
                    }
                    if (!isToolCall && fullResponse.find("tool") != std::string::npos && fullResponse.find("call") != std::string::npos && fullResponse.find("begin") != std::string::npos) {
                        isToolCall = true;
                    }
                    if (isToolCall) {
                        callback("[TOOL_CALL]", false, "");
                        break;
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

        llama_sampler_free(smpl);

        bool isToolCallResponse = (fullResponse.find("<|tool_call_begin|>") != std::string::npos ||
            fullResponse.find("tool_call_begin") != std::string::npos ||
            (fullResponse.find("tool") != std::string::npos && fullResponse.find("call") != std::string::npos && fullResponse.find("begin") != std::string::npos));

        if (!fullResponse.empty() && !isToolCallResponse) {
            Turn assistantTurn;
            assistantTurn.role = "assistant";
            assistantTurn.tokens = tokenize(fullResponse, false);
            assistantTurn.start_pos = current_pos - n_decode;
            assistantTurn.end_pos = current_pos;
            turns.push_back(assistantTurn);
        }

        LOGI("Chat send complete: %d tokens generated, kv_total=%d, turns=%zu, tool_call=%d",
             n_decode, total_tokens_in_kv, turns.size(), isToolCallResponse ? 1 : 0);
        callback(fullResponse, true, isToolCallResponse ? "[TOOL_CALL]" : "");
    }

    void stopGeneration() {
        shouldStop = true;
    }

    void clearChat() {
        if (ctx) {
            llama_memory_t mem = llama_get_memory(ctx);
            if (mem) llama_memory_clear(mem, true);
        }
        turns.clear();
        current_pos = 0;
        total_tokens_in_kv = 0;

        if (!global_tokens.empty()) {
            Turn globalTurn;
            globalTurn.role = "global";
            globalTurn.tokens = global_tokens;
            globalTurn.start_pos = current_pos;
            encodeTokens(global_tokens);
            globalTurn.end_pos = current_pos;
            turns.push_back(globalTurn);
        }

        if (!system_tokens.empty()) {
            Turn sysTurn;
            sysTurn.role = "system";
            sysTurn.tokens = system_tokens;
            sysTurn.start_pos = current_pos;
            encodeTokens(system_tokens);
            sysTurn.end_pos = current_pos;
            system_end_pos = current_pos;
            turns.push_back(sysTurn);
        }

        if (!normal_tokens.empty()) {
            Turn normalTurn;
            normalTurn.role = "normal";
            normalTurn.tokens = normal_tokens;
            normalTurn.start_pos = current_pos;
            encodeTokens(normal_tokens);
            normalTurn.end_pos = current_pos;
            turns.push_back(normalTurn);
        }

        LOGI("Chat cleared, prompts re-encoded (global=%zu, system=%zu, normal=%zu), kv_tokens=%d",
             global_tokens.size(), system_tokens.size(), normal_tokens.size(), total_tokens_in_kv);
    }

    std::string getInfo() {
        std::string info = "n_ctx: " + std::to_string(n_ctx) + "\n";
        info += "KV tokens: " + std::to_string(total_tokens_in_kv) + "/" + std::to_string(n_ctx) + "\n";
        info += "Turns: " + std::to_string(turns.size()) + "\n";
        info += "Global prompt: " + (global_prompt.empty() ? "(none)" : global_prompt.substr(0, 50) + (global_prompt.size() > 50 ? "..." : "")) + "\n";
        info += "Global tokens: " + std::to_string(global_tokens.size()) + "\n";
        info += "System tokens: " + std::to_string(system_tokens.size()) + "\n";
        info += "Normal prompt: " + (normal_prompt.empty() ? "(none)" : normal_prompt.substr(0, 50) + (normal_prompt.size() > 50 ? "..." : "")) + "\n";
        info += "Normal tokens: " + std::to_string(normal_tokens.size()) + "\n";
        info += "Available: " + std::to_string(n_ctx - total_tokens_in_kv) + " tokens\n";
        return info;
    }

    void destroy() {
        LOGI("NativeChatContext destroy");
        turns.clear();
        system_tokens.clear();
        if (ctx) { llama_free(ctx); ctx = nullptr; }
        if (ownsModel && model) { llama_model_free(model); model = nullptr; }
        vocab = nullptr;
        current_pos = 0;
        total_tokens_in_kv = 0;
    }

    bool isValid() const { return model != nullptr && vocab != nullptr; }

    std::vector<Turn>& getTurns() { return turns; }

    bool updatePrompts(const std::string& globalPrompt, const std::string& systemPrompt, const std::string& normalPrompt) {
        LOGI("updatePrompts called");

        if (ctx) {
            llama_memory_t mem = llama_get_memory(ctx);
            if (mem) llama_memory_clear(mem, true);
        }
        turns.clear();
        current_pos = 0;
        total_tokens_in_kv = 0;

        global_prompt = globalPrompt;
        normal_prompt = normalPrompt;
        global_tokens.clear();
        system_tokens.clear();
        normal_tokens.clear();

        if (!globalPrompt.empty()) {
            std::string formatted = "<|im_start|>global\n" + globalPrompt + "\n<|im_end|>\n";
            global_tokens = tokenize(formatted, true);
            if (global_tokens.empty()) {
                LOGE("Failed to tokenize global prompt in updatePrompts");
                return false;
            }
            Turn globalTurn;
            globalTurn.role = "global";
            globalTurn.tokens = global_tokens;
            globalTurn.start_pos = current_pos;
            if (!encodeTokens(global_tokens)) {
                LOGE("Failed to encode global prompt in updatePrompts");
                return false;
            }
            globalTurn.end_pos = current_pos;
            turns.push_back(globalTurn);
        }

        if (!systemPrompt.empty()) {
            std::string formatted = "<|im_start|>system\n" + systemPrompt + "\n<|im_end|>\n";
            system_tokens = tokenize(formatted, global_tokens.empty());
            if (system_tokens.empty()) {
                LOGE("Failed to tokenize system prompt in updatePrompts");
                return false;
            }
            Turn sysTurn;
            sysTurn.role = "system";
            sysTurn.tokens = system_tokens;
            sysTurn.start_pos = current_pos;
            if (!encodeTokens(system_tokens)) {
                LOGE("Failed to encode system prompt in updatePrompts");
                return false;
            }
            sysTurn.end_pos = current_pos;
            system_end_pos = current_pos;
            turns.push_back(sysTurn);
        }

        if (!normalPrompt.empty()) {
            std::string formatted = "<|im_start|>normal\n" + normalPrompt + "\n<|im_end|>\n";
            normal_tokens = tokenize(formatted, global_tokens.empty() && system_tokens.empty());
            if (normal_tokens.empty()) {
                LOGE("Failed to tokenize normal prompt in updatePrompts");
                return false;
            }
            Turn normalTurn;
            normalTurn.role = "normal";
            normalTurn.tokens = normal_tokens;
            normalTurn.start_pos = current_pos;
            if (!encodeTokens(normal_tokens)) {
                LOGE("Failed to encode normal prompt in updatePrompts");
                return false;
            }
            normalTurn.end_pos = current_pos;
            turns.push_back(normalTurn);
        }

        LOGI("Prompts updated: global_tokens=%zu, system_tokens=%zu, normal_tokens=%zu, kv_tokens=%d",
             global_tokens.size(), system_tokens.size(), normal_tokens.size(), total_tokens_in_kv);
        return true;
    }
};

static llama_jni::InferenceContext* s_helperContext = nullptr;

static NativeChatContext* g_chatContext = nullptr;

extern "C" {

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

    g_chatContext = new NativeChatContext();
    bool ok = false;
    try {
        ok = g_chatContext->initFromExisting(
            s_helperContext->getModel(),
            s_helperContext->getVocab(),
            ctxSize,
            nThreads,
            globalContent,
            sysContent,
            normalContent
        );
    } catch (const std::exception& e) {
        LOGE("nativeChatCreate: GPU exception during init: %s", e.what());
        ok = false;
    } catch (...) {
        LOGE("nativeChatCreate: Unknown exception during init (likely GPU backend failure)");
        ok = false;
    }

    if (!ok && s_helperContext->getGPULayers() > 0) {
        LOGI("nativeChatCreate: GPU failed, attempting CPU fallback (reloading model with 0 GPU layers)");
        delete g_chatContext;
        g_chatContext = nullptr;

        std::string savedModelPath = s_helperContext->getModelPath();
        int savedCtxSize = s_helperContext->getContextSize();
        int savedThreads = s_helperContext->getThreadCount();

        s_helperContext->setGPULayers(0);
        llama_jni::s_defaultGpuLayers = 0;

        if (s_helperContext->loadModel(savedModelPath, savedCtxSize, savedThreads)) {
            LOGI("nativeChatCreate: Model reloaded on CPU, retrying chat context creation");
            g_chatContext = new NativeChatContext();
            try {
                ok = g_chatContext->initFromExisting(
                    s_helperContext->getModel(),
                    s_helperContext->getVocab(),
                    ctxSize,
                    nThreads,
                    globalContent,
                    sysContent,
                    normalContent
                );
            } catch (const std::exception& e) {
                LOGE("nativeChatCreate: CPU fallback also failed: %s", e.what());
                ok = false;
            } catch (...) {
                LOGE("nativeChatCreate: CPU fallback also failed with unknown exception");
                ok = false;
            }
        } else {
            LOGE("nativeChatCreate: Failed to reload model for CPU fallback");
        }
    }

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
    LOGI("nativeChatSend called");

    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (!chatCtx || !chatCtx->isValid()) {
        jclass cbClass = env->GetObjectClass(callback);
        jmethodID onError = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
        if (onError) env->CallVoidMethod(callback, onError, env->NewStringUTF("Chat context not initialized"));
        env->DeleteLocalRef(cbClass);
        return;
    }

    const char* msgStr = env->GetStringUTFChars(message, nullptr);
    std::string msgContent(msgStr);
    env->ReleaseStringUTFChars(message, msgStr);

    jobject globalCallback = env->NewGlobalRef(callback);

    auto streamCallback = [globalCallback](const std::string& token, bool isDone, const std::string& error) {
        JavaVM* jvm = getJavaVM();
        JNIEnv* cbEnv = nullptr;
        bool didAttach = false;

        int result = jvm->GetEnv((void**)&cbEnv, JNI_VERSION_1_6);
        if (result == JNI_EDETACHED) {
            if (jvm->AttachCurrentThread(&cbEnv, nullptr) != JNI_OK) return;
            didAttach = true;
        } else if (result != JNI_OK) return;

        try {
            jclass cbClass = cbEnv->GetObjectClass(globalCallback);
            if (isDone) {
                if (!error.empty()) {
                    jmethodID onError = cbEnv->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
                    if (onError) cbEnv->CallVoidMethod(globalCallback, onError, cbEnv->NewStringUTF(error.c_str()));
                } else {
                    jmethodID onComplete = cbEnv->GetMethodID(cbClass, "onComplete", "(Ljava/lang/String;)V");
                    if (onComplete) cbEnv->CallVoidMethod(globalCallback, onComplete, cbEnv->NewStringUTF(token.c_str()));
                }
            } else {
                jmethodID onToken = cbEnv->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
                if (onToken) cbEnv->CallVoidMethod(globalCallback, onToken, cbEnv->NewStringUTF(token.c_str()));
            }
            cbEnv->DeleteLocalRef(cbClass);
        } catch (...) {}

        if (isDone) cbEnv->DeleteGlobalRef(globalCallback);
        if (didAttach) jvm->DetachCurrentThread();
    };

    chatCtx->chatSend(msgContent, maxTokens, temperature, topP, topK, enableThinking, streamCallback);
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatStop(
    JNIEnv* env, jclass, jlong handle) {
    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (chatCtx) chatCtx->stopGeneration();
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatClear(
    JNIEnv* env, jclass, jlong handle) {
    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (chatCtx) chatCtx->clearChat();
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatDestroy(
    JNIEnv* env, jclass, jlong handle) {
    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (chatCtx) {
        chatCtx->destroy();
        delete chatCtx;
        if (chatCtx == g_chatContext) g_chatContext = nullptr;
    }
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatGetInfo(
    JNIEnv* env, jclass, jlong handle) {
    auto* chatCtx = reinterpret_cast<NativeChatContext*>(handle);
    if (chatCtx) return env->NewStringUTF(chatCtx->getInfo().c_str());
    return env->NewStringUTF("No chat context");
}

JNIEXPORT jboolean JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeChatUpdatePrompts(
    JNIEnv* env, jclass, jlong handle, jstring globalPrompt, jstring systemPrompt, jstring normalPrompt) {
    LOGI("nativeChatUpdatePrompts called");

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
        LOGI("Critical memory pressure, releasing helper context");
        delete s_helperContext;
        s_helperContext = nullptr;
        freedTokens = -1;
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
    
    // Release existing context if any
    if (s_helperContext != nullptr) {
        delete s_helperContext;
        s_helperContext = nullptr;
    }
    
    s_helperContext = new llama_jni::InferenceContext();
    if (!s_helperContext->loadModel(modelPathStr, nCtx, nThreads)) {
        LOGE("LlamaHelper: Failed to load model");
        delete s_helperContext;
        s_helperContext = nullptr;
        return -1;
    }
    
    LOGI("LlamaHelper: Model initialized successfully");
    return 0;
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
    if (s_helperContext != nullptr) {
        delete s_helperContext;
        s_helperContext = nullptr;
        LOGI("LlamaHelper: Resources released successfully");
    }
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetGPULayers(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext != nullptr && s_helperContext->isValid()) {
        return s_helperContext->getGPULayers();
    }
    // 如果 s_helperContext 不存在，返回全局变量
    return llama_jni::s_defaultGpuLayers;
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
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetInferenceSpeed(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext != nullptr && s_helperContext->isValid()) {
        return s_helperContext->getInferenceSpeed();
    }
    return 0.0f;
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
    jobject callback) {
    LOGI("LlamaHelper: Stream generation called");
    LOGI("LlamaHelper: prompt=%s, maxTokens=%d, temp=%f, topP=%f, topK=%d", 
         (prompt ? "valid" : "null"), maxTokens, temperature, topP, topK);
    
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
    
    // Create a callback wrapper for JNI - 正确处理线程安全
    auto tokenCallback = [globalCallback](const std::string& token, bool isDone, const std::string& error) {
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
            jclass callbackClass = env->GetObjectClass(globalCallback);
            
            if (isDone) {
                if (!error.empty()) {
                    jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
                    if (onErrorMethod != nullptr) {
                        jstring errorStr = env->NewStringUTF(error.c_str());
                        env->CallVoidMethod(globalCallback, onErrorMethod, errorStr);
                        env->DeleteLocalRef(errorStr);
                    }
                } else {
                    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "(Ljava/lang/String;)V");
                    if (onCompleteMethod != nullptr) {
                        jstring resultStr = env->NewStringUTF(token.c_str());  // token 参数现在包含完整文本
                        env->CallVoidMethod(globalCallback, onCompleteMethod, resultStr);
                        env->DeleteLocalRef(resultStr);
                    }
                }
            } else if (!token.empty()) {
                jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
                if (onTokenMethod != nullptr) {
                    jstring tokenStr = env->NewStringUTF(token.c_str());
                    env->CallVoidMethod(globalCallback, onTokenMethod, tokenStr);
                    env->DeleteLocalRef(tokenStr);
                }
            }
            
            env->DeleteLocalRef(callbackClass);
        } catch (...) {
            LOGE("Exception in JNI callback");
        }
        
        // 如果是完成回调，释放全局引用
        if (isDone) {
            // 在这里安全地释放全局引用
            env->DeleteGlobalRef(globalCallback);
        }
        
        // 如果是我们Attach的，需要Detach
        if (didAttach) {
            jvm->DetachCurrentThread();
        }
    };
    
    // Start stream generation
    s_helperContext->generateStream(promptContent, maxTokens, temperature, topP, topK, tokenCallback);
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

}
