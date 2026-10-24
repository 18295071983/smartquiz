package com.oilquiz.app.ai.chat;

import com.oilquiz.app.SmartQuizApplication;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.agent.ToolResultInterpreter;
import com.oilquiz.app.ai.chat.component.ComponentActions;
import com.oilquiz.app.ai.chat.component.ComponentContentSplitter;
import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.chat.component.ComponentDisplay;
import com.oilquiz.app.ai.chat.component.ComponentRegistry;
import com.oilquiz.app.ai.chat.render.MarkdownRenderer;
import com.oilquiz.app.ai.chat.render.RenderExecutor;
import com.oilquiz.app.ai.chat.ui.ChatV2ActionSheet;
import com.oilquiz.app.ai.chat.ui.MarkdownWebView;
import com.oilquiz.app.ai.chat.ui.ThinkingPreview;
import com.oilquiz.app.ai.chat.util.ToolResultText;
import android.content.res.Configuration;

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
    private static final int VIEW_TYPE_TOOL_CALL = 5;
    private static final int VIEW_TYPE_AGENT_STEP = 6;
    private static final int VIEW_TYPE_AGENT_SUMMARY = 7;
    private static final int VIEW_TYPE_ERROR = 8;
    private static final int VIEW_TYPE_AGENT_REFLECTION = 10;
    /** 上下文注入行（记忆/任务/工具目录等系统注入内容的展示条，点击看全文） */
    private static final int VIEW_TYPE_CONTEXT_INJECTION = 13;
    /** 系统提示词行（system prompt 变更记录/全文展示，点击看全文） */
    private static final int VIEW_TYPE_SYSTEM_PROMPT = 14;

    public static final String PAYLOAD_CONTENT_UPDATE = "content_update";
    public static final String PAYLOAD_STATUS_UPDATE = "status_update";
    public static final String PAYLOAD_THINKING_UPDATE = "thinking_update";
    public static final String PAYLOAD_ATTACHMENT_UPDATE = "attachment_update";
    public static final String PAYLOAD_INFERENCE_PROGRESS = "inference_progress";
    public static final String PAYLOAD_AGENT_UPDATE = "agent_update";

    /** 纯文本气泡是否改用 WebView 渲染（commonmark→HTML），解决混排 HTML/表格的闪烁。默认关，由外部开关。 */
    private boolean webMarkdownEnabled = false;
    public void setWebMarkdownEnabled(boolean enabled) {
        if (this.webMarkdownEnabled == enabled) return;
        this.webMarkdownEnabled = enabled;
        // 开启时收紧 AI ViewHolder 回收上限：同时存活的 WebView 数 ≈ 可见 + 回收数，
        // 不设限则一屏多个 WebView × 几十 MB 在低端机易 OOM。关闭时恢复默认。
        if (attachedRecyclerView != null) {
            attachedRecyclerView.getRecycledViewPool()
                    .setMaxRecycledViews(VIEW_TYPE_AI, enabled ? 4 : 5);
        }
        notifyDataSetChanged(); // 切换渲染器需整体重绑（messageText ↔ markdownView 显隐）
    }

    private final List<ChatMessage> messages;
    private final OnActionClickListener actionClickListener;
    private OnRetryClickListener retryClickListener;
    private OnMessageClickListener messageClickListener;
    private MessageAttachmentAdapter.OnAttachmentClickListener attachmentClickListener;
    private RecyclerView attachedRecyclerView;
    /**
     * parts 内容尺寸变化回调（2026-10-25 流式滚动）：思考正文/正文 WebView 高度变化时通知宿主，
     * 由 {@code StickToBottom} 决定是否贴底跟随（只有 pinned 才动）。
     */
    private Runnable onContentSizeChanged;

    /** 设置 parts 内容尺寸变化监听（Activity 侧接贴底控制器）。 */
    public void setOnContentSizeChanged(Runnable listener) {
        this.onContentSizeChanged = listener;
    }
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

    /** 视图被回收：摘除尚未触发的布局监听，避免过期回调写入被复用的视图 */
    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof AIMessageViewHolder) {
            AIMessageViewHolder ai = (AIMessageViewHolder) holder;
            removePendingLayoutListener(ai);
            ai.holderMessage = null;
            // 回收时清理 Agent 汇总组件槽位，避免残留 view 挂到下一个绑定的消息上
            clearAgentSummarySlot(ai);
            // 活动区行指纹随消息走：回收后必须作废，否则下一条消息可能被误判成"结构未变"而沿用旧行
            ai.activityFp = null;
            ai.toolCardFp = null;
            ai.roundStructureFingerprint = null;
            ai.roundRenderedChildren = 0;
            // 接管标记随 holder 走，回收必须复位：残留 true 会让下一条（可能不被接管的）消息
            // 跳过 bindComponents/updateThinkingContent，表现为产物与思考行凭空消失。
            ai.partsTookOver = false;
            // 接管归属也要随 holder 走：留着上一条消息的 id，下一条消息若恰好复用同一 holder
            // 且 partsTookOver 被后面某条路径置真，就会跳过重装、把旧容器连带旧视图端上去。
            ai.partsTakenOverForId = null;
            // 回收时重置 WebView 正文渲染器（清占位高/待渲染状态），复用到别的消息不残留
            if (ai.markdownView != null) ai.markdownView.resetForReuse();
        }
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

    // 动画配置（2026-10-20 S4：与 demo 的 motion 口径对齐——180ms、短位移、尊重系统动画开关）
    private static final long ANIMATION_DURATION = 180;
    private boolean animationsEnabled = true;
    /** 已播放进入动画的消息 id（避免滚动回滚后重复动画） */
    private final java.util.Set<String> animatedMessageIds = new java.util.HashSet<>();
    /** 已播放完成态内容编排动画的 AI 消息 id（按子块错峰，只播一次） */
    private final java.util.Set<String> revealedContentMessageIds = new java.util.HashSet<>();

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
        // 数据集变化时同步空态视图可见性
        registerAdapterDataObserver(emptyViewObserver);
    }

    /** 无消息时展示的占位视图；由 Activity 通过 setEmptyView 传入 */
    private View emptyView;
    private final androidx.recyclerview.widget.RecyclerView.AdapterDataObserver emptyViewObserver
            = new androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
        @Override public void onChanged() { updateEmptyViewVisibility(); }
        @Override public void onItemRangeInserted(int p, int c) { updateEmptyViewVisibility(); }
        @Override public void onItemRangeRemoved(int p, int c) { updateEmptyViewVisibility(); }
    };

    /**
     * 设置空态视图。传入的 View 会在数据集非空时 GONE、为空时 VISIBLE。
     * View 应由 Activity 从布局中 findViewById 后传入，adapter 只负责切换可见性。
     */
    public void setEmptyView(View view) {
        this.emptyView = view;
        updateEmptyViewVisibility();
    }

    private void updateEmptyViewVisibility() {
        if (emptyView == null) return;
        emptyView.setVisibility(getItemCount() == 0 ? View.VISIBLE : View.GONE);
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
                return new UserMessageViewHolder(inflater.inflate(
                        R.layout.item_user_message_v2, parent, false));
            case VIEW_TYPE_AI:
                // AI 消息完全动态构建（不依赖布局文件/id），杜绝 id 错乱
                return createAiMessageItem(parent.getContext());
            case VIEW_TYPE_SYSTEM:
            case VIEW_TYPE_CONTEXT_INJECTION:
            case VIEW_TYPE_SYSTEM_PROMPT:
                return new SystemMessageViewHolder(inflater.inflate(R.layout.item_system_message, parent, false));
            case VIEW_TYPE_THINKING:
                return new ThinkingMessageViewHolder(inflater.inflate(
                        R.layout.item_thinking_message_v2, parent, false));
            case VIEW_TYPE_TOOL_CALL:
                return new ToolCallViewHolder(inflater.inflate(R.layout.item_tool_call_message, parent, false));
            case VIEW_TYPE_AGENT_STEP:
                return new AgentStepViewHolder(inflater.inflate(
                        R.layout.item_agent_step_message_v2, parent, false));
            case VIEW_TYPE_AGENT_SUMMARY:
                return new AgentSummaryViewHolder(inflater.inflate(R.layout.item_agent_summary_message, parent, false));
            case VIEW_TYPE_ERROR:
                return new ErrorMessageViewHolder(inflater.inflate(R.layout.item_error_message, parent, false));
            case VIEW_TYPE_AGENT_REFLECTION:
                return new AgentReflectionViewHolder(inflater.inflate(R.layout.item_agent_reflection_message, parent, false));
            default:
                return new SystemMessageViewHolder(inflater.inflate(R.layout.item_system_message, parent, false));
        }
    }

    /**
     * 动态构建 AI 消息视图树（完全代码创建，不依赖布局文件与 id）。
     * 结构：根容器 → 日期分隔 → 活动区（思考 + 工具，一个入口）→ 正文/产物 → 状态 → 操作段。
     */
    private AIMessageViewHolder createAiMessageItem(Context ctx) {
        int dp4 = dpToPx(4, ctx);
        int dp6 = dpToPx(6, ctx);
        int dp10 = dpToPx(10, ctx);
        int dp14 = dpToPx(14, ctx);

        // 主题色解析（AI 消息树只保留 v2 token；正文/元信息色已全部走 chat_v2_*）
        int colorOnSurfaceVariant = resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant);
        int colorPrimary = ThemeColors.attr(ctx, R.attr.colorPrimary);

        // ===== 根容器 =====
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp4, dp4, dp4, dp4);
        RecyclerView.LayoutParams rootLp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.setLayoutParams(rootLp);

        // ===== 日期分隔（demo .day：居中一行"今天 14:32"）=====
        // 刻意做成"本行内容的第一个子 View"而不是 RecyclerView 的独立条目：
        // 消息列表就是 chatHistory 本体，各处直接用 position 取消息
        //（Activity 里 notifyItemRangeInserted / chatHistory.get(position) 一大片），
        // 插虚拟条目会让所有位置计算偏移，风险远大于收益。
        // 日期分隔是本条消息行的第一个子 View（不是 RecyclerView 虚拟条目）。
        TextView dayLabel = new TextView(ctx);
        dayLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                ctx.getResources().getDimension(R.dimen.chat_v2_status_text));
        dayLabel.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.chat_v2_text_3));
        dayLabel.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams dayLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dayLp.topMargin = dpToPx(10, ctx);
        dayLp.bottomMargin = dpToPx(6, ctx);
        dayLabel.setLayoutParams(dayLp);
        dayLabel.setVisibility(View.GONE);
        root.addView(dayLabel);

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

        // ===== 主消息列：没有视觉气泡，只有一个竖列 =====
        // 2026-10-21 收敛：思考行 + 工具卡收进同一个 activitySection；
        // 正文与产物卡收进 contentHost；不再用"固定顺序的 bubble"把过程钉死在正文上方。
        // demo 的 AI 侧本来就没有气泡底色，页面底由 ChatUiV2Chrome 铺 chat_v2_bg。
        LinearLayout activitySection = new LinearLayout(ctx);
        activitySection.setOrientation(LinearLayout.VERTICAL);
        activitySection.setPadding(0, dpToPx(2, ctx), 0, 0);
        activitySection.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        vCol.addView(activitySection);

        // --- Agent 执行状态行（思考中/调用工具/完成），实时步骤可视化 ---
        TextView agentStatus = new TextView(ctx);
        agentStatus.setTextSize(11f);
        agentStatus.setTextColor(colorOnSurfaceVariant);
        agentStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams asLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        asLp.bottomMargin = dpToPx(4, ctx);
        agentStatus.setLayoutParams(asLp);
        activitySection.addView(agentStatus);

        // --- Agent 控制组（暂停/单步/回退），流式生成中显示 ---
        LinearLayout agentControlBar = new LinearLayout(ctx);
        agentControlBar.setOrientation(LinearLayout.HORIZONTAL);
        agentControlBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        agentControlBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams acbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        acbLp.bottomMargin = dpToPx(4, ctx);
        agentControlBar.setLayoutParams(acbLp);
        TextView btnPause = createActionButton(ctx, ctx.getString(R.string.button_pause),
                colorOnSurfaceVariant, true);
        TextView btnStep = createActionButton(ctx, ctx.getString(R.string.chat_agent_step),
                colorOnSurfaceVariant, true);
        TextView btnRewind = createActionButton(ctx, ctx.getString(R.string.chat_agent_rewind),
                colorOnSurfaceVariant, false);
        agentControlBar.addView(btnPause);
        agentControlBar.addView(btnStep);
        agentControlBar.addView(btnRewind);
        activitySection.addView(agentControlBar);

        // --- 活动区（demo .think-line / .think-body）---
        // header 是唯一入口，展开后是思考行 + 工具行的时间线；
        // thinkingLabel / thinkingContent / thinkingDivider 三个引用仍由 holder 持有。
        TextView thinkingLabel;
        TextView thinkingContent;
        View thinkingDivider;
        // 2026-10-20：整段思考只有一个折叠入口（上面那行 header），
        // 展开后是"一步一行"的时间线：steps 容器装已定稿的每一步，
        // thinkingContent 仍是"正在进行的当前轮"的实时正文，挂在时间线末尾。
        LinearLayout thinkingStepsHost;
        ImageView thinkingChevron;
        View thinkSection = LayoutInflater.from(ctx)
                .inflate(R.layout.view_chat_v2_think_line, activitySection, false);
        thinkingLabel = thinkSection.findViewById(R.id.chat_v2_think_text);
        thinkingContent = thinkSection.findViewById(R.id.chat_v2_think_body);
        thinkingStepsHost = thinkSection.findViewById(R.id.chat_v2_think_steps);
        thinkingChevron = thinkSection.findViewById(R.id.chat_v2_think_chev);
        // 藏标签会连带藏整行（ThinkLineLabel 把可见性转发给行容器），
        // 否则会剩下一行孤零零的图标和箭头
        thinkingLabel.setVisibility(View.GONE);
        thinkingContent.setTextSize(13f);
        thinkingContent.setLineSpacing(0f, 1.7f);
        activitySection.addView(thinkSection);
        // v2 的正文自带左侧竖线，不需要"思考与正文之间的分隔线"：
        // 给一个不入树的占位，holder 字段保持非空，调用点不用加判空
        thinkingDivider = new View(ctx);

        // --- 独立工具卡（demo .tool-line / .tool-body）---
        // 2026-10-21：按 demo 拆成两段——思考行在上，工具卡在下；卡头是唯一入口。
        View toolCard = LayoutInflater.from(ctx)
                .inflate(R.layout.view_chat_v2_tool_card, activitySection, false);
        toolCard.setVisibility(View.GONE);
        activitySection.addView(toolCard);
        LinearLayout toolCardBody = toolCard.findViewById(R.id.chat_v2_tool_card_body);

        // --- 推理进度 ---
        InferenceProgressView inferenceProgressView = new InferenceProgressView(ctx);
        inferenceProgressView.setVisibility(View.GONE);
        inferenceProgressView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        activitySection.addView(inferenceProgressView);

        // --- 工具卡片组件容器 ---
        LinearLayout componentContainer = new LinearLayout(ctx);
        componentContainer.setOrientation(LinearLayout.VERTICAL);
        componentContainer.setVisibility(View.GONE);
        componentContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- 附件 RecyclerView ---
        androidx.recyclerview.widget.RecyclerView attachmentsRecycler = new androidx.recyclerview.widget.RecyclerView(ctx);
        attachmentsRecycler.setClipToPadding(false);
        attachmentsRecycler.setVisibility(View.GONE);
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arLp.bottomMargin = dpToPx(3, ctx);
        attachmentsRecycler.setLayoutParams(arLp);

        // --- 内容宿主（正文 + 产物卡；含组件标记时动态追加段落） ---
        LinearLayout contentHost = new LinearLayout(ctx);
        contentHost.setOrientation(LinearLayout.VERTICAL);
        contentHost.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 顺序固定为：活动区（思考/工具）→ 正文/产物 → 附件 → 无轮次兜底卡。
        // componentContainer 必须排在 contentHost 之后：无轮次消息的产物卡若挂在正文前面，
        // 会和轮次模式的"正文段→产物"顺序相反（同一张卡两个位置）。
        vCol.addView(contentHost);
        vCol.addView(attachmentsRecycler);
        vCol.addView(componentContainer);

        // --- 正文 ---
        TextView messageText = new TextView(ctx);
        messageText.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        // demo .ai-text：15px / 行高 1.68 / 主文本色（页面已是纯色底，不再靠气泡底色撑对比）
        messageText.setTextColor(androidx.core.content.ContextCompat.getColor(ctx,
                R.color.chat_v2_text));
        messageText.setTextSize(15f);
        messageText.setLineSpacing(0f, 1.68f);
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
        vCol.addView(agentSummary);

        // ===== 操作段：三个图标 +「更多」（demo .ai-actions / #tpl-actions）=====
        LinearLayout v2Actions = new LinearLayout(ctx);
        v2Actions.setOrientation(LinearLayout.HORIZONTAL);
        v2Actions.setVisibility(View.GONE);
        LinearLayout.LayoutParams v2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        v2Lp.topMargin = dpToPx(8, ctx);
        v2Actions.setLayoutParams(v2Lp);
        int iconColor = androidx.core.content.ContextCompat.getColor(ctx, R.color.chat_v2_text_3);
        // demo .ai-actions 的图标间距只有 2px：30dp 的点击区紧挨着排，不要拉开
        int iconGap = dpToPx(2, ctx);
        ImageView v2BtnCopy = createIconAction(ctx, R.drawable.ic_copy,
                R.string.chat_copy, 0, iconColor);
        ImageView v2BtnRegenerate = createIconAction(ctx, R.drawable.ic_refresh,
                R.string.chat_regenerate, 0, iconColor);
        if (v2BtnCopy != null && v2BtnRegenerate != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v2BtnRegenerate.getLayoutParams();
            lp.setMarginStart(iconGap);
        }
        ImageView v2BtnMore = createIconAction(ctx, R.drawable.ic_more_vert,
                R.string.chat_v2_msg_actions, 0, iconColor);
        if (v2BtnMore != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v2BtnMore.getLayoutParams();
            lp.setMarginStart(iconGap);
        }
        v2Actions.addView(v2BtnCopy);
        v2Actions.addView(v2BtnRegenerate);
        v2Actions.addView(v2BtnMore);
        root.addView(v2Actions);

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

        int metaTextColor = androidx.core.content.ContextCompat.getColor(ctx, R.color.chat_v2_text_3);
        TextView statusText = new TextView(ctx);
        statusText.setTextSize(10f);
        statusText.setTextColor(metaTextColor);
        statusText.setVisibility(View.GONE);
        statusRow.addView(statusText);

        // ===== 时间戳 =====
        TextView timestampText = new TextView(ctx);
        timestampText.setTextSize(10f);
        timestampText.setTextColor(metaTextColor);
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tsLp.setMarginStart(dp6);
        timestampText.setLayoutParams(tsLp);
        root.addView(timestampText);

        // 组装为动态根容器（携带全部子视图引用，避免 findViewById）
        DynamicAiMessageRoot dynamicRoot = new DynamicAiMessageRoot(ctx, messageText,
                contentHost, componentContainer, thinkingLabel, thinkingContent, thinkingDivider,
                agentStatus, agentSummary, btnPause, btnStep, btnRewind,
                agentControlBar, timestampText, statusIcon, statusText,
                inferenceProgressView, attachmentsRecycler);
        dynamicRoot.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dynamicRoot.addView(root);
        AIMessageViewHolder holder = new AIMessageViewHolder(dynamicRoot);
        holder.dayLabel = dayLabel;
        holder.thinkSection = thinkSection;
        holder.thinkingRowsHost = thinkingStepsHost;
        holder.thinkingChevron = thinkingChevron;
        holder.toolCard = toolCard;
        holder.toolCardBody = toolCardBody;
        holder.v2ActionRow = v2Actions;
        holder.v2BtnCopy = v2BtnCopy;
        holder.v2BtnRegenerate = v2BtnRegenerate;
        holder.v2BtnMore = v2BtnMore;
        // 纯文本气泡的 WebView 渲染器：作为 contentHost 的第二子 View，默认 GONE，
        // 仅 webMarkdownEnabled 时接管正文（与 messageText 二选一）。随 holder 复用，
        // 存活上限由 Activity 侧 setMaxRecycledViews(VIEW_TYPE_AI, N) 兜底。
        MarkdownWebView mv = new MarkdownWebView(ctx);
        mv.setVisibility(View.GONE);
        contentHost.addView(mv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.markdownView = mv;
        return holder;
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

    /**
     * v2 操作段的图标按钮（demo {@code .act}：30×30、圆角 9、无底色、图标 18）。
     *
     * <p>只负责"长什么样 + 可点/可达"，不接管任何动作语义：点击在 bindV2Actions 里
     * 直接绑到 doCopy/doRegenerate 等方法上。</p>
     *
     * <p>contentDescription 是必需的：这一版把文字换成了图标，
     * 没有它 TalkBack 就念不出这颗键是干什么的（无障碍在 B2 清单里，但改一步就补一步）。</p>
     */
    private ImageView createIconAction(Context ctx, int iconRes, int descRes, int marginEndDp, int tintColor) {
        ImageView iv = new ImageView(ctx);
        int size = dpToPx(30, ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        if (marginEndDp > 0) {
            lp.setMarginEnd(dpToPx(marginEndDp, ctx));
        }
        iv.setLayoutParams(lp);
        iv.setImageResource(iconRes);
        iv.setColorFilter(tintColor);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dpToPx(6, ctx);
        iv.setPadding(pad, pad, pad, pad);
        // 无边框水波纹：demo .act 就是透明底 + hover 才出浅底，不额外造 shape drawable
        iv.setBackground(getSelectableItemBackground(ctx));
        iv.setContentDescription(ctx.getString(descRes));
        iv.setClickable(true);
        iv.setFocusable(true);
        return iv;
    }

    /** 创建操作按钮（小字、可点击） */
    private TextView createActionButton(Context ctx, int textRes, int color, boolean marginEnd) {
        TextView tv = new TextView(ctx);
        tv.setText(textRes);
        return finalizeActionButton(ctx, tv, color, marginEnd);
    }

    private TextView createActionButton(Context ctx, CharSequence text, int color, boolean marginEnd) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        return finalizeActionButton(ctx, tv, color, marginEnd);
    }

    private TextView finalizeActionButton(Context ctx, TextView tv, int color, boolean marginEnd) {
        tv.setTextSize(11f);
        tv.setTextColor(color);
        // 2026-10-20 S5：这套胶囊现在只服务 Agent 控制条（暂停/单步/回退）。
        // 原来的半透明白描边是给深蓝壁纸用的，浅底上等于隐形（报告 §3②），改读 chat_v2_* token。
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dpToPx(10, ctx));
        gd.setStroke(dpToPx(1, ctx), androidx.core.content.ContextCompat.getColor(
                ctx, R.color.chat_v2_border_strong));
        gd.setColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.chat_v2_surface_2));
        tv.setBackground(gd);
        tv.setPadding(dpToPx(8, ctx), dpToPx(3, ctx), dpToPx(8, ctx), dpToPx(3, ctx));
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

        // 旧 Agent 过程消息在 v2 消息流里不再单独占一行（demo 用 Plan/Tool/Log 卡承担）：
        // AGENT_SUMMARY / AGENT_STEP 以及 INFO/SUCCESS/PROGRESS 类系统消息整行收起，
        // 位置保留、高度为 0，不影响 Activity 里按 position 取消息的所有计算。
        if (shouldHideLegacyRow(message)) {
            ViewGroup.LayoutParams hiddenLp = holder.itemView.getLayoutParams();
            if (hiddenLp != null) {
                hiddenLp.height = 0;
                holder.itemView.setLayoutParams(hiddenLp);
            }
            holder.itemView.setVisibility(View.GONE);
            return;
        }
        // 复用池里可能带着上一条的 0 高度：可见消息必须复位
        holder.itemView.setVisibility(View.VISIBLE);
        ViewGroup.LayoutParams shownLp = holder.itemView.getLayoutParams();
        if (shownLp != null && shownLp.height == 0) {
            shownLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            holder.itemView.setLayoutParams(shownLp);
        }

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
                bindUserMessage((UserMessageViewHolder) holder, message, timeStr, position);
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
            case VIEW_TYPE_AGENT_REFLECTION:
                bindAgentReflectionMessage((AgentReflectionViewHolder) holder, message);
                break;
        }
        if (holder instanceof AIMessageViewHolder) {
            // 完成态按内容块（思考行/工具卡/正文段）错峰入场，替代 AI 整条滑入
            scheduleContentReveal((AIMessageViewHolder) holder, message);
        } else {
            // 用户/系统消息：整条方向性入场
            setAnimation(holder.itemView, message);
        }
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
                    // 插入式组件渲染：文本段/组件段交替，组件标记在流式中实时生效
                    refreshStreamingContent(aiHolder, message);
                    // 接管中跳过：思考由 parts 的 reasoning part 渲。§4.79 那轮修的是完成态的双份，
                    // 流式接管后这条 payload 每个 tick 都会来，不防就每 tick 把全局思考行重新点亮一次。
                    if (!aiHolder.partsTookOver) {
                        updateThinkingContent(aiHolder, message);
                    }
                    // STEP-STATUS-PAYLOAD(2026-10-09)：气泡内「步骤状态」行（思考中/调用工具/
                    // 执行完成）此前只由 PAYLOAD_STATUS_UPDATE 刷新，而流式 token 走的是
                    // PAYLOAD_CONTENT_UPDATE 分支。两者都从同一个 message.agentStepStatus 取值，
                    // 若状态只在字符串未变时被写入（如"✅ 执行完成"写两次），全量/状态分支可能
                    // 都不触发，导致该行停留在旧文案（表现为"一直显示思考中"）。
                    // 这里一并刷新，使两条刷新路径的可见状态一致。纯 setText+setVisibility，无副作用。
                    bindAgentStepStatus(aiHolder, message);
                    // COMPONENT-PAYLOAD-REFRESH(2026-10-09)：bindMessageContent 在「有轮次边界」
                    // 时走 renderRoundAssembled 并提前 return，那条路径**不会**调用
                    // bindComponents；而工具卡片状态更新（running→success）正是通过这个 payload
                    // 送达的。缺这一步时，消息级组件容器（折叠行 / 非 tool_call 组件）会停在旧状态，
                    // 直到下一次全量绑定。bindComponents 内部有内容指纹早退，重复调用不会重建。
                    // 接管中跳过：组件由 parts 的 data part 渲，再挂一份就是双渲。
                    if (!aiHolder.partsTookOver) {
                        bindComponents(aiHolder, message);
                    }
                } else if (holder instanceof UserMessageViewHolder) {
                    ((UserMessageViewHolder) holder).messageText.setText(message.content);
                } else if (holder instanceof ThinkingMessageViewHolder) {
                    ((ThinkingMessageViewHolder) holder).bind(message);
                }
            } else if (PAYLOAD_STATUS_UPDATE.equals(payload)) {
                if (holder instanceof AIMessageViewHolder) {
                    // 状态与步骤状态同源，一并刷新，避免二者不同步
                    bindAgentStepStatus((AIMessageViewHolder) holder, message);
                    updateMessageStatus((AIMessageViewHolder) holder, message);
                }
            } else if (PAYLOAD_INFERENCE_PROGRESS.equals(payload)) {
                if (holder instanceof AIMessageViewHolder) {
                    updateMessageStatus((AIMessageViewHolder) holder, message);
                }
            } else if (PAYLOAD_AGENT_UPDATE.equals(payload)) {
                // Agent 执行状态增量刷新：步骤行 + 汇总行 + 状态行
                if (holder instanceof AIMessageViewHolder) {
                    AIMessageViewHolder aiHolder = (AIMessageViewHolder) holder;
                    bindAgentStepStatus(aiHolder, message);
                    updateMessageStatus(aiHolder, message);
                }
            } else if (PAYLOAD_THINKING_UPDATE.equals(payload)) {
                if (holder instanceof AIMessageViewHolder) {
                    AIMessageViewHolder aiHolder = (AIMessageViewHolder) holder;
                    // 思考增量也要灌进 parts（2026-10-25 思考正文接流式）：
                    // parts 接管后，思考正文由 reasoning part 渲染（流式期走 WebView 块级尾巴），
                    // 只发 payload 不重绑 parts 的话，reasoning 正文会冻在上一次内容——
                    // 而 legacy 思考行在接管时是隐藏的，现场就表现为"思考正文不动"。
                    // 未接管时下面仍走 legacy 思考行，两条路径不会同时上屏。
                    if (com.oilquiz.app.ai.chat.parts.PartsRendererFlag.enabled(
                                aiHolder.itemView.getContext())
                            && com.oilquiz.app.ai.chat.parts.PartsStreamingFlag.enabled(
                                aiHolder.itemView.getContext())) {
                        refreshStreamingContent(aiHolder, message);
                    }
                    // 接管中跳过：全局思考行与 parts 的 reasoning part 同源两份内容，
                    // 流式期这条 payload 是按 token 节流发过来的，不防就会反复把旧行点亮。
                    if (!aiHolder.partsTookOver) {
                        updateThinkingContent(aiHolder, message);
                    }
                } else if (holder instanceof ThinkingMessageViewHolder) {
                    ((ThinkingMessageViewHolder) holder).bind(message);
                }
            }
        }
    }

    /**
     * 流式期把最新 {@code message.content / thinkingContent} 重新灌进这条 item 的渲染器。
     *
     * <p>CONTENT_UPDATE 与 THINKING_UPDATE 共用：parts 接管后，正文由 text part、思考由
     * reasoning part 各自增量渲染，payload 必须走到 {@link #bindMessageContent} 才会重新构建
     * parts turn（{@code TurnPartsHost} 再按 part id 复用视图、按内容指纹只重绑变化的那一段）。
     * 旧实现只在 CONTENT_UPDATE 里做这件事，思考期只有 THINKING_UPDATE → parts 已接管时
     * reasoning 正文不刷新（2026-10-25 思考正文接流式时一并修）。</p>
     *
     * <p>宽度未就绪（新建/重建 holder 首帧）时与 CONTENT_UPDATE 同路径回落
     * {@link #renderWithCorrectWidth}，避免按全屏宽兜底渲染表格。</p>
     */
    private void refreshStreamingContent(AIMessageViewHolder aiHolder, ChatMessage message) {
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
    }

    /**
     * 旧 Agent 过程消息是否在 v2 消息流里收起。
     * demo 的过程信息由 Agent 计划/工具/日志卡承担，不再有"收到你的消息…""Agent模式已激活"
     * 这类 INFO 卡片，也没有独立的 AGENT_SUMMARY/AGENT_STEP 行。
     */
    private static boolean shouldHideLegacyRow(ChatMessage m) {
        if (m == null) return false;
        if (m.type == ChatMessage.MessageType.AGENT_SUMMARY
                || m.type == ChatMessage.MessageType.AGENT_STEP) {
            return true;
        }
        if (m.type != ChatMessage.MessageType.SYSTEM) return false;
        if (m.isAgentGroupHeader) return true;
        ChatMessage.SystemMessageType t = m.systemType;
        if (t == null) return false;
        switch (t) {
            case INFO:
            case SUCCESS:
            case TIP:
            case TASK:
            case PROGRESS:
                return true;
            default:
                return false;
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
     * 本条消息是否是"新的一天"的第一条（v2 日期分隔的判据）。
     * 比的是日历日而不是"相隔 24 小时"：凌晨 00:30 与前一天 23:30 只差一小时，但它们属于两天，
     * 分隔行必须落在后者之后。
     */
    private boolean startsNewDay(int position) {
        if (position <= 0) return true;
        if (position >= messages.size()) return false;
        ChatMessage cur = messages.get(position);
        ChatMessage prev = messages.get(position - 1);
        if (cur == null || prev == null) return true;
        return !com.oilquiz.app.ai.chat.ui.ChatDayLabel.isSameDay(cur.timestamp, prev.timestamp);
    }

    /**
     * 日期分隔行（demo {@code .day}："今天 14:32"）。
     *
     * <p>逐条时间戳按设计收起（时间信息已经进这一行日期），
     * "点时间看详情"的入口跟着挪到分隔行上；长按菜单里本来也有「详情」，入口不会丢。</p>
     */
    private void bindDayLabel(TextView dayLabel, int position, ChatMessage message) {
        if (dayLabel == null || message == null) return;
        android.content.Context ctx = dayLabel.getContext();
        if (startsNewDay(position)) {
            dayLabel.setText(com.oilquiz.app.ai.chat.ui.ChatDayLabel.divider(ctx, message.timestamp));
            dayLabel.setVisibility(View.VISIBLE);
            bindTimestampClick(dayLabel, message);
        } else {
            dayLabel.setVisibility(View.GONE);
        }
    }

    /**
     * 设置 itemView 的点击/长按交互。
     * 长按优先展示内置操作菜单（复制/引用/编辑/删除/详情），
     * 若无菜单可用（非 AI/USER 消息）则退回 messageClickListener.onMessageLongClick。
     * ViewHolder 复用时需重置，否则旧状态会残留。
     */
    private void setupItemViewInteraction(View itemView, ChatMessage message) {
        if (messageClickListener != null) {
            itemView.setOnClickListener(v -> messageClickListener.onMessageClick(message));
        } else {
            itemView.setOnClickListener(null);
        }
        // 长按优先走内置操作菜单；无 listener 时不拦截文本选择
        boolean hasMenu = message.type == ChatMessage.MessageType.AI
                || message.type == ChatMessage.MessageType.USER;
        if (hasMenu || messageClickListener != null) {
            itemView.setOnLongClickListener(v -> {
                if (hasMenu) {
                    showActionMenu(v, message);
                } else if (messageClickListener != null) {
                    messageClickListener.onMessageLongClick(message);
                }
                return true;
            });
            itemView.setLongClickable(true);
        } else {
            itemView.setOnLongClickListener(null);
            itemView.setClickable(false);
            itemView.setLongClickable(false);
        }
    }

    /** 把 agentExecutionState.blocks 拼成可读文本（用于"复制步骤内容"） */
    private static String flattenAgentBlocks(ChatMessage message) {
        if (message.agentExecutionState == null
                || message.agentExecutionState.getBlocks() == null) {
            return message.content != null ? message.content : "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (com.oilquiz.app.ai.agent.AgentExecutionState.ExecutionBlock b
                : message.agentExecutionState.getBlocks()) {
            if (b == null) continue;
            sb.append("[").append(i++).append("] ")
              .append(mapBlockTypeToLabel(b.getType()));
            if (b.getTitle() != null && !b.getTitle().isEmpty()) {
                sb.append(" · ").append(b.getTitle());
            }
            sb.append("\n");
            if (b.getContent() != null && !b.getContent().isEmpty()) {
                sb.append(b.getContent()).append("\n");
            }
            if (b.getToolName() != null) {
                sb.append("工具：").append(b.getToolName());
                if (b.getToolArgs() != null) sb.append(" 参数：").append(b.getToolArgs());
                if (b.getToolResult() != null) sb.append(" 结果：").append(b.getToolResult());
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /** 长按操作菜单：AI 显示 复制/朗读/引用/详情；USER 显示 复制/编辑/引用/删除 */
    private void showActionMenu(android.view.View anchor, ChatMessage message) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(
                anchor.getContext(), anchor);
        menu.getMenu().add(0, 1, 0, "📋 复制");
        if (message.type == ChatMessage.MessageType.AI) {
            menu.getMenu().add(0, 2, 1, "🔊 朗读");
        }
        menu.getMenu().add(0, 3, 2, "💬 引用回复");
        if (message.type == ChatMessage.MessageType.USER) {
            menu.getMenu().add(0, 4, 3, "✏️ 编辑重发");
            menu.getMenu().add(0, 5, 4, "🗑 删除");
        }
        menu.getMenu().add(0, 6, 5, "ℹ️ 详情");
        // Agent 消息：额外提供"导出执行日志"和"复制步骤内容"
        if (message.agentMode && message.agentExecutionState != null) {
            menu.getMenu().add(0, 7, 6, "📄 导出执行日志 (JSON)");
            menu.getMenu().add(0, 8, 7, "📝 复制步骤内容");
        }
        final String exportContent = stripComponentMarkersForExport(message.content);
        final String msgId = message.id;
        final String rawContent = message.content;
        menu.setOnMenuItemClickListener(item -> {
            if (actionClickListener == null) return false;
            android.content.Context ctx = anchor.getContext();
            switch (item.getItemId()) {
                case 1:
                    copyToClipboard(ctx, exportContent);
                    actionClickListener.onAction(ChatMessage.Action.copy(exportContent));
                    ComponentActions.dispatchChatAction(ctx, "copy:" + msgId);
                    return true;
                case 2:
                    actionClickListener.onAction(ChatMessage.Action.speak(msgId, exportContent));
                    ComponentActions.dispatchChatAction(ctx, "speak:" + msgId);
                    return true;
                case 3:
                    actionClickListener.onAction(ChatMessage.Action.quoteMessage(msgId, rawContent));
                    ComponentActions.dispatchChatAction(ctx, "quote:" + msgId);
                    return true;
                case 4:
                    actionClickListener.onAction(ChatMessage.Action.editMessage(msgId, rawContent));
                    ComponentActions.dispatchChatAction(ctx, "edit:" + msgId);
                    return true;
                case 5:
                    actionClickListener.onAction(ChatMessage.Action.deleteMessage(msgId));
                    ComponentActions.dispatchChatAction(ctx, "delete:" + msgId);
                    return true;
                case 6:
                    actionClickListener.onAction(ChatMessage.Action.showDetail(msgId));
                    ComponentActions.dispatchChatAction(ctx, "detail:" + msgId);
                    return true;
                case 7:
                    // 导出执行日志为 JSON
                    actionClickListener.onAction(ChatMessage.Action.exportAgentLogs(msgId, "json"));
                    ComponentActions.dispatchChatAction(ctx, "export_logs:" + msgId);
                    return true;
                case 8:
                    // 复制步骤内容：拼接所有 block 的 title+content
                    String joined = flattenAgentBlocks(message);
                    copyToClipboard(ctx, joined);
                    ComponentActions.dispatchChatAction(ctx, "copy_steps:" + msgId);
                    return true;
                default:
                    return false;
            }
        });
        try { menu.show(); } catch (Throwable ignored) {}
    }

    private void bindUserMessage(UserMessageViewHolder holder, ChatMessage message, String timeStr, int position) {
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
        bindTimestampClick(holder.timestampText, message);
        bindDayLabel(holder.dayLabel, position, message);
        // 逐条时间戳收进日期分隔行（时间信息已进这一行日期）
        holder.timestampText.setVisibility(View.GONE);

        setupItemViewInteraction(holder.itemView, message);

        bindMessageStatus(holder.statusIcon, holder.statusText, message.status);
        // demo 的 USER 气泡下没有"已完成"这一行；只有失败/发送中需要说明
        if (message.status == ChatMessage.MessageStatus.COMPLETED) {
            if (holder.statusIcon != null) holder.statusIcon.setVisibility(View.GONE);
            if (holder.statusText != null) holder.statusText.setVisibility(View.GONE);
        }

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
        // 接管标记按"同一条消息 + 开关仍开着"延续，不再无条件复位（P6 流式接管，2026-10-24）。
        // 一复位就有两处连锁：① 下面那两个 legacy 写者会照常跑，把产物与全局思考行再挂一遍；
        // ② bindWithParts 那边必须重装容器，把上一个 tick 建好的图表/播放器连根释放再重建——
        // 观感就是每过一轮闪一次（轮次边界/思考定稿/状态变化都会引发全量绑定）。
        // 真正的复位时机是换消息、关掉 parts 开关、或 holder 回收（见 onViewRecycled）。
        if (holder.partsTakenOverForId != null && holder.partsTakenOverForId.equals(message.id)
                && com.oilquiz.app.ai.chat.parts.PartsRendererFlag.enabled(holder.itemView.getContext())) {
            holder.partsTookOver = true;
        } else {
            holder.partsTookOver = false;
        }
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
        bindTimestampClick(holder.timestampText, message);
        bindDayLabel(holder.dayLabel, position, message);
        // 逐条时间戳整体收起：时间信息由日期分隔行承担
        holder.timestampText.setVisibility(View.GONE);

        bindAttachments(holder, message);
        // 接管后组件由 parts 的 data part 渲，legacy 组件容器必须让路（否则双渲）；
        // 思考行/工具卡同理（否则与 parts 的 reasoning part 双份）。
        if (!holder.partsTookOver) {
            bindComponents(holder, message);
            updateThinkingContent(holder, message);
        }
        bindAgentStepStatus(holder, message);
        updateMessageStatus(holder, message);
        // demo 的消息里不再重复 token/耗时/速度（技术读数在「⋯」面板与消息详情里）；
        // 只有失败/错误/暂停这类需要用户注意的状态才保留状态行。
        if (message.status != ChatMessage.MessageStatus.FAILED
                && message.status != ChatMessage.MessageStatus.ERROR
                && message.status != ChatMessage.MessageStatus.PAUSED) {
            if (holder.statusText != null) holder.statusText.setVisibility(View.GONE);
            if (holder.statusIcon != null) holder.statusIcon.setVisibility(View.GONE);
        }

        setupItemViewInteraction(holder.itemView, message);

        boolean lastAi = isLastAiMessage(position);
        // 统计栏（状态行 tokens/耗时/速度）只对最终轮显示；中间完成轮隐藏
        if (!lastAi && message.isCompleted()) {
            if (holder.statusText != null) holder.statusText.setVisibility(View.GONE);
            if (holder.statusIcon != null) holder.statusIcon.setVisibility(View.GONE);
        }

        applyFeedbackVisual(holder, message);
        // 操作段（三图标 + 更多）：最终轮正常显示；ERROR/FAILED 的孤儿占位也显示，
        // 让用户能对「未生成回复」那一条直接点重新生成，而不是只能重试最后一条。
        boolean retryable = message.status == ChatMessage.MessageStatus.ERROR
                || message.status == ChatMessage.MessageStatus.FAILED;
        bindV2Actions(holder, message, (message.isCompleted() && lastAi) || retryable);
    }

    /**
     * v2 操作段（demo .ai-actions）：复制 / 重新生成 / 更多。
     *
     * <p>图标直接调 {@link #doCopy} 等方法，与「更多」面板共用一份动作实现（报告耦合 C1）。</p>
     */
    private void bindV2Actions(AIMessageViewHolder holder, ChatMessage message, boolean show) {
        holder.v2ActionRow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            // 复用池里防残留：非最终轮/流式中都要清掉，否则点图标会打到上一条消息的动作
            if (holder.v2BtnCopy != null) holder.v2BtnCopy.setOnClickListener(null);
            if (holder.v2BtnRegenerate != null) holder.v2BtnRegenerate.setOnClickListener(null);
            if (holder.v2BtnMore != null) holder.v2BtnMore.setOnClickListener(null);
            return;
        }
        // 直接调动作方法，不再转发给被 GONE 的旧胶囊（2026-10-20 S3，解报告耦合 C1）
        if (holder.v2BtnCopy != null) {
            holder.v2BtnCopy.setOnClickListener(v -> doCopy(v.getContext(), message));
        }
        if (holder.v2BtnRegenerate != null) {
            holder.v2BtnRegenerate.setOnClickListener(v -> doRegenerate(v.getContext(), message));
        }
        if (holder.v2BtnMore != null) {
            holder.v2BtnMore.setOnClickListener(v -> openMessageActionSheet(holder, message));
        }
    }

    /**
     * 「更多」面板（demo {@code #sheet-message}）：朗读 / 复制 / 分享 / 有帮助 / 没帮助 / 在新对话中继续。
     * 条目顺序与 demo 一致。
     *
     * <p>状态由两处表达：面板里的文案（已反馈时写"已反馈"）与点完的一条 Toast；
     * 「更多」图标另以着色表示这条已反馈（没有独立的赞/踩图标）。</p>
     */
    private void openMessageActionSheet(AIMessageViewHolder holder, ChatMessage message) {
        if (holder == null || message == null) return;
        android.content.Context ctx = holder.v2ActionRow.getContext();
        boolean speaking = message.id != null && message.id.equals(speakingMessageId);
        List<ChatV2ActionSheet.Item> items = new ArrayList<>();
        items.add(new ChatV2ActionSheet.Item(R.drawable.ic_speaker, R.string.chat_speak,
                ctx.getString(speaking ? R.string.chat_stop : R.string.chat_speak),
                v -> doSpeak(ctx, message)));
        items.add(new ChatV2ActionSheet.Item(R.drawable.ic_copy, R.string.chat_copy,
                v -> doCopy(ctx, message)));
        items.add(new ChatV2ActionSheet.Item(R.drawable.ic_share, R.string.chat_share,
                v -> doShare(ctx, message)));
        // feedbackState：1=有帮助、-1=没帮助（状态存在消息上，不另存一份）
        items.add(new ChatV2ActionSheet.Item(R.drawable.ic_thumb_up, R.string.chat_like,
                message.feedbackState == 1
                        ? ctx.getString(R.string.chat_v2_feedback_saved, ctx.getString(R.string.chat_like))
                        : null,
                v -> {
                    doLike(ctx, holder, message);
                    toastFeedbackSaved(ctx, message.feedbackState == 1);
                }));
        items.add(new ChatV2ActionSheet.Item(R.drawable.ic_thumb_down, R.string.chat_dislike,
                message.feedbackState == -1
                        ? ctx.getString(R.string.chat_v2_feedback_saved, ctx.getString(R.string.chat_dislike))
                        : null,
                v -> {
                    doDislike(ctx, holder, message);
                    toastFeedbackSaved(ctx, message.feedbackState == -1);
                }));
        items.add(new ChatV2ActionSheet.Item(R.drawable.ic_add, R.string.chat_new_chat,
                v -> doNewChat(ctx)));
        ChatV2ActionSheet.show(ctx, R.string.chat_v2_msg_actions, items);
    }

    /** 反馈确认：取消反馈时不报"已反馈"，避免和"再点一次取消"的语义打架 */
    private void toastFeedbackSaved(android.content.Context ctx, boolean saved) {
        if (ctx == null) return;
        android.widget.Toast.makeText(ctx,
                saved ? R.string.chat_v2_feedback_thanks : R.string.chat_v2_feedback_cleared,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * 绑定 Agent 执行步骤状态行与任务汇总行（气泡内实时可视化）。
     * agentStepStatus：思考中/调用工具X/完成；agentSummary：完成后汇总。
     */
    private void bindAgentStepStatus(AIMessageViewHolder holder, ChatMessage message) {
        if (holder.agentStatus != null) {
            // 状态文案由活动区 header 承担，这一行不再显示（v1 已删除，保留视图占位避免树结构变动）
            holder.agentStatus.setVisibility(View.GONE);
        }
        // 过程行（思考 + 工具）由活动区统一承载；token 统计块独立于过程行展示（见下）。
        if (holder.agentSummary != null) {
            boolean lastAi = isLastAiMessage(messages.indexOf(message));
            clearAgentSummarySlot(holder);
            if (!lastAi) {
                // 2026-10-25：输入/输出/缓存/上下文统计只跟在**最新一条 AI 回复**后面，
                // 历史轮即使有数据也不展示（否则旧统计停在对话中间，看起来"位置不稳定"）。
                holder.agentSummary.setVisibility(View.GONE);
            } else if (message.agentSummaryInfo != null && renderAgentSummaryMetricCard(holder, message)) {
                // Agent 汇总走结构化卡片（旧档）时，不再重复纯文本
                holder.agentSummary.setVisibility(View.GONE);
            } else {
                // 纯文本只放 token 统计（工具次数/思考轮次已在工具行/折叠头里，不重复）。
                // 注意：**即使有过程行也要显示**——它回答的是"这轮花了多少 token"，
                // 之前跟着 toolLineTookOver 一起让位，导致有时有、有时没有（用户报"不稳定"）。
                String text = buildAgentSummaryText(message);
                if (text == null || text.isEmpty()) {
                    holder.agentSummary.setVisibility(View.GONE);
                } else {
                    holder.agentSummary.setText(text);
                    holder.agentSummary.setVisibility(View.VISIBLE);
                }
            }
        }
        // 停止生成只保留输入框右侧那一颗（demo 口径）；消息内不再重复一颗文字按钮。
        // Agent 控制组（暂停/单步/回退）：流式生成中显示
        bindAgentControlBar(holder, message);
    }

    /**
     * Agent 控制组：暂停/单步/回退按钮。仅在流式生成 + Agent 模式时显示。
     * 点击触发对应 Action + dispatchChatAction 通知 Agent。
     */
    private void bindAgentControlBar(AIMessageViewHolder holder, ChatMessage message) {
        if (holder.agentControlBar == null) return;
        boolean streaming = message.status == ChatMessage.MessageStatus.GENERATING
                || message.status == ChatMessage.MessageStatus.IN_PROGRESS;
        boolean show = streaming && message.agentMode;
        holder.agentControlBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            if (holder.btnPause != null) holder.btnPause.setOnClickListener(null);
            if (holder.btnStep != null) holder.btnStep.setOnClickListener(null);
            if (holder.btnRewind != null) holder.btnRewind.setOnClickListener(null);
            return;
        }
        final String msgId = message.id;
        if (holder.btnPause != null) holder.btnPause.setOnClickListener(v -> {
            if (actionClickListener != null) {
                actionClickListener.onAction(ChatMessage.Action.pauseAgent(msgId));
            }
            ComponentActions.dispatchChatAction(v.getContext(), "pause:" + msgId);
        });
        if (holder.btnStep != null) holder.btnStep.setOnClickListener(v -> {
            if (actionClickListener != null) {
                actionClickListener.onAction(ChatMessage.Action.stepAgent(msgId));
            }
            ComponentActions.dispatchChatAction(v.getContext(), "step:" + msgId);
        });
        if (holder.btnRewind != null) holder.btnRewind.setOnClickListener(v -> {
            if (actionClickListener != null) {
                actionClickListener.onAction(ChatMessage.Action.rewindAgent(msgId));
            }
            ComponentActions.dispatchChatAction(v.getContext(), "rewind:" + msgId);
        });
    }


    /** BlockType → 中文标签（用于 AgentExecutionView 步骤类型显示） */
    private static String mapBlockTypeToLabel(
            com.oilquiz.app.ai.agent.AgentExecutionState.BlockType t) {
        if (t == null) return "步骤";
        switch (t) {
            case THINKING: return "思考";
            case PLANNING: return "规划";
            case TOOL_CALL: return "工具调用";
            case TOOL_RESULT: return "工具结果";
            case INFERENCE: return "推理";
            case OBSERVATION: return "观察";
            case REFLECTION: return "反思";
            case ERROR: return "错误";
            case TEXT:
            default: return "输出";
        }
    }

    /**
     * 用 MetricCardView 组件渲染 Agent 汇总卡片：耗时/步骤/工具/Token 四栏 + 状态/重试徽章。
     * 挂载在 agentSummary TextView 之后（同一条消息列内），带指纹缓存避免流式重复重建。
     * 返回 true 表示渲染成功；false 时调用方应回退到文本路径。
     */
    private boolean renderAgentSummaryMetricCard(AIMessageViewHolder holder, ChatMessage message) {
        ChatMessage.AgentSummaryInfo s = message.agentSummaryInfo;
        if (s == null) return false;
        final String fp = agentSummaryFp(s, message.retryCount);
        android.view.ViewGroup parent = (android.view.ViewGroup) holder.agentSummary.getParent();
        if (parent == null) return false;
        // 复用：槽位存在且指纹一致 → 只保证可见
        if (holder.agentSummarySlot != null
                && fp.equals(holder.agentSummaryFp)
                && holder.agentSummarySlot.getParent() == parent) {
            holder.agentSummarySlot.setVisibility(View.VISIBLE);
            return true;
        }
        // 移除旧槽位
        clearAgentSummarySlot(holder);
        try {
            org.json.JSONArray metrics = new org.json.JSONArray();
            metrics.put(new org.json.JSONObject()
                    .put("label", "耗时").put("value", s.getFormattedTime()));
            metrics.put(new org.json.JSONObject()
                    .put("label", "步骤").put("value", String.valueOf(s.totalSteps)));
            metrics.put(new org.json.JSONObject()
                    .put("label", "工具").put("value", String.valueOf(s.toolCallCount)));
            metrics.put(new org.json.JSONObject()
                    .put("label", "Token").put("value", s.getFormattedTokens())
                    .put("color", "accent"));
            if (!s.isSuccess) {
                metrics.put(new org.json.JSONObject()
                        .put("label", "状态").put("value", "未完成").put("color", "error"));
            }
            if (message.retryCount > 0) {
                metrics.put(new org.json.JSONObject()
                        .put("label", "重试").put("value", "第 " + (message.retryCount + 1) + " 次")
                        .put("color", "warning"));
            }
            org.json.JSONObject props = new org.json.JSONObject()
                    .put("title", "Agent 执行汇总")
                    .put("metrics", metrics);
            // 自定义 summary 文本（Agent 端可下发）
            if (s.summary != null && !s.summary.isEmpty()) {
                props.put("subtitle", s.summary);
            }
            ComponentData data = new ComponentData("metric_card", props);
            View v = ComponentRegistry.getInstance().render(parent.getContext(), data);
            if (v == null) return false;
            v.setTag("agent_summary_slot");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dpToPx(6, v.getContext());
            int idx = parent.indexOfChild(holder.agentSummary);
            parent.addView(v, idx + 1, lp);
            holder.agentSummarySlot = v;
            holder.agentSummaryFp = fp;
            // 工具分布：从 agentExecutionState.blocks 提取 TOOL_CALL/TOOL_RESULT 序列
            attachToolDistribution(parent, holder, message, idx + 2);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 在 Agent 汇总卡片之后挂"工具分布"表格（table primitive）：
     * 每行 = 工具名 / 耗时 / 状态。数据来源：agentExecutionState.blocks 中的 TOOL_CALL 序列。
     */
    private void attachToolDistribution(android.view.ViewGroup parent, AIMessageViewHolder holder,
                                         ChatMessage message, int insertAt) {
        if (message.agentExecutionState == null
                || message.agentExecutionState.getBlocks() == null) return;
        // 清理旧槽位
        View old = parent.findViewWithTag("tool_distribution_slot");
        if (old != null) parent.removeView(old);
        try {
            org.json.JSONArray rows = new org.json.JSONArray();
            java.util.List<com.oilquiz.app.ai.agent.AgentExecutionState.ExecutionBlock> blocks
                    = message.agentExecutionState.getBlocks();
            for (com.oilquiz.app.ai.agent.AgentExecutionState.ExecutionBlock b : blocks) {
                if (b == null || b.getType() != com.oilquiz.app.ai.agent.AgentExecutionState.BlockType.TOOL_CALL)
                    continue;
                org.json.JSONArray row = new org.json.JSONArray();
                row.put(b.getToolName() != null ? b.getToolName() : (b.getTitle() != null ? b.getTitle() : "工具"));
                row.put(b.getStatus() != null ? statusLabel(b.getStatus()) : "—");
                rows.put(row);
            }
            if (rows.length() == 0) return;
            org.json.JSONArray headers = new org.json.JSONArray();
            headers.put("工具");
            headers.put("状态");
            org.json.JSONObject props = new org.json.JSONObject()
                    .put("title", "工具调用分布（" + rows.length() + " 次）")
                    .put("headers", headers)
                    .put("rows", rows);
            ComponentData data = new ComponentData("table", props);
            View v = ComponentRegistry.getInstance().render(parent.getContext(), data);
            if (v == null) return;
            v.setTag("tool_distribution_slot");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dpToPx(6, v.getContext());
            int idx = Math.min(insertAt, parent.getChildCount());
            parent.addView(v, idx, lp);
        } catch (Throwable ignored) {}
    }

    private static String statusLabel(
            com.oilquiz.app.ai.agent.AgentExecutionState.ExecutionBlock.BlockStatus s) {
        if (s == null) return "—";
        switch (s) {
            case COMPLETED: return "✅ 成功";
            case FAILED: return "❌ 失败";
            case RUNNING: return "🔄 执行中";
            default: return "⏳ 待执行";
        }
    }

    /** Agent 汇总组件指纹：结构化字段变化才重渲染 */
    private static String agentSummaryFp(ChatMessage.AgentSummaryInfo s, int retryCount) {
        return s.totalTimeMs + "_" + s.totalSteps + "_" + s.toolCallCount
                + "_" + s.totalTokens + "_" + (s.isSuccess ? "ok" : "fail") + "_r" + retryCount;
    }

    /** 清理组件槽位 + holder 缓存字段（回退/复用切换时） */
    private void clearAgentSummarySlot(AIMessageViewHolder holder) {
        if (holder == null) return;
        if (holder.agentSummarySlot != null && holder.agentSummarySlot.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) holder.agentSummarySlot.getParent()).removeView(holder.agentSummarySlot);
        }
        holder.agentSummarySlot = null;
        holder.agentSummaryFp = null;
        // 工具分布表随汇总卡片一起清理
        if (holder.agentSummary != null && holder.agentSummary.getParent() instanceof android.view.ViewGroup) {
            android.view.ViewGroup p = (android.view.ViewGroup) holder.agentSummary.getParent();
            View dist = p.findViewWithTag("tool_distribution_slot");
            if (dist != null) p.removeView(dist);
        }
    }

    /**
     * 组装 Agent 汇总行文本：优先使用结构化 AgentSummaryInfo（耗时/步骤/工具/Token），
     * 无结构化数据时回退到 message.agentSummary 字符串；两者都附重试次数提示。
     * MetricCardView 不可用时的兜底路径。
     */
    private String buildAgentSummaryText(ChatMessage message) {
        StringBuilder sb = new StringBuilder();
        if (message.retryCount > 0) {
            sb.append("🔄 第 ").append(message.retryCount + 1).append(" 次尝试");
        }
        if (message.agentSummaryInfo != null) {
            ChatMessage.AgentSummaryInfo s = message.agentSummaryInfo;
            if (sb.length() > 0) sb.append(" · ");
            sb.append("⏱ ").append(s.getFormattedTime())
              .append(" · 📊 ").append(s.totalSteps).append("步")
              .append(" · 🔧 ").append(s.toolCallCount).append("工具")
              .append(" · 📝 ").append(s.getFormattedTokens()).append("tokens");
            if (!s.isSuccess) sb.append(" · ❌ 未完成");
            return sb.toString();
        }
        if (message.agentSummary != null && !message.agentSummary.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(message.agentSummary);
        }
        return sb.toString();
    }


    /** 工具状态文案（demo 用耗时占位，本仓没有逐调用耗时，先给状态） */
    private String toolRowStatusText(Context ctx, String status) {
        // 措辞与 parts 的工具行共用（见 ToolRowStyle），避免两套叫法各自漂移。
        return com.oilquiz.app.ai.chat.ui.ToolRowStyle.statusText(ctx, status);
    }

    /** 单条工具明细的状态色（字符串状态来自 tool_call 卡 props，与枚举版的 toolStatusColor 不是一条通道） */
    private int toolRowStatusColor(Context ctx, String status) {
        return com.oilquiz.app.ai.chat.ui.ToolRowStyle.statusColor(ctx, status);
    }

    /** 当前正在朗读的消息 id（由 Activity 通过 setSpeakingMessageId 设置）；null 表示未朗读 */
    private String speakingMessageId;

    /**
     * 由 Activity 调用，通知 adapter 哪条消息正在朗读中；
     * adapter 更新对应按钮文案（朗读 ↔ 停止）。传 null 表示停止。
     */
    public void setSpeakingMessageId(String messageId) {
        if (java.util.Objects.equals(this.speakingMessageId, messageId)) return;
        String old = this.speakingMessageId;
        this.speakingMessageId = messageId;
        if (old != null) {
            int pos = findMessagePosition(old);
            if (pos >= 0) notifyItemChanged(pos, PAYLOAD_STATUS_UPDATE);
        }
        if (messageId != null) {
            int pos = findMessagePosition(messageId);
            if (pos >= 0) notifyItemChanged(pos, PAYLOAD_STATUS_UPDATE);
        }
    }

    // ---------------------------------------------------------------- 消息动作（唯一实现）
    // 2026-10-20 S3：以前 v2 的三颗图标与「更多」面板全部 performClick() 转发给被 GONE 的
    // 旧胶囊，删掉胶囊就等于删掉 v2 的动作后端（报告耦合 C1）。现在动作只在这里实现，
    // v1 胶囊与 v2 图标/面板都直接调，删任一侧的视图都不影响行为。

    /** 复制：剥离组件标记后的可读文本 */
    private void doCopy(android.content.Context ctx, ChatMessage message) {
        if (message == null) return;
        String exportContent = stripComponentMarkersForExport(message.content);
        copyToClipboard(ctx, exportContent);
        if (actionClickListener != null) {
            actionClickListener.onAction(ChatMessage.Action.copy(exportContent));
        }
    }

    /** 朗读 / 停止朗读（状态由 Activity 回写 speakingMessageId 驱动） */
    private void doSpeak(android.content.Context ctx, ChatMessage message) {
        if (message == null) return;
        String exportContent = stripComponentMarkersForExport(message.content);
        if (actionClickListener != null) {
            actionClickListener.onAction(ChatMessage.Action.speak(message.id, exportContent));
        }
        boolean speakingNow = message.id != null && message.id.equals(speakingMessageId);
        ComponentActions.dispatchChatAction(ctx,
                speakingNow ? "stop_speak" : ("speak:" + message.id));
    }

    /** 分享（沿用旧口径：同时派发 copy 的埋点，不改语义） */
    private void doShare(android.content.Context ctx, ChatMessage message) {
        if (message == null) return;
        String exportContent = stripComponentMarkersForExport(message.content);
        shareText(ctx, message.content);
        if (actionClickListener != null) {
            actionClickListener.onAction(ChatMessage.Action.copy(exportContent));
        }
    }

    private void doRegenerate(android.content.Context ctx, ChatMessage message) {
        if (message == null) return;
        if (actionClickListener != null) {
            actionClickListener.onAction(ChatMessage.Action.regenerate(message.id));
        }
        ComponentActions.dispatchChatAction(ctx, "regen:" + message.id);
    }

    private void doNewChat(android.content.Context ctx) {
        if (actionClickListener != null) {
            actionClickListener.onAction(ChatMessage.Action.newChat());
        }
        ComponentActions.dispatchChatAction(ctx, "new_chat");
    }

    private void doLike(android.content.Context ctx, AIMessageViewHolder holder, ChatMessage message) {
        if (message == null) return;
        message.feedbackState = (message.feedbackState == 1) ? 0 : 1;
        applyFeedbackVisual(holder, message);
        if (actionClickListener != null && message.feedbackState == 1) {
            actionClickListener.onAction(ChatMessage.Action.like(message.id));
        }
        ComponentActions.dispatchChatAction(ctx,
                (message.feedbackState == 1 ? "like:" : "unlike:") + message.id);
    }

    private void doDislike(android.content.Context ctx, AIMessageViewHolder holder, ChatMessage message) {
        if (message == null) return;
        message.feedbackState = (message.feedbackState == -1) ? 0 : -1;
        applyFeedbackVisual(holder, message);
        if (actionClickListener != null && message.feedbackState == -1) {
            actionClickListener.onAction(ChatMessage.Action.dislike(message.id));
        }
        ComponentActions.dispatchChatAction(ctx,
                (message.feedbackState == -1 ? "dislike:" : "undislike:") + message.id);
    }

    /** 反馈状态回执：没有赞/踩图标，用「更多」的着色表示"这条已反馈" */
    private void applyFeedbackVisual(AIMessageViewHolder holder, ChatMessage message) {
        if (holder.v2BtnMore == null) return;
        android.content.Context ctx = holder.itemView.getContext();
        int color = androidx.core.content.ContextCompat.getColor(ctx,
                message.feedbackState != 0 ? R.color.chat_v2_brand : R.color.chat_v2_text_3);
        holder.v2BtnMore.setImageTintList(
                android.content.res.ColorStateList.valueOf(color));
    }

    /** 时间戳点击查看消息详情对话框 */
    private void bindTimestampClick(TextView timestampText, ChatMessage message) {
        if (timestampText == null || message == null) return;
        timestampText.setClickable(true);
        timestampText.setForeground(getSelectableItemBackground(timestampText.getContext()));
        timestampText.setOnClickListener(v -> showDetailDialog(v.getContext(), message));
    }

    /** 展示消息元数据：使用 HtmlCardView 组件渲染富样式卡片（表格+徽章+代码块） */
    private void showDetailDialog(android.content.Context ctx, ChatMessage message) {
        String html = buildDetailHtml(message);
        try {
            org.json.JSONObject props = new org.json.JSONObject();
            props.put("html", html);
            props.put("maxHeight", 480);
            ComponentData data = new ComponentData("html", props);
            View card = ComponentRegistry.getInstance().render(ctx, data);
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(ctx)
                    .setTitle("消息详情");
            if (card != null) {
                b.setView(card);
            } else {
                b.setMessage(html.replaceAll("(?s)<[^>]+>", " ").trim());
            }
            b.setPositiveButton("关闭", null).show();
        } catch (Throwable ignored) {}
    }

    /** 构造消息详情 HTML：卡片头 + 元数据表 + 模型统计 + 反馈徽章 */
    private static String buildDetailHtml(ChatMessage m) {
        java.text.SimpleDateFormat full = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='padding:12px;font-family:system-ui'>");
        // 头部徽章
        String roleColor = m.type == ChatMessage.MessageType.AI ? "#3B82F6"
                : m.type == ChatMessage.MessageType.USER ? "#10B981"
                : m.type == ChatMessage.MessageType.ERROR ? "#EF4444" : "#6B7280";
        sb.append("<div style='display:flex;align-items:center;gap:8px;margin-bottom:12px'>");
        sb.append("<span style='background:").append(roleColor)
                .append(";color:#fff;padding:3px 10px;border-radius:12px;font-size:12px'>")
                .append(escape(m.type.name())).append("</span>");
        sb.append("<span style='color:#6B7280;font-size:12px'>")
                .append(escape(m.status != null ? m.status.name() : "")).append("</span>");
        if (m.feedbackState == 1) sb.append("<span style='color:#10B981'>👍</span>");
        if (m.feedbackState == -1) sb.append("<span style='color:#EF4444'>👎</span>");
        if (m.retryCount > 0) sb.append("<span style='color:#F59E0B'>🔄 第 ").append(m.retryCount + 1).append(" 次</span>");
        sb.append("</div>");

        // 元数据表
        sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px'>");
        addRow(sb, "完整时间", full.format(new java.util.Date(m.timestamp)));
        addRow(sb, "消息 ID", m.id != null ? m.id : "—");
        if (m.turnId != null) addRow(sb, "回合", m.turnId);
        if (m.subId != null) addRow(sb, "子 id", m.subId);
        if (m.tokensGenerated > 0) {
            addRow(sb, "Tokens", String.valueOf(m.tokensGenerated));
            if (m.generationTimeMs > 0) {
                addRow(sb, "耗时", String.format(java.util.Locale.getDefault(),
                        "%.2fs", m.generationTimeMs / 1000.0));
            }
            if (m.tokensPerSecond > 0) {
                addRow(sb, "速度", String.format(java.util.Locale.getDefault(),
                        "%.1f tok/s", m.tokensPerSecond));
            }
        }
        if (m.usingGPU && m.gpuLayers > 0) {
            addRow(sb, "加速", "GPU/NPU " + m.gpuLayers + " 层");
        }
        sb.append("</table>");

        // 模型信息块
        if (m.modelInfo != null && m.modelInfo.modelName != null) {
            sb.append("<div style='margin-top:12px;padding:10px;background:#F1F5F9;border-radius:8px'>");
            sb.append("<div style='font-weight:600;color:#334155;font-size:13px;margin-bottom:4px'>")
                    .append(escape(m.modelInfo.modelName)).append("</div>");
            sb.append("<div style='color:#64748B;font-size:12px;display:flex;gap:12px'>");
            if (m.modelInfo.latencyMs > 0) sb.append("延迟 ").append(m.modelInfo.latencyMs).append("ms");
            if (m.modelInfo.costEstimate > 0) sb.append("成本 $")
                    .append(String.format(java.util.Locale.getDefault(), "%.4f", m.modelInfo.costEstimate));
            sb.append(" · 状态 ").append(escape(m.modelInfo.getStatusText()));
            sb.append("</div></div>");
        }

        // 错误详情
        if (m.errorDetail != null && !m.errorDetail.isEmpty()) {
            sb.append("<div style='margin-top:12px;padding:10px;background:#FEE2E2;border-left:4px solid #EF4444;border-radius:4px;color:#991B1B;font-size:12px'>")
                    .append(escape(m.errorDetail)).append("</div>");
        }

        sb.append("</div>");
        return sb.toString();
    }

    private static void addRow(StringBuilder sb, String key, String value) {
        sb.append("<tr>")
                .append("<td style='padding:6px 8px;color:#64748B;width:40%;vertical-align:top;border-bottom:1px solid #E5E7EB'>")
                .append(escape(key)).append("</td>")
                .append("<td style='padding:6px 8px;color:#1F2937;word-break:break-all;border-bottom:1px solid #E5E7EB'>")
                .append(escape(value)).append("</td>")
                .append("</tr>");
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * 多轮按轮次组装渲染：轮1(思考块+正文段) → 工具卡片 → 轮2(思考块+正文段) → …
     * contentRoundBounds 为工具调用边界；thinkingRounds 为每轮思考；components 为工具卡片。
     */
    /** 工具卡片归属键：toolCallId 优先，空则退化到 toolName（本地 Agent 无 id 场景）。 */
    /**
     * 第 i 轮产物的归属键：优先用 ActivityTimeline 按 toolCallId 配对后的工具身份，
     * 旧数据没有 id 时退回 tools 列表下标。
     */
    private static String roundKeyForIndex(java.util.List<ActivityGroup> groups,
                                           java.util.List<ComponentData> tools, int i) {
        if (i >= 0 && i < groups.size() && !groups.get(i).tools.isEmpty()) {
            ActivityTimeline.Item item = groups.get(i).tools.get(0);
            if (item.toolCallId != null && !item.toolCallId.isEmpty()) {
                return "id:" + item.toolCallId;
            }
        }
        return toolRoundKey(tools.size() > i ? tools.get(i) : null);
    }

    private static String toolRoundKey(ComponentData tool) {
        if (tool == null) return "";
        String id = tool.props != null ? tool.props.optString("toolCallId", "") : "";
        if (id != null && !id.isEmpty()) return "id:" + id;
        String name = tool.props != null ? tool.props.optString("toolName", "") : "";
        return "name:" + (name != null ? name : "");
    }

    /** 产物卡片归属键：producerToolCallId 优先，空则 producerToolName；都缺则 null（走尾部兜底）。 */
    private static String productOwnerKey(ComponentData prod) {
        if (prod == null || prod.props == null) return null;
        String id = prod.props.optString("producerToolCallId", "");
        if (id != null && !id.isEmpty()) return "id:" + id;
        String name = prod.props.optString("producerToolName", "");
        if (name != null && !name.isEmpty()) return "name:" + name;
        return null;
    }

    /**
     * 把一个组件（工具卡或产物卡）渲染挂载到 contentHost；渲染失败降级为占位文本，不显示源码。
     *
     * <p>2026-10-20：产物卡按 {@link ComponentDisplay} 的档位挂载——INLINE 整卡、PEEK 摘要行、
     * HIDDEN 由调用方（指纹段/增量跳位/重建三处）先跳过计数再跳过挂载，**这里只是第二道闸**，
     * 两处判定必须同口径，否则 contentHost 子 View 数与 expect 对不上、增量更新会错位。
     * 工具过程卡（tool_call）不参与分档：它已有"全部完成后折成一行"的组级折叠，
     * 再套一层摘要等于同一张卡收两次。
     */
    private void mountRoundComponent(AIMessageViewHolder holder, Context ctx, ChatMessage message,
                                     ComponentData comp, int prodIndex) {
        if (comp == null) return;
        boolean isToolCard = "tool_call".equals(comp.type);
        ComponentDisplay.Mode mode = isToolCard
                ? ComponentDisplay.Mode.INLINE : ComponentDisplay.resolve(ctx, comp);
        if (!ComponentDisplay.mounts(mode)) return;
        View v = ComponentRegistry.getInstance().render(ctx, comp);
        if (v == null) {
            TextView tv = createSegmentTextView(ctx, holder.messageText);
            tv.setText(SmartQuizApplication.getAppContext().getString(R.string.h_ce08c6dd)
                    + comp.type + SmartQuizApplication.getAppContext().getString(R.string.h_56e45313));
            tv.setTextColor(ThemeColors.attr(ctx, R.attr.colorControlTextSecondary));
            v = tv;
        }
        if (mode == ComponentDisplay.Mode.PEEK) {
            v = wrapProductPeek(ctx, message, comp, v, prodIndex);
            if (v == null) return;
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(8, ctx);
        holder.contentHost.addView(v, lp);
    }

    /**
     * peek 档产物卡的摘要行容器（2026-10-20）：一行"▸ 标题 · 关键值"，点击就地展开/收起完整卡。
     *
     * <p>返回<b>单个</b> View（标题行 + 卡体的垂直容器）：轮次渲染的 expect 子 View 计数
     * 不必为"展开与否"准备两套口径，指纹/增量/重建三处循环共用同一份计数。
     *
     * <p>展开态记在消息上（{@link ChatMessage#componentBodyExpandedIds}）而不是 View 上，
     * 整条消息重建后照样还原；完整卡即使收起也照常渲染（只是 GONE），
     * 渲染成本与旧行为一致，而 {@code ComponentLifecycle.release} 会深度优先递归销毁
     * 嵌套的 WebView，不会因为多包了一层而漏放。
     */
    private View wrapProductPeek(Context ctx, ChatMessage message, ComponentData comp,
                                 View body, int prodIndex) {
        if (body == null) return null;
        final String key = ComponentDisplay.stateKey(comp, prodIndex);
        final boolean startExpanded = message != null && message.isComponentBodyExpanded(key);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView row = new TextView(ctx);
        row.setTextSize(12f);
        row.setTextColor(ThemeColors.attr(ctx, R.attr.colorControlTextSecondary));
        row.setPadding(dpToPx(4, ctx), dpToPx(6, ctx), dpToPx(4, ctx), dpToPx(6, ctx));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(getSelectableItemBackground(ctx));
        body.setVisibility(startExpanded ? View.VISIBLE : View.GONE);
        row.setText(peekRowLabel(comp, startExpanded));
        row.setOnClickListener(v -> {
            boolean expanded = message != null
                    ? message.toggleComponentBodyExpanded(key)
                    : body.getVisibility() != View.VISIBLE;
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            row.setText(peekRowLabel(comp, expanded));
        });

        box.addView(row);
        box.addView(body);
        return box;
    }

    /** 摘要行文案：箭头 + {@link ComponentDisplay#peekLabel} 的"标题 · 关键值" */
    private static String peekRowLabel(ComponentData comp, boolean expanded) {
        return (expanded ? "▾ " : "▸ ") + ComponentDisplay.peekLabel(comp);
    }

    /** 每轮活动区：一轮的思考项 + 工具项（按 ActivityTimeline 的顺序切组）。 */
    static final class ActivityGroup {
        final java.util.List<ActivityTimeline.Item> thinks = new java.util.ArrayList<>();
        final java.util.List<ActivityTimeline.Item> tools = new java.util.ArrayList<>();

        boolean isEmpty() {
            return thinks.isEmpty() && tools.isEmpty();
        }

        String key() {
            if (!tools.isEmpty() && tools.get(0).id != null) return tools.get(0).id;
            if (!thinks.isEmpty() && thinks.get(0).id != null) return thinks.get(0).id;
            return "";
        }
    }

    /** 轮次边界归一（数量/范围/单调/toolCallId）统一委托给 {@link RoundBounds}。 */
    static void ensureRoundBounds(ChatMessage message) {
        RoundBounds.normalize(message);
    }

    /**
     * 把 ActivityTimeline 的线性 items 按 TOOL 切成"每轮一组"：
     * 思考0 → 工具0 → 思考1 → 工具1 → … → 末尾只剩思考。
     * 组的数量与 contentRoundBounds 对齐（第 i 组就是第 i 个边界）。
     */
    static java.util.List<ActivityGroup> buildActivityGroups(ChatMessage message) {
        ensureRoundBounds(message);
        java.util.List<ActivityGroup> groups = new java.util.ArrayList<>();
        ActivityGroup cur = new ActivityGroup();
        for (ActivityTimeline.Item item : ActivityTimeline.build(message).items) {
            if (item.kind == ActivityTimeline.Kind.TOOL) {
                cur.tools.add(item);
                groups.add(cur);
                cur = new ActivityGroup();
            } else {
                cur.thinks.add(item);
            }
        }
        if (!cur.isEmpty() || groups.isEmpty()) groups.add(cur);
        return groups;
    }

    /** 构造一轮的活动视图：思考行（有思考才挂）+ 工具卡（有工具才挂）。 */
    private View buildRoundActivityView(AIMessageViewHolder holder, Context ctx,
                                        ChatMessage message, ActivityGroup group,
                                        java.util.List<ComponentData> products,
                                        int groupIndex, int groupCount,
                                        int availableWidth, boolean streaming) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.topMargin = dpToPx(6, ctx);
        col.setLayoutParams(colLp);

        // ---- 思考行 ----
        if (!group.thinks.isEmpty()) {
            View think = LayoutInflater.from(ctx)
                    .inflate(R.layout.view_chat_v2_think_line, col, false);
            TextView label = think.findViewById(R.id.chat_v2_think_text);
            TextView body = think.findViewById(R.id.chat_v2_think_body);
            LinearLayout steps = think.findViewById(R.id.chat_v2_think_steps);
            ImageView chev = think.findViewById(R.id.chat_v2_think_chev);
            if (body != null) body.setVisibility(View.GONE);
            int count = group.thinks.size();
            long duration = 0L;
            for (ActivityTimeline.Item it : group.thinks) duration += it.durationMs;
            boolean expanded = streaming || message.thinkingExpanded;
            boolean lastGroup = groupIndex == groupCount - 1;
            if (label != null) {
                label.setVisibility(View.VISIBLE);
                String header;
                ChatMessage.InferencePhase thinkPhase = message.inferenceProgress != null
                        ? message.inferenceProgress.phase : null;
                // 活跃思考块：与输入区上方的生成状态行共用同一套多段文案
                // （理解问题→梳理思路→推敲关键点→验证推理→收敛结论）；非活跃块走完成态文案。
                String activePhrase = (streaming && lastGroup) ? activeThinkingPhrase(message) : null;
                if (activePhrase != null) {
                    header = activePhrase;
                } else if (streaming && lastGroup
                        && com.oilquiz.app.ai.chat.InferenceStatusLabel.isReasoningActive(thinkPhase)) {
                    // 阶段未知（历史/旧引擎）：保留旧文案
                    header = ctx.getString(R.string.chat_v2_thinking_now);
                } else if (duration > 0L) {
                    header = ctx.getString(R.string.chat_v2_thought_for_steps,
                            formatThinkingDuration(duration), count);
                } else {
                    header = ctx.getString(R.string.chat_v2_thought_steps_only, count);
                }
                label.setText(header);
                label.setTextColor(androidx.core.content.ContextCompat.getColor(ctx,
                        R.color.chat_v2_text_2));
                label.setClickable(true);
                label.setOnClickListener(v -> {
                    message.thinkingExpanded = !message.thinkingExpanded;
                    int pos = holder.getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos);
                });
            }
            if (chev != null) chev.setRotation(expanded ? 90f : 0f);
            if (steps != null) {
                if (expanded) {
                    steps.removeAllViews();
                    int width = availableWidth > 0 ? availableWidth : getScreenWidth(ctx);
                    for (ActivityTimeline.Item item : group.thinks) {
                        steps.addView(buildThinkingStepRow(holder, ctx, item.id,
                                item.body != null ? item.body : item.summary,
                                item.durationMs, width));
                    }
                    steps.setVisibility(View.VISIBLE);
                } else {
                    steps.setVisibility(View.GONE);
                }
            }
            col.addView(think);
        }

        // ---- 工具卡 ----
        if (!group.tools.isEmpty()) {
            View card = LayoutInflater.from(ctx)
                    .inflate(R.layout.view_chat_v2_tool_card, col, false);
            bindRoundToolCard(holder, ctx, message, card, group.tools,
                    group.key(), products, availableWidth);
            col.addView(card);
        }
        return col;
    }

    /** 绑定一轮工具卡（卡头/耗时/✓/展开 + 行）。 */
    private void bindRoundToolCard(AIMessageViewHolder holder, Context ctx, ChatMessage message,
                                   View card, java.util.List<ActivityTimeline.Item> tools,
                                   String groupKey, java.util.List<ComponentData> products,
                                   int availableWidth) {
        // XML 根默认 GONE（全局活动区按需显示）；逐轮挂载时必须显式打开
        card.setVisibility(View.VISIBLE);
        int running = 0;
        int failed = 0;
        long toolMs = 0L;
        for (ActivityTimeline.Item item : tools) {
            if (item.status == ActivityTimeline.Status.RUNNING) running++;
            if (item.status == ActivityTimeline.Status.FAILED) failed++;
            toolMs += item.durationMs;
        }
        boolean expanded = message.isToolSectionExpanded(groupKey);
        TextView text = card.findViewById(R.id.chat_v2_tool_card_text);
        if (text != null) {
            if (running > 0) {
                text.setText(R.string.chat_v2_tool_running_now);
            } else {
                text.setText(ctx.getString(R.string.chat_v2_tools_ran, tools.size()));
            }
            text.setTextColor(androidx.core.content.ContextCompat.getColor(ctx,
                    failed > 0 ? R.color.chat_v2_danger : R.color.chat_v2_text_2));
        }
        TextView time = card.findViewById(R.id.chat_v2_tool_card_time);
        if (time != null) {
            if (toolMs > 0L) {
                time.setText(formatThinkingDuration(toolMs));
                time.setVisibility(View.VISIBLE);
            } else {
                time.setVisibility(View.GONE);
            }
        }
        View ok = card.findViewById(R.id.chat_v2_tool_card_ok);
        if (ok != null) ok.setVisibility(running == 0 && failed == 0 ? View.VISIBLE : View.GONE);
        View chev = card.findViewById(R.id.chat_v2_tool_card_chev);
        if (chev != null) chev.setRotation(expanded ? 90f : 0f);

        LinearLayout body = card.findViewById(R.id.chat_v2_tool_card_body);
        if (body != null) {
            if (expanded) {
                body.removeAllViews();
                int width = availableWidth > 0 ? availableWidth : getScreenWidth(ctx);
                LayoutInflater inflater = LayoutInflater.from(ctx);
                for (int i = 0; i < tools.size(); i++) {
                    body.addView(buildToolActivityRow(holder, inflater, ctx, tools.get(i), i, width));
                }
                body.setVisibility(View.VISIBLE);
            } else {
                body.setVisibility(View.GONE);
            }
        }

        // 工具结果卡：挂在工具卡内、但不随工具行折叠；显隐/整卡/摘要由模型通过
        // 工具调用参数 display 决定（ComponentDisplay 的模型声明优先级）。
        LinearLayout productHost = card.findViewById(R.id.chat_v2_tool_card_products);
        if (productHost != null) {
            productHost.removeAllViews();
            int shown = 0;
            int prodIndex = 0;
            if (products != null) {
                for (ComponentData p : products) {
                    if (p == null || p.props == null) continue;
                    ComponentDisplay.Mode mode = ComponentDisplay.resolve(ctx, p);
                    int index = prodIndex++;
                    if (!ComponentDisplay.mounts(mode)) continue;
                    View pv = ComponentRegistry.getInstance().render(ctx, p);
                    if (pv == null) {
                        pv = ComponentRegistry.getInstance().renderFallback(ctx, p);
                    }
                    if (mode == ComponentDisplay.Mode.PEEK) {
                        pv = wrapProductPeek(ctx, message, p, pv, index);
                        if (pv == null) continue;
                    }
                    LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    plp.topMargin = dpToPx(6, ctx);
                    productHost.addView(pv, plp);
                    shown++;
                }
            }
            productHost.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
        }
        View header = card.findViewById(R.id.chat_v2_tool_card_header);
        if (header != null) {
            header.setOnClickListener(v -> {
                message.toggleToolSectionExpanded(groupKey);
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos);
            });
        }
    }

    private void renderRoundAssembled(AIMessageViewHolder holder, ChatMessage message, int availableWidth, Context ctx) {
        if (holder.contentHost == null) return;
        ensureRoundBounds(message);
        String content = message.content != null ? message.content : "";
        java.util.List<Integer> bounds = message.contentRoundBounds;
        java.util.List<String> thinks = message.thinkingRounds;
        java.util.List<ComponentData> comps = message.components;
        // ROUND-CARD-ALIGN(2026-10-13)：轮次边界与「工具卡片」才是 1:1（每个 onToolCallStart
        // 记一个 bound + append 一张 tool_call）。但 message.components 是混排列表：工具产物
        // （list_card/image_grid 等）由 ComponentCollector.drain() 在工具**完成**时 addAll 追加，
        // 多轮串行时产物会夹在「上一轮工具卡」与「下一轮 running 工具卡」之间，使裸下标
        // comps.get(i) 从第一次跨轮 drain 起错位——把产物当工具卡渲染、真正的工具卡越界丢失、
        // 产物又在底部 others 通道重复一份。故此处先滤出纯 tool_call 子列表，三个循环一律按它索引；
        // 产物仍按 PRODUCT-OWNER 内联到所属工具卡下方；显示档位由 ComponentDisplay 决定
        // （2026-10-20 前才是"始终完整显示"），不再参与轮次槽位。
        java.util.List<ComponentData> toolComps = new java.util.ArrayList<>();
        if (comps != null) {
            for (ComponentData c : comps) {
                if (c != null && "tool_call".equals(c.type)) toolComps.add(c);
            }
        }
        // PRODUCT-OWNER(2026-10-13)：把工具产物按「归属键」内联到其所属轮次工具卡正下方，
        // 形成"思考→正文→工具→该工具产物→下一轮"的完整时序；无归属标注或键匹配不到任何工具的
        // 产物收进 tailProds，在循环末尾兜底渲染，保证一个产物都不丢、也绝不重复。
        // 每个产物只归属首个键匹配的轮次（同一 toolName 出现在多轮时，产物落在第一轮，后续轮空手），
        // 用 assigned 身份集去重。三处循环（指纹/增量/重建）都读同一份 roundProds/tailProds 保持一致。
        int nRounds = bounds.size();
        // 2026-10-21 每轮活动区：思考/工具按 TOOL 切组，第 i 组挂在第 i 个 contentRoundBounds 处。
        java.util.List<ActivityGroup> activityGroups = buildActivityGroups(message);
        ActivityGroup trailingGroup = activityGroups.size() > nRounds
                ? activityGroups.get(nRounds) : null;
        java.util.List<java.util.List<ComponentData>> roundProds =
                new java.util.ArrayList<>(Math.max(0, nRounds));
        java.util.List<ComponentData> tailProds = new java.util.ArrayList<>();
        java.util.Set<ComponentData> assigned =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<ComponentData, Boolean>());
        for (int i = 0; i < nRounds; i++) roundProds.add(new java.util.ArrayList<ComponentData>());
        if (comps != null && nRounds > 0) {
            for (int i = 0; i < nRounds; i++) {
                String key = roundKeyForIndex(activityGroups, toolComps, i);
                java.util.List<ComponentData> bucket = roundProds.get(i);
                for (ComponentData p : comps) {
                    if (p == null || "tool_call".equals(p.type)) continue;
                    if (assigned.contains(p)) continue;
                    if (key.equals(productOwnerKey(p))) { bucket.add(p); assigned.add(p); }
                }
            }
            for (ComponentData p : comps) {
                if (p == null || "tool_call".equals(p.type)) continue;
                if (!assigned.contains(p)) tailProds.add(p);
            }
        } else if (comps != null) {
            // 无轮次边界（不应发生，外层已保证 bounds 非空才进此方法）：全部产物走尾部兜底
            for (ComponentData p : comps) {
                if (p != null && !"tool_call".equals(p.type)) tailProds.add(p);
            }
        }
        // 2026-10-20 S2：活动区（思考 + 工具）由 updateThinkingSectionV2 统一渲染，
        // 这里不再写 thinkingLabel / thinkingContent，也不再往正文里内联思考块与工具卡。


        // 轮次结构指纹（2026-10-20 S2）：只编码正文段 T 与产物 P。
        // 思考/工具已经移到活动区，不再参与正文结构；D=展示默认档（换档必须重建）。
        StringBuilder fp = new StringBuilder();
        fp.append('D').append(ComponentDisplay.name(ComponentDisplay.defaultTier(ctx)));
        // STREAM-STATUS(2026-10-21)：生成中/已完成必须参与轮次结构指纹。
        // 否则流式建出的「正在思考」轮次头在完成时结构没变，增量路径只更新正文，
        // 头部文案停留在“正在思考”，退出重进才因整体重建恢复。
        fp.append('S').append(
                (message.status == ChatMessage.MessageStatus.GENERATING
                        || message.status == ChatMessage.MessageStatus.IN_PROGRESS) ? '1' : '0');
        int expect = 0;
        // 每轮活动区也是结构的一部分：轮次/状态/耗时/展开位变化必须触发重建。
        for (int g = 0; g < activityGroups.size(); g++) {
            ActivityGroup group = activityGroups.get(g);
            if (group == null || group.isEmpty()) continue;
            fp.append('A').append(g).append(':').append(group.key())
              .append(':').append(message.thinkingExpanded ? '1' : '0');
            for (ActivityTimeline.Item it : group.thinks) {
                fp.append('t').append(it.id).append(':').append(it.status)
                  .append(':').append(it.durationMs)
                  .append(':').append(it.hasBody() ? it.body.length() : 0);
            }
            for (ActivityTimeline.Item it : group.tools) {
                fp.append('x').append(it.id).append(':').append(it.status)
                  .append(':').append(it.durationMs)
                  .append(':').append(message.isToolSectionExpanded(group.key()) ? '1' : '0')
                  .append(':').append(it.hasBody() ? it.body.length() : 0);
            }
            expect++;
        }
        int start = 0;
        for (int i = 0; i <= bounds.size(); i++) {
            int end = (i < bounds.size()) ? bounds.get(i) : content.length();
            if (end > content.length()) end = content.length();
            String seg = start < content.length() ? content.substring(start, end) : "";
            if (seg != null && !seg.trim().isEmpty()) { fp.append('T'); expect++; }
            start = end;
            if (i < nRounds) {
                // 产物卡现在挂在工具卡内部，不占 contentHost 的子 View；
                // 但仍进指纹，卡片内容变化必须触发重建。
                for (ComponentData p : roundProds.get(i)) {
                    fp.append('Q').append(p.type).append('|')
                      .append(p.props != null ? p.props.toString() : "null").append(';');
                }
                // 没有对应活动组时产物退回正文尾部，这时它确实占一个子 View
                boolean hasGroup = i < activityGroups.size() && !activityGroups.get(i).isEmpty();
                if (!hasGroup) expect += roundProds.get(i).size();
            }
        }
        for (ComponentData p : tailProds) {
            if (!ComponentDisplay.mounts(ComponentDisplay.resolve(ctx, p))) continue;
            fp.append('P').append(p.type).append('|')
              .append(p.props != null ? p.props.toString() : "null").append(';');
            expect++;
        }
        String newFp = fp.toString();

        boolean streaming = message.status == ChatMessage.MessageStatus.GENERATING
                || message.status == ChatMessage.MessageStatus.IN_PROGRESS;

        // ---- 结构未变：只增量刷新正文段文本 ----
        if (newFp.equals(holder.roundStructureFingerprint)
                && holder.contentHost.getChildCount() == expect) {
            int childIdx = 0;
            start = 0;
            for (int i = 0; i <= bounds.size(); i++) {
                // 末尾那个"只有思考、没有工具"的活动组挂在最后一段正文之前
                if (i == nRounds && trailingGroup != null && !trailingGroup.isEmpty()) childIdx++;
                int end = (i < bounds.size()) ? bounds.get(i) : content.length();
                if (end > content.length()) end = content.length();
                String seg = start < content.length() ? content.substring(start, end) : "";
                if (seg != null && !seg.trim().isEmpty()) {
                    View v = holder.contentHost.getChildAt(childIdx);
                    if (v instanceof TextView) {
                        TextView tv = (TextView) v;
                        if (streaming) {
                            if (!seg.equals(tv.getText().toString())) tv.setText(seg);
                        } else {
                            setRenderedText(tv, seg, availableWidth);
                            tv.setMovementMethod(LinkMovementMethod.getInstance());
                        }
                    }
                    childIdx++;
                }
                start = end;
                if (i < nRounds) {
                    if (i < activityGroups.size() && !activityGroups.get(i).isEmpty()) {
                        childIdx++;
                    } else {
                        childIdx += roundProds.get(i).size();
                    }
                }
            }
            childIdx += ComponentDisplay.countMounting(tailProds, ctx);
            return;
        }

        // ---- 结构变化：重建（正文段 + 产物卡片；不再挂思考块/工具卡） ----
        releaseAndClearComponents(holder.contentHost);
        holder.componentSegmentViews = null;
        holder.componentSegmentsFingerprint = null;
        start = 0;
        int prodSeq = 0;
        for (int i = 0; i <= bounds.size(); i++) {
            // 末尾活动组（最后一轮思考，没有后续工具）在最后一段正文之前
            if (i == nRounds && trailingGroup != null && !trailingGroup.isEmpty()) {
                holder.contentHost.addView(buildRoundActivityView(holder, ctx, message,
                        trailingGroup, java.util.Collections.<ComponentData>emptyList(),
                        nRounds, activityGroups.size(), availableWidth, streaming));
            }
            int end = (i < bounds.size()) ? bounds.get(i) : content.length();
            if (end > content.length()) end = content.length();
            String seg = start < content.length() ? content.substring(start, end) : "";
            if (seg != null && !seg.trim().isEmpty()) {
                TextView tv = createSegmentTextView(ctx, holder.messageText);
                setRenderedText(tv, seg, availableWidth);
                holder.contentHost.addView(tv);
            }
            start = end;
            if (i < nRounds) {
                if (i < activityGroups.size() && !activityGroups.get(i).isEmpty()) {
                    holder.contentHost.addView(buildRoundActivityView(holder, ctx, message,
                            activityGroups.get(i), roundProds.get(i), i, activityGroups.size(),
                            availableWidth, streaming));
                } else {
                    // 数据缺组（旧历史/异常）不能把产物丢掉：退回正文尾部兜底
                    for (ComponentData p : roundProds.get(i)) {
                        mountRoundComponent(holder, ctx, message, p, prodSeq++);
                    }
                }
            }
        }
        for (ComponentData p : tailProds) {
            mountRoundComponent(holder, ctx, message, p, prodSeq++);
        }

        holder.roundStructureFingerprint = newFp;
        holder.roundRenderedChildren = expect;
    }


    /**
     * 与 AgentSummaryInfo#getFormattedTime 同一口径的耗时格式（<b>不要另立一套</b>：
     * 工具行、汇总卡、思考 header 三处显示的都是"这一段时间"，格式不一致会被当成 bug）。
     */
    private static String formatThinkingDuration(long ms) {
        if (ms < 1000L) return ms + "ms";
        if (ms < 60000L) return String.format(java.util.Locale.getDefault(), "%.1fs", ms / 1000.0);
        return String.format(java.util.Locale.getDefault(), "%dm %ds", ms / 60000, (ms % 60000) / 1000);
    }

    /**
     * v2 思考段：整段<b>一个入口</b>，展开后是"一步一行"的时间线（2026-10-20 重做）。
     *
     * <p>为什么重做：上一版把"已定稿的每轮"做成各自独立折叠的一行、再在上面留一条
     * "正在进行的这一轮"的实时行，等于同一个信息有两套机制、两个开关；
     * 而"第 N 轮"这个单位是模型内部结构（一次 reasoning→工具 的循环），对用户没有意义。
     * 业界口径是：一个回合一个折叠容器，header 文案随状态从"正在思考…"变成
     * "已思考 4.2s · 6 步"，展开后按时间顺序一步一行、每行默认只给一句摘要
     * （assistant-ui 的 thinking accordion、Gemini 的 steps、LangChain 的
     * "default collapsed + truncated preview"）。</p>
     *
     * <p>轮次模式也一样：思考/工具只在这条时间线里出现，正文不再内嵌过程块，
     * header 始终是这一节的唯一入口。</p>
     */
    private void updateThinkingSectionV2(AIMessageViewHolder holder, ChatMessage message,
                                         boolean isStreaming) {
        Context ctx = holder.itemView.getContext();
        // 2026-10-21 每轮活动区：有轮次边界时，思考行/工具卡由 renderRoundAssembled
        // 按轮插到正文之间，不再使用顶部这一个全局活动区。
        if (message.contentRoundBounds != null && !message.contentRoundBounds.isEmpty()) {
            ActivityTimeline.Model roundModel = ActivityTimeline.build(message);
            // 只有确实有活动项时才交给逐轮渲染；数据缺失（旧历史/异常）仍回退全局活动区，
            // 避免"两边都不画"导致思考/工具凭空消失。
            if (roundModel != null && !roundModel.isEmpty()) {
                if (holder.thinkSection != null) holder.thinkSection.setVisibility(View.GONE);
                if (holder.toolCard != null) holder.toolCard.setVisibility(View.GONE);
                return;
            }
        }
        android.widget.LinearLayout host = holder.thinkingRowsHost;
        ActivityTimeline.Model model = ActivityTimeline.build(message);

        // 2026-10-21 拆回 demo 两段式：THINKING（含规划/反思）进思考行，TOOL 进独立工具卡。
        java.util.List<ActivityTimeline.Item> thinkItems = new java.util.ArrayList<>();
        java.util.List<ActivityTimeline.Item> toolItems = new java.util.ArrayList<>();
        for (ActivityTimeline.Item item : model.items) {
            if (item.kind == ActivityTimeline.Kind.TOOL) toolItems.add(item);
            else thinkItems.add(item);
        }

        // 正在进行的当前轮：挂在思考行末尾的实时正文里（thinkingContent）
        String live = message.thinkingContent == null ? "" : message.thinkingContent.trim();
        live = stripThinkingTagMarkers(live);
        if (!live.isEmpty() && message.thinkingRounds != null && !message.thinkingRounds.isEmpty()) {
            String last = message.thinkingRounds.get(message.thinkingRounds.size() - 1);
            if (last != null && live.equals(stripThinkingTagMarkers(last).trim())) live = "";
        }

        // ---------- 思考行（demo .think-line） ----------
        boolean thinkExpanded = isStreaming || message.thinkingExpanded;
        boolean hasThinking = !thinkItems.isEmpty() || !live.isEmpty() || isStreaming;
        holder.thinkingLabel.setVisibility(hasThinking ? View.VISIBLE : View.GONE);
        if (!hasThinking) {
            holder.thinkingContent.setVisibility(View.GONE);
            if (host != null) host.setVisibility(View.GONE);
            if (holder.thinkingDivider != null) holder.thinkingDivider.setVisibility(View.GONE);
        } else {
            holder.thinkingLabel.setText(thinkingHeaderText(ctx, message, model, isStreaming, live));
            holder.thinkingLabel.setTextColor(androidx.core.content.ContextCompat.getColor(ctx,
                    model.anyFailed && !isStreaming ? R.color.chat_v2_danger : R.color.chat_v2_text_2));

            if (live.isEmpty()) {
                holder.thinkingContent.setVisibility(View.GONE);
            } else {
                holder.thinkingContent.setVisibility(thinkExpanded ? View.VISIBLE : View.GONE);
                holder.thinkingContent.setMovementMethod(LinkMovementMethod.getInstance());
                if (isStreaming) {
                    holder.thinkingContent.setText(live);
                } else if (thinkExpanded) {
                    int w = holder.itemView.getWidth()
                            - holder.itemView.getPaddingLeft() - holder.itemView.getPaddingRight()
                            - dpToPx(2, ctx);
                    setRenderedText(holder.thinkingContent, live, w);
                }
            }
            if (host != null) {
                String fp = thinkingFingerprint(message, thinkItems, thinkExpanded, isStreaming);
                if (!fp.equals(holder.activityFp) || host.getChildCount() != thinkItems.size()) {
                    host.removeAllViews();
                    int width = holder.itemView.getWidth()
                            - holder.itemView.getPaddingLeft() - holder.itemView.getPaddingRight();
                    if (width <= 0) width = getScreenWidth(ctx);
                    for (ActivityTimeline.Item item : thinkItems) {
                        host.addView(buildThinkingStepRow(holder, ctx, item.id,
                                item.body != null ? item.body : item.summary,
                                item.durationMs, width));
                    }
                    holder.activityFp = fp;
                }
                host.setVisibility(thinkExpanded && !thinkItems.isEmpty()
                        ? View.VISIBLE : View.GONE);
            }
            rotateThinkChevron(holder, thinkExpanded);
            if (holder.thinkingDivider != null) holder.thinkingDivider.setVisibility(View.GONE);
            holder.thinkingLabel.setClickable(true);
            holder.thinkingLabel.setOnClickListener(v -> {
                message.thinkingExpanded = !message.thinkingExpanded;
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos);
            });
        }

        // ---------- 独立工具卡（demo .tool-line） ----------
        if (holder.toolCard == null) return;
        if (toolItems.isEmpty()) {
            holder.toolCard.setVisibility(View.GONE);
            holder.toolCardFp = null;
            return;
        }
        holder.toolCard.setVisibility(View.VISIBLE);
        boolean toolExpanded = message.toolSectionExpanded;
        int running = 0;
        int failed = 0;
        long toolMs = 0L;
        for (ActivityTimeline.Item item : toolItems) {
            if (item.status == ActivityTimeline.Status.RUNNING) running++;
            if (item.status == ActivityTimeline.Status.FAILED) failed++;
            toolMs += item.durationMs;
        }
        String time = toolMs > 0L ? formatThinkingDuration(toolMs)
                : (message.agentSummaryInfo != null
                ? message.agentSummaryInfo.getFormattedTime() : "");
        TextView text = holder.toolCard.findViewById(R.id.chat_v2_tool_card_text);
        if (text != null) {
            if (running > 0) {
                // 工具侧「思考」文案：本轮工具开始时刻 + 引擎侧活动分类
                // （正在联网搜索… / 正在生成文件… / 正在读取文件…），与底部状态行同一套
                long start = message.runningToolStartTime;
                text.setText(com.oilquiz.app.ai.chat.InferenceStatusPhrases.toolPhrase(
                        com.oilquiz.app.ai.chat.status.AgentActivityKind.fromName(
                                message.runningToolKind),
                        start > 0 ? System.currentTimeMillis() - start : 0L,
                        message.runningToolTarget));
            } else {
                // 时间单独放在右侧 timeView（demo 的 .tool-time），标题只报数量，避免写两遍
                text.setText(ctx.getString(R.string.chat_v2_tools_ran, toolItems.size()));
            }
            text.setTextColor(androidx.core.content.ContextCompat.getColor(ctx,
                    failed > 0 ? R.color.chat_v2_danger : R.color.chat_v2_text_2));
        }
        TextView timeView = holder.toolCard.findViewById(R.id.chat_v2_tool_card_time);
        if (timeView != null) {
            timeView.setText(time != null ? time : "");
            timeView.setVisibility(time == null || time.isEmpty() ? View.GONE : View.VISIBLE);
        }
        View ok = holder.toolCard.findViewById(R.id.chat_v2_tool_card_ok);
        if (ok != null) {
            ok.setVisibility(running == 0 && failed == 0 ? View.VISIBLE : View.GONE);
        }
        View chev = holder.toolCard.findViewById(R.id.chat_v2_tool_card_chev);
        if (chev != null) chev.setRotation(toolExpanded ? 90f : 0f);

        if (holder.toolCardBody != null) {
            String fp = toolCardFingerprint(toolItems, toolExpanded);
            if (!fp.equals(holder.toolCardFp)
                    || holder.toolCardBody.getChildCount() != toolItems.size()) {
                holder.toolCardBody.removeAllViews();
                int width = holder.itemView.getWidth()
                        - holder.itemView.getPaddingLeft() - holder.itemView.getPaddingRight()
                        - dpToPx(2, ctx);
                if (width <= 0) width = getScreenWidth(ctx);
                LayoutInflater inflater = LayoutInflater.from(ctx);
                for (int i = 0; i < toolItems.size(); i++) {
                    holder.toolCardBody.addView(buildToolActivityRow(
                            holder, inflater, ctx, toolItems.get(i), i, width));
                }
                holder.toolCardFp = fp;
            }
            holder.toolCardBody.setVisibility(toolExpanded ? View.VISIBLE : View.GONE);
        }
        View header = holder.toolCard.findViewById(R.id.chat_v2_tool_card_header);
        if (header != null) {
            header.setOnClickListener(v -> {
                message.toolSectionExpanded = !message.toolSectionExpanded;
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos);
            });
        }
    }

    /** 思考行 header 文案：流式看状态、完成看步数/耗时（demo 的 think-line 只讲思考） */
    private String thinkingHeaderText(Context ctx, ChatMessage message, ActivityTimeline.Model model,
                                      boolean streaming, String live) {
        ChatMessage.InferencePhase phase = message != null && message.inferenceProgress != null
                ? message.inferenceProgress.phase : null;
        // 只有「这一轮还在思考」时头部才挂活动态；进正文/结束就回到"已深度思考"（含多个思考块时
        // 只作用于当前活跃块——非流式轮次走下面的完成态分支）。
        if (streaming) {
            String activePhrase = activeThinkingPhrase(message);
            if (activePhrase != null) return activePhrase;
            if (com.oilquiz.app.ai.chat.InferenceStatusLabel.isReasoningActive(phase)) {
                return ctx.getString(R.string.chat_v2_thinking_now);
            }
        }
        if (model.thinkingSteps > 0) {
            return model.thinkingDurationMs > 0L
                    ? ctx.getString(R.string.chat_v2_thought_for_steps,
                    formatThinkingDuration(model.thinkingDurationMs), model.thinkingSteps)
                    : ctx.getString(R.string.chat_v2_thought_steps_only, model.thinkingSteps);
        }
        // 非流式但没有结构化 thinkingSteps（常见：本地 Agent 只回填了 thinkingContent）：
        // 不能回落成“正在思考”，否则完成后轮次头永远停在生成态。
        return ctx.getString(R.string.chat_v2_thought_done);
    }

    /**
     * 活跃思考块的动态文案：与底部生成状态行共用 {@code InferenceStatusPhrases}，
     * 按"本阶段已耗时"推进（阶段起点由 Activity 在 markStreamingPhase 里重置）。
     * 返回 null 表示"没有可用的活动文案"（阶段未知/无起点），调用方自行回落旧资源文案。
     */
    private static String activeThinkingPhrase(ChatMessage message) {
        if (message == null || message.inferenceProgress == null) return null;
        ChatMessage.InferencePhase phase = message.inferenceProgress.phase;
        if (!com.oilquiz.app.ai.chat.InferenceStatusLabel.isReasoningActive(phase)) return null;
        long start = message.inferenceProgress.phaseStartTime;
        if (start <= 0 && message.timestamp > 0) start = message.timestamp;
        if (start > 0) {
            // 多步深度思考不报轮号：活跃块只显示当前阶段文案，历史块仍"已深度思考 X 秒"
            String phrase = com.oilquiz.app.ai.chat.InferenceStatusPhrases
                    .phrase(phase, System.currentTimeMillis() - start);
            if (phrase != null) return phrase;
        }
        return com.oilquiz.app.ai.chat.InferenceStatusLabel.shortLabel(phase);
    }

    /** 思考行指纹（只管思考/规划/反思；工具已拆到 toolCard） */
    private String thinkingFingerprint(ChatMessage message,
                                       java.util.List<ActivityTimeline.Item> items,
                                       boolean expanded, boolean streaming) {
        StringBuilder sb = new StringBuilder();
        sb.append(expanded ? '1' : '0').append(streaming ? 'S' : 'D');
        for (ActivityTimeline.Item it : items) {
            sb.append('|').append(it.kind).append(':').append(it.id)
                    .append(':').append(it.status).append(':').append(it.durationMs);
            if (message.isThinkingRoundExpanded(it.id)) sb.append(":x");
            if (it.hasBody()) sb.append(":b").append(it.body.length());
        }
        return sb.toString();
    }

    /** 工具卡指纹：工具数/状态/耗时/展开位/结果长度 */
    private String toolCardFingerprint(java.util.List<ActivityTimeline.Item> items,
                                       boolean expanded) {
        StringBuilder sb = new StringBuilder();
        sb.append(expanded ? '1' : '0');
        for (ActivityTimeline.Item it : items) {
            sb.append('|').append(it.id).append(':').append(it.status)
                    .append(':').append(it.durationMs);
            if (it.hasBody()) sb.append(":b").append(it.body.length());
        }
        return sb.toString();
    }

    /** 工具行：名称/参数摘要/状态 + 展开后的结果预览（点行展开，截断的再点开全文） */
    private View buildToolActivityRow(AIMessageViewHolder holder, LayoutInflater inflater,
                                      Context ctx, ActivityTimeline.Item item, int index,
                                      int availableWidth) {
        final ChatMessage msg = holder.holderMessage;
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(ctx);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        View line = inflater.inflate(R.layout.item_chat_v2_tool_row, wrap, false);
        TextView name = line.findViewById(R.id.chat_v2_tool_row_name);
        if (name != null) {
            name.setText(item.title.isEmpty()
                    ? ctx.getString(R.string.chat_v2_tool_unknown) : item.title);
        }
        TextView desc = line.findViewById(R.id.chat_v2_tool_row_desc);
        if (desc != null) {
            desc.setText(item.summary);
            desc.setVisibility(item.summary.isEmpty() ? View.GONE : View.VISIBLE);
        }
        TextView state = line.findViewById(R.id.chat_v2_tool_row_time);
        if (state != null) {
            state.setText(toolRowStatusText(ctx, itemStatusName(item.status)));
            state.setTextColor(toolRowStatusColor(ctx, itemStatusName(item.status)));
        }
        View divider = line.findViewById(R.id.chat_v2_tool_row_divider);
        if (divider != null) divider.setVisibility(index == 0 ? View.GONE : View.VISIBLE);

        final boolean hasBody = item.hasBody();
        final boolean expanded = msg != null && msg.isThinkingRoundExpanded(item.id);
        if (hasBody && line instanceof ViewGroup && ((ViewGroup) line).getChildCount() > 1
                && ((ViewGroup) line).getChildAt(1) instanceof ViewGroup) {
            // 行尾箭头：没有结果时不给"可点"的暗示
            android.widget.ImageView chev = new android.widget.ImageView(ctx);
            int sz = dpToPx(13, ctx);
            android.widget.LinearLayout.LayoutParams clp =
                    new android.widget.LinearLayout.LayoutParams(sz, sz);
            clp.setMarginStart(dpToPx(4, ctx));
            chev.setLayoutParams(clp);
            chev.setImageResource(R.drawable.ic_chevron_right);
            chev.setImageTintList(android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.chat_v2_text_3)));
            chev.setAlpha(0.55f);
            chev.setRotation(expanded ? 90f : 0f);
            ((ViewGroup) ((ViewGroup) line).getChildAt(1)).addView(chev);
        }
        wrap.addView(line);

        if (hasBody) {
            TextView body = new TextView(ctx);
            body.setTextSize(12f);
            body.setLineSpacing(0f, 1.6f);
            body.setTextColor(androidx.core.content.ContextCompat.getColor(ctx,
                    R.color.chat_v2_text_2));
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            if (expanded) {
                com.oilquiz.app.ai.chat.util.ToolResultText.Preview preview =
                        com.oilquiz.app.ai.chat.util.ToolResultText.preview(item.body);
                body.setText(preview.text);
                body.setMovementMethod(LinkMovementMethod.getInstance());
                if (preview.truncated) {
                    body.setOnClickListener(v -> showFullToolResult(ctx,
                            ctx.getString(R.string.chat_v2_tool_result_title,
                                    item.title.isEmpty()
                                            ? ctx.getString(R.string.chat_v2_tool_unknown)
                                            : item.title),
                            item.body));
                }
            }
            wrap.addView(body, new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        line.setOnClickListener(v -> {
            if (msg == null || !hasBody) return;
            msg.toggleThinkingRoundExpanded(item.id);
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos);
        });
        return wrap;
    }

    private static String itemStatusName(ActivityTimeline.Status status) {
        if (status == ActivityTimeline.Status.DONE) return "completed";
        if (status == ActivityTimeline.Status.FAILED) return "failed";
        if (status == ActivityTimeline.Status.RUNNING) return "running";
        return "pending";
    }

    /** 箭头显式跟随整节展开态（ThinkLineBody 只跟正文显隐，节内只有步骤列表时它不知道） */
    private void rotateThinkChevron(AIMessageViewHolder holder, boolean expanded) {
        if (holder.thinkingChevron == null) return;
        holder.thinkingChevron.setRotation(expanded ? 90f : 0f);
    }

    /**
     * 时间线里的一步：一行摘要 + 右侧耗时 + 箭头，点开才是这一段的全文。
     *
     * <p>摘要取该段首句（{@link #thinkingPreview}），点开的全文按 Markdown 渲染。
     * 折叠状态仍按轮 id 记在 {@code message.thinkingExpandedRounds}——
     * 单位换了但锚点没换，历史恢复/复用后不会串。</p>
     */
    private View buildThinkingStepRow(AIMessageViewHolder holder, Context ctx, String roundId,
                                      String text, long durationMs, int availableWidth) {
        final ChatMessage msg = holder.holderMessage;
        final String anchorId = (roundId == null || roundId.isEmpty())
                ? ("round-" + System.identityHashCode(text)) : roundId;
        final boolean expanded = msg != null && msg.isThinkingRoundExpanded(anchorId);

        android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
        row.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams rlp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rlp);

        android.widget.LinearLayout head = new android.widget.LinearLayout(ctx);
        head.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setClickable(true);
        head.setFocusable(true);
        head.setForeground(getSelectableItemBackground(ctx));
        head.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.widget.TextView summary = new android.widget.TextView(ctx);
        summary.setTextSize(12f);
        summary.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.chat_v2_text_2));
        summary.setSingleLine(true);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        String preview = thinkingPreview(stripThinkingTagMarkers(text), false);
        summary.setText(preview.isEmpty() ? "…" : preview);
        head.addView(summary, new android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (durationMs > 0L) {
            android.widget.TextView dur = new android.widget.TextView(ctx);
            dur.setTextSize(11f);
            dur.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.chat_v2_text_3));
            dur.setPadding(dpToPx(6, ctx), 0, 0, 0);
            dur.setText(formatThinkingDuration(durationMs));
            head.addView(dur, new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        android.widget.ImageView chev = new android.widget.ImageView(ctx);
        int sz = dpToPx(13, ctx);
        android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(sz, sz);
        clp.setMarginStart(dpToPx(4, ctx));
        chev.setLayoutParams(clp);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setImageTintList(android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(ctx, R.color.chat_v2_text_3)));
        chev.setAlpha(0.55f);
        chev.setRotation(expanded ? 90f : 0f);
        head.addView(chev);

        android.widget.TextView body = new android.widget.TextView(ctx);
        body.setTextSize(12f);
        body.setLineSpacing(0f, 1.6f);
        body.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.chat_v2_text_2));
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        row.setTag(anchorId);
        body.setTag("round_body_" + anchorId);
        if (expanded) {
            setRenderedText(body, stripThinkingTagMarkers(text), availableWidth);
            body.setMovementMethod(LinkMovementMethod.getInstance());
        }

        head.setOnClickListener(v -> {
            if (msg == null) return;
            msg.toggleThinkingRoundExpanded(anchorId);
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos);
        });
        row.addView(head);
        row.addView(body, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }


    /**
     * 按思考轮 id 定位并更新单个思考轮内容（不重建整条消息）。
     *
     * <p>NO-HOT-UPDATE-OK(2026-10-09)：本方法目前**没有调用者**，这是**正确的**，不是遗漏。
     * 原因：轮次文本在被 {@code ChatMessage.addThinkingRound} 追加进 {@code thinkingRounds}
     * 时即已定稿（该方法只 append，且全仓库没有"按索引替换轮次文本"的 API），此后不再变化；
     * 正在流式的当前轮走 {@code message.thinkingContent} + {@link #updateThinkingContent}，
     * 与这里渲染的已定稿轮次不重叠。因此 {@code renderRoundAssembled} 的增量路径跳过思考块、
     * 不更新其文本是安全的。</p>
     *
     * <p>保留本方法作为备用通路：若将来需要"进行中的轮次也就地热更新"（而不是等轮次终结
     * 再落库），可直接接线到 {@code PAYLOAD_THINKING_UPDATE} 分支。</p>
     *
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
                            boolean roundExpanded = holder.holderMessage != null
                                    && holder.holderMessage.isThinkingRoundExpanded(roundId);
                            if (!roundExpanded) {
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
        boolean isStreaming = message.status == ChatMessage.MessageStatus.GENERATING
                || message.status == ChatMessage.MessageStatus.IN_PROGRESS;
        updateThinkingSectionV2(holder, message, isStreaming);
    }



    /**
     * dsh ReasoningRow.latestCompletedParagraphFirstLine 移植：取"最新已完成段落"的首行作折叠摘要。
     * 实现已抽到 {@link ThinkingPreview}（parts 的 reasoning 折叠头共用同一份）。
     */
    private static String thinkingPreview(String content, boolean streaming) {
        return ThinkingPreview.preview(content, streaming, ThinkingPreview.MAX_CHARS);
    }

    /**
     * 检查字符串是否以"段落分隔符"结尾。实现已抽到
     * {@link ThinkingPreview#endsWithParagraphBreak}。
     */
    private static boolean endsWithParagraphBreak(String s) {
        return ThinkingPreview.endsWithParagraphBreak(s);
    }


    /**
     * 判断 position 是否为列表中最后一条可交互 AI 消息（操作栏/统计/时间只显示在此）。
     * 后续的 AI 或 ERROR 消息都会使它不再是"最后可交互"（Fix I）。
     */
    private boolean isLastAiMessage(int position) {
        for (int i = position + 1; i < messages.size(); i++) {
            ChatMessage.MessageType t = messages.get(i).type;
            if (t == ChatMessage.MessageType.AI || t == ChatMessage.MessageType.ERROR) return false;
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

        // STATUS-LASTAI-GATE(2026-10-09)：统计行（tokens/耗时/速度）只应显示在**最后一条**
        // AI 消息上。这个门控原先只写在全量绑定里（bindAIMessage 的 `if (!lastAi && ...)`），
        // 而 updateMessageStatus 会在每次 PAYLOAD_STATUS_UPDATE / PAYLOAD_INFERENCE_PROGRESS
        // 时把 statusIcon/statusText 重新设为 VISIBLE —— 于是任何一次后续刷新都会把中间轮次的
        // 统计行"复活"，而只有下一次全量绑定才会再隐藏它。
        // 收敛到这里：所有调用路径都会经过结尾的 return，门控只写一处，不会再各自漂移。
        final int gatePos = holder.getBindingAdapterPosition();
        final boolean showStatusRow = gatePos == RecyclerView.NO_POSITION
                || isLastAiMessage(gatePos);

        // STATUS-SINGLE-CRITERION(2026-10-09)：完成判定只认 message.status。
        // 此前这里是双判据（message.isCompleted() **或** InferenceStateManager 已 COMPLETED），
        // 而思考标签/推理进度只看 message.status。当推理状态先到 COMPLETED、消息状态还是
        // GENERATING 时，同一条气泡会同时显示"✅ N tokens"和"🧠 思考中：…"。
        // 推理状态属于"推理任务"，消息状态属于"这条消息"，两者语义不同，以消息状态为准。
        if (message.status == ChatMessage.MessageStatus.FAILED) {
            // FAIL-SURFACE(2026-10-09)：此前 FAILED 落到最后的 else，把 statusIcon 和
            // statusText 双双隐藏，失败消息看上去和"成功的空消息"一样，用户拿不到任何
            // 失败信号（而 failMessage 其实已经把原因存进了 message.errorDetail）。
            if (holder.inferenceProgressView != null) {
                holder.inferenceProgressView.hide();
            }
            holder.statusIcon.setVisibility(View.VISIBLE);
            holder.statusIcon.setImageResource(R.drawable.ic_error);
            holder.statusIcon.setColorFilter(context.getColor(R.color.error));
            String failText = context.getString(R.string.h_1f21bef7);
            if (message.errorDetail != null && !message.errorDetail.trim().isEmpty()) {
                failText += " · " + message.errorDetail.trim();
            }
            holder.statusText.setText(failText);
            holder.statusText.setVisibility(View.VISIBLE);
            holder.statusText.setTextColor(context.getColor(R.color.error));
            return;
        }

        // 非失败态：恢复状态行的常规配色（失败分支可能改过）
        holder.statusText.setTextColor(ThemeColors.attr(context, R.attr.colorOnSurfaceVariant));
        holder.statusIcon.clearColorFilter();

        if (message.status == ChatMessage.MessageStatus.COMPLETED) {
            // 隐藏推理进度视图
            if (holder.inferenceProgressView != null) {
                holder.inferenceProgressView.hide();
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
                    // ACCEL-LABEL(2026-10-09)：GPU 层数实际可能卸载到 Hexagon NPU
                    // （它作为 GPU 类型设备参与 -ngl），故按**实际生效的后端**取词，
                    // 不再一律写 "GPU"。
                    statusStr += " · " + accelLabel() + " " + message.gpuLayers + "层";
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
            } else {
                if (holder.inferenceProgressView != null) {
                    holder.inferenceProgressView.updateState(currentState, stateDetails);
                }
            }
            
            holder.statusIcon.setVisibility(View.GONE);
            holder.statusText.setVisibility(View.GONE);
        } else {
            // 其他状态（失败、取消等）
            if (holder.inferenceProgressView != null) {
                holder.inferenceProgressView.hide();
            }
            holder.statusIcon.setVisibility(View.GONE);
            holder.statusText.setVisibility(View.GONE);
        }

        // STATUS-LASTAI-GATE：非最终轮不显示统计行（上面各分支可能刚把它设为 VISIBLE）。
        // 门控写在这一处，所有 return 之外的分支都会经过，不会再被后续 payload 刷新复活。
        // 2026-10-21：demo 的消息里没有「已完成 · N token · Ns」这一行，完成后/生成中的
        // 统计一律收起（「⋯」面板与消息详情里仍有），只有失败/错误/暂停保留。
        boolean quietStatus = message.status == ChatMessage.MessageStatus.COMPLETED
                || message.status == ChatMessage.MessageStatus.GENERATING
                || message.status == ChatMessage.MessageStatus.IN_PROGRESS;
        if (!showStatusRow || quietStatus) {
            if (holder.statusIcon != null) holder.statusIcon.setVisibility(View.GONE);
            if (holder.statusText != null) holder.statusText.setVisibility(View.GONE);
        }
    }

    /**
     * 加速后端显示名。层数由 -ngl 决定，而目标设备可能是 Hexagon NPU
     * （它作为 GPU 类型设备参与卸载），故按实际生效后端取词。
     *
     * <p>优先用 native 回传的**解析结果**：偏好为 "auto" 时它才说明最终落到
     * 哪个设备（真机上 auto 会解析成 HTP/NPU）。解析结果为空（尚未加载模型或
     * 纯 CPU）时退回偏好值。</p>
     */
    private String accelLabel() {
        String b = null;
        try {
            b = com.oilquiz.app.ai.jni.LlamaHelper.getResolvedBackend();
            if (b == null || b.isEmpty()) {
                b = com.oilquiz.app.ai.jni.LlamaHelper.getLoadedBackend();
            }
        } catch (Throwable ignored) {
            // 取不到时退回通用词，不影响渲染
        }
        if (b == null || b.isEmpty()) return "GPU";
        switch (b) {
            case "hexagon": return "NPU";
            case "opencl":  return "OpenCL";
            case "vulkan":  return "Vulkan";
            case "auto":    return "自动";
            default:        return "GPU";
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
                com.oilquiz.app.ai.chat.component.MermaidMathSplitter.splitMermaidOnly(message.content, isDark);
        StringBuilder fp = new StringBuilder();
        for (com.oilquiz.app.ai.chat.component.MermaidMathSplitter.Segment seg : segs) {
            if (seg.isComponent && seg.component != null && seg.component.props != null) {
                // CACHE-KEY-CONSISTENT(2026-10-09)：这里原先用 props.toString().hashCode()，
                // 而下方"组件标记"路径（ComponentContentSplitter 分支）用的是完整 props 字符串，
                // 两条路径却共用 holder.componentSegmentsFingerprint / componentSegmentViews。
                // 后果：① 同一份 props 在两条路径下算出不同指纹，切换路径时缓存必然失效；
                // ② 32 位哈希可碰撞（例：latex "Aa" 与 "BB" 的 props 哈希相同），
                //    一旦碰撞就会复用**错误的** WebView，显示成另一个公式/图表。
                // 改为与另一条路径完全一致的完整字符串指纹。
                fp.append(seg.component.type).append('|')
                        .append(seg.component.props.toString()).append(';');
            }
        }
        String newFp = fp.toString();
        // staleCache：上一轮的组件段 View。指纹一致才复用，否则本轮结尾统一释放
        List<View> staleCache = holder.componentSegmentViews;
        boolean canReuse = newFp.equals(holder.componentSegmentsFingerprint)
                && staleCache != null;
        List<View> oldCache = canReuse ? staleCache
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
        releaseStaleSegmentViews(staleCache, newCache);
        holder.componentSegmentsFingerprint = newFp;
        holder.componentSegmentViews = newCache;
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
        // LAYOUT-LISTENER-LIFETIME(2026-10-09)：这个一次性布局监听此前只在自身回调里移除，
        // 且闭包持有 holder + message 强引用。若布局发生前该项被重绑/回收，监听器仍会执行
        // bindMessageContent(holder, 旧 message, …) —— 把旧内容渲染进已被复用的视图；
        // 同一 holder 多次进入本方法还会叠加多个监听器。
        // 处理：① 注册前先摘掉上一个；② 回调里校验 holder 仍绑定同一消息；③ 记录到 holder，
        // 由 onViewRecycled 兜底摘除。
        removePendingLayoutListener(holder);
        final android.view.ViewTreeObserver.OnGlobalLayoutListener listener =
            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                private boolean ran = false;
                @Override
                public void onGlobalLayout() {
                    if (ran) return;
                    ran = true;
                    if (holder.messageText.getViewTreeObserver().isAlive()) {
                        holder.messageText.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                    holder.pendingLayoutListener = null;
                    // holder 已被重绑到别的消息：这次回调属于过期渲染，丢弃
                    if (holder.holderMessage != message) return;
                    int w = holder.itemView.getWidth();
                    int availableWidth = w - holder.itemView.getPaddingLeft()
                        - holder.itemView.getPaddingRight() - dpToPx(2, holder.itemView.getContext());
                    if (availableWidth <= 0) availableWidth = getScreenWidth(holder.itemView.getContext());
                    bindMessageContent(holder, message, availableWidth);
                }
            };
        holder.pendingLayoutListener = listener;
        holder.messageText.getViewTreeObserver().addOnGlobalLayoutListener(listener);
    }

    /** 摘除该 holder 上尚未触发的布局监听（存在则移除并清空引用） */
    private void removePendingLayoutListener(AIMessageViewHolder holder) {
        android.view.ViewTreeObserver.OnGlobalLayoutListener old = holder.pendingLayoutListener;
        if (old == null) return;
        holder.pendingLayoutListener = null;
        try {
            android.view.ViewTreeObserver vto = holder.messageText.getViewTreeObserver();
            if (vto.isAlive()) {
                vto.removeOnGlobalLayoutListener(old);
            }
        } catch (Throwable ignored) {
        }
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
    /** 判断当前是否深色模式（供 MarkdownWebView 主题）。 */
    private boolean isNightMode(Context ctx) {
        int m = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 从应用主题 token 生成 MarkdownWebView 的 CSS 变量覆盖，使正文配色与原生卡片一致。 */
    private String buildMarkdownThemeVars(Context ctx) {
        try {
            int fg = ThemeColors.attr(ctx, R.attr.colorOnSurface);
            int link = ThemeColors.attr(ctx, R.attr.colorPrimary);
            int muted = ThemeColors.attr(ctx, R.attr.colorControlTextSecondary);
            return "--fg:" + toCss(fg) + ";--link:" + toCss(link) + ";--muted:" + toCss(muted);
        } catch (Throwable t) {
            return ""; // 取色失败则用 MarkdownHtmlConverter 内置默认配色
        }
    }

    private static String toCss(int argb) {
        return String.format(java.util.Locale.US, "#%06X", 0xFFFFFF & argb);
    }

    /**
     * 确保纯文本模式下 contentHost 恰好含 [messageText, markdownView] 两个子 View。
     * mermaid/代码/轮次/组件等分支会 removeAllViews 自建子 View，切回纯文本时需补齐；
     * 二者显隐由调用方按 webMarkdownEnabled 决定，这里只管"都在场"。
     */
    private void ensurePlainTextHostViews(AIMessageViewHolder holder) {
        LinearLayout host = holder.contentHost;
        if (host == null) return;
        boolean hasText = false, hasMv = false;
        for (int i = 0; i < host.getChildCount(); i++) {
            View c = host.getChildAt(i);
            if (c == holder.messageText) hasText = true;
            else if (holder.markdownView != null && c == holder.markdownView) hasMv = true;
        }
        if (!hasText || !hasMv) {
            host.removeAllViews();
            host.addView(holder.messageText);
            if (holder.markdownView != null) host.addView(holder.markdownView);
        }
    }

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

    /** 每视图当前有效 token；在 drainPendingRenders 中清理已分离视图的条目 */
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
            // 清理已分离视图的 token 条目，防止 static map 持有回收后的 TextView 造成泄漏
            for (TextView tv : RENDER_TOKENS.keySet()) {
                if (tv != null && !tv.isAttachedToWindow()) {
                    RENDER_TOKENS.remove(tv);
                }
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

    /** 检测内容中是否包含 ``` 代码围栏（排除已由 MermaidMathSplitter 处理的 mermaid 块） */
    private static boolean hasFencedCode(String content) {
        return content != null && content.contains("```");
    }

    /**
     * 分割并渲染：把 content 按 ``` fence 拆成 (text, code, text, code, ...)，
     * 文本段走 Markdown，代码段生成 code_card ComponentData 交给 ComponentRegistry
     * 复用现成的 CodeCardView（自带语言标签、暗色主题、一键复制）。
     */
    private void renderWithCodeCards(AIMessageViewHolder holder, ChatMessage message,
                                     int availableWidth, Context ctx) {
        String content = message.content != null ? message.content : "";
        holder.contentHost.removeAllViews();
        java.util.List<String[]> segs = splitFencedSegments(content);
        for (String[] seg : segs) {
            if ("code".equals(seg[0])) {
                org.json.JSONObject props = new org.json.JSONObject();
                try {
                    props.put("language", seg[1]);
                    props.put("code", seg[2]);
                } catch (org.json.JSONException ignored) {}
                ComponentData data = new ComponentData("code_card", props);
                View v = ComponentRegistry.getInstance().render(ctx, data);
                if (v != null) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = dpToPx(8, ctx);
                    holder.contentHost.addView(v, lp);
                }
            } else {
                String text = seg[2];
                if (text == null || text.trim().isEmpty()) continue;
                TextView tv = createSegmentTextView(ctx, holder.messageText);
                setRenderedText(tv, text, availableWidth);
                tv.setMovementMethod(LinkMovementMethod.getInstance());
                holder.contentHost.addView(tv);
            }
        }
    }

    /**
     * 按 ``` fence 拆分：返回 [kind, language, content] 数组序列。
     * kind ∈ "text" | "code"。fence 首行的语言标识提取到 language 字段。
     * 流式中未闭合的 fence 也识别为 code（避免半渲染源码）。
     */
    private static java.util.List<String[]> splitFencedSegments(String content) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        int i = 0;
        int len = content.length();
        StringBuilder textBuf = new StringBuilder();
        while (i < len) {
            int fence = content.indexOf("```", i);
            if (fence < 0) {
                textBuf.append(content, i, len);
                break;
            }
            textBuf.append(content, i, fence);
            if (textBuf.length() > 0) {
                out.add(new String[]{"text", "", textBuf.toString()});
                textBuf.setLength(0);
            }
            // 解析 fence 首行（语言标识）
            int headerEnd = content.indexOf('\n', fence);
            if (headerEnd < 0) {
                // 未闭合的 fence 且无换行，剩余当代码
                String lang = content.substring(fence + 3).trim();
                out.add(new String[]{"code", lang, ""});
                break;
            }
            String lang = content.substring(fence + 3, headerEnd).trim();
            // 关闭 fence：从 headerEnd+1 起找下一个 ```
            int close = content.indexOf("```", headerEnd + 1);
            if (close < 0) {
                // 流式中未闭合：把余下全部当代码
                String code = content.substring(headerEnd + 1);
                out.add(new String[]{"code", lang, code});
                i = len;
                break;
            }
            String code = content.substring(headerEnd + 1, close);
            out.add(new String[]{"code", lang, code});
            i = close + 3;
            // 跳过紧跟的换行
            if (i < len && content.charAt(i) == '\n') i++;
        }
        if (textBuf.length() > 0) {
            out.add(new String[]{"text", "", textBuf.toString()});
        }
        return out;
    }

    /** 主线程应用渲染结果（代次校验通过才 setText） */
    private static void applyRenderedText(TextView textView, Spanned rendered, long token) {
        if (!isRenderCurrent(textView, token)) return;
        try {
            // 代码块的复制能力已由 CodeCardView 组件承担（renderWithCodeCards 分派）；
            // 这里只负责把 Markdown 渲染结果应用到 TextView。
            SpannableStringBuilder ssb = new SpannableStringBuilder(rendered);
            TextViewSpan.applyTo(ssb, textView);
            textView.setText(ssb);
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
        // COMPONENT-CACHE-KEY(2026-10-09)：缓存键不能用列表**引用**。生产方会就地把新组件
        // append 进同一个 List（AIChatActivity 在工具完成时对 finalMsg.components 做 addAll，
        // 不重新赋值），此时引用不变、内容已变，早退会导致工具产出的 list_card/image_grid
        // 等卡片永远不显示（除非 holder 被回收后重新绑定）。改为按内容指纹判断。
        int fp = 17;
        // 展示默认档也是缓存键的一部分（2026-10-20 设置页）：用户换档时组件内容一个字都没变，
        // 不把档位算进来，重绑会走"缓存命中"早退，屏幕上的卡片仍是旧样子。
        fp = fp * 31 + ComponentDisplay.name(ComponentDisplay.defaultTier(
                holder.componentContainer.getContext())).hashCode();
        if (message.components != null) {
            for (ComponentData c : message.components) {
                fp = fp * 31 + (c == null ? 0
                        : (c.type == null ? 0 : c.type.hashCode()) * 31
                          + (c.props == null ? 0 : c.props.toString().hashCode()));
            }
            fp = fp * 31 + message.components.size();
        }
        if (holder.boundComponents == message.components && holder.boundComponentsFingerprint == fp) {
            return;
        }
        releaseAndClearComponents(holder.componentContainer);
        holder.boundComponents = message.components;
        holder.boundComponentsFingerprint = fp;

        if (message.components == null || message.components.isEmpty()) {
            holder.componentContainer.setVisibility(View.GONE);
            return;
        }
        Context ctx = holder.itemView.getContext();
        holder.componentContainer.setVisibility(View.VISIBLE);

        // v2 口径：工具过程只由 activitySection（思考行 + 工具卡）渲染，
        // 这里不再画旧的 "🔧 工具过程 N 个" 折叠卡——同一份过程有两套 UI 正是要收掉的。
        // 本容器只兜底"无轮次"消息的产物卡，并且已经排在 contentHost 之后。
        boolean roundAssembled = message.contentRoundBounds != null
                && !message.contentRoundBounds.isEmpty();
        boolean first = true;
        int rendered = 0;

        // 渲染非工具组件（工具产物卡）：2026-10-20 起不再"始终完整显示"，改由 ComponentDisplay 分档——
        // INLINE 整卡；PEEK 收成一行摘要（点开才是完整卡）；HIDDEN 不挂载（卡片数据仍在 message.components 里，可复盘）。
        // prodIndex 对每个产物都推进（含 hidden），保证没有 id 的组件在同一消息里的锚点稳定。
        int prodIndex = 0;
        if (!roundAssembled && message.components != null) {
            for (ComponentData data : message.components) {
                if (data == null || data.props == null || "tool_call".equals(data.type)) continue;
                ComponentDisplay.Mode mode = ComponentDisplay.resolve(ctx, data);
                if (!ComponentDisplay.mounts(mode)) {
                    prodIndex++;
                    continue;
                }
                View view = ComponentRegistry.getInstance().render(ctx, data);
                if (view == null) {
                    // 渲染失败降级占位（统一错误态卡片，不显示组件源码）
                    view = ComponentRegistry.getInstance().renderFallback(ctx, data);
                }
                View mounted = mode == ComponentDisplay.Mode.PEEK
                        ? wrapProductPeek(ctx, message, data, view, prodIndex++)
                        : view;
                if (mounted == null) {
                    prodIndex++;
                    continue;
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = first ? dpToPx(6, ctx) : dpToPx(8, ctx);
                holder.componentContainer.addView(mounted, lp);
                first = false;
                rendered++;
            }
        }
        if (rendered == 0) {
            holder.componentContainer.setVisibility(View.GONE);
        }
    }

    /**
     * 切换工具过程卡片的展开/折叠：翻转状态后使指纹失效触发重建，
     * 不替换 List 引用避免与外部生产者的 addAll 断链（Fix D）。
     */
    private void toggleAgentToolsExpanded(AIMessageViewHolder holder, ChatMessage message) {
        message.agentToolsExpanded = !message.agentToolsExpanded;
        // 使组件指纹失效（-1 不可能被正常计算命中）
        holder.boundComponentsFingerprint = -1;
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
    /**
     * parts 渲染器接管的绑定（P3b，P6 扩到流式，2026-10-24）。调用方已判过
     * {@code parts_renderer}（出厂档已翻成开，见 {@link com.oilquiz.app.ai.chat.parts.PartsRendererFlag}）
     * 且消息是完成态或（{@code parts_streaming} 开着时的）生成中态；完成态不再要求
     * {@code assistantTurn} 非空——它不是渲染输入，见 {@link #bindMessageContent} 那段闸门注释。
     * 用一个 holder 独占的 {@code partsContainer} 让 {@link com.oilquiz.app.ai.chat.parts.TurnPartsHost}
     * 增量渲染，旧正文视图隐藏；旧渲染路径绝不往 partsContainer 里加 View，避免争抢子 View 顺序。
     *
     * @return 成功接管返回 true；无法接管（contentHost 为空、parts 构不出可渲内容、含未复刻类型）
     *         返回 false，调用方回落旧路径。
     */
    private boolean bindWithParts(AIMessageViewHolder holder, ChatMessage message,
                                  int availableWidth, Context ctx) {
        if (holder.contentHost == null) {
            return false;
        }
        // 能力覆盖闸门：先构建 parts（fromMessageForStreaming/Parts，不改 live assistantTurn）并判定
        // ① 每个 part 都有注册渲染器；② 只接管 allowed 名单里的类型（P3f–P3j 已逐类复刻并放开：
        //    text/reasoning/tool_call/tool_result/tool_group/data，见下方循环）；
        // ③ mermaid/公式已由 P3g 的 expandInline 切成 html data part，不再触发回落。
        // 任一不满足 → 直接 return false，**不碰 contentHost**。
        //
        // 流式的 parts 必须换真值来源：`roundSnapshots` 是每轮定稿才追加的，而 msg.content 在工具调用
        // 边界就被 flush 成当前累积正文——用完成态的快照分支重建，"正在写的这一轮"（还没有快照）
        // 会整段不见，观感就是正文永远落后一轮。所以这里按在不在生成中分叉。
        final com.oilquiz.app.ai.chat.turn.AssistantTurn partsTurn = isStreamingMessage(message)
                ? com.oilquiz.app.ai.chat.turn.AssistantTurn.fromMessageForStreaming(
                        message, isNightMode(ctx))
                : com.oilquiz.app.ai.chat.turn.AssistantTurn.fromMessageForParts(
                        message, isNightMode(ctx));
        final com.oilquiz.app.ai.chat.parts.PartRendererRegistry partsRegistry =
                com.oilquiz.app.ai.chat.parts.PartRendererRegistry.createDefault();
        if (partsTurn == null || partsTurn.parts == null || partsTurn.parts.isEmpty()
                || !partsRegistry.canRenderAll(partsTurn.parts)) {
            // 空 parts（正文已清空但消息仍标记完成的历史行）必须回落 legacy：canRenderAll 对空列表
            // 返回真，接管后会渲出一个空气泡，把 legacy 本来还能显示的内容吃掉。
            return false;
        }
        for (com.oilquiz.app.ai.chat.turn.AssistantTurn.Part p : partsTurn.parts) {
            if (p == null) {
                continue;
            }
            boolean allowed = com.oilquiz.app.ai.chat.turn.AssistantTurn.Part.TYPE_TEXT.equals(p.type)
                    || com.oilquiz.app.ai.chat.turn.AssistantTurn.Part.TYPE_REASONING.equals(p.type)
                    || com.oilquiz.app.ai.chat.turn.AssistantTurn.Part.TYPE_TOOL_CALL.equals(p.type)
                    || com.oilquiz.app.ai.chat.turn.AssistantTurn.Part.TYPE_TOOL_RESULT.equals(p.type)
                    || com.oilquiz.app.ai.chat.turn.AssistantTurn.Part.TYPE_TOOL_GROUP.equals(p.type)
                    || com.oilquiz.app.ai.chat.turn.AssistantTurn.Part.TYPE_DATA.equals(p.type);
            if (!allowed) {
                // 闸门保留给"将来真加 error/approval 生产者"的场景；**当前不可达**——
                // fromMessageForParts 只产上面这六种，生产代码里没有 error/approval part 的写者。
                return false;
            }
            // mermaid/公式已被 expandInline 切成 html data part，text part 不再含其结构。
        }

        if (holder.partsHost == null) {
            holder.partsHost = new ChatPartsHost(ctx, new ChatPartsHost.Callback() {
                @Override
                public void onApprovalDecision(String partId, String toolCallId, boolean approved) {
                    // 审批 part 的生产者（在线引擎/权限流）尚未接入，这里先占位；接入后回调权限流。
                }

                @Override
                public void onPartsContentChanged() {
                    if (holder.contentHost != null) {
                        holder.contentHost.post(holder.contentHost::requestLayout);
                    }
                    // 2026-10-25 流式滚动：parts 内容/WebView 高度变化（思考正文长高、正文流式
                    // 逐块变高）也要喂给贴底跟随。Activity 端只有 pinned 时才会真正滚，
                    // 用户上滑阅读时这里等于空操作。
                    if (onContentSizeChanged != null) {
                        onContentSizeChanged.run();
                    }
                }

                @Override
                public void onShowFullResult(String title, String fullText) {
                    // 复用 legacy 同一个全屏结果对话框，parts 行展开全文时不另造弹层。
                    showFullToolResult(ctx, title, fullText);
                }
            });
        }
        holder.partsHost.setMessage(message);
        holder.partsHost.setAvailableWidth(availableWidth);
        holder.partsHost.setWebMarkdownEnabled(webMarkdownEnabled);

        // parts 引擎独占容器：holder 从"流式(旧路径)"切到"完成(parts)"时，旧路径可能已往 contentHost
        // 塞过正文/组件子 View（含流式 mermaid/公式的 WebView）。这里让 contentHost 只保留 partsContainer
        // （其余子 View 摘掉，messageText/markdownView 由旧路径的 ensurePlainTextHostViews 在需要时再挂回），
        // 避免旧内容残留在 parts 树旁。清空前先深度 release，否则旧 WebView 的原生渲染器进程会随滚动一直泄漏。
        //
        // **只在"换了消息 / 这条还没被接管过"时做**：流式每 tick 都重进本方法，若每次都释放清空，
        // 上一个 tick 建好的 WebView/图表/播放器就白建了，TurnPartsHost 的按 id 复用随之失效。
        final boolean remountContainer = !holder.partsTookOver
                || message.id == null
                || !message.id.equals(holder.partsTakenOverForId);
        if (remountContainer) {
            if (holder.partsContainer == null) {
                holder.partsContainer = new LinearLayout(ctx);
                holder.partsContainer.setOrientation(LinearLayout.VERTICAL);
                holder.partsContainer.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (holder.partsContainer.getParent() instanceof ViewGroup) {
                ((ViewGroup) holder.partsContainer.getParent()).removeView(holder.partsContainer);
            }
            releaseAndClearComponents(holder.contentHost);
            holder.contentHost.addView(holder.partsContainer);
            // 组件容器也清空：parts 模式不再走 bindComponents，残留的是上一轮旧渲染（同样先 release）。
            releaseAndClearComponents(holder.componentContainer);
            // 作废组件缓存：接管期间不写 componentContainer，但缓存字段还留着上一条消息的指纹，
            // 不清会让"关掉 flag 后回 legacy"的那次绑定误判成"组件未变"而早退 → 产物凭空消失。
            holder.boundComponents = null;
            holder.boundComponentsFingerprint = 0;
            holder.partsTakenOverForId = message.id;
        }
        // 全局思考行 / 工具卡让路：parts 自己渲 reasoning part 与工具组卡，留着会双份。
        // 每 tick 都要重申：legacy 的思考行由 PAYLOAD_THINKING_UPDATE 刷新，只在首次重装时隐藏不够。
        if (holder.thinkSection != null) holder.thinkSection.setVisibility(View.GONE);
        if (holder.toolCard != null) holder.toolCard.setVisibility(View.GONE);
        holder.partsTookOver = true;

        if (holder.partsBinder == null) {
            holder.partsBinder = new com.oilquiz.app.ai.chat.parts.PartsTurnBinder(
                    holder.partsContainer, partsRegistry, holder.partsHost);
        }
        holder.partsBinder.bind(partsTurn);
        return true;
    }

    /**
     * 这条消息是否还在生成中（流式档）。与旧路径判据同源
     * （{@code ensurePlainTextHostViews} 那边也是 GENERATING/IN_PROGRESS 两个态算流式）。
     */
    private static boolean isStreamingMessage(ChatMessage message) {
        if (message == null) {
            return false;
        }
        return message.status == ChatMessage.MessageStatus.GENERATING
                || message.status == ChatMessage.MessageStatus.IN_PROGRESS;
    }

    private void bindMessageContent(AIMessageViewHolder holder, ChatMessage message, int availableWidth) {
        Context ctx = holder.itemView.getContext();
        // canonical parts → 兼容投影：UI 在每次完成态绑定前都用 parts 重算
        // content/bounds/toolIds，避免历史里旧的投影字段与 AssistantTurn 漂移。
        if (message.isCompleted() && message.assistantTurn != null) {
            message.assistantTurn.applyToMessage(message);
        }
        // parts 渲染器接管（P3b）。flag=false → 整个分支跳过，下面旧路径字节级不变。
        //
        // 完成态**不再要求 message.assistantTurn 非空**（P6，2026-10-24）。那个字段从来不是渲染输入——
        // 下面的构建每次绑定都从 content/roundSnapshots/components 现建（`fromMessageForParts`），
        // 落盘 turn 只当"生产者跑过了"的令牌和 applyToMessage 的投影源。以令牌当闸门就出现第二类
        // 倒退：停止/出错那六处终态（onGenerationStopped / onGenerationError / handleGenerationError
        // 及各自的 uiDetached 分支）都没人补 turn，流式期明明在被接管，一落到终态就判回 legacy，
        // 用户看到的是"停止那一刻整条重排"。逐个终态去补 turn 等于再开一个写者，所以把闸门修到
        // 真值本身：能构建出可渲 part 就接管，构建不出来（空 parts）照旧回落。
        //
        // 流式（GENERATING/IN_PROGRESS）另吃一个子开关 `parts_streaming`：它换了正文的顺序真值
        // （按 contentRoundBounds 切片而非按 roundSnapshots 重建），出问题时要能单独退回
        // "只有完成态进 parts"，而不必连整条 parts 一起关。
        // 这里**不能**调 applyToMessage：上面那段的 isCompleted 门控是故意的——流式期
        // msg.content / contentRoundBounds 正是 Activity 侧 flush 的写入目标，反向投影会打断切片。
        if (com.oilquiz.app.ai.chat.parts.PartsRendererFlag.enabled(ctx)
                && (message.isCompleted()
                    || (com.oilquiz.app.ai.chat.parts.PartsStreamingFlag.enabled(ctx)
                        && isStreamingMessage(message)))) {
            if (bindWithParts(holder, message, availableWidth, ctx)) {
                return;
            }
        }
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
                    .containsMermaid(message.content);
            boolean hasFenced = !hasMermaidMath && hasFencedCode(message.content);
            if (holder.contentHost != null && hasMermaidMath) {
                renderMermaidMath(holder, message, availableWidth, ctx);
            } else if (webMarkdownEnabled && holder.markdownView != null && holder.contentHost != null) {
                // 纯文本 + 代码围栏统一走 WebView：commonmark→HTML，混排裸 HTML/表格交浏览器引擎，
                // 代码块由 highlight.js 上色；规避 Markwon Span 在脏/混排内容下的错位与逐 token 重排闪烁。
                // （mermaid/数学仍在上一分支走原生，不进这里）
                ensurePlainTextHostViews(holder);
                holder.messageText.setVisibility(View.GONE);
                holder.markdownView.setVisibility(View.VISIBLE);
                holder.markdownView.setNight(isNightMode(ctx));
                holder.markdownView.setThemeColors(buildMarkdownThemeVars(ctx));
                boolean streaming = message.status == ChatMessage.MessageStatus.GENERATING
                        || message.status == ChatMessage.MessageStatus.IN_PROGRESS;
                holder.markdownView.setContent(message.content, streaming);
            } else if (holder.contentHost != null && hasFenced) {
                // 代码围栏 → CodeCardView 组件渲染（web 渲染关闭时的原生高亮路径）
                renderWithCodeCards(holder, message, availableWidth, ctx);
            } else {
                // 纯文本模式（Markwon）：宿主保留 messageText；关掉 web 渲染时 markdownView 隐藏
                ensurePlainTextHostViews(holder);
                if (holder.markdownView != null) holder.markdownView.setVisibility(View.GONE);
                holder.messageText.setVisibility(View.VISIBLE);
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
            // staleCache：上一轮的组件段 View。指纹一致才复用，否则本轮结尾统一释放
            List<View> staleCache = holder.componentSegmentViews;
            boolean canReuse = newFp.equals(holder.componentSegmentsFingerprint)
                    && staleCache != null;
            List<View> oldCache = canReuse ? staleCache
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
                        // 渲染失败降级占位（统一错误态卡片，不显示组件源码）
                        View fallback = ComponentRegistry.getInstance()
                                .renderFallback(ctx, seg.component);
                        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                        flp.topMargin = dpToPx(8, ctx);
                        holder.contentHost.addView(fallback, flp);
                        newCache.add(fallback);
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
            releaseStaleSegmentViews(staleCache, newCache);
            holder.componentSegmentsFingerprint = newFp;
            holder.componentSegmentViews = newCache;
        }
        // 消息级组件（Agent 工具卡片、工具 withComponent 组件）渲染到 component_container，
        // 执行中与完成后的渲染路径保持一致
        bindComponents(holder, message);
    }

    /**
     * 释放并清空组件容器（Track B0-1 生命周期）。
     *
     * <p>RecyclerView 复用 ViewHolder 时这里重建组件 View，旧 View 被摘下来就再没人引用，
     * 但 WebView 的原生内存/渲染器进程不会随 Java GC 立刻回收——长会话滚动会一直堆。
     * 所以在 removeAllViews 前显式 release 一次（只处理确定不复用的容器；
     * 组件段指纹缓存走 {@link #releaseStaleSegmentViews}）。
     */
    private static void releaseAndClearComponents(android.view.ViewGroup container) {
        if (container == null) return;
        for (int i = 0; i < container.getChildCount(); i++) {
            com.oilquiz.app.ai.chat.component.ComponentLifecycle.release(container.getChildAt(i));
        }
        container.removeAllViews();
    }

    /**
     * 释放上一轮组件段缓存里本次没被复用的 View（流式重建时 WebView 的确定性销毁点）。
     *
     * <p>必须在 contentHost.removeAllViews() 之后、缓存字段被新列表替换之前调用。
     */
    private static void releaseStaleSegmentViews(List<View> staleCache, List<View> newCache) {
        if (staleCache == null || staleCache.isEmpty()) return;
        for (View old : staleCache) {
            if (old == null || (newCache != null && newCache.contains(old))) continue;
            com.oilquiz.app.ai.chat.component.ComponentLifecycle.release(old);
        }
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
                // 局部刷新：只通知该组范围内的 item，避免全量 notifyDataSetChanged
                int headerPos = holder.getBindingAdapterPosition();
                if (headerPos != RecyclerView.NO_POSITION) {
                    int groupEnd = headerPos;
                    String gid = message.agentGroupId;
                    while (groupEnd + 1 < messages.size()
                            && gid != null && gid.equals(messages.get(groupEnd + 1).agentGroupId)) {
                        groupEnd++;
                    }
                    notifyItemRangeChanged(headerPos, groupEnd - headerPos + 1);
                }
            });
            return;
        }

        // 正常系统消息
        holder.itemView.setOnClickListener(null);
        // SYSTEM-CONTENT-NULL(2026-10-09)：createSystemMessage 系三个工厂方法都不校验 content，
        // 而这里（以及下面每一处 indexOf）都直接解引用 message.content，
        // new SpannableStringBuilder(null) 会抛 NPE。AI 消息路径一直有判空，这里补齐。
        if (message.content == null) {
            holder.messageText.setText("");
            return;
        }
        SpannableStringBuilder spannable = new SpannableStringBuilder(message.content);

        // SPAN-LEN(2026-10-09)：下面三处可点击 span 的长度此前写死为 2 / 2 / 4，
        // 那是按中文资源「帮助」「教程」「帮助图标」的字符数硬编码的，换语言即错位
        // （span 覆盖不到词尾或越界）。改为按实际匹配串的长度计算。
        final String helpKey = SmartQuizApplication.getAppContext().getString(R.string.h_92e3a830);
        final String guideKey = SmartQuizApplication.getAppContext().getString(R.string.h_b7824d5c);
        final String helpIconKey = SmartQuizApplication.getAppContext().getString(R.string.h_267bf2f9);

        
        // 查找并设置可点击的"帮助"文本
        int helpIndex = message.content.indexOf(helpKey);
        while (helpIndex >= 0) {
            int endIndex = helpIndex + helpKey.length();
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
            helpIndex = message.content.indexOf(helpKey, endIndex);
        }
        
        // 查找并设置可点击的"教程"文本
        int guideIndex = message.content.indexOf(guideKey);
        while (guideIndex >= 0) {
            int endIndex = guideIndex + guideKey.length();
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
            guideIndex = message.content.indexOf(guideKey, endIndex);
        }
        
        // 查找并设置可点击的帮助图标提示
        int iconIndex = message.content.indexOf(helpIconKey);
        while (iconIndex >= 0) {
            int endIndex = iconIndex + helpIconKey.length();
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
            iconIndex = message.content.indexOf(helpIconKey, endIndex);
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
                // 兼容旧 TOOL_RESULT 消息：同样只渲染预览（C0-T2 同一口径），
                // 否则历史里的几十 KB 结果仍会整段进 TextView
                ToolResultText.Preview pv = ToolResultText.preview(message.content);
                CharSequence shown = pv.truncated
                        ? appendViewAllSpan(pv.text, pv.totalChars,
                                SmartQuizApplication.getAppContext()
                                        .getString(R.string.tool_result_legacy_title),
                                message.content)
                        : pv.text;
                holder.toolResult.setText(shown);
                holder.toolResult.setMovementMethod(LinkMovementMethod.getInstance());
            }
            if (holder.toolProgress != null) holder.toolProgress.setVisibility(View.GONE);
            return;
        }

        holder.toolIcon.setText(info.toolIcon);
        holder.toolName.setText(info.toolDisplayName);
        holder.toolStatus.setText(info.getStatusText());
        // C0-T4：状态文字去掉 emoji 后，靠颜色区分完成/失败/进行中
        holder.toolStatus.setTextColor(toolStatusColor(holder.toolStatus.getContext(), info.status));

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
     * 工具状态文字配色（C0-T4）：完成=成功色、失败=错误色、等待/执行中=次要色。
     * 只取语义色资源，不在代码里写十六进制。
     */
    private static int toolStatusColor(Context context,
                                       ChatMessage.ToolCallInfo.ToolCallStatus status) {
        if (context == null || status == null) {
            return ThemeColors.get(context, R.color.text_secondary);
        }
        switch (status) {
            case COMPLETED:
                return ThemeColors.get(context, R.color.success);
            case FAILED:
                return ThemeColors.get(context, R.color.error);
            case PENDING:
            case RUNNING:
            default:
                return ThemeColors.get(context, R.color.text_secondary);
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
        // 参数区域：优先走 json_viewer 组件（结构化展示），失败回退到 toolParams TextView
        if (info.parameters != null && !info.parameters.isEmpty() && holder.toolParamsContainer != null) {
            holder.toolParamsContainer.setVisibility(View.VISIBLE);
            if (!renderToolParamsAsJsonViewer(holder, info)) {
                holder.toolParams.setText(info.parameters);
                holder.toolParams.setVisibility(View.VISIBLE);
            }
            // 参数编辑重跑按钮（tag=tool_edit_slot）
            attachToolEditButton(holder, message, info);
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
            // 失败态：结果区顶部加"重试该工具"按钮（tag=tool_retry_slot 避免重复挂载）
            attachToolRetryButton(holder, message, info);
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

    /**
     * 用 json_viewer 组件渲染工具参数（结构化树形展示 + 复制）。
     * 成功时把 view 挂到 toolParamsContainer（tag=tool_params_slot）并隐藏原 toolParams TextView；
     * 失败返回 false 让调用方回退到 setText。
     */
    private boolean renderToolParamsAsJsonViewer(ToolCallViewHolder holder, ChatMessage.ToolCallInfo info) {
        if (holder.toolParamsContainer == null || info.parameters == null) return false;
        if (!(holder.toolParamsContainer instanceof android.view.ViewGroup)) return false;
        android.view.ViewGroup container = (android.view.ViewGroup) holder.toolParamsContainer;
        try {
            org.json.JSONObject props = new org.json.JSONObject()
                    .put("json", info.parameters)
                    .put("title", "参数");
            ComponentData data = new ComponentData("json_viewer", props);
            View v = ComponentRegistry.getInstance().render(container.getContext(), data);
            if (v == null) return false;
            View old = container.findViewWithTag("tool_params_slot");
            if (old != null) container.removeView(old);
            v.setTag("tool_params_slot");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dpToPx(4, v.getContext());
            container.addView(v, lp);
            if (holder.toolParams != null) holder.toolParams.setVisibility(View.GONE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 工具失败时在结果容器顶部挂"重试该工具"按钮；非失败态清理旧按钮。
     * 点击触发 Action.retryToolCall + dispatchChatAction 通知 Agent。
     */
    private void attachToolRetryButton(ToolCallViewHolder holder, ChatMessage message,
                                        ChatMessage.ToolCallInfo info) {
        if (holder.toolResultContainer == null) return;
        if (!(holder.toolResultContainer instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup container = (android.view.ViewGroup) holder.toolResultContainer;
        View existing = container.findViewWithTag("tool_retry_slot");
        boolean failed = info.status == ChatMessage.ToolCallInfo.ToolCallStatus.FAILED;
        if (!failed) {
            if (existing != null) container.removeView(existing);
            return;
        }
        if (existing != null) return; // 已挂载
        android.content.Context ctx = container.getContext();
        TextView btn = new TextView(ctx);
        btn.setText("🔄 重试该工具");
        btn.setTextSize(11f);
        btn.setTextColor(0xFFDC2626);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(10, ctx));
        bg.setStroke(dpToPx(1, ctx), 0x66DC2626);
        bg.setColor(0x1ADC2626);
        btn.setBackground(bg);
        btn.setPadding(dpToPx(10, ctx), dpToPx(4, ctx), dpToPx(10, ctx), dpToPx(4, ctx));
        btn.setClickable(true);
        btn.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(6, ctx);
        lp.bottomMargin = dpToPx(4, ctx);
        btn.setLayoutParams(lp);
        btn.setTag("tool_retry_slot");
        final String msgId = message.id;
        final String tcId = info.toolCallId != null ? info.toolCallId : info.toolName;
        btn.setOnClickListener(v -> {
            if (actionClickListener != null) {
                actionClickListener.onAction(ChatMessage.Action.retryToolCall(msgId, tcId));
            }
            ComponentActions.dispatchChatAction(v.getContext(), "retry_tool:" + tcId);
        });
        // 插到结果 TextView 之前
        int idx = holder.toolResult != null ? container.indexOfChild(holder.toolResult) : 0;
        if (idx < 0) idx = container.getChildCount();
        container.addView(btn, idx, lp);
    }

    /**
     * 在参数容器底部挂"✏️ 编辑参数并重跑"按钮。点击弹输入框预填当前参数 JSON，
     * 确认后触发 Action.editToolArgs + dispatchChatAction 通知 Agent 端。
     */
    private void attachToolEditButton(ToolCallViewHolder holder, ChatMessage message,
                                       ChatMessage.ToolCallInfo info) {
        if (holder.toolParamsContainer == null) return;
        if (!(holder.toolParamsContainer instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup container = (android.view.ViewGroup) holder.toolParamsContainer;
        View existing = container.findViewWithTag("tool_edit_slot");
        if (existing != null) return; // 已挂载
        android.content.Context ctx = container.getContext();
        TextView btn = new TextView(ctx);
        btn.setText("✏️ 编辑参数并重跑");
        btn.setTextSize(11f);
        btn.setTextColor(0xFF1565C0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(10, ctx));
        bg.setStroke(dpToPx(1, ctx), 0x661565C0);
        bg.setColor(0x141565C0);
        btn.setBackground(bg);
        btn.setPadding(dpToPx(10, ctx), dpToPx(4, ctx), dpToPx(10, ctx), dpToPx(4, ctx));
        btn.setClickable(true);
        btn.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(6, ctx);
        btn.setLayoutParams(lp);
        btn.setTag("tool_edit_slot");
        final String msgId = message.id;
        final String tcId = info.toolCallId != null ? info.toolCallId : info.toolName;
        final String currentArgs = info.parameters != null ? info.parameters : "";
        btn.setOnClickListener(v -> {
            final android.widget.EditText input = new android.widget.EditText(v.getContext());
            input.setText(currentArgs);
            input.setMinLines(3);
            input.setTypeface(android.graphics.Typeface.MONOSPACE);
            input.setTextSize(12f);
            android.widget.ScrollView sv = new android.widget.ScrollView(v.getContext());
            sv.addView(input);
            new android.app.AlertDialog.Builder(v.getContext())
                    .setTitle("编辑工具参数 (JSON)")
                    .setView(sv)
                    .setPositiveButton("重跑", (d, w) -> {
                        String newArgs = input.getText().toString();
                        if (actionClickListener != null) {
                            actionClickListener.onAction(
                                    ChatMessage.Action.editToolArgs(msgId, tcId, newArgs));
                        }
                        ComponentActions.dispatchChatAction(v.getContext(),
                                "edit_args:" + tcId);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        container.addView(btn);
    }

    /** 应用参数区域展开/折叠状态 */
    private void applyParamsExpansion(ToolCallViewHolder holder, ChatMessage.ToolCallInfo info) {
        if (holder.toolParams == null) return;
        boolean expanded = info.paramsExpanded;
        holder.toolParams.setVisibility(expanded ? View.VISIBLE : View.GONE);
        // 同步 json_viewer 组件槽位显隐（若已挂载则优先展示它）
        if (holder.toolParamsContainer != null) {
            View slot = holder.toolParamsContainer.findViewWithTag("tool_params_slot");
            if (slot != null) {
                slot.setVisibility(expanded ? View.VISIBLE : View.GONE);
                // 组件已挂载时把 TextView 保持 GONE，避免重复展示
                holder.toolParams.setVisibility(View.GONE);
            }
            // 编辑按钮跟随展开态
            View editBtn = holder.toolParamsContainer.findViewWithTag("tool_edit_slot");
            if (editBtn != null) editBtn.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (holder.toolParamsArrow != null) holder.toolParamsArrow.setText(expanded ? "▼" : "▶");
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

    /**
     * 工具结果区的显示文本：预览 +「查看全部」+ 可选「AI 深度解读」chip。
     * 不修改任何 XML 布局：通过 Spannable 在文本末尾渲染可点击区域。
     *
     * <p>C0-T2：以前把 {@code formatForUi} 的整段结果直接进 TextView，一次
     * {@code python_execute} 几十 KB 输出就让这条 item 的测量/绘制/回收全部变贵
     * （聊天流滚动卡顿的主因之一）。现在只显示 {@link ToolResultText} 的预览，
     * 全文仍在 {@code info.result} 与历史里，点「查看全部」才一次性弹层展示。
     *
     * <p>chip 追加条件：状态为 COMPLETED 且存在 rawResult、尚未触发过解读、且 canInterpret。
     */
    private CharSequence buildResultWithInterpretChip(ChatMessage message, ChatMessage.ToolCallInfo info, boolean applyMarkdown) {
        final String fullText = buildToolResultFullText(info);
        final ToolResultText.Preview pv = ToolResultText.preview(fullText);
        CharSequence mainContent = applyMarkdown ? formatMessageContent(pv.text) : pv.text;

        try {
            if (pv.truncated) {
                String title = info.toolDisplayName != null && !info.toolDisplayName.isEmpty()
                        ? info.toolDisplayName : info.toolName;
                mainContent = appendViewAllSpan(mainContent, pv.totalChars, title, fullText);
            }
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
            SpannableStringBuilder ssb = new SpannableStringBuilder(
                    mainContent == null ? "" : mainContent);
            ssb.append("\n\n");
            final int chipStart = ssb.length();
            ssb.append(SmartQuizApplication.getAppContext()
                    .getString(R.string.tool_result_ai_chip));
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

    /**
     * 把「AI 解读摘要 + 原始结果」拼成一条完整文本（未截断），
     * 供预览与「查看全部」弹层共用同一份内容，避免两处口径漂移。
     */
    private String buildToolResultFullText(ChatMessage.ToolCallInfo info) {
        String raw = info.result == null ? "" : info.result;
        String interpreted = info.interpretedMessage;
        if (interpreted == null || interpreted.isEmpty()) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(interpreted);
        // 模板兜底时解读与原文一致，短文本不重复展示
        boolean duplicateRaw = interpreted.equals(raw) && raw.length() < 800;
        if (!duplicateRaw && !raw.isEmpty()) {
            sb.append(SmartQuizApplication.getAppContext()
                    .getString(R.string.tool_result_raw_section));
            sb.append(raw);
        }
        return sb.toString();
    }

    /** 预览末尾追加「查看全部（N 字）」点击区 */
    private CharSequence appendViewAllSpan(CharSequence content, int totalChars,
                                          String title, String fullText) {
        SpannableStringBuilder ssb = new SpannableStringBuilder(content == null ? "" : content);
        ssb.append("\n\n");
        final int start = ssb.length();
        ssb.append(SmartQuizApplication.getAppContext()
                .getString(R.string.tool_result_view_all, totalChars));
        final int end = ssb.length();
        ssb.setSpan(new ForegroundColorSpan(
                ThemeColors.attr(SmartQuizApplication.getAppContext(), R.attr.colorPrimary)),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new ClickableSpan() {
            @Override
            public void onClick(@NonNull android.view.View widget) {
                showFullToolResult(widget.getContext(), title, fullText);
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ssb;
    }

    /**
     * 完整结果弹层：原样展示（不再截断、不做 Markdown），可复制。
     * 用户主动点开才构建大 TextView，这里允许一次性付这笔渲染成本。
     */
    private void showFullToolResult(Context context, String title, String fullText) {
        if (context == null || fullText == null) return;
        try {
            android.widget.TextView tv = new android.widget.TextView(context);
            tv.setText(fullText);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
            tv.setTextIsSelectable(true);
            tv.setPadding(dpToPx(16, context), dpToPx(12, context),
                    dpToPx(16, context), dpToPx(12, context));
            android.widget.ScrollView scroll = new android.widget.ScrollView(context);
            scroll.addView(tv);
            final Context uiContext = context;
            new androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.tool_result_full_title,
                            title == null ? "" : title))
                    .setView(scroll)
                    .setPositiveButton(R.string.chat_copy, (dialog, which) -> {
                        try {
                            copyToClipboard(uiContext, fullText);
                        } catch (Throwable ignored) {
                        }
                    })
                    .setNegativeButton(R.string.close, null)
                    .show();
        } catch (Throwable t) {
            // 非 Activity 上下文（弹窗无 window token）等情况：不影响工具卡本身渲染
            android.util.Log.w("ChatAdapter", "完整结果弹层打开失败: " + t.getMessage());
        }
    }

    /**
     * 把 hex 色（如 "#8B5CF6"）应用到 TextView 的圆角胶囊背景 + 内边距。
     * 用于 AgentStepInfo.getStepColor() 视觉化：10 种步骤类型各有专属色。
     */
    private void applyStepColor(TextView tv, String hexColor) {
        if (tv == null || hexColor == null || hexColor.isEmpty()) return;
        int color;
        try {
            color = android.graphics.Color.parseColor(hexColor);
        } catch (IllegalArgumentException e) {
            return;
        }
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        // 半透明底 + 主色描边，避免图标看不清
        bg.setColor((color & 0x00FFFFFF) | 0x33000000);
        bg.setStroke(dpToPx(1, tv.getContext()), color);
        tv.setBackground(bg);
        int pad = dpToPx(4, tv.getContext());
        tv.setPadding(pad, pad, pad, pad);
        tv.setGravity(android.view.Gravity.CENTER);
    }

    private void bindAgentStepMessage(AgentStepViewHolder holder, ChatMessage message) {
        ChatMessage.AgentStepInfo stepInfo = message.agentStepInfo;
        if (stepInfo == null) return;

        // 设置步骤图标
        holder.stepIcon.setText(stepInfo.getStepIcon());
        // 步骤类型颜色应用：图标背景色 + 圆角胶囊
        applyStepColor(holder.stepIcon, stepInfo.getStepColor());

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
                // 同步通知 Agent 端：用户点击了"按建议重试"
                ComponentActions.dispatchChatAction(v.getContext(), "retry_suggested:" + message.id);
            });
        } else {
            holder.btnRetrySuggestion.setVisibility(View.GONE);
        }

        // 评分 progress_ring + suggestions 表格挂在 reflectionImprovements 之后
        attachReflectionExtras(holder, message, reflection);
    }

    /**
     * 反思附加视图：
     *  - score (0-100) 用 progress_ring 可视化
     *  - suggestions 用 list_card 组件展示（若为分点文本）
     * 挂载在 reflectionImprovements 之后（tag=reflection_extras_slot），复用时清理。
     */
    private void attachReflectionExtras(AgentReflectionViewHolder holder, ChatMessage message,
                                         ChatMessage.AgentReflectionInfo reflection) {
        android.view.View anchor = holder.reflectionImprovements != null
                ? holder.reflectionImprovements : holder.reflectionAnalysis;
        if (anchor == null || !(anchor.getParent() instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup parent = (android.view.ViewGroup) anchor.getParent();
        View old = parent.findViewWithTag("reflection_extras_slot");
        if (old != null) parent.removeView(old);
        if (reflection.score <= 0 && (reflection.suggestions == null || reflection.suggestions.isEmpty())) {
            return;
        }
        try {
            android.widget.LinearLayout slot = new android.widget.LinearLayout(parent.getContext());
            slot.setOrientation(android.widget.LinearLayout.VERTICAL);
            slot.setTag("reflection_extras_slot");
            if (reflection.score > 0) {
                org.json.JSONObject ringProps = new org.json.JSONObject()
                        .put("value", reflection.score)
                        .put("max", 100)
                        .put("label", "评分 " + reflection.score + "/100");
                View ring = ComponentRegistry.getInstance().render(parent.getContext(),
                        new ComponentData("progress_ring", ringProps));
                if (ring != null) {
                    slot.addView(ring, new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
                }
            }
            if (reflection.suggestions != null && !reflection.suggestions.isEmpty()) {
                org.json.JSONArray items = new org.json.JSONArray();
                for (String line : reflection.suggestions.split("\\n+")) {
                    String t = line.trim();
                    if (!t.isEmpty()) items.put(t);
                }
                if (items.length() > 0) {
                    org.json.JSONObject listProps = new org.json.JSONObject()
                            .put("title", "改进建议")
                            .put("items", items);
                    View list = ComponentRegistry.getInstance().render(parent.getContext(),
                            new ComponentData("list_card", listProps));
                    if (list != null) {
                        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                        lp.topMargin = dpToPx(6, parent.getContext());
                        slot.addView(list, lp);
                    }
                }
            }
            int idx = parent.indexOfChild(anchor);
            parent.addView(slot, idx + 1);
        } catch (Throwable ignored) {}
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

    /**
     * 剥离组件标记（```component:xxx {...}```）和 Mermaid/Math 块标记，
     * 返回用户可读的纯文本/Markdown。用于复制和分享。
     */
    private static String stripComponentMarkersForExport(String content) {
        if (content == null || content.isEmpty()) return "";
        // 去除 ```component:... ...``` 块（含内部 JSON）
        String result = content.replaceAll("(?s)```component:\\w+\\s*\\n.*?\\n```", "");
        // 去除 ```mermaid ...``` 块
        result = result.replaceAll("(?s)```mermaid\\s*\\n.*?\\n```", "[图表]\n");
        // 去除 $$..$$ 和 \[..\] 数学公式块
        result = result.replaceAll("(?s)\\$\\$.*?\\$\\$", "[公式]\n");
        // 压缩多余空行
        result = result.replaceAll("\\n{3,}", "\n\n").trim();
        return result;
    }

    private void shareText(Context context, String text) {
        String exportText = stripComponentMarkersForExport(text);
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, exportText);
        context.startActivity(Intent.createChooser(shareIntent, "分享消息"));
    }

    public void setOnAttachmentClickListener(MessageAttachmentAdapter.OnAttachmentClickListener listener) {
        this.attachmentClickListener = listener;
    }

    /**
     * 绑定用户消息中的附件（图片预览等）
     */
    /**
     * 尝试用 ImageGridCardView 组件渲染多张图片（AI 消息专用）。成功时把 view 挂到
     * attachmentsRecycler 父容器上（tag=image_grid_slot），返回 view；失败返回 null
     * （回退到 RecyclerView 路径）。让 Agent 生成的 image_grid 与用户消息附件复用同一渲染。
     */
    private View tryRenderImageGrid(AIMessageViewHolder holder, ChatMessage message) {
        try {
            org.json.JSONArray images = new org.json.JSONArray();
            for (ChatMessage.Attachment a : message.attachments) {
                if (a == null) continue;
                org.json.JSONObject item = new org.json.JSONObject();
                item.put("url", a.thumbnailPath != null && !a.thumbnailPath.isEmpty()
                        ? a.thumbnailPath : a.localFilePath != null ? a.localFilePath : a.url);
                if (a.name != null) item.put("name", a.name);
                images.put(item);
            }
            org.json.JSONObject props = new org.json.JSONObject()
                    .put("images", images)
                    .put("columns", message.attachments.size() > 4 ? 2 : Math.min(2, message.attachments.size()));
            ComponentData data = new ComponentData("image_grid", props);
            View v = ComponentRegistry.getInstance().render(
                    holder.attachmentsRecycler.getContext(), data);
            if (v == null) return null;
            android.view.ViewGroup parent = (android.view.ViewGroup) holder.attachmentsRecycler.getParent();
            if (parent == null) return null;
            View old = parent.findViewWithTag("image_grid_slot");
            if (old != null) parent.removeView(old);
            v.setTag("image_grid_slot");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dpToPx(3, v.getContext());
            int idx = parent.indexOfChild(holder.attachmentsRecycler);
            parent.addView(v, idx + 1, lp);
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 清理 AI holder 内可能挂载的 image_grid 组件槽位（复用/路径切换时） */
    private void clearImageGridSlot(AIMessageViewHolder holder) {
        if (holder == null || holder.attachmentsRecycler == null) return;
        android.view.ViewGroup parent = (android.view.ViewGroup) holder.attachmentsRecycler.getParent();
        if (parent == null) return;
        View old = parent.findViewWithTag("image_grid_slot");
        if (old != null) parent.removeView(old);
    }

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
                holder.attachmentsRecycler.setAdapter(attachmentAdapter);
            }
            // 监听器每绑必设：原来只在新建时设，复用 holder 拿到的是上一条消息留下的 adapter，
            // 若监听器是在那之后才装上（Activity 重建/延迟装配），这批气泡的附件就永远点不动。
            attachmentAdapter.setOnAttachmentClickListener(attachmentClickListener);

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
            // 优先路径：多张图片走 ImageGridCardView 组件，直接添加到 contentHost
            // 单一/混合附件仍走 MessageAttachmentAdapter（保留点击预览通路）
            boolean allImages = true;
            for (ChatMessage.Attachment a : message.attachments) {
                if (a == null || !a.isImage()) { allImages = false; break; }
            }
            if (allImages && message.attachments.size() >= 2) {
                View rendered = tryRenderImageGrid(holder, message);
                if (rendered != null) {
                    holder.attachmentsRecycler.setVisibility(View.GONE);
                    if (holder.attachmentsRecycler.getAdapter() != null) {
                        holder.attachmentsRecycler.setAdapter(null);
                    }
                    return;
                }
            }
            holder.attachmentsRecycler.setVisibility(View.VISIBLE);
            // 清理可能残留的 image_grid 组件（复用 holder 时）
            clearImageGridSlot(holder);

            // 根据附件类型选择 LayoutManager，ViewHolder 复用时需检查是否需要切换
            // （与上面 allImages 判定同一口径：显式防 null，否则列表里一个 null 就 NPE）
            boolean hasMultipleImages = true;
            int imageCount = 0;
            for (ChatMessage.Attachment a : message.attachments) {
                if (a == null || !a.isImage()) { hasMultipleImages = false; break; }
                imageCount++;
            }
            hasMultipleImages = hasMultipleImages && imageCount > 1;
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
                // ATTACH-LM-FALLBACK(2026-10-09)：AI 气泡的 attachmentsRecycler 是代码创建的，
                // 没有 XML 里配置的 layoutManager。上述两个分支只在"需要切换类型"时设置，
                // 单张图片或非全图片的附件集合两个条件都不满足 → 一直无 LayoutManager，
                // RecyclerView 不会布局任何子项，附件区空白。
                // 用户消息侧的同名方法本就有这一兜底（见 bindUserAttachments），此处补齐。
                androidx.recyclerview.widget.LinearLayoutManager linearLayout =
                    new androidx.recyclerview.widget.LinearLayoutManager(holder.itemView.getContext());
                holder.attachmentsRecycler.setLayoutManager(linearLayout);
            }

            MessageAttachmentAdapter attachmentAdapter = null;
            if (holder.attachmentsRecycler.getAdapter() instanceof MessageAttachmentAdapter) {
                attachmentAdapter = (MessageAttachmentAdapter) holder.attachmentsRecycler.getAdapter();
            } else {
                attachmentAdapter = new MessageAttachmentAdapter();
                holder.attachmentsRecycler.setAdapter(attachmentAdapter);
            }
            // 监听器每绑必设：原来只在新建时设，复用 holder 拿到的是上一条消息留下的 adapter，
            // 若监听器是在那之后才装上（Activity 重建/延迟装配），这批气泡的附件就永远点不动。
            attachmentAdapter.setOnAttachmentClickListener(attachmentClickListener);

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
     * 实现已抽到 {@link ThinkingPreview#stripTagMarkers}（思考折叠头摘要要用同一份）。
     */
    private static String stripThinkingTagMarkers(String content) {
        return ThinkingPreview.stripTagMarkers(content);
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

    /**
     * 列表 position 的稳定键（消息 id），供 {@code ScrollAnchorKeeper} 判断
     * "同 position 是否还是同一条消息"（切会话/清空时 position 可能不变但内容全换了）。
     * 越界或空消息返回 null。
     */
    public String getItemKeyAt(int position) {
        if (position < 0 || position >= messages.size()) return null;
        ChatMessage m = messages.get(position);
        return m == null ? null : m.id;
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
        /** 日期分隔行（demo .day） */
        TextView dayLabel;

        UserMessageViewHolder(View itemView) {
            super(itemView);
            blockLabel = itemView.findViewById(R.id.block_label);
            messageText = itemView.findViewById(R.id.message_text);
            timestampText = itemView.findViewById(R.id.timestamp_text);
            statusIcon = itemView.findViewById(R.id.status_icon);
            statusText = itemView.findViewById(R.id.status_text);
            attachmentsRecycler = itemView.findViewById(R.id.attachments_recycler);
            dayLabel = itemView.findViewById(R.id.day_label);
        }
    }

    /**
     * 动态构建的 AI 消息根容器：持有全部子视图引用（替代 findViewById/布局文件 id）。
     * 仅用于 createAiMessageItem 动态路径；其直接子 View 是实际内容根。
     */
    static class DynamicAiMessageRoot extends LinearLayout {
        final TextView messageText;
        final LinearLayout contentHost;
        final LinearLayout componentContainer;
        final TextView thinkingLabel;
        final TextView thinkingContent;
        final View thinkingDivider;
        final TextView agentStatus;
        final TextView agentSummary;
        final TextView btnPause;
        final TextView btnStep;
        final TextView btnRewind;
        final LinearLayout agentControlBar;
        final TextView timestampText;
        final ImageView statusIcon;
        final TextView statusText;
        final InferenceProgressView inferenceProgressView;
        final androidx.recyclerview.widget.RecyclerView attachmentsRecycler;

        DynamicAiMessageRoot(Context ctx, TextView messageText,
                LinearLayout contentHost, LinearLayout componentContainer, TextView thinkingLabel,
                TextView thinkingContent, View thinkingDivider, TextView agentStatus,
                TextView agentSummary, TextView btnPause, TextView btnStep,
                TextView btnRewind, LinearLayout agentControlBar, TextView timestampText,
                ImageView statusIcon, TextView statusText,
                InferenceProgressView inferenceProgressView,
                androidx.recyclerview.widget.RecyclerView attachmentsRecycler) {
            super(ctx);
            this.messageText = messageText;
            this.contentHost = contentHost;
            this.componentContainer = componentContainer;
            this.thinkingLabel = thinkingLabel;
            this.thinkingContent = thinkingContent;
            this.thinkingDivider = thinkingDivider;
            this.agentStatus = agentStatus;
            this.agentSummary = agentSummary;
            this.btnPause = btnPause;
            this.btnStep = btnStep;
            this.btnRewind = btnRewind;
            this.agentControlBar = agentControlBar;
            this.timestampText = timestampText;
            this.statusIcon = statusIcon;
            this.statusText = statusText;
            this.inferenceProgressView = inferenceProgressView;
            this.attachmentsRecycler = attachmentsRecycler;
        }
    }

    static class AIMessageViewHolder extends RecyclerView.ViewHolder {
        TextView messageText;
        /** 纯文本气泡的 WebView 渲染器（webMarkdownEnabled 时接管正文，与 messageText 二选一显示） */
        MarkdownWebView markdownView;
        LinearLayout contentHost;
        LinearLayout componentContainer;
        // ---- parts 渲染器（P3b，flag 默认 false 时这三个恒为 null，下面旧路径原样走）----
        /** parts 渲染环境（复用 ChatAdapter.renderInto 等）；按 holder 惰性建，跨绑定复用。 */
        com.oilquiz.app.ai.chat.ChatPartsHost partsHost;
        /** parts 引擎独占的容器：contentHost 的一个子 View，旧渲染绝不往它里面加东西。 */
        LinearLayout partsContainer;
        /** parts→视图 增量绑定器，持有 TurnPartsHost 的复用状态。 */
        com.oilquiz.app.ai.chat.parts.PartsTurnBinder partsBinder;
        /** 当前绑定的消息（2026-09-14：思考轮独立折叠状态读写 / 轮次组件 id 锚点） */
        ChatMessage holderMessage;
        /** 尚未触发的"等布局完成再按真实宽度渲染"监听器。持有它才能在重绑/回收时摘除，
         *  避免过期回调把旧消息渲染进已被复用的视图。 */
        android.view.ViewTreeObserver.OnGlobalLayoutListener pendingLayoutListener;
        /** 已绑定的组件列表引用：与下面的内容指纹一起判断是否需要重建 */
        List<ComponentData> boundComponents;
        /** 已绑定组件列表的内容指纹。仅比较列表引用不够：生产方会就地 append 组件
         *  （引用不变、内容已变），只比引用会导致新组件永不显示。 */
        int boundComponentsFingerprint;
        /**
         * 本次绑定是否由 parts 引擎接管（P4 前置修复，2026-10-22）。
         *
         * <p>接管会自己渲组件与思考块，legacy 的两个并行写者必须让路，否则：
         * ① {@code bindComponents} 又把产物挂进 {@code componentContainer} → 与 parts 的 data part <b>双渲</b>；
         * ② 全局思考行/工具卡不隐藏 → 与 parts 的 reasoning part <b>双份</b>。
         * 每次 {@code bindAIMessage} 开头复位（延后绑定时序下不能残留上一条的值）。</p>
         */
        boolean partsTookOver;
        /**
         * 当前被 parts 接管的是<b>哪一条消息</b>（流式接管，2026-10-24）。
         *
         * <p>流式期间每次刷新都会重进 {@code bindWithParts}，而接管开头那段"释放并清空 contentHost /
         * componentContainer"是给<b>首次</b>接管用的（legacy 可能已经往里面塞过正文与组件）。
         * 同一条消息的第 N 个 tick 再走一遍就会把上一个 tick 建好的 WebView/图表/播放器连根释放，
         * {@code TurnPartsHost} 的视图复用随即失效——正是这次改造要消灭的抖动。所以容器只在
         * "换了消息"或"这条消息此前没被接管过"时重装。</p>
         */
        String partsTakenOverForId;
        /** 宿主模式（插入式组件）组件段 View 缓存：流式更新时复用，避免 WebView/图表/图片反复重建闪烁 */
        List<View> componentSegmentViews;
        /** 组件段指纹（type|props 序列）：与缓存 View 对应，指纹不变则复用 */
        String componentSegmentsFingerprint;
        /** 轮次组装（contentRoundBounds 工具轮次消息）结构指纹：T 正文段 + P 产物卡序列，
         *  结构未变时流式更新只刷正文段、不重建容器与产物卡（2026-10-07 本地 Agent 闪烁修复） */
        String roundStructureFingerprint;
        /** 与 roundStructureFingerprint 对应的已挂载子 View 数（增量路径校验用） */
        int roundRenderedChildren;
        /** Agent 汇总卡片组件挂载视图（MetricCardView 渲染结果）；null 表示走文本回退 */
        View agentSummarySlot;
        /**
         * 日期分隔行（demo .day）。与 markdownView 一样在 createAiMessageItem 里建好后
         * 直接挂 holder，不进 DynamicAiMessageRoot 构造器。
         */
        TextView dayLabel;
        /** 操作段整行（三图标 + 更多）；动作走 doCopy/doRegenerate/...，不依赖旧胶囊 */
        LinearLayout v2ActionRow;
        ImageView v2BtnCopy;
        ImageView v2BtnRegenerate;
        ImageView v2BtnMore;
        /**
         * 活动区行容器（已定稿思考轮 + 工具行）。
         * 顺序 = 第 1 行 … 第 N 行 → 实时思考行（thinkingLabel / thinkingContent）。
         */
        LinearLayout thinkingRowsHost;
        /** think-line 的箭头：整节展开态由它表示（ThinkLineBody 只跟正文显隐） */
        ImageView thinkingChevron;
        /** 独立工具卡（demo .tool-line）与它的明细容器；无工具时整卡 GONE */
        View toolCard;
        LinearLayout toolCardBody;
        /** 工具卡行指纹：工具数/状态/耗时/展开位不变就不重建明细 */
        String toolCardFp;
        /**
         * 思考行指纹（2026-10-21 起只管思考/规划/反思，工具已拆到 toolCard）。
         * 行种类/锚点/状态/耗时/展开位不变就不重建行容器，流式期间只刷 header 与实时正文。
         */
        String activityFp;
        /** Agent 汇总卡片的指纹缓存，字段变化才重渲染 */
        String agentSummaryFp;
        View thinkSection;
        TextView thinkingLabel;
        TextView thinkingContent;
        View thinkingDivider;
        TextView agentStatus;
        TextView agentSummary;
        TextView btnPause;
        TextView btnStep;
        TextView btnRewind;
        LinearLayout agentControlBar;
        TextView timestampText;
        ImageView statusIcon;
        TextView statusText;
        InferenceProgressView inferenceProgressView;
        androidx.recyclerview.widget.RecyclerView attachmentsRecycler;

        AIMessageViewHolder(View itemView) {
            super(itemView);
            // AI 消息完全动态构建（createAiMessageItem），itemView 必为 DynamicAiMessageRoot
            DynamicAiMessageRoot dr = (DynamicAiMessageRoot) itemView;
            this.messageText = dr.messageText;
            this.contentHost = dr.contentHost;
            this.componentContainer = dr.componentContainer;
            this.thinkingLabel = dr.thinkingLabel;
            this.thinkingContent = dr.thinkingContent;
            this.thinkingDivider = dr.thinkingDivider;
            this.agentStatus = dr.agentStatus;
            this.agentSummary = dr.agentSummary;
            this.btnPause = dr.btnPause;
            this.btnStep = dr.btnStep;
            this.btnRewind = dr.btnRewind;
            this.agentControlBar = dr.agentControlBar;
            this.timestampText = dr.timestampText;
            this.statusIcon = dr.statusIcon;
            this.statusText = dr.statusText;
            this.inferenceProgressView = dr.inferenceProgressView;
            this.attachmentsRecycler = dr.attachmentsRecycler;
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
        // 全量替换时重置过滤状态（外部调用表明数据源已完全更新）
        if (originalMessages != null) {
            originalMessages = null;
            currentQuery = "";
        }
        ChatMessageDiffCallback diffCallback = new ChatMessageDiffCallback(messages, newMessages);
        androidx.recyclerview.widget.DiffUtil.DiffResult diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(diffCallback);
        messages.clear();
        messages.addAll(newMessages);
        diffResult.dispatchUpdatesTo(this);
    }

    public void addMessage(ChatMessage message) {
        if (message == null) return;
        messages.add(message);
        // 过滤态下同步维护完整列表，clearFilter 后新消息不丢失
        if (originalMessages != null) {
            originalMessages.add(message);
        }
        notifyItemInserted(messages.size() - 1);
        scrollToPosition(messages.size() - 1);
    }

    public void removeMessage(String messageId) {
        int position = findMessagePosition(messageId);
        if (position >= 0) {
            ChatMessage removed = messages.remove(position);
            notifyItemRemoved(position);
            animatedMessageIds.remove(messageId);
            if (originalMessages != null && removed != null) {
                originalMessages.remove(removed);
            }
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
        // CANCEL-NOT-SUCCESS(2026-10-09)：取消此前被标成 COMPLETED，于是被取消的生成
        // 显示"✅ 已完成"和 token 统计 —— 把用户主动中止说成了成功完成。
        // MessageStatus 里没有 CANCELLED，且该枚举被 SessionLogPlugin 以 name() 持久化、
        // 全仓库引用 130+ 处，新增取值的风险不划算；改标 FAILED 并通过 errorDetail 说明
        // 真实原因（FAILED 在 updateMessageStatus 里有专门的失败展示，不会再显示成空消息）。
        message.status = ChatMessage.MessageStatus.FAILED;
        message.errorDetail = SmartQuizApplication.getAppContext().getString(R.string.h_2111ccbb);
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
            case THINKING_STEP: {
                com.oilquiz.app.ai.chat.event.StreamingEvent.ThinkingStepData thinkingData = event.getThinkingStepData();
                if (thinkingData != null) {
                    updateThinkingStep(event.messageId, thinkingData.stepNumber, thinkingData.stepType,
                        thinkingData.title, thinkingData.content, thinkingData.progress);
                    if (isAgentMode) {
                        notifyItemChanged(findMessagePosition(event.messageId), PAYLOAD_AGENT_UPDATE);
                    }
                }
                break;
            }
                
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
     *
     * <p>SELECT-NO-RENDER(2026-10-09)：选中态目前**没有任何 binder 渲染**（没有选中背景/
     * 勾选框），而且本方法在项目内尚无调用方 —— 这是一条已实现但未接线的通路。
     * 因此这里不再发 notifyItemChanged：
     * ① 选中态目前无 binder 渲染，通知不产生视觉变化；
     * 仍需保证：将来接上选中态渲染时，必须在这里重新触发 item 刷新。</p>
     */
    public void toggleSelection(String messageId) {
        if (selectedMessageIds.contains(messageId)) {
            selectedMessageIds.remove(messageId);
        } else {
            selectedMessageIds.add(messageId);
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
                animatedMessageIds.remove(msg.id);
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
     * 完成态内容编排动画：正文、思考行、工具卡按从上到下的顺序错峰淡入 + 轻微上移。
     *
     * <p>不是 token 动画：生成期间不动，完成后一次性对已经上屏的子块做 180–240ms
     * 的入场编排；按 message id 去重，滚动回滚不重播。后台只需保证“完成”事件，
     * 前端负责节奏。</p>
     */
    private void scheduleContentReveal(AIMessageViewHolder holder, ChatMessage message) {
        if (!animationsEnabled || message == null || holder.contentHost == null) return;
        if (!message.isCompleted()) return;
        // 只给刚完成的消息编排；重进历史时不要整屏乱动
        if (System.currentTimeMillis() - message.timestamp > 120_000L) return;
        if (message.id == null) return;
        final LinearLayout host = holder.contentHost;
        host.post(() -> {
            if (holder.holderMessage != message) return;
            // 接管后 contentHost 只剩一个子 View（partsContainer），照 contentHost 编排就退化成
            // "整条一起淡入"——用户看到的正是"错峰入场没了"。改成对 parts 容器自己的子 View 编排：
            // 一个 part 一张卡，块粒度与 legacy 的思考行/工具卡/正文段一致。
            final ViewGroup revealHost = holder.partsTookOver
                    && holder.partsContainer != null
                    && holder.partsContainer.getChildCount() > 0
                    ? holder.partsContainer : host;
            int count = revealHost.getChildCount();
            if (count <= 0) return;
            synchronized (revealedContentMessageIds) {
                if (!revealedContentMessageIds.add(message.id)) return;
            }
            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
            int dp6 = dpToPx(6, revealHost.getContext());
            int i = 0;
            for (int c = 0; c < count; c++) {
                View child = revealHost.getChildAt(c);
                if (child == null || child.getVisibility() != View.VISIBLE) continue;
                child.setAlpha(0f);
                child.setTranslationY(dp6);
                child.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setStartDelay(Math.min(i, 6) * 35L)
                        .setDuration(220L)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
                i++;
            }
        });
    }

    /**
     * 应用进入动画（方向性：用户消息从右滑入，AI/系统消息从左滑入 + 淡入）。
     * 参考主流聊天应用（微信/Signal）与 wasabeef/recyclerview-animators 建议：
     * 消息进入用轻量方向滑动 + 透明度，避免纯淡入的"无方向感"和过度动画。
     * 用消息 id 集合记录已动画项，滚动回滚后重新 bind 不再重复动画。
     */
    private void setAnimation(View viewToAnimate, ChatMessage message) {
        if (!animationsEnabled || message == null || viewToAnimate == null) return;
        // AI 消息在占位插入时是空的；整条入场动画留到完成态按内容块编排，
        // 避免“空气泡先滑入、正文完成时又不动”。
        if (message.isAIMessage()) return;
        // 系统关闭动画（开发者选项/省电）时不再播；180ms 的位移对"正在读的列表"是干扰
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        if (animatedMessageIds.contains(message.id)) return;
        animatedMessageIds.add(message.id);

        boolean isUser = message.isUserMessage();
        // 固定位移替代 getWidth()*0.15f：首帧未布局时 getWidth()==0。
        // 2026-10-20 S4：30dp/200ms → 8dp/180ms（demo 的 motion 口径，短、轻、不抢阅读）。
        int offsetPx = dpToPx(8, viewToAnimate.getContext());
        float fromX = isUser ? offsetPx : -offsetPx;
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
        if (query == null) query = "";
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
