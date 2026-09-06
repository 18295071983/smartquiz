# -*- coding: utf-8 -*-
# ModelExecutionBridge.java: 加 thinking 实时事件处理 + thinkingStreamed 防 reasoning 重复
import io, sys
path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\bridge\ModelExecutionBridge.java"
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
'''        private final StringBuilder bodyBuf = new StringBuilder();
        private final long startTime = System.currentTimeMillis();
        private int tokenCount = 0;

        BridgeJsonCallback(String messageId, BridgeCallback callback) {''',
'''        private final StringBuilder bodyBuf = new StringBuilder();
        private final long startTime = System.currentTimeMillis();
        private int tokenCount = 0;
        /** 本轮是否已实时收到 thinking 增量事件：思考区已实时累积，complete 的 reasoning 全文跳过防重复 */
        private boolean thinkingStreamed = false;

        BridgeJsonCallback(String messageId, BridgeCallback callback) {''',
'field')

# 2) 加 thinking 事件 case + reasoning 防重复
rep(
'''                    case "reasoning": {
                        String reasoning = event.optString("content", "");
                        if (!reasoning.isEmpty()) {
                            mainHandler.post(() -> {
                                if (callback != null) {
                                    callback.onThinkingUpdate(messageId, 1, "thinking", "思考", reasoning, 0);
                                }
                            });
                        }
                        break;
                    }''',
'''                    case "thinking": {
                        // 实时思考增量事件：native 思考段每累积一段就下发，思考区实时滚动
                        String tk = event.optString("content", "");
                        if (!tk.isEmpty()) {
                            thinkingStreamed = true;
                            mainHandler.post(() -> {
                                if (callback != null) {
                                    callback.onThinkingUpdate(messageId, 1, "thinking", "思考", tk, 0);
                                }
                            });
                        }
                        break;
                    }
                    case "reasoning": {
                        // 若思考已实时累积（thinkingStreamed），全文不再追加（防思考区重复）；
                        // 否则为 legacy 兜底（无 thinking 事件路径），照常一次性写入
                        if (thinkingStreamed) break;
                        String reasoning = event.optString("content", "");
                        if (!reasoning.isEmpty()) {
                            mainHandler.post(() -> {
                                if (callback != null) {
                                    callback.onThinkingUpdate(messageId, 1, "thinking", "思考", reasoning, 0);
                                }
                            });
                        }
                        break;
                    }''',
'bridge thinking case')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("BRIDGE THINKING OK")
