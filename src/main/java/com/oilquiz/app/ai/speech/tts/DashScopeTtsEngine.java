package com.oilquiz.app.ai.speech.tts;

import android.content.Context;

import com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig;
import com.oilquiz.app.ai.speech.SpeechModelRegistry;
import com.oilquiz.app.ai.speech.core.SpeechHttpClient;
import com.oilquiz.app.ai.speech.core.SpeechModelSelector;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;

/**
 * 百炼 / DashScope 语音合成引擎
 *
 * <h3>两套模型体系</h3>
 *
 * <pre>
 * 1. Qwen-TTS / Qwen3-TTS-Flash 系列（apiType=API_DASHSCOPE_QWEN_TTS）
 *    使用 MultiModalConversation API（同聊天模型），非流式返回音频 URL，
 *    需再用 HTTP GET 下载音频文件。
 *
 * 2. CosyVoice / Sambert 系列（apiType=API_DASHSCOPE_NATIVE）
 *    使用 tts.SpeechSynthesizer，直接返回 ByteBuffer。
 * </pre>
 */
public class DashScopeTtsEngine implements TtsEngine {

    private static final String TAG = "DashScopeTtsEngine";

    /** CosyVoice 族请求的采样率 */
    private static final int COSYVOICE_SAMPLE_RATE = 24000;

    /** 兜底音色（注册表未命中且调用方未指定时使用） */
    private static final String FALLBACK_VOICE_QWEN = "Cherry";
    private static final String FALLBACK_VOICE_COSY = "longxiaochun_v2";

    /** 文本长度软上限：超出仅告警，由服务端裁决 */
    private static final int TEXT_SOFT_LIMIT = 500;

    private final Context context;

    public DashScopeTtsEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean handles(String apiUrl) {
        return SpeechModelSelector.isDashScopeEndpoint(apiUrl);
    }

    /**
     * 合成文本为音频文件
     *
     * @return 合成后的音频文件（Qwen-TTS 为 wav，CosyVoice 族为 mp3）
     */
    @Override
    public File synthesize(OnlineModelConfig config, String modelName, String text, String voice)
            throws Exception {
        int apiType = SpeechModelRegistry.getTtsApiType(modelName);
        boolean isQwenTts = apiType == SpeechModelRegistry.API_DASHSCOPE_QWEN_TTS;
        String actualVoice = resolveVoice(modelName, voice, isQwenTts);

        if (text != null && text.length() > TEXT_SOFT_LIMIT) {
            AILogger.w(TAG, "合成文本长度 " + text.length() + " 超过建议上限，服务端可能截断或报错");
        }
        AILogger.i(TAG, "百炼TTS(SDK): model=" + modelName + " voice=" + actualVoice
                + " api=" + (isQwenTts ? "MultiModalConversation(Qwen-TTS)" : "ttsv2(CosyVoice)"));

        ByteBuffer audio;
        String extension;
        if (isQwenTts) {
            // Qwen-TTS / Qwen3-TTS-Flash：使用 MultiModalConversation API
            audio = synthesizeWithMultiModal(config.apiKey, modelName, text, actualVoice);
            extension = ".wav";
        } else {
            // CosyVoice / Sambert 系列：使用 tts.SpeechSynthesizer
            com.alibaba.dashscope.audio.tts.SpeechSynthesisParam param =
                    com.alibaba.dashscope.audio.tts.SpeechSynthesisParam.builder()
                            .apiKey(config.apiKey)
                            .model(modelName)
                            .text(text)
                            .parameter("voice", actualVoice)
                            .format(com.alibaba.dashscope.audio.tts.SpeechSynthesisAudioFormat.MP3)
                            .sampleRate(COSYVOICE_SAMPLE_RATE)
                            .build();
            com.alibaba.dashscope.audio.tts.SpeechSynthesizer synthesizer =
                    new com.alibaba.dashscope.audio.tts.SpeechSynthesizer();
            audio = synthesizer.call(param);
            extension = ".mp3";
        }

        if (audio == null || audio.remaining() == 0) {
            throw new Exception("语音合成返回空音频");
        }
        return writeAudioFile(audio, extension);
    }

    /**
     * 使用 MultiModalConversation API 合成 Qwen-TTS / Qwen3-TTS-Flash 音频。
     * 非流式返回音频 URL，需要下载。
     */
    private ByteBuffer synthesizeWithMultiModal(String apiKey, String model, String text, String voice)
            throws Exception {
        try {
            // 设置百炼 API URL（北京地域）
            com.alibaba.dashscope.utils.Constants.baseHttpApiUrl =
                    "https://dashscope.aliyuncs.com/api/v1";

            com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam param =
                    com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam.builder()
                            .apiKey(apiKey)
                            .model(model)
                            .text(text)
                            .voice(resolveVoiceParam(voice))
                            .languageType(mapLanguageToDashScope(text))
                            .build();

            com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation conv =
                    new com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation();
            com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult result =
                    conv.call(param);

            String audioUrl = result.getOutput().getAudio().getUrl();
            if (audioUrl == null || audioUrl.isEmpty()) {
                throw new Exception("语音合成响应中无音频 URL: " + result);
            }

            AILogger.i(TAG, "音频下载 URL 已获取: " + audioUrl.substring(0, Math.min(80, audioUrl.length())) + "...");

            // 下载音频文件
            try (InputStream in = new URL(audioUrl).openStream()) {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                return ByteBuffer.wrap(baos.toByteArray());
            }
        } catch (com.alibaba.dashscope.exception.ApiException e) {
            AILogger.e(TAG, "MultiModalConversation API 调用失败: " + e.getMessage());
            throw new Exception("语音合成失败: " + e.getMessage(), e);
        } catch (com.alibaba.dashscope.exception.NoApiKeyException e) {
            throw new Exception("API Key 无效: " + e.getMessage(), e);
        }
    }

    /**
     * 将音色名称映射为 AudioParameters.Voice 枚举值。
     * 只包含 SDK 实际支持的音色（Qwen-TTS / Qwen3-TTS-Flash 系统音色）。
     * 如果未知音色或 SDK 中不存在该枚举，回退到默认 Cherry。
     */
    private com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters.Voice resolveVoiceParam(String voice) {
        if (voice == null) return com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters.Voice.CHERRY;
        String normalized = voice.trim();
        
        try {
            // 使用 valueOf 将字符串转为枚举（区分大小写）
            return com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters.Voice.valueOf(normalized.toUpperCase());
        } catch (IllegalArgumentException e) {
            // SDK 中不存在该枚举值，回退到默认
            AILogger.w(TAG, "未知音色 '" + voice + "'，使用默认 Cherry");
            return com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters.Voice.CHERRY;
        }
    }

    /**
     * 根据文本语种映射到 DashScope language_type
     */
    private String mapLanguageToDashScope(String text) {
        if (text == null) return "Chinese";
        // 简单判断：包含中文字符则为中文
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return "Chinese";
        }
        return "English";
    }

    /**
     * 解析实际音色：调用方指定优先，其次取注册表中该模型的推荐音色，最后按族兜底。
     * 不能统一兜底成 Cherry —— Cherry 只属于 Qwen-TTS，传给 CosyVoice 会报音色不存在。
     */
    private String resolveVoice(String modelName, String voice, boolean isQwenTts) {
        if (voice != null && !voice.isEmpty()) {
            return voice;
        }
        SpeechModelRegistry.ModelEntry entry = SpeechModelRegistry.matchTtsModel(modelName);
        if (entry != null && entry.defaultVoice != null && !entry.defaultVoice.isEmpty()) {
            return entry.defaultVoice;
        }
        return isQwenTts ? FALLBACK_VOICE_QWEN : FALLBACK_VOICE_COSY;
    }

    private File writeAudioFile(ByteBuffer data, String extension) throws Exception {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        File audioFile = SpeechHttpClient.newAudioFile(context.getCacheDir(), "tts_", extension);
        try (FileOutputStream fos = new FileOutputStream(audioFile)) {
            fos.write(bytes);
        }
        return audioFile;
    }
}
