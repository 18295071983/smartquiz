package com.oilquiz.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

/**
 * 流式输出测试广播接收器
 * 通过 ADB 广播触发：adb shell am broadcast -a com.oilquiz.app.STREAM_TEST -n com.oilquiz.app/.receiver.StreamTestReceiver
 */
public class StreamTestReceiver extends BroadcastReceiver {

    private static final String TAG = "StreamTestReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        AILogger.i(TAG, "========== 流式输出测试开始 ==========");

        AIService aiService = AIService.getInstance(context);
        if (!aiService.isInitialized() || !LlamaHelper.isModelInitialized()) {
            AILogger.e(TAG, "❌ AI 服务未初始化，无法测试");
            return;
        }

        // 测试 1: 通过 AIService.generateStream 测试（走修复后的消息列表路径）
        // 测试 2 在测试 1 完成后串行触发（generateStream 会销毁聊天上下文，不能并发）
        testAIServiceGenerateStream(aiService);
    }

    /**
     * 测试 1: AIService.generateStream（聊天界面使用的路径）
     */
    private void testAIServiceGenerateStream(AIService aiService) {
        AILogger.i(TAG, "----- 测试 1: AIService.generateStream -----");
        final AIService aiServiceRef = aiService;
        String prompt = "请用一句话介绍你自己";

        aiService.generateStream(prompt, 256, new AIService.GenerateStreamCallback() {
            private final StringBuilder fullText = new StringBuilder();
            private int tokenCount = 0;
            private long startTime = 0;

            @Override
            public void onToken(String token) {
                if (tokenCount == 0) {
                    startTime = System.currentTimeMillis();
                    AILogger.i(TAG, "[Test1] ✅ 收到第一个 token: \"" + token + "\"");
                }
                tokenCount++;
                fullText.append(token);
                // 打印前 5 个 token 和最后 5 个 token
                if (tokenCount <= 5) {
                    AILogger.i(TAG, "[Test1] Token #" + tokenCount + ": \"" + token + "\"");
                } else if (tokenCount % 20 == 0) {
                    AILogger.i(TAG, "[Test1] 已收到 " + tokenCount + " 个 token...");
                }
            }

            @Override
            public void onSuccess(String fullText) {
                long elapsed = System.currentTimeMillis() - startTime;
                float tps = elapsed > 0 ? (tokenCount * 1000f / elapsed) : 0;
                AILogger.i(TAG, "[Test1] ✅ 流式生成完成");
                AILogger.i(TAG, "[Test1] 总 token 数: " + tokenCount);
                AILogger.i(TAG, "[Test1] 耗时: " + elapsed + "ms");
                AILogger.i(TAG, "[Test1] 速度: " + String.format("%.1f t/s", tps));
                AILogger.i(TAG, "[Test1] 完整文本: " + (fullText != null ? fullText : this.fullText.toString()));
                // 测试 1 完成后延迟 3 秒再跑测试 2（等待上下文清理完成）
                new Handler(Looper.getMainLooper()).postDelayed(() -> testChatSendPath(aiServiceRef), 3000);
            }

            @Override
            public void onError(Exception error) {
                AILogger.e(TAG, "[Test1] ❌ 流式生成失败: " + error.getMessage(), error);
                new Handler(Looper.getMainLooper()).postDelayed(() -> testChatSendPath(aiServiceRef), 3000);
            }
        });
    }

    /**
     * 测试 2: AIService.chatSend（聊天界面真实路径）
     * 在后台线程执行避免主线程 ANR；验证系统信息（角色+当前时间）注入效果。
     */
    private void testChatSendPath(AIService aiService) {
        AILogger.i(TAG, "----- 测试 2: AIService.chatSend（聊天界面路径）-----");

        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            aiService.chatSend("今天星期几？请用一句话回答。", 128, false, new LlamaHelper.TokenCallback() {
                private final StringBuilder full = new StringBuilder();

                @Override
                public void onToken(String token) {
                    if (token != null) full.append(token);
                }

                @Override
                public void onComplete(String fullText) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    String result = (fullText != null && !fullText.isEmpty()) ? fullText : full.toString();
                    AILogger.i(TAG, "[Test2] ✅ chatSend 完成, 耗时: " + elapsed + "ms");
                    AILogger.i(TAG, "[Test2] 结果: " + result);
                    // 测试 2 完成后延迟 3 秒再跑测试 3（思考路径）
                    new Handler(Looper.getMainLooper()).postDelayed(() -> testThinkingPath(aiService), 3000);
                }

                @Override
                public void onError(String error) {
                    AILogger.e(TAG, "[Test2] ❌ chatSend 失败: " + error);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> testThinkingPath(aiService), 3000);
                }
            });
        }, "StreamTest-chatSend").start();
    }

    /**
     * 测试 3: 深度思考路径（enableThinking=true）
     * 验证思考结束机制：模型是否输出 </think> 标记、[THINK_END] 何时到达、
     * 思考/正文 token 分离是否正常。
     */
    private void testThinkingPath(AIService aiService) {
        AILogger.i(TAG, "----- 测试 3: chatSend 深度思考（enableThinking=true）-----");

        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            aiService.chatSend("用一句话介绍你自己。", 256, true, new LlamaHelper.TokenCallback() {
                private final StringBuilder thinking = new StringBuilder();
                private final StringBuilder main = new StringBuilder();
                private boolean inThinking = true;
                private boolean thinkEndReceived = false;
                private int tokenCount = 0;

                @Override
                public void onToken(String token) {
                    tokenCount++;
                    if ("[THINK_END]".equals(token)) {
                        thinkEndReceived = true;
                        inThinking = false;
                        AILogger.i(TAG, "[Test3] 🔔 收到 [THINK_END]，第 " + tokenCount + " 个 token，思考长度=" + thinking.length());
                        return;
                    }
                    if (inThinking) {
                        thinking.append(token);
                    } else {
                        main.append(token);
                    }
                }

                @Override
                public void onComplete(String fullText) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    AILogger.i(TAG, "[Test3] ✅ 思考生成完成, 耗时: " + elapsed + "ms, 总 token: " + tokenCount);
                    AILogger.i(TAG, "[Test3] 收到[THINK_END]: " + thinkEndReceived);
                    AILogger.i(TAG, "[Test3] 思考内容(" + thinking.length() + "字符): " + thinking.toString());
                    AILogger.i(TAG, "[Test3] 主回复(" + main.length() + "字符): " + main.toString());
                    AILogger.i(TAG, "========== 流式输出测试结束 ==========");
                }

                @Override
                public void onError(String error) {
                    AILogger.e(TAG, "[Test3] ❌ 思考生成失败: " + error);
                    AILogger.i(TAG, "========== 流式输出测试结束 ==========");
                }
            });
        }, "StreamTest-thinking").start();
    }
}
