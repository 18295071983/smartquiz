// ============================================================================
// SYSTEM PROBE JNI（2026-11-08）
//
// Java 类：com.oilquiz.app.ai.jni.SystemProbe（name-based JNI，与 LlamaHelper 一致）。
// 真正的采集在 sys_probe.cpp；活体引擎事实经 native_probe_bridge.h 向
// native-lib.cpp 取 POD 快照。本文件只做"POD ↔ long[]"的搬运。
//
// 为什么用 long[] 批量而不是 JSON/对象：
//   · 这些读数会被状态页/加载决策反复取，JSON 序列化会在 native 堆上反复分配；
//   · Java 侧解一组索引比解对象树便宜，且不会因为字段增删出现"少解析一个字段"；
//   · 数组长度与字段顺序在 SystemProbe.java 的 IDX_* 常量里一处声明。
// 字符串只有设备名这一路（nativeProbeDeviceNames），且不属于热路径。
// ============================================================================

#include <jni.h>

#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "native_probe_bridge.h"
#include "sys_probe.h"

#define PROBE_TAG "SystemProbe"
#define PROBE_LOGI(...) __android_log_print(ANDROID_LOG_INFO, PROBE_TAG, __VA_ARGS__)
#define PROBE_LOGW(...) __android_log_print(ANDROID_LOG_WARN, PROBE_TAG, __VA_ARGS__)

namespace {

jlongArray toLongArray(JNIEnv* env, const std::vector<int64_t>& values) {
    jlongArray arr = env->NewLongArray(static_cast<jsize>(values.size()));
    if (arr == nullptr) {
        return nullptr;
    }
    env->SetLongArrayRegion(arr, 0, static_cast<jsize>(values.size()),
                            reinterpret_cast<const jlong*>(values.data()));
    return arr;
}

} // namespace

extern "C" {

// [valid, MemTotal, MemAvailable, MemFree, Cached+SReclaimable, SwapTotal, SwapFree, pageSize]
JNIEXPORT jlongArray JNICALL
Java_com_oilquiz_app_ai_jni_SystemProbe_nativeProbeMemory(JNIEnv* env, jclass /* clazz */) {
    const sysprobe::MemoryInfo mi = sysprobe::readMemoryInfo();
    std::vector<int64_t> v = {
            mi.valid ? 1 : 0,
            static_cast<int64_t>(mi.memTotalBytes),
            static_cast<int64_t>(mi.memAvailableBytes),
            static_cast<int64_t>(mi.memFreeBytes),
            static_cast<int64_t>(mi.cachedBytes),
            static_cast<int64_t>(mi.swapTotalBytes),
            static_cast<int64_t>(mi.swapFreeBytes),
            static_cast<int64_t>(mi.pageSizeBytes),
    };
    return toLongArray(env, v);
}

// [valid, VmRSS, VmSize, VmSwap, Threads, fdCount, statmResident(按页大小换算)]
JNIEXPORT jlongArray JNICALL
Java_com_oilquiz_app_ai_jni_SystemProbe_nativeProbeSelf(JNIEnv* env, jclass /* clazz */) {
    const sysprobe::SelfInfo si = sysprobe::readSelfInfo();
    std::vector<int64_t> v = {
            si.valid ? 1 : 0,
            static_cast<int64_t>(si.vmRssBytes),
            static_cast<int64_t>(si.vmSizeBytes),
            static_cast<int64_t>(si.vmSwapBytes),
            static_cast<int64_t>(si.threads),
            static_cast<int64_t>(si.fdCount),
            static_cast<int64_t>(si.statmResidentBytes),
    };
    return toLongArray(env, v);
}

// [valid, some10, some60, some300, full10, full60, full300]，压力值 ×100 存整数。
// valid=0 表示内核没有 PSI —— 不是"压力 0"。
JNIEXPORT jlongArray JNICALL
Java_com_oilquiz_app_ai_jni_SystemProbe_nativeProbePressure(JNIEnv* env, jclass /* clazz */) {
    const sysprobe::PressureInfo pi = sysprobe::readPressure();
    const auto scaled = [](float f) { return static_cast<int64_t>(f * 100.0f + 0.5f); };
    std::vector<int64_t> v = {
            pi.valid ? 1 : 0,
            scaled(pi.someAvg10), scaled(pi.someAvg60), scaled(pi.someAvg300),
            scaled(pi.fullAvg10), scaled(pi.fullAvg60), scaled(pi.fullAvg300),
    };
    return toLongArray(env, v);
}

// [valid, arena, ordblks, hblkhd, uordblks, fordblks, keepcost]
JNIEXPORT jlongArray JNICALL
Java_com_oilquiz_app_ai_jni_SystemProbe_nativeProbeMalloc(JNIEnv* env, jclass /* clazz */) {
    const sysprobe::MallocInfo mi = sysprobe::readMallocInfo();
    std::vector<int64_t> v = {
            mi.valid ? 1 : 0,
            static_cast<int64_t>(mi.arena),
            static_cast<int64_t>(mi.ordblks),
            static_cast<int64_t>(mi.hblkhd),
            static_cast<int64_t>(mi.uordblks),
            static_cast<int64_t>(mi.fordblks),
            static_cast<int64_t>(mi.keepcost),
    };
    return toLongArray(env, v);
}

// [valid, hasModel, hasCtx, modelSizeBytes, modelParams, stateSizeBytes,
//  nCtx, nCtxSeq, nBatch, nUbatch,
//  kvPosMin, kvPosMax, kvTokens,
//  kvLayers, blockCount, headCountKv, headDim, kvBytesPerToken, nRsSeq]
JNIEXPORT jlongArray JNICALL
Java_com_oilquiz_app_ai_jni_SystemProbe_nativeProbeEngine(JNIEnv* env, jclass /* clazz */) {
    const sysprobe::EngineInfo ei = probebridge::engineInfo();
    std::vector<int64_t> v = {
            ei.valid ? 1 : 0,
            ei.hasModel ? 1 : 0,
            ei.hasCtx ? 1 : 0,
            static_cast<int64_t>(ei.modelSizeBytes),
            static_cast<int64_t>(ei.modelParams),
            static_cast<int64_t>(ei.stateSizeBytes),
            static_cast<int64_t>(ei.nCtx),
            static_cast<int64_t>(ei.nCtxSeq),
            static_cast<int64_t>(ei.nBatch),
            static_cast<int64_t>(ei.nUbatch),
            static_cast<int64_t>(ei.kvPosMin),
            static_cast<int64_t>(ei.kvPosMax),
            static_cast<int64_t>(ei.kvTokens),
            static_cast<int64_t>(ei.kvLayers),
            static_cast<int64_t>(ei.blockCount),
            static_cast<int64_t>(ei.headCountKv),
            static_cast<int64_t>(ei.headDim),
            static_cast<int64_t>(ei.kvBytesPerToken),
            static_cast<int64_t>(ei.nRsSeq),
    };
    return toLongArray(env, v);
}

// 扁平格式：[count, (type, memoryKnown, freeBytes, totalBytes) × count]
JNIEXPORT jlongArray JNICALL
Java_com_oilquiz_app_ai_jni_SystemProbe_nativeProbeDevices(JNIEnv* env, jclass /* clazz */) {
    const std::vector<sysprobe::DeviceInfo> devices = sysprobe::readDevices();
    std::vector<int64_t> v;
    v.reserve(1 + devices.size() * 4);
    v.push_back(static_cast<int64_t>(devices.size()));
    for (const sysprobe::DeviceInfo& d : devices) {
        v.push_back(static_cast<int64_t>(d.type));
        v.push_back(d.memoryKnown ? 1 : 0);
        v.push_back(static_cast<int64_t>(d.freeBytes));
        v.push_back(static_cast<int64_t>(d.totalBytes));
    }
    return toLongArray(env, v);
}

// 设备名（"name|description"），顺序与 nativeProbeDevices 一致。只用于日志/状态页。
JNIEXPORT jobjectArray JNICALL
Java_com_oilquiz_app_ai_jni_SystemProbe_nativeProbeDeviceNames(JNIEnv* env, jclass /* clazz */) {
    const std::vector<sysprobe::DeviceInfo> devices = sysprobe::readDevices();
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        return nullptr;
    }
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(devices.size()), stringClass, nullptr);
    if (arr == nullptr) {
        return nullptr;
    }
    for (size_t i = 0; i < devices.size(); ++i) {
        std::string label = devices[i].name;
        if (!devices[i].description.empty()) {
            label += "|";
            label += devices[i].description;
        }
        jstring js = env->NewStringUTF(label.c_str());
        if (js != nullptr) {
            env->SetObjectArrayElement(arr, static_cast<jsize>(i), js);
            env->DeleteLocalRef(js);
        }
    }
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_oilquiz_app_ai_jni_SystemProbe_nativeKvTrimTail(
        JNIEnv* /* env */, jclass /* clazz */, jint keepTokens) {
    const bool ok = probebridge::kvTrimTail(keepTokens);
    if (!ok) {
        PROBE_LOGW("kvTrimTail(%d) failed (no context or partial-sequence unsupported)", keepTokens);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_oilquiz_app_ai_jni_SystemProbe_nativeKvClear(JNIEnv* /* env */, jclass /* clazz */) {
    const bool ok = probebridge::kvClear();
    if (!ok) {
        PROBE_LOGW("kvClear failed (no context)");
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
