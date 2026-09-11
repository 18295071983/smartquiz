package com.oilquiz.app.ai.chat.component;

/**
 * 统一的全屏图片预览工具（全 App 各入口共用同一套"全屏流"）：
 * - 黑底全屏 Dialog（window 强制 MATCH_PARENT）
 * - PhotoView 双指缩放 + 初始 FIT_CENTER（小图也撑满可视区）
 * - 本地文件（file:// / 绝对路径 / content://）→ BitmapFactory 采样解码（系统解码，防 OOM）
 * - 网络图 → HttpURLConnection 原生下载 + BitmapFactory 解码（绕开 Glide 在设备上的
 *   长时间不回调/转圈问题；手机实测同 URL 1~3s 出图）
 * - 全程 loading 转圈提示，失败 Toast 明确报错，点击图片关闭
 */
public final class ImagePreviewUtil {

    private ImagePreviewUtil() {
    }

    public static void show(android.content.Context context, String url) {
        try {
            if (url == null || url.isEmpty()) return;
            if (!(context instanceof android.app.Activity)) {
                android.widget.Toast.makeText(context, "无法预览图片", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            final android.app.Dialog dialog = new android.app.Dialog(context);
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            final android.widget.FrameLayout root = new android.widget.FrameLayout(context);
            root.setBackgroundColor(android.graphics.Color.BLACK);
            final com.github.chrisbanes.photoview.PhotoView photoView =
                    new com.github.chrisbanes.photoview.PhotoView(context);
            photoView.setBackgroundColor(android.graphics.Color.BLACK);
            photoView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            root.addView(photoView, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            final android.widget.ProgressBar loading = new android.widget.ProgressBar(context);
            root.addView(loading, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER));
            dialog.setContentView(root, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            photoView.setOnClickListener(v -> dialog.dismiss());
            dialog.show();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                dialog.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK));
            }

            // 本地文件路径解析
            String localPath = null;
            if (url.startsWith("file://")) {
                localPath = android.net.Uri.parse(url).getPath();
            } else if (url.startsWith("/")) {
                localPath = url;
            }
            if (localPath != null && new java.io.File(localPath).isFile()) {
                decodeFileInThread(dialog, photoView, loading, context, new java.io.File(localPath));
                return;
            }
            if (url.startsWith("content://")) {
                decodeContentInThread(dialog, photoView, loading, context, url);
                return;
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                downloadInThread(dialog, photoView, loading, context, url);
                return;
            }
            // 无法识别的来源
            if (dialog.isShowing()) dialog.dismiss();
            android.widget.Toast.makeText(context, "无法识别的图片来源", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            try {
                android.widget.Toast.makeText(context, "图片预览失败: " + t.getMessage(),
                        android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 本地文件：BitmapFactory 采样解码（防 OOM），后台线程执行 */
    private static void decodeFileInThread(final android.app.Dialog dialog,
                                           final com.github.chrisbanes.photoview.PhotoView photoView,
                                           final android.widget.ProgressBar loading,
                                           final android.content.Context context,
                                           final java.io.File file) {
        new Thread(() -> {
            try {
                android.graphics.Bitmap bmp = decodeSampled(file.getAbsolutePath(), 2048);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    try {
                        if (bmp != null) {
                            if (dialog.isShowing()) {
                                photoView.setImageBitmap(bmp);
                                loading.setVisibility(android.view.View.GONE);
                            }
                        } else {
                            fail(dialog, context, "图片解码失败（文件损坏或非图片）");
                        }
                    } catch (Throwable ignored) {
                    }
                });
            } catch (Throwable t) {
                fail(dialog, context, "图片加载失败: " + t.getMessage());
            }
        }).start();
    }

    /** content://：ContentResolver 流 + 采样解码 */
    private static void decodeContentInThread(final android.app.Dialog dialog,
                                              final com.github.chrisbanes.photoview.PhotoView photoView,
                                              final android.widget.ProgressBar loading,
                                              final android.content.Context context,
                                              final String uri) {
        new Thread(() -> {
            try {
                android.graphics.Bitmap bmp = null;
                try {
                    android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    try (java.io.InputStream is = context.getContentResolver()
                            .openInputStream(android.net.Uri.parse(uri))) {
                        if (is != null) android.graphics.BitmapFactory.decodeStream(is, null, bounds);
                    }
                    int sample = 1;
                    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
                        sample *= 2;
                    }
                    try (java.io.InputStream is = context.getContentResolver()
                            .openInputStream(android.net.Uri.parse(uri))) {
                        if (is != null) {
                            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                            opts.inSampleSize = sample;
                            bmp = android.graphics.BitmapFactory.decodeStream(is, null, opts);
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.w("ImagePreviewUtil", "content decode failed: " + e.getMessage());
                }
                final android.graphics.Bitmap fbmp = bmp;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    try {
                        if (fbmp != null) {
                            if (dialog.isShowing()) {
                                photoView.setImageBitmap(fbmp);
                                loading.setVisibility(android.view.View.GONE);
                            }
                        } else {
                            fail(dialog, context, "图片解码失败（文件损坏或非图片）");
                        }
                    } catch (Throwable ignored) {
                    }
                });
            } catch (Throwable t) {
                fail(dialog, context, "图片加载失败: " + t.getMessage());
            }
        }).start();
    }

    /** 网络图：HttpURLConnection 下载 + 采样解码（绕开 Glide），超时 8s/10s */
    private static void downloadInThread(final android.app.Dialog dialog,
                                         final com.github.chrisbanes.photoview.PhotoView photoView,
                                         final android.widget.ProgressBar loading,
                                         final android.content.Context context,
                                         final String url) {
        new Thread(() -> {
            try {
                // 第一遍：读尺寸（采样用）
                android.graphics.BitmapFactory.Options bounds =
                        new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);
                conn.connect();
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code);
                }
                try (java.io.InputStream is = conn.getInputStream()) {
                    android.graphics.BitmapFactory.decodeStream(is, null, bounds);
                }
                int sample = 1;
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
                    sample *= 2;
                }
                // 第二遍：正式解码
                java.net.HttpURLConnection conn2 =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn2.setInstanceFollowRedirects(true);
                conn2.setConnectTimeout(8000);
                conn2.setReadTimeout(10000);
                conn2.connect();
                android.graphics.Bitmap bmp = null;
                try (java.io.InputStream is2 = conn2.getInputStream()) {
                    android.graphics.BitmapFactory.Options opts =
                            new android.graphics.BitmapFactory.Options();
                    opts.inSampleSize = sample;
                    bmp = android.graphics.BitmapFactory.decodeStream(is2, null, opts);
                }
                final android.graphics.Bitmap fbmp = bmp;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    try {
                        if (fbmp != null) {
                            if (dialog.isShowing()) {
                                photoView.setImageBitmap(fbmp);
                                loading.setVisibility(android.view.View.GONE);
                            }
                        } else {
                            fail(dialog, context, "图片解码失败（格式不支持或数据损坏）");
                        }
                    } catch (Throwable ignored) {
                    }
                });
            } catch (Throwable t) {
                fail(dialog, context, "图片加载失败: " + t.getMessage());
            }
        }).start();
    }

    /** 采样解码本地文件 */
    private static android.graphics.Bitmap decodeSampled(String path, int maxDimension) {
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(path, opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
        int sample = 1;
        while (opts.outWidth / sample > maxDimension || opts.outHeight / sample > maxDimension) {
            sample *= 2;
        }
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sample;
        return android.graphics.BitmapFactory.decodeFile(path, opts);
    }

    /** 失败：关窗 + Toast */
    private static void fail(final android.app.Dialog dialog,
                             final android.content.Context context,
                             final String msg) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                if (dialog.isShowing()) dialog.dismiss();
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
    }
}
