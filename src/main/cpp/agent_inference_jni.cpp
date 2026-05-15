#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "agent_inference.h"

#define LOG_TAG "AgentInferenceJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace agent_inference;

static jstring agentModeToJavaString(JNIEnv* env, AgentMode mode) {
    switch (mode) {
        case AgentMode::DIRECT: return env->NewStringUTF("direct");
        case AgentMode::CHAIN_OF_THOUGHT: return env->NewStringUTF("chain_of_thought");
        case AgentMode::REACT: return env->NewStringUTF("react");
        case AgentMode::PLAN_EXECUTE: return env->NewStringUTF("plan_execute");
        default: return env->NewStringUTF("unknown");
    }
}

static AgentMode javaStringToAgentMode(JNIEnv* env, jstring modeStr) {
    if (modeStr == nullptr) return AgentMode::CHAIN_OF_THOUGHT;

    const char* str = env->GetStringUTFChars(modeStr, nullptr);
    std::string mode(str);
    env->ReleaseStringUTFChars(modeStr, str);

    if (mode == "direct") return AgentMode::DIRECT;
    if (mode == "chain_of_thought") return AgentMode::CHAIN_OF_THOUGHT;
    if (mode == "react") return AgentMode::REACT;
    if (mode == "plan_execute") return AgentMode::PLAN_EXECUTE;
    return AgentMode::CHAIN_OF_THOUGHT;
}

static void fillToolsFromJava(JNIEnv* env, jobjectArray toolNames,
                               jobjectArray toolDescs, std::vector<ToolDef>& tools) {
    if (toolNames == nullptr || toolDescs == nullptr) return;

    jsize namesCount = env->GetArrayLength(toolNames);
    jsize descsCount = env->GetArrayLength(toolDescs);
    jsize count = (namesCount < descsCount) ? namesCount : descsCount;

    for (jsize i = 0; i < count; i++) {
        jstring nameObj = (jstring)env->GetObjectArrayElement(toolNames, i);
        jstring descObj = (jstring)env->GetObjectArrayElement(toolDescs, i);

        ToolDef tool;
        if (nameObj) {
            const char* nameStr = env->GetStringUTFChars(nameObj, nullptr);
            tool.name = std::string(nameStr);
            env->ReleaseStringUTFChars(nameObj, nameStr);
        }
        if (descObj) {
            const char* descStr = env->GetStringUTFChars(descObj, nullptr);
            tool.description = std::string(descStr);
            env->ReleaseStringUTFChars(descObj, descStr);
        }

        if (!tool.name.empty()) {
            tools.push_back(tool);
        }

        env->DeleteLocalRef(nameObj);
        env->DeleteLocalRef(descObj);
    }
}

// ========== JNI IMPLEMENTATIONS ==========

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_AgentInferenceJNI_nativeGetVersion(
    JNIEnv* env, jclass /*clazz*/) {
    return env->NewStringUTF("2.1.0-agent");
}

JNIEXPORT jlong JNICALL
Java_com_oilquiz_app_ai_jni_AgentInferenceJNI_nativeInit(
    JNIEnv* env, jobject /*thiz*/,
    jstring modelPath, jint contextSize, jint nThreads) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        LOGE("Failed to get model path");
        return 0;
    }

    std::string modelPathStr(path);
    env->ReleaseStringUTFChars(modelPath, path);

    auto* context = new AgentInferenceContext();
    if (!context->initialize(modelPathStr, contextSize, nThreads)) {
        LOGE("AgentInferenceContext init failed");
        delete context;
        return 0;
    }

    LOGI("AgentInferenceContext initialized: handle=%p, model=%s",
         context, modelPathStr.c_str());
    return reinterpret_cast<jlong>(context);
}

JNIEXPORT jlong JNICALL
Java_com_oilquiz_app_ai_jni_AgentInferenceJNI_nativeInitWithGpuLayers(
    JNIEnv* env, jobject /*thiz*/,
    jstring modelPath, jint contextSize, jint nThreads, jint gpuLayers) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        LOGE("Failed to get model path");
        return 0;
    }

    std::string modelPathStr(path);
    env->ReleaseStringUTFChars(modelPath, path);

    auto* context = new AgentInferenceContext();
    context->setGpuLayers(gpuLayers);
    if (!context->initialize(modelPathStr, contextSize, nThreads)) {
        LOGE("AgentInferenceContext init failed (gpuLayers=%d)", gpuLayers);
        delete context;
        return 0;
    }

    LOGI("AgentInferenceContext initialized: handle=%p, model=%s, gpuLayers=%d",
         context, modelPathStr.c_str(), gpuLayers);
    return reinterpret_cast<jlong>(context);
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_AgentInferenceJNI_nativeSetGpuLayers(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint gpuLayers) {
    auto* context = reinterpret_cast<AgentInferenceContext*>(handle);
    if (context) {
        context->setGpuLayers(gpuLayers);
        LOGI("Agent GPU layers set to: %d", gpuLayers);
    }
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_AgentInferenceJNI_nativeGetGpuLayers(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* context = reinterpret_cast<AgentInferenceContext*>(handle);
    if (context) {
        return context->getGpuLayers();
    }
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_AgentInferenceJNI_nativeGenerate(
    JNIEnv* env, jobject /*thiz*/,
    jlong handle, jstring prompt, jint maxTokens,
    jfloat temperature, jfloat topP, jint topK,
    jstring mode, jobjectArray toolNames, jobjectArray toolDescs) {

    auto* context = reinterpret_cast<AgentInferenceContext*>(handle);
    if (context == nullptr || !context->isInitialized()) {
        LOGE("Invalid context handle");
        return env->NewStringUTF("Error: Invalid context");
    }

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    if (promptStr == nullptr) {
        return env->NewStringUTF("Error: Failed to read prompt");
    }
    std::string promptContent(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    InferenceParams params;
    params.mode = javaStringToAgentMode(env, mode);
    params.maxTokens = maxTokens;
    params.temperature = temperature;
    params.topP = topP;
    params.topK = topK;

    fillToolsFromJava(env, toolNames, toolDescs, params.tools);

    ToolExecutor nullExecutor = nullptr;
    std::string result = context->generateAgentResponse(promptContent, params, nullExecutor);

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_AgentInferenceJNI_nativeGenerateAgent(
    JNIEnv* env, jobject /*thiz*/,
    jlong handle, jstring userInput, jint maxTokens,
    jfloat temperature, jfloat topP, jint topK,
    jstring mode, jint maxLoops,
    jobjectArray toolNames, jobjectArray toolDescs) {

    auto* context = reinterpret_cast<AgentInferenceContext*>(handle);
    if (context == nullptr || !context->isInitialized()) {
        LOGE("Invalid context handle");
        return env->NewStringUTF("Error: Invalid context");
    }

    const char* inputStr = env->GetStringUTFChars(userInput, nullptr);
    if (inputStr == nullptr) {
        return env->NewStringUTF("Error: Failed to read input");
    }
    std::string inputContent(inputStr);
    env->ReleaseStringUTFChars(userInput, inputStr);

    InferenceParams params;
    params.mode = javaStringToAgentMode(env, mode);
    params.maxTokens = maxTokens;
    params.temperature = temperature;
    params.topP = topP;
    params.topK = topK;
    params.maxLoopIterations = maxLoops;

    fillToolsFromJava(env, toolNames, toolDescs, params.tools);

    LOGI("Agent generation: mode=%d, input=%zu chars, tools=%zu",
         (int)params.mode, inputContent.size(), params.tools.size());

    ToolExecutor nullExecutor = nullptr;
    std::string result = context->generateAgentResponse(inputContent, params, nullExecutor);

    LOGI("Agent generation complete: %zu chars, %d tokens used",
         result.size(), context->getTotalTokensUsed());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_AgentInferenceJNI_nativeCancel(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* context = reinterpret_cast<AgentInferenceContext*>(handle);
    if (context) {
        context->cancel();
        LOGI("Agent inference cancelled");
    }
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_AgentInferenceJNI_nativeReset(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* context = reinterpret_cast<AgentInferenceContext*>(handle);
    if (context) {
        context->reset();
        LOGI("Agent inference context reset");
    }
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_jni_AgentInferenceJNI_nativeRelease(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* context = reinterpret_cast<AgentInferenceContext*>(handle);
    if (context) {
        delete context;
        LOGI("Agent inference context released");
    }
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_AgentInferenceJNI_nativeGetTokensUsed(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* context = reinterpret_cast<AgentInferenceContext*>(handle);
    if (context) {
        return context->getTotalTokensUsed();
    }
    return 0;
}

} // extern "C"
