# -*- coding: utf-8 -*-
# AgentLoopEngine.java: 加 thinking 实时事件处理 + reasoning 防重复
import io, sys
path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\agent\software\engine\AgentLoopEngine.java"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

def rep(old, new, label, expect=1):
    global src
    c = src.count(old)
    if c != expect:
        print("[FAIL] %s: count=%d" % (label, c)); sys.exit(1)
    src = src.replace(old, new, expect)
    print("[OK] %s" % label)

# 1) 局部标志
rep(
'''        final boolean[] done = {false};   // F10：幂等标志，error/complete 后忽略迟到事件
''',
'''        final boolean[] done = {false};   // F10：幂等标志，error/complete 后忽略迟到事件
        final boolean[] thinkingStreamedAgent = {false};   // 思考已实时累积（thinking 事件），reasoning 全文跳过防重复
''',
'flag')

# 2) thinking case + reasoning 防重复
rep(
'''                        case "reasoning":
                            String reasoning = event.optString("content", "");
                            if (!reasoning.isEmpty()) {
                                reasoningBuf.append(reasoning);
                                if (callback != null) callback.onThinkingUpdate(reasoning);
                            }
                            break;''',
'''                        case "thinking":
                            // 实时思考增量事件：native 思考段每累积一段下发，思考区实时显示
                            {
                                String tk = event.optString("content", "");
                                if (!tk.isEmpty()) {
                                    thinkingStreamedAgent[0] = true;
                                    reasoningBuf.append(tk);
                                    if (callback != null) callback.onThinkingUpdate(tk);
                                }
                            }
                            break;
                        case "reasoning":
                            // 思考已实时累积（thinkingStreamedAgent）则全文跳过防重复
                            if (thinkingStreamedAgent[0]) break;
                            String reasoning = event.optString("content", "");
                            if (!reasoning.isEmpty()) {
                                reasoningBuf.append(reasoning);
                                if (callback != null) callback.onThinkingUpdate(reasoning);
                            }
                            break;''',
'agent thinking case')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("AGENT THINKING OK")
