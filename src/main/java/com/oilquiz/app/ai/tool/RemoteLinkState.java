package com.oilquiz.app.ai.tool;

/**
 * 远程连接状态机（2026-11-17）。
 *
 * <p>以前状态条只有一个数字（"心跳 4s"），到 0 之后什么也不说，用户看不出"正常 / 在等 /
 * 出问题了"的区别。这里把"心跳是否按时到达"翻译成四个可读状态，供状态条文案、
 * 状态点颜色与呼吸开关共用（纯函数，可单测）：</p>
 *
 * <ul>
 *   <li>{@link State#WAITING} 通道已建立但还没收到第一次心跳；</li>
 *   <li>{@link State#HEALTHY} 心跳按时到达（早于 interval+1s），带"下次心跳 Ns"倒计时；</li>
 *   <li>{@link State#LAGGING} 迟到但没超过两个周期 —— 网络抖动，仍在呼吸；</li>
 *   <li>{@link State#ABNORMAL} 超过两个周期没有心跳 —— 报异常，停止呼吸（灯不再假装活着）；</li>
 *   <li>{@link State#DISCONNECTED} 通道本身断了（WS/SSE 不在线）。</li>
 * </ul>
 */
public final class RemoteLinkState {

    public enum State { WAITING, HEALTHY, LAGGING, ABNORMAL, DISCONNECTED }

    /** 当前状态 */
    public final State state;
    /** 人话文案（直接进状态条，不要空泛的"等待中"） */
    public final String text;
    /** HEALTHY 时"距下次心跳还剩几秒"，其余为 0 */
    public final int remainingSec;
    /** 距上次心跳多少秒；-1 表示还没有过心跳 */
    public final long silentSec;
    /** 是否让状态点呼吸（只有认为"还活着"的状态才呼吸） */
    public final boolean breathing;
    /** 是否需要醒目颜色（延迟/异常/断开为 true） */
    public final boolean warn;

    private RemoteLinkState(State state, String text, int remainingSec, long silentSec,
                            boolean breathing, boolean warn) {
        this.state = state;
        this.text = text;
        this.remainingSec = remainingSec;
        this.silentSec = silentSec;
        this.breathing = breathing;
        this.warn = warn;
    }

    /**
     * 根据线上状态与上一次心跳时间判定。
     *
     * @param online        通道是否在线（RemoteConnection.isOnline）
     * @param lastBeatAt    上一次真实心跳的毫秒时间（0 = 从没收到过）
     * @param intervalMs    期望心跳间隔（与 /status 轮询同一个常量）
     * @param now           当前时间
     * @param reconnectCount 已重连次数（异常文案里带上，便于判断是不是一直掉线）
     */
    public static RemoteLinkState evaluate(boolean online, long lastBeatAt, long intervalMs, long now,
                                           int reconnectCount) {
        long interval = Math.max(1000L, intervalMs);
        if (!online) {
            if (lastBeatAt > 0) {
                long silent = Math.max(0L, (now - lastBeatAt) / 1000L);
                return new RemoteLinkState(State.DISCONNECTED,
                        "电脑已断开 · " + silent + "s 前最后心跳", 0, silent, false, true);
            }
            return new RemoteLinkState(State.DISCONNECTED, "电脑已断开 · 点这里重连", 0, -1, false, true);
        }
        if (lastBeatAt <= 0) {
            return new RemoteLinkState(State.WAITING,
                    "等待电脑心跳…（通道已建立）", (int) Math.ceil(interval / 1000.0), -1, true, false);
        }
        long overdue = Math.max(0L, now - lastBeatAt);
        long silent = overdue / 1000L;
        if (overdue <= interval + 1000L) {
            int remain = (int) Math.max(0L, (interval - overdue + 999L) / 1000L);
            String tail = remain > 0 ? "下次心跳 " + remain + "s" : "心跳刚到";
            return new RemoteLinkState(State.HEALTHY, "连接正常 · " + tail, remain, silent, true, false);
        }
        if (overdue <= interval * 2) {
            return new RemoteLinkState(State.LAGGING,
                    "心跳延迟 · 已 " + silent + "s 没等到，可能网络抖动", 0, silent, true, true);
        }
        String re = reconnectCount > 0 ? "（已重连 " + reconnectCount + " 次）" : "";
        return new RemoteLinkState(State.ABNORMAL,
                "连接异常 · 已 " + silent + "s 没有心跳" + re + "，正在等它恢复", 0, silent, false, true);
    }

    /** 调试/日志用：SHORT 文本 */
    public String shortText() {
        switch (state) {
            case WAITING: return "等待心跳";
            case HEALTHY: return "正常";
            case LAGGING: return "心跳延迟";
            case ABNORMAL: return "连接异常";
            case DISCONNECTED: return "已断开";
            default: return String.valueOf(state);
        }
    }
}
