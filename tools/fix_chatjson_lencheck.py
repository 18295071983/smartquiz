# -*- coding: utf-8 -*-
# 修复 chatJson Prompt 长度校验 bug：
# 原逻辑 promptTokens + maxTokens >= safeRef 把生成上限 max_tokens 计入 context 预算，
# 导致 max_tokens(16384) > n_ctx(12288) 时任何短 prompt 都被拒绝。
# 改为只校验 prompt 本体，预留固定生成余量。
import io, sys

path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\jni\LlamaHelper.java"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

old = '''        try {
            int safeRef = getSafeContextReference(contextTotalSize > 0 ? contextTotalSize : 4096);
            int promptTokens = estimateChatJsonPromptTokens(requestJson);
            int maxTokens = extractJsonMaxTokens(requestJson);
            if (promptTokens + maxTokens >= safeRef) {
                AILogger.e(TAG, "[chatJson] ❌ Prompt过长: " + promptTokens + "+" + maxTokens
                        + " >= " + safeRef + "，拒绝生成避免 native 崩溃");
                if (callback != null) callback.onError("Prompt too long: " + promptTokens + " tokens, aborting");
                return;
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "[chatJson] 预算检查失败(放行): " + t.getMessage());
        }'''

new = '''        try {
            int safeRef = getSafeContextReference(contextTotalSize > 0 ? contextTotalSize : 4096);
            int promptTokens = estimateChatJsonPromptTokens(requestJson);
            // 修复：max_tokens 是生成停止上限，不是 context 预算的一部分。
            // 原 promptTokens + maxTokens 会把大 max_tokens（如 16384 > n_ctx 12288）
            // 误判为超长，导致 11 token 的短 prompt 也被拒绝。这里只校验 prompt
            // 本体是否超出安全窗口，预留固定生成余量（生成到顶时自然截断）。
            final int GENERATION_RESERVE = 512;
            if (promptTokens >= safeRef - GENERATION_RESERVE) {
                AILogger.e(TAG, "[chatJson] ❌ Prompt过长: " + promptTokens
                        + " >= " + (safeRef - GENERATION_RESERVE) + "，拒绝生成避免 native 崩溃");
                if (callback != null) callback.onError("Prompt too long: " + promptTokens + " tokens, aborting");
                return;
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "[chatJson] 预算检查失败(放行): " + t.getMessage());
        }'''

c = src.count(old)
print("old block count:", c)
if c != 1:
    print("ABORT: block not found/unique")
    sys.exit(1)

src = src.replace(old, new, 1)
with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("PATCH OK")
