package com.oilquiz.app.ai.speech.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.model.OnlineModelManager;

import org.junit.Test;

/**
 * 2026-11-04 真机事故的回归：语音合成/识别把「端点的聊天模型」当成了语音模型。
 *
 * <p>现场：阿里云端点 modelName = {@code qwen3.8-flash}（聊天模型），TTS 每轮都拿它去合成，
 * 百炼回 {@code ModelNotFound}（statusCode 44），一天 25 次；每次都要先白等一次失败，
 * 再回退系统 TTS。旧实现只拦「名字像 ASR 的」，不拦「既不是 TTS 也不是 ASR 的聊天模型」。
 */
public class SpeechModelSelectorSpeechFamilyTest {

    private static OnlineModelManager.OnlineModelConfig endpoint(String name, String apiUrl, String model) {
        OnlineModelManager.OnlineModelConfig c = new OnlineModelManager.OnlineModelConfig();
        c.name = name;
        c.apiUrl = apiUrl;
        c.modelName = model;
        c.selectedModel = model;
        return c;
    }

    private static OnlineModelManager.OnlineModelConfig dashscope(String model) {
        return endpoint("阿里云", "https://dashscope.aliyuncs.com/compatible-mode/v1", model);
    }

    @Test
    public void chatModelNeverBecomesSpeechModel() {
        OnlineModelManager.OnlineModelConfig cfg = dashscope("qwen3.8-flash");
        assertEquals("TTS 必须回落到百炼默认语音模型",
                "qwen3-tts-flash",
                SpeechModelSelector.resolveModelName(cfg, null, SpeechModelSelector.Capability.TTS));
        assertEquals("ASR 必须回落到百炼默认语音模型",
                "qwen3-asr-flash",
                SpeechModelSelector.resolveModelName(cfg, null, SpeechModelSelector.Capability.ASR));
    }

    @Test
    public void realSpeechModelsAreKept() {
        assertEquals("cosyvoice-v2",
                SpeechModelSelector.resolveModelName(dashscope("cosyvoice-v2"), null,
                        SpeechModelSelector.Capability.TTS));
        assertEquals("qwen3-asr-flash",
                SpeechModelSelector.resolveModelName(dashscope("qwen3-asr-flash"), null,
                        SpeechModelSelector.Capability.ASR));
    }

    @Test
    public void explicitOverrideStillWins() {
        // 用户在「功能专用模型」里显式选了 TTS 模型 → 即使端点 selectedModel 是聊天模型也照用
        assertEquals("qwen3-tts-flash",
                SpeechModelSelector.resolveModelName(dashscope("qwen3.8-flash"), "qwen3-tts-flash",
                        SpeechModelSelector.Capability.TTS));
    }

    @Test
    public void customEndpointIsNotSecondGuessed() {
        OnlineModelManager.OnlineModelConfig custom =
                endpoint("自建", "https://my-llm.local/v1", "my-voice-v3");
        assertEquals("未知端点没有默认语音模型可回落，只能按用户填的走",
                "my-voice-v3",
                SpeechModelSelector.resolveModelName(custom, null, SpeechModelSelector.Capability.TTS));
    }

    @Test
    public void xfyunNamesWithoutTtsKeywordAreStillSpeech() {
        OnlineModelManager.OnlineModelConfig xf =
                endpoint("讯飞", "https://www.xfyun.cn/v1", "x4_xiaoyan");
        assertEquals("x4_xiaoyan",
                SpeechModelSelector.resolveModelName(xf, null, SpeechModelSelector.Capability.TTS));

        OnlineModelManager.OnlineModelConfig xfAsr =
                endpoint("讯飞", "https://www.xfyun.cn/v1", "16k_zh");
        assertEquals("16k_zh",
                SpeechModelSelector.resolveModelName(xfAsr, null, SpeechModelSelector.Capability.ASR));
    }

    @Test
    public void familyCheckCoversBothSides() {
        assertTrue(SpeechModelSelector.inSpeechFamily("qwen3-tts-flash", SpeechModelSelector.Capability.TTS));
        assertTrue(SpeechModelSelector.inSpeechFamily("cosyvoice-v2", SpeechModelSelector.Capability.TTS));
        assertTrue(SpeechModelSelector.inSpeechFamily("x4_xiaoyan", SpeechModelSelector.Capability.TTS));
        assertTrue(SpeechModelSelector.inSpeechFamily("16k_zh", SpeechModelSelector.Capability.ASR));
        assertTrue(SpeechModelSelector.inSpeechFamily("paraformer-realtime-v2",
                SpeechModelSelector.Capability.ASR));
        assertFalse("聊天模型不是语音模型",
                SpeechModelSelector.inSpeechFamily("qwen3.8-flash", SpeechModelSelector.Capability.TTS));
        assertFalse(SpeechModelSelector.inSpeechFamily("qwen3.8-flash",
                SpeechModelSelector.Capability.ASR));
    }
}
