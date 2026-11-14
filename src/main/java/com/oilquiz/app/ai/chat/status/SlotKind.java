package com.oilquiz.app.ai.chat.status;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 插槽 {@code kind} 契约（④-1，2026-11-15）。
 *
 * <p>以前 {@code kind} 只有零散取值（display/list…），没有必填/可选字段表，插件和 App
 * 各猜各的。现在收敛为 4 种，<b>每种只有这些字段有意义</b>（其余忽略）：</p>
 *
 * <table>
 *   <tr><th>kind</th><th>必填</th><th>可选</th><th>渲染</th></tr>
 *   <tr><td>state（默认）</td><td>title</td><td>detail, warn, level</td><td>圆点 + 标题 + 副标题一行</td></tr>
 *   <tr><td>progress</td><td>title, progress(0–100)</td><td>detail</td><td>标题 + 细进度条 + 百分比</td></tr>
 *   <tr><td>list</td><td>title, count(&gt;0)</td><td>detail</td><td>标题 + 右侧「N 条」徽标</td></tr>
 *   <tr><td>action</td><td>title, actions[1–2]</td><td>detail</td><td>标题 + 右侧内联胶囊按钮</td></tr>
 * </table>
 *
 * <p><b>降级规则</b>（插件 {@code normalizeSlotFields} 同口径，不 422、不丢帧）：</p>
 * <ol>
 *   <li>未知 kind → 按 state 渲染并回 warn；旧值 {@code display}/{@code toggle} 是别名，静默按 state；</li>
 *   <li>kind 与必填不匹配（progress 无有效进度、list 无 count、action 无有效 actions、
 *       非 state 缺 title）→ warn + 降级 state；</li>
 *   <li>降级后只保留 state 有意义的字段（progress=0、count=0、actions 清空）；</li>
 *   <li>state 允许只有 detail 的老帧（App 端把 detail 当标题，保持旧行为）。</li>
 * </ol>
 */
public final class SlotKind {

    public static final String STATE = "state";
    public static final String PROGRESS = "progress";
    public static final String LIST = "list";
    public static final String ACTION = "action";

    private static final String[] KNOWN = {STATE, PROGRESS, LIST, ACTION};

    private SlotKind() {
    }

    /** 归一化结果：最终 kind + 该 kind 真正有意义的字段 + 校验警告（不阻断）。 */
    public static final class Normalized {
        public final String kind;
        public final int progress;
        public final int count;
        public final List<SlotAction> actions;
        public final List<String> warnings;

        private Normalized(String kind, int progress, int count,
                           List<SlotAction> actions, List<String> warnings) {
            this.kind = kind;
            this.progress = progress;
            this.count = count;
            this.actions = actions == null
                    ? Collections.<SlotAction>emptyList() : actions;
            this.warnings = warnings == null
                    ? Collections.<String>emptyList() : Collections.unmodifiableList(warnings);
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
    }

    /** 是否是 4 种契约 kind 之一（不含旧别名）。 */
    public static boolean isKnown(String raw) {
        String k = trimLower(raw);
        for (String known : KNOWN) {
            if (known.equals(k)) return true;
        }
        return false;
    }

    /**
     * 任意历史/错误 kind → 契约 kind。
     *
     * <p>{@code display}/{@code toggle} 是 2026-11-14 U1 分类里的旧值，静默映射 state；
     * 其余未知值也回 state（调用方负责回 warn）。</p>
     */
    public static String canonical(String raw) {
        String k = trimLower(raw);
        if (STATE.equals(k) || PROGRESS.equals(k) || LIST.equals(k) || ACTION.equals(k)) return k;
        if ("display".equals(k) || "toggle".equals(k)) return STATE;
        return STATE;
    }

    /**
     * 从一条完整帧解析（App 侧唯一入口，插件侧 {@code normalizeSlotFields} 同规则）。
     *
     * <p>所有异常都降级，不抛：插槽是瞬态状态，坏一帧不能把甲板搞没。</p>
     */
    public static Normalized normalize(JSONObject data) {
        if (data == null) return normalize("", "", "", null, null, null);
        return normalize(
                data.optString("kind", ""),
                data.optString("title", ""),
                data.optString("detail", ""),
                number(data, "progress"),
                integer(data, "count"),
                data.optJSONArray("actions"));
    }

    /**
     * 核心归一化（包内可见，单测直接打表）。
     *
     * @param progress 缺失传 null（progress=0 是合法的 0%）
     * @param count    缺失传 null（list 要求 &gt;0）
     * @param actions  原始 actions 数组（可空）
     */
    static Normalized normalize(String rawKind, String title, String detail,
                                Double progress, Integer count, JSONArray actions) {
        List<String> warnings = new ArrayList<>();
        String raw = trimLower(rawKind);
        String kind;
        if (raw.isEmpty()) {
            kind = STATE;
        } else if (isKnown(raw)) {
            kind = raw;
        } else if ("display".equals(raw) || "toggle".equals(raw)) {
            kind = STATE;
        } else {
            warnings.add("未知 kind=" + raw + "，按 state 渲染");
            kind = STATE;
        }

        String t = title == null ? "" : title.trim();
        // 与插件 normalizeSlotFields 一致：非 action 的 actions 是"无意义字段"，直接忽略，
        // 连坏项警告都不产生（契约只定义每种 kind 有意义的字段）。
        List<SlotAction> safeActions = ACTION.equals(kind)
                ? SlotAction.parse(actions, warnings) : Collections.<SlotAction>emptyList();
        int progressValue = 0;
        int countValue = 0;

        if (PROGRESS.equals(kind)) {
            String missing = "";
            if (t.isEmpty()) missing = "title";
            if (progress == null || !isFinite(progress)) {
                missing = missing.isEmpty() ? "progress(0-100)" : missing + "/progress(0-100)";
            }
            if (!missing.isEmpty()) {
                warnings.add("kind=progress 缺必填 " + missing + "，降级 state");
                kind = STATE;
            } else {
                progressValue = (int) Math.max(0, Math.min(100, Math.round(progress)));
            }
        } else if (LIST.equals(kind)) {
            if (t.isEmpty()) {
                warnings.add("kind=list 缺必填 title，降级 state");
                kind = STATE;
            } else if (count == null || count <= 0) {
                warnings.add("kind=list 缺有效 count(>0)，降级 state");
                kind = STATE;
            } else {
                countValue = count;
            }
        } else if (ACTION.equals(kind)) {
            if (t.isEmpty()) {
                warnings.add("kind=action 缺必填 title，降级 state");
                kind = STATE;
            } else if (safeActions.isEmpty()) {
                warnings.add("kind=action 缺有效 actions(1-2 个 {id,label})，降级 state");
                kind = STATE;
            }
        }

        if (STATE.equals(kind)) {
            progressValue = 0;
            countValue = 0;
            safeActions = Collections.emptyList();
        }
        return new Normalized(kind, progressValue, countValue, safeActions, warnings);
    }

    /** 渲染时再兜一层：直接 new Slot(kind=action) 但没给 actions 时按 state 画，不出空按钮。 */
    public static String effectiveKind(String kind, int count, List<SlotAction> actions) {
        String k = canonical(kind);
        if (ACTION.equals(k) && (actions == null || actions.isEmpty())) return STATE;
        if (LIST.equals(k) && count <= 0) return STATE;
        return k;
    }

    /** 写回帧（App 诊断/未来回传用；字段口径与插件快照一致）。 */
    public static JSONObject toJson(String kind, int progress, int count, List<SlotAction> actions) {
        JSONObject o = new JSONObject();
        try {
            o.put("kind", canonical(kind));
            o.put("progress", Math.max(0, Math.min(100, progress)));
            o.put("count", Math.max(0, count));
            o.put("actions", SlotAction.toJsonArray(actions));
        } catch (Exception ignored) {
            // 同上：瞬态数据，序列化失败不扩散
        }
        return o;
    }

    private static boolean isFinite(Double value) {
        return value != null && !value.isNaN() && !value.isInfinite();
    }

    private static Double number(JSONObject data, String key) {
        if (data == null || !data.has(key) || data.isNull(key)) return null;
        Object raw = data.opt(key);
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        if (raw instanceof String) {
            try {
                return Double.valueOf(((String) raw).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer integer(JSONObject data, String key) {
        Double d = number(data, key);
        if (d == null || d.isNaN() || d.isInfinite()) return null;
        return Integer.valueOf((int) Math.floor(d));
    }

    private static String trimLower(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
