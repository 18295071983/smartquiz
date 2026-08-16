package com.oilquiz.app.ai.speech;

import android.content.Context;
import android.net.Uri;

import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.speech.asr.AsrEngine;
import com.oilquiz.app.ai.speech.asr.BaiduAsrEngine;
import com.oilquiz.app.ai.speech.asr.DashScopeAsrEngine;
import com.oilquiz.app.ai.speech.asr.IflytekAsrEngine;
import com.oilquiz.app.ai.speech.asr.MimoAsrEngine;
import com.oilquiz.app.ai.speech.asr.OpenAiAsrEngine;
import com.oilquiz.app.ai.speech.asr.VolcanoAsrEngine;
import com.oilquiz.app.ai.speech.core.SpeechModelSelector;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 在线语音识别服务（ASR，Speech-to-Text）
 *
 * 将音频文件转写为文本，按端点类型路由到不同协议的识别引擎。
 *
 * <h3>为什么需要分引擎</h3>
 * OpenAI 系与百炼系的识别协议完全不同，不能共用一套请求：
 * - OpenAI 兼容端点：multipart 上传到 /audio/transcriptions，返回 {"text": "..."}
 * - 百炼端点：**没有** /audio/transcriptions，需走 multimodal-generation，
 *   音频以 Data URL 内联在 user 消息里，文本在 output.choices[0].message.content[0].text
 *
 * 旧实现只有前一套，导致配置百炼（qwen3-asr-flash 等）时在线识别必然失败。
 *
 * 设计原则：
 * 1. 优先选择支持音频能力的模型配置（委托 {@link SpeechModelSelector}）
 * 2. 按端点类型选择识别引擎（{@link #pickEngine}）
 * 3. 在线不可用时返回失败，由调用方决定兜底策略
 * 4. 支持文件路径与 content:// URI 两种输入
 */
public class SpeechRecognitionService {

    private static final String TAG = "SpeechRecognitionService";

    /** 语音识别结果 */
    public static class RecognitionResult {
        public final String text;           // 识别出的文本
        public final String modelName;      // 使用的模型名称
        public final long timestamp;        // 识别时间戳

        public RecognitionResult(String text, String modelName) {
            this.text = text;
            this.modelName = modelName;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final Context context;
    private final ExecutorService executor;
    private final OpenAiAsrEngine openAiEngine;
    private final DashScopeAsrEngine dashScopeEngine;
    private final IflytekAsrEngine iflytekEngine;
    private final VolcanoAsrEngine volcanoEngine;
    private final BaiduAsrEngine baiduEngine;
    private final MimoAsrEngine mimoEngine;

    /** 用户指定的 ASR 模型名覆盖（为 null 时自动选择） */
    private volatile String asrModelOverride = null;

    private static volatile SpeechRecognitionService INSTANCE;

    private SpeechRecognitionService(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "SpeechASR-Worker");
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        });
        this.openAiEngine = new OpenAiAsrEngine();
        this.dashScopeEngine = new DashScopeAsrEngine();
        this.iflytekEngine = new IflytekAsrEngine();
        this.volcanoEngine = new VolcanoAsrEngine();
        this.baiduEngine = new BaiduAsrEngine();
        this.mimoEngine = new MimoAsrEngine();
    }

    public static SpeechRecognitionService getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (SpeechRecognitionService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new SpeechRecognitionService(context);
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 设置 ASR 模型名覆盖（例如 whisper-1 / paraformer-v2 / qwen3-asr-flash）
     * 传 null 恢复自动选择
     */
    public void setAsrModel(String modelName) {
        this.asrModelOverride = modelName;
    }

    public String getAsrModel() {
        return asrModelOverride;
    }

    /** 是否有可用的在线 ASR 模型配置 */
    public boolean isAvailable() {
        // 优先检查是否有语音识别专用模型
        try {
            OnlineModelManager mm = OnlineModelManager.getInstance(context);
            if (mm.hasFeatureModel(OnlineModelManager.FEATURE_ASR)) {
                AILogger.d(TAG, "isAvailable: true (has FEATURE_ASR)");
                return true;
            }
        } catch (Exception e) {
            AILogger.e(TAG, "FEATURE_ASR check failed: " + e.getMessage(), e);
        }
        
        // 快速路径：检查是否有语音服务商的端点配置
        try {
            OnlineModelManager mm = OnlineModelManager.getInstance(context);
            for (OnlineModelManager.OnlineModelConfig config : mm.getModelList()) {
                if (!config.enabled) continue;
                if (config.apiUrl != null && (
                    SpeechModelSelector.isDashScopeEndpoint(config.apiUrl) ||
                    SpeechModelSelector.isXfyunEndpoint(config.apiUrl) ||
                    SpeechModelSelector.isVolcanoEndpoint(config.apiUrl) ||
                    SpeechModelSelector.isBaiduEndpoint(config.apiUrl) ||
                    SpeechModelSelector.isMimoEndpoint(config.apiUrl))) {
                    AILogger.d(TAG, "isAvailable: true (endpoint=" + config.name + ")");
                    return true;
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "isAvailable check failed: " + e.getMessage(), e);
        }
        AILogger.d(TAG, "isAvailable: false (no voice provider endpoint found)");
        return false;
    }

    /**
     * 异步识别音频文件（自动语言检测）
     */
    public CompletableFuture<RecognitionResult> recognizeAsync(File audioFile) {
        return recognizeAsync(audioFile, null);
    }

    /**
     * 异步识别音频文件
     *
     * @param audioFile 音频文件
     * @param language  语言提示（ISO-639-1，如 zh/en），可为 null 表示自动检测
     */
    public CompletableFuture<RecognitionResult> recognizeAsync(File audioFile, String language) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (audioFile == null || !audioFile.exists() || audioFile.length() == 0) {
                    throw new IllegalArgumentException("音频文件不存在或为空");
                }

                OnlineModelManager.OnlineModelConfig config =
                        SpeechModelSelector.select(context, SpeechModelSelector.Capability.ASR);
                if (config == null) {
                    throw new Exception("没有可用的在线语音识别模型配置，请在语音模型设置中选择 ASR 模型");
                }

                // 专用模型配置的具体模型名优先于手动覆盖
                String featureModelName = OnlineModelManager.getInstance(context)
                        .getFeatureModelName(OnlineModelManager.FEATURE_ASR);
                String modelName = SpeechModelSelector.resolveModelName(config,
                        featureModelName != null ? featureModelName : asrModelOverride,
                        SpeechModelSelector.Capability.ASR);
                AILogger.i(TAG, "ASR using model: " + modelName + " @ " + config.name);

                validateEndpoint(config);
                String text = pickEngine(config).transcribe(config, modelName, audioFile, language);
                if (text == null || text.trim().isEmpty()) {
                    throw new Exception("语音识别返回结果为空");
                }
                return new RecognitionResult(text.trim(), modelName);

            } catch (Exception e) {
                AILogger.e(TAG, "语音识别失败: " + e.getMessage(), e);
                throw new java.util.concurrent.CompletionException(e);
            }
        }, executor);
    }

    /**
     * 异步识别 content:// URI 指向的音频（先复制到缓存文件再上传）
     */
    public CompletableFuture<RecognitionResult> recognizeAsync(Uri audioUri, String language) {
        return CompletableFuture.supplyAsync(() -> {
            File tempFile = null;
            try {
                tempFile = copyUriToTempFile(audioUri);
                return tempFile;
            } catch (Exception e) {
                if (tempFile != null) {
                    tempFile.delete();
                }
                throw new java.util.concurrent.CompletionException(e);
            }
        }, executor).thenCompose(file -> {
            CompletableFuture<RecognitionResult> future = recognizeAsync(file, language);
            future.whenComplete((r, ex) -> file.delete());
            return future;
        });
    }

    /** 按端点类型选择识别引擎：百炼走原生多模态接口，其余走 OpenAI 兼容接口 */
    private AsrEngine pickEngine(OnlineModelManager.OnlineModelConfig config) {
        if (SpeechModelSelector.isDashScopeEndpoint(config.apiUrl)) return dashScopeEngine;
        if (SpeechModelSelector.isXfyunEndpoint(config.apiUrl)) return iflytekEngine;
        if (SpeechModelSelector.isVolcanoEndpoint(config.apiUrl)) return volcanoEngine;
        if (SpeechModelSelector.isBaiduEndpoint(config.apiUrl)) return baiduEngine;
        if (SpeechModelSelector.isMimoEndpoint(config.apiUrl)) return mimoEngine;
        return openAiEngine;
    }

    /** 请求前校验端点配置完整性，避免把空地址/空密钥的错误留到网络层才暴露 */
    private void validateEndpoint(OnlineModelManager.OnlineModelConfig config) {
        if (config.apiUrl == null || config.apiUrl.isEmpty()) {
            throw new IllegalArgumentException("API URL 不能为空");
        }
        if (config.apiKey == null || config.apiKey.isEmpty()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
    }

    /**
     * 将 content:// URI 复制到缓存文件（便于 multipart 上传）
     */
    private File copyUriToTempFile(Uri uri) throws Exception {
        if (uri == null) {
            throw new IllegalArgumentException("音频 URI 为空");
        }

        String extension = ".m4a";
        String lastPath = uri.getLastPathSegment();
        if (lastPath != null && lastPath.contains(".")) {
            extension = lastPath.substring(lastPath.lastIndexOf('.'));
        }

        File tempFile = new File(context.getCacheDir(), "asr_" + System.currentTimeMillis() + extension);
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(tempFile)) {
            if (is == null) {
                throw new Exception("无法打开音频 URI");
            }
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }
        return tempFile;
    }

    /** 释放资源 */
    public void shutdown() {
        executor.shutdownNow();
    }
}
