# -*- coding: utf-8 -*-
# 统一修复三处 Prompt 长度校验 bug（与 chatJson 一致）：
# 原 promptTokens + maxTokens >= safeRef 把生成上限计入 context 预算，
# maxTokens 大时（> n_ctx）任何短 prompt 都被误拒。
# 改为只校验 prompt 本体，预留固定生成余量 512。
import io, sys

path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\jni\LlamaHelper.java"
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

# 1) generateStream-Messages
replace_once(
'''        if (promptTokens + maxTokens >= safeRef) {
            AILogger.w(TAG, "[generateStream-Messages] ❌ Prompt过长: " + promptTokens + "+" + maxTokens + ">=" + safeRef
                + ", thread=" + threadName);
            if (callback != null) {
                callback.onError("Prompt too long: " + promptTokens + " tokens, aborting");
            }
            return;
        }''',
'''        // 修复：max_tokens 是生成停止上限，不计入 context 预算（见 chatJson）
        final int GENERATION_RESERVE = 512;
        if (promptTokens >= safeRef - GENERATION_RESERVE) {
            AILogger.w(TAG, "[generateStream-Messages] ❌ Prompt过长: " + promptTokens + ">=" + (safeRef - GENERATION_RESERVE)
                + ", thread=" + threadName);
            if (callback != null) {
                callback.onError("Prompt too long: " + promptTokens + " tokens, aborting");
            }
            return;
        }''',
'generateStream-Messages')

# 2) generateWithTools
replace_once(
'''            if (promptTokens + maxTokens >= safeRef) {
                AILogger.e(TAG, "[generateWithTools] ❌ Prompt过长: " + promptTokens + "+" + maxTokens
                        + " >= " + safeRef + "，拒绝生成避免 native 崩溃");
                if (callback != null) callback.onError("Prompt too long: " + promptTokens + " tokens, aborting");
                return;
            }''',
'''            // 修复：max_tokens 是生成停止上限，不计入 context 预算（见 chatJson）
            final int GENERATION_RESERVE = 512;
            if (promptTokens >= safeRef - GENERATION_RESERVE) {
                AILogger.e(TAG, "[generateWithTools] ❌ Prompt过长: " + promptTokens
                        + " >= " + (safeRef - GENERATION_RESERVE) + "，拒绝生成避免 native 崩溃");
                if (callback != null) callback.onError("Prompt too long: " + promptTokens + " tokens, aborting");
                return;
            }''',
'generateWithTools')

# 3) generateStream-ChatRequest
replace_once(
'''            int promptTokens = countTokens(new String(request.getFullPromptUtf8(), StandardCharsets.UTF_8));
            if (promptTokens + request.getMaxTokens() >= safeRef) {
                AILogger.w(TAG, "[generateStream-ChatRequest] ❌ Prompt过长: " + promptTokens + "+"
                        + request.getMaxTokens() + ">=" + safeRef + ", thread=" + threadName);
                if (callback != null) {
                    callback.onError("Prompt too long: " + promptTokens + " tokens, aborting");
                }
                return;
            }''',
'''            int promptTokens = countTokens(new String(request.getFullPromptUtf8(), StandardCharsets.UTF_8));
            // 修复：max_tokens 是生成停止上限，不计入 context 预算（见 chatJson）
            final int GENERATION_RESERVE = 512;
            if (promptTokens >= safeRef - GENERATION_RESERVE) {
                AILogger.w(TAG, "[generateStream-ChatRequest] ❌ Prompt过长: " + promptTokens + ">="
                        + (safeRef - GENERATION_RESERVE) + ", thread=" + threadName);
                if (callback != null) {
                    callback.onError("Prompt too long: " + promptTokens + " tokens, aborting");
                }
                return;
            }''',
'generateStream-ChatRequest')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("ALL PATCHES OK")
