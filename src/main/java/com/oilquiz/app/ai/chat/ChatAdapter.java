package com.oilquiz.app.ai.chat;

import com.oilquiz.app.R;

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
import android.view.animation.Animation;
import android.view.animation.AlphaAnimation;
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

    public static final String PAYLOAD_CONTENT_UPDATE = "content_update";
    public static final String PAYLOAD_STATUS_UPDATE = "status_update";
    public static final String PAYLOAD_EXPANDED_UPDATE = "expanded_update";

    private final List<ChatMessage> messages;
    private final OnActionClickListener actionClickListener;
    private OnTTSClickListener ttsClickListener;
    private OnRetryClickListener retryClickListener;
    private OnMessageClickListener messageClickListener;
    private RecyclerView attachedRecyclerView;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

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

    public interface OnTTSClickListener {
        void onTTSClick(String text);
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

    public void setTTSClickListener(OnTTSClickListener listener) {
        this.ttsClickListener = listener;
    }

    public ChatAdapter(List<ChatMessage> messages, OnActionClickListener actionClickListener, OnTTSClickListener ttsClickListener) {
        this.messages = messages;
        this.actionClickListener = actionClickListener;
        this.ttsClickListener = ttsClickListener;
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

        updateThinkingContent(holder, message);
        updateMessageStatus(holder, message);

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
            holder.actionButtons.setVisibility(View.VISIBLE);
            
            holder.btnCopy.setOnClickListener(v -> {
                copyToClipboard(v.getContext(), message.content);
                if (actionClickListener != null) {
                    actionClickListener.onAction(ChatMessage.Action.copy(message.content));
                }
            });

            holder.btnShare.setOnClickListener(v -> {
                shareText(v.getContext(), message.content);
            });

            holder.btnRegenerate.setOnClickListener(v -> {
                if (actionClickListener != null) {
                    actionClickListener.onAction(ChatMessage.Action.regenerate(message.id));
                }
            });

            holder.btnNewChat.setOnClickListener(v -> {
                if (actionClickListener != null) {
                    actionClickListener.onAction(ChatMessage.Action.newChat());
                }
            });

            holder.btnTTS.setOnClickListener(v -> {
                if (ttsClickListener != null) {
                    ttsClickListener.onTTSClick(message.content);
                }
            });
        } else {
            holder.actionButtons.setVisibility(View.GONE);
        }

        handleLongContent(holder, message);
    }

    private void updateThinkingContent(AIMessageViewHolder holder, ChatMessage message) {
        if (message.thinkingContent != null && !message.thinkingContent.isEmpty()) {
            holder.thinkingLabel.setVisibility(View.VISIBLE);
            String cleanedContent = message.thinkingContent
                .replace("<think", "")
                .replace("</think", "")
                .replace(">", "")
                .replace("<", "")
                .trim();
            holder.thinkingContent.setText(cleanedContent);
            holder.thinkingContent.setVisibility(View.VISIBLE);
        } else {
            holder.thinkingLabel.setVisibility(View.GONE);
            holder.thinkingContent.setVisibility(View.GONE);
        }
    }

    private void updateMessageStatus(AIMessageViewHolder holder, ChatMessage message) {
        Context context = holder.itemView.getContext();
        
        if (message.isCompleted()) {
            if (holder.inferenceProgressContainer != null && holder.inferenceProgressContainer.getVisibility() == View.VISIBLE) {
                holder.inferenceProgressContainer.setVisibility(View.GONE);
            }
            
            holder.statusIcon.setVisibility(View.VISIBLE);
            holder.statusIcon.setImageResource(R.drawable.ic_check_double);
            holder.statusIcon.setColorFilter(context.getColor(R.color.text_secondary));
            
            if (message.tokensGenerated > 0) {
                float seconds = message.generationTimeMs > 0 ? message.generationTimeMs / 1000.0f : 0;
                float speed = message.generationTimeMs > 0 ? (message.tokensGenerated * 1000.0f) / message.generationTimeMs : 0;
                holder.statusText.setText(String.format("已完成 · %d token · %.1fs · %.1f t/s", 
                    message.tokensGenerated, seconds, speed));
                holder.statusText.setVisibility(View.VISIBLE);
            } else {
                holder.statusText.setText("已完成");
                holder.statusText.setVisibility(View.VISIBLE);
            }
        } else {
            boolean showDetailedProgress = (message.inferenceProgress != null && 
                message.inferenceProgress.phase != null && 
                message.inferenceProgress.phase != ChatMessage.InferencePhase.IDLE && 
                message.inferenceProgress.phase != ChatMessage.InferencePhase.COMPLETED);
            
            if (showDetailedProgress) {
                if (!holder.ensureInferenceProgressView(context)) {
                    holder.statusIcon.setVisibility(View.VISIBLE);
                    holder.statusIcon.setImageResource(R.drawable.ic_send);
                    holder.statusIcon.setColorFilter(context.getColor(R.color.text_secondary));
                    holder.statusText.setText("生成中...");
                    holder.statusText.setVisibility(View.VISIBLE);
                    return;
                }
                holder.inferenceProgressContainer.setVisibility(View.VISIBLE);
                holder.statusIcon.setVisibility(View.GONE);
                holder.statusText.setVisibility(View.GONE);
                
                ChatMessage.InferenceProgress progress = message.inferenceProgress;
                ChatMessage.InferencePhase phase = progress.phase;
                
                if (holder.progressEmoji != null) {
                    holder.progressEmoji.setText(phase.getEmoji());
                }
                if (holder.progressPhaseText != null) {
                    holder.progressPhaseText.setText(phase.getDisplayText());
                }
                
                if (holder.progressInfoText != null) {
                    if (progress.additionalInfo != null && !progress.additionalInfo.isEmpty()) {
                        holder.progressInfoText.setText(progress.additionalInfo);
                        holder.progressInfoText.setVisibility(View.VISIBLE);
                    } else {
                        holder.progressInfoText.setVisibility(View.GONE);
                    }
                }
                
                int currentStep = getCurrentStep(phase);
                updateStepIndicator(holder, currentStep, context);
                
                if (holder.inferenceProgressBar != null) {
                    if (phase == ChatMessage.InferencePhase.GENERATING) {
                        holder.inferenceProgressBar.setIndeterminate(false);
                        if (progress.totalTokens > 0) {
                            holder.inferenceProgressBar.setProgress(progress.getPercentage());
                        } else {
                            holder.inferenceProgressBar.setIndeterminate(true);
                        }
                    } else {
                        holder.inferenceProgressBar.setIndeterminate(true);
                    }
                }
                
                int tokenCount = Math.max(progress.processedTokens, message.tokensGenerated);
                float tps = progress.tokensPerSecond;
                if (tps <= 0 && tokenCount > 0 && message.generationTimeMs > 0) {
                    tps = (tokenCount * 1000.0f) / message.generationTimeMs;
                }
                
                if (holder.statsTokens != null) {
                    holder.statsTokens.setText(String.valueOf(tokenCount));
                }
                if (holder.statsSpeed != null) {
                    holder.statsSpeed.setText(String.format("%.1f t/s", tps));
                }
                
                if (holder.statsRemaining != null) {
                    String estimatedTime = progress.getEstimatedTimeFormatted();
                    if (!estimatedTime.isEmpty()) {
                        holder.statsRemaining.setText(estimatedTime);
                    } else if (tps > 0 && tokenCount > 0) {
                        holder.statsRemaining.setText("计算中...");
                    } else {
                        holder.statsRemaining.setText("--秒");
                    }
                }
                
            } else if (message.status == ChatMessage.MessageStatus.GENERATING) {
                if (holder.inferenceProgressContainer != null) {
                    holder.inferenceProgressContainer.setVisibility(View.GONE);
                }
                holder.statusIcon.setVisibility(View.VISIBLE);
                holder.statusIcon.setImageResource(R.drawable.ic_send);
                holder.statusIcon.setColorFilter(context.getColor(R.color.text_secondary));
                holder.statusText.setVisibility(View.VISIBLE);
                if (message.tokensGenerated > 0) {
                    float seconds = message.generationTimeMs > 0 ? message.generationTimeMs / 1000.0f : 0;
                    float speed = message.generationTimeMs > 0 ? (message.tokensGenerated * 1000.0f) / message.generationTimeMs : 0;
                    holder.statusText.setText(String.format("生成中... %d token · %.1fs · %.1f t/s", 
                        message.tokensGenerated, seconds, speed));
                } else {
                    holder.statusText.setText("生成中...");
                }
            } else {
                if (holder.inferenceProgressContainer != null) {
                    holder.inferenceProgressContainer.setVisibility(View.GONE);
                }
                holder.statusIcon.setVisibility(View.GONE);
                holder.statusText.setVisibility(View.GONE);
            }
        }
    }
    
    private int getCurrentStep(ChatMessage.InferencePhase phase) {
        switch (phase) {
            case INITIALIZING:
            case LOADING_MODEL:
            case WAITING:
                return 1;
            case PREFILL:
            case FALLBACK_TO_CPU:
                return 2;
            case ENCODING:
            case THINKING:
                return 3;
            case GENERATING:
            case DECODING:
                return 4;
            case COMPLETED:
                return 4;
            default:
                return 1;
        }
    }
    
    private void updateStepIndicator(AIMessageViewHolder holder, int currentStep, Context context) {
        View[] dots = {holder.stepDot1, holder.stepDot2, holder.stepDot3, holder.stepDot4};
        View[] lines = {holder.stepLine1, holder.stepLine2, holder.stepLine3};
        TextView[] labels = {holder.stepLabel1, holder.stepLabel2, holder.stepLabel3, holder.stepLabel4};
        
        for (int i = 0; i < 4; i++) {
            if (i < currentStep) {
                if (dots[i] != null) {
                    dots[i].setBackgroundResource(R.drawable.step_dot_active);
                }
                if (labels[i] != null) {
                    labels[i].setTextColor(context.getColor(R.color.primary));
                }
                if (i < 3 && lines[i] != null) {
                    lines[i].setBackgroundColor(context.getColor(R.color.primary));
                }
            } else {
                if (dots[i] != null) {
                    dots[i].setBackgroundResource(R.drawable.step_dot_inactive);
                }
                if (labels[i] != null) {
                    labels[i].setTextColor(context.getColor(R.color.text_secondary));
                }
                if (i < 3 && lines[i] != null) {
                    lines[i].setBackgroundColor(context.getColor(R.color.divider));
                }
            }
        }
    }

    private void handleLongContent(AIMessageViewHolder holder, ChatMessage message) {
        boolean isLong = message.content != null && message.content.length() > 500;
        
        if (isLong && !message.isExpanded && message.isCompleted()) {
            holder.expandButton.setVisibility(View.VISIBLE);
            holder.expandButton.setText("展开全文");
            holder.messageText.setMaxLines(8);
            holder.messageText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            
            holder.expandButton.setOnClickListener(v -> {
                message.isExpanded = true;
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    notifyItemChanged(pos, PAYLOAD_EXPANDED_UPDATE);
                }
            });
        } else if (message.isExpanded) {
            holder.expandButton.setVisibility(View.VISIBLE);
            holder.expandButton.setText("收起");
            holder.messageText.setMaxLines(Integer.MAX_VALUE);
            holder.messageText.setEllipsize(null);
            
            holder.expandButton.setOnClickListener(v -> {
                message.isExpanded = false;
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    notifyItemChanged(pos, PAYLOAD_EXPANDED_UPDATE);
                }
            });
        } else {
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
        
        String formatted = content
            .replace("\\n", "<br>")
            .replace("**", "<strong>")
            .replace("`", "<code>");
        
        return Html.fromHtml(formatted, Html.FROM_HTML_MODE_LEGACY);
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
        holder.messageText.setText(message.content);
        holder.thinkingProgress.setProgress(message.getThinkingProgress());
        holder.thinkingProgress.setIndeterminate(message.getThinkingProgress() <= 0);
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
        View btnTTS;
        View actionButtons;
        TextView btnCopy;
        TextView btnShare;
        TextView btnRegenerate;
        TextView btnNewChat;
        TextView timestampText;
        ImageView statusIcon;
        TextView statusText;
        TextView expandButton;
        ViewGroup inferenceProgressContainer;
        TextView progressEmoji;
        TextView progressPhaseText;
        TextView progressInfoText;
        View stepDot1;
        View stepDot2;
        View stepDot3;
        View stepDot4;
        View stepLine1;
        View stepLine2;
        View stepLine3;
        TextView stepLabel1;
        TextView stepLabel2;
        TextView stepLabel3;
        TextView stepLabel4;
        ProgressBar inferenceProgressBar;
        TextView statsTokens;
        TextView statsSpeed;
        TextView statsRemaining;
        View inferenceProgressView;

        AIMessageViewHolder(View itemView) {
            super(itemView);
            blockLabel = itemView.findViewById(R.id.block_label);
            messageText = itemView.findViewById(R.id.message_text);
            thinkingLabel = itemView.findViewById(R.id.thinking_label);
            thinkingContent = itemView.findViewById(R.id.thinking_content);
            btnTTS = itemView.findViewById(R.id.btn_tts);
            actionButtons = itemView.findViewById(R.id.action_buttons);
            btnCopy = itemView.findViewById(R.id.btn_copy);
            btnShare = itemView.findViewById(R.id.btn_share);
            btnRegenerate = itemView.findViewById(R.id.btn_regenerate);
            btnNewChat = itemView.findViewById(R.id.btn_new_chat);
            timestampText = itemView.findViewById(R.id.timestamp_text);
            statusIcon = itemView.findViewById(R.id.status_icon);
            statusText = itemView.findViewById(R.id.status_text);
            expandButton = itemView.findViewById(R.id.btn_expand);
            inferenceProgressContainer = itemView.findViewById(R.id.inference_progress_placeholder);
        }
        
        boolean ensureInferenceProgressView(Context context) {
            if (inferenceProgressView != null) return true;
            if (inferenceProgressContainer == null) return false;
            LayoutInflater inflater = LayoutInflater.from(context);
            inferenceProgressView = inflater.inflate(R.layout.item_inference_progress, inferenceProgressContainer, false);
            inferenceProgressContainer.addView(inferenceProgressView);
            
            progressEmoji = inferenceProgressView.findViewById(R.id.progress_emoji);
            progressPhaseText = inferenceProgressView.findViewById(R.id.progress_phase_text);
            progressInfoText = inferenceProgressView.findViewById(R.id.progress_info_text);
            stepDot1 = inferenceProgressView.findViewById(R.id.step_dot_1);
            stepDot2 = inferenceProgressView.findViewById(R.id.step_dot_2);
            stepDot3 = inferenceProgressView.findViewById(R.id.step_dot_3);
            stepDot4 = inferenceProgressView.findViewById(R.id.step_dot_4);
            stepLine1 = inferenceProgressView.findViewById(R.id.step_line_1);
            stepLine2 = inferenceProgressView.findViewById(R.id.step_line_2);
            stepLine3 = inferenceProgressView.findViewById(R.id.step_line_3);
            stepLabel1 = inferenceProgressView.findViewById(R.id.step_label_1);
            stepLabel2 = inferenceProgressView.findViewById(R.id.step_label_2);
            stepLabel3 = inferenceProgressView.findViewById(R.id.step_label_3);
            stepLabel4 = inferenceProgressView.findViewById(R.id.step_label_4);
            inferenceProgressBar = inferenceProgressView.findViewById(R.id.inference_progress_bar);
            statsTokens = inferenceProgressView.findViewById(R.id.stats_tokens);
            statsSpeed = inferenceProgressView.findViewById(R.id.stats_speed);
            statsRemaining = inferenceProgressView.findViewById(R.id.stats_remaining);
            return true;
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

        ThinkingMessageViewHolder(View itemView) {
            super(itemView);
            messageText = itemView.findViewById(R.id.message_text);
            thinkingProgress = itemView.findViewById(R.id.thinking_progress);
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
}