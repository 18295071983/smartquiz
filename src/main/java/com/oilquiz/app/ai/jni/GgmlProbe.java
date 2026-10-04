package com.oilquiz.app.ai.jni;

import android.content.Context;
import android.util.Log;

/**
 * ggml 后端探测：验证"能否把 GenieX 的 Hexagon(NPU) 后端挂到我们自己的 llama.cpp 上"。
 *
 * <p><b>2026-10-03 实测结论（结论已定，此类保留作诊断工具）</b>：
 * <ul>
 *   <li>ABI <b>兼容</b>：{@code libggml-hexagon.so} 需要的 32 个 ggml C API 符号，我们的
 *       {@code libllama-jni.so} 全部导出；运行期 {@code ggml_backend_load()} 返回 0（被我们的
 *       后端注册表接受）。</li>
 *   <li>但插件<b>注册 0 个设备</b>：加载前后我们这边都只有 Vulkan / OpenCL / CPU 三个后端。
 *       即它依赖 GenieX 自己的 DSP 会话引导（他们日志里的 "Auto-resolved HTP runtime path"），
 *       单独挂到第三方 llama.cpp 上不足以让 HTP 设备出现。</li>
 *   <li>因此"NPU 上的 llama.cpp"实际就是 GenieX AAR 自带的那份（{@code libllama.so} +
 *       {@code libggml-hexagon.so}），App 的 NPU 引擎走它；我们原生那份
 *       {@code libllama-jni.so} 继续负责 CPU/OpenCL/Vulkan 与全部工具链功能。</li>
 * </ul>
 *
 * <p>调用方式：{@link #probe(Context)}，返回人类可读报告（不联网、不改配置）。
 */
public final class GgmlProbe {

    private static final String TAG = "GgmlProbe";
    private static final String LIB = "ggml-probe";

    private static volatile boolean loadFailed = false;
    private static volatile String loadError = null;

    static {
        try {
            System.loadLibrary(LIB);
        } catch (Throwable t) {
            loadFailed = true;
            loadError = t.toString();
            Log.e(TAG, "lib" + LIB + ".so 加载失败: " + t);
        }
    }

    private GgmlProbe() {
    }

    public static boolean isAvailable() {
        return !loadFailed;
    }

    public static String getLoadError() {
        return loadError;
    }

    private static native String nativeProbe(String nativeLibDir);

    /** 跑一次探测，返回人类可读报告（不联网、不改配置；失败也只是返回文本） */
    public static String probe(Context context) {
        if (loadFailed) {
            return "lib" + LIB + ".so 不可用: " + loadError;
        }
        try {
            String dir = context.getApplicationInfo().nativeLibraryDir;
            String report = nativeProbe(dir);
            Log.i(TAG, "探测完成:\n" + report);
            return report;
        } catch (Throwable t) {
            Log.e(TAG, "探测异常", t);
            return "探测异常: " + t;
        }
    }
}
