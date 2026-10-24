package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * 多套推理状态文案的推进契约（2026-10-25）：
 * 按"阶段 + 本阶段已耗时"换句，让等待有推进感；完成/失败/空闲不产生活动文案。
 */
public class InferenceStatusPhrasesTest {

    private static final long S = 1000L;

    @Test
    public void 等待阶段_从等待响应到连接慢到仍在等() {
        assertEquals("等待模型响应…",
                InferenceStatusPhrases.phrase(ChatMessage.InferencePhase.WAITING, 0));
        assertEquals("正在连接模型…",
                InferenceStatusPhrases.phrase(ChatMessage.InferencePhase.WAITING, 4 * S));
        assertEquals("响应较慢，仍在等待…",
                InferenceStatusPhrases.phrase(ChatMessage.InferencePhase.WAITING, 12 * S));
        assertEquals("仍在等待响应…",
                InferenceStatusPhrases.phrase(ChatMessage.InferencePhase.WAITING, 30 * S));
    }

    @Test
    public void 等待类的各种准备阶段共用同一套文案() {
        for (ChatMessage.InferencePhase p : new ChatMessage.InferencePhase[]{
                ChatMessage.InferencePhase.INITIALIZING,
                ChatMessage.InferencePhase.LOADING_MODEL,
                ChatMessage.InferencePhase.ENCODING,
                ChatMessage.InferencePhase.PREFILL}) {
            assertEquals("等待模型响应…", InferenceStatusPhrases.phrase(p, 0));
        }
    }

    @Test
    public void 思考阶段_随思考时长逐步深入() {
        ChatMessage.InferencePhase p = ChatMessage.InferencePhase.THINKING;
        assertEquals("正在理解问题…", InferenceStatusPhrases.phrase(p, 1 * S));
        assertEquals("正在梳理思路…", InferenceStatusPhrases.phrase(p, 6 * S));
        assertEquals("正在推敲关键点…", InferenceStatusPhrases.phrase(p, 14 * S));
        assertEquals("正在验证推理…", InferenceStatusPhrases.phrase(p, 28 * S));
        assertEquals("正在收敛结论…", InferenceStatusPhrases.phrase(p, 48 * S));
        assertEquals("思考较久，仍在继续…", InferenceStatusPhrases.phrase(p, 90 * S));
    }

    @Test
    public void 生成阶段_从组织到撰写到收尾() {
        ChatMessage.InferencePhase p = ChatMessage.InferencePhase.GENERATING;
        assertEquals("正在组织回答…", InferenceStatusPhrases.phrase(p, 1 * S));
        assertEquals("正在撰写回答…", InferenceStatusPhrases.phrase(p, 5 * S));
        assertEquals("正在补充细节…", InferenceStatusPhrases.phrase(p, 16 * S));
        assertEquals("正在收尾…", InferenceStatusPhrases.phrase(p, 40 * S));
        assertEquals("正在收尾…", InferenceStatusPhrases.phrase(
                ChatMessage.InferencePhase.DECODING, 40 * S));
    }

    @Test
    public void 结束态不产生活动文案() {
        assertNull(InferenceStatusPhrases.phrase(ChatMessage.InferencePhase.COMPLETED, 5 * S));
        assertNull(InferenceStatusPhrases.phrase(ChatMessage.InferencePhase.FAILED, 5 * S));
        assertNull(InferenceStatusPhrases.phrase(ChatMessage.InferencePhase.IDLE, 5 * S));
        assertNull(InferenceStatusPhrases.phrase(null, 5 * S));
    }
}
