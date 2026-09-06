# -*- coding: utf-8 -*-
# native-lib.cpp: 分阶段速度统计（THINKING 思考速度 + GENERATING 解码速度）
# 1) 加 thinkingStartTime/thinkingTokenCount 成员
# 2) gen_loop 进入 THINKING 时重置计时；思考 token 计数
# 3) getPhaseSpeed()：按当前状态机阶段返回对应速度
# 4) JNI nativeGetPhaseSpeed
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

# 1) 成员
rep(
'''    std::chrono::steady_clock::time_point decodeStartTime;  // 纯 decode 速度：正文生成计时起点
    int decodeTokenCount;                                   // 纯 decode 速度：正文生成 token 数''',
'''    std::chrono::steady_clock::time_point decodeStartTime;  // 纯 decode 速度：正文生成计时起点
    int decodeTokenCount;                                   // 纯 decode 速度：正文生成 token 数
    std::chrono::steady_clock::time_point thinkingStartTime; // 思考段速度：计时起点
    int thinkingTokenCount;                                 // 思考段速度：思考 token 数''',
'member decl')

# 2) ctor 初始化
rep(
'''                         lastError(""), totalTokenCount(0), currentTokenCount(0),
                         decodeTokenCount(0),''',
'''                         lastError(""), totalTokenCount(0), currentTokenCount(0),
                         decodeTokenCount(0), thinkingTokenCount(0),''',
'ctor init')

# 3) gen_loop 进入 THINKING 时重置思考计时
rep_in_func(
'    bool generateStreamIncremental(const std::string& prompt',
'// 单次生成路径：接收消息列表',
'''        setPhase(enableThinking ? GenPhase::THINKING : GenPhase::GENERATING, "incr:gen_loop");''',
'''        setPhase(enableThinking ? GenPhase::THINKING : GenPhase::GENERATING, "incr:gen_loop");
        if (enableThinking) {
            thinkingStartTime = std::chrono::steady_clock::now();  // 思考段速度计时起点
            thinkingTokenCount = 0;
        }''',
'thinking start reset')

# 4) 思考 token 计数
rep_in_func(
'    bool generateStreamIncremental(const std::string& prompt',
'// 单次生成路径：接收消息列表',
'''            if (inThinking && !thinkingEnded) {
                thinkingTokens++;''',
'''            if (inThinking && !thinkingEnded) {
                thinkingTokens++;
                thinkingTokenCount++;  // 分阶段速度：思考段 token 计数''',
'thinking token count')

# 5) getPhaseSpeed 方法（getDecodeSpeed 后）
rep(
'''    // 纯 decode 速度：正文生成阶段 token / 耗时（思考段不计，think_end 后计时）
    float getDecodeSpeed() {
        if (decodeTokenCount == 0) return 0.0f;
        auto endTime = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - decodeStartTime).count();
        if (elapsed == 0) return 0.0f;
        return (decodeTokenCount * 1000.0f) / elapsed;
    }''',
'''    // 纯 decode 速度：正文生成阶段 token / 耗时（思考段不计，think_end 后计时）
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
            default:
                return 0.0f;
        }
    }''',
'getPhaseSpeed')

# 6) JNI nativeGetPhaseSpeed
rep(
'''JNIEXPORT jfloat JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetInferenceSpeed(''',
'''JNIEXPORT jfloat JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetPhaseSpeed(
    JNIEnv* env,
    jclass /* clazz */) {
    if (s_helperContext != nullptr && s_helperContext->isValid()) {
        return s_helperContext->getPhaseSpeed();
    }
    return 0.0f;
}

JNIEXPORT jfloat JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetInferenceSpeed(''',
'JNI phase speed')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("PHASE SPEED NATIVE OK")
