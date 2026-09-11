package com.oilquiz.app.ai.spi;

import android.content.Context;

/**
 * 服务注册表（SPI 接入点）。
 *
 * 逻辑组件通过本类取各服务接口，不再直接持有 Context 或调用 android 单例。
 * 宿主在 Application#onCreate 调用 {@link #install(Context)} 安装默认 Android 实现；
 * 测试或替换场景可调用单接口 install 覆盖（如 {@link #install(StringProvider)}）。
 *
 * <pre>
 * // Application
 * AppServices.install(this);
 *
 * // 测试
 * AppServices.install(mockStringProvider);   // 覆盖单接口
 * </pre>
 *
 * 未安装时取用会抛 IllegalStateException；组件构造可传 Context 触发
 * {@link #ensure(Context)} 兜底安装（向后兼容旧调用点）。
 */
public final class AppServices {

    private static volatile Context appContext;
    private static volatile StringProvider strings;
    private static volatile PreferenceStore prefs;
    private static volatile SpeechGateway speech;
    private static volatile ModelGateway models;
    private static volatile ToolGateway tools;
    private static volatile FileDirProvider files;

    private AppServices() {}

    /** 安装全部默认 Android 实现（幂等；已安装则不覆盖） */
    public static synchronized void install(Context context) {
        if (strings == null) {
            appContext = context.getApplicationContext();
            AndroidAppServices impl = new AndroidAppServices(context);
            strings = impl;
            prefs = impl;
            speech = impl;
            models = impl;
            tools = impl;
            files = impl;
        }
    }

    // ---- 单接口覆盖 ----

    public static synchronized void install(StringProvider p) { strings = p; }
    public static synchronized void install(PreferenceStore p) { prefs = p; }
    public static synchronized void install(SpeechGateway p) { speech = p; }
    public static synchronized void install(ModelGateway p) { models = p; }
    public static synchronized void install(ToolGateway p) { tools = p; }
    public static synchronized void install(FileDirProvider p) { files = p; }

    /** 兜底：未安装时用 context 安装默认实现（组件构造兼容入口） */
    public static synchronized void ensure(Context context) {
        if (strings == null || prefs == null || speech == null
                || models == null || tools == null || files == null) {
            install(context);
        }
    }

    /** 清空（测试隔离用） */
    public static synchronized void reset() {
        strings = null; prefs = null; speech = null;
        models = null; tools = null; files = null;
    }

    // ---- 取用 ----

    /** 已安装的 applicationContext（平台工具构造用；未安装时 null） */
    public static Context appContext() { return appContext; }

    public static StringProvider strings() { return require(strings, "StringProvider"); }
    public static PreferenceStore prefs() { return require(prefs, "PreferenceStore"); }
    public static SpeechGateway speech() { return require(speech, "SpeechGateway"); }
    public static ModelGateway models() { return require(models, "ModelGateway"); }
    public static ToolGateway tools() { return require(tools, "ToolGateway"); }
    public static FileDirProvider files() { return require(files, "FileDirProvider"); }

    private static <T> T require(T v, String name) {
        if (v == null) {
            throw new IllegalStateException("AppServices." + name
                    + " 未安装：请在 Application#onCreate 调用 AppServices.install(context)");
        }
        return v;
    }
}
