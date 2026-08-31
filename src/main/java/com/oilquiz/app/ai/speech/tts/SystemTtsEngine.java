package com.oilquiz.app.ai.speech.tts;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.oilquiz.app.ai.speech.TTSService;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 系统 TTS 引擎（离线兜底）
 *
 * 封装 Android 系统 TextToSpeech，将文本合成到本地 wav 文件。
 * 支持 sys: 前缀的系统音色选择，对外暴露标准 Voice 列表。
 *
 * 从原 TTSService 中抽取，使 TTSService 不再混合离线/在线/网络多套实现。
 */
public class SystemTtsEngine {

    private static final String TAG = "SystemTtsEngine";

    private final Context context;
    private volatile TextToSpeech systemTts;
    private final AtomicBoolean systemTtsReady = new AtomicBoolean(false);

    public SystemTtsEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 合成文本到文件（离线兜底）
     *
     * <p><b>线程安全：</b>本方法整体 {@code synchronized}。系统 TTS 引擎为单例且
     * {@code setVoice} 设置的是引擎全局音色，而 {@code synthesizeToFile} 是异步的，
     * 若不串行化，流式朗读并发调用时会互相覆盖全局音色导致句间音色串台。
     * 串行化后"应用音色 → 合成 → 等待完成"成为原子操作，保证同一条消息音色始终一致。
     *
     * @param voiceId 音色 ID（sys: 前缀的系统音色会被应用，其他忽略）
     * @return 合成后的音频文件（wav）
     */
    public synchronized File synthesize(String text, String voiceId) throws Exception {
        ensureInitialized();
        // 系统 TTS 引擎为异步初始化，首次调用时等待其就绪（最多 5s）
        if (!systemTtsReady.get()) {
            for (int i = 0; i < 50 && !systemTtsReady.get(); i++) {
                Thread.sleep(100);
            }
        }
        if (!systemTtsReady.get()) {
            throw new Exception("系统TTS引擎初始化失败，请检查设备是否安装TTS引擎");
        }

        File audioFile = new File(context.getCacheDir(),
                "tts_sys_" + System.currentTimeMillis() + ".wav");

        CompletableFuture<Boolean> done = new CompletableFuture<>();
        // 必须在 synthesizeToFile 之前注册监听：合成若极快完成会先触发 onDone，
        // 若监听器晚于 synthesizeToFile 注册将丢失回调，导致 done 永远不完成而 120s 超时误报。
        systemTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                done.complete(true);
            }

            @Override
            public void onError(String utteranceId) {
                done.complete(false);
            }
        });

        applyVoice(voiceId);

        Bundle params = new Bundle();
        int ret = systemTts.synthesizeToFile(text, params, audioFile,
                "tts_utterance_" + System.currentTimeMillis());
        if (ret != TextToSpeech.SUCCESS) {
            throw new Exception("系统TTS合成启动失败");
        }

        Boolean success = done.get(120000, TimeUnit.MILLISECONDS);
        if (success == null || !success || !audioFile.exists() || audioFile.length() == 0) {
            throw new Exception("系统TTS合成失败");
        }
        return audioFile;
    }

    /** 获取系统 TTS 引擎内置音色列表（中文音色优先展示） */
    public List<TTSService.Voice> getVoices() {
        List<TTSService.Voice> result = new ArrayList<>();
        try {
            ensureInitialized();
            for (int i = 0; i < 30 && !systemTtsReady.get(); i++) {
                Thread.sleep(100);
            }
            if (systemTts == null || !systemTtsReady.get()) {
                return result;
            }
            java.util.Set<android.speech.tts.Voice> voices = systemTts.getVoices();
            if (voices == null) {
                return result;
            }
            HashSet<String> seen = new HashSet<>();
            for (android.speech.tts.Voice v : voices) {
                if (v == null || v.getName() == null || v.getName().isEmpty()) {
                    continue;
                }
                if (!seen.add(v.getName())) {
                    continue;
                }
                String locale = v.getLocale() != null ? v.getLocale().toString() : "";
                result.add(new TTSService.Voice(
                        TTSService.SYS_VOICE_PREFIX + v.getName(),
                        v.getName() + "（系统·" + locale + "）"));
            }
            // 中文音色优先展示
            Collections.sort(result, (a, b) -> {
                String al = a.id.toLowerCase();
                String bl = b.id.toLowerCase();
                boolean az = al.contains("zh") || al.contains("cmn");
                boolean bz = bl.contains("zh") || bl.contains("cmn");
                return Boolean.compare(bz, az);
            });
            AILogger.i(TAG, "获取到 " + result.size() + " 个系统TTS音色");
        } catch (Exception e) {
            AILogger.w(TAG, "获取系统TTS音色列表失败: " + e.getMessage());
        }
        return result;
    }

    private synchronized void ensureInitialized() {
        if (systemTts != null) {
            return;
        }
        systemTts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = systemTts.setLanguage(Locale.CHINESE);
                if (result == TextToSpeech.LANG_MISSING_DATA
                        || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    systemTts.setLanguage(Locale.getDefault());
                }
                systemTtsReady.set(true);
                AILogger.i(TAG, "系统TTS引擎初始化成功");
            } else {
                AILogger.w(TAG, "系统TTS引擎初始化失败: " + status);
            }
        });
    }

    /** 当前已应用的系统音色名（避免每次重复 setVoice 触发引擎状态抖动） */
    private String appliedVoiceName = null;
    /** 未指定系统音色时的稳定中文默认音色（缓存到成员，保证整轮合成音色一致） */
    private String defaultZhVoiceName = null;

    /**
     * 合成前应用音色：
     * 用户指定 sys: 前缀音色 → 应用该音色；
     * 未指定/非系统音色（在线音色回退系统TTS场景）→ 锁定稳定的中文默认音色，
     * 不再依赖引擎残留/随机音色，从根本上消除"本地语音合成音色漂移"。
     * 音色未变化时跳过 setVoice（避免反复设置引起引擎重初始化）。
     */
    private void applyVoice(String voiceId) {
        if (systemTts == null) {
            return;
        }
        String targetName = null;
        if (voiceId != null && voiceId.startsWith(TTSService.SYS_VOICE_PREFIX)) {
            targetName = voiceId.substring(TTSService.SYS_VOICE_PREFIX.length());
        } else {
            targetName = findDefaultZhVoiceName();
        }
        if (targetName == null || targetName.equals(appliedVoiceName)) {
            return; // 无可用音色或音色未变化
        }
        try {
            java.util.Set<android.speech.tts.Voice> voices = systemTts.getVoices();
            if (voices == null) {
                return;
            }
            for (android.speech.tts.Voice v : voices) {
                if (v != null && targetName.equals(v.getName())) {
                    systemTts.setVoice(v);
                    appliedVoiceName = targetName;
                    AILogger.i(TAG, "应用系统TTS音色: " + targetName);
                    return;
                }
            }
            AILogger.w(TAG, "未找到系统音色: " + targetName);
        } catch (Exception e) {
            AILogger.w(TAG, "应用系统音色失败: " + e.getMessage());
        }
    }

    /** 查找稳定的默认中文音色：优先 zh/cmn 音色，其次任意音色；结果缓存，整轮合成音色保持一致 */
    private String findDefaultZhVoiceName() {
        if (defaultZhVoiceName != null) {
            return defaultZhVoiceName;
        }
        try {
            if (systemTts == null) {
                return null;
            }
            java.util.Set<android.speech.tts.Voice> voices = systemTts.getVoices();
            if (voices == null) {
                return null;
            }
            for (android.speech.tts.Voice v : voices) {
                if (v != null && v.getName() != null) {
                    String n = v.getName().toLowerCase();
                    if (n.contains("zh") || n.contains("cmn")) {
                        defaultZhVoiceName = v.getName();
                        return defaultZhVoiceName;
                    }
                }
            }
            for (android.speech.tts.Voice v : voices) {
                if (v != null && v.getName() != null) {
                    defaultZhVoiceName = v.getName();
                    return defaultZhVoiceName;
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "查找默认中文音色失败: " + e.getMessage());
        }
        return null;
    }

    /** 释放系统 TTS 资源 */
    public void shutdown() {
        if (systemTts != null) {
            try {
                systemTts.stop();
                systemTts.shutdown();
            } catch (Exception ignored) {
            }
            systemTts = null;
            systemTtsReady.set(false);
        }
    }
}
