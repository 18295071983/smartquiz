# -*- coding: utf-8 -*-
# 修复 native-lib.cpp 三处 Prompt 长度校验 bug（与 Java 侧一致）：
# 原 prompt_tokens + maxTokens > n_ctx 把生成上限计入 context 预算，
# maxTokens(16384) > n_ctx(12288) 时任何短 prompt 都被拒。
# 生成循环已有 KV 满 guard（n_past >= n_ctx-4 优雅停止），
# 这里只校验 prompt 本体是否放得下，预留固定生成余量。
import io, sys

path = r"D:\qzq\smartquiz\src\main\cpp\native-lib.cpp"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

def replace_once(old, new, label):
    global src
    c = src.count(old)
    if c != 1:
        print("%s: count=%d ABORT" % (label, c))
        sys.exit(1)
    src = src.replace(old, new, 1)
    print("%s: OK" % label)

# 1) generate() —— printf 风格
replace_once(
'''        int n_ctx = llama_n_ctx(ctx);
        LOGI("Context size: n_ctx=%d, prompt_tokens=%zu, maxTokens=%d", n_ctx, prompt_tokens.size(), maxTokens);
        // 预留生成空间：prompt + maxTokens 不得超过 n_ctx，防止 KV cache 溢出触发 ggml_abort 崩溃
        if ((int)prompt_tokens.size() + maxTokens > n_ctx) {
            LOGE("Prompt too long: %zu tokens + maxTokens %d > n_ctx %d", prompt_tokens.size(), maxTokens, n_ctx);
            setLastError("Prompt too long for context window");
            return false;
        }''',
'''        int n_ctx = llama_n_ctx(ctx);
        LOGI("Context size: n_ctx=%d, prompt_tokens=%zu, maxTokens=%d", n_ctx, prompt_tokens.size(), maxTokens);
        // 修复：maxTokens 是生成停止上限，不计入 context 预算（生成循环有 n_ctx-4 guard 优雅停止）。
        // 只校验 prompt 本体是否放得下，预留固定生成余量。
        const int GENERATION_RESERVE = 512;
        if ((int)prompt_tokens.size() > n_ctx - GENERATION_RESERVE) {
            LOGE("Prompt too long: %zu tokens >= %d", prompt_tokens.size(), n_ctx - GENERATION_RESERVE);
            setLastError("Prompt too long for context window");
            return false;
        }''',
'generate()')

# 2) generateStream() —— string 风格
replace_once(
'''        int n_ctx = llama_n_ctx(ctx);
        LOGI("Context size: n_ctx=%d, prompt_tokens=%zu, maxTokens=%d", n_ctx, tokens_list.size(), maxTokens);
        // 预留生成空间：prompt + maxTokens 不得超过 n_ctx，防止 KV cache 溢出触发 ggml_abort 崩溃
        if ((int)tokens_list.size() + maxTokens > n_ctx) {
            std::string error = "Prompt too long: " + std::to_string(tokens_list.size()) + " tokens + maxTokens "
                + std::to_string(maxTokens) + " > n_ctx " + std::to_string(n_ctx);
            LOGE("%s", error.c_str());
            setLastError(error);
            callback("", true, error);
            return false;
        }''',
'''        int n_ctx = llama_n_ctx(ctx);
        LOGI("Context size: n_ctx=%d, prompt_tokens=%zu, maxTokens=%d", n_ctx, tokens_list.size(), maxTokens);
        // 修复：maxTokens 是生成停止上限，不计入 context 预算（生成循环有 n_ctx-4 guard 优雅停止）。
        // 只校验 prompt 本体是否放得下，预留固定生成余量。
        const int GENERATION_RESERVE = 512;
        if ((int)tokens_list.size() > n_ctx - GENERATION_RESERVE) {
            std::string error = "Prompt too long: " + std::to_string(tokens_list.size()) + " tokens >= "
                + std::to_string(n_ctx - GENERATION_RESERVE);
            LOGE("%s", error.c_str());
            setLastError(error);
            callback("", true, error);
            return false;
        }''',
'generateStream()')

# 3) generateStreamIncremental() —— string 风格
replace_once(
'''        int n_ctx = llama_n_ctx(ctx);
        LOGI("Context size: n_ctx=%d, prompt_tokens=%zu, maxTokens=%d", n_ctx, tokens_list.size(), maxTokens);
        if ((int)tokens_list.size() + maxTokens > n_ctx) {
            std::string error = "Prompt too long: " + std::to_string(tokens_list.size()) + " tokens + maxTokens "
                + std::to_string(maxTokens) + " > n_ctx " + std::to_string(n_ctx);
            LOGE("%s", error.c_str());
            setLastError(error);
            callback("", true, error);
            return false;
        }''',
'''        int n_ctx = llama_n_ctx(ctx);
        LOGI("Context size: n_ctx=%d, prompt_tokens=%zu, maxTokens=%d", n_ctx, tokens_list.size(), maxTokens);
        // 修复：maxTokens 是生成停止上限，不计入 context 预算（生成循环有 n_ctx-4 guard 优雅停止）。
        // 只校验 prompt 本体是否放得下，预留固定生成余量。
        const int GENERATION_RESERVE = 512;
        if ((int)tokens_list.size() > n_ctx - GENERATION_RESERVE) {
            std::string error = "Prompt too long: " + std::to_string(tokens_list.size()) + " tokens >= "
                + std::to_string(n_ctx - GENERATION_RESERVE);
            LOGE("%s", error.c_str());
            setLastError(error);
            callback("", true, error);
            return false;
        }''',
'generateStreamIncremental()')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("ALL PATCHES OK")
