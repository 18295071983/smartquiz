package com.oilquiz.app.ai.chat;

import com.oilquiz.app.ai.agent.AgentExecutionState;
import com.oilquiz.app.ai.chat.component.ComponentData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        AGENT_SUMMARY,
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
        /**
         * 当前推理阶段的开始时刻（毫秒）。多套状态文案按"本阶段已耗时"切换，
         * 跨阶段必须重置，否则思考结束进入生成后仍会按思考的时长选文案。
         * 运行时字段，不落盘。
         */
        public transient long phaseStartTime;

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

    /** 回合ID（消息对绑定，2026-09-14）：
     *  一次用户发送 = 一个回合；该回合的 user 消息、AI 回复（含多轮思考/工具/汇总组件）
     *  共享同一 turnId。UI 按 turnId 快速配对/聚合（跨任务不依赖顺序索引），
     *  Gson 自动持久化，会话恢复后消息对绑定不丢。
     *  由 {@link ChatIdDispatcher#applyMasterId()} 派发（T1、T2…），清空对话后重置。 */
    public String turnId;

    /** 子ID（消息对内序号，2026-09-14）：
     *  同一回合（turnId）内每条消息独立申请（T1-1、T1-2…），
     *  由 {@link ChatIdDispatcher#applySubId()} 派发，用于对内存量/顺序定位。 */
    public String subId;

    /** 时间戳 */
    public long timestamp;

    /** 消息类型 */
    public MessageType type;

    /** 消息状态 */
    public MessageStatus status;

    /** 消息内容 */
    public String content;

    /** 结构化 UI 组件列表（插件式渲染）：由工具结果或模型组件块生成，ChatAdapter 按类型渲染 */
    public List<ComponentData> components;

    /** 工具过程卡片是否展开（默认折叠为一行"工具过程 N 个"，点击展开；仅运行时使用，不持久化） */
    public boolean agentToolsExpanded = false;

    /** 思考步骤列表 */
    public List<ThinkingStep> thinkingSteps;

    /** 思考内容文本（流式思考过程的纯文本） */
    public String thinkingContent;

    /** 多轮思考的各轮内容（Agent 多轮推理时每轮独立保存，UI 分块展示）。
     *  元素顺序 = 轮次顺序；最后一轮进行中时 thinkingContent 为当前轮。 */
    public List<String> thinkingRounds;

    /** 各思考轮的 THINKING 子 id（2026-09-14）：与 thinkingRounds 一一对应，
     *  由 ChatIdDispatcher.applySubId(IdType.THINKING) 发放（T1-K1、T1-K2…），
     *  适配器按 id 定位/折叠/展开单个思考轮。 */
    public List<String> thinkingRoundIds;

    /**
     * 各思考轮的耗时（毫秒），与 {@link #thinkingRounds} 一一对应（2026-10-20）。
     *
     * <p>加这个字段是因为"每轮单独成行"之后，行尾要跟这一轮的耗时（"第 2 轮思考 · 1.8s"）；
     * 耗时只在流式过程中由 {@code GenerationStreamController} 知道（轮开始/定稿两个时刻），
     * 定稿后不记就永久丢了，所以必须落到消息模型上、跟着会话一起持久化。</p>
     *
     * <p>兼容：旧会话文件里没有这个字段 → 反序列化后为 null，UI 退回只显示"第 N 轮思考"；
     * 长度与轮数不一致时按短的那边取值，缺位不炸。0 / 负值视为"没有耗时"。</p>
     */
    public List<Long> thinkingRoundDurationsMs;

    /**
     * 被用户点开的思考轮 id（仅运行时 UI 状态，不持久化，2026-10-20 语义反转）。
     *
     * <p>原来这里是反着的：记的是"已折叠"的轮，默认全部展开。改成记"已展开"是因为
     * 每轮独立成行之后，默认全展开会让一条 10 轮的 Agent 消息变成 10 坨正文，
     * 比统一折叠更难读。现在的默认口径是：<b>已定稿的轮收起（只留一行摘要），
     * 正在流式的当前轮展开</b>；用户点开哪一轮，就只展开那一条。</p>
     */
    public transient java.util.Set<String> thinkingExpandedRounds;

    /** 该思考轮是否处于展开状态（未记录 = 收起；正在流式的当前轮由 UI 侧强制展开） */
    public boolean isThinkingRoundExpanded(String roundId) {
        return thinkingExpandedRounds != null && roundId != null
                && thinkingExpandedRounds.contains(roundId);
    }

    /** 切换单个思考轮的展开/收起 */
    public void toggleThinkingRoundExpanded(String roundId) {
        if (roundId == null || roundId.isEmpty()) return;
        if (thinkingExpandedRounds == null) thinkingExpandedRounds = new java.util.HashSet<>();
        if (!thinkingExpandedRounds.add(roundId)) {
            thinkingExpandedRounds.remove(roundId);
        }
    }

    /**
     * 被用户点开过的产物卡（2026-10-20）：peek 档卡片默认收成一行摘要
     * （见 {@link com.oilquiz.app.ai.chat.component.ComponentDisplay}），此集合记"这条已展开"。
     *
     * <p>仅运行时 UI 状态、不持久化，与 {@link #thinkingExpandedRounds} 同套路子：
     * 状态放在消息模型上而不是 View 上，RecyclerView 复用/整条重建后按它还原，
     * 不会像 v2 工具行那样一次绑定结束就丢。
     */
    public transient java.util.Set<String> componentBodyExpandedIds;

    /** 该产物卡是否处于展开状态（只有 peek 档会读到 true） */
    public boolean isComponentBodyExpanded(String key) {
        return key != null && !key.isEmpty()
                && componentBodyExpandedIds != null && componentBodyExpandedIds.contains(key);
    }

    /** 切换某张产物卡的展开状态，返回切换后的值 */
    public boolean toggleComponentBodyExpanded(String key) {
        if (key == null || key.isEmpty()) return false;
        if (componentBodyExpandedIds == null) {
            componentBodyExpandedIds = new java.util.HashSet<>();
        }
        if (componentBodyExpandedIds.remove(key)) return false;
        componentBodyExpandedIds.add(key);
        return true;
    }

    /** 工具组"已运行N个工具"折叠卡的展开态集合（parts 渲染器用；transient 不入库）。 */
    public transient java.util.Set<String> toolGroupExpandedIds;

    public boolean isToolGroupExpanded(String key) {
        return key != null && !key.isEmpty()
                && toolGroupExpandedIds != null && toolGroupExpandedIds.contains(key);
    }

    public boolean toggleToolGroupExpanded(String key) {
        if (key == null || key.isEmpty()) return false;
        if (toolGroupExpandedIds == null) {
            toolGroupExpandedIds = new java.util.HashSet<>();
        }
        if (toolGroupExpandedIds.remove(key)) return false;
        toolGroupExpandedIds.add(key);
        return true;
    }

    /**
     * 组内<b>单条工具行</b>的结果展开态（parts §11.1 对齐 legacy 紧凑行：点行展开该工具的结果全文）。
     * transient：与 {@link #toolGroupExpandedIds} 同理，是本次浏览的视图状态，不固化落盘。
     */
    public transient java.util.Set<String> toolRowExpandedIds;

    public boolean isToolRowExpanded(String key) {
        return key != null && !key.isEmpty()
                && toolRowExpandedIds != null && toolRowExpandedIds.contains(key);
    }

    public boolean toggleToolRowExpanded(String key) {
        if (key == null || key.isEmpty()) return false;
        if (toolRowExpandedIds == null) {
            toolRowExpandedIds = new java.util.HashSet<>();
        }
        if (toolRowExpandedIds.remove(key)) return false;
        toolRowExpandedIds.add(key);
        return true;
    }

    /** 追加一轮思考内容（自动创建列表；轮次 id 为空时由派发器自动发放） */
    public void addThinkingRound(String roundContent) {
        addThinkingRound(roundContent, null);
    }

    /** 追加一轮思考内容 + 该轮的 THINKING 子 id（与 thinkingRounds 一一对应） */
    public void addThinkingRound(String roundContent, String roundId) {
        addThinkingRound(roundContent, roundId, 0L);
    }

    /**
     * 追加一轮思考内容 + 子 id + 该轮耗时（毫秒，0 = 未知）。
     * 三个列表（内容 / id / 耗时）严格同下标，缺位一律补占位，避免 UI 取值错位。
     */
    public void addThinkingRound(String roundContent, String roundId, long durationMs) {
        if (roundContent == null || roundContent.trim().isEmpty()) return;
        if (thinkingRounds == null) thinkingRounds = new java.util.ArrayList<>();
        thinkingRounds.add(roundContent);
        if (thinkingRoundIds == null) thinkingRoundIds = new java.util.ArrayList<>();
        if (roundId == null || roundId.isEmpty()) {
            // 以本消息自己的 turnId 为前缀（1 参隐式版会读派发器的"当前主 id"，
            // 异步思考回调在清空对话/新回合后到达时会给旧消息发出不匹配的前缀）
            roundId = com.oilquiz.app.ai.chat.ChatIdDispatcher.getInstance()
                    .applySubId(com.oilquiz.app.ai.chat.ChatIdDispatcher.IdType.THINKING, turnId);
            if (roundId == null || roundId.isEmpty()) {
                // 本消息还没有 turnId（旧数据/异常路径）：退回隐式派发，至少保证 id 唯一
                roundId = com.oilquiz.app.ai.chat.ChatIdDispatcher.getInstance()
                        .applySubId(com.oilquiz.app.ai.chat.ChatIdDispatcher.IdType.THINKING);
            }
        }
        // 防御：若已有记录条数与轮数不一致（旧数据恢复），按需补齐占位
        while (thinkingRoundIds.size() < thinkingRounds.size() - 1) {
            thinkingRoundIds.add(null);
        }
        thinkingRoundIds.add(roundId);
        // 耗时同口径补齐：旧数据/未计时的那轮记 0（UI 侧 0 视为"没有耗时"，不显示秒数）
        if (thinkingRoundDurationsMs == null) thinkingRoundDurationsMs = new java.util.ArrayList<>();
        while (thinkingRoundDurationsMs.size() < thinkingRounds.size() - 1) {
            thinkingRoundDurationsMs.add(0L);
        }
        thinkingRoundDurationsMs.add(Math.max(0L, durationMs));
    }

    /** 第 index 轮的耗时（毫秒）；没有记录返回 0，调用方按 0 当作"未知"处理 */
    public long thinkingRoundDurationMs(int index) {
        if (thinkingRoundDurationsMs == null || index < 0
                || index >= thinkingRoundDurationsMs.size()) return 0L;
        Long v = thinkingRoundDurationsMs.get(index);
        return v == null ? 0L : Math.max(0L, v);
    }

    /** 正文轮次边界：content 中每个工具调用开始处的位置（工具调用 = 轮次边界）。
     *  第 i 个边界与第 i 个 tool_call 一一对应；UI 按边界切分正文并与思考轮次/工具卡组装显示。
     *  旧数据可能只有偏移、没有 {@link #contentRoundToolIds}，由
     *  {@link RoundBounds#normalize(ChatMessage)} 补齐。 */
    public java.util.List<Integer> contentRoundBounds;

    /** 与 {@link #contentRoundBounds} 等长的 toolCallId 列表；旧数据为 null。
     *  用于把"边界"从纯下标对齐升级为按工具身份对齐（并行/重试/缺回调时防止错位）。 */
    public java.util.List<String> contentRoundToolIds;

    /**
     * 每轮模型生成的完整正文快照（2026-10-21）。
     *
     * <p>注意：这不是 {@code ChatMessage.content} 的替代品，也不是把所有轮次拼成一条。
     * Agent 多轮时，每轮 complete 都有自己的 fullText；之前只保存了最后一轮
     * finalAnswer，前几轮正文只剩 contentRoundBounds 的位置信息。该列表把每轮
     * 清理后的 fullText + 思考 + 时间留档，供恢复/重放/组装使用。</p>
     */
    public java.util.List<RoundSnapshot> roundSnapshots;

    /**
     * 单轮用户请求的 canonical 助手回合（parts 模型，2026-10-21）。
     * `content` 仍是兼容投影；真正的顺序/每轮全量/工具结构以这里为准。
     */
    public com.oilquiz.app.ai.chat.turn.AssistantTurn assistantTurn;

    /** 一轮模型生成的完整快照。 */
    public static class RoundSnapshot {
        /** 第几轮（从 1 开始） */
        public int round;
        /** 该轮清理后的完整正文（不是 delta） */
        public String content;
        /** 该轮思考内容（没有则为空） */
        public String thinking;
        /** 该轮结束时间 */
        public long timestamp;
        /** 该轮使用的工具调用 id（可空；由 UI 工具边界补） */
        public java.util.List<String> toolCallIds;

        public RoundSnapshot() {
        }

        public RoundSnapshot(int round, String content, String thinking, long timestamp) {
            this.round = round;
            this.content = content != null ? content : "";
            this.thinking = thinking != null ? thinking : "";
            this.timestamp = timestamp;
        }
    }

    /** 追加快照；空正文也保留轮次占位，便于对齐 toolCallIds。 */
    public void addRoundSnapshot(RoundSnapshot snapshot) {
        if (snapshot == null) return;
        if (roundSnapshots == null) roundSnapshots = new java.util.ArrayList<>();
        roundSnapshots.add(snapshot);
    }

    /** 系统消息动作载荷（如本地Agent拦截时保存的原始问题，供"强行执行"按钮重发），仅运行时使用 */
    public String actionPayload;

    /**
     * 思考区域是否展开（默认折叠，思考中/流式时强制展开，思考完毕后折叠，用户可点击切换）。
     */
    public boolean thinkingExpanded = false;

    /**
     * 独立工具卡是否展开（2026-10-21 拆回 demo 两段式）。仅运行时 UI 状态，不持久化，
     * 与 {@link #thinkingExpanded} 同一口径：默认收起，点卡头才展开逐条明细。
     */
    public transient boolean toolSectionExpanded = false;

    /**
     * 每轮工具卡的展开状态（2026-10-21 每轮活动区）：key = 该轮首个 toolCallId，
     * 无 id 时退化到锚点。仅运行时 UI 状态，不持久化。
     */
    public transient java.util.Set<String> toolSectionExpandedIds;

    public boolean isToolSectionExpanded(String key) {
        return key != null && !key.isEmpty()
                && toolSectionExpandedIds != null && toolSectionExpandedIds.contains(key);
    }

    public void toggleToolSectionExpanded(String key) {
        if (key == null || key.isEmpty()) return;
        if (toolSectionExpandedIds == null) {
            toolSectionExpandedIds = new java.util.HashSet<>();
        }
        if (!toolSectionExpandedIds.add(key)) {
            toolSectionExpandedIds.remove(key);
        }
    }

    /** 附件列表 */
    public List<Attachment> attachments;

    /** 相关任务ID */
    public String taskId;

    /** Token数量 */
    public int tokensGenerated;

    /** 生成耗时（毫秒） */
    public long generationTimeMs;

    /** 模型推理速度（token/s） */
    public float tokensPerSecond;

    /** 系统消息类型 */
    public SystemMessageType systemType;

    /** 工具调用信息 */
    public ToolCallInfo toolCallInfo;

    /** Agent步骤信息 */
    public AgentStepInfo agentStepInfo;

    /** Agent汇总信息 */
    public AgentSummaryInfo agentSummaryInfo;

    /** 是否为Agent模式消息 */
    public boolean agentMode;

    /** Agent执行状态数据 */
    public AgentExecutionState agentExecutionState;

    /** Agent进度信息 */
    public AgentProgressInfo agentProgressInfo;

    /** Agent 执行步骤状态（实时显示在气泡内：🔍思考中/🔧调用X/✅完成），运行时字段 */
    public String agentStepStatus;

    /**
     * 本轮 token 统计文本（输入/输出/缓存/上下文，完成后只显示 2 秒）。
     * 运行时字段：transient 不落盘，避免历史恢复后旧统计又常驻最新一条回复上。
     */
    public transient String agentSummary;

    /**
     * 本轮第一个工具开始执行的时刻（毫秒）；0=当前没有工具在跑。
     * 工具卡/工具组的"工具侧思考"文案按它算已耗时。运行时字段，不落盘。
     */
    public transient long runningToolStartTime;

    /** 错误信息 */
    public String errorDetail;

    /** 是否可重试 */
    public boolean retryable;
    /** 消息来源是否为语音输入（按住说话识别后发送） */
    public boolean voiceInput;
    /** 用户已点赞（0=未反馈, 1=已赞, -1=已踩）；运行时反馈，不持久化 */
    public transient int feedbackState = 0;
    /** 该消息已被重试的次数（>0 时显示"第 N 次尝试"） */
    public int retryCount = 0;
    /** 引用回复的目标消息 id（编辑/引用时填充；运行时字段） */
    public transient String quoteFromMessageId;
    /** 引用回复的目标消息摘要（UI 展示用；运行时字段） */
    public transient String quotePreview;

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

    /** 用户是否手动操作过展开/收起（用于区分首次自动展开与用户主动收起） */
    public boolean hasUserToggledExpand;

    /** Agent执行组ID：同一组agent执行产生的中间消息（思考、工具调用、步骤）共享同一ID */
    public String agentGroupId;
    /** 是否为Agent执行组的header消息（点击可折叠/展开整组） */
    public boolean isAgentGroupHeader;
    /** Agent执行组是否已折叠（仅header使用，默认折叠） */
    public boolean agentGroupCollapsed = true;
    /** Agent执行组内的步骤数（仅header使用，用于显示摘要） */
    public int agentGroupStepCount = 0;
    /** Agent执行组内的工具调用数（仅header使用） */
    public int agentGroupToolCount = 0;

    // ---- 轮次模式标记（普通对话 ↔ Agent 上下文隔离）----
    /** 轮次模式：普通对话 */
    public static final int TURN_MODE_NORMAL = 0;
    /** 轮次模式：Agent（工具循环） */
    public static final int TURN_MODE_AGENT = 1;
    /** 本条消息所属轮次的模式。默认 NORMAL（旧历史消息无标记时按普通处理）。 */
    public int turnMode = TURN_MODE_NORMAL;

    /** 在线模型信息 */
    public ModelInfo modelInfo;

    /**
     * 在线模型信息类 - 用于显示模型名称、状态、延迟、成本等
     */
    public static class ModelInfo {
        /** 模型名称 */
        public String modelName;
        /** 模型类型（如 gpt-4, gpt-3.5-turbo） */
        public String modelType;
        /** API URL */
        public String apiUrl;
        /** 状态：0=未知, 1=在线, 2=离线, 3=错误 */
        public int status;
        /** 延迟（毫秒） */
        public long latencyMs;
        /** 上次使用时间戳 */
        public long lastUsedTime;
        /** 成本估算 */
        public double costEstimate;
        /** 是否启用 */
        public boolean isEnabled;

        public ModelInfo(String modelName) {
            this.modelName = modelName;
            this.status = 0;
            this.isEnabled = true;
        }

        public ModelInfo(String modelName, String modelType, long latencyMs, double costEstimate) {
            this.modelName = modelName;
            this.modelType = modelType;
            this.latencyMs = latencyMs;
            this.costEstimate = costEstimate;
            this.status = 0;
            this.isEnabled = true;
        }

        public String getStatusText() {
            switch (status) {
                case 1: return "在线";
                case 2: return "离线";
                case 3: return "错误";
                default: return "未知";
            }
        }
    }

    public static class ToolCallInfo {
        /** 工具调用唯一 ID（引擎生成），用于完成回调精确定位卡片，避免并行/乱序更新错位 */
        public String toolCallId;
        public String toolName;
        public String toolDisplayName;
        public String toolIcon;
        public String parameters;
        public String result;
        public ToolCallStatus status;
        public long executionTimeMs;
        /** 参数区域是否展开（默认折叠，节省屏幕空间） */
        public boolean paramsExpanded = false;
        /** 结果区域是否展开（默认折叠） */
        public boolean resultExpanded = false;
        /** 整个工具调用块是否展开（默认折叠，只显示摘要行） */
        public boolean callExpanded = false;
        /** 原始 AIToolResult.result 数据（未经过 formatForUi 处理），供后续用户点击 AI 深度解读按钮时使用 */
        public transient Object rawResult;
        /** 是否已完成/至少点击过一次 AI 深度解读（避免重复按钮/重复请求） */
        public boolean interpretationDone = false;
        /**
         * 是否有可用的 LLM（在线或本地）允许 AI 深度解读。
         * 由 AIChatActivity 在工具执行完时一次性赋值（调用 ToolResultInterpreter.isAnyModelAvailable），
         * ChatAdapter 只读取此字段，避免在 RecyclerView 绑定时做重量级操作（如 new ALChat()）导致气泡不显示。
         */
        public boolean canInterpret = false;
        /**
         * AI 自动解读产生的自然语言摘要（若为 null 则未解读）。
         * 原来作为独立 AI 主气泡（addAIMessage）显示，现直接挂在工具调用消息上作为结果区顶部文本。
         */
        public String interpretedMessage = null;
        /**
         * 工具声明化卡片意图（dsh presentCall/presentResult 对齐，2026-09-23）。
         * 引擎经 onToolPresent 回调传入；ChatAdapter 渲染工具卡时优先读此字段，null 走通用呈现。
         */
        public transient Map<String, Object> presentCard;

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
            // C0-T4：状态去 emoji（⏳//✅/❌ 与 B0 之后统一过的卡片视觉打架），
            // 颜色改由 ChatAdapter.bindToolCallMessage 按状态设置
            switch (status) {
                case PENDING: return "等待执行";
                case RUNNING: return "执行中";
                case COMPLETED: return "已完成";
                case FAILED: return "失败";
                default: return "";
            }
        }

        /** 工具中文名统一走 {@link ToolMetaRegistry}（此前这里只有 9 个 case） */
        private static String formatToolDisplayName(String name) {
            return ToolMetaRegistry.displayName(name);
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
        public String reasoningMode;
        public String detail;
        public long executionTimeMs;
        public int tokenCount;
        /** 步骤描述是否展开（默认折叠） */
        public boolean stepExpanded = false;

        public enum AgentStepType {
            THINKING,
            PLANNING,
            ACTING,
            OBSERVING,
            REFLECTING,
            TOOL_CALLING,
            REASONING,
            LOOPING,
            PAUSED,
            COMPLETED
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
                case ACTING: return "⚙️ 执行";
                case OBSERVING: return "👁️ 观察";
                case REFLECTING: return "🔄 反思";
                case TOOL_CALLING: return "🔧 工具调用";
                case REASONING: return "🧠 推理";
                case LOOPING: return "🔁 循环";
                case PAUSED: return "⏸️ 已暂停";
                case COMPLETED: return "✅ 完成";
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
                case TOOL_CALLING: return "🔧";
                case REASONING: return "🧠";
                case LOOPING: return "🔁";
                case PAUSED: return "⏸️";
                case COMPLETED: return "✅";
                default: return "📌";
            }
        }

        public String getStepColor() {
            switch (stepType) {
                case THINKING: return "#8B5CF6";
                case PLANNING: return "#3B82F6";
                case ACTING: return "#10B981";
                case OBSERVING: return "#F59E0B";
                case REFLECTING: return "#EC4899";
                case TOOL_CALLING: return "#6366F1";
                case REASONING: return "#8B5CF6";
                case LOOPING: return "#14B8A6";
                case PAUSED: return "#6B7280";
                case COMPLETED: return "#10B981";
                default: return "#6B7280";
            }
        }
    }

    /**
     * Agent 执行汇总信息
     */
    public static class AgentSummaryInfo {
        public long totalTimeMs;
        public int totalSteps;
        public int toolCallCount;
        public int totalTokens;
        public boolean isSuccess;
        public String summary;

        public AgentSummaryInfo(long totalTimeMs, int totalSteps, int toolCallCount,
                               int totalTokens, boolean isSuccess) {
            this.totalTimeMs = totalTimeMs;
            this.totalSteps = totalSteps;
            this.toolCallCount = toolCallCount;
            this.totalTokens = totalTokens;
            this.isSuccess = isSuccess;
        }

        public String getFormattedTime() {
            if (totalTimeMs < 1000) {
                return totalTimeMs + "ms";
            } else if (totalTimeMs < 60000) {
                return String.format("%.1fs", totalTimeMs / 1000.0);
            } else {
                return String.format("%dm %ds", totalTimeMs / 60000, (totalTimeMs % 60000) / 1000);
            }
        }

        public String getFormattedTokens() {
            if (totalTokens < 1000) {
                return String.valueOf(totalTokens);
            } else {
                return String.format("%.1fk", totalTokens / 1000.0);
            }
        }
    }

    /**
     * Agent 执行进度信息 - 实时显示 Agent 执行状态
     */
    public static class AgentProgressInfo {
        /** 当前步骤 */
        public int currentStep;
        /** 总步骤数 */
        public int totalSteps;
        /** 工具调用次数 */
        public int toolCallCount;
        /** 总执行时间（毫秒） */
        public long totalTimeMs;
        /** 总 Token 数 */
        public int totalTokens;
        /** 当前阶段：思考、执行、观察 */
        public String currentPhase;
        /** 当前工具名称（如果正在执行工具） */
        public String currentToolName;
        /** 工具执行时间（毫秒） */
        public long toolExecutionTimeMs;

        public AgentProgressInfo() {
            this.currentStep = 0;
            this.totalSteps = 0;
            this.toolCallCount = 0;
            this.totalTimeMs = 0;
            this.totalTokens = 0;
            this.currentPhase = "思考";
        }

        public AgentProgressInfo(int currentStep, int totalSteps) {
            this.currentStep = currentStep;
            this.totalSteps = totalSteps;
            this.currentPhase = "思考";
        }

        public String getProgressText() {
            if (totalSteps > 0) {
                return "步骤 " + currentStep + "/" + totalSteps + " - " + currentPhase;
            }
            return currentPhase;
        }

        public String getFormattedTime() {
            if (totalTimeMs < 1000) {
                return totalTimeMs + "ms";
            } else if (totalTimeMs < 60000) {
                return String.format("%.1fs", totalTimeMs / 1000.0);
            } else {
                return String.format("%dm %ds", totalTimeMs / 60000, (totalTimeMs % 60000) / 1000);
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

        /** 步骤编号 */
        public int stepNumber;

        /** 步骤名称/标题 */
        public String name;

        /** 步骤标题（别名，便于流式更新使用） */
        public String title;

        /** 步骤详细描述 */
        public String description;

        /** 步骤内容（别名，便于流式更新使用） */
        public String content;

        /** 步骤状态 */
        public ThinkingStepStatus status;

        /** 开始时间 */
        public long startTime;

        /** 结束时间 */
        public long endTime;

        /** 步骤结果 */
        public String result;

        /** 步骤类型 */
        public ThinkingStepType stepType;

        /** 步骤进度 (0-100) */
        public int progress;

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
            this.stepNumber = 0;
            this.name = name;
            this.title = name;
            this.description = description;
            this.content = description;
            this.status = ThinkingStepStatus.PENDING;
            this.stepType = stepType;
            this.progress = 0;
        }

        public ThinkingStep(int stepNumber, String stepType, String title, String content, int progress) {
            this.id = UUID.randomUUID().toString();
            this.stepNumber = stepNumber;
            this.name = title;
            this.title = title;
            this.description = content;
            this.content = content;
            this.status = ThinkingStepStatus.IN_PROGRESS;
            this.stepType = parseStepType(stepType);
            this.progress = progress;
        }

        private static ThinkingStepType parseStepType(String typeStr) {
            if (typeStr == null) return ThinkingStepType.UNDERSTAND;
            String upper = typeStr.toUpperCase().trim();
            try {
                return ThinkingStepType.valueOf(upper);
            } catch (IllegalArgumentException e) {
                if (upper.contains("UNDERSTAND") || upper.contains("理解")) return ThinkingStepType.UNDERSTAND;
                if (upper.contains("INTENT") || upper.contains("意图")) return ThinkingStepType.INTENT;
                if (upper.contains("PLAN") || upper.contains("规划")) return ThinkingStepType.PLANNING;
                if (upper.contains("DECOMPOSE") || upper.contains("分解")) return ThinkingStepType.DECOMPOSE;
                if (upper.contains("EXECUTE") || upper.contains("执行")) return ThinkingStepType.EXECUTE;
                if (upper.contains("SEARCH") || upper.contains("搜索")) return ThinkingStepType.SEARCH;
                if (upper.contains("ANALYSIS") || upper.contains("分析")) return ThinkingStepType.ANALYSIS;
                if (upper.contains("GENERATE") || upper.contains("生成")) return ThinkingStepType.GENERATE;
                if (upper.contains("VERIFY") || upper.contains("验证")) return ThinkingStepType.VERIFY;
                if (upper.contains("SUMMARIZE") || upper.contains("总结")) return ThinkingStepType.SUMMARIZE;
                return ThinkingStepType.UNDERSTAND;
            }
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
     * 附件上传/处理状态
     */
    public enum AttachmentStatus {
        PENDING,
        UPLOADING,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    /**
     * 附件类
     */
    public static class Attachment {
        public final String id;
        public String type;
        public String url;
        public String name;
        public long size;
        public String mimeType;
        public String thumbnailPath;
        public long durationMs;
        public AttachmentStatus status;
        public int uploadProgress;
        public String errorMessage;
        public String localFilePath;
        
        // 多模态解析相关字段
        public String extractedContent; // 提取的文本内容（OCR/文档解析）
        public boolean isExtracting = false; // 是否正在解析
        public boolean isExtracted = false; // 是否已解析完成
        public String extractionError; // 解析错误信息
        public String aiSummary; // AI生成的智能摘要
        
        // OCR 模型追踪（多层数据传递）
        public String ocrModelName; // 用于 OCR 的模型名称
        public String ocrModelId; // 用于 OCR 的模型 ID
        public long ocrTimestamp; // OCR 处理时间戳

        public Attachment(String type, String url, String name) {
            this.id = UUID.randomUUID().toString();
            this.type = type;
            this.url = url;
            this.name = name;
            this.size = 0;
            this.mimeType = getMimeFromType(type);
            this.status = AttachmentStatus.COMPLETED;
            this.uploadProgress = 0;
            this.durationMs = 0;
        }

        public Attachment(String type, String url, String name, long size) {
            this.id = UUID.randomUUID().toString();
            this.type = type;
            this.url = url;
            this.name = name;
            this.size = size;
            this.mimeType = getMimeFromType(type);
            this.status = AttachmentStatus.COMPLETED;
            this.uploadProgress = 0;
            this.durationMs = 0;
        }

        public Attachment withThumbnail(String thumbnailPath) {
            this.thumbnailPath = thumbnailPath;
            return this;
        }

        public Attachment withDuration(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Attachment withStatus(AttachmentStatus status) {
            this.status = status;
            return this;
        }

        public Attachment withUploadProgress(int progress) {
            this.uploadProgress = progress;
            return this;
        }

        public Attachment withLocalFilePath(String localFilePath) {
            this.localFilePath = localFilePath;
            return this;
        }

        public Attachment withError(String errorMessage) {
            this.errorMessage = errorMessage;
            this.status = AttachmentStatus.FAILED;
            return this;
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

        public String getFormattedDuration() {
            if (durationMs <= 0) return "";
            long seconds = durationMs / 1000;
            long minutes = seconds / 60;
            long hours = minutes / 60;
            if (hours > 0) {
                return String.format("%d:%02d:%02d", hours, minutes % 60, seconds % 60);
            } else {
                return String.format("%d:%02d", minutes, seconds % 60);
            }
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

        public boolean isImage() {
            return type != null && type.toLowerCase().contains("image");
        }

        public boolean isVideo() {
            return type != null && type.toLowerCase().contains("video");
        }

        public boolean isAudio() {
            return type != null && type.toLowerCase().contains("audio");
        }

        public boolean isDocument() {
            return !isImage() && !isVideo() && !isAudio();
        }

        public boolean isUploading() {
            return status == AttachmentStatus.UPLOADING || status == AttachmentStatus.PENDING;
        }

        public boolean hasError() {
            return status == AttachmentStatus.FAILED;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Attachment that = (Attachment) obj;
            return id != null && id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return id != null ? id.hashCode() : 0;
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
        REPORT_ERROR,
        /** 用户点击 AI 消息操作行的"朗读"按钮：由 AIChatActivity 触发 TTS 朗读/停止 */
        SPEAK,
        /** 用户点击工具调用卡片中的"AI深度解读"按钮：由 AIChatActivity 触发 LLM 解释 */
        AI_INTERPRET_RESULT,
        /** 用户点击本地Agent拦截提示中的"强行使用本地Agent"：重发原问题到本地Agent流程 */
        FORCE_LOCAL_AGENT,
        /** 停止正在流式生成的 AI 消息 */
        STOP_GENERATION,
        /** 编辑用户消息并重发 */
        EDIT_MESSAGE,
        /** 删除单条消息 */
        DELETE_MESSAGE,
        /** 引用回复某条消息 */
        QUOTE_MESSAGE,
        /** 查看消息详情（时间戳/元数据/模型信息） */
        SHOW_DETAIL,
        /** 复制代码块内容 */
        COPY_CODE_BLOCK,
        /** 重试失败的工具调用 */
        RETRY_TOOL_CALL,
        /** 编辑工具参数并重跑 */
        EDIT_TOOL_ARGS,
        /** Agent 执行控制：暂停 */
        PAUSE_AGENT,
        /** Agent 执行控制：继续 */
        RESUME_AGENT,
        /** Agent 执行控制：单步执行 */
        STEP_AGENT,
        /** Agent 执行控制：回退一步 */
        REWIND_AGENT,
        /** 导出 Agent 执行日志 */
        EXPORT_AGENT_LOGS
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

        /** 朗读指定 AI 消息（再次点击同一消息则停止） */
        public static Action speak(String messageId, String content) {
            return new Action(ActionType.SPEAK, messageId, content);
        }

        /**
         * 触发 AI 深度解读指定工具调用消息。
         * @param messageId 工具调用消息的 ID（用于定位 toolName + rawResult）
         */
        public static Action aiInterpretResult(String messageId) {
            return new Action(ActionType.AI_INTERPRET_RESULT, messageId, null);
        }

        /** 强行使用本地Agent执行（content 为需要重发的原始问题） */
        public static Action forceLocalAgent(String content) {
            return new Action(ActionType.FORCE_LOCAL_AGENT, null, content);
        }

        /** 停止生成 */
        public static Action stopGeneration(String messageId) {
            return new Action(ActionType.STOP_GENERATION, messageId, null);
        }

        /** 编辑消息（content 为原始内容供输入框预填） */
        public static Action editMessage(String messageId, String content) {
            return new Action(ActionType.EDIT_MESSAGE, messageId, content);
        }

        /** 删除单条消息 */
        public static Action deleteMessage(String messageId) {
            return new Action(ActionType.DELETE_MESSAGE, messageId, null);
        }

        /** 引用回复 */
        public static Action quoteMessage(String messageId, String content) {
            return new Action(ActionType.QUOTE_MESSAGE, messageId, content);
        }

        /** 查看消息详情 */
        public static Action showDetail(String messageId) {
            return new Action(ActionType.SHOW_DETAIL, messageId, null);
        }

        /** 复制代码块 */
        public static Action copyCodeBlock(String code) {
            return new Action(ActionType.COPY_CODE_BLOCK, null, code);
        }

        /** 重试失败的工具调用（messageId 定位消息，content 存 toolCallId） */
        public static Action retryToolCall(String messageId, String toolCallId) {
            return new Action(ActionType.RETRY_TOOL_CALL, messageId, toolCallId);
        }

        /** 编辑工具参数并重跑 */
        public static Action editToolArgs(String messageId, String toolCallId, String newArgsJson) {
            return new Action(ActionType.EDIT_TOOL_ARGS, messageId, newArgsJson);
        }

        /** Agent 执行控制：暂停 */
        public static Action pauseAgent(String messageId) {
            return new Action(ActionType.PAUSE_AGENT, messageId, null);
        }

        /** Agent 执行控制：继续 */
        public static Action resumeAgent(String messageId) {
            return new Action(ActionType.RESUME_AGENT, messageId, null);
        }

        /** Agent 执行控制：单步执行 */
        public static Action stepAgent(String messageId) {
            return new Action(ActionType.STEP_AGENT, messageId, null);
        }

        /** Agent 执行控制：回退一步 */
        public static Action rewindAgent(String messageId) {
            return new Action(ActionType.REWIND_AGENT, messageId, null);
        }

        /** 导出 Agent 执行日志（content 存导出格式：json / markdown） */
        public static Action exportAgentLogs(String messageId, String format) {
            return new Action(ActionType.EXPORT_AGENT_LOGS, messageId, format);
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
        PROGRESS("⏳"),
        /** 上下文注入行（记忆/任务/工具目录等系统注入内容的展示条，点击看全文；不进发送历史） */
        CONTEXT_INJECTION("🧠"),
        /** 系统提示词行（system prompt 变更记录/全文展示，点击看全文；不进发送历史） */
        SYSTEM_PROMPT("📄");

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
        this.agentSummaryInfo = builder.agentSummaryInfo;
        this.agentProgressInfo = builder.agentProgressInfo;
        this.agentMode = builder.agentMode;
        this.agentExecutionState = builder.agentExecutionState;
        this.errorDetail = builder.errorDetail;
        this.retryable = builder.retryable;
        this.voiceInput = builder.voiceInput;
        this.inferenceProgress = builder.inferenceProgress;
        this.agentGroupId = builder.agentGroupId;
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

    public static ChatMessage createToolCallMessage(String toolCallId, String toolName, String parameters) {
        ToolCallInfo info = new ToolCallInfo(toolName, parameters);
        info.toolCallId = toolCallId;
        return new Builder(MessageType.TOOL_CALL)
                .content(info.toolDisplayName)
                .toolCallInfo(info)
                .status(MessageStatus.IN_PROGRESS)
                .build();
    }

    public static ChatMessage createToolCallMessage(String toolName, String parameters) {
        return createToolCallMessage(null, toolName, parameters);
    }

    public static ChatMessage createAgentStepMessage(AgentStepInfo stepInfo) {
        return new Builder(MessageType.AGENT_STEP)
                .content(stepInfo.getStepTypeLabel())
                .agentStepInfo(stepInfo)
                .status(stepInfo.isCompleted ? MessageStatus.COMPLETED : MessageStatus.IN_PROGRESS)
                .build();
    }

    /**
     * 创建 Agent 汇总消息
     */
    public static ChatMessage createAgentSummaryMessage(AgentSummaryInfo summaryInfo) {
        return new Builder(MessageType.AGENT_SUMMARY)
                .content("Agent 执行汇总")
                .agentSummaryInfo(summaryInfo)
                .status(MessageStatus.COMPLETED)
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
            .agentSummaryInfo(this.agentSummaryInfo)
            .agentProgressInfo(this.agentProgressInfo)
            .agentReflectionInfo(this.agentReflectionInfo)
            .summaryInfo(this.summaryInfo)
            .taskProgress(this.taskProgress)
            .expanded(this.isExpanded)
            .errorDetail(this.errorDetail)
            .retryable(this.retryable)
            .inferenceProgress(this.inferenceProgress)
            .build();
        cloned.roundSnapshots = this.roundSnapshots != null
                ? new java.util.ArrayList<>(this.roundSnapshots) : null;
        cloned.assistantTurn = this.assistantTurn != null ? this.assistantTurn.copy() : null;
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
        private AgentSummaryInfo agentSummaryInfo;
        private AgentProgressInfo agentProgressInfo;
        private AgentReflectionInfo agentReflectionInfo;
        private boolean agentMode;
        private AgentExecutionState agentExecutionState;
        private SummaryInfo summaryInfo;
        private String errorDetail;
        private boolean retryable = false;
        private boolean voiceInput;
        private Integer taskProgress;
        private boolean isExpanded = false;
        private String agentGroupId;
        private InferenceProgress inferenceProgress;

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

        public Builder inferenceProgress(InferenceProgress progress) {
            this.inferenceProgress = progress;
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

        public Builder agentSummaryInfo(AgentSummaryInfo agentSummaryInfo) {
            this.agentSummaryInfo = agentSummaryInfo;
            return this;
        }

        public Builder agentProgressInfo(AgentProgressInfo agentProgressInfo) {
            this.agentProgressInfo = agentProgressInfo;
            return this;
        }

        public Builder agentMode(boolean agentMode) {
            this.agentMode = agentMode;
            return this;
        }

        public Builder agentExecutionState(AgentExecutionState state) {
            this.agentExecutionState = state;
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

        public Builder voiceInput(boolean voiceInput) {
            this.voiceInput = voiceInput;
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

        public Builder agentGroupId(String groupId) {
            this.agentGroupId = groupId;
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
