package com.oilquiz.app.util;

import android.content.Context;
import android.util.Log;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

/**
 * PDFBox-Android 的一次性初始化（2026-10-24）。
 *
 * <p>pdfbox-android 必须在做任何 PDF 操作之前调用 {@code PDFBoxResourceLoader.init(context)}，
 * 否则读字体/CMap 等资源时可能拿不到（表现为抽取乱码或 "resource not found"）。
 * 应用启动时调用一次即可，进程内所有 PDFBox 调用共用这份静态 Context。</p>
 *
 * <p>为什么不在各个调用点自己 init：{@code AdvancedFileParserUtil} 的 PDF 解析方法没有
 * Context 参数，逐个改签名会波及一串调用方；统一在 Application 启动时初始化最稳。</p>
 */
public final class PdfBoxInitializer {

    private static final String TAG = "PdfBoxInitializer";
    private static volatile boolean initialized = false;

    private PdfBoxInitializer() {
    }

    public static void ensure(Context context) {
        if (initialized || context == null) return;
        synchronized (PdfBoxInitializer.class) {
            if (initialized) return;
            try {
                PDFBoxResourceLoader.init(context.getApplicationContext());
                initialized = true;
            } catch (Throwable t) {
                Log.w(TAG, "PDFBoxResourceLoader 初始化失败: " + t);
            }
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
