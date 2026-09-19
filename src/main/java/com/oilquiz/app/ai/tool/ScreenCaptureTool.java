package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

import com.oilquiz.app.SmartQuizApplication;
import com.oilquiz.app.ai.agent.online.AgentWorkspace;
import com.oilquiz.app.ai.python.PythonToolManager;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 截屏工具：手机端 Agent 的"眼睛"——MediaProjection 授权后截取当前屏幕并保存到工作区 files/screenshots/，
 * 返回图片路径供 Agent 用 file_reader 读取查看。对齐电脑端 computer_use 的截图视觉观察能力。
 *
 * 授权机制：
 * - 首次使用需用户授权（系统弹窗，createScreenCaptureIntent）；
 * - 授权结果经宿主 Activity.onActivityResult → PythonToolManager.onAgentPickerResult 转发（AGENT_PICKERS）；
 * - 授权成功后持有 MediaProjection 复用（未 stop 前再次截屏不再弹窗）；进程重启/用户撤销后需重新授权；
 * - Android 14+ 每次系统弹窗确认，属系统约束。
 *
 * 参数：
 * - filename: 可选，保存文件名（默认 screen_时间戳.png，目录 files/screenshots/）
 */
@Tool(value = "screen_capture", category = "system")
public class ScreenCaptureTool implements AITool {

    private static final String TAG = "ScreenCaptureTool";
    private static final int AUTH_TIMEOUT_SECONDS = 90;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 截屏专用后台线程：VirtualDisplay 创建/抓帧不依赖主线程，避免主线程繁忙导致截屏超时误判投影失效 */
    private static final android.os.HandlerThread CAPTURE_THREAD = new android.os.HandlerThread("screen_capture_thread");
    private static final Handler CAPTURE_HANDLER;

    /** 常驻 VirtualDisplay + ImageReader（借鉴 Auto.js：授权后保持活跃投影，随时取最新帧） */
    private static volatile VirtualDisplay sPersistentVd;
    private static volatile ImageReader sPersistentReader;
    private static volatile MediaProjection sPersistentProjection;
    private static final AtomicReference<Bitmap> sLatestFrame = new AtomicReference<>();

    static {
        CAPTURE_THREAD.start();
        CAPTURE_HANDLER = new Handler(CAPTURE_THREAD.getLooper());
    }

    /** 确保常驻截屏通道已建立（幂等）：投影可用时创建常驻 VD+ImageReader，持续取最新帧 */
    private static void ensurePersistentCapture(MediaProjection projection) {
        if (projection == null || projection == sPersistentProjection && sPersistentVd != null) {
            return;
        }
        releasePersistentCapture();
        sPersistentProjection = projection;
        CAPTURE_HANDLER.post(() -> {
            try {
                WindowManager wm = (WindowManager) SmartQuizApplication.getAppContext()
                        .getSystemService(Context.WINDOW_SERVICE);
                DisplayMetrics metrics = new DisplayMetrics();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    wm.getCurrentWindowMetrics().getBounds();
                    wm.getDefaultDisplay().getRealMetrics(metrics);
                } else {
                    //noinspection deprecation
                    wm.getDefaultDisplay().getRealMetrics(metrics);
                }
                int w = metrics.widthPixels;
                int h = metrics.heightPixels;
                int dpi = metrics.densityDpi;
                ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
                reader.setOnImageAvailableListener(r -> {
                    try (Image image = r.acquireLatestImage()) {
                        if (image != null) {
                            Bitmap bmp = imageToBitmap(image, w, h);
                            Bitmap old = sLatestFrame.getAndSet(bmp);
                            if (old != null) {
                                try {
                                    old.recycle();
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    } catch (Throwable t) {
                        AILogger.w(TAG, "常驻抓帧失败: " + t.getMessage());
                    }
                }, CAPTURE_HANDLER);
                VirtualDisplay vd = projection.createVirtualDisplay("agent_screen_capture_persistent",
                        w, h, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(), null, CAPTURE_HANDLER);
                sPersistentReader = reader;
                sPersistentVd = vd;
                AILogger.i(TAG, "常驻截屏通道已建立（VD+ImageReader），后续截屏毫秒级取帧");
            } catch (Throwable t) {
                AILogger.w(TAG, "常驻截屏通道建立失败（回退按次抓帧）: " + t.getMessage());
                sPersistentVd = null;
                sPersistentReader = null;
            }
        });
    }

    /** 释放常驻截屏通道（投影停止/重授权前必须调用） */
    static void releasePersistentCapture() {
        sLatestFrame.set(null);
        sPersistentProjection = null;
        VirtualDisplay vd = sPersistentVd;
        sPersistentVd = null;
        if (vd != null) {
            CAPTURE_HANDLER.post(vd::release);
        }
        ImageReader ir = sPersistentReader;
        sPersistentReader = null;
        if (ir != null) {
            CAPTURE_HANDLER.post(ir::close);
        }
    }

    private final Context appContext;

    public ScreenCaptureTool() {
        this.appContext = SmartQuizApplication.getAppContext();
    }

    public ScreenCaptureTool(Context context) {
        this.appContext = context != null
                ? context.getApplicationContext()
                : SmartQuizApplication.getAppContext();
    }

    @Override
    public String getName() {
        return "screen_capture";
    }

    @Override
    public String getDescription() {
        return "截屏工具：截取手机当前屏幕画面并保存到工作区 files/screenshots/，返回图片路径。"
                + "截图后必须用 ocr_recognize 工具看图：action=ocr_recognize 识别图中文字，"
                + "action=image_understand 看图理解/回答关于画面的问题（这就是 Agent 的『眼睛』）。"
                + "四种用法：① 截一帧：默认，返回当前画面路径；② 盯屏/轮询观察：action=watch，"
                + "自动每隔几秒截一帧对比画面变化，检测到变化立即返回（含变化时刻与关键帧路径），"
                + "变化后用 ocr_recognize(image_understand) 读关键帧即可理解画面，"
                + "适合等页面加载/下载完成/用户操作结果等场景（观察结束记得 action=stop 停止屏幕共享）；"
                + "③ 停止共享：action=stop，释放授权（下次截屏重新弹窗）。"
                + "首次使用会弹系统授权框（共享屏幕授权）：调用后立即返回，授权弹窗停留在屏幕上，"
                + "请用户在弹窗中点击『立即开始』完成授权，然后再说一次截屏即可生效；"
                + "授权后同进程内可重复截屏不再弹窗；进程重启或用户在系统设置撤销授权后需重新授权。"
                + "filename=可选，自定义保存文件名（默认 screen_时间戳.png）。"
                + "适合：查看当前界面状态、核对用户操作结果、盯屏等页面变化、给后续步骤提供视觉上下文。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("filename", "可选，保存文件名（默认 screen_时间戳.png，保存到 files/screenshots/）");
        params.put("action", "可选: 默认截一帧 / watch=盯屏轮询观察(每隔interval秒截一帧对比变化，检测到变化即返回) / stop=停止屏幕共享释放授权");
        params.put("seconds", "watch 模式观察总时长（秒，默认 30，最大 300）");
        params.put("interval", "watch 模式轮询间隔（秒，默认 3，最小 1）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        // action=stop：停止屏幕共享前台服务，释放投影（下次截屏重新授权）
        Object actObj = parameters.get("action");
        String action = actObj != null ? String.valueOf(actObj).trim().toLowerCase() : "";
        if ("stop".equals(action)) {
            releasePersistentCapture();
            MediaProjectionService.stop(appContext);
            return AIToolResult.success("已停止屏幕共享，释放截屏授权（下次截屏需重新授权）");
        }
        if ("watch".equals(action)) {
            return watchMode(parameters);
        }

        Object nameObj = parameters.get("filename");
        String filename = nameObj != null ? String.valueOf(nameObj).trim() : "";
        if (filename.isEmpty()) {
            filename = "screen_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        }
        if (!filename.toLowerCase().endsWith(".png")) filename += ".png";
        // 防路径穿越
        filename = new File(filename).getName();

        try {
            // 授权等待会阻塞工具线程，必须在后台线程执行（主线程等待会卡死 onActivityResult 分发）
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return AIToolResult.fail("截屏需在后台线程执行，请重试");
            }
            android.app.Activity activity = SmartQuizApplication.getCurrentActivity();
            if (activity == null || activity.isFinishing()) {
                return AIToolResult.fail("没有前台界面可发起截屏授权，请先打开 AI 对话界面再试");
            }

            // 已有投影直接截屏；无投影则同步等待用户完成授权（弹窗→用户点『立即开始』→继续截屏）
            MediaProjection projection = MediaProjectionService.getProjection();
            AILogger.i(TAG, "截屏请求: 投影状态=" + (projection != null ? "可用(复用,不弹窗)" : "无(发起授权)"));
            if (projection == null) {
                projection = ensureProjection(activity);
                if (projection == null) {
                    return AIToolResult.fail("【等待用户操作】截屏授权未完成：系统『共享屏幕』窗口已弹出（或已超时），"
                            + "请提醒用户点击弹窗底部的『立即开始』完成授权后再继续，不要自动重试本工具");
                }
            }
            File outFile = capture(projection, filename);
            if (outFile == null) {
                // 瞬时失败（首帧慢/偶发）：先重试一次，投影本身可能仍有效，不销毁
                AILogger.w(TAG, "截屏失败，重试一次（不销毁投影）…");
                outFile = capture(projection, filename);
            }
            if (outFile == null) {
                // 仍失败 → 投影被系统掐断（切后台/系统回收）→ 自动重新授权，用户只需点『立即开始』即可续上
                AILogger.w(TAG, "截屏两次失败（投影被系统掐断），自动重新发起授权…");
                releasePersistentCapture();
                MediaProjectionService.stop(appContext);
                projection = ensureProjection(activity);
                if (projection == null) {
                    return AIToolResult.fail("【等待用户操作】截屏授权未完成：系统『共享屏幕』窗口已弹出（或已超时），"
                            + "请提醒用户点击弹窗底部的『立即开始』完成授权后再继续，不要自动重试本工具");
                }
                outFile = capture(projection, filename);
                if (outFile == null) {
                    MediaProjectionService.stop(appContext);
                    return AIToolResult.fail("截屏失败（投影被系统停止），请提醒用户重新授权后再试");
                }
            }
            return success(outFile, filename);
        } catch (Exception e) {
            AILogger.e(TAG, "screen capture failed: " + e.getMessage(), e);
            return AIToolResult.fail("截屏失败: " + e.getMessage());
        }
    }

    /**
     * 同步发起 MediaProjection 授权：弹出系统弹窗并阻塞等待用户确认（最长 90 秒）。
     * 用户点击『立即开始』→ 前台服务创建投影 → 返回投影，工具直接继续截屏；
     * 用户取消/超时返回 null（下次调用重新弹窗）。
     * 必须在后台线程调用（主线程等待会阻塞 onActivityResult 分发导致授权结果丢失）。
     */
    public MediaProjection ensureProjection(android.app.Activity activity) throws InterruptedException {
        final MediaProjectionManager mpm = (MediaProjectionManager)
                activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) return null;
        final Intent captureIntent = mpm.createScreenCaptureIntent();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Boolean> cancelled = new AtomicReference<>(false);
        final PythonToolManager ptm = PythonToolManager.getInstance(appContext);
        final int requestCode = ptm.registerAgentPicker(data -> {
            if (data == null) {
                cancelled.set(true);
                latch.countDown();
                return;
            }
            try {
                // Android 14 强制：创建投影前必须启动 mediaProjection 类型的前台服务
                MediaProjectionService.start(appContext, data);
            } catch (Throwable t) {
                AILogger.e(TAG, "启动屏幕共享服务失败: " + t.getMessage(), t);
            }
            latch.countDown();
        });
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                activity.startActivityForResult(captureIntent, requestCode);
            } else {
                final CountDownLatch launched = new CountDownLatch(1);
                final AtomicReference<Throwable> launchErr = new AtomicReference<>();
                activity.runOnUiThread(() -> {
                    try {
                        activity.startActivityForResult(captureIntent, requestCode);
                    } catch (Throwable t) {
                        launchErr.set(t);
                    } finally {
                        launched.countDown();
                    }
                });
                if (!launched.await(5, TimeUnit.SECONDS)) {
                    ptm.removeAgentPicker(requestCode);
                    AILogger.w(TAG, "授权弹窗发起超时");
                    return null;
                }
                if (launchErr.get() != null) {
                    ptm.removeAgentPicker(requestCode);
                    AILogger.w(TAG, "无法发起截屏授权: " + launchErr.get().getMessage());
                    return null;
                }
            }
        } catch (Throwable t) {
            ptm.removeAgentPicker(requestCode);
            AILogger.w(TAG, "无法发起截屏授权: " + t.getMessage());
            return null;
        }
        AILogger.i(TAG, "已弹出共享屏幕授权（requestCode=" + requestCode + "），等待用户确认…");

        if (!latch.await(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            ptm.removeAgentPicker(requestCode);
            AILogger.w(TAG, "截屏授权超时（90 秒内未完成系统弹窗确认，用户可能只选了应用未点『立即开始』）");
            return null;
        }
        if (cancelled.get()) {
            AILogger.i(TAG, "截屏授权被取消/拒绝");
            return null;
        }
        // 服务异步创建投影，等待就绪（最多 8 秒）
        MediaProjection mp = MediaProjectionService.getProjection();
        long deadline = System.currentTimeMillis() + 8000;
        while (mp == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            mp = MediaProjectionService.getProjection();
        }
        if (mp == null) {
            AILogger.w(TAG, "授权成功但投影创建失败（服务未就绪）");
            return null;
        }
        AILogger.i(TAG, "截屏授权完成，投影就绪（同进程内后续截屏复用，不再弹窗）");
        // 等系统授权弹窗/二次弹窗/浮层动画完全退场：连续探测帧画面稳定后才返回，
        // 避免把任何系统 UI 残影拍进截图（比固定 sleep 更可靠）
        waitForStableScreen(mp);
        return mp;
    }

    /**
     * 等待画面稳定：连续 3 帧画面指纹一致视为弹窗/动画已退场（最多等 10 秒）。
     * 探测帧不落盘，仅用于判断。复用投影的截屏不受影响（只在此处调用）。
     */
    private void waitForStableScreen(MediaProjection projection) {
        ensurePersistentCapture(projection);
        long deadline = System.currentTimeMillis() + 10_000;
        String prev = null;
        int stable = 0;
        int probeSeq = 0;
        while (System.currentTimeMillis() < deadline && stable < 3) {
            probeSeq++;
            Bitmap bmp = getLatestFrameCopy(250);
            if (bmp != null) {
                String h = frameHash(bmp);
                bmp.recycle();
                if (h != null) {
                    if (h.equals(prev)) {
                        stable++;
                    } else {
                        stable = 1;
                    }
                    prev = h;
                }
            }
            if (stable >= 3) {
                AILogger.i(TAG, "画面已稳定（探测 " + probeSeq + " 帧），系统弹窗已退场");
                return;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                return;
            }
        }
        AILogger.w(TAG, "画面稳定等待超时（10s），按当前画面继续");
    }

    /**
     * 盯屏/轮询观察模式：每隔 interval 秒截一帧，与上一帧做像素变化检测，
     * 检测到画面变化立即返回（含变化时刻与关键帧路径）；观察满 seconds 秒无变化则返回"无变化"。
     */
    private AIToolResult watchMode(Map<String, Object> parameters) {
        int seconds = 30;
        int interval = 3;
        try {
            Object sObj = parameters.get("seconds");
            if (sObj != null) seconds = Math.min(300, Math.max(5, Integer.parseInt(String.valueOf(sObj).trim())));
            Object iObj = parameters.get("interval");
            if (iObj != null) interval = Math.max(1, Integer.parseInt(String.valueOf(iObj).trim()));
        } catch (Exception ignored) {
        }

        try {
            android.app.Activity activity = SmartQuizApplication.getCurrentActivity();
            if (activity == null || activity.isFinishing()) {
                return AIToolResult.fail("没有前台界面可发起截屏授权，请先打开 AI 对话界面再试");
            }
            MediaProjection projection = MediaProjectionService.getProjection();
            if (projection == null) {
                projection = ensureProjection(activity);
                if (projection == null) {
                    return AIToolResult.fail("【等待用户操作】截屏授权未完成（盯屏场景）：系统『共享屏幕』窗口已弹出（或已超时），"
                            + "请提醒用户点击弹窗底部的『立即开始』完成授权后再继续，不要自动重试本工具");
                }
            }

            String lastHash = null;
            long startTime = System.currentTimeMillis();
            long deadline = startTime + seconds * 1000L;
            List<String> frames = new ArrayList<>();
            int seq = 0;
            while (System.currentTimeMillis() < deadline) {
                seq++;
                String name = "watch_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                        + "_" + seq + ".png";
                File f = capture(projection, name);
                if (f == null) {
                    // 瞬时失败先重试一次（不销毁投影）
                    f = capture(projection, name);
                }
                if (f == null) {
                    // 仍失败 → 投影被系统掐断：自动重新授权一次再续盯梢（用户只需点『立即开始』）
                    AILogger.w(TAG, "盯梢中断（投影被系统掐断），自动重新授权后续盯…");
                    com.oilquiz.app.ai.tool.MediaProjectionService.stop(appContext);
                    releasePersistentCapture();
                    projection = ensureProjection(activity);
                    if (projection == null) {
                        return AIToolResult.fail("盯梢中断：【等待用户操作】截屏授权未完成（投影被系统停止），"
                                + "请提醒用户点击『共享屏幕』弹窗底部的『立即开始』后再继续，不要自动重试本工具");
                    }
                    f = capture(projection, name);
                    if (f == null) {
                        com.oilquiz.app.ai.tool.MediaProjectionService.stop(appContext);
                        return AIToolResult.fail("盯梢中断：截屏失败（投影被系统停止），请重新授权后再盯屏");
                    }
                }
                frames.add(f.getAbsolutePath());
                String h = frameHash(f);
                if (lastHash != null && h != null && !h.equals(lastHash)) {
                    // 画面变化：立即 OCR 关键帧，把画面内容直接给 Agent（无需再绕一圈 ocr_recognize）
                    String ocrText = ocrFrame(f);
                    Map<String, Object> info = new HashMap<>();
                    info.put("changed", true);
                    info.put("changed_after_seconds", (System.currentTimeMillis() - startTime) / 1000);
                    info.put("frames", frames);
                    info.put("latest_frame", f.getAbsolutePath());
                    info.put("relative", "files/screenshots/" + name);
                    info.put("ocr_text", ocrText != null ? ocrText.trim() : "");
                    StringBuilder sb = new StringBuilder();
                    sb.append("画面发生变化（观察 ").append((System.currentTimeMillis() - startTime) / 1000)
                            .append(" 秒后检测到变化）。关键帧已保存: ").append(f.getAbsolutePath());
                    if (ocrText != null && !ocrText.trim().isEmpty()) {
                        String excerpt = ocrText.trim().length() > 800 ? ocrText.trim().substring(0, 800) : ocrText.trim();
                        sb.append("\n画面文字内容（OCR）: ").append(excerpt);
                    } else {
                        sb.append("\n画面文字识别失败（可调用 ocr_recognize action=image_understand 看图理解）");
                    }
                    return AIToolResult.success(sb.toString(), info);
                }
                lastHash = h;
                if (System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(interval * 1000L);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
            Map<String, Object> info = new HashMap<>();
            info.put("changed", false);
            info.put("observed_seconds", seconds);
            info.put("frames", frames);
            if (!frames.isEmpty()) {
                info.put("latest_frame", frames.get(frames.size() - 1));
            }
            return AIToolResult.success("观察 " + seconds + " 秒，画面无变化。"
                    + (frames.isEmpty() ? "" : "期间帧已保存，可用 file_reader 查看。")); 
        } catch (Exception e) {
            AILogger.e(TAG, "watch mode failed: " + e.getMessage(), e);
            return AIToolResult.fail("盯屏观察失败: " + e.getMessage());
        }
    }

    /** 画面指纹：缩小到 16x16 取像素亮度串，用于帧间变化对比（轻量、对微小闪烁不敏感） */
    private String frameHash(File png) {
        try {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(png.getAbsolutePath());
            if (bmp == null) return null;
            return frameHash(bmp);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 画面指纹（Bitmap 重载，用于探测帧） */
    private String frameHash(Bitmap bmp) {
        try {
            android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(bmp, 16, 16, true);
            StringBuilder sb = new StringBuilder(256);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int c = small.getPixel(x, y);
                    int lum = (android.graphics.Color.red(c) + android.graphics.Color.green(c)
                            + android.graphics.Color.blue(c)) / 3;
                    sb.append((char) ('0' + (lum >> 4)));
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 本地 OCR（快、离线可用）：识别图片文字；失败返回 null（供 watch 模式变化后直接给画面内容） */
    private String ocrFrame(File png) {
        try {
            Bitmap bmp = android.graphics.BitmapFactory.decodeFile(png.getAbsolutePath());
            if (bmp == null) return null;
            com.oilquiz.app.manager.OCRManager ocrManager =
                    com.oilquiz.app.toolkit.AppToolkit.getInstance(appContext).getOcrManager();
            final CountDownLatch latch = new CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicReference<String> ref = new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.atomic.AtomicReference<String> errRef = new java.util.concurrent.atomic.AtomicReference<>();
            ocrManager.processImage(bmp, new com.oilquiz.app.manager.OCRManager.OCRCallback() {
                @Override
                public void onSuccess(String text) {
                    ref.set(text);
                    latch.countDown();
                }

                @Override
                public void onFailure(String error) {
                    errRef.set(error);
                    latch.countDown();
                }
            });
            if (!latch.await(20, TimeUnit.SECONDS)) {
                AILogger.w(TAG, "OCR 超时（20s）");
                return null;
            }
            if (errRef.get() != null) {
                AILogger.w(TAG, "OCR 失败: " + errRef.get());
                return null;
            }
            return ref.get();
        } catch (Throwable t) {
            AILogger.w(TAG, "OCR 异常: " + t.getMessage());
            return null;
        }
    }

    private AIToolResult success(File outFile, String filename) {
        Map<String, Object> info = new HashMap<>();
        info.put("path", outFile.getAbsolutePath());
        info.put("filename", filename);
        info.put("relative", "files/screenshots/" + filename);
        return AIToolResult.success(
                "截屏成功，已保存: " + outFile.getAbsolutePath()
                        + "\n下一步用 ocr_recognize 看图：action=ocr_recognize&image_path=" + outFile.getAbsolutePath()
                        + "（识别文字）或 action=image_understand&image_path=" + outFile.getAbsolutePath()
                        + "&question=描述画面内容（看图理解）",
                info);
    }

    /** 截图：优先从常驻截屏通道取最新帧（毫秒级），无则回退按次抓帧（后台线程，不依赖主线程） */
    public File capture(MediaProjection projection, String filename) {
        try {
            ensurePersistentCapture(projection);
            Bitmap bmp = getLatestFrameCopy(3000);
            if (bmp == null) {
                bmp = captureBitmap(projection);
            }
            if (bmp == null) return null;

            AgentWorkspace workspace = AgentWorkspace.getInstance(appContext);
            File shotsDir = new File(workspace.getFilesDir(), "screenshots");
            if (!shotsDir.exists() && !shotsDir.mkdirs()) return null;
            File out = new File(shotsDir, filename);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            bmp.recycle();
            return out;
        } catch (Exception e) {
            AILogger.e(TAG, "capture failed: " + e.getMessage(), e);
            return null;
        }
    }

    /** 从常驻通道取最新帧的拷贝（最多等 timeoutMs）；无常驻通道/无帧返回 null */
    private Bitmap getLatestFrameCopy(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Bitmap b = sLatestFrame.get();
            if (b != null && !b.isRecycled()) {
                try {
                    return b.copy(Bitmap.Config.ARGB_8888, false);
                } catch (Throwable t) {
                    return null;
                }
            }
            try {
                Thread.sleep(30);
            } catch (InterruptedException ie) {
                return null;
            }
        }
        return null;
    }

    /** 抓一帧 Bitmap（后台线程执行 VirtualDisplay + ImageReader，不依赖主线程） */
    private Bitmap captureBitmap(MediaProjection projection) {
        final CountDownLatch frameLatch = new CountDownLatch(1);
        final AtomicReference<Bitmap> bitmapRef = new AtomicReference<>();
        final AtomicReference<VirtualDisplay> vdRef = new AtomicReference<>();
        final AtomicReference<ImageReader> irRef = new AtomicReference<>();

        CAPTURE_HANDLER.post(() -> {
            try {
                WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
                DisplayMetrics metrics = new DisplayMetrics();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    wm.getCurrentWindowMetrics().getBounds();
                    wm.getDefaultDisplay().getRealMetrics(metrics);
                } else {
                    //noinspection deprecation
                    wm.getDefaultDisplay().getRealMetrics(metrics);
                }
                int w = metrics.widthPixels;
                int h = metrics.heightPixels;
                int dpi = metrics.densityDpi;

                ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
                irRef.set(reader);
                reader.setOnImageAvailableListener(r -> {
                    try (Image image = r.acquireLatestImage()) {
                        if (image != null) {
                            bitmapRef.set(imageToBitmap(image, w, h));
                        }
                    } catch (Throwable t) {
                        AILogger.w(TAG, "抓帧失败: " + t.getMessage());
                    } finally {
                        frameLatch.countDown();
                    }
                }, CAPTURE_HANDLER);

                VirtualDisplay vd = projection.createVirtualDisplay("agent_screen_capture",
                        w, h, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(), null, null);
                vdRef.set(vd);
            } catch (Throwable t) {
                AILogger.e(TAG, "capture setup failed: " + t.getMessage(), t);
                frameLatch.countDown();
            }
        });

        try {
            if (!frameLatch.await(8, TimeUnit.SECONDS)) {
                AILogger.w(TAG, "capture 等待首帧超时（8s）");
                return null;
            }
            return bitmapRef.get();
        } catch (Exception e) {
            AILogger.e(TAG, "capture failed: " + e.getMessage(), e);
            return null;
        } finally {
            VirtualDisplay vd = vdRef.get();
            if (vd != null) {
                CAPTURE_HANDLER.post(vd::release);
            }
            ImageReader ir = irRef.get();
            if (ir != null) {
                CAPTURE_HANDLER.post(ir::close);
            }
        }
    }

    /** Image → Bitmap（RGBA_8888，处理行 stride） */
    private static Bitmap imageToBitmap(Image image, int width, int height) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        if (rowStride == width * pixelStride) {
            buffer.rewind();
            bmp.copyPixelsFromBuffer(buffer);
        } else {
            // 逐行拷贝（处理 padding）
            buffer.rewind();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                buffer.position(y * rowStride);
                for (int x = 0; x < width; x++) {
                    int idx = buffer.position();
                    int r = buffer.get(idx) & 0xFF;
                    int g = buffer.get(idx + 1) & 0xFF;
                    int b = buffer.get(idx + 2) & 0xFF;
                    int a = buffer.get(idx + 3) & 0xFF;
                    pixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
                    buffer.position(idx + pixelStride);
                }
            }
            bmp.setPixels(pixels, 0, width, 0, 0, width, height);
        }
        return bmp;
    }

    /** 释放全局投影（权限撤销/App 退出时调用） */
    public static void releaseProjection() {
        MediaProjectionService.stop(SmartQuizApplication.getAppContext());
    }
}
