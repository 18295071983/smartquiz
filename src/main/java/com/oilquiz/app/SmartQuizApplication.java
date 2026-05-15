package com.oilquiz.app;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatDelegate;

import com.oilquiz.app.ai.model.MultiModelManager;
import com.oilquiz.app.ai.model.ModelConfig;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.infra.GlobalExceptionHandler;

import dagger.hilt.android.HiltAndroidApp;

/**
 * 智能题库应用入口类
 * 使用 Hilt 进行依赖注入
 */
@HiltAndroidApp
public class SmartQuizApplication extends Application {

    private static final String TAG = "SmartQuizApplication";
    private static SmartQuizApplication instance;
    private int resumeCount = 0;
    private boolean isBackground = false;

    @Override
    public void onCreate() {
        applyThemeMode();
        super.onCreate();
        instance = this;
        
        // 立即初始化异常处理器（必须最先初始化）
        try {
            GlobalExceptionHandler.init(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // 在后台线程初始化所有耗时组件，避免主线程阻塞
        new Thread(() -> {
            try {
                // 初始化日志系统
                try {
                    com.oilquiz.app.infra.AppLogger.init(this);
                    com.oilquiz.app.util.AILogger.init(this);
                    com.oilquiz.app.util.AILogger.i(TAG, "日志系统初始化完成");
                } catch (Exception e) {
                    e.printStackTrace();
                }
                
                // 初始化多模型管理器
                try {
                    initMultiModelManager();
                    com.oilquiz.app.util.AILogger.i(TAG, "多模型管理器初始化完成");
                } catch (Exception e) {
                    com.oilquiz.app.util.AILogger.e(TAG, "多模型管理器初始化失败", e);
                }
                
                // 预加载AI服务（延迟一点，避免影响启动速度）
                try {
                    Thread.sleep(500); // 延迟启动，避免资源竞争
                    preloadAIServiceInternal();
                    
                    // 启动AI处理服务作为前台服务，确保应用运行时持续运行
                    startAIProcessingService();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                
                // 初始化默认用户
                try {
                    initDefaultUser();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
        
        // 立即注册应用生命周期监听，不阻塞
        registerActivityLifecycle();
    }
    
    public static SmartQuizApplication getInstance() {
        return instance;
    }
    
    private void registerActivityLifecycle() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }
            
            @Override
            public void onActivityStarted(Activity activity) {
            }
            
            @Override
            public void onActivityResumed(Activity activity) {
                resumeCount++;
                if (isBackground) {
                    // 应用从后台回到前台
                    com.oilquiz.app.util.AILogger.i("SmartQuizApplication", "应用从后台回到前台，检查AI服务状态...");
                    isBackground = false;
                    // 异步检查并恢复AI服务状态
                    new Thread(() -> {
                        try {
                            AIService aiService = AIService.getInstance(SmartQuizApplication.this);
                            // 调用AI服务的前台回调
                            aiService.onAppEnterForeground();
                            com.oilquiz.app.util.AILogger.i("SmartQuizApplication", 
                                "AI服务状态: " + aiService.getStatusInfo());
                        } catch (Exception e) {
                            com.oilquiz.app.util.AILogger.e("SmartQuizApplication", 
                                "恢复AI服务状态失败: " + e.getMessage(), e);
                        }
                    }).start();
                }
            }
            
            @Override
            public void onActivityPaused(Activity activity) {
            }
            
            @Override
            public void onActivityStopped(Activity activity) {
                resumeCount--;
                if (resumeCount == 0) {
                    // 所有Activity都停止了，应用进入后台
                    isBackground = true;
                    com.oilquiz.app.util.AILogger.i("SmartQuizApplication", "应用进入后台");
                    // 调用AI服务的后台回调
                    new Thread(() -> {
                        try {
                            AIService aiService = AIService.getInstance(SmartQuizApplication.this);
                            aiService.onAppEnterBackground();
                            // 智能资源管理 - 空闲超过30分钟可以考虑释放（但这里不强制）
                            aiService.smartResourceManagement(30 * 60 * 1000, false);
                        } catch (Exception e) {
                            com.oilquiz.app.util.AILogger.e("SmartQuizApplication", 
                                "AI服务后台处理失败: " + e.getMessage(), e);
                        }
                    }).start();
                }
            }
            
            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }
            
            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });
    }
    
    /**
     * 预加载AI服务 - 在应用启动时就初始化AI模型，实现热启动
     * 内部方法：直接调用，不启动新线程
     */
    private void preloadAIServiceInternal() {
        try {
            com.oilquiz.app.util.AILogger.i(TAG, "开始预加载AI服务...");
            
            // 调用 getInstance 仅获取单例并同步状态，不在此处阻塞加载模型
            AIService aiService = AIService.getInstance(this);
            
            if (!aiService.isInitialized()) {
                com.oilquiz.app.util.AILogger.i(TAG, "模型未初始化，尝试加载已导入的模型…");
                String[] availableModels = aiService.getAvailableModels();
                if (availableModels != null && availableModels.length > 0) {
                    String modelName = availableModels[0];
                    com.oilquiz.app.util.AILogger.i(TAG, "找到可用模型: " + modelName);
                    boolean success = aiService.switchModel(modelName);
                    com.oilquiz.app.util.AILogger.i(TAG, "模型加载结果: " + success);
                } else {
                    com.oilquiz.app.util.AILogger.i(TAG, "未找到已导入的 .gguf 模型，跳过预加载（assets 中无内置模型）");
                }
            }
            
            com.oilquiz.app.util.AILogger.i(TAG, "AI服务预加载完成，当前初始化状态: " + aiService.isInitialized());
            com.oilquiz.app.util.AILogger.i(TAG, "AI服务状态: " + aiService.getStatusInfo());
        } catch (Exception e) {
            com.oilquiz.app.util.AILogger.e(TAG, "AI服务预加载失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void onTerminate() {
        // 应用终止时刷新日志
        com.oilquiz.app.infra.AppLogger.flushLogs();
        
        // 停止AI处理服务
        stopAIProcessingService();
        
        super.onTerminate();
    }
    
    /**
     * 启动AI处理服务作为前台服务
     */
    private void startAIProcessingService() {
        try {
            com.oilquiz.app.util.AILogger.i(TAG, "启动AI处理服务...");
            
            // 启动AI处理服务
            Intent intent = new Intent(this, com.oilquiz.app.ai.service.AIProcessingService.class);
            
            // 适配 Android 14 的前台服务启动方式
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            
            com.oilquiz.app.util.AILogger.i(TAG, "AI处理服务启动成功");
        } catch (Exception e) {
            com.oilquiz.app.util.AILogger.e(TAG, "启动AI处理服务失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 停止AI处理服务
     */
    private void stopAIProcessingService() {
        try {
            com.oilquiz.app.util.AILogger.i(TAG, "停止AI处理服务...");
            
            // 停止AI处理服务
            Intent intent = new Intent(this, com.oilquiz.app.ai.service.AIProcessingService.class);
            stopService(intent);
            
            com.oilquiz.app.util.AILogger.i(TAG, "AI处理服务停止成功");
        } catch (Exception e) {
            com.oilquiz.app.util.AILogger.e(TAG, "停止AI处理服务失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void onLowMemory() {
        // 内存不足时刷新日志
        com.oilquiz.app.infra.AppLogger.flushLogs();
        super.onLowMemory();
    }
    
    @Override
    public void onTrimMemory(int level) {
        // 内存紧张时刷新日志
        if (level >= TRIM_MEMORY_MODERATE) {
            com.oilquiz.app.infra.AppLogger.flushLogs();
        }
        
        // 处理AI服务的内存紧张情况
        try {
            AIService aiService = AIService.getInstance(this);
            aiService.onTrimMemory(level);
        } catch (Exception e) {
            com.oilquiz.app.util.AILogger.e(TAG, "处理AI服务内存紧张失败: " + e.getMessage(), e);
        }
        
        super.onTrimMemory(level);
    }
    
    private void initMultiModelManager() {
        // 初始化 MultiModelManager
        MultiModelManager.initialize(this);
        
        // 配置默认参数
        ModelConfig defaultConfig = new ModelConfig();
        defaultConfig.modelId = "qwen2.5-7b-q4_k_m";
        defaultConfig.inferenceParams = new ModelConfig.InferenceParams();
        defaultConfig.generationParams = new ModelConfig.GenerationParams();
        
        // 设置默认配置
        MultiModelManager.getInstance(this).setDefaultConfig(defaultConfig);
    }

    private void initDefaultUser() {
        try {
            // 获取数据库实例
            com.oilquiz.app.database.AppDatabase database = com.oilquiz.app.database.AppDatabase.getDatabase(this);
            if (database == null) {
                System.out.println("数据库初始化失败，无法创建默认用户");
                return;
            }
            
            com.oilquiz.app.database.UserDao userDao = database.userDao();
            
            // 检查是否已有用户
            java.util.List<com.oilquiz.app.model.User> users = userDao.getAllUsers();
            if (users == null || users.isEmpty()) {
                // 创建默认用户
                com.oilquiz.app.model.User defaultUser = new com.oilquiz.app.model.User();
                defaultUser.setUsername("admin");
                defaultUser.setEmail("admin@example.com");
                defaultUser.setPassword("123456");
                defaultUser.setIsLoggedIn(1);
                
                // 插入默认用户
                userDao.insert(defaultUser);
                System.out.println("默认用户创建成功: admin/123456");
            }
        } catch (Exception e) {
            System.out.println("初始化默认用户失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void applyThemeMode() {
        SharedPreferences prefs = getSharedPreferences("theme_preferences", MODE_PRIVATE);
        int themeMode = prefs.getInt("current_theme", 2);
        switch (themeMode) {
            case 0:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case 1:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case 2:
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }
}
