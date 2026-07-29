package com.oilquiz.app.ai.agent;

import java.util.ArrayList;
import java.util.List;

public class AgentExecutionState {

    public enum ExecutionStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public enum BlockType {
        HEADER,
        THINKING,
        PLANNING,
        TOOL_CALL,
        TOOL_RESULT,
        INFERENCE,
        OBSERVATION,
        REFLECTION,
        TEXT,
        STATS,
        ERROR
    }

    private String messageId;
    private ExecutionStatus status;
    private long startTime;
    private long endTime;
    private int totalSteps;
    private int currentStep;
    private int totalTokens;
    private float tokensPerSecond;

    private final List<ExecutionBlock> blocks = new ArrayList<>();
    private final List<LogEntry> logs = new ArrayList<>();

    private String finalAnswer;
    private String errorMessage;

    // 版本号 - 每次数据变化时递增，用于UI增量更新检测
    private volatile long version = 0;
    // 已渲染到的block索引，用于增量更新
    private volatile int renderedBlockCount = 0;

    public AgentExecutionState(String messageId) {
        this.messageId = messageId;
        this.status = ExecutionStatus.PENDING;
        this.startTime = System.currentTimeMillis();
        this.totalSteps = 0;
        this.currentStep = 0;
        this.totalTokens = 0;
        this.tokensPerSecond = 0;
    }

    /** 获取当前版本号 */
    public long getVersion() { return version; }

    /** 数据已变化，递增版本号 */
    private void bumpVersion() { version++; }

    public synchronized void start() {
        this.status = ExecutionStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
        bumpVersion();
    }

    public synchronized void complete(String answer) {
        this.status = ExecutionStatus.COMPLETED;
        this.endTime = System.currentTimeMillis();
        this.finalAnswer = answer;
        bumpVersion();
    }

    public synchronized void fail(String error) {
        this.status = ExecutionStatus.FAILED;
        this.endTime = System.currentTimeMillis();
        this.errorMessage = error;
        bumpVersion();
    }

    public synchronized void cancel() {
        this.status = ExecutionStatus.CANCELLED;
        this.endTime = System.currentTimeMillis();
        bumpVersion();
    }

    /**
     * 重置数据结构 - 用于异常恢复或重新开始执行
     * 清除所有执行块、日志、统计信息，恢复到初始状态
     */
    public synchronized void reset() {
        this.status = ExecutionStatus.PENDING;
        this.startTime = System.currentTimeMillis();
        this.endTime = 0;
        this.totalSteps = 0;
        this.currentStep = 0;
        this.totalTokens = 0;
        this.tokensPerSecond = 0;
        this.finalAnswer = null;
        this.errorMessage = null;
        this.blocks.clear();
        this.logs.clear();
        this.renderedBlockCount = 0;
        bumpVersion();
    }

    public synchronized ExecutionBlock addBlock(BlockType type, String title, String content) {
        ExecutionBlock block = new ExecutionBlock(type, title, content);
        block.setStepNumber(blocks.size() + 1);
        blocks.add(block);
        totalSteps = blocks.size();
        currentStep = totalSteps;
        bumpVersion();
        return block;
    }

    public synchronized void updateBlockContent(int blockIndex, String content) {
        if (blockIndex >= 0 && blockIndex < blocks.size()) {
            blocks.get(blockIndex).setContent(content);
            bumpVersion();
        }
    }

    public synchronized void appendBlockContent(int blockIndex, String text) {
        if (blockIndex >= 0 && blockIndex < blocks.size()) {
            ExecutionBlock block = blocks.get(blockIndex);
            block.setContent((block.getContent() != null ? block.getContent() : "") + text);
            bumpVersion();
        }
    }

    public synchronized void setBlockStatus(int blockIndex, ExecutionBlock.BlockStatus blockStatus) {
        if (blockIndex >= 0 && blockIndex < blocks.size()) {
            blocks.get(blockIndex).setStatus(blockStatus);
            bumpVersion();
        }
    }

    public synchronized int getCurrentBlockIndex() {
        return blocks.size() - 1;
    }

    public synchronized ExecutionBlock getBlock(int index) {
        if (index >= 0 && index < blocks.size()) {
            return blocks.get(index);
        }
        return null;
    }

    public synchronized void addLog(String type, String content) {
        logs.add(new LogEntry(type, content, System.currentTimeMillis()));
        if (logs.size() > 200) {
            logs.remove(0);
        }
        bumpVersion();
    }

    public synchronized void updateTokenStats(int tokens, float tps) {
        this.totalTokens = tokens;
        this.tokensPerSecond = tps;
        bumpVersion();
    }

    /** 获取blocks的快照（线程安全） */
    public synchronized List<ExecutionBlock> getBlocksSnapshot() {
        return new ArrayList<>(blocks);
    }

    public String getMessageId() { return messageId; }
    public ExecutionStatus getStatus() { return status; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }
    public int getTotalSteps() { return totalSteps; }
    public int getCurrentStep() { return currentStep; }
    public int getTotalTokens() { return totalTokens; }
    public float getTokensPerSecond() { return tokensPerSecond; }
    public List<ExecutionBlock> getBlocks() { return new ArrayList<>(blocks); }
    public List<LogEntry> getLogs() { return new ArrayList<>(logs); }
    public String getFinalAnswer() { return finalAnswer; }
    public String getErrorMessage() { return errorMessage; }

    public long getElapsedTime() {
        if (status == ExecutionStatus.RUNNING || status == ExecutionStatus.PENDING) {
            return System.currentTimeMillis() - startTime;
        }
        return endTime - startTime;
    }

    public static class ExecutionBlock {
        public enum BlockStatus {
            PENDING,
            RUNNING,
            COMPLETED,
            FAILED
        }

        private final BlockType type;
        private String title;
        private String content;
        private int stepNumber;
        private BlockStatus status;
        private long createdAt;
        private long completedAt;
        private String toolName;
        private String toolArgs;
        private boolean toolSuccess;
        private String toolResult;

        public ExecutionBlock(BlockType type, String title, String content) {
            this.type = type;
            this.title = title;
            this.content = content;
            this.status = BlockStatus.RUNNING;
            this.createdAt = System.currentTimeMillis();
        }

        public BlockType getType() { return type; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public int getStepNumber() { return stepNumber; }
        public void setStepNumber(int stepNumber) { this.stepNumber = stepNumber; }
        public BlockStatus getStatus() { return status; }
        public void setStatus(BlockStatus status) {
            this.status = status;
            if (status == BlockStatus.COMPLETED || status == BlockStatus.FAILED) {
                this.completedAt = System.currentTimeMillis();
            }
        }
        public long getCreatedAt() { return createdAt; }
        public long getCompletedAt() { return completedAt; }
        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
        public String getToolArgs() { return toolArgs; }
        public void setToolArgs(String toolArgs) { this.toolArgs = toolArgs; }
        public boolean isToolSuccess() { return toolSuccess; }
        public void setToolSuccess(boolean toolSuccess) { this.toolSuccess = toolSuccess; }
        public String getToolResult() { return toolResult; }
        public void setToolResult(String toolResult) { this.toolResult = toolResult; }

        public long getDuration() {
            if (completedAt > 0) return completedAt - createdAt;
            return System.currentTimeMillis() - createdAt;
        }
    }

    public static class LogEntry {
        public final String type;
        public final String content;
        public final long timestamp;

        public LogEntry(String type, String content, long timestamp) {
            this.type = type;
            this.content = content;
            this.timestamp = timestamp;
        }
    }
}