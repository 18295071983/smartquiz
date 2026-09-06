# -*- coding: utf-8 -*-
# native-lib.cpp: 纯 decode 速度统计（正文生成阶段 token/耗时）
# 1) InferenceContext 加 decodeStartTime/decodeTokenCount 成员
# 2) gen_loop 开始 + think_end 迁移处重置；正文 token 才计数（思考段不计）
# 3) getDecodeSpeed() 方法
# 4) JNI nativeGetDecodeSpeed
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

def rep_in_func(sig, end_marker, old, new, label):
    global src
    i = src.find(sig)
    if i < 0:
        print("[FAIL] %s: sig not found" % label); sys.exit(1)
    j = src.find(end_marker, i)
    if j < 0:
        print("[FAIL] %s: end_marker not found" % label); sys.exit(1)
    j += len(end_marker)
    body = src[i:j]
    c = body.count(old)
    if c != 1:
        print("[FAIL] %s: in-func count=%d" % (label, c)); sys.exit(1)
    src = src[:i] + body.replace(old, new, 1) + src[j:]
    print("[OK] %s" % label)

# 1) 成员声明
rep(
'''    std::chrono::steady_clock::time_point inferenceStartTime;
    int currentTokenCount;''',
'''    std::chrono::steady_clock::time_point inferenceStartTime;
    int currentTokenCount;
    std::chrono::steady_clock::time_point decodeStartTime;  // 纯 decode 速度：正文生成计时起点
    int decodeTokenCount;                                   // 纯 decode 速度：正文生成 token 数''',
'member decl')

# 2) 构造函数初始化
rep(
'''                         lastError(""), totalTokenCount(0), currentTokenCount(0),''',
'''                         lastError(""), totalTokenCount(0), currentTokenCount(0),
                         decodeTokenCount(0),''',
'ctor init')

# 3) gen_loop 开始重置（incremental 内）
rep_in_func(
'    bool generateStreamIncremental(const std::string& prompt',
'// 单次生成路径：接收消息列表',
'''        auto start = std::chrono::steady_clock::now();''',
'''        auto start = std::chrono::steady_clock::now();
        decodeStartTime = std::chrono::steady_clock::now();  // decode 速度计时起点
        decodeTokenCount = 0;''',
'gen_loop decode reset')

# 4) think_end 迁移处重置（进入正文阶段重新计时）
rep_in_func(
'    bool generateStreamIncremental(const std::string& prompt',
'// 单次生成路径：接收消息列表',
'''                    setPhase(GenPhase::GENERATING, "incr:think_end_marker");''',
'''                    setPhase(GenPhase::GENERATING, "incr:think_end_marker");
                    decodeStartTime = std::chrono::steady_clock::now();
                    decodeTokenCount = 0;''',
'think_end reset')

rep_in_func(
'    bool generateStreamIncremental(const std::string& prompt',
'// 单次生成路径：接收消息列表',
'''                    setPhase(GenPhase::GENERATING, "incr:think_limit");''',
'''                    setPhase(GenPhase::GENERATING, "incr:think_limit");
                    decodeStartTime = std::chrono::steady_clock::now();
                    decodeTokenCount = 0;''',
'think_limit reset')

rep_in_func(
'    bool generateStreamIncremental(const std::string& prompt',
'// 单次生成路径：接收消息列表',
'''        setPhase(GenPhase::GENERATING, "incr:think_fallback_end");''',
'''        setPhase(GenPhase::GENERATING, "incr:think_fallback_end");
        decodeStartTime = std::chrono::steady_clock::now();
        decodeTokenCount = 0;''',
'think_fallback reset')

# 5) 正文 token 计数（只统计正文，思考段不计）：在 incremental gen_loop 的 currentTokenCount++ 处
rep_in_func(
'    bool generateStreamIncremental(const std::string& prompt',
'// 单次生成路径：接收消息列表',
'''            generatedTokens.push_back(new_token_id);   // KV 增量记账：记录已 decode 进 KV 的输出 token
            n_remain--;
            n_decode++;
            currentTokenCount++;''',
'''            generatedTokens.push_back(new_token_id);   // KV 增量记账：记录已 decode 进 KV 的输出 token
            n_remain--;
            n_decode++;
            currentTokenCount++;
            if (!inThinking) decodeTokenCount++;  // 纯 decode 速度：仅正文生成阶段计数''',
'decode token count')

# 6) getDecodeSpeed 方法（getInferenceSpeed 后）
rep(
'''        return (currentTokenCount * 1000.0f) / elapsed;
    }
    
    int getTokenCount() {''',
'''        return (currentTokenCount * 1000.0f) / elapsed;
    }

    // 纯 decode 速度：正文生成阶段 token / 耗时（思考段不计，think_end 后计时）
    float getDecodeSpeed() {
        if (decodeTokenCount == 0) return 0.0f;
        auto endTime = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - decodeStartTime).count();
        if (elapsed == 0) return 0.0f;
        return (decodeTokenCount * 1000.0f) / elapsed;
    }
    
    int getTokenCount() {''',
'getDecodeSpeed')

# 7) JNI nativeGetDecodeSpeed
rep(
'''JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetTokenCount(''',
'''JNIEXPORT jfloat JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetDecodeSpeed(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext != nullptr && s_helperContext->isValid()) {
        return s_helperContext->getDecodeSpeed();
    }
    return 0.0f;
}

JNIEXPORT jint JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetTokenCount(''',
'JNI decode speed')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("DECODE SPEED NATIVE OK")
