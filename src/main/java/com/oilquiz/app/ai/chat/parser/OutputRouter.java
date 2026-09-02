package com.oilquiz.app.ai.chat.parser;

import com.oilquiz.app.util.AILogger;

import org.json.JSONObject;

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
    /** 跨 token 标签边界缓冲：标签可能被 tokenizer 拆开，凑齐后再判定，避免漏检 */
    private final StringBuilder tagLookahead = new StringBuilder();

    private boolean isInThinking = false;
    private boolean isInToolCall = false;
    private boolean isInStructuredData = false;
    private String currentStructuredDataType;

    // native 层启用思考时，不发开始标记，首个 token 到达即自动进入思考模式
    // native 层通过 [THINK_END] 标记结束思考
    private boolean thinkingEnabled = false;
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
     * 设置思考标签（来自 chat template，不硬编码）。
     * 调用方从 native meta 事件或 {@code LlamaHelper.getThinkingTags()} 获取后注入。
     * 传 null 等同于清空（按"该模型无思考段"处理）。
     */
    public void setThinkingTags(ThinkingTagConfig config) {
        this.tagConfig = config != null ? config : ThinkingTagConfig.empty();
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

        // 检查是否是工具调用标签
        if (token.contains("<tool_call>")) {
            isInToolCall = true;
            return;
        }

        if (token.contains("</tool_call>")) {
            isInToolCall = false;
            // 解析工具调用
            String jsonContent = extractBetweenTags(fullContentBuffer.toString(), "<tool_call>", "</tool_call>");
            if (jsonContent != null) {
                try {
                    JSONObject jsonData = new JSONObject(jsonContent);
                    String toolName = jsonData.optString("name", "unknown");
                    JSONObject params = jsonData.optJSONObject("parameters");
                    handler.onToolCall(toolName, params);
                } catch (Exception e) {
                    AILogger.w(TAG, "Failed to parse tool call: " + e.getMessage());
                }
            }
            return;
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

        // 普通文本
        textBuffer.append(token);
        handler.onTextOutput(token, false);
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
        handler.onTextOutput(textBuffer.toString(), true);
        handler.onStreamComplete(fullContentBuffer.toString());
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

    private String extractDataType(String text) {
        int startIndex = text.indexOf("<数据类型=\"");
        int endIndex = text.indexOf("\"", startIndex + 11);

        if (startIndex >= 0 && endIndex > startIndex) {
            return text.substring(startIndex + 11, endIndex);
        }
        return "unknown";
    }
}
