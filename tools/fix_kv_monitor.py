# -*- coding: utf-8 -*-
# native-lib.cpp KV 监控补丁：
# 1) generateStreamIncremental 里 plan 前设置 ctxSize
# 2) 新增 JNI nativeGetKvCacheStats（返回 KV 状态 JSON）
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

# 1) ctxSize 设置（generateStreamIncremental 内，KV 增量判定之前）
rep_in_func(
'    bool generateStreamIncremental(const std::string& prompt',
'// 单次生成路径：接收消息列表',
'''        int n_ctx = llama_n_ctx(ctx);
        LOGI("Context size: n_ctx=%d, prompt_tokens=%zu, maxTokens=%d", n_ctx, tokens_list.size(), maxTokens);''',
'''        int n_ctx = llama_n_ctx(ctx);
        kvCache.setContextSize(n_ctx);   // KV 监控：记录 n_ctx 供上下文占用率计算
        LOGI("Context size: n_ctx=%d, prompt_tokens=%zu, maxTokens=%d", n_ctx, tokens_list.size(), maxTokens);''',
'ctxSize set')

# 2) JNI nativeGetKvCacheStats：追加到 nativeGetThinkingTags 之后
rep(
'''    nlohmann::ordered_json j;
    j["thinking_start_tag"] = start;
    j["thinking_end_tags"] = ends;
    return utf8StringToJstring(env, j.dump());
}''',
'''    nlohmann::ordered_json j;
    j["thinking_start_tag"] = start;
    j["thinking_end_tags"] = ends;
    return utf8StringToJstring(env, j.dump());
}

JNIEXPORT jstring JNICALL
Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetKvCacheStats(JNIEnv* env, jclass /* clazz */) {
    // 返回 KV 增量缓存状态与上下文占用监控：
    // {"strategy":"FULL","matched_len":0,"cached_npast":0,"ctx_size":12288,
    //  "ctx_usage_pct":0.0,"plans":3,"inc":0,"part":0,"full":3,"hit_rate_pct":0.0,
    //  "valid":false,"full_reason":"first_call_or_invalidated"}
    nlohmann::ordered_json j;
    if (s_helperContext != nullptr) {
        auto& kc = s_helperContext->kvCache;
        const char* strat = "FULL";
        if (kc.isIncremental()) strat = "INCREMENTAL";
        else if (kc.isPartial()) strat = "PARTIAL";
        j["strategy"] = strat;
        j["matched_len"] = kc.matchedLen();
        j["cached_npast"] = kc.cachedNPast();
        j["ctx_size"] = kc.contextSize();
        j["ctx_usage_pct"] = kc.ctxUsage() * 100.0;
        j["plans"] = kc.planCount();
        j["inc"] = kc.incCount();
        j["part"] = kc.partCount();
        j["full"] = kc.fullCount();
        j["hit_rate_pct"] = kc.hitRate() * 100.0;
        j["valid"] = kc.valid();
        j["full_reason"] = kc.fullEvalReason();
    } else {
        LOGW("nativeGetKvCacheStats: helper context not initialized");
        j["error"] = "not_initialized";
    }
    return utf8StringToJstring(env, j.dump());
}''',
'JNI kv cache stats')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("ALL KV MONITOR PATCHES OK")
