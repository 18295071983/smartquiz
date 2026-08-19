package com.oilquiz.app.ai.agent.online;

import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 在线模型思考链管理器。
 * 独立于本地 ThinkingChainManager，
 * 管理多轮推理的 reasoning_content 流式累积、工具调用记录和分块展示。
 *
 * 线程安全：reasoning token 在主线程回调，工具调用/块控制在 executor 线程调用，
 * 因此 blocks/activeBlock 的访问均通过 synchronized 保护，listeners 使用
 * {@link CopyOnWriteArrayList} 保证遍历安全。
 */
public class OnlineThinkingChain {

    private static final String TAG = "OnlineThinkingChain";

    private final List<ThinkingBlock> blocks = new ArrayList<>();
    private ThinkingBlock activeBlock;
    private final List<OnThinkingChainListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 思考块 —— 对应一轮推理
     */
    public static class ThinkingBlock {
        public final int iteration;
        public String reasoningContent;
        public final List<ToolCallRecord> toolCalls = new ArrayList<>();
        public boolean isCompleted;

        public ThinkingBlock(int iteration) {
            this.iteration = iteration;
            this.reasoningContent = "";
            this.isCompleted = false;
        }
    }

    /**
     * 工具调用记录
     */
    public static class ToolCallRecord {
        public final String toolName;
        public final String arguments;
        public final boolean success;
        public final String resultSummary;

        public ToolCallRecord(String toolName, String arguments, boolean success, String resultSummary) {
            this.toolName = toolName;
            this.arguments = arguments;
            this.success = success;
            this.resultSummary = resultSummary;
        }
    }

    /**
     * 监听器接口
     */
    public interface OnThinkingChainListener {
        void onBlockStarted(ThinkingBlock block);
        void onReasoningToken(ThinkingBlock block, String token);
        void onBlockCompleted(ThinkingBlock block);
        void onToolCallAppended(ThinkingBlock block, ToolCallRecord record);
    }

    public void addListener(OnThinkingChainListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(OnThinkingChainListener listener) {
        listeners.remove(listener);
    }

    /**
     * 开始新一轮思考块
     */
    public synchronized void startNewBlock(int iteration) {
        if (activeBlock != null && !activeBlock.isCompleted) {
            activeBlock.isCompleted = true;
            for (OnThinkingChainListener l : listeners) {
                l.onBlockCompleted(activeBlock);
            }
        }
        activeBlock = new ThinkingBlock(iteration);
        blocks.add(activeBlock);
        AILogger.d(TAG, "Started thinking block #" + iteration);
        for (OnThinkingChainListener l : listeners) {
            l.onBlockStarted(activeBlock);
        }
    }

    /**
     * 追加 reasoning_content token
     */
    public synchronized void appendReasoningToken(String token) {
        if (activeBlock == null) {
            startNewBlock(1);
        }
        activeBlock.reasoningContent += token;
        for (OnThinkingChainListener l : listeners) {
            l.onReasoningToken(activeBlock, token);
        }
    }

    /**
     * 在当前思考块中追加工具调用记录
     */
    public synchronized void appendToolCall(String toolName, String arguments, boolean success, String resultSummary) {
        if (activeBlock == null) {
            startNewBlock(1);
        }
        ToolCallRecord record = new ToolCallRecord(toolName, arguments, success, resultSummary);
        activeBlock.toolCalls.add(record);
        for (OnThinkingChainListener l : listeners) {
            l.onToolCallAppended(activeBlock, record);
        }
    }

    /**
     * 完成当前思考块
     */
    public synchronized void completeActiveBlock() {
        if (activeBlock != null && !activeBlock.isCompleted) {
            activeBlock.isCompleted = true;
            for (OnThinkingChainListener l : listeners) {
                l.onBlockCompleted(activeBlock);
            }
        }
    }

    /**
     * 完成所有思考块
     */
    public synchronized void completeAll() {
        completeActiveBlock();
        activeBlock = null;
    }

    /**
     * 清空所有思考块
     */
    public synchronized void clear() {
        blocks.clear();
        activeBlock = null;
    }

    public synchronized List<ThinkingBlock> getBlocks() {
        return new ArrayList<>(blocks);
    }

    public synchronized ThinkingBlock getActiveBlock() {
        return activeBlock;
    }

    /**
     * 获取完整思考链文本（用于日志/调试）
     */
    public synchronized String getFullText() {
        StringBuilder sb = new StringBuilder();
        for (ThinkingBlock block : blocks) {
            sb.append("=== 推理轮次 ").append(block.iteration).append(" ===\n");
            if (block.reasoningContent != null && !block.reasoningContent.isEmpty()) {
                sb.append("[思考] ").append(block.reasoningContent).append("\n");
            }
            for (ToolCallRecord tc : block.toolCalls) {
                sb.append("[工具] ").append(tc.toolName)
                  .append(" → ").append(tc.success ? "成功" : "失败")
                  .append(": ").append(tc.resultSummary).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 构建最近一轮推理的紧凑摘要（供回注下一轮，提升跨轮推理连贯性）。
     * 包含：本轮思考要点（尾部最近内容）+ 工具调用结论。
     * 控制总长不超过 maxChars，避免上下文膨胀。
     */
    public synchronized String buildRecentSummary(int maxChars) {
        if (blocks.isEmpty()) return null;
        ThinkingBlock last = blocks.get(blocks.size() - 1);
        StringBuilder sb = new StringBuilder();
        // 思考要点：取推理内容尾部（最近的思考，避免超长）
        if (last.reasoningContent != null && !last.reasoningContent.trim().isEmpty()) {
            String think = last.reasoningContent.trim();
            if (think.length() > 300) {
                think = "…" + think.substring(think.length() - 300);
            }
            sb.append("本轮已思考：").append(think);
        }
        // 工具结论
        for (ToolCallRecord tc : last.toolCalls) {
            String summary = tc.resultSummary;
            if (summary != null && summary.length() > 120) {
                summary = summary.substring(0, 120) + "…";
            }
            sb.append("\n工具[").append(tc.toolName).append("] ")
              .append(tc.success ? "成功" : "失败").append(": ")
              .append(summary != null ? summary : "");
        }
        String result = sb.toString().trim();
        if (result.isEmpty()) return null;
        if (result.length() > maxChars) {
            result = result.substring(0, maxChars);
        }
        return result;
    }
}
