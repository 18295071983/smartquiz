# -*- coding: utf-8 -*-
# chatJson 生成阶段重构：状态机驱动的阶段化处理器
# 替换 step 7（生成阶段）为 ThinkingStage + GeneratingStage + 薄 tokenCallback
# 行为保持：思考剥离/累积/状态机校正/tool_call 检测/事件协议 与重构前一致
import io, sys
path = r"D:\qzq\smartquiz\src\main\cpp\native-lib.cpp"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

START = '        // ===== step 7：生成阶段（generateStreamIncremental，enableThinking 恒传 false 交给模板）====='
END_TAIL = '''            } else {
                if (!error.empty()) genError = error;   // R7-1：生成失败（isComplete+error）
            }
        };
'''

i = src.find(START)
if i < 0:
    print("[FAIL] start anchor not found"); sys.exit(1)
j = src.find(END_TAIL, i)
if j < 0:
    print("[FAIL] end anchor not found"); sys.exit(1)
j += len(END_TAIL)

NEW = '''        // ===== step 7：生成阶段（阶段化处理器 + 状态机驱动）=====
        clearThinkingContent();             // 实时思考内容监控：新推理清空上一轮
        // 状态机 GenPhase 是本阶段的单一事实源：
        //   THINKING   → ThinkingStage：思考段识别/剥离/累积（思考内容监控）
        //   GENERATING → GeneratingStage：tool_call 增量检测 + 发 token
        // generateStreamIncremental 按 enableThinking=false 会在 gen_loop 置 GENERATING，
        // 本层每个 token 回调后由 ThinkingStage 校正为实际阶段，保证 UI/监控对齐。
        std::string collectedText;          // 完整输出（原始字节，供 parse）
        std::string utf8Buffer;             // R9-1：token 级 UTF-8 完整性缓冲
        std::string genError;               // 生成失败信息（R7-1）
        // §5.2 第一阶段：required → 所有 token 标记 is_tool_call=true；auto/none → false
        bool isInToolCall = (toolChoice == COMMON_CHAT_TOOL_CHOICE_REQUIRED);
        // 思考态识别：模板 enable_thinking=true 时模型会输出  thinking... response，
        // 生成循环 thinking=0（思考交给模板），故流式阶段需自行识别思考段——
        // 思考 token 标记 is_thinking=true（UI 折叠显示、不朗读），标签本身剥离
        // 关键：Qwen 模板把思考起始标签预置到 assistant 前缀（prompt 以 start tag 结尾），
        // 模型直接续写思考内容而不会再次输出起始标签——此时流式须初始处于思考态，
        // 否则思考内容被当作正文下发（泄漏到 UI/TTS）。
        bool promptTailInThinking = false;
        {
            std::string tail = chat_params.prompt;
            size_t lastNs = tail.find_last_not_of(" \\t\\r\\n");
            if (lastNs != std::string::npos) tail = tail.substr(0, lastNs + 1);
            promptTailInThinking = !mThinkStartTag.empty()
                && tail.size() >= mThinkStartTag.size()
                && tail.compare(tail.size() - mThinkStartTag.size(), mThinkStartTag.size(), mThinkStartTag) == 0;
        }
        bool isInThinking = promptTailInThinking;
        if (isInThinking) {
            setPhase(GenPhase::THINKING, "chatJson:init_think");
            LOGI("chatJson: initial thinking state (prompt tail ends with start tag)");
        }
        std::string thinkingAccum;   // 用于检测标签边界（跨 token 的标签片段）
        // §5.2 第二阶段（阶段 4 优化）：auto/none 模式用 is_partial 增量解析检测 tool_call 起始，
        // 检测到后锁定 is_tool_call=true，减少 UI 短暂闪烁（已发出的前几个 token 无法撤回）
        const int PARTIAL_PARSE_INTERVAL = 4;   // 每收 N 个 token 检测一次（16→4：缩短泄漏窗口）
        int partialParseCounter = 0;

        // ---- 阶段A：ThinkingStage —— 思考段流式识别（THINKING 阶段）----
        // 用模板标签（mThinkStartTag/mThinkEndTags）识别完整前缀中的思考段：
        // 进入/退出思考态，标签不发给 UI（标签种类随模型模板变化，不硬编码）；
        // 思考内容累积到 thinkingBuffer_（实时思考内容监控），状态机校正为实际阶段。
        auto thinkingStage = [&](const std::string& completePart, std::string& filtered) -> void {
            filtered.reserve(completePart.size());
            const bool tagsAvailable = !mThinkStartTag.empty() && !mThinkEndTags.empty();
            if (!tagsAvailable) {
                // 无模板标签：不做思考段识别，整段作为正文
                filtered.append(completePart);
                return;
            }
            const std::string& thinkOpen = mThinkStartTag;
            const std::vector<std::string>& endTags = mThinkEndTags;
            size_t pos = 0;
            while (pos < completePart.size()) {
                if (!isInThinking) {
                    size_t open = completePart.find(thinkOpen, pos);
                    if (open == std::string::npos) {
                        filtered.append(completePart, pos, std::string::npos);
                        pos = completePart.size();
                    } else {
                        filtered.append(completePart, pos, open - pos);
                        isInThinking = true;
                        setPhase(GenPhase::THINKING, "chatJson:think_start");
                        LOGI("chatJson: thinking START detected");
                        pos = open + thinkOpen.size();
                    }
                } else {
                    // 思考中：确保状态机 THINKING。
                    // gen_loop 按 enableThinking=0 曾置 GENERATING，覆盖 init_think 的 THINKING，
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
                }
            }
        };

        // ---- 阶段B：GeneratingStage —— 正文 + 工具调用（GENERATING 阶段）----
        // 增量检测 tool_call 起始并锁定 is_tool_call 标记；剥离后的正文发流式 token。
        auto generatingStage = [&](const std::string& filtered) -> void {
            if (filtered.empty()) return;
            // 增量检测：仅 auto/none 且尚未进入 tool_call 时启用
            if (!isInToolCall && toolChoice != COMMON_CHAT_TOOL_CHOICE_REQUIRED) {
                // 快速路径：collectedText 出现 tool_call 标签特征立即锁定，
                // 不等 PARTIAL_PARSE_INTERVAL——避免 `<tool_call>` 前几个 token
                // 以 is_tool_call=false 泄漏到 UI/TTS（实测 TTS 朗读 "<toolcall"）
                if (collectedText.find("<tool_call>") != std::string::npos
                        || collectedText.find("<tool_call") != std::string::npos
                        || collectedText.find("<toolcall") != std::string::npos
                        || collectedText.find("tool_call") != std::string::npos) {
                    isInToolCall = true;
                    LOGI("chatJson: fast-path detected tool_call output");
                } else if (++partialParseCounter >= PARTIAL_PARSE_INTERVAL) {
                    partialParseCounter = 0;
                    try {
                        common_chat_parser_params pp(chat_params);
                        pp.parse_tool_calls = true;
                        common_chat_msg partial = common_chat_parse(collectedText, true, pp);
                        if (!partial.tool_calls.empty()) {
                            isInToolCall = true;   // 检测到 tool_call 输出，锁定后续标记
                            LOGI("chatJson: is_partial detected tool_call output");
                        }
                    } catch (const std::exception& e) {
                        LOGW("chatJson: partial parse failed (ignored): %s", e.what());
                    }
                }
            }
            // 思考内容本身不发流式 token（Java 端经 complete 的 reasoning 字段获取），
            // 只发剥离标签后的正文；工具调用中标记 is_tool_call=true（UI 不朗读）
            nlohmann::ordered_json j = {{"type", "token"}, {"content", filtered},
                                        {"is_tool_call", isInToolCall}};
            jsonCallback(j.dump());
        };

        // ---- tokenCallback：收集 → UTF-8 完整 → 阶段路由 ----
        auto tokenCallback = [&](const std::string& text, bool isComplete, const std::string& error) {
            if (!isComplete) {
                if (!error.empty()) {
                    genError = error;   // R7-1：生成中错误
                    return;
                }
                collectedText += text;
                // R9-1：UTF-8 完整性——只发完整前缀，不完整尾部留在 buffer
                std::string combined = utf8Buffer + text;
                std::string completePart;
                utf8Buffer = splitUtf8Complete(combined, completePart);
                if (completePart.empty()) return;
                // 阶段路由：ThinkingStage（思考剥离+状态机）→ GeneratingStage（tool_call+发 token）
                std::string filtered;
                thinkingStage(completePart, filtered);
                generatingStage(filtered);
            } else {
                if (!error.empty()) genError = error;   // R7-1：生成失败（isComplete+error）
            }
        };

'''

src = src[:i] + NEW + src[j:]
with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("CHATJSON STAGED REFACTOR OK")
