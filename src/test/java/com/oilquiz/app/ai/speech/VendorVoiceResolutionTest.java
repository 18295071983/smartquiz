package com.oilquiz.app.ai.speech;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 2026-11-04：讯飞/火山/百度的「模型名」是音色 id 或厂商默认值（x4_xiaoyan /
 * zh_female_qingxin / 4），按模型名永远匹配不到它们自己的音色表 → 音色列表掉进「全量预设」，
 * 把百炼的 Cherry/Moon 摆在讯飞用户面前。这里钉住「按端点厂商兜底匹配」这条链路。
 */
public class VendorVoiceResolutionTest {

    @Test
    public void voiceIdAsModelNameStillResolvesToTheVendorTable() {
        SpeechModelRegistry.ModelEntry xf =
                SpeechModelRegistry.matchTtsModel("x4_xiaoyan", "https://www.xfyun.cn/v1");
        assertNotNull("讯飞端点必须能匹配到自己的音色表", xf);
        assertEquals("讯飞语音合成", xf.displayName);

        List<TTSService.Voice> voices =
                SpeechModelRegistry.getVoicesForModel("x4_xiaoyan", "https://www.xfyun.cn/v1");
        assertTrue("讯飞音色表不该为空", voices.size() >= 10);
        assertEquals("x4_xiaoyan", voices.get(0).id);

        SpeechModelRegistry.ModelEntry baidu =
                SpeechModelRegistry.matchTtsModel("4", "https://aip.baidubce.com");
        assertNotNull(baidu);
        assertEquals("百度语音合成", baidu.displayName);

        SpeechModelRegistry.ModelEntry volcano =
                SpeechModelRegistry.matchTtsModel("zh_female_qingxin",
                        "https://openspeech.bytedance.com/api/v1/tts");
        assertNotNull(volcano);
        assertEquals("火山引擎语音合成", volcano.displayName);
    }

    @Test
    public void dashScopeStillMatchesByModelNameNotByVendor() {
        // 百炼一个端点下有多族模型，按厂商一刀切会串族，必须仍按模型名区分
        SpeechModelRegistry.ModelEntry cosy =
                SpeechModelRegistry.matchTtsModel("cosyvoice-v2",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertNotNull(cosy);
        assertEquals("百炼 CosyVoice v2", cosy.displayName);

        assertTrue("模型名不认识时不该硬塞一个族",
                SpeechModelRegistry.getVoicesForModel("qwen3.8-flash",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1").isEmpty());
    }
}
