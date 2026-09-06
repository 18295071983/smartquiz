# -*- coding: utf-8 -*-
# OnlineInferenceService.java + InferenceRouter.java: reasoning_content 经 onThinkingToken 实时转发
import io, sys

# ===== OnlineInferenceService.java =====
p1 = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\service\OnlineInferenceService.java"
s1 = io.open(p1, "r", encoding="utf-8").read()
def rep1(old, new, label, expect=1):
    global s1
    c = s1.count(old)
    if c != expect:
        print("[FAIL1] %s: count=%d" % (label, c)); sys.exit(1)
    s1 = s1.replace(old, new, expect)
    print("[OK1] %s" % label)

rep1(
'''                                // 深度思考：reasoning_content 思考链（累计，用于 content 空时兜底）
                                if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                                    String rc = delta.get("reasoning_content").getAsString();
                                    reasoningText.append(rc);
                                }''',
'''                                // 深度思考：reasoning_content 思考链（累计，用于 content 空时兜底；
                                // 同时实时转发 onThinkingToken 供思考区/顶部单行显示）
                                if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                                    String rc = delta.get("reasoning_content").getAsString();
                                    reasoningText.append(rc);
                                    if (!rc.isEmpty()) {
                                        final String rct = rc;
                                        mainHandler.post(() -> callback.onThinkingToken(rct));
                                    }
                                }''',
'online thinking callback')

io.open(p1, "w", encoding="utf-8", newline="\n").write(s1)

# ===== InferenceRouter.java =====
p2 = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\inference\InferenceRouter.java"
s2 = io.open(p2, "r", encoding="utf-8").read()
def rep2(old, new, label, expect=1):
    global s2
    c = s2.count(old)
    if c != expect:
        print("[FAIL2] %s: count=%d" % (label, c)); sys.exit(1)
    s2 = s2.replace(old, new, expect)
    print("[OK2] %s" % label)

rep2(
'''                    @Override
                    public void onToken(String token) {
                        callback.onToken(token);
                    }

                    @Override
                    public void onComplete(String fullText) {
                        callback.onComplete(fullText);
                    }

                    @Override
                    public void onError(String error) {
                        callback.onError(error);
                    }

                    @Override
                    public void onTokenStats(int promptTokens, int completionTokens) {
                        // 转发在线模型 API 返回的 Token 统计
                        callback.onTokenStats(promptTokens, completionTokens);
                    }
                });''',
'''                    @Override
                    public void onToken(String token) {
                        callback.onToken(token);
                    }

                    @Override
                    public void onThinkingToken(String token) {
                        // 转发在线思考（reasoning_content）增量
                        callback.onThinkingToken(token);
                    }

                    @Override
                    public void onComplete(String fullText) {
                        callback.onComplete(fullText);
                    }

                    @Override
                    public void onError(String error) {
                        callback.onError(error);
                    }

                    @Override
                    public void onTokenStats(int promptTokens, int completionTokens) {
                        // 转发在线模型 API 返回的 Token 统计
                        callback.onTokenStats(promptTokens, completionTokens);
                    }
                });''',
'router thinking forward')

io.open(p2, "w", encoding="utf-8", newline="\n").write(s2)
print("ONLINE FORWARD OK")
