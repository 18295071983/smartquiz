package com.oilquiz.app.ai.spi;

/**
 * 语音引擎网关（SPI，解耦 SpeechManager / SenseVoiceAsr 单例）。
 *
 * 逻辑组件只依赖本接口完成 ASR 可用性判定、TTS 朗读、本地 ASR 模型预热；
 * 宿主实现桥接具体引擎（SpeechManager / SenseVoiceAsr / TTS 引擎族）。
 */
public interface SpeechGateway {

    /** 是否存在任一 ASR 引擎（在线/本地） */
    boolean isAnyAsrAvailable();

    /** 是否存在在线 ASR */
    boolean isAsrAvailable();

    boolean isSpeaking();

    /** 朗读文本（异步回调播放状态） */
    void speakLocked(String text, PlaybackListener listener);

    void stopSpeaking();

    // ---- 本地 ASR 模型（SenseVoice） ----

    /** 本地 ASR 模型是否已就绪 */
    boolean isLocalAsrReady();

    /** 异步获取本地 ASR 模型（预热），onReady 成功后回调 */
    void acquireLocalAsr(Runnable onReady, ErrorListener onError);

    void releaseLocalAsr();

    /** 播放状态回调 */
    interface PlaybackListener {
        void onStart();
        void onComplete();
        void onError(String error);
    }

    /** 异步错误回调 */
    interface ErrorListener {
        void onError(Exception e);
    }
}
