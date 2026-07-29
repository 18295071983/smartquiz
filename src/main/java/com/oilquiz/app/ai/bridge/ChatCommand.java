package com.oilquiz.app.ai.bridge;

/**
 * UI层发送给模型的命令
 *
 * 设计原则：UI不直接调用模型服务，只通过命令与模型通信
 * 命令由ModelExecutionBridge执行，结果通过BridgeCallback返回
 */
public class ChatCommand {

    public enum CommandType {
        // 消息生成
        SEND_MESSAGE,
        SEND_MESSAGE_AGENT,
        STOP_GENERATION,
        // 上下文管理
        CLEAR_CONTEXT,
        INIT_CONTEXT,
        // 模型生命周期
        INIT_MODEL,
        RELOAD_MODEL,
        // 模型信息查询
        GET_MODEL_INFO,
        GET_TOKEN_COUNT,
        // 内存管理
        HANDLE_MEMORY_PRESSURE,
        // 状态检查
        CHECK_NATIVE_STATE
    }

    public final CommandType type;
    public final String messageId;
    public String content;
    public String systemPrompt;
    public int maxTokens;
    public boolean enableThinking;
    public int memoryLevel;
    public long timestamp;

    private ChatCommand(CommandType type, String messageId) {
        this.type = type;
        this.messageId = messageId;
        this.timestamp = System.currentTimeMillis();
    }

    // ========== 工厂方法 ==========

    public static ChatCommand sendMessage(String messageId, String content, int maxTokens, boolean thinking) {
        ChatCommand cmd = new ChatCommand(CommandType.SEND_MESSAGE, messageId);
        cmd.content = content;
        cmd.maxTokens = maxTokens;
        cmd.enableThinking = thinking;
        return cmd;
    }

    public static ChatCommand sendMessageAgent(String messageId, String content, int maxTokens) {
        ChatCommand cmd = new ChatCommand(CommandType.SEND_MESSAGE_AGENT, messageId);
        cmd.content = content;
        cmd.maxTokens = maxTokens;
        return cmd;
    }

    public static ChatCommand stopGeneration() {
        return new ChatCommand(CommandType.STOP_GENERATION, null);
    }

    public static ChatCommand clearContext() {
        return new ChatCommand(CommandType.CLEAR_CONTEXT, null);
    }

    public static ChatCommand initContext(String systemPrompt) {
        ChatCommand cmd = new ChatCommand(CommandType.INIT_CONTEXT, null);
        cmd.systemPrompt = systemPrompt;
        return cmd;
    }

    public static ChatCommand initModel() {
        return new ChatCommand(CommandType.INIT_MODEL, null);
    }

    public static ChatCommand reloadModel() {
        return new ChatCommand(CommandType.RELOAD_MODEL, null);
    }

    public static ChatCommand getModelInfo() {
        return new ChatCommand(CommandType.GET_MODEL_INFO, null);
    }

    public static ChatCommand getTokenCount(String text) {
        ChatCommand cmd = new ChatCommand(CommandType.GET_TOKEN_COUNT, null);
        cmd.content = text;
        return cmd;
    }

    public static ChatCommand handleMemoryPressure(int level) {
        ChatCommand cmd = new ChatCommand(CommandType.HANDLE_MEMORY_PRESSURE, null);
        cmd.memoryLevel = level;
        return cmd;
    }

    public static ChatCommand checkNativeState() {
        return new ChatCommand(CommandType.CHECK_NATIVE_STATE, null);
    }

    @Override
    public String toString() {
        return "ChatCommand{" + type + ", msg=" + messageId + "}";
    }
}