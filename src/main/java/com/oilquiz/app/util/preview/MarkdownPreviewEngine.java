package com.oilquiz.app.util.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.oilquiz.app.ai.chat.render.MarkdownDocPage;
import com.oilquiz.app.util.render.FileEncodingUtil;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Markdown 缩略图引擎（2026-10-25 重做）。
 *
 * <p>此前是"正则把 md 转 HTML → {@code Html.fromHtml} 去标签 → {@code Canvas.drawText} 逐行画"，
 * 表格、代码块、公式、列表全部丢掉，缩略图就是一段没排版的纯文本，而且行数一多还会被截断。</p>
 *
 * <p>现在改成与 {@code markdown_doc} 卡片、开源许可页同一条渲染链路：{@link MarkdownDocPage}
 * 生成自带 markdown-it + footnote/deflist/tasklists + KaTeX + highlight.js 的<b>单文件</b> HTML，
 * 交给一个没挂到窗口上的 WebView 加载；{@code onPageFinished} 后再等一帧让字体/表格稳定，
 * 然后 measure + layout，软件层 draw 到 Bitmap（与 {@code WebRenderTool} 的网页截图同一做法），
 * 最后缩到 720px 宽按文件内容哈希缓存。</p>
 *
 * <p>线程：WebView 只能在主线程创建，而截图又要等它的回调，所以 {@link #preview} 统一丢后台线程，
 * 主线程建/画、后台读文件与缓存，结果回调再回主线程 —— 调用方在主线程调也不会死锁。</p>
 */
public class MarkdownPreviewEngine extends BasePreviewEngine {

    private static final String TAG = "MarkdownPreviewEngine";
    private static final String[] SUPPORTED_EXTENSIONS = {".md", ".markdown"};

    /** 缓存/输出缩略图宽度（高度按 4:3 竖版文档） */
    private static final int THUMBNAIL_WIDTH = 720;
    /** 离屏 WebView 的布局宽度上限：太宽费内存，太窄中文行宽不够 */
    private static final int MAX_CAPTURE_WIDTH = 1080;
    private static final float CAPTURE_ASPECT = 4f / 3f;
    /** onPageFinished 后再等一帧，等字体/表格完成首次排版 */
    private static final long SETTLE_MS = 120L;
    /** 页面加载卡住时的兜底：按已渲染内容截图 */
    private static final long TIMEOUT_MS = 12_000L;
    private static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    private static final int MAX_MARKDOWN_CHARS = 2_000_000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public boolean supports(File file) {
        return hasExtension(file, SUPPORTED_EXTENSIONS);
    }

    @Override
    public String getEngineName() {
        return "Markdown Preview Engine";
    }

    @Override
    public void preview(Context context, File file, PreviewCallback callback,
                        PreviewProgressCallback progressCallback) {
        if (file == null || !file.isFile()) {
            callback.onFailure("文件不存在");
            return;
        }
        if (!supports(file)) {
            callback.onFailure("文件格式不支持");
            return;
        }
        if (file.length() > MAX_FILE_BYTES) {
            callback.onFailure("文件过大，无法预览");
            return;
        }
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        new Thread(() -> {
            Bitmap bitmap = null;
            String error = null;
            try {
                bitmap = getCachedPreview(app, file);
                if (bitmap == null) {
                    bitmap = generatePreview(app, file, progressCallback);
                    if (bitmap != null) {
                        cachePreview(app, file, bitmap);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "生成 Markdown 缩略图失败: " + t.getMessage(), t);
                error = t.getMessage();
            }
            final Bitmap result = bitmap;
            final String err = error;
            MAIN.post(() -> {
                if (result != null) {
                    if (progressCallback != null) progressCallback.onProgress(100);
                    callback.onSuccess(result);
                } else {
                    callback.onFailure("生成预览失败" + (err == null ? "" : ": " + err));
                }
            });
        }, "md-thumbnail").start();
    }

    @Override
    protected Bitmap generatePreview(Context context, File file,
                                     PreviewProgressCallback progressCallback) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 主线程不能阻塞等 WebView 回调；主线程入口请走 preview()（异步）
            throw new IllegalStateException("主线程请调用 preview()，不要直接生成缩略图");
        }
        String markdown = FileEncodingUtil.readText(file, MAX_MARKDOWN_CHARS);
        return capture(context, file, buildThumbnailHtml(context, markdown));
    }

    /** 缩略图页面：与 markdown_doc 卡片同源；相对图片按 md 所在目录解析（baseUrl）。 */
    static String buildThumbnailHtml(Context context, String markdown) {
        return MarkdownDocPage.buildForScreen(context, markdown, false, false);
    }

    /** md 所在目录的 file:// base；无父目录时返回 null（相对图片失效，但页面仍可显示）。 */
    static String baseUrlOf(File file) {
        try {
            File dir = file == null ? null : file.getAbsoluteFile().getParentFile();
            if (dir == null) return null;
            String url = Uri.fromFile(dir).toString();
            return url.endsWith("/") ? url : url + "/";
        } catch (Throwable t) {
            return null;
        }
    }

    /** 后台线程阻塞等一次离屏截图（{@link #preview} 与直接调用的旧入口共用）。 */
    private static Bitmap capture(Context context, File file, String html) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Bitmap> bitmap = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
        new Capture(context, file, html, (shot, err) -> {
            bitmap.set(shot);
            error.set(err);
            done.countDown();
        }).start();
        if (!done.await(TIMEOUT_MS + 3_000L, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("缩略图截取超时");
        }
        Bitmap shot = bitmap.get();
        if (shot == null) {
            throw new IllegalStateException(error.get() != null ? error.get() : "缩略图截取失败");
        }
        return shot;
    }

    /** 一次离屏截图：主线程建 WebView → 等页面完成 → draw 到 Bitmap → 缩放 → 销毁。 */
    private static final class Capture implements Runnable {

        interface ShotCallback {
            /** @param bitmap null 表示失败；error 非空时是降级原因（如超时） */
            void onShot(Bitmap bitmap, String error);
        }

        private final Context context;
        private final File file;
        private final String html;
        private final ShotCallback callback;
        private final int width;
        private final int height;
        private final long startedAt = SystemClock.uptimeMillis();
        private final Runnable timeout = () -> complete("页面加载超时");

        private WebView webView;
        private boolean finished;

        Capture(Context context, File file, String html, ShotCallback callback) {
            this.context = context;
            this.file = file;
            this.html = html;
            this.callback = callback;
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            int screenWidth = dm != null ? dm.widthPixels : MAX_CAPTURE_WIDTH;
            this.width = Math.max(THUMBNAIL_WIDTH, Math.min(screenWidth, MAX_CAPTURE_WIDTH));
            this.height = Math.round(this.width * CAPTURE_ASPECT);
        }

        void start() {
            MAIN.post(this);
        }

        @Override
        public void run() {
            try {
                WebView view = new WebView(context);
                webView = view;
                view.setBackgroundColor(Color.WHITE);
                // 软件层渲染，draw 到 Canvas 才拿得到内容（与 WebRenderTool 截图一致）
                view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

                WebSettings settings = view.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(false);
                settings.setAllowFileAccess(true);          // md 同目录的本地图片
                settings.setAllowContentAccess(false);
                settings.setAllowFileAccessFromFileURLs(false);
                settings.setAllowUniversalAccessFromFileURLs(false);
                settings.setJavaScriptCanOpenWindowsAutomatically(false);
                settings.setBlockNetworkLoads(true);        // 缩略图不拉网络资源：离线、快
                settings.setBlockNetworkImage(true);
                settings.setUseWideViewPort(true);
                settings.setLoadWithOverviewMode(true);
                settings.setSupportZoom(false);
                settings.setBuiltInZoomControls(false);
                settings.setDisplayZoomControls(false);

                view.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView v, String url) {
                        // 页面脚本是内联同步执行的，onPageFinished 时正文已渲染；
                        // 再等一帧，避免首次排版/字体还没落定。
                        // 注意必须用 MAIN 而不是 view.postDelayed：这个 WebView 从没 attach 到
                        // 窗口，View.postDelayed 会进 attach 前的 RunQueue，永远不会被执行。
                        MAIN.postDelayed(() -> {
                            if (!finished) complete(null);
                        }, SETTLE_MS);
                    }
                });

                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, width, height);
                view.loadDataWithBaseURL(baseUrlOf(file), html, "text/html", "UTF-8", null);
                MAIN.postDelayed(timeout, TIMEOUT_MS);
            } catch (Throwable t) {
                Log.w(TAG, "离屏 WebView 初始化失败: " + t.getMessage());
                complete("离屏 WebView 初始化失败: " + t.getMessage());
            }
        }

        private void complete(String error) {
            if (finished) return;
            finished = true;
            MAIN.removeCallbacks(timeout);
            Log.d(TAG, "离屏截图完成: " + (SystemClock.uptimeMillis() - startedAt) + "ms"
                    + (error != null ? "（" + error + "）" : ""));

            Bitmap shot = null;
            try {
                if (webView != null) {
                    webView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    webView.layout(0, 0, width, height);
                    shot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(shot);
                    canvas.drawColor(Color.WHITE);      // 页面 body 透明，缩略图给白底
                    webView.draw(canvas);
                }
            } catch (Throwable t) {
                Log.w(TAG, "缩略图 draw 失败: " + t.getMessage());
            }
            releaseWebView();

            if (shot == null) {
                callback.onShot(null, error != null ? error : "缩略图截取失败");
                return;
            }
            Bitmap out = shot;
            if (shot.getWidth() > THUMBNAIL_WIDTH) {
                try {
                    int thumbHeight = Math.max(1,
                            Math.round(shot.getHeight() * THUMBNAIL_WIDTH / (float) shot.getWidth()));
                    Bitmap scaled = Bitmap.createScaledBitmap(shot, THUMBNAIL_WIDTH, thumbHeight, true);
                    if (scaled != shot) {
                        shot.recycle();
                        out = scaled;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "缩略图缩放失败: " + t.getMessage());
                }
            }
            callback.onShot(out, error);
        }

        private void releaseWebView() {
            final WebView view = webView;
            webView = null;
            if (view == null) return;
            try {
                view.stopLoading();
                view.loadUrl("about:blank");
                view.destroy();
            } catch (Throwable ignored) {
            }
        }
    }
}
