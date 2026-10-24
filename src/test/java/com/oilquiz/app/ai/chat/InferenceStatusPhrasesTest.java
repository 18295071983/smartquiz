package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

    @Test
    public void 工具侧思考文案_随工具执行时长推进() {
        assertEquals("正在调用工具…", InferenceStatusPhrases.toolPhrase(0));
        assertEquals("正在调用工具…", InferenceStatusPhrases.toolPhrase(2 * S));
        assertEquals("工具执行中…", InferenceStatusPhrases.toolPhrase(5 * S));
        assertEquals("工具耗时较长，仍在执行…", InferenceStatusPhrases.toolPhrase(20 * S));
        assertEquals("仍在等待工具结果…", InferenceStatusPhrases.toolPhrase(45 * S));
    }

    @Test
    public void 多步深度思考_全局文案不报轮号() {
        // 业界 Progress Steps 口径：轮次/耗时属于可折叠 trace（每轮块自身"已深度思考 X 秒"），
        // 全局状态文案只描述"当前在做什么"，不出现"第 N 轮"这类内部计数
        for (long sec : new long[]{1, 6, 14, 28, 48, 90}) {
            String p = InferenceStatusPhrases.phrase(ChatMessage.InferencePhase.THINKING, sec * S);
            assertNotNull(p);
            assertFalse("思考文案不应出现轮号: " + p, p.contains("轮"));
            assertFalse("思考文案不应出现'第': " + p, p.contains("第"));
        }
        assertEquals("正在撰写回答…", InferenceStatusPhrases.phrase(
                ChatMessage.InferencePhase.GENERATING, 5 * S));
    }

    @Test
    public void 完成态文案_已完成与已停止带耗时() {
        assertEquals("已完成 · 12.3 秒", InferenceStatusPhrases.completionPhrase(
                InferenceStatusPhrases.FINISH_COMPLETED, 12_345));
        assertEquals("已停止 · 5 秒", InferenceStatusPhrases.completionPhrase(
                InferenceStatusPhrases.FINISH_STOPPED, 5_000));
        assertEquals("已完成 · 0.5 秒", InferenceStatusPhrases.completionPhrase(
                InferenceStatusPhrases.FINISH_COMPLETED, 543));
        assertEquals("已停止 · 1 分 5 秒", InferenceStatusPhrases.completionPhrase(
                InferenceStatusPhrases.FINISH_STOPPED, 65_000));
        // 未知结束类型不产生活动文案（避免结束路径误显示"已完成"）
        assertNull(InferenceStatusPhrases.completionPhrase(0, 1_000));
    }
}
