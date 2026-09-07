package com.oilquiz.app.ai.chat.streaming;

import android.util.Log;

import com.oilquiz.app.ai.chat.parser.ThinkingTagConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式 Token 管道：接收原始 token，分离 thinking/content，调度批量 UI 更新。
 * 从 AIChatActivity 中提取的独立模块。
 */
public class StreamingTokenPipeline {

    private static final String TAG = "StreamingTokenPipeline";

    public interface TokenListener {
        void onContentToken(String token);
        void onThinkingToken(String token);
        void onToolCall(String toolCallData);
        void onGenerationComplete(String fullContent);
    }

    private final TokenListener listener;
    private final StringBuilder contentBuffer = new StringBuilder();
    private final StringBuilder thinkingBuffer = new StringBuilder();
    private boolean isInThinking = false;
    /** 模板思考标签：来自 chat template（LlamaHelper.getThinkingTags()），不硬编码 */
    private ThinkingTagConfig tagConfig = ThinkingTagConfig.empty();
    /** 跨 token 标签边界缓冲：标签可能被 tokenizer 拆开，凑齐后再判定，避免漏检 */
    private final StringBuilder tagLookahead = new StringBuilder();

    public StreamingTokenPipeline(TokenListener listener) {
        this.listener = listener;
    }

    /**
     * 注入模板思考标签（不硬编码）。调用方从 LlamaHelper.getThinkingTags() 获取后注入。
     */
    public void setThinkingTags(ThinkingTagConfig config) {
        this.tagConfig = config != null ? config : ThinkingTagConfig.empty();
    }

    /**
     * 处理单个 token
     */
    public void processToken(String token) {
        if (token == null || token.isEmpty()) return;

        // Check for tool call
        if (token.contains("[TOOL_CALL]")) {
            if (listener != null) listener.onToolCall(token);
            return;
        }

        // 思考标签可能跨 token 到达（如 <|thinking_start|> 被 tokenizer 拆开），
        // 先合并到 tagLookahead 缓冲；若尾部是某标签的前缀则等后续 token 凑齐，避免漏检。
        tagLookahead.append(token);
        String buf = tagLookahead.toString();
        if (possibleTagPrefix(buf) != null) {
            return;   // 标签尚未到齐，保留缓冲等待下一个 token
        }
        tagLookahead.setLength(0);

        // 思考标签来自 chat template（tagConfig），不硬编码 <think>/</think>/<|thinking_*|>
        if (tagConfig.isAvailable()) {
            String start = tagConfig.getStartTag();
            if (buf.contains(start)) {
                isInThinking = true;
                int s = buf.indexOf(start);
                String before = buf.substring(0, s);
                if (!before.isEmpty()) {
                    contentBuffer.append(before);
                    if (listener != null) listener.onContentToken(before);
                }
                String rest = buf.substring(s + start.length());
                String end = firstEndTagIn(rest);
                if (end != null) {
                    int e = rest.indexOf(end);
                    String t = rest.substring(0, e);
                    if (!t.isEmpty()) {
                        thinkingBuffer.append(t);
                        if (listener != null) listener.onThinkingToken(t);
                    }
                    isInThinking = false;
                    String after = rest.substring(e + end.length());
                    if (!after.isEmpty()) {
                        contentBuffer.append(after);
                        if (listener != null) listener.onContentToken(after);
                    }
                } else {
                    if (!rest.isEmpty()) {
                        thinkingBuffer.append(rest);
                        if (listener != null) listener.onThinkingToken(rest);
                    }
                }
                return;
            }
            String end = firstEndTagIn(buf);
            if (end != null) {
                int e = buf.indexOf(end);
                String before = buf.substring(0, e);
                if (!before.isEmpty()) {
                    thinkingBuffer.append(before);
                    if (listener != null) listener.onThinkingToken(before);
                }
                isInThinking = false;
                String after = buf.substring(e + end.length());
                if (!after.isEmpty()) {
                    contentBuffer.append(after);
                    if (listener != null) listener.onContentToken(after);
                }
                return;
            }
        }

        if (isInThinking) {
            thinkingBuffer.append(token);
            if (listener != null) listener.onThinkingToken(token);
        } else {
            contentBuffer.append(token);
            if (listener != null) listener.onContentToken(token);
        }
    }

    /**
     * 通知生成完成
     */
    public void complete() {
        if (listener != null) {
            listener.onGenerationComplete(contentBuffer.toString());
        }
    }

    /**
     * 获取当前内容
     */
    public String getContent() {
        return contentBuffer.toString();
    }

    /**
     * 获取当前思考内容
     */
    public String getThinkingContent() {
        return thinkingBuffer.toString();
    }

    /**
     * 重置管道状态
     */
    public void reset() {
        contentBuffer.setLength(0);
        thinkingBuffer.setLength(0);
        isInThinking = false;
        tagLookahead.setLength(0);
    }

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
     * 若 text 尾部是某个思考标签（开始/结束）的前缀，返回该后缀，表示标签尚未到齐、需等待
     * 后续 token 凑齐；否则返回 null。用于解决标签跨 token 漏检。
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
                if (!tag.isEmpty() && tag.startsWith(suffix)) {
                    return suffix;
                }
            }
        }
        return null;
    }

    public boolean isInThinking() {
        return isInThinking;
    }
}
