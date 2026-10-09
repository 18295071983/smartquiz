package com.oilquiz.app.ai.chat.parser;

import com.oilquiz.app.util.AILogger;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;

/**
 * OutputRouter - 模型输出路由器
 * 
 * 功能：
 * 1. 接收原始 token 并解析
 * 2. 根据解析结果路由到不同的处理器
 * 3. 管理流式输出的状态
 * 4. 累积完整内容
 */
public class OutputRouter {

    private static final String TAG = "OutputRouter";

    /**
     * 输出处理器接口
     */
    public interface OutputHandler {
        /**
         * 处理普通文本输出
         */
        void onTextOutput(String text, boolean isComplete);

        /**
         * 处理思考开始
         */
        void onThinkingStart();

        /**
         * 处理思考内容
         */
        void onThinkingContent(String content);

        /**
         * 处理思考结束
         */
        void onThinkingEnd();

        /**
         * 处理工具调用
         */
        void onToolCall(String toolName, JSONObject parameters);

        /**
         * 处理结构化数据
         */
        void onStructuredData(String dataType, JSONObject data);

        /**
         * 处理错误
         */
        void onError(String error);

        /**
         * 处理流式完成
         */
        void onStreamComplete(String fullContent);
    }

    /**
     * native 思考结束控制标记（协议标记，不是模型标签）。
     * 由 native 生成循环在思考结束时主动插入，Java 按协议识别即可，无需猜模型标签。
     */
    private static final String THINK_END_MARKER = "[THINK_END]";

    private final OutputHandler handler;
    private final StringBuilder textBuffer;
    private final StringBuilder thinkingBuffer;
    private final StringBuilder fullContentBuffer;
    /**
     * 模板思考标签：来自 chat template（native meta 事件 / LlamaHelper.getThinkingTags()）。
     * 不同模型的写法不同（Qwen3 用 &lt;think&gt;、DeepSeek 用 [THINK]、GPT-OSS 用
     * &lt;|channel|&gt;analysis&lt;|message|&gt;…），因此绝不在 Java 侧写死。
     */
    private ThinkingTagConfig tagConfig = ThinkingTagConfig.empty();
    /**
     * 模板工具调用标签：来自 chat template（native meta 事件 / LlamaHelper.getToolCallTags()）。
     * 各模型写法不同（Qwen3.5 用 &lt;function=...&gt;/&lt;parameter=...&gt;、Qwen2.5 用
     * &lt;tool_call&gt;、其他用各自标记），因此与思考标签同理，绝不在 Java 侧写死。
     */
    private ToolCallTagConfig toolTagConfig = ToolCallTagConfig.empty();
    /** 跨 token 标签边界缓冲：标签可能被 tokenizer 拆开，凑齐后再判定，避免漏检 */
    private final StringBuilder tagLookahead = new StringBuilder();

    private boolean isInThinking = false;
    private boolean isInToolCall = false;
    private boolean isInStructuredData = false;
    private String currentStructuredDataType;

    // native 层启用思考时，不发开始标记，首个 token 到达即自动进入思考模式
    // native 层通过 [THINK_END] 标记结束思考
    private boolean thinkingEnabled = false;
    /**
     * 本轮是否由**模板解析器**接管分流（native 已结构化分离正文/思考/工具调用）。
     *
     * <p><b>必须由配置侧显式设置，不能在本类里查 native 全局标志。</b>
     * 原因：native 的 {@code parser 非空} 判据反映的是"当前加载的本地模型模板有没有 PEG 语法"，
     * 一旦本地模型加载过它就长期为真 —— 而**在线 API 轮次也会经过本类**。
     * 若在此处直接查 native，在线轮次会误判为"解析器权威"而跳过在线路径需要的文本清理。
     * 因此权威性由 {@code AIChatActivity.configureOutputRouterForNewTurn} 按
     * "本地 && 解析器可用" 计算后注入。</p>
     */
    private boolean parserAuthority = false;
    /**
     * 解析器路径下本轮是否**启用思考**。
     *
     * <p>为什么不能复用 {@link #thinkingEnabled}：那个字段的语义是"允许 router 在**在线**路径
     * 自己猜分段"（见 AIChatActivity.configureOutputRouterForNewTurn 的统一规则），
     * 本地路径恒为 false。解析器路径需要的是"本轮生成有没有思考段"，语义不同，必须分开。</p>
     *
     * <p>置位条件：native 解析器生效 **且** 本轮启用思考。为真时首个 token 起进入思考段，
     * 直到 native 下发 {@code [THINK_END]} 切回正文。</p>
     */
    private boolean parserThinkingActive = false;
    /** 工具调用标记：native 检出工具调用后下发，需与 [THINK_END] 一样按协议处理 */
    private static final String TOOL_CALL_MARKER = "[TOOL_CALL]";
    private boolean thinkingStarted = false;

    public OutputRouter(OutputHandler handler) {
        this.handler = handler;
        this.textBuffer = new StringBuilder();
        this.thinkingBuffer = new StringBuilder();
        this.fullContentBuffer = new StringBuilder();
    }

    /**
     * 设置 native 层是否启用了思考。
     * 启用后，首个 token 到达时自动进入思考模式，直到收到 [THINK_END]。
     */
    public void setThinkingEnabled(boolean enabled) {
        this.thinkingEnabled = enabled;
        this.thinkingStarted = false;
    }

    /**
     * 设置本轮是否由**模板解析器**接管分流。
     *
     * <p>由配置侧（{@code AIChatActivity.configureOutputRouterForNewTurn}）按
     * "本地引擎 && native 模板声明了 parser" 计算后注入。</p>
     *
     * <p><b>为什么必须显式注入、不在本类查 native</b>：native 的判据反映"当前已加载的本地模型
     * 模板有没有 PEG 语法"，本地模型一旦加载过就长期为真，而在线 API 轮次也会经过本类 ——
     * 直接查 native 会把在线轮次误判为解析器权威，从而跳过在线路径需要的文本清理。</p>
     */
    public void setParserAuthority(boolean authority) {
        this.parserAuthority = authority;
        if (!authority) {
            this.parserThinkingActive = false;
            this.thinkingStarted = false;
        }
    }

    /** 本轮是否由模板解析器接管（供配置侧打印/断言） */
    public boolean isParserAuthority() {
        return parserAuthority;
    }

    /**
     * 设置**解析器路径**下本轮是否启用思考。
     *
     * <p>解析器生效时（native 模板声明了 parser），本文本标签匹配全部关闭，思考段与正文的
     * 切换只能靠 native 的 {@code [THINK_END]} 标记。为让"标记之前的 token 归思考段"成立，
     * 必须显式告知本轮是否真的启用思考 —— 语义不同于 {@link #setThinkingEnabled}
     * （后者专指"在线路径让 router 自己猜分段"）。</p>
     *
     * @param active native 解析器生效且本轮启用思考时为 true
     */
    public void setParserThinkingActive(boolean active) {
        this.parserThinkingActive = active;
        if (!active) {
            this.thinkingStarted = false;
        }
    }

    /** 解析器生效时的思考分段是否已启用（供配置侧打印/断言） */
    public boolean isParserThinkingActive() {
        return parserThinkingActive;
    }

    /**
     * 设置思考标签（来自 chat template，不硬编码）。
     * 调用方从 native meta 事件或 {@code LlamaHelper.getThinkingTags()} 获取后注入。
     * 传 null 等同于清空（按"该模型无思考段"处理）。
     */
    public void setThinkingTags(ThinkingTagConfig config) {
        this.tagConfig = config != null ? config : ThinkingTagConfig.empty();
    }

    /**
     * 设置工具调用标签（来自 chat template，不硬编码）。
     * 传 null 等同于清空（按"该模型无工具调用语法"处理，此时不做任何吞除）。
     */
    public void setToolCallTags(ToolCallTagConfig config) {
        this.toolTagConfig = config != null ? config : ToolCallTagConfig.empty();
    }

    /**
     * 处理 token
     */
    public void processToken(String token) {
        if (token == null || token.isEmpty()) return;

        // 检查 native 层思考结束标记
        if (THINK_END_MARKER.equals(token)) {
            if (isInThinking) {
                isInThinking = false;
                handler.onThinkingEnd();
            }
            return;
        }

        // 记录完整内容（排除内部控制标记）
        fullContentBuffer.append(token);

        // PARSER-AUTHORITATIVE(2026-10-09)：解析器生效时，native 下发的已经是
        // common_chat_parse 给出的**干净正文/思考**，这里**绝不再做任何标签匹配**。
        //
        // 为什么必须这样：下面的文本匹配路径有三处会**吞掉正常文本**（尤其破坏 HTML）：
        //   ① possibleTagPrefix() 命中即扣住 token 不下发
        //   ② toolTagConfig.containsOpenTag() 是 text.contains(子串) 匹配，HTML 标签名
        //      一旦撞上工具标签名，整段被当工具调用丢弃
        //   ③ isInToolCall 期间所有 token 被丢弃
        // 解析器已经把"正文 / 思考 / 工具调用"结构化分开，再用文本猜一遍属于双协议，
        // 且实测模型输出的 HTML 因此大量丢 "<"。
        //
        // 状态机（native 只提供两种显式标记，其余 token 按当前段归属）：
        //   parserActive 且未收到 [THINK_END] → 这些 token 是思考段
        //   收到 [THINK_END] 之后            → 正文
        //   [TOOL_CALL]                      → 工具调用轮，正文不再下发
        if (parserAuthority) {
            processTokenWithParser(token);
            return;
        }

        processTokenWithTagMatching(token);
    }

    /**
     * 解析器路径：native 已结构化分离，本方法只做**段归属**，不做标签匹配。
     */
    private void processTokenWithParser(String token) {
        if (TOOL_CALL_MARKER.equals(token)) {
            isInToolCall = true;
            handler.onToolCall("", new JSONObject());
            return;
        }
        if (isInToolCall) return;

        // 解析器路径下【没有标签可扫】，思考状态由显式标志决定：
        // 本轮启用思考 → 首个 token 起即进入思考段，直到收到 [THINK_END] 切回正文。
        // （原文本匹配路径靠 tagConfig/thinkingEnabled 在标签处切换，这里不能沿用。）
        if (parserThinkingActive && !thinkingStarted && !isInThinking) {
            thinkingStarted = true;
            isInThinking = true;
            handler.onThinkingStart();
        }

        if (isInThinking) {
            thinkingBuffer.append(token);
            handler.onThinkingContent(token);
            return;
        }
        textBuffer.append(token);
        handler.onTextOutput(token, false);
    }

    /**
     * 文本匹配路径：**仅**在没有解析器可用时使用（纯内容模板 / 模板未声明 parser）。
     * 保留原有行为作为兜底，不得在解析器生效时进入。
     */
    private void processTokenWithTagMatching(String token) {
        // 思考标签可能跨 token 到达（如 <|thinking_start|> 被 tokenizer 拆开），
        // 先合并到 tagLookahead 缓冲；若尾部是某标签的前缀则等后续 token 凑齐，避免漏检。
        tagLookahead.append(token);
        String buffer = tagLookahead.toString();
        if (possibleTagPrefix(buffer) != null) {
            return;   // 标签尚未到齐，保留缓冲等待下一个 token
        }
        tagLookahead.setLength(0);

        // 思考标签来自 chat template（tagConfig），不硬编码 <think>/</think>；
        // 模板未提供时跳过本段，仍可由 native [THINK_END] / thinkingEnabled 兜底。
        if (tagConfig.isAvailable()) {
            String startTag = tagConfig.getStartTag();
            if (buffer.contains(startTag)) {
                if (!isInThinking) {
                    isInThinking = true;
                    thinkingStarted = true;
                    handler.onThinkingStart();
                }
                String content = extractAfterTag(buffer, startTag);
                if (!content.isEmpty()) {
                    thinkingBuffer.append(content);
                    handler.onThinkingContent(content);
                }
                return;
            }
            String endTag = firstEndTagIn(buffer);
            if (endTag != null) {
                String content = extractBeforeTag(buffer, endTag);
                if (!content.isEmpty()) {
                    thinkingBuffer.append(content);
                    handler.onThinkingContent(content);
                }
                if (isInThinking) {
                    isInThinking = false;
                    handler.onThinkingEnd();
                }
                // 结束标签之后同 buffer 的内容属于正文，必须下发（否则吞掉正文首个字符）
                String after = buffer.substring(buffer.indexOf(endTag) + endTag.length());
                if (!after.isEmpty()) {
                    textBuffer.append(after);
                    handler.onTextOutput(after, false);
                }
                return;
            }
        }

        // native 层启用思考时，首个 token 自动进入思考模式
        if (thinkingEnabled && !thinkingStarted && !isInThinking) {
            thinkingStarted = true;
            isInThinking = true;
            handler.onThinkingStart();
        }

        // 如果在思考中，累积思考内容
        if (isInThinking) {
            thinkingBuffer.append(token);
            handler.onThinkingContent(token);
            return;
        }

        // TOOLCALL-TEMPLATE-DRIVEN(2026-10-09)：工具调用标签一律取自 chat template
        // （ToolCallTagConfig，由 native 从 preserved_tokens 推导），不再在 Java 侧
        // 写死 "<tool_call>" / "<function="。模板未声明工具语法时不吞任何内容。
        //
        // CONSECUTIVE-SPECIAL-TOKENS(2026-10-09)：同一个 token 里可能同时带开标签和
        // 闭标签（连续特殊 token，如 "<tool_call>...</tool_call>" 一次到达）。
        // 旧实现先判开标签并直接 return，闭标签永远轮不到 -> isInToolCall 卡死在 true
        // -> 之后所有正文被永久吞掉。因此必须：先处理闭标签（它是"退出抑制"的更强信号），
        // 再处理开标签，最后按"本 token 是否还残留工具语法"决定要不要继续抑制。
        if (toolTagConfig.isAvailable()) {
            final boolean hasOpen = toolTagConfig.containsOpenTag(token);
            final boolean hasClose = toolTagConfig.containsCloseTag(token);
            if (hasClose) {
                // 无论此前是否在工具调用中，闭标签都意味着该段工具调用结束
                isInToolCall = false;
                emitToolCallFromBuffer();
                // 同一 token 里若还有新的开标签（连续工具调用），下面重新进入抑制
                if (!hasOpen) {
                    return;
                }
                isInToolCall = true;
                return;
            }
            if (hasOpen) {
                isInToolCall = true;
                return;
            }
        }

        // 如果在工具调用中，跳过内容（已在完整内容中）
        if (isInToolCall) {
            return;
        }

        // 检查是否是结构化数据标签
        if (token.contains("<数据类型=\"")) {
            isInStructuredData = true;
            currentStructuredDataType = extractDataType(token);
            return;
        }

        if (token.contains("</数据>")) {
            isInStructuredData = false;
            // 解析结构化数据
            String jsonContent = extractBetweenTags(fullContentBuffer.toString(), "<数据类型=\"", "</数据>");
            if (jsonContent != null) {
                try {
                    JSONObject jsonData = new JSONObject(jsonContent);
                    handler.onStructuredData(currentStructuredDataType, jsonData);
                } catch (Exception e) {
                    AILogger.w(TAG, "Failed to parse structured data: " + e.getMessage());
                }
            }
            currentStructuredDataType = null;
            return;
        }

        // 如果在结构化数据中，跳过内容
        if (isInStructuredData) {
            return;
        }

        // TOOL-SYNTAX-SUPPRESS(2026-10-09)：兜底吞除。
        // 上面的模板分支只处理"本 token 含开/闭标签"的情形；当标签跨 token 到达、
        // 或 token 落在工具调用段内部（既无开标签也无闭标签）时，仍可能漏到正文，
        // 表现为 "function=location>function=ai_weather>parameter今天银川..."。
        // 这里用同一份模板标签做兜底：命中开标签就进入抑制状态。
        // 标签一律取自 chat template，不硬编码模型写法。
        if (toolTagConfig.isAvailable() && toolTagConfig.containsOpenTag(token)) {
            isInToolCall = true;
            return;
        }
        if (isInToolCall) {
            return;
        }

        // 普通文本
        textBuffer.append(token);
        handler.onTextOutput(token, false);
    }

    /**
     * 按模板剥离工具调用段（防御性，供终稿与下游使用）。
     *
     * TOOL-SYNTAX-STRIP(2026-10-09)：只删标记与包裹本身，不删裸露的参数值 ——
     * 参数值往往是 native 工具执行实际用到的内容，一并删掉有误伤正文的风险。
     * 标签取自 {@link ToolCallTagConfig}（chat template），不硬编码模型写法。
     */
    public static String stripToolCallSyntax(String text, ToolCallTagConfig cfg) {
        if (text == null || text.isEmpty() || cfg == null || !cfg.isAvailable()) return text;

        String out = text;
        for (String open : cfg.getOpenTags()) {
            int guard = 0;
            while (guard++ < 64) {
                int i = out.indexOf(open);
                if (i < 0) break;
                int end = -1;
                int best = Integer.MAX_VALUE;
                for (String close : cfg.getCloseTags()) {
                    int j = out.indexOf(close, i + open.length());
                    if (j >= 0 && j < best) {
                        best = j;
                        end = j + close.length();
                    }
                }
                if (end < 0) {
                    // NO-TRUNCATE(2026-10-09)：此处原先 `out = out.substring(0, i)` —— 即"只要
                    // 出现一个未配对的工具开标签，它**之后的所有正文整段丢弃**"。HTML 内容里 `<`
                    // 密集，极易命中，一旦命中就会成片吞掉正文（实测模型输出的 HTML 丢了几十个
                    // `<`，与这类文本匹配式删改高度相关）。改为**只删掉这个开标签本身**，
                    // 其后内容按原样保留：宁可让残留的少量标记出现在正文里，也不能静默删正文。
                    out = out.substring(0, i) + out.substring(i + open.length());
                } else {
                    out = out.substring(0, i) + out.substring(end);
                }
            }
        }
        return out;
    }

    /**
     * 从完整缓冲里取出工具调用段并下发 onToolCall。
     * 标签全部取自模板；同时兼容 JSON 形态与 XML 参数形态。
     */
    private void emitToolCallFromBuffer() {
        String full = fullContentBuffer.toString();
        JSONObject parsed = null;
        for (String open : toolTagConfig.getOpenTags()) {
            for (String close : toolTagConfig.getCloseTags()) {
                String block = extractBetweenTags(full, open, close);
                if (block == null || block.isEmpty()) continue;
                if (block.indexOf("<function=") >= 0 || block.indexOf("<parameter=") >= 0) {
                    parsed = parseQwen35XmlToolCall(block);
                } else {
                    try {
                        parsed = new JSONObject(block);
                    } catch (Exception e) {
                        AILogger.w(TAG, "tool call block is not JSON: " + e.getMessage());
                    }
                }
                if (parsed != null) break;
            }
            if (parsed != null) break;
        }
        if (parsed != null) {
            handler.onToolCall(parsed.optString("name", "unknown"),
                    parsed.optJSONObject("parameters"));
        }
    }

    /**
     * 流式完成
     */
    public void complete() {
        // 若思考未正常结束（如 native 层未发送 [THINK_END]），在此兜底关闭。
        // 思考内容保留在思考布局（thinkingBuffer 已通过 onThinkingContent 实时送达 UI），
        // 不再移入主回复，避免思考内容泄漏到主消息导致重复/内容错乱。
        if (isInThinking) {
            isInThinking = false;
            handler.onThinkingEnd();
        }
        // PARSER-AUTHORITATIVE(2026-10-09)：解析器生效时，native 侧 common_chat_parse 已经把
        // 正文与 tool_calls 结构化分离（下发的就是干净正文），这里**不再按文本标签剥第二遍**。
        // 文本匹配式剥离会误伤正常内容（未配对开标签会吞掉其后正文；实测模型输出的 HTML
        // 因此大量丢 `<`），且与解析器构成"双协议"。仅当没有解析器可用时，才保留文本兜底。
        //
        // 注意判据用本类的 parserAuthority（由配置侧按"本地 && 解析器可用"注入），
        // **不能**在这里查 native 全局标志 —— 在线轮次也走本方法，而本地模型加载后
        // 那个标志长期为真，会导致在线路径被误判而跳过它需要的清理。
        final String text;
        final String full;
        if (parserAuthority) {
            text = textBuffer.toString();
            full = fullContentBuffer.toString();
        } else {
            text = stripToolCallSyntax(textBuffer.toString(), toolTagConfig);
            full = stripToolCallSyntax(fullContentBuffer.toString(), toolTagConfig);
        }
        handler.onTextOutput(text, true);
        handler.onStreamComplete(full);
        reset();
    }

    /**
     * 重置状态
     */
    public void reset() {
        textBuffer.setLength(0);
        thinkingBuffer.setLength(0);
        fullContentBuffer.setLength(0);
        isInThinking = false;
        isInToolCall = false;
        isInStructuredData = false;
        currentStructuredDataType = null;
        thinkingStarted = false;
        tagLookahead.setLength(0);
    }

    /**
     * 获取完整内容
     */
    public String getFullContent() {
        return fullContentBuffer.toString();
    }

    /**
     * 获取文本内容
     */
    public String getTextContent() {
        return textBuffer.toString();
    }

    /**
     * 获取思考内容
     */
    public String getThinkingContent() {
        return thinkingBuffer.toString();
    }

    // ========== 辅助方法 ==========

    /** 返回 text 中最早出现的思考结束标签，无则返回 null（多个结束标签取最早） */
    private String firstEndTagIn(String text) {
        if (!tagConfig.isAvailable()) return null;
        String first = null;
        int firstPos = Integer.MAX_VALUE;
        for (String tag : tagConfig.getEndTags()) {
            if (tag.isEmpty()) continue;
            int p = text.indexOf(tag);
            if (p >= 0 && p < firstPos) {
                firstPos = p;
                first = tag;
            }
        }
        return first;
    }

    /**
     * 若 text 尾部是某个思考标签（开始/结束）的“真·前缀”（即比标签本身短），返回该后缀，
     * 表示标签尚未到齐、需等待后续 token 凑齐；否则返回 null（可直接处理）。
     * 注意：suffix 必须严格短于标签（suffix.length() < tag.length()），否则完整标签
     * （如 <think> 整段一个 token 到达）会被误判为“未到齐”而挂起，导致与下一个 token 拼接。
     */
    private String possibleTagPrefix(String text) {
        if (!tagConfig.isAvailable()) return null;
        List<String> tags = new ArrayList<>();
        tags.add(tagConfig.getStartTag());
        tags.addAll(tagConfig.getEndTags());
        int n = text.length();
        int maxSuffix = 0;
        for (String tag : tags) {
            if (!tag.isEmpty()) maxSuffix = Math.max(maxSuffix, tag.length() - 1);
        }
        for (int len = Math.min(maxSuffix, n); len >= 1; len--) {
            String suffix = text.substring(n - len);
            for (String tag : tags) {
                if (!tag.isEmpty() && suffix.length() < tag.length() && tag.startsWith(suffix)) {
                    return suffix;
                }
            }
        }
        return null;
    }

    private String extractAfterTag(String text, String tag) {
        int index = text.indexOf(tag);
        if (index >= 0) {
            return text.substring(index + tag.length());
        }
        return text;
    }

    private String extractBeforeTag(String text, String tag) {
        int index = text.indexOf(tag);
        if (index >= 0) {
            return text.substring(0, index);
        }
        return text;
    }

    private String extractBetweenTags(String text, String startTag, String endTag) {
        int startIndex = text.lastIndexOf(startTag);
        int endIndex = text.lastIndexOf(endTag);

        if (startIndex >= 0 && endIndex > startIndex) {
            return text.substring(startIndex + startTag.length(), endIndex);
        }
        return null;
    }

    /**
     * 解析 Qwen3.5 XML 参数形态的单个工具调用块。
     * 输入为 tool_call 块内部文本：function=工具名 + 多个 parameter=参数名 值对。
     * 返回 {name: 工具名, parameters: {参数名: 值}}，解析失败返回 null。
     */
    private JSONObject parseQwen35XmlToolCall(String blockText) {
        final String paramClose = "</" + "parameter>";
        try {
            int fnStart = blockText.indexOf("<function=");
            if (fnStart < 0) return null;
            int nameStart = fnStart + "<function=".length();
            int nameEnd = blockText.indexOf('>', nameStart);
            if (nameEnd <= nameStart) return null;
            String toolName = blockText.substring(nameStart, nameEnd).trim();
            if (toolName.isEmpty()) return null;

            JSONObject params = new JSONObject();
            int searchFrom = nameEnd + 1;
            while (true) {
                int pStart = blockText.indexOf("<parameter=", searchFrom);
                if (pStart < 0) break;
                int pNameStart = pStart + "<parameter=".length();
                int pNameEnd = blockText.indexOf('>', pNameStart);
                if (pNameEnd < 0) break;
                String paramName = blockText.substring(pNameStart, pNameEnd).trim();
                int vStart = pNameEnd + 1;
                int vEnd = blockText.indexOf(paramClose, vStart);
                if (vEnd < 0) break;
                String paramValue = blockText.substring(vStart, vEnd).trim();
                params.put(paramName, tryParseParamValue(paramValue));
                searchFrom = vEnd + paramClose.length();
            }

            JSONObject result = new JSONObject();
            result.put("name", toolName);
            result.put("parameters", params);
            return result;
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to parse Qwen3.5 XML tool call: " + e.getMessage());
            return null;
        }
    }

    /**
     * 参数值尝试按 JSON 解析（对象/数组/标量），失败保留为字符串。
     */
    private static Object tryParseParamValue(String raw) {
        String v = raw.trim();
        if (v.isEmpty()) return "";
        if (v.startsWith("{")) {
            try {
                return new JSONObject(v);
            } catch (Exception ignored) {
                // 非 JSON 对象，按普通文本处理
            }
        } else if (v.startsWith("[")) {
            try {
                return new org.json.JSONArray(v);
            } catch (Exception ignored) {
                // 非 JSON 数组，按普通文本处理
            }
        }
        try {
            return new JSONTokener(v).nextValue();
        } catch (Exception e) {
            return v;
        }
    }

    private String extractDataType(String text) {
        int startIndex = text.indexOf("<数据类型=\"");
        int endIndex = text.indexOf("\"", startIndex + 11);

        if (startIndex >= 0 && endIndex > startIndex) {
            return text.substring(startIndex + 11, endIndex);
        }
        return "unknown";
    }
}
