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
    private static final int VIEW_TYPE_AGENT_SUMMARY = 7;
    private static final int VIEW_TYPE_ERROR = 8;
    private static final int VIEW_TYPE_TOOL_RESULT = 9;
    private static final int VIEW_TYPE_AGENT_REFLECTION = 10;
    private static final int VIEW_TYPE_SUMMARY = 11;
    private static final int VIEW_TYPE_INFERENCE_PROGRESS = 12;

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
            case AGENT_SUMMARY:
                return VIEW_TYPE_AGENT_SUMMARY;
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
                    aiHolder.messageText.setText(formatMessageContent(message.content));
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
                if (holder instanceof AIMessageViewHolder) {
                    AIMessageViewHolder aiHolder = (AIMessageViewHolder) holder;
                    if (aiHolder.agentExecutionPanel != null) {
                        if (message.agentExecutionState != null) {
                            aiHolder.agentExecutionPanel.setVisibility(View.VISIBLE);
                            aiHolder.agentExecutionPanel.setState(message.agentExecutionState);
                            aiHolder.agentExecutionPanel.refresh();
                        } else {
                            aiHolder.agentExecutionPanel.setVisibility(View.GONE);
                        }
                    }
                }
            } else if (PAYLOAD_EXPANDED_UPDATE.equals(payload)) {
                if (holder instanceof AIMessageViewHolder) {
                    toggleMessageExpansion((AIMessageViewHolder) holder, message);
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
        holder.messageText.setText(message.content);
        holder.timestampText.setText(timeStr);

        setupItemViewInteraction(holder.itemView, message);

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

        // blockLabel 当前未使用，确保隐藏
        if (holder.blockLabel != null) {
            holder.blockLabel.setVisibility(View.GONE);
        }

        bindAttachments(holder, message);
        updateThinkingContent(holder, message);
        updateMessageStatus(holder, message);
        bindModelInfo(holder, message);

        setupItemViewInteraction(holder.itemView, message);

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
            // 清除按钮点击事件，避免 ViewHolder 复用时旧消息的监听器残留
            if (holder.btnCopy != null) holder.btnCopy.setOnClickListener(null);
            if (holder.btnShare != null) holder.btnShare.setOnClickListener(null);
            if (holder.btnRegenerate != null) holder.btnRegenerate.setOnClickListener(null);
            if (holder.btnNewChat != null) holder.btnNewChat.setOnClickListener(null);
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
            if (holder.agentExecutionView != null) {
                holder.agentExecutionView.hide();
            }
            // Agent模式：保持panel可见显示最终状态，而不是隐藏
            if (holder.agentExecutionPanel != null) {
                if (message.agentMode && message.agentExecutionState != null) {
                    holder.agentExecutionPanel.setVisibility(View.VISIBLE);
                    holder.agentExecutionPanel.setState(message.agentExecutionState);
                    holder.agentExecutionPanel.refresh();
                } else {
                    holder.agentExecutionPanel.setVisibility(View.GONE);
                }
            }
            
            holder.statusIcon.setVisibility(View.VISIBLE);
            holder.statusIcon.setImageResource(R.drawable.ic_check_double);
            holder.statusIcon.setColorFilter(context.getColor(R.color.text_secondary));
            
            if (message.tokensGenerated > 0) {
                float seconds = message.generationTimeMs > 0 ? message.generationTimeMs / 1000.0f : 0;
                float speed = message.tokensPerSecond > 0 
                    ? message.tokensPerSecond 
                    : (message.generationTimeMs > 0 ? (message.tokensGenerated * 1000.0f) / message.generationTimeMs : 0);
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
            // Agent模式显示AgentExecutionView，其他模式显示InferenceProgressView
            boolean isAgentMode = message.agentMode;

            if (isAgentMode) {
                if (holder.inferenceProgressView != null) {
                    holder.inferenceProgressView.hide();
                }
                if (holder.agentExecutionView != null) {
                    holder.agentExecutionView.hide();
                }
                if (holder.agentExecutionPanel != null && message.agentExecutionState != null) {
                    holder.agentExecutionPanel.setVisibility(View.VISIBLE);
                    holder.agentExecutionPanel.setState(message.agentExecutionState);
                    holder.agentExecutionPanel.refresh();
                }
            } else {
                if (holder.inferenceProgressView != null) {
                    holder.inferenceProgressView.updateState(currentState, stateDetails);
                }
                if (holder.agentExecutionView != null) {
                    holder.agentExecutionView.hide();
                }
                if (holder.agentExecutionPanel != null) {
                    holder.agentExecutionPanel.setVisibility(View.GONE);
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
            // Agent模式：保持panel可见显示错误/取消状态
            if (holder.agentExecutionPanel != null) {
                if (message.agentMode && message.agentExecutionState != null) {
                    holder.agentExecutionPanel.setVisibility(View.VISIBLE);
                    holder.agentExecutionPanel.setState(message.agentExecutionState);
                    holder.agentExecutionPanel.refresh();
                } else {
                    holder.agentExecutionPanel.setVisibility(View.GONE);
                }
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

        // 仅在首次完成长消息且用户未手动操作过时自动展开
        if (isLong && message.isCompleted() && !message.isExpanded && !message.hasUserToggledExpand) {
            message.isExpanded = true;
        }

        if (!isLong || !message.isCompleted()) {
            // 短消息或未完成：不显示展开按钮，全部展开
            holder.expandButton.setVisibility(View.GONE);
            holder.messageText.setMaxLines(Integer.MAX_VALUE);
            holder.messageText.setEllipsize(null);
            return;
        }

        // 长消息已完成：显示展开/收起按钮，根据当前状态切换
        holder.expandButton.setVisibility(View.VISIBLE);
        holder.expandButton.setOnClickListener(v -> {
            message.hasUserToggledExpand = true;
            message.isExpanded = !message.isExpanded;
            applyExpansionState(holder, message);
        });
        applyExpansionState(holder, message);
    }

    /** 应用展开/收起状态到视图 */
    private void applyExpansionState(AIMessageViewHolder holder, ChatMessage message) {
        if (message.isExpanded) {
            holder.expandButton.setText("收起");
            holder.messageText.setMaxLines(Integer.MAX_VALUE);
            holder.messageText.setEllipsize(null);
        } else {
            holder.expandButton.setText("展开全文");
            holder.messageText.setMaxLines(8);
            holder.messageText.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
                holder.toolResult.setText(info.result);
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

        if (info.executionTimeMs > 0) {
            holder.toolStatus.setText(info.getStatusText() + " · " + info.executionTimeMs + "ms");
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
                    description.append("💭 正在分析问题...");
                }
                break;
            case PLANNING:
                if (stepInfo.thought != null && !stepInfo.thought.isEmpty()) {
                    // 显示具体的意图分析结果
                    description.append("📋 已识别到用户意图：").append(stepInfo.thought);
                    if (stepInfo.action != null && !stepInfo.action.isEmpty()) {
                        description.append("\n计划执行：").append(stepInfo.action);
                    }
                } else {
                    description.append("📋 正在分析用户意图...");
                }
                break;
            case ACTING:
                if (stepInfo.action != null && !stepInfo.action.isEmpty()) {
                    description.append("⚙️ 正在执行：").append(stepInfo.action);
                    if (stepInfo.observation != null && !stepInfo.observation.isEmpty()) {
                        description.append("\n执行结果：").append(stepInfo.observation);
                    }
                } else {
                    description.append("⚙️ 正在执行操作...");
                }
                break;
            case OBSERVING:
                if (stepInfo.observation != null && !stepInfo.observation.isEmpty()) {
                    description.append("👁️ 执行结果：").append(stepInfo.observation);
                    if (stepInfo.thought != null && !stepInfo.thought.isEmpty()) {
                        description.append("\n分析：").append(stepInfo.thought);
                    }
                } else {
                    description.append("👁️ 正在分析执行结果...");
                }
                break;
            case REFLECTING:
                if (stepInfo.thought != null && !stepInfo.thought.isEmpty()) {
                    description.append("🔄 反思：").append(stepInfo.thought);
                    if (stepInfo.action != null && !stepInfo.action.isEmpty()) {
                        description.append("\n改进建议：").append(stepInfo.action);
                    }
                } else {
                    description.append("🔄 正在反思...");
                }
                break;
            case TOOL_CALLING:
                if (stepInfo.action != null && !stepInfo.action.isEmpty()) {
                    description.append("🔧 调用工具：").append(stepInfo.action);
                    if (stepInfo.observation != null && !stepInfo.observation.isEmpty()) {
                        description.append("\n工具返回：").append(stepInfo.observation);
                    }
                } else {
                    description.append("🔧 正在调用工具...");
                }
                break;
            case REASONING:
                if (stepInfo.thought != null && !stepInfo.thought.isEmpty()) {
                    description.append("🧠 推理过程：").append(stepInfo.thought);
                    if (stepInfo.detail != null && !stepInfo.detail.isEmpty()) {
                        description.append("\n推理结论：").append(stepInfo.detail);
                    }
                } else {
                    description.append("🧠 正在推理分析...");
                }
                break;
            case LOOPING:
                description.append("🔁 循环处理中...");
                if (stepInfo.detail != null && !stepInfo.detail.isEmpty()) {
                    description.append("\n").append(stepInfo.detail);
                }
                if (stepInfo.iteration > 0 && stepInfo.totalIterations > 0) {
                    description.append("\n进度：").append(stepInfo.iteration).append("/").append(stepInfo.totalIterations);
                }
                break;
            case PAUSED:
                description.append("⏸️ 已暂停，等待用户输入");
                if (stepInfo.detail != null && !stepInfo.detail.isEmpty()) {
                    description.append("\n").append(stepInfo.detail);
                }
                break;
            case COMPLETED:
                description.append("✅ 任务完成");
                if (stepInfo.detail != null && !stepInfo.detail.isEmpty()) {
                    description.append("\n").append(stepInfo.detail);
                }
                break;
            default:
                description.append("📌 正在处理...");
                if (stepInfo.thought != null && !stepInfo.thought.isEmpty()) {
                    description.append("\n").append(stepInfo.thought);
                }
        }

        holder.stepDescription.setText(description.toString());

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

    private void bindAgentSummaryMessage(AgentSummaryViewHolder holder, ChatMessage message) {
        ChatMessage.AgentSummaryInfo summary = message.agentSummaryInfo;
        if (summary == null) return;

        // 设置状态
        if (summary.isSuccess) {
            holder.summaryStatus.setText("✅ 成功");
            holder.summaryStatus.setTextColor(holder.itemView.getContext().getColor(R.color.agent_summary_success));
        } else {
            holder.summaryStatus.setText("❌ 失败");
            holder.summaryStatus.setTextColor(holder.itemView.getContext().getColor(R.color.agent_summary_error));
        }

        // 设置统计信息
        holder.summaryTime.setText(summary.getFormattedTime());
        holder.summarySteps.setText(String.valueOf(summary.totalSteps));
        holder.summaryTools.setText(String.valueOf(summary.toolCallCount));
        holder.summaryTokens.setText(summary.getFormattedTokens());
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
        AgentExecutionView agentExecutionView;
        com.oilquiz.app.ai.agent.AgentExecutionPanel agentExecutionPanel;
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
            agentExecutionView = itemView.findViewById(R.id.agent_execution_view);
            agentExecutionPanel = itemView.findViewById(R.id.agent_execution_panel);
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
            // 构建标签文字：Agent多轮思考显示轮次
            String label;
            boolean isAgentRound = message.agentMode && message.taskProgress != null && message.taskProgress > 0;
            if (isAgentRound) {
                label = "💭 第" + message.taskProgress + "轮思考";
            } else {
                label = "🧠 思考过程";
            }

            // 处理思考内容
            if (messageText != null) {
                String displayContent = "";
                if (message.thinkingContent != null && !message.thinkingContent.isEmpty()) {
                    // 清理思考标签
                    displayContent = message.thinkingContent
                        .replaceAll("<think[^>]*>", "")
                        .replace("</think>", "")
                        .replace("<think>", "")
                        .trim();
                }
                if (displayContent.isEmpty() && message.content != null) {
                    displayContent = message.content;
                }
                if (displayContent.isEmpty() && message.status == ChatMessage.MessageStatus.IN_PROGRESS) {
                    displayContent = "思考中...";
                }

                messageText.setText(displayContent);

                // 展开/折叠控制
                if (message.thinkingExpanded) {
                    messageText.setVisibility(View.VISIBLE);
                    if (thinkingLabel != null) {
                        thinkingLabel.setText(label);
                    }
                } else {
                    messageText.setVisibility(View.GONE);
                    if (thinkingLabel != null) {
                        thinkingLabel.setText(label + " (已折叠)");
                    }
                }
            }

            if (thinkingLabel != null) {
                thinkingLabel.setVisibility(View.VISIBLE);
            }

            // 处理进度条：思考进行中显示不确定进度动画
            if (thinkingProgress != null) {
                if (message.status == ChatMessage.MessageStatus.GENERATING ||
                    message.status == ChatMessage.MessageStatus.IN_PROGRESS) {
                    thinkingProgress.setVisibility(View.VISIBLE);
                    thinkingProgress.setIndeterminate(true);
                } else {
                    thinkingProgress.setVisibility(View.GONE);
                }
            }

            // 点击标签折叠/展开
            if (thinkingLabel != null) {
                thinkingLabel.setOnClickListener(v -> {
                    message.thinkingExpanded = !message.thinkingExpanded;
                    if (message.thinkingExpanded) {
                        messageText.setVisibility(View.VISIBLE);
                        thinkingLabel.setText(label);
                    } else {
                        messageText.setVisibility(View.GONE);
                        thinkingLabel.setText(label + " (已折叠)");
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

        AgentSummaryViewHolder(View itemView) {
            super(itemView);
            summaryTitle = itemView.findViewById(R.id.summary_title);
            summaryStatus = itemView.findViewById(R.id.summary_status);
            summaryTime = itemView.findViewById(R.id.summary_time);
            summarySteps = itemView.findViewById(R.id.summary_steps);
            summaryTools = itemView.findViewById(R.id.summary_tools);
            summaryTokens = itemView.findViewById(R.id.summary_tokens);
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
            message.agentExecutionState.addLog("COMPLETE", "执行完成");
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
            message.agentExecutionState.addLog("ERROR", "执行失败: " + error);
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
        // 先检查消息自身的agentMode标志
        int position = findMessagePosition(messageId);
        if (position >= 0 && position < messages.size()) {
            ChatMessage message = messages.get(position);
            if (message != null && message.agentMode) return true;
        }
        // 消息可能还没添加到列表中，查询ChatOrchestrator
        try {
            ChatOrchestrator orchestrator = ChatOrchestrator.getInstance();
            if (orchestrator != null) {
                return orchestrator.isAgentMessage(messageId);
            }
        } catch (Exception e) {
            // ignore
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