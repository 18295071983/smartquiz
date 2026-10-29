package com.oilquiz.app.ai.config;

import android.content.Context;

import com.oilquiz.app.ai.model.ModelStateCache;
import com.oilquiz.app.util.AILogger;

import org.json.JSONObject;

/**
 * 异常恢复（2026-10-30 收敛）。
 *
 * <p>旧版本是 9 种异常类型 × 6 种恢复策略的纸面矩阵，且 {@code RETRY}/{@code NOTIFY_USER}
 * 都是空壳（注释写明"由调用方实现"），全仓库零调用、UI 从没接过。现在只保留两个
 * <b>真实动作</b>，由本地推理失败卡片上的按钮显式触发，并写 AUDIT：</p>
 *
 * <ul>
 *   <li>{@link #clearModelCacheAndRetry()}：清 {@code ModelStateCache}（下次加载重新探测
 *       后端/上下文），然后由 UI 重新生成；</li>
 *   <li>{@link #restoreDefaultConfig()}：把 model/ai/ui 三段配置回退到出厂默认并清缓存。</li>
 * </ul>
 *
 * <p>不做"自动恢复"：是否重试由用户点按钮决定，避免静默改配置。</p>
 */
public final class ExceptionRecoveryManager {

    private static final String TAG = "ExceptionRecoveryManager";

    private static volatile ExceptionRecoveryManager INSTANCE;

    private final Context context;

    private ExceptionRecoveryManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static ExceptionRecoveryManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ExceptionRecoveryManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ExceptionRecoveryManager(context);
                }
            }
        }
        return INSTANCE;
    }

    /** 清除模型状态缓存（后端探测/上下文记录），返回是否成功。 */
    public boolean clearModelCacheAndRetry() {
        try {
            ModelStateCache.getInstance(context).clearCache();
            AILogger.audit(TAG, "本地推理失败：已清除模型缓存，准备重试");
            return true;
        } catch (Throwable t) {
            AILogger.w(TAG, "清除模型缓存失败: " + t.getMessage());
            return false;
        }
    }

    /** 回退 model/ai/ui 默认配置并清除模型缓存，返回是否成功。 */
    public boolean restoreDefaultConfig() {
        try {
            AppConfigManager configManager = AppConfigManager.getInstance(context);
            JSONObject def = AppConfigManager.getDefaultConfig();
            if (def != null) {
                configManager.set("model", def.optJSONObject("model"));
                configManager.set("ai", def.optJSONObject("ai"));
                configManager.set("ui", def.optJSONObject("ui"));
            }
            configManager.saveConfig(null);
            ModelStateCache.getInstance(context).clearCache();
            AILogger.audit(TAG, "本地推理失败：已回退默认配置并清除模型缓存");
            return true;
        } catch (Throwable t) {
            AILogger.w(TAG, "回退默认配置失败: " + t.getMessage());
            return false;
        }
    }
}
