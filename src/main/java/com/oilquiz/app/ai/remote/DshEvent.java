package com.oilquiz.app.ai.remote;

import org.json.JSONObject;

/**
 * 电脑端（DSH）事件流里的一条原始事件 —— 值对象，解析一次后只读。
 *
 * <p>为什么要有这一层：转换器之前直接对 {@code JSONObject} 做 {@code optString},
 * 每个分支都在猜字段名；而"事件带不带 turn/step"这件事（不带时要沿用上一条的位置）
 * 又被每个分支各写一遍。这里把信封解析收口成一次：</p>
 *
 * <pre>
 * { v, seq, id, ts, type, data, ... }
 * </pre>
 *
 * <p>{@link #turn}/{@link #step} 为 {@link #NO_INDEX} 表示这条事件<b>没带</b>位置，
 * 由 {@link DshTimeline} 决定挂到哪一步；注意 approval/question 的
 * {@code request} 在信封顶层而不是 {@code data} 里，所以保留 {@link #raw}。</p>
 */
public final class DshEvent {

    /** turn/step 缺失 */
    public static final int NO_INDEX = -1;

    public final String type;
    public final int seq;
    public final String id;
    public final long ts;
    public final JSONObject data;
    /** 原始信封：approval/question 的 request 在顶层，事件里也有 v/kind 等字段需要偶尔回看 */
    public final JSONObject raw;
    public final int turn;
    public final int step;

    private DshEvent(String type, int seq, String id, long ts, JSONObject data, JSONObject raw,
                     int turn, int step) {
        this.type = type;
        this.seq = seq;
        this.id = id;
        this.ts = ts;
        this.data = data;
        this.raw = raw;
        this.turn = turn;
        this.step = step;
    }

    public static DshEvent from(JSONObject raw) {
        if (raw == null) return null;
        String type = raw.optString("type", "").trim();
        if (type.isEmpty()) return null;
        JSONObject data = raw.optJSONObject("data");
        int turn = data != null && data.has("turn") ? data.optInt("turn", NO_INDEX) : NO_INDEX;
        int step = data != null && data.has("step") ? data.optInt("step", NO_INDEX) : NO_INDEX;
        long ts = raw.optLong("ts", raw.optLong("time", 0L));
        return new DshEvent(type, raw.optInt("seq", -1), raw.optString("id", "").trim(),
                ts, data, raw, turn, step);
    }

    /** 这条事件是否自带位置（turn 缺失时 step 也不可信，按缺失处理） */
    public boolean located() {
        return turn != NO_INDEX;
    }

    /** 取 data 里某个数组，缺省返回空数组（转换器不写 null 分支） */
    public JSONObject dataOrEmpty() {
        return data != null ? data : new JSONObject();
    }

    @Override
    public String toString() {
        return "DshEvent{" + type + " seq=" + seq + " turn=" + turn + " step=" + step + "}";
    }
}
