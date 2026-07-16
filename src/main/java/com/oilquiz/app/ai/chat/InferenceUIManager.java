package com.oilquiz.app.ai.chat;

import android.app.Activity;
import android.util.Log;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.ai.chat.event.StreamingEvent;

/**
 * 推理UI管理器
 * 
 * 整合InferenceStateManager、NativeEventBridge和ChatAdapter，
 * 提供简化的API来管理Native推理事件的UI显示。
 */
public class InferenceUIManager {
    private static final String TAG = "InferenceUIManager";
    
    private final Activity activity;
    private final InferenceStateManager stateManager;
    private final NativeEventBridge nativeBridge;
    private ChatAdapter chatAdapter;
    
    // 超时配置（毫秒）
    private long inferenceTimeout = 120000;  // 默认2分钟
    private long heartbeatTimeout = 15000;   // 默认15秒
    
    public InferenceUIManager(Activity activity) {
        this.activity = activity;
        this.stateManager = InferenceStateManager.getInstance();
        this.nativeBridge = NativeEventBridge.getInstance();
        
        setupListeners();
    }
    
    /**
     * 设置ChatAdapter
     */
    public void setChatAdapter(ChatAdapter adapter) {
        this.chatAdapter = adapter;
        
        // 设置推理进度监听器
        adapter.setInferenceProgressListener(new ChatAdapter.InferenceProgressUpdateListener() {
            @Override
            public void onInferenceStateChanged(String messageId, 
                                               InferenceStateManager.InferenceState oldState,
                                               InferenceStateManager.InferenceState newState,
                                               InferenceStateManager.StateDetails details) {
                Log.d(TAG, String.format("Inference state changed: %s -> %s for message %s",
                    oldState, newState, messageId));
                
                // 可以在这里添加额外的UI反馈，如震动、声音等
                if (newState == InferenceStateManager.InferenceState.COMPLETED) {
                    onInferenceCompleted(messageId, details);
                } else if (newState == InferenceStateManager.InferenceState.FAILED) {
                    onInferenceFailed(messageId, details);
                } else if (newState == InferenceStateManager.InferenceState.TIMEOUT) {
                    onInferenceTimeout(messageId, details);
                }
            }
            
            @Override
            public void onInferenceProgressUpdated(String messageId, 
                                                  InferenceStateManager.StateDetails details) {
                // 进度更新已在ChatAdapter中处理
                Log.v(TAG, String.format("Progress update for %s: %d tokens, %.1f t/s",
                    messageId, details.processedTokens, details.tokensPerSecond));
            }
        });
    }
    
    /**
     * 设置监听器
     */
    private void setupListeners() {
        // 设置Native事件监听器
        nativeBridge.setEventListener(new NativeEventBridge.NativeEventListener() {
            @Override
            public void onNativeEvent(String messageId, InferenceStateManager.NativeEvent event) {
                // 事件已传递给StateManager处理
                Log.v(TAG, "Native event: " + event.type + " for message: " + messageId);
            }
            
            @Override
            public void onTokenGenerated(String messageId, String token) {
                // Token生成事件
            }
        });
        
        // 设置通信错误监听器
        nativeBridge.setErrorListener(new NativeEventBridge.CommunicationErrorListener() {
            @Override
            public void onNativeError(String messageId, String error) {
                Log.e(TAG, "Native error for message " + messageId + ": " + error);
                showError("推理错误: " + error);
            }
            
            @Override
            public void onCommunicationBreakdown(String messageId, String reason) {
                Log.e(TAG, "Communication breakdown for message " + messageId + ": " + reason);
                showError("通信中断，正在尝试恢复...");
            }
            
            @Override
            public void onRecoveryStarted(String messageId, int attemptNumber) {
                Log.i(TAG, "Recovery started for message " + messageId + 
                    ", attempt: " + attemptNumber);
            }
            
            @Override
            public void onRecoverySuccess(String messageId) {
                Log.i(TAG, "Recovery successful for message " + messageId);
            }
            
            @Override
            public void onRecoveryFailed(String messageId, String reason) {
                Log.e(TAG, "Recovery failed for message " + messageId + ": " + reason);
                showError("无法恢复推理，请重试");
            }
        });
        
        // 设置超时监听器
        stateManager.setTimeoutListener(new InferenceStateManager.TimeoutListener() {
            @Override
            public void onTimeout(String messageId, InferenceStateManager.InferenceState lastState,
                                 long elapsedTimeMs) {
                Log.w(TAG, String.format("Inference timeout for message %s in state %s after %dms",
                    messageId, lastState, elapsedTimeMs));
                
                showError("推理超时，已自动取消");
                
                // 自动取消生成
                nativeBridge.cancelGeneration(messageId);
            }
        });
    }
    
    /**
     * 开始推理
     */
    public void startInference(String messageId, String prompt, int maxTokens,
                                NativeEventBridge.GenerationCallback callback) {
        Log.i(TAG, "Starting inference for message: " + messageId);
        
        // 启动状态管理
        stateManager.startInference(messageId);
        
        // 启动Native生成
        nativeBridge.startGeneration(messageId, prompt, maxTokens, callback);
    }
    
    /**
     * 取消推理
     */
    public void cancelInference(String messageId) {
        Log.i(TAG, "Cancelling inference for message: " + messageId);
        nativeBridge.cancelGeneration(messageId);
        stateManager.cancelInference(messageId);
    }
    
    /**
     * 处理StreamingEvent
     */
    public void handleStreamingEvent(StreamingEvent event) {
        if (event == null) return;
        
        // 传递给StateManager处理
        stateManager.handleStreamingEvent(event.messageId, event);
        
        // 传递给ChatAdapter处理
        if (chatAdapter != null) {
            chatAdapter.handleStreamingEvent(event);
        }
    }
    
    /**
     * 推理完成回调
     */
    private void onInferenceCompleted(String messageId, InferenceStateManager.StateDetails details) {
        Log.i(TAG, String.format("Inference completed for message %s: %d tokens in %dms",
            messageId, details.processedTokens, details.getElapsedTime()));
    }
    
    /**
     * 推理失败回调
     */
    private void onInferenceFailed(String messageId, InferenceStateManager.StateDetails details) {
        Log.e(TAG, "Inference failed for message " + messageId + ": " + details.additionalInfo);
    }
    
    /**
     * 推理超时回调
     */
    private void onInferenceTimeout(String messageId, InferenceStateManager.StateDetails details) {
        Log.w(TAG, "Inference timeout for message " + messageId);
    }
    
    /**
     * 显示错误提示
     */
    private void showError(String message) {
        if (activity != null && !activity.isFinishing()) {
            activity.runOnUiThread(() -> {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            });
        }
    }
    
    /**
     * 设置推理超时时间
     */
    public void setInferenceTimeout(long timeoutMs) {
        this.inferenceTimeout = timeoutMs;
    }
    
    /**
     * 设置心跳超时时间
     */
    public void setHeartbeatTimeout(long timeoutMs) {
        this.heartbeatTimeout = timeoutMs;
    }
    
    /**
     * 检查是否有活动的推理
     */
    public boolean hasActiveInference(String messageId) {
        return stateManager.isInferenceActive(messageId);
    }
    
    /**
     * 获取当前推理状态
     */
    public InferenceStateManager.InferenceState getCurrentState(String messageId) {
        return stateManager.getCurrentState(messageId);
    }
    
    /**
     * 销毁管理器
     */
    public void destroy() {
        Log.i(TAG, "Destroying InferenceUIManager");
        nativeBridge.destroy();
    }
}
