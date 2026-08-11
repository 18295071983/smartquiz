package com.oilquiz.app.ai.speech.asr;

import com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig;

import java.io.File;

/**
 * ASR（语音识别）引擎统一接口
 *
 * 与 {@code TtsEngine} 对称：在线识别同样存在多套互不兼容的服务端协议，
 * 由 {@code SpeechRecognitionService} 按端点类型路由到具体实现。
 *
 * - {@link OpenAiAsrEngine}：OpenAI 兼容 /audio/transcriptions（multipart 上传）
 * - {@link DashScopeAsrEngine}：百炼 multimodal-generation（官方 SDK MultiModalConversation 文件识别）
 */
public interface AsrEngine {

    /** 该引擎是否接管给定端点的识别请求 */
    boolean handles(String apiUrl);

    /**
     * 将音频转写为文本
     *
     * @param config    在线模型配置（含 apiUrl / apiKey）
     * @param modelName 实际模型名
     * @param audioFile 待识别音频文件
     * @param language  语言提示（ISO-639-1，如 zh/en），可为 null 表示自动检测
     * @return 识别出的文本
     */
    String transcribe(OnlineModelConfig config, String modelName, File audioFile, String language)
            throws Exception;
}
