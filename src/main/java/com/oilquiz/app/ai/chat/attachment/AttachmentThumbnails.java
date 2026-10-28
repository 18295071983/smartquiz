package com.oilquiz.app.ai.chat.attachment;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.util.preview.FilePreviewManager;
import com.oilquiz.app.util.preview.PreviewEngine;

import java.io.File;
import java.util.Locale;

/**
 * 聊天附件的缩略图加载器（2026-10-25）。
 *
 * <p>统一入口，给三处复用同一套口径与线程模型：已发送消息的附件行（{@code MessageAttachmentAdapter}）、
 * 输入区待发附件（{@code ui.adapter.AttachmentAdapter}）、聊天文件卡（{@code FileCardView}）。</p>
 *
 * <p>只接"有专用 PreviewEngine"的类型：md 家族、txt/log/json/xml/csv、pdf、常见图片。
 * Office（doc/docx/xls/ppt…）会落到 X5/TBS，依赖主线程与 TBS 内核，不在这里接。</p>
 *
 * <p>线程：文件读取/引擎渲染在后台线程，回调统一回主线程；生成过的缩略图由
 * {@link FilePreviewManager} 按文件内容哈希落盘缓存，第二次打开直接命中。</p>
 */
public final class AttachmentThumbnails {

    private static final String TAG = "AttachmentThumbnails";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 缩略图回调（均在主线程）。 */
    public interface Callback {
        void onThumbnail(Bitmap bitmap);

        void onUnavailable();
    }

    @SuppressWarnings("unused")
    private static final Callback NO_OP = new Callback() {
        @Override
        public void onThumbnail(Bitmap bitmap) {
            if (bitmap != null) bitmap.recycle();
        }

        @Override
        public void onUnavailable() {
        }
    };

    /** 进缩略图前把长边压到这个值内：chip 只有 40dp，原图分辨率没意义还费内存。 */
    public static final int MAX_EDGE = 1024;

    private static final String[] EXTENSIONS = {
            ".md", ".markdown", ".mdown", ".mkd", ".mkdn",
            ".txt", ".log", ".json", ".xml", ".csv",
            ".pdf",
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp"
    };

    private AttachmentThumbnails() {
    }

    /** 该文件是否有可用的缩略图引擎（纯扩展名判断，供单测钉口径）。 */
    public static boolean supports(File file) {
        if (file == null || !file.isFile() || file.length() <= 0) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * 附件 → 可直接读取的本地文件：{@code localFilePath} 优先，其次 {@code file://} 的 url
     * （历史消息恢复后 localFilePath 可能缺失，但 url 通常还是落盘路径）。
     */
    public static File localFileOf(ChatMessage.Attachment attachment) {
        if (attachment == null) return null;
        File file = asFile(attachment.localFilePath);
        if (file != null && file.isFile()) return file;
        file = asFile(attachment.url);
        if (file != null && file.isFile()) return file;
        file = asFile(attachment.thumbnailPath);          // 图片附件：缩略图本身就是本地文件
        return file != null && file.isFile() ? file : null;
    }

    private static File asFile(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            if (raw.startsWith("file://")) {
                // 不经过 Uri.parse：Android 上 file:///sdcard/a.md 解析出的 path 正常，但 JVM/Windows
                // 下 Uri.fromFile 会生成 file://C%3A%5C... 这种"盘符当 authority"的形式，parse 回来是空路径。
                String path = raw.substring("file://".length());
                if (path.isEmpty()) return null;
                try {
                    path = Uri.decode(path);
                } catch (Throwable ignored) {
                }
                return new File(path);
            }
            if (raw.startsWith("/")) return new File(raw);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 异步生成缩略图：后台线程生成，主线程回调。 */
    public static void load(Context context, File file, int maxEdge, Callback callback) {
        final Callback cb = callback != null ? callback : NO_OP;
        if (!supports(file)) {
            MAIN.post(cb::onUnavailable);
            return;
        }
        final Context app = context != null && context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        if (app == null) {
            MAIN.post(cb::onUnavailable);
            return;
        }
        final int edge = Math.max(64, maxEdge);
        new Thread(() -> {
            try {
                FilePreviewManager.getInstance().preview(app, file,
                        new PreviewEngine.PreviewCallback() {
                            @Override
                            public void onSuccess(Bitmap bitmap) {
                                Bitmap shown = fit(bitmap, edge);
                                if (shown == null) {
                                    MAIN.post(cb::onUnavailable);
                                    return;
                                }
                                MAIN.post(() -> cb.onThumbnail(shown));
                            }

                            @Override
                            public void onFailure(String error) {
                                Log.d(TAG, "缩略图不可用: " + file.getName() + "（" + error + "）");
                                MAIN.post(cb::onUnavailable);
                            }
                        });
            } catch (Throwable t) {
                Log.w(TAG, "缩略图生成异常: " + t.getMessage());
                MAIN.post(cb::onUnavailable);
            }
        }, "attachment-thumb").start();
    }

    /** 长边压到 maxEdge 内（返回新 Bitmap 时旧的可回收）。 */
    private static Bitmap fit(Bitmap src, int maxEdge) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        if (w <= maxEdge && h <= maxEdge) return src;
        try {
            float scale = Math.min(maxEdge / (float) w, maxEdge / (float) h);
            Bitmap scaled = Bitmap.createScaledBitmap(src,
                    Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true);
            if (scaled != src) src.recycle();
            return scaled;
        } catch (Throwable t) {
            return src;
        }
    }
}
