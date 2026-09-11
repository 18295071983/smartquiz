package com.oilquiz.app.ai.chat.stream;

import android.os.Handler;
import android.os.Looper;

import com.oilquiz.app.ai.chat.ChatMessage;

import java.util.List;

/**
 * 生成流式编排控制器（全局可复用，逻辑层）。
 *
 * 从 AIChatActivity 流式链路抽取的状态机与缓冲逻辑：
 * 生成状态（begin/end/reset）、流式消息定位（resolveStreamingIndex）、
 * 标签缓冲解析（processTagBuffer）、安全更新（锁快照+节流）、
 * token 分流（thinking/tag/正文）。渲染通过 {@link Host} 回调，不依赖页面。
 */
public class GenerationStreamController {

    /** 渲染/UI 回调 */
    public interface Host {
        /** 主线程调度 */
        void runOnUi(Runnable r);
        /** 节流刷新消息内容（宿主调用 adapter 更新 + 滚动） */
        void onRefreshMessage(int index);
        /** 更新消息正文/思考内容（宿主写 msg 字段并渲染） */
        void onContentSnapshot(ChatMessage msg, String content, String thinking);
        /** 思考内容刷新（节流） */
        void onThinkingRefresh(int index);
        /** 流式 TTS 喂 token */
        void onFeedStreamingTts(String token);
        /** 状态更新：阶段/进度 */
        void onPhaseUpdate(int index, ChatMessage.InferencePhase phase, String info);
        void onProgressUpdate(int index, int tokens, float tps);
        /** 生成开始/结束（宿主同步按钮/指示器） */
        void onGenerationBegin();
        void onGenerationEnd();
        /** 统计刷新（宿主更新 Token 状态） */
        void onStatsUpdate(int totalTokens, float tps);
    }

    // 节流参数（与原页面一致）
    private static final int BATCH_TOKEN_COUNT = 5;
    private static final long BATCH_INTERVAL_MS = 120;
    private static final long UI_UPDATE_THROTTLE_MS = 80;

    private final Object streamingLock = new Object();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Host host;

    // 生成状态
    private volatile boolean isGenerating;
    private volatile boolean isDirectStreaming;
    private int totalTokensGenerated;
    private volatile String currentStreamingMessageId;
    private volatile int currentStreamingMessageIndex = -1;
    private StringBuilder currentStreamingContent;
    private StringBuilder currentThinkingContent;
    private volatile boolean isInThinking;
    private boolean isInTag;
    private StringBuilder tagBuffer;
    private int thinkingRoundCount = 1;
    private boolean thinkingRoundEnded;
    private int tokenCountSinceLastUpdate;
    private long lastUpdateTime;
    private boolean isUpdateScheduled;

    public GenerationStreamController(Host host) {
        this.host = host;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    // ==================== 生成状态 ====================

    public void beginGeneration() {
        isGenerating = true;
        isDirectStreaming = true;
        totalTokensGenerated = 0;
        host.onGenerationBegin();
    }

    public void endGeneration() {
        isGenerating = false;
        isDirectStreaming = false;
        cancelPendingScheduledUpdate();
        host.onGenerationEnd();
    }

    public boolean isGenerating() { return isGenerating; }

    public void setStreamingTarget(String messageId, int index) {
        currentStreamingMessageId = messageId;
        currentStreamingMessageIndex = index;
    }

    public String getStreamingMessageId() { return currentStreamingMessageId; }

    public void resetStreamingState() {
        tokenCountSinceLastUpdate = 0;
        lastUpdateTime = System.currentTimeMillis();
        isUpdateScheduled = false;
        uiHandler.removeCallbacksAndMessages(null);
    }

    public void clearStreamingTarget() {
        currentStreamingContent = null;
        currentStreamingMessageIndex = -1;
        currentStreamingMessageId = null;
        thinkingRoundEnded = false;
        thinkingRoundCount = 1;
    }

    public void resetThinkingFlags() {
        isInThinking = false;
        isInTag = false;
        tagBuffer = null;
        thinkingRoundCount = 1;
        thinkingRoundEnded = false;
    }

    /** 启动新一轮正文流式内容 */
    public void startContentBuffer() {
        synchronized (streamingLock) {
            currentStreamingContent = new StringBuilder();
        }
    }

    // ==================== 消息定位 ====================

    public static int findMessageIndexById(List<ChatMessage> history, String messageId) {
        if (history == null || messageId == null) return -1;
        for (int i = 0; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            if (m != null && messageId.equals(m.id)) return i;
        }
        return -1;
    }

    public static int findToolCallMessageById(List<ChatMessage> history, String toolCallId) {
        if (history == null || toolCallId == null) return -1;
        for (int i = 0; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            if (m != null && m.toolCallInfo != null && toolCallId.equals(m.toolCallInfo.toolCallId)) return i;
        }
        return -1;
    }

    public static int findLastSpecialMessage(List<ChatMessage> history, ChatMessage.MessageType type) {
        if (history == null) return -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if (m != null && m.type == type) return i;
        }
        return -1;
    }

    /** 解析当前流式 AI 消息位置（优先按 id 抗插入漂移；id 为空时不可用） */
    public int resolveStreamingIndex(List<ChatMessage> history) {
        if (currentStreamingMessageId == null) return -1;
        int byId = findMessageIndexById(history, currentStreamingMessageId);
        if (byId >= 0) return byId;
        return (currentStreamingMessageIndex >= 0 && currentStreamingMessageIndex < history.size())
                ? currentStreamingMessageIndex : -1;
    }

    // ==================== token 分流 ====================

    /**
     * 旧式 token 处理（兼容回退）：[TOOL_CALL] 跳过 / [THINK_END] 收束思考 /
     * 标签缓冲累积 / 思考区累积 / 正文累积 + 节流刷新。
     */
    public void handleStreamTokenLegacy(String token, List<ChatMessage> history) {
        if (token == null) return;
        if ("[TOOL_CALL]".equals(token)) return;
        if ("[THINK_END]".equals(token)) {
            isInThinking = false;
            final int idx = resolveStreamingIndex(history);
            if (idx >= 0) {
                String thinkingSnapshot;
                synchronized (streamingLock) {
                    thinkingSnapshot = currentThinkingContent != null ? currentThinkingContent.toString() : "";
                }
                ChatMessage msg = history.get(idx);
                msg.thinkingContent = thinkingSnapshot;
                host.onThinkingRefresh(idx);
            }
            return;
        }
        if (token.contains("<")) {
            isInTag = true;
            if (tagBuffer == null) tagBuffer = new StringBuilder();
            tagBuffer.append(token);
            if (token.contains(">")) processTagBuffer(tagBuffer.toString());
            return;
        }
        if (isInTag) {
            if (tagBuffer != null) tagBuffer.append(token);
            if (token.contains(">")) processTagBuffer(tagBuffer.toString());
            return;
        }
        if (isInThinking) {
            String thinkingSnapshot;
            synchronized (streamingLock) {
                if (currentThinkingContent == null) return;
                currentThinkingContent.append(token);
                thinkingSnapshot = currentThinkingContent.toString();
            }
            final int idx = resolveStreamingIndex(history);
            if (idx >= 0) {
                ChatMessage msg = history.get(idx);
                msg.thinkingContent = thinkingSnapshot;
                host.onThinkingRefresh(idx);
            }
            return;
        }
        synchronized (streamingLock) {
            if (currentStreamingContent != null) {
                currentStreamingContent.append(token);
                totalTokensGenerated++;
            }
        }
        host.onFeedStreamingTts(token);
        scheduleStreamingUpdate(history);
    }

    /** 标签缓冲解析：按配置切换思考状态（模板未提供标签时不误判） */
    public void processTagBuffer(String tagContent) {
        if (tagContent == null) return;
        // 简版判定：think 起止标签（宿主可传自定义配置）
        if (tagContent.contains("<think>")) {
            isInThinking = true;
        } else if (tagContent.contains("</think>")) {
            isInThinking = false;
        }
        if (tagBuffer != null) tagBuffer.setLength(0);
        isInTag = false;
    }

    private void scheduleStreamingUpdate(List<ChatMessage> history) {
        long now = System.currentTimeMillis();
        long timeSinceLastUpdate = now - lastUpdateTime;
        boolean shouldUpdateNow = tokenCountSinceLastUpdate >= BATCH_TOKEN_COUNT
                || timeSinceLastUpdate >= BATCH_INTERVAL_MS || !isUpdateScheduled;
        if (shouldUpdateNow) {
            safeUpdateMessage(history);
            tokenCountSinceLastUpdate = 0;
            lastUpdateTime = now;
            isUpdateScheduled = false;
        } else if (!isUpdateScheduled) {
            isUpdateScheduled = true;
            long delay = BATCH_INTERVAL_MS - timeSinceLastUpdate;
            uiHandler.postDelayed(() -> {
                if (isUpdateScheduled) {
                    safeUpdateMessage(history);
                    tokenCountSinceLastUpdate = 0;
                    lastUpdateTime = System.currentTimeMillis();
                    isUpdateScheduled = false;
                }
            }, delay);
        }
    }

    /** 安全更新：锁快照 + 内容/思考节流去重（防并发 StringBuilder 错乱与重复渲染） */
    public void safeUpdateMessage(List<ChatMessage> history) {
        try {
            final int idx = resolveStreamingIndex(history);
            if (idx < 0) return;
            String contentSnapshot;
            String thinkingSnapshot = null;
            synchronized (streamingLock) {
                if (currentStreamingContent == null) return;
                contentSnapshot = currentStreamingContent.toString();
                if (currentThinkingContent != null && currentThinkingContent.length() > 0) {
                    thinkingSnapshot = currentThinkingContent.toString();
                }
            }
            ChatMessage msg = history.get(idx);
            boolean thinkingSame = (thinkingSnapshot == null)
                    || (msg.thinkingContent != null && thinkingSnapshot.equals(msg.thinkingContent));
            if (contentSnapshot.equals(msg.content) && thinkingSame) {
                return;
            }
            msg.content = contentSnapshot;
            msg.status = ChatMessage.MessageStatus.GENERATING;
            if (thinkingSnapshot != null) msg.thinkingContent = thinkingSnapshot;
            host.onContentSnapshot(msg, contentSnapshot, thinkingSnapshot);
        } catch (IndexOutOfBoundsException e) {
            currentStreamingMessageIndex = -1;
        }
    }

    /** 追加正文 token（Agent 回调侧）并节流刷新 */
    public void appendContentToken(String token, List<ChatMessage> history) {
        synchronized (streamingLock) {
            if (currentStreamingContent != null) {
                currentStreamingContent.append(token);
            }
        }
        host.onFeedStreamingTts(token);
        long now = System.currentTimeMillis();
        if (now - lastUpdateTime >= UI_UPDATE_THROTTLE_MS) {
            lastUpdateTime = now;
            host.runOnUi(() -> safeUpdateMessage(history));
        }
    }

    // ==================== Agent 思考轮次 ====================

    /** 新一轮思考开始（轮次切换时先保存上一轮） */
    public void startThinkingRound(List<ChatMessage> history) {
        if (thinkingRoundEnded || currentThinkingContent == null) {
            if (currentThinkingContent != null && currentThinkingContent.length() > 0) {
                final int curIdx = resolveStreamingIndex(history);
                if (curIdx >= 0 && curIdx < history.size()) {
                    history.get(curIdx).addThinkingRound(currentThinkingContent.toString());
                }
            }
            thinkingRoundEnded = false;
            thinkingRoundCount++;
            synchronized (streamingLock) {
                currentThinkingContent = new StringBuilder();
            }
        }
    }

    public void appendThinkingToken(String token, List<ChatMessage> history) {
        synchronized (streamingLock) {
            if (currentThinkingContent == null) currentThinkingContent = new StringBuilder();
            currentThinkingContent.append(token);
        }
        final int idx = resolveStreamingIndex(history);
        if (idx >= 0) {
            String snapshot;
            synchronized (streamingLock) {
                snapshot = currentThinkingContent.toString();
            }
            ChatMessage msg = history.get(idx);
            msg.thinkingContent = snapshot;
            msg.thinkingExpanded = false;
            host.onThinkingRefresh(idx);
        }
    }

    /** 收束思考：快照写入消息 + 折叠 */
    public void finalizeAgentThinking(List<ChatMessage> history) {
        final int idx = resolveStreamingIndex(history);
        if (idx < 0) return;
        String snapshot;
        synchronized (streamingLock) {
            snapshot = currentThinkingContent != null ? currentThinkingContent.toString() : "";
        }
        ChatMessage msg = history.get(idx);
        if (msg == null) return;
        if (!snapshot.isEmpty()) {
            msg.thinkingContent = snapshot;
            msg.addThinkingRound(snapshot);
        }
        msg.thinkingExpanded = false;
        host.onRefreshMessage(idx);
    }

    private void cancelPendingScheduledUpdate() {
        isUpdateScheduled = false;
    }

    /** 状态更新便捷方法 */
    public void updatePhase(int index, ChatMessage.InferencePhase phase, String info) {
        host.onPhaseUpdate(index, phase, info);
    }

    public void updateProgress(int index, int tokens, float tps) {
        host.onProgressUpdate(index, tokens, tps);
    }
}
