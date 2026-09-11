package com.oilquiz.app.ai.spi;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import com.oilquiz.app.ai.chat.ChatModeManager;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.speech.SpeechManager;
import com.oilquiz.app.ai.speech.TTSService;
import com.oilquiz.app.ai.speech.asr.SenseVoiceAsr;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;

import java.io.File;
import java.util.Map;

/**
 * AppServices 的 Android 默认实现（桥接既有单例与资源系统）。
 *
 * 实现全部 SPI 接口；由 {@link AppServices#install(Context)} 注册。
 * 替换任意接口即可实现定制（如云端文案 StringProvider、测试 mock）。
 */
final class AndroidAppServices implements StringProvider, PreferenceStore,
        SpeechGateway, ModelGateway, ToolGateway, FileDirProvider {

    private static final String PREFS = "ai_chat_prefs";

    private final Context app;
    private final SharedPreferences prefs;

    AndroidAppServices(Context context) {
        this.app = context.getApplicationContext();
        this.prefs = this.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- StringProvider ----

    @Override public String get(int resId) { return app.getString(resId); }

    @Override public String get(int resId, Object... args) { return app.getString(resId, args); }

    // ---- PreferenceStore ----

    @Override public boolean getBoolean(String key, boolean def) { return prefs.getBoolean(key, def); }

    @Override public void putBoolean(String key, boolean value) { prefs.edit().putBoolean(key, value).apply(); }

    // ---- SpeechGateway ----

    private SpeechManager speech() { return SpeechManager.getInstance(app); }

    @Override public boolean isAnyAsrAvailable() { return speech().isAnyAsrAvailable(); }

    @Override public boolean isAsrAvailable() { return speech().isAsrAvailable(); }

    @Override public boolean isSpeaking() { return speech().isSpeaking(); }

    @Override public void speakLocked(String text, PlaybackListener listener) {
        speech().speakLocked(text, new TTSService.PlaybackCallback() {
            @Override public void onStart() { if (listener != null) listener.onStart(); }
            @Override public void onComplete() { if (listener != null) listener.onComplete(); }
            @Override public void onError(String error) { if (listener != null) listener.onError(error); }
        });
    }

    @Override public void stopSpeaking() { speech().stopSpeaking(); }

    @Override public boolean isLocalAsrReady() { return SenseVoiceAsr.isReady(); }

    @Override public void acquireLocalAsr(Runnable onReady, ErrorListener onError) {
        new Thread(() -> {
            try {
                SenseVoiceAsr.acquire(app);
                SenseVoiceAsr.release(); // 模型保留（TTL 缓存），完成预热
                if (onReady != null) onReady.run();
            } catch (Exception e) {
                if (onError != null) onError.onError(e);
            }
        }).start();
    }

    @Override public void releaseLocalAsr() { SenseVoiceAsr.release(); }

    // ---- ModelGateway ----

    @Override public String getFeatureModelId(String feature) {
        return OnlineModelManager.getInstance(app).getFeatureModelId(feature);
    }

    @Override public boolean isDeepThinkingEnabled() {
        return ChatModeManager.getInstance(app).isDeepThinkingEnabled();
    }

    // ---- ToolGateway ----

    @Override public AIToolResult executeTool(String toolName, Map<String, Object> params) {
        return AIToolManager.getInstance(app).executeTool(toolName, params);
    }

    // ---- FileDirProvider ----

    @Override public File getCacheDir() { return app.getCacheDir(); }

    @Override public File getExternalMusicDir() {
        File f = app.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        return f != null ? f : app.getCacheDir();
    }
}
