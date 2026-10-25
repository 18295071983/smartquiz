package com.oilquiz.app.infra;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import com.oilquiz.app.util.AILogger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 主线程卡死哨兵（死亡归因，2026-10-25）。
 *
 * <p>背景：{@code SmartQuizApplication} 的"连续崩溃计数"把**任何**没活满 30 秒的进程死亡
 * 都记成一次崩溃，可它分不清死因。真机现场（2026-10-25 dropbox）里两次被计入的其实是
 * {@code Input dispatching timed out}（聊天页 WebView 绑定吊住主线程 &gt; 5s 被系统杀掉），
 * 还有开发期的 force-stop——这些和"模型自动加载把进程搞崩"是两回事，
 * 却会触发"跳过模型自动加载"，把不相干的死因算到模型头上。</p>
 *
 * <p>本类只做一件事：哨兵线程每 500ms 往主线程投递一枚心跳；下一拍检查时若上一拍仍未被
 * 主线程执行、且已超过 {@link #STALL_THRESHOLD_MS}，就认定主线程卡住（与系统判 ANR 的
 * 5s 输入分发超时同量级），把 {@link #KEY_ANR_BOOT} 写进 {@code crash_protection_prefs}。
 * 正常路径只是 Handler.post + 原子写，**不碰磁盘**；只有真的卡住才 apply 一次。</p>
 */
public final class MainThreadWatchdog {

    private static final String TAG = "MainThreadWatchdog";

    /** 与 SmartQuizApplication 的崩溃保护共用同一份 prefs。 */
    public static final String CRASH_PREFS = "crash_protection_prefs";
    /** 卡死发生时所在的启动代次（与 {@code session_boot_id} 同值）。 */
    public static final String KEY_ANR_BOOT = "anr_boot_id";
    /** 卡死检测时刻（elapsedRealtime），用于判断新鲜度。 */
    public static final String KEY_ANR_AT = "anr_at_elapsed";

    /** 心跳投递间隔 */
    private static final long PROBE_INTERVAL_MS = 500L;
    /** 心跳未被执行多久算卡死（对齐系统 5s 输入分发超时） */
    public static final long STALL_THRESHOLD_MS = 5_000L;

    /** 卡死标记的有效期：超过 24 小时的旧标记不参与归因 */
    private static final long MARKER_TTL_MS = 24 * 3600_000L;

    private static final AtomicBoolean started = new AtomicBoolean(false);

    private MainThreadWatchdog() {
    }

    /**
     * 启动哨兵。重复调用只生效一次；全程 try 兜底，哨兵自身出问题不影响应用。
     *
     * @param appContext 应用上下文（prefs 用）
     * @param bootId 本次启动代次，卡死时写进 {@link #KEY_ANR_BOOT}
     */
    public static void start(final Context appContext, final String bootId) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            HandlerThread thread = new HandlerThread("MainThreadWatchdog");
            thread.start();
            final Handler handler = new Handler(thread.getLooper());
            final Handler main = new Handler(Looper.getMainLooper());
            final SharedPreferences prefs =
                    appContext.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE);
            final AtomicLong respondedSeq = new AtomicLong(0);
            handler.post(new Runnable() {
                /** 上一拍心跳的序号与投递时刻；-1 = 还没有过一拍 */
                private long pendingSeq = -1;
                private long pendingAtElapsed = 0;
                /** 已记录过卡死的拍号：一次卡死只写一次，恢复后新卡死还能再记 */
                private long reportedSeq = -1;

                @Override
                public void run() {
                    long now = SystemClock.elapsedRealtime();
                    if (pendingSeq >= 0 && respondedSeq.get() < pendingSeq
                            && now - pendingAtElapsed >= STALL_THRESHOLD_MS
                            && reportedSeq != pendingSeq) {
                        reportedSeq = pendingSeq;
                        try {
                            prefs.edit()
                                    .putString(KEY_ANR_BOOT, bootId)
                                    .putLong(KEY_ANR_AT, now)
                                    .apply();
                            AILogger.w(TAG, "主线程卡死 ≥" + STALL_THRESHOLD_MS + "ms（seq="
                                    + pendingSeq + "，boot=" + bootId + "）→ 记为 ANR 死因");
                        } catch (Throwable ignore) {
                        }
                    }
                    final long tick = pendingSeq + 1;
                    pendingSeq = tick;
                    pendingAtElapsed = now;
                    main.post(() -> respondedSeq.set(tick));
                    handler.postDelayed(this, PROBE_INTERVAL_MS);
                }
            });
            AILogger.i(TAG, "主线程卡死哨兵已启动（boot=" + bootId + "）");
        } catch (Throwable t) {
            AILogger.e(TAG, "主线程卡死哨兵启动失败", t);
        }
    }

    /**
     * 上次启动是否被记为"主线程卡死（ANR）"。纯函数，便于单测。
     *
     * @param anrBoot    prefs 里记录的卡死 bootId（null=从没卡过）
     * @param prevBoot   上次启动的 bootId
     * @param nowElapsed 当前 elapsedRealtime
     * @param anrAtElapsed 上次记录卡死的时刻
     */
    public static boolean isAnrDeath(String anrBoot, String prevBoot,
                                     long nowElapsed, long anrAtElapsed) {
        if (anrBoot == null || prevBoot == null || !anrBoot.equals(prevBoot)) {
            return false;
        }
        if (nowElapsed < anrAtElapsed) {
            return false; // 计时源回拨等异常：宁可当真崩溃处理
        }
        return nowElapsed - anrAtElapsed < MARKER_TTL_MS;
    }
}
