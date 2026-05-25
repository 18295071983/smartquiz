package com.oilquiz.app.ai.chat;

import androidx.recyclerview.widget.DiffUtil;

import java.util.List;

public class ChatMessageDiffCallback extends DiffUtil.Callback {

    private final List<ChatMessage> oldList;
    private final List<ChatMessage> newList;

    public ChatMessageDiffCallback(List<ChatMessage> oldList, List<ChatMessage> newList) {
        this.oldList = oldList;
        this.newList = newList;
    }

    @Override
    public int getOldListSize() {
        return oldList != null ? oldList.size() : 0;
    }

    @Override
    public int getNewListSize() {
        return newList != null ? newList.size() : 0;
    }

    @Override
    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
        ChatMessage oldItem = oldList.get(oldItemPosition);
        ChatMessage newItem = newList.get(newItemPosition);
        return oldItem.id != null && oldItem.id.equals(newItem.id);
    }

    @Override
    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
        ChatMessage oldItem = oldList.get(oldItemPosition);
        ChatMessage newItem = newList.get(newItemPosition);
        
        if (oldItem.type != newItem.type) return false;
        if (oldItem.status != newItem.status) return false;
        if (oldItem.content == null ? newItem.content != null : !oldItem.content.equals(newItem.content)) return false;
        if (oldItem.thinkingContent == null ? newItem.thinkingContent != null : 
            !oldItem.thinkingContent.equals(newItem.thinkingContent)) return false;
        if (oldItem.tokensGenerated != newItem.tokensGenerated) return false;
        if (oldItem.generationTimeMs != newItem.generationTimeMs) return false;
        if (oldItem.isExpanded != newItem.isExpanded) return false;
        if (!oldItem.attachmentsEquals(newItem)) return false;
        if (!isThinkingStepsEqual(oldItem, newItem)) return false;
        if (!isInferenceProgressEqual(oldItem, newItem)) return false;
        if (!isToolCallInfoEqual(oldItem, newItem)) return false;
        if (!isAgentStepInfoEqual(oldItem, newItem)) return false;
        
        return true;
    }

    @Override
    public Object getChangePayload(int oldItemPosition, int newItemPosition) {
        ChatMessage oldItem = oldList.get(oldItemPosition);
        ChatMessage newItem = newList.get(newItemPosition);
        
        boolean contentChanged = oldItem.content == null ? newItem.content != null : 
            !oldItem.content.equals(newItem.content);
        boolean thinkingContentChanged = oldItem.thinkingContent == null ? newItem.thinkingContent != null : 
            !oldItem.thinkingContent.equals(newItem.thinkingContent);
        boolean statusChanged = oldItem.status != newItem.status;
        boolean tokensChanged = oldItem.tokensGenerated != newItem.tokensGenerated;
        boolean timeChanged = oldItem.generationTimeMs != newItem.generationTimeMs;
        boolean expandedChanged = oldItem.isExpanded != newItem.isExpanded;
        boolean thinkingStepsChanged = !isThinkingStepsEqual(oldItem, newItem);
        boolean inferenceProgressChanged = !isInferenceProgressEqual(oldItem, newItem);
        boolean attachmentsChanged = !oldItem.attachmentsEquals(newItem);
        boolean toolCallChanged = !isToolCallInfoEqual(oldItem, newItem);
        boolean agentStepChanged = !isAgentStepInfoEqual(oldItem, newItem);

        if (!contentChanged && !thinkingContentChanged && !statusChanged && 
            !expandedChanged && !thinkingStepsChanged && !inferenceProgressChanged &&
            !tokensChanged && !timeChanged && !attachmentsChanged &&
            !toolCallChanged && !agentStepChanged) {
            return null;
        }

        if (contentChanged && !statusChanged && !expandedChanged && !thinkingContentChanged &&
            !thinkingStepsChanged && !inferenceProgressChanged) {
            return "content_update";
        }

        if (statusChanged && !contentChanged && !thinkingContentChanged &&
            !expandedChanged && !thinkingStepsChanged) {
            return "status_update";
        }

        if (expandedChanged && !contentChanged && !thinkingContentChanged &&
            !statusChanged && !thinkingStepsChanged) {
            return "expanded_update";
        }

        if ((thinkingContentChanged || thinkingStepsChanged || inferenceProgressChanged) &&
            !contentChanged && !expandedChanged && !statusChanged) {
            return "thinking_update";
        }

        return super.getChangePayload(oldItemPosition, newItemPosition);
    }

    private boolean isThinkingStepsEqual(ChatMessage oldItem, ChatMessage newItem) {
        if (oldItem.thinkingSteps == null && newItem.thinkingSteps == null) return true;
        if (oldItem.thinkingSteps == null || newItem.thinkingSteps == null) return false;
        if (oldItem.thinkingSteps.size() != newItem.thinkingSteps.size()) return false;
        
        for (int i = 0; i < oldItem.thinkingSteps.size(); i++) {
            ChatMessage.ThinkingStep oldStep = oldItem.thinkingSteps.get(i);
            ChatMessage.ThinkingStep newStep = newItem.thinkingSteps.get(i);
            if (oldStep.status != newStep.status) return false;
        }
        return true;
    }

    private boolean isInferenceProgressEqual(ChatMessage oldItem, ChatMessage newItem) {
        if (oldItem.inferenceProgress == null && newItem.inferenceProgress == null) return true;
        if (oldItem.inferenceProgress == null || newItem.inferenceProgress == null) return false;
        
        ChatMessage.InferenceProgress oldProgress = oldItem.inferenceProgress;
        ChatMessage.InferenceProgress newProgress = newItem.inferenceProgress;
        
        return oldProgress.phase == newProgress.phase &&
               oldProgress.processedTokens == newProgress.processedTokens &&
               oldProgress.totalTokens == newProgress.totalTokens &&
               oldProgress.tokensPerSecond == newProgress.tokensPerSecond &&
               oldProgress.overallProgress == newProgress.overallProgress;
    }

    private boolean isToolCallInfoEqual(ChatMessage oldItem, ChatMessage newItem) {
        if (oldItem.toolCallInfo == null && newItem.toolCallInfo == null) return true;
        if (oldItem.toolCallInfo == null || newItem.toolCallInfo == null) return false;
        return oldItem.toolCallInfo.status == newItem.toolCallInfo.status &&
               oldItem.toolCallInfo.executionTimeMs == newItem.toolCallInfo.executionTimeMs;
    }

    private boolean isAgentStepInfoEqual(ChatMessage oldItem, ChatMessage newItem) {
        if (oldItem.agentStepInfo == null && newItem.agentStepInfo == null) return true;
        if (oldItem.agentStepInfo == null || newItem.agentStepInfo == null) return false;
        return oldItem.agentStepInfo.isCompleted == newItem.agentStepInfo.isCompleted &&
               oldItem.agentStepInfo.iteration == newItem.agentStepInfo.iteration;
    }
}
