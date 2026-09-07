# -*- coding: utf-8 -*-
# 修复：JNI nativeGetKvCacheStats 访问 kvCache（private）编译错误
# 方案：InferenceContext public 区加 getKvCacheRef()，JNI 改用该方法
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

# 1) InferenceContext public 区加 getKvCacheRef（在 refreshThinkingTags 前找 public 区方法）
rep(
'''public:
    // 函数前向声明
    std::string applyChatTemplateForMessages(const std::vector<std::pair<std::string, std::string>>& messages, bool addAssistantStart);''',
'''public:
    // KV 增量缓存引用（供 JNI 监控查询；AgentKvCache 为 private 成员，须经此访问）
    AgentKvCache& getKvCacheRef() { return kvCache; }

    // 函数前向声明
    std::string applyChatTemplateForMessages(const std::vector<std::pair<std::string, std::string>>& messages, bool addAssistantStart);''',
'getKvCacheRef public method')

# 2) JNI 改用 getKvCacheRef()
rep(
'''        auto& kc = s_helperContext->kvCache;''',
'''        auto& kc = s_helperContext->getKvCacheRef();''',
'JNI use getKvCacheRef')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("FIX OK")
