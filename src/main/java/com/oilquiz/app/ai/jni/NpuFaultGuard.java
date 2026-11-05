package com.oilquiz.app.ai.jni;

import android.content.Context;
import android.content.SharedPreferences;

import com.oilquiz.app.ai.spi.AppServices;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * NPU（hexagon/HTP）硬件层致命中断的自愈守卫（2026-11-01）。
 *
 * <p><b>为什么需要</b>：hexagon 后端在 DSP 故障时走 {@code GGML_ABORT} → {@code abort()} →
 * SIGABRT。仓库里已有的 sigsetjmp 兜底是 {@code thread_local} 的：抛信号的线程上没 arm 过
 * 跳转点时，处理器只能 {@code SIG_DFL + raise}，进程必死——QNN/HTP 自己的 worker 线程就属于
 * 这种（{@code native-lib.cpp:fatal_signal_handler}）。这类死亡在进程内救不回来，能救的是
 * <b>下一次</b>：处理器在死前用 open/write 落一条记录（{@code write_fatal_signal_record}），
 * 下次启动读到它就说明"上次死在硬件层"，本次装载临时改用 GPU(opencl) 继续可用。</p>
 *
 * <p><b>三条纪律</b>：<br>
 * ① <b>绝不写</b> {@code gpu_backend} 偏好——那是用户显式选择，降级只做运行时覆盖，用户设置页
 * 看不出被篡改；<br>
 * ② 交还自动：降级期间只要有任意一轮正常收尾，计数与降级标记一起清零，下次装载回到偏好后端
 * （hexagon），不需要人记着改回来；<br>
 * ③ 判定核心（{@link Snapshot} 上的静态方法）不碰 Android，JVM 单测直接喂事件序列。</p>
 *
 * <p><b>代价与边界</b>：降级生效点是<b>下一次模型装载</b>（后端只在装载时传给 native），
 * 所以同一会话里已经装载好的模型不会因为本轮中断就换后端；内存里那套 KV 也已跟着中断作废。
 * 若用户在中断后立刻重试而模型没有重新装载，看到的仍是同一条错误——这不是守卫失灵，
 * 守卫给的是"下次启动/下次装载"的可用性。</p>
 */
public final class NpuFaultGuard {

    private static final String TAG = "NpuFaultGuard";

    /** 降级目标：hexagon 故障时改走 OpenCL（本机实测 decode ~15.6 tok/s，慢但可用）。 */
    public static final String FALLBACK_BACKEND = "opencl";
    /** 连续几次硬件层致命中断才降级：1 次可能只是偶发，2 次连续说明这台机器当前跑不了 HTP。 */
    public static final int FATALS_BEFORE_DEMOTE = 2;

    private static final String PREFS = "npu_fault_guard";
    private static final String K_INFLIGHT_SINCE = "inflight_since";
    private static final String K_INFLIGHT_BACKEND = "inflight_backend";
    private static final String K_CONSECUTIVE = "consecutive_fatal";
    private static final String K_DEMOTE_ARMED = "demote_armed";

    /** 观测到的致命信号计数（进程内，读 native 计数器）。 */
    private static volatile int sFaultsAtRoundStart = -1;
    private static volatile boolean sRoundInflight;
    /** 本轮是否真的吐出了最终回答（complete 分支置位）。普通错误/取消不置位。 */
    private static volatile boolean sDelivered;
    /** 上一次打过"已降级"日志的后端，避免每轮装载都刷一条。 */
    private static volatile String sLastAnnouncedOverride;
    /** 记录文件路径是否已成功下发（native 未载入时下发会静默失败，需要重试）。 */
    private static volatile boolean sRecordPathSet;

    private NpuFaultGuard() {
    }

    // ==================== 纯决策核心（单测覆盖这一段） ====================

    /** 决策只需要两个量：连续致命中断次数、是否已处于降级状态。 */
    public static final class Snapshot {
        public final int consecutiveFatal;
        public final boolean demoteArmed;

        public Snapshot(int consecutiveFatal, boolean demoteArmed) {
            this.consecutiveFatal = Math.max(0, consecutiveFatal);
            this.demoteArmed = demoteArmed;
        }
    }

    public static Snapshot idle() {
        return new Snapshot(0, false);
    }

    /** 观测到一次硬件层致命中断。 */
    public static Snapshot onFatalObserved(Snapshot s) {
        Snapshot base = s != null ? s : idle();
        int c = base.consecutiveFatal + 1;
        return new Snapshot(c, base.demoteArmed || c >= FATALS_BEFORE_DEMOTE);
    }

    /** 一轮正常收尾：计数清零，降级标记一并解除（下次装载自动交还原后端）。 */
    public static Snapshot onHealthyRound(Snapshot s) {
        return idle();
    }

    /**
     * 装载期实际下发给 native 的后端。只在"已降级 且 请求的是 hexagon"时替换：
     * 用户显式选的 opencl/vulkan/auto 一律不动（我们只兜 NPU 这一类故障）。
     */
    public static String effectiveBackend(Snapshot s, String requested) {
        if (s == null || !s.demoteArmed || requested == null) return requested;
        return "hexagon".equalsIgnoreCase(requested) ? FALLBACK_BACKEND : requested;
    }

    // ==================== 轮次生命周期（桥接层只调这三个） ====================

    /** 一轮推理进入 native 之前调用：记在飞标记 + 快照当前致命计数。 */
    public static void onRoundStart(Context context, String backend) {
        Context ctx = resolve(context);
        sRoundInflight = true;
        sDelivered = false;
        sFaultsAtRoundStart = LlamaHelper.getFatalSignalsCaught();
        if (ctx != null) {
            // 记录文件路径也要在这里兜一次：Application.onCreate 那次可能早于 native 库加载，
            // 失败会静默返回 false——不补的话"首轮就崩"这一类恰好没有证据。
            if (!sRecordPathSet) {
                File f = recordFile(ctx);
                if (f != null && LlamaHelper.setFatalRecordFile(f.getAbsolutePath())) {
                    sRecordPathSet = true;
                }
            }
            prefs(ctx).edit()
                    .putLong(K_INFLIGHT_SINCE, System.currentTimeMillis())
                    .putString(K_INFLIGHT_BACKEND, backend)
                    .apply();
        }
    }

    /** complete 分支调用：本轮真的交付了回答。 */
    public static void markDelivered() {
        sDelivered = true;
    }

    /**
     * 一轮结束（complete / error / 取消 / 任何兜底收尾）调用，幂等。
     *
     * @return 需要追加到错误卡上的"下一步"提示；无需提示时返回 null
     */
    public static String onRoundEnd(Context context) {
        if (!sRoundInflight) return null;
        sRoundInflight = false;
        Context ctx = resolve(context);

        int nowFaults = LlamaHelper.getFatalSignalsCaught();
        int delta = (sFaultsAtRoundStart >= 0 && nowFaults >= 0) ? (nowFaults - sFaultsAtRoundStart) : 0;
        // 兜底：即使计数器不可用（native 未载入等），处理器落的记录也能证明这一轮出过致命信号
        boolean recordFault = consumeRecordFile(ctx);

        Snapshot before = load(ctx);
        Snapshot after;
        String hint = null;
        if (delta > 0 || recordFault) {
            after = onFatalObserved(before);
            if (after.demoteArmed && !before.demoteArmed) {
                hint = "已连续 " + after.consecutiveFatal + " 次硬件层中断（NPU），下次装载将临时改用 GPU 跑本地模型。";
                AILogger.audit(TAG, "NPU 连续致命中断 " + after.consecutiveFatal
                        + " 次 → 装载期临时降级到 " + FALLBACK_BACKEND + "（不改用户偏好）");
            } else {
                AILogger.i(TAG, "NPU 硬件层中断（连续第 " + after.consecutiveFatal
                        + " 次，阈值 " + FATALS_BEFORE_DEMOTE + "），暂不降级");
            }
        } else if (sDelivered) {
            after = onHealthyRound(before);
            if (before.demoteArmed || before.consecutiveFatal > 0) {
                AILogger.audit(TAG, "NPU 恢复：本轮正常收尾，解除降级标记，下次装载回到偏好后端");
            }
        } else {
            after = before;  // 普通错误/取消：不奖励也不惩罚
        }
        save(ctx, after);
        clearInflight(ctx);
        return hint;
    }

    // ==================== 装载期与启动期 ====================

    /** 后端解析点用：返回本次装载实际应下发的后端（可能是降级值）。 */
    public static String effectiveBackend(Context context, String requested) {
        Context ctx = resolve(context);
        String chosen = effectiveBackend(load(ctx), requested);
        if (equals(chosen, requested)) {
            // 回到偏好值：清掉去重标记，之后再次降级时这条 AUDIT 还能打出来。
            sLastAnnouncedOverride = null;
            return chosen;
        }
        if (!equals(chosen, sLastAnnouncedOverride)) {
            sLastAnnouncedOverride = chosen;
            AILogger.audit(TAG, "装载期后端覆盖：" + requested + " → " + chosen
                    + "（因连续硬件层中断，用户偏好未改动）");
        }
        return chosen;
    }

    /**
     * 启动时（下发后端偏好之前）调用：读上一次进程留下的致命信号记录。
     *
     * @return 本次启动是否带着"上次死于硬件层"的证据
     */
    public static boolean consumeBootFatalRecord(Context context) {
        Context ctx = resolve(context);
        File f = recordFile(ctx);
        if (f == null || !f.exists()) return false;
        readRecord(ctx);
        try {
            f.delete();
        } catch (Throwable ignored) {
        }
        Snapshot before = load(ctx);
        Snapshot after = onFatalObserved(before);
        save(ctx, after);
        String backend = prefs(ctx).getString(K_INFLIGHT_BACKEND, "未知");
        clearInflight(ctx);
        AILogger.audit(TAG, "上次进程死于硬件层致命信号（backend=" + backend
                + "，连续第 " + after.consecutiveFatal + " 次"
                + (after.demoteArmed ? "，本次装载临时降级到 " + FALLBACK_BACKEND : "")
                + "）");
        return true;
    }

    /** 供退出归因用：上次是否死在一轮推理中间（与后端名一起返回，便于直接写日志）。 */
    public static String describeUnfinishedRound(Context context) {
        Context ctx = resolve(context);
        if (ctx == null) return null;
        SharedPreferences p = prefs(ctx);
        long since = p.getLong(K_INFLIGHT_SINCE, 0L);
        if (since <= 0L) return null;
        String backend = p.getString(K_INFLIGHT_BACKEND, "未知");
        return "上次死在推理轮内（backend=" + backend + "，始于 " + since + "）";
    }

    /** 致命信号落盘文件（native 在信号上下文里写，Java 读后即删）。 */
    public static File recordFile(Context context) {
        Context ctx = resolve(context);
        if (ctx == null) return null;
        File dir = new File(ctx.getFilesDir(), "oilquiz/logs");
        return new File(dir, "npu_fatal.last");
    }

    // ==================== 内部 ====================

    private static Context resolve(Context context) {
        if (context != null) return context;
        try {
            return AppServices.appContext();
        } catch (Throwable t) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Snapshot load(Context ctx) {
        if (ctx == null) return idle();
        try {
            SharedPreferences p = prefs(ctx);
            return new Snapshot(p.getInt(K_CONSECUTIVE, 0), p.getBoolean(K_DEMOTE_ARMED, false));
        } catch (Throwable t) {
            return idle();
        }
    }

    private static void save(Context ctx, Snapshot s) {
        if (ctx == null || s == null) return;
        try {
            prefs(ctx).edit()
                    .putInt(K_CONSECUTIVE, s.consecutiveFatal)
                    .putBoolean(K_DEMOTE_ARMED, s.demoteArmed)
                    .apply();
        } catch (Throwable t) {
            AILogger.w(TAG, "save failed: " + t.getMessage());
        }
    }

    private static void clearInflight(Context ctx) {
        if (ctx == null) return;
        try {
            prefs(ctx).edit().remove(K_INFLIGHT_SINCE).remove(K_INFLIGHT_BACKEND).apply();
        } catch (Throwable stepFailed) {
            com.oilquiz.app.util.AILogger.w("NpuFaultGuard", "这一步失败，结果可能被当成正常 · clearInflight: " + stepFailed);
        }
    }

    /** 读记录文件但不删（用于启动期先判 presence）。 */
    private static String readRecord(Context ctx) {
        File f = recordFile(ctx);
        if (f == null || !f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.US_ASCII) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 读并删除记录文件。存在即代表有过一次致命信号（含进程随之死亡的情况）。 */
    private static boolean readRecordFile(Context ctx) {
        File f = recordFile(ctx);
        if (f == null || !f.exists()) return false;
        readRecord(ctx);
        // 删除失败也无害：下次启动会再被判一次（最坏多降级一次，自愈仍成立）
        try {
            f.delete();
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** 轮末消费记录文件（进程内被接住的致命信号也会落盘，用完即删，避免重复计数）。 */
    private static boolean consumeRecordFile(Context ctx) {
        return readRecordFile(ctx);
    }

    private static boolean equals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
