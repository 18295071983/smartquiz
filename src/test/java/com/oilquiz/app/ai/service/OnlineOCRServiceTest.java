package com.oilquiz.app.ai.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 在线 OCR 的"按模型名猜视觉能力"（2026-11-10）。
 *
 * <p>背景：很多端点不填 {@code supportsVision}，只能靠模型名兜底。旧实现是纯族名包含匹配，
 * 会把同前缀的非视觉变体一起捞进来（{@code gpt-4o-audio-preview} 命中 {@code gpt-4o}、
 * {@code gemini-embedding-001} 命中 {@code gemini}），选中后请求 400，白跑一次 60s 才回退本地。</p>
 *
 * <p>现在先排非视觉变体（audio/realtime/transcribe/tts/embedding…），再匹配视觉族名。
 * 这组用例同时钉住"别误伤"：{@code claude-3-haiku} 确实支持图片输入，不能被砍掉。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class OnlineOCRServiceTest {

    @Test
    public void gpt4oFamilyIsVisionButAudioAndTranscribeVariantsAreNot() {
        assertTrue(OnlineOCRService.looksLikeVisionModel("gpt-4o"));
        assertTrue(OnlineOCRService.looksLikeVisionModel("gpt-4o-mini"));
        assertTrue(OnlineOCRService.looksLikeVisionModel("gpt-4o-2024-11-21"));
        assertTrue(OnlineOCRService.looksLikeVisionModel("gpt-4.1-mini"));

        assertFalse(OnlineOCRService.looksLikeVisionModel("gpt-4o-audio-preview"));
        assertFalse(OnlineOCRService.looksLikeVisionModel("gpt-4o-realtime-preview"));
        assertFalse(OnlineOCRService.looksLikeVisionModel("gpt-4o-transcribe"));
    }

    @Test
    public void geminiIsVisionButEmbeddingVariantIsNot() {
        assertTrue(OnlineOCRService.looksLikeVisionModel("gemini-1.5-pro"));
        assertTrue(OnlineOCRService.looksLikeVisionModel("gemini-2.0-flash"));

        assertFalse(OnlineOCRService.looksLikeVisionModel("gemini-embedding-001"));
    }

    @Test
    public void claudeThreeFamilyKeepsAllTiers() {
        // Claude 3 的 opus/sonnet/haiku 都支持图片输入：按族名收敛会误伤便宜型号
        assertTrue(OnlineOCRService.looksLikeVisionModel("claude-3-haiku-20240307"));
        assertTrue(OnlineOCRService.looksLikeVisionModel("claude-3-5-sonnet-20241022"));
        assertTrue(OnlineOCRService.looksLikeVisionModel("claude-sonnet-4-20250514"));
        assertTrue(OnlineOCRService.looksLikeVisionModel("claude-opus-4-20250514"));
    }

    @Test
    public void qwenAndGlmVisionSuffixesAreRecognized() {
        assertTrue(OnlineOCRService.looksLikeVisionModel("qwen2.5-vl-7b-instruct"));
        assertTrue(OnlineOCRService.looksLikeVisionModel("qwen-vl-max"));
        assertTrue(OnlineOCRService.looksLikeVisionModel("glm-4v-flash"));
        assertTrue(OnlineOCRService.looksLikeVisionModel("llava-1.5-7b"));
        assertTrue(OnlineOCRService.looksLikeVisionModel("moonshot-v1-8k-vision-preview"));

        assertFalse("纯文本同族不能被当成视觉", OnlineOCRService.looksLikeVisionModel("qwen2.5-7b-instruct"));
        assertFalse("纯文本同族不能被当成视觉", OnlineOCRService.looksLikeVisionModel("glm-4-flash"));
    }

    @Test
    public void plainTextAndEmbeddingModelsAreNotVision() {
        assertFalse(OnlineOCRService.looksLikeVisionModel("deepseek-chat"));
        assertFalse(OnlineOCRService.looksLikeVisionModel("text-embedding-v3"));
        assertFalse(OnlineOCRService.looksLikeVisionModel("BAAI/bge-m3"));
        assertFalse(OnlineOCRService.looksLikeVisionModel("rerank-multilingual-v3.0"));
    }

    @Test
    public void nullAndEmptyAreNotVision() {
        assertFalse(OnlineOCRService.looksLikeVisionModel(null));
        assertFalse(OnlineOCRService.looksLikeVisionModel(""));
        assertFalse(OnlineOCRService.looksLikeVisionModel("   "));
    }
}
