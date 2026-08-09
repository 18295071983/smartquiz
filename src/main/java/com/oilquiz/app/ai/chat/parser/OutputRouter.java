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

    private static final String THINK_END_MARKER = "[THINK_END]";

    private final OutputHandler handler;
    private final StringBuilder textBuffer;
    private final StringBuilder thinkingBuffer;
    private final StringBuilder fullContentBuffer;

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

        // 检查是否是思考标签 <think>（模型自发输出的情况）
        if (token.contains("<think>")) {
            if (!isInThinking) {
                isInThinking = true;
                thinkingStarted = true;
                handler.onThinkingStart();
            }
            String content = extractAfterTag(token, "<think>");
            if (!content.isEmpty()) {
                thinkingBuffer.append(content);
                handler.onThinkingContent(content);
            }
            return;
        }

        if (token.contains("</think>")) {
            String content = extractBeforeTag(token, "</think>");
            if (!content.isEmpty()) {
                thinkingBuffer.append(content);
                handler.onThinkingContent(content);
            }
            if (isInThinking) {
                isInThinking = false;
                handler.onThinkingEnd();
            }
            return;
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
