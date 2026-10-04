// ============================================================================
// ggml-probe：探测"把 GenieX 的 Hexagon(NPU) ggml 后端挂到我们自己的 llama.cpp 上"是否可行。
//
// 背景（2026-10-03）：App 里有两份 llama.cpp —— 我们自己的 libllama-jni.so（CPU/OpenCL/Vulkan，
// 对话/Agent/题库导入都在用）和 GenieX AAR 自带的 libllama.so + libggml-hexagon.so（跑 HTP）。
// 静态检查发现：libggml-hexagon.so 需要的 32 个 ggml C API 符号，libllama-jni.so 全部导出。
// 本探测器做运行期验证：
//   1) dlopen 我们自己的 libllama-jni.so（RTLD_GLOBAL，让插件的未定义符号能绑到它上面）；
//   2) 调 ggml_backend_load(<nativeLibraryDir>/libggml-hexagon.so)；
//   3) 列 ggml_backend_dev_count()/dev_name/dev_description/dev_type。
//
// 不动 libllama-jni 目标（重链 195MB 太慢），只 dlopen 它的符号来调用。
// ============================================================================
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include <cstdio>

#define LOG_TAG "GgmlProbe"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ggml 的枚举（来自 ggml-backend.h；为避免和 llama.cpp 头文件耦合，这里按 ABI 数值写死）
enum ggml_backend_dev_type {
    GGML_BACKEND_DEVICE_TYPE_CPU = 0,
    GGML_BACKEND_DEVICE_TYPE_GPU = 1,
    GGML_BACKEND_DEVICE_TYPE_ACCEL = 2,
    GGML_BACKEND_DEVICE_TYPE_META = 3,
};

const char * devTypeName(int t) {
    switch (t) {
        case GGML_BACKEND_DEVICE_TYPE_CPU:   return "CPU";
        case GGML_BACKEND_DEVICE_TYPE_GPU:   return "GPU";
        case GGML_BACKEND_DEVICE_TYPE_ACCEL: return "ACCEL";
        case GGML_BACKEND_DEVICE_TYPE_META:  return "META";
        default:                              return "?";
    }
}

typedef int            (*fn_load_t)(const char *);            // ggml_backend_load(path)
typedef int            (*fn_load_all_t)(void);                 // ggml_backend_load_all()
typedef size_t         (*fn_dev_count_t)(void);                // ggml_backend_dev_count()
typedef void *         (*fn_dev_get_t)(size_t);                // ggml_backend_dev_get(i)
typedef const char *   (*fn_dev_name_t)(void *);               // ggml_backend_dev_name(dev)
typedef const char *   (*fn_dev_desc_t)(void *);               // ggml_backend_dev_description(dev)
typedef int            (*fn_dev_type_t)(void *);               // ggml_backend_dev_type(dev)
typedef const char *   (*fn_reg_name_t)(void *);               // ggml_backend_reg_name(reg)
typedef void *         (*fn_dev_reg_t)(void *);                // ggml_backend_dev_backend_reg(dev)

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_GgmlProbe_nativeProbe(JNIEnv * env, jclass, jstring jNativeLibDir) {
    const char * libDir = env->GetStringUTFChars(jNativeLibDir, nullptr);
    std::string base = libDir ? libDir : "";
    env->ReleaseStringUTFChars(jNativeLibDir, libDir);

    std::string ourLib     = base + "/libllama-jni.so";
    std::string hexagonLib = base + "/libggml-hexagon.so";
    std::string out;
    char buf[512];

    // 1) 先把自己那份 llama.cpp 拉进来（RTLD_GLOBAL：插件的未定义 ggml_* 符号要绑到它上面）
    void * our = dlopen(ourLib.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!our) {
        snprintf(buf, sizeof(buf), "dlopen(%s) 失败: %s\n", ourLib.c_str(), dlerror());
        out += buf;
        return env->NewStringUTF(out.c_str());
    }
    snprintf(buf, sizeof(buf), "✓ 已加载 %s\n", ourLib.c_str());
    out += buf;

    auto p_load       = (fn_load_t)       dlsym(our, "ggml_backend_load");
    auto p_load_all   = (fn_load_all_t)   dlsym(our, "ggml_backend_load_all");
    auto p_dev_count  = (fn_dev_count_t)  dlsym(our, "ggml_backend_dev_count");
    auto p_dev_get    = (fn_dev_get_t)    dlsym(our, "ggml_backend_dev_get");
    auto p_dev_name   = (fn_dev_name_t)   dlsym(our, "ggml_backend_dev_name");
    auto p_dev_desc   = (fn_dev_desc_t)   dlsym(our, "ggml_backend_dev_description");
    auto p_dev_type   = (fn_dev_type_t)   dlsym(our, "ggml_backend_dev_type");
    auto p_reg_name   = (fn_reg_name_t)   dlsym(our, "ggml_backend_reg_name");
    auto p_dev_reg    = (fn_dev_reg_t)    dlsym(our, "ggml_backend_dev_backend_reg");

    snprintf(buf, sizeof(buf),
             "符号: load=%s load_all=%s dev_count=%s dev_get=%s dev_name=%s dev_desc=%s dev_type=%s reg_name=%s dev_reg=%s\n",
             p_load ? "有" : "无", p_load_all ? "有" : "无", p_dev_count ? "有" : "无",
             p_dev_get ? "有" : "无", p_dev_name ? "有" : "无", p_dev_desc ? "有" : "无",
             p_dev_type ? "有" : "无", p_reg_name ? "有" : "无", p_dev_reg ? "有" : "无");
    out += buf;

    // 2) 设 DSP 搜索路径（skel 发现）：插件里写的是
    //    file:///libggml-htp-v%u.so?htp_iface_skel_handle_invoke&_modver=1.0&_dom=adsp
    //    → 走 FastRPC 的 adsp 域，需要 ADSP_LIBRARY_PATH 指向 skel 所在目录（nativeLibraryDir）。
    //    这正是 GenieX 运行时那句 "Auto-resolved HTP runtime path" 在做的事。
    setenv("ADSP_LIBRARY_PATH", base.c_str(), 1);
    setenv("DSP_LIBRARY_PATH", base.c_str(), 1);
    snprintf(buf, sizeof(buf), "ADSP_LIBRARY_PATH=%s\n", base.c_str());
    out += buf;

    // 按后端枚举设备（新 API：ggml_backend_reg_count/reg_get + reg_dev_count/reg_dev_get）
    typedef size_t   (*fn_reg_count_t)(void);
    typedef void *   (*fn_reg_get_t)(size_t);
    typedef size_t   (*fn_reg_dev_count_t)(void *);
    typedef void *   (*fn_reg_dev_get_t)(void *, size_t);
    auto p_reg_count  = (fn_reg_count_t)  dlsym(our, "ggml_backend_reg_count");
    auto p_reg_get    = (fn_reg_get_t)    dlsym(our, "ggml_backend_reg_get");
    auto p_rdev_count = (fn_reg_dev_count_t) dlsym(our, "ggml_backend_reg_dev_count");
    auto p_rdev_get   = (fn_reg_dev_get_t)   dlsym(our, "ggml_backend_reg_dev_get");
    snprintf(buf, sizeof(buf), "按后端枚举 API: reg_count=%s reg_get=%s reg_dev_count=%s reg_dev_get=%s\n",
             p_reg_count ? "有" : "无", p_reg_get ? "有" : "无",
             p_rdev_count ? "有" : "无", p_rdev_get ? "有" : "无");
    out += buf;

    auto dumpRegs = [&](const char * tag) {
        if (!p_reg_count || !p_reg_get) return;
        size_t rc = p_reg_count();
        snprintf(buf, sizeof(buf), "[%s] 后端数: %zu\n", tag, rc);
        out += buf;
        for (size_t i = 0; i < rc; i++) {
            void * reg = p_reg_get(i);
            const char * rn = (p_reg_name && reg) ? p_reg_name(reg) : "?";
            size_t dc = (p_rdev_count && reg) ? p_rdev_count(reg) : 0;
            snprintf(buf, sizeof(buf), "  后端 %s: 设备数 %zu\n", rn ? rn : "?", dc);
            out += buf;
            for (size_t j = 0; j < dc; j++) {
                void * dev = p_rdev_get ? p_rdev_get(reg, j) : nullptr;
                const char * dn = (p_dev_name && dev) ? p_dev_name(dev) : "?";
                const char * dd = (p_dev_desc && dev) ? p_dev_desc(dev) : "";
                int dt = (p_dev_type && dev) ? p_dev_type(dev) : -1;
                snprintf(buf, sizeof(buf), "    - %s | %s | type=%s(%d)\n",
                         dn ? dn : "?", dd ? dd : "", devTypeName(dt), dt);
                out += buf;
            }
        }
    };

    // 先看加载前的后端
    dumpRegs("加载前");

    // 3) 加载 Hexagon 插件（显式路径 + load_all 两种都试）
    if (p_load) {
        int rc = p_load(hexagonLib.c_str());
        snprintf(buf, sizeof(buf), "ggml_backend_load(%s) → %d\n", hexagonLib.c_str(), rc);
        out += buf;
    }
    if (p_load_all) {
        int rc = p_load_all();
        snprintf(buf, sizeof(buf), "ggml_backend_load_all() → %d\n", rc);
        out += buf;
    }
    dumpRegs("加载后");

    // 4) 全局设备表
    if (p_dev_count && p_dev_get && p_dev_name && p_dev_type) {
        size_t n = p_dev_count();
        snprintf(buf, sizeof(buf), "全局设备数: %zu\n", n);
        out += buf;
        for (size_t i = 0; i < n; i++) {
            void * dev = p_dev_get(i);
            const char * nm = p_dev_name(dev);
            const char * ds = p_dev_desc ? p_dev_desc(dev) : "";
            int ty = p_dev_type ? p_dev_type(dev) : -1;
            const char * rn = "-";
            if (p_dev_reg && p_reg_name) {
                void * reg = p_dev_reg(dev);
                if (reg) rn = p_reg_name(reg);
            }
            snprintf(buf, sizeof(buf), "  [%zu] %s | %s | type=%s(%d) | backend=%s\n",
                     i, nm ? nm : "?", ds ? ds : "", devTypeName(ty), ty, rn);
            out += buf;
        }
    } else {
        out += "缺少全局设备 API\n";
    }

    LOGI("probe 结果:\n%s", out.c_str());
    return env->NewStringUTF(out.c_str());
}
