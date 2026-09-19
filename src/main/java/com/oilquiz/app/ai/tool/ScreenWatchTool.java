package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.projection.MediaProjection;
import android.os.Looper;

import com.oilquiz.app.SmartQuizApplication;
import com.oilquiz.app.ai.agent.online.AgentWorkspace;
import com.oilquiz.app.ai.python.PythonToolManager;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.manager.OCRManager;
import com.oilquiz.app.toolkit.AppToolkit;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 盯梢工具：手机端 Agent 的"哨兵"——持续监控屏幕，直到出现/消失指定内容或画面发生变化。
 *
 * 用法：
 * - 指定目标文字（target）：轮询截帧 + 本地 OCR 识别，目标出现(appear，默认)或消失(disappear)时立即返回；
 *   例如"盯梢：等屏幕上出现『下载完成』"、"盯着屏幕直到『加载中』消失"。
 * - 不指定目标：只检测画面变化（与截屏 watch 相同），画面一变立即返回。
 * - 命中/变化时返回关键帧路径，Agent 可用 ocr_recognize 读图理解。
 *
 * 授权：复用 ScreenCaptureTool 的同步授权（弹窗→用户点『立即开始』→投影就绪→开始盯梢），
 * 授权期间工具会等待用户，不会误报失败。同进程内授权一次后复用。
 *
 * 参数：
 * - action: start(默认，开始盯梢) / stop(停止) / status(状态)
 * - target: 要等待出现/消失的目标文字（可选，不填则检测画面变化）
 * - watch_for: appear(默认，目标出现即报) / disappear(目标消失即报)
 * - seconds: 总盯梢时长(默认60，最大600)
 * - interval: 轮询间隔(默认3，最小2)
 */
@Tool(value = "screen_watch", category = "system")
public class ScreenWatchTool implements AITool {

    private static final String TAG = "ScreenWatchTool";
    private static final int MAX_SECONDS = 600;

    private final Context appContext;
    private final ScreenCaptureTool captureTool;
    private volatile boolean stopRequested = false;

    public ScreenWatchTool() {
        this(SmartQuizApplication.getAppContext());
    }

    public ScreenWatchTool(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : SmartQuizApplication.getAppContext();
        this.captureTool = new ScreenCaptureTool(appContext);
    }

    @Override
    public String getName() {
        return "screen_watch";
    }

    @Override
    public String getDescription() {
        return "盯梢工具（哨兵）：持续监控手机屏幕，直到『指定内容出现/消失』或『画面发生变化』才返回。"
                + "① 指定目标文字：target=要等的文字，watch_for=appear(出现即报,默认)/disappear(消失即报)，"
                + "轮询截帧并本地OCR识别判断，命中立即返回关键帧路径与识别文本，"
                + "适合：等『下载完成/支付成功/加载完成』出现、等『加载中/处理中』消失；"
                + "② 不指定目标：检测画面变化，画面一变立即返回（适合等页面跳转/内容刷新）。"
                + "动作：action=start(默认)开始盯梢，agent会在目标命中或超时后返回；"
                + "action=stop 停止当前盯梢；action=status 查询盯梢状态。"
                + "参数：seconds=总时长(默认60,最大600)、interval=轮询间隔(默认3,最小2)。"
                + "首次使用会弹『共享屏幕』授权框：请用户点击『立即开始』完成授权（工具会等待用户操作，不会中途失败）；"
                + "授权后同进程内复用，盯梢结束可 action=stop 释放授权。"
                + "盯梢过程中 App 需保持前台（Android 14 切后台投影会中断）。"
                + "命中后用 ocr_recognize 读关键帧即可理解画面（action=image_understand&image_path=帧路径&question=画面内容）。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "start(默认,开始盯梢)/stop(停止盯梢)/status(查询盯梢状态)");
        params.put("target", "要等待的目标文字（可选，不填则检测画面变化；如：下载完成、支付成功、加载中）");
        params.put("watch_for", "appear(默认,目标出现即报)/disappear(目标消失即报)");
        params.put("seconds", "总盯梢时长（秒，默认60，最大600）");
        params.put("interval", "轮询间隔（秒，默认3，最小2）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        Object actObj = parameters.get("action");
        String action = actObj != null ? String.valueOf(actObj).trim().toLowerCase() : "start";
        switch (action) {
            case "stop":
                stopRequested = true;
                return AIToolResult.success("已请求停止盯梢（如有正在执行的盯梢将在下一轮终止）");
            case "status":
                return AIToolResult.success("盯梢状态：停止请求=" + stopRequested
                        + "，投影可用=" + (com.oilquiz.app.ai.tool.MediaProjectionService.getProjection() != null));
            case "start":
            default:
                return startWatch(parameters);
        }
    }

    private AIToolResult startWatch(Map<String, Object> parameters) {
        String target = parameters.get("target") != null ? String.valueOf(parameters.get("target")).trim() : "";
        String watchFor = parameters.get("watch_for") != null
                ? String.valueOf(parameters.get("watch_for")).trim().toLowerCase() : "appear";
        boolean waitAppear = !"disappear".equals(watchFor);
        int seconds = 60;
        int interval = 3;
        try {
            Object sObj = parameters.get("seconds");
            if (sObj != null) seconds = Math.min(MAX_SECONDS, Math.max(5, Integer.parseInt(String.valueOf(sObj).trim())));
            Object iObj = parameters.get("interval");
            if (iObj != null) interval = Math.max(2, Integer.parseInt(String.valueOf(iObj).trim()));
        } catch (Exception ignored) {
        }

        try {
            // 授权等待阻塞线程，须在后台线程执行
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return AIToolResult.fail("盯梢需在后台线程执行，请重试");
            }
            android.app.Activity activity = SmartQuizApplication.getCurrentActivity();
            if (activity == null || activity.isFinishing()) {
                return AIToolResult.fail("没有前台界面可发起截屏授权，请先打开 AI 对话界面再试");
            }
            MediaProjection projection = com.oilquiz.app.ai.tool.MediaProjectionService.getProjection();
            AILogger.i(TAG, "盯梢请求: 投影状态=" + (projection != null ? "可用(复用,不弹窗)" : "无(发起授权)"));
            if (projection == null) {
                projection = captureTool.ensureProjection(activity);
                if (projection == null) {
                    return AIToolResult.fail("截屏授权未完成：请在系统『共享屏幕』窗口中点击『立即开始』完成授权，然后重新说一次盯梢");
                }
            }

            stopRequested = false;
            AILogger.i(TAG, "开始盯梢: target=" + (target.isEmpty() ? "(画面变化)" : target)
                    + " watchFor=" + (waitAppear ? "出现" : "消失")
                    + " seconds=" + seconds + " interval=" + interval);

            long startTime = System.currentTimeMillis();
            long deadline = startTime + seconds * 1000L;
            List<String> frames = new ArrayList<>();
            String lastHash = null;
            int seq = 0;
            AppToolkit toolkit = AppToolkit.getInstance(appContext);
            OCRManager ocrManager = toolkit.getOcrManager();

            while (System.currentTimeMillis() < deadline) {
                if (stopRequested) {
                    AILogger.i(TAG, "盯梢被手动停止");
                    return AIToolResult.fail("盯梢已停止（用户/Agent 手动停止）");
                }
                seq++;
                String name = "watch_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                        + "_" + seq + ".png";
                File f = captureTool.capture(projection, name);
                if (f == null) {
                    // 瞬时失败先重试一次（不销毁投影）
                    f = captureTool.capture(projection, name);
                }
                if (f == null) {
                    // 仍失败 → 投影被系统掐断：自动重新授权一次再续盯梢（用户只需点『立即开始』）
                    AILogger.w(TAG, "盯梢中断（投影被系统掐断），自动重新授权后续盯…");
                    com.oilquiz.app.ai.tool.MediaProjectionService.stop(appContext);
                    com.oilquiz.app.ai.tool.ScreenCaptureTool.releasePersistentCapture();
                    projection = captureTool.ensureProjection(activity);
                    if (projection == null) {
                        return AIToolResult.fail("盯梢中断：【等待用户操作】截屏授权未完成（投影被系统停止），"
                                + "请提醒用户点击『共享屏幕』弹窗底部的『立即开始』后再继续，不要自动重试本工具");
                    }
                    f = captureTool.capture(projection, name);
                    if (f == null) {
                        com.oilquiz.app.ai.tool.MediaProjectionService.stop(appContext);
                        return AIToolResult.fail("盯梢中断：截屏失败（投影被系统停止），请重新授权后再盯梢");
                    }
                }
                frames.add(f.getAbsolutePath());

                String h = frameHash(f);
                boolean changed = lastHash != null && h != null && !h.equals(lastHash);
                lastHash = h;

                // 目标文字检测（本地 OCR，快且不依赖网络）
                if (!target.isEmpty()) {
                    String ocrText = recognizeLocal(ocrManager, f);
                    boolean hit = ocrText != null && ocrText.contains(target);
                    if (hit && waitAppear) {
                        return hitResult(target, true, f, ocrText, frames, (System.currentTimeMillis() - startTime) / 1000);
                    }
                    if (!hit && !waitAppear) {
                        // 目标消失：上一帧还在、当前帧已不在
                        return hitResult(target, false, f, ocrText, frames, (System.currentTimeMillis() - startTime) / 1000);
                    }
                    // appear 模式：目标出现前的变化不中断，继续等
                } else if (changed) {
                    // 画面变化：立即 OCR 关键帧，把画面内容直接给 Agent（无需再绕一圈 ocr_recognize）
                    String ocrText = recognizeLocal(ocrManager, f);
                    Map<String, Object> info = new HashMap<>();
                    info.put("changed", true);
                    info.put("changed_after_seconds", (System.currentTimeMillis() - startTime) / 1000);
                    info.put("frames", frames);
                    info.put("latest_frame", f.getAbsolutePath());
                    info.put("ocr_text", ocrText != null ? ocrText.trim() : "");
                    StringBuilder sb = new StringBuilder();
                    sb.append("画面发生变化（盯梢 ").append((System.currentTimeMillis() - startTime) / 1000)
                            .append(" 秒后）。").append("\n关键帧已保存: ").append(f.getAbsolutePath());
                    if (ocrText != null && !ocrText.trim().isEmpty()) {
                        String excerpt = ocrText.trim().length() > 800 ? ocrText.trim().substring(0, 800) : ocrText.trim();
                        sb.append("\n画面文字内容（OCR）: ").append(excerpt);
                    } else {
                        sb.append("\n画面文字识别失败（可调用 ocr_recognize action=image_understand 看图理解）");
                    }
                    return AIToolResult.success(sb.toString(), info);
                }
                if (System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(interval * 1000L);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }

            Map<String, Object> info = new HashMap<>();
            info.put("hit", false);
            info.put("observed_seconds", seconds);
            info.put("frames", frames);
            if (target.isEmpty()) {
                return AIToolResult.fail("盯梢 " + seconds + " 秒，画面无变化。"
                        + (frames.isEmpty() ? "" : "期间帧已保存，可用 file_reader/ocr_recognize 查看。"));
            }
            return AIToolResult.fail("盯梢 " + seconds + " 秒，目标『" + target + "』未"
                    + (waitAppear ? "出现" : "消失") + "（可延长 seconds 或检查目标文字是否准确）。");
        } catch (Exception e) {
            AILogger.e(TAG, "watch failed: " + e.getMessage(), e);
            return AIToolResult.fail("盯梢失败: " + e.getMessage());
        }
    }

    private AIToolResult hitResult(String target, boolean appeared, File frame, String ocrText,
                                   List<String> frames, long elapsedSeconds) {
        Map<String, Object> info = new HashMap<>();
        info.put("hit", true);
        info.put("target", target);
        info.put("watch_for", appeared ? "appear" : "disappear");
        info.put("hit_after_seconds", elapsedSeconds);
        info.put("frames", frames);
        info.put("latest_frame", frame.getAbsolutePath());
        info.put("ocr_text", ocrText != null ? ocrText.trim() : "");
        String actionWord = appeared ? "出现" : "消失";
        StringBuilder sb = new StringBuilder();
        sb.append("盯梢命中：目标『").append(target).append("』已").append(actionWord)
                .append("（盯梢 ").append(elapsedSeconds).append(" 秒）。")
                .append("\n关键帧已保存: ").append(frame.getAbsolutePath());
        if (ocrText != null && !ocrText.trim().isEmpty()) {
            String excerpt = ocrText.trim().length() > 800 ? ocrText.trim().substring(0, 800) : ocrText.trim();
            sb.append("\n画面文字内容（OCR）: ").append(excerpt);
        } else {
            sb.append("\n画面文字识别失败（可调用 ocr_recognize action=image_understand 看图理解）");
        }
        sb.append("\n如需进一步理解画面，可调用 ocr_recognize action=image_understand&image_path=")
                .append(frame.getAbsolutePath()).append("&question=描述画面当前状态");
        return AIToolResult.success(sb.toString(), info);
    }

    /** 本地 OCR（快、离线可用）：识别图片文字；失败返回 null */
    private String recognizeLocal(OCRManager ocrManager, File png) {
        try {
            Bitmap bmp = BitmapFactoryDecode(png);
            if (bmp == null) return null;
            final CountDownLatch latch = new CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicReference<String> ref = new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.atomic.AtomicReference<String> errRef = new java.util.concurrent.atomic.AtomicReference<>();
            ocrManager.processImage(bmp, new OCRManager.OCRCallback() {
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

    private Bitmap BitmapFactoryDecode(File png) {
        try {
            return android.graphics.BitmapFactory.decodeFile(png.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 画面指纹：缩小到 16x16 取像素亮度串，用于帧间变化对比 */
    private String frameHash(File png) {
        try {
            Bitmap bmp = android.graphics.BitmapFactory.decodeFile(png.getAbsolutePath());
            if (bmp == null) return null;
            Bitmap small = android.graphics.Bitmap.createScaledBitmap(bmp, 16, 16, true);
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
}
