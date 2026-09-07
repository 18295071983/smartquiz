# -*- coding: utf-8 -*-
# native-lib.cpp: 追加 JNI nativeGetGenPhase（返回状态机阶段 + 停止原因 JSON）
import io, sys
path = r"D:\qzq\smartquiz\src\main\cpp\native-lib.cpp"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

def rep(old, new, label):
    global src
    c = src.count(old)
    if c != 1:
        print("[FAIL] %s: count=%d" % (label, c)); sys.exit(1)
    src = src.replace(old, new, 1)
    print("[OK] %s" % label)

rep(
'''    j["valid"] = kc.valid();
        j["full_reason"] = kc.fullEvalReason();
    } else {
        LOGW("nativeGetKvCacheStats: helper context not initialized");
        j["error"] = "not_initialized";
    }
    return utf8StringToJstring(env, j.dump());
}''',
'''    j["valid"] = kc.valid();
        j["full_reason"] = kc.fullEvalReason();
    } else {
        LOGW("nativeGetKvCacheStats: helper context not initialized");
        j["error"] = "not_initialized";
    }
    return utf8StringToJstring(env, j.dump());
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetGenPhase(JNIEnv* env, jclass /* clazz */) {
    // 返回 native 生成流程状态机的当前阶段（对话界面顶部状态条展示）：
    // {"phase":"THINKING","stop_cause":"EOS","running":true}
    // phase: IDLE/PREPROCESS/THINKING/GENERATING/COMPLETE/ERROR
    nlohmann::ordered_json j;
    if (s_helperContext != nullptr) {
        j["phase"] = InferenceContext::phaseName(s_helperContext->phase);
        j["stop_cause"] = InferenceContext::stopName(s_helperContext->stopCause);
        j["running"] = (s_helperContext->phase == InferenceContext::GenPhase::THINKING ||
                        s_helperContext->phase == InferenceContext::GenPhase::GENERATING ||
                        s_helperContext->phase == InferenceContext::GenPhase::PREPROCESS);
    } else {
        j["phase"] = "IDLE";
        j["stop_cause"] = "NONE";
        j["running"] = false;
    }
    return utf8StringToJstring(env, j.dump());
}''',
'JNI gen phase')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("GENPHASE JNI OK")
