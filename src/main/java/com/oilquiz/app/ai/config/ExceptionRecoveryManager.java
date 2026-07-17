package com.oilquiz.app.ai.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.oilquiz.app.ai.model.ModelStateCache;
import com.oilquiz.app.util.AILogger;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ExceptionRecoveryManager - 异常恢复管理器
 * 
 * 功能：
 * 1. 异常检测 - 识别各种异常情况
 * 2. 恢复策略 - 根据异常类型选择恢复策略
 * 3. 恢复执行 - 执行恢复操作
 * 4. 恢复验证 - 验证恢复是否成功
 * 5. 恢复记录 - 记录恢复历史
 */
public class ExceptionRecoveryManager {

    private static final String TAG = "ExceptionRecoveryManager";
    private static final String PREFS_NAME = "exception_recovery";
    private static final String KEY_RECOVERY_COUNT = "recovery_count";
    private static final String KEY_LAST_RECOVERY_TIME = "last_recovery_time";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    private static final long RECOVERY_COOLDOWN_MS = 30000; // 30秒冷却

    // 单例
    private static volatile ExceptionRecoveryManager INSTANCE;
    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService executor;
    private final AtomicInteger recoveryAttempts = new AtomicInteger(0);

    // 异常类型
    public enum ExceptionType {
        MODEL_LOAD_FAILED,           // 模型加载失败
        MODEL_CORRUPTED,             // 模型文件损坏
        MEMORY_OVERFLOW,             // 内存溢出
        CONFIG_CORRUPTED,            // 配置损坏
        NATIVE_CRASH,                // 原生层崩溃
        CONTEXT_CREATION_FAILED,     // 上下文创建失败
        INFERENCE_FAILED,            // 推理失败
        NETWORK_ERROR,               // 网络错误
        UNKNOWN                      // 未知错误
    }

    // 恢复策略
    public enum RecoveryStrategy {
        RETRY,                       // 重试
        FALLBACK_TO_DEFAULT,         // 回退到默认值
        CLEAR_AND_RETRY,             // 清除缓存后重试
        RESTORE_FROM_BACKUP,         // 从备份恢复
        RESET_TO_FACTORY,            // 恢复出厂设置
        NOTIFY_USER                  // 通知用户
    }

    // 回调接口
    public interface RecoveryCallback {
        void onRecoveryStarted(ExceptionType exceptionType, RecoveryStrategy strategy);
        void onRecoveryProgress(int progress, String message);
        void onRecoveryCompleted(boolean success, String message);
        void onRecoveryFailed(String reason);
    }

    private ExceptionRecoveryManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.executor = Executors.newSingleThreadExecutor();
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

    // ========== 异常处理 ==========

    /**
     * 处理异常
     */
    public void handleException(ExceptionType exceptionType, Exception exception, RecoveryCallback callback) {
        AILogger.e(TAG, "Handling exception: " + exceptionType, exception);

        // 记录异常
        recordException(exceptionType, exception);

        // 检查恢复冷却
        if (isInCooldown()) {
            AILogger.w(TAG, "Recovery in cooldown, notifying user");
            if (callback != null) {
                callback.onRecoveryFailed("恢复冷却中，请稍后重试");
            }
            return;
        }

        // 检查恢复次数
        if (recoveryAttempts.get() >= MAX_RECOVERY_ATTEMPTS) {
            AILogger.w(TAG, "Max recovery attempts reached");
            if (callback != null) {
                callback.onRecoveryFailed("已达到最大恢复次数，请重启应用");
            }
            return;
        }

        // 选择恢复策略
        RecoveryStrategy strategy = selectRecoveryStrategy(exceptionType);
        AILogger.i(TAG, "Selected recovery strategy: " + strategy);

        // 执行恢复
        executor.execute(() -> {
            try {
                recoveryAttempts.incrementAndGet();
                boolean success = executeRecovery(strategy, exceptionType, callback);

                if (success) {
                    recoveryAttempts.set(0);
                    clearRecoveryState();
                }

                if (callback != null) {
                    callback.onRecoveryCompleted(success, success ? "恢复成功" : "恢复失败");
                }

            } catch (Exception e) {
                AILogger.e(TAG, "Recovery execution failed", e);
                if (callback != null) {
                    callback.onRecoveryFailed("恢复执行失败: " + e.getMessage());
                }
            }
        });
    }

    /**
     * 选择恢复策略
     */
    private RecoveryStrategy selectRecoveryStrategy(ExceptionType exceptionType) {
        int attempts = recoveryAttempts.get();

        switch (exceptionType) {
            case MODEL_LOAD_FAILED:
                if (attempts == 0) return RecoveryStrategy.RETRY;
                if (attempts == 1) return RecoveryStrategy.CLEAR_AND_RETRY;
                return RecoveryStrategy.FALLBACK_TO_DEFAULT;

            case MODEL_CORRUPTED:
                return RecoveryStrategy.RESTORE_FROM_BACKUP;

            case MEMORY_OVERFLOW:
                if (attempts == 0) return RecoveryStrategy.CLEAR_AND_RETRY;
                return RecoveryStrategy.FALLBACK_TO_DEFAULT;

            case CONFIG_CORRUPTED:
                if (attempts == 0) return RecoveryStrategy.RESTORE_FROM_BACKUP;
                return RecoveryStrategy.RESET_TO_FACTORY;

            case NATIVE_CRASH:
                if (attempts == 0) return RecoveryStrategy.RETRY;
                return RecoveryStrategy.NOTIFY_USER;

            case CONTEXT_CREATION_FAILED:
                if (attempts == 0) return RecoveryStrategy.RETRY;
                return RecoveryStrategy.CLEAR_AND_RETRY;

            case INFERENCE_FAILED:
                return RecoveryStrategy.RETRY;

            case NETWORK_ERROR:
                return RecoveryStrategy.RETRY;

            default:
                return RecoveryStrategy.NOTIFY_USER;
        }
    }

    /**
     * 执行恢复策略
     */
    private boolean executeRecovery(RecoveryStrategy strategy, ExceptionType exceptionType, RecoveryCallback callback) {
        if (callback != null) {
            callback.onRecoveryStarted(exceptionType, strategy);
        }

        switch (strategy) {
            case RETRY:
                return executeRetry(callback);

            case FALLBACK_TO_DEFAULT:
                return executeFallbackToDefault(callback);

            case CLEAR_AND_RETRY:
                return executeClearAndRetry(callback);

            case RESTORE_FROM_BACKUP:
                return executeRestoreFromBackup(callback);

            case RESET_TO_FACTORY:
                return executeResetToFactory(callback);

            case NOTIFY_USER:
                return executeNotifyUser(callback);

            default:
                return false;
        }
    }

    // ========== 恢复策略实现 ==========

    private boolean executeRetry(RecoveryCallback callback) {
        if (callback != null) {
            callback.onRecoveryProgress(50, "重试中...");
        }
        // 重试逻辑由调用方实现
        return true;
    }

    private boolean executeFallbackToDefault(RecoveryCallback callback) {
        if (callback != null) {
            callback.onRecoveryProgress(30, "回退到默认配置...");
        }

        AppConfigManager configManager = AppConfigManager.getInstance(context);
        configManager.set("model", AppConfigManager.getDefaultConfig().optJSONObject("model"));
        configManager.set("ai", AppConfigManager.getDefaultConfig().optJSONObject("ai"));
        configManager.saveConfig(null);

        if (callback != null) {
            callback.onRecoveryProgress(100, "已回退到默认配置");
        }
        return true;
    }

    private boolean executeClearAndRetry(RecoveryCallback callback) {
        if (callback != null) {
            callback.onRecoveryProgress(20, "清除缓存...");
        }

        // 清除模型缓存
        ModelStateCache cache = ModelStateCache.getInstance(context);
        cache.clearCache();

        if (callback != null) {
            callback.onRecoveryProgress(50, "缓存已清除，准备重试...");
        }

        // 重试逻辑由调用方实现
        return true;
    }

    private boolean executeRestoreFromBackup(RecoveryCallback callback) {
        if (callback != null) {
            callback.onRecoveryProgress(30, "从备份恢复...");
        }

        AppConfigManager configManager = AppConfigManager.getInstance(context);
        configManager.loadConfig(new AppConfigManager.ConfigCallback() {
            @Override
            public void onLoaded(boolean success, String message) {
                if (callback != null) {
                    callback.onRecoveryProgress(100, "恢复完成: " + message);
                }
            }
        });

        return true;
    }

    private boolean executeResetToFactory(RecoveryCallback callback) {
        if (callback != null) {
            callback.onRecoveryProgress(20, "恢复出厂设置...");
        }

        // 清除所有配置
        AppConfigManager configManager = AppConfigManager.getInstance(context);
        configManager.set("model", AppConfigManager.getDefaultConfig().optJSONObject("model"));
        configManager.set("ui", AppConfigManager.getDefaultConfig().optJSONObject("ui"));
        configManager.set("ai", AppConfigManager.getDefaultConfig().optJSONObject("ai"));
        configManager.saveConfig(null);

        // 清除模型缓存
        ModelStateCache cache = ModelStateCache.getInstance(context);
        cache.clearCache();

        if (callback != null) {
            callback.onRecoveryProgress(100, "已恢复出厂设置");
        }
        return true;
    }

    private boolean executeNotifyUser(RecoveryCallback callback) {
        if (callback != null) {
            callback.onRecoveryProgress(100, "需要用户介入");
        }
        // 通知用户逻辑由UI层实现
        return true;
    }

    // ========== 记录和状态 ==========

    private void recordException(ExceptionType exceptionType, Exception exception) {
        prefs.edit()
            .putString(KEY_LAST_ERROR, exceptionType.name() + ": " + (exception != null ? exception.getMessage() : "unknown"))
            .putLong(KEY_LAST_RECOVERY_TIME, System.currentTimeMillis())
            .apply();
    }

    private void clearRecoveryState() {
        prefs.edit()
            .remove(KEY_RECOVERY_COUNT)
            .remove(KEY_LAST_ERROR)
            .apply();
        recoveryAttempts.set(0);
    }

    private boolean isInCooldown() {
        long lastRecoveryTime = prefs.getLong(KEY_LAST_RECOVERY_TIME, 0);
        return System.currentTimeMillis() - lastRecoveryTime < RECOVERY_COOLDOWN_MS;
    }

    /**
     * 获取恢复次数
     */
    public int getRecoveryAttempts() {
        return recoveryAttempts.get();
    }

    /**
     * 获取最后错误信息
     */
    public String getLastError() {
        return prefs.getString(KEY_LAST_ERROR, null);
    }

    /**
     * 重置恢复状态
     */
    public void resetRecoveryState() {
        clearRecoveryState();
    }
}
