package com.oilquiz.app.ai.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 聊天消息类 - 完整设计
 *
 * 支持的消息类型：
 * - USER: 用户消息
 * - AI: AI回复消息
 * - SYSTEM: 系统消息
 * - THINKING: 思考过程消息
 * - TASK_BREAKDOWN: 任务分解消息
 * - TASK_PROGRESS: 任务执行进度消息
 *
 * 特点：
 * - 支持思考过程可视化
 * - 支持任务分解步骤显示
 * - 支持附件
 * - 支持消息状态跟踪
 */
public class ChatMessage {

    /** 消息类型枚举 */
    public enum MessageType {
        USER,
        AI,
        SYSTEM,
        THINKING,
        TASK_BREAKDOWN,
        TASK_PROGRESS,
        TOOL_CALL,
        TOOL_RESULT,
        AGENT_STEP,
        AGENT_REFLECTION,
        SUMMARY,
        ERROR
    }

    /** 消息状态 */
    public enum MessageStatus {
        /** 发送中 */
        SENDING,
        /** 已发送 */
        SENT,
        /** 已送达 */
        DELIVERED,
        /** 已读 */
        READ,
        /** 生成中 */
        GENERATING,
        /** 进行中 */
        IN_PROGRESS,
        /** 完成 */
        COMPLETED,
        /** 失败 */
        FAILED,
        /** 暂停 */
        PAUSED,
        /** 错误 */
        ERROR
    }

    /** 思考步骤状态 */
    public enum ThinkingStepStatus {
        /** 等待执行 */
        PENDING,
        /** 执行中 */
        IN_PROGRESS,
        /** 完成 */
        COMPLETED,
        /** 跳过 */
        SKIPPED,
        /** 失败 */
        FAILED
    }

    /** 推理阶段 - 用于显示详细进度 */
    public enum InferencePhase {
        IDLE("空闲", "💤"),
        INITIALIZING("正在初始化...", "⚙️"),
        LOADING_MODEL("正在加载模型...", "📦"),
        ENCODING("正在编码输入...", "🔄"),
        PREFILL("正在处理上下文...", "📝"),
        THINKING("正在思考...", "🤔"),
        GENERATING("正在生成回复...", "✍️"),
        DECODING("正在解码...", "📤"),
        COMPLETED("已完成", "✅"),
        FAILED("生成失败", "❌"),
        FALLBACK_TO_CPU("GPU不可用，切换到CPU...", "🔄"),
        WAITING("等待中...", "⏳");

        private final String displayText;
        private final String emoji;

        InferencePhase(String displayText, String emoji) {
            this.displayText = displayText;
            this.emoji = emoji;
        }

        public String getDisplayText() {
            return displayText;
        }

        public String getEmoji() {
            return emoji;
        }

        public String getFullDisplay() {
            return emoji + " " + displayText;
        }

        public boolean isProcessing() {
            return this != IDLE && this != COMPLETED && this != FAILED;
        }
    }

    /** 推理进度信息 */
    public static class InferenceProgress {
        public InferencePhase phase;
        public int totalTokens;
        public int processedTokens;
        public float tokensPerSecond;
        public long estimatedRemainingMs;
        public int overallProgress;
        public String additionalInfo;

        public InferenceProgress() {
            this.phase = InferencePhase.IDLE;
            this.totalTokens = 0;
            this.processedTokens = 0;
            this.tokensPerSecond = 0;
            this.estimatedRemainingMs = 0;
            this.overallProgress = 0;
            this.additionalInfo = "";
        }

        public InferenceProgress(InferencePhase phase) {
            this.phase = phase;
            this.totalTokens = 0;
            this.processedTokens = 0;
            this.tokensPerSecond = 0;
            this.estimatedRemainingMs = 0;
            this.overallProgress = 0;
            this.additionalInfo = "";
        }

        public String getEstimatedTimeFormatted() {
            if (estimatedRemainingMs <= 0) return "";
            long seconds = (estimatedRemainingMs + 500) / 1000;
            if (seconds < 60) return seconds + "秒";
            return (seconds / 60) + "分" + (seconds % 60) + "秒";
        }

        public int getPercentage() {
            if (totalTokens <= 0) return 0;
            return Math.min(100, (processedTokens * 100) / totalTokens);
        }
    }

    /** 消息唯一ID */
    public String id;

    /** 父消息ID（用于关联思考过程和回复） */
    public String parentId;

    /** 时间戳 */
    public long timestamp;

    /** 消息类型 */
    public MessageType type;

    /** 消息状态 */
    public MessageStatus status;

    /** 消息内容 */
    public String content;

    /** 思考步骤列表 */
    public List<ThinkingStep> thinkingSteps;

    /** 思考内容文本（流式思考过程的纯文本） */
    public String thinkingContent;

    /** 附件列表 */
    public List<Attachment> attachments;

    /** 相关任务ID */
    public String taskId;

    /** Token数量 */
    public int tokensGenerated;

    /** 生成耗时（毫秒） */
    public long generationTimeMs;

    /** 系统消息类型 */
    public SystemMessageType systemType;

    /** 工具调用信息 */
    public ToolCallInfo toolCallInfo;

    /** Agent步骤信息 */
    public AgentStepInfo agentStepInfo;

    /** 错误信息 */
    public String errorDetail;

    /** 是否可重试 */
    public boolean retryable;

    /** Agent反思信息 */
    public AgentReflectionInfo agentReflectionInfo;

    /** 总结信息 */
    public SummaryInfo summaryInfo;

    /** 任务进度（0-100） */
    public Integer taskProgress;

    /** 推理进度信息 */
    public InferenceProgress inferenceProgress;

    /** 是否使用GPU加速 */
    public boolean usingGPU;

    /** GPU层数（如果使用GPU） */
    public int gpuLayers;

    /** 是否展开（用于长消息折叠） */
    public boolean isExpanded;

    public static class ToolCallInfo {
        public String toolName;
        public String toolDisplayName;
        public String toolIcon;
        public String parameters;
        public String result;
        public ToolCallStatus status;
        public long executionTimeMs;

        public enum ToolCallStatus {
            PENDING,
            RUNNING,
            COMPLETED,
            FAILED
        }

        public ToolCallInfo(String toolName, String parameters) {
            this.toolName = toolName;
            this.toolDisplayName = formatToolDisplayName(toolName);
            this.toolIcon = getToolIcon(toolName);
            this.parameters = parameters;
            this.status = ToolCallStatus.PENDING;
        }

        public String getStatusText() {
            switch (status) {
                case PENDING: return "⏳ 等待执行";
                case RUNNING: return "🔄 执行中...";
                case COMPLETED: return "✅ 执行完成";
                case FAILED: return "❌ 执行失败";
                default: return "";
            }
        }

        private static String formatToolDisplayName(String name) {
            if (name == null) return "";
            switch (name) {
                case "get_weather": return "天气查询";
                case "network_search": return "网络搜索";
                case "database": return "数据库查询";
                case "file": return "文件操作";
                case "translation": return "翻译";
                case "app_operation": return "应用操作";
                case "generate_questions": return "生成题目";
                case "analyze_question": return "分析题目";
                case "search_questions": return "搜索题目";
                case "create_study_plan": return "创建学习计划";
                case "get_statistics": return "获取统计";
                default: return name.replace("_", " ");
            }
        }

        private static String getToolIcon(String name) {
            if (name == null) return "🔧";
            if (name.contains("weather")) return "🌤️";
            if (name.contains("search")) return "🔍";
            if (name.contains("database")) return "🗄️";
            if (name.contains("file")) return "📁";
            if (name.contains("translat")) return "🌐";
            if (name.contains("question") || name.contains("generat")) return "📝";
            if (name.contains("study") || name.contains("plan")) return "📚";
            if (name.contains("statist")) return "📊";
            if (name.contains("app")) return "📱";
            return "🔧";
        }
    }

    public static class AgentStepInfo {
        public AgentStepType stepType;
        public String thought;
        public String action;
        public String observation;
        public int iteration;
        public int totalIterations;
        public boolean isCompleted;

        public enum AgentStepType {
            THINKING,
            PLANNING,
            ACTING,
            OBSERVING,
            REFLECTING
        }

        public AgentStepInfo(AgentStepType stepType, int iteration, int totalIterations) {
            this.stepType = stepType;
            this.iteration = iteration;
            this.totalIterations = totalIterations;
            this.isCompleted = false;
        }

        public String getStepTypeLabel() {
            switch (stepType) {
                case THINKING: return "💭 思考";
                case PLANNING: return "📋 规划";
                case ACTING: return "⚙️ 行动";
                case OBSERVING: return "👁️ 观察";
                case REFLECTING: return "🔄 反思";
                default: return "📌 步骤";
            }
        }

        public String getStepIcon() {
            switch (stepType) {
                case THINKING: return "💭";
                case PLANNING: return "📋";
                case ACTING: return "⚙️";
                case OBSERVING: return "👁️";
                case REFLECTING: return "🔄";
                default: return "📌";
            }
        }
    }

    public static class AgentReflectionInfo {
        public String analysis;
        public String improvements;
        public boolean retrySuggested;
        public int score;
        public String suggestions;

        public AgentReflectionInfo(String analysis) {
            this.analysis = analysis;
            this.improvements = "";
            this.retrySuggested = false;
            this.score = 0;
        }
    }

    public static class SummaryInfo {
        public int stepsCount;
        public long totalTimeMs;
        public String keyPoints;
        public String nextSteps;

        public SummaryInfo() {
            this.stepsCount = 0;
            this.totalTimeMs = 0;
            this.keyPoints = "";
            this.nextSteps = "";
        }

        public SummaryInfo(int stepsCount, long totalTimeMs) {
            this.stepsCount = stepsCount;
            this.totalTimeMs = totalTimeMs;
            this.keyPoints = "";
            this.nextSteps = "";
        }
    }

    /**
     * 思考步骤类 - 表示AI思考过程中的单个步骤
     */
    public static class ThinkingStep {
        /** 步骤ID */
        public final String id;

        /** 步骤名称 */
        public final String name;

        /** 步骤详细描述 */
        public final String description;

        /** 步骤状态 */
        public ThinkingStepStatus status;

        /** 开始时间 */
        public long startTime;

        /** 结束时间 */
        public long endTime;

        /** 步骤结果 */
        public String result;

        /** 步骤类型 */
        public final ThinkingStepType stepType;

        public enum ThinkingStepType {
            /** 理解用户输入 */
            UNDERSTAND,
            /** 识别意图 */
            INTENT,
            /** 规划任务 */
            PLANNING,
            /** 分解任务 */
            DECOMPOSE,
            /** 执行任务 */
            EXECUTE,
            /** 索信息 */
            SEARCH,
            /** 分析数据 */
            ANALYSIS,
            /** 生成内容 */
            GENERATE,
            /** 验证结果 */
            VERIFY,
            /** 总结回答 */
            SUMMARIZE
        }

        public ThinkingStep(String name, String description, ThinkingStepType stepType) {
            this.id = UUID.randomUUID().toString();
            this.name = name;
            this.description = description;
            this.status = ThinkingStepStatus.PENDING;
            this.stepType = stepType;
        }

        public ThinkingStep withStatus(ThinkingStepStatus status) {
            this.status = status;
            return this;
        }

        public ThinkingStep withStartTime(long time) {
            this.startTime = time;
            return this;
        }

        public ThinkingStep withEndTime(long time) {
            this.endTime = time;
            return this;
        }

        public ThinkingStep withResult(String result) {
            this.result = result;
            return this;
        }

        public long getDuration() {
            if (endTime > 0 && startTime > 0) {
                return endTime - startTime;
            }
            return 0;
        }

        public String getEmoji() {
            switch (stepType) {
                case UNDERSTAND:
                    return "🔍";
                case INTENT:
                    return "🎯";
                case PLANNING:
                    return "📋";
                case DECOMPOSE:
                    return "📦";
                case EXECUTE:
                    return "⚙️";
                case SEARCH:
                    return "🔎";
                case ANALYSIS:
                    return "📊";
                case GENERATE:
                    return "✍️";
                case VERIFY:
                    return "✅";
                case SUMMARIZE:
                    return "📝";
                default:
                    return "💭";
            }
        }

        public String getStatusEmoji() {
            switch (status) {
                case PENDING:
                    return "⏳";
                case IN_PROGRESS:
                    return "🔄";
                case COMPLETED:
                    return "✅";
                case SKIPPED:
                    return "⏭️";
                case FAILED:
                    return "❌";
                default:
                    return "❓";
            }
        }
    }

    /**
     * 附件类
     */
    public static class Attachment {
        public final String id;
        public final String type;
        public final String url;
        public final String name;
        public final long size;
        public final String mimeType;

        public Attachment(String type, String url, String name) {
            this.id = UUID.randomUUID().toString();
            this.type = type;
            this.url = url;
            this.name = name;
            this.size = 0;
            this.mimeType = getMimeFromType(type);
        }

        public Attachment(String type, String url, String name, long size) {
            this.id = UUID.randomUUID().toString();
            this.type = type;
            this.url = url;
            this.name = name;
            this.size = size;
            this.mimeType = getMimeFromType(type);
        }

        private String getMimeFromType(String type) {
            if (type == null) return "application/octet-stream";
            String lowerType = type.toLowerCase();
            if (lowerType.contains("image")) return "image/*";
            if (lowerType.contains("video")) return "video/*";
            if (lowerType.contains("audio")) return "audio/*";
            if (lowerType.contains("pdf")) return "application/pdf";
            if (lowerType.contains("doc")) return "application/msword";
            return "application/octet-stream";
        }

        public String getDisplaySize() {
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
            if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0));
            return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
        }

        public String getEmoji() {
            if (type == null) return "📎";
            String lowerType = type.toLowerCase();
            if (lowerType.contains("image")) return "🖼️";
            if (lowerType.contains("video")) return "🎬";
            if (lowerType.contains("audio")) return "🎵";
            if (lowerType.contains("pdf")) return "📄";
            if (lowerType.contains("doc") || lowerType.contains("word")) return "📝";
            if (lowerType.contains("excel") || lowerType.contains("sheet")) return "📊";
            if (lowerType.contains("json")) return "📋";
            if (lowerType.contains("html")) return "🌐";
            return "📎";
        }
    }

    /**
     * 操作类型枚举
     */
    public enum ActionType {
        COPY,
        REGENERATE,
        NEW_CHAT,
        LIKE,
        DISLIKE,
        SHOW_HELP,
        SHOW_GUIDE,
        VIEW_TOOL_DETAILS,
        EXPORT_SUMMARY,
        REPORT_ERROR
    }

    /**
     * 用户操作类
     */
    public static class Action {
        public final ActionType type;
        public final String messageId;
        public final String content;

        private Action(ActionType type, String messageId, String content) {
            this.type = type;
            this.messageId = messageId;
            this.content = content;
        }

        public static Action copy(String content) {
            return new Action(ActionType.COPY, null, content);
        }

        public static Action regenerate(String messageId) {
            return new Action(ActionType.REGENERATE, messageId, null);
        }

        public static Action newChat() {
            return new Action(ActionType.NEW_CHAT, null, null);
        }

        public static Action like(String messageId) {
            return new Action(ActionType.LIKE, messageId, null);
        }

        public static Action dislike(String messageId) {
            return new Action(ActionType.DISLIKE, messageId, null);
        }

        public static Action showHelp() {
            return new Action(ActionType.SHOW_HELP, null, null);
        }

        public static Action showGuide() {
            return new Action(ActionType.SHOW_GUIDE, null, null);
        }

        public static Action viewToolDetails(String messageId) {
            return new Action(ActionType.VIEW_TOOL_DETAILS, messageId, null);
        }

        public static Action exportSummary(String messageId) {
            return new Action(ActionType.EXPORT_SUMMARY, messageId, null);
        }

        public static Action reportError(String messageId, String content) {
            return new Action(ActionType.REPORT_ERROR, messageId, content);
        }
    }

    /**
     * 系统消息类型
     */
    public enum SystemMessageType {
        INFO("ℹ️"),
        SUCCESS("✅"),
        WARNING("⚠️"),
        ERROR("❌"),
        TIP("💡"),
        THINKING("🤔"),
        TASK("📋"),
        PROGRESS("⏳");

        private final String emoji;

        SystemMessageType(String emoji) {
            this.emoji = emoji;
        }

        public String getEmoji() {
            return emoji;
        }
    }

    // ==================== 私有构造函数 ====================

    private ChatMessage(Builder builder) {
        this.id = builder.id;
        this.parentId = builder.parentId;
        this.timestamp = builder.timestamp;
        this.type = builder.type;
        this.status = builder.status;
        this.content = builder.content;
        this.thinkingSteps = builder.thinkingSteps;
        this.thinkingContent = builder.thinkingContent;
        this.attachments = builder.attachments;
        this.taskId = builder.taskId;
        this.tokensGenerated = builder.tokensGenerated;
        this.generationTimeMs = builder.generationTimeMs;
        this.systemType = builder.systemType;
        this.toolCallInfo = builder.toolCallInfo;
        this.agentStepInfo = builder.agentStepInfo;
        this.errorDetail = builder.errorDetail;
        this.retryable = builder.retryable;
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 创建用户消息
     */
    public static ChatMessage createUserMessage(String content) {
        return new Builder(MessageType.USER)
                .content(content)
                .build();
    }

    /**
     * 创建带附件的用户消息
     */
    public static ChatMessage createUserMessage(String content, List<Attachment> attachments) {
        return new Builder(MessageType.USER)
                .content(content)
                .attachments(attachments)
                .build();
    }

    /**
     * 创建用户消息（旧API兼容）
     */
    public static ChatMessage createUserMessage(String id, String content, long timestamp) {
        return new Builder(MessageType.USER)
                .id(id)
                .content(content)
                .timestamp(timestamp)
                .build();
    }

    /**
     * 创建用户消息（旧API兼容，带附件）
     */
    public static ChatMessage createUserMessage(String id, String content, long timestamp, List<Attachment> attachments) {
        return new Builder(MessageType.USER)
                .id(id)
                .content(content)
                .timestamp(timestamp)
                .attachments(attachments)
                .build();
    }

    /**
     * 创建AI消息
     */
    public static ChatMessage createAIMessage(String content) {
        return new Builder(MessageType.AI)
                .content(content)
                .status(MessageStatus.COMPLETED)
                .build();
    }

    /**
     * 创建带思考过程的AI消息
     */
    public static ChatMessage createAIMessage(String content, List<ThinkingStep> thinkingSteps) {
        return new Builder(MessageType.AI)
                .content(content)
                .thinkingSteps(thinkingSteps)
                .status(MessageStatus.COMPLETED)
                .build();
    }

    /**
     * 创建AI消息（旧API兼容）
     */
    public static ChatMessage createAIMessage(String id, String content, long timestamp) {
        return new Builder(MessageType.AI)
                .id(id)
                .content(content)
                .timestamp(timestamp)
                .status(MessageStatus.COMPLETED)
                .build();
    }

    /**
     * 创建AI消息（旧API兼容，带思考步骤）
     */
    public static ChatMessage createAIMessage(String id, String content, long timestamp,
                                              List<ThinkingStep> thinkingSteps) {
        return new Builder(MessageType.AI)
                .id(id)
                .content(content)
                .timestamp(timestamp)
                .thinkingSteps(thinkingSteps)
                .status(MessageStatus.COMPLETED)
                .build();
    }

    /**
     * 创建AI消息（旧API兼容，6参数版本）
     */
    public static ChatMessage createAIMessage(String id, String content, long timestamp,
                                              String parentId, int tokens, int duration) {
        return new Builder(MessageType.AI)
                .id(id)
                .content(content)
                .timestamp(timestamp)
                .parentId(parentId)
                .tokensGenerated(tokens)
                .generationTimeMs(duration)
                .status(MessageStatus.COMPLETED)
                .build();
    }

    /**
     * 创建系统消息
     */
    public static ChatMessage createSystemMessage(String content, SystemMessageType systemType) {
        return new Builder(MessageType.SYSTEM)
                .content(content)
                .systemType(systemType)
                .build();
    }

    /**
     * 创建简单系统消息（默认INFO类型）
     */
    public static ChatMessage createSystemMessage(String content) {
        return new Builder(MessageType.SYSTEM)
                .content(content)
                .systemType(SystemMessageType.INFO)
                .build();
    }

    /**
     * 创建系统消息（旧API兼容）
     */
    public static ChatMessage createSystemMessage(String id, String content,
                                                  SystemMessageType systemType, long timestamp) {
        return new Builder(MessageType.SYSTEM)
                .id(id)
                .content(content)
                .systemType(systemType)
                .timestamp(timestamp)
                .build();
    }

    /**
     * 创建思考过程消息
     */
    public static ChatMessage createThinkingMessage(String parentId, ThinkingStep step) {
        return new Builder(MessageType.THINKING)
                .parentId(parentId)
                .thinkingSteps(List.of(step))
                .status(MessageStatus.IN_PROGRESS)
                .build();
    }

    /**
     * 创建任务分解消息
     */
    public static ChatMessage createTaskBreakdownMessage(String content, List<ThinkingStep> steps) {
        return new Builder(MessageType.TASK_BREAKDOWN)
                .content(content)
                .thinkingSteps(steps)
                .status(MessageStatus.COMPLETED)
                .build();
    }

    /**
     * 创建任务进度消息
     */
    public static ChatMessage createTaskProgressMessage(String content, int current, int total) {
        return new Builder(MessageType.TASK_PROGRESS)
                .content(content + " (" + current + "/" + total + ")")
                .status(MessageStatus.IN_PROGRESS)
                .build();
    }

    public static ChatMessage createToolCallMessage(String toolName, String parameters) {
        ToolCallInfo info = new ToolCallInfo(toolName, parameters);
        return new Builder(MessageType.TOOL_CALL)
                .content(info.toolDisplayName)
                .toolCallInfo(info)
                .status(MessageStatus.IN_PROGRESS)
                .build();
    }

    public static ChatMessage createAgentStepMessage(AgentStepInfo stepInfo) {
        return new Builder(MessageType.AGENT_STEP)
                .content(stepInfo.getStepTypeLabel())
                .agentStepInfo(stepInfo)
                .status(stepInfo.isCompleted ? MessageStatus.COMPLETED : MessageStatus.IN_PROGRESS)
                .build();
    }

    public static ChatMessage createErrorMessage(String error, String detail, boolean retryable) {
        return new Builder(MessageType.ERROR)
                .content(error)
                .errorDetail(detail)
                .retryable(retryable)
                .status(MessageStatus.FAILED)
                .build();
    }

    public static ChatMessage createToolResultMessage(String toolName, String result) {
        ToolCallInfo info = new ToolCallInfo(toolName, "");
        info.result = result;
        info.status = ToolCallInfo.ToolCallStatus.COMPLETED;
        return new Builder(MessageType.TOOL_RESULT)
                .content(result)
                .toolCallInfo(info)
                .status(MessageStatus.COMPLETED)
                .build();
    }

    public static ChatMessage createAgentReflectionMessage(AgentReflectionInfo reflection) {
        return new Builder(MessageType.AGENT_REFLECTION)
                .content(reflection.analysis)
                .agentReflectionInfo(reflection)
                .status(MessageStatus.COMPLETED)
                .build();
    }

    public static ChatMessage createSummaryMessage(String content, SummaryInfo summaryInfo) {
        return new Builder(MessageType.SUMMARY)
                .content(content)
                .summaryInfo(summaryInfo)
                .status(MessageStatus.COMPLETED)
                .build();
    }

    // ==================== 类型判断方法 ====================

    public boolean isUserMessage() {
        return type == MessageType.USER;
    }

    public boolean isAIMessage() {
        return type == MessageType.AI;
    }

    public boolean isSystemMessage() {
        return type == MessageType.SYSTEM;
    }

    public boolean isThinkingMessage() {
        return type == MessageType.THINKING;
    }

    public boolean isTaskBreakdownMessage() {
        return type == MessageType.TASK_BREAKDOWN;
    }

    public boolean isTaskProgressMessage() {
        return type == MessageType.TASK_PROGRESS;
    }

    public boolean isToolCallMessage() {
        return type == MessageType.TOOL_CALL;
    }

    public boolean isAgentStepMessage() {
        return type == MessageType.AGENT_STEP;
    }

    public boolean isErrorMessage() {
        return type == MessageType.ERROR;
    }

    public boolean isToolResultMessage() {
        return type == MessageType.TOOL_RESULT;
    }

    public boolean isAgentReflectionMessage() {
        return type == MessageType.AGENT_REFLECTION;
    }

    public boolean isSummaryMessage() {
        return type == MessageType.SUMMARY;
    }

    public boolean hasError() {
        return status == MessageStatus.ERROR || status == MessageStatus.FAILED;
    }

    /**
     * 获取角色字符串（用于JNI调用）
     * @return "user", "assistant", 或 "system"
     */
    public String getRole() {
        switch (type) {
            case USER:
                return "user";
            case AI:
            case THINKING:
            case TASK_BREAKDOWN:
            case TASK_PROGRESS:
            case TOOL_CALL:
            case AGENT_STEP:
                return "assistant";
            case SYSTEM:
            case ERROR:
                return "system";
            default:
                return "user";
        }
    }

    /**
     * 获取消息内容（用于JNI调用）
     * @return 消息内容字符串
     */
    public String getContent() {
        return content;
    }

    public boolean hasThinkingSteps() {
        return thinkingSteps != null && !thinkingSteps.isEmpty();
    }

    public boolean hasAttachments() {
        return attachments != null && !attachments.isEmpty();
    }

    public boolean attachmentsEquals(ChatMessage other) {
        if (attachments == null && other.attachments == null) {
            return true;
        }
        if (attachments == null || other.attachments == null) {
            return false;
        }
        if (attachments.size() != other.attachments.size()) {
            return false;
        }
        for (int i = 0; i < attachments.size(); i++) {
            Attachment a1 = attachments.get(i);
            Attachment a2 = other.attachments.get(i);
            if (!a1.equals(a2)) {
                return false;
            }
        }
        return true;
    }

    public boolean isGenerating() {
        return status == MessageStatus.GENERATING;
    }

    public boolean isCompleted() {
        return status == MessageStatus.COMPLETED;
    }

    // ==================== 格式化方法 ====================

    /**
     * 获取格式化的思考过程文本
     */
    public String getFormattedThinkingProcess() {
        if (!hasThinkingSteps()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("思考过程：\n");

        for (int i = 0; i < thinkingSteps.size(); i++) {
            ThinkingStep step = thinkingSteps.get(i);
            sb.append(step.getEmoji())
              .append(" ")
              .append(step.name)
              .append(" ")
              .append(step.getStatusEmoji())
              .append("\n");

            if (step.description != null && !step.description.isEmpty()) {
                sb.append("   └ ")
                  .append(step.description)
                  .append("\n");
            }

            if (step.result != null && !step.result.isEmpty()) {
                sb.append("   └ 结果: ")
                  .append(step.result)
                  .append("\n");
            }

            if (step.getDuration() > 0) {
                sb.append("   └ 耗时: ")
                  .append(step.getDuration())
                  .append("ms\n");
            }
        }

        return sb.toString();
    }

    /**
     * 获取思考进度百分比
     */
    public int getThinkingProgress() {
        if (!hasThinkingSteps()) {
            return 0;
        }

        int completed = 0;
        for (ThinkingStep step : thinkingSteps) {
            if (step.status == ThinkingStepStatus.COMPLETED ||
                step.status == ThinkingStepStatus.SKIPPED) {
                completed++;
            }
        }

        return (completed * 100) / thinkingSteps.size();
    }

    /**
     * 获取下一个待执行的思考步骤
     */
    public ThinkingStep getNextPendingStep() {
        if (!hasThinkingSteps()) {
            return null;
        }

        for (ThinkingStep step : thinkingSteps) {
            if (step.status == ThinkingStepStatus.PENDING ||
                step.status == ThinkingStepStatus.IN_PROGRESS) {
                return step;
            }
        }
        return null;
    }

    /**
     * 克隆方法 - 创建当前消息的浅拷贝
     * 用于流式更新时保持数据一致性
     */
    public ChatMessage clone() {
        ChatMessage cloned = new ChatMessage.Builder(this.type)
            .id(this.id)
            .parentId(this.parentId)
            .timestamp(this.timestamp)
            .content(this.content)
            .thinkingContent(this.thinkingContent)
            .thinkingSteps(this.thinkingSteps != null ? new ArrayList<>(this.thinkingSteps) : new ArrayList<>())
            .attachments(this.attachments != null ? new ArrayList<>(this.attachments) : new ArrayList<>())
            .taskId(this.taskId)
            .tokensGenerated(this.tokensGenerated)
            .generationTimeMs(this.generationTimeMs)
            .status(this.status)
            .systemType(this.systemType)
            .toolCallInfo(this.toolCallInfo)
            .agentStepInfo(this.agentStepInfo)
            .agentReflectionInfo(this.agentReflectionInfo)
            .summaryInfo(this.summaryInfo)
            .taskProgress(this.taskProgress)
            .expanded(this.isExpanded)
            .errorDetail(this.errorDetail)
            .retryable(this.retryable)
            .build();
        return cloned;
    }

    // ==================== Builder模式 ====================

    public static class Builder {
        private String id = UUID.randomUUID().toString();
        private String parentId;
        private long timestamp = System.currentTimeMillis();
        private MessageType type;
        private MessageStatus status = MessageStatus.COMPLETED;
        private String content = "";
        private List<ThinkingStep> thinkingSteps = new ArrayList<>();
        private String thinkingContent = "";
        private List<Attachment> attachments = new ArrayList<>();
        private String taskId;
        private int tokensGenerated;
        private long generationTimeMs;
        private SystemMessageType systemType = SystemMessageType.INFO;
        private ToolCallInfo toolCallInfo;
        private AgentStepInfo agentStepInfo;
        private AgentReflectionInfo agentReflectionInfo;
        private SummaryInfo summaryInfo;
        private String errorDetail;
        private boolean retryable = false;
        private Integer taskProgress;
        private boolean isExpanded = false;

        public Builder(MessageType type) {
            this.type = type;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder parentId(String parentId) {
            this.parentId = parentId;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder type(MessageType type) {
            this.type = type;
            return this;
        }

        public Builder status(MessageStatus status) {
            this.status = status;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder thinkingSteps(List<ThinkingStep> thinkingSteps) {
            this.thinkingSteps = thinkingSteps != null ? thinkingSteps : new ArrayList<>();
            return this;
        }

        public Builder addThinkingStep(ThinkingStep step) {
            this.thinkingSteps.add(step);
            return this;
        }

        public Builder thinkingContent(String thinkingContent) {
            this.thinkingContent = thinkingContent != null ? thinkingContent : "";
            return this;
        }

        public Builder appendThinkingContent(String text) {
            if (this.thinkingContent == null) {
                this.thinkingContent = "";
            }
            if (text != null && !text.isEmpty()) {
                if (this.thinkingContent.length() > 0) {
                    this.thinkingContent += "\n\n";
                }
                this.thinkingContent += text;
            }
            return this;
        }

        public Builder attachments(List<Attachment> attachments) {
            this.attachments = attachments != null ? attachments : new ArrayList<>();
            return this;
        }

        public Builder addAttachment(Attachment attachment) {
            this.attachments.add(attachment);
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder tokensGenerated(int tokens) {
            this.tokensGenerated = tokens;
            return this;
        }

        public Builder generationTimeMs(long timeMs) {
            this.generationTimeMs = timeMs;
            return this;
        }

        public Builder systemType(SystemMessageType type) {
            this.systemType = type;
            return this;
        }

        public Builder toolCallInfo(ToolCallInfo toolCallInfo) {
            this.toolCallInfo = toolCallInfo;
            return this;
        }

        public Builder agentStepInfo(AgentStepInfo agentStepInfo) {
            this.agentStepInfo = agentStepInfo;
            return this;
        }

        public Builder errorDetail(String errorDetail) {
            this.errorDetail = errorDetail;
            return this;
        }

        public Builder retryable(boolean retryable) {
            this.retryable = retryable;
            return this;
        }

        public Builder agentReflectionInfo(AgentReflectionInfo agentReflectionInfo) {
            this.agentReflectionInfo = agentReflectionInfo;
            return this;
        }

        public Builder summaryInfo(SummaryInfo summaryInfo) {
            this.summaryInfo = summaryInfo;
            return this;
        }

        public Builder taskProgress(Integer taskProgress) {
            this.taskProgress = taskProgress;
            return this;
        }

        public Builder expanded(boolean expanded) {
            this.isExpanded = expanded;
            return this;
        }

        public ChatMessage build() {
            return new ChatMessage(this);
        }
    }

    // ==================== toString方法 ====================

    @Override
    public String toString() {
        return "ChatMessage{" +
                "id='" + id + '\'' +
                ", type=" + type +
                ", status=" + status +
                ", content='" + (content != null && content.length() > 50 ?
                    content.substring(0, 50) + "..." : content) + '\'' +
                ", thinkingStepsCount=" + (thinkingSteps != null ? thinkingSteps.size() : 0) +
                ", attachmentsCount=" + (attachments != null ? attachments.size() : 0) +
                '}';
    }
}
