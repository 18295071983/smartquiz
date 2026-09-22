package com.oilquiz.app.ai.agent.software;

import android.content.Context;

import com.oilquiz.app.ai.agent.software.engine.AgentLoopEngine;
import com.oilquiz.app.ai.agent.software.model.*;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AgentSoftwareLayer - Agent 软件层架构（MiMo 风格单循环）
 *
 * 架构：
 * 1. AgentLoopEngine 单循环：每轮一次 generate()，解析工具调用 → 执行 → 追加结果 → 继续
 * 2. 最终回复用 generateStream() 流式输出
 * 3. 消除旧版 IntentRecognizer/TaskDecomposer/ThinkingChainEngine 多轮 LLM 调用
 *
 * 硬件层调用：
 * - LLM.generate() - 每轮推理
 * - LLM.generateStream() - 最终回复流式输出
 * - Tool.execute() - 工具执行
 */
public class AgentSoftwareLayer {
    
    private static final String TAG = "AgentSoftwareLayer";
    
    // ========== 硬件层引用 ==========
    private final AIService aiService;
    
    // ========== 软件层模块 ==========
    private final AgentLoopEngine loopEngine;
    
    // ========== 状态管理 ==========
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    // ========== 回调 ==========
    public interface AgentCallback {
        void onStepUpdate(String step, String detail);
        void onThinkingUpdate(String thought);
        void onToolCallStart(String toolName, String args);
        void onToolCallComplete(String toolName, boolean success, String result);
        void onToken(String token);
        void onComplete(AgentResponse response);
        void onError(String error);
        void onInferenceProgress(int tokenCount, float tokensPerSecond);
        /** 工具声明化卡片意图（dsh presentCall/presentResult 对齐，2026-09-23）。card 为 null 表示无声明。 */
        default void onToolPresent(String toolName, Map<String, Object> card) {}
    }
    
    private AgentCallback callback;
    
    public AgentSoftwareLayer(Context context, AIService aiService) {
        this.aiService = aiService;

        // 初始化 MiMo 风格 Agent 单循环引擎
        this.loopEngine = new AgentLoopEngine(context, aiService);

        // 设置循环引擎回调 → 桥接到 AgentCallback
        this.loopEngine.setCallback(new AgentLoopEngine.LoopCallback() {
            @Override
            public void onIterationStart(int iteration, String promptSummary) {
                if (callback != null) {
                    callback.onStepUpdate("循环 " + iteration, promptSummary);
                }
            }

            @Override
            public void onIterationEnd(int iteration, String response) {
                if (callback != null && response != null) {
                    // 清理模板前缀/思考标签后再显示（模型偶尔输出 <|im_start|>assistant 等）
                    String clean = response
                            .replaceAll("<\\|im_start\\|>\\s*assistant", "")
                            .replaceAll("<\\|im_start\\|>", "")
                            .replaceAll("<\\|im_end\\|>", "")
                            .replaceAll("(?s)<think>.*?</think>", "")
                            .replaceAll("(?s)<thought>.*?</thought>", "")
                            .trim();
                    // 第 N 轮结束仅作状态栏进度提示（onStepUpdate），不再把正文摘要当思考更新：
                    // 思考区只接收 onThinkingUpdate 的真实 reasoning 事件，避免正文污染思考气泡
                    callback.onStepUpdate("第 " + iteration + " 轮", truncate(clean, 40));
                }
            }

            @Override
            public void onThinkingUpdate(String thought) {
                if (callback != null && thought != null) {
                    callback.onThinkingUpdate(thought);
                }
            }

            @Override
            public void onToolCall(String toolName, String args) {
                if (callback != null) {
                    callback.onToolCallStart(toolName, args);
                }
            }

            @Override
            public void onToolResult(String toolName, boolean success, String result) {
                if (callback != null) {
                    callback.onToolCallComplete(toolName, success, result);
                }
            }

            @Override
            public void onToolPresent(String toolName, Map<String, Object> card) {
                if (callback != null) {
                    callback.onToolPresent(toolName, card);
                }
            }

            @Override
            public void onToken(String token) {
                if (callback != null && token != null) {
                    callback.onToken(token);
                }
            }

            @Override
            public void onComplete(String finalText) {
                // 完成由 processMessageInternal 处理
            }

            @Override
            public void onError(String error) {
                if (callback != null) {
                    callback.onError(error);
                }
            }

            @Override
            public void onInferenceProgress(int tokenCount, float tokensPerSecond) {
                if (callback != null) {
                    callback.onInferenceProgress(tokenCount, tokensPerSecond);
                }
            }
        });

        AILogger.i(TAG, "AgentSoftwareLayer initialized (MiMo Loop Engine)");
    }
    
    /**
     * 设置回调
     */
    public void setCallback(AgentCallback callback) {
        this.callback = callback;
    }
    
    /**
     * 处理用户消息 - 主入口（无历史，单轮）
     * @param enableThinking 是否启用思考（R8-1：贯穿传递，Agent 模式默认 false）
     */
    public void processMessage(String userMessage, boolean enableThinking) {
        processMessage(userMessage, enableThinking, null);
    }

    /**
     * 处理用户消息 - 主入口（带多轮上下文）
     * @param history 最近几轮对话历史（user/assistant），可为 null
     */
    public void processMessage(String userMessage, boolean enableThinking,
                               java.util.List<AgentLoopEngine.HistoryEntry> history) {
        if (isProcessing.getAndSet(true)) {
            AILogger.w(TAG, "Already processing a message");
            return;
        }
        
        executor.execute(() -> {
            try {
                AgentResponse response = processMessageInternal(userMessage, enableThinking, history);
                if (callback != null) {
                    callback.onComplete(response);
                }
            } catch (Throwable t) {
                AILogger.e(TAG, "Error processing message: " + t.getMessage(), t);
                if (callback != null) {
                    String errorMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                    callback.onError("处理失败: " + errorMsg);
                }
            } finally {
                isProcessing.set(false);
            }
        });
    }
    
    /**
     * 内部处理流程 - MiMo 单循环架构
     */
    private AgentResponse processMessageInternal(String userMessage, boolean enableThinking,
                                                 java.util.List<AgentLoopEngine.HistoryEntry> history) {
        long startTime = System.currentTimeMillis();

        notifyStep("Agent 启动", "开始处理用户消息...");
        AILogger.i(TAG, "Processing message via AgentLoopEngine: " + truncate(userMessage, 50)
                + ", history: " + (history != null ? history.size() : 0));

        // 单循环执行：推理 → 工具调用 → 结果整合 → 最终回复
        AgentResponse response = loopEngine.run(userMessage, enableThinking, history);

        long totalTime = System.currentTimeMillis() - startTime;
        AILogger.i(TAG, "Agent processing completed in " + totalTime + "ms");

        if (response != null && response.stats != null) {
            notifyStep("处理完成",
                    String.format("用时: %.1fs | Token: %d | 工具调用: %d | 推理轮次: %d",
                            totalTime / 1000.0f,
                            response.stats.totalTokens,
                            response.stats.toolCallCount,
                            response.stats.thinkingSteps));
        }

        // 上下文预警
        float usagePercent = LlamaHelper.getContextUsagePercent();
        if (usagePercent >= 80.0f && usagePercent < 100.0f) {
            AILogger.w(TAG, String.format("Context usage warning: %.1f%%", usagePercent));
            if (callback != null) {
                callback.onStepUpdate("上下文预警",
                        String.format("上下文已使用 %.0f%%，接近上限，建议开启新会话", usagePercent));
            }
        }

        return response;
    }

    /**
     * 安全地统计文本 token 数
     */
    private int countTokensSafe(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            int n = LlamaHelper.countTokens(text);
            return n > 0 ? n : Math.max(1, text.length() / 4);
        } catch (Exception e) {
            return Math.max(1, text.length() / 4);
        }
    }

    /**
     * 截断字符串到指定长度
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
    
    /**
     * 取消当前处理（R3-2：先打断 native 生成，再置状态）
     */
    public void cancel() {
        try {
            LlamaHelper.stopGeneration();   // native shouldStop 置位，打断阻塞中的 chatJson/generateStream
        } catch (Throwable t) {
            AILogger.w(TAG, "stopGeneration failed: " + t.getMessage());
        }
        isProcessing.set(false);
    }
    
    /**
     * 检查是否正在处理
     */
    public boolean isProcessing() {
        return isProcessing.get();
    }
    
    /**
     * 通知步骤更新
     */
    private void notifyStep(String step, String detail) {
        AILogger.i(TAG, "Step: " + step + " - " + detail);
        AILogger.i(TAG, "Callback is null: " + (callback == null));
        if (callback != null) {
            try {
                callback.onStepUpdate(step, detail);
                AILogger.i(TAG, "Step callback triggered successfully");
            } catch (Exception e) {
                AILogger.e(TAG, "Error in step callback: " + e.getMessage());
            }
        }
    }
    
    private void notifyInferenceProgress(int tokenCount, float tokensPerSecond) {
        if (callback != null) {
            try {
                callback.onInferenceProgress(tokenCount, tokensPerSecond);
            } catch (Exception e) {
                AILogger.e(TAG, "Error in inference progress callback: " + e.getMessage());
            }
        }
    }
    
    /**
     * 释放资源
     */
    public void destroy() {
        isProcessing.set(false);
        executor.shutdown();
    }
}
