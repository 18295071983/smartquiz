# -*- coding: utf-8 -*-
# native-lib.cpp: thinkingStage 思考段累积时实时下发 {"type":"thinking","content":增量} 事件
# Java 思考区（msg.thinkingContent + 120ms 节流）实时显示，顶部状态条 marquee 保留
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

rep(
'''                    if (close == std::string::npos) {
                        // 思考内容不发流式 token（折叠显示由 Java 端收集），但实时累积供监控
                        thinkingBuffer_.append(completePart, pos, std::string::npos);
                        pos = completePart.size();
                    } else {
                        thinkingBuffer_.append(completePart, pos, close - pos);  // 实时思考内容监控
                        isInThinking = false;
                        setPhase(GenPhase::GENERATING, "chatJson:think_end");
                        LOGI("chatJson: thinking END detected");
                        pos = close + closeLen;
                    }''',
'''                    if (close == std::string::npos) {
                        // 思考内容不发流式 token（折叠显示由 Java 端收集），但实时累积供监控；
                        // 同时实时下发 thinking 增量事件 → Java 思考区（msg.thinkingContent）实时滚动
                        size_t sLen = thinkingBuffer_.size();
                        thinkingBuffer_.append(completePart, pos, std::string::npos);
                        if (thinkingBuffer_.size() > sLen) {
                            nlohmann::ordered_json tj = {{"type", "thinking"},
                                                         {"content", thinkingBuffer_.substr(sLen)}};
                            jsonCallback(tj.dump());
                        }
                        pos = completePart.size();
                    } else {
                        size_t sLen = thinkingBuffer_.size();
                        thinkingBuffer_.append(completePart, pos, close - pos);  // 实时思考内容监控
                        if (thinkingBuffer_.size() > sLen) {
                            nlohmann::ordered_json tj = {{"type", "thinking"},
                                                         {"content", thinkingBuffer_.substr(sLen)}};
                            jsonCallback(tj.dump());
                        }
                        isInThinking = false;
                        setPhase(GenPhase::GENERATING, "chatJson:think_end");
                        LOGI("chatJson: thinking END detected");
                        pos = close + closeLen;
                    }''',
'thinking realtime event')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("THINKING EVENT OK")
