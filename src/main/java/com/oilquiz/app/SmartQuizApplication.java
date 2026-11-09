package com.oilquiz.app;

import com.oilquiz.app.util.AILogger;
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
@SuppressWarnings("deprecation") // 壁纸变更广播/内存档位仍是平台唯一信号
public class SmartQuizApplication extends Application {

    private static final String TAG = "SmartQuizApplication";
    private static final String CRASH_PREFS = "crash_protection_prefs";
    private static final String CRASH_COUNT_KEY = "consecutive_crash_count";
    private static final String CRASH_TIMESTAMP_KEY = "last_crash_timestamp";
    /**
     * 跳过模型自动加载的阈值（2026-10-25 由 2 提到 3）。
     *
     * <p>旧口径"任何没活满 30 秒的死亡都算崩溃、连续 2 次就跳过"在真机上被证伪：
     * 一次聊天页 ANR 被杀 + 一次开发期 force-stop 就能把<b>模型自动加载</b>误判成崩溃源。
     * 现在死亡按 {@code MainThreadWatchdog}/{@code GlobalExceptionHandler} 的标记归因，
     * ANR 与体面停止不再计数；阈值 3 让"启动即被强杀"这类无法完全区分的情况至少容忍两轮。</p>
     */
    private static final int MAX_CRASH_BEFORE_SKIP = 3;
    private static final long SURVIVE_RESET_MS = 30_000; // 存活30秒后重置计数

    /** 本次启动代次：所有"上一次怎么死的"归因都比对它。 */
    private static final String BOOT_ID = Long.toHexString(System.currentTimeMillis())
            + "-" + Integer.toHexString((int) (Math.random() * 0x10000));
    /** onCreate 早期从 prefs 读到的上一次启动代次（此时 SESSION 键已被本次覆盖，必须提前留存）。 */
    private static String prevBootId = null;
    private static final String KEY_SESSION_BOOT = "session_boot_id";
    private static final String KEY_HEALTHY_BOOT = "healthy_boot_id";
    private static final String KEY_JAVA_CRASH_BOOT = "java_crash_boot_id";

    private static SmartQuizApplication instance;
    private static android.app.Activity currentActivity;
    private int resumeCount = 0;
    private boolean isBackground = false;
    /** 主动壁纸探测：前台周期轮询（不依赖系统广播/颜色回调，小米等 ROM 上两者均可能不可靠） */
    private android.os.Handler wallpaperProbeHandler;
    private static final long WALLPAPER_PROBE_INTERVAL_MS = 20000L;
    private final Runnable wallpaperProbeRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (!isBackground
                        && com.oilquiz.app.theme.AppWallpaperManager.getMode(SmartQuizApplication.this)
                        == com.oilquiz.app.theme.AppWallpaperManager.MODE_FOLLOW_SYSTEM) {
                    long f = com.oilquiz.app.theme.AppWallpaperManager
                            .getSystemWallpaperFingerprint(SmartQuizApplication.this);
                    android.util.Log.d(TAG, "壁纸主动探测: 指纹=" + f
                            + ", 动态壁纸=" + com.oilquiz.app.theme.AppWallpaperManager
                            .isLiveWallpaper(SmartQuizApplication.this));
                    if (f != 0 && com.oilquiz.app.theme.AppWallpaperManager.wallpaperFingerprintChanged(f)) {
                        if (com.oilquiz.app.theme.AppWallpaperManager.isWallpaperRefreshCoolingDown()) {
                            android.util.Log.d(TAG, "壁纸主动探测: 指纹已变但处于重建冷却期，跳过本次重建");
                        } else {
                            android.util.Log.i(TAG, "壁纸主动探测: 系统壁纸已变化，刷新前台页面");
                            com.oilquiz.app.theme.AppWallpaperManager.markWallpaperRefreshed();
                            refreshWallpaperForFrontActivity();
                        }
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w(TAG, "壁纸主动探测失败: " + t.getMessage());
            } finally {
                if (wallpaperProbeHandler != null) {
                    wallpaperProbeHandler.postDelayed(this, WALLPAPER_PROBE_INTERVAL_MS);
                }
            }
        }
    };

    private void startWallpaperProbe() {
        try {
            wallpaperProbeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            wallpaperProbeHandler.postDelayed(wallpaperProbeRunnable, WALLPAPER_PROBE_INTERVAL_MS);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "启动壁纸主动探测失败: " + t.getMessage());
        }
    }
    
    public static android.app.Activity getCurrentActivity() {
        return currentActivity;
    }

    /** 全局应用 Context（pdfium 等需要 Context 的库使用） */
    public static android.content.Context getAppContext() {
        return instance;
    }

    /**
     * 壁纸变化后刷新前台页面背景：仅「跟随系统壁纸」模式下生效。
     * 重建当前 Activity（重建会经 onActivityResumed 重新 applyTo 实时读取新壁纸），
     * 供 ACTION_WALLPAPER_CHANGED 广播与 OnColorsChangedListener 双通道共用。
     */
    private void refreshWallpaperForFrontActivity() {
        try {
            if (com.oilquiz.app.theme.AppWallpaperManager.getMode(this)
                    != com.oilquiz.app.theme.AppWallpaperManager.MODE_FOLLOW_SYSTEM) {
                return;
            }
            android.app.Activity a = currentActivity;
            if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                // 延迟重建：换壁纸广播发出时 WallpaperManagerService 的文件可能尚未落盘，
                // 稍等片刻再重建，保证重建后读到的是新壁纸（而非系统侧旧缓存）
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.postDelayed(() -> {
                    if (!a.isFinishing() && !a.isDestroyed()) {
                        a.runOnUiThread(a::recreate);
                    }
                }, 400);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "refresh wallpaper failed: " + t.getMessage());
        }
    }

    @Override
    public void onCreate() {
        // 恢复用户上次选择的语言（须在 Activity 创建前调用）
        com.oilquiz.app.manager.LanguageManager.applyLanguage(this);
        applyThemeMode();
        super.onCreate();
        instance = this;

        // 死亡归因基座（2026-10-25）：先留存上一次启动代次，再写本次；启动主线程卡死哨兵。
        // 越早在 onCreate 落 BOOT_ID，越好——启动阶段的主线程卡死/崩溃也要能被归因。
        boot("启动代次基座(死亡归因用)", () -> {
            android.content.SharedPreferences cp =
                    getSharedPreferences(CRASH_PREFS, MODE_PRIVATE);
            prevBootId = cp.getString(KEY_SESSION_BOOT, null);
            cp.edit().putString(KEY_SESSION_BOOT, BOOT_ID).apply();
        });
        com.oilquiz.app.infra.MainThreadWatchdog.start(this, BOOT_ID);

        // PDFBox-Android 必须在使用前 init 一次（PDF 导出/抽取都靠它加载字体与 CMap 资源）。
        // 放在这里而不是各调用点：AdvancedFileParserUtil 的解析方法没有 Context 参数。
        boot("PDFBox 初始化(PDF 导出/抽取依赖)", () -> com.oilquiz.app.util.PdfBoxInitializer.ensure(this));

        // NPU-FAULT-GUARD(2026-11-01)：必须排在 initBackendPreference 之前——
        // ① 先下发致命信号记录的落盘路径（信号处理器在进程被 SIGABRT 打死前写它）；
        // ② 再消费上一次留下的记录：上一次死在硬件层的话，这次装载就临时降级到 opencl，
        //    否则 hexagon 会在同一台机器上反复死。降级不写 gpu_backend 偏好。
        boot("NPU 致命信号通道(落盘路径+上次降级消费)", () -> {
            java.io.File npuLogDir = new java.io.File(getFilesDir(), "oilquiz/logs");
            if (!npuLogDir.exists()) npuLogDir.mkdirs();
            com.oilquiz.app.ai.jni.LlamaHelper.setFatalRecordFile(
                    new java.io.File(npuLogDir, "npu_fatal.last").getAbsolutePath());
            com.oilquiz.app.ai.jni.NpuFaultGuard.consumeBootFatalRecord(this);
        });

        // BACKEND-INIT-EARLY(2026-10-09)：GPU 后端偏好必须在这里读，不能等到 Activity。
        // 模型加载由后台线程在启动时就发起（比 Activity.onCreate 早数秒），若等到那时才读
        // SharedPreferences，读到的仍是 LlamaHelper 的静态默认值 "opencl"，会出现
        // "设置里存 hexagon、模型却按 opencl 加载"的不一致（实测相差 6 秒）。
        boot("GPU 后端偏好预读", () -> com.oilquiz.app.ai.jni.LlamaHelper.initBackendPreference(this));

        // 服务商配置表挂载（providers.json：地址/服务/思考参数/地址拼装规则统一从此读取）
        boot("服务商配置表挂载(providers.json)", () -> {
            com.oilquiz.app.ai.model.ProviderConfigManager.init(this);
        });

        // 推理引擎日志落盘（日志中心「引擎」Tab 的历史来源）：native 追加写，超过 2MB 自动截断
        boot("引擎日志落盘(日志中心「引擎」Tab 来源)", () -> {
            java.io.File engineLogDir = new java.io.File(getFilesDir(), "oilquiz/logs");
            if (!engineLogDir.exists()) engineLogDir.mkdirs();
            com.oilquiz.app.ai.jni.LlamaHelper.setEngineLogFile(
                    new java.io.File(engineLogDir, "engine_logs.txt").getAbsolutePath());
            // HTP-DIAG(2026-11-01)：ggml-hexagon 的详细日志（arch/skel、HMX、fusion、repack）。
            // 显式开关、默认关：开着会把引擎日志冲掉。要排查"NPU 到底走没走快路径"时
            // 把 ai_chat_prefs.htp_verbose 置 true，重启后看「引擎」Tab。
            boolean htpVerbose = getSharedPreferences("ai_chat_prefs", MODE_PRIVATE)
                    .getBoolean("htp_verbose", false);
            com.oilquiz.app.ai.jni.LlamaHelper.setHtpVerbose(htpVerbose);
        });

        // 一次性清理旧会话里的伪过程行（"收到你的消息…/Agent模式已激活/正在思考中…"）：
        // 只重写历史 JSON，后台线程做；失败不影响启动（显示层另有兜底隐藏）
        // 外层 try 去掉：Thread(...).start() 除了 OOM 不会抛，那不是一个需要"降级"的失败；
        // 真正的兜底在清理本体上，交给 boot() 收集（后台线程晚到也能看见）
        new Thread(() -> boot("旧会话伪过程行一次性清理",
                () -> new com.oilquiz.app.ai.util.ChatHistoryManager(SmartQuizApplication.this)
                        .cleanLegacyProcessRowsOnce()), "legacy-process-row-cleanup").start();
        // ① 代码生成的三份 —— 工具创建指南.md / 使用速查表.md / 核心工具速查.md；
        // ② assets/apk_shell/guides/ 下自动发现的全部文件（LINUX_TOOLKIT_GUIDE.md、
        //    MEDIA_TOOLKIT_GUIDE.md、HTML_DESIGN_RULES.md、APK_SOURCE_GUIDE.md 等，
        //    新增只要丢进目录、不用改代码）。
        // 因此模型读到的工作区说明永远跟着 APK 里的内容；提示词若引用某份文档而 assets 里没有，
        // 模型就会"照指引去找一个不存在的文件"—— 加引用时记得同步加文档。无服务/权限副作用。
        boot("Agent 工作区预热(内置文档刷新)", () -> {
            com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(this);
        });

        // 导入工作区双根初始化：有"所有文件访问"→公共 /sdcard/OilQuiz；无权限 → 私有 files/import_workspace
        // （Python 与 App 同进程可读写私有目录，导入管线无需该权限）
        boot("导入工作区双根初始化", () -> {
            com.oilquiz.app.ai.importing.v2.ImportDirs.init(this);
        });

        // 后台线程预生成内置壁纸（仅首次或版本更新时）
        new Thread(() -> {
        boot("内置壁纸预生成", () -> {
                com.oilquiz.app.theme.WallpaperStore.ensureBuiltin(this);
        });
            // 预热内置 Linux 工具箱（busybox applet 软链接 + 依赖库软链接）。
            // 必须在 Python 引擎起来之前就绪：Python 只在启动时读一次 PATH/LD_LIBRARY_PATH，
            // 否则 python_execute 里的 subprocess 用不到 busybox（实测复现过）。
        boot("内置 Linux 工具箱预热(busybox/依赖库软链)", () -> {
                com.oilquiz.app.ai.tool.SystemResourceTool.prepareToolkit(this);
        });
        }, "wallpaper-init").start();

        // 应用壁纸跟随：系统壁纸变化时，若开启「跟随系统壁纸」模式，重建前台页面刷新背景。
        // 双通道：① ACTION_WALLPAPER_CHANGED 系统广播为主（AOSP WallpaperManagerService 换壁纸必发，
        //           各版本/各 ROM 通用，实时）；② OnColorsChangedListener 颜色事件为补充
        //           （仅 Android 12+，部分 ROM（如小米）颜色事件不可靠，不能作为唯一通道）。
        try {
            android.content.BroadcastReceiver wallpaperReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent intent) {
                    if (intent == null
                            || !android.content.Intent.ACTION_WALLPAPER_CHANGED.equals(intent.getAction())) {
                        return;
                    }
                    refreshWallpaperForFrontActivity();
                }
            };
            android.content.IntentFilter wallpaperFilter =
                    new android.content.IntentFilter(android.content.Intent.ACTION_WALLPAPER_CHANGED);
            registerReceiver(wallpaperReceiver, wallpaperFilter);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "register wallpaper broadcast failed: " + t.getMessage());
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(this);
                wm.addOnColorsChangedListener((listener, which) -> refreshWallpaperForFrontActivity(),
                        new android.os.Handler(android.os.Looper.getMainLooper()));
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "register wallpaper color listener failed: " + t.getMessage());
        }

        // 主动壁纸探测（前台周期轮询指纹）：不依赖系统广播/颜色回调，换壁纸后最多一个轮询周期内跟随
        startWallpaperProbe();

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

                    // 崩溃保护：按上一次退出"死因"归因后再决定是否跳过模型自动加载。
                    // 旧口径把任何没活满 30 秒的死亡都记成崩溃，导致聊天页 ANR 被杀 + 开发期
                    // force-stop 也能凑够阈值、把模型自动加载误杀。现在：
                    //   · 上次 Java 未捕获崩溃（GlobalExceptionHandler 落标记）→ 计数 +1
                    //   · 上次主线程卡死≥5s（MainThreadWatchdog 落 ANR 标记）→ 清零（与模型无关）
                    //   · 上次活满 30 秒后被停（划掉/force-stop/健康后崩溃）→ 清零
                    //   · 上次没活满 30 秒、又没有任何上述标记（native 崩溃或早于哨兵的强杀）→ 计数 +1
                    int crashCount = classifyPreviousExitAndCount();
                    com.oilquiz.app.util.AILogger.i(TAG, "连续崩溃计数(按死因归因后): " + crashCount);
                    if (crashCount >= MAX_CRASH_BEFORE_SKIP) {
                        AILogger.w(TAG,
                            "检测到连续 " + crashCount + " 次崩溃，跳过模型自动加载，防止崩溃循环");
                        // 重置计数，下次启动允许重试
                        resetCrashCount();
                    } else {
                        preloadAIServiceInternal();
                        // 调试钩子：prefs debug_fc_test.run=true 时自动跑一次原生 FC 生成测试
                        runFcDebugTestIfRequested();
                        // 调试钩子：prefs debug_agent_test.run=true + message=xx 时自动跑一次
                        // 本地 Agent 意图编排测试（程序化输出验证，不调模型总结）
                        runAgentDebugTestIfRequested();
                    }

                    // 说明（2026-10-07）：AIProcessingService 及其广播链路（含日志查看页）
                    // 已整体删除 —— 当前 AI 对话/Agent 生成走 direct streaming
                    // （AgentChatHandler → ModelExecutionBridge → OutputRouter），不经服务。

                    // 存活超过阈值：标记本次启动"健康"，并重置崩溃计数。
                    // 标记健康后，本次进程无论之后是被划掉还是被 force-stop，下次启动都会
                    // 归为"体面停止"而不再累加崩溃计数（这正是与旧口径的关键区别）。
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
        boot("标记本次启动健康并重置崩溃计数", () -> {
                            getSharedPreferences(CRASH_PREFS, MODE_PRIVATE).edit()
                                    .putString(KEY_HEALTHY_BOOT, BOOT_ID).apply();
        });
                        resetCrashCount();
                        com.oilquiz.app.util.AILogger.i(TAG, "应用存活超过30秒，标记健康并重置崩溃计数");
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
        reportBootFailures();
    }

    /**
     * 启动期子系统引导的收集器。
     *
     * <p>原来 onCreate 里排着 13 个 {@code try { initX(this); } catch (Throwable ignored) {}}：
     * 单个子系统失败确实不该打断启动（同一台机器上某些 native 装载就是会失败），但那层空 catch
     * 顺带把"这次启动哪些能力没起来"整个抹掉了——出问题只能在功能侧猜。改成每个子系统起个名字、
     * 失败进清单，onCreate 末尾**一行**汇总打出去（不是 13 条，免得刷日志中心）；
     * 清单上报之后才失败的异步引导逐条打，否则就丢了。</p>
     */
    private final java.util.Queue<String> bootFailures =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean bootReported = false;

    private void boot(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            String line = what + "：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null || t.getMessage().isEmpty() ? "" : " " + t.getMessage());
            if (bootReported) {
                AILogger.w(TAG, "启动后异步引导失败: " + line);
            } else {
                bootFailures.add(line);
            }
        }
    }

    private void reportBootFailures() {
        bootReported = true;
        if (bootFailures.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String item : bootFailures) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(item);
        }
        AILogger.w(TAG, "启动期 " + bootFailures.size()
                + " 个子系统引导失败（对应功能会降级，排查从这里起）: " + sb);
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
                // 真 edge-to-edge 全局适配（Android 16 强制 edge-to-edge 前的统一方案）：
                // 开启内容延伸到系统栏 + 根容器 insets padding + 深浅图标；AI 对话/透明悬浮窗自动跳过
                com.oilquiz.app.theme.EdgeToEdgeHelper.apply(activity);
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
                    // DEBUG-CONSOLE(2026-11-08)：调试台是纯深色控制台，壁纸（尤其猫和老鼠兜底图）
                    // 会把日志压得不可读，也会让探针面板与气泡混在一起。调试页跳过壁纸，
                    // 只保留 edge-to-edge/状态栏同步，根布局自己给 #0b1020。
                    final boolean debugConsole =
                            activity instanceof com.oilquiz.app.ui.activity.AgentDebugActivity;
                    if (!debugConsole) {
                        com.oilquiz.app.theme.AppWallpaperManager.applyTo(activity, root);
                    }
                    // 统一沉浸: 窗口背景同步为页面根背景(壁纸/背景图)延伸到系统栏
                    com.oilquiz.app.theme.EdgeToEdgeHelper.syncWindowBackground(activity, root);
                    // 图标自适应: 按状态栏区实际背景亮度设深/浅图标(含 AI 对话等 insets 白名单页)
                    com.oilquiz.app.theme.EdgeToEdgeHelper.syncStatusBarIcons(activity, root);
                    // 导航条固定为 surface 色带（在 syncStatusBarIcons 之后，避免图标深浅被覆盖）：
                    // 消除滚动时系统对比遮罩与壁纸交替导致的底部闪烁
                    com.oilquiz.app.theme.EdgeToEdgeHelper.stabilizeNavBar(activity, root);
                    // 多重刷新兜底：换壁纸返回 App 时 onResume 立即读取可能落在系统壁纸服务落盘竞态窗口（旧图），
                    // 300ms/1200ms 延迟再读两次，覆盖广播/颜色回调错过后的最后一次刷新机会
                    if (root != null) {
                        final android.view.View fRoot = root;
                        root.postDelayed(() -> {
                            if (!debugConsole) {
                                com.oilquiz.app.theme.AppWallpaperManager.applyTo(activity, fRoot);
                            }
                            com.oilquiz.app.theme.EdgeToEdgeHelper.syncWindowBackground(activity, fRoot);
                            com.oilquiz.app.theme.EdgeToEdgeHelper.syncStatusBarIcons(activity, fRoot);
                            com.oilquiz.app.theme.EdgeToEdgeHelper.stabilizeNavBar(activity, fRoot);
                        }, 300);
                        root.postDelayed(() -> {
                            if (!debugConsole) {
                                com.oilquiz.app.theme.AppWallpaperManager.applyTo(activity, fRoot);
                            }
                            com.oilquiz.app.theme.EdgeToEdgeHelper.syncWindowBackground(activity, fRoot);
                            com.oilquiz.app.theme.EdgeToEdgeHelper.syncStatusBarIcons(activity, fRoot);
                            com.oilquiz.app.theme.EdgeToEdgeHelper.stabilizeNavBar(activity, fRoot);
                        }, 1200);
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
                                            getString(R.string.h_9c561bf1) + success + " - " + message);
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
    
    private void resetCrashCount() {
        SharedPreferences prefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE);
        prefs.edit().putInt(CRASH_COUNT_KEY, 0).apply();
    }

    /** 本次启动代次（供 {@link GlobalExceptionHandler} 落 Java 崩溃标记时读取）。 */
    public static String currentBootId() {
        return BOOT_ID;
    }

    /**
     * Java 未捕获崩溃时，把"崩溃发生在哪一次启动"落进崩溃保护 prefs（供下次启动归因）。
     * 由 {@link GlobalExceptionHandler#uncaughtException} 调用。用 commit 同步写，
     * 因为进程即将被系统终止，apply 可能来不及落盘。
     */
    public static void recordJavaCrashForAttribution() {
        try {
            if (instance == null) return;
            instance.getSharedPreferences(CRASH_PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_JAVA_CRASH_BOOT, BOOT_ID)
                    .commit();
        } catch (Throwable notStored) {
            AILogger.w("SmartQuizApplication", "崩溃归因没落盘（下次启动无法把崩溃归到那次模型上）: " + notStored);
        }
    }

    /**
     * 按上一次退出的死因归因，算出当前"模型相关连续崩溃"计数并落盘。
     *
     * <p>判据（越靠前优先级越高，都比对 {@link #prevBootId}）：
     * <ol>
     *   <li>无上一次 boot（首次安装/升级）→ 计数归 0；</li>
     *   <li>上次 Java 未捕获崩溃 → 计数 +1；</li>
     *   <li>上次主线程卡死（ANR 标记命中）→ 归 0（与模型自动加载无关，跳过模型治不了它）；</li>
     *   <li>上次活满 30 秒后被判健康（划掉/force-stop/健康后崩溃）→ 归 0；</li>
     *   <li>上次没活满 30 秒且无上述标记（native 崩溃、或早于哨兵起动的强杀，无法区分）→ 计数 +1，
     *       靠提高后的阈值 {@link #MAX_CRASH_BEFORE_SKIP} 容忍偶发误判。</li>
     * </ol></p>
     *
     * @return 归因后的最新计数
     */
    private int classifyPreviousExitAndCount() {
        SharedPreferences prefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE);
        int base = prefs.getInt(CRASH_COUNT_KEY, 0);
        String prev = prevBootId;
        int next;
        String reason;
        if (prev == null) {
            next = 0;
            reason = "首次安装/升级";
        } else if (prev.equals(prefs.getString(KEY_JAVA_CRASH_BOOT, null))) {
            next = base + 1;
            reason = "上次 Java 未捕获崩溃";
        } else if (com.oilquiz.app.infra.MainThreadWatchdog.isAnrDeath(
                prefs.getString(com.oilquiz.app.infra.MainThreadWatchdog.KEY_ANR_BOOT, null),
                prev, android.os.SystemClock.elapsedRealtime(),
                prefs.getLong(com.oilquiz.app.infra.MainThreadWatchdog.KEY_ANR_AT, 0))) {
            next = 0;
            reason = "上次主线程卡死(ANR)，与模型无关，清零";
        } else if (prev.equals(prefs.getString(KEY_HEALTHY_BOOT, null))) {
            next = 0;
            reason = "上次健康运行后被停止(划掉/force-stop)，清零";
        } else {
            next = base + 1;
            // NPU-FAULT-GUARD(2026-11-01)：把"早杀"里能分辨的那类分出来——若上一进程倒在
            // 一轮推理中间，原因就不是泛泛的 native 崩溃/早杀，而是具体后端上的硬件层问题。
            String unfinished = com.oilquiz.app.ai.jni.NpuFaultGuard.describeUnfinishedRound(this);
            reason = "上次未活满30秒且无崩溃/ANR标记(native崩溃或早杀，无法区分)"
                    + (unfinished != null ? "；" + unfinished : "");
        }
        prefs.edit().putInt(CRASH_COUNT_KEY, next)
                .putLong(CRASH_TIMESTAMP_KEY, System.currentTimeMillis()).apply();
        com.oilquiz.app.util.AILogger.i(TAG, "上次退出归因: " + reason + " → 计数 " + base + "→" + next);
        return next;
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

            // "要不要主动加载本地模型"统一走 LocalModelLoadPolicy（2026-10-25）：
            // 已激活在线模型 → 跳过（加载本地只会白占内存）；本地 Agent 关闭 → 同样跳过（按需懒加载）。
            // 已配置但未激活的在线模型不再触发跳过：用户可能随时切回本地模型。
            // 与旧版的差异只有一处：以前"读在线模型状态抛异常"会落到 catch 里照常预加载，
            // 现在按保守处理为不主动加载——宁可让用户显式点一次加载，也不要"不用也占 1GB"。
            String preloadSkip = com.oilquiz.app.ai.model.LocalModelLoadPolicy.skipReason(this);
            if (preloadSkip != null) {
                com.oilquiz.app.util.AILogger.i(TAG, "跳过本地模型预加载：" + preloadSkip);
                return;
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
            AILogger.w(TAG, "选择预加载主模型失败: " + e.getMessage());
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
            final String message = prefs.getString("message", getString(R.string.h_f44c7e2e));
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
        
        super.onTerminate();
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
