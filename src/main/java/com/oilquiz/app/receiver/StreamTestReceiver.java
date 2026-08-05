package com.oilquiz.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.util.PromptBuilder;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;

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
        testAIServiceGenerateStream(aiService);

        // 测试 2: 直接通过 LlamaHelper.generate(List<Message>) 测试
        new Handler().postDelayed(() -> testLlamaHelperGenerateDirect(), 3000);
    }

    /**
     * 测试 1: AIService.generateStream（聊天界面使用的路径）
     */
    private void testAIServiceGenerateStream(AIService aiService) {
        AILogger.i(TAG, "----- 测试 1: AIService.generateStream -----");
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
            }

            @Override
            public void onError(Exception error) {
                AILogger.e(TAG, "[Test1] ❌ 流式生成失败: " + error.getMessage(), error);
            }
        });
    }

    /**
     * 测试 2: 直接调用 LlamaHelper.generate(List<Message>)
     */
    private void testLlamaHelperGenerateDirect() {
        AILogger.i(TAG, "----- 测试 2: LlamaHelper.generate(List<Message>) -----");

        List<PromptBuilder.Message> messages = new ArrayList<>();
        messages.add(new PromptBuilder.Message("system", "你是一个乐于助人的AI助手。"));
        messages.add(new PromptBuilder.Message("user", "1+1等于几？"));

        long startTime = System.currentTimeMillis();
        String result = LlamaHelper.generate(messages, 128, 0.7f);
        long elapsed = System.currentTimeMillis() - startTime;

        AILogger.i(TAG, "[Test2] ✅ 同步生成完成");
        AILogger.i(TAG, "[Test2] 耗时: " + elapsed + "ms");
        AILogger.i(TAG, "[Test2] 结果: " + result);
        AILogger.i(TAG, "========== 流式输出测试结束 ==========");
    }
}
