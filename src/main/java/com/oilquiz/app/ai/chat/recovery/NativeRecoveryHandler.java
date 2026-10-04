package com.oilquiz.app.ai.chat.recovery;

import android.app.Activity;
import android.os.Handler;
import android.util.Log;

import com.oilquiz.app.ai.service.AIService;

/**
 * 处理原生 JNI 层的崩溃恢复。
 * 从 AIChatActivity 中提取的独立模块。
 */
public class NativeRecoveryHandler {

    private static final String TAG = "NativeRecoveryHandler";

    public interface Callback {
        void onRecoveryStarted(String message);
        void onRecoveryProgress(String message, int progress);
        void onRecoveryComplete(String message);
        void onRecoveryFailed(String error);
        void onAddSystemMessage(String message);
        void onShowToast(String message);
        void onTriggerAutoRecovery();
    }

    private final Activity activity;
    private final Handler uiHandler;
    private final Callback callback;
    private AIService aiService;
    private AIService.NativeStateRecoveryListener recoveryListener;
    private boolean isRecovering = false;
    private String pendingMessageForRecovery = null;
    private int recoveryProgressUpdateCount = 0;

    public NativeRecoveryHandler(Activity activity, Handler uiHandler, Callback callback) {
        this.activity = activity;
        this.uiHandler = uiHandler;
        this.callback = callback;
    }

    public void setAIService(AIService aiService) {
        this.aiService = aiService;
    }

    public void setupListener() {
        if (aiService == null) return;
        recoveryListener = new AIService.NativeStateRecoveryListener() {
            @Override
            public void onRecoveryStarted(int attemptCount) {
                activity.runOnUiThread(() -> {
                    isRecovering = true;
                    recoveryProgressUpdateCount = 0;
                    callback.onRecoveryStarted("正在恢复AI服务 (尝试 " + attemptCount + ")");
                });
            }

            @Override
            public void onRecoverySuccess(long recoveryTimeMs) {
                activity.runOnUiThread(() -> {
                    isRecovering = false;
                    callback.onRecoveryComplete("恢复成功 (耗时 " + recoveryTimeMs + "ms)");
                    if (pendingMessageForRecovery != null) {
                        pendingMessageForRecovery = null;
                        callback.onTriggerAutoRecovery();
                    }
                });
            }

            @Override
            public void onRecoveryFailed(int attemptCount, int maxAttempts, String reason) {
                activity.runOnUiThread(() -> {
                    isRecovering = false;
                    callback.onRecoveryFailed("恢复失败 (" + attemptCount + "/" + maxAttempts + "): " + reason);
                });
            }
        };
        aiService.setNativeStateRecoveryListener(recoveryListener);
    }

    public void removeListener() {
        if (aiService != null && recoveryListener != null) {
            aiService.setNativeStateRecoveryListener(null);
            recoveryListener = null;
        }
    }

    public boolean isRecovering() {
        return isRecovering;
    }

    public void setPendingMessage(String message) {
        this.pendingMessageForRecovery = message;
    }

    public String getPendingMessage() {
        return pendingMessageForRecovery;
    }

    public void clearPendingMessage() {
        this.pendingMessageForRecovery = null;
    }

    public void triggerAutoRecovery() {
        if (aiService == null) return;

        // 没有模型文件就没什么可"恢复"的：直接跳过（用户要求：先查文件再加载/恢复）。
        // 之前这里会去加载一个不存在的模型 → 反复失败并往对话里写"AI服务初始化失败"。
        try {
            if (!aiService.isCurrentModelFileExists()) {
                Log.w(TAG, "模型文件不存在，跳过自动恢复（无需恢复；请先下载/选择模型）");
                clearPendingMessage();
                return;
            }
        } catch (Throwable ignored) {
        }

        String pendingMsg = pendingMessageForRecovery;
        new Thread(() -> {
            try {
                aiService.autoRecoverNativeState(pendingMsg, new AIService.NativeStateRecoveryCallback() {
                    @Override
                    public void onRecoverySuccess(String pendingMessage) {
                        activity.runOnUiThread(() -> callback.onRecoveryComplete("自动恢复完成"));
                    }

                    @Override
                    public void onRecoveryFailed(String reason) {
                        activity.runOnUiThread(() -> callback.onRecoveryFailed("自动恢复失败: " + reason));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Auto recovery failed", e);
                activity.runOnUiThread(() -> callback.onRecoveryFailed("自动恢复失败: " + e.getMessage()));
            }
        }).start();
    }
}
