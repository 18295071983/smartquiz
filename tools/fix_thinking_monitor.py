# -*- coding: utf-8 -*-
# native-lib.cpp: 实时思考内容监控
# 1) InferenceContext 加 thinkingBuffer_ 成员 + clear/append/get 方法
# 2) chatJson tokenCallback 思考段实时累积思考内容
# 3) JNI nativeGetThinkingContent
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

# 1) 成员（加在 thinkingStartTime/thinkingTokenCount 后）
rep(
'''    std::chrono::steady_clock::time_point thinkingStartTime; // 思考段速度：计时起点
    int thinkingTokenCount;                                 // 思考段速度：思考 token 数''',
'''    std::chrono::steady_clock::time_point thinkingStartTime; // 思考段速度：计时起点
    int thinkingTokenCount;                                 // 思考段速度：思考 token 数
    std::string thinkingBuffer_;                            // 实时思考内容监控：当前推理思考段累积''',
'think buffer member')

# 2) public 访问方法（genRunning 后）
rep(
'''    bool genRunning() const {
        return phase == GenPhase::THINKING || phase == GenPhase::GENERATING || phase == GenPhase::PREPROCESS;
    }''',
'''    bool genRunning() const {
        return phase == GenPhase::THINKING || phase == GenPhase::GENERATING || phase == GenPhase::PREPROCESS;
    }

    // 实时思考内容监控：思考段累积/读取（供 JNI 与 UI 轮询查看模型思考过程）
    void clearThinkingContent() { thinkingBuffer_.clear(); }
    void appendThinkingContent(const std::string& s) { thinkingBuffer_ += s; }
    const std::string& getThinkingContent() const { return thinkingBuffer_; }''',
'think buffer accessors')

# 3) chatJson tokenCallback 思考段实时累积（isInThinking 分支）
rep(
'''                        } else {
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
                                pos = completePart.size();   // 思考内容不发（折叠显示由 Java 端收集）
                            } else {
                                isInThinking = false;
                                setPhase(GenPhase::GENERATING, "chatJson:think_end");
                                LOGI("chatJson: thinking END detected");
                                pos = close + closeLen;
                            }
                        }''',
'''                        } else {
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
                                // 思考内容不发流式 token（折叠显示由 Java 端收集），但实时累积供监控
                                thinkingBuffer_.append(completePart, pos, std::string::npos);
                                pos = completePart.size();
                            } else {
                                thinkingBuffer_.append(completePart, pos, close - pos);  // 实时思考内容监控
                                isInThinking = false;
                                setPhase(GenPhase::GENERATING, "chatJson:think_end");
                                LOGI("chatJson: thinking END detected");
                                pos = close + closeLen;
                            }
                        }''',
'chatJson accumulate thinking')

# 4) chatJson 生成前清空 + 完成后打印长度（在 chatJson 里找 tokenCallback 定义前的 collectedText 处）
rep(
'''        std::string collectedText;          // 完整输出（原始字节，供 parse）''',
'''        clearThinkingContent();             // 实时思考内容监控：新推理清空上一轮
        std::string collectedText;          // 完整输出（原始字节，供 parse）''',
'clear before generate')

# 5) JNI nativeGetThinkingContent
rep(
'''JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetGenPhase(JNIEnv* env, jclass /* clazz */) {''',
'''JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetThinkingContent(JNIEnv* env, jclass /* clazz */) {
    // 返回当前推理已累积的思考内容（实时监控模型思考过程，供 UI 轮询显示）
    if (s_helperContext != nullptr) {
        return utf8StringToJstring(env, s_helperContext->getThinkingContent());
    }
    return utf8StringToJstring(env, "");
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetGenPhase(JNIEnv* env, jclass /* clazz */) {''',
'JNI thinking content')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("THINKING MONITOR OK")
