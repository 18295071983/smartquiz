package com.oilquiz.app;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatDelegate;

import com.oilquiz.app.ai.model.MultiModelManager;
import com.oilquiz.app.ai.model.ModelConfig;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.infra.GlobalExceptionHandler;

import dagger.hilt.android.HiltAndroidApp;

/**
 * 智能题库应用入口类
 * 使用 Hilt 进行依赖注入
 */
@HiltAndroidApp
public class SmartQuizApplication extends Application {

    private static final String TAG = "SmartQuizApplication";
    private static final String CRASH_PREFS = "crash_protection_prefs";
    private static final String CRASH_COUNT_KEY = "consecutive_crash_count";
    private static final String CRASH_TIMESTAMP_KEY = "last_crash_timestamp";
    private static final int MAX_CRASH_BEFORE_SKIP = 2;
    private static final long SURVIVE_RESET_MS = 30_000; // 存活30秒后重置计数

    private static SmartQuizApplication instance;
    private static android.app.Activity currentActivity;
    private int resumeCount = 0;
    private boolean isBackground = false;
    
    public static android.app.Activity getCurrentActivity() {
        return currentActivity;
    }

    /** 全局应用 Context（pdfium 等需要 Context 的库使用） */
    public static android.content.Context getAppContext() {
        return instance;
    }

    @Override
    public void onCreate() {
        // 恢复用户上次选择的语言（须在 Activity 创建前调用）
        com.oilquiz.app.manager.LanguageManager.applyLanguage(this);
        applyThemeMode();
        super.onCreate();
        instance = this;

        // 后台线程预生成内置壁纸（仅首次或版本更新时）
        new Thread(() -> {
            try {
                com.oilquiz.app.theme.WallpaperStore.ensureBuiltin(this);
            } catch (Throwable ignored) {
            }
        }, "wallpaper-init").start();

        // 应用壁纸跟随：系统壁纸变化时，若开启「跟随系统壁纸」模式，重建前台页面刷新背景
        try {
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(this);
            wm.addOnColorsChangedListener((listener, which) -> {
                if (com.oilquiz.app.theme.AppWallpaperManager.getMode(this)
                        != com.oilquiz.app.theme.AppWallpaperManager.MODE_FOLLOW_SYSTEM) {
                    return;
                }
                android.app.Activity a = currentActivity;
                if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                    a.runOnUiThread(a::recreate);
                }
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "register wallpaper listener failed: " + t.getMessage());
        }

        // 立即初始化异常处理器（必须最先初始化）
        try {
            GlobalExceptionHandler.init(this);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 组4.3：注册 native 信号处理器（必须在首次 initModel 之前）
        // 接住 llama 内部 SIGABRT/SIGSEGV/SIGBUS/SIGILL，返回错误码而非自杀
        try {
            LlamaHelper.installSignalHandlers();
        } catch (UnsatisfiedLinkError | Exception e) {
            android.util.Log.w(TAG, "Signal handlers not installed: " + e.getMessage());
        }

        // 在主线程初始化 Chaquopy Python 解释器（Chaquopy 要求主线程调用）
        try {
            if (!com.chaquo.python.Python.isStarted()) {
                com.chaquo.python.Python.start(new com.chaquo.python.android.AndroidPlatform(this));
                com.oilquiz.app.util.AILogger.i(TAG, "Chaquopy Python 解释器已在主线程初始化");
            }
        } catch (Exception e) {
            com.oilquiz.app.util.AILogger.e(TAG, "Chaquopy Python 初始化失败: " + e.getMessage(), e);
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

                    // 崩溃保护：检查是否连续崩溃
                    int crashCount = getCrashCount();
                    com.oilquiz.app.util.AILogger.i(TAG, "连续崩溃计数: " + crashCount);
                    if (crashCount >= MAX_CRASH_BEFORE_SKIP) {
                        com.oilquiz.app.util.AILogger.w(TAG,
                            "检测到连续 " + crashCount + " 次崩溃，跳过模型自动加载，防止崩溃循环");
                        // 重置计数，下次启动允许重试
                        resetCrashCount();
                    } else {
                        // 记录本次启动，如果崩溃则计数+1
                        incrementCrashCount();
                        preloadAIServiceInternal();
                        // 调试钩子：prefs debug_fc_test.run=true 时自动跑一次原生 FC 生成测试
                        runFcDebugTestIfRequested();
                        // 调试钩子：prefs debug_agent_test.run=true + message=xx 时自动跑一次
                        // 本地 Agent 意图编排测试（程序化输出验证，不调模型总结）
                        runAgentDebugTestIfRequested();
                    }

                    // 启动AI处理服务作为前台服务，确保应用运行时持续运行
                    startAIProcessingService();

                    // 存活超过阈值后重置崩溃计数
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        resetCrashCount();
                        com.oilquiz.app.util.AILogger.i(TAG, "应用存活超过30秒，重置崩溃计数");
                    }, SURVIVE_RESET_MS);
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
            public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
                // 在 setContentView 之前注入主题 overlay：系统动态色开启时用原生 Material You 配色，
                // 否则用自定义（7 预设色 + 24 色相网格）overlay
                if (com.oilquiz.app.manager.ThemeManager.isSystemDynamicColor(activity)) {
                    activity.getTheme().applyStyle(R.style.OilQuizDynamicOverlay, true);
                } else {
                    com.oilquiz.app.manager.ThemeManager.applyThemeOverlay(activity);
                }
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                currentActivity = activity;
            }
            
            @Override
            public void onActivityStarted(Activity activity) {
                currentActivity = activity;
            }
            
            @Override
            public void onActivityResumed(Activity activity) {
                currentActivity = activity;
                // 应用壁纸到页面根布局（跟随系统壁纸 / 壁纸库 / 关闭，全局统一）
                try {
                    android.view.ViewGroup wc = (android.view.ViewGroup) activity.findViewById(android.R.id.content);
                    android.view.View root = (wc != null && wc.getChildCount() > 0) ? wc.getChildAt(0) : wc;
                    com.oilquiz.app.theme.AppWallpaperManager.applyTo(activity, root);
                    // 双保险：页面渲染完成后再次应用，防止页面代码 setBackground 覆盖壁纸
                    if (root != null) {
                        final android.view.View fRoot = root;
                        root.post(() -> com.oilquiz.app.theme.AppWallpaperManager.applyTo(activity, fRoot));
                    }
                } catch (Throwable ignored) {
                }
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
                            
                            // 尝试热启动恢复模型
                            if (aiService.canHotStart()) {
                                com.oilquiz.app.util.AILogger.i("SmartQuizApplication", "检测到可热启动，尝试恢复AI模型...");
                                aiService.tryHotStart(new AIService.HotStartCallback() {
                                    @Override
                                    public void onHotStartComplete(boolean success, String message) {
                                        com.oilquiz.app.util.AILogger.i("SmartQuizApplication", 
                                            "热启动结果: " + success + " - " + message);
                                    }
                                });
                            }
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
                if (currentActivity == activity) {
                    currentActivity = null;
                }
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
                if (currentActivity == activity) {
                    currentActivity = null;
                }
            }
        });
    }
    
    private int getCrashCount() {
        SharedPreferences prefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE);
        return prefs.getInt(CRASH_COUNT_KEY, 0);
    }

    private void incrementCrashCount() {
        SharedPreferences prefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE);
        int count = prefs.getInt(CRASH_COUNT_KEY, 0) + 1;
        prefs.edit().putInt(CRASH_COUNT_KEY, count).putLong(CRASH_TIMESTAMP_KEY, System.currentTimeMillis()).apply();
        com.oilquiz.app.util.AILogger.i(TAG, "崩溃计数递增: " + count);
    }

    private void resetCrashCount() {
        SharedPreferences prefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE);
        prefs.edit().putInt(CRASH_COUNT_KEY, 0).apply();
    }

    /**
     * 预加载AI服务 - 在应用启动时就初始化AI模型，实现热启动
     * 内部方法：直接调用，不启动新线程
     */
    private void preloadAIServiceInternal() {
        try {
            // 一键初始化进行中：跳过自动预加载，避免与下载/加载互相冲突
            if (com.oilquiz.app.ai.service.AIServiceInitializer.isInitializing()) {
                com.oilquiz.app.util.AILogger.i(TAG,
                        "检测到一键初始化进行中，跳过自动预加载AI服务，避免冲突");
                return;
            }
            com.oilquiz.app.util.AILogger.i(TAG, "开始预加载AI服务...");

            // 仅当"激活"的在线模型时才跳过本地 GGUF 预加载（激活=当前主用在线，加载本地只会白占内存）。
            // 已配置但未激活的在线模型不再跳过：用户可能随时切回本地模型
            // （本地 Agent / 离线场景），启动即预加载本地模型实现热启动。
            try {
                com.oilquiz.app.ai.model.OnlineModelManager onlineManager =
                        com.oilquiz.app.ai.model.OnlineModelManager.getInstance(this);
                if (onlineManager.hasActiveOnlineModel()) {
                    com.oilquiz.app.util.AILogger.i(TAG,
                            "检测到激活的在线模型，跳过本地模型预加载（本地模型按需加载）");
                    return;
                }
            } catch (Throwable t) {
                com.oilquiz.app.util.AILogger.w(TAG, "检查在线模型配置失败，按默认流程预加载: " + t.getMessage());
            }

            // 调用 getInstance 仅获取单例并同步状态，不在此处阻塞加载模型
            AIService aiService = AIService.getInstance(this);

            if (!aiService.isInitialized()) {
                com.oilquiz.app.util.AILogger.i(TAG, "模型未初始化，尝试加载已导入的模型…");
                // 选择可作主模型的模型：排除 mmproj/CLIP 投影文件（llama.cpp 无法将其作为主模型加载，
                // 否则报 "CLIP cannot be used as main model" 导致预加载失败、AI 状态错误）
                String modelName = selectMainModelForPreload(aiService);
                if (modelName != null) {
                    com.oilquiz.app.util.AILogger.i(TAG, "找到可用模型: " + modelName);
                    // 直接加载模型（预加载在后台异步进行，不会阻塞本次加载）
                    boolean success = aiService.switchModel(modelName);
                    com.oilquiz.app.util.AILogger.i(TAG, "模型加载结果: " + success);
                } else {
                    com.oilquiz.app.util.AILogger.i(TAG, "未找到可作主模型的 .gguf 模型，跳过预加载（避免误加载 mmproj/CLIP 投影文件）");
                }
            }

            com.oilquiz.app.util.AILogger.i(TAG, "AI服务预加载完成，当前初始化状态: " + aiService.isInitialized());
            com.oilquiz.app.util.AILogger.i(TAG, "AI服务状态: " + aiService.getStatusInfo());
        } catch (Exception e) {
            com.oilquiz.app.util.AILogger.e(TAG, "AI服务预加载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 选择自动预加载的主模型：优先当前配置的主模型（非投影文件），
     * 否则从可用主模型中选第一个（排除 mmproj/CLIP 投影文件）。
     */
    private String selectMainModelForPreload(com.oilquiz.app.ai.service.AIService aiService) {
        try {
            // 1) 优先当前配置的主模型（非投影文件 + 文件完整）
            String current = aiService.getCurrentModelName();
            if (current != null && !current.isEmpty()
                    && aiService.isMainModelUsable(current)) {
                return current;
            }
            // 2) 从可用主模型中选第一个完整可用的（排除 mmproj/CLIP 与不完整半截文件）
            String[] mains = aiService.getAvailableMainModels();
            if (mains != null) {
                for (String name : mains) {
                    if (name != null && !name.isEmpty() && aiService.isMainModelUsable(name)) {
                        return name;
                    }
                }
            }
        } catch (Exception e) {
            com.oilquiz.app.util.AILogger.w(TAG, "选择预加载主模型失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * FC 测试钩子（调试用）：prefs debug_fc_test.run=true 时启动后自动跑一次
     * 原生 function calling 生成（chatJson + tools + tool_choice=required），
     * 结果打到 logcat 的 FcTest 标签。跑完自动清除开关，不影响正常使用。
     */
    private void runFcDebugTestIfRequested() {
        try {
            final android.content.SharedPreferences prefs = getSharedPreferences("debug_fc_test", MODE_PRIVATE);
            if (!prefs.getBoolean("run", false)) return;
            prefs.edit().remove("run").apply();
            new Thread(() -> {
                try {
                    com.oilquiz.app.util.AILogger.i("FcTest", "=== FC test starting ===");
                    com.oilquiz.app.ai.service.AIService aiService =
                            com.oilquiz.app.ai.service.AIService.getInstance(this);
                    for (int i = 0; i < 90 && !aiService.isInitialized(); i++) {
                        Thread.sleep(1000);
                    }
                    if (!aiService.isInitialized()) {
                        com.oilquiz.app.util.AILogger.e("FcTest", "Model NOT initialized after 90s");
                        return;
                    }
                    com.oilquiz.app.util.AILogger.i("FcTest", "Model initialized, chatCtxActive="
                            + com.oilquiz.app.ai.jni.LlamaHelper.isChatContextActive());
                    if (!com.oilquiz.app.ai.jni.LlamaHelper.isChatContextActive()) {
                        aiService.initChatContext("", "", "");
                    }

                    org.json.JSONObject req = new org.json.JSONObject();
                    req.put("action", "chat");
                    org.json.JSONArray msgs = new org.json.JSONArray();
                    org.json.JSONObject sys = new org.json.JSONObject();
                    sys.put("role", "system");
                    sys.put("content", "你是答题宝AI助手，可以调用工具帮助用户完成任务。");
                    msgs.put(sys);
                    org.json.JSONObject user = new org.json.JSONObject();
                    user.put("role", "user");
                    user.put("content", "今天北京的天气怎么样？");
                    msgs.put(user);
                    req.put("messages", msgs);

                    org.json.JSONArray tools = new org.json.JSONArray();
                    org.json.JSONObject t = new org.json.JSONObject();
                    t.put("type", "function");
                    org.json.JSONObject fn = new org.json.JSONObject();
                    fn.put("name", "ai_weather");
                    fn.put("description", "查询指定城市的当前天气与预报");
                    org.json.JSONObject params = new org.json.JSONObject();
                    params.put("type", "object");
                    org.json.JSONObject props = new org.json.JSONObject();
                    org.json.JSONObject city = new org.json.JSONObject();
                    city.put("type", "string");
                    city.put("description", "城市名，如 北京/上海");
                    props.put("city", city);
                    params.put("properties", props);
                    params.put("required", new org.json.JSONArray().put("city"));
                    fn.put("parameters", params);
                    t.put("function", fn);
                    tools.put(t);
                    req.put("tools", tools);
                    req.put("tool_choice", "auto");
                    req.put("max_tokens", 100);
                    req.put("enable_thinking", false);
                    req.put("temperature", 0.7);

                    final java.util.List<String> toolCalls = new java.util.ArrayList<>();
                    final StringBuilder content = new StringBuilder();
                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    com.oilquiz.app.util.AILogger.i("FcTest", "Sending chatJson with tools, reqLen=" + req.length());
                    com.oilquiz.app.ai.jni.LlamaHelper.chatJson(req.toString(), new com.oilquiz.app.ai.jni.LlamaHelper.JsonCallback() {
                        @Override
                        public void onJson(String json) {
                            try {
                                org.json.JSONObject ev = new org.json.JSONObject(json);
                                String type = ev.optString("type", "");
                                if ("tool_call".equals(type)) {
                                    String name = ev.optString("name", "");
                                    String args = ev.optString("arguments", "");
                                    toolCalls.add(name + " args=" + args);
                                    com.oilquiz.app.util.AILogger.i("FcTest", "TOOL_CALL: " + name + " args=" + args);
                                } else if ("token".equals(type)) {
                                    content.append(ev.optString("content", ""));
                                } else if ("reasoning".equals(type)) {
                                    com.oilquiz.app.util.AILogger.i("FcTest", "REASONING: " + ev.optString("content", ""));
                                } else if ("complete".equals(type)) {
                                    String c = ev.optString("content", "");
                                    com.oilquiz.app.util.AILogger.i("FcTest", "COMPLETE content=" + c);
                                    // 模拟 Agent 引擎的 Java 标签兜底解析：
                                    // 模板指示 <tool_call> 标签格式，C++ parse 不识别，Java 从文本提取
                                    java.util.regex.Matcher m = java.util.regex.Pattern
                                            .compile("<tool_call>(.*?)</tool_call>", java.util.regex.Pattern.DOTALL)
                                            .matcher(c);
                                    while (m.find()) {
                                        try {
                                            org.json.JSONObject j = new org.json.JSONObject(m.group(1).trim());
                                            toolCalls.add(j.optString("name", "") + " args=" + j.optString("arguments", ""));
                                        } catch (Exception ignored) {
                                        }
                                    }
                                    latch.countDown();
                                } else if ("error".equals(type)) {
                                    com.oilquiz.app.util.AILogger.e("FcTest", "ERROR: " + ev.optString("message", ""));
                                    latch.countDown();
                                }
                            } catch (Exception e) {
                                com.oilquiz.app.util.AILogger.e("FcTest", "onJson parse failed: " + e.getMessage());
                            }
                        }
                    });
                    boolean done = latch.await(180, java.util.concurrent.TimeUnit.SECONDS);
                    com.oilquiz.app.util.AILogger.i("FcTest",
                            "=== FC TEST RESULT === done=" + done
                                    + " toolCalls=" + toolCalls
                                    + " content=" + content.length() + "chars"
                                    + (done ? "" : " TIMEOUT"));
                } catch (Throwable t) {
                    com.oilquiz.app.util.AILogger.e("FcTest", "FC test failed: " + t.getMessage(), t);
                }
            }, "fc-test").start();
        } catch (Throwable ignored) {
        }
    }
    
    /**
     * 本地 Agent 意图编排测试钩子（调试用）：prefs debug_agent_test.run=true +
     * message=消息文本 时启动后自动跑一次 AgentSoftwareLayer.processMessage，
     * 结果打到 logcat 的 AgentTest 标签。跑完自动清除开关，不影响正常使用。
     */
    private void runAgentDebugTestIfRequested() {
        try {
            final android.content.SharedPreferences prefs = getSharedPreferences("debug_agent_test", MODE_PRIVATE);
            if (!prefs.getBoolean("run", false)) return;
            final String message = prefs.getString("message", "查一下北京天气");
            prefs.edit().clear().apply();
            new Thread(() -> {
                try {
                    com.oilquiz.app.util.AILogger.i("AgentTest", "=== Agent test starting, msg=" + message);
                    com.oilquiz.app.ai.service.AIService aiService =
                            com.oilquiz.app.ai.service.AIService.getInstance(this);
                    for (int i = 0; i < 90 && !aiService.isInitialized(); i++) {
                        Thread.sleep(1000);
                    }
                    if (!aiService.isInitialized()) {
                        com.oilquiz.app.util.AILogger.e("AgentTest", "Model NOT initialized after 90s");
                        return;
                    }
                    if (!com.oilquiz.app.ai.jni.LlamaHelper.isChatContextActive()) {
                        aiService.initChatContext("", "", "");
                    }
                    final StringBuilder tokens = new StringBuilder();
                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    com.oilquiz.app.ai.agent.software.AgentSoftwareLayer layer =
                            new com.oilquiz.app.ai.agent.software.AgentSoftwareLayer(this, aiService);
                    layer.setCallback(new com.oilquiz.app.ai.agent.software.AgentSoftwareLayer.AgentCallback() {
                        @Override public void onStepUpdate(String step, String detail) {
                            com.oilquiz.app.util.AILogger.i("AgentTest", "STEP: " + step + " | " + detail);
                        }
                        @Override public void onThinkingUpdate(String thought) {
                            com.oilquiz.app.util.AILogger.i("AgentTest", "THINK: " + thought);
                        }
                        @Override public void onToolCallStart(String toolName, String args) {
                            com.oilquiz.app.util.AILogger.i("AgentTest", "TOOL: " + toolName + " " + args);
                        }
                        @Override public void onToolCallComplete(String toolName, boolean success, String result) {
                            com.oilquiz.app.util.AILogger.i("AgentTest", "TOOL-RESULT: " + toolName
                                    + " ok=" + success + " " + (result != null ? result.length() : 0) + "chars");
                        }
                        @Override public void onToken(String token) {
                            if (token != null) tokens.append(token);
                        }
                        @Override public void onComplete(com.oilquiz.app.ai.agent.software.model.AgentResponse response) {
                            String ans = response != null && response.finalAnswer != null
                                    ? response.finalAnswer : "";
                            com.oilquiz.app.util.AILogger.i("AgentTest", "=== AGENT TEST RESULT ===");
                            com.oilquiz.app.util.AILogger.i("AgentTest", "FINAL_ANSWER: " + ans);
                            com.oilquiz.app.util.AILogger.i("AgentTest", "TOKENS_COLLECTED: " + tokens.length());
                            latch.countDown();
                        }
                        @Override public void onError(String error) {
                            com.oilquiz.app.util.AILogger.e("AgentTest", "ERROR: " + error);
                            latch.countDown();
                        }
                        @Override public void onInferenceProgress(int tokenCount, float tokensPerSecond) {
                        }
                    });
                    layer.processMessage(message, false);
                    boolean done = latch.await(150, java.util.concurrent.TimeUnit.SECONDS);
                    com.oilquiz.app.util.AILogger.i("AgentTest",
                            "=== DONE=" + done + " (token output " + tokens.length() + " chars) ===");
                } catch (Throwable t) {
                    com.oilquiz.app.util.AILogger.e("AgentTest", "Agent test failed: " + t.getMessage(), t);
                }
            }, "agent-test").start();
        } catch (Throwable ignored) {
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
        com.oilquiz.app.manager.ThemeManager.applyNightMode(this);
    }
}
