package com.oilquiz.app.ai.remote;

/**
 * UI 呈现节拍器（2026-11-18，用户口径"就算合起来也该给 UI 反应时间"）。
 *
 * <p><b>问题</b>：事件合帧解决了"主线程被淹没"，但合帧后如果仍按 16ms 连发投影，
 * 折叠头会在几帧内连续改状态（展开→运行中→收起），UI 来不及播完 200ms 的动画，
 * 看起来就是跳变/闪烁——用户侧表现为"不好折叠、有显示 bug"。</p>
 *
 * <p><b>做法</b>：接收器先把攒下的事件集合**归约成一次投影**（一批最多 50 条），
 * 再按最小间隔（默认 120ms）通知 UI。两次通知之间继续收集、合并，UI 每次拿到的都是
 * 一个**完整且自洽的状态**，并且至少有 120ms 的反应时间把上一段动画播完。</p>
 *
 * <p><b>为什么是纯类</b>：节流边界最容易写错（第一次放行、边界相等、时间回拨），
 * 抽出来单测，Activity 只负责"问能不能放、放完记一笔"。</p>
 */
public final class PresentationPacer {

    /** 两次 UI 投影之间的最小间隔：120ms ≈ 8 次/秒，够 200ms 的折叠动画播一半以上。 */
    public static final long DEFAULT_MIN_INTERVAL_MS = 120L;

    private final long minIntervalMs;
    /** 上次投影时刻（-1 = 还没投过）。 */
    private long lastPresentAt = -1L;

    public PresentationPacer() {
        this(DEFAULT_MIN_INTERVAL_MS);
    }

    public PresentationPacer(long minIntervalMs) {
        this.minIntervalMs = Math.max(0L, minIntervalMs);
    }

    public long minIntervalMs() {
        return minIntervalMs;
    }

    /** 现在能不能把这一批投影给 UI。 */
    public boolean canPresent(long nowMs) {
        if (lastPresentAt < 0L) {
            return true;
        }
        long elapsed = nowMs - lastPresentAt;
        // SystemClock 单调递增，正常不会回拨；真回拨（测试/重启）就当已到点，别把 UI 永久压住
        return elapsed < 0L || elapsed >= minIntervalMs;
    }

    /** 记录一次投影完成（必须在 UI 线程、投影之后调用）。 */
    public void notePresented(long nowMs) {
        lastPresentAt = nowMs;
    }

    /** 距离下一次允许投影还要等多久（ms）；0 = 现在就可以。 */
    public long delayUntilNext(long nowMs) {
        if (lastPresentAt < 0L) {
            return 0L;
        }
        long elapsed = nowMs - lastPresentAt;
        if (elapsed < 0L) {
            return 0L;
        }
        long remain = minIntervalMs - elapsed;
        return Math.max(0L, remain);
    }

    /** 页面销毁/重建时复位（避免拿旧时间戳压住新页面的第一帧）。 */
    public void reset() {
        lastPresentAt = -1L;
    }
}
