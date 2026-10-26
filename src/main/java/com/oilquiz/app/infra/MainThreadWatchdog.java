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
 * <p>本类只做一件事：哨兵线程每 500ms 往主线程投递一枚心跳，并记住"最近一次心跳被主线程
 * 执行掉"的时刻；主线程吊住时这个时刻会停住不动，距今一旦超过 {@link #STALL_THRESHOLD_MS}
 * 就认定卡死（与系统判 ANR 的 5s 输入分发超时同量级），把 {@link #KEY_ANR_BOOT} 写进
 * {@code crash_protection_prefs}。度量必须取"被应答"的时刻而不是"被投递"的时刻——
 * 后者每 500ms 就被刷新一次，差值永远够不到阈值，见 {@link #isStalled} 的说明。
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
            final AtomicLong postedSeq = new AtomicLong(0);
            final AtomicLong answeredSeq = new AtomicLong(0);
            /** 最近一次"心跳被主线程执行掉"的时刻；主线程吊住时它就停住不动 —— 唯一可靠的卡死度量 */
            final AtomicLong answeredAtElapsed = new AtomicLong(SystemClock.elapsedRealtime());
            handler.post(new Runnable() {
                /** 上一次报告卡死时的 outstanding 拍号：同一次卡死只写一次，恢复后的新卡死还能再记 */
                private long reportedPostedSeq = -1;

                @Override
                public void run() {
                    long now = SystemClock.elapsedRealtime();
                    long outstanding = postedSeq.get();
                    if (isStalled(now, answeredAtElapsed.get(), outstanding, answeredSeq.get(),
                            STALL_THRESHOLD_MS) && reportedPostedSeq != outstanding) {
                        reportedPostedSeq = outstanding;
                        try {
                            prefs.edit()
                                    .putString(KEY_ANR_BOOT, bootId)
                                    .putLong(KEY_ANR_AT, now)
                                    .apply();
                            AILogger.w(TAG, "主线程卡死：已 "
                                    + (now - answeredAtElapsed.get()) + "ms 没有执行掉任何一拍心跳"
                                    + "（outstanding=" + outstanding + "，boot=" + bootId
                                    + "）→ 记为 ANR 死因");
                        } catch (Throwable ignore) {
                        }
                    }
                    final long tick = postedSeq.incrementAndGet();
                    main.post(() -> {
                        // 能在主线程跑起来 = 主线程此刻没被吊住；这一行就是"心跳被应答"的唯一凭据
                        answeredSeq.set(tick);
                        answeredAtElapsed.set(SystemClock.elapsedRealtime());
                    });
                    handler.postDelayed(this, PROBE_INTERVAL_MS);
                }
            });
            AILogger.i(TAG, "主线程卡死哨兵已启动（boot=" + bootId + "）");
        } catch (Throwable t) {
            AILogger.e(TAG, "主线程卡死哨兵启动失败", t);
        }
    }

    /**
     * 卡死判定（纯函数，便于单测）。
     *
     * <p>度量必须是<b>"最近一次心跳被主线程执行掉"距今多久</b>，不是"最近一次投递距今多久"：
     * 投递每 {@code PROBE_INTERVAL_MS} 就发一次，后者的差值天然只有半秒，永远够不到阈值——
     * 2026-10-25 真机第一版就栽在这里，同一进程 gfxinfo 明明有 8 帧 ≥4.95s
     * （{@code Davey! duration=9137ms}、{@code Skipped 741 frames}），哨兵却一声不响，
     * 让"没有卡死记录"变成了无效证据。主线程吊住时 {@code answeredAtElapsed} 会停住不动，
     * 差值才会真正长到阈值。</p>
     *
     * @param nowElapsed           当前 elapsedRealtime
     * @param lastAnsweredAtElapsed 最近一次心跳被主线程执行掉的时刻
     * @param postedSeq            已投递的心跳拍号
     * @param answeredSeq          已被主线程执行掉的心跳拍号
     * @param thresholdMs          判定阈值
     */
    public static boolean isStalled(long nowElapsed, long lastAnsweredAtElapsed,
                                    long postedSeq, long answeredSeq, long thresholdMs) {
        if (postedSeq <= answeredSeq) {
            return false; // 没有待应答的心跳：主线程刚应答过，一定不卡
        }
        if (nowElapsed < lastAnsweredAtElapsed) {
            return false; // 计时源回拨等异常：宁可不报，也别把健康会话判成卡死
        }
        return nowElapsed - lastAnsweredAtElapsed >= thresholdMs;
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
