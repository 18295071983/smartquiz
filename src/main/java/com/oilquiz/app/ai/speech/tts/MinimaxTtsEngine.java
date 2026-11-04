package com.oilquiz.app.ai.speech.tts;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig;
import com.oilquiz.app.ai.speech.core.SpeechHttpClient;
import com.oilquiz.app.ai.speech.core.SpeechModelSelector;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * MiniMax 语音合成（同步 HTTP）——{@code POST {base}/v1/t2a_v2}。
 *
 * <p>为什么需要单独一个引擎：MiniMax 的 TTS 协议和 OpenAI 的 {@code /audio/speech} 完全不是一回事。
 * 文本走 {@code text}，音色走 {@code voice_setting.voice_id}，音频放在响应
 * {@code data.audio} 里、且是**十六进制字符串**（{@code output_format=hex}）。旧实现把 MiniMax
 * 当 OpenAI 兼容端点，请求直接打到 {@code /audio/speech}，它并不提供这个路径——选了必失败。</p>
 *
 * <p>接口文档：<a href="https://platform.minimax.io/docs/api-reference/speech-t2a-http">Synchronous
 * Text to Speech</a>。</p>
 */
public class MinimaxTtsEngine implements TtsEngine {

    private static final String TAG = "MinimaxTtsEngine";

    /** 注册表里 MiniMax 的推荐音色；调用方没指定时用它 */
    private static final String DEFAULT_VOICE = "female-shaonv";
    /** providers.json 里配的默认模型；模型名为空时兜底 */
    private static final String DEFAULT_MODEL = "speech-02-hd";

    private final Context context;
    private final Gson gson = new Gson();

    public MinimaxTtsEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean handles(String apiUrl) {
        return SpeechModelSelector.isMinimaxEndpoint(apiUrl);
    }

    @Override
    public File synthesize(OnlineModelConfig config, String modelName, String text, String voice)
            throws Exception {
        String apiKey = config.apiKey;
        if (apiKey == null || apiKey.isEmpty()) {
            throw new Exception("MiniMax TTS 缺少 API Key，请在「服务商密钥」页设置");
        }
        if (text == null || text.trim().isEmpty()) {
            throw new Exception("MiniMax TTS 合成文本为空");
        }
        String model = (modelName == null || modelName.isEmpty()) ? DEFAULT_MODEL : modelName;
        String actualVoice = (voice == null || voice.isEmpty()) ? DEFAULT_VOICE : voice;

        String url = buildUrl(config);
        String body = buildRequestJson(model, text, actualVoice);
        AILogger.i(TAG, "MiniMax TTS: model=" + model + " voice=" + actualVoice
                + " textLen=" + text.length() + " url=" + url);

        HttpURLConnection conn = SpeechHttpClient.openPostJson(url, apiKey);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        try {
            SpeechHttpClient.assertOk(conn, "MiniMax 语音合成");
            return parseAndWrite(SpeechHttpClient.readBody(conn));
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 接口地址：把配置里的 apiUrl 收敛到根（去掉尾部斜杠与 /v1 之后的路径），再拼 {@code /v1/t2a_v2}。
     * 国内站老版本要求 {@code ?GroupId=}，这里只在配置了 appId 时才带——带了不认的站点也不会受影响。
     */
    static String buildUrl(OnlineModelConfig config) {
        String base = config.apiUrl == null ? "" : config.apiUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        int v1 = base.indexOf("/v1");
        if (v1 > 0) base = base.substring(0, v1);
        String url = base + "/v1/t2a_v2";
        String groupId = config.appId;
        if (groupId != null && !groupId.trim().isEmpty()) {
            url += "?GroupId=" + groupId.trim();
        }
        return url;
    }

    /** 请求体（字段名与文档一致；抽成静态方法便于单测） */
    static String buildRequestJson(String model, String text, String voice) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("text", text);
        body.addProperty("stream", false);
        // 明确要 hex：非流式下服务端默认就是 hex，但写出来避免以后默认值变化
        body.addProperty("output_format", "hex");

        JsonObject voiceSetting = new JsonObject();
        voiceSetting.addProperty("voice_id", voice);
        voiceSetting.addProperty("speed", 1);
        voiceSetting.addProperty("vol", 1);
        voiceSetting.addProperty("pitch", 0);
        body.add("voice_setting", voiceSetting);

        JsonObject audioSetting = new JsonObject();
        audioSetting.addProperty("sample_rate", 32000);
        audioSetting.addProperty("bitrate", 128000);
        audioSetting.addProperty("format", "mp3");
        audioSetting.addProperty("channel", 1);
        body.add("audio_setting", audioSetting);
        return body.toString();
    }

    /** 解析响应：{@code base_resp.status_code} 非 0 即失败；{@code data.audio} 是十六进制音频 */
    private File parseAndWrite(String body) throws Exception {
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            throw new Exception("MiniMax TTS 返回非 JSON: " + truncate(body));
        }
        if (root.has("base_resp") && root.get("base_resp").isJsonObject()) {
            JsonObject base = root.getAsJsonObject("base_resp");
            int code = base.has("status_code") ? base.get("status_code").getAsInt() : 0;
            if (code != 0) {
                String msg = base.has("status_msg") ? base.get("status_msg").getAsString() : "";
                throw new Exception("MiniMax TTS 失败: " + msg + "（code=" + code + "）");
            }
        }
        if (!root.has("data") || !root.get("data").isJsonObject()) {
            throw new Exception("MiniMax TTS 响应缺少 data: " + truncate(body));
        }
        JsonObject data = root.getAsJsonObject("data");
        if (!data.has("audio") || data.get("audio").isJsonNull()) {
            throw new Exception("MiniMax TTS 响应缺少音频: " + truncate(body));
        }
        byte[] audio = decodeHex(data.get("audio").getAsString());
        if (audio.length == 0) {
            throw new Exception("MiniMax TTS 返回空音频");
        }
        File out = SpeechHttpClient.newAudioFile(context.getCacheDir(), "tts_minimax_", ".mp3");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(audio);
        }
        AILogger.i(TAG, "MiniMax TTS 合成完成: " + audio.length + " bytes");
        return out;
    }

    /** 十六进制字符串转字节；非法输入抛异常（不静默返回半截音频） */
    static byte[] decodeHex(String hex) throws Exception {
        if (hex == null || hex.isEmpty()) return new byte[0];
        String s = hex.trim();
        if ((s.length() & 1) == 1) {
            throw new Exception("MiniMax TTS 音频数据长度非法（奇数长度）");
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new Exception("MiniMax TTS 音频数据不是合法十六进制");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
