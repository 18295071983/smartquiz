package com.oilquiz.app.ai.speech.tts;

import com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig;

import java.io.File;

/**
 * TTS 引擎统一接口
 *
 * 重构前，OpenAI / DashScope / 系统三种合成实现散落在 TTSService 这一上帝类中。
 * 本接口将"在线合成"抽象为可插拔引擎，由 {@code TTSService} 按端点类型路由：
 * - {@link OpenAiTtsEngine}：OpenAI 兼容 /audio/speech
 * - {@link DashScopeTtsEngine}：百炼原生 SpeechSynthesizer（HTTP，稳定可靠）
 * 系统 TTS 作为离线兜底单独由 {@link SystemTtsEngine} 承担。
 */
public interface TtsEngine {

    /** 该引擎是否接管给定端点的合成请求 */
    boolean handles(String apiUrl);

    /**
     * 合成文本为音频文件
     *
     * @param config    在线模型配置（含 apiUrl / apiKey）
     * @param modelName 实际模型名
     * @param text      待合成文本
     * @param voice     音色 ID
     * @return 合成后的音频文件
     */
    File synthesize(OnlineModelConfig config, String modelName, String text, String voice)
            throws Exception;
}
