# -*- coding: utf-8 -*-
# native-lib.cpp: PREPROCESS(prefill) 阶段进度/速度统计
import io, sys
path = r"D:\qzq\smartquiz\src\main\cpp\native-lib.cpp"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

def rep(old, new, label, expect=1):
    global src
    c = src.count(old)
    if c != expect:
        print("[FAIL] %s: count=%d" % (label, c)); sys.exit(1)
    src = src.replace(old, new, expect)
    print("[OK] %s" % label)

# 1) 字段
rep(
'''    std::chrono::steady_clock::time_point thinkingStartTime; // 思考段速度：计时起点
    int thinkingTokenCount;                                 // 思考段速度：思考 token 数
''',
'''    std::chrono::steady_clock::time_point thinkingStartTime; // 思考段速度：计时起点
    int thinkingTokenCount;                                 // 思考段速度：思考 token 数
    std::chrono::steady_clock::time_point prefillStartTime;  // PREPROCESS 阶段：prefill 计时起点
    int prefillDoneTokens;                                   // PREPROCESS 阶段：已处理 prompt token 数
    int prefillTotalTokens;                                  // PREPROCESS 阶段：本轮 eval token 总数
''',
'fields')

# 2) 构造初始化
rep(
'''                         lastError(""), totalTokenCount(0), currentTokenCount(0),
                         decodeTokenCount(0), thinkingTokenCount(0),
''',
'''                         lastError(""), totalTokenCount(0), currentTokenCount(0),
                         decodeTokenCount(0), thinkingTokenCount(0),
                         prefillDoneTokens(0), prefillTotalTokens(0),
''',
'ctor')

# 3) setPhase(PREPROCESS) 后初始化 prefill 计时
rep(
'''        setPhase(GenPhase::PREPROCESS, "incr:entry");
        if (isGenerating.exchange(true)) {''',
'''        setPhase(GenPhase::PREPROCESS, "incr:entry");
        prefillStartTime = std::chrono::steady_clock::now();  // PREPROCESS：prefill 计时起点
        prefillDoneTokens = 0;
        prefillTotalTokens = 0;
        if (isGenerating.exchange(true)) {''',
'prefill init')

# 4) 分块循环更新进度
rep(
'''        int ret = 0;
        for (size_t offset = 0; offset < evalTokens.size(); offset += batchSize) {
            size_t nTokens = std::min((size_t)batchSize, evalTokens.size() - offset);
            llama_batch prompt_batch = llama_batch_get_one(evalTokens.data() + offset, (int)nTokens);
            ret = llama_decode(ctx, prompt_batch);''',
'''        int ret = 0;
        prefillTotalTokens = (int)evalTokens.size();
        for (size_t offset = 0; offset < evalTokens.size(); offset += batchSize) {
            size_t nTokens = std::min((size_t)batchSize, evalTokens.size() - offset);
            prefillDoneTokens += (int)nTokens;   // PREPROCESS：逐块累积已处理 prompt token
            llama_batch prompt_batch = llama_batch_get_one(evalTokens.data() + offset, (int)nTokens);
            ret = llama_decode(ctx, prompt_batch);''',
'prefill progress')

# 5) getPhaseSpeed 加 PREPROCESS case
rep(
'''            case GenPhase::GENERATING:
                return getDecodeSpeed();
            default:
                return 0.0f;
        }
    }
''',
'''            case GenPhase::GENERATING:
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
        char buf[128];
        int pct = prefillTotalTokens > 0
            ? (int)((prefillDoneTokens * 100L) / prefillTotalTokens) : 0;
        if (pct > 100) pct = 100;
        snprintf(buf, sizeof(buf), "{\"done\":%d,\"total\":%d,\"pct\":%d}",
                 prefillDoneTokens, prefillTotalTokens, pct);
        return std::string(buf);
    }
''',
'getPhaseSpeed preprocess')

# 6) JNI nativeGetPrefillProgress
rep(
'''JNIEXPORT jfloat JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetDecodeSpeed(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext != nullptr && s_helperContext->isValid()) {
        return s_helperContext->getDecodeSpeed();
    }
    return 0.0f;
}
''',
'''JNIEXPORT jfloat JNICALL
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
    return env->NewStringUTF("{\"done\":0,\"total\":0,\"pct\":0}");
}
''',
'jni prefill')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("PREFILL STATS OK")
