package com.oilquiz.app.ai.chat.event;

public class StreamingEvent {
    private static final String TAG = "StreamingEvent";

    public enum Type {
        MESSAGE_CREATED,
        TOKEN_APPENDED,
        STATUS_CHANGED,
        THINKING_UPDATE,
        THINKING_STEP,
        INFERENCE_PROGRESS,
        ATTACHMENT_UPDATE,
        MESSAGE_COMPLETED,
        MESSAGE_FAILED,
        MESSAGE_CANCELLED
    }

    public final String messageId;
    public final Type type;
    public final Object data;
    public final long timestamp;

    public StreamingEvent(String messageId, Type type, Object data) {
        this.messageId = messageId;
        this.type = type;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public static StreamingEvent createMessageCreated(String messageId, String initialContent) {
        return new StreamingEvent(messageId, Type.MESSAGE_CREATED, initialContent);
    }

    public static StreamingEvent createTokenAppended(String messageId, String token, int position) {
        return new StreamingEvent(messageId, Type.TOKEN_APPENDED, new TokenData(token, position));
    }

    public static StreamingEvent createStatusChanged(String messageId, String newStatus) {
        return new StreamingEvent(messageId, Type.STATUS_CHANGED, newStatus);
    }

    public static StreamingEvent createThinkingUpdate(String messageId, ThinkingStepData thinkingStep) {
        return new StreamingEvent(messageId, Type.THINKING_UPDATE, thinkingStep);
    }

    public static StreamingEvent createAttachmentUpdate(String messageId, Object attachments) {
        return new StreamingEvent(messageId, Type.ATTACHMENT_UPDATE, attachments);
    }

    public static StreamingEvent createMessageCompleted(String messageId, String finalContent, StatsData stats) {
        return new StreamingEvent(messageId, Type.MESSAGE_COMPLETED, new CompletedData(finalContent, stats));
    }

    public static StreamingEvent createMessageFailed(String messageId, String error) {
        return new StreamingEvent(messageId, Type.MESSAGE_FAILED, error);
    }

    public static StreamingEvent createMessageCancelled(String messageId) {
        return new StreamingEvent(messageId, Type.MESSAGE_CANCELLED, null);
    }

    public static StreamingEvent createInferenceProgress(String messageId, InferenceProgressData progress) {
        return new StreamingEvent(messageId, Type.INFERENCE_PROGRESS, progress);
    }

    public static StreamingEvent createThinkingStep(String messageId, ThinkingStepData step) {
        return new StreamingEvent(messageId, Type.THINKING_STEP, step);
    }

    public TokenData getTokenData() {
        if (data instanceof TokenData) {
            return (TokenData) data;
        }
        return null;
    }

    public CompletedData getCompletedData() {
        if (data instanceof CompletedData) {
            return (CompletedData) data;
        }
        return null;
    }

    public CompletionData getCompletionData() {
        if (data instanceof CompletionData) {
            return (CompletionData) data;
        }
        return null;
    }

    public ThinkingStepData getThinkingStepData() {
        if (data instanceof ThinkingStepData) {
            return (ThinkingStepData) data;
        }
        return null;
    }

    public InferenceProgressData getInferenceProgressData() {
        if (data instanceof InferenceProgressData) {
            return (InferenceProgressData) data;
        }
        return null;
    }

    public String getStringData() {
        if (data instanceof String) {
            return (String) data;
        }
        return null;
    }

    public static class TokenData {
        public final String token;
        public final int position;

        public TokenData(String token, int position) {
            this.token = token;
            this.position = position;
        }
    }

    public static class StatsData {
        public final int totalTokens;
        public final long generationTimeMs;
        public final float tokensPerSecond;
        public final boolean usingGPU;
        public final int gpuLayers;

        public StatsData(int totalTokens, long generationTimeMs, float tokensPerSecond, 
                        boolean usingGPU, int gpuLayers) {
            this.totalTokens = totalTokens;
            this.generationTimeMs = generationTimeMs;
            this.tokensPerSecond = tokensPerSecond;
            this.usingGPU = usingGPU;
            this.gpuLayers = gpuLayers;
        }

        public StatsData(int totalTokens, long generationTimeMs, float tokensPerSecond) {
            this(totalTokens, generationTimeMs, tokensPerSecond, false, 0);
        }
    }

    public static class CompletedData {
        public final String content;
        public final StatsData stats;

        public CompletedData(String content, StatsData stats) {
            this.content = content;
            this.stats = stats;
        }
    }

    public static class ThinkingStepData {
        public final int stepNumber;
        public final String stepType;
        public final String title;
        public final String content;
        public final int progress;
        public final String status;

        public ThinkingStepData(int stepNumber, String stepType, String title, 
                               String content, int progress, String status) {
            this.stepNumber = stepNumber;
            this.stepType = stepType;
            this.title = title;
            this.content = content;
            this.progress = progress;
            this.status = status;
        }

        public ThinkingStepData(int stepNumber, String stepType, String title,
                                String content, int progress) {
            this(stepNumber, stepType, title, content, progress, "IN_PROGRESS");
        }

        public ThinkingStepData(String stepType, String title, String content, int progress) {
            this(-1, stepType, title, content, progress, "IN_PROGRESS");
        }
    }

    public static class InferenceProgressData {
        public final com.oilquiz.app.ai.chat.ChatMessage.InferencePhase phase;
        public final int processedTokens;
        public final float tokensPerSecond;
        public final int totalTokens;
        public final String additionalInfo;

        public InferenceProgressData(com.oilquiz.app.ai.chat.ChatMessage.InferencePhase phase,
                                    int processedTokens, float tokensPerSecond) {
            this(phase, processedTokens, tokensPerSecond, 0, null);
        }

        public InferenceProgressData(com.oilquiz.app.ai.chat.ChatMessage.InferencePhase phase,
                                    int processedTokens, float tokensPerSecond,
                                    int totalTokens, String additionalInfo) {
            this.phase = phase;
            this.processedTokens = processedTokens;
            this.tokensPerSecond = tokensPerSecond;
            this.totalTokens = totalTokens;
            this.additionalInfo = additionalInfo;
        }
    }

    public static class CompletionData {
        public final String content;
        public final int tokensGenerated;
        public final long generationTimeMs;
        public final float tokensPerSecond;

        public CompletionData(String content, int tokensGenerated, long generationTimeMs) {
            this(content, tokensGenerated, generationTimeMs, 0f);
        }

        public CompletionData(String content, int tokensGenerated, long generationTimeMs, float tokensPerSecond) {
            this.content = content;
            this.tokensGenerated = tokensGenerated;
            this.generationTimeMs = generationTimeMs;
            this.tokensPerSecond = tokensPerSecond;
        }
    }
}
