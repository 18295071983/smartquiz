# -*- coding: utf-8 -*-
# native-lib.cpp: 思考段累积时强制标记状态机 THINKING
# 根因：generateStreamIncremental(enableThinking=false) 的 gen_loop 置 GENERATING，
# 覆盖 chatJson init_think 的 THINKING；导致 UI THINKING 分支（思考内容预览）不触发。
# 修复：chatJson tokenCallback 思考段（isInThinking=true）累积时确保状态机 THINKING。
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
'''                        } else {
                            size_t close = std::string::npos;
                            size_t closeLen = 0;
                            for (const auto& et : endTags) {
                                size_t pp = completePart.find(et, pos);
                                if (pp != std::string::npos && (close == std::string::npos || pp < close)) {
                                    close = pp;
                                    closeLen = et.size();
                                }
                            }
                            if (close == std::string::npos) {
                                // 思考内容不发流式 token（折叠显示由 Java 端收集），但实时累积供监控
                                thinkingBuffer_.append(completePart, pos, std::string::npos);
                                pos = completePart.size();
                            } else {
                                thinkingBuffer_.append(completePart, pos, close - pos);  // 实时思考内容监控
                                isInThinking = false;
                                setPhase(GenPhase::GENERATING, "chatJson:think_end");
                                LOGI("chatJson: thinking END detected");
                                pos = close + closeLen;
                            }
                        }''',
'''                        } else {
                            // 思考中：确保状态机标记 THINKING。
                            // gen_loop 曾按 enableThinking=0 置 GENERATING，覆盖 init_think 的 THINKING，
                            // 若不纠正，UI 的 THINKING 分支（实时思考内容预览）永远不触发。
                            setPhase(GenPhase::THINKING, "chatJson:think_monitor");
                            size_t close = std::string::npos;
                            size_t closeLen = 0;
                            for (const auto& et : endTags) {
                                size_t pp = completePart.find(et, pos);
                                if (pp != std::string::npos && (close == std::string::npos || pp < close)) {
                                    close = pp;
                                    closeLen = et.size();
                                }
                            }
                            if (close == std::string::npos) {
                                // 思考内容不发流式 token（折叠显示由 Java 端收集），但实时累积供监控
                                thinkingBuffer_.append(completePart, pos, std::string::npos);
                                pos = completePart.size();
                            } else {
                                thinkingBuffer_.append(completePart, pos, close - pos);  // 实时思考内容监控
                                isInThinking = false;
                                setPhase(GenPhase::GENERATING, "chatJson:think_end");
                                LOGI("chatJson: thinking END detected");
                                pos = close + closeLen;
                            }
                        }''',
'force THINKING during thinking')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("THINKING FORCE OK")
