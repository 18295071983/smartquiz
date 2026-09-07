# -*- coding: utf-8 -*-
# native-lib.cpp: PREPROCESS 统计增加完整 prompt 大小显示
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
'''    std::chrono::steady_clock::time_point prefillStartTime;  // PREPROCESS 阶段：prefill 计时起点
    int prefillDoneTokens;                                   // PREPROCESS 阶段：已处理 prompt token 数
    int prefillTotalTokens;                                  // PREPROCESS 阶段：本轮 eval token 总数
''',
'''    std::chrono::steady_clock::time_point prefillStartTime;  // PREPROCESS 阶段：prefill 计时起点
    int prefillDoneTokens;                                   // PREPROCESS 阶段：已处理 prompt token 数
    int prefillTotalTokens;                                  // PREPROCESS 阶段：本轮 eval token 总数
    int promptTotalTokens;                                   // 本轮完整 prompt token 数（含历史，用于统计显示）
''',
'field prompt')

# 2) 构造
rep(
'''                         prefillDoneTokens(0), prefillTotalTokens(0),
''',
'''                         prefillDoneTokens(0), prefillTotalTokens(0), promptTotalTokens(0),
''',
'ctor prompt')

# 3) prefill 初始化
rep(
'''        prefillStartTime = std::chrono::steady_clock::now();  // PREPROCESS：prefill 计时起点
        prefillDoneTokens = 0;
        prefillTotalTokens = 0;
''',
'''        prefillStartTime = std::chrono::steady_clock::now();  // PREPROCESS：prefill 计时起点
        prefillDoneTokens = 0;
        prefillTotalTokens = 0;
        promptTotalTokens = 0;
''',
'init prompt')

# 4) tokenize 后记录完整 prompt 大小（evalTokens 确定处，kvCache.setContextSize 前）
rep(
'''        int n_ctx = llama_n_ctx(ctx);
        kvCache.setContextSize(n_ctx);   // KV 监控：记录 n_ctx 供上下文占用率计算
''',
'''        int n_ctx = llama_n_ctx(ctx);
        kvCache.setContextSize(n_ctx);   // KV 监控：记录 n_ctx 供上下文占用率计算
        promptTotalTokens = (int)tokens_list.size();   // 完整 prompt 大小（含历史），供状态条统计显示
''',
'prompt size')

# 5) getPrefillProgress 加 prompt
rep(
'''    std::string getPrefillProgress() {
        char buf[128];
        int pct = prefillTotalTokens > 0
            ? (int)((prefillDoneTokens * 100L) / prefillTotalTokens) : 0;
        if (pct > 100) pct = 100;
        snprintf(buf, sizeof(buf), "{\\"done\\":%d,\\"total\\":%d,\\"pct\\":%d}",
                 prefillDoneTokens, prefillTotalTokens, pct);
        return std::string(buf);
    }
''',
'''    std::string getPrefillProgress() {
        char buf[160];
        int pct = prefillTotalTokens > 0
            ? (int)((prefillDoneTokens * 100L) / prefillTotalTokens) : 0;
        if (pct > 100) pct = 100;
        snprintf(buf, sizeof(buf), "{\\"done\\":%d,\\"total\\":%d,\\"pct\\":%d,\\"prompt\\":%d}",
                 prefillDoneTokens, prefillTotalTokens, pct, promptTotalTokens);
        return std::string(buf);
    }
''',
'progress json')

# 6) JNI 默认返回
rep(
'''    return env->NewStringUTF("{\\"done\\":0,\\"total\\":0,\\"pct\\":0}");
''',
'''    return env->NewStringUTF("{\\"done\\":0,\\"total\\":0,\\"pct\\":0,\\"prompt\\":0}");
''',
'jni default')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("PROMPT SIZE OK")
