#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <memory>
#include <mutex>

#define LOG_TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include "llama.h"

namespace llama_bridge {

static struct llama_model* model = nullptr;
static struct llama_context* ctx = nullptr;
static struct llama_vocab const* vocab = nullptr;

static int gpuLayers = 0;
static int threadCount = 4;
static int contextSize = 2048;
static int batchSize = 512;
static bool isInitialized = false;
static std::mutex mutex;

static int s_defaultGpuLayers = 0;
static int s_defaultThreadCount = 4;
static int s_defaultContextSize = 2048;
static int s_defaultBatchSize = 512;

bool initializeBackend() {
    static bool initialized = false;
    static std::mutex initMutex;

    std::lock_guard<std::mutex> lock(initMutex);
    if (!initialized) {
        llama_backend_init();
        initialized = true;
        LOGI("Llama backend initialized");
    }
    return true;
}

bool loadModel(const std::string& modelPath, int nGpuLayers = 0) {
    std::lock_guard<std::mutex> lock(mutex);

    if (isInitialized) {
        LOGI("Model already loaded, releasing first");
        release();
    }

    LOGI("=== LOAD MODEL START ===");
    LOGI("Model path: %s", modelPath.c_str());
    LOGI("GPU layers: %d", nGpuLayers > 0 ? nGpuLayers : s_defaultGpuLayers);

    int effectiveGpuLayers = nGpuLayers > 0 ? nGpuLayers : s_defaultGpuLayers;

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = effectiveGpuLayers;

    if (effectiveGpuLayers > 0) {
        LOGI("Loading model with GPU acceleration (layers: %d)", effectiveGpuLayers);
    } else {
        LOGI("Loading model with CPU only");
    }

    model = llama_model_load_from_file(modelPath.c_str(), model_params);
    if (!model) {
        LOGE("Failed to load model from: %s", modelPath.c_str());
        return false;
    }

    LOGI("Model loaded successfully");

    vocab = llama_model_get_vocab(model);
    if (!vocab) {
        LOGE("Failed to get vocab");
        llama_model_free(model);
        model = nullptr;
        return false;
    }

    int vocabSize = llama_vocab_n_tokens(vocab);
    LOGI("Vocabulary loaded with %d tokens", vocabSize);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = s_defaultContextSize > 0 ? s_defaultContextSize : contextSize;
    ctx_params.n_threads = s_defaultThreadCount > 0 ? s_defaultThreadCount : threadCount;
    ctx_params.n_threads_batch = s_defaultThreadCount > 0 ? s_defaultThreadCount : threadCount;

    int effectiveBatchSize = s_defaultBatchSize > 0 ? s_defaultBatchSize : batchSize;
    const int MAX_CONTEXT_BATCH = 8;
    if (effectiveBatchSize > MAX_CONTEXT_BATCH) {
        effectiveBatchSize = MAX_CONTEXT_BATCH;
    }
    ctx_params.n_batch = effectiveBatchSize;
    ctx_params.n_ubatch = effectiveBatchSize;

    LOGI("Creating context: n_ctx=%d, n_threads=%d, n_batch=%d",
         ctx_params.n_ctx, ctx_params.n_threads, ctx_params.n_batch);

    ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        model = nullptr;
        vocab = nullptr;
        return false;
    }

    isInitialized = true;
    gpuLayers = effectiveGpuLayers;
    LOGI("=== LOAD MODEL COMPLETE ===");
    return true;
}

bool generate(const std::string& prompt, int maxTokens, float temperature, float topP, int topK, std::string& output) {
    std::lock_guard<std::mutex> lock(mutex);

    if (!isInitialized || !model || !ctx || !vocab) {
        LOGE("Model not initialized");
        return false;
    }

    LOGI("=== GENERATE START ===");
    LOGI("Prompt length: %zu, maxTokens: %d", prompt.size(), maxTokens);

    int n_prompt = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    if (n_prompt < 0) {
        LOGE("Tokenization failed");
        return false;
    }

    std::vector<llama_token> prompt_tokens(n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), prompt_tokens.data(), prompt_tokens.size(), true, true) < 0) {
        LOGE("Tokenization failed");
        return false;
    }

    LOGI("Tokenized prompt to %zu tokens", prompt_tokens.size());

    llama_batch prompt_batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());
    if (llama_decode(ctx, prompt_batch)) {
        LOGE("llama_decode failed for prompt");
        llama_batch_free(prompt_batch);
        return false;
    }
    llama_batch_free(prompt_batch);

    LOGI("Prompt evaluated successfully");

    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = false;
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    int n_decode = 0;
    llama_token new_token_id;
    output = "";

    llama_batch batch = llama_batch_get_one(&new_token_id, 1);

    for (int n_pos = (int)prompt_tokens.size(); n_pos < (int)prompt_tokens.size() + maxTokens;) {
        if (llama_decode(ctx, batch)) {
            LOGE("llama_decode failed");
            llama_batch_free(batch);
            llama_sampler_free(smpl);
            return false;
        }

        n_pos += batch.n_tokens;

        new_token_id = llama_sampler_sample(smpl, ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token_id)) {
            LOGI("EOS token encountered");
            break;
        }

        char buf[128];
        int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n >= 0) {
            std::string s(buf, n);
            output += s;
        }

        llama_batch_free(batch);
        batch = llama_batch_get_one(&new_token_id, 1);
        n_decode++;
    }

    llama_batch_free(batch);
    llama_sampler_free(smpl);

    LOGI("Generation completed: %d tokens, output length: %zu", n_decode, output.length());
    return !output.empty();
}

void release() {
    std::lock_guard<std::mutex> lock(mutex);

    LOGI("Releasing resources");

    if (ctx) {
        llama_free(ctx);
        ctx = nullptr;
    }

    if (model) {
        llama_model_free(model);
        model = nullptr;
    }

    vocab = nullptr;
    isInitialized = false;

    llama_backend_free();
    LOGI("Resources released");
}

bool isValid() const {
    return isInitialized && model != nullptr && ctx != nullptr && vocab != nullptr;
}

int getGpuLayers() { return gpuLayers; }
int getThreadCount() { return threadCount; }
int getContextSize() { return contextSize; }
int getBatchSize() { return batchSize; }

void setGpuLayers(int layers) {
    s_defaultGpuLayers = layers;
    LOGI("Default GPU layers set to: %d", layers);
}

void setThreadCount(int threads) {
    s_defaultThreadCount = threads;
    LOGI("Default thread count set to: %d", threads);
}

void setContextSize(int size) {
    s_defaultContextSize = size;
    LOGI("Default context size set to: %d", size);
}

void setBatchSize(int size) {
    s_defaultBatchSize = size;
    LOGI("Default batch size set to: %d", size);
}

} // namespace llama_bridge

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_oilquiz_app_ai_engine_LlamaBridge_initModel(
    JNIEnv *env,
    jobject thiz,
    jstring modelPath,
    jint nGpuLayers) {

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) {
        LOGE("Failed to get model path");
        return JNI_FALSE;
    }

    std::string modelPathStr(path);
    env->ReleaseStringUTFChars(modelPath, path);

    bool success = llama_bridge::loadModel(modelPathStr, nGpuLayers);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_oilquiz_app_ai_engine_LlamaBridge_initModelWithConfig(
    JNIEnv *env,
    jobject thiz,
    jstring modelPath,
    jint nGpuLayers,
    jint nThreads,
    jint nCtx,
    jint nBatch) {

    llama_bridge::setGpuLayers(nGpuLayers);
    llama_bridge::setThreadCount(nThreads);
    llama_bridge::setContextSize(nCtx);
    llama_bridge::setBatchSize(nBatch);

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) {
        LOGE("Failed to get model path");
        return JNI_FALSE;
    }

    std::string modelPathStr(path);
    env->ReleaseStringUTFChars(modelPath, path);

    bool success = llama_bridge::loadModel(modelPathStr, nGpuLayers);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_engine_LlamaBridge_generate(
    JNIEnv *env,
    jobject thiz,
    jstring prompt,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jint topK) {

    if (!llama_bridge::isValid()) {
        LOGE("Model not initialized");
        return env->NewStringUTF("Model not initialized");
    }

    const char *prompt_str = env->GetStringUTFChars(prompt, nullptr);
    if (!prompt_str) {
        LOGE("Failed to get prompt");
        return env->NewStringUTF("Failed to get prompt");
    }

    std::string promptContent(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    std::string output;
    if (!llama_bridge::generate(promptContent, maxTokens, temperature, topP, topK, output)) {
        return env->NewStringUTF("Generation failed");
    }

    return env->NewStringUTF(output.c_str());
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_engine_LlamaBridge_close(JNIEnv *env, jobject thiz) {
    LOGI("Closing LlamaBridge");
    llama_bridge::release();
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_engine_LlamaBridge_getGpuLayers(JNIEnv *env, jobject thiz) {
    return llama_bridge::getGpuLayers();
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_engine_LlamaBridge_getThreadCount(JNIEnv *env, jobject thiz) {
    return llama_bridge::getThreadCount();
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_engine_LlamaBridge_getContextSize(JNIEnv *env, jobject thiz) {
    return llama_bridge::getContextSize();
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_engine_LlamaBridge_getBatchSize(JNIEnv *env, jobject thiz) {
    return llama_bridge::getBatchSize();
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_engine_LlamaBridge_setGpuLayers(JNIEnv *env, jobject thiz, jint layers) {
    llama_bridge::setGpuLayers(layers);
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_engine_LlamaBridge_setThreadCount(JNIEnv *env, jobject thiz, jint threads) {
    llama_bridge::setThreadCount(threads);
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_engine_LlamaBridge_setContextSize(JNIEnv *env, jobject thiz, jint size) {
    llama_bridge::setContextSize(size);
}

JNIEXPORT void JNICALL
Java_com_oilquiz_app_ai_engine_LlamaBridge_setBatchSize(JNIEnv *env, jobject thiz, jint size) {
    llama_bridge::setBatchSize(size);
}

JNIEXPORT jboolean JNICALL
Java_com_oilquiz_app_ai_engine_LlamaBridge_isInitialized(JNIEnv *env, jobject thiz) {
    return llama_bridge::isValid() ? JNI_TRUE : JNI_FALSE;
}

}