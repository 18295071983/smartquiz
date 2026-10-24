package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 推理状态机短标签契约（2026-10-25）：
 * 底部生成状态行与气泡内「深度思考」折叠头必须用同一套词，否则同屏出现
 * "生成中"/"正在思考…"两种说法（用户反馈）。
 */
public class InferenceStatusLabelTest {

    @Test
    public void 思考阶段与底部状态行同词() {
        assertEquals("思考中", InferenceStatusLabel.shortLabel(ChatMessage.InferencePhase.THINKING));
        assertEquals(InferenceStatusLabel.THINKING,
                InferenceStatusLabel.shortLabel(ChatMessage.InferencePhase.THINKING));
    }

    @Test
    public void 正文阶段统一叫生成中() {
        assertEquals("生成中", InferenceStatusLabel.shortLabel(ChatMessage.InferencePhase.GENERATING));
        assertEquals("生成中", InferenceStatusLabel.shortLabel(ChatMessage.InferencePhase.DECODING));
    }

    @Test
    public void 等待类阶段统一叫等待响应() {
        for (ChatMessage.InferencePhase p : new ChatMessage.InferencePhase[]{
                ChatMessage.InferencePhase.WAITING,
                ChatMessage.InferencePhase.INITIALIZING,
                ChatMessage.InferencePhase.LOADING_MODEL,
                ChatMessage.InferencePhase.ENCODING,
                ChatMessage.InferencePhase.PREFILL}) {
            assertEquals("等待响应", InferenceStatusLabel.shortLabel(p));
        }
    }

    @Test
    public void 完成失败空闲没有活动态短标签() {
        assertNull(InferenceStatusLabel.shortLabel(ChatMessage.InferencePhase.COMPLETED));
        assertNull(InferenceStatusLabel.shortLabel(ChatMessage.InferencePhase.FAILED));
        assertNull(InferenceStatusLabel.shortLabel(ChatMessage.InferencePhase.IDLE));
        assertNull(InferenceStatusLabel.shortLabel(null));
    }

    @Test
    public void 思考激活判定_进正文与结束即不再激活() {
        // 未知阶段按"还在思考"处理，保持历史/旧引擎行为
        assertTrue(InferenceStatusLabel.isReasoningActive(null));
        assertTrue(InferenceStatusLabel.isReasoningActive(ChatMessage.InferencePhase.THINKING));
        assertTrue(InferenceStatusLabel.isReasoningActive(ChatMessage.InferencePhase.WAITING));
        assertFalse(InferenceStatusLabel.isReasoningActive(ChatMessage.InferencePhase.GENERATING));
        assertFalse(InferenceStatusLabel.isReasoningActive(ChatMessage.InferencePhase.DECODING));
        assertFalse(InferenceStatusLabel.isReasoningActive(ChatMessage.InferencePhase.COMPLETED));
        assertFalse(InferenceStatusLabel.isReasoningActive(ChatMessage.InferencePhase.FAILED));
    }
}
