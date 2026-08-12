package com.oilquiz.app.ai.speech.tts;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig;
import com.oilquiz.app.ai.speech.core.SpeechHttpClient;
import com.oilquiz.app.ai.speech.core.SpeechModelSelector;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 百度语音合成引擎（HTTP REST API）
 *
 * <h3>接入方式</h3>
 * HTTP POST {@code https://tsn.baidu.com/text2audio}
 *
 * <h3>鉴权</h3>
 * 先通过 API Key + Secret Key 获取 Access Token，再在请求中携带 Token。
 * config.apiKey → 百度 API Key
 * config.apiSecret → 百度 Secret Key（通过 OnlineModelConfig.apiSecret 传入）
 *
 * <h3>协议</h3>
 * 表单请求（tex/lan/ctp/cuid/per/spd/pit/vol/aue），返回音频二进制。
 *
 * @see <a href="https://ai.baidu.com/ai-doc/SPEECH/Jkdepooz7">百度语音合成 API 文档</a>
 */
public class BaiduTtsEngine implements TtsEngine {

    private static final String TAG = "BaiduTtsEngine";
    private static final String TTS_URL = "https://tsn.baidu.com/text2audio";
    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final String DEFAULT_VOICE = "4";  // 情感女声
    private static final int TIMEOUT_MS = 30000;

    private static final long TOKEN_CACHE_TTL = 25 * 60 * 60 * 1000L;  // 25 小时

    private final Context context;
    private final Gson gson = new Gson();

    /** Token 缓存（apiKey -> {token, timestamp}） */
    private static volatile String cachedToken;
    private static volatile long cachedTokenTime;
    private static volatile String cachedTokenKey;

    public BaiduTtsEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean handles(String apiUrl) {
        return SpeechModelSelector.isBaiduEndpoint(apiUrl);
    }

    @Override
    public File synthesize(OnlineModelConfig config, String modelName, String text, String voice)
            throws Exception {
        String apiKey = config.apiKey;
        String secretKey = config.apiSecret;

        if (apiKey == null || apiKey.isEmpty()) {
            throw new Exception("百度TTS缺少 API Key，请在模型管理页设置");
        }
        if (secretKey == null || secretKey.isEmpty()) {
            throw new Exception("百度TTS缺少 Secret Key，请在模型管理页设置");
        }

        String actualVoice = (voice != null && !voice.isEmpty()) ? voice : DEFAULT_VOICE;
        AILogger.i(TAG, "百度TTS: voice=" + actualVoice + " textLen=" + text.length());

        // 1. 获取 Access Token
        String token = getAccessToken(apiKey, secretKey);

        // 2. 构建请求参数
        StringBuilder params = new StringBuilder();
        params.append("tex=").append(URLEncoder.encode(text, "UTF-8"));
        params.append("&lan=zh");
        params.append("&ctp=1");  // 客户端类型：web
        params.append("&cuid=smartquiz_android");
        params.append("&tok=").append(token);
        params.append("&per=").append(actualVoice);
        params.append("&spd=5");
        params.append("&pit=5");
        params.append("&vol=5");
        params.append("&aue=3");  // mp3

        // 3. 发送请求（百度需要自定义 URL，不使用 openPost）
        String fullUrl = TTS_URL + "?access_token=" + token;
        java.net.URL url = new java.net.URL(fullUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(params.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            SpeechHttpClient.assertOk(conn, "百度TTS");

            // 判断返回类型
            String contentType = conn.getContentType();
            if (contentType != null && contentType.contains("audio")) {
                // 音频二进制
                File audioFile = SpeechHttpClient.newAudioFile(
                        context.getCacheDir(), "tts_baidu_", ".mp3");
                try (InputStream is = conn.getInputStream()) {
                    SpeechHttpClient.pipeToFile(is, audioFile);
                }
                if (audioFile.length() == 0) {
                    audioFile.delete();
                    throw new Exception("百度语音合成返回空音频");
                }
                return audioFile;
            } else {
                // JSON 错误响应
                String body = SpeechHttpClient.readBody(conn);
                throw new Exception("百度TTS合成失败: " + body);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 获取百度 Access Token（带缓存）
     */
    private String getAccessToken(String apiKey, String secretKey) throws Exception {
        // 缓存命中
        if (cachedToken != null && apiKey.equals(cachedTokenKey)
                && System.currentTimeMillis() - cachedTokenTime < TOKEN_CACHE_TTL) {
            return cachedToken;
        }

        String url = TOKEN_URL + "?grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(apiKey, "UTF-8")
                + "&client_secret=" + URLEncoder.encode(secretKey, "UTF-8");

        HttpURLConnection conn = SpeechHttpClient.openPost(url, "");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);

        try {
            String body = SpeechHttpClient.readBody(conn);
            JsonObject json = gson.fromJson(body, JsonObject.class);
            if (json == null || !json.has("access_token")) {
                String err = json != null && json.has("error_description")
                        ? json.get("error_description").getAsString() : body;
                throw new Exception("百度Token获取失败: " + err);
            }
            String token = json.get("access_token").getAsString();
            cachedToken = token;
            cachedTokenTime = System.currentTimeMillis();
            cachedTokenKey = apiKey;
            return token;
        } finally {
            conn.disconnect();
        }
    }
}
