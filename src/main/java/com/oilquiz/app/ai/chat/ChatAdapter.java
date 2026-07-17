package com.oilquiz.app.ai.chat;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.render.MarkdownRenderer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.animation.ValueAnimator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_USER = 0;
    private static final int VIEW_TYPE_AI = 1;
    private static final int VIEW_TYPE_SYSTEM = 2;
    private static final int VIEW_TYPE_THINKING = 3;
    private static final int VIEW_TYPE_TASK = 4;
    private static final int VIEW_TYPE_TOOL_CALL = 5;
    private static final int VIEW_TYPE_AGENT_STEP = 6;
    private static final int VIEW_TYPE_ERROR = 7;
    private static final int VIEW_TYPE_TOOL_RESULT = 8;
    private static final int VIEW_TYPE_AGENT_REFLECTION = 9;
    private static final int VIEW_TYPE_SUMMARY = 10;
    private static final int VIEW_TYPE_INFERENCE_PROGRESS = 11;

    public static final String PAYLOAD_CONTENT_UPDATE = "content_update";
    public static final String PAYLOAD_STATUS_UPDATE = "status_update";
    public static final String PAYLOAD_EXPANDED_UPDATE = "expanded_update";
    public static final String PAYLOAD_THINKING_UPDATE = "thinking_update";
    public static final String PAYLOAD_ATTACHMENT_UPDATE = "attachment_update";
    public static final String PAYLOAD_INFERENCE_PROGRESS = "inference_progress";

    private final List<ChatMessage> messages;
    private final OnActionClickListener actionClickListener;
    private OnRetryClickListener retryClickListener;
    private OnMessageClickListener messageClickListener;
    private MessageAttachmentAdapter.OnAttachmentClickListener attachmentClickListener;
    private RecyclerView attachedRecyclerView;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
    private final SimpleDateFormat groupDateFormat = new SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault());

    // 时间分组间隔（5分钟）
    private static final long TIME_GROUP_INTERVAL_MS = 5 * 60 * 1000;

    // 推理状态管理
    private InferenceStateManager inferenceStateManager;
    private InferenceProgressUpdateListener inferenceProgressListener;

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        this.attachedRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        this.attachedRecyclerView = null;
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
    private int lastAnimatedPosition = -1;

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
                return VIEW_TYPE_SYSTEM;
            case THINKING:
                return VIEW_TYPE_THINKING;
            case TASK_BREAKDOWN:
            case TASK_PROGRESS:
                return VIEW_TYPE_TASK;
            case TOOL_CALL:
                return VIEW_TYPE_TOOL_CALL;
            case AGENT_STEP:
                return VIEW_TYPE_AGENT_STEP;
            case ERROR:
                return VIEW_TYPE_ERROR;
            case TOOL_RESULT:
                return VIEW_TYPE_TOOL_RESULT;
            case AGENT_REFLECTION:
                return VIEW_TYPE_AGENT_REFLECTION;
            case SUMMARY:
                return VIEW_TYPE_SUMMARY;
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
                return new AIMessageViewHolder(inflater.inflate(R.layout.item_ai_message, parent, false));
            case VIEW_TYPE_SYSTEM:
                return new SystemMessageViewHolder(inflater.inflate(R.layout.item_system_message, parent, false));
            case VIEW_TYPE_THINKING:
                return new ThinkingMessageViewHolder(inflater.inflate(R.layout.item_thinking_message, parent, false));
            case VIEW_TYPE_TASK:
                return new TaskMessageViewHolder(inflater.inflate(R.layout.item_task_message, parent, false));
            case VIEW_TYPE_TOOL_CALL:
                return new ToolCallViewHolder(inflater.inflate(R.layout.item_tool_call_message, parent, false));
            case VIEW_TYPE_AGENT_STEP:
                return new AgentStepViewHolder(inflater.inflate(R.layout.item_agent_step_message, parent, false));
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

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        String timeStr = shouldShowDate(position) ? dateFormat.format(new Date(message.timestamp)) : timeFormat.format(new Date(message.timestamp));

        switch (holder.getItemViewType()) {
            case VIEW_TYPE_USER:
                bindUserMessage((UserMessageViewHolder) holder, message, timeStr);
                break;
            case VIEW_TYPE_AI:
                bindAIMessage((AIMessageViewHolder) holder, message, timeStr);
                break;
            case VIEW_TYPE_SYSTEM:
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
                    aiHolder.messageText.setText(message.content);
                    updateThinkingContent(aiHolder, message);
                    handleLongContent(aiHolder, message);
                } else if (holder instanceof UserMessageViewHolder) {
                    ((UserMessageViewHolder) holder).messageText.setText(message.content);
                }
            } else if (PAYLOAD_STATUS_UPDATE.equals(payload)) {
                if (holder instanceof AIMessageViewHolder) {
                    updateMessageStatus((AIMessageViewHolder) holder, message);
                }
            } else if (PAYLOAD_EXPANDED_UPDATE.equals(payload)) {
                if (holder instanceof AIMessageViewHolder) {
                    toggleMessageExpansion((AIMessageViewHolder) holder, message);
                }
            } else if (payload instanceof String) {
                if (holder instanceof AIMessageViewHolder) {
                    AIMessageViewHolder aiHolder = (AIMessageViewHolder) holder;
                    aiHolder.messageText.setText((String) payload);
                    handleLongContent(aiHolder, message);
                }
            }
        }
    }

    private boolean shouldShowDate(int position) {
        if (position == 0) return true;
        if (position >= messages.size()) return false;
        long currentTime = messages.get(position).timestamp;
        long prevTime = messages.get(position - 1).timestamp;
        return currentTime - prevTime > 30 * 60 * 1000;
    }

    private void bindUserMessage(UserMessageViewHolder holder, ChatMessage message, String timeStr) {
        holder.messageText.setText(message.content);
        holder.timestampText.setText(timeStr);
        
        holder.itemView.setOnClickListener(v -> {
            if (messageClickListener != null) {
                messageClickListener.onMessageClick(message);
            }
        });
        
        holder.itemView.setOnLongClickListener(v -> {
            if (messageClickListener != null) {
                messageClickListener.onMessageLongClick(message);
            }
            return true;
        });

        bindMessageStatus(holder.statusIcon, holder.statusText, message.status);
        
        if (message.hasError()) {
            holder.statusIcon.setImageResource(R.drawable.ic_error);
            holder.statusIcon.setColorFilter(holder.itemView.getContext().getColor(R.color.error));
        }
    }

    private void bindAIMessage(AIMessageViewHolder holder, ChatMessage message, String timeStr) {
        holder.messageText.setText(formatMessageContent(message.content));
        holder.messageText.setMovementMethod(LinkMovementMethod.getInstance());
        holder.timestampText.setText(timeStr);

        bindAttachments(holder, message);
        updateThinkingContent(holder, message);
        updateMessageStatus(holder, message);
        bindModelInfo(holder, message);

        holder.itemView.setOnClickListener(v -> {
            if (messageClickListener != null) {
                messageClickListener.onMessageClick(message);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (messageClickListener != null) {
                messageClickListener.onMessageLongClick(message);
            }
            return true;
        });

        if (message.isCompleted()) {
            if (holder.actionButtons != null) holder.actionButtons.setVisibility(View.VISIBLE);
            
            if (holder.btnCopy != null) holder.btnCopy.setOnClickListener(v -> {
                copyToClipboard(v.getContext(), message.content);
                if (actionClickListener != null) {
                    actionClickListener.onAction(ChatMessage.Action.copy(message.content));
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
        }

        handleLongContent(holder, message);
    }

    private void updateThinkingContent(AIMessageViewHolder holder, ChatMessage message) {
        if (message.thinkingContent != null && !message.thinkingContent.isEmpty()) {
            holder.thinkingLabel.setVisibility(View.VISIBLE);

            // 清理思考标签并格式化内容
            String cleanedContent = message.thinkingContent
                .replaceAll("<think[^>]*>", "")
                .replace("</think>", "")
                .replace("<think>", "")
                .trim();

            // 如果内容为空，隐藏思考区域
            if (cleanedContent.isEmpty()) {
                holder.thinkingLabel.setVisibility(View.GONE);
                holder.thinkingContent.setVisibility(View.GONE);
                return;
            }

            // 设置思考内容，保持换行格式
            holder.thinkingContent.setText(cleanedContent);

            // 根据 message.thinkingExpanded 决定展开/折叠（状态存在数据模型，不依赖 ViewHolder tag）
            if (message.thinkingExpanded) {
                holder.thinkingContent.setVisibility(View.VISIBLE);
                holder.thinkingContent.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                updateThinkingLabel(holder, true);
            } else {
                holder.thinkingContent.setVisibility(View.GONE);
                updateThinkingLabel(holder, false);
            }

            // 点击展开/折叠，带动画效果
            holder.thinkingLabel.setOnClickListener(v -> {
                if (message.thinkingExpanded) {
                    collapseThinkingContent(holder, message);
                } else {
                    expandThinkingContent(holder, message);
                }
            });

        } else {
            holder.thinkingLabel.setVisibility(View.GONE);
            holder.thinkingContent.setVisibility(View.GONE);
        }
    }

    private void updateThinkingLabel(AIMessageViewHolder holder, boolean expanded) {
        if (expanded) {
            holder.thinkingLabel.setText("▼ 思考过程");
        } else {
            holder.thinkingLabel.setText("▶ 思考过程 (已折叠)");
        }
    }

    private void expandThinkingContent(AIMessageViewHolder holder, ChatMessage message) {
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
        updateThinkingLabel(holder, true);
    }

    private void collapseThinkingContent(AIMessageViewHolder holder, ChatMessage message) {
        final int initialHeight = holder.thinkingContent.getHeight();
        if (initialHeight == 0) {
            holder.thinkingContent.setVisibility(View.GONE);
            message.thinkingExpanded = false;
            updateThinkingLabel(holder, false);
            return;
        }

        ValueAnimator animator = ValueAnimator.ofInt(initialHeight, 0);
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
        updateThinkingLabel(holder, false);
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
            
            holder.statusIcon.setVisibility(View.VISIBLE);
            holder.statusIcon.setImageResource(R.drawable.ic_check_double);
            holder.statusIcon.setColorFilter(context.getColor(R.color.text_secondary));
            
            if (message.tokensGenerated > 0) {
                float seconds = message.generationTimeMs > 0 ? message.generationTimeMs / 1000.0f : 0;
                float speed = message.generationTimeMs > 0 ? (message.tokensGenerated * 1000.0f) / message.generationTimeMs : 0;
                String statusStr = String.format("已完成 · %d token · %.1fs · %.1f t/s",
                    message.tokensGenerated, seconds, speed);
                if (message.usingGPU && message.gpuLayers > 0) {
                    statusStr += " · GPU " + message.gpuLayers + "层";
                }
                holder.statusText.setText(statusStr);
                holder.statusText.setVisibility(View.VISIBLE);
            } else {
                holder.statusText.setText("已完成");
                holder.statusText.setVisibility(View.VISIBLE);
            }
        } else if (message.status == ChatMessage.MessageStatus.GENERATING || 
                   currentState.isProcessing()) {
            // 显示推理进度
            if (holder.inferenceProgressView != null) {
                holder.inferenceProgressView.updateState(currentState, stateDetails);
            }
            holder.statusIcon.setVisibility(View.GONE);
            holder.statusText.setVisibility(View.GONE);
        } else {
            // 隐藏所有状态
            if (holder.inferenceProgressView != null) {
                holder.inferenceProgressView.hide();
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

    private void handleLongContent(AIMessageViewHolder holder, ChatMessage message) {
        boolean isLong = message.content != null && message.content.length() > 500;

        // 消息完成后自动展开（如果之前没有手动收起过）
        if (message.isCompleted() && !message.isExpanded && isLong) {
            // 首次完成长消息时自动展开
            message.isExpanded = true;
        }

        if (isLong && message.isCompleted()) {
            // 长消息已完成，显示收起按钮
            holder.expandButton.setVisibility(View.VISIBLE);
            holder.expandButton.setText("收起");
            holder.messageText.setMaxLines(Integer.MAX_VALUE);
            holder.messageText.setEllipsize(null);

            holder.expandButton.setOnClickListener(v -> {
                message.isExpanded = false;
                holder.expandButton.setText("展开全文");
                holder.messageText.setMaxLines(8);
                holder.messageText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            });
        } else if (isLong && !message.isCompleted()) {
            // 长消息生成中，限制行数避免过度滚动
            holder.expandButton.setVisibility(View.GONE);
            holder.messageText.setMaxLines(Integer.MAX_VALUE);
            holder.messageText.setEllipsize(null);
        } else {
            // 短消息或已收起
            holder.expandButton.setVisibility(View.GONE);
            holder.messageText.setMaxLines(Integer.MAX_VALUE);
            holder.messageText.setEllipsize(null);
        }
    }

    private void toggleMessageExpansion(AIMessageViewHolder holder, ChatMessage message) {
        handleLongContent(holder, message);
        holder.messageText.setText(formatMessageContent(message.content));
    }

    private Spanned formatMessageContent(String content) {
        if (content == null) return Html.fromHtml("");

        // 使用 MarkdownRenderer 进行完整渲染
        return MarkdownRenderer.render(content);
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
                text.setText("发送中...");
                break;
            case SENT:
                icon.setImageResource(R.drawable.ic_check);
                text.setText("已发送");
                break;
            case DELIVERED:
                icon.setImageResource(R.drawable.ic_check_double);
                text.setText("已送达");
                break;
            case READ:
                icon.setImageResource(R.drawable.ic_check_double);
                text.setText("已读");
                break;
            case GENERATING:
                icon.setImageResource(R.drawable.ic_send);
                if (tokensGenerated > 0) {
                    float seconds = generationTimeMs > 0 ? generationTimeMs / 1000.0f : 0;
                    float speed = generationTimeMs > 0 ? (tokensGenerated * 1000.0f) / generationTimeMs : 0;
                    text.setText(String.format("生成中... %d token · %.1fs · %.1f t/s", tokensGenerated, seconds, speed));
                } else {
                    text.setText("生成中...");
                }
                break;
            case COMPLETED:
                icon.setImageResource(R.drawable.ic_check_double);
                if (tokensGenerated > 0) {
                    float seconds = generationTimeMs > 0 ? generationTimeMs / 1000.0f : 0;
                    float speed = generationTimeMs > 0 ? (tokensGenerated * 1000.0f) / generationTimeMs : 0;
                    text.setText(String.format("已完成 · %d token · %.1fs · %.1f t/s", tokensGenerated, seconds, speed));
                } else {
                    text.setText("已完成");
                }
                break;
            case IN_PROGRESS:
                icon.setImageResource(R.drawable.ic_send);
                text.setText("处理中...");
                break;
            case FAILED:
                icon.setImageResource(R.drawable.ic_error);
                text.setText("执行失败");
                break;
            case PAUSED:
                icon.setImageResource(R.drawable.ic_check);
                text.setText("已暂停");
                break;
            case ERROR:
                icon.setImageResource(R.drawable.ic_error);
                text.setText("发送失败");
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
        SpannableStringBuilder spannable = new SpannableStringBuilder(message.content);
        
        // 查找并设置可点击的"帮助"文本
        int helpIndex = message.content.indexOf("帮助");
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
                        ds.setColor(holder.itemView.getContext().getColor(R.color.primary));
                        ds.setUnderlineText(true);
                    }
                }, helpIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            helpIndex = message.content.indexOf("帮助", endIndex);
        }
        
        // 查找并设置可点击的"教程"文本
        int guideIndex = message.content.indexOf("教程");
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
                        ds.setColor(holder.itemView.getContext().getColor(R.color.primary));
                        ds.setUnderlineText(true);
                    }
                }, guideIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            guideIndex = message.content.indexOf("教程", endIndex);
        }
        
        // 查找并设置可点击的帮助图标提示
        int iconIndex = message.content.indexOf("帮助图标");
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
                        ds.setColor(holder.itemView.getContext().getColor(R.color.primary));
                        ds.setUnderlineText(true);
                    }
                }, iconIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            iconIndex = message.content.indexOf("帮助图标", endIndex);
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
            holder.taskLabel.setText("📋 任务分解");
            holder.taskLabel.setBackgroundColor(holder.itemView.getContext().getColor(R.color.task_background));
        } else {
            holder.taskLabel.setText("📋 任务进度");
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
        if (info == null) return;

        holder.toolIcon.setText(info.toolIcon);
        holder.toolName.setText(info.toolDisplayName);
        holder.toolStatus.setText(info.getStatusText());

        if (info.parameters != null && !info.parameters.isEmpty()) {
            holder.toolParams.setVisibility(View.VISIBLE);
            holder.toolParams.setText(info.parameters);
        } else {
            holder.toolParams.setVisibility(View.GONE);
        }

        if (info.status == ChatMessage.ToolCallInfo.ToolCallStatus.COMPLETED
                || info.status == ChatMessage.ToolCallInfo.ToolCallStatus.FAILED) {
            holder.toolResultContainer.setVisibility(View.VISIBLE);
            if (info.result != null) {
                holder.toolResult.setText(info.result);
            }
            holder.toolProgress.setVisibility(View.GONE);
        } else if (info.status == ChatMessage.ToolCallInfo.ToolCallStatus.RUNNING) {
            holder.toolResultContainer.setVisibility(View.GONE);
            holder.toolProgress.setVisibility(View.VISIBLE);
            holder.toolProgress.setIndeterminate(true);
        } else {
            holder.toolResultContainer.setVisibility(View.GONE);
            holder.toolProgress.setVisibility(View.GONE);
        }

        if (info.executionTimeMs > 0) {
            holder.toolStatus.setText(info.getStatusText() + " · " + info.executionTimeMs + "ms");
        }
    }

    private void bindToolResultMessage(ToolResultViewHolder holder, ChatMessage message) {
        ChatMessage.ToolCallInfo info = message.toolCallInfo;
        if (info == null) return;

        holder.toolIcon.setText(info.toolIcon);
        holder.toolName.setText(info.toolDisplayName);
        
        if (info.result != null && !info.result.isEmpty()) {
            holder.toolResult.setText(formatMessageContent(info.result));
            holder.toolResult.setMovementMethod(LinkMovementMethod.getInstance());
        }

        if (info.executionTimeMs > 0) {
            holder.toolTime.setText("执行耗时: " + info.executionTimeMs + "ms");
        }

        holder.btnCopyResult.setOnClickListener(v -> {
            if (info.result != null) {
                copyToClipboard(v.getContext(), info.result);
            }
        });

        holder.btnViewDetails.setOnClickListener(v -> {
            if (actionClickListener != null) {
                actionClickListener.onAction(ChatMessage.Action.viewToolDetails(message.id));
            }
        });
    }

    private void bindAgentStepMessage(AgentStepViewHolder holder, ChatMessage message) {
        ChatMessage.AgentStepInfo stepInfo = message.agentStepInfo;
        if (stepInfo == null) return;

        holder.stepIcon.setText(stepInfo.getStepIcon());
        holder.stepTypeLabel.setText(stepInfo.getStepTypeLabel());
        holder.stepTypeLabel.setBackgroundColor(getStepTypeColor(holder.itemView.getContext(), stepInfo.stepType));

        if (stepInfo.totalIterations > 0) {
            holder.stepIteration.setText(stepInfo.iteration + "/" + stepInfo.totalIterations);
            holder.stepIteration.setVisibility(View.VISIBLE);
        } else {
            holder.stepIteration.setVisibility(View.GONE);
        }

        if (stepInfo.thought != null && !stepInfo.thought.isEmpty()) {
            holder.stepThought.setVisibility(View.VISIBLE);
            holder.stepThought.setText("💭 " + stepInfo.thought);
        } else {
            holder.stepThought.setVisibility(View.GONE);
        }

        if (stepInfo.action != null && !stepInfo.action.isEmpty()) {
            holder.stepAction.setVisibility(View.VISIBLE);
            holder.stepAction.setText("⚙️ " + stepInfo.action);
        } else {
            holder.stepAction.setVisibility(View.GONE);
        }

        if (stepInfo.observation != null && !stepInfo.observation.isEmpty()) {
            holder.stepObservation.setVisibility(View.VISIBLE);
            holder.stepObservation.setText("👁️ " + stepInfo.observation);
        } else {
            holder.stepObservation.setVisibility(View.GONE);
        }

        if (!stepInfo.isCompleted) {
            holder.stepProgress.setVisibility(View.VISIBLE);
            holder.stepProgress.setIndeterminate(true);
        } else {
            holder.stepProgress.setVisibility(View.GONE);
        }
    }

    private void bindAgentReflectionMessage(AgentReflectionViewHolder holder, ChatMessage message) {
        ChatMessage.AgentReflectionInfo reflection = message.agentReflectionInfo;
        if (reflection == null) return;

        holder.reflectionIcon.setText("🔍");
        holder.reflectionLabel.setText("反思总结");

        if (reflection.analysis != null && !reflection.analysis.isEmpty()) {
            holder.reflectionAnalysis.setText(reflection.analysis);
        }

        if (reflection.improvements != null && !reflection.improvements.isEmpty()) {
            holder.reflectionImprovements.setVisibility(View.VISIBLE);
            holder.reflectionImprovements.setText("📈 改进方向:\n" + reflection.improvements);
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

    private void bindSummaryMessage(SummaryMessageViewHolder holder, ChatMessage message) {
        holder.summaryIcon.setText("📊");
        holder.summaryLabel.setText("总结");
        holder.summaryContent.setText(formatMessageContent(message.content));
        holder.summaryContent.setMovementMethod(LinkMovementMethod.getInstance());

        if (message.summaryInfo != null) {
            if (message.summaryInfo.stepsCount > 0) {
                holder.summaryMeta.setText("共执行 " + message.summaryInfo.stepsCount + " 个步骤");
            }
            if (message.summaryInfo.totalTimeMs > 0) {
                holder.summaryMeta.setText(holder.summaryMeta.getText() + " · 耗时 " + message.summaryInfo.totalTimeMs + "ms");
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
            holder.errorDetail.setText("详细信息:\n" + message.errorDetail);
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

    private void bindAttachments(AIMessageViewHolder holder, ChatMessage message) {
        if (holder.attachmentsRecycler == null) return;
        
        if (message.attachments != null && !message.attachments.isEmpty()) {
            holder.attachmentsRecycler.setVisibility(View.VISIBLE);
            
            if (holder.attachmentsRecycler.getAdapter() == null) {
                boolean hasMultipleImages = message.attachments.stream().allMatch(a -> a.isImage()) && message.attachments.size() > 1;
                if (hasMultipleImages) {
                    androidx.recyclerview.widget.GridLayoutManager gridLayout = 
                        new androidx.recyclerview.widget.GridLayoutManager(
                            holder.itemView.getContext(), 
                            message.attachments.size() > 4 ? 2 : Math.min(2, message.attachments.size()));
                    holder.attachmentsRecycler.setLayoutManager(gridLayout);
                } else {
                    androidx.recyclerview.widget.LinearLayoutManager linearLayout = 
                        new androidx.recyclerview.widget.LinearLayoutManager(holder.itemView.getContext());
                    holder.attachmentsRecycler.setLayoutManager(linearLayout);
                }
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

    public void updateMessageThinkingContent(int position, String thinkingContent) {
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        message.thinkingContent = thinkingContent;
        notifyItemChanged(position, PAYLOAD_CONTENT_UPDATE);
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

    public void updateAIMessageFull(int position, String content, String thinkingContent, ChatMessage.MessageStatus status) {
        if (position < 0 || position >= messages.size()) return;
        ChatMessage message = messages.get(position);
        if (content != null) message.content = content;
        if (thinkingContent != null) message.thinkingContent = thinkingContent;
        if (status != null) message.status = status;
        notifyItemChanged(position, PAYLOAD_CONTENT_UPDATE);
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

        UserMessageViewHolder(View itemView) {
            super(itemView);
            blockLabel = itemView.findViewById(R.id.block_label);
            messageText = itemView.findViewById(R.id.message_text);
            timestampText = itemView.findViewById(R.id.timestamp_text);
            statusIcon = itemView.findViewById(R.id.status_icon);
            statusText = itemView.findViewById(R.id.status_text);
        }
    }

    static class AIMessageViewHolder extends RecyclerView.ViewHolder {
        TextView blockLabel;
        TextView messageText;
        TextView thinkingLabel;
        TextView thinkingContent;
        View actionButtons;
        TextView btnCopy;
        TextView btnShare;
        TextView btnRegenerate;
        TextView btnNewChat;
        TextView timestampText;
        ImageView statusIcon;
        TextView statusText;
        TextView expandButton;
        InferenceProgressView inferenceProgressView;
        androidx.recyclerview.widget.RecyclerView attachmentsRecycler;
        // 在线模型信息
        View modelInfoContainer;
        View modelStatusIndicator;
        TextView modelNameText;
        TextView modelLatencyText;
        TextView modelCostText;

        AIMessageViewHolder(View itemView) {
            super(itemView);
            blockLabel = itemView.findViewById(R.id.block_label);
            messageText = itemView.findViewById(R.id.message_text);
            thinkingLabel = itemView.findViewById(R.id.thinking_label);
            thinkingContent = itemView.findViewById(R.id.thinking_content);
            actionButtons = itemView.findViewById(R.id.action_buttons);
            btnCopy = itemView.findViewById(R.id.btn_copy);
            btnShare = itemView.findViewById(R.id.btn_share);
            btnRegenerate = itemView.findViewById(R.id.btn_regenerate);
            btnNewChat = itemView.findViewById(R.id.btn_new_chat);
            timestampText = itemView.findViewById(R.id.timestamp_text);
            statusIcon = itemView.findViewById(R.id.status_icon);
            statusText = itemView.findViewById(R.id.status_text);
            expandButton = itemView.findViewById(R.id.btn_expand);
            inferenceProgressView = itemView.findViewById(R.id.inference_progress_view);
            attachmentsRecycler = itemView.findViewById(R.id.attachments_recycler);
            // 在线模型信息视图
            modelInfoContainer = itemView.findViewById(R.id.model_info_container);
            modelStatusIndicator = itemView.findViewById(R.id.model_status_indicator);
            modelNameText = itemView.findViewById(R.id.model_name_text);
            modelLatencyText = itemView.findViewById(R.id.model_latency_text);
            modelCostText = itemView.findViewById(R.id.model_cost_text);
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
            // 处理思考内容
            if (messageText != null) {
                if (message.thinkingContent != null && !message.thinkingContent.isEmpty()) {
                    // 清理思考标签
                    String cleanedContent = message.thinkingContent
                        .replaceAll("<think[^>]*>", "")
                        .replace("</think>", "")
                        .replace("<think>", "")
                        .trim();
                    
                    if (!cleanedContent.isEmpty()) {
                        messageText.setText(cleanedContent);
                        if (thinkingLabel != null) {
                            thinkingLabel.setVisibility(View.VISIBLE);
                            thinkingLabel.setText("🧠 思考过程");
                        }
                    } else {
                        messageText.setText(message.content != null ? message.content : "");
                        if (thinkingLabel != null) {
                            thinkingLabel.setVisibility(View.GONE);
                        }
                    }
                } else if (message.content != null) {
                    messageText.setText(message.content);
                    if (thinkingLabel != null) {
                        thinkingLabel.setVisibility(View.GONE);
                    }
                }
            }

            // 处理进度条
            if (thinkingProgress != null) {
                if (message.status == ChatMessage.MessageStatus.GENERATING ||
                    message.status == ChatMessage.MessageStatus.IN_PROGRESS) {
                    thinkingProgress.setVisibility(View.VISIBLE);
                    thinkingProgress.setIndeterminate(true);
                } else {
                    thinkingProgress.setVisibility(View.GONE);
                }
            }
            
            // 添加点击折叠/展开功能
            if (thinkingLabel != null) {
                thinkingLabel.setOnClickListener(v -> {
                    if (messageText.getVisibility() == View.VISIBLE) {
                        messageText.setVisibility(View.GONE);
                        thinkingLabel.setText("🧠 思考过程 (已折叠)");
                    } else {
                        messageText.setVisibility(View.VISIBLE);
                        thinkingLabel.setText("🧠 思考过程");
                    }
                });
            }
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
        TextView toolIcon;
        TextView toolName;
        TextView toolStatus;
        TextView toolParams;
        View toolResultContainer;
        TextView toolResult;
        ProgressBar toolProgress;

        ToolCallViewHolder(View itemView) {
            super(itemView);
            toolIcon = itemView.findViewById(R.id.tool_icon);
            toolName = itemView.findViewById(R.id.tool_name);
            toolStatus = itemView.findViewById(R.id.tool_status);
            toolParams = itemView.findViewById(R.id.tool_params);
            toolResultContainer = itemView.findViewById(R.id.tool_result_container);
            toolResult = itemView.findViewById(R.id.tool_result);
            toolProgress = itemView.findViewById(R.id.tool_progress);
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
        TextView stepTypeLabel;
        TextView stepIteration;
        TextView stepThought;
        TextView stepAction;
        TextView stepObservation;
        ProgressBar stepProgress;

        AgentStepViewHolder(View itemView) {
            super(itemView);
            stepIcon = itemView.findViewById(R.id.step_icon);
            stepTypeLabel = itemView.findViewById(R.id.step_type_label);
            stepIteration = itemView.findViewById(R.id.step_iteration);
            stepThought = itemView.findViewById(R.id.step_thought);
            stepAction = itemView.findViewById(R.id.step_action);
            stepObservation = itemView.findViewById(R.id.step_observation);
            stepProgress = itemView.findViewById(R.id.step_progress);
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
        
        switch (event.type) {
            case MESSAGE_CREATED:
                String initialContent = event.getStringData();
                if (initialContent == null) initialContent = "";
                ChatMessage newMessage = new ChatMessage.Builder(ChatMessage.MessageType.AI)
                    .id(event.messageId)
                    .content(initialContent)
                    .status(ChatMessage.MessageStatus.GENERATING)
                    .inferenceProgress(new ChatMessage.InferenceProgress(ChatMessage.InferencePhase.GENERATING))
                    .build();
                addMessage(newMessage);
                break;
                
            case TOKEN_APPENDED:
                com.oilquiz.app.ai.chat.event.StreamingEvent.TokenData tokenData = event.getTokenData();
                if (tokenData != null) {
                    appendToken(event.messageId, tokenData.token);
                }
                break;
                
            case THINKING_STEP:
                com.oilquiz.app.ai.chat.event.StreamingEvent.ThinkingStepData thinkingData = event.getThinkingStepData();
                if (thinkingData != null) {
                    updateThinkingStep(event.messageId, thinkingData.stepNumber, thinkingData.stepType,
                        thinkingData.title, thinkingData.content, thinkingData.progress);
                }
                break;
                
            case INFERENCE_PROGRESS:
                com.oilquiz.app.ai.chat.event.StreamingEvent.InferenceProgressData progressData = event.getInferenceProgressData();
                if (progressData != null) {
                    updateInferenceProgress(event.messageId, progressData.phase,
                        progressData.processedTokens, progressData.tokensPerSecond);
                }
                break;
                
            case MESSAGE_COMPLETED:
                com.oilquiz.app.ai.chat.event.StreamingEvent.CompletionData completionData = event.getCompletionData();
                if (completionData != null) {
                    completeMessage(event.messageId, completionData.content,
                        completionData.tokensGenerated, completionData.generationTimeMs);
                } else {
                    completeMessage(event.messageId, null, 0, 0);
                }
                break;
                
            case MESSAGE_FAILED:
                failMessage(event.messageId, event.getStringData());
                break;
                
            case MESSAGE_CANCELLED:
                cancelMessage(event.messageId);
                break;
        }
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
     * 应用进入动画
     */
    private void setAnimation(View viewToAnimate, int position) {
        if (!animationsEnabled || position <= lastAnimatedPosition) {
            return;
        }
        Animation animation = new AlphaAnimation(0f, 1f);
        animation.setDuration(ANIMATION_DURATION);
        viewToAnimate.startAnimation(animation);
        lastAnimatedPosition = position;
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