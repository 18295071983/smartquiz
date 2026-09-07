# -*- coding: utf-8 -*-
"""C++ 修复：prompt 尾部预置思考起始标签时，流式剥离初始处于思考态。"""
import io

p = r"D:\qzq\smartquiz\src\main\cpp\native-lib.cpp"
s = io.open(p, encoding="utf-8").read()

old = "        bool isInThinking = false;"
assert s.count(old) == 1, "anchor not unique: %d" % s.count(old)

new = (
    "        // 关键：Qwen 模板把思考起始标签预置到 assistant 前缀（prompt 以 start tag 结尾），\n"
    "        // 模型直接续写思考内容而不会再次输出起始标签——此时流式须初始处于思考态，\n"
    "        // 否则思考内容被当作正文下发（泄漏到 UI/TTS）。\n"
    "        bool promptTailInThinking = false;\n"
    "        {\n"
    "            std::string tail = chat_params.prompt;\n"
    "            size_t lastNs = tail.find_last_not_of(\" \\t\\r\\n\");\n"
    "            if (lastNs != std::string::npos) tail = tail.substr(0, lastNs + 1);\n"
    "            promptTailInThinking = !mThinkStartTag.empty()\n"
    "                && tail.size() >= mThinkStartTag.size()\n"
    "                && tail.compare(tail.size() - mThinkStartTag.size(), mThinkStartTag.size(), mThinkStartTag) == 0;\n"
    "        }\n"
    "        bool isInThinking = promptTailInThinking;\n"
    "        if (isInThinking) LOGI(\"chatJson: initial thinking state (prompt tail ends with start tag)\");"
)

s = s.replace(old, new)
io.open(p, "w", encoding="utf-8").write(s)
print("OK patched C++")
