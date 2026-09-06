# -*- coding: utf-8 -*-
# 修复 nativeGetGenPhase：phase/phaseName 为 private 成员
# 方案：InferenceContext public 区加 genPhaseName/genStopName/genRunning，JNI 改用
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

# 1) public 区加访问方法（getKvCacheRef 后）
rep(
'''    // KV 增量缓存引用（供 JNI 监控查询；AgentKvCache 为 private 成员，须经此访问）
    AgentKvCache& getKvCacheRef() { return kvCache; }''',
'''    // KV 增量缓存引用（供 JNI 监控查询；AgentKvCache 为 private 成员，须经此访问）
    AgentKvCache& getKvCacheRef() { return kvCache; }

    // 状态机阶段/停止原因（供 JNI 查询；phase/phaseName 为 private 成员，须经此访问）
    const char* genPhaseName() const { return phaseName(phase); }
    const char* genStopName() const { return stopName(stopCause); }
    bool genRunning() const {
        return phase == GenPhase::THINKING || phase == GenPhase::GENERATING || phase == GenPhase::PREPROCESS;
    }''',
'public accessors')

# 2) JNI 改用访问方法
rep(
'''        j["phase"] = InferenceContext::phaseName(s_helperContext->phase);
        j["stop_cause"] = InferenceContext::stopName(s_helperContext->stopCause);
        j["running"] = (s_helperContext->phase == InferenceContext::GenPhase::THINKING ||
                        s_helperContext->phase == InferenceContext::GenPhase::GENERATING ||
                        s_helperContext->phase == InferenceContext::GenPhase::PREPROCESS);''',
'''        j["phase"] = s_helperContext->genPhaseName();
        j["stop_cause"] = s_helperContext->genStopName();
        j["running"] = s_helperContext->genRunning();''',
'JNI use accessors')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("GENPHASE ACCESS FIX OK")
