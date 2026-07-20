package com.oilquiz.app.ai.model;

import android.content.Context;

import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

/**
 * ModelHotSwitcher - 模型热切换管理器（已弃用）
 * 
 * @deprecated 请使用 {@link AIService#hotSwitchModel(String, AIService.HotSwitchCallback)} 
 * 或 {@link AIService#preloadModel(String, AIService.PreloadCallback)}
 * 
 * 此类现在作为兼容层，委托给 AIService 进行实际的热切换操作。
 * 保留此类是为了向后兼容已有的调用代码。
 */
@Deprecated
public class ModelHotSwitcher {

    private static final String TAG = "ModelHotSwitcher";

    // 单例
    private static volatile ModelHotSwitcher INSTANCE;
    private final Context context;

    private ModelHotSwitcher(Context context) {
        this.context = context.getApplicationContext();
    }

    public static ModelHotSwitcher getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ModelHotSwitcher.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ModelHotSwitcher(context);
                }
            }
        }
        return INSTANCE;
    }

    // ========== 回调接口 ==========

    public interface SwitchCallback {
        void onSwitchStarted(String fromModel, String toModel);
        void onSwitchProgress(int progress, String message);
        void onSwitchCompleted(boolean success, String model);
        void onSwitchFailed(String reason);
    }

    public interface PreloadCallback {
        void onPreloadProgress(int progress, String message);
        void onPreloadCompleted(boolean success, String modelId);
    }

    // ========== 热切换 ==========

    /**
     * 热切换模型
     * 
     * @deprecated 请使用 {@link AIService#hotSwitchModel(String, AIService.HotSwitchCallback)}
     */
    @Deprecated
    public boolean hotSwitch(String targetModelId, SwitchCallback callback) {
        AILogger.w(TAG, "ModelHotSwitcher.hotSwitch() is deprecated, use AIService.hotSwitchModel() instead");
        
        AIService aiService = AIService.getInstance(context);
        
        return aiService.hotSwitchModel(targetModelId, new AIService.HotSwitchCallback() {
            @Override
            public void onSwitchStarted(String fromModel, String toModel) {
                if (callback != null) {
                    callback.onSwitchStarted(fromModel, toModel);
                }
            }

            @Override
            public void onSwitchProgress(int progress, String message) {
                if (callback != null) {
                    callback.onSwitchProgress(progress, message);
                }
            }

            @Override
            public void onSwitchCompleted(boolean success, String model) {
                if (callback != null) {
                    callback.onSwitchCompleted(success, model);
                }
            }

            @Override
            public void onSwitchFailed(String reason) {
                if (callback != null) {
                    callback.onSwitchFailed(reason);
                }
            }
        });
    }

    // ========== 预加载 ==========

    /**
     * 预加载模型
     * 
     * @deprecated 请使用 {@link AIService#preloadModel(String, AIService.PreloadCallback)}
     */
    @Deprecated
    public boolean preloadModel(String modelId, PreloadCallback callback) {
        AILogger.w(TAG, "ModelHotSwitcher.preloadModel() is deprecated, use AIService.preloadModel() instead");
        
        AIService aiService = AIService.getInstance(context);
        
        return aiService.preloadModel(modelId, new AIService.PreloadCallback() {
            @Override
            public void onPreloadProgress(int progress, String message) {
                if (callback != null) {
                    callback.onPreloadProgress(progress, message);
                }
            }

            @Override
            public void onPreloadCompleted(String modelName) {
                if (callback != null) {
                    callback.onPreloadCompleted(true, modelName);
                }
            }

            @Override
            public void onPreloadFailed(String reason) {
                if (callback != null) {
                    callback.onPreloadCompleted(false, null);
                }
            }
        });
    }

    // ========== 状态查询 ==========

    /**
     * 获取当前模型
     */
    public String getCurrentModel() {
        AIService aiService = AIService.getInstance(context);
        return aiService.getCurrentModelName();
    }

    /**
     * 检查是否正在切换
     */
    public boolean isSwitching() {
        return false; // 委托给 AIService，此方法保留用于兼容
    }

    /**
     * 检查是否正在预加载
     */
    public boolean isPreloading() {
        return false; // 委托给 AIService，此方法保留用于兼容
    }
}
