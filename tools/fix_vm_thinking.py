# -*- coding: utf-8 -*-
# AIChatViewModel.java: 备用本地 chatJson 路径加 thinking 实时事件 + reasoning 防重复
import io, sys
path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\chat\viewmodel\AIChatViewModel.java"
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
'''    private StringBuilder currentThinkingContent;
    private boolean isInThinking = false;''',
'''    private StringBuilder currentThinkingContent;
    private boolean isInThinking = false;
    /** 本轮是否已实时收到 thinking 增量事件：思考区已实时累积，reasoning 全文跳过防重复 */
    private volatile boolean thinkingStreamedLocal = false;''',
'field')

# 2) 事件处理：加 thinking case + reasoning 防重复
rep(
'''                                case "reasoning": {
                                    String reasoning = event.optString("content", "");
                                    if (!reasoning.isEmpty()) handleThinkingToken(reasoning);
                                    break;
                                }''',
'''                                case "thinking": {
                                    String tk = event.optString("content", "");
                                    if (!tk.isEmpty()) {
                                        thinkingStreamedLocal = true;
                                        handleThinkingToken(tk);
                                    }
                                    break;
                                }
                                case "reasoning": {
                                    // 思考已实时累积（thinkingStreamedLocal）则全文跳过防重复
                                    if (thinkingStreamedLocal) break;
                                    String reasoning = event.optString("content", "");
                                    if (!reasoning.isEmpty()) handleThinkingToken(reasoning);
                                    break;
                                }''',
'vm thinking case')

# 3) startLocalInference 开头重置标志
rep(
'''    private void startLocalInference(String message) {
        try {
        executor.execute(() -> {''',
'''    private void startLocalInference(String message) {
        try {
        thinkingStreamedLocal = false;
        executor.execute(() -> {''',
'vm reset flag')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("VM THINKING OK")
