package com.oilquiz.app.ai.tool;

import org.json.JSONObject;

/**
 * 远程连接状态条的**实时状态**（2026-11-13）：从会话事件里派生出"电脑现在在干什么"。
 *
 * <p>为什么单独一个类：{@code /status} 只能给出"运行中/排队/待审批"这种粗粒度计数，
 * 而手机每秒都在收会话事件（{@code turn/start}、{@code tool/call}、{@code tool/result}…）。
 * 把这些事件折叠成一个状态机，状态条就能显示"执行工具 bash 0:42"这种动态信息，
 * 而且计时用**本地时钟**——即使服务端日志静止，秒数照样在跳，卡死一眼可见。</p>
 *
 * <p>线程模型：写入在 RemoteConnection 的主线程回调里；读取在 UI 线程。字段全部 volatile，
 * 不做加锁（读到的只是一个瞬时快照，轻微不一致无害）。</p>
 */
public final class RemoteLiveStatus {

    public enum Phase {
        IDLE, THINKING, GENERATING, TOOL, WAITING_APPROVAL, COMPACTING, RETRYING
    }

    private static volatile Phase phase = Phase.IDLE;
    private static volatile String tool = "";
    private static volatile long phaseSince = 0L;
    private static volatile long lastEventAt = 0L;
    private static volatile int turn = 0;
    private static volatile int step = 0;
    private static volatile int events = 0;
    private static volatile long windowStartedAt = 0L;
    private static volatile int windowEvents = 0;

    private RemoteLiveStatus() {
    }

    /** 事件入口：RemoteConnection 每收到一条会话事件就调一次。 */
    public static void onEvent(String type, JSONObject data) {
        if (type == null || type.isEmpty()) return;
        long now = System.currentTimeMillis();
        lastEventAt = now;
        events += 1;
        if (windowStartedAt == 0L || now - windowStartedAt > 60000L) {
            windowStartedAt = now;
            windowEvents = 1;
        } else {
            windowEvents += 1;
        }

        switch (type) {
            case "turn/start":
                turn = optInt(data, "turn", turn + 1);
                step = 0;
                tool = "";
                setPhase(Phase.THINKING, now);
                break;
            case "step/start":
                syncTurn(data);
                step = optInt(data, "step", step);
                if (phase == Phase.IDLE) setPhase(Phase.THINKING, now);
                break;
            case "assistant/message":
                if (phase != Phase.WAITING_APPROVAL) setPhase(Phase.GENERATING, now);
                break;
            case "tool/call":
                syncTurn(data);
                tool = optString(data, "name");
                if (tool.isEmpty()) tool = deepToolName(data);
                setPhase(Phase.TOOL, now);
                break;
            case "tool/result":
                syncTurn(data);
                tool = "";
                setPhase(Phase.GENERATING, now);
                break;
            case "approval/asked":
                setPhase(Phase.WAITING_APPROVAL, now);
                break;
            case "approval/decided":
                setPhase(Phase.THINKING, now);
                break;
            case "llm/retry":
            case "llm/retry-started":
                setPhase(Phase.RETRYING, now);
                break;
            case "compaction/start":
                setPhase(Phase.COMPACTING, now);
                break;
            case "compaction/end":
            case "compaction/prune":
                if (phase == Phase.COMPACTING) setPhase(Phase.THINKING, now);
                break;
            case "turn/end":
                tool = "";
                setPhase(Phase.IDLE, now);
                break;
            case "user/message":
                if (phase == Phase.IDLE) setPhase(Phase.THINKING, now);
                break;
            default:
                break;
        }
    }

    /** 事件里带 turn 字段时同步轮次（不同事件形状不完全一致，取到就用）。 */
    private static void syncTurn(JSONObject data) {
        int t = optInt(data, "turn", 0);
        if (t > 0) turn = t;
    }

    private static void setPhase(Phase p, long at) {
        if (phase != p) {
            phase = p;
            phaseSince = at;
        }
    }

    /** 断开/切会话时清空，避免把上一条会话的状态带过来。 */
    public static void reset() {
        phase = Phase.IDLE;
        tool = "";
        phaseSince = 0L;
        lastEventAt = 0L;
        turn = 0;
        step = 0;
        events = 0;
        windowStartedAt = 0L;
        windowEvents = 0;
    }

    /** 当前状态快照（给状态栏/详情面板用）。 */
    public static Snapshot snapshot() {
        long now = System.currentTimeMillis();
        return new Snapshot(
                phase,
                tool,
                phaseSince <= 0L ? 0L : now - phaseSince,
                lastEventAt <= 0L ? -1L : now - lastEventAt,
                turn,
                step,
                events,
                windowStartedAt <= 0L ? 0 : windowEvents,
                lastEventAt);
    }

    private static int optInt(JSONObject o, String k, int def) {
        try {
            return o == null ? def : o.optInt(k, def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static String optString(JSONObject o, String k) {
        try {
            String s = o == null ? "" : o.optString(k, "");
            return s == null ? "" : s.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 兜底：从 data.message.content 里挖 tool-call 的 name（事件形状不固定时用）。 */
    private static String deepToolName(JSONObject data) {
        try {
            JSONObject msg = data == null ? null : data.optJSONObject("message");
            org.json.JSONArray arr = msg == null ? null : msg.optJSONArray("content");
            if (arr == null) return "";
            for (int i = 0; i < arr.length(); i++) {
                JSONObject part = arr.optJSONObject(i);
                if (part == null) continue;
                if ("tool-call".equals(part.optString("type"))) {
                    String n = part.optString("name", "");
                    if (!n.isEmpty()) return n;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 不可变快照。 */
    public static final class Snapshot {
        public final Phase phase;
        public final String tool;
        public final long phaseElapsedMs;
        public final long lastEventAgeMs;
        public final int turn;
        public final int step;
        public final int events;
        public final int windowEvents;
        public final long lastEventAt;

        Snapshot(Phase phase, String tool, long phaseElapsedMs, long lastEventAgeMs,
                 int turn, int step, int events, int windowEvents, long lastEventAt) {
            this.phase = phase;
            this.tool = tool == null ? "" : tool;
            this.phaseElapsedMs = phaseElapsedMs;
            this.lastEventAgeMs = lastEventAgeMs;
            this.turn = turn;
            this.step = step;
            this.events = events;
            this.windowEvents = windowEvents;
            this.lastEventAt = lastEventAt;
        }

        public boolean isWorking() {
            return phase != Phase.IDLE && phase != Phase.WAITING_APPROVAL;
        }
    }
}
