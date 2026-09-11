package com.oilquiz.app.ai.chat;

/**
 * 模式切换指令注入器（全局可复用，逻辑层）。
 *
 * 从 AIChatActivity injectModeSwitchInstruction 抽取：模式切换时把系统指令
 * 追加到本地模型 system 提示词（后台线程，避免污染用户消息历史）。
 * 通过 {@link ModelService} 注入模型服务，不依赖页面。
 */
public class ModeInstructionInjector {

    /** 模型服务能力（宿主实现，通常包 aiService） */
    public interface ModelService {
        boolean isInitialized();
        boolean appendSystemInstruction(String instruction);
    }

    private static final String TAG = "ModeInstructionInjector";

    /**
     * 注入模式切换指令（old==new 或指令为空时跳过；后台线程执行）。
     */
    public void inject(ChatModeManager.ChatMode oldMode, ChatModeManager.ChatMode newMode,
                       ModelService service) {
        if (oldMode == newMode) return;
        String instruction = ChatModeManager.getModeSwitchInstruction(oldMode, newMode);
        if (instruction == null || instruction.isEmpty()) return; // 普通模式无指令

        new Thread(() -> {
            try {
                if (service != null && service.isInitialized()) {
                    boolean ok = service.appendSystemInstruction(instruction);
                    android.util.Log.i(TAG, "Mode switch instruction appended: " + ok);
                } else {
                    android.util.Log.w(TAG, "AI service not ready, skip mode instruction injection");
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "Error injecting mode switch instruction: " + e.getMessage());
            }
        }).start();
    }
}
