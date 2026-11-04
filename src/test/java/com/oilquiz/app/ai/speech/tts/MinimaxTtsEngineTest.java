package com.oilquiz.app.ai.speech.tts;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig;

import org.json.JSONObject;
import org.junit.Test;

/**
 * MiniMax TTS 引擎的协议契约（2026-11-04 新增）。
 *
 * <p>它和 OpenAI 的 /audio/speech 完全是两套：文本在 {@code text}、音色在
 * {@code voice_setting.voice_id}、音频是 {@code data.audio} 里的**十六进制**。
 * 这几条一旦写错，症状是"选了 MiniMax 就失败"，所以钉在单测里。</p>
 */
public class MinimaxTtsEngineTest {

    private static OnlineModelConfig cfg(String apiUrl, String appId) {
        OnlineModelConfig c = new OnlineModelConfig();
        c.apiUrl = apiUrl;
        c.apiKey = "k";
        c.appId = appId;
        return c;
    }

    @Test
    public void requestJsonMatchesDocumentedShape() throws Exception {
        JSONObject o = new JSONObject(MinimaxTtsEngine.buildRequestJson("speech-02-hd", "你好", "female-shaonv"));

        assertEquals("speech-02-hd", o.getString("model"));
        assertEquals("你好", o.getString("text"));
        assertEquals(false, o.getBoolean("stream"));
        assertEquals("hex", o.getString("output_format"));
        assertEquals("female-shaonv", o.getJSONObject("voice_setting").getString("voice_id"));
        assertEquals("mp3", o.getJSONObject("audio_setting").getString("format"));
        assertEquals(32000, o.getJSONObject("audio_setting").getInt("sample_rate"));
    }

    @Test
    public void urlIsCollapsedToV1T2aV2() {
        assertEquals("https://api.minimax.chat/v1/t2a_v2",
                MinimaxTtsEngine.buildUrl(cfg("https://api.minimax.chat/v1", null)));
        assertEquals("https://api.minimax.chat/v1/t2a_v2",
                MinimaxTtsEngine.buildUrl(cfg("https://api.minimax.chat/v1/t2a_v2", null)));
        // 国内站老版本要 GroupId，配置了 appId 才带
        assertEquals("https://api.minimaxi.com/v1/t2a_v2?GroupId=g123",
                MinimaxTtsEngine.buildUrl(cfg("https://api.minimaxi.com", "g123")));
    }

    @Test
    public void hexAudioIsDecodedStrictly() throws Exception {
        assertArrayEquals(new byte[]{0x48, 0x69}, MinimaxTtsEngine.decodeHex("4869"));
        assertArrayEquals(new byte[0], MinimaxTtsEngine.decodeHex(""));
        try {
            MinimaxTtsEngine.decodeHex("486");
            fail("奇数长度应当报错");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("奇数"));
        }
        try {
            MinimaxTtsEngine.decodeHex("zz");
            fail("非十六进制应当报错");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("十六进制"));
        }
    }
}
