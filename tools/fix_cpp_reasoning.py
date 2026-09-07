# -*- coding: utf-8 -*-
"""C++ 修复：用模板标签手动提取思考段（common_chat_parse 对 Qwen3 空格分隔 thinking
标签解析不出 reasoning_content），思考 → reasoning 事件，正文 → complete（干净）。"""
import io

p = r"D:\qzq\smartquiz\src\main\cpp\native-lib.cpp"
s = io.open(p, encoding="utf-8").read()

# ---- A: 在 parseOk 块前插入手动思考段提取 ----
old_a = """        if (parseOk) {
            for (auto& tc : parsed.tool_calls) {"""
new_a = """        // step 8 增强：common_chat_parse 对 Qwen3 空格分隔 thinking 标签解析不出
        // reasoning_content（思考段混入正文）。用模板标签手动提取思考段，保证：
        // 思考 → reasoning 事件（UI 折叠显示），正文 → complete（干净）。
        std::string manualThinking;
        std::string manualContent;
        const bool tagsOk = !mThinkStartTag.empty() && !mThinkEndTags.empty();
        if (tagsOk) {
            size_t open = collectedText.find(mThinkStartTag);
            if (open != std::string::npos) {
                size_t cs = open + mThinkStartTag.size();
                size_t close = std::string::npos;
                size_t closeLen = 0;
                for (const auto& et : mThinkEndTags) {
                    size_t pp = collectedText.find(et, cs);
                    if (pp != std::string::npos && (close == std::string::npos || pp < close)) {
                        close = pp; closeLen = et.size();
                    }
                }
                if (close != std::string::npos) {
                    manualThinking = collectedText.substr(cs, close - cs);
                    manualContent = collectedText.substr(0, open) + collectedText.substr(close + closeLen);
                } else {
                    manualThinking = collectedText.substr(cs);
                    manualContent = collectedText.substr(0, open);
                }
            }
        }

        if (parseOk) {
            for (auto& tc : parsed.tool_calls) {"""

# ---- B1: reasoning 入口条件 + 内容兜底 ----
old_b1 = "            if (!parsed.reasoning_content.empty()) {"
new_b1 = """            // 思考内容：common_chat_parse 解析出 reasoning_content 则用之，否则用模板标签
            // 手动提取的思考段（Qwen3 空格分隔 thinking 标签 parse 常解析不出）
            const std::string reasoningText = !parsed.reasoning_content.empty() ? parsed.reasoning_content : manualThinking;
            if (!reasoningText.empty()) {"""

# ---- B2: reasoning 事件内容 ----
old_b2 = """                    nlohmann::ordered_json j = {{"type", "reasoning"}, {"content", parsed.reasoning_content}};
                    jsonCallback(j.dump());
                    LOGI("chatJson: reasoning (%zu chars)", parsed.reasoning_content.size());"""
new_b2 = """                    nlohmann::ordered_json j = {{"type", "reasoning"}, {"content", reasoningText}};
                    jsonCallback(j.dump());
                    LOGI("chatJson: reasoning (%zu chars)", reasoningText.size());"""

# ---- C: complete 正文优先用剥离思考后的内容 ----
old_c = "                std::string finalContent = parsed.content;"
new_c = """                // 思考已手动提取时正文用剥离后的内容，避免思考混入正文
                std::string finalContent = !manualContent.empty() ? manualContent : parsed.content;"""

for name, o, n in [("A", old_a, new_a), ("B1", old_b1, new_b1), ("B2", old_b2, new_b2), ("C", old_c, new_c)]:
    c = s.count(o)
    print(name, "count:", c)
    assert c == 1, "anchor %s not unique/missing" % name
    s = s.replace(o, n)

io.open(p, "w", encoding="utf-8").write(s)
print("OK patched C++ step8")
