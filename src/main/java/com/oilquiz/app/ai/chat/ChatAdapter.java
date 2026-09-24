package com.oilquiz.app.ai.chat;

import com.oilquiz.app.SmartQuizApplication;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.agent.ToolResultInterpreter;
import com.oilquiz.app.ai.chat.component.ComponentContentSplitter;
import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.chat.component.ComponentRegistry;
import com.oilquiz.app.ai.chat.render.MarkdownRenderer;
import com.oilquiz.app.ai.chat.render.RenderExecutor;

import io.noties.markwon.core.spans.TextViewSpan;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.animation.ValueAnimator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.util.DisplayMetrics;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.chat.parser.ThinkingTagConfig;

import com.oilquiz.app.theme.ThemeColors;
public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_USER = 0;
    private static final int VIEW_TYPE_AI = 1;
    private static final int VIEW_TYPE_SYSTEM = 2;
    private static final int VIEW_TYPE_THINKING = 3;
    private static final int VIEW_TYPE_TASK = 4;
    private static final int VIEW_TYPE_TOOL_CALL = 5;
    private static final int VIEW_TYPE_AGENT_STEP = 6;
    private static final int VIEW_TYPE_AGENT_SUMMARY = 7;
    private static final int VIEW_TYPE_ERROR = 8;
    private static final int VIEW_TYPE_TOOL_RESULT = 9;
    private static final int VIEW_TYPE_AGENT_REFLECTION = 10;
    private static final int VIEW_TYPE_SUMMARY = 11;
    private static final int VIEW_TYPE_INFERENCE_PROGRESS = 12;
    /** 上下文注入行（记忆/任务/工具目录等系统注入内容的展示条，点击看全文） */
    private static final int VIEW_TYPE_CONTEXT_INJECTION = 13;
    /** 系统提示词行（system prompt 变更记录/全文展示，点击看全文） */
    private static final int VIEW_TYPE_SYSTEM_PROMPT = 14;

    public static final String PAYLOAD_CONTENT_UPDATE = "content_update";
    public static final String PAYLOAD_STATUS_UPDATE = "status_update";
    public static final String PAYLOAD_EXPANDED_UPDATE = "expanded_update";
    public static final String PAYLOAD_THINKING_UPDATE = "thinking_update";
    public static final String PAYLOAD_ATTACHMENT_UPDATE = "attachment_update";
    public static final String PAYLOAD_INFERENCE_PROGRESS = "inference_progress";
    public static final String PAYLOAD_AGENT_UPDATE = "agent_update";

    private final List<ChatMessage> messages;
    private final OnActionClickListener actionClickListener;
    private OnRetryClickListener retryClickListener;
    private OnMessageClickListener messageClickListener;
    private MessageAttachmentAdapter.OnAttachmentClickListener attachmentClickListener;
    private RecyclerView attachedRecyclerView;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
    private final SimpleDateFormat groupDateFormat = new SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault());

    // 时间分组间隔（30分钟，超过则显示完整日期）
    private static final long TIME_GROUP_INTERVAL_MS = 30 * 60 * 1000;

    // 推理状态管理
    private InferenceStateManager inferenceStateManager;
    private InferenceProgressUpdateListener inferenceProgressListener;

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        this.attachedRecyclerView = recyclerView;
        // 初始化 Markwon 渲染引擎（只需一次）
        MarkdownRenderer.init(recyclerView.getContext());
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        this.attachedRecyclerView = null;
    }

    /** 获取屏幕宽度（像素） */
    private int getScreenWidth(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return metrics.widthPixels;
    }

    public interface OnActionClickListener {
        void onAction(ChatMessage.Action action);
    }

    public interface OnRetryClickListener {
        void onRetry(String messageId);
    }

    public interface OnMessageClickListener {
        void onMessageClick(ChatMessage message);
        void onMessageLongClick(ChatMessage message);
    }

    public void setRetryClickListener(OnRetryClickListener listener) {
        this.retryClickListener = listener;
    }

    public void setMessageClickListener(OnMessageClickListener listener) {
        this.messageClickListener = listener;
    }

    // 动画配置
    private static final long ANIMATION_DURATION = 200;
    private boolean animationsEnabled = true;
    /** 已播放进入动画的消息 id（避免滚动回滚后重复动画） */
    private final java.util.Set<String> animatedMessageIds = new java.util.HashSet<>();

    // 选择模式
    private boolean selectionMode = false;
    private final java.util.Set<String> selectedMessageIds = new java.util.HashSet<>();
    private OnSelectionChangeListener selectionChangeListener;

    public ChatAdapter(List<ChatMessage> messages, OnActionClickListener actionClickListener) {
        this.messages = messages;
        this.actionClickListener = actionClickListener;
        this.inferenceStateManager = InferenceStateManager.getInstance();
        setupInferenceStateListener();
        setHasStableIds(true);
    }

    /**
     * 设置推理状态监听器
     */
    private void setupInferenceStateListener() {
        inferenceStateManager.setStateChangeListener(new InferenceStateManager.StateChangeListener() {
            @Override
            public void onStateChanged(String messageId, InferenceStateManager.InferenceState oldState,
                                       InferenceStateManager.InferenceState newState,
                                       InferenceStateManager.StateDetails details) {
                // 找到对应的消息位置并更新
                int position = findMessagePositionById(messageId);
                if (position != -1) {
                    notifyItemChanged(position, PAYLOAD_INFERENCE_PROGRESS);
                }

                // 通知外部监听器
                if (inferenceProgressListener != null) {
                    inferenceProgressListener.onInferenceStateChanged(messageId, oldState, newState, details);
                }
            }

            @Override
            public void onProgressUpdated(String messageId, InferenceStateManager.StateDetails details) {
                int position = findMessagePositionById(messageId);
                if (position != -1) {
                    notifyItemChanged(position, PAYLOAD_INFERENCE_PROGRESS);
                }

                if (inferenceProgressListener != null) {
                    inferenceProgressListener.onInferenceProgressUpdated(messageId, details);
                }
            }
        });
    }

    /**
     * 根据消息ID查找位置
     */
    private int findMessagePositionById(String messageId) {
        for (int i = 0; i < messages.size(); i++) {
            if (messageId.equals(messages.get(i).id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 设置推理进度更新监听器
     */
    public void setInferenceProgressListener(InferenceProgressUpdateListener listener) {
        this.inferenceProgressListener = listener;
    }

    /**
     * 推理进度更新监听器接口
     */
    public interface InferenceProgressUpdateListener {
        void onInferenceStateChanged(String messageId, InferenceStateManager.InferenceState oldState,
                                     InferenceStateManager.InferenceState newState,
                                     InferenceStateManager.StateDetails details);
        void onInferenceProgressUpdated(String messageId, InferenceStateManager.StateDetails details);
    }

    @Override
    public long getItemId(int position) {
        if (position >= 0 && position < messages.size()) {
            ChatMessage message = messages.get(position);
            return message.id != null ? message.id.hashCode() : position;
        }
        return position;
    }

    @Override
    public int getItemViewType(int position) {
        if (position < 0 || position >= messages.size()) return VIEW_TYPE_SYSTEM;
        ChatMessage message = messages.get(position);
        switch (message.type) {
            case USER:
                return VIEW_TYPE_USER;
            case AI:
                return VIEW_TYPE_AI;
            case SYSTEM:
                if (message.systemType == ChatMessage.SystemMessageType.CONTEXT_INJECTION) {
                    return VIEW_TYPE_CONTEXT_INJECTION;
                }
                if (message.systemType == ChatMessage.SystemMessageType.SYSTEM_PROMPT) {
                    return VIEW_TYPE_SYSTEM_PROMPT;
                }
                return VIEW_TYPE_SYSTEM;
            case THINKING:
                return VIEW_TYPE_THINKING;
            case TASK_BREAKDOWN:
            case TASK_PROGRESS:
            case SUMMARY:
                // 合并显示：这些类型无消息产生（遗留），统一用系统消息样式展示
                return VIEW_TYPE_SYSTEM;
            case AGENT_SUMMARY:
                // Agent 执行汇总：独立卡片（耗时/步骤/工具/Token）
                return VIEW_TYPE_AGENT_SUMMARY;
            case AGENT_REFLECTION:
                // Agent 反思：独立卡片（反思总结/改进方向）
                return VIEW_TYPE_AGENT_REFLECTION;
            case TOOL_CALL:
            case TOOL_RESULT:
                // 合并显示：工具调用与结果统一用工具卡片渲染
                return VIEW_TYPE_TOOL_CALL;
            case AGENT_STEP:
                return VIEW_TYPE_AGENT_STEP;
            case ERROR:
                return VIEW_TYPE_ERROR;
            default:
                return VIEW_TYPE_SYSTEM;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case VIEW_TYPE_USER:
                return new UserMessageViewHolder(inflater.inflate(R.layout.item_user_message, parent, false));
            case VIEW_TYPE_AI:
                // AI 消息完全动态构建（不依赖布局文件/id），杜绝 id 错乱
                return createAiMessageItem(parent.getContext());
            case VIEW_TYPE_SYSTEM:
            case VIEW_TYPE_CONTEXT_INJECTION:
            case VIEW_TYPE_SYSTEM_PROMPT:
                return new SystemMessageViewHolder(inflater.inflate(R.layout.item_system_message, parent, false));
            case VIEW_TYPE_THINKING:
                return new ThinkingMessageViewHolder(inflater.inflate(R.layout.item_thinking_message, parent, false));
            case VIEW_TYPE_TASK:
                return new TaskMessageViewHolder(inflater.inflate(R.layout.item_task_message, parent, false));
            case VIEW_TYPE_TOOL_CALL:
                return new ToolCallViewHolder(inflater.inflate(R.layout.item_tool_call_message, parent, false));
            case VIEW_TYPE_AGENT_STEP:
                return new AgentStepViewHolder(inflater.inflate(R.layout.item_agent_step_message, parent, false));
            case VIEW_TYPE_AGENT_SUMMARY:
                return new AgentSummaryViewHolder(inflater.inflate(R.layout.item_agent_summary_message, parent, false));
            case VIEW_TYPE_ERROR:
                return new ErrorMessageViewHolder(inflater.inflate(R.layout.item_error_message, parent, false));
            case VIEW_TYPE_TOOL_RESULT:
                return new ToolResultViewHolder(inflater.inflate(R.layout.item_tool_result_message, parent, false));
            case VIEW_TYPE_AGENT_REFLECTION:
                return new AgentReflectionViewHolder(inflater.inflate(R.layout.item_agent_reflection_message, parent, false));
            case VIEW_TYPE_SUMMARY:
                return new SummaryMessageViewHolder(inflater.inflate(R.layout.item_summary_message, parent, false));
            default:
                return new SystemMessageViewHolder(inflater.inflate(R.layout.item_system_message, parent, false));
        }
    }

    /**
     * 动态构建 AI 消息视图树（完全代码创建，不依赖布局文件与 id）。
     * 结构：根容器 → 主气泡（思考区 + 工具卡片 + 正文）→ 展开按钮 → 操作按钮 → 状态 → 模型信息 → 时间戳。
     */
    private AIMessageViewHolder createAiMessageItem(Context ctx) {
        int dp4 = dpToPx(4, ctx);
        int dp6 = dpToPx(6, ctx);
        int dp10 = dpToPx(10, ctx);
        int dp12 = dpToPx(12, ctx);
        int dp14 = dpToPx(14, ctx);

        // 主题色解析
        int colorOnSurface = resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurface);
        int colorOnSurfaceVariant = resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant);
        int colorOutlineVariant = resolveAttrColor(ctx, com.google.android.material.R.attr.colorOutlineVariant);
        int colorPrimary = ThemeColors.attr(ctx, R.attr.colorPrimary);
        int colorTextSecondary = ThemeColors.attr(ctx, R.attr.colorControlTextSecondary);
        int colorTextTertiary = ThemeColors.attr(ctx, R.attr.colorControlTextHint);

        // ===== 根容器 =====
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp4, dp4, dp4, dp4);
        RecyclerView.LayoutParams rootLp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.setLayoutParams(rootLp);

        // ===== blockLabel（未使用，保持隐藏） =====
        TextView blockLabel = new TextView(ctx);
        blockLabel.setTextSize(10f);
        blockLabel.setTextColor(colorTextSecondary);
        blockLabel.setVisibility(View.GONE);
        LinearLayout.LayoutParams blockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blockLp.bottomMargin = dpToPx(1, ctx);
        blockLabel.setLayoutParams(blockLp);
        root.addView(blockLabel);

        // ===== 横向容器（气泡在 weight=1 列内） =====
        LinearLayout hRow = new LinearLayout(ctx);
        hRow.setOrientation(LinearLayout.HORIZONTAL);
        hRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(hRow);

        LinearLayout vCol = new LinearLayout(ctx);
        vCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams vColLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        vColLp.weight = 1f;
        vCol.setLayoutParams(vColLp);
        hRow.addView(vCol);

        // ===== 主气泡：思考区 + 工具卡片 + 正文 全部包裹 =====
        LinearLayout bubble = new LinearLayout(ctx);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackgroundResource(R.drawable.ai_message_background);
        bubble.setPadding(dp12, dp10, dp12, dp10);
        bubble.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        vCol.addView(bubble);

        // --- Agent 执行状态行（思考中/调用工具/完成），实时步骤可视化 ---
        TextView agentStatus = new TextView(ctx);
        agentStatus.setTextSize(11f);
        agentStatus.setTextColor(colorOnSurfaceVariant);
        agentStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams asLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        asLp.bottomMargin = dpToPx(4, ctx);
        agentStatus.setLayoutParams(asLp);
        bubble.addView(agentStatus);

        // --- 思考标签 ---
        TextView thinkingLabel = new TextView(ctx);
        thinkingLabel.setText(R.string.chat_thinking_process);
        thinkingLabel.setTextSize(11f);
        thinkingLabel.setTextColor(colorOnSurfaceVariant);
        thinkingLabel.setClickable(true);
        thinkingLabel.setFocusable(true);
        thinkingLabel.setForeground(getSelectableItemBackground(ctx));
        thinkingLabel.setVisibility(View.GONE);
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlLp.bottomMargin = dpToPx(2, ctx);
        thinkingLabel.setLayoutParams(tlLp);
        bubble.addView(thinkingLabel);

        // --- 思考内容 ---
        TextView thinkingContent = new TextView(ctx);
        thinkingContent.setTextColor(colorOnSurfaceVariant);
        thinkingContent.setTextSize(12f);
        thinkingContent.setLineSpacing(0f, 1.2f);
        thinkingContent.setVisibility(View.GONE);
        thinkingContent.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bubble.addView(thinkingContent);

        // --- 思考与正文分隔线 ---
        View thinkingDivider = new View(ctx);
        thinkingDivider.setBackgroundColor(colorOutlineVariant);
        thinkingDivider.setVisibility(View.GONE);
        LinearLayout.LayoutParams tdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1, ctx));
        tdLp.topMargin = dp6;
        tdLp.bottomMargin = dpToPx(2, ctx);
        thinkingDivider.setLayoutParams(tdLp);
        bubble.addView(thinkingDivider);

        // --- 推理进度 ---
        InferenceProgressView inferenceProgressView = new InferenceProgressView(ctx);
        inferenceProgressView.setVisibility(View.GONE);
        inferenceProgressView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bubble.addView(inferenceProgressView);

        // --- 工具卡片组件容器 ---
        LinearLayout componentContainer = new LinearLayout(ctx);
        componentContainer.setOrientation(LinearLayout.VERTICAL);
        componentContainer.setVisibility(View.GONE);
        componentContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bubble.addView(componentContainer);

        // --- 附件 RecyclerView ---
        androidx.recyclerview.widget.RecyclerView attachmentsRecycler = new androidx.recyclerview.widget.RecyclerView(ctx);
        attachmentsRecycler.setClipToPadding(false);
        attachmentsRecycler.setVisibility(View.GONE);
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arLp.bottomMargin = dpToPx(3, ctx);
        attachmentsRecycler.setLayoutParams(arLp);
        bubble.addView(attachmentsRecycler);

        // --- 内容宿主（正文；含组件标记时动态追加段落） ---
        LinearLayout contentHost = new LinearLayout(ctx);
        contentHost.setOrientation(LinearLayout.VERTICAL);
        contentHost.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bubble.addView(contentHost);

        // --- 正文 ---
        TextView messageText = new TextView(ctx);
        messageText.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        messageText.setTextColor(colorOnSurface);
        messageText.setTextSize(14f);
        messageText.setLineSpacing(0f, 1.3f);
        messageText.setTextIsSelectable(true);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            messageText.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY);
            messageText.setHyphenationFrequency(android.graphics.text.LineBreaker.HYPHENATION_FREQUENCY_NORMAL);
        }
        messageText.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentHost.addView(messageText);

        // --- Agent 任务汇总行（完成后：工具数/轮次/耗时） ---
        TextView agentSummary = new TextView(ctx);
        agentSummary.setTextSize(11f);
        agentSummary.setTextColor(colorOnSurfaceVariant);
        agentSummary.setVisibility(View.GONE);
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sumLp.topMargin = dpToPx(6, ctx);
        agentSummary.setLayoutParams(sumLp);
        bubble.addView(agentSummary);

        // ===== 展开按钮（主回复不再折叠，恒隐藏） =====
        TextView btnExpand = new TextView(ctx);
        btnExpand.setText(R.string.chat_expand_full);
        btnExpand.setTextSize(11f);
        btnExpand.setTextColor(colorPrimary);
        btnExpand.setPadding(dpToPx(3, ctx), dpToPx(3, ctx), dpToPx(3, ctx), dpToPx(3, ctx));
        btnExpand.setClickable(true);
        btnExpand.setFocusable(true);
        btnExpand.setForeground(getSelectableItemBackground(ctx));
        btnExpand.setVisibility(View.GONE);
        LinearLayout.LayoutParams beLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        beLp.topMargin = dpToPx(2, ctx);
        btnExpand.setLayoutParams(beLp);
        root.addView(btnExpand);

        // ===== 操作按钮行 =====
        LinearLayout actionButtons = new LinearLayout(ctx);
        actionButtons.setOrientation(LinearLayout.HORIZONTAL);
        actionButtons.setVisibility(View.GONE);
        actionButtons.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(actionButtons);

        TextView btnCopy = createActionButton(ctx, R.string.chat_copy, colorTextSecondary, true);
        TextView btnSpeak = createActionButton(ctx, R.string.chat_speak, colorTextSecondary, true);
        TextView btnShare = createActionButton(ctx, R.string.chat_share, colorTextSecondary, true);
        TextView btnRegenerate = createActionButton(ctx, R.string.chat_regenerate, colorTextSecondary, true);
        TextView btnNewChat = createActionButton(ctx, R.string.chat_new_chat, colorTextSecondary, false);
        actionButtons.addView(btnCopy);
        actionButtons.addView(btnSpeak);
        actionButtons.addView(btnShare);
        actionButtons.addView(btnRegenerate);
        actionButtons.addView(btnNewChat);

        // ===== 状态行 =====
        LinearLayout statusRow = new LinearLayout(ctx);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srLp.topMargin = dpToPx(1, ctx);
        statusRow.setLayoutParams(srLp);
        root.addView(statusRow);

        ImageView statusIcon = new ImageView(ctx);
        statusIcon.setVisibility(View.GONE);
        statusIcon.setLayoutParams(new LinearLayout.LayoutParams(dp14, dp14));
        statusRow.addView(statusIcon);

        TextView statusText = new TextView(ctx);
        statusText.setTextSize(10f);
        statusText.setTextColor(colorTextSecondary);
        statusText.setVisibility(View.GONE);
        statusRow.addView(statusText);

        // ===== 在线模型信息行 =====
        LinearLayout modelInfoContainer = new LinearLayout(ctx);
        modelInfoContainer.setOrientation(LinearLayout.HORIZONTAL);
        modelInfoContainer.setGravity(android.view.Gravity.CENTER_VERTICAL);
        modelInfoContainer.setPadding(dp6, dp6, dp6, dp6);
        modelInfoContainer.setVisibility(View.GONE);
        modelInfoContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(modelInfoContainer);

        View modelStatusIndicator = new View(ctx);
        modelStatusIndicator.setBackgroundResource(R.drawable.status_indicator_unknown);
        LinearLayout.LayoutParams msiLp = new LinearLayout.LayoutParams(dpToPx(5, ctx), dpToPx(5, ctx));
        msiLp.setMarginStart(dpToPx(5, ctx));
        modelStatusIndicator.setLayoutParams(msiLp);
        modelInfoContainer.addView(modelStatusIndicator);

        TextView modelNameText = new TextView(ctx);
        modelNameText.setTextSize(10f);
        modelNameText.setTextColor(colorTextSecondary);
        LinearLayout.LayoutParams mntLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        mntLp.weight = 1f;
        mntLp.setMarginStart(dpToPx(5, ctx));
        modelNameText.setLayoutParams(mntLp);
        modelInfoContainer.addView(modelNameText);

        TextView modelLatencyText = new TextView(ctx);
        modelLatencyText.setTextSize(10f);
        modelLatencyText.setTextColor(colorTextTertiary);
        modelInfoContainer.addView(modelLatencyText);

        TextView modelCostText = new TextView(ctx);
        modelCostText.setTextSize(10f);
        modelCostText.setTextColor(colorTextTertiary);
        LinearLayout.LayoutParams mctLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mctLp.setMarginStart(dp6);
        modelCostText.setLayoutParams(mctLp);
        modelInfoContainer.addView(modelCostText);

        // ===== 时间戳 =====
        TextView timestampText = new TextView(ctx);
        timestampText.setTextSize(10f);
        timestampText.setTextColor(colorTextSecondary);
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tsLp.setMarginStart(dp6);
        timestampText.setLayoutParams(tsLp);
        root.addView(timestampText);

        // 组装为动态根容器（携带全部子视图引用，避免 findViewById）
        DynamicAiMessageRoot dynamicRoot = new DynamicAiMessageRoot(ctx, blockLabel, messageText,
                contentHost, componentContainer, thinkingLabel, thinkingContent, thinkingDivider,
                agentStatus, agentSummary, actionButtons, btnCopy, btnSpeak, btnShare, btnRegenerate,
                btnNewChat, timestampText, statusIcon, statusText, btnExpand, inferenceProgressView,
                attachmentsRecycler, modelInfoContainer, modelStatusIndicator, modelNameText,
                modelLatencyText, modelCostText);
        dynamicRoot.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dynamicRoot.addView(root);
        return new AIMessageViewHolder(dynamicRoot);
    }

    /** 解析主题 attr 颜色 */
    private int resolveAttrColor(Context ctx, int attrRes) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (ctx.getTheme().resolveAttribute(attrRes, tv, true)) {
            return tv.data;
        }
        return ThemeColors.get(R.color.hc_ff1e293b); // 兜底
    }

    /** 获取 selectableItemBackground（点击水波纹） */
    private android.graphics.drawable.Drawable getSelectableItemBackground(Context ctx) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            return ctx.getDrawable(tv.resourceId);
        }
        return null;
    }

    /** 创建操作按钮（小字、可点击） */
    private TextView createActionButton(Context ctx, int textRes, int color, boolean marginEnd) {
        TextView tv = new TextView(ctx);
        tv.setText(textRes);
        tv.setTextSize(11f);
        tv.setTextColor(color);
        tv.setPadding(dpToPx(3, ctx), dpToPx(3, ctx), dpToPx(3, ctx), dpToPx(3, ctx));
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setForeground(getSelectableItemBackground(ctx));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (marginEnd) {
            lp.setMarginEnd(dpToPx(10, ctx));
        }
        tv.setLayoutParams(lp);
        return tv;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        String timeStr = shouldShowDate(position) ? dateFormat.format(new Date(message.timestamp)) : timeFormat.format(new Date(message.timestamp));

        // ===== Agent执行组折叠：非header且组已折叠 → 隐藏（height=0） =====
        if (message.agentGroupId != null && !message.isAgentGroupHeader) {
            ChatMessage header = findAgentGroupHeader(message.agentGroupId);
            if (header != null && header.agentGroupCollapsed) {
                holder.itemView.getLayoutParams().height = 0;
                holder.itemView.setVisibility(View.GONE);
                return;
            }
        }
        // 恢复正常高度（防止复用残留）
        if (holder.itemView.getLayoutParams() != null && holder.itemView.getLayoutParams().height == 0) {
            holder.itemView.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        holder.itemView.setVisibility(View.VISIBLE);

        switch (holder.getItemViewType()) {
            case VIEW_TYPE_USER:
                bindUserMessage((UserMessageViewHolder) holder, message, timeStr);
                break;
            case VIEW_TYPE_AI:
                bindAIMessage((AIMessageViewHolder) holder, message, timeStr, position);
                break;
            case VIEW_TYPE_SYSTEM:
            case VIEW_TYPE_CONTEXT_INJECTION:
            case VIEW_TYPE_SYSTEM_PROMPT:
                bindSystemMessage((SystemMessageViewHolder) holder, message);
                break;
            case VIEW_TYPE_THINKING:
                bindThinkingMessage((ThinkingMessageViewHolder) holder, message);
                break;
            case VIEW_TYPE_TASK:
                bindTaskMessage((TaskMessageViewHolder) holder, message);
                break;
            case VIEW_TYPE_TOOL_CALL:
                bindToolCallMessage((ToolCallViewHolder) holder, message);
                break;
            case VIEW_TYPE_AGENT_STEP:
                bindAgentStepMessage((AgentStepViewHolder) holder, message);
                break;
            case VIEW_TYPE_AGENT_SUMMARY:
                bindAgentSummaryMessage((AgentSummaryViewHolder) holder, message);
                break;
            case VIEW_TYPE_ERROR:
                bindErrorMessage((ErrorMessageViewHolder) holder, message);
                break;
            case VIEW_TYPE_TOOL_RESULT:
                bindToolResultMessage((ToolResultViewHolder) holder, message);
                break;
            case VIEW_TYPE_AGENT_REFLECTION:
                bindAgentReflectionMessage((AgentReflectionViewHolder) holder, message);
                break;
            case VIEW_TYPE_SUMMARY:
                bindSummaryMessage((SummaryMessageViewHolder) holder, message);
                break;
        }
        // 消息进入动画：仅新消息触发（id 去重），用户消息右滑入、AI/系统左滑入
        setAnimation(holder.itemView, message);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
            return;
        }
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        for (Object payload : payloads) {
            if (PAYLOAD_CONTENT_UPDATE.equals(payload)) {
                if (holder instanceof AIMessageViewHolder) {
                    AIMessageViewHolder aiHolder = (AIMessageViewHolder) holder;
                    aiHolder.itemView.post(() -> {
                        int availableWidth = aiHolder.itemView.getWidth()
                            - aiHolder.itemView.getPaddingLeft()
                            - aiHolder.itemView.getPaddingRight()
                            - dpToPx(2, aiHolder.itemView.getContext());
                        if (availableWidth <= 0) {
                            // 未布局完成（新建/重建 holder 首帧）：等布局后按真实宽度渲染，
                            // 避免表格按全屏宽兜底导致列宽错位（重进页面正常、生成完成错位即此根因）
                            renderWithCorrectWidth(aiHolder, message);
                            return;
                        }
                        // 插入式组件渲染：文本段/组件段交替，组件标记在流式中实时生效
                        bindMessageContent(aiHolder, message, availableWidth);
                    });
                    updateThinkingContent(aiHolder, message);
                    handleLongContent(aiHolder, message);
                } else if (holder instanceof UserMessageViewHolder) {
                    ((UserMessageViewHolder) holder).messageText.setText(message.content);
                } else if (holder instanceof ThinkingMessageViewHolder) {
                    ((ThinkingMessageViewHolder) holder).bind(message);
                }
            } else if (PAYLOAD_STATUS_UPDATE.equals(payload)) {
                if (holder instanceof AIMessageViewHolder) {
                    updateMessageStatus((AIMessageViewHolder) holder, message);
                }
            } else if (PAYLOAD_INFERENCE_PROGRESS.equals(payload)) {
                if (holder instanceof AIMessageViewHolder) {
                    updateMessageStatus((AIMessageViewHolder) holder, message);
                }
            } else if (PAYLOAD_AGENT_UPDATE.equals(payload)) {
                // Agent执行状态更新 - 通过payload增量刷新，不触发完整rebind
                // （AgentExecutionPanel 已解绑移除，无需处理）
            } else if (PAYLOAD_EXPANDED_UPDATE.equals(payload)) {
                if (holder instanceof AIMessageViewHolder) {
                    toggleMessageExpansion((AIMessageViewHolder) holder, message);
                }
            } else if (PAYLOAD_THINKING_UPDATE.equals(payload)) {
                if (holder instanceof AIMessageViewHolder) {
                    updateThinkingContent((AIMessageViewHolder) holder, message);
                } else if (holder instanceof ThinkingMessageViewHolder) {
                    ((ThinkingMessageViewHolder) holder).bind(message);
                }
            } else if (payload instanceof String) {
                // 处理 "selection_change" 等自定义 String payload
                // 仅更新选中状态视觉反馈，不修改消息内容
                if (holder instanceof AIMessageViewHolder) {
                    handleLongContent((AIMessageViewHolder) holder, message);
                }
            }
        }
    }

    private boolean shouldShowDate(int position) {
        if (position == 0) return true;
        if (position >= messages.size()) return false;
        long currentTime = messages.get(position).timestamp;
        long prevTime = messages.get(position - 1).timestamp;
        return currentTime - prevTime > TIME_GROUP_INTERVAL_MS;
    }

    /**
     * 设置 itemView 的点击/长按交互。
     * 仅在设置了 messageClickListener 时才让 itemView 可点击，避免干扰
     * messageText 的 textIsSelectable 文本选择功能（长按选择文本）。
     * ViewHolder 复用时需重置，否则旧状态会残留。
     */
    private void setupItemViewInteraction(View itemView, ChatMessage message) {
        if (messageClickListener != null) {
            itemView.setOnClickListener(v -> messageClickListener.onMessageClick(message));
            itemView.setOnLongClickListener(v -> {
                messageClickListener.onMessageLongClick(message);
                return true;
            });
        } else {
            // 清除监听器并显式置为不可点击，确保 messageText 长按选择文本不被拦截
            itemView.setOnClickListener(null);
            itemView.setOnLongClickListener(null);
            itemView.setClickable(false);
            itemView.setLongClickable(false);
        }
    }

    private void bindUserMessage(UserMessageViewHolder holder, ChatMessage message, String timeStr) {
        // 语音消息标识：来源语音的 USER 消息在气泡上方显示 "🎤 语音" 标签，与文字消息区分
        if (holder.blockLabel != null) {
            if (message.voiceInput) {
                holder.blockLabel.setText(SmartQuizApplication.getAppContext().getString(R.string.h_d38d0f21));
                holder.blockLabel.setVisibility(View.VISIBLE);
            } else {
                holder.blockLabel.setText("");
                holder.blockLabel.setVisibility(View.GONE);
            }
        }
        holder.messageText.setText(message.content);
        holder.timestampText.setText(timeStr);

        setupItemViewInteraction(holder.itemView, message);

        bindMessageStatus(holder.statusIcon, holder.statusText, message.status);

        if (message.hasError()) {
            holder.statusIcon.setImageResource(R.drawable.ic_error);
            holder.statusIcon.setColorFilter(holder.itemView.getContext().getColor(R.color.error));
        }

        // 渲染用户消息中的附件（图片等）
        bindUserAttachments(holder, message);
    }

    private void bindAIMessage(AIMessageViewHolder holder, ChatMessage message, String timeStr, int position) {
        // 消息引用绑定（2026-09-14）：思考轮独立折叠状态 / 轮次组件 id 锚点读写
        holder.holderMessage = message;
        // 获取 messageText 的实际宽度用于表格自动换行：
        // 已布局（rebind 场景）立即按真实宽度渲染；未布局则等测量完成后渲染。
        // 修复：生成完成 rebind 时 holder 已布局、onGlobalLayoutListener 不再触发，
        // 若只依赖 listener 会导致表格仍按流式期间的错误宽度（全屏宽兜底）渲染而排版错位。
        renderWithCorrectWidth(holder, message);
        if (holder.messageText.getText().length() == 0) {
            // 如果 onGlobalLayout 还没执行，先设置空文本
            holder.messageText.setText("");
        }
        holder.messageText.setMovementMethod(LinkMovementMethod.getInstance());
        holder.timestampText.setText(timeStr);
        // 时间栏只对最终轮（最后一条 AI 消息）显示；中间轮次隐藏
        holder.timestampText.setVisibility(isLastAiMessage(position) ? View.VISIBLE : View.GONE);

        // blockLabel 当前未使用，确保隐藏
        if (holder.blockLabel != null) {
            holder.blockLabel.setVisibility(View.GONE);
        }

        bindAttachments(holder, message);
        bindComponents(holder, message);
        updateThinkingContent(holder, message);
        bindAgentStepStatus(holder, message);
        updateMessageStatus(holder, message);
        bindModelInfo(holder, message);

        setupItemViewInteraction(holder.itemView, message);

        boolean lastAi = isLastAiMessage(position);
        // 统计栏（状态行 tokens/耗时/速度）只对最终轮显示；中间完成轮隐藏
        if (!lastAi && message.isCompleted()) {
            if (holder.statusText != null) holder.statusText.setVisibility(View.GONE);
            if (holder.statusIcon != null) holder.statusIcon.setVisibility(View.GONE);
        }

        if (message.isCompleted()) {
            // 操作栏（复制/朗读/分享/重新生成）只对最终轮显示；中间轮次隐藏
            if (holder.actionButtons != null) {
                holder.actionButtons.setVisibility(lastAi ? View.VISIBLE : View.GONE);
            }

            if (holder.btnCopy != null) holder.btnCopy.setOnClickListener(v -> {
                copyToClipboard(v.getContext(), message.content);
                if (actionClickListener != null) {
                    actionClickListener.onAction(ChatMessage.Action.copy(message.content));
                }
            });

            if (holder.btnSpeak != null) holder.btnSpeak.setOnClickListener(v -> {
                if (actionClickListener != null) {
                    actionClickListener.onAction(ChatMessage.Action.speak(message.id, message.content));
                }
            });

            if (holder.btnShare != null) holder.btnShare.setOnClickListener(v -> {
                shareText(v.getContext(), message.content);
            });

            if (holder.btnRegenerate != null) holder.btnRegenerate.setOnClickListener(v -> {
                if (actionClickListener != null) {
                    actionClickListener.onAction(ChatMessage.Action.regenerate(message.id));
                }
            });

            if (holder.btnNewChat != null) holder.btnNewChat.setOnClickListener(v -> {
                if (actionClickListener != null) {
                    actionClickListener.onAction(ChatMessage.Action.newChat());
                }
            });
        } else {
            if (holder.actionButtons != null) holder.actionButtons.setVisibility(View.GONE);
            // 清除按钮点击事件，避免 ViewHolder 复用时旧消息的监听器残留
            if (holder.btnCopy != null) holder.btnCopy.setOnClickListener(null);
            if (holder.btnSpeak != null) holder.btnSpeak.setOnClickListener(null);
            if (holder.btnShare != null) holder.btnShare.setOnClickListener(null);
            if (holder.btnRegenerate != null) holder.btnRegenerate.setOnClickListener(null);
            if (holder.btnNewChat != null) holder.btnNewChat.setOnClickListener(null);
        }

        handleLongContent(holder, message);
    }

    /**
     * 绑定 Agent 执行步骤状态行与任务汇总行（气泡内实时可视化）。
     * agentStepStatus：思考中/调用工具X/完成；agentSummary：完成后汇总。
     */
    private void bindAgentStepStatus(AIMessageViewHolder holder, ChatMessage message) {
        if (holder.agentStatus != null) {
            if (message.agentStepStatus != null && !message.agentStepStatus.isEmpty()) {
                holder.agentStatus.setText(message.agentStepStatus);
                holder.agentStatus.setVisibility(View.VISIBLE);
            } else {
                holder.agentStatus.setVisibility(View.GONE);
            }
        }
        if (holder.agentSummary != null) {
            if (message.agentSummary != null && !message.agentSummary.isEmpty()) {
                holder.agentSummary.setText(message.agentSummary);
                holder.agentSummary.setVisibility(View.VISIBLE);
            } else {
                holder.agentSummary.setVisibility(View.GONE);
            }
        }
    }

    /**
     * 多轮按轮次组装渲染：轮1(思考块+正文段) → 工具卡片 → 轮2(思考块+正文段) → …
     * contentRoundBounds 为工具调用边界；thinkingRounds 为每轮思考；components 为工具卡片。
     */
    private void renderRoundAssembled(AIMessageViewHolder holder, ChatMessage message, int availableWidth, Context ctx) {
        if (holder.contentHost == null) return;
        String content = message.content != null ? message.content : "";
        java.util.List<Integer> bounds = message.contentRoundBounds;
        java.util.List<String> thinks = message.thinkingRounds;
        java.util.List<ComponentData> comps = message.components;
        holder.contentHost.removeAllViews();
        // 折叠机制：thinkingLabel 作为折叠开关（始终可见，流式中显示实时思考预览），
        // 思考块仅在 thinkingExpanded 时内嵌到轮次中显示；折叠时只显示正文段+工具卡片
        boolean expanded = message.thinkingExpanded;
        if (holder.thinkingLabel != null) holder.thinkingLabel.setVisibility(View.VISIBLE);
        if (holder.thinkingContent != null) holder.thinkingContent.setVisibility(View.GONE);
        if (holder.thinkingDivider != null) holder.thinkingDivider.setVisibility(View.GONE);
        // 多轮折叠开关：点击切换展开/折叠（重建轮次视图）
        holder.thinkingLabel.setOnClickListener(v -> {
            message.thinkingExpanded = !message.thinkingExpanded;
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                notifyItemChanged(pos);
            }
        });

        int start = 0;
        int thinkIdx = 0;
        int compIdx = 0;
        java.util.List<String> thinkIds = message.thinkingRoundIds;
        for (int i = 0; i <= bounds.size(); i++) {
            int end = (i < bounds.size()) ? bounds.get(i) : content.length();
            if (end > content.length()) end = content.length();
            // 该轮思考块（第 i+1 轮思考，独立组件；消息级展开时显示，轮级独立折叠可收起）
            if (expanded && thinks != null && thinkIdx < thinks.size()) {
                String t = thinks.get(thinkIdx);
                String roundId = (thinkIds != null && thinkIdx < thinkIds.size()) ? thinkIds.get(thinkIdx) : null;
                if (t != null && !t.trim().isEmpty()) {
                    addRoundThinkingBlock(holder, ctx, thinkIdx + 1, roundId, t.trim(), availableWidth);
                }
                thinkIdx++;
            }
            // 该轮正文段
            String seg = start < content.length() ? content.substring(start, end) : "";
            if (seg != null && !seg.trim().isEmpty()) {
                TextView tv = createSegmentTextView(ctx, holder.messageText);
                setRenderedText(tv, seg, availableWidth);
                holder.contentHost.addView(tv);
            }
            start = end;
            // 该轮后的工具卡片（第 i 个工具调用）
            if (i < bounds.size() && comps != null && compIdx < comps.size()) {
                ComponentData comp = comps.get(compIdx);
                compIdx++;
                if (comp != null) {
                    View v = ComponentRegistry.getInstance().render(ctx, comp);
                    if (v == null) {
                        TextView tv = createSegmentTextView(ctx, holder.messageText);
                        tv.setText(SmartQuizApplication.getAppContext().getString(R.string.h_ce08c6dd)
                                + comp.type + SmartQuizApplication.getAppContext().getString(R.string.h_56e45313));
                        tv.setTextColor(ThemeColors.attr(ctx, R.attr.colorControlTextSecondary));
                        holder.contentHost.addView(tv);
                    } else {
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                        lp.topMargin = dpToPx(8, ctx);
                        holder.contentHost.addView(v, lp);
                    }
                }
            }
        }

        // 最终轮思考（流式中或未落库的当前轮思考，未进 thinkingRounds 时补一块；仅展开时显示）
        String curThink = message.thinkingContent != null ? message.thinkingContent.trim() : "";
        String lastThink = (thinks != null && !thinks.isEmpty()) ? thinks.get(thinks.size() - 1).trim() : "";
        if (expanded && !curThink.isEmpty() && !curThink.equals(lastThink)) {
            // 最终轮 id：优先取 thinkingRoundIds 末位（进行中的轮次 id），无则 null（无锚点，仅展示）
            String lastId = (thinkIds != null && !thinkIds.isEmpty()) ? thinkIds.get(thinkIds.size() - 1) : null;
            addRoundThinkingBlock(holder, ctx, thinkIdx + 1, lastId, curThink, availableWidth);
        }
    }

    /**
     * 添加一个轮次思考块（第N轮思考）：【独立 UI 组件】
     * 2026-09-14 升级：每个思考轮 = 独立容器（roundBox），tag 绑定该轮 THINKING 子 id（thinkingRoundIds），
     * - 标题行可【独立点击折叠/展开】该轮（状态存 message.thinkingCollapsedRounds，重建后保留）；
     * - 内容区按 id 可被 updateThinkingRound(roundId, content) 定位并热更新（不重建整条消息）。
     */
    private void addRoundThinkingBlock(AIMessageViewHolder holder, Context ctx, int roundNo,
                                       String roundId, String thinkText, int availableWidth) {
        // 独立容器：垂直布局 = 标题行 + 内容体
        LinearLayout roundBox = new LinearLayout(ctx);
        roundBox.setOrientation(LinearLayout.VERTICAL);
        roundBox.setTag(roundId); // id 锚点：updateThinkingRound 按此定位
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dpToPx(roundNo == 1 ? 0 : 10, ctx);
        roundBox.setLayoutParams(rlp);

        // 标题行（可点击折叠开关）：第N轮思考 + 折叠/展开状态指示
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        titleRow.setClickable(true);
        titleRow.setFocusable(true);
        titleRow.setForeground(getSelectableItemBackground(ctx));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleRow.setLayoutParams(tlp);

        boolean collapsed = holder.holderMessage != null
                && holder.holderMessage.isThinkingRoundCollapsed(roundId);
        TextView title = new TextView(ctx);
        title.setText(SmartQuizApplication.getAppContext().getString(R.string.h_71505bcf)
                + roundNo + SmartQuizApplication.getAppContext().getString(R.string.h_1d8b034e)
                + (collapsed ? "  ▸" : "  ▾"));
        title.setTextSize(11f);
        title.setTextColor(ThemeColors.attr(ctx, R.attr.colorPrimary));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        title.setLayoutParams(tl);
        titleRow.addView(title);

        TextView body = new TextView(ctx);
        body.setTextSize(12f);
        body.setTextColor(ThemeColors.attr(ctx, R.attr.colorOnSurfaceVariant));
        body.setLineSpacing(0f, 1.2f);
        body.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 折叠态：展示"段落首行摘要"预览（dsh ReasoningRow 同款），展开态展示全文
        if (collapsed) {
            String preview = thinkingPreview(thinkText, false);
            body.setText(preview.isEmpty() ? "…" : preview + "…");
            body.setMaxLines(1);
            body.setEllipsize(android.text.TextUtils.TruncateAt.END);
            body.setAlpha(0.55f);
            body.setVisibility(View.VISIBLE);
        } else {
            setRenderedText(body, thinkText, availableWidth);
            body.setMaxLines(Integer.MAX_VALUE);
            body.setAlpha(1f);
            body.setVisibility(View.VISIBLE);
        }
        // 内容体 tag 前缀：updateThinkingRound 定位内容 TextView
        body.setTag("round_body_" + roundId);

        titleRow.setOnClickListener(v -> {
            if (holder.holderMessage != null) {
                holder.holderMessage.toggleThinkingRoundCollapsed(roundId);
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    notifyItemChanged(pos);
                }
            }
        });

        roundBox.addView(titleRow);
        roundBox.addView(body);
        holder.contentHost.addView(roundBox);
    }

    /**
     * 按思考轮 id 定位并更新单个思考轮内容（不重建整条消息）。
     * @param roundId 该轮 THINKING 子 id（thinkingRoundIds 元素）
     * @param newContent 该轮新内容
     * @return 是否找到并更新成功
     */
    public boolean updateThinkingRound(String roundId, String newContent) {
        if (roundId == null) return false;
        boolean updated = false;
        for (int i = 0; i < getItemCount(); i++) {
            AIMessageViewHolder holder = null;
            RecyclerView.ViewHolder vh = attachedRecyclerView != null
                    ? attachedRecyclerView.findViewHolderForAdapterPosition(i) : null;
            if (vh instanceof AIMessageViewHolder) {
                holder = (AIMessageViewHolder) vh;
            }
            if (holder == null || holder.contentHost == null) continue;
            for (int c = 0; c < holder.contentHost.getChildCount(); c++) {
                View child = holder.contentHost.getChildAt(c);
                if (child != null && roundId.equals(child.getTag())) {
                    // 找到轮次容器：更新内容体（2026-09-14：走 setRenderedText 全量 Markdown 渲染，
                    // 直接 setText 会把 **加粗**/代码块/列表显示成原文）
                    for (int b = 0; b < ((LinearLayout) child).getChildCount(); b++) {
                        View sub = ((LinearLayout) child).getChildAt(b);
                        if (sub != null && ("round_body_" + roundId).equals(sub.getTag())) {
                            int w = holder.itemView.getWidth()
                                    - holder.itemView.getPaddingLeft()
                                    - holder.itemView.getPaddingRight();
                            if (w <= 0) w = getScreenWidth(holder.itemView.getContext());
                            String nc = newContent != null ? newContent : "";
                            boolean roundCollapsed = holder.holderMessage != null
                                    && holder.holderMessage.isThinkingRoundCollapsed(roundId);
                            if (roundCollapsed) {
                                // 折叠轮：仅刷新摘要预览（不打断折叠态）
                                String preview = thinkingPreview(nc, true);
                                ((TextView) sub).setText(preview.isEmpty() ? "…" : preview + "…");
                            } else {
                                setRenderedText((TextView) sub, nc, w);
                            }
                            updated = true;
                            break;
                        }
                    }
                    break;
                }
            }
        }
        return updated;
    }

    private void updateThinkingContent(AIMessageViewHolder holder, ChatMessage message) {
        // 判断是否为流式生成中
        boolean isStreaming = message.status == ChatMessage.MessageStatus.GENERATING
                || message.status == ChatMessage.MessageStatus.IN_PROGRESS;

        // 分隔线仅在思考区实际显示时可见（思考内容与正文之间）
        boolean showDivider = false;

        // 多轮思考：分块展示（每轮加粗标题 + 段落分隔，布局上明显分开）
        String displayContent = message.thinkingContent != null ? message.thinkingContent : "";
        if (message.thinkingRounds != null && !message.thinkingRounds.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            List<String> rounds = message.thinkingRounds;
            // 流式时：已完成轮次 + 当前轮；非流式时：全部轮次
            for (int i = 0; i < rounds.size(); i++) {
                String r = rounds.get(i);
                if (r == null || r.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append("\n\n");
                String label = SmartQuizApplication.getAppContext().getString(R.string.h_71505bcf)
                        + (i + 1) + SmartQuizApplication.getAppContext().getString(R.string.h_1d8b034e);
                sb.append("**").append(label).append("**\n").append(r.trim());
            }
            // 当前轮（最后一轮,thinkingContent 可能等于 thinkingRounds 末位，去重）
            String cur = displayContent.trim();
            String lastRound = rounds.isEmpty() ? "" : rounds.get(rounds.size() - 1).trim();
            if (!cur.isEmpty() && !cur.equals(lastRound)) {
                if (sb.length() > 0) sb.append("\n\n");
                String label = SmartQuizApplication.getAppContext().getString(R.string.h_71505bcf)
                        + (rounds.size() + 1) + SmartQuizApplication.getAppContext().getString(R.string.h_1d8b034e);
                sb.append("**").append(label).append("**\n").append(cur);
            }
            displayContent = sb.toString();
        }

        if (displayContent != null && !displayContent.isEmpty()) {
            holder.thinkingLabel.setVisibility(View.VISIBLE);

            // 清理思考标签并格式化内容（标签来自 chat template，不硬编码）
            String cleanedContent = stripThinkingTagMarkers(displayContent);

            // 如果内容为空，隐藏思考区域
            if (cleanedContent.isEmpty()) {
                holder.thinkingLabel.setVisibility(View.GONE);
                holder.thinkingContent.setVisibility(View.GONE);
                if (holder.thinkingDivider != null) {
                    holder.thinkingDivider.setVisibility(View.GONE);
                }
                return;
            }

            // 设置思考内容：全量 Markdown 渲染（恢复简单直接渲染）
            holder.thinkingContent.post(() -> {
                int width = holder.itemView.getWidth()
                    - holder.itemView.getPaddingLeft()
                    - holder.itemView.getPaddingRight()
                    - dpToPx(2, holder.itemView.getContext());
                setRenderedText(holder.thinkingContent, cleanedContent, width);
            });
            holder.thinkingContent.setMovementMethod(LinkMovementMethod.getInstance());

            // 展开状态完全由 thinkingExpanded 控制：思考中/思考完毕均默认折叠，
            // 用户点击标签展开（思考中展开可实时看到思考过程，思考后保留展开直到再次点击）。
            // 状态未变化时不重复 setVisibility/height，避免流式 token 更新打断点击展开/折叠动画
            boolean wasExpanded = holder.thinkingContent.getVisibility() == View.VISIBLE;
            if (message.thinkingExpanded) {
                if (!wasExpanded) {
                    cancelThinkingAnimator(holder);
                    holder.thinkingContent.setVisibility(View.VISIBLE);
                    holder.thinkingContent.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                }
                updateThinkingLabel(holder, true, isStreaming, message);
                showDivider = true;
            } else {
                if (wasExpanded) {
                    cancelThinkingAnimator(holder);
                    holder.thinkingContent.setVisibility(View.GONE);
                }
                updateThinkingLabel(holder, false, isStreaming, message);
            }

            // 点击展开/折叠，带动画效果
            holder.thinkingLabel.setOnClickListener(v -> {
                if (message.thinkingExpanded) {
                    collapseThinkingContent(holder, message);
                } else {
                    expandThinkingContent(holder, message);
                }
            });

        } else if (isStreaming) {
            // 流式中但思考内容还没到，显示思考中标签让用户有感知
            holder.thinkingLabel.setVisibility(View.VISIBLE);
            holder.thinkingContent.setVisibility(View.GONE);
            updateThinkingLabel(holder, false, true, message);
            // 点击占位标签也允许后续内容到来时展开
            holder.thinkingLabel.setOnClickListener(v -> {
                message.thinkingExpanded = !message.thinkingExpanded;
                if (message.thinkingContent != null && !message.thinkingContent.isEmpty()) {
                    if (message.thinkingExpanded) {
                        expandThinkingContent(holder, message);
                    } else {
                        collapseThinkingContent(holder, message);
                    }
                }
            });
        } else {
            holder.thinkingLabel.setVisibility(View.GONE);
            holder.thinkingContent.setVisibility(View.GONE);
        }

        if (holder.thinkingDivider != null) {
            holder.thinkingDivider.setVisibility(showDivider ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 更新思考标签文字
     * @param expanded 是否展开
     * @param isStreaming 是否处于流式生成中
     * @param message 绑定消息（折叠+流式时用于实时思考预览）
     */
    private void updateThinkingLabel(AIMessageViewHolder holder, boolean expanded, boolean isStreaming, ChatMessage message) {
        // 折叠态摘要预览上限 2 行，超长省略（dsh ReasoningRow 同款：折叠只展示摘要，不占正文空间）
        holder.thinkingLabel.setMaxLines(2);
        holder.thinkingLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (isStreaming) {
            // 流式中：提示用户"思考中"，并告知可点击展开/折叠
            if (expanded) {
                holder.thinkingLabel.setText(R.string.chat_thinking_streaming_collapse);
            } else {
                // 折叠 + 思考中：展示"最新已完成段落首行"摘要预览（dsh ReasoningRow 算法移植），
                // 段落完成时逐段推进，不再是旧版"最后40字符"粗暴截断
                String c = (message != null && message.thinkingContent != null)
                        ? message.thinkingContent : "";
                String preview = thinkingPreview(c, true);
                if (!preview.isEmpty()) {
                    holder.thinkingLabel.setText("🧠 思考中：" + preview + "…");
                } else {
                    holder.thinkingLabel.setText(R.string.chat_thinking_streaming_expand);
                }
            }
        } else {
            // 已完成：显示"思考过程"+ 段落首行摘要（让用户知道思考了什么）
            if (expanded) {
                holder.thinkingLabel.setText(R.string.chat_thinking_expanded);
            } else {
                String c = (message != null && message.thinkingContent != null)
                        ? message.thinkingContent : "";
                String preview = thinkingPreview(c, false);
                if (!preview.isEmpty()) {
                    holder.thinkingLabel.setText("💭 思考过程：" + preview + "…");
                } else {
                    // 折叠时附上内容长度，让用户知道不是空的
                    holder.thinkingLabel.setText(R.string.chat_thinking_collapsed);
                }
            }
        }
    }

    /**
     * dsh ReasoningRow.latestCompletedParagraphFirstLine 移植：取"最新已完成段落"的首行作折叠摘要。
     * - 段落按双换行（\n\n 及以上）分隔；
     * - 流式时：末段后无空行结尾视为"进行中"段落，摘要取上一个已完成段落；无已完成段落时退回进行中段；
     * - 非流式：取首个段落（思考的开篇即结论性内容）；
     * - 去掉 ** 强调与 ` 行内代码标记、压缩空白，超长截断（由调用方追加省略号）。
     */
    private static String thinkingPreview(String content, boolean streaming) {
        if (content == null || content.trim().isEmpty()) return "";
        String t = content.trim();
        String[] paras = t.split("\n\\s*\n");
        // 用未 trim 的原文判断末段是否进行中（trim 会吃掉尾部空行分隔，导致已完成误判为进行中）
        int idx;
        if (streaming) {
            boolean lastInProgress = !content.matches("(?s).*\\n\\s*\\n\\s*$");
            idx = (lastInProgress && paras.length >= 2) ? paras.length - 2 : paras.length - 1;
        } else {
            idx = 0; // 非流式：取首段（思考开篇）
        }
        if (idx < 0 || idx >= paras.length) idx = paras.length - 1;
        String target = paras[idx].trim();
        int nl = target.indexOf('\n');
        if (nl >= 0) target = target.substring(0, nl).trim();
        target = target.replace("**", "").replace("`", "");
        target = target.replaceAll("\\s+", " ").trim();
        if (target.length() > 48) target = target.substring(0, 48);
        return target;
    }

    /** 取消思考区进行中的展开/折叠动画（点击切换/流式更新前调用，防止动画竞争 height） */
    private void cancelThinkingAnimator(AIMessageViewHolder holder) {
        if (holder.thinkingAnimator != null) {
            try {
                holder.thinkingAnimator.cancel();
            } catch (Throwable ignored) {
            }
            holder.thinkingAnimator = null;
        }
    }

    private void expandThinkingContent(AIMessageViewHolder holder, ChatMessage message) {
        boolean isStreaming = message.status == ChatMessage.MessageStatus.GENERATING
                || message.status == ChatMessage.MessageStatus.IN_PROGRESS;
        cancelThinkingAnimator(holder);
        holder.thinkingContent.setVisibility(View.VISIBLE);
        final int targetHeight = holder.thinkingContent.getHeight();
        if (targetHeight == 0) {
            holder.thinkingContent.measure(
                View.MeasureSpec.makeMeasureSpec(holder.thinkingContent.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            );
            final int measuredHeight = holder.thinkingContent.getMeasuredHeight();
            holder.thinkingContent.getLayoutParams().height = 0;
            holder.thinkingContent.requestLayout();

            ValueAnimator animator = ValueAnimator.ofInt(0, measuredHeight);
            holder.thinkingAnimator = animator;
            animator.addUpdateListener(animation -> {
                holder.thinkingContent.getLayoutParams().height = (int) animation.getAnimatedValue();
                holder.thinkingContent.requestLayout();
            });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    holder.thinkingContent.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    holder.thinkingContent.requestLayout();
                }
            });
            animator.setDuration(250);
            animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
            animator.start();
        } else {
            holder.thinkingContent.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        message.thinkingExpanded = true;
        updateThinkingLabel(holder, true, isStreaming, message);
        if (holder.thinkingDivider != null) {
            holder.thinkingDivider.setVisibility(View.VISIBLE);
        }
    }

    private void collapseThinkingContent(AIMessageViewHolder holder, ChatMessage message) {
        boolean isStreaming = message.status == ChatMessage.MessageStatus.GENERATING
                || message.status == ChatMessage.MessageStatus.IN_PROGRESS;
        final int initialHeight = holder.thinkingContent.getHeight();
        if (initialHeight == 0) {
            cancelThinkingAnimator(holder);
            holder.thinkingContent.setVisibility(View.GONE);
            message.thinkingExpanded = false;
            updateThinkingLabel(holder, false, isStreaming, message);
            if (holder.thinkingDivider != null) {
                holder.thinkingDivider.setVisibility(View.GONE);
            }
            return;
        }

        cancelThinkingAnimator(holder);
        ValueAnimator animator = ValueAnimator.ofInt(initialHeight, 0);
        holder.thinkingAnimator = animator;
        animator.addUpdateListener(animation -> {
            holder.thinkingContent.getLayoutParams().height = (int) animation.getAnimatedValue();
            holder.thinkingContent.requestLayout();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                holder.thinkingContent.setVisibility(View.GONE);
                holder.thinkingContent.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
            }
        });
        animator.setDuration(200);
        animator.setInterpolator(new android.view.animation.AccelerateInterpolator());
        animator.start();

        message.thinkingExpanded = false;
        updateThinkingLabel(holder, false, isStreaming, message);
        if (holder.thinkingDivider != null) {
            holder.thinkingDivider.setVisibility(View.GONE);
        }
    }

    /** 判断 position 是否为列表中最后一条 AI 消息（最终轮 meta：操作栏/统计/时间 只显示在此） */
    private boolean isLastAiMessage(int position) {
        for (int i = position + 1; i < messages.size(); i++) {
            if (messages.get(i).type == ChatMessage.MessageType.AI) return false;
        }
        return true;
    }

    /** 查找最后一条 AI 消息索引（外部插入新 AI 消息前调用，用于刷新旧消息隐藏其 meta） */
    public int findLastAiMessageIndex() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).type == ChatMessage.MessageType.AI) return i;
        }
        return -1;
    }

    private void updateMessageStatus(AIMessageViewHolder holder, ChatMessage message) {
        Context context = holder.itemView.getContext();
        
        // 获取推理状态
        InferenceStateManager.InferenceState currentState = 
            inferenceStateManager.getCurrentState(message.id);
        InferenceStateManager.StateDetails stateDetails = 
            inferenceStateManager.getStateDetails(message.id);
        
        if (message.isCompleted() || currentState == InferenceStateManager.InferenceState.COMPLETED) {
            // 隐藏推理进度视图
            if (holder.inferenceProgressView != null) {
                holder.inferenceProgressView.hide();
            }
            if (holder.agentExecutionView != null) {
                holder.agentExecutionView.hide();
            }
            
            holder.statusIcon.setVisibility(View.VISIBLE);
            holder.statusIcon.setImageResource(R.drawable.ic_check_double);
            holder.statusIcon.setColorFilter(ThemeColors.attr(context, R.attr.colorControlTextSecondary));
            
            if (message.tokensGenerated > 0) {
                float seconds = message.generationTimeMs > 0 ? message.generationTimeMs / 1000.0f : 0;
                float speed = message.tokensPerSecond > 0 
                    ? message.tokensPerSecond 
                    : (message.generationTimeMs > 0 ? (message.tokensGenerated * 1000.0f) / message.generationTimeMs : 0);
                String statusStr = String.format(context.getString(R.string.h_4f54d8ba),
                    message.tokensGenerated, seconds, speed);
                if (message.usingGPU && message.gpuLayers > 0) {
                    statusStr += " · GPU " + message.gpuLayers + "层";
                }
                holder.statusText.setText(statusStr);
                holder.statusText.setVisibility(View.VISIBLE);
            } else {
                holder.statusText.setText(R.string.chat_status_completed);
                holder.statusText.setVisibility(View.VISIBLE);
            }
        } else if (message.status == ChatMessage.MessageStatus.GENERATING ||
                   currentState.isProcessing()) {
            // Agent模式显示AgentExecutionView，其他模式显示InferenceProgressView
            boolean isAgentMode = message.agentMode;

            if (isAgentMode) {
                if (holder.inferenceProgressView != null) {
                    holder.inferenceProgressView.hide();
                }
                if (holder.agentExecutionView != null) {
                    holder.agentExecutionView.hide();
                }
            } else {
                if (holder.inferenceProgressView != null) {
                    holder.inferenceProgressView.updateState(currentState, stateDetails);
                }
                if (holder.agentExecutionView != null) {
                    holder.agentExecutionView.hide();
                }
            }
            
            holder.statusIcon.setVisibility(View.GONE);
            holder.statusText.setVisibility(View.GONE);
        } else {
            // 其他状态（失败、取消等）
            if (holder.inferenceProgressView != null) {
                holder.inferenceProgressView.hide();
            }
            if (holder.agentExecutionView != null) {
                holder.agentExecutionView.hide();
            }
            holder.statusIcon.setVisibility(View.GONE);
            holder.statusText.setVisibility(View.GONE);
        }
    }

    /**
     * 绑定在线模型信息
     */
    private void bindModelInfo(AIMessageViewHolder holder, ChatMessage message) {
        if (message.modelInfo == null || holder.modelInfoContainer == null) {
            if (holder.modelInfoContainer != null) {
                holder.modelInfoContainer.setVisibility(View.GONE);
            }
            return;
        }

        holder.modelInfoContainer.setVisibility(View.VISIBLE);

        // 模型名称
        if (message.modelInfo.modelName != null) {
            holder.modelNameText.setText(message.modelInfo.modelName);
        } else {
            holder.modelNameText.setText("");
        }

        // 状态指示器
        if (holder.modelStatusIndicator != null) {
            int indicatorDrawable;
            switch (message.modelInfo.status) {
                case 1: // online
                    indicatorDrawable = R.drawable.status_indicator_online;
                    break;
                case 2: // offline
                    indicatorDrawable = R.drawable.status_indicator_offline;
                    break;
                case 3: // error
                    indicatorDrawable = R.drawable.status_indicator_error;
                    break;
                default:
                    indicatorDrawable = R.drawable.status_indicator_unknown;
                    break;
            }
            holder.modelStatusIndicator.setBackgroundResource(indicatorDrawable);
        }

        // 延迟
        if (message.modelInfo.latencyMs > 0 && holder.modelLatencyText != null) {
            holder.modelLatencyText.setVisibility(View.VISIBLE);
            holder.modelLatencyText.setText(formatLatency(message.modelInfo.latencyMs));
        } else if (holder.modelLatencyText != null) {
            holder.modelLatencyText.setVisibility(View.GONE);
        }

        // 成本估算
        if (message.modelInfo.costEstimate > 0 && holder.modelCostText != null) {
            holder.modelCostText.setVisibility(View.VISIBLE);
            holder.modelCostText.setText(String.format(Locale.getDefault(), "$%.4f", message.modelInfo.costEstimate));
        } else if (holder.modelCostText != null) {
            holder.modelCostText.setVisibility(View.GONE);
        }
    }

    private String formatLatency(long latencyMs) {
        if (latencyMs < 1000) {
            return latencyMs + "ms";
        } else {
            return String.format(Locale.getDefault(), "%.1fs", latencyMs / 1000.0);
        }
    }

    /**
     * Mermaid/数学图形化渲染：```mermaid 块与 $$ 公式拆为 html 组件段（WebView 渲染），
     * 文本段保持 Markdown。组件段复用指纹缓存（流式期间内容稳定时不重建）。
     */
    private void renderMermaidMath(AIMessageViewHolder holder, ChatMessage message,
                                   int availableWidth, Context ctx) {
        boolean isDark = (ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        List<com.oilquiz.app.ai.chat.component.MermaidMathSplitter.Segment> segs =
                com.oilquiz.app.ai.chat.component.MermaidMathSplitter.split(message.content, isDark);
        StringBuilder fp = new StringBuilder();
        for (com.oilquiz.app.ai.chat.component.MermaidMathSplitter.Segment seg : segs) {
            if (seg.isComponent && seg.component != null && seg.component.props != null) {
                fp.append(seg.component.type).append('|')
                        .append(seg.component.props.toString().hashCode()).append(';');
            }
        }
        String newFp = fp.toString();
        boolean canReuse = newFp.equals(holder.componentSegmentsFingerprint)
                && holder.componentSegmentViews != null;
        List<View> oldCache = canReuse ? holder.componentSegmentViews
                : java.util.Collections.<View>emptyList();
        List<View> newCache = new java.util.ArrayList<View>();
        int compIdx = 0;
        holder.contentHost.removeAllViews();
        for (com.oilquiz.app.ai.chat.component.MermaidMathSplitter.Segment seg : segs) {
            if (seg.isComponent) {
                View view = (canReuse && compIdx < oldCache.size()) ? oldCache.get(compIdx) : null;
                if (view == null) {
                    view = ComponentRegistry.getInstance().render(ctx, seg.component);
                }
                if (view == null) {
                    newCache.add(null);
                    compIdx++;
                    continue;
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dpToPx(8, ctx);
                holder.contentHost.addView(view, lp);
                newCache.add(view);
                compIdx++;
            } else {
                TextView tv = createSegmentTextView(ctx, holder.messageText);
                setRenderedText(tv, seg.text, availableWidth);
                tv.setMovementMethod(LinkMovementMethod.getInstance());
                holder.contentHost.addView(tv);
            }
        }
        holder.componentSegmentsFingerprint = newFp;
        holder.componentSegmentViews = newCache;
    }

    private void handleLongContent(AIMessageViewHolder holder, ChatMessage message) {
        // 用户要求：主回复默认全部展开显示，长内容不自动折叠
        holder.expandButton.setVisibility(View.GONE);
        holder.messageText.setMaxLines(Integer.MAX_VALUE);
        holder.messageText.setEllipsize(null);
    }

    private void toggleMessageExpansion(AIMessageViewHolder holder, ChatMessage message) {
        handleLongContent(holder, message);
        holder.itemView.post(() -> renderWithCorrectWidth(holder, message));
    }

    /**
     * 以"正确宽度"渲染 AI 消息内容（表格按真实可用宽度计算列宽）：
     * - holder 已布局（isLaidOut 且宽度有效）：立即渲染；
     * - 未布局完成（新建/重建 holder、布局排队中）：注册一次性 OnGlobalLayoutListener，
     *   布局完成后按真实宽度渲染。
     * 用于替代"getWidth()==0 时兜底全屏宽"的旧逻辑——表格按全屏宽分配列宽会导致
     * 单元格文字重叠/截断，且生成完成 rebind 时 holder 已布局不再触发布局监听，
     * 旧逻辑无法纠正流式期间产生的错位渲染。
     */
    private void renderWithCorrectWidth(AIMessageViewHolder holder, ChatMessage message) {
        if (holder.itemView.isLaidOut() && holder.itemView.getWidth() > 0) {
            int availableWidth = holder.itemView.getWidth()
                - holder.itemView.getPaddingLeft()
                - holder.itemView.getPaddingRight()
                - dpToPx(2, holder.itemView.getContext());
            if (availableWidth <= 0) availableWidth = getScreenWidth(holder.itemView.getContext());
            bindMessageContent(holder, message, availableWidth);
            return;
        }
        holder.messageText.getViewTreeObserver().addOnGlobalLayoutListener(
            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                private boolean ran = false;
                @Override
                public void onGlobalLayout() {
                    if (ran) return;
                    ran = true;
                    if (holder.messageText.getViewTreeObserver().isAlive()) {
                        holder.messageText.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                    int w = holder.itemView.getWidth();
                    int availableWidth = w - holder.itemView.getPaddingLeft()
                        - holder.itemView.getPaddingRight() - dpToPx(2, holder.itemView.getContext());
                    if (availableWidth <= 0) availableWidth = getScreenWidth(holder.itemView.getContext());
                    bindMessageContent(holder, message, availableWidth);
                }
            });
    }

    private Spanned formatMessageContent(String content, int availableWidth) {
        // 使用 RenderExecutor 渲染执行器：自动检测内容类型（Markdown/LaTeX/Mermaid/HTML），
        // 分段渲染并拼接为最终 Spanned
        Context ctx = attachedRecyclerView != null ? attachedRecyclerView.getContext() : null;
        return RenderExecutor.getInstance().execute(content, ctx, availableWidth);
    }

    /**
     * 渲染 Markdown 并设置到 TextView。
     * 复制 Spanned 后绑定 TextViewSpan：Markwon 表格（TableRowSpan）绘制时会用
     * TextView 内容区域宽度（getWidth - padding）计算表格宽度；
     * 若不绑定，则回落到 Canvas 全宽（含 padding），表格右侧会被裁掉。
     * 复制是为了不污染 RenderExecutor 的渲染缓存。
     */
    private void setRenderedText(TextView textView, String content, int availableWidth) {
        // 渲染统一走后台线程池（避免主线程卡顿/ANR）：单线程串行保证与主线程一致的渲染器线程安全，
        // 代次守卫丢弃过期结果，防抖合并避免流式更新时渲染队列堆积。
        renderInto(textView, content, availableWidth);
    }

    /** 后台渲染请求（防抖合并的最小单位） */
    private static final class RenderRequest {
        final TextView textView;
        final String content;
        final int availableWidth;
        final long token;
        RenderRequest(TextView tv, String content, int w, long token) {
            this.textView = tv;
            this.content = content;
            this.availableWidth = w;
            this.token = token;
        }
    }

    /** 主线程 Handler：渲染结果统一回投主线程应用 */
    private static final Handler RENDER_MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /** 单线程渲染池：Markwon/Prism4j 等渲染器按主线程同样的串行语义执行，只是搬离 UI 线程 */
    private static final ExecutorService RENDER_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ChatRenderPool");
        t.setDaemon(true);
        return t;
    });

    /** 全局渲染代次序列：每次请求分配新 token，应用时校验防止过期渲染覆盖新内容 */
    private static final AtomicLong RENDER_SEQ = new AtomicLong();

    /** 每视图当前有效 token */
    private static final ConcurrentHashMap<TextView, Long> RENDER_TOKENS = new ConcurrentHashMap<>();

    /** 待渲染请求（防抖合并：同视图只保留最新一条） */
    private static final ConcurrentHashMap<TextView, RenderRequest> PENDING_RENDERS = new ConcurrentHashMap<>();

    /** 是否已有排队的批量渲染任务 */
    private static final AtomicBoolean DRAIN_SCHEDULED = new AtomicBoolean();

    /** 提交异步渲染：同视图最新内容合并 + 代次守卫 */
    static void renderInto(TextView textView, String content, int availableWidth) {
        if (textView == null) return;
        if (content == null) content = "";
        long token = RENDER_SEQ.incrementAndGet();
        RENDER_TOKENS.put(textView, token);
        PENDING_RENDERS.put(textView, new RenderRequest(textView, content, availableWidth, token));
        if (DRAIN_SCHEDULED.compareAndSet(false, true)) {
            RENDER_POOL.execute(ChatAdapter::drainPendingRenders);
        }
    }

    /** 批量消费待渲染请求（在渲染线程执行） */
    private static void drainPendingRenders() {
        for (;;) {
            List<RenderRequest> batch = new ArrayList<>(PENDING_RENDERS.values());
            PENDING_RENDERS.clear();
            if (batch.isEmpty()) {
                DRAIN_SCHEDULED.set(false);
                // 清空瞬间又来了新请求：保持标记并继续下一轮，避免丢请求
                if (PENDING_RENDERS.isEmpty()) {
                    return;
                }
                DRAIN_SCHEDULED.set(true);
                continue;
            }
            for (RenderRequest req : batch) {
                renderOne(req);
            }
        }
    }

    /** 后台渲染单个请求 */
    private static void renderOne(RenderRequest req) {
        final Spanned rendered;
        try {
            // 与 formatMessageContent 等效；使用 Application Context 保证渲染器初始化与资源可用
            rendered = RenderExecutor.getInstance().execute(
                    req.content, SmartQuizApplication.getAppContext(), req.availableWidth);
        } catch (Throwable t) {
            RENDER_MAIN_HANDLER.post(() -> applyFallbackText(req));
            return;
        }
        RENDER_MAIN_HANDLER.post(() -> applyRenderedText(req.textView, rendered, req.token));
    }

    /** 主线程应用渲染结果（代次校验通过才 setText） */
    private static void applyRenderedText(TextView textView, Spanned rendered, long token) {
        if (!isRenderCurrent(textView, token)) return;
        try {
            Spannable spannable = new SpannableStringBuilder(rendered);
            TextViewSpan.applyTo(spannable, textView);
            textView.setText(spannable);
        } catch (Throwable ignored) {
            // 应用失败（视图已分离等）：静默丢弃，等待下次渲染
        }
    }

    /** 渲染失败兜底：主线程直接显示原文 */
    private static void applyFallbackText(RenderRequest req) {
        if (!isRenderCurrent(req.textView, req.token)) return;
        try {
            req.textView.setText(req.content);
        } catch (Throwable ignored) {
        }
    }

    /** 代次校验：返回 false 表示该视图已有更新的渲染请求，丢弃过期结果 */
    private static boolean isRenderCurrent(TextView textView, long token) {
        Long cur = RENDER_TOKENS.get(textView);
        return cur != null && cur.longValue() == token;
    }

    /**
     * 渲染消息附带的插件式 UI 组件（ComponentRegistry 按类型匹配组件插件）。
     * 通过 boundComponents 引用检测避免重复重建：流式/重复刷新时组件不闪烁。
     * tool_call 工具过程卡片：全部执行完成后折叠为一行"🔧 工具过程 N 个 ▶"，
     * 点击展开查看每个工具及结果（执行中保持实时显示不折叠）。
     */
    private void bindComponents(AIMessageViewHolder holder, ChatMessage message) {
        if (holder.componentContainer == null) return;
        // 同一组件列表引用不重复重建（内容未变）
        if (holder.boundComponents == message.components) return;
        holder.componentContainer.removeAllViews();
        holder.boundComponents = message.components;

        if (message.components == null || message.components.isEmpty()) {
            holder.componentContainer.setVisibility(View.GONE);
            return;
        }
        Context ctx = holder.itemView.getContext();
        holder.componentContainer.setVisibility(View.VISIBLE);

        // 分离 tool_call 工具过程卡片与其他组件
        java.util.List<ComponentData> toolCalls = new java.util.ArrayList<>();
        java.util.List<ComponentData> others = new java.util.ArrayList<>();
        for (ComponentData data : message.components) {
            if (data == null || data.props == null) continue;
            if ("tool_call".equals(data.type)) toolCalls.add(data);
            else others.add(data);
        }

        // 工具卡片是否全部执行完成（存在 running 则保持实时显示不折叠）
        boolean allToolsDone = !toolCalls.isEmpty();
        for (ComponentData tc : toolCalls) {
            String st = tc.props != null ? tc.props.optString("status", "running") : "running";
            if ("running".equals(st)) { allToolsDone = false; break; }
        }
        boolean toolsCollapsed = allToolsDone && !message.agentToolsExpanded;

        boolean first = true;
        int rendered = 0;

        // 渲染非工具组件（工具产物如 list_card/image_grid 等始终完整显示）
        for (ComponentData data : others) {
            View view = ComponentRegistry.getInstance().render(ctx, data);
            if (view == null) {
                // 渲染失败降级占位（不显示组件源码）
                TextView tv = new TextView(ctx);
                tv.setText(SmartQuizApplication.getAppContext().getString(R.string.h_ce08c6dd) + data.type + SmartQuizApplication.getAppContext().getString(R.string.h_56e45313));
                tv.setTextSize(12);
                tv.setTextColor(ThemeColors.attr(ctx, R.attr.colorControlTextSecondary));
                LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                flp.topMargin = first ? dpToPx(6, ctx) : dpToPx(8, ctx);
                holder.componentContainer.addView(tv, flp);
                first = false;
                rendered++;
                continue;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = first ? dpToPx(6, ctx) : dpToPx(8, ctx);
            holder.componentContainer.addView(view, lp);
            first = false;
            rendered++;
        }

        // 工具过程卡片：全部完成 → 折叠一行（可点击展开）；执行中或已展开 → 逐个渲染
        if (!toolCalls.isEmpty()) {
            if (toolsCollapsed) {
                TextView foldRow = new TextView(ctx);
                foldRow.setText(SmartQuizApplication.getAppContext().getString(R.string.h_17f41f0b) + toolCalls.size() + SmartQuizApplication.getAppContext().getString(R.string.h_c8290962));
                foldRow.setTextSize(12);
                foldRow.setTextColor(ThemeColors.attr(ctx, R.attr.colorControlTextSecondary));
                foldRow.setPadding(dpToPx(4, ctx), dpToPx(6, ctx), dpToPx(4, ctx), dpToPx(6, ctx));
                foldRow.setOnClickListener(v -> toggleAgentToolsExpanded(holder, message));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = first ? dpToPx(6, ctx) : dpToPx(8, ctx);
                holder.componentContainer.addView(foldRow, lp);
                first = false;
                rendered++;
            } else {
                for (ComponentData tc : toolCalls) {
                    View view = ComponentRegistry.getInstance().render(ctx, tc);
                    if (view == null) continue;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = first ? dpToPx(6, ctx) : dpToPx(8, ctx);
                    holder.componentContainer.addView(view, lp);
                    first = false;
                    rendered++;
                }
                // 展开态底部提供收起入口
                if (allToolsDone) {
                    TextView collapseRow = new TextView(ctx);
                    collapseRow.setText(SmartQuizApplication.getAppContext().getString(R.string.h_ef56d078));
                    collapseRow.setTextSize(11);
                    collapseRow.setTextColor(ThemeColors.attr(ctx, R.attr.colorControlTextSecondary));
                    collapseRow.setPadding(dpToPx(4, ctx), dpToPx(4, ctx), dpToPx(4, ctx), dpToPx(4, ctx));
                    collapseRow.setOnClickListener(v -> toggleAgentToolsExpanded(holder, message));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = dpToPx(4, ctx);
                    holder.componentContainer.addView(collapseRow, lp);
                }
            }
        }
        if (rendered == 0) {
            holder.componentContainer.setVisibility(View.GONE);
        }
    }

    /**
     * 切换工具过程卡片的展开/折叠：翻转状态后给 components 赋新引用，
     * 绕过 boundComponents 引用检测触发组件容器重建。
     */
    private void toggleAgentToolsExpanded(AIMessageViewHolder holder, ChatMessage message) {
        message.agentToolsExpanded = !message.agentToolsExpanded;
        if (message.components != null) {
            message.components = new java.util.ArrayList<>(message.components);
        }
        int pos = holder.getBindingAdapterPosition();
        if (pos != RecyclerView.NO_POSITION) {
            notifyItemChanged(pos);
        }
    }

    private Spanned formatMessageContent(String content) {
        return formatMessageContent(content, 0);
    }

    /**
     * 渲染消息内容（插入式组件渲染核心）。
     *
     * 内容含组件标记（```component:xxx {...}```）时按「文本段/组件段」交替渲染到 contentHost：
     * 文本段 → Markdown TextView，组件段 → ComponentRegistry 组件 View（插入在标记所在位置）。
     * 无标记时走纯文本模式（messageText 渲染全文 + 消息级组件容器）。
     */
    private void bindMessageContent(AIMessageViewHolder holder, ChatMessage message, int availableWidth) {
        Context ctx = holder.itemView.getContext();
        // 按轮次组装：存在工具轮次边界时，思考块+正文段+工具卡片按轮次交替渲染
        // （轮1思考+正文 → 工具卡片 → 轮2思考+正文 → …）；单轮无边界走现有逻辑
        if (message.contentRoundBounds != null && !message.contentRoundBounds.isEmpty()) {
            renderRoundAssembled(holder, message, availableWidth, ctx);
            return;
        }
        boolean hasMarkers = ComponentContentSplitter.containsComponent(message.content);

        if (!hasMarkers) {
            // Mermaid/数学公式图形化：```mermaid 块与 $$ 公式 → WebView(html 组件) 渲染
            // （mermaid.js 画图 / katex 渲染公式），文本段保持 Markdown 渲染
            boolean hasMermaidMath = com.oilquiz.app.ai.chat.component.MermaidMathSplitter
                    .containsStructure(message.content);
            if (holder.contentHost != null && hasMermaidMath) {
                renderMermaidMath(holder, message, availableWidth, ctx);
            } else {
                // 纯文本模式：宿主只保留 messageText（Markdown 由 Markwon 统一渲染：
                // 代码块带 Prism4j 语法高亮、表格带 TablePlugin，均由 MarkdownRenderer 处理）
                if (holder.contentHost != null
                        && (holder.contentHost.getChildCount() != 1
                            || holder.contentHost.getChildAt(0) != holder.messageText)) {
                    holder.contentHost.removeAllViews();
                    holder.contentHost.addView(holder.messageText);
                }
                setRenderedText(holder.messageText, message.content, availableWidth);
            }
            bindComponents(holder, message);
            return;
        }

        // 宿主模式：文本段 / 组件段交替，组件插入在标记所在位置；
        // 消息级组件（Agent 执行中的工具卡片等）统一由 component_container 显示，与纯文本模式一致
        if (holder.contentHost != null) {
            List<ComponentContentSplitter.Segment> segs = ComponentContentSplitter.split(message.content);
            // 组件段指纹：type|props 序列（空 props 段不渲染也不计入，保证与缓存索引对齐）。
            // 流式期间组件 JSON 稳定时复用已渲染 View，避免每次 token 更新都销毁重建
            // WebView/图表/图片（闪烁 + 性能损耗）
            StringBuilder fp = new StringBuilder();
            for (ComponentContentSplitter.Segment seg : segs) {
                if (seg.isComponent && seg.component != null && seg.component.props != null) {
                    fp.append(seg.component.type).append('|')
                            .append(seg.component.props.toString()).append(';');
                }
            }
            String newFp = fp.toString();
            boolean canReuse = newFp.equals(holder.componentSegmentsFingerprint)
                    && holder.componentSegmentViews != null;
            List<View> oldCache = canReuse ? holder.componentSegmentViews
                    : java.util.Collections.<View>emptyList();
            List<View> newCache = new java.util.ArrayList<View>();
            int compIdx = 0;
            holder.contentHost.removeAllViews();
            for (ComponentContentSplitter.Segment seg : segs) {
                if (seg.isComponent) {
                    if (seg.component == null || seg.component.props == null) continue;
                    View view = (canReuse && compIdx < oldCache.size())
                            ? oldCache.get(compIdx) : null;
                    if (view == null) {
                        view = ComponentRegistry.getInstance().render(ctx, seg.component);
                    }
                    if (view == null) {
                        // 渲染失败降级占位（不显示组件源码）
                        TextView tv = createSegmentTextView(ctx, holder.messageText);
                        tv.setText(SmartQuizApplication.getAppContext().getString(R.string.h_ce08c6dd) + seg.component.type + SmartQuizApplication.getAppContext().getString(R.string.h_56e45313));
                        tv.setTextColor(ThemeColors.attr(ctx, R.attr.colorControlTextSecondary));
                        holder.contentHost.addView(tv);
                        newCache.add(tv);
                        compIdx++;
                        continue;
                    }
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = dpToPx(8, ctx);
                    holder.contentHost.addView(view, lp);
                    newCache.add(view);
                    compIdx++;
                } else {
                    TextView tv = createSegmentTextView(ctx, holder.messageText);
                    // 宿主模式文本段较短，直接渲染（组件段已增量复用）
                    setRenderedText(tv, seg.text, availableWidth);
                    tv.setMovementMethod(LinkMovementMethod.getInstance());
                    holder.contentHost.addView(tv);
                }
            }
            // 更新缓存：本次实际渲染的组件段 View（与指纹一一对应，供下次流式更新复用）
            holder.componentSegmentsFingerprint = newFp;
            holder.componentSegmentViews = newCache;
        }
        // 消息级组件（Agent 工具卡片、工具 withComponent 组件）渲染到 component_container，
        // 执行中与完成后的渲染路径保持一致
        bindComponents(holder, message);
    }

    /** 按 messageText 模板创建段落 TextView（样式一致） */
    private TextView createSegmentTextView(Context ctx, TextView template) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, template.getTextSize());
        tv.setTextColor(template.getCurrentTextColor());
        tv.setBackground(template.getBackground());
        tv.setPadding(template.getPaddingLeft(), template.getPaddingTop(),
                template.getPaddingRight(), template.getPaddingBottom());
        tv.setLineSpacing(template.getLineSpacingExtra(), template.getLineSpacingMultiplier());
        tv.setTextIsSelectable(true);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            tv.setBreakStrategy(template.getBreakStrategy());
            tv.setHyphenationFrequency(template.getHyphenationFrequency());
        }
        return tv;
    }

    private int dpToPx(int dp, Context context) {
        if (context == null) return dp;
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void bindMessageStatus(ImageView icon, TextView text, ChatMessage.MessageStatus status) {
        bindMessageStatusWithStats(icon, text, status, -1, -1);
    }
    
    private void bindMessageStatusWithStats(ImageView icon, TextView text, ChatMessage.MessageStatus status, int tokensGenerated, long generationTimeMs) {
        if (status == null) {
            icon.setVisibility(View.GONE);
            text.setVisibility(View.GONE);
            return;
        }
        
        switch (status) {
            case SENDING:
                icon.setImageResource(R.drawable.ic_send);
                text.setText(R.string.chat_status_sending);
                break;
            case SENT:
                icon.setImageResource(R.drawable.ic_check);
                text.setText(R.string.chat_status_sent);
                break;
            case DELIVERED:
                icon.setImageResource(R.drawable.ic_check_double);
                text.setText(R.string.chat_status_delivered);
                break;
            case READ:
                icon.setImageResource(R.drawable.ic_check_double);
                text.setText(R.string.chat_status_read);
                break;
            case GENERATING:
                icon.setImageResource(R.drawable.ic_send);
                if (tokensGenerated > 0) {
                    float seconds = generationTimeMs > 0 ? generationTimeMs / 1000.0f : 0;
                    float speed = generationTimeMs > 0 ? (tokensGenerated * 1000.0f) / generationTimeMs : 0;
                    text.setText(String.format(SmartQuizApplication.getAppContext().getString(R.string.h_20b4237a), tokensGenerated, seconds, speed));
                } else {
                    text.setText(R.string.chat_status_generating);
                }
                break;
            case COMPLETED:
                icon.setImageResource(R.drawable.ic_check_double);
                if (tokensGenerated > 0) {
                    float seconds = generationTimeMs > 0 ? generationTimeMs / 1000.0f : 0;
                    float speed = generationTimeMs > 0 ? (tokensGenerated * 1000.0f) / generationTimeMs : 0;
                    text.setText(String.format(SmartQuizApplication.getAppContext().getString(R.string.h_4f54d8ba), tokensGenerated, seconds, speed));
                } else {
                    text.setText(R.string.chat_status_completed);
                }
                break;
            case IN_PROGRESS:
                icon.setImageResource(R.drawable.ic_send);
                text.setText(R.string.chat_status_in_progress);
                break;
            case FAILED:
                icon.setImageResource(R.drawable.ic_error);
                text.setText(R.string.chat_status_failed);
                break;
            case PAUSED:
                icon.setImageResource(R.drawable.ic_check);
                text.setText(R.string.chat_status_paused);
                break;
            case ERROR:
                icon.setImageResource(R.drawable.ic_error);
                text.setText(R.string.chat_status_send_failed);
                break;
            default:
                icon.setVisibility(View.GONE);
                text.setVisibility(View.GONE);
                return;
        }
        icon.setVisibility(View.VISIBLE);
        text.setVisibility(View.VISIBLE);
    }

    private void bindSystemMessage(SystemMessageViewHolder holder, ChatMessage message) {
        // ===== 上下文注入行 / 系统提示词行：标题 + 摘要，点击弹全文 =====
        if (message.systemType == ChatMessage.SystemMessageType.CONTEXT_INJECTION
                || message.systemType == ChatMessage.SystemMessageType.SYSTEM_PROMPT) {
            boolean isInjection = message.systemType == ChatMessage.SystemMessageType.CONTEXT_INJECTION;
            holder.systemIcon.setText(isInjection ? "🧠" : "📄");
            // 内容首行为标题/摘要，剩余为全文（Activity 组装时用换行分隔）
            String full = message.content == null ? "" : message.content;
            int nl = full.indexOf('\n');
            String headline = nl > 0 ? full.substring(0, nl).trim() : full.trim();
            if (headline.length() > 60) headline = headline.substring(0, 60) + "…";
            holder.messageText.setText(headline);
            holder.messageText.setMovementMethod(null);
            final String body = full;
            holder.itemView.setOnClickListener(v -> {
                try {
                    new android.app.AlertDialog.Builder(v.getContext())
                            .setTitle(isInjection ? "已注入上下文" : "系统提示词")
                            .setMessage(body)
                            .setPositiveButton("关闭", null)
                            .show();
                } catch (Throwable ignored) {
                }
            });
            return;
        }

        // ===== Agent执行组header：特殊渲染，点击折叠/展开整组 =====
        if (message.isAgentGroupHeader) {
            StringBuilder sb = new StringBuilder();
            sb.append(message.agentGroupCollapsed ? "▶" : "▼");
            sb.append(SmartQuizApplication.getAppContext().getString(R.string.h_ed851eb6));
            // 摘要信息
            int steps = message.agentGroupStepCount;
            int tools = message.agentGroupToolCount;
            if (steps > 0 || tools > 0) {
                sb.append(" (");
                if (steps > 0) sb.append(steps).append("步");
                if (steps > 0 && tools > 0) sb.append(" | ");
                if (tools > 0) sb.append(tools).append(SmartQuizApplication.getAppContext().getString(R.string.h_20dce2c6));
                sb.append(")");
            }
            if (message.agentGroupCollapsed) sb.append(SmartQuizApplication.getAppContext().getString(R.string.h_64df64c4));
            holder.messageText.setText(sb.toString());
            if (message.systemType != null) {
                holder.systemIcon.setText("🤖");
            }
            holder.messageText.setMovementMethod(null);
            holder.itemView.setOnClickListener(v -> {
                message.agentGroupCollapsed = !message.agentGroupCollapsed;
                notifyDataSetChanged();
            });
            return;
        }

        // 正常系统消息
        holder.itemView.setOnClickListener(null);
        SpannableStringBuilder spannable = new SpannableStringBuilder(message.content);
        
        // 查找并设置可点击的"帮助"文本
        int helpIndex = message.content.indexOf(SmartQuizApplication.getAppContext().getString(R.string.h_92e3a830));
        while (helpIndex >= 0) {
            int endIndex = helpIndex + 2;
            if (endIndex <= message.content.length()) {
                spannable.setSpan(new android.text.style.ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        if (actionClickListener != null) {
                            actionClickListener.onAction(ChatMessage.Action.showHelp());
                        }
                    }
                    
                    @Override
                    public void updateDrawState(android.text.TextPaint ds) {
                        super.updateDrawState(ds);
                        ds.setColor(ThemeColors.get(holder.itemView.getContext(), R.color.primary));
                        ds.setUnderlineText(true);
                    }
                }, helpIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            helpIndex = message.content.indexOf(SmartQuizApplication.getAppContext().getString(R.string.h_92e3a830), endIndex);
        }
        
        // 查找并设置可点击的"教程"文本
        int guideIndex = message.content.indexOf(SmartQuizApplication.getAppContext().getString(R.string.h_b7824d5c));
        while (guideIndex >= 0) {
            int endIndex = guideIndex + 2;
            if (endIndex <= message.content.length()) {
                spannable.setSpan(new android.text.style.ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        if (actionClickListener != null) {
                            actionClickListener.onAction(ChatMessage.Action.showGuide());
                        }
                    }
                    
                    @Override
                    public void updateDrawState(android.text.TextPaint ds) {
                        super.updateDrawState(ds);
                        ds.setColor(ThemeColors.get(holder.itemView.getContext(), R.color.primary));
                        ds.setUnderlineText(true);
                    }
                }, guideIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            guideIndex = message.content.indexOf(SmartQuizApplication.getAppContext().getString(R.string.h_b7824d5c), endIndex);
        }
        
        // 查找并设置可点击的帮助图标提示
        int iconIndex = message.content.indexOf(SmartQuizApplication.getAppContext().getString(R.string.h_267bf2f9));
        while (iconIndex >= 0) {
            int endIndex = iconIndex + 4;
            if (endIndex <= message.content.length()) {
                spannable.setSpan(new android.text.style.ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        if (actionClickListener != null) {
                            actionClickListener.onAction(ChatMessage.Action.showGuide());
                        }
                    }
                    
                    @Override
                    public void updateDrawState(android.text.TextPaint ds) {
                        super.updateDrawState(ds);
                        ds.setColor(ThemeColors.get(holder.itemView.getContext(), R.color.primary));
                        ds.setUnderlineText(true);
                    }
                }, iconIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            iconIndex = message.content.indexOf(SmartQuizApplication.getAppContext().getString(R.string.h_267bf2f9), endIndex);
        }
        
        // 查找并设置可点击的"🚀 强行使用本地Agent"文本（本地Agent拦截引导消息）
        final String forceAgentKey = "🚀 强行使用本地Agent";
        int forceIndex = message.content.indexOf(forceAgentKey);
        if (forceIndex >= 0) {
            int endIndex = forceIndex + forceAgentKey.length();
            final String payload = message.actionPayload;
            spannable.setSpan(new android.text.style.ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    if (actionClickListener != null) {
                        actionClickListener.onAction(ChatMessage.Action.forceLocalAgent(payload));
                    }
                }

                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    super.updateDrawState(ds);
                    ds.setColor(ThemeColors.get(holder.itemView.getContext(), R.color.primary));
                    ds.setUnderlineText(true);
                    ds.setFakeBoldText(true);
                }
            }, forceIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        
        holder.messageText.setText(spannable);
        holder.messageText.setMovementMethod(LinkMovementMethod.getInstance());
        
        if (message.systemType != null) {
            holder.systemIcon.setText(message.systemType.getEmoji());
        }
    }

    private void bindThinkingMessage(ThinkingMessageViewHolder holder, ChatMessage message) {
        holder.bind(message);
    }

    private void bindTaskMessage(TaskMessageViewHolder holder, ChatMessage message) {
        holder.messageText.setText(message.content);
        if (message.type == ChatMessage.MessageType.TASK_BREAKDOWN) {
            holder.taskLabel.setText(R.string.chat_task_breakdown);
            holder.taskLabel.setBackgroundColor(holder.itemView.getContext().getColor(R.color.task_background));
        } else {
            holder.taskLabel.setText(R.string.chat_task_progress);
            holder.taskLabel.setBackgroundColor(holder.itemView.getContext().getColor(R.color.task_background));
        }
        
        if (message.taskProgress != null) {
            holder.taskProgress.setProgress(message.taskProgress);
            holder.taskProgress.setVisibility(View.VISIBLE);
        } else {
            holder.taskProgress.setVisibility(View.GONE);
        }
    }

    private void bindToolCallMessage(ToolCallViewHolder holder, ChatMessage message) {
        ChatMessage.ToolCallInfo info = message.toolCallInfo;
        if (info == null) {
            // 兼容旧 TOOL_RESULT 消息（已与工具调用合并渲染）：无 ToolCallInfo，直接显示内容为结果
            holder.toolName.setText(message.content != null && !message.content.isEmpty()
                    ? message.content : SmartQuizApplication.getAppContext().getString(R.string.h_879cbfca));
            holder.toolStatus.setText(R.string.chat_status_completed);
            if (holder.toolResultContainer != null) {
                holder.toolResultContainer.setVisibility(View.VISIBLE);
            }
            if (message.content != null && holder.toolResult != null) {
                holder.toolResult.setText(message.content);
                holder.toolResult.setMovementMethod(LinkMovementMethod.getInstance());
            }
            if (holder.toolProgress != null) holder.toolProgress.setVisibility(View.GONE);
            return;
        }

        holder.toolIcon.setText(info.toolIcon);
        holder.toolName.setText(info.toolDisplayName);
        holder.toolStatus.setText(info.getStatusText());

        // dsh 对齐：工具声明化卡片意图（presentCall/presentResult → presentCard.title，2026-09-23）
        Object cardTitle = info.presentCard != null ? info.presentCard.get("title") : null;
        if (cardTitle != null && !String.valueOf(cardTitle).isEmpty()) {
            holder.toolStatus.setText(info.getStatusText() + " · " + String.valueOf(cardTitle));
        }

        // 整体折叠控制：点击头部行切换 callExpanded
        applyCallExpansion(holder, message, info);
        if (holder.toolCallHeader != null) {
            holder.toolCallHeader.setOnClickListener(v -> {
                info.callExpanded = !info.callExpanded;
                applyCallExpansion(holder, message, info);
            });
        }

        if (info.executionTimeMs > 0) {
            holder.toolStatus.setText(info.getStatusText() + " · " + info.executionTimeMs + "ms");
        }
    }

    /**
     * 应用工具调用整体折叠/展开状态。
     * 折叠时只显示头部摘要行（图标+名称+状态），隐藏参数和结果区域。
     * 展开时显示参数和结果区域（各自仍受 paramsExpanded/resultExpanded 控制）。
     */
    private void applyCallExpansion(ToolCallViewHolder holder, ChatMessage message, ChatMessage.ToolCallInfo info) {
        // 箭头指示器
        if (holder.toolCallArrow != null) {
            holder.toolCallArrow.setText(info.callExpanded ? "▼" : "▶");
        }

        if (!info.callExpanded) {
            // 折叠：隐藏参数和结果区域
            if (holder.toolParamsContainer != null) holder.toolParamsContainer.setVisibility(View.GONE);
            if (holder.toolResultContainer != null) holder.toolResultContainer.setVisibility(View.GONE);
            // 执行中时保留头部进度条，否则隐藏
            if (holder.toolProgress != null) {
                holder.toolProgress.setVisibility(
                    info.status == ChatMessage.ToolCallInfo.ToolCallStatus.RUNNING ? View.VISIBLE : View.GONE);
            }
            return;
        }

        // 展开：按原逻辑显示参数和结果区域
        // 参数区域：无参数时隐藏整个容器，有参数时支持折叠
        if (info.parameters != null && !info.parameters.isEmpty() && holder.toolParamsContainer != null) {
            holder.toolParamsContainer.setVisibility(View.VISIBLE);
            holder.toolParams.setText(info.parameters);
            applyParamsExpansion(holder, info);
            if (holder.toolParamsHeader != null) {
                holder.toolParamsHeader.setOnClickListener(v -> {
                    info.paramsExpanded = !info.paramsExpanded;
                    applyParamsExpansion(holder, info);
                });
            }
        } else if (holder.toolParamsContainer != null) {
            holder.toolParamsContainer.setVisibility(View.GONE);
        } else {
            // 兼容旧布局：无 container 时只控制 toolParams
            if (info.parameters != null && !info.parameters.isEmpty()) {
                holder.toolParams.setVisibility(View.VISIBLE);
                holder.toolParams.setText(info.parameters);
            } else {
                holder.toolParams.setVisibility(View.GONE);
            }
        }

        // 结果区域
        if (info.status == ChatMessage.ToolCallInfo.ToolCallStatus.COMPLETED
                || info.status == ChatMessage.ToolCallInfo.ToolCallStatus.FAILED) {
            holder.toolResultContainer.setVisibility(View.VISIBLE);
            if (info.result != null) {
                CharSequence displayResult = buildResultWithInterpretChip(message, info, false);
                holder.toolResult.setText(displayResult);
                if (displayResult instanceof Spanned) {
                    holder.toolResult.setMovementMethod(LinkMovementMethod.getInstance());
                }
            }
            holder.toolProgress.setVisibility(View.GONE);
            applyResultExpansion(holder, info);
            if (holder.toolResultHeader != null) {
                holder.toolResultHeader.setOnClickListener(v -> {
                    info.resultExpanded = !info.resultExpanded;
                    applyResultExpansion(holder, info);
                });
            }
        } else if (info.status == ChatMessage.ToolCallInfo.ToolCallStatus.RUNNING) {
            holder.toolResultContainer.setVisibility(View.GONE);
            holder.toolProgress.setVisibility(View.VISIBLE);
            holder.toolProgress.setIndeterminate(true);
        } else {
            holder.toolResultContainer.setVisibility(View.GONE);
            holder.toolProgress.setVisibility(View.GONE);
        }
    }

    /** 应用参数区域展开/折叠状态 */
    private void applyParamsExpansion(ToolCallViewHolder holder, ChatMessage.ToolCallInfo info) {
        if (holder.toolParams == null) return;
        if (info.paramsExpanded) {
            holder.toolParams.setVisibility(View.VISIBLE);
            if (holder.toolParamsArrow != null) holder.toolParamsArrow.setText("▼");
        } else {
            holder.toolParams.setVisibility(View.GONE);
            if (holder.toolParamsArrow != null) holder.toolParamsArrow.setText("▶");
        }
    }

    /** 应用结果区域展开/折叠状态 */
    private void applyResultExpansion(ToolCallViewHolder holder, ChatMessage.ToolCallInfo info) {
        if (holder.toolResult == null) return;
        if (info.resultExpanded) {
            holder.toolResult.setVisibility(View.VISIBLE);
            if (holder.toolResultArrow != null) holder.toolResultArrow.setText("▼");
        } else {
            holder.toolResult.setVisibility(View.GONE);
            if (holder.toolResultArrow != null) holder.toolResultArrow.setText("▶");
        }
    }

    private void bindToolResultMessage(ToolResultViewHolder holder, ChatMessage message) {
        ChatMessage.ToolCallInfo info = message.toolCallInfo;
        if (info == null) return;

        holder.toolIcon.setText(info.toolIcon);
        holder.toolName.setText(info.toolDisplayName);
        
        if (info.result != null && !info.result.isEmpty()) {
            CharSequence displayResult = buildResultWithInterpretChip(message, info, true);
            holder.toolResult.setText(displayResult);
            if (displayResult instanceof Spanned) {
                holder.toolResult.setMovementMethod(LinkMovementMethod.getInstance());
            }
            // 长结果默认折叠，点击展开/收起
            applyToolResultCollapse(holder, info);
            holder.toolResult.setOnClickListener(v -> {
                info.resultExpanded = !info.resultExpanded;
                applyToolResultCollapse(holder, info);
            });
        }

        if (info.executionTimeMs > 0) {
            holder.toolTime.setText(SmartQuizApplication.getAppContext().getString(R.string.h_25856aec) + info.executionTimeMs + "ms");
        }

        holder.btnCopyResult.setOnClickListener(v -> {
            if (info.result != null) {
                copyToClipboard(v.getContext(), info.result);
            }
        });

        holder.btnViewDetails.setOnClickListener(v -> {
            info.resultExpanded = !info.resultExpanded;
            applyToolResultCollapse(holder, info);
        });
    }

    /** 应用工具结果折叠/展开状态（ToolResultViewHolder） */
    private void applyToolResultCollapse(ToolResultViewHolder holder, ChatMessage.ToolCallInfo info) {
        if (holder.toolResult == null) return;
        boolean isLong = info.result != null && info.result.length() > 200;
        if (!isLong) {
            // 短结果不需要折叠
            holder.toolResult.setMaxLines(Integer.MAX_VALUE);
            holder.toolResult.setEllipsize(null);
            if (holder.btnViewDetails != null) holder.btnViewDetails.setVisibility(View.GONE);
            return;
        }
        // 长结果支持折叠
        if (holder.btnViewDetails != null) {
            holder.btnViewDetails.setVisibility(View.VISIBLE);
            holder.btnViewDetails.setText(info.resultExpanded ? SmartQuizApplication.getAppContext().getString(R.string.h_def9e98b) : SmartQuizApplication.getAppContext().getString(R.string.h_fe0a2c38));
        }
        if (info.resultExpanded) {
            holder.toolResult.setMaxLines(Integer.MAX_VALUE);
            holder.toolResult.setEllipsize(null);
        } else {
            holder.toolResult.setMaxLines(4);
            holder.toolResult.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
    }

    /**
     * 在工具结果文本末尾追加可点击的「AI深度解读」chip（仅满足条件时追加）。
     * 不修改任何 XML 布局：通过 Spannable 在文本末尾渲染一个高亮圆角效果的可点击区域。
     *
     * 追加条件：状态为 COMPLETED 且存在 rawResult、尚未触发过解读、且任意 LLM (在线或本地) 可用。
     */
    private CharSequence buildResultWithInterpretChip(ChatMessage message, ChatMessage.ToolCallInfo info, boolean applyMarkdown) {
        // 如果有自动写入的 AI 解读摘要（interpretedMessage）：放在顶部作为自然语言结论，
        // 其后保留原始结构化结果作为详细参考，避免信息丢失
        CharSequence interpreted = null;
        if (info.interpretedMessage != null && !info.interpretedMessage.isEmpty()) {
            if (applyMarkdown) {
                interpreted = formatMessageContent(info.interpretedMessage);
            } else {
                interpreted = info.interpretedMessage;
            }
        }

        // 渲染基础内容（结构化结果）
        CharSequence baseContent;
        if (applyMarkdown) {
            baseContent = info.result == null ? "" : formatMessageContent(info.result);
        } else {
            baseContent = info.result == null ? "" : info.result;
        }

        // 拼接：解读 + 分隔 + 原始结果（若原始结果与解读文本相同且过短，可省略以避免重复）
        CharSequence mainContent;
        if (interpreted != null && interpreted.length() > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("💡 ");
            sb.append(interpreted);
            // 如果原始结果和 interpreted 完全一样且是短文本（模板产物），不重复展示
            boolean duplicateRaw = info.result != null && info.result.equals(info.interpretedMessage)
                    && info.result.length() < 800;
            if (!duplicateRaw && baseContent != null && baseContent.length() > 0
                    && !info.interpretedMessage.contentEquals(baseContent)) {
                sb.append(SmartQuizApplication.getAppContext().getString(R.string.h_129d0a0f));
                sb.append(baseContent);
            }
            if (applyMarkdown) {
                mainContent = formatMessageContent(sb.toString());
            } else {
                mainContent = sb.toString();
            }
        } else {
            mainContent = baseContent;
        }

        try {
            // canInterpret 由 AIChatActivity 在工具执行完毕时一次性赋值，
            // 此处仅读取 boolean，不做任何重量级操作（避免触发 ALChat 初始化等导致气泡不显示）
            final boolean shouldShowChip =
                    info.status == ChatMessage.ToolCallInfo.ToolCallStatus.COMPLETED
                            && info.rawResult != null
                            && !info.interpretationDone
                            && info.canInterpret;
            if (!shouldShowChip) {
                return mainContent;
            }
            final String chipText = "\n\n🔍 点击使用AI深度解读此结果";
            SpannableStringBuilder ssb = new SpannableStringBuilder();
            ssb.append(mainContent == null ? "" : mainContent);
            ssb.append(chipText);
            final int chipStart = (mainContent == null ? 0 : mainContent.length()) + 2;
            final int chipEnd = ssb.length();
            if (chipStart < chipEnd) {
                ssb.setSpan(new BackgroundColorSpan(ThemeColors.get(R.color.hc_ffe3f2fd)), chipStart, chipEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ForegroundColorSpan(ThemeColors.get(R.color.hc_ff1565c0)), chipStart, chipEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                final String messageId = message.id;
                ClickableSpan clickSpan = new ClickableSpan() {
                    @Override
                    public void onClick(@NonNull android.view.View widget) {
                        try {
                            info.interpretationDone = true;
                            int pos = messages.indexOf(message);
                            if (pos >= 0) notifyItemChanged(pos, PAYLOAD_STATUS_UPDATE);
                            if (actionClickListener != null) {
                                actionClickListener.onAction(ChatMessage.Action.aiInterpretResult(messageId));
                            }
                        } catch (Throwable t) {
                            android.util.Log.w("ChatAdapter", "AI解读按钮点击异常: " + t.getMessage());
                        }
                    }
                    // 不重写 updateDrawState，使用 ClickableSpan 默认行为（下划线+颜色），
                    // 避免在 TextView 渲染阶段因自定义 drawState 异常导致整个 item 渲染失败
                };
                ssb.setSpan(clickSpan, chipStart, chipEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return ssb;
        } catch (Throwable t) {
            android.util.Log.w("ChatAdapter", "buildResultWithInterpretChip 异常: " + t.getMessage());
            return mainContent == null ? "" : mainContent;
        }
    }

    private void bindAgentStepMessage(AgentStepViewHolder holder, ChatMessage message) {
        ChatMessage.AgentStepInfo stepInfo = message.agentStepInfo;
        if (stepInfo == null) return;

        // 设置步骤图标
        holder.stepIcon.setText(stepInfo.getStepIcon());

        // 构建自然语言描述
        StringBuilder description = new StringBuilder();
        
        // 根据步骤类型和内容构建详细描述
        switch (stepInfo.stepType) {
            case THINKING:
                if (stepInfo.thought != null && !stepInfo.thought.isEmpty()) {
                    description.append("💭 ").append(stepInfo.thought);
                } else {
                    description.append(SmartQuizApplication.getAppContext().getString(R.string.h_eed93c0c));
                }
                break;
            case PLANNING:
                if (stepInfo.thought != null && !stepInfo.thought.isEmpty()) {
                    // 显示具体的意图分析结果
                    description.append(SmartQuizApplication.getAppContext().getString(R.string.h_e1510213)).append(stepInfo.thought);
                    if (stepInfo.action != null && !stepInfo.action.isEmpty()) {
                        description.append(SmartQuizApplication.getAppContext().getString(R.string.h_b01a18bd)).append(stepInfo.action);
                    }
                } else {
                    description.append(SmartQuizApplication.getAppContext().getString(R.string.h_b5c7f7a2));
                }
                break;
            case ACTING:
                if (stepInfo.action != null && !stepInfo.action.isEmpty()) {
                    description.append(SmartQuizApplication.getAppContext().getString(R.string.h_04c76d51)).append(stepInfo.action);
                    if (stepInfo.observation != null && !stepInfo.observation.isEmpty()) {
                        description.append(SmartQuizApplication.getAppContext().getString(R.string.h_acac05fd)).append(stepInfo.observation);
                    }
                } else {
                    description.append(SmartQuizApplication.getAppContext().getString(R.string.h_0aad2da9));
                }
                break;
            case OBSERVING:
                if (stepInfo.observation != null && !stepInfo.observation.isEmpty()) {
                    description.append(SmartQuizApplication.getAppContext().getString(R.string.h_2a8126f5)).append(stepInfo.observation);
                    if (stepInfo.thought != null && !stepInfo.thought.isEmpty()) {
                        description.append(SmartQuizApplication.getAppContext().getString(R.string.h_2ed56b05)).append(stepInfo.thought);
                    }
                } else {
                    description.append(SmartQuizApplication.getAppContext().getString(R.string.h_ee1be032));
                }
                break;
            case REFLECTING:
                if (stepInfo.thought != null && !stepInfo.thought.isEmpty()) {
                    description.append(SmartQuizApplication.getAppContext().getString(R.string.h_821a7c42)).append(stepInfo.thought);
                    if (stepInfo.action != null && !stepInfo.action.isEmpty()) {
                        description.append(SmartQuizApplication.getAppContext().getString(R.string.h_967876bc)).append(stepInfo.action);
                    }
                } else {
                    description.append(SmartQuizApplication.getAppContext().getString(R.string.h_e2f0f38e));
                }
                break;
            case TOOL_CALLING:
                if (stepInfo.action != null && !stepInfo.action.isEmpty()) {
                    description.append(SmartQuizApplication.getAppContext().getString(R.string.h_74b3bd13)).append(stepInfo.action);
                    if (stepInfo.observation != null && !stepInfo.observation.isEmpty()) {
                        description.append(SmartQuizApplication.getAppContext().getString(R.string.h_5ae08a6b)).append(stepInfo.observation);
                    }
                } else {
                    description.append(SmartQuizApplication.getAppContext().getString(R.string.h_181e086c));
                }
                break;
            case REASONING:
                if (stepInfo.thought != null && !stepInfo.thought.isEmpty()) {
                    description.append(SmartQuizApplication.getAppContext().getString(R.string.h_f27b7129)).append(stepInfo.thought);
                    if (stepInfo.detail != null && !stepInfo.detail.isEmpty()) {
                        description.append(SmartQuizApplication.getAppContext().getString(R.string.h_e05c2bda)).append(stepInfo.detail);
                    }
                } else {
                    description.append(SmartQuizApplication.getAppContext().getString(R.string.h_24030883));
                }
                break;
            case LOOPING:
                description.append(SmartQuizApplication.getAppContext().getString(R.string.h_62402e9c));
                if (stepInfo.detail != null && !stepInfo.detail.isEmpty()) {
                    description.append("\n").append(stepInfo.detail);
                }
                if (stepInfo.iteration > 0 && stepInfo.totalIterations > 0) {
                    description.append(SmartQuizApplication.getAppContext().getString(R.string.h_499acacb)).append(stepInfo.iteration).append("/").append(stepInfo.totalIterations);
                }
                break;
            case PAUSED:
                description.append(SmartQuizApplication.getAppContext().getString(R.string.h_a2466280));
                if (stepInfo.detail != null && !stepInfo.detail.isEmpty()) {
                    description.append("\n").append(stepInfo.detail);
                }
                break;
            case COMPLETED:
                description.append(SmartQuizApplication.getAppContext().getString(R.string.h_f8c4c633));
                if (stepInfo.detail != null && !stepInfo.detail.isEmpty()) {
                    description.append("\n").append(stepInfo.detail);
                }
                break;
            default:
                description.append(SmartQuizApplication.getAppContext().getString(R.string.h_1d956dde));
                if (stepInfo.thought != null && !stepInfo.thought.isEmpty()) {
                    description.append("\n").append(stepInfo.thought);
                }
        }

        holder.stepDescription.setText(description.toString());

        // 长描述默认折叠（maxLines=3），点击展开
        final String descText = description.toString();
        boolean isLongDesc = descText.length() > 100;
        if (isLongDesc) {
            applyStepExpansion(holder, stepInfo);
            holder.stepDescription.setOnClickListener(v -> {
                stepInfo.stepExpanded = !stepInfo.stepExpanded;
                applyStepExpansion(holder, stepInfo);
            });
        } else {
            holder.stepDescription.setMaxLines(Integer.MAX_VALUE);
            holder.stepDescription.setEllipsize(null);
            holder.stepDescription.setOnClickListener(null);
        }

        // 显示推理模式（小标签）
        if (stepInfo.reasoningMode != null && !stepInfo.reasoningMode.isEmpty()) {
            holder.stepReasoningMode.setText(stepInfo.reasoningMode);
            holder.stepReasoningMode.setVisibility(View.VISIBLE);
        } else {
            holder.stepReasoningMode.setVisibility(View.GONE);
        }

        // 显示详细信息（如果有额外的detail且不在主要描述中）
        if (stepInfo.detail != null && !stepInfo.detail.isEmpty() && 
            description.toString().contains(stepInfo.detail)) {
            // detail已经在描述中显示了，不再重复
            holder.stepDetail.setVisibility(View.GONE);
        } else if (stepInfo.detail != null && !stepInfo.detail.isEmpty()) {
            holder.stepDetail.setVisibility(View.VISIBLE);
            holder.stepDetail.setText(stepInfo.detail);
        } else {
            holder.stepDetail.setVisibility(View.GONE);
        }

        // 显示进度条
        if (!stepInfo.isCompleted) {
            holder.stepProgress.setVisibility(View.VISIBLE);
            holder.stepProgress.setIndeterminate(true);
        } else {
            holder.stepProgress.setVisibility(View.GONE);
        }
    }

    /** 应用 Agent 步骤描述的折叠/展开状态 */
    private void applyStepExpansion(AgentStepViewHolder holder, ChatMessage.AgentStepInfo stepInfo) {
        if (stepInfo.stepExpanded) {
            holder.stepDescription.setMaxLines(Integer.MAX_VALUE);
            holder.stepDescription.setEllipsize(null);
        } else {
            holder.stepDescription.setMaxLines(3);
            holder.stepDescription.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
    }

    private void bindAgentReflectionMessage(AgentReflectionViewHolder holder, ChatMessage message) {
        ChatMessage.AgentReflectionInfo reflection = message.agentReflectionInfo;
        if (reflection == null) return;

        holder.reflectionIcon.setText("🔍");
        holder.reflectionLabel.setText(SmartQuizApplication.getAppContext().getString(R.string.h_c086bda6));

        if (reflection.analysis != null && !reflection.analysis.isEmpty()) {
            holder.reflectionAnalysis.setText(reflection.analysis);
        }

        if (reflection.improvements != null && !reflection.improvements.isEmpty()) {
            holder.reflectionImprovements.setVisibility(View.VISIBLE);
            holder.reflectionImprovements.setText(SmartQuizApplication.getAppContext().getString(R.string.h_ecbc64cc) + reflection.improvements);
        } else {
            holder.reflectionImprovements.setVisibility(View.GONE);
        }

        if (reflection.retrySuggested) {
            holder.btnRetrySuggestion.setVisibility(View.VISIBLE);
            holder.btnRetrySuggestion.setOnClickListener(v -> {
                if (retryClickListener != null) {
                    retryClickListener.onRetry(message.id);
                }
            });
        } else {
            holder.btnRetrySuggestion.setVisibility(View.GONE);
        }
    }

    private void bindAgentSummaryMessage(AgentSummaryViewHolder holder, ChatMessage message) {
        ChatMessage.AgentSummaryInfo summary = message.agentSummaryInfo;
        if (summary == null) return;

        // 设置状态
        if (summary.isSuccess) {
            holder.summaryStatus.setText(SmartQuizApplication.getAppContext().getString(R.string.h_8a28fd77));
            holder.summaryStatus.setTextColor(holder.itemView.getContext().getColor(R.color.agent_summary_success));
        } else {
            holder.summaryStatus.setText(SmartQuizApplication.getAppContext().getString(R.string.h_1f21bef7));
            holder.summaryStatus.setTextColor(holder.itemView.getContext().getColor(R.color.agent_summary_error));
        }

        // 设置统计信息
        holder.summaryTime.setText(summary.getFormattedTime());
        holder.summarySteps.setText(String.valueOf(summary.totalSteps));
        holder.summaryTools.setText(String.valueOf(summary.toolCallCount));
        holder.summaryTokens.setText(summary.getFormattedTokens());

        // 详细汇总文本（工具名/思考轮次/缓存命中等）
        if (holder.summaryText != null) {
            String detail = summary.summary;
            if (detail != null && !detail.trim().isEmpty()) {
                holder.summaryText.setText(detail);
                holder.summaryText.setVisibility(View.VISIBLE);
            } else {
                holder.summaryText.setVisibility(View.GONE);
            }
        }
    }

    private void bindSummaryMessage(SummaryMessageViewHolder holder, ChatMessage message) {
        holder.summaryIcon.setText("📊");
        holder.summaryLabel.setText(SmartQuizApplication.getAppContext().getString(R.string.h_25f9c7fa));
        holder.summaryContent.post(() -> {
            int availableWidth = holder.itemView.getWidth()
                - holder.itemView.getPaddingLeft()
                - holder.itemView.getPaddingRight()
                - dpToPx(2, holder.itemView.getContext());
            if (availableWidth <= 0) availableWidth = getScreenWidth(holder.itemView.getContext());
            setRenderedText(holder.summaryContent, message.content, availableWidth);
        });
        holder.summaryContent.setMovementMethod(LinkMovementMethod.getInstance());

        if (message.summaryInfo != null) {
            if (message.summaryInfo.stepsCount > 0) {
                holder.summaryMeta.setText(SmartQuizApplication.getAppContext().getString(R.string.h_ba889559) + message.summaryInfo.stepsCount + SmartQuizApplication.getAppContext().getString(R.string.h_668fffcd));
            }
            if (message.summaryInfo.totalTimeMs > 0) {
                holder.summaryMeta.setText(holder.summaryMeta.getText() + SmartQuizApplication.getAppContext().getString(R.string.h_67ec4a7b) + message.summaryInfo.totalTimeMs + "ms");
            }
        }

        holder.btnCopySummary.setOnClickListener(v -> {
            copyToClipboard(v.getContext(), message.content);
        });

        holder.btnExport.setOnClickListener(v -> {
            if (actionClickListener != null) {
                actionClickListener.onAction(ChatMessage.Action.exportSummary(message.id));
            }
        });
    }

    private void bindErrorMessage(ErrorMessageViewHolder holder, ChatMessage message) {
        holder.errorMessage.setText(message.content);

        if (message.errorDetail != null && !message.errorDetail.isEmpty()) {
            holder.errorDetail.setVisibility(View.VISIBLE);
            holder.errorDetail.setText(SmartQuizApplication.getAppContext().getString(R.string.h_4cb2e994) + message.errorDetail);
        } else {
            holder.errorDetail.setVisibility(View.GONE);
        }

        if (message.retryable) {
            holder.btnRetry.setVisibility(View.VISIBLE);
            holder.btnRetry.setOnClickListener(v -> {
                if (retryClickListener != null) {
                    retryClickListener.onRetry(message.id);
                }
            });
        } else {
            holder.btnRetry.setVisibility(View.GONE);
        }

        holder.btnReport.setOnClickListener(v -> {
            if (actionClickListener != null) {
                actionClickListener.onAction(ChatMessage.Action.reportError(message.id, message.content));
            }
        });
    }

    private int getStepTypeColor(Context context, ChatMessage.AgentStepInfo.AgentStepType type) {
        if (type == null) return context.getColor(R.color.agent_step_background);
        switch (type) {
            case THINKING:
                return context.getColor(R.color.agent_thinking_background);
            case PLANNING:
                return context.getColor(R.color.agent_thinking_background);
            case ACTING:
                return context.getColor(R.color.agent_action_background);
            case OBSERVING:
                return context.getColor(R.color.agent_observation_background);
            case REFLECTING:
                return context.getColor(R.color.agent_reflection_background);
            default:
                return context.getColor(R.color.agent_step_background);
        }
    }

    private void copyToClipboard(Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Chat Message", text);
        clipboard.setPrimaryClip(clip);
    }

    private void shareText(Context context, String text) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, text);
        context.startActivity(Intent.createChooser(shareIntent, "分享消息"));
    }

    public void setOnAttachmentClickListener(MessageAttachmentAdapter.OnAttachmentClickListener listener) {
        this.attachmentClickListener = listener;
    }

    /**
     * 绑定用户消息中的附件（图片预览等）
     */
    private void bindUserAttachments(UserMessageViewHolder holder, ChatMessage message) {
        if (holder.attachmentsRecycler == null) return;

        if (message.attachments != null && !message.attachments.isEmpty()) {
            holder.attachmentsRecycler.setVisibility(View.VISIBLE);

            boolean hasMultipleImages = message.attachments.stream().allMatch(a -> a.isImage()) && message.attachments.size() > 1;
            boolean needGridLayout = hasMultipleImages;
            boolean isCurrentGrid = holder.attachmentsRecycler.getLayoutManager()
                    instanceof androidx.recyclerview.widget.GridLayoutManager;

            if (needGridLayout && !isCurrentGrid) {
                androidx.recyclerview.widget.GridLayoutManager gridLayout =
                    new androidx.recyclerview.widget.GridLayoutManager(
                        holder.itemView.getContext(),
                        message.attachments.size() > 4 ? 2 : Math.min(2, message.attachments.size()));
                holder.attachmentsRecycler.setLayoutManager(gridLayout);
            } else if (!needGridLayout && isCurrentGrid) {
                androidx.recyclerview.widget.LinearLayoutManager linearLayout =
                    new androidx.recyclerview.widget.LinearLayoutManager(holder.itemView.getContext());
                holder.attachmentsRecycler.setLayoutManager(linearLayout);
            } else if (holder.attachmentsRecycler.getLayoutManager() == null) {
                androidx.recyclerview.widget.LinearLayoutManager linearLayout =
                    new androidx.recyclerview.widget.LinearLayoutManager(holder.itemView.getContext());
                holder.attachmentsRecycler.setLayoutManager(linearLayout);
            }

            MessageAttachmentAdapter attachmentAdapter = null;
            if (holder.attachmentsRecycler.getAdapter() instanceof MessageAttachmentAdapter) {
                attachmentAdapter = (MessageAttachmentAdapter) holder.attachmentsRecycler.getAdapter();
            } else {
                attachmentAdapter = new MessageAttachmentAdapter();
                attachmentAdapter.setOnAttachmentClickListener(attachmentClickListener);
                holder.attachmentsRecycler.setAdapter(attachmentAdapter);
            }

            attachmentAdapter.setAttachments(message.attachments);
        } else {
            holder.attachmentsRecycler.setVisibility(View.GONE);
            if (holder.attachmentsRecycler.getAdapter() != null) {
                holder.attachmentsRecycler.setAdapter(null);
            }
        }
    }

    private void bindAttachments(AIMessageViewHolder holder, ChatMessage message) {
        if (holder.attachmentsRecycler == null) return;

        if (message.attachments != null && !message.attachments.isEmpty()) {
            holder.attachmentsRecycler.setVisibility(View.VISIBLE);

            // 根据附件类型选择 LayoutManager，ViewHolder 复用时需检查是否需要切换
            boolean hasMultipleImages = message.attachments.stream().allMatch(a -> a.isImage()) && message.attachments.size() > 1;
            boolean needGridLayout = hasMultipleImages;
            boolean isCurrentGrid = holder.attachmentsRecycler.getLayoutManager()
                    instanceof androidx.recyclerview.widget.GridLayoutManager;

            if (needGridLayout && !isCurrentGrid) {
                androidx.recyclerview.widget.GridLayoutManager gridLayout =
                    new androidx.recyclerview.widget.GridLayoutManager(
                        holder.itemView.getContext(),
                        message.attachments.size() > 4 ? 2 : Math.min(2, message.attachments.size()));
                holder.attachmentsRecycler.setLayoutManager(gridLayout);
            } else if (!needGridLayout && isCurrentGrid) {
                androidx.recyclerview.widget.LinearLayoutManager linearLayout =
                    new androidx.recyclerview.widget.LinearLayoutManager(holder.itemView.getContext());
                holder.attachmentsRecycler.setLayoutManager(linearLayout);
            }

            MessageAttachmentAdapter attachmentAdapter = null;
            if (holder.attachmentsRecycler.getAdapter() instanceof MessageAttachmentAdapter) {
                attachmentAdapter = (MessageAttachmentAdapter) holder.attachmentsRecycler.getAdapter();
            } else {
                attachmentAdapter = new MessageAttachmentAdapter();
                attachmentAdapter.setOnAttachmentClickListener(attachmentClickListener);
                holder.attachmentsRecycler.setAdapter(attachmentAdapter);
            }

            attachmentAdapter.setAttachments(message.attachments);
        } else {
            holder.attachmentsRecycler.setVisibility(View.GONE);
            if (holder.attachmentsRecycler.getAdapter() != null) {
                holder.attachmentsRecycler.setAdapter(null);
            }
        }
    }

    private ChatMessage.ThinkingStep.ThinkingStepType parseThinkingStepType(String typeStr) {
        if (typeStr == null) return ChatMessage.ThinkingStep.ThinkingStepType.UNDERSTAND;
        String upper = typeStr.toUpperCase().trim();
        try {
            return ChatMessage.ThinkingStep.ThinkingStepType.valueOf(upper);
        } catch (IllegalArgumentException e) {
            if (upper.contains("UNDERSTAND") || upper.contains("理解")) return ChatMessage.ThinkingStep.ThinkingStepType.UNDERSTAND;
            if (upper.contains("INTENT") || upper.contains("意图")) return ChatMessage.ThinkingStep.ThinkingStepType.INTENT;
            if (upper.contains("PLAN") || upper.contains("规划")) return ChatMessage.ThinkingStep.ThinkingStepType.PLANNING;
            if (upper.contains("DECOMPOSE") || upper.contains("分解")) return ChatMessage.ThinkingStep.ThinkingStepType.DECOMPOSE;
            if (upper.contains("EXECUTE") || upper.contains("执行")) return ChatMessage.ThinkingStep.ThinkingStepType.EXECUTE;
            if (upper.contains("SEARCH") || upper.contains("搜索")) return ChatMessage.ThinkingStep.ThinkingStepType.SEARCH;
            if (upper.contains("ANALYSIS") || upper.contains("分析")) return ChatMessage.ThinkingStep.ThinkingStepType.ANALYSIS;
            if (upper.contains("GENERATE") || upper.contains("生成")) return ChatMessage.ThinkingStep.ThinkingStepType.GENERATE;
            if (upper.contains("VERIFY") || upper.contains("验证")) return ChatMessage.ThinkingStep.ThinkingStepType.VERIFY;
            if (upper.contains("SUMMARIZE") || upper.contains("总结")) return ChatMessage.ThinkingStep.ThinkingStepType.SUMMARIZE;
            return ChatMessage.ThinkingStep.ThinkingStepType.UNDERSTAND;
        }
    }

    @Override
    public int getItemCount() {
        return messages != null ? messages.size() : 0;
    }

    public void updateAIMessageContent(int position, String content) {
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        message.content = content;
        notifyItemChanged(position, PAYLOAD_CONTENT_UPDATE);
    }

    public void updateAIMessageContentWithPayload(int position, String content) {
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        message.content = content;
        notifyItemChanged(position, PAYLOAD_CONTENT_UPDATE);
    }

    public void updateMessageStatus(int position, ChatMessage.MessageStatus status) {
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        message.status = status;
        notifyItemChanged(position, PAYLOAD_STATUS_UPDATE);
    }

    /**
     * 去除思考标签标记（保留内容），用于渲染思考气泡时兜底清理模板声明的标签。
     * 优先用 chat template 的标签（非硬编码）；模板不可用时回退到旧的 <think> 系正则，
     * 保持对未识别模型的兼容。
     */
    private static String stripThinkingTagMarkers(String content) {
        if (content == null || content.isEmpty()) return content;
        ThinkingTagConfig cfg = LlamaHelper.getThinkingTags();
        String cleaned = cfg.removeTagMarkers(content);
        if (!cfg.isAvailable()) {
            cleaned = content.replaceAll("<think[^>]*>", "").replace("</think>", "").replace("<think>", "");
        }
        return cleaned.trim();
    }

    public void updateMessageThinkingContent(int position, String thinkingContent) {
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        message.thinkingContent = thinkingContent;
        notifyItemChanged(position, PAYLOAD_THINKING_UPDATE);
    }

    public void updateToolCallStatus(int position, ChatMessage.ToolCallInfo.ToolCallStatus status, String result) {
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        if (message.toolCallInfo != null) {
            message.toolCallInfo.status = status;
            if (result != null) {
                message.toolCallInfo.result = result;
            }
            if (status == ChatMessage.ToolCallInfo.ToolCallStatus.COMPLETED
                    || status == ChatMessage.ToolCallInfo.ToolCallStatus.FAILED) {
                message.toolCallInfo.executionTimeMs = System.currentTimeMillis() - message.timestamp;
            }
            notifyItemChanged(position);
        }
    }

    public void updateAgentStep(int position, String thought, String action, String observation, boolean isCompleted) {
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        if (message.agentStepInfo != null) {
            if (thought != null) message.agentStepInfo.thought = thought;
            if (action != null) message.agentStepInfo.action = action;
            if (observation != null) message.agentStepInfo.observation = observation;
            message.agentStepInfo.isCompleted = isCompleted;
            notifyItemChanged(position);
        }
    }

    /** 查找指定agentGroupId的header消息 */
    public ChatMessage findAgentGroupHeader(String groupId) {
        if (groupId == null) return null;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg.isAgentGroupHeader && groupId.equals(msg.agentGroupId)) {
                return msg;
            }
        }
        return null;
    }

    /** 更新agent组的计数器并刷新header显示 */
    public void updateAgentGroupCounts(String groupId, int stepCount, int toolCount) {
        ChatMessage header = findAgentGroupHeader(groupId);
        if (header != null) {
            header.agentGroupStepCount = stepCount;
            header.agentGroupToolCount = toolCount;
            int pos = messages.indexOf(header);
            if (pos >= 0) notifyItemChanged(pos);
        }
    }

    public void updateAIMessageFull(int position, String content, String thinkingContent, ChatMessage.MessageStatus status) {
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        if (content != null) message.content = content;
        if (thinkingContent != null) message.thinkingContent = thinkingContent;
        if (status != null) message.status = status;
        // 三个字段同时更新，使用全量rebind避免遗漏status刷新
        notifyItemChanged(position);
    }

    public void updateMessageGenerationStats(int position, int tokensGenerated, long generationTimeMs) {
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        message.tokensGenerated = tokensGenerated;
        message.generationTimeMs = generationTimeMs;
        notifyItemChanged(position, PAYLOAD_STATUS_UPDATE);
    }

    public int findMessagePosition(String messageId) {
        for (int i = 0; i < messages.size(); i++) {
            if (messageId.equals(messages.get(i).id)) {
                return i;
            }
        }
        return -1;
    }

    /** 按消息 ID 实时查找消息（索引漂移免疫；未找到返回 null） */
    public ChatMessage getMessageById(String messageId) {
        if (messageId == null) return null;
        for (ChatMessage m : messages) {
            if (m != null && messageId.equals(m.id)) return m;
        }
        return null;
    }

    /** 按回合 ID 查找该回合内最后一条消息（消息对聚合用；未找到返回 null） */
    public ChatMessage getLastMessageByTurnId(String turnId) {
        if (turnId == null) return null;
        ChatMessage last = null;
        for (ChatMessage m : messages) {
            if (m != null && turnId.equals(m.turnId)) last = m;
        }
        return last;
    }

    /** 按回合 ID 返回该回合（单轮内）全部消息，保持顺序；未找到返回空列表 */
    public List<ChatMessage> getMessagesByTurnId(String turnId) {
        List<ChatMessage> result = new java.util.ArrayList<>();
        if (turnId == null) return result;
        for (ChatMessage m : messages) {
            if (m != null && turnId.equals(m.turnId)) result.add(m);
        }
        return result;
    }

    /** 按消息类型返回全部消息（分类管理：user/AI/工具/系统…）；未找到返回空列表 */
    public List<ChatMessage> getMessagesByType(ChatMessage.MessageType type) {
        List<ChatMessage> result = new java.util.ArrayList<>();
        if (type == null) return result;
        for (ChatMessage m : messages) {
            if (m != null && m.type == type) result.add(m);
        }
        return result;
    }

    /** 按「回合 × 类型」返回消息（单轮内分类定位）；未找到返回空列表 */
    public List<ChatMessage> getMessagesByTurnAndType(String turnId, ChatMessage.MessageType type) {
        List<ChatMessage> result = new java.util.ArrayList<>();
        if (turnId == null || type == null) return result;
        for (ChatMessage m : messages) {
            if (m != null && turnId.equals(m.turnId) && m.type == type) result.add(m);
        }
        return result;
    }

    /** 按子 id（subId，如 T1-F2）精确查找消息；未找到返回 null */
    public ChatMessage getBySubId(String subId) {
        if (subId == null) return null;
        for (ChatMessage m : messages) {
            if (m != null && subId.equals(m.subId)) return m;
        }
        return null;
    }

    public ChatMessage getMessage(int position) {
        if (position < 0 || position >= messages.size()) return null;
        return messages.get(position);
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    static class UserMessageViewHolder extends RecyclerView.ViewHolder {
        TextView blockLabel;
        TextView messageText;
        TextView timestampText;
        ImageView statusIcon;
        TextView statusText;
        androidx.recyclerview.widget.RecyclerView attachmentsRecycler;

        UserMessageViewHolder(View itemView) {
            super(itemView);
            blockLabel = itemView.findViewById(R.id.block_label);
            messageText = itemView.findViewById(R.id.message_text);
            timestampText = itemView.findViewById(R.id.timestamp_text);
            statusIcon = itemView.findViewById(R.id.status_icon);
            statusText = itemView.findViewById(R.id.status_text);
            attachmentsRecycler = itemView.findViewById(R.id.attachments_recycler);
        }
    }

    /**
     * 动态构建的 AI 消息根容器：持有全部子视图引用（替代 findViewById/布局文件 id）。
     * 仅用于 createAiMessageItem 动态路径；其直接子 View 是实际内容根。
     */
    static class DynamicAiMessageRoot extends LinearLayout {
        final TextView blockLabel;
        final TextView messageText;
        final LinearLayout contentHost;
        final LinearLayout componentContainer;
        final TextView thinkingLabel;
        final TextView thinkingContent;
        final View thinkingDivider;
        final TextView agentStatus;
        final TextView agentSummary;
        final View actionButtons;
        final TextView btnCopy;
        final TextView btnSpeak;
        final TextView btnShare;
        final TextView btnRegenerate;
        final TextView btnNewChat;
        final TextView timestampText;
        final ImageView statusIcon;
        final TextView statusText;
        final TextView btnExpand;
        final InferenceProgressView inferenceProgressView;
        final androidx.recyclerview.widget.RecyclerView attachmentsRecycler;
        final View modelInfoContainer;
        final View modelStatusIndicator;
        final TextView modelNameText;
        final TextView modelLatencyText;
        final TextView modelCostText;

        DynamicAiMessageRoot(Context ctx, TextView blockLabel, TextView messageText,
                LinearLayout contentHost, LinearLayout componentContainer, TextView thinkingLabel,
                TextView thinkingContent, View thinkingDivider, TextView agentStatus, TextView agentSummary,
                View actionButtons, TextView btnCopy, TextView btnSpeak, TextView btnShare,
                TextView btnRegenerate, TextView btnNewChat, TextView timestampText, ImageView statusIcon,
                TextView statusText, TextView btnExpand, InferenceProgressView inferenceProgressView,
                androidx.recyclerview.widget.RecyclerView attachmentsRecycler, View modelInfoContainer,
                View modelStatusIndicator, TextView modelNameText, TextView modelLatencyText,
                TextView modelCostText) {
            super(ctx);
            this.blockLabel = blockLabel;
            this.messageText = messageText;
            this.contentHost = contentHost;
            this.componentContainer = componentContainer;
            this.thinkingLabel = thinkingLabel;
            this.thinkingContent = thinkingContent;
            this.thinkingDivider = thinkingDivider;
            this.agentStatus = agentStatus;
            this.agentSummary = agentSummary;
            this.actionButtons = actionButtons;
            this.btnCopy = btnCopy;
            this.btnSpeak = btnSpeak;
            this.btnShare = btnShare;
            this.btnRegenerate = btnRegenerate;
            this.btnNewChat = btnNewChat;
            this.timestampText = timestampText;
            this.statusIcon = statusIcon;
            this.statusText = statusText;
            this.btnExpand = btnExpand;
            this.inferenceProgressView = inferenceProgressView;
            this.attachmentsRecycler = attachmentsRecycler;
            this.modelInfoContainer = modelInfoContainer;
            this.modelStatusIndicator = modelStatusIndicator;
            this.modelNameText = modelNameText;
            this.modelLatencyText = modelLatencyText;
            this.modelCostText = modelCostText;
        }
    }

    static class AIMessageViewHolder extends RecyclerView.ViewHolder {
        TextView blockLabel;
        TextView messageText;
        LinearLayout contentHost;
        LinearLayout componentContainer;
        /** 当前绑定的消息（2026-09-14：思考轮独立折叠状态读写 / 轮次组件 id 锚点） */
        ChatMessage holderMessage;
        /** 已绑定的组件列表引用：同一引用跳过重建（避免流式/重复刷新闪烁） */
        List<ComponentData> boundComponents;
        /** 宿主模式（插入式组件）组件段 View 缓存：流式更新时复用，避免 WebView/图表/图片反复重建闪烁 */
        List<View> componentSegmentViews;
        /** 组件段指纹（type|props 序列）：与缓存 View 对应，指纹不变则复用 */
        String componentSegmentsFingerprint;
        /** 思考区展开/折叠动画：点击切换时先取消旧动画，防止连续点击/流式更新时动画竞争 */
        android.animation.ValueAnimator thinkingAnimator;
        TextView thinkingLabel;
        TextView thinkingContent;
        View thinkingDivider;
        TextView agentStatus;
        TextView agentSummary;
        View actionButtons;
        TextView btnCopy;
        TextView btnSpeak;
        TextView btnShare;
        TextView btnRegenerate;
        TextView btnNewChat;
        TextView timestampText;
        ImageView statusIcon;
        TextView statusText;
        TextView expandButton;
        InferenceProgressView inferenceProgressView;
        AgentExecutionView agentExecutionView;
        androidx.recyclerview.widget.RecyclerView attachmentsRecycler;
        // 在线模型信息
        View modelInfoContainer;
        View modelStatusIndicator;
        TextView modelNameText;
        TextView modelLatencyText;
        TextView modelCostText;

        AIMessageViewHolder(View itemView) {
            super(itemView);
            // AI 消息完全动态构建（createAiMessageItem），itemView 必为 DynamicAiMessageRoot
            DynamicAiMessageRoot dr = (DynamicAiMessageRoot) itemView;
            this.blockLabel = dr.blockLabel;
            this.messageText = dr.messageText;
            this.contentHost = dr.contentHost;
            this.componentContainer = dr.componentContainer;
            this.thinkingLabel = dr.thinkingLabel;
            this.thinkingContent = dr.thinkingContent;
            this.thinkingDivider = dr.thinkingDivider;
            this.agentStatus = dr.agentStatus;
            this.agentSummary = dr.agentSummary;
            this.actionButtons = dr.actionButtons;
            this.btnCopy = dr.btnCopy;
            this.btnSpeak = dr.btnSpeak;
            this.btnShare = dr.btnShare;
            this.btnRegenerate = dr.btnRegenerate;
            this.btnNewChat = dr.btnNewChat;
            this.timestampText = dr.timestampText;
            this.statusIcon = dr.statusIcon;
            this.statusText = dr.statusText;
            this.expandButton = dr.btnExpand;
            this.inferenceProgressView = dr.inferenceProgressView;
            this.attachmentsRecycler = dr.attachmentsRecycler;
            this.modelInfoContainer = dr.modelInfoContainer;
            this.modelStatusIndicator = dr.modelStatusIndicator;
            this.modelNameText = dr.modelNameText;
            this.modelLatencyText = dr.modelLatencyText;
            this.modelCostText = dr.modelCostText;
        }
    }

    static class SystemMessageViewHolder extends RecyclerView.ViewHolder {
        TextView systemIcon;
        TextView messageText;

        SystemMessageViewHolder(View itemView) {
            super(itemView);
            systemIcon = itemView.findViewById(R.id.system_icon);
            messageText = itemView.findViewById(R.id.message_text);
        }
    }

    static class ThinkingMessageViewHolder extends RecyclerView.ViewHolder {
        TextView messageText;
        ProgressBar thinkingProgress;
        TextView thinkingLabel;

        ThinkingMessageViewHolder(View itemView) {
            super(itemView);
            messageText = itemView.findViewById(R.id.message_text);
            thinkingProgress = itemView.findViewById(R.id.thinking_progress);
            thinkingLabel = itemView.findViewById(R.id.thinking_label);
        }

        void bind(ChatMessage message) {
            // 构建标签文字：与 AI 消息内嵌思考区一致的"💭 思考过程"；Agent 多轮思考附加轮次
            String label;
            boolean isAgentRound = message.agentMode && message.taskProgress != null && message.taskProgress > 0;
            if (isAgentRound) {
                label = SmartQuizApplication.getAppContext().getString(R.string.h_8aaa43c5) + message.taskProgress + SmartQuizApplication.getAppContext().getString(R.string.h_ad32c319);
            } else {
                label = "💭 思考过程";
            }

            // 处理思考内容（displayContent/processing 提升到方法级，供折叠点击监听器复用）
            String displayContent = "";
            if (message.thinkingContent != null && !message.thinkingContent.isEmpty()) {
                // 清理思考标签（标签来自 chat template，不硬编码）
                displayContent = stripThinkingTagMarkers(message.thinkingContent);
            }
            if (displayContent.isEmpty() && message.content != null) {
                displayContent = message.content;
            }
            if (displayContent.isEmpty() && message.status == ChatMessage.MessageStatus.IN_PROGRESS) {
                displayContent = "思考中...";
            }
            boolean processing = message.status == ChatMessage.MessageStatus.GENERATING
                    || message.status == ChatMessage.MessageStatus.IN_PROGRESS;
            if (messageText != null) {

                // 展开/折叠控制：由 thinkingExpanded 决定（思考中/思考后均默认折叠，点击展开）
                // 折叠态展示"段落首行摘要"预览（dsh ReasoningRow 同款），不再整块隐藏
                if (message.thinkingExpanded) {
                    messageText.setVisibility(View.VISIBLE);
                    messageText.setMaxLines(Integer.MAX_VALUE);
                    messageText.setAlpha(1f);
                    if (!displayContent.isEmpty()) {
                        renderThinkingFull(messageText, displayContent);
                    } else {
                        messageText.setText("");
                    }
                    if (thinkingLabel != null) {
                        thinkingLabel.setText(label);
                    }
                } else {
                    String preview = displayContent.isEmpty()
                            ? ""
                            : thinkingPreview(displayContent, processing);
                    messageText.setVisibility(View.VISIBLE);
                    messageText.setMaxLines(2);
                    messageText.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    messageText.setAlpha(0.6f);
                    messageText.setText(preview.isEmpty() ? "…" : preview + "…");
                    if (thinkingLabel != null) {
                        thinkingLabel.setText(label + (processing ? SmartQuizApplication.getAppContext().getString(R.string.h_1e913a58) : SmartQuizApplication.getAppContext().getString(R.string.h_7492ce53)));
                    }
                }
            }

            if (thinkingLabel != null) {
                thinkingLabel.setVisibility(View.VISIBLE);
            }

            // 处理进度条：仅思考进行中时显示（折叠时隐藏，精简视觉）
            if (thinkingProgress != null) {
                if (processing) {
                    thinkingProgress.setVisibility(View.VISIBLE);
                    thinkingProgress.setIndeterminate(true);
                } else {
                    thinkingProgress.setVisibility(View.GONE);
                }
            }

            // 点击标签折叠/展开：展开时全量 Markdown 渲染，折叠时切回摘要预览
            // （lambda 需 effectively-final 快照；displayContent 因多分支赋值不可直接用）
            final String contentSnapshot = displayContent;
            final boolean processingSnapshot = processing;
            if (thinkingLabel != null) {
                thinkingLabel.setOnClickListener(v -> {
                    message.thinkingExpanded = !message.thinkingExpanded;
                    if (message.thinkingExpanded) {
                        messageText.setVisibility(View.VISIBLE);
                        messageText.setMaxLines(Integer.MAX_VALUE);
                        messageText.setAlpha(1f);
                        if (!contentSnapshot.isEmpty()) {
                            renderThinkingFull(messageText, contentSnapshot);
                        }
                        thinkingLabel.setText(label);
                    } else {
                        String preview = contentSnapshot.isEmpty()
                                ? ""
                                : thinkingPreview(contentSnapshot, processingSnapshot);
                        messageText.setVisibility(View.VISIBLE);
                        messageText.setMaxLines(2);
                        messageText.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        messageText.setAlpha(0.6f);
                        messageText.setText(preview.isEmpty() ? "…" : preview + "…");
                        thinkingLabel.setText(label + SmartQuizApplication.getAppContext().getString(R.string.h_7492ce53));
                    }
                });
            }
        }

        /** 全量 Markdown 渲染（RenderExecutor + TextViewSpan），供展开态/点击展开时调用 */
        private void renderThinkingFull(android.widget.TextView tv, String content) {
            if (tv == null) return;
            tv.post(() -> {
                int w = tv.getWidth() - tv.getPaddingLeft() - tv.getPaddingRight();
                if (w <= 0) {
                    w = tv.getContext().getResources().getDisplayMetrics().widthPixels;
                }
                ChatAdapter.renderInto(tv, content, w);
            });
        }
    }

    static class TaskMessageViewHolder extends RecyclerView.ViewHolder {
        TextView taskLabel;
        TextView messageText;
        ProgressBar taskProgress;

        TaskMessageViewHolder(View itemView) {
            super(itemView);
            taskLabel = itemView.findViewById(R.id.task_label);
            messageText = itemView.findViewById(R.id.message_text);
            taskProgress = itemView.findViewById(R.id.task_progress);
        }
    }

    static class ToolCallViewHolder extends RecyclerView.ViewHolder {
        View toolCallHeader;
        TextView toolCallArrow;
        TextView toolIcon;
        TextView toolName;
        TextView toolStatus;
        TextView toolParams;
        View toolParamsContainer;
        View toolParamsHeader;
        TextView toolParamsArrow;
        View toolResultContainer;
        View toolResultHeader;
        TextView toolResultArrow;
        TextView toolResultLabel;
        TextView toolResult;
        ProgressBar toolProgress;

        ToolCallViewHolder(View itemView) {
            super(itemView);
            toolCallHeader = itemView.findViewById(R.id.tool_call_header);
            toolCallArrow = itemView.findViewById(R.id.tool_call_arrow);
            toolIcon = itemView.findViewById(R.id.tool_icon);
            toolName = itemView.findViewById(R.id.tool_name);
            toolStatus = itemView.findViewById(R.id.tool_status);
            toolParams = itemView.findViewById(R.id.tool_params);
            toolParamsContainer = itemView.findViewById(R.id.tool_params_container);
            toolParamsHeader = itemView.findViewById(R.id.tool_params_header);
            toolParamsArrow = itemView.findViewById(R.id.tool_params_arrow);
            toolResultContainer = itemView.findViewById(R.id.tool_result_container);
            toolResultHeader = itemView.findViewById(R.id.tool_result_header);
            toolResultArrow = itemView.findViewById(R.id.tool_result_arrow);
            toolResultLabel = itemView.findViewById(R.id.tool_result_label);
            toolResult = itemView.findViewById(R.id.tool_result);
            toolProgress = itemView.findViewById(R.id.tool_progress_small);
        }
    }

    static class ToolResultViewHolder extends RecyclerView.ViewHolder {
        TextView toolIcon;
        TextView toolName;
        TextView toolResult;
        TextView toolTime;
        Button btnCopyResult;
        Button btnViewDetails;

        ToolResultViewHolder(View itemView) {
            super(itemView);
            toolIcon = itemView.findViewById(R.id.tool_icon);
            toolName = itemView.findViewById(R.id.tool_name);
            toolResult = itemView.findViewById(R.id.tool_result);
            toolTime = itemView.findViewById(R.id.tool_time);
            btnCopyResult = itemView.findViewById(R.id.btn_copy_result);
            btnViewDetails = itemView.findViewById(R.id.btn_view_details);
        }
    }

    static class AgentStepViewHolder extends RecyclerView.ViewHolder {
        TextView stepIcon;
        TextView stepDescription;
        TextView stepReasoningMode;
        TextView stepDetail;
        ProgressBar stepProgress;

        AgentStepViewHolder(View itemView) {
            super(itemView);
            stepIcon = itemView.findViewById(R.id.step_icon);
            stepDescription = itemView.findViewById(R.id.step_description);
            stepReasoningMode = itemView.findViewById(R.id.step_reasoning_mode);
            stepDetail = itemView.findViewById(R.id.step_detail);
            stepProgress = itemView.findViewById(R.id.step_progress_bar);
        }
    }

    static class AgentReflectionViewHolder extends RecyclerView.ViewHolder {
        TextView reflectionIcon;
        TextView reflectionLabel;
        TextView reflectionAnalysis;
        TextView reflectionImprovements;
        Button btnRetrySuggestion;

        AgentReflectionViewHolder(View itemView) {
            super(itemView);
            reflectionIcon = itemView.findViewById(R.id.reflection_icon);
            reflectionLabel = itemView.findViewById(R.id.reflection_label);
            reflectionAnalysis = itemView.findViewById(R.id.reflection_analysis);
            reflectionImprovements = itemView.findViewById(R.id.reflection_improvements);
            btnRetrySuggestion = itemView.findViewById(R.id.btn_retry_suggestion);
        }
    }

    static class AgentSummaryViewHolder extends RecyclerView.ViewHolder {
        TextView summaryTitle;
        TextView summaryStatus;
        TextView summaryTime;
        TextView summarySteps;
        TextView summaryTools;
        TextView summaryTokens;
        TextView summaryText;

        AgentSummaryViewHolder(View itemView) {
            super(itemView);
            summaryTitle = itemView.findViewById(R.id.summary_title);
            summaryStatus = itemView.findViewById(R.id.summary_status);
            summaryTime = itemView.findViewById(R.id.summary_time);
            summarySteps = itemView.findViewById(R.id.summary_steps);
            summaryTools = itemView.findViewById(R.id.summary_tools);
            summaryTokens = itemView.findViewById(R.id.summary_tokens);
            summaryText = itemView.findViewById(R.id.summary_text);
        }
    }

    static class SummaryMessageViewHolder extends RecyclerView.ViewHolder {
        TextView summaryIcon;
        TextView summaryLabel;
        TextView summaryContent;
        TextView summaryMeta;
        Button btnCopySummary;
        Button btnExport;

        SummaryMessageViewHolder(View itemView) {
            super(itemView);
            summaryIcon = itemView.findViewById(R.id.summary_icon);
            summaryLabel = itemView.findViewById(R.id.summary_label);
            summaryContent = itemView.findViewById(R.id.summary_content);
            summaryMeta = itemView.findViewById(R.id.summary_meta);
            btnCopySummary = itemView.findViewById(R.id.btn_copy_summary);
            btnExport = itemView.findViewById(R.id.btn_export);
        }
    }

    static class ErrorMessageViewHolder extends RecyclerView.ViewHolder {
        TextView errorTitle;
        TextView errorMessage;
        TextView errorDetail;
        TextView btnRetry;
        TextView btnReport;

        ErrorMessageViewHolder(View itemView) {
            super(itemView);
            errorTitle = itemView.findViewById(R.id.error_title);
            errorMessage = itemView.findViewById(R.id.error_message);
            errorDetail = itemView.findViewById(R.id.error_detail);
            btnRetry = itemView.findViewById(R.id.btn_retry);
            btnReport = itemView.findViewById(R.id.btn_report);
        }
    }

    public void updateMessages(List<ChatMessage> newMessages) {
        if (newMessages == null) return;
        ChatMessageDiffCallback diffCallback = new ChatMessageDiffCallback(messages, newMessages);
        androidx.recyclerview.widget.DiffUtil.DiffResult diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(diffCallback);
        messages.clear();
        messages.addAll(newMessages);
        diffResult.dispatchUpdatesTo(this);
    }

    public void addMessage(ChatMessage message) {
        if (message == null) return;
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
        scrollToPosition(messages.size() - 1);
    }

    public void removeMessage(String messageId) {
        int position = findMessagePosition(messageId);
        if (position >= 0) {
            messages.remove(position);
            notifyItemRemoved(position);
        }
    }

    public void appendToken(String messageId, String token) {
        int position = findMessagePosition(messageId);
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        if (message.content == null) {
            message.content = token;
        } else {
            message.content = message.content + token;
        }
        notifyItemChanged(position, PAYLOAD_CONTENT_UPDATE);
    }

    public void updateInferenceProgress(String messageId, ChatMessage.InferencePhase phase, 
                                        int processedTokens, float tokensPerSecond) {
        int position = findMessagePosition(messageId);
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        if (message.inferenceProgress == null) {
            message.inferenceProgress = new ChatMessage.InferenceProgress(phase);
        } else {
            message.inferenceProgress.phase = phase;
        }
        message.inferenceProgress.processedTokens = processedTokens;
        message.inferenceProgress.tokensPerSecond = tokensPerSecond;
        notifyItemChanged(position, PAYLOAD_STATUS_UPDATE);
    }

    public void updateThinkingStep(String messageId, int stepNumber, String stepType, 
                                   String stepTitle, String stepContent, int progress) {
        int position = findMessagePosition(messageId);
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        if (message.thinkingSteps == null) {
            message.thinkingSteps = new java.util.ArrayList<>();
        }
        
        boolean found = false;
        for (int i = 0; i < message.thinkingSteps.size(); i++) {
            ChatMessage.ThinkingStep step = message.thinkingSteps.get(i);
            if (step.stepNumber == stepNumber) {
                step.stepType = parseThinkingStepType(stepType);
                step.name = stepTitle;
                step.title = stepTitle;
                step.description = stepContent;
                step.content = stepContent;
                step.progress = progress;
                found = true;
                break;
            }
        }
        
        if (!found) {
            message.thinkingSteps.add(new ChatMessage.ThinkingStep(stepNumber, stepType, stepTitle, stepContent, progress));
        }
        
        notifyItemChanged(position, PAYLOAD_THINKING_UPDATE);
    }

    public void completeMessage(String messageId, String finalContent, int tokensGenerated, long generationTimeMs) {
        int position = findMessagePosition(messageId);
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        if (finalContent != null) {
            message.content = finalContent;
        }
        message.status = ChatMessage.MessageStatus.COMPLETED;
        message.tokensGenerated = tokensGenerated;
        message.generationTimeMs = generationTimeMs;
        if (message.inferenceProgress != null) {
            message.inferenceProgress.phase = ChatMessage.InferencePhase.COMPLETED;
        }
        notifyItemChanged(position);
    }

    public void startAgentExecution(String messageId) {
        int position = findMessagePosition(messageId);
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);

        // 创建或重置执行状态
        if (message.agentExecutionState == null) {
            message.agentExecutionState = new com.oilquiz.app.ai.agent.AgentExecutionState(messageId);
        } else {
            message.agentExecutionState.reset();
        }
        message.agentExecutionState.start();

        // 只通过payload通知UI，不直接操作ViewHolder
        notifyItemChanged(position, PAYLOAD_AGENT_UPDATE);
    }

    public void updateAgentExecutionStep(String messageId, int stepNumber, String stepType,
                                         String title, String content) {
        int position = findMessagePosition(messageId);
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);

        if (message.agentExecutionState != null) {
            com.oilquiz.app.ai.agent.AgentExecutionState.BlockType blockType =
                mapStepTypeToBlockType(stepType);
            message.agentExecutionState.addBlock(blockType, title, content);
        }

        notifyItemChanged(position, PAYLOAD_AGENT_UPDATE);
    }

    private com.oilquiz.app.ai.agent.AgentExecutionState.BlockType mapStepTypeToBlockType(String stepType) {
        if (stepType == null) return com.oilquiz.app.ai.agent.AgentExecutionState.BlockType.TEXT;
        switch (stepType.toUpperCase()) {
            case "THINKING": return com.oilquiz.app.ai.agent.AgentExecutionState.BlockType.THINKING;
            case "PLANNING": return com.oilquiz.app.ai.agent.AgentExecutionState.BlockType.PLANNING;
            case "TOOL_CALL": return com.oilquiz.app.ai.agent.AgentExecutionState.BlockType.TOOL_CALL;
            case "TOOL_RESULT": return com.oilquiz.app.ai.agent.AgentExecutionState.BlockType.TOOL_RESULT;
            case "INFERENCE": return com.oilquiz.app.ai.agent.AgentExecutionState.BlockType.INFERENCE;
            case "OBSERVATION": return com.oilquiz.app.ai.agent.AgentExecutionState.BlockType.OBSERVATION;
            case "REFLECTION": return com.oilquiz.app.ai.agent.AgentExecutionState.BlockType.REFLECTION;
            case "ERROR": return com.oilquiz.app.ai.agent.AgentExecutionState.BlockType.ERROR;
            default: return com.oilquiz.app.ai.agent.AgentExecutionState.BlockType.TEXT;
        }
    }

    public void updateAgentToolCall(String messageId, String toolName, String args) {
        int position = findMessagePosition(messageId);
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);

        if (message.agentExecutionState != null) {
            int idx = message.agentExecutionState.getCurrentBlockIndex();
            if (idx >= 0) {
                com.oilquiz.app.ai.agent.AgentExecutionState.ExecutionBlock block =
                    message.agentExecutionState.getBlocks().get(idx);
                if (block.getType() == com.oilquiz.app.ai.agent.AgentExecutionState.BlockType.TOOL_CALL) {
                    block.setToolName(toolName);
                    block.setToolArgs(args);
                }
            }
        }

        notifyItemChanged(position, PAYLOAD_AGENT_UPDATE);
    }

    public void updateAgentToolResult(String messageId, String toolName, boolean success, String result) {
        int position = findMessagePosition(messageId);
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);

        if (message.agentExecutionState != null) {
            int idx = message.agentExecutionState.getCurrentBlockIndex();
            if (idx >= 0) {
                com.oilquiz.app.ai.agent.AgentExecutionState.ExecutionBlock block =
                    message.agentExecutionState.getBlocks().get(idx);
                if (block.getType() == com.oilquiz.app.ai.agent.AgentExecutionState.BlockType.TOOL_RESULT) {
                    block.setToolSuccess(success);
                    block.setToolResult(result);
                    block.setStatus(com.oilquiz.app.ai.agent.AgentExecutionState.ExecutionBlock.BlockStatus.COMPLETED);
                }
            }
        }

        notifyItemChanged(position, PAYLOAD_AGENT_UPDATE);
    }

    public void updateAgentInferenceProgress(String messageId, int tokenCount, float tokensPerSecond) {
        int position = findMessagePosition(messageId);
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);

        if (message.agentExecutionState != null) {
            message.agentExecutionState.updateTokenStats(tokenCount, tokensPerSecond);
        }

        notifyItemChanged(position, PAYLOAD_AGENT_UPDATE);
    }

    public void appendAgentToken(String messageId, String token) {
        int position = findMessagePosition(messageId);
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);

        if (message.agentExecutionState != null) {
            int idx = message.agentExecutionState.getCurrentBlockIndex();
            if (idx >= 0) {
                message.agentExecutionState.appendBlockContent(idx, token);
            }
        }

        notifyItemChanged(position, PAYLOAD_AGENT_UPDATE);
    }

    public void completeAgentExecution(String messageId, String finalContent) {
        int position = findMessagePosition(messageId);
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);

        if (message.agentExecutionState != null) {
            message.agentExecutionState.complete(finalContent);
            message.agentExecutionState.addLog("COMPLETE", SmartQuizApplication.getAppContext().getString(R.string.h_c044a14e));
        }

        notifyItemChanged(position, PAYLOAD_AGENT_UPDATE);
    }

    public void failAgentExecution(String messageId, String error) {
        int position = findMessagePosition(messageId);
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);

        if (message.agentExecutionState != null) {
            message.agentExecutionState.fail(error);
            message.agentExecutionState.addBlock(
                com.oilquiz.app.ai.agent.AgentExecutionState.BlockType.ERROR,
                "执行错误", error);
            message.agentExecutionState.addLog("ERROR", SmartQuizApplication.getAppContext().getString(R.string.h_23cc6892) + error);
        }

        notifyItemChanged(position, PAYLOAD_AGENT_UPDATE);
    }

    public void failMessage(String messageId, String errorMessage) {
        int position = findMessagePosition(messageId);
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        message.status = ChatMessage.MessageStatus.FAILED;
        message.errorDetail = errorMessage;
        if (message.inferenceProgress != null) {
            message.inferenceProgress.phase = ChatMessage.InferencePhase.FAILED;
        }
        notifyItemChanged(position);
    }

    public void cancelMessage(String messageId) {
        int position = findMessagePosition(messageId);
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        message.status = ChatMessage.MessageStatus.COMPLETED;
        notifyItemChanged(position);
    }

    private void scrollToPosition(int position) {
        if (attachedRecyclerView != null && position >= 0) {
            attachedRecyclerView.smoothScrollToPosition(position);
        }
    }

    public void handleStreamingEvent(com.oilquiz.app.ai.chat.event.StreamingEvent event) {
        if (event == null) return;
        
        boolean isAgentMode = isAgentModeMessage(event.messageId);
        
        switch (event.type) {
            case MESSAGE_CREATED:
                String initialContent = event.getStringData();
                if (initialContent == null) initialContent = "";
                ChatMessage newMessage = new ChatMessage.Builder(ChatMessage.MessageType.AI)
                    .id(event.messageId)
                    .content(initialContent)
                    .status(ChatMessage.MessageStatus.GENERATING)
                    .agentMode(isAgentMode)
                    .inferenceProgress(new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.GENERATING))
                    .build();
                addMessage(newMessage);
                
                if (isAgentMode) {
                    startAgentExecution(event.messageId);
                }
                break;
                
            case TOKEN_APPENDED:
                com.oilquiz.app.ai.chat.event.StreamingEvent.TokenData tokenData = event.getTokenData();
                if (tokenData != null) {
                    appendToken(event.messageId, tokenData.token);
                    if (isAgentMode) {
                        appendAgentToken(event.messageId, tokenData.token);
                    }
                }
                break;
                
            case THINKING_UPDATE:
                com.oilquiz.app.ai.chat.event.StreamingEvent.ThinkingStepData thinkingUpdateData = event.getThinkingStepData();
                if (thinkingUpdateData != null) {
                    updateThinkingStep(event.messageId, thinkingUpdateData.stepNumber, thinkingUpdateData.stepType,
                        thinkingUpdateData.title, thinkingUpdateData.content, thinkingUpdateData.progress);
                    
                    if (isAgentMode) {
                        // Agent模式下，block由AgentExecutionEngine创建，这里只刷新UI
                        notifyItemChanged(findMessagePosition(event.messageId), PAYLOAD_AGENT_UPDATE);
                    }
                }
                break;
                
            case THINKING_STEP:
                com.oilquiz.app.ai.chat.event.StreamingEvent.ThinkingStepData thinkingData = event.getThinkingStepData();
                if (thinkingData != null) {
                    updateThinkingStep(event.messageId, thinkingData.stepNumber, thinkingData.stepType,
                        thinkingData.title, thinkingData.content, thinkingData.progress);
                    
                    if (isAgentMode) {
                        // Agent模式下，block由AgentExecutionEngine创建，这里只刷新UI
                        notifyItemChanged(findMessagePosition(event.messageId), PAYLOAD_AGENT_UPDATE);
                    }
                }
                break;
                
            case INFERENCE_PROGRESS:
                com.oilquiz.app.ai.chat.event.StreamingEvent.InferenceProgressData progressData = event.getInferenceProgressData();
                if (progressData != null) {
                    updateInferenceProgress(event.messageId, progressData.phase,
                        progressData.processedTokens, progressData.tokensPerSecond);
                    
                    if (isAgentMode) {
                        updateAgentInferenceProgress(event.messageId, 
                            progressData.processedTokens, progressData.tokensPerSecond);
                    }
                }
                break;
                
            case MESSAGE_COMPLETED:
                com.oilquiz.app.ai.chat.event.StreamingEvent.CompletionData completionData = event.getCompletionData();
                String finalContent = completionData != null ? completionData.content : null;
                int tokens = completionData != null ? completionData.tokensGenerated : 0;
                long time = completionData != null ? completionData.generationTimeMs : 0;
                completeMessage(event.messageId, finalContent, tokens, time);
                
                if (isAgentMode) {
                    completeAgentExecution(event.messageId, finalContent);
                }
                break;
                
            case MESSAGE_FAILED:
                failMessage(event.messageId, event.getStringData());
                
                if (isAgentMode) {
                    failAgentExecution(event.messageId, event.getStringData());
                }
                break;
                
            case MESSAGE_CANCELLED:
                cancelMessage(event.messageId);
                break;
        }
    }
    
    private boolean isAgentModeMessage(String messageId) {
        // 检查消息自身的 agentMode 标志（在线模型自动 Agent，不再有独立 Agent 模式）
        int position = findMessagePosition(messageId);
        if (position >= 0 && position < messages.size()) {
            ChatMessage message = messages.get(position);
            if (message != null && message.agentMode) return true;
        }
        return false;
    }

    // ===================== Selection Mode =====================

    /**
     * 进入选择模式
     */
    public void enterSelectionMode() {
        selectionMode = true;
        selectedMessageIds.clear();
        notifyDataSetChanged();
        if (selectionChangeListener != null) {
            selectionChangeListener.onSelectionModeChanged(true);
        }
    }

    /**
     * 退出选择模式
     */
    public void exitSelectionMode() {
        selectionMode = false;
        selectedMessageIds.clear();
        notifyDataSetChanged();
        if (selectionChangeListener != null) {
            selectionChangeListener.onSelectionModeChanged(false);
        }
    }

    /**
     * 是否在选择模式
     */
    public boolean isInSelectionMode() {
        return selectionMode;
    }

    /**
     * 切换消息选择状态
     */
    public void toggleSelection(String messageId) {
        if (selectedMessageIds.contains(messageId)) {
            selectedMessageIds.remove(messageId);
        } else {
            selectedMessageIds.add(messageId);
        }
        int position = findMessagePosition(messageId);
        if (position != -1) {
            notifyItemChanged(position, "selection_change");
        }
        if (selectionChangeListener != null) {
            selectionChangeListener.onSelectionChanged(selectedMessageIds.size());
        }
    }

    /**
     * 全选/取消全选
     */
    public void selectAll(boolean select) {
        if (select) {
            for (ChatMessage msg : messages) {
                if (msg.id != null) {
                    selectedMessageIds.add(msg.id);
                }
            }
        } else {
            selectedMessageIds.clear();
        }
        notifyDataSetChanged();
        if (selectionChangeListener != null) {
            selectionChangeListener.onSelectionChanged(selectedMessageIds.size());
        }
    }

    /**
     * 获取选中的消息ID
     */
    public java.util.Set<String> getSelectedMessageIds() {
        return new java.util.HashSet<>(selectedMessageIds);
    }

    /**
     * 删除选中的消息
     */
    public void deleteSelectedMessages() {
        java.util.Iterator<ChatMessage> iterator = messages.iterator();
        while (iterator.hasNext()) {
            ChatMessage msg = iterator.next();
            if (msg.id != null && selectedMessageIds.contains(msg.id)) {
                iterator.remove();
            }
        }
        selectedMessageIds.clear();
        exitSelectionMode();
        notifyDataSetChanged();
    }

    /**
     * 设置选择变化监听器
     */
    public void setOnSelectionChangeListener(OnSelectionChangeListener listener) {
        this.selectionChangeListener = listener;
    }

    public interface OnSelectionChangeListener {
        void onSelectionModeChanged(boolean inSelectionMode);
        void onSelectionChanged(int selectedCount);
    }

    // ===================== Animation Control =====================

    /**
     * 设置是否启用动画
     */
    public void setAnimationsEnabled(boolean enabled) {
        this.animationsEnabled = enabled;
    }

    /**
     * 应用进入动画（方向性：用户消息从右滑入，AI/系统消息从左滑入 + 淡入）。
     * 参考主流聊天应用（微信/Signal）与 wasabeef/recyclerview-animators 建议：
     * 消息进入用轻量方向滑动 + 透明度，避免纯淡入的"无方向感"和过度动画。
     * 用消息 id 集合记录已动画项，滚动回滚后重新 bind 不再重复动画。
     */
    private void setAnimation(View viewToAnimate, ChatMessage message) {
        if (!animationsEnabled || message == null || viewToAnimate == null) return;
        if (animatedMessageIds.contains(message.id)) return;
        animatedMessageIds.add(message.id);

        boolean isUser = message.isUserMessage();
        float fromX = isUser ? viewToAnimate.getWidth() * 0.15f : -viewToAnimate.getWidth() * 0.15f;
        android.view.animation.AnimationSet set = new android.view.animation.AnimationSet(true);
        android.view.animation.TranslateAnimation translate = new android.view.animation.TranslateAnimation(
                fromX, 0f, 0f, 0f);
        translate.setDuration(ANIMATION_DURATION);
        translate.setInterpolator(new android.view.animation.DecelerateInterpolator());
        AlphaAnimation alpha = new AlphaAnimation(0f, 1f);
        alpha.setDuration(ANIMATION_DURATION);
        set.addAnimation(translate);
        set.addAnimation(alpha);
        viewToAnimate.startAnimation(set);
    }

    // ===================== Search & Filter =====================

    private List<ChatMessage> originalMessages;
    private String currentQuery = "";

    /**
     * 搜索过滤消息
     */
    public void filter(String query) {
        this.currentQuery = query.toLowerCase();
        if (originalMessages == null) {
            originalMessages = new java.util.ArrayList<>(messages);
        }
        messages.clear();
        if (query.isEmpty()) {
            messages.addAll(originalMessages);
        } else {
            for (ChatMessage msg : originalMessages) {
                if (msg.content != null && msg.content.toLowerCase().contains(currentQuery)) {
                    messages.add(msg);
                }
            }
        }
        notifyDataSetChanged();
    }

    /**
     * 清除过滤
     */
    public void clearFilter() {
        if (originalMessages != null) {
            messages.clear();
            messages.addAll(originalMessages);
            originalMessages = null;
            currentQuery = "";
            notifyDataSetChanged();
        }
    }

    /**
     * 高亮搜索文本
     */
    public static SpannableStringBuilder highlightText(String text, String query, int highlightColor) {
        SpannableStringBuilder spannable = new SpannableStringBuilder(text);
        if (query == null || query.isEmpty()) {
            return spannable;
        }
        String lowerText = text.toLowerCase();
        String lowerQuery = query.toLowerCase();
        int start = 0;
        while ((start = lowerText.indexOf(lowerQuery, start)) != -1) {
            int end = start + query.length();
            spannable.setSpan(
                new android.text.style.BackgroundColorSpan(highlightColor),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            start = end;
        }
        return spannable;
    }
}